// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ImportState(val busy: Boolean = false, val stage: String = "", val preview: LibraryItem? = null,
    val needsCopy: Boolean = false, val error: String? = null, val imported: LibraryItem? = null,
    val possibleDuplicate: Boolean = false)

class ImportViewModel(private val files: PublicationFiles, private val repository: LibraryRepository,
    private val cleanupScope: CoroutineScope) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state = _state.asStateFlow()
    private var pending: PreparedImport? = null
    private var uri: Uri? = null
    private var job: Job? = null

    fun choose(source: Uri, copy: Boolean = false) {
        if (job?.isActive == true) return
        uri = source
        job = viewModelScope.launch {
            _state.value = ImportState(busy = true, stage = "Preparing import…")
            try {
                val existing = repository.publications.first().find { it.sourceUri == source.toString() }
                if (existing != null) { _state.value = ImportState(imported = existing); return@launch }
                val prepared = files.prepare(source, copy) { stage -> _state.update { it.copy(stage = stage) } }
                pending = prepared
                val duplicate = repository.publications.first().any { it.fileName == prepared.item.fileName && it.byteSize == prepared.item.byteSize }
                _state.value = ImportState(preview = prepared.item, possibleDuplicate = duplicate)
            } catch (e: CancellationException) { _state.value = ImportState(); throw e }
            catch (_: CopyRequired) { _state.value = ImportState(needsCopy = true) }
            catch (e: PublicationException) { _state.value = ImportState(error = e.message) }
            catch (_: SecurityException) { _state.value = ImportState(error = "Access was denied. Choose the file again.") }
            catch (_: Exception) { _state.value = ImportState(error = "This file could not be imported. Check that it is available and not damaged.") }
        }
    }
    fun copySource() { uri?.let { choose(it, copy = true) } }
    fun confirm(title: String, creator: String, category: MediaCategory) {
        val prepared = pending ?: return
        if (title.isBlank() || job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, stage = "Saving publication…") }
            try {
                val item = prepared.item.copy(title = title.trim(), creator = creator.trim(), category = category,
                    titleOrigin = if (title.trim() == prepared.item.title) prepared.item.titleOrigin else "user",
                    creatorOrigin = if (creator.isBlank()) "unknown" else "user")
                withContext(NonCancellable) {
                    val id = repository.add(item)
                    if (id != item.id) withContext(Dispatchers.IO) { files.discard(prepared, true) }
                    pending = null
                    _state.value = ImportState(imported = item.copy(id = id))
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(busy = false, error = "Could not save the library entry. You can retry.") } }
        }
    }
    fun dismiss() {
        if (_state.value.busy && _state.value.stage == "Saving publication…") return
        job?.cancel()
        val abandoned = pending
        pending = null
        if (abandoned != null) cleanup(abandoned)
        _state.value = ImportState()
    }
    fun consumed() { _state.value = ImportState() }
    private fun cleanup(prepared: PreparedImport) { cleanupScope.launch(Dispatchers.IO) {
        files.discard(prepared, repository.publications.first().any { it.sourceUri == prepared.item.sourceUri })
    } }
    override fun onCleared() { pending?.let(::cleanup) }
}
