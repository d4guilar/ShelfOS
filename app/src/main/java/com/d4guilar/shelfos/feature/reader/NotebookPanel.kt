// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.onKeyEvent
import com.d4guilar.shelfos.core.input.InputContext
import com.d4guilar.shelfos.core.input.ShelfCommand
import com.d4guilar.shelfos.core.input.shelfCommand
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.designsystem.resolve
import com.d4guilar.shelfos.core.reader.FoldRect
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.FixedPageLocator
import com.d4guilar.shelfos.domain.annotations.FixedPageNavigation
import com.d4guilar.shelfos.domain.annotations.fixedPageNavigation

/**
 * Compose buttons activate on Enter and D-pad centre only. Gamepad A is the semantic CONFIRM command, so a focused control
 * also activates on it (on key up, like `shelfAction`). Enter and D-pad centre are consumed by the button itself first, so
 * nothing fires twice. Never remaps or consumes A anywhere except on the focused control it is applied to.
 */
internal fun Modifier.activateOnGamepadConfirm(enabled: Boolean = true, onActivate: () -> Unit): Modifier = onKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (enabled && native.shelfCommand(InputContext.READER) == ShelfCommand.CONFIRM) {
        if (native.action == android.view.KeyEvent.ACTION_UP) onActivate()
        true
    } else false
}

/** Whether activating a Notebook entry may move the reader. A non-available entry is still listed, editable and deletable. */
enum class NotebookJump { AVAILABLE, PENDING, UNAVAILABLE }

/**
 * One row of the reader-local Publication Notebook, format-neutral: kind, optional 1-based [page] label, optional body
 * preview, and what activating it may do. The panel never assumes an entry is text; a later reader (EPUB, Highlights)
 * supplies its own mapper and reuses [NotebookPanel] unchanged.
 */
data class NotebookEntry(
    val id: String, val kind: AnnotationKind, val page: Int?, val body: String?,
    val jump: NotebookJump, val unavailableReason: UiMessage?, val editable: Boolean,
)

/** Maps canonical fixed-page annotations (already in reading order) to rows. Strict: only [FixedPageNavigation.Navigable] may jump. */
fun fixedNotebookEntries(annotations: List<Annotation>, pageCount: Int): List<NotebookEntry> = annotations.map { a ->
    val nav = fixedPageNavigation(a.locatorJson, pageCount)
    NotebookEntry(a.id, a.kind, FixedPageLocator.parse(a.locatorJson)?.plus(1), a.body?.takeIf { it.isNotBlank() },
        jump = when (nav) {
            is FixedPageNavigation.Navigable -> NotebookJump.AVAILABLE
            FixedPageNavigation.Pending -> NotebookJump.PENDING
            else -> NotebookJump.UNAVAILABLE
        },
        unavailableReason = when (nav) {
            FixedPageNavigation.Malformed -> UiMessage.Resource(R.string.notebook_location_malformed)
            is FixedPageNavigation.OutOfRange -> UiMessage.Resource(R.string.notebook_location_out_of_range, listOf(nav.page + 1, nav.pageCount))
            else -> null
        },
        editable = a.kind == AnnotationKind.NOTE)
}

/**
 * The Notebook list for ONE publication: an optional "add note" action, filter chips, a count and the rows. It is plain
 * content (no window), so a dialog hosts it today and a side pane can host the same content later. Jump, Edit and Delete
 * are separate focusable buttons, so touch, keyboard and gamepad reach every action; kind and location are always text.
 */
