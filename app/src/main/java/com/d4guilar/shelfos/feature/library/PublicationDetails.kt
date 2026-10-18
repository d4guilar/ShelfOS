// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.LocalCoverArtwork
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.domain.library.CoverLocalSource
import com.d4guilar.shelfos.domain.library.CoverRepresentation
import kotlinx.coroutines.flow.flowOf
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.annotations.KnowledgePolicy
import com.d4guilar.shelfos.feature.reader.activateOnGamepadConfirm
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.MetadataValues
import com.d4guilar.shelfos.feature.importing.PublicationEditor
import com.d4guilar.shelfos.feature.importing.formatSize
import com.d4guilar.shelfos.feature.importing.originLabel

/**
 * [focusPrimaryAction]: a dedicated details screen takes keyboard focus on Read so focus is never lost on entry.
 * [onRead] receives this cover's current on-screen position (window coordinates), for the reader-entry cover
 * transition; null when it has not been measured yet (the read action is still usable either way).
 */
@Composable
fun PublicationDetails(item: LibraryItem, favorite: Boolean, onFavorite: () -> Unit, modifier: Modifier = Modifier,
    onRead: (Rect?) -> Unit, onEdit: (String, String, MediaCategory) -> Unit, onRemove: (KnowledgePolicy) -> Unit, focusPrimaryAction: Boolean = false,
    /** Saves manual metadata edits (original, edited); null hides the metadata fields in the editor. */
    onEditMetadata: ((MetadataValues, MetadataValues) -> Unit)? = null,
    /** Canonical annotation counts for this item; null while still loading, which keeps removal unconfirmable (never remove with an undisclosed choice). */
    knowledgeCounts: AnnotationCounts?) {
    val t = LocalShelfTokens.current
    var details by rememberSaveable(item.id) { mutableStateOf(false) }
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    var removing by rememberSaveable(item.id) { mutableStateOf(false) }
    var coverOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    val userCovers = LocalUserCovers.current
    if (coverOpen && userCovers != null) CoverDialog(item, userCovers) { coverOpen = false }
    var coverBounds by remember(item.id) { mutableStateOf<Rect?>(null) }
    val readFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(Unit) { if (focusPrimaryAction && inputMode == InputMode.Keyboard) runCatching { readFocus.requestFocus() } }
    val metadata = rememberStoredMetadata(item.id)
    // Captured when Edit is pressed and never refreshed from Room while editing (H-1); per item, survives recreation.
    var editSnapshot by rememberSaveable(item.id) { mutableStateOf<List<String>?>(null) }
    if (editing) PublicationEditor(item, stringResource(R.string.action_save_changes), { editing = false; editSnapshot = null }, { title, creator, category ->
        onEdit(title, creator, category); editing = false; editSnapshot = null
    }, metadata = if (onEditMetadata != null) editSnapshot?.let(::snapshotValues) else null,
        onSaveMetadata = { original, edited -> onEditMetadata?.invoke(original, edited) })
    val hasKnowledge = knowledgeCounts != null && knowledgeCounts.total > 0
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text(stringResource(R.string.dialog_remove_title)) },
        text = {
            val body = stringResource(R.string.dialog_remove_body)
            val copyNote = item.managedPath?.let { stringResource(R.string.dialog_remove_private_copy_note, formatSize(item.byteSize)) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                if (hasKnowledge && knowledgeCounts != null) KnowledgeRemovalChoice(knowledgeCounts)
                if (copyNote != null) Text(copyNote)
            }
        },
        confirmButton = {
            // With saved items the choice is explicit and has no default: two distinct actions, neither implied by the other.
            if (hasKnowledge) Column(horizontalAlignment = Alignment.End) {
                val keep = { removing = false; onRemove(KnowledgePolicy.KEEP) }
                val delete = { removing = false; onRemove(KnowledgePolicy.DELETE) }
                TextButton(keep, Modifier.testTag("confirm_remove_keep").activateOnGamepadConfirm(onActivate = keep)) { Text(stringResource(R.string.action_remove_keep_notes)) }
                TextButton(delete, Modifier.testTag("confirm_remove_delete").activateOnGamepadConfirm(onActivate = delete)) { Text(stringResource(R.string.action_remove_delete_notes)) }
            } else {
                val remove = { removing = false; onRemove(KnowledgePolicy.DELETE) }
                TextButton(remove, Modifier.testTag("confirm_remove").activateOnGamepadConfirm(knowledgeCounts != null, remove), enabled = knowledgeCounts != null) { Text(stringResource(R.string.action_remove)) }
            }
        },
        dismissButton = { val cancel = { removing = false }; TextButton(cancel, Modifier.testTag("cancel_remove").activateOnGamepadConfirm(onActivate = cancel)) { Text(stringResource(R.string.action_cancel)) } })
    BoxWithConstraints(modifier.fillMaxHeight()) {
        // The complete artwork at its real ratio (D11), sized height-first: it yields height so the primary Read action
        // stays in view on compact windows, and never overflows the pane's width.
        val coverHeight = minOf(DETAILS_COVER_MAX_HEIGHT, maxHeight * 0.4f)
        val coverMaxWidth = maxWidth - t.spacing.large * 2
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.large),
            verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
            PublicationCover(item, Modifier.sizeIn(maxWidth = coverMaxWidth, maxHeight = coverHeight)
                .align(Alignment.CenterHorizontally).testTag("details_cover")
                .onGloballyPositioned { coverBounds = it.boundsInWindow() }, whole = true)
            Text(item.title, style = MaterialTheme.typography.headlineMedium)
            if (item.creator.isNotBlank()) Text(item.creator, color = t.colors.secondary)
            Text("${stringResource(item.category.singularRes())} · ${item.format}", style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
            MetadataSummary(item.category, metadata.stored)
            ReadingProgress(item.progress)
            if (!item.available) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.original_file_unavailable_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.original_file_unavailable_body), color = t.colors.secondary)
            }
            Button({ onRead(coverBounds) }, Modifier.fillMaxWidth().focusRequester(readFocus).testTag("read_action")) {
                Text(stringResource(when { !item.available -> R.string.action_try_open; item.lastRead > 0 -> R.string.action_continue_reading; else -> R.string.action_read }))
            }
            Surface(color = t.colors.muted, shape = t.shapes.small) {
                Text(stringResource(if (favorite) R.string.favorite_on else R.string.favorite_off), Modifier.fillMaxWidth()
                    .testTag("favorite_action").shelfAction(selected = favorite, onClick = onFavorite)
                    .padding(16.dp), style = MaterialTheme.typography.labelLarge)
            }
            Row {
                // Editing waits for the stored metadata, so it never opens on (and saves) blank placeholders.
                TextButton({ editSnapshot = captureEditSnapshot(metadata.stored?.values); editing = true }, Modifier.testTag("edit_action"),
                    enabled = metadata.loaded) { Text(stringResource(R.string.action_edit)) }
                if (userCovers != null) TextButton({ coverOpen = true }, Modifier.testTag("cover_action")) { Text(stringResource(R.string.action_cover)) }
                TextButton({ removing = true }, Modifier.testTag("remove_action")) { Text(stringResource(R.string.action_remove)) }
            }
            HorizontalDivider(color = t.colors.divider)
            Text(stringResource(if (details) R.string.details_collapse else R.string.details_expand),
                Modifier.fillMaxWidth().shelfAction(onClick = { details = !details }).padding(vertical = 16.dp))
            if (details) Text(listOf(
                "${item.format} · ${formatSize(item.byteSize)}",
                item.fileName,
                stringResource(R.string.detail_title_origin, originLabel(item.titleOrigin)),
                stringResource(R.string.detail_creator_origin, originLabel(item.creatorOrigin)),
                stringResource(if (item.managedPath == null) R.string.detail_source_linked else R.string.detail_source_private_copy),
                coverDetail(item.id),
            ).plus(identifierLines(metadata.stored)).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
        }
    }
}

