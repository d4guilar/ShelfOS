// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2B.2.1: bookmark location presentation. A bookmark should read as a place in a book, not merely a progress
 * meter, without ever fabricating a chapter or a "Location N" that cannot actually be determined — mirroring
 * [matchChapter]/[sameEpubBookmarkLocation]'s own honesty contracts. [resolveEpubLocation] and the two formatting
 * functions are pure and Readium-free, so they are directly unit-testable without a real Locator/Publication;
 * the Readium-touching boundary (`EpubSession.presentBookmark`/`epubPositions`) is covered instead by
 * `EpubBookmarkLocationInstrumentedTest` against a real fixture EPUB, since JVM unit tests in this project cannot
 * exercise `org.json.JSONObject`/Readium `Locator` parsing (no Robolectric, matching every other Room/Readium-
 * adjacent test here).
 */
class EpubBookmarkPresentationTest {
    // --- resolveEpubLocation ---

    @Test fun resolvesUniquelyWhenExactlyOnePositionSharesTheResource() {
        val positions = listOf(EpubPosition("chapter1.xhtml", progression = 0.0, position = 1))
        assertEquals(1, resolveEpubLocation(positions, EpubBookmarkLocation("chapter1.xhtml")))
    }

    @Test fun noPositionSharesTheResourceIsUnresolved() {
        val positions = listOf(EpubPosition("chapter1.xhtml", progression = 0.0, position = 1))
        assertNull(resolveEpubLocation(positions, EpubBookmarkLocation("chapter2.xhtml", progression = 0.0)))
    }

    @Test fun severalCandidatesWithoutAProgressionToCompareAreUnresolvedRatherThanGuessed() {
        val positions = listOf(
            EpubPosition("chapter1.xhtml", progression = 0.0, position = 1),
            EpubPosition("chapter1.xhtml", progression = 0.5, position = 2),
        )
        assertNull(resolveEpubLocation(positions, EpubBookmarkLocation("chapter1.xhtml", progression = null)))
    }

    @Test fun severalCandidatesResolveToTheClosestProgression() {
        val positions = listOf(
            EpubPosition("chapter1.xhtml", progression = 0.0, position = 1),
            EpubPosition("chapter1.xhtml", progression = 0.4, position = 2),
            EpubPosition("chapter1.xhtml", progression = 0.8, position = 3),
        )
        assertEquals(2, resolveEpubLocation(positions, EpubBookmarkLocation("chapter1.xhtml", progression = 0.5)))
    }

    /** 0.25/0.5/0.75 are exact in binary floating point, so both candidates are genuinely equidistant — not merely
     * "close enough" — making this a real tie rather than an artifact of decimal/double rounding. */
    @Test fun tiedDistancesFavorTheLowerPositionForDeterminism() {
        val positions = listOf(
            EpubPosition("chapter1.xhtml", progression = 0.25, position = 5),
            EpubPosition("chapter1.xhtml", progression = 0.75, position = 9),
        )
        assertEquals(5, resolveEpubLocation(positions, EpubBookmarkLocation("chapter1.xhtml", progression = 0.5)))
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
