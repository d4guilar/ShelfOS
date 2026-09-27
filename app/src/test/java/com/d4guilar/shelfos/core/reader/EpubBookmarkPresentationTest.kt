// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2B.2.1: bookmark location presentation. A bookmark should read as a place in a book, not merely a progress
 * meter, without ever fabricating a chapter or a "Location N" that cannot actually be determined — mirroring
 * [matchChapter]/[sameEpubBookmarkLocation]'s own honesty contracts. [resolveEpubLocation], [toEpubPositions], and
 * the two formatting functions are pure and Readium-free, so they are directly unit-testable without a real
 * Locator/Publication; only the trivial field-extraction step that builds [RawEpubPosition] from a real Readium
 * `Locator` (`EpubSession.epubPositions`) and `presentBookmark`'s `Locator.fromJSON` parsing remain instrumented-
 * only, covered instead by `EpubBookmarkLocationInstrumentedTest` against a real fixture EPUB, since JVM unit
 * tests in this project cannot exercise `org.json.JSONObject`/Readium `Locator` construction (no Robolectric,
 * matching every other Room/Readium-adjacent test here).
 */
class EpubBookmarkPresentationTest {
    // --- resolveEpubLocation: segment-start / floor semantics (Codex R2) ---
    // Shared fixture for cases A-G: one resource, three segment starts at 0.0 (pos 1), 0.4 (pos 2), 0.8 (pos 3).
    private val abcdefgPositions = listOf(
        EpubPosition("chapter.xhtml", progression = 0.0, position = 1),
        EpubPosition("chapter.xhtml", progression = 0.4, position = 2),
        EpubPosition("chapter.xhtml", progression = 0.8, position = 3),
    )
    private fun resolve(progression: Double) = resolveEpubLocation(abcdefgPositions, EpubBookmarkLocation("chapter.xhtml", progression = progression))

    @Test fun a_exactFirstBoundaryResolvesToTheFirstSegment() = assertEquals(1, resolve(0.0))
    @Test fun b_betweenFirstAndSecondResolvesToTheFirstSegment() = assertEquals(1, resolve(0.2))
    @Test fun c_exactMiddleBoundaryResolvesToTheMiddleSegment() = assertEquals(2, resolve(0.4))
    /** The critical regression Codex flagged: the old "nearest" implementation picked segment 3 (starts at 0.8,
     * numerically closer to 0.7) instead of the segment the bookmark is actually inside (starts at 0.4). */
    @Test fun d_betweenMiddleAndFinalResolvesToTheMiddleSegmentNotTheNearestOne() = assertEquals(2, resolve(0.7))
    @Test fun e_exactFinalBoundaryResolvesToTheFinalSegment() = assertEquals(3, resolve(0.8))
    @Test fun f_nearResourceEndResolvesToTheFinalSegment() = assertEquals(3, resolve(0.99))
    @Test fun g_progressionOneResolvesToTheFinalValidSegment() = assertEquals(3, resolve(1.0))

    @Test fun h_negativeProgressionIsUnresolved() = assertNull(resolve(-0.001))
    @Test fun i_progressionAboveOneIsUnresolved() = assertNull(resolve(1.001))
    @Test fun j_nanProgressionIsUnresolved() = assertNull(resolve(Double.NaN))
    @Test fun j2_infiniteProgressionIsUnresolved() = assertNull(resolve(Double.POSITIVE_INFINITY))
    @Test fun k_missingProgressionIsUnresolved() =
        assertNull(resolveEpubLocation(abcdefgPositions, EpubBookmarkLocation("chapter.xhtml", progression = null)))
    /** The old implementation resolved a lone same-resource candidate even without a progression to compare —
     * that special case is deliberately removed: a missing progression is missing evidence regardless of how many
     * candidates exist. */
    @Test fun k2_missingProgressionIsUnresolvedEvenWithASingleCandidateResource() {
        val onlyOne = listOf(EpubPosition("solo.xhtml", progression = 0.0, position = 1))
        assertNull(resolveEpubLocation(onlyOne, EpubBookmarkLocation("solo.xhtml", progression = null)))
    }
    @Test fun l_unmatchedHrefIsUnresolved() = assertNull(resolveEpubLocation(abcdefgPositions, EpubBookmarkLocation("other.xhtml", progression = 0.5)))

