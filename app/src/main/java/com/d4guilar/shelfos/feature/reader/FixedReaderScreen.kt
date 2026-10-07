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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.d4guilar.shelfos.core.reader.FoldPaneWidths
import com.d4guilar.shelfos.core.reader.FoldPresentation
import com.d4guilar.shelfos.core.reader.FoldRect
import com.d4guilar.shelfos.core.reader.PageGeometry
import com.d4guilar.shelfos.core.reader.PageGroup
import com.d4guilar.shelfos.core.reader.ReaderFoldDescriptor
import com.d4guilar.shelfos.core.reader.ReaderFoldLayout
import com.d4guilar.shelfos.core.reader.capabilities
import com.d4guilar.shelfos.core.reader.fixedReaderClampPan
import com.d4guilar.shelfos.core.reader.fixedReaderFittedContentSize
import com.d4guilar.shelfos.core.reader.fixedReaderMaxPan
import com.d4guilar.shelfos.core.reader.fixedReaderMaxPanY
import com.d4guilar.shelfos.core.reader.fixedReaderVerticalScaleOverflow
import com.d4guilar.shelfos.core.reader.resolveReaderFoldLayout
import com.d4guilar.shelfos.core.reader.selectSoloPane
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import com.d4guilar.shelfos.domain.library.*
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Original-page reader. Back, Escape and gamepad B reveal hidden controls first; only once controls are
 * visible do they leave the reader (ADR-0023). Direction changes navigation and control placement, never
 * the stored page order or the artwork.
 */
