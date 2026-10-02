// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2D.1: pure JVM coverage for the fixed-reader pan-clamp geometry, reproducing the owner's live-confirmed
 * defect ("zoom out pulling to the gray side... the page is completely gone") as a deterministic, Compose-free
 * math model. Uses the real production helpers directly, no duplicated test-only math.
 */
class FixedReaderTransformTest {

    // ---- fixedReaderFittedContentSize: Fit Page (letterboxed, ContentScale.Fit-equivalent) ----

    @Test fun fitPageLetterboxesOnWidthWhenBitmapIsTallerThanViewport() {
        // 400x600 bitmap (2:3) inside an 800x800 viewport: height-limited, fit = 800/600, width letterboxes.
        val content = fixedReaderFittedContentSize(400f, 600f, 800f, 800f, fitWidth = false)
        assertEquals(800f / 600f * 400f, content.width, 0.01f)
        assertEquals(800f, content.height, 0.01f)
    }

    @Test fun fitPageLetterboxesOnHeightWhenBitmapIsWiderThanViewport() {
        // 600x400 bitmap (3:2) inside an 800x800 viewport: width-limited, fit = 800/600, height letterboxes.
        val content = fixedReaderFittedContentSize(600f, 400f, 800f, 800f, fitWidth = false)
        assertEquals(800f, content.width, 0.01f)
        assertEquals(800f / 600f * 400f, content.height, 0.01f)
    }

    @Test fun fitPageExactlyFillsBothAxesWhenAspectRatiosMatch() {
        val content = fixedReaderFittedContentSize(400f, 600f, 400f, 600f, fitWidth = false)
        assertEquals(400f, content.width, 0.01f)
        assertEquals(600f, content.height, 0.01f)
    }

    // ---- fixedReaderFittedContentSize: Fit Width ----

    @Test fun fitWidthAlwaysEqualsViewportWidthAndFollowsAspectForHeight() {
        val content = fixedReaderFittedContentSize(400f, 600f, 350f, 500f, fitWidth = true)
        assertEquals(350f, content.width, 0.01f)
        assertEquals(350f * (600f / 400f), content.height, 0.01f)
    }

    @Test fun fitWidthHeightCanExceedViewportHeight() {
        // A tall page in a short viewport: fit-width height exceeds the viewport (handled by verticalScroll,
        // independent of this pan model) rather than being clamped here.
        val content = fixedReaderFittedContentSize(300f, 1200f, 300f, 400f, fitWidth = true)
        assertEquals(300f, content.width, 0.01f)
        assertTrue(content.height > 400f)
    }

    // ---- fixedReaderFittedContentSize: defensive edge cases ----

    @Test fun fittedContentSizeIsZeroForNonPositiveOrNonFiniteInputs() {
        assertEquals(FixedReaderContentSize(0f, 0f), fixedReaderFittedContentSize(0f, 600f, 800f, 800f, false))
        assertEquals(FixedReaderContentSize(0f, 0f), fixedReaderFittedContentSize(400f, -600f, 800f, 800f, false))
        assertEquals(FixedReaderContentSize(0f, 0f), fixedReaderFittedContentSize(400f, 600f, Float.NaN, 800f, false))
        assertEquals(FixedReaderContentSize(0f, 0f), fixedReaderFittedContentSize(400f, 600f, 800f, Float.POSITIVE_INFINITY, false))
        assertEquals(FixedReaderContentSize(0f, 0f), fixedReaderFittedContentSize(400f, 600f, 0f, 0f, true))
    }

    // ---- fixedReaderMaxPan ----

    @Test fun maxPanIsZeroWhenContentSmallerThanViewportBothAxes() {
        assertEquals(0f, fixedReaderMaxPan(contentSize = 300f, viewportSize = 800f, scale = 1f), 0f)
    }

    @Test fun maxPanIsZeroAtDefaultScaleWhenContentExactlyFillsViewport() {
        // Case 1 of the acceptance list: content <= viewport on this axis at scale == 1 -> max is exactly 0.
        assertEquals(0f, fixedReaderMaxPan(contentSize = 800f, viewportSize = 800f, scale = 1f), 0f)
    }

    @Test fun maxPanIsPositiveWhenScaledContentExceedsViewport() {
        // content 400, scale 3 -> scaled 1200, viewport 800 -> maxPan = (1200-800)/2 = 200
        assertEquals(200f, fixedReaderMaxPan(contentSize = 400f, viewportSize = 800f, scale = 3f), 0.01f)
    }

