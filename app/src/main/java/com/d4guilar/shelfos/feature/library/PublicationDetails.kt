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
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.feature.importing.PublicationEditor

@Composable
fun PublicationDetails(item: LibraryItem, favorite: Boolean, onFavorite: () -> Unit, modifier: Modifier = Modifier,
    onRead: () -> Unit, onEdit: (String, String, MediaCategory) -> Unit, onRemove: () -> Unit) {
    val t = LocalShelfTokens.current
    var details by rememberSaveable(item.id) { mutableStateOf(false) }
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    var removing by rememberSaveable(item.id) { mutableStateOf(false) }
    if (editing) PublicationEditor(item, "Save changes", { editing = false }, { title, creator, category ->
        onEdit(title, creator, category); editing = false
    })
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Remove from library?") },
        text = { Text("ShelfOS will remove this entry and its reading state. Your original file stays untouched.") },
        confirmButton = { TextButton({ removing = false; onRemove() }) { Text("Remove") } },
        dismissButton = { TextButton({ removing = false }) { Text("Cancel") } })
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(t.spacing.large),
        verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
        PublicationCover(item, Modifier.widthIn(max = 240.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
        Text(item.title, style = MaterialTheme.typography.headlineMedium)
        Text(item.creator, color = t.colors.secondary)
        Text(item.category.label, style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
        ReadingProgress(item.progress)
        if (!item.available) Text("Source unavailable. Restore access to the original file and try again.")
        Button(onRead, Modifier.fillMaxWidth().testTag("read_action")) { Text(if (item.lastRead > 0) "Continue reading" else "Read") }
        Surface(color = t.colors.muted, shape = t.shapes.small) {
            Text(if (favorite) "★ Favorited" else "☆ Favorite", Modifier.fillMaxWidth()
                .testTag("favorite_action").shelfAction(selected = favorite, onClick = onFavorite)
                .padding(16.dp), style = MaterialTheme.typography.labelLarge)
        }
        Row { TextButton({ editing = true }) { Text("Edit") }; TextButton({ removing = true }) { Text("Remove") } }
        HorizontalDivider(color = t.colors.divider)
        Text(if (details) "Details −" else "Details +", Modifier.fillMaxWidth().shelfAction(onClick = { details = !details }).padding(vertical = 16.dp))
        if (details) Text("${item.format} · ${item.byteSize?.let { "${it / (1024 * 1024)} MB" } ?: "Size unavailable"}\n${item.fileName}\nTitle: ${item.titleOrigin}\n${if (item.managedPath == null) "Linked source file" else "Private offline copy"}\nGenerated fallback cover",
            style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
    }
}
