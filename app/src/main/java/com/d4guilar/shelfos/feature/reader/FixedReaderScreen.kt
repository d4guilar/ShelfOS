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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.InputKeycap
import com.d4guilar.shelfos.core.designsystem.resolve
import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.capabilities
import com.d4guilar.shelfos.core.reader.fixedReaderClampPan
import com.d4guilar.shelfos.core.reader.fixedReaderFittedContentSize
import com.d4guilar.shelfos.core.reader.fixedReaderMaxPan
import com.d4guilar.shelfos.core.reader.fixedReaderMaxPanY
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
    // Fit-mode change is folded into the same page-change reset key (Phase 2D.1): switching Fit Page <-> Fit
    // Width while zoomed/panned would otherwise keep a transform computed for the old fit's content geometry.
    val fitWidth = state.preferences.fit == FitMode.WIDTH
    var scale by remember(state.page, fitWidth) { mutableFloatStateOf(1f) }
    var panX by remember(state.page, fitWidth) { mutableFloatStateOf(0f) }
    var panY by remember(state.page, fitWidth) { mutableFloatStateOf(0f) }
    // Live viewport size (Phase 2D.1): the gesture handler already reads a fresh `size` on every pointer event,
    // but an idle transform must also be re-clamped after a resize/rotation/fold with no new gesture.
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var sliderTarget by remember { mutableStateOf<Float?>(null) }
    val pageFocus = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val rtl = readingDirection(item?.category ?: MediaCategory.BOOK, state.preferences.direction) == ReadingDirection.RTL
    val previousHint = InputHints.hint(ShelfCommand.PREVIOUS_PAGE, modality, rtl)
    val nextHint = InputHints.hint(ShelfCommand.NEXT_PAGE, modality, rtl)
    val backHint = InputHints.hint(ShelfCommand.BACK, modality, rtl)
    // Semantics lambdas run outside composition, so these localized strings are resolved here.
    val controlsShownDescription = stringResource(R.string.content_desc_controls_shown)
    val controlsHiddenDescription = stringResource(R.string.content_desc_controls_hidden)
    val showControlsActionLabel = stringResource(R.string.action_show_controls)
    val previousLabel = stringResource(R.string.content_desc_previous_hint)
    val nextLabel = stringResource(R.string.content_desc_next_hint)
    fun previousHintDescription(hint: String) = String.format(previousLabel, hint)
    fun nextHintDescription(hint: String) = String.format(nextLabel, hint)
    val pageOfCountTemplate = stringResource(R.string.content_desc_page_of_count)
    val openingLabel = stringResource(R.string.reader_opening)

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
    // Phase 2D.1: re-clamp an idle transform whenever the viewport, zoom, fit mode or bitmap changes, so a
    // resize/rotation/fold that happens outside an active gesture can never leave panX/panY out of bounds.
    LaunchedEffect(viewportSize, scale, fitWidth, state.bitmap) {
        val bitmap = state.bitmap
        if (bitmap == null || viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val content = fixedReaderFittedContentSize(bitmap.width.toFloat(), bitmap.height.toFloat(),
            viewportSize.width.toFloat(), viewportSize.height.toFloat(), fitWidth)
        panX = fixedReaderClampPan(panX, fixedReaderMaxPan(content.width, viewportSize.width.toFloat(), scale))
        // Phase 2D.1 remediation (Fit Width tall-content blocker): Fit Width's vertical movement belongs
        // entirely to `verticalScroll`, never to this graphicsLayer pan (see fixedReaderMaxPanY's doc and the
        // gesture handler below for why the previous shared Y formula allowed the page to be dragged into gray
        // when the fitted content was taller than the viewport). This re-clamp can therefore never resurrect a
        // stale nonzero Fit Width panY across a resize/rotation/fold.
        panY = fixedReaderClampPan(panY, fixedReaderMaxPanY(content, viewportSize.height.toFloat(), scale, fitWidth))
    }
    BackHandler { backPress() }
    if (appearance && item != null) ReaderAppearance(state.preferences, capabilities(item.format), { appearance = false },
        vm::applyAppearance, vm::resetAppearance)

    Column(Modifier.fillMaxSize().background(t.colors.canvas).testTag("reader_screen").onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        // Raw modality classification is independent of which (if any) ShelfCommand the event becomes: it must
        // also apply to focus-navigation keys, CONFIRM and anything else that never reaches the branch below.
        // inputModalityOrNull() itself excludes the raw system Back/Home keys (never a modality signal) rather
        // than filtering by the resolved *semantic* command here, since Escape/gamepad B legitimately produce
        // ShelfCommand.BACK while still being real, attributable keyboard/controller input.
        native.inputModalityOrNull()?.let { modality = it }
        when (val command = native.readerCommand(rtl, controls && (topFocused || bottomFocused))) {
            ShelfCommand.NEXT_PAGE, ShelfCommand.PREVIOUS_PAGE, ShelfCommand.OPEN_MENU, ShelfCommand.BACK -> {
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
            TextButton(onBack, Modifier.focusRequester(firstControl).testTag("reader_library")) { Text(stringResource(R.string.nav_library)) }
            TextButton({ appearance = true }, enabled = item != null) { Text(stringResource(R.string.action_appearance)) }
            TextButton({ scale = if (scale == 1f) 2f else 1f; panX = 0f; panY = 0f }) { Text(stringResource(if (scale == 1f) R.string.action_zoom_in else R.string.action_reset_zoom)) }
            TextButton({ hideControls() }) { Text(stringResource(R.string.action_hide_controls)) }
            // Input-discovery hint (Phase 2A.1): decorative only, since no single existing control is exactly
            // "Back" to merge this into; the system Back gesture/button remains self-describing to TalkBack.
            backHint?.let { Row(Modifier.padding(start = 4.dp).testTag("back_hint").clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) { InputKeycap(it); Text(stringResource(R.string.action_back), color = t.colors.secondary, style = t.typography.labelSmall) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().focusRequester(pageFocus).focusable().testTag("reader_page")
            .onSizeChanged { viewportSize = it }
            .semantics {
                // Tap zones (edges turn pages, center toggles chrome) and double-tap-to-zoom are unchanged;
                // this only adds an accessibility action, exposed exclusively while chrome is hidden, so
                // TalkBack's announced instruction always matches an action that actually reveals chrome.
                if (controls) stateDescription = controlsShownDescription
                else {
                    stateDescription = controlsHiddenDescription
                    onClick(label = showControlsActionLabel) { toggleControls(moveFocus = true); true }
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
            }.pointerInput(state.page, rtl, fitWidth) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var horizontal = 0f
                    var vertical = 0f
                    var transformed = scale > 1f
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val pointerCount = event.changes.count { it.pressed }
                        // Phase 2D.1 remediation (Fit Width tall-content blocker): Fit Width's `Image` lives
                        // inside `verticalScroll`, which already owns vertical movement for content taller than
                        // the viewport. Letting this handler ALSO drive `translationY` from the same one-finger
                        // drag double-moved the page (both systems advancing together) and, worse, the old
                        // shared Y clamp measured excess against the viewport alone, not the already-tall
                        // content, so panY could grow unbounded and push the page into gray. The fix: in Fit
                        // Width, only a real pinch (2+ pointers) or a one-finger drag that is horizontal-
                        // dominant *while already zoomed* is treated as a transform gesture; a one-finger
                        // vertical-dominant drag is left unconsumed so `verticalScroll`'s own gesture detector
                        // handles it, and panY is never written here (it stays at the 0 the fit-mode/page reset
                        // already gives it). Fit Page is unaffected: it has no scroll container, so it keeps the
                        // original full pinch-zoom + clamped pan X/Y behavior unchanged below.
                        val isTransformGesture = if (fitWidth) pointerCount > 1 || (scale > 1f && abs(pan.x) > abs(pan.y))
                            else pointerCount > 1 || scale > 1f
                        if (isTransformGesture) {
                            transformed = true
                            val bitmap = state.bitmap
                            if (bitmap != null) {
                                // Phase 2D.1: clamp against the actual fitted-content-vs-viewport geometry (not
                                // the viewport's own size) using the NEW scale/translation together, so the
                                // page can never be dragged past its own real edge into empty space.
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                val content = fixedReaderFittedContentSize(bitmap.width.toFloat(), bitmap.height.toFloat(),
                                    size.width.toFloat(), size.height.toFloat(), fitWidth)
                                scale = newScale
                                panX = fixedReaderClampPan(panX + pan.x, fixedReaderMaxPan(content.width, size.width.toFloat(), newScale))
                                panY = fixedReaderClampPan(panY + pan.y, fixedReaderMaxPanY(content, size.height.toFloat(), newScale, fitWidth))
                            }
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
                key(state.page) {
                    val scrollState = rememberScrollState()
                    Box(if (fitWidth) Modifier.fillMaxSize().verticalScroll(scrollState) else Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Image(image, String.format(pageOfCountTemplate, state.page + 1, state.count),
                            (if (fitWidth) Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height) else Modifier.fillMaxSize())
                                // Fit Width never applies translationY (verticalScroll owns vertical movement there,
                                // see the gesture handler and viewport-resize LaunchedEffect above); panY is always
                                // 0 in that mode, but the graphicsLayer also forces it structurally so the two
                                // vertical-movement systems can never both act on the same gesture.
                                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = panX; translationY = if (fitWidth) 0f else panY }, contentScale = ContentScale.Fit)
                    }
                    // Phase 2D.1 remediation test seam: exposes the real verticalScroll state (value/maxValue) that
                    // Fit Width's vertical movement now exclusively relies on, so instrumented tests can drive and
                    // assert real scroll-extreme geometry without a larger debug-only API, mirroring the existing
                    // reader_transform_probe pattern below.
                    if (fitWidth) Text("", Modifier.size(0.dp).testTag("reader_scroll_probe").clearAndSetSemantics {
                        stateDescription = "${scrollState.value},${scrollState.maxValue}"
                    })
                }
                // Phase 2D.1 test seam: zero-size and semantics-cleared (invisible to users and TalkBack), but
                // queryable by testTag so instrumented tests can assert the real production scale/pan state stays
                // within bounds after a gesture, without a larger debug-only state-exposure API.
                Text("", Modifier.size(0.dp).testTag("reader_transform_probe").clearAndSetSemantics {
                    stateDescription = "$scale,$panX,$panY"
                })
            }
            if (state.loading && state.error == null) CircularProgressIndicator()
            state.error?.let { message -> Surface(color = t.colors.surface, shape = t.shapes.small) {
                Column(Modifier.padding(16.dp).widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message.resolve())
                    if (state.count > 0) TextButton(vm::retry) { Text(stringResource(R.string.action_retry_page)) } else TextButton(onBack) { Text(stringResource(R.string.action_back_to_library)) }
                }
            } }
        }
        // Page controls follow the reading direction: in right-to-left reading, Next sits on the left.
        if (controls) CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column(Modifier.padding(horizontal = 8.dp).onFocusChanged { bottomFocused = it.hasFocus }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ vm.turn(-1) }, Modifier.let { m -> previousHint?.let { m.semantics { contentDescription = previousHintDescription(it) } } ?: m },
                        enabled = state.page > 0) { previousHint?.let { InputKeycap(it, Modifier.padding(end = 4.dp)) }; Text(stringResource(R.string.action_previous)) }
                    // Numbers keep left-to-right order inside the mirrored row ("3 / 193", never "193 / 3").
                    if (state.count > 0) Text("${(sliderTarget?.roundToInt() ?: state.page) + 1} / ${state.count}", Modifier.testTag("page_number"),
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                    else if (state.loading && state.error == null) Text(openingLabel, color = t.colors.secondary)
                    TextButton({ vm.turn(1) }, Modifier.let { m -> nextHint?.let { m.semantics { contentDescription = nextHintDescription(it) } } ?: m },
                        enabled = state.page + 1 < state.count) { Text(stringResource(R.string.action_next)); nextHint?.let { InputKeycap(it, Modifier.padding(start = 4.dp)) } }
                }
                if (state.count > 1) Slider(sliderTarget ?: state.page.toFloat(), { sliderTarget = it },
                    valueRange = 0f..(state.count - 1).toFloat(),
                    onValueChangeFinished = { sliderTarget?.let { vm.showPage(it.roundToInt()) }; sliderTarget = null })
            }
        }
    }
}
