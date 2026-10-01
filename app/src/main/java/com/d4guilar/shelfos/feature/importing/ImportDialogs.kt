// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.importing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.ShelfChoiceChip
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.designsystem.resolve
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.*
import com.d4guilar.shelfos.feature.library.labelRes

@Composable
fun ImportDialogs(state: ImportState, onCancel: () -> Unit, onCopy: () -> Unit,
    onConfirm: (String, String, MediaCategory) -> Unit) {
    when {
        // Back cancels while cancellation is safe; an outside tap never discards a long copy.
        state.busy -> AlertDialog(onDismissRequest = { if (state.cancellable) onCancel() }, title = { Text(stringResource(R.string.import_dialog_adding_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.stage.resolve()) } },
            confirmButton = {}, dismissButton = { TextButton(onCancel, enabled = state.cancellable) { Text(stringResource(R.string.action_cancel)) } },
            properties = DialogProperties(dismissOnClickOutside = false))
        state.needsCopy -> AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.import_dialog_keep_copy_title)) },
            text = { Text(stringResource(R.string.import_dialog_keep_copy_body)) },
            confirmButton = { TextButton(onCopy) { Text(stringResource(R.string.action_copy_and_continue)) } },
            dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) } })
        state.preview != null -> PublicationEditor(state.preview, stringResource(R.string.import_action_add_to_library), onCancel, onConfirm,
            if (state.possibleDuplicate) UiMessage.Resource(R.string.import_possible_duplicate_notice) else state.error)
        state.error != null -> AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.import_dialog_unavailable_title)) },
            text = { Text(state.error.resolve()) }, confirmButton = { TextButton(onCancel) { Text(stringResource(R.string.action_close)) } })
    }
}

@Composable
fun PublicationEditor(item: LibraryItem, action: String, onCancel: () -> Unit,
    onSave: (String, String, MediaCategory) -> Unit, notice: UiMessage? = null) {
    val t = LocalShelfTokens.current
    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var creator by rememberSaveable(item.id) { mutableStateOf(item.creator) }
    var category by rememberSaveable(item.id) { mutableStateOf(item.category) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(action) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notice != null) Text(notice.resolve())
            Text("${item.format} · ${formatSize(item.byteSize)}")
            OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.import_title_field)) }, modifier = Modifier.testTag("import_title"))
            Text(stringResource(R.string.import_title_origin_note, originLabel(item.titleOrigin).replaceFirstChar { it.lowercase() }),
                color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(creator, { creator = it }, label = { Text(stringResource(R.string.import_creator_field)) })
            MediaCategory.entries.forEach { option -> ShelfChoiceChip(category == option, { category = option }, stringResource(option.labelRes())) }
        }
    }, confirmButton = { TextButton({ onSave(title, creator, category) }, enabled = title.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) } })
}

/** Plain-language provenance; provider mechanics stay out of the main UI. */
@Composable
fun originLabel(origin: String) = when (origin) {
    "user" -> stringResource(R.string.origin_user)
    "embedded" -> stringResource(R.string.origin_embedded)
    "filename" -> stringResource(R.string.origin_filename)
    else -> stringResource(R.string.origin_unknown)
}

@Composable
fun formatSize(bytes: Long?): String {
    val gb = 1024L * 1024 * 1024
    // LocalLocale (not Locale.getDefault()) so this recomposes if ShelfOS's interface language changes live.
    val locale = LocalLocale.current.platformLocale
    return when {
        bytes == null -> stringResource(R.string.file_size_unavailable)
        bytes >= gb -> stringResource(R.string.file_size_gb, String.format(locale, "%.1f", bytes / gb.toDouble()))
        bytes >= 1024L * 1024 -> stringResource(R.string.file_size_mb, bytes / (1024 * 1024))
        else -> stringResource(R.string.file_size_kb, (bytes / 1024).coerceAtLeast(1))
    }
}
