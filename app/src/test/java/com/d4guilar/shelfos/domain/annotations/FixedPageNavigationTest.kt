// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations

import com.d4guilar.shelfos.feature.reader.NotebookJump
import com.d4guilar.shelfos.feature.reader.fixedNotebookEntries
import org.junit.Assert.*
import org.junit.Test

/** Phase 4C.1: strict fixed-page navigation, canonical locator formatting, ordering and toggle rules (pure Kotlin). */
class FixedPageNavigationTest {
    private fun locator(page: Int) = FixedPageLocator.format(page)
    private fun annotation(id: String, kind: AnnotationKind = AnnotationKind.BOOKMARK, json: String, body: String? = null,
        format: LocatorFormat = LocatorFormat.FIXED_PAGE_1) =
        Annotation(id, "item", kind, format, json, 0, null, null, null, body, null, 1L, 1L)

    @Test fun formatIsCanonicalAndRoundTripsThroughTheStrictParser() {
        assertEquals("""{"version":1,"page":0}""", FixedPageLocator.format(0))
        assertEquals("""{"version":1,"page":41}""", FixedPageLocator.format(41))
        for (page in listOf(0, 1, 12, Int.MAX_VALUE)) assertEquals(page, FixedPageLocator.parse(FixedPageLocator.format(page)))
        assertTrue(runCatching { FixedPageLocator.format(-1) }.isFailure)
    }

    @Test fun validLocatorsNavigateOnlyWithinTheOpenPublication() {
        assertEquals(FixedPageNavigation.Navigable(0), fixedPageNavigation(locator(0), 5))
        assertEquals(FixedPageNavigation.Navigable(4), fixedPageNavigation(locator(4), 5))
        assertEquals(FixedPageNavigation.OutOfRange(5, 5), fixedPageNavigation(locator(5), 5))
        assertEquals(FixedPageNavigation.OutOfRange(9999, 5), fixedPageNavigation(locator(9999), 5))
        assertEquals(FixedPageNavigation.Navigable(0), fixedPageNavigation(locator(0), 1))
        assertEquals(FixedPageNavigation.OutOfRange(1, 1), fixedPageNavigation(locator(1), 1))
    }

    @Test fun unknownPageCountIsPendingNeverNavigable() {
        assertEquals(FixedPageNavigation.Pending, fixedPageNavigation(locator(0), 0))
        assertEquals(FixedPageNavigation.Pending, fixedPageNavigation(locator(3), 0))
    }

    @Test fun malformedLocatorsAreRejectedWithoutClamping() {
        val bad = listOf("", "not json", """{"version":1}""", """{"page":2}""", """{"version":2,"page":2}""", """{"version":1,"page":-1}""",
            """{"version":1,"page":1.5}""", """{"version":1,"page":"3"}""", """{"version":1,"page":1e2}""", """{"version":1,"page":2,"x":1}""",
            """{"href":"c1.xhtml"}""", """{"version":1,"page":99999999999}""")
        for (json in bad) {
            assertEquals("should be malformed: $json", FixedPageNavigation.Malformed, fixedPageNavigation(json, 10))
            assertEquals("malformed even while the count is unknown: $json", FixedPageNavigation.Malformed, fixedPageNavigation(json, 0))
        }
    }

    @Test fun orderKeyIsOnTheNormalizedScaleAndDeterministicForOnePage() {
        assertEquals(0.0, fixedPageOrderKey(0, 1), 0.0)
        assertEquals(0.0, fixedPageOrderKey(0, 10), 0.0)
        assertEquals(0.3, fixedPageOrderKey(3, 10), 1e-12)
        assertTrue(fixedPageOrderKey(9, 10) < 1.0)
        assertEquals(0.0, fixedPageOrderKey(0, 0), 0.0)
        assertEquals(1.0, fixedPageOrderKey(50, 10), 0.0) // never leaves 0..1, whatever the inputs
    }

    @Test fun draftsUseTheCanonicalLocatorAndCarryNoTextStyleOrTitle() {
        val bookmark = fixedPageDraft("item", AnnotationKind.BOOKMARK, 3, 10, 40)
        assertEquals(LocatorFormat.FIXED_PAGE_1, bookmark.locatorFormat); assertEquals(locator(3), bookmark.locatorJson)
        assertEquals(0.3, bookmark.orderKey!!, 1e-12)
        assertNull(bookmark.body); assertNull(bookmark.selectedText); assertNull(bookmark.styleKey); assertNull(bookmark.title)
        val note = fixedPageDraft("item", AnnotationKind.NOTE, 0, 1, 100, "Body")
        assertEquals(locator(0), note.locatorJson); assertEquals(0.0, note.orderKey!!, 0.0); assertEquals("Body", note.body)
        AnnotationRules.validate(bookmark, AnnotationLocatorValidator { f, j -> f == LocatorFormat.FIXED_PAGE_1 && FixedPageLocator.isValid(j) })
        AnnotationRules.validate(note, null)
    }

    @Test fun bookmarkToggleTargetsExactlyThePageAndOnlyFixedBookmarks() {
        val list = listOf(
            annotation("a", json = locator(3)), annotation("b", json = locator(3)), annotation("c", json = locator(4)),
            annotation("n", AnnotationKind.NOTE, locator(3), "body"),
            annotation("e", json = """{"href":"c1.xhtml"}""", format = LocatorFormat.READIUM_LOCATOR_1),
            annotation("m", json = """{"version":1}"""))
        assertEquals(listOf("a", "b"), fixedBookmarkIdsAt(list, 3))
        assertEquals(listOf("c"), fixedBookmarkIdsAt(list, 4))
        assertTrue(fixedBookmarkIdsAt(list, 5).isEmpty())
        assertEquals(setOf(3, 4), fixedBookmarkedPages(list))
    }

    @Test fun notebookRowsJumpOnlyWhenNavigableAndShowOneBasedPages() {
        val rows = fixedNotebookEntries(listOf(
            annotation("ok", json = locator(3)),
            annotation("far", AnnotationKind.NOTE, locator(50), "late"),
            annotation("bad", AnnotationKind.NOTE, """{"version":1}""", "orphan")), 10)
        assertEquals(listOf(NotebookJump.AVAILABLE, NotebookJump.UNAVAILABLE, NotebookJump.UNAVAILABLE), rows.map { it.jump })
        assertEquals(listOf<Int?>(4, 51, null), rows.map { it.page })
        assertNull(rows[0].unavailableReason); assertNotNull(rows[1].unavailableReason); assertNotNull(rows[2].unavailableReason)
        assertEquals(listOf(false, true, true), rows.map { it.editable })
        assertEquals(NotebookJump.PENDING, fixedNotebookEntries(listOf(annotation("ok", json = locator(3))), 0).single().jump)
    }
}
