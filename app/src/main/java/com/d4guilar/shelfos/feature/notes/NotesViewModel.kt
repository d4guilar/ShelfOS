// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.notes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.data.annotations.AnnotationRepository
import com.d4guilar.shelfos.data.annotations.NOTES_PAGE_SIZE
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.feature.reader.AnnotationFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** One presentable entry of the hub list. Keys are stable so scroll position survives list updates. */
sealed interface NotesItem {
    val key: String
    /** A publication group header (publication sort, and once above a single-publication filter). [total] is the publication's overall count. */
    data class Header(val source: AnnotationSource, val total: Int) : NotesItem { override val key get() = "pub:${source.libraryItemId}" }
    /** [page] is the 1-based fixed page, parsed once when the row is built (never per recomposition); null for EPUB rows. */
    data class Row(val item: AnnotationWithSource, val page: Int?) : NotesItem { override val key get() = "note:${item.annotation.id}" }
}

data class NotesUiState(
    val query: String = "", val filter: AnnotationFilter = AnnotationFilter.ALL, val publicationId: String? = null, val sort: NotesSort = NotesSort.PUBLICATION,
    val items: List<NotesItem> = emptyList(), val publications: List<NotesPublication> = emptyList(),
    /** False until the first read completes, so "empty" is never shown before the data arrived. */
    val loaded: Boolean = false, val hasMore: Boolean = false,
    /** How many annotations match the current filters (all pages, not just the loaded ones). */
    val total: Int = 0,
    /** The annotation being edited, loaded in full (list rows are never the source of an edit). */
    val editing: Annotation? = null, val deleteId: String? = null, val deleteOrphanGroupId: String? = null,
    val notice: UiMessage? = null,
) {
    val filtering: Boolean get() = query.isNotBlank() || filter != AnnotationFilter.ALL || publicationId != null
}

/**
 * The global Notes hub: every annotation across the library, read through bounded keyset pages (no Paging dependency).
 * Search text, kind filter, publication filter and sort live in [SavedStateHandle], so ordinary recreation keeps them; a
 * committed edit is already in Room. Reloads on any annotation/snapshot/library change keep as many rows as are already
 * shown, so an edit or delete never throws the reader back to the top of a long list.
 */
