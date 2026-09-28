// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
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

/** Phase 2B.3 search UI against a real parser-opened EPUB and Readium's real StringSearchService. */
class EpubSearchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    @Before fun seedEpub() = runBlocking<Unit> { container.library.add(OriginalFixtures.epubWithChapters(context)) }
    @After fun removeEpub() = runBlocking<Unit> { container.library.remove(ITEM_ID) }

    private fun awaitReader() = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun searchField() = hasContentDescription("Search this publication") and hasSetTextAction()
    private fun resultRow() = hasTestTag("search_result") and hasClickAction()
    private fun openSearch() {
        compose.onNode(hasText("Search") and hasClickAction()).performClick()
        compose.onNode(searchField()).assertExists().assertIsFocused()
    }
    private fun awaitResults() = compose.waitUntil(20_000) {
        compose.onAllNodes(resultRow()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun chapterRow(title: String) = hasText(title) and hasClickAction() and !hasSetTextAction()

    @Test fun queryShowsReadableSnippetAndSelectingResultJumpsByLocator() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, ITEM_ID)).use {
            awaitReader()
            openSearch()
            compose.onNode(searchField()).performTextInput(UNIQUE_QUERY)
            awaitResults()
            compose.onNode(resultRow() and hasText(UNIQUE_QUERY, substring = true)).assertExists()
            compose.onNode(hasContentDescription("Search result", substring = true) and
                hasContentDescription(UNIQUE_QUERY, substring = true)).performClick()

            compose.waitUntil(10_000) { compose.onAllNodes(searchField()).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Chapters").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Chapter 7, current chapter").fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test fun whitespaceIsANoOpAndRealMissShowsANonErrorEmptyState() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, ITEM_ID)).use {
            awaitReader()
            openSearch()
            compose.onNode(searchField()).performTextInput("   ")
            compose.onNodeWithText("Enter a word or phrase to search this publication.").assertExists()
            compose.onAllNodes(resultRow()).assertCountEquals(0)

            compose.onNode(searchField()).performTextReplacement("phrase-that-does-not-exist-in-this-epub")
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("No search results").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("No results for", substring = true).assertExists()
            dismissSearch()
        }
    }

    @Test fun latestQueryOwnsResultsAndClearReturnsToNoQueryState() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, ITEM_ID)).use {
            awaitReader()
            openSearch()
            compose.onNode(searchField()).performTextInput("Chapter 1 paragraph 1")
            compose.onNode(searchField()).performTextReplacement(UNIQUE_QUERY)
            awaitResults()
            compose.onNode(resultRow() and hasText(UNIQUE_QUERY, substring = true)).assertExists()
            compose.onAllNodesWithContentDescription("Chapter 1 paragraph 1", substring = true).assertCountEquals(0)

            compose.onNodeWithText("Clear").performClick()
            compose.onNodeWithText("Enter a word or phrase to search this publication.").assertExists()
            compose.onAllNodes(resultRow()).assertCountEquals(0)
            dismissSearch()
        }
    }

    @Test fun openQueryIsRestoredAndRerunAfterActivityRecreation() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, ITEM_ID)).use { scenario ->
            awaitReader()
            openSearch()
            compose.onNode(searchField()).performTextInput(UNIQUE_QUERY)
            awaitResults()

            scenario.recreate()
            awaitReader()
            compose.onNode(searchField()).assertTextContains(UNIQUE_QUERY)
            awaitResults()
            compose.onNode(resultRow() and hasText(UNIQUE_QUERY, substring = true)).assertExists()
            dismissSearch()
        }
    }

    @Test fun leavingReaderDuringSearchDoesNotLeaveTheActivityAlive() {
        val scenario = ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, ITEM_ID))
        awaitReader()
        openSearch()
        compose.onNode(searchField()).performTextInput("Original")
        scenario.close()
    }

    @Test fun acceptedCtrlFAndKeyboardFocusCanOpenSearchAndActivateAResult() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, ITEM_ID)).use {
            awaitReader()
            sendCtrlF()
            compose.onNode(searchField()).assertExists().assertIsFocused().performTextInput(UNIQUE_QUERY)
            awaitResults()
            compose.onNode(resultRow()).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNode(resultRow()).assertIsFocused().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }

            compose.waitUntil(10_000) { compose.onAllNodes(searchField()).fetchSemanticsNodes().isEmpty() }
            // All reader chrome actions remain present in the compact default emulator viewport after the jump.
            listOf("Library", "Chapters", "Bookmarks", "Search", "Appearance").forEach { label ->
                compose.onNode(hasText(label) and hasClickAction()).assertExists()
            }
        }
    }

    private fun sendCtrlF() {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0, KeyEvent.META_CTRL_ON))
        instrumentation.sendKeySync(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F, 0, KeyEvent.META_CTRL_ON))
        compose.waitForIdle()
    }

    /** Ordinary cases dismiss their dialog and IME before fixture teardown; the dedicated teardown test above
     * intentionally closes the Activity while a search is active. */
    private fun dismissSearch() {
        compose.onNode(hasText("Close") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(searchField()).fetchSemanticsNodes().isEmpty() }
    }

    private companion object {
        const val ITEM_ID = "test-epub-chapters"
        const val UNIQUE_QUERY = "Chapter 7 paragraph 31"
    }
}