    /** Global, one-based numbering is preserved and never reset per resource: the second resource's segments
     * continue numbering from where the first resource's left off. */
    @Test fun m_globalOneBasedNumberingIsPreservedAcrossResources() {
        val positions = listOf(
            EpubPosition("chapter1.xhtml", progression = 0.0, position = 1),
            EpubPosition("chapter1.xhtml", progression = 0.5, position = 2),
            EpubPosition("chapter2.xhtml", progression = 0.0, position = 3),
            EpubPosition("chapter2.xhtml", progression = 0.5, position = 4),
        )
        assertEquals(3, resolveEpubLocation(positions, EpubBookmarkLocation("chapter2.xhtml", progression = 0.0)))
        assertEquals(4, resolveEpubLocation(positions, EpubBookmarkLocation("chapter2.xhtml", progression = 0.5)))
    }

    /** Candidates with an invalid progression (should not occur in a real Readium catalog, but not assumed) are
     * ignored rather than trusted — the valid candidate for the same resource still resolves normally. */
    @Test fun invalidCandidatePositionsAreIgnoredRatherThanTrusted() {
        val positions = listOf(
            EpubPosition("chapter.xhtml", progression = Double.NaN, position = 1),
            EpubPosition("chapter.xhtml", progression = 0.2, position = 2),
        )
        assertEquals(2, resolveEpubLocation(positions, EpubBookmarkLocation("chapter.xhtml", progression = 0.9)))
    }

    /** When two positions for the same resource share the exact maximal qualifying progression (should not occur
     * in a real Readium catalog, but not assumed), resolution is still deterministic: the earlier-encountered
     * (lower, global-position-ordered) one wins rather than an arbitrary one. */
    @Test fun tiedFloorCandidatesResolveDeterministicallyToTheEarlierPosition() {
        val positions = listOf(
            EpubPosition("chapter.xhtml", progression = 0.0, position = 1),
            EpubPosition("chapter.xhtml", progression = 0.4, position = 2),
            EpubPosition("chapter.xhtml", progression = 0.4, position = 3),
        )
        assertEquals(2, resolveEpubLocation(positions, EpubBookmarkLocation("chapter.xhtml", progression = 0.6)))
    }

    /** A target enriched with a `position` value (simulating Readium enriching the same logical locator after it
     * was stored) must resolve identically to the minimal target, since resolution only ever compares resource and
     * progression — never the target's own (possibly absent, possibly later-added) position field. */
    @Test fun anEnrichedTargetResolvesToTheSameLocationAsTheMinimalOne() {
        val positions = listOf(
            EpubPosition("chapter1.xhtml", progression = 0.0, position = 1),
            EpubPosition("chapter1.xhtml", progression = 0.4, position = 2),
        )
        val minimal = EpubBookmarkLocation("chapter1.xhtml", progression = 0.4)
        val enriched = EpubBookmarkLocation("chapter1.xhtml", progression = 0.4, position = 999)
        assertEquals(resolveEpubLocation(positions, minimal), resolveEpubLocation(positions, enriched))
    }

    // --- toEpubPositions: dropping malformed/incomplete catalog entries (Codex R3, final round) ---

    @Test fun aCatalogEntryMissingProgressionIsDroppedRatherThanDefaultedToZero() {
        val raw = listOf(RawEpubPosition("chapter1.xhtml", position = 1, progression = null))
        assertEquals(emptyList<EpubPosition>(), toEpubPositions(raw))
    }

    @Test fun aCatalogEntryMissingPositionIsAlsoDropped() {
        val raw = listOf(RawEpubPosition("chapter1.xhtml", position = null, progression = 0.5))
        assertEquals(emptyList<EpubPosition>(), toEpubPositions(raw))
    }

    /** Before this fix, a missing progression defaulted to 0.0 — indistinguishable from a genuine first-segment
     * start, so it could win resolveEpubLocation's floor comparison and become "Location 1" for any bookmark. */
    @Test fun aDroppedEntryCannotBecomeLocationOneByDefault() {
        val raw = listOf(
            RawEpubPosition("chapter1.xhtml", position = 1, progression = null),
            RawEpubPosition("chapter1.xhtml", position = 2, progression = 0.5),
        )
        val positions = toEpubPositions(raw)
        assertEquals(listOf(2), positions.map { it.position })
        assertNull(resolveEpubLocation(positions, EpubBookmarkLocation("chapter1.xhtml", progression = 0.0)))
    }

