// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.presentBookmark
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2B.2.1: proves `EpubSession.epubPositions()`/`presentBookmark` against a real fixture EPUB opened through
 * the real Readium pipeline — the pure resolution/formatting logic itself (`resolveEpubLocation`,
 * `bookmarkDisplayText`, `bookmarkAccessibilityText`) is covered by `EpubBookmarkPresentationTest` (plain JVM);
 * this class exists to prove the real `Publication.positions()` boundary (wired by the pinned Readium 3.4.0
 * `EpubParser`'s default `EpubPositionsService`) actually behaves the way that logic assumes: a stable, offline,
 * global position list a stored locator can be resolved against — which a synthetic-string JVM test cannot show.
 */
class EpubBookmarkLocationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    /** A Readium 3.4 Locator JSON for a resource at a given resource-relative progression, matching the shape the
     * real navigator itself produces (see `EpubSurface`'s `onLocation`/`currentLocatorJson`). [position]/
     * [totalProgression]/[title] simulate fields Readium may enrich the same logical locator with after it was
     * originally stored, without moving the reader (see `EpubBookmarkLocationTest`'s equivalence contract). */
    private fun locatorJson(href: String, progression: Double, position: Int? = null, totalProgression: Double? = null, title: String? = null): String {
        val locations = buildString {
            append("\"progression\":$progression")
            position?.let { append(",\"position\":$it") }
            totalProgression?.let { append(",\"totalProgression\":$it") }
        }
        val titleField = title?.let { ""","title":"$it"""" } ?: ""
        return """{"href":"$href","type":"application/xhtml+xml"$titleField,"locations":{$locations}}"""
    }

    /**
     * Empirically confirmed here (2026-09-27, and re-confirmed via `javap` decompilation of
     * `ArchiveEntryLength.positionCount`): the pinned Readium 3.4.0 `ArchiveEntryLength` strategy's "1024
     * bytes/position" prefers each resource's archive-*stored* entry length (`ArchiveProperties.entryLength`,
     * typically its DEFLATE-compressed size in a real zip-backed EPUB) when the container reports one, falling
     * back to the resource's own decoded length (`Resource.length()`) otherwise — not universally "the compressed
     * length." This fixture's chapters are ~40 paragraphs of highly repetitive text (`"Chapter N paragraph
     * M..."`), which compresses to well under 1024 bytes each, so every chapter here yields exactly one position
     * (10 chapters -> 10 positions) rather than several. The multi-position-per-resource, floor/segment-start
     * disambiguation path in [resolveEpubLocation] is proven both against synthetic [EpubPosition] lists in
     * `EpubBookmarkPresentationTest` and, below, against a real, deliberately poorly-compressible fixture resource
     * (`epubWithLongChapter`) that does yield several real positions.
     */
    @Test fun positionsAreOfflineStableAndCoverTheWholeReadingOrder() = runBlocking<Unit> {
        val item = OriginalFixtures.epubWithChapters(context)
        container.epubs.open(item).use { session ->
            val positions = session.epubPositions()
            assertEquals(10, positions.size)
            // Readium's own global position numbering: 1-based, contiguous, strictly increasing across the whole
            // reading order (never reset per resource).
            assertEquals(1, positions.first().position)
            assertEquals(positions.map { it.position }, (1..positions.size).toList())
            // One position per chapter here (see this test's own doc comment), in reading order.
            val expectedHrefs = (1..10).map { "chapter$it.xhtml" }
            assertEquals(expectedHrefs, positions.map { it.resource.substringAfterLast('/') })

            // Computed once, memoized: a second call returns the identical (reference-equal) list rather than
            // recomputing, matching the "compute at most once per session" contract in EpubSession's doc comment.
            assertSame(positions, session.epubPositions())
        }
    }

    /** Proves, against the real fixture (not a fabricated position list), that a bookmark in a later reading-order
     * resource resolves successfully, that its Location is greater than 1, and that global numbering therefore did
     * not reset for this resource (Codex R3 real-fixture strengthening). */
    @Test fun aBookmarkInALaterResourceResolvesWithoutResettingGlobalNumbering() = runBlocking<Unit> {
        val item = OriginalFixtures.epubWithChapters(context)
        container.epubs.open(item).use { session ->
            val positions = session.epubPositions()
            val presentation = session.presentBookmark(locatorJson("chapter5.xhtml", 0.0), progress = 40, positions)
            assertEquals("Chapter 5", presentation.chapterTitle)
            assertEquals(5, presentation.location)
            assertTrue(requireNotNull(presentation.location) > 1)
        }
    }

    /** Proves [resolveEpubLocation]'s floor/segment-start semantics against a *real* Readium-computed position
     * catalog, not only the synthetic `EpubPosition` lists in `EpubBookmarkPresentationTest`: using
     * `epubWithLongChapter`'s high-entropy content (which, unlike `epubWithChapters`, does not compress down to one
     * position), reads the real segment starts Readium actually computed for this resource, picks a bookmark
     * progression strictly between two consecutive real segment starts, and confirms resolution selects the
     * earlier (containing) segment — never the numerically nearer one. This is the same regression Codex flagged
     * (`EpubBookmarkPresentationTest.d_betweenMiddleAndFinalResolvesToTheMiddleSegmentNotTheNearestOne`), now
     * demonstrated end-to-end against real data. */
    @Test fun aLongResourceYieldsMultipleRealPositionsAndFloorSemanticsHoldAgainstRealData() = runBlocking<Unit> {
        val item = OriginalFixtures.epubWithLongChapter(context)
        container.epubs.open(item).use { session ->
            val positions = session.epubPositions()
            val chapterPositions = positions.filter { it.resource.substringAfterLast('/') == "chapter1.xhtml" }.sortedBy { it.progression }
            assertTrue("expected several real positions within one high-entropy resource, got ${chapterPositions.size}", chapterPositions.size >= 3)
            assertEquals((1..positions.size).toList(), positions.map { it.position })

            val second = chapterPositions[1]
            val third = chapterPositions[2]
            val between = (second.progression + third.progression) / 2
            val presentation = session.presentBookmark(locatorJson("chapter1.xhtml", between), progress = 50, positions)
            assertEquals(second.position, presentation.location)
        }
    }

    @Test fun aBookmarkAtTheStartOfTheFirstChapterResolvesToTheFirstLocation() = runBlocking<Unit> {
        val item = OriginalFixtures.epubWithChapters(context)
        container.epubs.open(item).use { session ->
            val positions = session.epubPositions()
            val presentation = session.presentBookmark(locatorJson("chapter1.xhtml", 0.0), progress = 0, positions)
            assertEquals("Chapter 1", presentation.chapterTitle)
            assertEquals(1, presentation.location)
        }
    }

    /** A locator enriched with fields Readium may add later (title, totalProgression, an unrelated position value)
     * must resolve to the identical location as the minimal one Add-time actually stores — proving, against a real
     * fixture, that resolution genuinely never depends on those transient fields (see
     * `EpubBookmarkPresentationTest.anEnrichedTargetResolvesToTheSameLocationAsTheMinimalOne` for the pure-logic
     * proof this exercises end-to-end). */
    @Test fun anEnrichedLocatorAtTheSameLocationResolvesToTheSameRealLocation() = runBlocking<Unit> {
        val item = OriginalFixtures.epubWithChapters(context)
        container.epubs.open(item).use { session ->
            val positions = session.epubPositions()
            val minimal = session.presentBookmark(locatorJson("chapter3.xhtml", 0.5), progress = 45, positions)
            val enriched = session.presentBookmark(
                locatorJson("chapter3.xhtml", 0.5, position = 999999, totalProgression = 0.25, title = "Chapter 3"),
                progress = 45, positions,
            )
            assertNotNull(minimal.location)
            assertEquals(minimal.location, enriched.location)
            assertEquals(minimal.chapterTitle, enriched.chapterTitle)
        }
    }

    @Test fun aMalformedLocatorResolvesToTheProgressOnlyFallbackRatherThanCrashing() = runBlocking<Unit> {
        val item = OriginalFixtures.epubWithChapters(context)
        container.epubs.open(item).use { session ->
            val presentation = session.presentBookmark("not a valid locator", progress = 12, session.epubPositions())
            assertNull(presentation.chapterTitle)
            assertNull(presentation.location)
            assertEquals(12, presentation.progress)
        }
    }
}