@Composable
fun FixedReaderScreen(vm: FixedReaderViewModel, fold: ReaderFoldDescriptor? = null, onBack: () -> Unit) {
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
    // Phase 3D: the reader surface's own bounds in WINDOW coordinates (captured below, on the "reader_page" Box
    // itself) and the fold-aware layout resolved from them + the fold descriptor handed down from MainActivity.
    // See FoldLayout.kt's class docs for why this translation happens exactly once, here, rather than comparing
    // window-space fold bounds against local Compose coordinates anywhere else.
    var foldLayoutState by remember { mutableStateOf(ReaderFoldLayout.flat(FoldRect(0f, 0f, 0f, 0f))) }
    // Reader chrome (Appearance/Pages/Zoom/Back, Previous/Next/slider) must not sit under an occluding/
    // separating hinge. For a VERTICAL_SPLIT, chrome is confined to ONE safe pane (the same one a solo page
    // would use) rather than spanning the full width across the hinge; FLAT/HORIZONTAL_SPLIT are unaffected
    // (chrome already sits at the screen's true top/bottom edges, clear of a horizontal fold in the ordinary
    // case, and there is no vertical hinge to avoid horizontally).
    val chromePane = if (foldLayoutState.presentation == FoldPresentation.VERTICAL_SPLIT) selectSoloPane(foldLayoutState, rtl) else null
    // The ONE pane active content renders into for every case EXCEPT a vertical-split spread (which needs two
    // independently-positioned panes -- see foldSpreadPanes below): FLAT's whole bounds, HORIZONTAL_SPLIT's
    // chosen safe pane (reusing the existing flat 3C single/spread rendering unchanged, just confined to that
    // pane instead of the whole window -- "run the EXISTING width-based behavior inside that safe pane"), or a
    // VERTICAL_SPLIT's chosen solo pane for a single visible page (cover, landscape split, explicit SINGLE,
    // AUTO-resolved-single, unmatched final page -- never stretched across or hidden behind the hinge).
    val activePane: FoldRect? = when (foldLayoutState.presentation) {
        FoldPresentation.FLAT -> foldLayoutState.flatPane
        FoldPresentation.HORIZONTAL_SPLIT -> foldLayoutState.safePane
        FoldPresentation.VERTICAL_SPLIT -> if (state.slots.size <= 1) selectSoloPane(foldLayoutState, rtl) else null
    }
    // Non-null only for an active two-page spread under a vertical fold split -- the one case needing a
    // genuinely new fold-aware renderer (see the "Phase 3D vertical fold spread" block below) rather than the
    // existing combinedContentDimensions Row, because a Row's internal gutter Spacer is not pinned to the
    // REAL physical hinge position once the whole Row can be panned -- it would let artwork drift under the
    // hinge exactly as the 3D contract's "never rely only on a Spacer" warning describes.
    val foldSpreadPanes: Pair<FoldRect, FoldRect>? = if (foldLayoutState.presentation == FoldPresentation.VERTICAL_SPLIT &&
        state.slots.size >= 2) foldLayoutState.leftPane?.let { l -> foldLayoutState.rightPane?.let { r -> l to r } } else null
    val effectiveViewport: IntSize = activePane?.let { IntSize(it.width.roundToInt().coerceAtLeast(0), it.height.roundToInt().coerceAtLeast(0)) }
        ?: viewportSize
    LaunchedEffect(viewportSize, foldLayoutState, scale, fitWidth, state.slots) {
        val spreadPanes = foldSpreadPanes
        if (spreadPanes != null) {
            val physical = PageGroup(state.slots.map { it.page }).physicalOrder(rtl).map { p -> state.slots.first { it.page == p } }
            val (maxX, maxY) = foldSpreadSharedMaxPan(slotDims(physical[0]), spreadPanes.first,
                slotDims(physical[1]), spreadPanes.second, scale, fitWidth)
            panX = fixedReaderClampPan(panX, maxX); panY = fixedReaderClampPan(panY, maxY)
        } else {
            val (contentW, contentH) = combinedContentDimensions(state.slots, gutterPx) ?: return@LaunchedEffect
            if (effectiveViewport.width <= 0 || effectiveViewport.height <= 0) return@LaunchedEffect
            val content = fixedReaderFittedContentSize(contentW, contentH,
                effectiveViewport.width.toFloat(), effectiveViewport.height.toFloat(), fitWidth)
            panX = fixedReaderClampPan(panX, fixedReaderMaxPan(content.width, effectiveViewport.width.toFloat(), scale))
            // Phase 2D.1 remediation (Fit Width tall-content blocker): Fit Width's vertical movement belongs
            // entirely to `verticalScroll`, never to this graphicsLayer pan (see fixedReaderMaxPanY's doc and the
            // gesture handler below for why the previous shared Y formula allowed the page to be dragged into gray
            // when the fitted content was taller than the viewport). This re-clamp can therefore never resurrect a
            // stale nonzero Fit Width panY across a resize/rotation/fold.
            panY = fixedReaderClampPan(panY, fixedReaderMaxPanY(content, effectiveViewport.height.toFloat(), scale, fitWidth))
        }
    }
    // Codex R1 finding 4 (3C remediation): the zoom/pan pointerInput coroutine below is deliberately keyed only
    // by (state.page, rtl, fitWidth) -- restarting a gesture mid-touch merely because AUTO flipped single<->spread
    // (or a retry replaced a slot's bitmap) without state.page itself changing would be a worse defect than the
    // one being fixed. rememberUpdatedState keeps the content geometry the gesture reads always current WITHOUT
    // restarting that coroutine and WITHOUT the coroutine ever closing over the raw state.slots/Bitmap list
    // itself -- only the minimal immutable dims it actually needs. Phase 3D adds the same discipline for fold
    // geometry: currentFoldSpreadGeometry/currentEffectiveViewport so a fold/unfold mid-gesture is always
    // reflected without restarting the coroutine or closing over the raw foldLayoutState.
    val currentContentDimensions = rememberUpdatedState(combinedContentDimensions(state.slots, gutterPx))
    val currentEffectiveViewport = rememberUpdatedState(effectiveViewport)
    val currentFoldSpreadGeometry = rememberUpdatedState(foldSpreadPanes?.let { (left, right) ->
        val physical = PageGroup(state.slots.map { it.page }).physicalOrder(rtl).map { p -> state.slots.first { it.page == p } }
        FoldSpreadGeometry(left, slotDims(physical[0]), right, slotDims(physical[1]))
    })
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
        // Phase 3D: confined to chromePane's width/offset under a vertical fold split (see chromePane's doc
        // above); an ordinary Modifier.fillMaxWidth() otherwise -- byte-for-byte the pre-3D behavior.
        val topChromeModifier = chromePane?.let { pane -> with(screenDensity) { Modifier.offset(x = pane.left.toDp()).width(pane.width.toDp()) } }
            ?: Modifier.fillMaxWidth()
        if (controls) Row(topChromeModifier.onFocusChanged { topFocused = it.hasFocus }.horizontalScroll(rememberScrollState()),
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
            // Phase 3D: onGloballyPositioned (superseding plain onSizeChanged) captures this surface's own
            // WINDOW-coordinate bounds -- not just its size -- so resolveReaderFoldLayout can intersect them
            // against the fold descriptor's own window-coordinate bounds and translate the result into this
            // surface's LOCAL coordinates (see resolveReaderFoldLayout's doc for why this exact translation is
            // the highest-risk part of this slice). foldPaneWidths is handed to the ViewModel ONLY when a real
            // vertical split with two usable panes exists; storing it (like viewportWidth/Height/Dp before it)
            // never itself forces a re-render -- see updateViewport's doc for the one narrow exception.
            .onGloballyPositioned { coordinates ->
                val size = coordinates.size
                viewportSize = size
                val windowBounds = coordinates.boundsInWindow()
                val readerBoundsWindow = FoldRect(windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom)
                val layout = resolveReaderFoldLayout(readerBoundsWindow, fold, gutterPx)
                foldLayoutState = layout
                val foldPaneWidths = if (layout.hasTwoPanes) with(screenDensity) {
                    val left = requireNotNull(layout.leftPane); val right = requireNotNull(layout.rightPane)
                    FoldPaneWidths(leftPx = left.width.roundToInt(), rightPx = right.width.roundToInt(),
                        leftDp = left.width.toDp().value, rightDp = right.width.toDp().value)
                } else null
                vm.updateViewport(size.width, size.height, with(screenDensity) { size.width.toDp().value.toInt() }, foldPaneWidths)
            }
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
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            // Phase 3D: a vertical-split spread clamps against BOTH panes' own fitted geometry
                            // (see foldSpreadSharedMaxPan's doc -- never the full box/viewport), read through
                            // rememberUpdatedState so a fold/unfold mid-gesture is always reflected without
                            // restarting this long-lived coroutine. Every other case (FLAT, HORIZONTAL_SPLIT, a
                            // VERTICAL_SPLIT solo page) keeps the exact pre-3D combinedContentDimensions clamp,
                            // just against the active PANE's size (currentEffectiveViewport) rather than always
                            // the raw gesture-reported `size` -- so a horizontal-fold-confined or solo-pane page
                            // can never be dragged past its own pane's real edge.
                            val foldGeometry = currentFoldSpreadGeometry.value
                            if (foldGeometry != null) {
                                val (maxX, maxY) = foldSpreadSharedMaxPan(foldGeometry.leftDims, foldGeometry.leftPane,
                                    foldGeometry.rightDims, foldGeometry.rightPane, newScale, fitWidth)
                                scale = newScale
                                panX = fixedReaderClampPan(panX + pan.x, maxX)
                                panY = fixedReaderClampPan(panY + pan.y, maxY)
                            } else {
                                // Codex R1 finding 4: read through rememberUpdatedState, never state.slots directly,
                                // so a resize-driven single<->spread flip or a retry mid-gesture is always reflected.
                                val combined = currentContentDimensions.value
                                val vp = currentEffectiveViewport.value
                                if (combined != null && vp.width > 0 && vp.height > 0) {
                                    // Phase 2D.1: clamp against the actual fitted-content-vs-viewport geometry (not
                                    // the viewport's own size) using the NEW scale/translation together, so the
                                    // page can never be dragged past its own real edge into empty space. Phase 3C:
                                    // the same clamp now operates on the combined spread content box (see
                                    // combinedContentDimensions) when 2 slots are visible, never on either page's
                                    // bitmap independently -- there is no separate per-page zoom/pan state. Phase
                                    // 3D: `vp` is the active PANE's size (FLAT/HORIZONTAL_SPLIT/VERTICAL_SPLIT-solo),
                                    // never the raw full-box `size`, so a fold-confined page's clamp matches the
                                    // pane it actually renders into.
                                    val content = fixedReaderFittedContentSize(combined.first, combined.second,
                                        vp.width.toFloat(), vp.height.toFloat(), fitWidth)
                                    scale = newScale
                                    panX = fixedReaderClampPan(panX + pan.x, fixedReaderMaxPan(content.width, vp.width.toFloat(), newScale))
                                    panY = fixedReaderClampPan(panY + pan.y, fixedReaderMaxPanY(content, vp.height.toFloat(), newScale, fitWidth))
                                }
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
            // Phase 3D: every case EXCEPT an active vertical-fold spread (activePane == null exactly then --
            // see foldSpreadPanes/activePane's docs above) renders into ONE pane, confined by offset+size+
            // clipToBounds -- for FLAT this pane is the whole reader surface (offset 0,0, full size), so this
            // wrapper is a visual no-op for the pre-3D flat case; for HORIZONTAL_SPLIT/VERTICAL_SPLIT-solo it
            // confines the EXISTING, unchanged single-page/legacy-spread rendering below to the chosen safe
            // pane instead of the whole window. clipToBounds() here is defense-in-depth (the outer "reader_page"
            // Box already clips to its own bounds) specifically for the case where the pane is narrower than
            // the full surface.
            if (activePane != null) Box(Modifier.align(Alignment.TopStart)
                    .offset(x = with(screenDensity) { activePane.left.toDp() }, y = with(screenDensity) { activePane.top.toDp() })
                    .size(width = with(screenDensity) { effectiveViewport.width.toDp() }, height = with(screenDensity) { effectiveViewport.height.toDp() })
                    .clipToBounds().testTag("reader_pane_content"), contentAlignment = Alignment.Center) {
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
                        bitmap.height.toFloat(), effectiveViewport.width.toFloat(), effectiveViewport.height.toFloat(), true).height else 0f
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
            if (state.slots.size >= 2 && foldSpreadPanes == null) {
                val combined = combinedContentDimensions(state.slots, gutterPx)
                if (combined != null) key(state.slots.map { it.page }) {
                    val scrollState = rememberScrollState()
                    val density = LocalDensity.current
                    val content = fixedReaderFittedContentSize(combined.first, combined.second,
                        effectiveViewport.width.toFloat(), effectiveViewport.height.toFloat(), fitWidth)
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
                                    Image(image, description, Modifier.fillMaxHeight().aspectRatio(bmp.width.toFloat() / bmp.height)
                                        .testTag("spread_slot_${slot.page}"), contentScale = ContentScale.Fit)
                                } else {
                                    // Codex R1 finding 3 (3C remediation): a failed slot's placeholder occupies
                                    // ITS OWN page geometry/aspect ratio -- never the healthy sibling's -- so a
                                    // corrupt first (or second) physical slot can never consume/constrain the
                                    // available width and push the healthy sibling outside its own correct box.
                                    Box(Modifier.fillMaxHeight().aspectRatio(placeholderAspect(slot.geometry)).background(t.colors.surface)
                                        .testTag("spread_slot_${slot.page}")
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
            } // closes the activePane-confined Box opened above
            // Phase 3D vertical-fold spread: the one case needing a dedicated renderer (see foldSpreadPanes'
            // doc). Each physical slot lives in its OWN fixed, clipped pane Box (never moving -- the hinge gap
            // between them is the real, unscaled distance between the two panes, not a Row Spacer that could
            // drift with pan/zoom) and fits WITHIN that pane independently ("each page fits inside its assigned
            // unobstructed pane," per the 3D Fit Page/Fit Width contract). Scale and pan stay ONE shared value
            // (no independent per-pane transform state) -- foldSpreadSharedMaxPan clamps that single pan value
            // against the MORE restrictive of the two panes' own bounds so neither pane's content can ever
            // overflow its own edge, and each pane's clipToBounds() is a hard safety net even if that clamp were
            // ever imprecise: artwork can never render past its own pane's boundary into the hinge, at any scale
            // or pan value, by construction. Unlike flat Fit Width, there is no verticalScroll container here
            // (a shared scroll across two independently-clipped panes has no single well-defined scroll position
            // with a hinge-interrupted layout); vertical overflow beyond a pane's own height is instead reached
            // via the same shared pan this block already clamps, exactly like Fit Page's reachability model --
            // an intentional, documented narrowing from flat Fit Width's auto-scroll convenience, never a
            // regression into unreachable/hidden content (flagged here as a 3F-follow-up refinement candidate,
            // not a silent gap).
            if (foldSpreadPanes != null) key(state.slots.map { it.page }) {
                val (leftPane, rightPane) = foldSpreadPanes
                val physicalSlots = PageGroup(state.slots.map { it.page }).physicalOrder(rtl).map { p -> state.slots.first { it.page == p } }
                val panes = listOf(physicalSlots[0] to leftPane, physicalSlots[1] to rightPane)
                panes.forEach { (slot, pane) ->
                    Box(Modifier.align(Alignment.TopStart)
                            .offset(x = with(screenDensity) { pane.left.toDp() }, y = with(screenDensity) { pane.top.toDp() })
                            .size(width = with(screenDensity) { pane.width.toDp() }, height = with(screenDensity) { pane.height.toDp() })
                            .clipToBounds().testTag("spread_slot_${slot.page}"), contentAlignment = Alignment.Center) {
                        val bmp = slot.bitmap
                        if (bmp != null) {
                            val image = remember(bmp) { bmp.asImageBitmap() }
                            val isCurrent = slot.page == state.page
                            val description = if (isCurrent) String.format(currentSpreadPageDescription, slot.page + 1, state.count)
                                else String.format(pageOfCountTemplate, slot.page + 1, state.count)
                            val fitted = fixedReaderFittedContentSize(bmp.width.toFloat(), bmp.height.toFloat(), pane.width, pane.height, fitWidth)
                            Image(image, description, Modifier.size(width = with(screenDensity) { fitted.width.toDp() },
                                    height = with(screenDensity) { fitted.height.toDp() })
                                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = panX; translationY = panY },
                                contentScale = ContentScale.Fit)
                        } else {
                            // Codex R1 finding 3 (3C): a failed slot's placeholder occupies its OWN page geometry,
                            // never a sibling's -- here that is simply "fill this slot's own fixed pane," since
                            // each pane is already sized/positioned independently (no shared combined box to
                            // mis-size around a corrupt sibling the way the flat Row model had to guard against).
                            Box(Modifier.fillMaxSize().background(t.colors.surface)
                                .semantics { contentDescription = String.format(pageUnavailableDescription, slot.page + 1) },
                                contentAlignment = Alignment.Center) { Text(stringResource(R.string.label_unavailable)) }
                        }
                    }
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
        // Page controls follow the reading direction: in right-to-left reading, Next sits on the left. Phase
        // 3D: confined to chromePane's width/offset under a vertical fold split, computed OUTSIDE the RTL
        // CompositionLocalProvider below so the pane's real physical (window-space) position is never itself
        // mirrored -- only the Row's internal Previous/Next arrangement mirrors for RTL, exactly as before.
        val bottomChromeModifier = chromePane?.let { pane -> with(screenDensity) { Modifier.offset(x = pane.left.toDp()).width(pane.width.toDp()) } }
            ?: Modifier.fillMaxWidth()
        if (controls) Box(bottomChromeModifier) { CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).onFocusChanged { bottomFocused = it.hasFocus }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ vm.turn(-1) }, Modifier.let { m -> previousHint?.let { m.semantics { contentDescription = previousHintDescription(it) } } ?: m },
                        // Codex R1 finding 6: semantic enablement via the SAME canonical navigation resolver
                        // turn() itself uses -- never raw `state.page > 0` arithmetic, which is wrong exactly at
                        // the final-complete-spread boundary (see FixedReaderViewModel.hasPrevious's doc).
                        enabled = vm.hasPrevious()) { previousHint?.let { InputKeycap(it, Modifier.padding(end = 4.dp)) }; Text(stringResource(R.string.action_previous)) }
                    // Numbers keep left-to-right order inside the mirrored row ("3 / 193", never "193 / 3").
                    if (state.count > 0) Text("${(sliderTarget?.roundToInt() ?: state.page) + 1} / ${state.count}", Modifier.testTag("page_number"),
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                    else if (state.loading && state.error == null) Text(openingLabel, color = t.colors.secondary)
                    TextButton({ vm.turn(1) }, Modifier.let { m -> nextHint?.let { m.semantics { contentDescription = nextHintDescription(it) } } ?: m },
                        enabled = vm.hasNext()) { Text(stringResource(R.string.action_next)); nextHint?.let { InputKeycap(it, Modifier.padding(start = 4.dp)) } }
                }
                if (state.count > 1) Slider(sliderTarget ?: state.page.toFloat(), { sliderTarget = it },
                    Modifier.semantics { contentDescription = pageSliderDescription },
                    valueRange = 0f..(state.count - 1).toFloat(),
                    onValueChangeFinished = { sliderTarget?.let { vm.showPage(it.roundToInt()) }; sliderTarget = null })
            }
        } }
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
    // Codex R1 finding 3 (3C remediation): every VISIBLE logical slot must participate in this combined geometry
    // even when its own bitmap failed to decode -- using that slot's OWN PageGeometry (already resolved by
    // FixedReaderViewModel.render regardless of decode outcome), never a healthy sibling's dimensions. The old
    // behavior (mapNotNull { it.bitmap }) silently dropped a failed slot from the combined box entirely, letting
    // a corrupt first/second slot consume or constrain width that rightfully belonged to both slots.
    if (slots.isEmpty() || slots.all { it.bitmap == null && it.geometry.isUnknown() }) return null
    val pairs = slots.map(::slotDims)
    if (pairs.size == 1) return pairs[0]
    val refHeight = pairs.maxOf { it.second }
    val totalWidth = pairs.sumOf { (refHeight * it.first / it.second).toDouble() }.toFloat() + gutterPx
    return totalWidth to refHeight
}

