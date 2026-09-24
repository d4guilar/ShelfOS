// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.core.files.PublicationException
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.LibraryItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EpubReaderState(val item: LibraryItem? = null, val session: EpubSession? = null,
    val preferences: ReaderPreferences = ReaderPreferences.DEFAULT, val error: String? = null)

class EpubReaderViewModel(private val id: String, private val repository: LibraryRepository,
    private val factory: EpubReaderFactory, private val cleanupScope: CoroutineScope) : ViewModel() {
    private val _state = MutableStateFlow(EpubReaderState())
    val state = _state.asStateFlow()
    private var opening = false
    init { viewModelScope.launch {
        combine(repository.publications, repository.globalPreferences) { items, global -> items.find { it.id == id } to global }
            .collect { (item, global) ->
                if (item == null) { _state.update { it.copy(error = "Publication unavailable.") }; return@collect }
                _state.update { it.copy(item = item, preferences = ReaderPreferences.parse(item.preferences)
                    .over(ReaderPreferences.parse(global)).over(ReaderPreferences.DEFAULT)) }
                if (!opening) {
                    opening = true
                    viewModelScope.launch {
                        var opened: EpubSession? = null
                        try {
                            // Retain ownership even if cancellation arrives at the IO boundary.
                            withContext(NonCancellable) { opened = factory.open(item) }
                            ensureActive()
                            _state.update { it.copy(session = opened) }; opened = null
                            repository.available(id, true)
                        } catch (e: CancellationException) { opened?.close(); throw e }
                        catch (e: Exception) { opened?.close(); _state.update { it.copy(error = (e as? PublicationException)?.message ?: "This EPUB could not be opened.") } }
                    }
                }
            }
    } }
    fun location(locator: String, progress: Int) { viewModelScope.launch {
        try { repository.reading(id, locator, progress) }
        catch (_: Exception) { _state.update { it.copy(error = "Reading position could not be saved.") } }
    } }
    fun preferences(preferences: ReaderPreferences, globally: Boolean) { viewModelScope.launch {
        try { repository.preferences(if (globally) "" else id, preferences.json()) }
        catch (_: Exception) { _state.update { it.copy(error = "Reading preferences could not be saved.") } }
    } }
    override fun onCleared() { _state.value.session?.let { session -> cleanupScope.launch { session.close() } } }
}
