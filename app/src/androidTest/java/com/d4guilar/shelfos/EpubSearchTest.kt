// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.EpubSearchCursor
import com.d4guilar.shelfos.core.reader.EpubSearchRead
import com.d4guilar.shelfos.core.reader.EpubSearchResult
import com.d4guilar.shelfos.feature.reader.EpubActivity
import com.d4guilar.shelfos.feature.reader.EpubSearchLifecycleTestActivity
import com.d4guilar.shelfos.feature.reader.EpubSearchLifecycleTestBoundary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2B.3 search UI against a real parser-opened EPUB and Readium's real StringSearchService. The two lifecycle
 * cases use the debug-only Activity host so cursor acquisition/closure is observable rather than timing-dependent.
 */
class EpubSearchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    @Before fun seedEpub() = runBlocking<Unit> { container.library.add(OriginalFixtures.epubWithChapters(context)) }
    @After fun removeEpub() = runBlocking<Unit> {
        EpubSearchLifecycleTestBoundary.clear()
        container.library.remove(ITEM_ID)
    }

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

    @Test fun recreationWhileCursorIsActiveClosesItAndUsesAFreshCursor() {
        val old = ControlledCursor()
        val fresh = ControlledCursor()
        val boundary = ControlledBoundary(old, fresh)
        EpubSearchLifecycleTestBoundary.install { _, query -> boundary.open(query) }

        ActivityScenario.launch<EpubSearchLifecycleTestActivity>(lifecycleIntent()).use { scenario ->
            awaitReader()
            openSearch()
            compose.onNode(searchField()).performTextInput(LIFECYCLE_QUERY)
            awaitActive(old)

            // Recreate before the first cursor can produce anything. The old composition clears the active query;
            // rememberSaveable then restores it and the new composition opens a second cursor.
            scenario.recreate()
            awaitReader()
            compose.onNode(searchField()).assertTextContains(LIFECYCLE_QUERY)
            compose.waitUntil(10_000) { old.closed.get() }
            awaitActive(fresh)
            org.junit.Assert.assertEquals(listOf(LIFECYCLE_QUERY, LIFECYCLE_QUERY), boundary.queries)

            fresh.emit(EpubSearchRead.Page(listOf(result(FRESH_RESULT))), EpubSearchRead.Complete)
            awaitResults()
            compose.onNode(resultRow() and hasText(FRESH_RESULT, substring = true)).assertExists()
            compose.waitUntil(10_000) { fresh.closed.get() }

            org.junit.Assert.assertEquals(1, old.closeCalls.get())
            dismissSearch()
        }
    }

    @Test fun leavingReaderWhileCursorIsActiveClosesItAndDestroysActivity() {
        val cursor = ControlledCursor()
        val boundary = ControlledBoundary(cursor)
        EpubSearchLifecycleTestBoundary.install { _, query -> boundary.open(query) }
        val scenario = ActivityScenario.launch<EpubSearchLifecycleTestActivity>(lifecycleIntent())
        awaitReader()
        openSearch()
        compose.onNode(searchField()).performTextInput(LIFECYCLE_QUERY)
        awaitActive(cursor)

        // The cursor is blocked inside next(): it cannot complete before teardown closes the reader.
        scenario.close() // A successful return is ActivityScenario's positive DESTROYED-state observation.
        compose.waitUntil(10_000) { cursor.closed.get() }
        org.junit.Assert.assertEquals(1, cursor.closeCalls.get())
        org.junit.Assert.assertEquals(listOf(LIFECYCLE_QUERY), boundary.queries)
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

    private fun lifecycleIntent() = EpubActivity.intent(context, ITEM_ID)
        .setClass(context, EpubSearchLifecycleTestActivity::class.java)

    private fun awaitActive(cursor: ControlledCursor) = compose.waitUntil(10_000) {
        cursor.acquired.get() && cursor.nextStarted.get() && !cursor.closed.get()
    }

    private fun result(highlight: String) = EpubSearchResult(
        locator = "{\"href\":\"chapter-7.xhtml\",\"type\":\"application/xhtml+xml\",\"locations\":{\"progression\":0.5}}",
        href = "chapter-7.xhtml",
        title = "Lifecycle result",
        progression = 0.5,
        before = "before ",
        highlight = highlight,
        after = " after",
    )

    private class ControlledBoundary(vararg cursors: ControlledCursor) {
        private val remaining = ArrayDeque(cursors.toList())
        val queries = CopyOnWriteArrayList<String>()

        fun open(query: String): EpubSearchCursor {
            val cursor = synchronized(remaining) {
                check(remaining.isNotEmpty()) { "Unexpected extra search cursor request for $query" }
                remaining.removeFirst()
            }
            queries += query
            cursor.acquired.set(true)
            return cursor
        }
    }

    /** A cursor that remains suspended after acquisition until the test emits a read. */
    private class ControlledCursor : EpubSearchCursor {
        private val reads = Channel<EpubSearchRead>(Channel.UNLIMITED)
        val acquired = AtomicBoolean()
        val nextStarted = AtomicBoolean()
        val closed = AtomicBoolean()
        val closeCalls = AtomicInteger()

        override suspend fun next(): EpubSearchRead {
            nextStarted.set(true)
            return reads.receive()
        }

        fun emit(vararg values: EpubSearchRead) = values.forEach { check(reads.trySend(it).isSuccess) }

        override fun close() {
            closeCalls.incrementAndGet()
            closed.set(true)
        }
    }

    private companion object {
        const val ITEM_ID = "test-epub-chapters"
        const val UNIQUE_QUERY = "Chapter 7 paragraph 31"
        const val LIFECYCLE_QUERY = "active lifecycle query"
        const val FRESH_RESULT = "fresh lifecycle result"
    }
}