/** One [PageSlot]'s own dimensions for fit/zoom geometry -- its decoded bitmap when present, else its own
 * resolved [PageGeometry] when usable, else a conservative placeholder aspect. Shared by
 * [combinedContentDimensions] (the flat/horizontal-fold combined-box model) and Phase 3D's per-pane fold-spread
 * geometry ([foldPaneMaxPan]/[FoldSpreadGeometry]) so both ultimately agree on "this slot's own size" from one
 * place, never two independent copies of the same fallback chain. */
private fun slotDims(slot: PageSlot): Pair<Float, Float> = slot.bitmap?.let { it.width.toFloat() to it.height.toFloat() }
    ?: slot.geometry?.takeIf { !it.isUnknown() }?.let { it.width.toFloat() to it.height.toFloat() }
    ?: (DEFAULT_PLACEHOLDER_ASPECT_WIDTH to DEFAULT_PLACEHOLDER_ASPECT_HEIGHT)

/** Phase 3D: the two physical slots' own panes + own dimensions for a vertical-fold spread's shared transform
 * clamp (see [foldSpreadSharedMaxPan]) -- captured as one immutable snapshot so the long-lived zoom/pan gesture
 * coroutine can read it via `rememberUpdatedState` without ever closing over `state.slots`/[FoldRect] mutable
 * state directly (the same discipline Codex R1 finding 4 established for [combinedContentDimensions]). */