    @Test fun maxPanGrowsMonotonicallyAsScaleIncreases() {
        val low = fixedReaderMaxPan(400f, 800f, 2f)
        val high = fixedReaderMaxPan(400f, 800f, 4f)
        assertTrue(high > low)
    }

    @Test fun maxPanShrinksAsScaleDecreases() {
        val high = fixedReaderMaxPan(400f, 800f, 4f)
        val low = fixedReaderMaxPan(400f, 800f, 2f)
        assertTrue(low < high)
    }

    @Test fun maxPanTreatsNonPositiveOrNonFiniteScaleAsOne() {
        val viaZero = fixedReaderMaxPan(400f, 800f, 0f)
        val viaNegative = fixedReaderMaxPan(400f, 800f, -5f)
        val viaNaN = fixedReaderMaxPan(400f, 800f, Float.NaN)
        val viaOne = fixedReaderMaxPan(400f, 800f, 1f)
        assertEquals(viaOne, viaZero, 0f)
        assertEquals(viaOne, viaNegative, 0f)
        assertEquals(viaOne, viaNaN, 0f)
    }

    @Test fun maxPanIsZeroForNonPositiveOrNonFiniteDimensions() {
        assertEquals(0f, fixedReaderMaxPan(0f, 800f, 3f), 0f)
        assertEquals(0f, fixedReaderMaxPan(-400f, 800f, 3f), 0f)
        assertEquals(0f, fixedReaderMaxPan(400f, 0f, 3f), 0f)
        assertEquals(0f, fixedReaderMaxPan(Float.NaN, 800f, 3f), 0f)
        assertEquals(0f, fixedReaderMaxPan(400f, Float.POSITIVE_INFINITY, 3f), 0f)
    }

    @Test fun maxPanNeverProducesInfiniteOrNaNForExtremeFiniteInputs() {
        val result = fixedReaderMaxPan(Float.MAX_VALUE / 2, 1f, 3f)
        assertTrue(result.isFinite())
        assertTrue(result >= 0f)
    }

    // ---- fixedReaderClampPan ----

    @Test fun clampPanLeavesInRangeValueUnchanged() {
        assertEquals(50f, fixedReaderClampPan(50f, 100f), 0f)
    }

    @Test fun clampPanClampsAboveAndBelowBound() {
        assertEquals(100f, fixedReaderClampPan(500f, 100f), 0f)
        assertEquals(-100f, fixedReaderClampPan(-500f, 100f), 0f)
    }

    @Test fun clampPanReClampsStaleTranslationToASmallerRange() {
        // Simulates zoom-out: a translation valid at the old (larger) bound must re-clamp to the new one.
        val stale = 180f // valid when max was 200
        assertEquals(50f, fixedReaderClampPan(stale, 50f), 0f)
    }

    @Test fun clampPanForcesZeroWhenMaxIsZero() {
        // Case 1/9 of the acceptance list: content that fits the viewport (max == 0) can never be dragged.
        assertEquals(0f, fixedReaderClampPan(123f, 0f), 0f)
        assertEquals(0f, fixedReaderClampPan(-123f, 0f), 0f)
    }

    @Test fun clampPanTreatsNegativeOrNonFiniteMaxAsZero() {
        assertEquals(0f, fixedReaderClampPan(50f, -10f), 0f)
        assertEquals(0f, fixedReaderClampPan(50f, Float.NaN), 0f)
    }

    @Test fun clampPanOfNonFiniteValueIsZero() {
        assertEquals(0f, fixedReaderClampPan(Float.NaN, 100f), 0f)
        assertEquals(0f, fixedReaderClampPan(Float.POSITIVE_INFINITY, 100f), 0f)
    }

    // ---- End-to-end acceptance cases from docs/PHASE_2D_IMPLEMENTATION_PLAN.md §13 ----

    @Test fun case1PageSmallerThanViewportBothAxesClampsToZeroZero() {
        val content = fixedReaderFittedContentSize(200f, 300f, 800f, 800f, fitWidth = false)
        val maxX = fixedReaderMaxPan(content.width, 800f, 1f)
        val maxY = fixedReaderMaxPan(content.height, 800f, 1f)
        assertEquals(0f, fixedReaderClampPan(999f, maxX), 0f)
        assertEquals(0f, fixedReaderClampPan(999f, maxY), 0f)
    }

