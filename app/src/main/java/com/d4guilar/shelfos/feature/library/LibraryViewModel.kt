// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.lifecycle.*
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LibraryUiState(
    val filter: LibraryFilter = LibraryFilter.BOOKS, val items: List<LibraryItem> = emptyList(),
    val continueReading: List<LibraryItem> = emptyList(), val selected: LibraryItem? = null,
    val favorites: Set<String> = emptySet(), val query: String = "", val all: List<LibraryItem> = emptyList(),
    val loading: Boolean = true,
)

class LibraryViewModel(val repository: LibraryRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val filter = saved.getStateFlow("filter", LibraryFilter.BOOKS.name)
    private val selection = saved.getStateFlow("selection", "")
    val query = saved.getStateFlow("query", "")
    private val records = repository.publications.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val state = combine(records, filter, selection, query) { all, filterName, id, queryText ->
        val active = LibraryFilter.entries.firstOrNull { it.name == filterName } ?: LibraryFilter.BOOKS
        val items = filterPublications(all, active)
        LibraryUiState(active, items, all.filter { it.lastRead > 0 && it.progress < 100 }.sortedByDescending { it.lastRead }.take(5),
            items.find { it.id == id } ?: items.firstOrNull(), all.filter { it.favorite }.map { it.id }.toSet(), queryText, all, false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    fun selectFilter(filter: LibraryFilter) { saved["filter"] = filter.name; saved["selection"] = "" }
    fun select(id: String) { saved["selection"] = id }
    fun showImported(item: LibraryItem, id: String) { saved["filter"] = LibraryFilter.entries.first { it.category == item.category }.name; select(id) }
    fun search(query: String) { saved["query"] = query }
    fun searchResults(query: String) = records.value.filter { it.title.contains(query.trim(), true) || it.creator.contains(query.trim(), true) }
    fun publication(id: String?) = records.value.find { it.id == id }
    fun toggleFavorite(id: String) = action { repository.favorite(id) }
    fun remove(id: String) = action { repository.remove(id) }
    fun edit(id: String, title: String, creator: String, category: MediaCategory) = action {
        require(title.isNotBlank())
        repository.edit(id, title, creator, category)
    }
    fun dismissError() { _error.value = null }
    private fun action(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _error.value = "The library change could not be saved. Please try again." }
    } }
}
