// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.FixedPageLocator
import com.d4guilar.shelfos.domain.annotations.KnowledgePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Phase 4C.1 committed-annotation durability across a REAL process death. It is meant to be run as two separate
 * invocations with the app process force-stopped between them (`adb shell am force-stop com.d4guilar.shelfos`):
 * `a_createBookmarkAndNoteThroughTheReaderAndLeaveThemBehind` then `b_annotationsSurviveAndAreShownInTheNotebook`
 * (which cleans up). Run together in one process it only proves a re-read; the report must say which was done.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class FixedAnnotationProcessDeathTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container
    private fun stored() = runBlocking { container.annotations.observeForPublication("test-pdf").first().annotations }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun openReader() {
        awaitTag("library_grid"); awaitTag("publication_test-pdf")
        compose.onNodeWithTag("publication_test-pdf").performClick()
        compose.onNodeWithTag("read_action").performScrollTo().performClick()
        awaitTag("page_number")
    }

    @Test fun a_createBookmarkAndNoteThroughTheReaderAndLeaveThemBehind() = runBlocking<Unit> {
        container.library.add(OriginalFixtures.pdf(context))
        container.annotations.deleteForPublication("test-pdf", KnowledgePolicy.DELETE)
        openReader()
        compose.onNodeWithText("Next").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText("2 / 3")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        compose.waitUntil(10_000) { stored().size == 1 }
        compose.onNodeWithTag("reader_notebook").performScrollTo().performClick()
        awaitTag("notebook_panel")
        compose.onNodeWithTag("notebook_add_note").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").performTextInput("Survives process death")
        compose.onNodeWithTag("annotation_editor_save").performClick()
        compose.waitUntil(10_000) { stored().size == 2 }
        assertEquals(setOf(AnnotationKind.BOOKMARK, AnnotationKind.NOTE), stored().map { it.kind }.toSet())
        // Deliberately NOT cleaned up: the next invocation runs in a fresh process.
    }

    @Test fun b_annotationsSurviveAndAreShownInTheNotebook() = runBlocking<Unit> {
        try {
            val rows = stored()
            assertEquals(2, rows.size)
            assertTrue(rows.all { FixedPageLocator.parse(it.locatorJson) == 1 })
            assertEquals("Survives process death", rows.single { it.kind == AnnotationKind.NOTE }.body)
            openReader()
            compose.onNodeWithTag("reader_notebook").performScrollTo().performClick()
            awaitTag("notebook_panel")
            compose.onAllNodes(hasTestTag("notebook_row")).assertCountEquals(2)
        } finally {
            container.annotations.deleteForPublication("test-pdf", KnowledgePolicy.DELETE)
            container.library.remove("test-pdf")
        }
    }
}
