// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationExceptionDetail
import com.d4guilar.shelfos.domain.library.PublicationProblem
import com.d4guilar.shelfos.domain.library.publicationProblem
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Phase 3E-E CBR acceptance hardening: pure-JVM proofs of [RarContainer]/[RarCacheCoordinator] behavior under hostile
 * or edge conditions that the accepted 3E-C/3E-D suites ([RarPageSourceTest], [RarCacheCoordinatorTest],
 * [RarExtractionCacheTest], [RarExtractionCacheCeilingTest]) did not already cover. Fakes prove ShelfOS's own policy
 * only; real libarchive parsing is proven by the instrumented native suites.
 */
class RarAcceptanceHardeningTest {
    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        cacheRoot = File.createTempFile("rar-acceptance-test", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        cacheRoot.deleteRecursively()
    }

    private val coordinatorRoot get() = File(cacheRoot, "cbr")

    /** The same process-wide coordinator [RarContainer.open] resolves for [cacheRoot] (unique per test). */
    private val coordinator get() = RarCacheCoordinator.getInstance(coordinatorRoot)

    private fun entry(index: Int, name: String, type: NativeRarEntryType = NativeRarEntryType.REGULAR_FILE, size: Long? = null) =
        FakeRarArchiveSession.entry(index, name, type, size)

    private fun open(session: RarArchiveSession, sourceKey: String? = "acceptance", maxEntries: Int = 64, maxBytes: Long = Long.MAX_VALUE) =
        RarContainer.open(session, cacheRoot, sourceKey, maxCacheBytes = maxBytes, maxCacheEntries = maxEntries)

    private fun RarContainer.read(index: Int): ByteArray = extractPage(index).open().use { it.readBytes() }

    private fun expectProblem(block: () -> Unit): PublicationException = try {
        block()
        fail("expected PublicationException"); throw AssertionError()
    } catch (e: PublicationException) {
        e
    }

