// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 3E-C R1A (HIGH-1 remediation): proves [RarCacheCoordinator]'s GLOBAL contract -- the one a per-instance
 * [RarExtractionCache] could never prove on its own: a single byte/entry budget spanning every source
 * namespace, lazy cross-reopen disk discovery and reuse, same-root multi-client coordination (one
 * materialization per key even under real concurrency), active-lease eviction protection across clients, and a
 * bound on aggregate ephemeral-namespace payload. See [RarExtractionCacheTest] for the per-namespace
 * atomicity/eviction/stale-cleanup contract this class delegates to from a single namespace's point of view.
 */
class RarCacheCoordinatorTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("rar-coordinator-test", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun globalByteLimitIsEnforcedAcrossNamespacesCombinedNotPerNamespace() {
        val coordinator = RarCacheCoordinator.createForTest(root, maxBytes = 20, maxEntries = 100)
        coordinator.acquire("ns-a", 1) { it.writeBytes(ByteArray(10)); null }.release()
        coordinator.acquire("ns-b", 1) { it.writeBytes(ByteArray(10)); null }.release()
        assertEquals("two different namespaces' 10-byte entries must count toward ONE 20-byte budget", 20L, coordinator.usedBytesForTest)

        // A third namespace's entry must evict across namespace boundaries to stay within the GLOBAL budget --
        // if each namespace had its own independent budget (the pre-remediation defect), this would never evict.
        coordinator.acquire("ns-c", 1) { it.writeBytes(ByteArray(10)); null }.release()
        assertTrue("global byte budget must never be exceeded across namespaces", coordinator.usedBytesForTest <= 20L)
        assertFalse(
            "the least-recently-used entry must be evicted regardless of which namespace it lives in",
            coordinator.containsForTest("ns-a", 1),
        )
        assertTrue(coordinator.containsForTest("ns-c", 1))
    }

    @Test
    fun globalEntryLimitIsEnforcedAcrossNamespacesCombinedNotPerNamespace() {
        val coordinator = RarCacheCoordinator.createForTest(root, maxBytes = Long.MAX_VALUE, maxEntries = 2)
        coordinator.acquire("ns-a", 1) { it.writeBytes(byteArrayOf(1)); null }.release()
        coordinator.acquire("ns-b", 1) { it.writeBytes(byteArrayOf(2)); null }.release()
        assertEquals(2, coordinator.entryCountForTest)

        coordinator.acquire("ns-c", 1) { it.writeBytes(byteArrayOf(3)); null }.release()
        assertEquals(
            "a 2-entry GLOBAL cap must still hold after a THIRD namespace's entry is added",
            2,
            coordinator.entryCountForTest,
        )
        assertFalse(coordinator.containsForTest("ns-a", 1))
        assertTrue(coordinator.containsForTest("ns-b", 1))
        assertTrue(coordinator.containsForTest("ns-c", 1))
    }

    @Test
    fun reopenedCoordinatorLazilyDiscoversAPriorFinalAndIncludesItInAccounting() {
        val first = RarCacheCoordinator.createForTest(root)
        first.acquire("stable-ns", 4) { it.writeBytes(byteArrayOf(9, 9, 9)); null }.release()
        assertEquals(1, first.entryCountForTest)

        // A brand-new coordinator instance over the SAME on-disk root, sharing NO in-memory state with `first`
        // -- simulates a real process restart (see createForTest's doc).
        val reopened = RarCacheCoordinator.createForTest(root)
        assertEquals("discovery is lazy -- must not have run yet merely from construction", 0, reopened.entryCountForTest)

        val extraction = reopened.acquire("stable-ns", 4) { it.writeBytes(byteArrayOf(0)); null }
        assertTrue(
            "a reopened coordinator must discover and fold in the prior final before acquire() runs",
            reopened.containsForTest("stable-ns", 4),
        )
        assertArrayEquals("the DISCOVERED final's real bytes must be served, never freshly re-extracted ones", byteArrayOf(9, 9, 9), extraction.file.readBytes())
        extraction.release()
    }

    @Test
    fun aDiscoveredPriorFinalIsActuallyReusedOnRepeatRequestExtractionCountUnchanged() {
        var extractionCount = 0
        val extract: (File) -> NativeRarError? = { dest -> extractionCount++; dest.writeBytes(byteArrayOf(1, 2, 3)); null }

        val first = RarCacheCoordinator.createForTest(root)
        first.acquire("reuse-ns", 2, extract).release()
        assertEquals(1, extractionCount)

        val reopened = RarCacheCoordinator.createForTest(root)
        val extraction = reopened.acquire("reuse-ns", 2, extract)
        assertEquals(
            "reopening the same source/namespace and requesting the SAME physical entry must never re-extract",
            1,
            extractionCount,
        )
        extraction.release()
    }

    @Test
    fun ephemeralRandomNamespacePayloadCannotAccumulateUnbounded() {
        val coordinator = RarCacheCoordinator.createForTest(root, maxBytes = 50, maxEntries = 1000)
        // Simulates many RarPageSource.open() calls with no stable sourceKey (see RarPageSource.open's doc): a
        // fresh random namespace every time, as ShelfOS's own fallback policy produces.
        repeat(20) {
            val namespace = UUID.randomUUID().toString()
            coordinator.acquire(namespace, 0) { it.writeBytes(ByteArray(10)); null }.release()
        }
        assertTrue(
            "20 * 10-byte ephemeral-namespace entries (200B) must still be bounded by the 50B global budget",
            coordinator.usedBytesForTest <= 50L,
        )
        val totalOnDisk = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("the actual on-disk footprint, not only in-memory accounting, must stay bounded too", totalOnDisk <= 50L)
    }

    @Test
    fun getInstanceReturnsTheSameSharedCoordinatorForTheSameRootWithinThisProcess() {
        val a = RarCacheCoordinator.getInstance(root)
        val b = RarCacheCoordinator.getInstance(root)
        assertSame("two callers targeting the same canonical root must share ONE coordinator instance", a, b)
    }

    @Test
    fun twoClientInstancesTargetingTheSameRootNamespaceAndOrdinalCoordinateIntoOneMaterializationNoDuplicateExtraction() {
        // Two independently constructed RarExtractionCache "clients" sharing the coordinator obtained via the
        // real production getInstance() path for the same root -- exactly how two separately opened
        // RarPageSource instances over the same source would behave.
        val cacheA = RarExtractionCache(RarCacheCoordinator.getInstance(root), "shared-ns")
        val cacheB = RarExtractionCache(RarCacheCoordinator.getInstance(root), "shared-ns")

        val extractionsStarted = java.util.concurrent.atomic.AtomicInteger(0)
        val firstExtractionEntered = CountDownLatch(1)
        val releaseFirstExtraction = CountDownLatch(1)
        val extract: (File) -> NativeRarError? = { dest ->
            extractionsStarted.incrementAndGet()
            firstExtractionEntered.countDown()
            releaseFirstExtraction.await(5, TimeUnit.SECONDS)
            dest.writeBytes(byteArrayOf(42))
            null
        }

        var resultA: CachedExtraction? = null
        var resultB: CachedExtraction? = null
        val threadA = Thread { resultA = cacheA.acquire(7, extract) }
        threadA.start()
        // Deterministic: wait until A is ACTUALLY inside extract() (and therefore holding this key's lock)
        // before starting B -- never a fixed sleep.
        assertTrue(firstExtractionEntered.await(5, TimeUnit.SECONDS))

        val threadB = Thread { resultB = cacheB.acquire(7, extract) }
        threadB.start()
        // B is now guaranteed to be blocked behind A's held per-key lock (A has not released it yet).
        releaseFirstExtraction.countDown()
        threadA.join(5_000)
        threadB.join(5_000)

        assertEquals(
            "the SAME (namespace, physicalIndex) key must be materialized exactly once even under real concurrency",
            1,
            extractionsStarted.get(),
        )
        assertEquals("both clients must observe the SAME final file", resultA!!.file, resultB!!.file)
        resultA?.release()
        resultB?.release()
    }

    @Test
    fun anActiveLeaseInOneClientProtectsItsEntryFromEvictionTriggeredByAnotherClientsCachePressure() {
        // maxEntries=2: A's entry is the OLDEST by access order (would normally be evicted first under plain
        // LRU), but it is held (an active lease, never released) by client A for this entire test. B's entry is
        // released immediately -- newer than A's, but INACTIVE. When client C's insert pushes the coordinator
        // over its 2-entry global budget, the active lease must force B's (newer but inactive) entry to be the
        // victim instead of A's (older but active) one -- proving this is lease-aware, not plain recency-LRU.
        val coordinator = RarCacheCoordinator.createForTest(root, maxBytes = Long.MAX_VALUE, maxEntries = 2)
        val cacheA = RarExtractionCache(coordinator, "client-a")
        val cacheB = RarExtractionCache(coordinator, "client-b")
        val cacheC = RarExtractionCache(coordinator, "client-c")

        val held = cacheA.acquire(1) { it.writeBytes(byteArrayOf(1)); null } // never released -- an active lease
        cacheB.acquire(1) { it.writeBytes(byteArrayOf(2)); null }.release() // inactive once released
        assertEquals(2, coordinator.entryCountForTest) // within budget so far, no eviction yet

        cacheC.acquire(1) { it.writeBytes(byteArrayOf(3)); null }.release() // pushes to 3 entries > maxEntries(2)

        assertTrue(
            "an active lease from one client must survive eviction pressure from a DIFFERENT client sharing the coordinator",
            cacheA.containsForTest(1),
        )
        assertFalse(
            "the newer-but-inactive entry must be evicted instead of the older-but-actively-leased one",
            cacheB.containsForTest(1),
        )
        assertTrue(cacheC.containsForTest(1))
        held.release()
    }

    @Test
    fun releasingLastLeaseRestoresGlobalEntryBoundWithoutAnotherCacheAccess() {
        val coordinator = RarCacheCoordinator.createForTest(root, maxBytes = Long.MAX_VALUE, maxEntries = 1)
        val first = coordinator.acquire("ns", 1) { it.writeBytes(byteArrayOf(1)); null }
        val second = coordinator.acquire("ns", 2) { it.writeBytes(byteArrayOf(2)); null }

        assertEquals("both active entries may temporarily defer eviction", 2, coordinator.entryCountForTest)
        first.release()

        assertEquals("release must restore the bound immediately", 1, coordinator.entryCountForTest)
        assertFalse(coordinator.containsForTest("ns", 1))
        assertTrue(coordinator.containsForTest("ns", 2))
        second.release()
    }

    @Test
    fun failedDeletionStaysAccountedWhileAnotherEligibleCandidateCanBeEvicted() {
        val attempted = mutableListOf<String>()
        val coordinator = RarCacheCoordinator.createForTest(
            root,
            maxBytes = Long.MAX_VALUE,
            maxEntries = 2,
            deleteFile = { file ->
                attempted += file.name
                if (file.name == "1.bin") false else file.delete()
            },
        )
        coordinator.acquire("ns", 1) { it.writeBytes(ByteArray(4)); null }.release()
        coordinator.acquire("ns", 2) { it.writeBytes(ByteArray(5)); null }.release()

        val newest = coordinator.acquire("ns", 3) { it.writeBytes(ByteArray(6)); null }

        assertEquals(listOf("1.bin", "2.bin"), attempted)
        assertTrue("the undeletable final must remain accounted", coordinator.containsForTest("ns", 1))
        assertFalse("eviction must continue to another eligible candidate", coordinator.containsForTest("ns", 2))
        assertTrue(coordinator.containsForTest("ns", 3))
        assertEquals(2, coordinator.entryCountForTest)
        assertEquals("failed deletion must not falsely reclaim bytes", 10L, coordinator.usedBytesForTest)
        newest.release()
    }
}
