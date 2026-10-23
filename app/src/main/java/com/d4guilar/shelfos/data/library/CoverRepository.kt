// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.database.CoverDao
import com.d4guilar.shelfos.core.database.CoverCandidate
import com.d4guilar.shelfos.core.database.PublicationCoverEntity
import com.d4guilar.shelfos.core.reader.ExtractedCover
import com.d4guilar.shelfos.core.reader.StoredImage
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlin.random.Random

/**
 * The only writer of `publication_cover` (plan §23.1): typed enums in, validated before SQL, LOCAL slot from 4.5A.1, plus the user-chosen USER slot from 4.5F.0.
 * Reads tolerate unknown stored strings (the row is unusable, never a crash). Attempt tokens are the attempt start time.
 */
class RoomCoverRepository(
    private val dao: CoverDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    throttleMillis: Long = COVER_MAP_THROTTLE_MILLIS,
) {
    /**
     * §19.1: READY rows observed on their own (never joined into the Library list), slot-resolved, distinct and
     * latest-value throttled, so a long backfill keeps publishing progress at a bounded rate.
     */
    val covers: Flow<Map<String, CoverRef>> = dao.observeReady()
        .map { rows -> resolveCovers(rows.map { ReadyCover(it.libraryItemId, enumOrNull<CoverSlot>(it.slot), it.fileName, it.storedWidthPx, it.storedHeightPx) }) }
        .distinctUntilChanged()
        .throttleLatest(throttleMillis)

    fun provenance(id: String): Flow<CoverProvenance?> = dao.observeLocal(id).map { row ->
        row?.takeIf { it.status == CoverStatus.READY.name }?.let { CoverProvenance(enumOrNull(it.localSource), enumOrNull(it.representation)) }
    }.distinctUntilChanged()

    suspend fun local(id: String): PublicationCoverEntity? = dao.local(id)

    suspend fun itemExists(id: String) = dao.itemExists(id)

    /** True while a READY user-chosen cover overrides every other slot. */
    fun hasUser(id: String): Flow<Boolean> = dao.observeHasUser(id).distinctUntilChanged()

    /** Guarded USER commit (4.5F.0); returns the previous USER file name. Throws `CoverRejectedException` if the item is gone. */
    internal suspend fun commitUser(id: String, fileName: String, image: StoredImage, origin: CoverOrigin? = null): String? {
        requireValidUser(fileName, image)
        val now = clock()
        // The origin columns and the file name commit in the same transaction; an unknown origin stays all-NULL.
        return dao.commitUser(id, fileName, image.mimeType, image.representation.name, image.sourceWidth, image.sourceHeight,
            image.storedWidth, image.storedHeight, image.bytes, now, origin?.kind?.name, origin?.providerId, origin?.editionRef,
            origin?.refKind, origin?.refValue, origin?.takeIf { it.kind == CoverOriginKind.PROVIDER_SELECTED }?.let { it.selectedAtMs ?: now })
    }

    /** The recorded origin of the USER cover, or null when there is none or it predates origin tracking (unknown). */
    suspend fun userOrigin(id: String): CoverOrigin? = dao.user(id)?.let { row ->
        val kind = enumOrNull<CoverOriginKind>(row.originKind) ?: return null
        runCatching { CoverOrigin(kind, row.originProvider, row.originEdition, row.originRefKind, row.originRefValue, row.originSelectedAt) }.getOrNull()
    }

    /** Removes the USER override; returns the file to delete once this has committed, or null when there was none. */
    suspend fun clearUser(id: String): String? = dao.clearUser(id)

    /** Attempt-begin commit; null when the item no longer exists or an attempt is already open. */
    suspend fun begin(id: String): Long? = clock().takeIf { dao.beginAttempt(id, it, VERSION) }

    /** Guarded success commit; returns the previous file name. Throws `CoverRejectedException` unless exactly one row. */
    suspend fun commit(id: String, token: Long, fileName: String, cover: ExtractedCover): String? {
        requireValidReady(CoverSlot.LOCAL, fileName, cover)
        return dao.commitReady(id, token, fileName, cover.mimeType, cover.representation.name, cover.sourceWidth, cover.sourceHeight,
            cover.storedWidth, cover.storedHeight, cover.bytes, cover.localSource.name, cover.sourceRef, cover.choice.name,
            cover.sourceRevision, VERSION, clock())
    }

    /** Explained close (§23.5 step 4); the park rule and retry schedule come from [CoverRetryPolicy]. */
    suspend fun fail(id: String, token: Long, reason: CoverFailureReason) {
        val row = dao.local(id)?.takeIf { it.attemptStartedAt == token } ?: return
        val next = CoverRetryPolicy.afterFailure(row.state(), reason, clock(), random)
        dao.closeAttempt(id, token, if (reason.permanent) CoverStatus.FAILED_PERMANENT.name else CoverStatus.FAILED_TEMPORARY.name,
            reason.name, next.attempts, next.interruptions, next.parked, next.nextEligibleAt, VERSION, clock())
    }

    /** Explicit cancellation records nothing: the marker is cleared and the attempt given back. */
    suspend fun cancel(id: String, token: Long) { dao.cancelAttempt(id, token, clock()) }

    /** A guard failure before any work (no attempt, no timer). Only waiting reasons are accepted here. */
    suspend fun recordWaiting(id: String, reason: CoverFailureReason) {
        require(!reason.permanent && !reason.consumesAttempt && !reason.timer) { "not a waiting reason: $reason" }
        dao.recordWaiting(id, reason.name, VERSION, clock())
    }

    /** A trigger (Refresh cover, storage recovery): opens a fresh retry window, un-parking the item. */
    suspend fun openWindow(id: String) = dao.openWindow(id, clock()) == 1

    /**
     * Startup reconciliation (§23.5 step 6), a database update only: every open marker is an unexplained interruption,
     * parked at attempts >= 4 or interruptions >= 2, otherwise backed off (never eligible at startup). Persisted
     * not-before times are clamped against clock skew. Returns the number of abandoned attempts.
     */
    suspend fun reconcile(): Int {
        val now = clock()
        dao.clampNotBefore(now + CoverRetryPolicy.MAX_SINGLE_DELAY_MILLIS)
        val open = dao.openAttempts()
        for (row in open) {
            val next = CoverRetryPolicy.afterInterruption(row.state(), now, random)
            dao.recordInterruption(row.libraryItemId, row.slot, row.attemptStartedAt!!, next.interruptions, next.parked, next.nextEligibleAt, now)
        }
        return open.size
    }

    suspend fun deleteOrphanRows() = dao.deleteOrphanRows()

    suspend fun referencedFiles(): Map<String, Set<String>> =
        dao.referencedFiles().groupBy({ it.libraryItemId }, { it.fileName }).mapValues { it.value.toSet() }

    suspend fun candidates(allowRetries: Boolean, limit: Int): List<CoverCandidate> = dao.candidates(clock(), allowRetries, limit)

    suspend fun waiting(reasons: Collection<CoverFailureReason>, limit: Int): List<CoverCandidate> = dao.waiting(reasons.map { it.name }, limit)

    /**
     * Whether an on-demand request may start an attempt now: never while parked, an attempt is open or the not-before
     * time is in the future; never over READY artwork unless its file is [missing]; never after a permanent outcome;
     * a source/grant/storage wait only during its once-per-cold-start reassessment ([reassessWaiting]).
     */
    suspend fun eligibleOnDemand(id: String, missing: Boolean, reassessWaiting: Boolean = false): Boolean {
        val row = dao.local(id) ?: return true
        if (row.attemptStartedAt != null || row.parked) return false
        return when (enumOrNull<CoverStatus>(row.status)) {
            CoverStatus.PENDING -> true
            CoverStatus.READY -> missing
            CoverStatus.FAILED_TEMPORARY -> when (val reason = enumOrNull<CoverFailureReason>(row.failureReason)) {
                null -> false
                else -> if (reason.timer) CoverRetryPolicy.eligible(row.state(), clock()) else reassessWaiting
            }
            CoverStatus.FAILED_PERMANENT, null -> false
        }
    }

    companion object {
        /** Bumping this requalifies failed and parked rows (a §23.3 trigger, wired when a second version exists). */
        const val VERSION = com.d4guilar.shelfos.core.reader.LocalCoverExtractor.VERSION
        const val COVER_MAP_THROTTLE_MILLIS = 300L
    }
}