private data class FoldSpreadGeometry(val leftPane: FoldRect, val leftDims: Pair<Float, Float>,
    val rightPane: FoldRect, val rightDims: Pair<Float, Float>)

/** One pane's own max-pan bound (x, y) for [dims]-sized content fitted inside [pane] at [scale] -- the per-pane
 * building block [foldSpreadSharedMaxPan] takes the stricter of two of. Deliberately does NOT force Y to `0` for
 * [fitWidth] the way [fixedReaderMaxPanY] does for the flat case: a vertical-fold spread pane has no
 * `verticalScroll` container of its own (see the fold-spread rendering block's doc in [FixedReaderScreen] for
 * why), so vertical overflow beyond the pane's own height is reached through this same pan value, exactly like
 * Fit Page's reachability model -- using [fixedReaderMaxPanY]'s always-`0` Fit Width behavior here would make
 * that overflow permanently unreachable (clipped by the pane's own `clipToBounds()`) rather than merely
 * differently-reached. */
private fun foldPaneMaxPan(dims: Pair<Float, Float>, pane: FoldRect, scale: Float, fitWidth: Boolean): Pair<Float, Float> {
    val (width, height) = dims
    if (width <= 0f || height <= 0f || pane.width <= 0f || pane.height <= 0f) return 0f to 0f
    val content = fixedReaderFittedContentSize(width, height, pane.width, pane.height, fitWidth)
    return fixedReaderMaxPan(content.width, pane.width, scale) to fixedReaderMaxPan(content.height, pane.height, scale)
}

