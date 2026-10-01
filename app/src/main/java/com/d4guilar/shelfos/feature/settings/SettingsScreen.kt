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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.designsystem.resolve
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.files.PrivateCopyUsage
import com.d4guilar.shelfos.core.localization.AppLanguage
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.core.theme.ThemeRegistry
import com.d4guilar.shelfos.feature.importing.formatSize

@Composable
fun SettingsScreen(theme: ThemeId, error: UiMessage?, onTheme: (ThemeId) -> Unit,
    unusedCopies: PrivateCopyUsage? = null, onDeleteUnusedCopies: () -> Unit = {},
    language: AppLanguage = AppLanguage.SYSTEM_DEFAULT, onLanguage: (AppLanguage) -> Unit = {}) {
    val t = LocalShelfTokens.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete && unusedCopies != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.settings_delete_copies_title)) },
        text = { Text(stringResource(R.string.settings_delete_copies_body,
            pluralStringResource(R.plurals.private_copy_count, unusedCopies.count, unusedCopies.count), formatSize(unusedCopies.bytes))) },
        confirmButton = { TextButton({ confirmDelete = false; onDeleteUnusedCopies() }, Modifier.testTag("confirm_delete_copies")) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(t.spacing.large),
        verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.action_appearance), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_appearance_description), color = t.colors.secondary)
        ThemeRegistry.entries.forEach { entry ->
            Row(Modifier.fillMaxWidth().testTag("theme_${entry.id.storageKey}")
                .shelfAction(selected = entry.id == theme, enabled = entry.available, role = Role.RadioButton,
                    onClick = { onTheme(entry.id) }).padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (entry.id == theme) "●" else "○", color = t.colors.secondary)
                Column(Modifier.weight(1f)) {
                    // Theme names are ShelfOS product/brand names and are not translated (see AGENTS.md).
                    Text(entry.label, color = if (entry.available) t.colors.ink else t.colors.secondary)
                    if (!entry.available) Text(stringResource(R.string.settings_theme_planned), color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(color = t.colors.divider)
        }
        if (error != null) Text(error.resolve())
        Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_language_description), color = t.colors.secondary)
        AppLanguage.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().testTag("language_${option.name.lowercase()}")
                .shelfAction(selected = option == language, role = Role.RadioButton, onClick = { onLanguage(option) })
                .padding(vertical = 16.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (option == language) "●" else "○", color = t.colors.secondary)
                Text(stringResource(option.labelRes()), color = t.colors.ink)
            }
            HorizontalDivider(color = t.colors.divider)
        }
        Text(stringResource(R.string.settings_storage_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_storage_description), color = t.colors.secondary)
        Row(Modifier.fillMaxWidth().testTag("storage_unused_copies"), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                unusedCopies == null -> stringResource(R.string.settings_storage_checking)
                unusedCopies.count == 0 -> stringResource(R.string.settings_storage_none_unused)
                else -> stringResource(R.string.settings_storage_unused_count,
                    pluralStringResource(R.plurals.private_copy_count, unusedCopies.count, unusedCopies.count), formatSize(unusedCopies.bytes))
            }, Modifier.weight(1f))
            if (unusedCopies != null && unusedCopies.count > 0) TextButton({ confirmDelete = true }) { Text(stringResource(R.string.action_delete)) }
        }
        Text(stringResource(R.string.settings_about_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_about_body), color = t.colors.secondary)
        Text(stringResource(R.string.settings_keyboard_help), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
    }
}

private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM_DEFAULT -> R.string.language_system_default
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.SPANISH -> R.string.language_spanish
    AppLanguage.PORTUGUESE_BRAZIL -> R.string.language_portuguese_brazil
}
