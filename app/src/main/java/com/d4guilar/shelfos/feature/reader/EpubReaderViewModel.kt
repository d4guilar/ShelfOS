// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class EpubReaderState(val item: LibraryItem? = null, val session: EpubSession? = null,
    val preferences: ReaderPreferences = ReaderPreferences.DEFAULT, val globalPreferences: String? = null,
    val error: String? = null)

/** Owns one EPUB session for one title; it survives configuration changes and closes when cleared. */
class EpubReaderViewModel(private val id: String, private val repository: LibraryRepository,
    private val factory: EpubReaderFactory, private val appScope: CoroutineScope) : ViewModel() {
    private val _state = MutableStateFlow(EpubReaderState())
    val state = _state.asStateFlow()
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
    }

    private suspend fun open(item: LibraryItem) {
        var opened: EpubSession? = null
        try {
            // Retain ownership even if cancellation arrives at the IO boundary.
            withContext(NonCancellable) { opened = factory.open(item) }
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(session = opened) }; opened = null
            markAvailable(true)
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

    private fun persistPreferences(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = "Reading preferences could not be saved.") } }
    } }

    private suspend fun markAvailable(available: Boolean) {
        try { repository.available(id, available) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Display state only. */ }
    }

    override fun onCleared() {
        positions.close()
        _state.value.session?.let { session -> appScope.launch { session.close() } }
    }
}
