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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
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
    onRead: (Rect?) -> Unit, onEdit: (String, String, MediaCategory) -> Unit, onRemove: () -> Unit, focusPrimaryAction: Boolean = false) {
    val t = LocalShelfTokens.current
    var details by rememberSaveable(item.id) { mutableStateOf(false) }
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    var removing by rememberSaveable(item.id) { mutableStateOf(false) }
    var coverBounds by remember(item.id) { mutableStateOf<Rect?>(null) }
    val readFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(Unit) { if (focusPrimaryAction && inputMode == InputMode.Keyboard) runCatching { readFocus.requestFocus() } }
    if (editing) PublicationEditor(item, stringResource(R.string.action_save_changes), { editing = false }, { title, creator, category ->
        onEdit(title, creator, category); editing = false
    })
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text(stringResource(R.string.dialog_remove_title)) },
        text = {
            val body = stringResource(R.string.dialog_remove_body)
            val copyNote = item.managedPath?.let { stringResource(R.string.dialog_remove_private_copy_note, formatSize(item.byteSize)) }
            Text(if (copyNote != null) "$body\n\n$copyNote" else body)
        },
        confirmButton = { TextButton({ removing = false; onRemove() }, Modifier.testTag("confirm_remove")) { Text(stringResource(R.string.action_remove)) } },
        dismissButton = { TextButton({ removing = false }) { Text(stringResource(R.string.action_cancel)) } })
    BoxWithConstraints(modifier.fillMaxHeight()) {
        // The cover yields height first so the primary Read action stays in view on compact windows.
        val coverWidth = minOf(240.dp, maxHeight * 0.4f * 0.68f)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.large),
            verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
            PublicationCover(item, Modifier.width(coverWidth).align(Alignment.CenterHorizontally)
                .onGloballyPositioned { coverBounds = it.boundsInWindow() })
            Text(item.title, style = MaterialTheme.typography.headlineMedium)
            if (item.creator.isNotBlank()) Text(item.creator, color = t.colors.secondary)
            Text("${stringResource(item.category.singularRes())} · ${item.format}", style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
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
                TextButton({ editing = true }) { Text(stringResource(R.string.action_edit)) }
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
                stringResource(R.string.detail_cover_generated),
            ).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
        }
    }
}
