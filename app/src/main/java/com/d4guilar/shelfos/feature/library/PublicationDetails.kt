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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.data.library.DemoPublication

@Composable
fun PublicationDetails(item: DemoPublication, favorite: Boolean, onFavorite: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalShelfTokens.current
    var details by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(t.spacing.large),
        verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
        PublicationCover(item, Modifier.widthIn(max = 240.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
        Text(item.title, style = MaterialTheme.typography.headlineMedium)
        Text(item.creator, color = t.colors.secondary)
        Text(item.category.label, style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
        ReadingProgress(item.progress)
        // Do not imply that a demo fixture has a readable source file.
        Text("Sample publication · Reading is not available in this prototype.", style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
        Surface(color = t.colors.muted, shape = t.shapes.small) {
            Text(if (favorite) "★ Favorited" else "☆ Favorite", Modifier.fillMaxWidth()
                .testTag("favorite_action").shelfAction(selected = favorite, onClick = onFavorite)
                .padding(16.dp), style = MaterialTheme.typography.labelLarge)
        }
        Text(item.synopsis, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(color = t.colors.divider)
        Text(if (details) "Details −" else "Details +", Modifier.fillMaxWidth().shelfAction(onClick = { details = !details }).padding(vertical = 16.dp))
        if (details) Text("Original ShelfOS demo data\nGenerated geometric cover\nNo source file attached\nFavorite changes last for this demo session.",
            style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
    }
}
