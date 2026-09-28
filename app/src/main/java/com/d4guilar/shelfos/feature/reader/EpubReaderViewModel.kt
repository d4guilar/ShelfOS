// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.data.library.BookmarkRepository
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EpubReaderState(val item: LibraryItem? = null, val session: EpubSession? = null,
    val preferences: ReaderPreferences = ReaderPreferences.DEFAULT, val globalPreferences: String? = null,
    val bookmarks: List<Bookmark> = emptyList(), val epubPositions: List<EpubPosition> = emptyList(), val error: String? = null)

/** Owns one EPUB session for one title; it survives configuration changes and closes when cleared. */
class EpubReaderViewModel(private val id: String, private val repository: LibraryRepository,
    private val bookmarks: BookmarkRepository, private val factory: EpubReaderFactory, private val appScope: CoroutineScope) : ViewModel() {
    private val _state = MutableStateFlow(EpubReaderState())
    val state = _state.asStateFlow()
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val searchCoordinator = EpubSearchCoordinator(searchScope)
    val searchState = searchCoordinator.state
    private val positions = PositionWriter<Pair<String, Int>>(appScope, write = { (locator, progress) ->
        repository.reading(id, locator, progress)
    }, onFailure = { _state.update { it.copy(error = "Your reading position could not be saved.") } })

    init {
        viewModelScope.launch {
            var opening = false
            combine(repository.publication(id), repository.globalPreferences) { item, global -> item to global }.collect { (item, global) ->
                if (item == null) { _state.update { it.copy(error = "This publication is no longer in your library.") }; return@collect }
                _state.update { it.copy(item = item, globalPreferences = global,
                    preferences = resolveReaderPreferences(ReaderPreferences.parse(item.preferences), ReaderPreferences.parse(global))) }
                if (!opening) { opening = true; launch { open(item) } }
            }
        }
        // Independent of session opening, so bookmarks never gate or delay reader startup.
        viewModelScope.launch { bookmarks.bookmarks(id).collect { list -> _state.update { it.copy(bookmarks = list) } } }
    }

    private suspend fun open(item: LibraryItem) {
        var opened: EpubSession? = null
        try {
            // Retain ownership even if cancellation arrives at the IO boundary.
            withContext(NonCancellable) { opened = factory.open(item) }
            currentCoroutineContext().ensureActive()
            val session = requireNotNull(opened)
            _state.update { it.copy(session = session) }; opened = null
            markAvailable(true)
            // Independent of session opening, so a slow/large publication never delays showing the reader: Location
            // N (Phase 2B.2.1) is a presentation nicety, not something bookmark display can't work without. Dispatched
            // onto Dispatchers.IO (Codex R3: viewModelScope's own dispatcher is Main, and Publication.positions()
            // does not switch dispatchers itself) rather than the plain catch-all runCatching this replaced, which
            // would have silently swallowed a real CancellationException along with an ordinary catalog-generation
            // failure — cancellation now propagates normally; only a genuine failure degrades to no Location N.
            viewModelScope.launch {
                val located = try { withContext(Dispatchers.IO) { session.epubPositions() } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { emptyList() }
                _state.update { it.copy(epubPositions = located) }
            }
        } catch (e: CancellationException) { opened?.close(); throw e }
        catch (e: Exception) {
            opened?.close()
            if (e.publicationProblem().unavailable) markAvailable(false)
            _state.update { it.copy(error = e.readerMessage()) }
        }
    }

    fun location(locator: String, progress: Int) = positions.save(locator to progress)

    fun applyAppearance(before: ReaderPreferences, after: ReaderPreferences, globally: Boolean) = persistPreferences {
        val current = _state.value
        repository.saveAppearance(id, current.item?.preferences ?: "{}", current.globalPreferences, before, after, globally)
    }

    fun resetAppearance(globally: Boolean) = persistPreferences {
        repository.resetAppearance(id, _state.value.item?.preferences ?: "{}", globally)
    }

    /** Snapshots the reader's live locator/progress (owned by the caller, not this ViewModel — see EpubActivity's
     * currentLocatorJson) into a new bookmark row. A bookmark at the exact same locator is a safe no-op, handled
     * by the repository, not here. */
    fun addBookmark(locator: String, progress: Int) = viewModelScope.launch {
        try { bookmarks.addBookmark(id, locator, progress) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = "This bookmark could not be saved.") } }
    }

    fun deleteBookmark(bookmarkId: String) = viewModelScope.launch {
        try { bookmarks.deleteBookmark(bookmarkId) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = "This bookmark could not be deleted.") } }
    }

    fun search(session: EpubSession, query: String) = searchCoordinator.search(query, session::search)

    fun clearSearch() = searchCoordinator.clear()

    private fun persistPreferences(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = "Reading preferences could not be saved.") } }
    } }

    private suspend fun markAvailable(available: Boolean) {
        try { repository.available(id, available) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Display state only. */ }
    }

    override fun onCleared() {
        positions.close()
        val session = _state.value.session
        // Search owns session-bound Readium iterators. Join its guaranteed cursor cleanup before closing the
        // publication itself; this scope is independent of viewModelScope so framework cancellation cannot race
        // past that ordering during teardown.
        appScope.launch {
            searchCoordinator.close()
            searchScope.cancel()
            session?.close()
        }
    }
}
