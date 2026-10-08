// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.input.InputContext
import com.d4guilar.shelfos.core.input.ShelfCommand
import com.d4guilar.shelfos.core.input.shelfCommand
import com.d4guilar.shelfos.core.reader.FoldRect

/**
 * Phase 3D Codex R1 remediation, finding 4: a fold-aware, in-reader modal surface for Appearance/Pages, used ONLY
 * when a relevant vertical separating/FULL fold currently constrains the reader (see
 * [FixedReaderScreen][com.d4guilar.shelfos.feature.reader.FixedReaderScreen]'s own `chromePane`, which [paneWindow]
 * is always given as -- the SAME selected safe pane the reader's own Appearance/Pages/Zoom/Back chrome already
 * confines itself to, for policy coherence between the chrome and its own dialogs).
 *
 * A real platform `AlertDialog`/`Dialog` window is centered on the WHOLE window by Android -- nothing about that
 * centering is fold-aware, so its content could straddle or sit directly under the hinge whenever the window
 * itself is currently split. Rather than fighting platform `Window` attributes to reposition a real dialog
 * window (fragile, device/version-sensitive, and hard to assert in a test), this renders as an ordinary Compose
 * overlay INSIDE the reader's own composition tree -- confined by plain `Modifier.offset`/`width`, the exact same
 * mechanism [FixedReaderScreen]'s own chrome already uses for `chromePane` -- entirely within [paneWindow] (a
 * reader-LOCAL [FoldRect]). The unconstrained (non-fold) case is completely untouched: callers only ever reach
 * for this overlay when a pane is actually selected, keeping zero risk of regressing the ordinary `AlertDialog`
 * path most users see.
 *
 * [onDismissRequest] is wired to BOTH a full-size scrim tap (mirroring `AlertDialog`'s own
 * `dismissOnClickOutside` default) and a dedicated [BackHandler] -- this overlay is NOT a real platform `Dialog`,
 * so the reader's own outer `BackHandler` would otherwise treat system Back as "hide controls/leave the reader"
 * instead of dismissing this overlay first; Compose's back-dispatcher stack gives priority to whichever
 * `BackHandler` was composed/enabled most recently, and this overlay is always composed strictly after (nested
 * inside) the reader's own, so it always intercepts Back first while visible -- exactly mirroring a real Dialog
 * window's own back-interception precedence. [content]'s own Surface absorbs its own clicks so tapping inside
 * the dialog itself never dismisses it.
 *
 * Phase 3D Codex R2 remediation, finding B: R1's version was visually dialog-like but not TRULY modal for
 * keyboard/D-pad/controller focus or accessibility traversal -- nothing stopped focus from moving OUT of this
 * overlay into the reader's own background chrome/page content (a sibling in the composition tree, never a
 * descendant of this overlay), at which point this overlay's own [BackHandler]/`onKeyEvent` (both scoped to this
 * overlay's own focus subtree) would simply never see a subsequent key event again, and D-pad arrow keys would
 * be read by the background reader as page-turn commands. This container now additionally exposes dialog-like
 * accessibility semantics ([paneTitle], spoken by TalkBack as "this is a modal surface," never visible on
 * screen) on its own [Surface]. BACKGROUND focus/accessibility suppression -- the other half of true modality --
 * is applied by the CALLER ([FixedReaderScreen][com.d4guilar.shelfos.feature.reader.FixedReaderScreen], via
 * `hingeSafeModalOpen`-gated `Modifier.focusProperties { canFocus = false }` + `Modifier.clearAndSetSemantics {}`
 * on its own background content, plus a root-level `onPreviewKeyEvent` that intercepts Back/gamepad-B and
 * swallows page/menu commands BEFORE they can reach the background handler), because this overlay is
 * DELIBERATELY a sibling (not a wrapper) of the reader's own content -- it has no way to reach into that
 * sibling subtree itself. See [FixedReaderScreen]'s own `hingeSafeModalOpen`/`dismissAppearance`/
 * `dismissThumbnails` docs for that half of the fix.
 */
@Composable
fun HingeSafeDialogOverlay(paneWindow: FoldRect, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { onDismissRequest() }
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    val dialogPaneTitle = stringResource(R.string.content_desc_hinge_safe_dialog)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize()
        .background(Color.Black.copy(alpha = 0.32f))
        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismissRequest)
        .testTag("hinge_safe_dialog_scrim")) {
        Box(Modifier.align(Alignment.TopStart)
            .offset(x = with(density) { paneWindow.left.toDp() })
            .width(with(density) { paneWindow.width.toDp() })
            .fillMaxHeight()
            .testTag("hinge_safe_dialog_pane"), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.testTag("hinge_safe_dialog_surface")
                    .focusRequester(focus).focusable()
                    // Codex R2 remediation, finding B: dialog-like accessibility semantics -- paneTitle marks
                    // this subtree as a distinct, spoken-of modal pane (the same primitive Compose's own
                    // drawer/sheet-style surfaces use when they are not backed by a real platform Dialog window),
                    // without inventing visible "hinge dialog" copy -- the string is spoken by TalkBack only.
                    .semantics { paneTitle = dialogPaneTitle }
                    // Mirrors ThumbnailNavigator's pre-existing gamepad-B fix: BackHandler above reliably
                    // catches system Back/Escape, but not KEYCODE_BUTTON_B, which this app's own InputMapper
                    // maps to the same semantic ShelfCommand.BACK -- checked here too so gamepad B dismisses
                    // this overlay exactly like system Back does, regardless of which control inside has focus.
                    .onKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action == KeyEvent.ACTION_UP && native.shelfCommand(InputContext.READER) == ShelfCommand.BACK) {
                            onDismissRequest(); true
                        } else false
                    }
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { /* absorb -- never dismiss */ },
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) { content() }
        }
    }
}
