// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.AnnotationLimits

/** Which annotations the review list shows. Persisted by ordinal across recreation. */
enum class AnnotationFilter { ALL, NOTES, HIGHLIGHTS, BOOKMARKS;
    fun accepts(annotation: Annotation) = when (this) {
        ALL -> true
        NOTES -> annotation.kind == AnnotationKind.NOTE
        HIGHLIGHTS -> annotation.kind == AnnotationKind.HIGHLIGHT
        BOOKMARKS -> annotation.kind == AnnotationKind.BOOKMARK
    }
}

@Composable
private fun kindLabel(annotation: Annotation): String = stringResource(when {
    annotation.kind == AnnotationKind.HIGHLIGHT -> R.string.annotation_kind_highlight
    annotation.kind == AnnotationKind.BOOKMARK -> R.string.annotation_kind_bookmark
    annotation.selectedText == null -> R.string.annotation_kind_note_position
    else -> R.string.annotation_kind_note
})

@Composable
internal fun styleLabel(styleKey: String?): String = stringResource(when (styleKey) {
    "amber" -> R.string.annotation_style_amber
    "green" -> R.string.annotation_style_green
    "blue" -> R.string.annotation_style_blue
    "rose" -> R.string.annotation_style_rose
    else -> R.string.annotation_style_unknown
})

/**
 * The reader-local review list for ONE publication (not the global Notes hub): notes, highlights and bookmarks in
 * reading order with a kind label, excerpt, note preview, style name, location and an "unplaced" state. Every row is a
 * real button (jump), with separate Edit and Delete buttons, so touch, keyboard and D-pad all reach every action.
 */
@Composable
fun AnnotationListDialog(
    annotations: List<Annotation>, anchors: Map<String, AnchorStatus>,
    presentationOf: (Annotation) -> EpubBookmarkPresentation,
    inlineSupport: InlineAnnotationSupport, canAddHere: Boolean, openFailed: Boolean,
    onAddHere: () -> Unit, onJump: (Annotation) -> Unit, onEdit: (Annotation) -> Unit, onDelete: (Annotation) -> Unit,
    onClose: () -> Unit,
) {
    var filterOrdinal by rememberSaveable { mutableIntStateOf(AnnotationFilter.ALL.ordinal) }
    val filter = AnnotationFilter.entries[filterOrdinal]
    val tokens = LocalShelfTokens.current
    val locationTemplate = stringResource(R.string.bookmark_location)
    val progressTemplate = stringResource(R.string.reading_progress_visual)
    val spokenTemplate = stringResource(R.string.reading_progress_spoken)
    val rowTemplate = stringResource(R.string.content_desc_annotation_row)
    val editTemplate = stringResource(R.string.content_desc_annotation_edit)
    val deleteTemplate = stringResource(R.string.content_desc_annotation_delete)
    val visible = annotations.filter(filter::accepts)
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.annotations_title)) }, text = {
        Column(Modifier.fillMaxWidth()) {
            TextButton(onAddHere, enabled = canAddHere, modifier = Modifier.testTag("annotation_add_here")) {
                Text(stringResource(R.string.annotation_add_note_here))
            }
            if (!inlineSupport.offersInlineAnnotations) Text(stringResource(R.string.annotation_inline_unavailable),
                Modifier.padding(vertical = 4.dp).testTag("annotation_inline_unavailable"), color = tokens.colors.secondary, style = tokens.typography.labelSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AnnotationFilter.ALL to R.string.annotations_filter_all, AnnotationFilter.NOTES to R.string.annotations_filter_notes,
                    AnnotationFilter.HIGHLIGHTS to R.string.annotations_filter_highlights, AnnotationFilter.BOOKMARKS to R.string.annotations_filter_bookmarks)
                    .forEach { (value, label) -> FilterChip(filter == value, { filterOrdinal = value.ordinal },
                        label = { Text(stringResource(label)) },
                        leadingIcon = if (filter == value) { { Text("✓", Modifier.clearAndSetSemantics { }) } } else null) }
            }
            Text(pluralStringResource(R.plurals.annotations_count, visible.size, visible.size), color = tokens.colors.secondary, style = tokens.typography.labelSmall)
            if (openFailed) Text(stringResource(R.string.annotation_open_failed), Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.error)
            if (visible.isEmpty()) Text(stringResource(if (annotations.isEmpty()) R.string.annotations_empty else R.string.annotations_empty_filtered),
                Modifier.padding(vertical = 16.dp).testTag("annotations_empty"))
            else LazyColumn(Modifier.heightIn(max = 420.dp).testTag("annotations_list")) {
                items(visible, key = { it.id }) { annotation ->
                    val kind = kindLabel(annotation)
                    val presentation = presentationOf(annotation)
                    val where = bookmarkDisplayText(presentation, { String.format(locationTemplate, it) }, { String.format(progressTemplate, it) }).replace('\n', ' ')
                    val spokenWhere = bookmarkAccessibilityText(presentation, { String.format(locationTemplate, it) }, { String.format(spokenTemplate, it) })
                    val unplaced = annotation.isSelectionBacked && anchors[annotation.id].let { it != null && it != AnchorStatus.PLACEABLE }
                    val style = if (annotation.kind == AnnotationKind.HIGHLIGHT) styleLabel(annotation.styleKey) else null
                    val details = listOfNotNull(style, annotation.selectedText, annotation.body, spokenWhere).joinToString(", ")
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("annotation_row"), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(role = Role.Button) { onJump(annotation) }
                            .semantics(mergeDescendants = true) { contentDescription = String.format(rowTemplate, kind, details) }
                            .padding(vertical = 4.dp)) {
                            Text(listOfNotNull(kind, style).joinToString(" · "), fontWeight = FontWeight.Bold)
                            annotation.selectedText?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            annotation.body?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = tokens.colors.secondary) }
                            Text(where, color = tokens.colors.secondary, style = tokens.typography.labelSmall)
                            if (unplaced) Text(stringResource(R.string.annotation_location_unavailable), color = MaterialTheme.colorScheme.error,
                                style = tokens.typography.labelSmall, modifier = Modifier.testTag("annotation_unplaced"))
                        }
                        if (annotation.kind != AnnotationKind.BOOKMARK) TextButton({ onEdit(annotation) },
                            Modifier.semantics { contentDescription = String.format(editTemplate, kind) }) {
                            Text(stringResource(R.string.action_edit))
                        }
                        TextButton({ onDelete(annotation) },
                            Modifier.semantics { contentDescription = String.format(deleteTemplate, kind) }) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClose) { Text(stringResource(R.string.action_close)) } })
}