/** The previous fixed details cover was at most 240 dp wide at 0.68 (≈ 353 dp tall); kept as the height ceiling. */
private val DETAILS_COVER_MAX_HEIGHT = 352.dp

/** The technical cover provenance line: where the local artwork came from and whether it is an exact or reduced copy. */
@Composable
private fun coverDetail(id: String): String {
    val artwork = LocalCoverArtwork.current
    val flow = remember(artwork, id) { artwork?.provenance?.invoke(id) ?: flowOf(null) }
    val provenance by flow.collectAsState(null)
    val userCovers = LocalUserCovers.current
    val custom by remember(userCovers, id) { userCovers?.hasCustom(id) ?: flowOf(false) }.collectAsState(false)
    if (custom) return stringResource(R.string.detail_cover_user)
    val source = when (provenance?.source) {
        CoverLocalSource.EPUB_COVER -> R.string.detail_cover_epub
        CoverLocalSource.COMICINFO_FRONT_COVER -> R.string.detail_cover_comicinfo
        CoverLocalSource.ARCHIVE_FIRST_PAGE -> R.string.detail_cover_first_page
        CoverLocalSource.PDF_FIRST_PAGE -> R.string.detail_cover_pdf_preview
        null -> return stringResource(R.string.detail_cover_generated)
    }
    val copy = when (provenance?.representation) {
        CoverRepresentation.ORIGINAL -> R.string.detail_cover_original
        CoverRepresentation.DERIVED -> R.string.detail_cover_derived
        null -> return stringResource(source)
    }
    return stringResource(R.string.detail_cover_line, stringResource(source), stringResource(copy))
}

/** Discloses the saved items removal affects and what each explicit choice does; only shown when the total is not 0. */
@Composable
private fun KnowledgeRemovalChoice(counts: AnnotationCounts) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.dialog_remove_annotations_intro))
        if (counts.bookmarks > 0) Text(pluralStringResource(R.plurals.dialog_remove_bookmarks_line, counts.bookmarks, counts.bookmarks))
        if (counts.highlights > 0) Text(pluralStringResource(R.plurals.dialog_remove_highlights_line, counts.highlights, counts.highlights))
        if (counts.notes > 0) Text(pluralStringResource(R.plurals.dialog_remove_notes_line, counts.notes, counts.notes))
        if (counts.other > 0) Text(pluralStringResource(R.plurals.dialog_remove_other_saved_line, counts.other, counts.other))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.dialog_remove_keep_explain))
        Text(stringResource(R.string.dialog_remove_delete_explain))
    }
}
