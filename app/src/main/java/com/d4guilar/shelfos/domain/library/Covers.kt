// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

import kotlin.random.Random

/**
 * Local cover artwork model (Phase 4.5A.1, plan §23.1). Persisted as enum NAMES in `publication_cover`; an unknown stored
 * string never throws: [enumOrNull] returns null and the row is treated as unusable (generated cover, reassessable).
 */
enum class CoverSlot { LOCAL, USER, ONLINE }
enum class CoverStatus { PENDING, READY, FAILED_PERMANENT, FAILED_TEMPORARY }
enum class CoverRepresentation { ORIGINAL, DERIVED }
enum class CoverLocalSource { EPUB_COVER, COMICINFO_FRONT_COVER, ARCHIVE_FIRST_PAGE, PDF_FIRST_PAGE }
enum class CoverSourceChoice { DECLARED, INFERRED }

enum class CoverFailureReason(val permanent: Boolean, val consumesAttempt: Boolean = true, val timer: Boolean = !permanent) {
    NO_SUITABLE_ARTWORK(true), UNSUPPORTED_REPRESENTATION(true), UNDECODABLE_CONTENT(true),
    // Detected before any extraction work; the attempt is given back and the row waits for the next cold-start
    // reassessment (or storage recovery), never a timer, so a missing source or a full device cannot park an item.
    SOURCE_UNAVAILABLE(false, consumesAttempt = false, timer = false), PERMISSION_UNAVAILABLE(false, consumesAttempt = false, timer = false),
    STORAGE_FULL(false, consumesAttempt = false, timer = false),
    INTERRUPTED(false), CANCELLED(false, consumesAttempt = false, timer = false), IO_ERROR(false), RESOURCE_EXHAUSTED(false), TIMEOUT(false),
}

inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

/** What the shared renderer needs: the file name is the artwork's identity, so a replaced cover is a new cache key. */
data class CoverRef(val libraryItemId: String, val fileName: String, val widthPx: Int, val heightPx: Int)

/** The details pane's technical provenance line; a null field is an unknown stored value. */
data class CoverProvenance(val source: CoverLocalSource?, val representation: CoverRepresentation?)

/** A READY row as the cover map reads it; null fields mean an unreadable value (never displayed). */
data class ReadyCover(val libraryItemId: String, val slot: CoverSlot?, val fileName: String?, val widthPx: Int?, val heightPx: Int?)

/**
 * Slot resolution (D21): USER > ONLINE > LOCAL, READY rows only, unknown slots ignored. 4.5A.1 writes LOCAL only; the
 * ONLINE eligibility gate (§17) arrives with the first provider slice.
 */
fun resolveCovers(rows: List<ReadyCover>): Map<String, CoverRef> = rows.asSequence()
    .filter { it.slot != null && it.fileName != null && (it.widthPx ?: 0) > 0 && (it.heightPx ?: 0) > 0 }
    .groupBy { it.libraryItemId }
    .mapValues { (id, slots) ->
        val best = listOf(CoverSlot.USER, CoverSlot.ONLINE, CoverSlot.LOCAL).firstNotNullOf { s -> slots.firstOrNull { it.slot == s } }
        CoverRef(id, best.fileName!!, best.widthPx!!, best.heightPx!!)
    }

/** The single page an image-sequence cover comes from (§13, §19.2 rule 4): a valid FrontCover, else the first logical page. */
data class ArchiveCoverChoice(val pageIndex: Int, val source: CoverLocalSource, val choice: CoverSourceChoice)

/** ComicInfo `Page Image` is treated as a 0-based index into the logical page list (plan §13; index base unverified). */
fun chooseArchiveCover(frontCoverImage: Int?, pageCount: Int): ArchiveCoverChoice? = when {
    pageCount <= 0 -> null
    frontCoverImage != null && frontCoverImage in 0 until pageCount ->
        ArchiveCoverChoice(frontCoverImage, CoverLocalSource.COMICINFO_FRONT_COVER, CoverSourceChoice.DECLARED)
    else -> ArchiveCoverChoice(0, CoverLocalSource.ARCHIVE_FIRST_PAGE, CoverSourceChoice.INFERRED)
}

/** Attempt counters carried by the LOCAL row (§23.3, §23.5). */
data class CoverAttemptState(val attempts: Int, val interruptions: Int, val parked: Boolean, val nextEligibleAt: Long?)

/**
 * The single retry schedule (§23.3): attempt 1 immediate, retries after ~1/4/16 min ±20% jitter, at most 4 attempts per
 * trigger window; parked at attempts >= 4 OR interruptions >= 2, whichever first. A launch is never a trigger.
 */
object CoverRetryPolicy {
    const val MAX_ATTEMPTS = 4
    const val MAX_INTERRUPTIONS = 2
    private const val MINUTE = 60_000L
    private val NOMINAL_DELAYS = longArrayOf(1 * MINUTE, 4 * MINUTE, 16 * MINUTE)
    const val JITTER = 0.2
    /** The largest SINGLE delay (16 min x 1.2 = 19.2 min): the clock-skew clamp. Not the 25.2 min window bound. */
    val MAX_SINGLE_DELAY_MILLIS: Long = (16 * MINUTE * (1 + JITTER)).toLong()

