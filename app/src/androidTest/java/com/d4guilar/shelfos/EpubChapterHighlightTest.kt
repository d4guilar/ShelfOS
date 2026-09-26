// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 2B.1: the Chapters dialog's current-chapter highlight, chapter jump and the (above-threshold) chapter
 * title filter, against a real 10-chapter EPUB (`OriginalFixtures.epubWithChapters`) opened and navigated through
 * the real Readium navigator/`EpubSession` wiring — the matching *algorithm* itself is covered by
 * `EpubChapterMatchTest` (plain JVM); this class exists to prove the real `Publication.locatorFromLink`/
 * `navigator.currentLocator` values actually agree with each other in practice, which a JVM test cannot exercise.
 */
class EpubChapterHighlightTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    @Before fun seedOriginalEpub() = runBlocking<Unit> { container.library.add(OriginalFixtures.epubWithChapters(context)) }
    @After fun removeOriginalEpub() = runBlocking<Unit> { container.library.remove("test-epub-chapters") }

    private fun awaitReader() = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    /** A chapter row's title exactly — not a substring match, since e.g. "Chapter 1" is a substring of both
     * "Chapter 10" and the filter field's own typed text (which could even equal a title exactly, hence also
     * excluding the field's own `SetText` action). The checkmark is its own sibling node when current (see
     * EpubActivity), so this matches the title whether or not the row is currently marked. */
    private fun chapterRow(title: String) = hasText(title) and hasClickAction() and !hasSetTextAction()

    @Test fun currentChapterIsMarkedAndUpdatesAfterNavigatingElsewhere() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            // A fresh item has no persisted locator, so the reader opens at the first spine item, Chapter 1.
            compose.onNodeWithText("Chapters").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Chapter 1, current chapter").fetchSemanticsNodes().isNotEmpty() }
            // Tapping/activating another chapter navigates exactly as before this slice, then closes the dialog.
            compose.onNode(chapterRow("Chapter 5")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size == 1 }
            // Reopen: the current chapter recomputes from the resulting locator, not from stored state.
            compose.onNodeWithText("Chapters").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Chapter 5, current chapter").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Chapter 1, current chapter").assertCountEquals(0)
        }
    }

    @Test fun chapterTitleFilterNarrowsClearsAndHandlesNoMatches() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            compose.onNodeWithText("Chapters").performClick()
            // The fixture's 10 entries are above CHAPTER_FILTER_THRESHOLD, so the filter field must be present.
            compose.onNodeWithContentDescription("Filter chapters").assertExists()
            compose.onNodeWithTag("chapters_list").performScrollToNode(chapterRow("Chapter 10"))
            compose.onNodeWithContentDescription("Filter chapters").performTextInput("Chapter 7")
            compose.onNode(chapterRow("Chapter 7")).assertExists()
            compose.onAllNodes(chapterRow("Chapter 1")).assertCountEquals(0)
            compose.onAllNodes(chapterRow("Chapter 10")).assertCountEquals(0)
            // Clearing restores the full, original-order list.
            compose.onNodeWithContentDescription("Filter chapters").performTextClearance()
            compose.onNode(chapterRow("Chapter 1")).assertExists()
            compose.onNodeWithTag("chapters_list").performScrollToNode(chapterRow("Chapter 10"))
            // A query matching nothing is a plain, non-error empty state, not a crash or a stale list.
            compose.onNodeWithContentDescription("Filter chapters").performTextInput("no such chapter")
            compose.onNodeWithText("No chapters match “no such chapter”.").assertExists()
            compose.onAllNodes(chapterRow("Chapter 1")).assertCountEquals(0)
        }
    }
}
