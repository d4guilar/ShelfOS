// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.files.PrivateCopyUsage
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.core.theme.ThemeRegistry
import com.d4guilar.shelfos.feature.importing.formatSize

@Composable
fun SettingsScreen(theme: ThemeId, error: String?, onTheme: (ThemeId) -> Unit,
    unusedCopies: PrivateCopyUsage? = null, onDeleteUnusedCopies: () -> Unit = {}) {
    val t = LocalShelfTokens.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete && unusedCopies != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete unused private copies?") },
        text = { Text("This deletes ${copyCount(unusedCopies.count)} (${formatSize(unusedCopies.bytes)}) that no publication in your library uses. " +
            "Your original files and your library are not affected.") },
        confirmButton = { TextButton({ confirmDelete = false; onDeleteUnusedCopies() }, Modifier.testTag("confirm_delete_copies")) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.large),
        verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Choose a quiet backdrop for your library.", color = t.colors.secondary)
        ThemeRegistry.entries.forEach { entry ->
            Row(Modifier.fillMaxWidth().testTag("theme_${entry.id.storageKey}")
                .shelfAction(selected = entry.id == theme, enabled = entry.available, role = Role.RadioButton,
                    onClick = { onTheme(entry.id) }).padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (entry.id == theme) "●" else "○", color = t.colors.secondary)
                Column(Modifier.weight(1f)) {
                    Text(entry.label, color = if (entry.available) t.colors.ink else t.colors.secondary)
                    if (!entry.available) Text("Planned", color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(color = t.colors.divider)
        }
        if (error != null) Text(error)
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Text("When a provider can't offer lasting access, ShelfOS keeps a private offline copy. Removing a publication " +
            "keeps its copy until you delete it here. Original files are never changed or deleted.", color = t.colors.secondary)
        Row(Modifier.fillMaxWidth().testTag("storage_unused_copies"), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                unusedCopies == null -> "Checking private copies…"
                unusedCopies.count == 0 -> "No unused private copies"
                else -> "${copyCount(unusedCopies.count)} not in your library · ${formatSize(unusedCopies.bytes)}"
            }, Modifier.weight(1f))
            if (unusedCopies != null && unusedCopies.count > 0) TextButton({ confirmDelete = true }) { Text("Delete") }
        }
        Text("ShelfOS · Early development", style = MaterialTheme.typography.titleMedium)
        Text("A local-first personal library for files you own. Add PDF, EPUB and CBZ publications with Add file; " +
            "they stay where they are. Nothing is uploaded and no account is needed.", color = t.colors.secondary)
        Text("Keyboard: Tab or arrow keys to move, Enter to select, Ctrl+F to search. In a reader: arrows, Page Up/Down or Space to turn pages, " +
            "Menu for controls, Escape to go back. Gamepad: D-pad to move, A to select, B to go back, L1/R1 to turn pages.",
            style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
    }
}

private fun copyCount(count: Int) = if (count == 1) "1 private copy" else "$count private copies"
