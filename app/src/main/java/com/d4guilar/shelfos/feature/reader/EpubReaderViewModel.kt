// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.designsystem.readerMessage
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.data.annotations.AnnotationRepository
import com.d4guilar.shelfos.data.annotations.addEpubBookmark
import com.d4guilar.shelfos.data.annotations.observeEpubBookmarks
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationDraft
import com.d4guilar.shelfos.domain.annotations.AnnotationEdit
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.AnnotationLimits
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** What the annotation editor is editing. Held by the ViewModel so it survives rotation; an unsaved draft is not persisted across process death. */
sealed interface AnnotationEditorTarget {
    /** A note on selected text: nothing exists until the user saves. */
    data class NewSelectionNote(val draft: EpubSelectionDraft) : AnnotationEditorTarget
    /** A note at the reader's position when it was opened ([locatorJson] is the live Readium locator). */
    data class NewPositionNote(val locatorJson: String, val progressSnapshot: Int, val orderKey: Double?) : AnnotationEditorTarget
    data class Existing(val id: String) : AnnotationEditorTarget
}

data class EpubReaderState(val item: LibraryItem? = null, val session: EpubSession? = null,
    val preferences: ReaderPreferences = ReaderPreferences.DEFAULT, val globalPreferences: String? = null,
    val bookmarks: List<Bookmark> = emptyList(), val epubPositions: List<EpubPosition> = emptyList(), val error: UiMessage? = null,
    /** Every canonical annotation of this publication (bookmarks, highlights, notes) in reading order. */
    val annotations: List<Annotation> = emptyList(),
    /** Placement of selection-backed annotations in the current sanitized rendition; an id without an entry has not been checked yet. */
    val anchors: Map<String, AnchorStatus> = emptyMap(),
    val editor: AnnotationEditorTarget? = null,
    /** A transient, localized annotation message (rejected selection, failed save). Cleared by [EpubReaderViewModel.dismissNotice]. */
    val notice: UiMessage? = null)

/** Injectable at the Activity factory boundary so lifecycle tests can observe cursor ownership deterministically. */
typealias EpubSearchCursorOpener = suspend (EpubSession, String) -> EpubSearchCursor

