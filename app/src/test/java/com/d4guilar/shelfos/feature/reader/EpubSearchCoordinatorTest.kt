// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.reader.EpubSearchCursor
import com.d4guilar.shelfos.core.reader.EpubSearchRead
import com.d4guilar.shelfos.core.reader.EpubSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EpubSearchCoordinatorTest {
    @Test fun normalCompletionPublishesResultsAndClosesIterator() = runTest {
        val cursor = QueueCursor(EpubSearchRead.Page(listOf(result("alpha"))), EpubSearchRead.Complete)
        val coordinator = EpubSearchCoordinator(this, StandardTestDispatcher(testScheduler))

        coordinator.search(" alpha ") { cursor }
        advanceUntilIdle()

        assertEquals("alpha", coordinator.state.value.query)
        assertEquals(listOf("alpha"), coordinator.state.value.results.map { it.highlight })
        assertTrue(coordinator.state.value.complete)
        assertTrue(cursor.closed)
    }

    @Test fun searchErrorIsReadableAndClosesIterator() = runTest {
        val cursor = QueueCursor(EpubSearchRead.Error)
        val coordinator = EpubSearchCoordinator(this, StandardTestDispatcher(testScheduler))

        coordinator.search("broken") { cursor }
        advanceUntilIdle()

        assertEquals(UiMessage.Resource(R.string.search_error_failed), coordinator.state.value.error)
        assertTrue(cursor.closed)
    }

    @Test fun rapidReplacementClosesAlphaBeforeOpeningBetaAndOnlyBetaOwnsUi() = runTest {
        val events = mutableListOf<String>()
        val alpha = BlockingCursor("alpha", events)
        val beta = QueueCursor(EpubSearchRead.Page(listOf(result("beta"))), EpubSearchRead.Complete,
            onClose = { events += "close beta" })
        val coordinator = EpubSearchCoordinator(this, StandardTestDispatcher(testScheduler))

        coordinator.search("alpha") { events += "open alpha"; alpha }
        runCurrent()
        assertTrue(alpha.started.isCompleted)

        coordinator.search("beta") { events += "open beta"; beta }
        advanceUntilIdle()

        assertTrue(alpha.closed)
        assertEquals(listOf("open alpha", "close alpha", "open beta", "close beta"), events)
        assertEquals("beta", coordinator.state.value.query)
        assertEquals(listOf("beta"), coordinator.state.value.results.map { it.highlight })
    }

    @Test fun threeRapidRequestsCannotOvertakeOldestCursorCleanup() = runTest {
        val events = mutableListOf<String>()
        val alpha = BlockingCursor("alpha", events)
        val gamma = QueueCursor(EpubSearchRead.Complete, onClose = { events += "close gamma" })
        val coordinator = EpubSearchCoordinator(this, StandardTestDispatcher(testScheduler))

        coordinator.search("alpha") { events += "open alpha"; alpha }
        runCurrent()
        coordinator.search("beta") { events += "open beta"; QueueCursor(EpubSearchRead.Complete) }
        coordinator.search("gamma") { events += "open gamma"; gamma }
        advanceUntilIdle()

        assertEquals(listOf("open alpha", "close alpha", "open gamma", "close gamma"), events)
        assertTrue(alpha.closed)
        assertEquals("gamma", coordinator.state.value.query)
        assertTrue(coordinator.state.value.complete)
    }

    @Test fun clearingActiveSearchClosesIteratorAndReturnsToNoQueryState() = runTest {
        val cursor = BlockingCursor("alpha")
        val coordinator = EpubSearchCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.search("alpha") { cursor }
        runCurrent()

        coordinator.clear()
        advanceUntilIdle()

        assertTrue(cursor.closed)
        assertEquals(EpubSearchUiState(), coordinator.state.value)
    }

    @Test fun whitespaceNeverAcquiresIterator() = runTest {
        var opens = 0
        val coordinator = EpubSearchCoordinator(this, StandardTestDispatcher(testScheduler))

        coordinator.search("  \n\t ") { opens++; QueueCursor(EpubSearchRead.Complete) }
        advanceUntilIdle()

        assertEquals(0, opens)
        assertEquals(EpubSearchUiState(), coordinator.state.value)
    }

    @Test fun readerTeardownDoesNotReturnUntilActiveIteratorIsClosed() = runTest {
        val events = mutableListOf<String>()
        val cursor = BlockingCursor("alpha", events)
        val coordinator = EpubSearchCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.search("alpha") { cursor }
        runCurrent()

        coordinator.close()
        events += "coordinator close returned"

        assertTrue(cursor.closed)
        assertEquals(listOf("close alpha", "coordinator close returned"), events)
        assertEquals(EpubSearchUiState(), coordinator.state.value)
    }

    @Test fun cancellationCannotPublishAStalePageAfterReplacement() = runTest {
        val release = CompletableDeferred<Unit>()
        val alpha = object : EpubSearchCursor {
            var closed = false
            override suspend fun next(): EpubSearchRead {
                try { awaitCancellation() } finally { release.complete(Unit) }
            }
            override fun close() { closed = true }
        }
        val beta = QueueCursor(EpubSearchRead.Page(listOf(result("beta"))), EpubSearchRead.Complete)
        val coordinator = EpubSearchCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.search("alpha") { alpha }
        runCurrent()

        coordinator.search("beta") { beta }
        advanceUntilIdle()

        assertTrue(release.isCompleted)
        assertTrue(alpha.closed)
        assertFalse(coordinator.state.value.results.any { it.highlight == "alpha" })
        assertEquals(listOf("beta"), coordinator.state.value.results.map { it.highlight })
    }

    @Test fun returnedPageCannotPublishAfterReplacementBecomesLatest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val alphaAtPublication = CompletableDeferred<Unit>()
        val releaseAlphaPublication = CompletableDeferred<Unit>()
        val alphaResumed = CompletableDeferred<Unit>()
        val alpha = ControlledPageCursor(result("stale alpha"), initiallyReleased = true)
        val beta = ControlledPageCursor(result("beta"))
        val coordinator = EpubSearchCoordinator(this, dispatcher) { candidate ->
            if (candidate.query == "alpha" && candidate.results.isNotEmpty()) {
                alphaAtPublication.complete(Unit)
                // Deliberately let post-result processing resume after replacement cancellation. This is test-only;
                // production cursor reads and cancellation remain fully cooperative.
                withContext(NonCancellable) { releaseAlphaPublication.await() }
                alphaResumed.complete(Unit)
            }
        }

        coordinator.search("alpha") { alpha }
        runCurrent()
        assertTrue(alpha.returned.isCompleted)
        assertTrue(alphaAtPublication.isCompleted)

        coordinator.search("beta") { beta }
        assertEquals("beta", coordinator.state.value.query)
        assertTrue(coordinator.state.value.results.isEmpty())

        releaseAlphaPublication.complete(Unit)
        runCurrent()
        assertTrue(alphaResumed.isCompleted)
        assertTrue(alpha.closed)
        assertTrue("B waits for A cleanup before acquiring its cursor", beta.started.isCompleted)
        assertEquals("beta", coordinator.state.value.query)
        assertTrue("A's stale page must not replace B's authoritative state", coordinator.state.value.results.isEmpty())

        beta.release.complete(Unit)
        advanceUntilIdle()
        assertTrue(beta.returned.isCompleted)
        assertTrue(beta.closed)
        assertEquals("beta", coordinator.state.value.query)
        assertEquals(listOf("beta"), coordinator.state.value.results.map { it.highlight })
    }

    private fun result(highlight: String) = EpubSearchResult(
        locator = "{\"href\":\"chapter.xhtml\"}", href = "chapter.xhtml", title = null,
        progression = null, before = "before", highlight = highlight, after = "after",
    )

    private class QueueCursor(
        vararg reads: EpubSearchRead,
        private val onClose: () -> Unit = {},
    ) : EpubSearchCursor {
        private val queue = ArrayDeque(reads.toList())
        var closed = false
        override suspend fun next(): EpubSearchRead = queue.removeFirst()
        override fun close() { closed = true; onClose() }
    }

    private class BlockingCursor(
        private val name: String,
        private val events: MutableList<String>? = null,
    ) : EpubSearchCursor {
        val started = CompletableDeferred<Unit>()
        var closed = false
        override suspend fun next(): EpubSearchRead {
            started.complete(Unit)
            awaitCancellation()
        }
        override fun close() { closed = true; events?.add("close $name") }
    }

    private class ControlledPageCursor(
        private val result: EpubSearchResult,
        initiallyReleased: Boolean = false,
    ) : EpubSearchCursor {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>().apply { if (initiallyReleased) complete(Unit) }
        val returned = CompletableDeferred<Unit>()
        var closed = false
        private var pagePending = true

        override suspend fun next(): EpubSearchRead {
            if (!pagePending) return EpubSearchRead.Complete
            pagePending = false
            started.complete(Unit)
            release.await()
            returned.complete(Unit)
            return EpubSearchRead.Page(listOf(result))
        }

        override fun close() { closed = true }
    }
}