    @Test fun anotherValidCandidateStillResolvesCorrectlyWhenAMalformedEntryIsAlsoPresent() {
        val raw = listOf(
            RawEpubPosition("chapter1.xhtml", position = 1, progression = null),
            RawEpubPosition("chapter1.xhtml", position = 2, progression = 0.5),
        )
        val positions = toEpubPositions(raw)
        assertEquals(2, resolveEpubLocation(positions, EpubBookmarkLocation("chapter1.xhtml", progression = 0.9)))
    }

    @Test fun ifEveryCandidateIsUnusableNoLocationIsProduced() {
        val raw = listOf(
            RawEpubPosition("chapter1.xhtml", position = 1, progression = null),
            RawEpubPosition("chapter1.xhtml", position = null, progression = 0.5),
        )
        val positions = toEpubPositions(raw)
        assertTrue(positions.isEmpty())
        assertNull(resolveEpubLocation(positions, EpubBookmarkLocation("chapter1.xhtml", progression = 0.5)))
    }

    /** A dropped entry's global position number is never handed to the entry after it — remaining entries keep
     * Readium's own original index, they are not renumbered/compacted around the gap. */
    @Test fun droppingAMalformedEntryDoesNotRenumberRemainingGlobalPositions() {
        val raw = listOf(
            RawEpubPosition("chapter1.xhtml", position = 5, progression = null),
            RawEpubPosition("chapter1.xhtml", position = 6, progression = 0.5),
        )
        assertEquals(listOf(6), toEpubPositions(raw).map { it.position })
    }

    // --- bookmarkDisplayText / bookmarkAccessibilityText ---

    @Test fun chapterLocationAndProgressAreAllShown() {
        val p = EpubBookmarkPresentation(chapterTitle = "Chapter 7", location = 184, progress = 56)
        assertEquals("Chapter 7\nLocation 184 · 56% through book", bookmarkDisplayText(p))
        assertEquals("Chapter 7, Location 184, 56 percent through book", bookmarkAccessibilityText(p))
    }

    @Test fun locationAndProgressWithoutChapter() {
        val p = EpubBookmarkPresentation(chapterTitle = null, location = 184, progress = 56)
        assertEquals("Location 184 · 56% through book", bookmarkDisplayText(p))
        assertEquals("Location 184, 56 percent through book", bookmarkAccessibilityText(p))
    }

    @Test fun chapterAndProgressWithoutLocation() {
        val p = EpubBookmarkPresentation(chapterTitle = "Chapter 7", location = null, progress = 56)
        assertEquals("Chapter 7\n56% through book", bookmarkDisplayText(p))
        assertEquals("Chapter 7, 56 percent through book", bookmarkAccessibilityText(p))
    }

    @Test fun progressOnlyFallbackWhenNeitherChapterNorLocationIsAvailable() {
        val p = EpubBookmarkPresentation(chapterTitle = null, location = null, progress = 56)
        assertEquals("56% through book", bookmarkDisplayText(p))
        assertEquals("56 percent through book", bookmarkAccessibilityText(p))
    }

    /** A malformed/unparseable stored locator yields this same presentation upstream (see
     * `EpubSession.presentBookmark`'s `runCatching`), so formatting it must not crash or show anything misleading. */
    @Test fun missingOrInvalidLocatorFormatsAsTheProgressOnlyFallback() {
        val p = EpubBookmarkPresentation(chapterTitle = null, location = null, progress = 0)
        assertEquals("0% through book", bookmarkDisplayText(p))
        assertEquals("0 percent through book", bookmarkAccessibilityText(p))
    }

    @Test fun sameLogicalBookmarkFormatsConsistentlyOnRepeatedCalls() {
        val p = EpubBookmarkPresentation(chapterTitle = "Chapter 7", location = 184, progress = 56)
        assertEquals(bookmarkDisplayText(p), bookmarkDisplayText(p))
        assertEquals(bookmarkAccessibilityText(p), bookmarkAccessibilityText(p))
    }

    @Test fun neverFabricatesAPageNumberForAReflowableEpub() {
        val presentations = listOf(
            EpubBookmarkPresentation("Chapter 7", 184, 56),
            EpubBookmarkPresentation(null, 184, 56),
            EpubBookmarkPresentation("Chapter 7", null, 56),
            EpubBookmarkPresentation(null, null, 56),
        )
        presentations.forEach { p ->
            assertTrue(bookmarkDisplayText(p), !bookmarkDisplayText(p).contains("Page", ignoreCase = true))
            assertTrue(bookmarkAccessibilityText(p), !bookmarkAccessibilityText(p).contains("Page", ignoreCase = true))
        }
    }
}
