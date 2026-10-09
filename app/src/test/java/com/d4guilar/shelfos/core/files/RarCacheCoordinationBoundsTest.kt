// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Phase 3E-E R1A: proves [RarCacheCoordinator]'s bounded coordination state --
 * (1) same-key lock metadata is a fixed stripe array, never a per-key map that grows with ephemeral-namespace
 * churn; (2) a vanished final stops counting toward bytes/entries the moment it is observed, even when
 * re-materialization then fails, while outstanding leases stay valid; (3) empty namespace directories are pruned
 * safely and never race an in-flight materialization.
 */
class RarCacheCoordinationBoundsTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("rar-bounds-test", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun namespaceDirs(base: File = root): List<File> = base.listFiles()?.filter { it.isDirectory } ?: emptyList()

    private fun write(vararg bytes: Byte): (File) -> NativeRarError? = { it.writeBytes(bytes); null }

    private fun expectRarError(block: () -> Unit): RarExtractionException = try {
        block()
        fail("expected RarExtractionException"); throw AssertionError()
    } catch (e: RarExtractionException) {
        e
    }

    /** Sizes of every instance-level Map/Collection/Array field: the coordinator's complete coordination state. */
    private fun instanceContainerSizes(c: RarCacheCoordinator): Map<String, Int> =
        RarCacheCoordinator::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                field.isAccessible = true
                val value: Any = field.get(c) ?: return@mapNotNull null
                val size = when (value) {
                    is Map<*, *> -> value.size
                    is Collection<*> -> value.size
                    is Array<*> -> value.size
                    else -> null
                }
                size?.let { field.name to it }
            }.toMap()

    // ---- Finding 1: bounded lock metadata -------------------------------------------------------------------

    @Test
    fun stripeMappingIsDeterministicAlwaysInRangeIncludingNegativeHashes() {
        val c = RarCacheCoordinator.createForTest(root)
        val indexes = listOf(Int.MIN_VALUE, -1, 0, 1, 7, Int.MAX_VALUE)
        repeat(500) { n ->
            val namespace = if (n % 2 == 0) UUID.randomUUID().toString() else "ns-$n"
            for (index in indexes) {
                val stripe = c.stripeIndexForTest(namespace, index)
                assertTrue("stripe $stripe out of range", stripe in 0 until RarCacheCoordinator.LOCK_STRIPE_COUNT)
                assertEquals("the same key must always map to the same stripe", stripe, c.stripeIndexForTest(namespace, index))
            }
        }
        assertEquals(RarCacheCoordinator.LOCK_STRIPE_COUNT, c.lockStripeCountForTest)
    }

    @Test
    fun sustainedEphemeralNamespaceChurnKeepsAllCoordinationMetadataBounded() {
        val maxEntries = 8
        val c = RarCacheCoordinator.createForTest(root, maxBytes = Long.MAX_VALUE, maxEntries = maxEntries)
        val bound = maxOf(RarCacheCoordinator.LOCK_STRIPE_COUNT, maxEntries)
        val namespaces = 400
        val ordinals = 4
        var peak = 0
        repeat(namespaces) { n ->
            val namespace = UUID.randomUUID().toString() // a fresh ephemeral namespace per "open"
            val held = c.acquire(namespace, 0, write(n.toByte()))
            for (index in 1 until ordinals) c.acquire(namespace, index, write(n.toByte(), index.toByte())).release()
            held.release() // "close"
            peak = maxOf(peak, instanceContainerSizes(c).values.maxOrNull() ?: 0)
        }
        val keysUsed = namespaces * ordinals
        assertTrue("$keysUsed distinct keys were used; any per-key map would exceed $bound", keysUsed > bound)
        assertTrue("no coordination container may grow with key history (peak=$peak)", peak <= bound)
        assertEquals("stripe count is fixed", RarCacheCoordinator.LOCK_STRIPE_COUNT, c.lockStripeCountForTest)
        assertEquals(0, c.inFlightNamespaceCountForTest())
        assertEquals(0, c.leaseOnlyCountForTest())
        assertTrue(c.entryCountForTest <= maxEntries)

        // Finding 3 at the same time: empty historical namespace directories do not accumulate.
        val dirs = namespaceDirs()
        assertEquals("only namespaces with live finals keep a directory", c.liveNamespacesForTest(), dirs.map { it.name }.toSet())
        assertTrue(dirs.size <= maxEntries)
        dirs.forEach { dir -> assertTrue("a live namespace dir keeps its finals", dir.listFiles()!!.any { it.name.endsWith(".bin") }) }
        val finals = root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(c.entryCountForTest, finals.size)
        assertEquals(c.usedBytesForTest, finals.sumOf { it.length() })
    }

    @Test
    fun ephemeralRarContainerChurnLeavesBoundedFinalsAndDirectories() {
        val cacheRoot = File(root, "app-cache")
        val entries = listOf(FakeRarArchiveSession.entry(0, "p1.jpg"), FakeRarArchiveSession.entry(1, "p2.jpg"))
        val content = mapOf(0 to byteArrayOf(1), 1 to byteArrayOf(2))
        repeat(120) {
            val container = RarContainer.open(FakeRarArchiveSession(entries, content), cacheRoot, sourceKey = null, maxCacheEntries = 4)
            container.extractPage(0).open().use { it.readBytes() }
            container.extractPage(1).open().use { it.readBytes() }
            container.close()
        }
        val cbr = File(cacheRoot, "cbr")
        val coordinator = RarCacheCoordinator.getInstance(cbr)
        assertTrue(coordinator.entryCountForTest <= 4)
        assertEquals(coordinator.liveNamespacesForTest(), namespaceDirs(cbr).map { it.name }.toSet())
        assertTrue("120 ephemeral opens must not leave 120 namespace directories", namespaceDirs(cbr).size <= 4)
        assertEquals(RarCacheCoordinator.LOCK_STRIPE_COUNT, coordinator.lockStripeCountForTest)
        assertEquals(0, coordinator.inFlightNamespaceCountForTest())
    }

    // ---- Finding 2: a vanished final stops counting immediately ------------------------------------------------

    @Test
    fun vanishedFinalStopsCountingImmediatelyWhenReMaterializationReportsAnError() {
        val c = RarCacheCoordinator.createForTest(root)
        val first = c.acquire("ns", 1, write(1, 2, 3, 4, 5)).also { it.release() }
        c.acquire("other", 1, write(9)).release()
        assertTrue(first.file.delete())

        val error = expectRarError { c.acquire("ns", 1) { dest -> dest.writeBytes(ByteArray(3)); NativeRarError.CORRUPT } }
        assertEquals(NativeRarError.CORRUPT, error.error)
        assertEquals("vanished bytes must no longer be accounted", 1L, c.usedBytesForTest)
        assertEquals("vanished final must no longer count as an entry", 1, c.entryCountForTest)
        assertFalse(c.containsForTest("ns", 1))
        assertFalse("final absent", first.file.exists())
        assertTrue("no temp file", root.walkTopDown().none { it.name.contains(".tmp-") })
        assertEquals(0, c.leaseOnlyCountForTest())
        assertEquals(0, c.inFlightNamespaceCountForTest())
        assertFalse("the empty namespace directory is pruned after the failure", File(root, "ns").exists())
    }

    @Test
    fun vanishedFinalStopsCountingImmediatelyWhenReMaterializationThrows() {
        val c = RarCacheCoordinator.createForTest(root)
        val first = c.acquire("ns", 1, write(1, 2, 3)).also { it.release() }
        assertTrue(first.file.delete())

        try {
            c.acquire("ns", 1) { dest -> dest.writeBytes(ByteArray(2)); throw IllegalStateException("synthetic") }
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0L, c.usedBytesForTest)
        assertEquals(0, c.entryCountForTest)
        assertFalse(first.file.exists())
        assertTrue(root.walkTopDown().none { it.name.contains(".tmp-") })
        assertEquals(0, c.inFlightNamespaceCountForTest())
    }

    @Test
    fun vanishedFinalStopsCountingImmediatelyWhenPublishRenameFails() {
        val c = RarCacheCoordinator.createForTest(root)
        val first = c.acquire("ns", 1, write(1, 2, 3)).also { it.release() }
        assertTrue(first.file.delete())
        // A non-empty directory now occupies the final path, so neither delete nor the atomic rename can succeed.
        File(first.file, "blocker").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }

        val error = expectRarError { c.acquire("ns", 1, write(4, 5)) }
        assertEquals(NativeRarError.IO, error.error)
        assertEquals(0L, c.usedBytesForTest)
        assertEquals(0, c.entryCountForTest)
        assertFalse("no final file was published", first.file.isFile)
        assertTrue(root.walkTopDown().none { it.name.contains(".tmp-") })
        assertTrue("a non-empty namespace directory is never pruned", File(root, "ns").isDirectory)

        first.file.deleteRecursively()
        val retry = c.acquire("ns", 1, write(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), retry.file.readBytes())
        assertEquals(2L, c.usedBytesForTest)
        retry.release()
    }

    @Test
    fun inheritedLeaseOnAVanishedFinalStaysValidWhileItsBytesStopCounting() {
        val attempted = mutableListOf<String>()
        val c = RarCacheCoordinator.createForTest(
            root,
            maxBytes = Long.MAX_VALUE,
            maxEntries = 1,
            deleteFile = { attempted += it.relativeTo(root).invariantSeparatorsPath; it.delete() },
        )
        val held = c.acquire("ns", 1, write(1, 2, 3, 4))
        assertTrue(held.file.delete())

        expectRarError { c.acquire("ns", 1) { NativeRarError.IO } }
        assertEquals("disk accounting excludes the missing final", 0L, c.usedBytesForTest)
        assertEquals(0, c.entryCountForTest)
        assertEquals("the outstanding lease survives as a lease-only holder", 1, c.activeReadersForTest("ns", 1))
        assertEquals(1, c.leaseOnlyCountForTest())
        assertTrue("a namespace with an outstanding lease is not pruned", File(root, "ns").isDirectory)

        // Eviction pressure never treats the nonexistent payload as removable bytes.
        c.acquire("a", 0, write(1)).release()
        c.acquire("b", 0, write(1)).release()
        assertFalse("eviction must never target a lease-only key", attempted.any { it.startsWith("ns/") })

        held.release()
        assertEquals(0, c.activeReadersForTest("ns", 1))
        assertEquals(0, c.leaseOnlyCountForTest())
        assertFalse("the last lease release prunes the now-idle namespace", File(root, "ns").exists())
        held.release() // idempotent: no underflow, no effect
        assertEquals(0, c.leaseOnlyCountForTest())

        val retry = c.acquire("ns", 1, write(7, 7))
        assertEquals(1, c.activeReadersForTest("ns", 1))
        assertArrayEquals(byteArrayOf(7, 7), retry.file.readBytes())
        retry.release()
        assertEquals(0, c.activeReadersForTest("ns", 1))
    }

    @Test
    fun laterSuccessfulRetryReaccountsExactlyOnceAndFoldsInheritedLeasesOnce() {
        val c = RarCacheCoordinator.createForTest(root)
        c.acquire("other", 0, write(1, 1, 1, 1, 1)).release()
        val held = c.acquire("ns", 1, write(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        assertEquals(15L, c.usedBytesForTest)
        assertTrue(held.file.delete())

        expectRarError { c.acquire("ns", 1) { NativeRarError.CORRUPT } }
        assertEquals(5L, c.usedBytesForTest)
        assertEquals(1, c.entryCountForTest)

        val retry = c.acquire("ns", 1, write(1, 2, 3, 4, 5, 6, 7))
        assertEquals("new bytes counted exactly once", 12L, c.usedBytesForTest)
        assertEquals(2, c.entryCountForTest)
        assertEquals("inherited lease + the retry's own lease", 2, c.activeReadersForTest("ns", 1))
        assertEquals(0, c.leaseOnlyCountForTest())
        assertEquals("exactly one final reappears", listOf("1.bin"), File(root, "ns").list()!!.toList())

        held.release()
        assertEquals(1, c.activeReadersForTest("ns", 1))
        retry.release()
        assertEquals(0, c.activeReadersForTest("ns", 1))
        assertEquals(12L, c.usedBytesForTest)
        val finals = root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(c.usedBytesForTest, finals.sumOf { it.length() })
    }

    // ---- Finding 3: empty namespace directory pruning --------------------------------------------------------

    @Test
    fun evictingANamespacesLastFinalPrunesItsDirectoryButKeepsLiveOnes() {
        val c = RarCacheCoordinator.createForTest(root, maxBytes = Long.MAX_VALUE, maxEntries = 1)
        c.acquire("ns-a", 1, write(1)).release()
        c.acquire("ns-b", 1, write(2)).release()
        assertFalse(File(root, "ns-a").exists())
        assertTrue(File(root, "ns-b").isDirectory)
        assertTrue(File(root, "ns-b/1.bin").isFile)
    }

    @Test
    fun aNamespaceWithAnInFlightMaterializationIsNeverPrunedByConcurrentEviction() {
        val c = RarCacheCoordinator.createForTest(root, maxBytes = Long.MAX_VALUE, maxEntries = 1)
        val x = "x-ns"
        c.acquire(x, 1, write(1)).release()
        // Pick a namespace whose key uses a different stripe, so the main thread never waits behind the worker.
        val y = (0 until 1000).map { "y-$it" }.first { c.stripeIndexForTest(it, 0) != c.stripeIndexForTest(x, 0) }

        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var result: CachedExtraction? = null
        var failure: Throwable? = null
        val worker = Thread {
            try {
                result = c.acquire(x, 0) { dest ->
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS))
                    dest.writeBytes(byteArrayOf(7)) // the temp file is created only AFTER the eviction below
                    null
                }
            } catch (t: Throwable) {
                failure = t
            }
        }
        worker.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        c.acquire(y, 0, write(2)).release() // evicts x/1, the namespace's last final, leaving its dir empty
        assertFalse(c.containsForTest(x, 1))
        assertTrue("an in-flight namespace's directory must survive pruning", File(root, x).isDirectory)
        assertEquals(1, c.inFlightNamespaceCountForTest())

        proceed.countDown()
        worker.join(5_000)
        assertNull(failure)
        val published = checkNotNull(result)
        assertArrayEquals(byteArrayOf(7), published.file.readBytes())
        assertTrue(c.containsForTest(x, 0))
        assertEquals(0, c.inFlightNamespaceCountForTest())
        published.release()
    }

    @Test
    fun lazyDiscoveryPrunesEmptyStaleDirsKeepsValidFinalsAndStaysInsideRoot() {
        File(root, "valid-ns").mkdirs(); File(root, "valid-ns/3.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "empty-ns").mkdirs()
        File(root, "temp-only-ns").mkdirs(); File(root, "temp-only-ns/5.tmp-1-1").writeBytes(byteArrayOf(1))
        File(root, "nested-ns/sub").mkdirs() // unexpected layout: a non-empty dir is never removed
        val outside = File(root.parentFile, "${root.name}-outside").apply { mkdirs() }
        val outsideFile = File(outside, "keep.bin").apply { writeBytes(byteArrayOf(9)) }
        val link = File(root, "link-ns")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        try {
            val c = RarCacheCoordinator.createForTest(root)
            c.acquire("probe", 0, write(1)).release()

            assertTrue(c.containsForTest("valid-ns", 3))
            assertTrue(File(root, "valid-ns/3.bin").isFile)
            assertFalse("empty stale namespace dir is pruned", File(root, "empty-ns").exists())
            assertFalse("a dir emptied by temp cleanup is pruned", File(root, "temp-only-ns").exists())
            assertTrue("unexpected non-empty layout is left conservatively", File(root, "nested-ns/sub").isDirectory)
            assertTrue("nothing outside the root is touched", outsideFile.isFile)
            if (linked) assertTrue("a symlinked namespace pointing outside root is never followed", outside.isDirectory)

            // A fresh "process" still discovers and reuses the valid final after pruning.
            var extractions = 0
            val reopened = RarCacheCoordinator.createForTest(root)
            val hit = reopened.acquire("valid-ns", 3) { extractions++; it.writeBytes(byteArrayOf(0)); null }
            assertEquals(0, extractions)
            assertArrayEquals(byteArrayOf(1, 2, 3), hit.file.readBytes())
            hit.release()
            assertEquals(3L + 1L, reopened.usedBytesForTest)
        } finally {
            if (linked) Files.deleteIfExists(link.toPath())
            outside.deleteRecursively()
        }
    }
}
