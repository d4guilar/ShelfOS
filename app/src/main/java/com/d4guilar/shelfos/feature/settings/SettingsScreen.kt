// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.core.theme.ThemeRegistry

@Composable
fun SettingsScreen(theme: ThemeId, error: String?, onTheme: (ThemeId) -> Unit) {
    val t = LocalShelfTokens.current
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
        Text("ShelfOS · Early development", style = MaterialTheme.typography.titleMedium)
        Text("A local-first personal library for files you own. This prototype uses sample publications. Importing and reading are coming later.", color = t.colors.secondary)
        Text("Keyboard: Tab or arrow keys to move, Enter to select, Ctrl+F to search. Gamepad: D-pad to move, A to select, B to go back.", style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
    }
}
