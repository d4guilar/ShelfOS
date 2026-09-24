// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.data.library.DemoPublication
import com.d4guilar.shelfos.data.library.LibraryFilter
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.data.library.filterPublications
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LibraryUiState(
    val filter: LibraryFilter, val items: List<DemoPublication>,
    val continueReading: List<DemoPublication>, val selected: DemoPublication?,
    val favorites: Set<String>, val query: String,
)

class LibraryViewModel(private val repository: LibraryRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val filter = saved.getStateFlow("filter", LibraryFilter.FAVORITES.name)
    private val selection = saved.getStateFlow("selection", "forest")
    private val favorites = saved.getStateFlow("favorites", ArrayList(repository.publications.filter { it.initiallyFavorite }.map { it.id }))
    val query = saved.getStateFlow("query", "")
    val state = combine(filter, selection, favorites, query) { filterName, id, favoriteIds, queryText ->
        createState(filterName, id, favoriteIds.toSet(), queryText)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
        createState(filter.value, selection.value, favorites.value.toSet(), query.value))

    private fun createState(filterName: String, id: String, favorites: Set<String>, query: String): LibraryUiState {
        val active = LibraryFilter.entries.firstOrNull { it.name == filterName } ?: LibraryFilter.FAVORITES
        val items = filterPublications(repository.publications, active, favorites)
        val selected = repository.publications.find { it.id == id } ?: items.firstOrNull()
        return LibraryUiState(active, items, repository.publications.filter { it.progress in 1..99 }.take(3), selected, favorites, query)
    }

    fun selectFilter(filter: LibraryFilter) {
        saved["filter"] = filter.name
        saved["selection"] = filterPublications(repository.publications, filter, favorites.value.toSet()).firstOrNull()?.id.orEmpty()
    }
    fun select(id: String) { if (repository.publications.any { it.id == id }) saved["selection"] = id }
    fun search(query: String) { saved["query"] = query }
    fun searchResults(query: String) = repository.publications.filter {
        it.title.contains(query.trim(), true) || it.creator.contains(query.trim(), true)
    }
    fun publication(id: String?) = repository.publications.find { it.id == id }
    fun toggleFavorite(id: String) {
        if (publication(id) == null) return
        val current = favorites.value.toMutableSet()
        if (!current.remove(id)) current.add(id)
        saved["favorites"] = ArrayList(current)
    }
}
