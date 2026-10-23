// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.database.CoverCandidate
import com.d4guilar.shelfos.core.database.CoverDao
import com.d4guilar.shelfos.core.database.CoverFileRow
import com.d4guilar.shelfos.core.database.PublicationCoverEntity
import com.d4guilar.shelfos.core.database.ReadyCoverRow
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.reader.CoverExtractor
import com.d4guilar.shelfos.core.reader.ExtractedCover
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * L1/L6: the real [CoverScheduler.run] dispatch loop (priority before waiting before backfill, nothing dropped) and its
 * reader-first gate ([CoverScheduler.setReaderActive] stops the NEXT dispatch; a running extraction is never cancelled).
 * The DAO stub has no state of its own: the attempt-begin and the guarded success commit both succeed (one row), so
 * dispatched items take the production success path; ordering is observed through latches, not sleeps.
 */
class CoverSchedulerTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private class StubDao(private val waitingIds: List<String>) : CoverDao() {
        val committed = ConcurrentHashMap<String, Int>()
        val commitLatches = ConcurrentHashMap<String, CountDownLatch>()
        fun committedLatch(id: String) = commitLatches.getOrPut(id) { CountDownLatch(1) }
        override fun observeReady(): Flow<List<ReadyCoverRow>> = flowOf(emptyList())
        override suspend fun local(id: String): PublicationCoverEntity? = null
        override suspend fun openAttempts(): List<PublicationCoverEntity> = emptyList()
        override suspend fun referencedFiles(): List<CoverFileRow> = emptyList()
        override suspend fun deleteOrphanRows(): Int = 0
        override suspend fun candidates(now: Long, allowRetries: Boolean, limit: Int): List<CoverCandidate> = emptyList()
        override suspend fun waiting(reasons: List<String>, limit: Int) = waitingIds.map { CoverCandidate(it, "EPUB") }
        override fun observeLocal(id: String): Flow<PublicationCoverEntity?> = flowOf(null)
        override suspend fun openWindow(id: String, now: Long): Int = 0
        override suspend fun recordInterruption(id: String, slot: String, token: Long, interruptions: Int, parked: Boolean, nextEligibleAt: Long?, now: Long) = 0
        override suspend fun clampNotBefore(limit: Long): Int = 0
        override suspend fun insertPending(id: String, version: Int, now: Long) {}
        override suspend fun markAttempt(id: String, token: Long, version: Int): Int = 1
        override suspend fun ownedFileName(id: String, token: Long): String? = null
        override suspend fun updateReady(id: String, token: Long, fileName: String, mimeType: String, representation: String, sourceWidth: Int,
            sourceHeight: Int, storedWidth: Int, storedHeight: Int, storedBytes: Long, localSource: String, sourceRef: String, sourceChoice: String,
            sourceRevision: String?, version: Int, now: Long): Int {
            committed.merge(id, 1, Int::plus); committedLatch(id).countDown(); return 1
        }
        override suspend fun closeAttempt(id: String, token: Long, status: String, reason: String?, attempts: Int, interruptions: Int,
            parked: Boolean, nextEligibleAt: Long?, version: Int, now: Long): Int = error("no attempt may fail here: $id $reason")
        override suspend fun cancelAttempt(id: String, token: Long, now: Long): Int = 0
        override suspend fun markWaiting(id: String, reason: String, now: Long) {}
        override suspend fun itemExists(id: String) = true
        override fun observeHasUser(id: String): Flow<Boolean> = flowOf(false)
        override suspend fun userFileName(id: String): String? = null
        override suspend fun upsertUser(id: String, fileName: String, mimeType: String, representation: String, sourceWidth: Int, sourceHeight: Int,
            storedWidth: Int, storedHeight: Int, storedBytes: Long, now: Long, originKind: String?, originProvider: String?, originEdition: String?,
            originRefKind: String?, originRefValue: String?, originSelectedAt: Long?) {}
        override suspend fun user(id: String): PublicationCoverEntity? = null
        override suspend fun deleteUserRow(id: String): Int = 0
    }

    /** Records extraction order; [hold] blocks an item's extraction until its latch is released. */
    private class GatedExtractor(private val hold: Map<String, CountDownLatch> = emptyMap()) : CoverExtractor {
        val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val startLatches = ConcurrentHashMap<String, CountDownLatch>()
        fun startedLatch(id: String) = startLatches.getOrPut(id) { CountDownLatch(1) }
        override fun extract(item: LibraryItem, output: File, admitTemporary: (Long) -> Boolean): ExtractedCover {
            started += item.id; startedLatch(item.id).countDown()
            hold[item.id]?.let { check(it.await(10, TimeUnit.SECONDS)) { "held too long" } }
            output.writeBytes(ByteArray(100))
            return ExtractedCover("image/jpeg", "jpg", CoverRepresentation.DERIVED, 100, 150, 100, 150, 100,
                CoverLocalSource.ARCHIVE_FIRST_PAGE, "page:0", CoverSourceChoice.INFERRED, null)
        }
    }

    private fun item(id: String) = LibraryItem(id, "Item $id", "", MediaCategory.BOOK, "source:$id", PublicationFormat.EPUB, "$id.epub", 1L)
    private fun scheduler(dao: StubDao, extractor: CoverExtractor) = CoverScheduler(RoomCoverRepository(dao, throttleMillis = 0),
        CoverFiles(temp.newFolder("covers"), { Long.MAX_VALUE }), { id -> if (id == "unavailable") null else item(id) }, extractor,
        idleDelayMillis = 0, cbrPauseMillis = 0, pollMillis = 20)
    private fun CountDownLatch.ok(what: String) = assertTrue("timed out: $what", await(10, TimeUnit.SECONDS))
    /** Absence check with a bounded window: [latch] must NOT fire within it. */
    private fun CountDownLatch.stillClosed(millis: Long = 300) = !await(millis, TimeUnit.MILLISECONDS)

    @Test fun runDispatchesPriorityBeforeWaitingAndDropsNeither() {
        val dao = StubDao(waitingIds = listOf("A"))
        val releaseB = CountDownLatch(1)
        val extractor = GatedExtractor(mapOf("B" to releaseB))
        val s = scheduler(dao, extractor)
        s.request("B"); s.request("B") // deduplicated: B is dispatched once
        s.setForeground(true)
        scope.launch { s.run() }

        extractor.startedLatch("B").ok("B starts first")
        assertEquals(listOf("B"), extractor.started.toList())
        assertTrue("A stays queued while B runs (one worker)", extractor.startedLatch("A").stillClosed())
        releaseB.countDown()
        dao.committedLatch("B").ok("B commits")
        dao.committedLatch("A").ok("A, still queued, runs afterwards")
        assertTrue("nothing else is dispatched", CountDownLatch(1).stillClosed())
        assertEquals(listOf("B", "A"), extractor.started.toList())
        assertEquals(mapOf("A" to 1, "B" to 1), dao.committed.toMap())
    }

    @Test fun readerActiveBlocksTheNextDispatchButNotTheRunningOneAndInactiveResumesIt() {
        val dao = StubDao(waitingIds = listOf("A", "B"))
        val releaseA = CountDownLatch(1)
        val extractor = GatedExtractor(mapOf("A" to releaseA))
        val s = scheduler(dao, extractor)
        s.setForeground(true)
        scope.launch { s.run() }

        extractor.startedLatch("A").ok("A starts")
        s.setReaderActive(true)
        releaseA.countDown() // the running extraction finishes; it is never cancelled by the gate
        dao.committedLatch("A").ok("A completes while the reader is active")
        assertTrue("B must not start while a reader is active", extractor.startedLatch("B").stillClosed())
        assertEquals(listOf("A"), extractor.started.toList())
        s.setReaderActive(false)
        dao.committedLatch("B").ok("B runs once the reader closes")
        assertTrue(CountDownLatch(1).stillClosed())
        assertEquals(listOf("A", "B"), extractor.started.toList())
        assertEquals(mapOf("A" to 1, "B" to 1), dao.committed.toMap())
    }

    @Test fun noDispatchWhileInBackgroundUntilForeground() {
        val dao = StubDao(waitingIds = listOf("A"))
        val extractor = GatedExtractor()
        val s = scheduler(dao, extractor)
        scope.launch { s.run() }
        assertTrue("background: nothing starts", extractor.startedLatch("A").stillClosed())
        s.setForeground(true)
        dao.committedLatch("A").ok("A runs in the foreground")
    }

    /** 4.5B L-3: idle work (local metadata analysis) that keeps failing never stops covers, never spins, never runs under a reader. */
    @Test fun failingIdleWorkNeverStopsCoversNorSpinsNorRunsWhileReading() {
        val dao = StubDao(waitingIds = emptyList())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val s = CoverScheduler(RoomCoverRepository(dao, throttleMillis = 0), CoverFiles(temp.newFolder("covers"), { Long.MAX_VALUE }),
            { id -> item(id) }, GatedExtractor(), idleDelayMillis = 0, cbrPauseMillis = 0, pollMillis = 20,
            idleWork = { calls.incrementAndGet(); throw java.io.IOException("unreadable") })
        s.setForeground(true)
        scope.launch { s.run() }
        Thread.sleep(500)
        assertTrue("idle work ran, paced by the idle poll (no busy loop): ${calls.get()}", calls.get() in 1..60)
        s.request("C")
        dao.committedLatch("C").ok("covers still run after idle work failed")
        s.setReaderActive(true)
        Thread.sleep(100)
        val whileReading = calls.get()
        Thread.sleep(300)
        assertEquals("no idle work while a reader is open", whileReading, calls.get())
    }

    @Test fun unavailableItemYieldsNoExtraction() = runBlocking {
        val extractor = GatedExtractor()
        assertNull(scheduler(StubDao(emptyList()), extractor).attempt("unavailable"))
        assertTrue(extractor.started.isEmpty())
    }
}
