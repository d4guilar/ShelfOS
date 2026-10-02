// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 2D.2: focused Activity-recreation continuity coverage for the fixed reader (PDF/CBZ), which had no
 * recreation test analogous to [EpubRecreationTest] before this slice. Reuses the same probe seams
 * ([FixedReaderTransformBoundsTest]'s `reader_transform_probe`/`reader_scroll_probe`) and does not re-derive or
 * duplicate 2D.1's already-settled pan/zoom/Fit-Width math -- it only asserts that page position and fit
 * preference survive a real `ActivityScenario.recreate()`, and that zoom/pan/scroll reset to a *valid* state
 * rather than leaking stale geometry from before the recreation (the risk flagged by this slice's state-ownership
 * audit: Fit Width's `verticalScroll` offset is `rememberSaveable`-backed, but the zoom `scale` it was computed
 * against is plain `remember` and resets to 1x on recreation, which could leave a restored scroll value outside
 * the freshly-collapsed, unzoomed scroll range unless something re-clamps it).
 */
class FixedReaderRecreationTest {
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

    private fun awaitLibrary() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitTag(tag: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitPage(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun read(id: String) {
        awaitTag("publication_$id")
        compose.onNodeWithTag("publication_$id").performClick()
        compose.onNodeWithTag("read_action").performScrollTo().performClick()
    }

    private data class Transform(val scale: Float, val panX: Float, val panY: Float)
    private fun transform(): Transform {
        val node = compose.onNodeWithTag("reader_transform_probe").fetchSemanticsNode()
        val raw = node.config.getOrNull(SemanticsProperties.StateDescription) ?: "1.0,0.0,0.0"
        val (s, x, y) = raw.split(",")
        return Transform(s.toFloat(), x.toFloat(), y.toFloat())
    }
    private data class ScrollExtent(val value: Int, val max: Int, val fittedHeight: Float)
    private fun scrollExtent(): ScrollExtent {
        val node = compose.onNodeWithTag("reader_scroll_probe").fetchSemanticsNode()
        val raw = node.config.getOrNull(SemanticsProperties.StateDescription) ?: "0,0,0"
        val (value, max, h) = raw.split(",")
        return ScrollExtent(value.toInt(), max.toInt(), h.toFloat())
    }

    /** Phase 2D.2 acceptance #2: PDF survives recreation with a truthful page position (not page 1). */
    @Test fun pdfRecreationRestoresNonFirstPage() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 3")
        compose.waitForIdle()
        Thread.sleep(1_000) // PositionWriter has no debounce, but let the conflated channel's async Room write land.
        compose.activityRule.scenario.recreate()
        awaitPage("2 / 3")
    }

    /** Phase 2D.2 acceptance #3: CBZ survives recreation with a truthful page position, same shared code path. */
    @Test fun cbzRecreationRestoresNonFirstPage() {
        awaitLibrary()
        compose.onNodeWithText("Manga").performClick()
        read("test-cbz")
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 3")
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.activityRule.scenario.recreate()
        awaitPage("2 / 3")
    }

    /** Phase 2D.2 Part M: the first-page edge case isn't off-by-one after recreation. */
    @Test fun pdfRecreationRestoresFirstPage() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        Thread.sleep(1_000)
        compose.activityRule.scenario.recreate()
        awaitPage("1 / 3")
    }

    /** Phase 2D.2 Part M: the last-page edge case isn't incorrectly clamped after recreation. */
    @Test fun pdfRecreationRestoresLastPage() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitPage("3 / 3")
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.activityRule.scenario.recreate()
        awaitPage("3 / 3")
    }

    /** Phase 2D.2: Fit Page zoom/pan is transient and must reset to a *valid* (not merely absent) transform
     * across recreation -- (0,0) pan at 1x scale is the only valid resting state for a page that fits the
     * viewport, per 2D.1's own clamp model, so this also doubles as a no-regression check on 2D.1's invariant
     * surviving a real Activity rebuild, not just a live gesture session. */
    @Test fun pdfFitPageZoomPanResetsToValidTransformAfterRecreation() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Zoom in").performClick()
        assertEquals(2f, transform().scale, 0f)
        compose.onNodeWithTag("reader_page").performTouchInput {
            swipe(start = Offset(centerX - 30f, centerY), end = Offset(centerX + 30f, centerY), durationMillis = 120)
        }
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.activityRule.scenario.recreate()
        awaitPage("1 / 3")
        val after = transform()
        assertEquals("zoom/pan is transient and must reset on recreation, not leak a stale scale", 1f, after.scale, 0f)
        assertEquals(0f, after.panX, 0f)
        assertEquals(0f, after.panY, 0f)
    }

    /** Phase 2D.2's key suspected-gap check (state-ownership audit): Fit Width + tall page + zoomed + scrolled to
     * the middle, then a real Activity recreation. `scale` (plain `remember`) resets to 1x, but
     * `rememberScrollState()`'s value is `rememberSaveable`-backed -- this asserts the post-recreation scroll
     * value is never left exceeding the freshly-collapsed (unzoomed) scroll ceiling, i.e. no invalid/"gray
     * escape" scroll position survives a recreation that resets zoom but not scroll. */
    @Test fun fitWidthTallPageRecreationNeverLeavesScrollPastCollapsedCeiling() {
        awaitLibrary()
        read("test-pdf-tall")
        awaitPage("1 / 2")
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Fit width").performClick().assertIsSelected()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty() }
        awaitTag("reader_scroll_probe")
        assertTrue("fixture must actually produce a scrollable (tall) Fit Width page", scrollExtent().max > 0)
        compose.onNodeWithText("Zoom in").performClick()
        assertEquals(2f, transform().scale, 0f)
        // Scroll to roughly the middle of the zoomed range.
        repeat(3) {
            compose.onNodeWithTag("reader_page").performTouchInput {
                swipe(start = Offset(centerX, bottom - 20f), end = Offset(centerX, top + 20f), durationMillis = 120)
            }
            compose.waitForIdle()
        }
        val midScroll = scrollExtent()
        assertTrue("must have actually scrolled into the zoomed range before recreating", midScroll.value > 0)
        Thread.sleep(1_000)
        compose.activityRule.scenario.recreate()
        awaitPage("1 / 2")
        awaitTag("reader_scroll_probe")
        compose.waitForIdle()
        val after = transform()
        val afterScroll = scrollExtent()
        assertEquals("zoom must reset on recreation like any other transient transform", 1f, after.scale, 0f)
        assertTrue("post-recreation scroll value (${afterScroll.value}) must never exceed the current, " +
            "freshly-collapsed (unzoomed) scroll ceiling (${afterScroll.max}) -- a restored-but-uncorrected " +
            "scrollable value past the real content range is the Fit Width 'gray escape' 2D.1 fixed for pan/zoom",
            afterScroll.value <= afterScroll.max)
    }

    /** Phase 2D.2 Part L (focused leave/return regression): reader -> Back to library -> reopen restores the
     * durable page position, reusing the existing shared fixture/helpers rather than a new large matrix. */
    @Test fun pdfLeaveAndReturnRestoresPage() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 3")
        compose.waitForIdle()
        Thread.sleep(1_000)
        // Reader -> details (one popBackStack) -> library (a second one); FixedReaderScreen's own Back wiring
        // only pops one entry at a time, matching real navigation (library -> details -> reader).
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitTag("details_screen")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitLibrary()
        read("test-pdf")
        awaitPage("2 / 3")
    }
}
