// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.core.files.PrivateCopyUsage
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.library.PrivateCopyStore
import com.d4guilar.shelfos.data.preferences.ThemeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: ThemeRepository, private val copies: PrivateCopyStore? = null) : ViewModel() {
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _unusedCopies = MutableStateFlow<PrivateCopyUsage?>(null)
    /** Private copies no library item references; null until measured. */
    val unusedCopies = _unusedCopies.asStateFlow()
    val theme = repository.theme.catch { failure ->
        if (failure is CancellationException) throw failure
        _error.value = "Your saved appearance could not be loaded."
        emit(ThemeId.CLASSIC)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun select(id: ThemeId) {
        viewModelScope.launch {
            try {
                repository.select(id)
                _error.value = null
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _error.value = "Appearance could not be saved. Please try again."
            }
        }
    }

    fun refreshStorage() = storage { copies?.unusedPrivateCopies() }

    /** Explicit, confirmed cleanup; never touches originals or copies still in the library. */
    fun deleteUnusedCopies() = storage { copies?.deleteUnusedPrivateCopies() }

    private fun storage(block: suspend () -> PrivateCopyUsage?) {
        viewModelScope.launch {
            try { _unusedCopies.value = block() } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _error.value = "Storage information could not be updated."
            }
        }
    }
}
