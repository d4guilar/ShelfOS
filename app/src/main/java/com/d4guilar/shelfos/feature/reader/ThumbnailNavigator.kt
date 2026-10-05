// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
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
import com.d4guilar.shelfos.core.input.InputContext
import com.d4guilar.shelfos.core.input.ShelfCommand
import com.d4guilar.shelfos.core.input.shelfCommand
import com.d4guilar.shelfos.core.reader.ThumbnailLoader
import com.d4guilar.shelfos.core.reader.ThumbnailResult
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Phase 3B: a bounded, lazily-decoded thumbnail strip for jumping directly to a logical page. Reuses the same
 * [androidx.compose.material3.AlertDialog] pattern every other reader overlay already uses (Appearance/Chapters/
 * Search/Bookmarks) rather than introducing a new dialog idiom, with no new key-handling code in
 * [FixedReaderScreen] -- but Codex R1 finding 2 found that assumption incomplete: an ordinary Android `Dialog`
 * reliably handles system Back and Escape (both already dismiss this dialog correctly), but does **not** reliably
 * translate `KEYCODE_BUTTON_B` into dismissal, because the dialog owns focus/window state once shown, making
 * [FixedReaderScreen]'s own key handler an unreliable fallback while this dialog is open. The fix below is
 * dialog-local (lives on content actually inside this dialog's window, so it genuinely receives the event) and
 * reuses the existing [com.d4guilar.shelfos.core.input.ShelfCommand] semantic layer rather than hard-coding
 * `KEYCODE_BUTTON_B`: [InputMapper][com.d4guilar.shelfos.core.input.InputMapper] already maps both Escape and
 * gamepad B to [ShelfCommand.BACK] (see `ShelfCommand.kt`), so a single `onKeyEvent` check for that one semantic
 * command covers both -- redundant with (never conflicting with) the system handling already correct for Escape.
 * Ordinary D-pad/focus movement between cells is untouched: this listener only ever consumes `ShelfCommand.BACK`
 * and returns `false` (unconsumed) for every other key, so normal focus navigation and activation keep working.
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
    val dialogFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { dialogFocus.requestFocus() }

    // Codex R1 finding 3: dismissing Pages must stop the loader's *active prefetch window*, not the whole session
    // (the reader itself stays open) -- already-cached thumbnails remain available, and a fresh setVisibleRange
    // after reopening establishes a genuinely new window normally. Keyed on `loader` identity, not `Unit`, so this
    // still deactivates correctly if the loader instance itself were ever swapped out from under a live navigator.
    DisposableEffect(loader) { onDispose { loader?.deactivate() } }

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
        Box(Modifier
            .testTag("thumbnail_dialog_surface")
            .focusRequester(dialogFocus)
            .focusable()
            // Codex R1 finding 2: dialog-local gamepad-B dismissal, reusing ShelfCommand.BACK (see this file's
            // class doc). Only ShelfCommand.BACK is ever consumed here -- every other key (including the D-pad/
            // focus-navigation keys the cells below already handle via `clickable`) is left unconsumed.
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == KeyEvent.ACTION_UP && native.shelfCommand(InputContext.READER) == ShelfCommand.BACK) {
                    onDismiss(); true
                } else false
            }) {
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
