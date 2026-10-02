// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

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
        listOf(OriginalFixtures.pdf(context), OriginalFixtures.cbz(context)).forEach { container.library.add(it) }
    }
    @After fun removeFixtures() = runBlocking<Unit> {
        listOf("test-pdf", "test-cbz").forEach { container.library.remove(it) }
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
}
