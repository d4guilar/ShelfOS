// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.core.reader.CoverExtractor
import com.d4guilar.shelfos.core.reader.ExtractedCover
import com.d4guilar.shelfos.data.library.CoverScheduler
import com.d4guilar.shelfos.data.library.RoomCoverRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.KnowledgePolicy
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 4.5A.1 cover lifecycle on a real Room database and real files (plan §23.3–§23.5, §18.1, §19.1): unique immutable
 * files, database-before-delete replacement, zero-row rejection, removal races, startup reconciliation → sweep → schedule,
 * parking across launches, explicit refresh, the storage guard, unknown stored values and a 2,000-item backfill.
 * The extractor is a fake (no Bitmap work); real extraction is covered by CoverExtractionCorpusTest. A "launch" is a new
 * repository + scheduler over the same database and files; a real process kill is simulated by an attempt left open.
 */
class CoverLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase
    private lateinit var root: File
    private lateinit var library: RoomLibraryRepository
    private var free = Long.MAX_VALUE
    private lateinit var files: CoverFiles
    private val calls = AtomicInteger()
    private var onExtract: (LibraryItem, File, (Long) -> Boolean) -> ExtractedCover = { _, out, _ -> write(out) }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        root = File(context.cacheDir, "cover-lifecycle-${System.nanoTime()}")
        files = CoverFiles(root, { free })
        library = RoomLibraryRepository(db.library(), covers = files)
    }
    @After fun tearDown() { db.close(); root.deleteRecursively() }

    private fun write(out: File, bytes: Long = 1234) = out.writeBytes(ByteArray(bytes.toInt()) { 7 }).let {
        ExtractedCover("image/jpeg", "jpg", CoverRepresentation.DERIVED, 2000, 3000, 800, 1200, bytes, CoverLocalSource.ARCHIVE_FIRST_PAGE,
            "page:0", CoverSourceChoice.INFERRED, null)
    }
    private val extractor = CoverExtractor { item, out, admit -> calls.incrementAndGet(); onExtract(item, out, admit) }
    private fun repo(throttle: Long = 0) = RoomCoverRepository(db.covers(), throttleMillis = throttle)
    private fun scheduler(covers: RoomCoverRepository = repo()) = CoverScheduler(covers, files, { id -> library.publication(id).first() },
        extractor, idleDelayMillis = 0, cbrPauseMillis = 0, pollMillis = 50)
    private suspend fun add(id: String, format: PublicationFormat = PublicationFormat.CBZ, addedAt: Long = 1) =
        library.add(LibraryItem(id, "T $id", category = MediaCategory.COMIC, sourceUri = "test:$id", format = format, fileName = "$id.x", byteSize = 1, addedAt = addedAt))
    private fun dir(id: String) = File(root, id).list()?.sorted().orEmpty()
    private suspend fun row(id: String) = db.covers().local(id)
    private fun sql(statement: String) = db.openHelper.writableDatabase.execSQL(statement)

    @Test fun firstExtractionWritesAUniqueImmutableFileAndOneReadyRow() = runBlocking<Unit> {
        add("a")
        assertEquals(PublicationFormat.CBZ, scheduler().attempt("a"))
        val r = row("a")!!
        assertEquals("READY", r.status); assertEquals("LOCAL", r.slot); assertNull(r.failureReason); assertNull(r.attemptStartedAt)
        assertTrue(r.fileName!!.matches(Regex("local-[0-9a-f]{16}\\.jpg")))
        assertEquals(listOf(r.fileName), dir("a"))
        assertEquals(listOf("ARCHIVE_FIRST_PAGE", "INFERRED", "DERIVED"), listOf(r.localSource, r.sourceChoice, r.representation))
        assertEquals(listOf(2000, 3000, 800, 1200), listOf(r.sourceWidthPx, r.sourceHeightPx, r.storedWidthPx, r.storedHeightPx))
        assertEquals(0, r.attempts)
        assertEquals(CoverRef("a", r.fileName, 800, 1200), repo().covers.first()["a"])
        assertNull("READY artwork is not re-extracted on demand", scheduler().attempt("a"))
    }

    @Test fun replacementWritesANewFileAndDeletesTheOldOneOnlyAfterTheCommit() = runBlocking<Unit> {
        add("a"); scheduler().attempt("a")
        val old = row("a")!!.fileName!!
        onExtract = { _, out, _ ->
            assertTrue("the old READY file stays during work", File(File(root, "a"), old).isFile)
            assertEquals(old, runBlocking { row("a") }!!.fileName)
            write(out, 999)
        }
        // The re-extraction trigger in 4.5A.1 is a READY file the renderer could not read ("missing"); the old file is
        // kept here to prove it is never overwritten and is deleted only after the new row has committed.
        assertNotNull(scheduler().attempt("a", missing = true))
        val new = row("a")!!.fileName!!
        assertNotEquals(old, new)
        assertEquals(listOf(new), dir("a"))
        assertEquals("the cover map (and bitmap-cache key) follows the new name", new, repo().covers.first()["a"]!!.fileName)
    }

    @Test fun aGuardedWriteThatTouchesZeroRowsKeepsTheOldCoverAndDiscardsTheNewFile() = runBlocking<Unit> {
        add("a"); scheduler().attempt("a")
        val old = row("a")!!.fileName!!
        // Another owner took the row mid-extraction: the commit's WHERE attemptStartedAt = token matches nothing.
        onExtract = { _, out, _ -> sql("UPDATE publication_cover SET attemptStartedAt = attemptStartedAt + 1"); write(out) }
        scheduler().attempt("a", missing = true)
        assertEquals(old, row("a")!!.fileName); assertEquals("READY", row("a")!!.status)
        assertEquals(listOf(old), dir("a"))
    }

    @Test fun aThrowingCommitKeepsTheOldCoverAndDiscardsTheNewFile() = runBlocking<Unit> {
        add("a"); scheduler().attempt("a")
        val old = row("a")!!.fileName!!
        // Rejected at the persistence boundary before SQL runs (0 stored bytes): an explained IO_ERROR close.
        onExtract = { _, out, _ -> write(out).copy(bytes = 0) }
        scheduler().attempt("a", missing = true)
        val r = row("a")!!
        assertEquals(listOf("READY", old), listOf(r.status, r.fileName)); assertNull(r.failureReason); assertNull(r.attemptStartedAt)
        assertNotNull("retry metadata only", r.nextEligibleAt)
        assertEquals(listOf(old), dir("a"))
    }

    @Test fun anItemRemovedMidExtractionGetsNoRowAndNoFile() = runBlocking<Unit> {
        add("a")
        // Removal commits while the extractor works; files are deleted afterwards by the repository, so the transaction
        // alone is used here to prove the guarded UPDATE (item-exists) rejects the late commit.
        onExtract = { _, out, _ -> val c = write(out); runBlocking { db.library().removeDeletingKnowledge("a") }; c }
        scheduler().attempt("a")
        assertNull(row("a"))
        assertEquals(emptyList<String>(), dir("a"))
        assertNull("a guarded attempt-begin after removal inserts nothing", repo().begin("a"))
        assertNull(row("a"))
    }

    @Test fun removalDeletesRowsInTheTransactionAndFilesAfterIt() = runBlocking<Unit> {
        add("a"); scheduler().attempt("a")
        library.remove("a", KnowledgePolicy.KEEP)
        assertNull(row("a")); assertFalse(File(root, "a").exists())
    }

    @Test fun startupReconcilesAnAbandonedAttemptThenParksAtTheSecondInterruption() = runBlocking<Unit> {
        add("a")
        // "Process death" during extraction: a committed marker and a temporary file, no close.
        val token = repo().begin("a")!!
        files.newTemporary("a").writeBytes(byteArrayOf(1, 2, 3))
        val launch1 = scheduler(); launch1.reconcileAndSweep()
        var r = row("a")!!
        assertEquals(listOf("FAILED_TEMPORARY", "INTERRUPTED"), listOf(r.status, r.failureReason))
        assertEquals(listOf(1, 1, false), listOf(r.attempts, r.interruptions, r.parked))
        assertNull(r.attemptStartedAt); assertTrue("never eligible at startup", r.nextEligibleAt!! > System.currentTimeMillis())
        assertTrue(token > 0); assertEquals("the .tmp is swept after reconciliation", emptyList<String>(), dir("a"))
        assertNull(launch1.attempt("a")); assertEquals(0, calls.get())
        // The backoff elapses, attempt 2 starts and the process dies again: parked, no timer.
        sql("UPDATE publication_cover SET nextEligibleAt = 0")
        repo().begin("a")
        scheduler().reconcileAndSweep()
        r = row("a")!!
        assertEquals(listOf(2, 2, true), listOf(r.attempts, r.interruptions, r.parked)); assertNull(r.nextEligibleAt)
    }

    @Test fun aParkedItemIsNotRetriedOnThreeLaunchesUntilAnExplicitRefresh() = runBlocking<Unit> {
        add("parked", addedAt = 2); add("fresh", addedAt = 1)
        sql("INSERT INTO publication_cover (libraryItemId, slot, status, failureReason, extractorVersion, attempts, interruptions, parked, updatedAt) " +
            "VALUES ('parked', 'LOCAL', 'FAILED_TEMPORARY', 'INTERRUPTED', 1, 2, 2, 1, 1)")
        val extracted = mutableListOf<String>()
        onExtract = { item, out, _ -> synchronized(extracted) { extracted += item.id }; write(out) }
        repeat(3) { launch ->
            val s = scheduler()
            val job = launch(Dispatchers.IO) { s.run() }
            s.setForeground(true)
            withTimeout(10_000) { while (row("fresh")?.status != "READY") delay(20) }
            delay(300)
            job.cancelAndJoin()
            assertTrue("launch $launch", row("parked")!!.parked)
        }
        assertEquals(listOf("fresh"), extracted)
        assertEquals("a relaunch never reset the history", listOf(2, 2), row("parked")!!.let { listOf(it.attempts, it.interruptions) })
        val s = scheduler()
        val job = launch(Dispatchers.IO) { s.run() }
        s.setForeground(true)
        s.refresh("parked")
        withTimeout(10_000) { while (row("parked")?.status != "READY") delay(20) }
        job.cancelAndJoin()
        assertEquals(listOf("fresh", "parked"), extracted)
    }

    @Test fun theSweepRunsAfterReconciliationAndBeforeAnyExtraction() = runBlocking<Unit> {
        add("a"); add("b")
        scheduler().attempt("a")
        val kept = row("a")!!.fileName!!
        File(File(root, "a"), "local-0000000000000001.jpg").writeBytes(byteArrayOf(1)) // a replaced cover's leftover
        File(File(root, "a"), "0000000000000002.tmp").writeBytes(byteArrayOf(1))
        File(root, "removed-item").mkdirs(); File(File(root, "removed-item"), "local-0000000000000003.jpg").writeBytes(byteArrayOf(1))
        repo().begin("b") // an attempt left open by the previous process
        val s = scheduler()
        onExtract = { _, out, _ -> assertTrue("never before reconcile + sweep", s.ready.value); write(out) }
        val job = launch(Dispatchers.IO) { s.run() }
        s.setForeground(true)
        withTimeout(10_000) { while (!s.ready.value) delay(10) }
        assertEquals(listOf(kept), dir("a"))
        assertFalse(File(root, "removed-item").exists())
        assertEquals(1, row("b")!!.interruptions)
        delay(300); job.cancelAndJoin()
        assertNull("b is backing off, not re-run at startup", row("b")!!.fileName)
    }

    @Test fun theStorageGuardSkipsWorkKeepsArtworkAndGivesTheAttemptBack() = runBlocking<Unit> {
        add("a"); add("b")
        free = CoverStoragePolicy.RESERVE_FREE_BYTES + CoverStoragePolicy.MAX_COVER_BYTES - 1
        assertNull(scheduler().attempt("a"))
        assertEquals(0, calls.get())
        assertEquals(listOf("FAILED_TEMPORARY", "STORAGE_FULL", "0"), row("a")!!.let { listOf(it.status, it.failureReason, it.attempts.toString()) })
        assertNull("no timer, no retry storm", row("a")!!.nextEligibleAt)
        // Existing artwork is never deleted to make room.
        free = Long.MAX_VALUE; scheduler().attempt("b"); val kept = row("b")!!.fileName
        free = 1; scheduler().attempt("b", missing = true)
        assertEquals(listOf("READY", kept), listOf(row("b")!!.status, row("b")!!.fileName)); assertEquals(listOf(kept), dir("b"))
        // STORAGE_FULL discovered during work (a CBR's temporary need): explained, no interruption, attempt given back.
        add("c", PublicationFormat.CBR)
        free = CoverStoragePolicy.RESERVE_FREE_BYTES + CoverStoragePolicy.MAX_COVER_BYTES + CoverStoragePolicy.UNKNOWN_TEMP_RESERVE_BYTES
        onExtract = { _, _, admit -> if (!admit(100L * 1024 * 1024)) throw CoverExtractionFailure(CoverFailureReason.STORAGE_FULL); error("admitted") }
        scheduler().attempt("c")
        val c = row("c")!!
        assertEquals(listOf("FAILED_TEMPORARY", "STORAGE_FULL"), listOf(c.status, c.failureReason))
        assertEquals(listOf(0, 0, false), listOf(c.attempts, c.interruptions, c.parked)); assertNull(c.nextEligibleAt); assertNull(c.attemptStartedAt)
        assertEquals(emptyList<String>(), dir("c"))
    }

    @Test fun unknownStoredValuesDegradeToTheGeneratedCoverWithoutCrashing() = runBlocking<Unit> {
        add("a"); add("b"); add("c")
        sql("INSERT INTO publication_cover (libraryItemId, slot, status, fileName, storedWidthPx, storedHeightPx, extractorVersion, updatedAt) VALUES ('a', 'FUTURE', 'READY', 'future-0000000000000001.jpg', 10, 10, 9, 1)")
        sql("INSERT INTO publication_cover (libraryItemId, slot, status, extractorVersion, updatedAt) VALUES ('b', 'LOCAL', 'SOMETHING_NEW', 9, 1)")
        sql("INSERT INTO publication_cover (libraryItemId, slot, status, failureReason, extractorVersion, updatedAt) VALUES ('c', 'LOCAL', 'FAILED_TEMPORARY', 'NEW_REASON', 9, 1)")
        val covers = repo()
        assertEquals(emptyMap<String, CoverRef>(), covers.covers.first())
        assertFalse(covers.eligibleOnDemand("b", missing = false)); assertFalse(covers.eligibleOnDemand("c", missing = false))
        assertNull(covers.provenance("b").first())
        assertNull(scheduler().attempt("b")); assertEquals(0, calls.get())
    }

    @Test fun aTwoThousandItemBackfillNeverReEmitsTheLibraryListAndKeepsPublishingCovers() = runBlocking<Unit> {
        db.runInTransaction { repeat(2000) { sql(LegacyDatabases.libraryRow("item-$it", "CBZ")) } }
        val libraryEmissions = AtomicInteger(); val coverEmissions = AtomicInteger(); var last = emptyMap<String, CoverRef>()
        val covers = repo(throttle = RoomCoverRepository.COVER_MAP_THROTTLE_MILLIS)
        val watchers = listOf(
            launch(Dispatchers.IO) { db.library().observe().collect { libraryEmissions.incrementAndGet() } },
            launch(Dispatchers.IO) { covers.covers.collect { coverEmissions.incrementAndGet(); last = it } })
        withTimeout(10_000) { while (libraryEmissions.get() == 0 || coverEmissions.get() == 0) delay(10) }
        val s = scheduler(covers)
        val started = System.currentTimeMillis()
        repeat(2000) { s.attempt("item-$it") }
        val elapsed = System.currentTimeMillis() - started
        withTimeout(10_000) { while (last.size < 2000) delay(20) }
        delay(500)
        watchers.forEach { it.cancelAndJoin() }
        android.util.Log.i("CoverBackfill", "2000 covers in ${elapsed}ms; library emissions=${libraryEmissions.get()} cover-map emissions=${coverEmissions.get()}")
        assertEquals("cover writes never re-emit LibraryDao.observe()", 1, libraryEmissions.get())
        assertTrue("bounded: ${coverEmissions.get()} in ${elapsed}ms", coverEmissions.get() <= (elapsed + 1000) / RoomCoverRepository.COVER_MAP_THROTTLE_MILLIS + 3)
        assertTrue("progress is published during the backfill: ${coverEmissions.get()}", coverEmissions.get() >= 3)
    }
}
