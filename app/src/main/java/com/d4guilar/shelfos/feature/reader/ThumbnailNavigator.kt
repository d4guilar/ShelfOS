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
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * Phase 3B: a bounded, lazily-decoded thumbnail strip for jumping directly to a logical page. Visually this still
 * follows the same [androidx.compose.material3.AlertDialog] look every other reader overlay already uses
 * (Appearance/Chapters/Search/Bookmarks) -- title, content, trailing Close button -- with no new key-handling code
 * in [FixedReaderScreen]. Codex R1 finding 2 found that an ordinary Android `Dialog` reliably handles system Back
 * and Escape (both already dismiss this dialog correctly), but does **not** reliably translate `KEYCODE_BUTTON_B`
 * into dismissal, because the dialog owns focus/window state once shown, making [FixedReaderScreen]'s own key
 * handler an unreliable fallback while this dialog is open. Codex R2 then found the R1 fix incomplete: it placed
 * `onKeyEvent` only on the Box inside `AlertDialog`'s `text` slot, which is a *sibling* of `confirmButton`'s Close
 * button inside AlertDialog's own internal layout -- Compose key events bubble up the focus-parent chain from
 * whichever node is focused, so once focus moved to Close (a sibling, not a descendant, of that Box) the handler
 * no longer received the event. Fixing that requires a single container that is an ancestor of BOTH the content
 * and the Close button inside the dialog's own window; the public `AlertDialog` composable doesn't expose one (it
 * only accepts separate slot lambdas), so this now builds on [BasicAlertDialog] -- material3's own lower-level
 * primitive, still backed by the same `Dialog`/`DialogProperties` AlertDialog uses internally (so
 * `dismissOnBackPress`/`dismissOnClickOutside` still apply unchanged) -- and composes title, thumbnail content and
 * Close button together inside one [Surface], with the key handler on that single root. This is the smallest
 * restructuring that gives Back/gamepad-B dialog-wide reach without inventing a new dialog idiom or a
 * device-specific key path: it still reuses the existing [com.d4guilar.shelfos.core.input.ShelfCommand] semantic
 * layer rather than hard-coding `KEYCODE_BUTTON_B`: [InputMapper][com.d4guilar.shelfos.core.input.InputMapper]
 * already maps both Escape and gamepad B to [ShelfCommand.BACK] (see `ShelfCommand.kt`), so a single `onKeyEvent`
 * check for that one semantic command covers both -- redundant with (never conflicting with) the system handling
 * already correct for Escape. Ordinary D-pad/focus movement between cells and to/from Close is untouched: this
 * listener only ever consumes `ShelfCommand.BACK` and returns `false` (unconsumed) for every other key, so normal
 * focus navigation and activation keep working everywhere in the dialog, Close button included.
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
// BasicAlertDialog (needed for the dialog-wide Back/gamepad-B fix -- see this file's class doc) is still an
// experimental material3 API; this opt-in mirrors the same per-function pattern other experimental-API call sites
// in this codebase already use (e.g. EpubActivity.kt's @OptIn(ExperimentalLayoutApi::class)).
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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

    BasicAlertDialog(onDismissRequest = onDismiss) {
        // Codex R2 finding: this Surface is the single container that is an ancestor of BOTH the thumbnail
        // content AND the Close button below (see this file's class doc) -- unlike AlertDialog's separate `text`/
        // `confirmButton` slots, a key event bubbling up from whichever child currently has focus always reaches
        // this one `onKeyEvent`, so gamepad B (and Escape, redundantly with the system handling `BasicAlertDialog`
        // already provides via the same `DialogProperties` AlertDialog uses) dismisses Pages regardless of
        // whether focus is on a thumbnail cell or on Close. Only ShelfCommand.BACK is ever consumed here -- every
        // other key (including D-pad/focus-navigation keys and Enter/center activation) is left unconsumed, so
        // normal focus movement and activation across the whole dialog, Close button included, keep working.
        Surface(
            modifier = Modifier
                .testTag("thumbnail_dialog_surface")
                .focusRequester(dialogFocus)
                .focusable()
                .onKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == KeyEvent.ACTION_UP && native.shelfCommand(InputContext.READER) == ShelfCommand.BACK) {
                        onDismiss(); true
                    } else false
                },
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.action_thumbnails), style = MaterialTheme.typography.headlineSmall,
                    color = AlertDialogDefaults.titleContentColor)
                Spacer(Modifier.height(16.dp))
                CompositionLocalProvider(
                    LocalContentColor provides AlertDialogDefaults.textContentColor,
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    LazyRow(state = listState, modifier = Modifier.fillMaxWidth().height(176.dp).testTag("thumbnail_strip"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pageCount, key = { it }) { page ->
                            val entry = remember(revision, page) { loader?.peek(page) }
                            ThumbnailCell(page, page == currentPage, entry, { onSelect(page) },
                                pageDescTemplate, currentPageDescTemplate, failedLabel)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
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
