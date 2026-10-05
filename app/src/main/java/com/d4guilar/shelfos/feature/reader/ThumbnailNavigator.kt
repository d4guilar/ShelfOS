// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.reader.ThumbnailLoader
import com.d4guilar.shelfos.core.reader.ThumbnailResult
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Phase 3B: a bounded, lazily-decoded thumbnail strip for jumping directly to a logical page. Reuses the same
 * [androidx.compose.material3.AlertDialog] pattern every other reader overlay already uses (Appearance/Chapters/
 * Search/Bookmarks) rather than introducing a new dialog idiom -- so Back/Escape/gamepad B dismiss exactly the way
 * they already do for those, with no new key-handling code in [FixedReaderScreen].
 *
 * Selecting a thumbnail calls [onSelect] with the plain logical page index; [FixedReaderScreen] wires that straight
 * to [FixedReaderViewModel.showPage], the same jump path a slider drag already uses -- there is no second page-
 * numbering model here, and no locator/progress concept specific to thumbnails.
 *
 * RTL/Manga: only the strip's visual layout direction mirrors (via [LocalLayoutDirection], the same technique the
 * bottom control row already uses for Previous/Next placement); the underlying `items(pageCount) { page -> ... }`
 * loop always iterates true logical indices in natural order regardless of [rtl] -- there is no reversed list to
 * get wrong, so the page-identity invariant holds by construction, not by a runtime check.
 */
@Composable
fun ThumbnailNavigator(pageCount: Int, currentPage: Int, rtl: Boolean, loader: ThumbnailLoader<Bitmap>?,
    onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    val revisionFlow = remember(loader) { loader?.revision ?: MutableStateFlow(0L) }
    val revision by revisionFlow.collectAsStateWithLifecycle()
    val pageDescTemplate = stringResource(R.string.content_desc_thumbnail_page)
    val currentPageDescTemplate = stringResource(R.string.content_desc_thumbnail_current_page)
    val failedLabel = stringResource(R.string.content_desc_thumbnail_unavailable)

    // Reveal the current page roughly centered rather than merely scrolled to its leading edge, each time the
    // strip is (re)opened -- this LaunchedEffect(Unit) re-runs on every fresh entry into composition, since the
    // whole navigator leaves composition when dismissed (no state to separately reset).
    LaunchedEffect(Unit) { listState.scrollToItem((currentPage - 2).coerceAtLeast(0)) }
    // Drives the loader from the strip's ACTUAL visible items, not a guess -- this is what keeps decoding lazy
    // (only a scrolled-to window is ever requested) and keeps a fast scroll from queuing stale work (each new
    // visible-range value supersedes the previous one inside ThumbnailLoader; see its doc).
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }.collect { visible ->
            if (visible.isNotEmpty()) loader?.setVisibleRange(visible[visible.size / 2].index)
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.action_thumbnails)) }, text = {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            LazyRow(state = listState, modifier = Modifier.fillMaxWidth().height(176.dp).testTag("thumbnail_strip"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pageCount, key = { it }) { page ->
                    val entry = remember(revision, page) { loader?.peek(page) }
                    ThumbnailCell(page, page == currentPage, entry, { onSelect(page) },
                        pageDescTemplate, currentPageDescTemplate, failedLabel)
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_close)) } })
}

@Composable
private fun ThumbnailCell(page: Int, isCurrent: Boolean, result: ThumbnailResult<Bitmap>?, onClick: () -> Unit,
    pageDescTemplate: String, currentPageDescTemplate: String, failedLabel: String) {
    val t = LocalShelfTokens.current
    // "Doesn't rely solely on color" (3B's accessibility requirement): the current page is marked by BOTH a
    // checkmark glyph and bold weight -- the same two-signal idiom EpubActivity's Chapters dialog already uses for
    // its own current-chapter marker -- not merely a border/background tint.
    val description = if (isCurrent) String.format(currentPageDescTemplate, page + 1) else String.format(pageDescTemplate, page + 1)
    Column(Modifier.width(96.dp).testTag("thumbnail_$page")
        .clickable(onClickLabel = description, onClick = onClick)
        .semantics { contentDescription = description }
        .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(120.dp).background(t.colors.muted, t.shapes.small),
            contentAlignment = Alignment.Center) {
            when (result) {
                is ThumbnailResult.Loaded -> Image(result.value.asImageBitmap(), null,
                    Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                ThumbnailResult.Failed -> Text(failedLabel, color = t.colors.secondary, style = t.typography.labelSmall)
                null -> CircularProgressIndicator(Modifier.size(20.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isCurrent) Text("✓ ", fontWeight = FontWeight.Bold)
            Text("${page + 1}", fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, style = t.typography.labelSmall)
        }
    }
}