/** The ONE shared pan value's max bound across BOTH panes of a vertical-fold spread: the minimum (most
 * restrictive) of each pane's own [foldPaneMaxPan] bound on each axis, so a single shared `panX`/`panY` can never
 * push EITHER pane's content past that pane's own edge -- satisfying "never artwork under hinge" by construction,
 * with each pane's `clipToBounds()` in [FixedReaderScreen] as an unconditional second line of defense regardless
 * of this clamp's own correctness. */
private fun foldSpreadSharedMaxPan(leftDims: Pair<Float, Float>, leftPane: FoldRect, rightDims: Pair<Float, Float>,
    rightPane: FoldRect, scale: Float, fitWidth: Boolean): Pair<Float, Float> {
    val (leftMaxX, leftMaxY) = foldPaneMaxPan(leftDims, leftPane, scale, fitWidth)
    val (rightMaxX, rightMaxY) = foldPaneMaxPan(rightDims, rightPane, scale, fitWidth)
    return minOf(leftMaxX, rightMaxX) to minOf(leftMaxY, rightMaxY)
}

/** `true` when a [PageGeometry] carries no usable dimensions (the `PageGeometry(0, 0)` sentinel
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]'s `geometryCache` stores for a
 * page whose geometry lookup itself failed, or a genuinely absent value). Codex R2 finding 1 (3C remediation):
 * delegates to [PageGeometry.isUsable], the single centralized definition of "usable," rather than repeating its
 * own `width <= 0 || height <= 0` check -- this is the PRESENTATION side, which intentionally keeps treating
 * unusable/unknown geometry as a conservative-default PLACEHOLDER aspect here, never the navigation-only
 * "could still be landscape" reading [FixedReaderViewModel.isLandscapeAtForNavigation] applies. */
private fun PageGeometry?.isUnknown(): Boolean = this?.isUsable != true

/** Codex R1 finding 3: a failed slot's own placeholder aspect ratio, from its OWN [PageGeometry] when known --
 * never a sibling's. Only when this specific slot's geometry is also unknown does it fall back to the
 * conservative default placeholder aspect (never derived from any other slot). */
private fun placeholderAspect(geometry: PageGeometry?): Float =
    if (geometry.isUnknown()) DEFAULT_PLACEHOLDER_ASPECT_WIDTH / DEFAULT_PLACEHOLDER_ASPECT_HEIGHT
    else geometry!!.width.toFloat() / geometry.height

/** Conservative portrait-ish default used only when NEITHER a bitmap NOR a resolved [PageGeometry] exists for a
 * slot -- never derived from any sibling slot's own geometry (Codex R1 finding 3). */
private const val DEFAULT_PLACEHOLDER_ASPECT_WIDTH = 2f
private const val DEFAULT_PLACEHOLDER_ASPECT_HEIGHT = 3f