    /** Every file physically present under the cache root, relative to it, with '/' separators. */
    private fun cacheFiles(): List<String> = coordinatorRoot.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(coordinatorRoot).invariantSeparatorsPath }.toList()

    /** Counts closes so double-close / close-on-failure can be asserted exactly. */
    private class CountingSession(
        private val delegate: RarArchiveSession,
        private val onEntryCount: (() -> Unit)? = null,
    ) : RarArchiveSession {
        val closes = AtomicInteger(0)
        override val entryCount: Int get() { onEntryCount?.invoke(); return delegate.entryCount }
        override fun entryAt(index: Int) = delegate.entryAt(index)
        override fun extractEntry(index: Int, destination: File, maxBytes: Long) = delegate.extractEntry(index, destination, maxBytes)
        override fun close() { closes.incrementAndGet(); delegate.close() }
    }

    // ---- Hostile entry metadata --------------------------------------------------------------------------------

    @Test
    fun everyTraversalOrAbsoluteEntryNameIsRejectedBeforeAnyExtraction() {
        val hostile = listOf(
            "/etc/passwd.jpg", "/abs/page1.png", "C:\\pages\\page1.jpg", "C:/pages/page1.jpg", "D:page1.jpg",
            "..\\escape.jpg", "pages/../../escape.jpg", "a/b/../../../escape.jpg", "\\\\server\\share\\p.jpg", "..",
        )
        for (name in hostile) {
            val fake = FakeRarArchiveSession(
                listOf(entry(0, "page1.jpg"), entry(1, name)),
                content = mapOf(0 to byteArrayOf(1), 1 to byteArrayOf(2)),
            )
            val session = CountingSession(fake)
            val error = expectProblem { open(session) }
            assertEquals(name, PublicationProblem.CORRUPT, error.problem)
            assertEquals(name, PublicationExceptionDetail.UNSAFE_ENTRY_PATH, error.detail)
            assertEquals("no extraction may be attempted for $name", 0, fake.extractionCount)
            assertEquals("a rejected open must close its session exactly once ($name)", 1, session.closes.get())
        }
        assertTrue("no cache payload may ever be produced for a rejected archive", cacheFiles().isEmpty())
    }

    @Test
    fun unicodeAndOddButSafeNamesBecomePagesAndOnlyOrdinalNamedFilesReachDisk() {
        val names = listOf(
            "ページ２.jpg", "صفحة1.png", "\uD83D\uDCD6 emoji page.webp", "e\u0301clair.jpeg", "zero\u200Bwidth.PNG",
            "deep/nested/folder/page.jpg", "   spaced   .jpg", "CON.jpg", "name with %2e%2e.jpg",
        )
        val entries = names.mapIndexed { i, name -> entry(i, name) }
        val content = names.indices.associateWith { byteArrayOf(it.toByte(), 0x5A) }
        val fake = FakeRarArchiveSession(entries, content)
        val container = open(fake)
        assertEquals(names.size, container.pageCount)
        val seen = (0 until container.pageCount).map { container.read(it)[0].toInt() }.toSet()
        assertEquals("every logical page must map to a distinct physical entry", names.indices.toSet(), seen)
        container.close()

        val files = cacheFiles()
        assertEquals(names.size, files.size)
        val layout = Regex("""[0-9a-f-]{36}/\d+\.bin""")
        files.forEach { assertTrue("unexpected cache path $it (archive names must never reach the filesystem)", layout.matches(it)) }
    }

    @Test
    fun emptyDirectoryOtherAndNonImageEntriesAreFilteredWithoutCrashing() {
        val entries = listOf(
            entry(0, ""), // empty decoded name
            entry(1, "folder.jpg", type = NativeRarEntryType.DIRECTORY),
            entry(2, "link.jpg", type = NativeRarEntryType.OTHER), // symlink/special entry named like an image
            entry(3, "notes.txt"),
            entry(4, "trailing/"),
            entry(5, ".hidden.jpg"),
            entry(6, "__MACOSX/page1.jpg"),
            entry(7, "real.png"),
        )
        val fake = FakeRarArchiveSession(entries, mapOf(7 to byteArrayOf(7)))
        val container = open(fake)
        assertEquals("only the one real regular image entry may become a page", 1, container.pageCount)
        assertEquals(7, container.read(0)[0].toInt())
        assertEquals(listOf(7), fake.extractedIndices)
        container.close()
    }

    @Test
    fun noImagePagesAtAllFailsEmptyArchiveAndClosesTheSession() {
        val fake = FakeRarArchiveSession(listOf(entry(0, "ComicInfo.xml"), entry(1, "dir", NativeRarEntryType.DIRECTORY)))
        val session = CountingSession(fake)
        assertEquals(PublicationProblem.EMPTY_ARCHIVE, expectProblem { open(session) }.problem)
        assertEquals(1, session.closes.get())
        assertEquals(0, fake.extractionCount)
    }

    @Test
    fun duplicateNamesSortDeterministicallyByPhysicalOrdinalAndNeverCollideOnDisk() {
        val entries = listOf(entry(0, "p.jpg"), entry(1, "other/p.jpg"), entry(2, "p.jpg"), entry(3, "p.jpg"))
        val content = mapOf(0 to byteArrayOf(10), 1 to byteArrayOf(11), 2 to byteArrayOf(12), 3 to byteArrayOf(13))
        repeat(2) { attempt ->
            val container = open(FakeRarArchiveSession(entries, content), sourceKey = "dup-$attempt")
            val order = (0 until container.pageCount).map { container.read(it)[0].toInt() }
            assertEquals("identical names must keep physical order on every open", listOf(11, 10, 12, 13), order)
            container.close()
        }
        assertEquals("each physical entry keeps its own cache file", 8, cacheFiles().size)
    }

    @Test
    fun duplicateComicInfoUsesTheFirstRootEntryOnlyAndIsNeverAPage() {
        val first = "<ComicInfo><Title>First</Title></ComicInfo>".toByteArray()
        val nested = "<ComicInfo><Title>Nested</Title></ComicInfo>".toByteArray()
        val second = "<ComicInfo><Title>Second</Title></ComicInfo>".toByteArray()
        val entries = listOf(entry(0, "sub/ComicInfo.xml"), entry(1, "comicinfo.XML"), entry(2, "page1.jpg"), entry(3, "ComicInfo.xml"))
        val fake = FakeRarArchiveSession(entries, mapOf(0 to nested, 1 to first, 2 to byteArrayOf(1), 3 to second))
        val container = open(fake)
        assertEquals(1, container.pageCount)
        assertEquals("First", container.comicInfo()?.title)
        container.close()
    }

    @Test
    fun malformedOversizedOrFailingComicInfoNeverBlocksTheContainer() {
        val cases = listOf(
            FakeRarArchiveSession(listOf(entry(0, "ComicInfo.xml"), entry(1, "p.jpg")), mapOf(0 to "<ComicInfo><Title>".toByteArray(), 1 to byteArrayOf(1))),
            FakeRarArchiveSession(listOf(entry(0, "ComicInfo.xml"), entry(1, "p.jpg")), mapOf(0 to ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() }, 1 to byteArrayOf(1))),
            FakeRarArchiveSession(listOf(entry(0, "ComicInfo.xml"), entry(1, "p.jpg")), mapOf(1 to byteArrayOf(1)), failures = mapOf(0 to NativeRarError.CORRUPT)),
        )
        cases.forEachIndexed { i, fake ->
            val container = open(fake, sourceKey = "comicinfo-$i")
            assertNull("unusable ComicInfo yields no title ($i)", container.comicInfo()?.title)
            assertEquals("pages stay readable after ComicInfo failure $i", 1, container.read(0)[0].toInt())
            container.close()
        }
        assertFalse("no temp file may survive a failed ComicInfo materialization", cacheFiles().any { it.contains(".tmp-") })
    }

    // ---- MAX_ENTRIES boundary (lazy metadata: nothing is materialized up front) --------------------------------

    /** Generates entry metadata on demand: one image at index 0, everything else a tiny non-image file. */
    private class LazySession(override val entryCount: Int) : RarArchiveSession {
        val entryAtCalls = AtomicInteger(0)
        var closed = false
        override fun entryAt(index: Int): NativeRarEntry? {
            entryAtCalls.incrementAndGet()
            if (index !in 0 until entryCount) return null
            return NativeRarEntry(index, if (index == 0) "page.jpg" else "f$index.txt", true, NativeRarEntryType.REGULAR_FILE, 1)
        }
        override fun extractEntry(index: Int, destination: File, maxBytes: Long): NativeRarError? {
            destination.writeBytes(byteArrayOf(index.toByte())); return null
        }
        override fun close() { closed = true }
    }

    @Test
    fun exactlyMaxEntriesIsAcceptedAndOneOverFailsBeforeEnumeration() {
        val atLimit = LazySession(ArchivePolicy.MAX_ENTRIES)
        val container = open(atLimit)
        assertEquals(1, container.pageCount)
        container.close()

        val overLimit = LazySession(ArchivePolicy.MAX_ENTRIES + 1)
        val error = expectProblem { open(overLimit) }
        assertEquals(PublicationProblem.TOO_LARGE, error.problem)
        assertEquals(PublicationExceptionDetail.TOO_MANY_ENTRIES, error.detail)
        assertEquals("an over-limit archive must be rejected before any entry is enumerated", 0, overLimit.entryAtCalls.get())
        assertTrue(overLimit.closed)
    }

    // ---- Oversized images ----------------------------------------------------------------------------------------

    @Test
    fun declaredSizeExactlyAtTheImageLimitIsAcceptedWhileUnknownSizeDefersToTheExtractionCeiling() {
        val fake = FakeRarArchiveSession(
            listOf(entry(0, "a.jpg", size = ArchivePolicy.MAX_IMAGE_BYTES), entry(1, "b.jpg", size = null)),
            mapOf(0 to byteArrayOf(1), 1 to byteArrayOf(2)),
        )
        val container = open(fake)
        assertEquals(2, container.pageCount)
        container.close()
    }

    /** Writes a partial payload and reports the native streamed ceiling abort, recording the ceiling it was given. */
    private class DishonestSizeSession : RarArchiveSession {
        val ceilings = mutableListOf<Long>()
        override val entryCount = 2
        override fun entryAt(index: Int) = when (index) {
            0 -> NativeRarEntry(0, "liar.jpg", true, NativeRarEntryType.REGULAR_FILE, size = 10) // declares 10 bytes
            1 -> NativeRarEntry(1, "safe.jpg", true, NativeRarEntryType.REGULAR_FILE, size = 1)
            else -> null
        }
        override fun extractEntry(index: Int, destination: File, maxBytes: Long): NativeRarError? {
            ceilings += maxBytes
            return if (index == 0) {
                destination.writeBytes(ByteArray(4096)); NativeRarError.TOO_LARGE
            } else {
                destination.writeBytes(byteArrayOf(42)); null
            }
        }
        override fun close() {}
    }

    @Test
    fun dishonestlySizedEntryAbortedByTheCeilingLeavesNoFinalNoTempNoAccountingAndSiblingsStillWork() {
        val session = DishonestSizeSession()
        val container = open(session)
        val usedBefore = coordinator.usedBytesForTest
        val error = expectProblem { container.extractPage(0) }
        assertEquals(PublicationProblem.TOO_LARGE, error.problem)
        assertEquals(NativeRarError.TOO_LARGE, (error.cause as RarExtractionException).error)
        assertEquals("the hard per-entry ceiling must be passed to the engine", listOf(RarExtractionCache.MAX_ENTRY_BYTES), session.ceilings)
        assertTrue("no final or temp may remain after a ceiling abort", cacheFiles().isEmpty())
        assertEquals(usedBefore, coordinator.usedBytesForTest)
        assertEquals(0, coordinator.entryCountForTest)

        assertEquals(42, container.read(1)[0].toInt())
        assertEquals(1L, coordinator.usedBytesForTest)
        container.close()
    }

    // ---- Error boundary --------------------------------------------------------------------------------------

    @Test
    fun securityExceptionWhileIndexingClosesTheSessionAndMapsToPermissionLost() {
        val denied = SecurityException("synthetic revoked grant")
        val session = CountingSession(FakeRarArchiveSession(listOf(entry(0, "p.jpg")))) { throw denied }
        val thrown = try { open(session); null } catch (t: Throwable) { t }
        assertSame(denied, thrown)
        assertEquals(PublicationProblem.PERMISSION_LOST, thrown!!.publicationProblem())
        assertEquals("a failed open must release its native session exactly once", 1, session.closes.get())
        assertTrue("a failed open must never create cache payload", cacheFiles().isEmpty())
    }

    @Test
    fun cancellationWhileIndexingIsRethrownUnchangedAndClosesTheSession() {
        val cancellation = CancellationException("synthetic")
        val session = CountingSession(FakeRarArchiveSession(listOf(entry(0, "p.jpg")))) { throw cancellation }
        val thrown = try { open(session); null } catch (t: Throwable) { t }
        assertSame(cancellation, thrown)
        assertEquals(1, session.closes.get())
    }

    @Test
    fun securityExceptionDuringExtractionMapsToPermissionLostLeavesNoFinalAndRetrySucceeds() {
        val denied = SecurityException("synthetic")
        val entries = listOf(entry(0, "p.jpg"))
        val failing = open(FakeRarArchiveSession(entries, throwables = mapOf(0 to denied)), sourceKey = "managed:perm")
        val error = expectProblem { failing.extractPage(0) }
        assertEquals(PublicationProblem.PERMISSION_LOST, error.problem)
        assertSame(denied, error.cause)
        assertTrue(cacheFiles().isEmpty())
        failing.close()

        val retry = open(FakeRarArchiveSession(entries, mapOf(0 to byteArrayOf(5))), sourceKey = "managed:perm")
        assertEquals(5, retry.read(0)[0].toInt())
        retry.close()
    }

    @Test
    fun interruptedExtractionThatThrowsAfterAPartialWriteRemovesTheTempAndChangesNoAccounting() {
        val entries = listOf(entry(0, "p.jpg"))
        val session = object : RarArchiveSession {
            override val entryCount = 1
            override fun entryAt(index: Int) = entries.getOrNull(index)
            override fun extractEntry(index: Int, destination: File, maxBytes: Long): NativeRarError? {
                destination.writeBytes(ByteArray(64))
                throw IllegalStateException("synthetic crash mid-extraction")
            }
            override fun close() {}
        }
        val container = open(session)
        try { container.extractPage(0); fail("expected failure") } catch (_: IllegalStateException) { }
        assertTrue("partial temp output must be removed", cacheFiles().isEmpty())
        assertEquals(0L, coordinator.usedBytesForTest)
        assertEquals(0, coordinator.entryCountForTest)
        container.close()
    }

    // ---- Cache-directory failures ---------------------------------------------------------------------------

    @Test
    fun unusableCacheRootFailsTypedAsUnreadableWithoutCrashingOrPublishing() {
        // The coordinator root path is occupied by a regular file, so neither the root, a namespace directory nor
        // a temp file can be created.
        coordinatorRoot.writeBytes(byteArrayOf(1))
        val fake = FakeRarArchiveSession(listOf(entry(0, "p.jpg")), mapOf(0 to byteArrayOf(1)))
        val container = open(fake)
        val error = expectProblem { container.extractPage(0) }
        assertEquals(PublicationProblem.UNREADABLE, error.problem)
        assertTrue(error.cause is IOException)
        assertTrue("the occupying file is untouched", coordinatorRoot.isFile)
        container.close()
    }

    @Test
    fun publishRenameFailureIsTypedIoRemovesTheTempAndRetrySucceedsOnceUnblocked() {
        val fake = FakeRarArchiveSession(listOf(entry(0, "p.jpg")), mapOf(0 to byteArrayOf(9)))
        val container = open(fake, sourceKey = "managed:rename")
        // Materialize once, then replace the final with a non-empty directory so the atomic rename must fail.
        container.extractPage(0).release()
        val finalFile = coordinatorRoot.walkTopDown().first { it.isFile && it.name == "0.bin" }
        assertTrue(finalFile.delete())
        File(finalFile, "blocker").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }

        val error = expectProblem { container.extractPage(0) }
        assertEquals(PublicationProblem.UNREADABLE, error.problem)
        assertEquals(NativeRarError.IO, (error.cause as RarExtractionException).error)
        assertFalse("no temp may remain after a failed publish", cacheFiles().any { it.contains(".tmp-") })

        finalFile.deleteRecursively()
        assertEquals(9, container.read(0)[0].toInt())
        container.close()
    }

    // ---- Missing / vanished finals (Phase 3E-E fix) -----------------------------------------------------------

    @Test
    fun aFinalThatVanishedFromDiskIsReMaterializedInsteadOfReturningAMissingFile() {
        val fake = FakeRarArchiveSession(listOf(entry(0, "p.jpg")), mapOf(0 to byteArrayOf(1, 2, 3)))
        val container = open(fake, sourceKey = "managed:vanish")
        assertArrayEquals(byteArrayOf(1, 2, 3), container.read(0))
        assertEquals(1, fake.extractionCount)

        // Simulates Android/the user clearing the app cache while the reader is open.
        coordinatorRoot.deleteRecursively()

        assertArrayEquals("a vanished final must be re-materialized, never surfaced as a missing file", byteArrayOf(1, 2, 3), container.read(0))
        assertEquals(2, fake.extractionCount)
        assertEquals("replacement must not double-count the vanished slot", 3L, coordinator.usedBytesForTest)
        assertEquals(1, coordinator.entryCountForTest)
        assertEquals(0, coordinator.activeReadersForTest(UUIDNamespace.of("managed:vanish"), 0))
        // And it is a normal cache hit again afterward.
        container.read(0)
        assertEquals(2, fake.extractionCount)
        container.close()
    }

    @Test
    fun reMaterializingAVanishedFinalPreservesOutstandingLeases() {
        val c = RarCacheCoordinator.createForTest(File(cacheRoot, "direct"))
        val held = c.acquire("ns", 1) { it.writeBytes(byteArrayOf(1)); null }
        assertTrue(held.file.delete())
        val again = c.acquire("ns", 1) { it.writeBytes(byteArrayOf(2)); null }
        assertEquals(2, c.activeReadersForTest("ns", 1))
        held.release()
        assertEquals(1, c.activeReadersForTest("ns", 1))
        again.release()
        assertEquals(0, c.activeReadersForTest("ns", 1))
        assertEquals(1L, c.usedBytesForTest)
    }

    @Test
    fun evictionReclaimsAccountingForAFinalThatAlreadyVanished() {
        val c = RarCacheCoordinator.createForTest(File(cacheRoot, "direct"), maxBytes = Long.MAX_VALUE, maxEntries = 1)
        val first = c.acquire("ns", 1) { it.writeBytes(ByteArray(7)); null }
        first.release()
        assertTrue(first.file.delete())
        c.acquire("ns", 2) { it.writeBytes(ByteArray(3)); null }.release()
        assertFalse("a vanished final must not stay pinned as an undeletable entry", c.containsForTest("ns", 1))
        assertEquals(1, c.entryCountForTest)
        assertEquals(3L, c.usedBytesForTest)
    }

    // ---- Global pressure: physical disk payload matches accounting --------------------------------------------

    @Test
    fun physicalFinalPayloadOnDiskMatchesAccountingAfterSustainedCrossNamespacePressure() {
        val c = RarCacheCoordinator.createForTest(File(cacheRoot, "pressure"), maxBytes = 100, maxEntries = 5)
        val pinned = c.acquire("pinned", 0) { it.writeBytes(ByteArray(20)); null } // active the whole time
        for (round in 0 until 6) {
            for (ns in listOf("ns-a", "ns-b", "ephemeral-$round")) {
                for (index in 0 until 3) {
                    c.acquire(ns, index) { it.writeBytes(ByteArray(7 + index * 5 + round)); null }.release()
                }
            }
        }
        val root = File(cacheRoot, "pressure")
        val finals = root.walkTopDown().filter { it.isFile }.toList()
        assertTrue("no temp files after successful runs", finals.none { it.name.contains(".tmp-") })
        assertEquals("physical final count == accounted entry count", c.entryCountForTest, finals.size)
        assertEquals("physical bytes on disk == accounted bytes", c.usedBytesForTest, finals.sumOf { it.length() })
        assertTrue(c.usedBytesForTest <= 100)
        assertTrue(c.entryCountForTest <= 5)
        assertTrue("the active lease survived all pressure", c.containsForTest("pinned", 0))
        pinned.release()

        // A fresh "process" discovering the same root reaches the same physical == accounted state.
        val rediscovered = RarCacheCoordinator.createForTest(root, maxBytes = 100, maxEntries = 5)
        rediscovered.acquire("probe", 0) { it.writeBytes(byteArrayOf(1)); null }.release()
        val after = root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(rediscovered.entryCountForTest, after.size)
        assertEquals(rediscovered.usedBytesForTest, after.sumOf { it.length() })
    }

    // ---- Reopen / lifecycle / access patterns ------------------------------------------------------------------

    @Test
    fun managedStableKeyReopenReusesTheExistingFinalWithNoSecondExtraction() {
        val entries = listOf(entry(0, "p1.jpg"), entry(1, "p2.jpg"))
        val content = mapOf(0 to byteArrayOf(1), 1 to byteArrayOf(2))
        val first = FakeRarArchiveSession(entries, content)
        open(first, sourceKey = "managed:item-1").apply { read(1); close() }
        assertEquals(1, first.extractionCount)

        val reopened = FakeRarArchiveSession(entries, content)
        val container = open(reopened, sourceKey = "managed:item-1")
        assertEquals(2, container.read(1)[0].toInt())
        assertEquals("a reopened managed source must reuse the existing final", 0, reopened.extractionCount)
        container.close()
    }

    @Test
    fun repeatedLaterEarlierSameAdjacentAccessReturnsCorrectBytesExtractsEachPageOnceAndReleasesEveryLease() {
        val entries = (0 until 6).map { entry(it, "page${it + 1}.jpg") }
        val content = (0 until 6).associateWith { byteArrayOf((it + 100).toByte()) }
        val fake = FakeRarArchiveSession(entries, content)
        val container = open(fake, sourceKey = "managed:pattern")
        val pattern = listOf(5, 1, 5, 5, 4, 3, 4, 0, 5, 2, 2, 1)
        pattern.forEach { assertEquals((it + 100).toByte(), container.read(it)[0]) }
        assertEquals("each distinct page is extracted exactly once", 6, fake.extractionCount)
        val ns = UUIDNamespace.of("managed:pattern")
        (0 until 6).forEach { assertEquals("lease for page $it must be released", 0, coordinator.activeReadersForTest(ns, it)) }
        container.close()
    }

    @Test
    fun thumbnailThenReaderOnTheSamePageShareOneMaterializationAndBothLeasesRelease() {
        val fake = FakeRarArchiveSession(listOf(entry(0, "p.jpg")), mapOf(0 to byteArrayOf(1, 2)))
        val container = open(fake, sourceKey = "managed:thumb")
        val ns = UUIDNamespace.of("managed:thumb")
        val thumbnail = container.extractPage(0).open()
        val reader = container.extractPage(0).open()
        assertEquals(2, coordinator.activeReadersForTest(ns, 0))
        assertArrayEquals(reader.readBytes(), thumbnail.readBytes())
        thumbnail.close(); thumbnail.close() // double close is harmless
        reader.close()
        assertEquals(1, fake.extractionCount)
        assertEquals(0, coordinator.activeReadersForTest(ns, 0))
        container.close()
    }

    @Test
    fun closeIsIdempotentAndEveryPostCloseAccessFailsDeterministicallyWithoutTouchingTheSession() {
        val fake = FakeRarArchiveSession(listOf(entry(0, "ComicInfo.xml"), entry(1, "p.jpg")), mapOf(1 to byteArrayOf(1)))
        val session = CountingSession(fake)
        val container = open(session)
        container.close()
        container.close()
        assertEquals("the native session is released exactly once", 1, session.closes.get())
        try { container.extractPage(0); fail() } catch (_: IllegalStateException) { }
        try { container.comicInfo(); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, fake.extractionCount)
    }

    @Test
    fun publicationSwitchKeepsSourcesIsolatedAndClosesThePreviousSessionOnce() {
        val a = CountingSession(FakeRarArchiveSession(listOf(entry(0, "p.jpg")), mapOf(0 to byteArrayOf(1))))
        val b = CountingSession(FakeRarArchiveSession(listOf(entry(0, "p.jpg")), mapOf(0 to byteArrayOf(2))))
        val first = open(a, sourceKey = null)
        assertEquals(1, first.read(0)[0].toInt())
        first.close()
        val second = open(b, sourceKey = null)
        assertEquals(2, second.read(0)[0].toInt())
        second.close()
        assertEquals(1, a.closes.get())
        assertEquals(1, b.closes.get())
    }

    @Test
    fun boundedConcurrentAccessFromSeveralThreadsNeverDeadlocksOrCorruptsBytes() {
        val pages = 8
        val entries = (0 until pages).map { entry(it, "page$it.jpg") }
        val content = (0 until pages).associateWith { i -> ByteArray(256) { (i * 31 + it).toByte() } }
        // NativeRarSession serializes per session; mirror that so the fake models the real contract.
        val inner = FakeRarArchiveSession(entries, content)
        val session = object : RarArchiveSession by inner {
            override fun extractEntry(index: Int, destination: File, maxBytes: Long) =
                synchronized(this) { inner.extractEntry(index, destination, maxBytes) }
        }
        val container = open(session, sourceKey = "managed:concurrent")
        val pool = Executors.newFixedThreadPool(4)
        val failures = AtomicInteger(0)
        repeat(4) { worker ->
            pool.execute {
                repeat(100) { step ->
                    val page = (worker * 7 + step * 3) % pages
                    if (!container.read(page).contentEquals(content[page])) failures.incrementAndGet()
                }
            }
        }
        pool.shutdown()
        assertTrue("concurrent access must finish (no deadlock)", pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(0, failures.get())
        assertEquals("each page materialized once despite concurrency", pages, inner.extractionCount)
        val ns = UUIDNamespace.of("managed:concurrent")
        (0 until pages).forEach { assertEquals(0, coordinator.activeReadersForTest(ns, it)) }
        container.close()
    }

    @Test
    fun recreationRestoresTheSamePageThroughANewSessionAndExternalSourcesNeverReuseStaleBytes() {
        val entries = (0 until 4).map { entry(it, "page$it.jpg") }
        val original = (0 until 4).associateWith { byteArrayOf(it.toByte()) }
        val persistedPage = 2 // e.g. the locator page saved before process death

        // Managed copy: a new session after recreation restores page N from the reused final.
        open(FakeRarArchiveSession(entries, original), sourceKey = "managed:recreate").apply { read(persistedPage); close() }
        val recreated = FakeRarArchiveSession(entries, original)
        open(recreated, sourceKey = "managed:recreate").apply {
            assertEquals(persistedPage, read(persistedPage)[0].toInt())
            close()
        }
        assertEquals(0, recreated.extractionCount)

        // External source (null key): bytes replaced behind the same identity with the same size.
        open(FakeRarArchiveSession(entries, original), sourceKey = null).apply { read(persistedPage); close() }
        val replaced = (0 until 4).associateWith { byteArrayOf((it + 50).toByte()) }
        val external = FakeRarArchiveSession(entries, replaced)
        open(external, sourceKey = null).apply {
            assertEquals("an external reopen must never serve the old cached page", 52, read(persistedPage)[0].toInt())
            close()
        }
        assertEquals(1, external.extractionCount)
    }

    /** Mirrors [RarContainer.open]'s namespace derivation so tests can inspect coordinator lease state. */
    private object UUIDNamespace {
        fun of(sourceKey: String): String = java.util.UUID.nameUUIDFromBytes(sourceKey.toByteArray(Charsets.UTF_8)).toString()
    }
}
