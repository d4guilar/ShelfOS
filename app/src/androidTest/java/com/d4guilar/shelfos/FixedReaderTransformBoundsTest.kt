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

    /** Fit Width's live `verticalScroll` offset/max, as reported by the production `reader_scroll_probe` seam. */
    private data class ScrollExtent(val value: Int, val max: Int)
    private fun scrollExtent(): ScrollExtent {
        val node = compose.onNodeWithTag("reader_scroll_probe").fetchSemanticsNode()
        val raw = node.config.getOrNull(SemanticsProperties.StateDescription) ?: "0,0"
        val (value, max) = raw.split(",")
        return ScrollExtent(value.toInt(), max.toInt())
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

    /** Symmetric case at the BOTTOM of the scroll range: scroll all the way down, then drag further upward. */
    @Test fun fitWidthTallPageBottomDragBeyondEdgeStaysWithinBounds() {
        awaitLibrary()
        rotateToLandscape()
        read("test-pdf-tall")
        awaitPage("1 / 2")
        switchToFitWidth()
        awaitTag("reader_scroll_probe")
        val max = scrollExtent().max
        assertTrue("fixture must actually produce a scrollable (tall) Fit Width page", max > 0)
        zoomIn()
        assertEquals(2f, transform().scale, 0f)
        // Scroll all the way to the bottom first, then keep dragging upward (finger moves up) past the edge.
        swipeVertical(up = true, repeats = 10)
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
}