    fun shouldPark(attempts: Int, interruptions: Int) = attempts >= MAX_ATTEMPTS || interruptions >= MAX_INTERRUPTIONS

    /** Delay before the attempt following attempt number [attempts] (1-based), jittered. */
    fun delayAfter(attempts: Int, random: Random): Long {
        val nominal = NOMINAL_DELAYS[(attempts - 1).coerceIn(0, NOMINAL_DELAYS.lastIndex)]
        return (nominal * (1 + JITTER * (random.nextDouble() * 2 - 1))).toLong()
    }

    /** Explained close of an attempt (the attempt was already counted at begin). */
    fun afterFailure(state: CoverAttemptState, reason: CoverFailureReason, now: Long, random: Random): CoverAttemptState {
        val attempts = if (reason.consumesAttempt) state.attempts else (state.attempts - 1).coerceAtLeast(0)
        return when {
            reason.permanent || (reason.consumesAttempt && shouldPark(attempts, state.interruptions)) ->
                CoverAttemptState(attempts, state.interruptions, parked = true, nextEligibleAt = null)
            reason.timer -> CoverAttemptState(attempts, state.interruptions, false, now + delayAfter(attempts, random))
            else -> CoverAttemptState(attempts, state.interruptions, false, null)
        }
    }

    /** An attempt marker found open at startup: an unexplained interruption, never eligible immediately. */
    fun afterInterruption(state: CoverAttemptState, now: Long, random: Random): CoverAttemptState {
        val interruptions = state.interruptions + 1
        return if (shouldPark(state.attempts, interruptions)) CoverAttemptState(state.attempts, interruptions, true, null)
        else CoverAttemptState(state.attempts, interruptions, false, now + delayAfter(state.attempts.coerceAtLeast(1), random))
    }

    /** A persisted not-before time later than now + the largest single delay means the clock moved backwards. */
    fun clamp(nextEligibleAt: Long?, now: Long): Long? = nextEligibleAt?.coerceAtMost(now + MAX_SINGLE_DELAY_MILLIS)

    /** Automatic eligibility: never while parked; never before the persisted not-before time. */
    fun eligible(state: CoverAttemptState, now: Long) = !state.parked && (clamp(state.nextEligibleAt, now) ?: Long.MIN_VALUE) <= now
}

/** Storage bounds (D12/D20, §7 rule 1, §18.1). Numbers other than the approved 512 KiB / 512 MiB are reviewable. */
object CoverStoragePolicy {
    const val MAX_COVER_BYTES = 512L * 1024
    const val MAX_EDGE_PX = 1200
    const val MIN_EDGE_PX = 800
    /** Input header pixel cap: larger art is rejected before allocation (UNSUPPORTED_REPRESENTATION). */
    const val MAX_INPUT_PIXELS = 40_000_000L
    /** Decoded working-bitmap cap (ARGB_8888 ≈ 16 MiB) before the final downscale. */
    const val MAX_DECODE_PIXELS = 4_000_000L
    const val RESERVE_FREE_BYTES = 512L * 1024 * 1024
    /** Proposed fixed temporary reserve for an unknown/implausible CBR declared entry size (§18.1). */
    const val UNKNOWN_TEMP_RESERVE_BYTES = 32L * 1024 * 1024
    const val RECOVERY_HYSTERESIS_BYTES = 64L * 1024 * 1024
    /** The fixed, bounded encode ladder: (longest edge, JPEG quality); never re-encodes an encoded file. */
    val LADDER = listOf(1200 to 90, 1200 to 80, 1200 to 70, 1000 to 70, MIN_EDGE_PX to 70)

    /** Byte-exact storage only when small, within the edge bound and the input pixel cap. */
    fun storesOriginal(bytes: Long, width: Int, height: Int) =
        bytes in 1..MAX_COVER_BYTES && maxOf(width, height) <= MAX_EDGE_PX && width.toLong() * height <= MAX_INPUT_PIXELS

    /** Temporary bytes a CBR entry extraction may need: the declared size capped by [ceiling], else the fixed reserve. */
    fun temporaryNeed(declaredBytes: Long?, ceiling: Long): Long =
        if (declaredBytes == null || declaredBytes <= 0 || declaredBytes > ceiling) UNKNOWN_TEMP_RESERVE_BYTES else declaredBytes

    /** At least 512 MiB must remain after the artifact and the temporary bytes ([extra] adds recovery hysteresis). */
    fun admits(freeBytes: Long, temporaryBytes: Long, extra: Long = 0) =
        freeBytes - MAX_COVER_BYTES - temporaryBytes - extra >= RESERVE_FREE_BYTES

    /** Power-of-two subsampling so the decoded bitmap stays within [maxPixels] and is not needlessly above [targetEdge]. */
    fun sampleSize(width: Int, height: Int, targetEdge: Int, maxPixels: Long = MAX_DECODE_PIXELS): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= targetEdge) sample *= 2
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) sample *= 2
        return sample
    }
}