private fun PublicationCoverEntity.state() = CoverAttemptState(attempts, interruptions, parked, nextEligibleAt)

/** Persistence-boundary validation for a READY write (§23.1): no SQL runs for an invalid combination. */
fun requireValidReady(slot: CoverSlot, fileName: String, cover: ExtractedCover) {
    require(slot == CoverSlot.LOCAL) { "4.5A.1 writes only the LOCAL slot" }
    require(Regex("local-[0-9a-f]{16}\\.[a-z]{3,4}").matches(fileName)) { "invalid cover file name" }
    require(cover.extension == fileName.substringAfterLast('.')) { "extension does not match" }
    require(cover.mimeType.startsWith("image/")) { "not an image" }
    require(cover.storedWidth > 0 && cover.storedHeight > 0 && cover.sourceWidth > 0 && cover.sourceHeight > 0) { "invalid dimensions" }
    require(cover.bytes in 1..CoverStoragePolicy.MAX_COVER_BYTES) { "stored bytes out of bounds" }
    require(maxOf(cover.storedWidth, cover.storedHeight) <= CoverStoragePolicy.MAX_EDGE_PX) { "stored edge out of bounds" }
    require(cover.representation == CoverRepresentation.DERIVED || (cover.storedWidth == cover.sourceWidth && cover.storedHeight == cover.sourceHeight)) {
        "an ORIGINAL copy must keep the source dimensions"
    }
    require(cover.localSource != CoverLocalSource.PDF_FIRST_PAGE || cover.representation == CoverRepresentation.DERIVED) { "a PDF preview is never ORIGINAL" }
    require(cover.sourceRef.isNotBlank()) { "missing source reference" }
}

/** Persistence-boundary validation for a USER write: same bounds as LOCAL, different slot and file prefix. */
internal fun requireValidUser(fileName: String, image: StoredImage) {
    require(Regex("user-[0-9a-f]{16}\\.[a-z]{3,4}").matches(fileName)) { "invalid user cover file name" }
    require(image.extension == fileName.substringAfterLast('.')) { "extension does not match" }
    require(image.mimeType.startsWith("image/")) { "not an image" }
    require(image.storedWidth > 0 && image.storedHeight > 0 && image.sourceWidth > 0 && image.sourceHeight > 0) { "invalid dimensions" }
    require(image.bytes in 1..CoverStoragePolicy.MAX_COVER_BYTES) { "stored bytes out of bounds" }
    require(maxOf(image.storedWidth, image.storedHeight) <= CoverStoragePolicy.MAX_EDGE_PX) { "stored edge out of bounds" }
    require(image.representation == CoverRepresentation.DERIVED || (image.storedWidth == image.sourceWidth && image.storedHeight == image.sourceHeight)) {
        "an ORIGINAL copy must keep the source dimensions"
    }
}

/** Leading + trailing latest-value throttle: at most one emission per [periodMillis], never starved like a debounce. */
fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> = conflate().transform { emit(it); delay(periodMillis) }
