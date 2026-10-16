// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.database.CoverRejectedException
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.core.reader.CoverExtractor
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local cover backfill (plan §19, §19.1, §19.2, §23.5). One worker, so at most one extraction (and therefore at most one
 * CBR extraction) runs at a time. Startup order is fixed: reconcile attempt markers → delete orphan rows → sweep
 * orphan/unreferenced/temporary files → idle delay → schedule. Interrupted work is never re-run at startup.
 *
 * Gates, checked before each attempt (an attempt in progress, native or not, is never cancelled by a gate):
 * the app is in the foreground, no reader is active, and the §18.1 storage guard passes. Visible tiles and an opened
 * details pane [request] their item, which jumps the idle backfill but still obeys every eligibility rule.
 */
class CoverScheduler(
    private val covers: RoomCoverRepository,
    private val files: CoverFiles,
    private val publication: suspend (String) -> LibraryItem?,
    private val extractor: CoverExtractor,
    private val idleDelayMillis: Long = 3_000,
    private val cbrPauseMillis: Long = 3_000,
    private val pollMillis: Long = 30_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Lower-priority local work run on this same worker when no cover work is due (4.5B local metadata analysis), so it
     * shares the foreground/no-reader gates and never overlaps a cover extraction. Returns the processed item's format,
     * or null when it has nothing to do.
     */
    private val idleWork: suspend () -> PublicationFormat? = { null },
) {
    private val foreground = MutableStateFlow(false)
    private val readerActive = MutableStateFlow(false)
    private val storageBlocked = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val recovery = Channel<Unit>(Channel.CONFLATED)
    private val waiting = ArrayDeque<String>()
    private val lock = Mutex()
    private val priority = LinkedHashMap<String, Boolean>()  // id -> file reported missing
    private val requested = HashSet<String>()
    private var retriesThisLaunch = 0

    /** Observable for tests and diagnostics: true once reconciliation and the sweep have completed. */
    val ready = MutableStateFlow(false)

    fun setForeground(value: Boolean) {
        // A return to the foreground is the storage-recovery signal (§18.1 item 4), never a timer.
        if (value && !foreground.value) recovery.trySend(Unit)
        foreground.value = value; wake.trySend(Unit)
    }
    fun setReaderActive(value: Boolean) { readerActive.value = value; wake.trySend(Unit) }

    /** On-demand: an item became visible or its details opened. Asked once per process per item. */
    fun request(id: String) = enqueue(id, missing = false)

    /** A READY row's file could not be read: queued once per process (§18.1 item 5), never per render. */
    fun reportMissing(id: String) = enqueue(id, missing = true)

    private fun enqueue(id: String, missing: Boolean) {
        synchronized(priority) {
            if (!requested.add(if (missing) "missing:$id" else id)) return
            priority[id] = missing || priority[id] == true
            while (priority.size > MAX_PRIORITY) priority.remove(priority.keys.first())
        }
        wake.trySend(Unit)
    }

    /** An explicit Refresh cover trigger (§23.3): a fresh window (un-parks), then one immediate on-demand attempt. */
    suspend fun refresh(id: String) {
        covers.openWindow(id)
        synchronized(priority) { requested.remove(id); requested.remove("missing:$id") }
        enqueue(id, missing = covers.local(id)?.status == CoverStatus.READY.name)
    }

    /** Startup steps 1-3 (§23.5 step 7). Never starts or awaits an extraction. */
    suspend fun reconcileAndSweep() = lock.withLock {
        covers.reconcile()
        covers.deleteOrphanRows()
        withContext(io) { files.sweep(covers.referencedFiles()) }
        ready.value = true
    }

    /** The whole lifecycle; runs until its scope is cancelled. */
    suspend fun run() {
        reconcileAndSweep()
        delay(idleDelayMillis)
        // Waiting rows (source, grant, storage) are reassessed once per cold start, bounded by the per-launch cap.
        waiting += covers.waiting(WAITING, PER_LAUNCH_RETRY_CAP).map { it.id }
        while (true) {
            awaitGates()
            // Priority first: a visible/details-picked item jumps the idle backfill.
            val priority = nextPriority()
            if (priority != null) {
                val (id, missing) = priority
                val format = attempt(id, missing, reassessWaiting = false) ?: continue
                if (format == PublicationFormat.CBR) delay(cbrPauseMillis) else yield()
                continue
            }
            // Waiting: peek, remove only when we actually process it — priority never discards waiting work.
            val waitingId = waiting.firstOrNull()
            if (waitingId != null) {
                waiting.removeFirst()
                val format = attempt(waitingId, missing = false, reassessWaiting = true) ?: continue
                if (format == PublicationFormat.CBR) delay(cbrPauseMillis) else yield()
                continue
            }
            // Idle backfill candidates.
            val candidate = covers.candidates(retriesThisLaunch < PER_LAUNCH_RETRY_CAP, 1).firstOrNull()?.id
            if (candidate != null) {
                val format = attempt(candidate, missing = false, reassessWaiting = false) ?: continue
                if (format == PublicationFormat.CBR) delay(cbrPauseMillis) else yield()
                continue
            }
            val other = idleWork()
            if (other != null) { if (other == PublicationFormat.CBR) delay(cbrPauseMillis) else yield(); continue }
            idle()
        }
    }

    private fun nextPriority(): Pair<String, Boolean>? = synchronized(priority) {
        priority.entries.firstOrNull()?.let { (id, missing) -> priority.remove(id); id to missing }
    }

    // shortcut: polls for elapsed retry timers every pollMillis while idle; a precise next-deadline wake can replace it if needed.
    private suspend fun idle() { withTimeoutOrNull(pollMillis) { wake.receive() } }

    /** Storage-blocked: wait for a recovery signal and re-check with hysteresis; then foreground and no reader. */
    private suspend fun awaitGates() {
        while (storageBlocked.value) {
            recovery.receive()
            if (CoverStoragePolicy.admits(withContext(io) { files.freeBytes() }, 0, CoverStoragePolicy.RECOVERY_HYSTERESIS_BYTES)) {
                storageBlocked.value = false
                waiting += covers.waiting(listOf(CoverFailureReason.STORAGE_FULL), PER_LAUNCH_RETRY_CAP).map { it.id }
            }
        }
        combine(foreground, readerActive, storageBlocked) { fg, reader, blocked -> fg && !reader && !blocked }.first { it }
    }

    /**
     * One attempt for [id] (§23.5): storage guard → attempt-begin commit → extraction into a temp file → finalize under
     * a new unique name → guarded commit (exactly one row) → only then delete the previous file. Returns the item's
     * format when an attempt ran, null when nothing was attempted.
     */
    internal suspend fun attempt(id: String, missing: Boolean = false, reassessWaiting: Boolean = false): PublicationFormat? = lock.withLock {
        val item = publication(id)?.takeIf { it.available } ?: return null
        if (!covers.eligibleOnDemand(id, missing, reassessWaiting)) return null
        val reserve = if (item.format == PublicationFormat.CBR) CoverStoragePolicy.UNKNOWN_TEMP_RESERVE_BYTES else 0L
        if (!CoverStoragePolicy.admits(withContext(io) { files.freeBytes() }, reserve)) {
            covers.recordWaiting(id, CoverFailureReason.STORAGE_FULL)
            storageBlocked.value = true
            return null
        }
        val retry = covers.local(id)?.status == CoverStatus.FAILED_TEMPORARY.name
        val token = covers.begin(id) ?: return null
        if (retry) retriesThisLaunch++
        var temporary: java.io.File? = null
        var finalName: String? = null
        var committed = false
        try {
            temporary = files.newTemporary(id)
            val cover = withContext(io) {
                extractor.extract(item, temporary) { need -> CoverStoragePolicy.admits(files.freeBytes(), need) }
            }
            finalName = withContext(io) { files.finalize(id, temporary, CoverSlot.LOCAL.name, cover.extension) }
            // Never cancelled half-way: a commit that landed must not have its referenced file deleted below.
            val previous = withContext(NonCancellable) { covers.commit(id, token, finalName, cover).also { committed = true } }
            if (previous != null && previous != finalName) withContext(io) { files.delete(id, previous) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { covers.cancel(id, token) } }
            throw e
        } catch (_: CoverRejectedException) {
            // Item removed or ownership lost mid-extraction: nothing recorded, nothing revived; files cleaned below.
        } catch (e: CoverExtractionFailure) {
            if (e.reason == CoverFailureReason.STORAGE_FULL) storageBlocked.value = true
            runCatching { covers.fail(id, token, e.reason) }
        } catch (_: OutOfMemoryError) {
            runCatching { covers.fail(id, token, CoverFailureReason.RESOURCE_EXHAUSTED) }
        } catch (_: Exception) {
            runCatching { covers.fail(id, token, CoverFailureReason.IO_ERROR) }
        } finally {
            withContext(NonCancellable + io) {
                temporary?.delete()
                if (!committed) finalName?.let { files.delete(id, it) }
            }
        }
        item.format
    }

    companion object {
        const val PER_LAUNCH_RETRY_CAP = 25
        private const val MAX_PRIORITY = 64
        private val WAITING = listOf(CoverFailureReason.SOURCE_UNAVAILABLE, CoverFailureReason.PERMISSION_UNAVAILABLE, CoverFailureReason.STORAGE_FULL)
    }
}
