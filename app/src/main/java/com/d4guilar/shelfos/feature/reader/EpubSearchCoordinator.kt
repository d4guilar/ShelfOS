// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import com.d4guilar.shelfos.core.reader.EpubSearchCursor
import com.d4guilar.shelfos.core.reader.EpubSearchRead
import com.d4guilar.shelfos.core.reader.EpubSearchResult
import com.d4guilar.shelfos.core.reader.normalizeSearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EpubSearchUiState(
    val query: String = "",
    val results: List<EpubSearchResult> = emptyList(),
    val loading: Boolean = false,
    val complete: Boolean = false,
    val error: String? = null,
)

/**
 * Runs at most one publication search. A replacement waits for the superseded job's `finally` block to close its
 * cursor before it acquires another one; a monotonically increasing request id also prevents stale UI writes.
 */
class EpubSearchCoordinator(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    /** Generic observation seam; the release path uses the immediate no-op default. */
    private val beforeStatePublication: suspend (EpubSearchUiState) -> Unit = {},
) {
    private val _state = MutableStateFlow(EpubSearchUiState())
    val state = _state.asStateFlow()
    private val cursorMutex = Mutex()
    private var requestId = 0L
    private var active: Job? = null

    fun search(rawQuery: String, open: suspend (String) -> EpubSearchCursor) {
        val query = normalizeSearchQuery(rawQuery)
        val id = ++requestId
        val previous = active
        previous?.cancel()
        _state.value = if (query.isEmpty()) EpubSearchUiState() else EpubSearchUiState(query = query, loading = true)
        active = scope.launch {
            // The mutex is the cursor-ownership boundary. It covers acquisition through close, so even a burst of
            // three or more replacements cannot let the newest request overtake an older iterator's cleanup.
            cursorMutex.withLock {
                if (query.isEmpty()) return@withLock
                var cursor: EpubSearchCursor? = null
                try {
                    // Assign ownership inside the worker context so cancellation at the context boundary cannot lose
                    // a cursor that was acquired successfully but not yet returned to this coroutine.
                    withContext(worker) { cursor = open(query) }
                    currentCoroutineContext().ensureActive()
                    val accumulated = mutableListOf<EpubSearchResult>()
                    while (true) {
                        when (val read = withContext(worker) { requireNotNull(cursor).next() }) {
                            EpubSearchRead.Complete -> {
                                publish(id, EpubSearchUiState(query, accumulated.toList(), complete = true))
                                return@withLock
                            }
                            EpubSearchRead.Error -> {
                                publish(id, EpubSearchUiState(query = query,
                                    error = "This publication could not be searched. Try another query."))
                                return@withLock
                            }
                            is EpubSearchRead.Page -> {
                                accumulated += read.results
                                publish(id, EpubSearchUiState(query, accumulated.toList(), loading = true))
                            }
                        }
                        currentCoroutineContext().ensureActive()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    publish(id, EpubSearchUiState(query = query,
                        error = "This publication could not be searched. Try another query."))
                } finally {
                    withContext(NonCancellable + worker) { cursor?.close() }
                }
            }
        }
    }

    fun clear() = search("") { error("A blank query never opens a search cursor.") }

    /** Cancels and joins active work, which guarantees cursor closure before the caller tears down the session. */
    suspend fun close() {
        requestId++
        val job = active
        active = null
        job?.cancelAndJoin()
        // A superseded sibling may still be finishing its non-cancellable close. Crossing the ownership boundary
        // guarantees all session-bound iterators are gone before the publication session is allowed to close.
        cursorMutex.withLock { }
        _state.value = EpubSearchUiState()
    }

    private suspend fun publish(id: Long, value: EpubSearchUiState) {
        beforeStatePublication(value)
        if (id == requestId) _state.value = value
    }
}
