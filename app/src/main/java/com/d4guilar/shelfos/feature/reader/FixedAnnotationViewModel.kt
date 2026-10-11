// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.reader.pageProgress
import com.d4guilar.shelfos.data.annotations.AnnotationRepository
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationEdit
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.AnnotationLimits
import com.d4guilar.shelfos.domain.annotations.fixedBookmarkIdsAt
import com.d4guilar.shelfos.domain.annotations.fixedBookmarkedPages
import com.d4guilar.shelfos.domain.annotations.fixedPageDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the fixed-page note editor is editing. A new note captures its true 0-based source [page] (and the page
 * [count], for ordering) when the editor OPENS, so rotating, resizing or turning pages while it is open can never
 * move the anchor. An unsaved draft survives rotation (the ViewModel plus `rememberSaveable` body) but not process death.
 */
sealed interface FixedEditorTarget {
    data class NewNote(val page: Int, val count: Int) : FixedEditorTarget
    data class Existing(val id: String) : FixedEditorTarget
}

data class FixedAnnotationState(
    /** Every canonical annotation of this publication in reading order. */
    val annotations: List<Annotation> = emptyList(),
    /** 0-based pages carrying a fixed-page bookmark; rebuilt only when the list changes, never per page turn. */
    val bookmarkedPages: Set<Int> = emptySet(),
    val editor: FixedEditorTarget? = null,
    val notice: UiMessage? = null,
)

/**
 * Fixed-layout (PDF, CBZ, CBR) bookmarks and page notes for ONE publication over the canonical annotation layer.
 * Kept apart from [FixedReaderViewModel] (render/mutex/spread state): it is given the page and count by the screen
 * and never derives page identity itself. Every anchor is `FixedReaderState.page`, the true 0-based source page.
 */
class FixedAnnotationViewModel(private val id: String, private val annotations: AnnotationRepository) : ViewModel() {
    private val _state = MutableStateFlow(FixedAnnotationState())
    val state = _state.asStateFlow()
    // One bookmark toggle or note creation at a time: a rapid second press is ignored, never a second row.
    private val busy = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            annotations.observeForPublication(id).collect { listing ->
                _state.update { it.copy(annotations = listing.annotations, bookmarkedPages = fixedBookmarkedPages(listing.annotations)) }
            }
        }
    }

    private fun notice(resource: Int, vararg args: Any) { _state.update { it.copy(notice = UiMessage.Resource(resource, args.toList())) } }
    fun dismissNotice() { _state.update { it.copy(notice = null) } }
    fun closeEditor() { _state.update { it.copy(editor = null) } }
    fun openEditor(target: FixedEditorTarget) { _state.update { it.copy(editor = target) } }
    fun beginNote(page: Int, count: Int) { if (count > 0 && page in 0 until count) openEditor(FixedEditorTarget.NewNote(page, count)) }

    /** Removes every bookmark at [page] when there is one, otherwise creates exactly one. Strict page equality, no label. */
    fun toggleBookmark(page: Int, count: Int) {
        if (count <= 0 || page !in 0 until count || !busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val existing = fixedBookmarkIdsAt(annotations.observeForPublication(id, setOf(AnnotationKind.BOOKMARK)).first().annotations, page)
                if (existing.isEmpty()) annotations.create(fixedPageDraft(id, AnnotationKind.BOOKMARK, page, count, pageProgress(page, count)))
                else existing.forEach { annotations.delete(it, AnnotationKind.BOOKMARK) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice(R.string.bookmark_save_failed) }
            finally { busy.set(false) }
        }
    }

    /** Saves a new note's required body at the page captured when the editor opened. Never truncates; never saves blank text. */
    fun saveNewNote(target: FixedEditorTarget.NewNote, body: String) {
        if (body.isBlank()) { notice(R.string.annotation_note_empty); return }
        if (body.length > AnnotationLimits.MAX_BODY) { notice(R.string.annotation_note_too_long, AnnotationLimits.MAX_BODY); return }
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                annotations.create(fixedPageDraft(id, AnnotationKind.NOTE, target.page, target.count, pageProgress(target.page, target.count), body))
                closeEditor()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice(R.string.annotation_save_failed) }
            finally { busy.set(false) }
        }
    }

    /** Changes only a NOTE's body; kind, locator, item and creation time are untouched by the repository edit. */
    fun updateNote(annotationId: String, body: String) {
        val current = _state.value.annotations.firstOrNull { it.id == annotationId && it.kind == AnnotationKind.NOTE } ?: return
        if (body.isBlank()) { notice(R.string.annotation_note_empty); return }
        if (body.length > AnnotationLimits.MAX_BODY) { notice(R.string.annotation_note_too_long, AnnotationLimits.MAX_BODY); return }
        viewModelScope.launch {
            try {
                annotations.update(annotationId, AnnotationEdit(current.title, null, body, null))
                closeEditor()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice(R.string.annotation_update_failed) }
        }
    }

    fun delete(annotationId: String) {
        viewModelScope.launch {
            try { annotations.delete(annotationId); closeEditor() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice(R.string.annotation_delete_failed) }
        }
    }
}