@OptIn(FlowPreview::class)
class NotesViewModel(private val annotations: AnnotationRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val query = saved.getStateFlow(KEY_QUERY, "")
    private val filter = saved.getStateFlow(KEY_FILTER, AnnotationFilter.ALL.ordinal)
    private val publication = saved.getStateFlow<String?>(KEY_PUBLICATION, null)
    private val sort = saved.getStateFlow(KEY_SORT, NotesSort.PUBLICATION.ordinal)
    // Seeded from saved state so the very first frame already shows the restored controls (no flash of defaults, no spurious scroll reset).
    private val _state = MutableStateFlow(NotesUiState(
        query = saved[KEY_QUERY] ?: "", filter = AnnotationFilter.entries.getOrElse(saved[KEY_FILTER] ?: 0) { AnnotationFilter.ALL },
        publicationId = saved[KEY_PUBLICATION], sort = NotesSort.entries.getOrElse(saved[KEY_SORT] ?: 0) { NotesSort.PUBLICATION },
        deleteId = saved[KEY_DELETE], deleteOrphanGroupId = saved[KEY_DELETE_GROUP]))
    val state = _state.asStateFlow()

    private var rows: List<AnnotationWithSource> = emptyList()
    private var next: NotesCursor? = null
    private var current = NotesQuery()
    private var loading: Job? = null
    private var generation = 0

    init {
        // Controls show at once; the database is only asked after typing pauses.
        viewModelScope.launch {
            combine(query, filter, publication, sort) { q, f, p, s -> NotesControls(q, f, p, s) }.collect { c ->
                _state.update { it.copy(query = c.query, filter = c.filterValue(), publicationId = c.publicationId, sort = c.sortValue()) }
            }
        }
        viewModelScope.launch {
            combine(query.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }, filter, publication, sort) { q, f, p, s -> NotesControls(q, f, p, s).toQuery() }
                .distinctUntilChanged().collect { reload(it, reset = true) }
        }
        // Edits, deletes and removals elsewhere change the same tables; keep what is already loaded.
        viewModelScope.launch { annotations.observeChanges().drop(1).collect { reload(current, reset = false) } }
        viewModelScope.launch {
            annotations.observePublications().collect { list ->
                _state.update { it.copy(publications = list) }
                // A publication filter whose last annotation is gone would show an empty list for no visible reason.
                val selected = publication.value
                if (selected != null && list.none { it.source.libraryItemId == selected }) selectPublication(null) else rebuildItems()
            }
        }
    }

    private data class NotesControls(val query: String, val filter: Int, val publicationId: String?, val sort: Int) {
        fun filterValue() = AnnotationFilter.entries.getOrElse(filter) { AnnotationFilter.ALL }
        fun sortValue() = NotesSort.entries.getOrElse(sort) { NotesSort.PUBLICATION }
        fun toQuery() = NotesQuery(
            kinds = when (filterValue()) {
                AnnotationFilter.ALL -> null
                AnnotationFilter.NOTES -> setOf(AnnotationKind.NOTE)
                AnnotationFilter.HIGHLIGHTS -> setOf(AnnotationKind.HIGHLIGHT)
                AnnotationFilter.BOOKMARKS -> setOf(AnnotationKind.BOOKMARK)
            }, publicationId = publicationId, text = query, sort = sortValue())
    }

    fun search(text: String) { saved[KEY_QUERY] = text }
    fun selectFilter(value: AnnotationFilter) { saved[KEY_FILTER] = value.ordinal }
    fun selectPublication(id: String?) { saved[KEY_PUBLICATION] = id }
    fun selectSort(value: NotesSort) { saved[KEY_SORT] = value.ordinal }
    fun dismissNotice() { _state.update { it.copy(notice = null) } }

    /** Loads the first page again ([reset] = back to one page, otherwise as many rows as are already shown) and replaces the list. */
    private fun reload(criteria: NotesQuery, reset: Boolean) {
        loading?.cancel()
        val mine = ++generation
        val limit = if (reset) NOTES_PAGE_SIZE else maxOf(NOTES_PAGE_SIZE, rows.size)
        loading = viewModelScope.launch {
            try {
                val page = annotations.notes(criteria, null, limit)
                val total = annotations.countNotes(criteria)
                if (mine != generation) return@launch
                current = criteria; rows = page.rows; next = page.next
                _state.update { it.copy(loaded = true, hasMore = page.next != null, total = total) }
                rebuildItems()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (mine == generation) _state.update { it.copy(loaded = true, notice = UiMessage.Resource(R.string.notes_load_failed)) } }
        }
    }

    /** Appends the next keyset page. A no-op while a load is running or when everything is already loaded. */
    fun loadMore() {
        val cursor = next ?: return
        if (loading?.isActive == true) return
        val mine = generation
        loading = viewModelScope.launch {
            try {
                val page = annotations.notes(current, cursor, NOTES_PAGE_SIZE)
                if (mine != generation) return@launch
                rows = rows + page.rows; next = page.next
                _state.update { it.copy(hasMore = page.next != null) }
                rebuildItems()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (mine == generation) _state.update { it.copy(notice = UiMessage.Resource(R.string.notes_load_failed)) } }
        }
    }

    /** Rows with publication headers: at every group change in the publication sort, or once at the top for a single-publication filter. */
    private fun rebuildItems() {
        val totals = _state.value.publications.associate { it.source.libraryItemId to it.total }
        val grouped = current.sort == NotesSort.PUBLICATION
        val out = ArrayList<NotesItem>(rows.size + 16)
        var last: String? = null
        for (row in rows) {
            val id = row.source.libraryItemId
            if ((grouped || current.publicationId != null) && (if (grouped) id != last else last == null)) out += NotesItem.Header(row.source, totals[id] ?: 0)
            last = id
            val a = row.annotation
            out += NotesItem.Row(row, if (a.locatorFormat == LocatorFormat.FIXED_PAGE_1) FixedPageLocator.parse(a.locatorJson)?.plus(1) else null)
        }
        // A single-publication filter keeps its header (and, for a removed publication, the group action) even when search matches nothing.
        if (rows.isEmpty()) _state.value.publications.firstOrNull { it.source.libraryItemId == current.publicationId }
            ?.let { out += NotesItem.Header(it.source, it.total) }
        _state.update { it.copy(items = out) }
    }

    // ------------------------------------------------------------------ edit / delete

    /** Loads the full annotation and opens the editor; a BOOKMARK has nothing to edit. */
    fun beginEdit(id: String) { viewModelScope.launch {
        try {
            val full = annotations.get(id) ?: return@launch
            if (full.kind != AnnotationKind.BOOKMARK) _state.update { it.copy(editing = full) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { notice(R.string.annotation_update_failed) }
    } }

    fun closeEditor() { _state.update { it.copy(editing = null) } }

    /**
     * Saves an edit. A NOTE keeps a required body; a HIGHLIGHT keeps its kind and gets an optional body (blank clears it)
     * and its chosen style. Kind, locator and publication never change.
     */
    fun saveEdit(body: String, styleKey: String?) {
        val target = _state.value.editing ?: return
        if (body.length > AnnotationLimits.MAX_BODY) { notice(R.string.annotation_note_too_long, AnnotationLimits.MAX_BODY); return }
        if (target.kind == AnnotationKind.NOTE && body.isBlank()) { notice(R.string.annotation_note_empty); return }
        val newBody = if (target.kind == AnnotationKind.NOTE) body else body.takeIf { it.isNotBlank() }
        val newStyle = if (target.kind == AnnotationKind.HIGHLIGHT) (styleKey ?: target.styleKey) else target.styleKey
        viewModelScope.launch {
            try {
                annotations.update(target.id, AnnotationEdit(target.title, target.selectedText, newBody, newStyle))
                closeEditor()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice(R.string.annotation_update_failed) }
        }
    }

    fun requestDelete(id: String) { saved[KEY_DELETE] = id; _state.update { it.copy(deleteId = id) } }
    fun cancelDelete() { saved[KEY_DELETE] = null; _state.update { it.copy(deleteId = null) } }
    fun confirmDelete() {
        val id = _state.value.deleteId ?: return
        cancelDelete()
        viewModelScope.launch {
            try { annotations.delete(id) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice(R.string.annotation_delete_failed) }
        }
    }

    fun requestDeleteOrphanGroup(libraryItemId: String) { saved[KEY_DELETE_GROUP] = libraryItemId; _state.update { it.copy(deleteOrphanGroupId = libraryItemId) } }
    fun cancelDeleteOrphanGroup() { saved[KEY_DELETE_GROUP] = null; _state.update { it.copy(deleteOrphanGroupId = null) } }
    /** Explicit, user-confirmed group deletion; the repository refuses while the item is still in the library. */
    fun confirmDeleteOrphanGroup() {
        val id = _state.value.deleteOrphanGroupId ?: return
        cancelDeleteOrphanGroup()
        viewModelScope.launch {
            try { annotations.deleteOrphanedPublication(id) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice(R.string.annotation_delete_failed) }
        }
    }

    private fun notice(resource: Int, vararg args: Any) { _state.update { it.copy(notice = UiMessage.Resource(resource, args.toList())) } }

    private companion object {
        const val KEY_QUERY = "notes.query"
        const val KEY_FILTER = "notes.filter"
        const val KEY_PUBLICATION = "notes.publication"
        const val KEY_SORT = "notes.sort"
        const val KEY_DELETE = "notes.delete"
        const val KEY_DELETE_GROUP = "notes.deleteGroup"
        const val SEARCH_DEBOUNCE_MILLIS = 200L
    }
}
