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
 * Phase 2B.2: durable EPUB bookmarks — add at current position, list, jump, delete, duplicate-safe add,
 * recreation and reopen persistence, and malformed-locator handling — against a real 10-chapter EPUB
 * (`OriginalFixtures.epubWithChapters`) and the real Room-backed repository, exercising the same
 * `EpubReaderViewModel`/`EpubActivity` wiring the app itself uses. The bookmark row label reuses 2B.1's chapter
 * matcher, so this also incidentally re-proves that path still works.
 */
class EpubBookmarkTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    @Before fun seedOriginalEpub() = runBlocking<Unit> { container.library.add(OriginalFixtures.epubWithChapters(context)) }
    @After fun removeOriginalEpub() = runBlocking<Unit> { container.library.remove("test-epub-chapters") }

    private fun awaitReader() = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun chapterRow(title: String) = hasText(title) and hasClickAction() and !hasSetTextAction()
    /** Any bookmark row whose derived label mentions [chapterTitle] — "Bookmark, " is unique to a row's own
     * contentDescription (the Add/Delete buttons' descriptions use a lowercase "bookmark" and never this prefix). */
    private fun bookmarkRow(chapterTitle: String) =
        hasContentDescription("Bookmark, ", substring = true) and hasContentDescription(chapterTitle, substring = true) and hasClickAction()
    private fun awaitAddEnabled() = compose.waitUntil(10_000) { compose.onAllNodes(hasText("Add bookmark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    private fun bookmarkCount(n: Int) = compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Bookmark, ", substring = true)).fetchSemanticsNodes().size == n }

    @Test fun addListJumpAndDeleteBookmarksAcrossDialogReopens() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()

            // Empty state, then add at the current (fresh-open) position.
            compose.onNodeWithText("Bookmarks").performClick()
            compose.onNodeWithText("No bookmarks yet.").assertExists()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Bookmarked").fetchSemanticsNodes().isNotEmpty() }
            bookmarkCount(1)
            compose.onNode(bookmarkRow("Chapter 1")).assertExists()
            // Adding again at the same, now-bookmarked position must not create a duplicate row: the Add control
            // itself is disabled once bookmarked, so there is nothing left to (mis)click here — the duplicate-safe
            // repository behavior itself is covered directly by BookmarkPersistenceTest.

            // Close/reopen the dialog: the bookmark is Room-backed, not saved-instance-state.
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Bookmarks").performClick()
            bookmarkCount(1)
            compose.onNodeWithText("Close").performClick()

            // Navigate elsewhere, then add a second bookmark at the new position.
            compose.onNodeWithText("Chapters").performClick()
            compose.onNode(chapterRow("Chapter 5")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").performClick()
            bookmarkCount(2)
            compose.onNode(bookmarkRow("Chapter 5")).assertExists()

            // Jump to the first (lowest-progress) bookmark; the Chapters dialog's own 2B.1 highlight confirms the
            // reader actually moved there, not merely that the Bookmarks dialog closed.
            compose.onNode(bookmarkRow("Chapter 1")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Bookmarks").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Chapters").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Chapter 1, current chapter").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Close").performClick()

            // Reopen: both bookmarks remain, in deterministic (progress-ascending) order.
            compose.onNodeWithText("Bookmarks").performClick()
            bookmarkCount(2)

            // Delete the first (lowest-progress) row; the other remains.
            compose.onAllNodesWithText("Delete")[0].performClick()
            bookmarkCount(1)
            compose.onNode(bookmarkRow("Chapter 5")).assertExists()

            // Delete the last remaining bookmark; a normal, non-error empty state appears.
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No bookmarks yet.").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun bookmarksSurviveActivityRecreation() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use { scenario ->
            awaitReader()
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").performClick()
            bookmarkCount(1)
            compose.onNodeWithText("Close").performClick()

            scenario.recreate()
            awaitReader()
            compose.onNodeWithText("Bookmarks").performClick()
            bookmarkCount(1)
        }
    }

    @Test fun bookmarksSurviveClosingAndReopeningThePublication() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").performClick()
            bookmarkCount(1)
        }
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            compose.onNodeWithText("Bookmarks").performClick()
            bookmarkCount(1)
        }
    }

    /** A bookmark's locator can never be edited by the user in this slice, so a malformed one can only arise from
     * data corruption/a future format change — simulated here by writing one directly through the repository. */
    @Test fun malformedBookmarkLocatorShowsAReadableFailureRatherThanCrashingAndCanStillBeDeleted() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            runBlocking { container.library.addBookmark("test-epub-chapters", "not a valid locator", 0) }
            compose.onNodeWithText("Bookmarks").performClick()
            bookmarkCount(1)
            compose.onNode(hasContentDescription("Bookmark, ", substring = true) and hasClickAction()).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("This bookmark's saved location could not be opened. You can still delete it.").fetchSemanticsNodes().isNotEmpty()
            }
            // The app did not crash and the dialog is still usable: the bad bookmark can still be deleted.
            compose.onNodeWithText("Delete").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No bookmarks yet.").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
