// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationNavigationTest {
    private fun annotation(kind: AnnotationKind, selected: String?) = Annotation(
        "id", "item", kind, LocatorFormat.READIUM_LOCATOR_1, "{\"href\":\"c.xhtml\"}", 0, null, null, selected,
        if (kind == AnnotationKind.HIGHLIGHT) null else if (kind == AnnotationKind.NOTE) "body" else null,
        if (kind == AnnotationKind.HIGHLIGHT) "amber" else null, 0, 0)

    private val highlight = annotation(AnnotationKind.HIGHLIGHT, "text")
    private val selectionNote = annotation(AnnotationKind.NOTE, "text")
    private val positionNote = annotation(AnnotationKind.NOTE, null)
    private val bookmark = annotation(AnnotationKind.BOOKMARK, null)

    @Test fun textAnchoredAnnotationsNavigateOnlyWhenPlaceable() {
        listOf(highlight, selectionNote).forEach { a ->
            assertEquals(true, a.canNavigate(AnchorStatus.PLACEABLE))
            assertEquals(false, a.canNavigate(AnchorStatus.UNPLACED))
            assertEquals(false, a.canNavigate(AnchorStatus.MALFORMED))
            assertEquals("not yet verified is not proven", false, a.canNavigate(null))
        }
    }

    @Test fun positionNotesAndBookmarksNavigateRegardlessOfTextVerdict() {
        listOf(positionNote, bookmark).forEach { a ->
            (AnchorStatus.entries.map { it as AnchorStatus? } + null).forEach { assertEquals(true, a.canNavigate(it)) }
        }
    }

    @Test fun blockedAnnotationsNeverReachTheNavigator() {
        var calls = 0
        val jump: suspend (String) -> JumpResult = { calls++; JumpResult.ARRIVED }
        listOf(highlight, selectionNote).forEach { a ->
            listOf(AnchorStatus.UNPLACED, AnchorStatus.MALFORMED, null).forEach {
                assertEquals(AnnotationNavigation.BLOCKED, runBlocking { navigateToAnnotation(a, it, jump) })
            }
        }
        assertEquals(0, calls)
        assertEquals(AnnotationNavigation.ARRIVED, runBlocking { navigateToAnnotation(highlight, AnchorStatus.PLACEABLE, jump) })
        assertEquals(AnnotationNavigation.ARRIVED, runBlocking { navigateToAnnotation(positionNote, AnchorStatus.UNPLACED, jump) })
        assertEquals(AnnotationNavigation.ARRIVED, runBlocking { navigateToAnnotation(bookmark, null, jump) })
        assertEquals(3, calls)
        assertEquals(AnnotationNavigation.FAILED, runBlocking { navigateToAnnotation(bookmark, null) { JumpResult.UNAVAILABLE } })
    }
}