/**
 * Create/edit surface. A NOTE needs a non-blank body ([bodyRequired]); a HIGHLIGHT lets the user change its style and add,
 * edit or clear an optional body. The body is never truncated: past [AnnotationLimits.MAX_BODY] the Save button is disabled
 * and the counter explains why. [onSave] receives the body and the chosen style key (null when [styleEditable] is false).
 */
@Composable
fun AnnotationEditorDialog(
    editorKey: String, title: String, quote: String?, initialBody: String, initialStyle: String?,
    styleEditable: Boolean, bodyRequired: Boolean,
    onSave: (body: String, styleKey: String?) -> Unit, onDelete: (() -> Unit)?, onCancel: () -> Unit,
) {
    var body by rememberSaveable(editorKey) { mutableStateOf(initialBody) }
    var style by rememberSaveable(editorKey) { mutableStateOf(initialStyle) }
    val tokens = LocalShelfTokens.current
    val tone = if (tokens.dark) ReaderTone.DARK else ReaderTone.LIGHT
    val tooLong = body.length > AnnotationLimits.MAX_BODY
    val canSave = !tooLong && (!bodyRequired || body.isNotBlank())
    val field = remember { FocusRequester() }
    val selectedTemplate = stringResource(R.string.annotation_style_selected)
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = {
        // A Dialog owns its own window: request focus only once that window has it, as the Search dialog does.
        val window = LocalWindowInfo.current
        LaunchedEffect(window.isWindowFocused) { if (window.isWindowFocused) runCatching { field.requestFocus() } }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quote?.let { Text(it, maxLines = 6, overflow = TextOverflow.Ellipsis, color = tokens.colors.secondary, modifier = Modifier.testTag("annotation_editor_quote")) }
            if (styleEditable) {
                Text(stringResource(R.string.annotation_style_label), style = tokens.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnnotationStyles.keys.forEach { key ->
                        val label = styleLabel(key)
                        val selected = style == key
                        FilterChip(selected, { style = key }, label = { Text(label) },
                            leadingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selected) Text("✓ ", Modifier.clearAndSetSemantics { })
                                Box(Modifier.size(14.dp).background(Color(AnnotationStyles.tint(key, tone)), CircleShape).clearAndSetSemantics { })
                            } },
                            modifier = Modifier.testTag("annotation_style_$key").semantics {
                                if (selected) contentDescription = String.format(selectedTemplate, label)
                            })
                    }
                }
            }
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(field).testTag("annotation_body"),
                label = { Text(stringResource(if (bodyRequired) R.string.annotation_note_label else R.string.annotation_highlight_note_label)) },
                isError = tooLong)
            if (body.length > AnnotationLimits.MAX_BODY * 9 / 10) Text(
                String.format(stringResource(R.string.annotation_note_counter), body.length, AnnotationLimits.MAX_BODY),
                color = if (tooLong) MaterialTheme.colorScheme.error else tokens.colors.secondary, style = tokens.typography.labelSmall)
            onDelete?.let { TextButton(it, Modifier.testTag("annotation_editor_delete")) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) } }
        }
    }, dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton({ onSave(body, if (styleEditable) style else null) }, enabled = canSave, modifier = Modifier.testTag("annotation_editor_save")) {
            Text(stringResource(R.string.action_save))
        } })
}

@Composable
fun AnnotationDeleteConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.annotation_delete_confirm_title)) },
        text = { Text(stringResource(R.string.annotation_delete_confirm_body)) },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(onConfirm, Modifier.testTag("annotation_delete_confirm")) { Text(stringResource(R.string.action_delete)) } })
}
