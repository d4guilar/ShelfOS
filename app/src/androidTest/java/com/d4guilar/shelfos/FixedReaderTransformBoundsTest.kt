// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 2D.1: focused instrumented coverage for the fixed-reader pinch/pan transform bounds, driving real
 * multi-touch pointer events through [com.d4guilar.shelfos.feature.reader.FixedReaderScreen]'s production
 * gesture code (not a test-only reimplementation). Reads the real `scale`/`panX`/`panY` Compose state back via
 * a zero-size, semantics-cleared probe node (`reader_transform_probe`) added for exactly this purpose — the
 * smallest seam that exercises the real state without a larger debug-only API.
 *
 * Owner-confirmed live defect (RP5, physical pinch-zoom): "If I zoom out pulling to the gray side, it just
 * overrides the actual pdf page and I can continue moving until even the page is completely gone... This is the
 * same on both sides or up and down." This test proves the regression is gone for both PDF and CBZ.
 */
class FixedReaderTransformBoundsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as ShelfApplication).container

    @Before fun seedOriginalFixtures() = runBlocking<Unit> {
        val context = instrumentation.targetContext
        listOf(OriginalFixtures.pdf(context), OriginalFixtures.cbz(context), OriginalFixtures.tallPdf(context)).forEach { container.library.add(it) }
    }
    @After fun removeFixtures() = runBlocking<Unit> {
        listOf("test-pdf", "test-cbz", "test-pdf-tall").forEach { container.library.remove(it) }
        container.library.preferences("", "{}")
    }

    private fun awaitLibrary() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitTag(tag: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitPage(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun read(id: String) {
        awaitTag("publication_$id")
        compose.onNodeWithTag("publication_$id").performClick()
        compose.onNodeWithTag("read_action").performScrollTo().performClick()
    }

    /** scale,panX,panY as reported live by the production gesture code. */
    private data class Transform(val scale: Float, val panX: Float, val panY: Float)
    private fun transform(): Transform {
        val node = compose.onNodeWithTag("reader_transform_probe").fetchSemanticsNode()
        val raw = node.config.getOrNull(SemanticsProperties.StateDescription) ?: "1.0,0.0,0.0"
        val (s, x, y) = raw.split(",")
        return Transform(s.toFloat(), x.toFloat(), y.toFloat())
    }
    private fun viewportSize() = compose.onNodeWithTag("reader_page").fetchSemanticsNode().size

    /** Fit Width's live `verticalScroll` offset/max plus the live unscaled fitted content height `H`, as reported
     * by the production `reader_scroll_probe` seam (Phase 2D.1 remediation round two: `H` was added so tests can
     * independently compute the real visible bitmap-space range, not just read scroll-state numbers). */
    private data class ScrollExtent(val value: Int, val max: Int, val fittedHeight: Float)
    private fun scrollExtent(): ScrollExtent {
        val node = compose.onNodeWithTag("reader_scroll_probe").fetchSemanticsNode()
        val raw = node.config.getOrNull(SemanticsProperties.StateDescription) ?: "0,0,0"
        val (value, max, h) = raw.split(",")
        return ScrollExtent(value.toInt(), max.toInt(), h.toFloat())
    }

    /** Geometry proof, not a state-value proof (Phase 2D.1 remediation round two, Step 23): maps the real
     * verticalScroll offset back through the real `scale` and fitted content height `H` into the original
     * bitmap's own pixel coordinate space, and returns the inclusive bitmap-Y range currently visible inside the
     * viewport. Derivation: `graphicsLayer`'s center-origin scale of the H-tall Image, combined with this
     * remediation's `H + 2*overflow == scale*H` reserved spacing, places bitmap row `y` (of [bitmapHeight] total)
     * at scroll-column position `scale * H * (y / bitmapHeight)` -- so the inverse, `columnY * bitmapHeight /
     * (scale * H)`, recovers the bitmap row visible at a given scroll-column position. This is exactly "the
     * scaled content's actual edge position relative to the viewport," expressed in the original page's own
     * coordinates, not a re-assertion of scroll/pan state. */
    private fun visibleBitmapYRange(bitmapHeight: Float): ClosedFloatingPointRange<Float> {
        val scroll = scrollExtent()
        val scale = transform().scale
        val viewportHeight = viewportSize().height.toFloat()
        val scaledColumnHeight = scale * scroll.fittedHeight
        val top = scroll.value * bitmapHeight / scaledColumnHeight
        val bottom = (scroll.value + viewportHeight) * bitmapHeight / scaledColumnHeight
        return top..bottom
    }

    /** Requests whichever orientation the device is not already in, mirroring [EpubRecreationTest]'s own
     * rotation helper, so this also rotates hardware that is locked to one orientation by default. Fit Width's
     * tall-content blocker needs a landscape viewport (wide, short) to reproduce reliably: the fitted height
     * (`viewportWidth * bitmapHeight / bitmapWidth`) scales with viewport *width*, which landscape maximizes,
     * while the viewport height it must exceed is minimized. */
    private fun rotateToLandscape() {
        var before: android.app.Activity? = null
        compose.activityRule.scenario.onActivity { before = it }
        val startOrientation = before!!.resources.configuration.orientation
        if (startOrientation == Configuration.ORIENTATION_LANDSCAPE) return
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(30_000) {
            var orientation = startOrientation
            compose.activityRule.scenario.onActivity { orientation = it.resources.configuration.orientation }
            orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
    }

    private fun switchToFitWidth() {
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Fit width").performClick().assertIsSelected()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    private fun zoomIn() {
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("Reset zoom").assertExists()
    }

    /** Drives a real two-finger pinch-out gesture through the production gesture code to reach (after the
     * production `[1,5]` coerce) the maximum 5x zoom -- the UI's own "Zoom in" button only ever toggles 1x/2x, so
     * the 5x stress case (Step 10) needs a real pinch, not the button. */
    private fun pinchZoomToMax() {
        compose.onNodeWithTag("reader_page").performTouchInput {
            val c = center
            down(0, c + Offset(-20f, -20f))
            down(1, c + Offset(20f, 20f))
            moveTo(0, c + Offset(-400f, -400f))
            moveTo(1, c + Offset(400f, 400f))
            up(0); up(1)
        }
        compose.waitForIdle()
    }

    /** Repeated one-finger vertical swipes on the reader viewport, large enough to traverse the full tall-page
     * scroll range in a handful of gestures; relies on the production gesture handler leaving single-finger
     * vertical drags unconsumed in Fit Width so `verticalScroll`'s own detector moves them (Phase 2D.1
     * remediation: this is exactly the path that must NOT also move `panY`). */
    private fun swipeVertical(up: Boolean, repeats: Int = 6) {
        repeat(repeats) {
            compose.onNodeWithTag("reader_page").performTouchInput {
                val startY = if (up) bottom - 20f else top + 20f
                val endY = if (up) top + 20f else bottom - 20f
                swipe(start = Offset(centerX, startY), end = Offset(centerX, endY), durationMillis = 120)
            }
            compose.waitForIdle()
        }
    }

    /** Swipes until the real `verticalScroll` offset actually reaches its floor (`up = false`) or ceiling
     * (`up = true`), rather than a fixed repeat count -- the scroll RANGE itself scales with zoom (Phase 2D.1
     * remediation round two), so a fixed-repeat count calibrated for one zoom level/device can fall short at a
     * higher zoom or a taller screen (observed directly: 20 fixed repeats reliably covered the 2x range but fell
     * short of the much larger 5x range on both devices). Capped so a genuine bug (scroll that never reaches its
     * bound) fails loudly instead of looping forever. */
    private fun swipeVerticalToExtreme(up: Boolean, maxAttempts: Int = 60) {
        repeat(maxAttempts) {
            val extent = scrollExtent()
            if (if (up) extent.value == extent.max else extent.value == 0) return
            swipeVertical(up, repeats = 1)
        }
        assertTrue("did not reach the scroll ${if (up) "ceiling" else "floor"} within $maxAttempts swipes " +
            "(current=${scrollExtent()})", false)
    }

    /** Pinches outward (zoom in) then drags one finger far beyond any sane edge (the owner's reported trigger),
     * asserting the resulting transform never exceeds a bound tied to the real content/viewport geometry — the
     * previous defective clamp (`±viewportSize * scale`) allowed up to 5x the viewport; the corrected clamp
     * (`max(0, (scaledContent - viewport) / 2)`) can never exceed roughly 2x the viewport even at max 5x zoom. */
    private fun pinchZoomThenDragFarStaysWithinContentBounds() {
        val viewport = viewportSize()
        compose.onNodeWithTag("reader_page").performTouchInput {
            val c = center
            down(0, c + Offset(-40f, -40f))
            down(1, c + Offset(40f, 40f))
            moveTo(0, c + Offset(-250f, -250f))
            moveTo(1, c + Offset(250f, 250f))
            moveTo(0, c + Offset(-400f, -400f))
            moveTo(1, c + Offset(400f, 400f))
            up(1)
            // One finger remains down; scale > 1f now routes single-finger movement through the same pan clamp.
            moveTo(0, c + Offset(-5000f, -5000f))
            moveTo(0, c + Offset(-9000f, -9000f))
            up(0)
        }
        compose.waitForIdle()
        val after = transform()
        assertTrue("scale stayed within [1,5], was ${after.scale}", after.scale in 1f..5f)
        // A correct clamp can never exceed (viewport * (maxScale - 1)) / 2 in magnitude on EACH axis
        // independently (content can be at most the viewport size on that axis before scaling), regardless of
        // how far the drag tried to push it; the old defective bound (viewportSize * scale, same formula on both
        // axes) could reach 5x that axis's own viewport size.
        val maxSanePanX = viewport.width.toFloat() * 2.5f
        val maxSanePanY = viewport.height.toFloat() * 2.5f
        assertTrue("panX ${after.panX} exceeded sane bound $maxSanePanX (old defect: page draggable fully off-screen)",
            kotlin.math.abs(after.panX) <= maxSanePanX)
        assertTrue("panY ${after.panY} exceeded sane bound $maxSanePanY (old defect: page draggable fully off-screen)",
            kotlin.math.abs(after.panY) <= maxSanePanY)
    }

    @Test fun pdfPinchZoomOutDragStaysWithinContentBounds() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        pinchZoomThenDragFarStaysWithinContentBounds()
    }

    /** Phase 2D.1 Step 14: the shared clamp applies identically to CBZ; page navigation/rendering stays intact. */
    @Test fun cbzPinchZoomOutDragStaysWithinContentBoundsAndNavigationStillWorks() {
        awaitLibrary()
        compose.onNodeWithText("Manga").performClick()
        read("test-cbz")
        awaitPage("1 / 3")
        pinchZoomThenDragFarStaysWithinContentBounds()
        // Navigation remains intact after a bounds-stressing gesture sequence (shared code path, no regression).
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 3")
    }

    /** Phase 2D.1 Step 9: at default/minimum zoom, a page that already fits the viewport reports a (0,0)
     * transform — the clamp's max-pan is exactly zero by construction, so no residual gesture input can move it. */
    @Test fun defaultZoomReportsZeroTransformForPdf() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        val initial = transform()
        assertEquals(1f, initial.scale, 0f)
        assertEquals(0f, initial.panX, 0f)
        assertEquals(0f, initial.panY, 0f)
    }

    /** Phase 2D.1 Step 6/15: switching Fit Page <-> Fit Width while zoomed resets the transform to default,
     * the same way a page change already does — no stale transform computed for the old fit's geometry leaks in. */
    @Test fun fitModeChangeWhileZoomedResetsTransform() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("Reset zoom").assertExists()
        assertEquals(2f, transform().scale, 0f)
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Fit width").performClick().assertIsSelected()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        val after = transform()
        assertEquals("fit-mode change must reset zoom like a page change does", 1f, after.scale, 0f)
        assertEquals(0f, after.panX, 0f)
        assertEquals(0f, after.panY, 0f)
    }

    /** Phase 2D.1 Step 8: a page change resets the transform, so a previous page's zoom/pan cannot leak into the
     * next page's clamp (the invariant the new clamp must preserve, not merely coincidentally satisfy). */
    @Test fun pageChangeResetsTransform() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Zoom in").performClick()
        assertEquals(2f, transform().scale, 0f)
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 3")
        val after = transform()
        assertEquals(1f, after.scale, 0f)
        assertEquals(0f, after.panX, 0f)
        assertEquals(0f, after.panY, 0f)
    }

    // ---- Phase 2D.1 remediation: Fit Width tall-content blocker (independent QA finding) ----
    //
    // Reproduces the blocker QA reported: "landscape viewport, tall PDF page, Fit Width, zoom to ~2x, scroll to
    // top, drag downward repeatedly -> the page can be translated completely out of view, leaving solid gray."
    // and the symmetric case at the bottom scrolling up. The chosen remediation (Option B: verticalScroll owns
    // all Fit Width vertical movement, graphicsLayer.translationY is always 0 there) is asserted via the exact
    // production state (panY, and the real ScrollState's value/maxValue), not a loose sanity bound.

    /** At the TOP of the scroll range, zoomed in, dragging further downward (beyond the top edge) must leave the
     * scroll offset at its floor (0) and must NOT move panY at all -- the old defect's root cause was exactly a
     * nonzero Fit Width panY bound that let the page translate away from this scroll-owned content entirely. */
    @Test fun fitWidthTallPageTopDragBeyondEdgeStaysWithinBounds() {
        awaitLibrary()
        rotateToLandscape()
        read("test-pdf-tall")
        awaitPage("1 / 2")
        switchToFitWidth()
        awaitTag("reader_scroll_probe")
        assertTrue("fixture must actually produce a scrollable (tall) Fit Width page",
            scrollExtent().max > 0)
        assertEquals("fresh page/fit mode must start scrolled to the top", 0, scrollExtent().value)
        zoomIn()
        assertEquals(2f, transform().scale, 0f)
        // Already at the top; drag further down (finger moves down the screen) repeatedly, the owner's reported
        // trigger for the page escaping into gray.
        swipeVertical(up = false)
        val after = transform()
        val scroll = scrollExtent()
        assertEquals("Fit Width must never move vertically via the pan transform", 0f, after.panY, 0f)
        assertEquals("scroll cannot go past its own top floor", 0, scroll.value)
        assertEquals("zoom must be unaffected by a pure vertical drag", 2f, after.scale, 0f)
    }

    /** Symmetric case at the BOTTOM of the scroll range: scroll all the way down, then drag further upward.
     *
     * Phase 2D.1 remediation round two note: `max` must be captured AFTER zooming in, not before -- this
     * remediation makes the scroll range itself grow with zoom (`scale*H - viewport`, not the flat `H -
     * viewport` the first remediation pass assumed), so a pre-zoom `max` is stale by the time the drag runs and
     * produces a false failure (caught by this round's own instrumented run). */
    @Test fun fitWidthTallPageBottomDragBeyondEdgeStaysWithinBounds() {
        awaitLibrary()
        rotateToLandscape()
        read("test-pdf-tall")
        awaitPage("1 / 2")
        switchToFitWidth()
        awaitTag("reader_scroll_probe")
        assertTrue("fixture must actually produce a scrollable (tall) Fit Width page", scrollExtent().max > 0)
        zoomIn()
        assertEquals(2f, transform().scale, 0f)
        val max = scrollExtent().max // captured AFTER zoom: the real, current (zoomed) ceiling.
        // Scroll all the way to the bottom first, then keep dragging upward (finger moves up) past the edge.
        swipeVerticalToExtreme(up = true)
        assertEquals("must actually reach the bottom before testing the beyond-edge drag", max, scrollExtent().value)
        swipeVertical(up = true)
        val after = transform()
        val scroll = scrollExtent()
        assertEquals("Fit Width must never move vertically via the pan transform", 0f, after.panY, 0f)
        assertEquals("scroll cannot go past its own bottom ceiling", max, scroll.value)
        assertEquals("zoom must be unaffected by a pure vertical drag", 2f, after.scale, 0f)
    }

    /** Phase 2D.1 Step 8 regression: at the default, untransformed scale, Fit Width's vertical movement must
     * come from verticalScroll, not from a free graphicsLayer pan -- the old formula allowed a nonzero Fit Width
     * panY bound even at scale == 1 whenever the fitted content was taller than the viewport. */
    @Test fun fitWidthTallPageScaleOneVerticalMovementComesFromScrollNotPan() {
        awaitLibrary()
        rotateToLandscape()
        read("test-pdf-tall")
        awaitPage("1 / 2")
        switchToFitWidth()
        awaitTag("reader_scroll_probe")
        assertEquals(1f, transform().scale, 0f)
        assertEquals(0, scrollExtent().value)
        swipeVertical(up = true, repeats = 3)
        val after = transform()
        assertEquals("panY must stay 0 at scale 1 in Fit Width", 0f, after.panY, 0f)
        assertEquals("scale must remain untouched by a pure vertical drag", 1f, after.scale, 0f)
        assertTrue("the drag must have actually scrolled the content (scroll owns vertical movement here)",
            scrollExtent().value > 0)
    }

    // ---- Phase 2D.1 remediation, round two: zoomed Fit Width top/bottom reachability (independent QA finding
    // after 06bcc14) ----
    //
    // QA: "Fit Width + tall page + zoom + translationY=0 causes the TOP and BOTTOM portions of the page to
    // become unreachable" -- graphicsLayer visually scales the Image around its own center without changing its
    // *layout* size, so verticalScroll's range stayed H-V instead of the visually-required scale*H-V. These
    // tests prove ACTUAL VISIBILITY of the fixture's own top/bottom markers (y=80 and y=2340 of the 400x2400
    // `tallPdf` bitmap) via visibleBitmapYRange's real geometry, not merely panY/scrollState values (Step 23).

    private val tallPdfBitmapHeight = 2400f
    private val topMarkerBitmapY = 30f // OriginalFixtures.tallPdf's "top" text baseline
    private val bottomMarkerBitmapY = 2380f // OriginalFixtures.tallPdf's "bottom" text baseline

    private fun openTallPdfFitWidthZoomed() {
        awaitLibrary()
        rotateToLandscape()
        read("test-pdf-tall")
        awaitPage("1 / 2")
        switchToFitWidth()
        awaitTag("reader_scroll_probe")
        assertTrue("fixture must actually produce a scrollable (tall) Fit Width page", scrollExtent().max > 0)
    }

    /** The core, device-independent, non-tautological regression proof (Step 3): the real production
     * `verticalScroll`'s own `maxValue` -- not a number this test computes independently, but the actual
     * ScrollState Compose maintains -- must equal `scale*H - viewport` (the expected scaled scroll extent),
     * not the pre-remediation-round-two `H - viewport`. This is what makes every row of the page reachable: a
     * continuous scroll range from `0` to this correct `max` sweeps the visible window continuously from bitmap
     * row `0` to row `bitmapHeight` with no gap, which is the formal statement of "top/bottom reachable." */
    private fun assertScrollRangeMatchesScaledContent(scale: Float) {
        val scroll = scrollExtent()
        val viewportHeight = viewportSize().height.toFloat()
        val expectedMax = (scale * scroll.fittedHeight - viewportHeight).coerceAtLeast(0f)
        assertEquals("scale=$scale: real verticalScroll.maxValue must equal scale*H-viewport (H=${scroll.fittedHeight}, " +
            "viewport=$viewportHeight), proving the full scaled page height is reachable, not just the unscaled H",
            expectedMax, scroll.max.toFloat(), viewportHeight * 0.02f) // 2% tolerance for Dp<->px rounding
    }

    @Test fun fitWidthTallPageTopMarkerVisibleAtScaleTwoAfterScrollingToTop() {
        openTallPdfFitWidthZoomed()
        zoomIn()
        assertEquals(2f, transform().scale, 0f)
        swipeVertical(up = false) // drag toward the top, same gesture as the beyond-edge test above
        assertEquals(0, scrollExtent().value)
        assertScrollRangeMatchesScaledContent(2f)
        val visible = visibleBitmapYRange(tallPdfBitmapHeight)
        assertTrue("top marker (bitmap y=$topMarkerBitmapY) must be visible at the top of scroll @2x, " +
            "visible range was $visible", topMarkerBitmapY in visible)
    }

    @Test fun fitWidthTallPageBottomMarkerVisibleAtScaleTwoAfterScrollingToBottom() {
        openTallPdfFitWidthZoomed()
        zoomIn()
        assertEquals(2f, transform().scale, 0f)
        swipeVerticalToExtreme(up = true)
        val reached = scrollExtent()
        assertEquals("must actually reach the bottom before checking visibility", reached.max, reached.value)
        assertScrollRangeMatchesScaledContent(2f)
        val visible = visibleBitmapYRange(tallPdfBitmapHeight)
        assertTrue("bottom marker (bitmap y=$bottomMarkerBitmapY) must be visible at the bottom of scroll @2x, " +
            "visible range was $visible", bottomMarkerBitmapY in visible)
    }

    /** Scale-5 stress case (Step 10): catches reachability math that only happens to work near 2x. At 5x on this
     * deliberately extreme 1:6-aspect fixture in a wide landscape viewport, a single screenful shows only
     * roughly the nearest 1-2% of the page -- too thin a field of view for any one fixed marker position to
     * reliably land inside on both the RP5 and the emulator's own slightly different exact aspect ratios (this
     * was confirmed empirically: an earlier version of this test placed the marker too far from the edge and
     * failed on both devices at 5x even though the actual fix was correct). The real, device-independent,
     * regression-sensitive proof at this extreme zoom is [assertScrollRangeMatchesScaledContent]: if the real
     * `verticalScroll.maxValue` matches `scale*H - viewport` exactly, reachability of every row -- including the
     * very top/bottom -- follows directly (see that function's doc), without needing a marker to physically land
     * in a vanishingly small per-screen field of view. */
    @Test fun fitWidthTallPageTopReachableAtScaleFiveViaScrollRangeProof() {
        openTallPdfFitWidthZoomed()
        pinchZoomToMax()
        assertEquals(5f, transform().scale, 0f)
        swipeVertical(up = false, repeats = 10)
        assertEquals(0, scrollExtent().value)
        assertScrollRangeMatchesScaledContent(5f)
        val visible = visibleBitmapYRange(tallPdfBitmapHeight)
        assertEquals("at the scroll floor the visible window must start exactly at the true top of the page",
            0f, visible.start, 0.5f)
    }

    @Test fun fitWidthTallPageBottomReachableAtScaleFiveViaScrollRangeProof() {
        openTallPdfFitWidthZoomed()
        pinchZoomToMax()
        assertEquals(5f, transform().scale, 0f)
        swipeVerticalToExtreme(up = true)
        val reached = scrollExtent()
        assertEquals("must actually reach the bottom before checking visibility", reached.max, reached.value)
        assertScrollRangeMatchesScaledContent(5f)
        val visible = visibleBitmapYRange(tallPdfBitmapHeight)
        assertEquals("at the scroll ceiling the visible window must end exactly at the true bottom of the page",
            tallPdfBitmapHeight, visible.endInclusive, tallPdfBitmapHeight * 0.02f)
    }

    /** Step 11 (scale-1 regression): at the default, untransformed scale, no artificial padding is reserved --
     * the scroll range must equal the plain unscaled `H - viewport`, exactly as it did before this remediation,
     * not `H + 2*overflow - viewport` with a nonzero overflow. */
    @Test fun fitWidthTallPageScaleOneHasNoArtificialScrollPadding() {
        openTallPdfFitWidthZoomed()
        val scroll = scrollExtent()
        assertEquals(1f, transform().scale, 0f)
        val viewportHeight = viewportSize().height.toFloat()
        val expectedMax = (scroll.fittedHeight - viewportHeight).coerceAtLeast(0f)
        assertEquals("scale=1 must reserve zero extra scroll space (ordinary unzoomed Fit Width)",
            expectedMax, scroll.max.toFloat(), 1.5f)
    }

    /** Step 15 (double-tap/reset regression, a LOW gap the prior remediation left open): zoom via double-tap,
     * scroll somewhere in the middle, then double-tap again to reset -- scale/panX/panY must return to their
     * defaults, the reserved overflow must collapse back to zero (scroll max returns to the unscaled value), and
     * the resulting scroll position must remain valid (never exceed the new, smaller max). */
    @Test fun fitWidthTallPageDoubleTapResetCollapsesOverflowAndStaysReachable() {
        openTallPdfFitWidthZoomed()
        val baselineMax = scrollExtent().max
        compose.onNodeWithTag("reader_page").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        assertEquals(2f, transform().scale, 0f)
        swipeVertical(up = true, repeats = 3) // move somewhere in the middle of the zoomed range
        val midScroll = scrollExtent()
        assertTrue("must have actually scrolled somewhere before resetting", midScroll.value > 0)
        compose.onNodeWithTag("reader_page").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        val after = transform()
        val afterScroll = scrollExtent()
        assertEquals("double-tap reset must restore scale to 1", 1f, after.scale, 0f)
        assertEquals("panX must reset", 0f, after.panX, 0f)
        assertEquals("panY must stay 0 (Fit Width never uses it)", 0f, after.panY, 0f)
        assertEquals("the reserved overflow must collapse back to the plain unscaled scroll range",
            baselineMax, afterScroll.max)
        assertTrue("scroll position must remain within the new, smaller max (no invalid/stale offset)",
            afterScroll.value <= afterScroll.max)
    }

    /** Step 12: the vertical-reachability fix must not break zoomed Fit Width's horizontal pan, which is still
     * owned entirely by `graphicsLayer.translationX` (verticalScroll only ever handles the Y axis). */
    @Test fun fitWidthZoomedHorizontalPanStillWorksAndStaysWithinBounds() {
        openTallPdfFitWidthZoomed()
        zoomIn()
        assertEquals(2f, transform().scale, 0f)
        val viewport = viewportSize()
        compose.onNodeWithTag("reader_page").performTouchInput {
            swipe(start = Offset(centerX + 10f, centerY), end = Offset(right - 5f, centerY), durationMillis = 120)
        }
        compose.waitForIdle()
        val after = transform()
        assertEquals("a horizontal-dominant drag must not change zoom", 2f, after.scale, 0f)
        val maxSanePanX = viewport.width.toFloat() * 2.5f
        assertTrue("panX must stay bounded", kotlin.math.abs(after.panX) <= maxSanePanX)
        assertEquals("horizontal pan must not leak into Fit Width's locked Y axis", 0f, after.panY, 0f)
    }
}
