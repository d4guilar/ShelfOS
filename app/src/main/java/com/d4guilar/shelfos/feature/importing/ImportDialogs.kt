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
import com.d4guilar.shelfos.domain.library.*

@Composable
fun ImportDialogs(state: ImportState, onCancel: () -> Unit, onCopy: () -> Unit,
    onConfirm: (String, String, MediaCategory) -> Unit) {
    when {
        state.busy -> AlertDialog(onDismissRequest = {}, title = { Text("Adding publication") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(state.stage) } },
            confirmButton = {}, dismissButton = { TextButton(onCancel, enabled = state.stage != "Saving publication…") { Text("Cancel") } })
        state.needsCopy -> AlertDialog(onDismissRequest = onCancel, title = { Text("Keep an offline copy?") },
            text = { Text("This provider cannot offer durable, seekable access. ShelfOS can store a private copy, using the file's full size in device storage. The original stays untouched.") },
            confirmButton = { TextButton(onCopy) { Text("Copy and continue") } }, dismissButton = { TextButton(onCancel) { Text("Cancel") } })
        state.preview != null -> PublicationEditor(state.preview, "Add to library", onCancel, onConfirm,
            if (state.possibleDuplicate) "A file with the same name and size is already in your library. Add this as a separate copy?" else state.error)
        state.error != null -> AlertDialog(onDismissRequest = onCancel, title = { Text("Import unavailable") },
            text = { Text(state.error) }, confirmButton = { TextButton(onCancel) { Text("Close") } })
    }
}

@Composable
fun PublicationEditor(item: LibraryItem, action: String, onCancel: () -> Unit,
    onSave: (String, String, MediaCategory) -> Unit, notice: String? = null) {
    var title by rememberSaveable(item.id) { mutableStateOf(item.title) }
    var creator by rememberSaveable(item.id) { mutableStateOf(item.creator) }
    var category by rememberSaveable(item.id) { mutableStateOf(item.category) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(action) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (notice != null) Text(notice)
            Text("${item.format} · ${item.byteSize?.let { "${it / (1024 * 1024)} MB" } ?: "Size unavailable"}")
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.testTag("import_title"))
            OutlinedTextField(creator, { creator = it }, label = { Text("Author / creator") })
            MediaCategory.entries.forEach { option ->
                FilterChip(selected = category == option, onClick = { category = option }, label = { Text(option.label) })
            }
        }
    }, confirmButton = { TextButton({ onSave(title, creator, category) }, enabled = title.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } })
}