@Composable
fun NotebookPanel(
    entries: List<NotebookEntry>, filters: List<AnnotationFilter>, addNoteLabel: String, canAddNote: Boolean,
    onAddNote: () -> Unit, onJump: (String) -> Unit, onEdit: (String) -> Unit, onDelete: (String) -> Unit,
) {
    var filterOrdinal by rememberSaveable { mutableIntStateOf(AnnotationFilter.ALL.ordinal) }
    val filter = AnnotationFilter.entries[filterOrdinal].takeIf { it in filters } ?: AnnotationFilter.ALL
    val tokens = LocalShelfTokens.current
    val rowTemplate = stringResource(R.string.content_desc_annotation_row)
    val editTemplate = stringResource(R.string.content_desc_notebook_edit)
    val deleteTemplate = stringResource(R.string.content_desc_notebook_delete)
    val editPlain = stringResource(R.string.content_desc_annotation_edit)
    val deletePlain = stringResource(R.string.content_desc_annotation_delete)
    val pageTemplate = stringResource(R.string.notebook_row_page)
    val visible = entries.filter { filter.acceptsKind(it.kind) }
    // A dialog owns its window: take focus once that window has it, so Back/Escape/gamepad B and D-pad reach the Notebook.
    val initialFocus = remember { FocusRequester() }
    val window = LocalWindowInfo.current
    LaunchedEffect(window.isWindowFocused) { if (window.isWindowFocused) runCatching { initialFocus.requestFocus() } }
    Column(Modifier.fillMaxWidth().focusRequester(initialFocus).focusable().testTag("notebook_panel")) {
        TextButton(onAddNote, enabled = canAddNote, modifier = Modifier.testTag("notebook_add_note").activateOnGamepadConfirm(canAddNote, onAddNote)) { Text(addNoteLabel) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { value -> FilterChip(filter == value, { filterOrdinal = value.ordinal },
                label = { Text(stringResource(value.labelRes())) },
                leadingIcon = if (filter == value) { { Text("✓", Modifier.clearAndSetSemantics { }) } } else null,
                modifier = Modifier.testTag("notebook_filter_${value.name.lowercase()}").activateOnGamepadConfirm { filterOrdinal = value.ordinal }) }
        }
        Text(pluralStringResource(R.plurals.annotations_count, visible.size, visible.size), color = tokens.colors.secondary, style = tokens.typography.labelSmall)
        if (visible.isEmpty()) Text(stringResource(if (entries.isEmpty()) R.string.annotations_empty else R.string.annotations_empty_filtered),
            Modifier.padding(vertical = 16.dp).testTag("notebook_empty"))
        else LazyColumn(Modifier.heightIn(max = 420.dp).testTag("notebook_list")) {
            items(visible, key = { it.id }) { entry ->
                val kind = stringResource(entry.kind.labelRes())
                val where = entry.page?.let { String.format(pageTemplate, it) }
                val reason = entry.unavailableReason?.resolve()
                val details = listOfNotNull(where, entry.body, reason).joinToString(", ")
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("notebook_row"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).activateOnGamepadConfirm(entry.jump == NotebookJump.AVAILABLE) { onJump(entry.id) }
                        .clickable(enabled = entry.jump == NotebookJump.AVAILABLE, role = Role.Button) { onJump(entry.id) }
                        .semantics(mergeDescendants = true) { contentDescription = String.format(rowTemplate, kind, details.ifEmpty { kind }) }
                        .padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
                        Text(listOfNotNull(kind, where).joinToString(" · "), fontWeight = FontWeight.Bold)
                        entry.body?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = tokens.colors.secondary) }
                        reason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = tokens.typography.labelSmall, modifier = Modifier.testTag("notebook_unavailable")) }
                    }
                    if (entry.editable) TextButton({ onEdit(entry.id) }, Modifier.testTag("notebook_edit").activateOnGamepadConfirm { onEdit(entry.id) }.semantics {
                        contentDescription = entry.page?.let { String.format(editTemplate, kind, it) } ?: String.format(editPlain, kind)
                    }) { Text(stringResource(R.string.action_edit)) }
                    TextButton({ onDelete(entry.id) }, Modifier.testTag("notebook_delete").activateOnGamepadConfirm { onDelete(entry.id) }.semantics {
                        contentDescription = entry.page?.let { String.format(deleteTemplate, kind, it) } ?: String.format(deletePlain, kind)
                    }) { Text(stringResource(R.string.action_delete)) }
                }
            }
        }
    }
}

/**
 * Hosts the Notebook in the reader's established dialog pattern: a platform dialog normally, the hinge-safe in-reader
 * overlay when a vertical fold constrains the reader ([safePane] non-null), exactly like Appearance and Pages.
 */
@Composable
fun NotebookDialog(safePane: FoldRect?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val title = stringResource(R.string.notebook_title)
    if (safePane != null) HingeSafeDialogOverlay(paneWindow = safePane, onDismissRequest = onDismiss) {
        Column(Modifier.padding(24.dp).widthIn(max = 360.dp).testTag("notebook_hinge_safe_content")) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            content()
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss, Modifier.testTag("notebook_close").activateOnGamepadConfirm(onActivate = onDismiss)) { Text(stringResource(R.string.action_close)) }
            }
        }
    } else AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.dismissOnShelfBack(onDismiss), title = { Text(title) }, text = { content() },
        confirmButton = { TextButton(onDismiss, Modifier.testTag("notebook_close").activateOnGamepadConfirm(onActivate = onDismiss)) { Text(stringResource(R.string.action_close)) } })
}

private fun AnnotationFilter.labelRes() = when (this) {
    AnnotationFilter.ALL -> R.string.annotations_filter_all
    AnnotationFilter.NOTES -> R.string.annotations_filter_notes
    AnnotationFilter.HIGHLIGHTS -> R.string.annotations_filter_highlights
    AnnotationFilter.BOOKMARKS -> R.string.annotations_filter_bookmarks
}

private fun AnnotationFilter.acceptsKind(kind: AnnotationKind) = when (this) {
    AnnotationFilter.ALL -> true
    AnnotationFilter.NOTES -> kind == AnnotationKind.NOTE
    AnnotationFilter.HIGHLIGHTS -> kind == AnnotationKind.HIGHLIGHT
    AnnotationFilter.BOOKMARKS -> kind == AnnotationKind.BOOKMARK
}

private fun AnnotationKind.labelRes() = when (this) {
    AnnotationKind.BOOKMARK -> R.string.annotation_kind_bookmark
    AnnotationKind.HIGHLIGHT -> R.string.annotation_kind_highlight
    AnnotationKind.NOTE -> R.string.annotation_kind_note
}
