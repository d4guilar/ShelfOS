// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.reader.ExtractedCover
import com.d4guilar.shelfos.data.library.requireValidReady
import com.d4guilar.shelfos.data.library.throttleLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Phase 4.5A.1 pure cover policy: retry schedule and park rule (§23.3), slot resolution, page choice, storage bounds. */
class CoverPolicyTest {
    private val minute = 60_000L
    private fun fixed(value: Double) = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = value
    }
    private val fresh = CoverAttemptState(0, 0, false, null)

    @Test fun retryDelaysAreOneFourSixteenMinutesWithTwentyPercentJitter() {
        for ((attempt, nominal) in listOf(1 to minute, 2 to 4 * minute, 3 to 16 * minute)) {
            assertEquals((nominal * 0.8).toLong(), CoverRetryPolicy.delayAfter(attempt, fixed(0.0)))
            assertEquals(nominal, CoverRetryPolicy.delayAfter(attempt, fixed(0.5)))
            assertEquals((nominal * 1.2).toLong(), CoverRetryPolicy.delayAfter(attempt, fixed(1.0)))
        }
        val seeded = Random(7)
        repeat(1000) { val d = CoverRetryPolicy.delayAfter(2, seeded); assertTrue(d in (3.2 * minute).toLong()..(4.8 * minute).toLong()) }
    }

    @Test fun temporaryFailuresBackOffThenTheFourthAttemptParksWithNoTimer() {
        val now = 1_000_000L
        var state = fresh
        for (attempt in 1..3) {
            state = CoverRetryPolicy.afterFailure(state.copy(attempts = attempt), CoverFailureReason.IO_ERROR, now, fixed(0.5))
            assertFalse(state.parked); assertEquals(attempt, state.attempts)
            assertEquals(now + CoverRetryPolicy.delayAfter(attempt, fixed(0.5)), state.nextEligibleAt)
        }
        state = CoverRetryPolicy.afterFailure(state.copy(attempts = 4), CoverFailureReason.TIMEOUT, now, fixed(0.5))
        assertTrue(state.parked); assertNull(state.nextEligibleAt)
        // A parked item never becomes eligible because time passed.
        assertFalse(CoverRetryPolicy.eligible(state, Long.MAX_VALUE / 2))
    }

    @Test fun twoUnexplainedInterruptionsParkBeforeTheAttemptCap() {
        val now = 5_000L
        val first = CoverRetryPolicy.afterInterruption(CoverAttemptState(1, 0, false, null), now, fixed(0.0))
        assertEquals(1, first.interruptions); assertFalse(first.parked)
        assertTrue("never eligible at startup", first.nextEligibleAt!! > now)
        assertFalse(CoverRetryPolicy.eligible(first, now))
        val second = CoverRetryPolicy.afterInterruption(first.copy(attempts = 2), now, fixed(0.0))
        assertTrue(second.parked); assertNull(second.nextEligibleAt); assertEquals(2, second.attempts)
        // An interrupted attempt 4 parks by the attempt count even with no earlier interruption.
        assertTrue(CoverRetryPolicy.afterInterruption(CoverAttemptState(4, 0, false, null), now, fixed(0.0)).parked)
    }

    @Test fun storageFullCancellationAndUnavailableSourcesGiveTheAttemptBackWithoutTimerOrParking() {
        for (reason in listOf(CoverFailureReason.STORAGE_FULL, CoverFailureReason.CANCELLED, CoverFailureReason.SOURCE_UNAVAILABLE, CoverFailureReason.PERMISSION_UNAVAILABLE)) {
            val after = CoverRetryPolicy.afterFailure(CoverAttemptState(4, 1, false, null), reason, 0, fixed(0.5))
            assertEquals(reason.name, CoverAttemptState(3, 1, false, null), after)
        }
    }

    @Test fun permanentFailuresParkWithoutATimerAndTemporaryOnesNeverBecomePermanent() {
        for (reason in CoverFailureReason.entries) {
            val after = CoverRetryPolicy.afterFailure(CoverAttemptState(1, 0, false, null), reason, 0, fixed(0.5))
            if (reason.permanent) { assertTrue(after.parked); assertNull(after.nextEligibleAt) }
            else assertFalse("$reason must not park on attempt 1", after.parked)
        }
        assertEquals(setOf(CoverFailureReason.NO_SUITABLE_ARTWORK, CoverFailureReason.UNSUPPORTED_REPRESENTATION, CoverFailureReason.UNDECODABLE_CONTENT),
            CoverFailureReason.entries.filter { it.permanent }.toSet())
    }

    @Test fun clockSkewClampIsTheLargestSingleDelay() {
        assertEquals((19.2 * minute).toLong(), CoverRetryPolicy.MAX_SINGLE_DELAY_MILLIS)
        assertEquals(1_000 + CoverRetryPolicy.MAX_SINGLE_DELAY_MILLIS, CoverRetryPolicy.clamp(Long.MAX_VALUE, 1_000))
        assertEquals(5_000L, CoverRetryPolicy.clamp(5_000, 1_000))
    }

    @Test fun slotResolutionPrefersUserThenOnlineThenLocalAndIgnoresUnreadableRows() {
        fun row(id: String, slot: CoverSlot?, file: String?, w: Int? = 10, h: Int? = 15) = ReadyCover(id, slot, file, w, h)
        val map = resolveCovers(listOf(
            row("a", CoverSlot.LOCAL, "local-a"), row("a", CoverSlot.USER, "user-a"), row("a", CoverSlot.ONLINE, "online-a"),
            row("b", CoverSlot.LOCAL, "local-b"), row("b", CoverSlot.ONLINE, "online-b"),
            row("c", null, "future-c"), row("d", CoverSlot.LOCAL, null), row("e", CoverSlot.LOCAL, "local-e", 0, 10),
        ))
        assertEquals("user-a", map["a"]!!.fileName)
        assertEquals("online-b", map["b"]!!.fileName)
        assertEquals(setOf("a", "b"), map.keys)
        assertNull(enumOrNull<CoverSlot>("FUTURE")); assertNull(enumOrNull<CoverStatus>(null)); assertEquals(CoverSlot.LOCAL, enumOrNull<CoverSlot>("LOCAL"))
    }

    @Test fun frontCoverIsUsedOnlyWhenInRangeOtherwiseTheFirstLogicalPage() {
        assertEquals(ArchiveCoverChoice(2, CoverLocalSource.COMICINFO_FRONT_COVER, CoverSourceChoice.DECLARED), chooseArchiveCover(2, 5))
        assertEquals("a FrontCover naming page 0 is still the one declared cover", ArchiveCoverChoice(0, CoverLocalSource.COMICINFO_FRONT_COVER,
            CoverSourceChoice.DECLARED), chooseArchiveCover(0, 5))
        val first = ArchiveCoverChoice(0, CoverLocalSource.ARCHIVE_FIRST_PAGE, CoverSourceChoice.INFERRED)
        for (invalid in listOf(null, -1, 5, Int.MAX_VALUE)) assertEquals(first, chooseArchiveCover(invalid, 5))
        assertNull(chooseArchiveCover(0, 0))
    }

    @Test fun originalOnlyWhenSmallWithinTheEdgeAndPixelBounds() {
        val max = CoverStoragePolicy.MAX_COVER_BYTES
        assertEquals(512L * 1024, max)
        assertTrue(CoverStoragePolicy.storesOriginal(max, 800, 1200))
        assertFalse(CoverStoragePolicy.storesOriginal(max + 1, 800, 1200))
        assertFalse(CoverStoragePolicy.storesOriginal(100_000, 800, 1201))
        assertFalse(CoverStoragePolicy.storesOriginal(0, 800, 1200))
        assertEquals(listOf(1200 to 90, 1200 to 80, 1200 to 70, 1000 to 70, 800 to 70), CoverStoragePolicy.LADDER)
    }

    @Test fun storageGuardCountsTheArtifactAndTemporaryExtractionAgainstTheReserve() {
        val reserve = 512L * 1024 * 1024
        val artifact = CoverStoragePolicy.MAX_COVER_BYTES
        assertTrue(CoverStoragePolicy.admits(reserve + artifact, 0))
        assertFalse(CoverStoragePolicy.admits(reserve + artifact - 1, 0))
        assertFalse(CoverStoragePolicy.admits(reserve + artifact + 10, 11))
        assertFalse("recovery needs the hysteresis margin", CoverStoragePolicy.admits(reserve + artifact, 0, CoverStoragePolicy.RECOVERY_HYSTERESIS_BYTES))
        val ceiling = 128L * 1024 * 1024
        assertEquals(5_000_000L, CoverStoragePolicy.temporaryNeed(5_000_000, ceiling))
        for (unknown in listOf(null, 0L, -1L, ceiling + 1)) assertEquals(32L * 1024 * 1024, CoverStoragePolicy.temporaryNeed(unknown, ceiling))
    }

    @Test fun sampleSizeNeverDecodesBelowTheTargetEdgeAndCapsWorkingPixels() {
        assertEquals(1, CoverStoragePolicy.sampleSize(1000, 1500, 1200))
        assertEquals(2, CoverStoragePolicy.sampleSize(3000, 4500, 1200))
        assertTrue(CoverStoragePolicy.sampleSize(10_000, 10_000, 1200).let { (10_000L / it) * (10_000L / it) <= CoverStoragePolicy.MAX_DECODE_PIXELS })
    }

    private fun cover(rep: CoverRepresentation = CoverRepresentation.DERIVED, source: CoverLocalSource = CoverLocalSource.EPUB_COVER,
        sw: Int = 2000, sh: Int = 3000, w: Int = 800, h: Int = 1200, bytes: Long = 100_000, ext: String = "jpg") =
        ExtractedCover("image/jpeg", ext, rep, sw, sh, w, h, bytes, source, "page:0", CoverSourceChoice.INFERRED, null)

    @Test fun persistenceBoundaryRejectsInvalidReadyCombinations() {
        requireValidReady(CoverSlot.LOCAL, "local-0123456789abcdef.jpg", cover())
        fun rejected(slot: CoverSlot = CoverSlot.LOCAL, name: String = "local-0123456789abcdef.jpg", c: ExtractedCover = cover()) =
            runCatching { requireValidReady(slot, name, c) }.isFailure
        assertTrue("only LOCAL is written in 4.5A.1", rejected(slot = CoverSlot.USER))
        assertTrue(rejected(slot = CoverSlot.ONLINE))
        assertTrue("source-derived names are never accepted", rejected(name = "My Book cover.jpg"))
        assertTrue(rejected(name = "local-0123456789abcdef.png"))
        assertTrue(rejected(c = cover(bytes = CoverStoragePolicy.MAX_COVER_BYTES + 1)))
        assertTrue(rejected(c = cover(w = 1201)))
        assertTrue("ORIGINAL keeps the source dimensions", rejected(c = cover(rep = CoverRepresentation.ORIGINAL)))
        assertTrue("a PDF preview is never ORIGINAL", rejected(c = cover(rep = CoverRepresentation.ORIGINAL, source = CoverLocalSource.PDF_FIRST_PAGE, sw = 800, sh = 1200)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun latestValueThrottleKeepsPublishingDuringAContinuousBackfillWhereDebounceWouldStarve() = runTest {
        val source = flow { for (i in 1..2000) { emit(i); delay(10) } }
        val throttled = source.throttleLatest(300).toList()
        assertEquals("the final state is always delivered", 2000, throttled.last())
        assertTrue("bounded rate: ${throttled.size}", throttled.size <= 20_000 / 300 + 2)
        assertTrue("continuous progress, not one emission at the end", throttled.size >= 20_000 / 300 - 2)
        assertEquals("a debounce emits nothing until the backfill stops", listOf(2000), source.debounce(300).toList())
    }

    /** Safeguard 1: the migration executes exactly the createSql Room exported to 6.json (read as text: org.json is stubbed on the JVM). */
    @Test fun migrationSqlIsTheExportedRoomCreateSql() {
        val json = File("schemas/com.d4guilar.shelfos.core.database.ShelfDatabase/6.json").readText()
        val block = json.substringAfter("\"tableName\": \"publication_cover\"").substringBefore("\"tableName\"")
        val createSql = Regex("\"createSql\": \"([^\"]*)\"").find(block)!!.groupValues[1]
        assertEquals(createSql.replace("\${TABLE_NAME}", "publication_cover"), ShelfDatabase.PUBLICATION_COVER_CREATE_SQL)
        assertFalse(ShelfDatabase.PUBLICATION_COVER_CREATE_SQL.contains("CHECK", ignoreCase = true))
        assertTrue("primary key (libraryItemId, slot)", Regex("\"columnNames\": \\[\\s*\"libraryItemId\",\\s*\"slot\"\\s*]").containsMatchIn(block))
        assertFalse("the composite primary key covers the FK child column; no extra index", block.contains("\"indices\""))
    }
}
