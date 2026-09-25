// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.core.designsystem.SectionTitle
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.LibraryFilter
import com.d4guilar.shelfos.domain.library.MediaCategory

@Composable
fun LibraryScreen(
    state: LibraryUiState, expanded: Boolean,
    onFilter: (LibraryFilter) -> Unit, onSelect: (String) -> Unit,
    onOpen: (String) -> Unit, onFavorite: (String) -> Unit,
    // Rect is this cover's on-screen position, when known, for the reader-entry cover transition (CLASSIC_UI.md §12).
    onRead: (String, Rect?) -> Unit, onEdit: (String, String, String, MediaCategory) -> Unit, onRemove: (String) -> Unit,
    restoreFocus: Boolean = false,
) {
    val t = LocalShelfTokens.current
    val grid = rememberLazyGridState()
    val selectedFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    // Returning from details or a reader with a keyboard/D-pad restores focus to the selected publication.
    LaunchedEffect(Unit) {
        val index = state.items.indexOfFirst { it.id == state.selected?.id }
        if (restoreFocus && inputMode == InputMode.Keyboard && index >= 0) {
            grid.scrollToItem(index + 1)
            runCatching { selectedFocus.requestFocus() }
        }
    }
    Row(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (expanded) 136.dp else 112.dp),
            modifier = Modifier.weight(1f).testTag("library_grid"),
            state = grid,
            contentPadding = PaddingValues(t.spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(t.spacing.large),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
                    SectionTitle("Continue Reading")
                    if (state.continueReading.isEmpty()) Text("Your reading will appear here once you open a publication.", color = t.colors.secondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(t.spacing.medium)) {
                        items(state.continueReading, key = { it.id }) { item ->
                            Row(Modifier.width(244.dp).testTag("continue_${item.id}").shelfAction(
                                // A direct Continue Reading tap has no "selected cover" moment to expand from.
                                onFocused = { onSelect(item.id) }, onClick = { onRead(item.id, null) },
                            ).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PublicationCover(item, Modifier.width(60.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(item.creator, color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
                                    ReadingProgress(item.progress)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = t.colors.divider)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("categories")) {
                        LibraryFilter.entries.forEach { filter ->
                            val selected = state.filter == filter
                            Column(Modifier.width(IntrinsicSize.Max).shelfAction(selected = selected, role = Role.Tab, onClick = { onFilter(filter) })) {
                                Text(filter.label, Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) t.colors.ink else t.colors.secondary,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                                Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) t.colors.ink else t.colors.divider))
                            }
                        }
                    }
                    Text("${state.items.size} ${if (state.items.size == 1) "publication" else "publications"} · Recently added",
                        color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (state.loading) "Loading your library…" else if (state.all.isEmpty()) "Your library starts here. Add a PDF, EPUB or CBZ using Add file." else "No publications in this view yet.", Modifier.padding(vertical = 24.dp))
            }
            items(state.items, key = { it.id }) { item ->
                val selected = state.selected?.id == item.id
                PublicationTile(item, selected, if (selected) Modifier.focusRequester(selectedFocus) else Modifier,
                    onFocus = { onSelect(item.id) }, onOpen = { onOpen(item.id) })
            }
        }
        if (expanded && state.selected != null) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(t.colors.divider))
            PublicationDetails(state.selected, state.selected.id in state.favorites,
                onFavorite = { onFavorite(state.selected.id) }, modifier = Modifier.width(300.dp).testTag("detail_pane"),
                onRead = { bounds -> onRead(state.selected.id, bounds) }, onEdit = { title, creator, category -> onEdit(state.selected.id, title, creator, category) },
                onRemove = { onRemove(state.selected.id) })
        }
    }
}

@Composable
fun PublicationTile(item: LibraryItem, selected: Boolean, modifier: Modifier = Modifier, onFocus: () -> Unit, onOpen: () -> Unit) {
    val t = LocalShelfTokens.current
    Column(modifier.testTag("publication_${item.id}").shelfAction(selected, onFocused = onFocus, onClick = onOpen)
        .padding(4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        PublicationCover(item, Modifier.fillMaxWidth().border(
            t.surfaces.border, if (selected) t.colors.ink else androidx.compose.ui.graphics.Color.Transparent,
        ).padding(3.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.creator, color = t.colors.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!item.available) Text("Unavailable", color = t.colors.secondary, style = MaterialTheme.typography.labelSmall)
        else if (item.progress > 0) ReadingProgress(item.progress)
    }
}

@Composable
fun ReadingProgress(progress: Int) {
    val t = LocalShelfTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.weight(1f).height(3.dp),
            color = t.colors.secondary, trackColor = t.colors.divider, drawStopIndicator = {})
        Text("$progress%", color = t.colors.secondary, style = MaterialTheme.typography.labelSmall)
    }
}
