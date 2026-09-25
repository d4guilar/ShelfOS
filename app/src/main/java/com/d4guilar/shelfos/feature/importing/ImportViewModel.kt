// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.importing.*
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ImportState(val busy: Boolean = false, val cancellable: Boolean = true, val stage: String = "",
    val preview: LibraryItem? = null, val needsCopy: Boolean = false, val error: String? = null,
    val imported: LibraryItem? = null, val possibleDuplicate: Boolean = false)

/**
 * Single-file import: prepare → review → commit. Nothing reaches the library until the user confirms.
 *
 * An import's work (a possible private copy and grant, and its hold on the source in [leases]) has one owner at a
 * time: the preparing job, then the review ([pending]), then the save, which hands it to the library. Whoever owns
 * it when the import ends without committing abandons it; committed work is never discarded. An unfinished import
 * is lost on process death and must be retried; startup maintenance cleans what it left behind.
 */
class ImportViewModel(private val importer: PublicationImporter, private val repository: LibraryRepository,
    private val cleanupScope: CoroutineScope, private val beforeImport: suspend () -> Unit = {},
    private val leases: ImportLeases = ImportLeases()) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state = _state.asStateFlow()
    /** The preparation under review. A save takes it over; dismissing, replacing or clearing abandons it. */
    private var pending: PreparedImport? = null
    private var source: String? = null
    private var job: Job? = null

    fun choose(sourceUri: String, copy: Boolean = false) {
        if (job?.isActive == true) return
        pending?.let { superseded -> pending = null; abandon(superseded.item.sourceUri, superseded) }
        source = sourceUri
        val previous = job
        job = viewModelScope.launch {
            leases.hold(sourceUri)
            var prepared: PreparedImport? = null
            var reviewing = false
            try {
                // A cancelled earlier preparation finishes, releasing what it acquired, before this one starts.
                previous?.join()
                _state.value = ImportState(busy = true, stage = "Preparing import…")
                beforeImport()
                existingSource(repository.publications.first(), sourceUri)?.let { existing ->
                    _state.value = ImportState(imported = existing)
                    return@launch
                }
                leases.awaitRelease(sourceUri)
                val result = importer.prepare(sourceUri, copy) { stage -> _state.update { it.copy(stage = stage) } }
                prepared = result
                val duplicate = isPossibleDuplicate(repository.publications.first(), result.item)
                pending = result; reviewing = true // Ownership: this job → the review.
                _state.value = ImportState(preview = result.item, possibleDuplicate = duplicate)
            } catch (e: CancellationException) { _state.value = ImportState(); throw e }
            catch (e: Exception) {
                _state.value = if (e.publicationProblem() == PublicationProblem.NEEDS_COPY) ImportState(needsCopy = true)
                    else ImportState(error = e.importMessage())
            } finally { if (!reviewing) abandon(sourceUri, prepared) }
        }
    }

    fun copySource() { source?.let { choose(it, copy = true) } }

    fun confirm(title: String, creator: String, category: MediaCategory) {
        if (pending == null || title.isBlank() || job?.isActive == true) return
        job = viewModelScope.launch {
            // Ownership: the review → this save, before anything suspends. If the review is dismissed or cleared
            // before this runs, the save never starts and the review abandons its preparation as usual.
            val prepared = pending ?: return@launch
            pending = null
            _state.update { it.copy(busy = true, cancellable = false, stage = "Saving publication…") }
            val original = prepared.item
            val item = original.copy(title = title.trim(), creator = creator.trim(), category = category,
                titleOrigin = if (title.trim() == original.title) original.titleOrigin else "user",
                creatorOrigin = when { creator.isBlank() -> "unknown"; creator.trim() == original.creator -> original.creatorOrigin; else -> "user" })
            val save = coroutineContext.job
            // The save completes and settles ownership even if this ViewModel is cleared meanwhile.
            withContext(NonCancellable) {
                try {
                    val id = repository.add(item)
                    // Ownership: this save → the library. If a concurrent save of this source won, its item owns the
                    // grant and only this preparation's own private copy is discarded.
                    if (id != item.id) withContext(Dispatchers.IO) { importer.discard(prepared, sourceStillUsed = true) }
                    leases.committed(original.sourceUri)
                    _state.value = ImportState(imported = item.copy(id = id))
                } catch (_: Exception) {
                    // Not saved: the review takes the preparation back for a retry; without a review it is abandoned.
                    if (save.isActive) {
                        pending = prepared
                        _state.update { it.copy(busy = false, cancellable = true, error = "Could not save the library entry. You can retry.") }
                    } else abandon(original.sourceUri, prepared)
                }
            }
        }
    }

    fun dismiss() {
        if (_state.value.busy && !_state.value.cancellable) return
        job?.cancel()
        pending?.let { abandoned -> pending = null; abandon(abandoned.item.sourceUri, abandoned) }
        _state.value = ImportState()
    }

    fun consumed() { _state.value = ImportState() }

    /**
     * Ends an import that did not commit. What it still owned is disposed of in [cleanupScope] (the app's IO scope),
     * so it is released even after this ViewModel is cleared.
     */
    private fun abandon(sourceUri: String, prepared: PreparedImport?) {
        val owned = leases.end(sourceUri, prepared) ?: return
        cleanupScope.launch {
            val library = repository.publications.first()
            if (!isCommitted(library, owned)) leases.dispose(owned, existingSource(library, owned.item.sourceUri) != null, importer::discard)
        }
    }

    override fun onCleared() { pending?.let { abandoned -> pending = null; abandon(abandoned.item.sourceUri, abandoned) } }
}

/** A specific detail (for example a damaged EPUB container) wins over the problem's import wording. */
private fun Exception.importMessage(): String {
    val problem = publicationProblem()
    return (this as? PublicationException)?.message?.takeIf { it != problem.message } ?: problem.importMessage
}
