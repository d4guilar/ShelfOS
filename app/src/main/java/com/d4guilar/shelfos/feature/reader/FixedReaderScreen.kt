// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d4guilar.shelfos.core.designsystem.InputKeycap
import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.capabilities
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Original-page reader. Back, Escape and gamepad B reveal hidden controls first; only once controls are
 * visible do they leave the reader (ADR-0023). Direction changes navigation and control placement, never
 * the stored page order or the artwork.
 */
@Composable
fun FixedReaderScreen(vm: FixedReaderViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = LocalShelfTokens.current
    val item = state.item
    var controls by rememberSaveable { mutableStateOf(true) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    // Recent input modality (Phase 2A.1): only real touch gestures and real key events update this, never a
    // button click, since a click may itself have been keyboard/gamepad-activated.
    var modality by rememberSaveable { mutableStateOf(InputModality.TOUCH) }
    var topFocused by remember { mutableStateOf(false) }
    var bottomFocused by remember { mutableStateOf(false) }
    var controlFocusRequests by remember { mutableIntStateOf(0) }
    var scale by remember(state.page) { mutableFloatStateOf(1f) }
    var panX by remember(state.page) { mutableFloatStateOf(0f) }
    var panY by remember(state.page) { mutableFloatStateOf(0f) }
    var sliderTarget by remember { mutableStateOf<Float?>(null) }
    val pageFocus = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val rtl = readingDirection(item?.category ?: MediaCategory.BOOK, state.preferences.direction) == ReadingDirection.RTL
    val previousHint = InputHints.hint(ShelfCommand.PREVIOUS_PAGE, modality, rtl)
    val nextHint = InputHints.hint(ShelfCommand.NEXT_PAGE, modality, rtl)
    val backHint = InputHints.hint(ShelfCommand.BACK, modality, rtl)

    fun hideControls() { controls = false; pageFocus.requestFocus() }
    fun toggleControls(moveFocus: Boolean) {
        if (controls) { hideControls(); return }
        controls = true
        if (moveFocus) controlFocusRequests++
    }
    // Back never leaves the reader from hidden chrome: it reveals controls first, then a second Back exits.
    fun backPress() { if (controls) onBack() else { controls = true; controlFocusRequests++ } }

    LaunchedEffect(Unit) { pageFocus.requestFocus() }
    LaunchedEffect(controlFocusRequests) { if (controlFocusRequests > 0) runCatching { firstControl.requestFocus() } }
    BackHandler { backPress() }
    if (appearance && item != null) ReaderAppearance(state.preferences, capabilities(item.format), { appearance = false },
        vm::applyAppearance, vm::resetAppearance)

    Column(Modifier.fillMaxSize().background(t.colors.canvas).testTag("reader_screen").onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        when (val command = native.readerCommand(rtl, controls && (topFocused || bottomFocused))) {
            ShelfCommand.NEXT_PAGE, ShelfCommand.PREVIOUS_PAGE, ShelfCommand.OPEN_MENU, ShelfCommand.BACK -> {
                // Back is a universal dismissal action available identically from every modality; letting it
                // update the tracked modality would flip an active controller/keyboard hint set away from
                // itself on every Back press (confirmed on RP5 hardware: BACK defaults to KEYBOARD below).
                if (command != ShelfCommand.BACK) modality = native.inputModality()
                if (native.action == KeyEvent.ACTION_UP) when (command) {
                    ShelfCommand.NEXT_PAGE -> vm.turn(1)
                    ShelfCommand.PREVIOUS_PAGE -> vm.turn(-1)
                    ShelfCommand.OPEN_MENU -> toggleControls(moveFocus = true)
                    else -> backPress()
                }
                true
            }
            else -> false
        }
    }) {
        if (controls) Row(Modifier.fillMaxWidth().onFocusChanged { topFocused = it.hasFocus }.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, Modifier.focusRequester(firstControl).testTag("reader_library")) { Text("Library") }
            TextButton({ appearance = true }, enabled = item != null) { Text("Appearance") }
            TextButton({ scale = if (scale == 1f) 2f else 1f; panX = 0f; panY = 0f }) { Text(if (scale == 1f) "Zoom in" else "Reset zoom") }
            TextButton({ hideControls() }) { Text("Hide controls") }
            // Input-discovery hint (Phase 2A.1): decorative only, since no single existing control is exactly
            // "Back" to merge this into; the system Back gesture/button remains self-describing to TalkBack.
            backHint?.let { Row(Modifier.padding(start = 4.dp).testTag("back_hint").clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) { InputKeycap(it); Text("Back", color = t.colors.secondary, style = t.typography.labelSmall) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().focusRequester(pageFocus).focusable().testTag("reader_page")
            .semantics {
                // Tap zones (edges turn pages, center toggles chrome) and double-tap-to-zoom are unchanged;
                // this only adds an accessibility action, exposed exclusively while chrome is hidden, so
                // TalkBack's announced instruction always matches an action that actually reveals chrome.
                if (controls) stateDescription = "Controls shown"
                else {
                    stateDescription = "Controls hidden"
                    onClick(label = "Show reader controls") { toggleControls(moveFocus = true); true }
                }
            }
            .pointerInput(state.page, rtl) {
                detectTapGestures(onDoubleTap = { modality = InputModality.TOUCH; scale = if (scale == 1f) 2f else 1f; panX = 0f; panY = 0f }, onTap = { point ->
                    modality = InputModality.TOUCH
                    when {
                        point.x < size.width * .25f -> vm.turn(if (rtl) 1 else -1)
                        point.x > size.width * .75f -> vm.turn(if (rtl) -1 else 1)
                        else -> toggleControls(moveFocus = false)
                    }
                })
            }.pointerInput(state.page, rtl) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var horizontal = 0f
                    var vertical = 0f
                    var transformed = scale > 1f
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (event.changes.count { it.pressed } > 1 || scale > 1f) {
                            transformed = true
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            panX = (panX + pan.x).coerceIn(-size.width * scale, size.width * scale)
                            panY = (panY + pan.y).coerceIn(-size.height * scale, size.height * scale)
                            event.changes.forEach { it.consume() }
                        } else {
                            horizontal += pan.x; vertical += pan.y
                            if (abs(horizontal) > viewConfiguration.touchSlop && abs(horizontal) > abs(vertical))
                                event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    // Swiping toward the reading direction's start turns forward: left in LTR, right in RTL.
                    if (!transformed && abs(horizontal) > 64.dp.toPx() && abs(horizontal) > abs(vertical)) {
                        modality = InputModality.TOUCH
                        vm.turn(if ((horizontal > 0) == rtl) 1 else -1)
                    }
                }
            }, contentAlignment = Alignment.Center) {
            state.bitmap?.let { bitmap ->
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                val fitWidth = state.preferences.fit == FitMode.WIDTH
                key(state.page) {
                    Box(if (fitWidth) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Image(image, "Page ${state.page + 1} of ${state.count}",
                            (if (fitWidth) Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height) else Modifier.fillMaxSize())
                                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = panX; translationY = panY }, contentScale = ContentScale.Fit)
                    }
                }
            }
            if (state.loading && state.error == null) CircularProgressIndicator()
            state.error?.let { message -> Surface(color = t.colors.surface, shape = t.shapes.small) {
                Column(Modifier.padding(16.dp).widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message)
                    if (state.count > 0) TextButton(vm::retry) { Text("Retry page") } else TextButton(onBack) { Text("Back to Library") }
                }
            } }
        }
        // Page controls follow the reading direction: in right-to-left reading, Next sits on the left.
        if (controls) CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column(Modifier.padding(horizontal = 8.dp).onFocusChanged { bottomFocused = it.hasFocus }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ vm.turn(-1) }, Modifier.let { m -> previousHint?.let { m.semantics { contentDescription = "Previous, $it" } } ?: m },
                        enabled = state.page > 0) { previousHint?.let { InputKeycap(it, Modifier.padding(end = 4.dp)) }; Text("Previous") }
                    // Numbers keep left-to-right order inside the mirrored row ("3 / 193", never "193 / 3").
                    if (state.count > 0) Text("${(sliderTarget?.roundToInt() ?: state.page) + 1} / ${state.count}", Modifier.testTag("page_number"),
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                    else if (state.loading && state.error == null) Text("Opening…", color = t.colors.secondary)
                    TextButton({ vm.turn(1) }, Modifier.let { m -> nextHint?.let { m.semantics { contentDescription = "Next, $it" } } ?: m },
                        enabled = state.page + 1 < state.count) { Text("Next"); nextHint?.let { InputKeycap(it, Modifier.padding(start = 4.dp)) } }
                }
                if (state.count > 1) Slider(sliderTarget ?: state.page.toFloat(), { sliderTarget = it },
                    valueRange = 0f..(state.count - 1).toFloat(),
                    onValueChangeFinished = { sliderTarget?.let { vm.showPage(it.roundToInt()) }; sliderTarget = null })
            }
        }
    }
}
