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
import com.d4guilar.shelfos.core.reader.PageGroup
import com.d4guilar.shelfos.core.reader.capabilities
import com.d4guilar.shelfos.core.reader.fixedReaderClampPan
import com.d4guilar.shelfos.core.reader.fixedReaderFittedContentSize
import com.d4guilar.shelfos.core.reader.fixedReaderMaxPan
import com.d4guilar.shelfos.core.reader.fixedReaderMaxPanY
import com.d4guilar.shelfos.core.reader.fixedReaderVerticalScaleOverflow
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import androidx.compose.ui.platform.LocalDensity
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
    // Phase 3B: the thumbnail strip is its own overlay (like Appearance), not part of the permanent chrome --
    // reopens closed on recreation like the other reader dialogs (none of them persist their open/closed state),
    // and re-derives its own content from vm.thumbnails/state.page rather than any separately-persisted position.
    var thumbnails by rememberSaveable { mutableStateOf(false) }
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
    val pageSliderDescription = stringResource(R.string.content_desc_page_slider)
    // Phase 3C: per-slot accessibility labels within an active spread (see the spread rendering block below).
    val currentSpreadPageDescription = stringResource(R.string.content_desc_spread_current_page)
    val pageUnavailableDescription = stringResource(R.string.content_desc_spread_page_unavailable)

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
    val screenDensity = LocalDensity.current
    val gutterPx = with(screenDensity) { t.spacing.small.toPx() }
    LaunchedEffect(viewportSize, scale, fitWidth, state.slots) {
        val (contentW, contentH) = combinedContentDimensions(state.slots, gutterPx) ?: return@LaunchedEffect
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val content = fixedReaderFittedContentSize(contentW, contentH,
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
    if (appearance && item != null) ReaderAppearance(state.preferences, capabilities(item.format, item.category), { appearance = false },
        vm::applyAppearance, vm::resetAppearance)
    if (thumbnails && state.count > 0) ThumbnailNavigator(state.count, state.page, rtl, vm.thumbnails,
        onSelect = { page -> vm.showPage(page); thumbnails = false }, onDismiss = { thumbnails = false })

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
            TextButton({ thumbnails = true }, Modifier.testTag("reader_thumbnails"), enabled = state.count > 0) { Text(stringResource(R.string.action_thumbnails)) }
            TextButton({ scale = if (scale == 1f) 2f else 1f; panX = 0f; panY = 0f }) { Text(stringResource(if (scale == 1f) R.string.action_zoom_in else R.string.action_reset_zoom)) }
            TextButton({ hideControls() }) { Text(stringResource(R.string.action_hide_controls)) }
            // Input-discovery hint (Phase 2A.1): decorative only, since no single existing control is exactly
            // "Back" to merge this into; the system Back gesture/button remains self-describing to TalkBack.
            backHint?.let { Row(Modifier.padding(start = 4.dp).testTag("back_hint").clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) { InputKeycap(it); Text(stringResource(R.string.action_back), color = t.colors.secondary, style = t.typography.labelSmall) } }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().focusRequester(pageFocus).focusable().testTag("reader_page")
            .onSizeChanged { viewportSize = it
                vm.updateViewport(it.width, it.height, with(screenDensity) { it.width.toDp().value.toInt() }) }
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
                            val combined = combinedContentDimensions(state.slots, gutterPx)
                            if (combined != null) {
                                // Phase 2D.1: clamp against the actual fitted-content-vs-viewport geometry (not
                                // the viewport's own size) using the NEW scale/translation together, so the
                                // page can never be dragged past its own real edge into empty space. Phase 3C:
                                // the same clamp now operates on the combined spread content box (see
                                // combinedContentDimensions) when 2 slots are visible, never on either page's
                                // bitmap independently -- there is no separate per-page zoom/pan state.
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                val content = fixedReaderFittedContentSize(combined.first, combined.second,
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
            if (state.slots.size <= 1) state.bitmap?.let { bitmap ->
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                key(state.page) {
                    val scrollState = rememberScrollState()
                    val density = LocalDensity.current
                    // Phase 2D.1 remediation, round two (zoomed Fit Width top/bottom reachability): `graphicsLayer`
                    // scales the Image visually around its own layout center without changing its *layout* size, so
                    // `verticalScroll` only ever sees the unscaled fitted height `H` -- its scroll range stayed
                    // `max(0, H - viewport)` when the visually-scaled content actually needs `max(0, scale*H -
                    // viewport)`, permanently hiding the outer fraction of a zoomed tall page at both ends. Fix:
                    // reserve `overflow` of blank layout space above and below the Image, OUTSIDE its graphicsLayer
                    // (so the reserved space is never itself scaled), making the scrollable column's measured height
                    // exactly `H + 2*overflow == scale*H` -- matching the visual extent and handing verticalScroll
                    // the correct range for free. See fixedReaderVerticalScaleOverflow's doc for the full math. Fit
                    // Page needs none of this: it has no scroll container.
                    val fitWidthContentHeight = if (fitWidth) fixedReaderFittedContentSize(bitmap.width.toFloat(),
                        bitmap.height.toFloat(), viewportSize.width.toFloat(), viewportSize.height.toFloat(), true).height else 0f
                    val verticalOverflowPx = if (fitWidth) fixedReaderVerticalScaleOverflow(fitWidthContentHeight, scale) else 0f
                    val verticalOverflowDp = with(density) { verticalOverflowPx.toDp() }
                    if (fitWidth) {
                        Column(Modifier.fillMaxSize().verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(verticalOverflowDp))
                            Image(image, String.format(pageOfCountTemplate, state.page + 1, state.count),
                                Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height)
                                    // Fit Width never applies translationY (verticalScroll owns all vertical movement
                                    // there); panY is always 0 in that mode, but the graphicsLayer also forces it
                                    // structurally so the two vertical-movement systems can never both act at once.
                                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = panX; translationY = 0f },
                                contentScale = ContentScale.Fit)
                            Spacer(Modifier.height(verticalOverflowDp))
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Image(image, String.format(pageOfCountTemplate, state.page + 1, state.count),
                                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = panX; translationY = panY },
                                contentScale = ContentScale.Fit)
                        }
                    }
                    // Phase 2D.1 remediation test seam: exposes the real verticalScroll state (value/maxValue) that
                    // Fit Width's vertical movement now exclusively relies on, plus the live fitted (unscaled)
                    // content height, so instrumented tests can independently compute the real scaled visible
                    // bitmap-space range (scroll position / (scale*H) * bitmapHeight) and assert actual edge
                    // visibility rather than only scroll-state values -- mirroring the existing reader_transform_probe
                    // pattern below.
                    if (fitWidth) Text("", Modifier.size(0.dp).testTag("reader_scroll_probe").clearAndSetSemantics {
                        stateDescription = "${scrollState.value},${scrollState.maxValue},$fitWidthContentHeight"
                    })
                }
                // Phase 2D.1 test seam: zero-size and semantics-cleared (invisible to users and TalkBack), but
                // queryable by testTag so instrumented tests can assert the real production scale/pan state stays
                // within bounds after a gesture, without a larger debug-only state-exposure API.
                Text("", Modifier.size(0.dp).testTag("reader_transform_probe").clearAndSetSemantics {
                    stateDescription = "$scale,$panX,$panY"
                })
            }
            // Phase 3C: an active 2-page spread. Two independent Images, never one stitched bitmap (see
            // `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s "do NOT stitch bitmaps" requirement) -- physical left/right
            // order mirrors for RTL Manga via `physicalOrder`, while `state.slots`/`state.page` (the logical
            // source identity) are never reordered themselves. Zoom/pan acts on the whole Row as one unit (a
            // single shared `graphicsLayer`, exactly as the single-page case above uses one `graphicsLayer` on
            // its one Image) -- there is no independent per-slot transform state.
            if (state.slots.size >= 2) {
                val combined = combinedContentDimensions(state.slots, gutterPx)
                if (combined != null) key(state.slots.map { it.page }) {
                    val scrollState = rememberScrollState()
                    val density = LocalDensity.current
                    val content = fixedReaderFittedContentSize(combined.first, combined.second,
                        viewportSize.width.toFloat(), viewportSize.height.toFloat(), fitWidth)
                    val contentWidthDp = with(density) { content.width.toDp() }
                    val contentHeightDp = with(density) { content.height.toDp() }
                    val verticalOverflowPx = if (fitWidth) fixedReaderVerticalScaleOverflow(content.height, scale) else 0f
                    val verticalOverflowDp = with(density) { verticalOverflowPx.toDp() }
                    val physicalSlots = PageGroup(state.slots.map { it.page }).physicalOrder(rtl).map { p -> state.slots.first { it.page == p } }
                    val spreadRow: @Composable () -> Unit = {
                        Row(Modifier.width(contentWidthDp).height(contentHeightDp)
                                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = panX; translationY = if (fitWidth) 0f else panY },
                            verticalAlignment = Alignment.CenterVertically) {
                            physicalSlots.forEachIndexed { i, slot ->
                                if (i > 0) Spacer(Modifier.width(with(density) { gutterPx.toDp() }))
                                val bmp = slot.bitmap
                                if (bmp != null) {
                                    val image = remember(bmp) { bmp.asImageBitmap() }
                                    val isCurrent = slot.page == state.page
                                    val description = if (isCurrent) String.format(currentSpreadPageDescription, slot.page + 1, state.count)
                                        else String.format(pageOfCountTemplate, slot.page + 1, state.count)
                                    Image(image, description, Modifier.fillMaxHeight().aspectRatio(bmp.width.toFloat() / bmp.height), contentScale = ContentScale.Fit)
                                } else {
                                    val siblingAspect = state.slots.firstNotNullOfOrNull { it.bitmap }?.let { it.width.toFloat() / it.height } ?: 0.7f
                                    Box(Modifier.fillMaxHeight().aspectRatio(siblingAspect).background(t.colors.surface)
                                        .semantics { contentDescription = String.format(pageUnavailableDescription, slot.page + 1) },
                                        contentAlignment = Alignment.Center) { Text(stringResource(R.string.label_unavailable)) }
                                }
                            }
                        }
                    }
                    if (fitWidth) Column(Modifier.fillMaxSize().verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(verticalOverflowDp)); spreadRow(); Spacer(Modifier.height(verticalOverflowDp))
                    } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { spreadRow() }
                    if (fitWidth) Text("", Modifier.size(0.dp).testTag("reader_scroll_probe").clearAndSetSemantics {
                        stateDescription = "${scrollState.value},${scrollState.maxValue},${content.height}"
                    })
                }
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
                    Modifier.semantics { contentDescription = pageSliderDescription },
                    valueRange = 0f..(state.count - 1).toFloat(),
                    onValueChangeFinished = { sliderTarget?.let { vm.showPage(it.roundToInt()) }; sliderTarget = null })
            }
        }
    }
}

