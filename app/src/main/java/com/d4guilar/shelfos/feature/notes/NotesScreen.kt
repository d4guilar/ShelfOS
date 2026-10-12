// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.resolve
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.reader.AnnotationDeleteConfirmDialog
import com.d4guilar.shelfos.feature.reader.AnnotationEditorDialog
import com.d4guilar.shelfos.feature.reader.AnnotationFilter
import com.d4guilar.shelfos.feature.reader.activateOnGamepadConfirm
import com.d4guilar.shelfos.feature.reader.dismissOnShelfBack
import com.d4guilar.shelfos.feature.reader.styleLabel
import com.d4guilar.shelfos.core.reader.AnnotationStyles
import kotlinx.coroutines.delay

/** Width at which the controls share one row instead of stacking (the existing 840dp adaptive threshold family; no window-size-class dependency). */
private val WIDE_CONTROLS = 840.dp
private const val LOAD_MORE_AHEAD = 20

/**
 * The global Notes destination: every note, highlight and bookmark across the library, with kind filter, search,
 * publication filter and sort. [onOpenSource] is invoked only for rows whose publication is still in the library;
 * orphans (kept after a removal) can be read, edited and deleted but never open anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesScreen(vm: NotesViewModel, onOpenSource: (libraryItemId: String, annotationId: String, format: PublicationFormat?) -> Unit,
    modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = LocalShelfTokens.current
    val listState = rememberLazyListState()
    var picking by rememberSaveable { mutableStateOf(false) }
    state.notice?.let { notice -> LaunchedEffect(notice) { delay(6_000); vm.dismissNotice() } }
    // A new filter, sort, publication or search starts at the top of the new result; recreation keeps the saved position.
    val criteria = listOf(state.query.trim(), state.filter.name, state.publicationId, state.sort.name).joinToString("|")
    var lastCriteria by rememberSaveable { mutableStateOf(criteria) }
    var scrollToTop by remember { mutableStateOf(false) }
    LaunchedEffect(criteria) { if (criteria != lastCriteria) { lastCriteria = criteria; scrollToTop = true } }
    LaunchedEffect(state.items) { if (scrollToTop) { scrollToTop = false; listState.scrollToItem(0) } }
    LaunchedEffect(listState, vm) {
        snapshotFlow { listState.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: -1) to it.totalItemsCount } }
            .collect { (last, total) -> if (last >= 0 && total - last <= LOAD_MORE_AHEAD) vm.loadMore() }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("notes_screen")) {
        val wide = maxWidth >= WIDE_CONTROLS
        Column(Modifier.fillMaxSize().padding(horizontal = t.spacing.medium), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nav_notes), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            val search: @Composable (Modifier) -> Unit = { m ->
                OutlinedTextField(state.query, vm::search, m.testTag("notes_search"), singleLine = true,
                    label = { Text(stringResource(R.string.notes_search_label)) },
                    trailingIcon = if (state.query.isEmpty()) null else { {
                        val clear = { vm.search("") }
                        TextButton(clear, Modifier.testTag("notes_search_clear").activateOnGamepadConfirm(onActivate = clear)) { Text(stringResource(R.string.action_clear)) }
                    } })
            }
            val publicationButton: @Composable () -> Unit = {
                val selected = state.publications.firstOrNull { it.source.libraryItemId == state.publicationId }
                val label = selected?.let { publicationTitle(it.source) } ?: stringResource(R.string.notes_publication_all)
                val open = { picking = true }
                OutlinedButton(open, Modifier.testTag("notes_publication_filter").activateOnGamepadConfirm(onActivate = open)) {
                    Text(stringResource(R.string.notes_publication_filter_label, label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                search(Modifier.weight(1f)); publicationButton()
            } else { search(Modifier.fillMaxWidth()); publicationButton() }
            // Wraps instead of scrolling sideways: every filter and sort is on screen, reachable by D-pad focus order, and never clipped.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                AnnotationFilter.entries.forEach { value -> ChoiceChip("notes_filter_${value.name.lowercase()}", stringResource(value.labelRes()), state.filter == value) { vm.selectFilter(value) } }
                NotesSort.entries.forEach { value -> ChoiceChip("notes_sort_${value.name.lowercase()}", stringResource(value.labelRes()), state.sort == value) { vm.selectSort(value) } }
            }
            if (state.loaded) Text(pluralStringResource(R.plurals.annotations_count, state.total, state.total), color = t.colors.secondary, style = t.typography.labelSmall,
                modifier = Modifier.testTag("notes_count"))
            state.notice?.let { notice -> Text(notice.resolve(), Modifier.fillMaxWidth().clickable { vm.dismissNotice() }.padding(vertical = 8.dp).testTag("notes_notice"),
                color = MaterialTheme.colorScheme.error) }
            when {
                !state.loaded -> Unit
                state.items.isEmpty() -> Text(stringResource(if (state.filtering) R.string.annotations_empty_filtered else R.string.annotations_empty),
                    Modifier.padding(vertical = 16.dp).testTag("notes_empty"))
                else -> LazyColumn(Modifier.weight(1f).widthIn(max = 840.dp).testTag("notes_list"), state = listState) {
                    items(state.items, key = { it.key }, contentType = { if (it is NotesItem.Header) 0 else 1 }) { item ->
                        when (item) {
                            is NotesItem.Header -> PublicationHeader(item, onDeleteGroup = { vm.requestDeleteOrphanGroup(item.source.libraryItemId) })
                            is NotesItem.Row -> NoteRow(item, showPublication = state.sort == NotesSort.DATE,
                                onOpen = { onOpenSource(item.item.source.libraryItemId, item.item.annotation.id, item.item.source.format) },
                                onEdit = { vm.beginEdit(item.item.annotation.id) }, onDelete = { vm.requestDelete(item.item.annotation.id) })
                        }
                    }
                }
            }
        }
    }
    if (picking) PublicationPicker(state.publications, state.publicationId, onSelect = { vm.selectPublication(it); picking = false }, onDismiss = { picking = false })
    state.editing?.let { annotation ->
        AnnotationEditorDialog("notes-edit-${annotation.id}",
            stringResource(if (annotation.kind == AnnotationKind.HIGHLIGHT) R.string.annotation_editor_highlight else R.string.annotation_editor_note),
            annotation.selectedText, annotation.body.orEmpty(), annotation.styleKey ?: AnnotationStyles.DEFAULT,
            styleEditable = annotation.kind == AnnotationKind.HIGHLIGHT, bodyRequired = annotation.kind == AnnotationKind.NOTE,
            onSave = { body, style -> vm.saveEdit(body, style) }, onDelete = null, onCancel = vm::closeEditor)
    }
    if (state.deleteId != null) AnnotationDeleteConfirmDialog(onConfirm = vm::confirmDelete, onCancel = vm::cancelDelete, bodyRes = R.string.notes_delete_confirm_body)
    state.deleteOrphanGroupId?.let { id ->
        val group = state.publications.firstOrNull { it.source.libraryItemId == id }
        val cancel = vm::cancelDeleteOrphanGroup
        val confirm = vm::confirmDeleteOrphanGroup
        AlertDialog(onDismissRequest = cancel, modifier = Modifier.dismissOnShelfBack(cancel),
            title = { Text(stringResource(R.string.notes_group_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.notes_group_delete_body, group?.total ?: 0, group?.total ?: 0, group?.let { publicationTitle(it.source) }.orEmpty())) },
            dismissButton = { TextButton(cancel, Modifier.activateOnGamepadConfirm(onActivate = cancel)) { Text(stringResource(R.string.action_cancel)) } },
            confirmButton = { TextButton(confirm, Modifier.testTag("notes_group_delete_confirm").activateOnGamepadConfirm(onActivate = confirm)) { Text(stringResource(R.string.action_delete)) } })
    }
}

@Composable
private fun ChoiceChip(tag: String, label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) }, modifier = Modifier.testTag(tag).activateOnGamepadConfirm(onActivate = onClick),
        leadingIcon = if (selected) { { Text("✓", Modifier.clearAndSetSemantics { }) } } else null)
}

@Composable
private fun publicationTitle(source: AnnotationSource): String = source.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.notes_publication_removed_untitled)

@Composable
private fun PublicationHeader(header: NotesItem.Header, onDeleteGroup: () -> Unit) {
    val t = LocalShelfTokens.current
    val source = header.source
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).semantics { heading() }.testTag("notes_header")) {
        Text(publicationTitle(source), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val creator = source.creator?.takeIf { it.isNotBlank() }
        Text(listOfNotNull(creator, pluralStringResource(R.plurals.notes_group_count, header.total, header.total)).joinToString(" · "),
            color = t.colors.secondary, style = t.typography.labelSmall)
        if (!source.live) {
            Text(stringResource(R.string.notes_group_removed), color = t.colors.secondary, style = t.typography.labelSmall, modifier = Modifier.testTag("notes_group_removed"))
            TextButton(onDeleteGroup, Modifier.testTag("notes_group_delete").activateOnGamepadConfirm(onActivate = onDeleteGroup)) { Text(stringResource(R.string.notes_group_delete)) }
        } else if (!source.available) Text(stringResource(R.string.original_file_unavailable_title), color = t.colors.secondary, style = t.typography.labelSmall)
    }
}

@Composable
private fun NoteRow(row: NotesItem.Row, showPublication: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val t = LocalShelfTokens.current
    val a = row.item.annotation
    val source = row.item.source
    val kind = stringResource(when {
        a.kind == AnnotationKind.HIGHLIGHT -> R.string.annotation_kind_highlight
        a.kind == AnnotationKind.BOOKMARK -> R.string.annotation_kind_bookmark
        a.locatorFormat == LocatorFormat.READIUM_LOCATOR_1 && a.selectedText == null -> R.string.annotation_kind_note_position
        else -> R.string.annotation_kind_note
    })
    val style = if (a.kind == AnnotationKind.HIGHLIGHT) styleLabel(a.styleKey) else null
    val body = a.body?.takeIf { it.isNotBlank() }
    // A user label (legacy bookmark labels; search matches it); user text is shown as written, never translated.
    val label = a.title?.takeIf { it.isNotBlank() }
    // Visual and spoken forms stay separate (the 4C.2 rule): "42%" is shown, "42 percent" is spoken.
    val locationVisual = row.page?.let { stringResource(R.string.notebook_row_page, it) }
        ?: if (a.locatorFormat == LocatorFormat.READIUM_LOCATOR_1) stringResource(R.string.reading_progress_visual, a.progressSnapshot) else null
    val locationSpoken = row.page?.let { stringResource(R.string.notebook_row_page, it) }
        ?: if (a.locatorFormat == LocatorFormat.READIUM_LOCATOR_1) stringResource(R.string.reading_progress_spoken, a.progressSnapshot) else null
    val publication = publicationTitle(source)
    val status = when {
        !source.live -> stringResource(R.string.notes_row_orphan)
        !source.available -> stringResource(R.string.original_file_unavailable_title)
        else -> null
    }
    val canOpen = source.live && source.format != null
    val spoken = listOfNotNull(style, label, a.selectedText?.takeIf { it.isNotBlank() }, body, publication, locationSpoken, status).joinToString(", ")
    val rowDescription = String.format(stringResource(R.string.content_desc_annotation_row), kind, spoken.ifEmpty { kind })
    val openLabel = stringResource(R.string.content_desc_notes_open, kind, publication)
    val editLabel = stringResource(R.string.content_desc_notes_edit, kind, publication)
    val deleteLabel = stringResource(R.string.content_desc_notes_delete, kind, publication)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("notes_row")) {
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).shelfAction(enabled = canOpen, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = rowDescription }.padding(vertical = 4.dp)) {
            Text(listOfNotNull(kind, style).joinToString(" · "), fontWeight = FontWeight.Bold)
            label?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            a.selectedText?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            body?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = t.colors.secondary) }
            val where = listOfNotNull(publication.takeIf { showPublication }, locationVisual).joinToString(" · ")
            if (where.isNotEmpty()) Text(where, color = t.colors.secondary, style = t.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.let { Text(it, color = t.colors.secondary, style = t.typography.labelSmall, modifier = Modifier.testTag("notes_row_status")) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (canOpen) TextButton(onOpen, Modifier.heightIn(min = 48.dp).testTag("notes_open").activateOnGamepadConfirm(onActivate = onOpen).semantics { contentDescription = openLabel }) {
                Text(stringResource(R.string.notes_open_source))
            }
            if (a.kind != AnnotationKind.BOOKMARK) TextButton(onEdit, Modifier.heightIn(min = 48.dp).testTag("notes_edit").activateOnGamepadConfirm(onActivate = onEdit).semantics { contentDescription = editLabel }) {
                Text(stringResource(R.string.action_edit))
            }
            TextButton(onDelete, Modifier.heightIn(min = 48.dp).testTag("notes_delete").activateOnGamepadConfirm(onActivate = onDelete).semantics { contentDescription = deleteLabel }) {
                Text(stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
private fun PublicationPicker(publications: List<NotesPublication>, selectedId: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.dismissOnShelfBack(onDismiss), title = { Text(stringResource(R.string.notes_publication_picker_title)) },
        text = {
            LazyColumn(Modifier.testTag("notes_publication_list")) {
                item(key = "all") { PickerRow(stringResource(R.string.notes_publication_all), selectedId == null, "notes_publication_option_all") { onSelect(null) } }
                items(publications, key = { it.source.libraryItemId }) { pub ->
                    val title = publicationTitle(pub.source)
                    val label = stringResource(if (pub.source.live) R.string.notes_publication_option else R.string.notes_publication_option_removed, title, pub.total)
                    PickerRow(label, pub.source.libraryItemId == selectedId, "notes_publication_option") { onSelect(pub.source.libraryItemId) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss, Modifier.activateOnGamepadConfirm(onActivate = onDismiss)) { Text(stringResource(R.string.action_close)) } })
}

@Composable
private fun PickerRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).shelfAction(selected = selected, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // The check is decoration: selection is also conveyed by the row's selected semantics, never by this glyph or color alone.
        Text(if (selected) "✓" else "", Modifier.width(24.dp).clearAndSetSemantics { })
        Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun AnnotationFilter.labelRes() = when (this) {
    AnnotationFilter.ALL -> R.string.annotations_filter_all
    AnnotationFilter.NOTES -> R.string.annotations_filter_notes
    AnnotationFilter.HIGHLIGHTS -> R.string.annotations_filter_highlights
    AnnotationFilter.BOOKMARKS -> R.string.annotations_filter_bookmarks
}

private fun NotesSort.labelRes() = when (this) {
    NotesSort.PUBLICATION -> R.string.notes_sort_publication
    NotesSort.DATE -> R.string.notes_sort_date
}
