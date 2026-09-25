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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.d4guilar.shelfos.core.designsystem.ShelfChoiceChip
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.*

@Composable
fun ImportDialogs(state: ImportState, onCancel: () -> Unit, onCopy: () -> Unit,
    onConfirm: (String, String, MediaCategory) -> Unit) {
    when {
        // Back cancels while cancellation is safe; an outside tap never discards a long copy.
        state.busy -> AlertDialog(onDismissRequest = { if (state.cancellable) onCancel() }, title = { Text("Adding publication") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.stage) } },
            confirmButton = {}, dismissButton = { TextButton(onCancel, enabled = state.cancellable) { Text("Cancel") } },
            properties = DialogProperties(dismissOnClickOutside = false))
        state.needsCopy -> AlertDialog(onDismissRequest = onCancel, title = { Text("Keep an offline copy?") },
            text = { Text("This provider cannot offer durable, seekable access. ShelfOS can store a private copy, using the file's full size in device storage. The original stays untouched.") },
            confirmButton = { TextButton(onCopy) { Text("Copy and continue") } }, dismissButton = { TextButton(onCancel) { Text("Cancel") } })
        state.preview != null -> PublicationEditor(state.preview, "Add to library", onCancel, onConfirm,
            if (state.possibleDuplicate) "A file with the same name and size is already in your library. Add this as a separate entry?" else state.error)
        state.error != null -> AlertDialog(onDismissRequest = onCancel, title = { Text("Import unavailable") },
            text = { Text(state.error) }, confirmButton = { TextButton(onCancel) { Text("Close") } })
    }
}

@Composable
fun PublicationEditor(item: LibraryItem, action: String, onCancel: () -> Unit,
    onSave: (String, String, MediaCategory) -> Unit, notice: String? = null) {
    val t = LocalShelfTokens.current
    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var creator by rememberSaveable(item.id) { mutableStateOf(item.creator) }
    var category by rememberSaveable(item.id) { mutableStateOf(item.category) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(action) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notice != null) Text(notice)
            Text("${item.format} · ${formatSize(item.byteSize)}")
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.testTag("import_title"))
            Text("Title ${originLabel(item.titleOrigin).replaceFirstChar { it.lowercase() }}", color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(creator, { creator = it }, label = { Text("Author / creator") })
            MediaCategory.entries.forEach { option -> ShelfChoiceChip(category == option, { category = option }, option.label) }
        }
    }, confirmButton = { TextButton({ onSave(title, creator, category) }, enabled = title.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } })
}

/** Plain-language provenance; provider mechanics stay out of the main UI. */
fun originLabel(origin: String) = when (origin) {
    "user" -> "Edited by you"
    "embedded" -> "Embedded in publication"
    "filename" -> "From the file name"
    else -> "Not provided"
}

fun formatSize(bytes: Long?): String {
    val gb = 1024L * 1024 * 1024
    return when {
        bytes == null -> "Size unavailable"
        bytes >= gb -> "${bytes / gb}.${bytes % gb * 10 / gb} GB"
        bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    }
}