/**
 * Phase 3C: a virtual combined-content size standing in for a single bitmap's width/height wherever the existing
 * Fit Page/Fit Width/zoom-pan transform math ([fixedReaderFittedContentSize], [fixedReaderMaxPan],
 * [fixedReaderMaxPanY]) expects one. For 1 visible slot this is exactly that slot's own bitmap size (byte-for-byte
 * the pre-3C single-page behavior). For 2 slots, each bitmap is notionally scaled to a shared reference height
 * (the taller of the two, so neither bitmap is ever upscaled relative to the other) and laid side by side with
 * [gutterPx] between them -- this lets the EXISTING pure transform functions treat "the whole spread" as one
 * fittable/zoomable/pannable content box with no new geometry primitive, and without the two bitmaps ever being
 * merged into one (they stay two independent `Image`s in [FixedReaderScreen]'s spread `Row`). If only one of the
 * two slots has a bitmap (its sibling failed to decode -- see "Corrupt page within a spread"), this degrades to
 * that one bitmap's own size, so fit/zoom geometry is still driven by real decoded content, not an error
 * placeholder's arbitrary size. Returns `null` only when no slot has a bitmap yet (nothing to fit against).
 */
private fun combinedContentDimensions(slots: List<PageSlot>, gutterPx: Float): Pair<Float, Float>? {
    val bitmaps = slots.mapNotNull { it.bitmap }
    if (bitmaps.isEmpty()) return null
    if (bitmaps.size == 1) return bitmaps[0].width.toFloat() to bitmaps[0].height.toFloat()
    val refHeight = bitmaps.maxOf { it.height }.toFloat()
    val totalWidth = bitmaps.sumOf { (refHeight * it.width / it.height).toDouble() }.toFloat() + gutterPx
    return totalWidth to refHeight
}
