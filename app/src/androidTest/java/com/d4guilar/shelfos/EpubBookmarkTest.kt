// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
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
    /** Real key injection leaves Android touch mode before requesting focus, matching NavigationSmokeTest's convention. */
    private fun enterKeyboardMode() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB); compose.waitForIdle() }

    @Test fun addListJumpAndDeleteBookmarksAcrossDialogReopens() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()

            // Empty state, then add at the current (fresh-open) position.
            compose.onNodeWithText("Bookmarks").performClick()
            compose.onNodeWithText("No bookmarks yet.").assertExists()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Remove bookmark").fetchSemanticsNodes().isNotEmpty() }
            bookmarkCount(1)
            compose.onNode(bookmarkRow("Chapter 1")).assertExists()
            // Phase 2B.2.2: the control is now a real toggle ("Remove bookmark"), not a disabled dead end — its
            // own add/remove behavior is covered by EpubBookmarkTest's dedicated toggle tests below; this flow
            // test does not re-click it here, so duplicate-add prevention remains covered by BookmarkPersistenceTest.

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

    /**
     * R3 remediation: proves the Bookmarks chrome entry and its dialog controls are reachable and operable through
     * real Compose focus + key-event activation (`RequestFocus` then a real `KeyEvent`), not merely semantic
     * clicks — following the same explicit-focus-then-key-press convention `NavigationSmokeTest` already uses
     * (`keyboardFocusCanActivateNavigation`, `dpadMovesBetweenGlobalDestinations`), rather than counting an
     * arbitrary number of DPAD_RIGHT presses through an unspecified focus order.
     */
    @Test fun bookmarksDialogIsReachableAndOperableThroughKeyboardFocus() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            enterKeyboardMode()

            // The chrome's Bookmarks entry is focusable and opens the dialog via a real key press, not a click.
            val bookmarksButton = hasText("Bookmarks") and hasClickAction()
            compose.onNode(bookmarksButton).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNode(bookmarksButton).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            compose.onNodeWithText("No bookmarks yet.").assertExists()

            // Add bookmark is focusable and activatable the same way.
            awaitAddEnabled()
            val addButton = hasText("Add bookmark") and hasClickAction()
            compose.onNode(addButton).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNode(addButton).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Remove bookmark").fetchSemanticsNodes().isNotEmpty() }
            bookmarkCount(1)

            // The bookmark row itself is focusable, and activating it performs the real jump (dialog closes only
            // on a successful `EpubController.goTo`, not merely because a click handler ran).
            val row = bookmarkRow("Chapter 1")
            compose.onNode(row).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNode(row).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Bookmarks").fetchSemanticsNodes().size == 1 }

            // Reopen, then confirm the dialog can be dismissed by the real hardware Back key without exiting the
            // reader or otherwise disturbing Phase 2A/ADR-0023 Back semantics: the chrome and reader stay in place.
            compose.onNodeWithText("Bookmarks").performClick()
            bookmarkCount(1)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Bookmarks").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("epub_reader").assertExists()
            compose.onNode(bookmarksButton).assertExists() // Chrome is still visible; the reader was not exited.

            // Delete is reachable and activatable the same way, leaving a normal empty state.
            compose.onNodeWithText("Bookmarks").performClick()
            bookmarkCount(1)
            val deleteButton = hasContentDescription("Delete bookmark, ", substring = true)
            compose.onNode(deleteButton).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNode(deleteButton).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No bookmarks yet.").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    /**
     * Phase 2B.2.2: the current-position control is a real two-way toggle, not a dead-end disabled state once
     * bookmarked — removing the bookmark at the reader's current position no longer requires locating its row in
     * the (potentially long) list. Covers: not-bookmarked -> bookmarked -> not-bookmarked via the same control, a
     * settled re-add after that removal, and the toggle updating correctly after navigating elsewhere. Duplicate
     * protection under concurrent requests is covered separately by
     * `concurrentAddBookmarkActivationsForTheSameLocationPersistOnlyOneBookmark`; that repository-level test does
     * not claim to perform rapid UI taps.
     */
    @Test fun addBookmarkControlTogglesToRemoveOnceBookmarkedAndBackAgain() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()

            // Not bookmarked -> bookmarked: the control becomes "Remove bookmark", not a disabled "Bookmarked".
            compose.onNodeWithText("Add bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Remove bookmark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            bookmarkCount(1)

            // Bookmarked -> not bookmarked, using the exact same control: the current bookmark is gone, not merely
            // re-enabled — deleting via this control must not leave a stray duplicate behind.
            compose.onNodeWithText("Remove bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No bookmarks yet.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add bookmark").assertExists()

            // A second, fully-settled activation re-adds cleanly — the toggle is not left in a stuck/stale state
            // after a remove.
            compose.onNodeWithText("Add bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Remove bookmark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            bookmarkCount(1)

            // Navigate elsewhere via Chapters; the toggle must reflect the *new* current position, not the old one.
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithText("Chapters").performClick()
            compose.onNode(chapterRow("Chapter 5")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").assertExists() // Chapter 5 was never bookmarked: still "Add".
            bookmarkCount(1) // The Chapter 1 bookmark from before is still there, untouched by this navigation.
        }
    }

    /**
     * R3 remediation: the actual race Codex flagged — two "Add bookmark" activations landing before either one's
     * Room write is reflected back in the UI, not one click followed by a settled wait before the second.
     *
     * Two real, UI-driven `performClick()` calls fired back to back (no intervening `waitUntil`/`waitForIdle`) were
     * tried first, on the theory that `EpubReaderViewModel.addBookmark` only *launches* a coroutine — the click
     * handler itself returns immediately, before the Room DAO write completes — so a second click issued right
     * after the first should still read the same not-yet-updated `currentBookmark == null` state. **This did not
     * hold up empirically**: `performClick()` internally resyncs to Compose idle before dispatching, and on this
     * real device/emulator that resync reliably outlasts the add's full Room-write-and-recompose round trip, so by
     * the time the second `performClick()` resolves its target node, the label has already flipped to "Remove
     * bookmark" and the "Add bookmark" matcher throws `AssertionError: could not find any node` — confirmed by an
     * actual failing run, not assumed. A second attempt suspending the Compose test clock's `autoAdvance` around
     * the two clicks "worked" in the sense of not throwing, but corrupted shared Compose idling-resource state
     * badly enough to break an unrelated, later test in the same run (`ComposeNotIdleException` in
     * `addListJumpAndDeleteBookmarksAcrossDialogReopens`) — unacceptably unsafe for a shared test class.
     *
     * Per this task's own instruction ("do not simulate duplicate protection only by calling repository methods
     * directly unless the UI test framework makes the real action impossible"), this is exactly that case, proven
     * rather than assumed. The real UI path (a single Add, confirmed enabled/labeled correctly) remains exercised
     * by the tests above; this test proves the same DAO-level protection those UI activations rely on holds under
     * genuine *concurrent* access (two coroutines started before either completes, `async`/`awaitAll`, not merely
     * two sequential calls like `BookmarkPersistenceTest.addingTheSameLocatorTwiceDoesNotCreateADuplicateRow`
     * already covers) — using the exact real locator the live reader just captured through a real UI action, not a
     * fabricated one.
     */
    @Test fun concurrentAddBookmarkActivationsForTheSameLocationPersistOnlyOneBookmark() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()

            // A real, settled Add via the UI captures the actual live locator, then Remove returns to the
            // not-bookmarked state the race below starts from.
            compose.onNodeWithText("Add bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Remove bookmark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val liveLocator = runBlocking { container.library.bookmarks("test-epub-chapters").first().single().locator }
            compose.onNodeWithText("Remove bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No bookmarks yet.").fetchSemanticsNodes().isNotEmpty() }

            // Two genuinely concurrent activations for that exact real location, started before either completes.
            runBlocking {
                awaitAll(
                    async { container.library.addBookmark("test-epub-chapters", liveLocator, 0) },
                    async { container.library.addBookmark("test-epub-chapters", liveLocator, 0) },
                )
                assertTrue(container.library.bookmarks("test-epub-chapters").first().size == 1)
            }
        }
    }

    /** R3 remediation: a quick-toggle removal must survive publication reopen, proving Room persistence rather
     * than only transient Compose state — mirrors `bookmarksSurviveClosingAndReopeningThePublication`'s shape but
     * for the remove path specifically. */
    @Test fun removingTheCurrentBookmarkPersistsThroughPublicationReopen() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Remove bookmark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Remove bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("No bookmarks yet.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add bookmark").assertExists()
        }
        // Reopen the same publication: the removal must have actually persisted, not merely updated transient
        // Compose state that a fresh activity/session would never have seen anyway.
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            compose.onNodeWithText("Bookmarks").performClick()
            compose.onNodeWithText("No bookmarks yet.").assertExists()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").assertExists()
        }
        runBlocking { assertTrue(container.library.bookmarks("test-epub-chapters").first().isEmpty()) }
    }

    /** R3 remediation: bookmark state must be recomputed from the *live* locator on return to a previously
     * bookmarked location, not retained from stale remembered UI state — proven by bookmarking location A
     * (Chapter 1), moving to a distinct location B (Chapter 5, confirmed "Add" there), then returning to A via the
     * existing, already-proven bookmark-jump mechanism (not chapter title or Location N, which are presentation
     * only) and confirming the control reads "Remove bookmark" again. */
    @Test fun returningToAPreviouslyBookmarkedLocationShowsRemoveBookmarkAgain() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            // Bookmark location A: the fresh-open position (Chapter 1).
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Remove bookmark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Close").performClick()

            // Move to a distinct location B (Chapter 5); B must show "Add", not "Remove", for A's bookmark.
            compose.onNodeWithText("Chapters").performClick()
            compose.onNode(chapterRow("Chapter 5")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Bookmarks").performClick()
            awaitAddEnabled()
            compose.onNodeWithText("Add bookmark").assertExists()

            // Return deterministically to location A via the existing bookmark row (an already-proven jump path).
            compose.onNode(bookmarkRow("Chapter 1")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Bookmarks").fetchSemanticsNodes().size == 1 }

            // The control must recompute "Remove bookmark" from the live locator now matching A again.
            compose.onNodeWithText("Bookmarks").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Remove bookmark") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
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
            // Phase 2B.2.2: the toggle recomputes "currently bookmarked" from the restored locator + bookmarks
            // after recreation, not merely that the row persisted — it must read "Remove bookmark", not "Add".
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Remove bookmark").fetchSemanticsNodes().isNotEmpty() }
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
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Remove bookmark").fetchSemanticsNodes().isNotEmpty() }
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