/** Owns one EPUB session for one title; it survives configuration changes and closes when cleared. */
class EpubReaderViewModel(private val id: String, private val repository: LibraryRepository,
    private val annotations: AnnotationRepository,
    private val factory: EpubReaderFactory, private val appScope: CoroutineScope,
    private val openSearchCursor: EpubSearchCursorOpener = { session, query -> session.search(query) }) : ViewModel() {
    private val _state = MutableStateFlow(EpubReaderState())
    val state = _state.asStateFlow()
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val searchCoordinator = EpubSearchCoordinator(searchScope)
    val searchState = searchCoordinator.state
    private val positions = PositionWriter<Pair<String, Int>>(appScope, write = { (locator, progress) ->
        repository.reading(id, locator, progress)
    }, onFailure = { _state.update { it.copy(error = UiMessage.Resource(R.string.reader_position_save_failed)) } })

    init {
        viewModelScope.launch {
            var opening = false
            combine(repository.publication(id), repository.globalPreferences) { item, global -> item to global }.collect { (item, global) ->
                if (item == null) { _state.update { it.copy(error = UiMessage.Resource(R.string.reader_no_longer_in_library)) }; return@collect }
                _state.update { it.copy(item = item, globalPreferences = global,
                    preferences = resolveReaderPreferences(ReaderPreferences.parse(item.preferences), ReaderPreferences.parse(global))) }
                if (!opening) { opening = true; launch { open(item) } }
            }
        }
        // Independent of session opening, so bookmarks never gate or delay reader startup.
        viewModelScope.launch { annotations.observeEpubBookmarks(id).collect { list -> _state.update { it.copy(bookmarks = list) } } }
        // Same independence for the canonical annotation list; stored annotations never wait for or gate the reader.
        viewModelScope.launch { annotations.observeForPublication(id).collect { listing -> _state.update { it.copy(annotations = listing.annotations) } } }
        // Stale-anchor protection: every selection-backed annotation is verified against the sanitized rendition before it is drawn.
        viewModelScope.launch {
            combine(_state.map { it.annotations }.distinctUntilChanged(), _state.map { it.session }.distinctUntilChanged()) { list, session -> list to session }
                .mapLatest { (list, session) -> if (session == null) emptyMap() else verifyAnchors(session, list) }
                .collect { anchors -> _state.update { it.copy(anchors = anchors) } }
        }
    }

    private suspend fun verifyAnchors(session: EpubSession, list: List<Annotation>): Map<String, AnchorStatus> {
        val backed = list.filter { it.isSelectionBacked && it.locatorFormat == LocatorFormat.READIUM_LOCATOR_1 }
        if (backed.isEmpty()) return emptyMap()
        val verdicts = session.anchorVerifier.verifyAll(backed.map { it.locatorJson })
        return backed.associate { it.id to (verdicts[it.locatorJson]?.status ?: AnchorStatus.UNPLACED) }
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
        try { annotations.addEpubBookmark(id, locator, progress) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = UiMessage.Resource(R.string.bookmark_save_failed)) } }
    }

    /** The annotation a Notes-hub jump names, or null when it is gone or belongs to another publication. */
    suspend fun annotation(annotationId: String): Annotation? = annotations.get(annotationId)?.takeIf { it.libraryItemId == id }

    /** Explains a Notes-hub jump that could not be completed; the annotation itself is untouched. */
    fun reportOpenFailed() = notice(R.string.notes_open_failed)

    fun deleteBookmark(bookmarkId: String) = viewModelScope.launch {
        try { annotations.delete(bookmarkId, AnnotationKind.BOOKMARK) } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = UiMessage.Resource(R.string.bookmark_delete_failed)) } }
    }

    // ---------------------------------------------------------------- Phase 4B: highlights and notes

    private fun notice(resource: Int, vararg args: Any) { _state.update { it.copy(notice = UiMessage.Resource(resource, args.toList())) } }
    fun dismissNotice() { _state.update { it.copy(notice = null) } }
    fun closeEditor() { _state.update { it.copy(editor = null) } }
    fun openEditor(target: AnnotationEditorTarget) { _state.update { it.copy(editor = target) } }

    /** Creates a HIGHLIGHT (default style, no body) from a native selection, after verifying its text against the rendition. */
    fun createHighlight(read: EpubSelectionRead) { viewModelScope.launch {
        val prepared = prepareSelection(read) ?: return@launch
        save(AnnotationDraft(id, AnnotationKind.HIGHLIGHT, LocatorFormat.READIUM_LOCATOR_1, prepared.locatorJson,
            prepared.progressSnapshot, prepared.orderKey, selectedText = prepared.selectedText, styleKey = AnnotationStyles.DEFAULT))
    } }

    /** Opens the note editor for a native selection. A selection that cannot be saved is rejected with a message instead. */
    fun beginSelectionNote(read: EpubSelectionRead) { viewModelScope.launch {
        val prepared = prepareSelection(read) ?: return@launch
        openEditor(AnnotationEditorTarget.NewSelectionNote(prepared))
    } }

    /** Opens the note editor at the reader's current authoritative locator; needs no text selection. */
    fun beginPositionNote(locatorJson: String) {
        if (!locatorWithinCap(locatorJson)) { notice(R.string.annotation_selection_too_large); return }
        val total = EpubAnchorVerifier.totalProgressionOf(locatorJson)
        openEditor(AnnotationEditorTarget.NewPositionNote(locatorJson, progressSnapshotOf(total), orderKeyOf(total)))
    }

    /** Maps and verifies a selection. Null (with a localized notice already posted) when it cannot become an annotation. */
    private suspend fun prepareSelection(read: EpubSelectionRead): EpubSelectionDraft? {
        val provisional = when (val result = buildSelectionDraft(read)) {
            is SelectionDraftResult.Rejected -> {
                notice(if (result.reason == SelectionRejection.LOCATOR_TOO_LARGE) R.string.annotation_selection_too_large else R.string.annotation_selection_no_text)
                return null
            }
            is SelectionDraftResult.Ready -> result.draft
        }
        val session = _state.value.session
        val verdict = session?.anchorVerifier?.verify(read.locatorJson)
        if (session == null || verdict == null || verdict.status != AnchorStatus.PLACEABLE) { notice(R.string.annotation_selection_unanchorable); return null }
        val fraction = verdict.textFraction
        val href = EpubAnchorVerifier.parseAnchor(read.locatorJson)?.href
        val range = if (fraction != null && href != null) session.resourceRange(href) else null
        val orderKey = if (fraction != null && range != null) (range.start + fraction * (range.endInclusive - range.start)).coerceIn(0.0, 1.0)
            else orderKeyOf(read.totalProgression)
        return provisional.copy(orderKey = orderKey)
    }

    private suspend fun save(draft: AnnotationDraft): Boolean =
        try { annotations.create(draft); true } catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice(R.string.annotation_save_failed); false }

    /** Saves a new note's required body. The text is never truncated: an over-long or blank body is refused with a message. */
    fun saveNewNote(target: AnnotationEditorTarget, body: String) { viewModelScope.launch {
        if (body.isBlank()) { notice(R.string.annotation_note_empty); return@launch }
        if (body.length > AnnotationLimits.MAX_BODY) { notice(R.string.annotation_note_too_long, AnnotationLimits.MAX_BODY); return@launch }
        val saved = when (target) {
            is AnnotationEditorTarget.NewSelectionNote -> save(AnnotationDraft(id, AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1,
                target.draft.locatorJson, target.draft.progressSnapshot, target.draft.orderKey, selectedText = target.draft.selectedText, body = body))
            is AnnotationEditorTarget.NewPositionNote -> save(AnnotationDraft(id, AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1,
                target.locatorJson, target.progressSnapshot, target.orderKey, body = body))
            is AnnotationEditorTarget.Existing -> false
        }
        if (saved) closeEditor()
    } }

    /**
     * Edits an existing annotation: a HIGHLIGHT's style and optional [body] (blank removes it), or a NOTE's required
     * body. The kind never changes, so a highlight that gains a body stays a highlight.
     */
    fun updateAnnotation(annotationId: String, styleKey: String?, body: String) { viewModelScope.launch {
        val current = _state.value.annotations.firstOrNull { it.id == annotationId } ?: return@launch
        if (body.length > AnnotationLimits.MAX_BODY) { notice(R.string.annotation_note_too_long, AnnotationLimits.MAX_BODY); return@launch }
        if (current.kind == AnnotationKind.NOTE && body.isBlank()) { notice(R.string.annotation_note_empty); return@launch }
        val newBody = if (current.kind == AnnotationKind.NOTE) body else body.takeIf { it.isNotBlank() }
        val newStyle = if (current.kind == AnnotationKind.HIGHLIGHT) (styleKey ?: current.styleKey) else current.styleKey
        try {
            annotations.update(annotationId, AnnotationEdit(current.title, current.selectedText, newBody, newStyle))
            closeEditor()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice(R.string.annotation_update_failed) }
    } }

    /** Deletes the canonical row (and therefore its decoration). Never touches the inert legacy bookmark table. */
    fun deleteAnnotation(annotationId: String) { viewModelScope.launch {
        try { annotations.delete(annotationId); closeEditor() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice(R.string.annotation_delete_failed) }
    } }

    fun search(session: EpubSession, query: String) = searchCoordinator.search(query) { openSearchCursor(session, it) }

    fun clearSearch() = searchCoordinator.clear()

    private fun persistPreferences(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = UiMessage.Resource(R.string.reader_preferences_save_failed)) } }
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