    @Test fun case2PageWiderThanViewportScaledHorizontalBoundedVerticalZero() {
        // Fit width content: width always == viewport at scale 1; zoom 3x only moves width out of bounds since
        // the content height (aspect-derived) already equals/exceeds the viewport in this construction, so pick
        // a Fit Page case instead: a landscape bitmap in a portrait viewport, zoomed so width now exceeds.
        val content = fixedReaderFittedContentSize(800f, 400f, 400f, 1000f, fitWidth = false) // width-limited fit
        assertEquals(400f, content.width, 0.01f)
        val maxX = fixedReaderMaxPan(content.width, 400f, 3f)
        val maxY = fixedReaderMaxPan(content.height, 1000f, 3f)
        assertTrue(maxX > 0f)
        assertEquals(0f, maxY, 0f) // content height * 3 still <= viewport height in this construction
    }

    @Test fun case3PageTallerThanViewportScaledVerticalBoundedHorizontalZero() {
        val content = fixedReaderFittedContentSize(400f, 800f, 1000f, 400f, fitWidth = false) // height-limited fit
        assertEquals(400f, content.height, 0.01f)
        val maxX = fixedReaderMaxPan(content.width, 1000f, 1.1f)
        val maxY = fixedReaderMaxPan(content.height, 400f, 3f)
        assertEquals(0f, maxX, 0f)
        assertTrue(maxY > 0f)
    }

    @Test fun case4PageLargerThanViewportBothAxesClampIndependently() {
        val maxX = fixedReaderMaxPan(500f, 300f, 2f)
        val maxY = fixedReaderMaxPan(600f, 200f, 2f)
        assertTrue(maxX > 0f)
        assertTrue(maxY > 0f)
        assertTrue(maxX != maxY)
    }

    @Test fun case5ZoomInExpandsRangeAndKeepsPreviouslyValidTranslationValid() {
        val maxAtLowZoom = fixedReaderMaxPan(400f, 800f, 2f)
        val translation = fixedReaderClampPan(maxAtLowZoom, maxAtLowZoom)
        val maxAtHighZoom = fixedReaderMaxPan(400f, 800f, 4f)
        assertEquals(translation, fixedReaderClampPan(translation, maxAtHighZoom), 0f) // still in range, unaffected
    }

    @Test fun case6ZoomOutImmediatelyReClampsStaleTranslation() {
        val maxAtHighZoom = fixedReaderMaxPan(400f, 800f, 4f)
        val staleTranslation = maxAtHighZoom
        val maxAtLowZoom = fixedReaderMaxPan(400f, 800f, 1.5f)
        val reclamped = fixedReaderClampPan(staleTranslation, maxAtLowZoom)
        assertEquals(maxAtLowZoom, reclamped, 0.001f)
        assertTrue(reclamped < staleTranslation)
    }

    @Test fun case7MinimumZoomRecentersWhenContentNoLongerExceedsViewport() {
        val maxAtMinZoom = fixedReaderMaxPan(400f, 800f, 1f) // content fits viewport at scale 1 -> 0
        assertEquals(0f, maxAtMinZoom, 0f)
        assertEquals(0f, fixedReaderClampPan(250f, maxAtMinZoom), 0f)
    }

    @Test fun case9ViewportResizeRecalculatesAgainstNewViewportDimensions() {
        val oldMax = fixedReaderMaxPan(1000f, 800f, 1f) // (1000-800)/2 = 100
        val newMax = fixedReaderMaxPan(1000f, 1200f, 1f) // content now fits -> 0
        assertEquals(100f, oldMax, 0.01f)
        assertEquals(0f, newMax, 0f)
        val stale = oldMax
        assertEquals(0f, fixedReaderClampPan(stale, newMax), 0f)
    }

    @Test fun case10PdfAndCbzShareIdenticalClampBehaviorForEquivalentInputs() {
        // The helper has no format concept at all; identical geometric inputs must yield identical outputs
        // regardless of which format (PDF bitmap vs. CBZ-decoded bitmap) supplied the dimensions.
        val pdfLikeContent = fixedReaderFittedContentSize(400f, 600f, 350f, 700f, fitWidth = false)
        val cbzLikeContent = fixedReaderFittedContentSize(400f, 600f, 350f, 700f, fitWidth = false)
        assertEquals(pdfLikeContent, cbzLikeContent)
        val pdfMax = fixedReaderMaxPan(pdfLikeContent.width, 350f, 2f)
        val cbzMax = fixedReaderMaxPan(cbzLikeContent.width, 350f, 2f)
        assertEquals(pdfMax, cbzMax, 0f)
    }
}
