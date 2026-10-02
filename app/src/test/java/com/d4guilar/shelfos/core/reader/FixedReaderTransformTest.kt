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
        // via fixedReaderMaxPanY forcing the pan transform's own Y bound to 0, not by anything in
        // fixedReaderFittedContentSize) rather than being clamped here.
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

    @Test fun case4PageLargerThanViewportBothAxesClampToExactIndependentValues() {
        // width: content 500 * scale 2 = 1000, viewport 300 -> (1000-300)/2 = 350
        // height: content 600 * scale 2 = 1200, viewport 200 -> (1200-200)/2 = 500
        val maxX = fixedReaderMaxPan(500f, 300f, 2f)
        val maxY = fixedReaderMaxPan(600f, 200f, 2f)
        assertEquals(350f, maxX, 0.01f)
        assertEquals(500f, maxY, 0.01f)
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

    @Test fun case10FunctionsTakeNoFormatInputAndAreGovernedOnlyByDimensions() {
        // The real defect this guards against: a format-specific branch accidentally sneaking into the shared
        // geometry (e.g. "if PDF do X, if CBZ do Y"). None of these functions accept a format parameter at all —
        // PDF's rasterized bitmap and CBZ's decoded bitmap are indistinguishable to this file by construction,
        // not merely by coincidence of equal test inputs. Proving that requires two *different* dimension sets
        // that a format-aware implementation might plausibly special-case, confirming each still obeys the same
        // single geometric formula rather than one of them silently taking a different code path.
        val wideLandscapeBitmap = fixedReaderFittedContentSize(1200f, 400f, 350f, 700f, fitWidth = false) // PDF-shaped
        val tallPortraitBitmap = fixedReaderFittedContentSize(400f, 1200f, 350f, 700f, fitWidth = false) // CBZ-shaped
        // Width-limited fit: width pinned to the viewport, height follows the aspect ratio.
        assertEquals(350f, wideLandscapeBitmap.width, 0.01f)
        assertEquals(350f * (400f / 1200f), wideLandscapeBitmap.height, 0.01f)
        // Height-limited fit: height pinned to the viewport, width follows the aspect ratio.
        assertEquals(700f * (400f / 1200f), tallPortraitBitmap.width, 0.01f)
        assertEquals(700f, tallPortraitBitmap.height, 0.01f)
        // Both still obey the exact same fixedReaderMaxPan formula afterward, with no branch on which produced them.
        assertEquals((wideLandscapeBitmap.width * 3f - 350f) / 2f, fixedReaderMaxPan(wideLandscapeBitmap.width, 350f, 3f), 0.01f)
        assertEquals((tallPortraitBitmap.height * 3f - 700f) / 2f, fixedReaderMaxPan(tallPortraitBitmap.height, 700f, 3f), 0.01f)
    }

    // ---- fixedReaderMaxPanY: Fit Width's tall-content remediation (QA-flagged blocker) ----

    @Test fun maxPanYIsZeroForFitWidthAtScaleOneEvenWhenContentIsMuchTallerThanViewport() {
        // The exact shape of the reported blocker: a tall page (H >> V) in landscape Fit Width. At the default,
        // untransformed scale, vertical movement must come entirely from verticalScroll, never from the pan
        // transform -- unlike Fit Page, where the old (pre-remediation) formula would have returned (H-V)/2 > 0
        // here, which was the root cause of the page being draggable into gray even at low zoom.
        val tallContent = fixedReaderFittedContentSize(400f, 2400f, 1200f, 500f, fitWidth = true)
        assertTrue("fixture must actually reproduce H > V", tallContent.height > 500f)
        assertEquals(0f, fixedReaderMaxPanY(tallContent, 500f, scale = 1f, fitWidth = true), 0f)
    }

    @Test fun maxPanYIsZeroForFitWidthTallContentAtScaleTwoAndFive() {
        // QA explicitly asked for scale 1, 2 and 5: Fit Width's Y bound must stay 0 at every zoom level, since
        // the chosen remediation model (Option B) gives verticalScroll sole ownership of vertical movement for
        // this axis regardless of how far zoomed in the user is.
        val tallContent = fixedReaderFittedContentSize(400f, 2400f, 1200f, 500f, fitWidth = true)
        assertEquals(0f, fixedReaderMaxPanY(tallContent, 500f, scale = 2f, fitWidth = true), 0f)
        assertEquals(0f, fixedReaderMaxPanY(tallContent, 500f, scale = 5f, fitWidth = true), 0f)
    }

    @Test fun maxPanYIsZeroForFitWidthWhenContentAlreadyFitsViewportHeight() {
        // The H <= V case (short/wide page, or a portrait viewport): still always 0 for Fit Width, same as Fit
        // Page's own untransformed case -- Fit Width simply never uses the pan transform's Y axis at all.
        val shortContent = fixedReaderFittedContentSize(400f, 300f, 400f, 900f, fitWidth = true)
        assertTrue(shortContent.height <= 900f)
        assertEquals(0f, fixedReaderMaxPanY(shortContent, 900f, scale = 1f, fitWidth = true), 0f)
        assertEquals(0f, fixedReaderMaxPanY(shortContent, 900f, scale = 3f, fitWidth = true), 0f)
    }

    @Test fun maxPanYZoomDownSequenceStaysZeroThroughoutForFitWidth() {
        // A full zoom-in-then-out sequence (5 -> 2 -> 1) must never produce a nonzero Fit Width Y bound at any
        // point, confirming there is no intermediate scale where the old per-viewport formula could resurface.
        val tallContent = fixedReaderFittedContentSize(400f, 2400f, 1200f, 500f, fitWidth = true)
        listOf(5f, 2f, 1f).forEach { scale ->
            assertEquals("scale=$scale must yield a zero Fit Width Y bound", 0f,
                fixedReaderMaxPanY(tallContent, 500f, scale, fitWidth = true), 0f)
        }
    }

    @Test fun maxPanYDelegatesToStandardFormulaForFitPageRegardlessOfContentShape() {
        // Fit Page must be completely unaffected by the Fit Width remediation: fixedReaderMaxPanY(fitWidth =
        // false) must equal the plain fixedReaderMaxPan(contentSize.height, ...) result exactly.
        val content = fixedReaderFittedContentSize(400f, 800f, 1000f, 400f, fitWidth = false) // height-limited fit
        val expected = fixedReaderMaxPan(content.height, 400f, 3f)
        assertTrue("fixture must actually exercise a positive bound", expected > 0f)
        assertEquals(expected, fixedReaderMaxPanY(content, 400f, scale = 3f, fitWidth = false), 0f)
    }

    @Test fun maxPanYForFitWidthDoesNotAffectHorizontalPanWhichStillGrowsWithZoom() {
        // Confirms the remediation is Y-axis-only: Fit Width's horizontal pan bound (via plain fixedReaderMaxPan
        // on contentSize.width) must still grow normally with zoom, exactly like Fit Page's X axis.
        val tallContent = fixedReaderFittedContentSize(400f, 2400f, 1200f, 500f, fitWidth = true)
        val maxXAtOne = fixedReaderMaxPan(tallContent.width, 1200f, 1f)
        val maxXAtTwo = fixedReaderMaxPan(tallContent.width, 1200f, 2f)
        assertEquals(0f, maxXAtOne, 0f) // content width == viewport width at scale 1 in Fit Width
        assertTrue("horizontal pan must open up once zoomed, even though Y stays locked", maxXAtTwo > 0f)
        assertEquals(0f, fixedReaderMaxPanY(tallContent, 500f, scale = 2f, fitWidth = true), 0f) // Y stays 0 throughout
    }

    // ---- fixedReaderVerticalScaleOverflow: zoomed Fit Width top/bottom reachability remediation ----

    @Test fun verticalScaleOverflowIsZeroAtScaleOne() {
        // No zoom, no graphicsLayer bulge -> no reserved space -> ordinary unzoomed Fit Width is unchanged.
        assertEquals(0f, fixedReaderVerticalScaleOverflow(2400f, scale = 1f), 0f)
    }

    @Test fun verticalScaleOverflowIsHalfContentHeightAtScaleTwo() {
        // (2-1) * 2400 / 2 = 1200
        assertEquals(1200f, fixedReaderVerticalScaleOverflow(2400f, scale = 2f), 0.01f)
    }

    @Test fun verticalScaleOverflowIsDoubleContentHeightAtScaleFive() {
        // (5-1) * 2400 / 2 = 4800
        assertEquals(4800f, fixedReaderVerticalScaleOverflow(2400f, scale = 5f), 0.01f)
    }

    @Test fun verticalScaleOverflowGrowsLinearlyWithScale() {
        val h = 1000f
        val atTwo = fixedReaderVerticalScaleOverflow(h, 2f)
        val atThree = fixedReaderVerticalScaleOverflow(h, 3f)
        val atFour = fixedReaderVerticalScaleOverflow(h, 4f)
        assertEquals(atThree - atTwo, atFour - atThree, 0.001f)
        assertTrue(atFour > atThree && atThree > atTwo)
    }

    @Test fun verticalScaleOverflowReservedTotalMatchesScaledContentHeightExactly() {
        // The whole point of the fix: content + 2*overflow == scale*content, for every scale tested, including
        // the QA-flagged 2x case and the 5x stress case.
        val h = 2400f
        listOf(1f, 2f, 5f).forEach { scale ->
            val overflow = fixedReaderVerticalScaleOverflow(h, scale)
            assertEquals("scale=$scale: H + 2*overflow must equal scale*H", h * scale, h + 2f * overflow, 0.01f)
        }
    }

    @Test fun verticalScaleOverflowTreatsNonPositiveOrNonFiniteScaleAsOne() {
        assertEquals(0f, fixedReaderVerticalScaleOverflow(1000f, 0f), 0f)
        assertEquals(0f, fixedReaderVerticalScaleOverflow(1000f, -3f), 0f)
        assertEquals(0f, fixedReaderVerticalScaleOverflow(1000f, Float.NaN), 0f)
    }

    @Test fun verticalScaleOverflowIsZeroForNonPositiveOrNonFiniteContentHeight() {
        assertEquals(0f, fixedReaderVerticalScaleOverflow(0f, 2f), 0f)
        assertEquals(0f, fixedReaderVerticalScaleOverflow(-500f, 2f), 0f)
        assertEquals(0f, fixedReaderVerticalScaleOverflow(Float.NaN, 2f), 0f)
        assertEquals(0f, fixedReaderVerticalScaleOverflow(Float.POSITIVE_INFINITY, 2f), 0f)
    }

    @Test fun verticalScaleOverflowNeverProducesInfiniteOrNaNForExtremeFiniteInputs() {
        val result = fixedReaderVerticalScaleOverflow(Float.MAX_VALUE / 2, 5f)
        assertTrue(result.isFinite())
        assertTrue(result >= 0f)
    }
}
