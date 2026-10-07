// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM coverage for [resolveRenderTarget], the render-request resolution policy (Codex R1 remediation of
 * Phase 3A). Replaces the original Phase 3A `resolveRenderTargetLongestEdge(request)`, which took no source
 * dimensions and so could only ever reason about the caller's raw viewport longest edge -- independent review
 * (Codex R1) found two defects that required knowing the page's own aspect ratio to fix: (finding 1) a small/
 * narrow viewport could resolve to a target *below* Phase 2's flat `2048` fidelity for ordinary reading, and
 * (finding 2) [FitMode.WIDTH] could not be told apart from a viewport-longest-edge-only target, so a tall page in
 * a landscape viewport resolved to a tiny, severely-upscaled-on-screen bitmap. No Android classes are involved
 * (the function is plain `Int`/`Double` math), so this runs as an ordinary local unit test -- the actual bitmap
 * decode/rasterization behavior that consumes this value is covered separately by instrumented tests
 * (`FixedReaderRenderRequestTest`), since `BitmapFactory`/`PdfRenderer` are not available in a local JVM test for
 * this project.
 */
class PageRenderRequestTest {

    @Test fun unknownViewportFallsBackToTheOldFlatDefault() {
        // The pre-layout case: no real viewport known yet. Must reproduce Phase 1/2/3A's exact flat ceiling,
        // independent of fit mode, on a source large enough that the floor/ceiling isn't the binding constraint.
        val target = resolveRenderTarget(6000, 4000, PageRenderRequest.DEFAULT)
        assertEquals(2048, maxOf(target.width, target.height))
        assertEquals(2048, resolveRenderTarget(6000, 4000, PageRenderRequest(0, 0)).let { maxOf(it.width, it.height) })
    }

    // ---- Codex R1 finding 1: reading-quality floor ----

    @Test fun smallNarrowViewportNeverRegressesBelowTheReadingFloor() {
        // The exact regression Codex found: a 720x1280 portrait phone viewport against a 6000x4000 (3:2)
        // landscape source. A naive viewport-longest-edge approach (1280) or an aspect-correct-but-floor-less
        // Fit Page computation (longest edge ~720) would both fall below Phase 2's flat 2048 fidelity for normal
        // reading. The floor must win.
        val target = resolveRenderTarget(6000, 4000, PageRenderRequest(720, 1280, fit = FitMode.PAGE))
        assertTrue("expected floor-preserving >=2048, got ${target.width}x${target.height}",
            maxOf(target.width, target.height) >= PageRenderRequest.DEFAULT_MAX_DIMENSION)
        assertEquals(2048, target.width)
    }

    @Test fun largerViewportWhoseFitAspectMatchesTheSourceExceedsTheOldCap() {
        // A tablet-class viewport, aspect-matched to the source, must still be able to exceed the old flat 2048
        // cap -- the floor added for finding 1 must not re-impose a ceiling that defeats 3A's original point.
        val target = resolveRenderTarget(6000, 3750, PageRenderRequest(2560, 1600, fit = FitMode.PAGE))
        assertEquals(2560, target.width)
        assertTrue(target.width > 2048)
    }

    @Test fun explicitSmallMaxDimensionStillOptsOutOfTheReadingFloor() {
        // A future thumbnail-like request (deliberately small maxDimension) must still be able to go below the
        // reading floor: the per-request ceiling is applied after the floor and always wins when smaller.
        val target = resolveRenderTarget(6000, 4000, PageRenderRequest(viewportWidth = 1080, viewportHeight = 1920, maxDimension = 200))
        assertEquals(200, maxOf(target.width, target.height))
    }

    // ---- Codex R1 finding 2: Fit Width is aspect-aware ----

    @Test fun fitWidthDoesNotResolveFromRawViewportLongestEdgeAlone() {
        // A tall (1:6) page in a landscape viewport. The old longest-edge-only policy would size this off the
        // viewport's own longest edge (1920) against the source's longest edge (7200) -- a ~320px-wide result,
        // the defect Codex found. The fix must preserve meaningfully more horizontal fidelity by actually scaling
        // width to the viewport width (not the viewport's longest edge) before any safety ceiling applies.
        val target = resolveRenderTarget(1200, 7200, PageRenderRequest(1920, 1080, fit = FitMode.WIDTH))
        val naiveLongestEdgeOnlyWidth = (1200.0 * (1920.0 / 7200.0)).toInt() // the old defect's approximate result
        assertTrue("expected meaningfully wider than the old longest-edge-only defect ($naiveLongestEdgeOnlyWidth), got ${target.width}",
            target.width > naiveLongestEdgeOnlyWidth * 2)
        // Still safe: bounded by both the absolute per-axis ceiling and the explicit byte budget.
        assertTrue(target.height <= PageRenderRequest.SAFE_MAX_DIMENSION)
        assertTrue(target.estimatedBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    @Test fun fitPageAndFitWidthDisagreeOnTheSameTallSourceAndViewport() {
        // Fit Page (fits both axes inside the viewport) and Fit Width (matches viewport width, follows aspect
        // past viewport height) must genuinely diverge for the same tall source/viewport pair -- proving fit mode
        // actually participates in resolution, not just viewport numbers.
        val source = 1200 to 7200
        val viewport = 1920 to 1080
        val page = resolveRenderTarget(source.first, source.second, PageRenderRequest(viewport.first, viewport.second, fit = FitMode.PAGE))
        val width = resolveRenderTarget(source.first, source.second, PageRenderRequest(viewport.first, viewport.second, fit = FitMode.WIDTH))
        assertNotEquals(page.width, width.width)
        assertTrue(width.width > page.width) // Fit Width keeps far more horizontal resolution for a tall page.
    }

    // ---- Codex R1 finding 3: explicit byte/pixel peak-memory budget, aspect-aware ----

    @Test fun squareOversizedRequestIsBoundedByTheByteBudgetNotJustTheLongestEdge() {
        // A square source/viewport pair whose naive (longest-edge-only) target would be a full
        // SAFE_MAX_DIMENSION square (~64MiB ARGB_8888) -- the exact "no concrete justification" case Codex
        // flagged. The explicit byte budget must bind here, not just the per-axis ceiling.
        val target = resolveRenderTarget(10_000, 10_000, PageRenderRequest(20_000, 20_000, fit = FitMode.PAGE))
        assertEquals(target.width, target.height) // still square; budget reduction is aspect-preserving.
        assertTrue("expected the byte budget to bind below the full safe-max square, got ${target.width}",
            target.width < PageRenderRequest.SAFE_MAX_DIMENSION)
        assertTrue(target.estimatedBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    @Test fun oversizedRequestIsBoundedByTheAbsoluteSafeMaximum() {
        val target = resolveRenderTarget(6000, 4000, PageRenderRequest(50_000, 50_000))
        assertTrue(maxOf(target.width, target.height) <= PageRenderRequest.SAFE_MAX_DIMENSION)
        assertTrue(target.estimatedBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    @Test fun requestedMaxDimensionCanNeverRaiseTheResultAboveTheSafeMaximum() {
        // A viewport at/above the safe ceiling, paired with an even larger requested maxDimension: the ceiling
        // -- not the inflated maxDimension -- must win.
        val target = resolveRenderTarget(6000, 4000, PageRenderRequest(viewportWidth = 10_000, viewportHeight = 10_000, maxDimension = 100_000))
        assertTrue(maxOf(target.width, target.height) <= PageRenderRequest.SAFE_MAX_DIMENSION)
    }

    // ---- General resolution/safety properties, carried over from the original Phase 3A coverage ----

    @Test fun malformedNegativeOrZeroInputsAreTreatedAsUnspecifiedRatherThanPropagated() {
        assertEquals(2048, resolveRenderTarget(6000, 4000, PageRenderRequest(-1, -1)).let { maxOf(it.width, it.height) })
        assertEquals(2048, resolveRenderTarget(6000, 4000, PageRenderRequest(0, 0, maxDimension = -5)).let { maxOf(it.width, it.height) })
        // A huge but structurally valid Int viewport (e.g. a malformed layout pass) must clamp, never throw/overflow.
        val target = resolveRenderTarget(6000, 4000, PageRenderRequest(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(maxOf(target.width, target.height) <= PageRenderRequest.SAFE_MAX_DIMENSION)
    }

    @Test fun malformedOrZeroSourceDimensionsAreAlsoTreatedAsUnspecified() {
        val target = resolveRenderTarget(0, -5, PageRenderRequest(1920, 1080))
        assertTrue(target.width >= 1)
        assertTrue(target.height >= 1)
    }

    @Test fun resultIsAlwaysAtLeastOnePixelOnEachAxis() {
        val target = resolveRenderTarget(1, 1, PageRenderRequest(1, 1, maxDimension = 0))
        assertTrue(target.width >= 1)
        assertTrue(target.height >= 1)
    }

    @Test fun resolutionIsDeterministicForTheSameRequest() {
        val request = PageRenderRequest(1234, 987, maxDimension = 3000)
        val first = resolveRenderTarget(6000, 4000, request)
        val second = resolveRenderTarget(6000, 4000, request)
        assertEquals(first, second)
    }

    @Test fun sourceSmallerThanTheRequestedTargetIsNotForcedAboveItsOwnNativeAspectByThisFunctionAlone() {
        // resolveRenderTarget itself may compute a scale-up factor (no-upscale is enforced downstream by
        // ImagePageRenderer's sample-size floor at 1x, not here); this just proves the aspect stays correct and
        // the result stays bounded even when the implied scale is an upscale.
        val target = resolveRenderTarget(100, 150, PageRenderRequest(3000, 3000, fit = FitMode.PAGE))
        assertTrue(target.width >= 1 && target.height >= 1)
        assertTrue(target.estimatedBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    // ---- Phase 3B: PageRenderRequest.thumbnail() ----

    @Test fun thumbnailRequestStaysWellBelowTheReadingFloorRegardlessOfSourceSize() {
        // The dedicated thumbnail factory must resolve well under DEFAULT_MAX_DIMENSION (the reading-quality
        // floor), on both a huge source and a small one -- it opts out of the floor by tightening maxDimension,
        // exactly the mechanism Codex R1's remediation built resolveRenderTarget around (finding 1's doc).
        val large = resolveRenderTarget(6000, 4000, PageRenderRequest.thumbnail())
        assertEquals(PageRenderRequest.THUMBNAIL_MAX_DIMENSION, maxOf(large.width, large.height))
        assertTrue(maxOf(large.width, large.height) < PageRenderRequest.DEFAULT_MAX_DIMENSION)
    }

    @Test fun thumbnailRequestPreservesAspectRatioAndStaysBudgetSafeForASmallSource() {
        // resolveRenderTarget's own math may compute an upscale factor for a source well under the thumbnail
        // ceiling (no-upscale is enforced downstream by ImagePageRenderer's sample-size floor at 1x during a real
        // decode, not here -- see FixedReaderRenderRequestTest's real-decode proof of that for the thumbnail
        // factory specifically). This proves the pure function's own guarantees instead: aspect preservation and
        // byte-budget safety, consistent with the equivalent non-thumbnail small-source coverage above.
        val target = resolveRenderTarget(100, 150, PageRenderRequest.thumbnail())
        assertTrue(target.width > 0 && target.height > 0)
        assertEquals(100.0 / 150.0, target.width.toDouble() / target.height, 0.02)
        assertTrue(target.estimatedBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    @Test fun thumbnailRequestByteCostStaysATinyFractionOfTheFullReadingBudget() {
        // Worst case for the thumbnail ceiling is a perfectly square page -- proves the documented ~410KB
        // worst-case reasoning behind ThumbnailLoader.DEFAULT_BUDGET_BYTES.
        val target = resolveRenderTarget(10_000, 10_000, PageRenderRequest.thumbnail())
        assertEquals(PageRenderRequest.THUMBNAIL_MAX_DIMENSION, target.width)
        assertEquals(PageRenderRequest.THUMBNAIL_MAX_DIMENSION, target.height)
        assertTrue(target.estimatedBytes < RenderMemoryPolicy.MAX_BITMAP_BYTES / 10)
    }

    // ---- Codex R1 finding 2 (3C spread remediation): deterministic spread-transition memory policy ----

    @Test fun thumbnailReservationIsCarvedOutOfTheSessionBudgetBeforeDividingAmongReadingBitmaps() {
        assertEquals(16L * 1024 * 1024, RenderMemoryPolicy.THUMBNAIL_RESERVATION_BYTES)
        assertEquals(RenderMemoryPolicy.SESSION_BUDGET_BYTES - RenderMemoryPolicy.THUMBNAIL_RESERVATION_BYTES,
            RenderMemoryPolicy.READING_BUDGET_BYTES)
    }

    @Test fun singlePageBudgetIsDerivedFromTheReadingBudgetNotTheFullSessionBudget() {
        // Preserves the 3A single-page guarantee's SHAPE (budget / 3 concurrent bitmaps), but against the
        // post-thumbnail-reservation reading budget, not the raw session budget -- the thumbnail cache always
        // coexisted in the same process; this constant now actually reflects that.
        assertEquals(RenderMemoryPolicy.READING_BUDGET_BYTES / 3, RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    @Test fun spreadSlotBudgetIsStricterThanSinglePageBudget() {
        assertEquals(RenderMemoryPolicy.READING_BUDGET_BYTES / 4, RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES)
        assertTrue(RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES < RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    @Test fun spreadToSpreadWorstCaseOfFourLiveBitmapsExactlyFillsTheReadingBudgetAndNeverExceedsIt() {
        // Two old spread slots (already rendered under the spread-slot budget) + two newly-decoding spread
        // slots: the real worst-case ownership graph Codex R1 finding 2 identified. This must land AT or BELOW
        // READING_BUDGET_BYTES, never above it.
        val worstCase = 4 * RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES
        assertTrue("4 spread slots of ${RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES}B each = ${worstCase}B " +
            "exceeds the reading budget ${RenderMemoryPolicy.READING_BUDGET_BYTES}B", worstCase <= RenderMemoryPolicy.READING_BUDGET_BYTES)
    }

    @Test fun singleToSpreadTransitionWorstCaseStaysWithinTheReadingBudget() {
        // One old single-page slot + two new spread slots.
        val worstCase = RenderMemoryPolicy.MAX_BITMAP_BYTES + 2 * RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES
        assertTrue("single->spread worst case ${worstCase}B exceeds ${RenderMemoryPolicy.READING_BUDGET_BYTES}B",
            worstCase <= RenderMemoryPolicy.READING_BUDGET_BYTES)
    }

    @Test fun spreadToSingleTransitionWorstCaseStaysWithinTheReadingBudget() {
        // Two old spread slots + one new single-page slot.
        val worstCase = 2 * RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES + RenderMemoryPolicy.MAX_BITMAP_BYTES
        assertTrue("spread->single worst case ${worstCase}B exceeds ${RenderMemoryPolicy.READING_BUDGET_BYTES}B",
            worstCase <= RenderMemoryPolicy.READING_BUDGET_BYTES)
    }

    @Test fun singleToSingleTransitionWorstCaseOfThreeBitmapsExactlyFillsTheReadingBudget() {
        // Preserves the exact 3A invariant (3 concurrent single-page bitmaps), now against the reading budget --
        // integer division means MAX_BITMAP_BYTES * 3 can be up to 2 bytes under READING_BUDGET_BYTES (never
        // over), so this asserts "at the limit, never exceeding it" rather than bit-for-bit equality.
        val worstCase = RenderMemoryPolicy.MAX_CONCURRENT_BITMAPS * RenderMemoryPolicy.MAX_BITMAP_BYTES
        assertTrue(worstCase <= RenderMemoryPolicy.READING_BUDGET_BYTES)
        assertTrue(RenderMemoryPolicy.READING_BUDGET_BYTES - worstCase < RenderMemoryPolicy.MAX_CONCURRENT_BITMAPS)
    }

    @Test fun aNearCeilingSpreadSlotRequestIsBoundedBySpreadBudgetNotTheLooserSingleBudget() {
        // A large, square-ish source at a generous viewport, requested AS a spread slot, must resolve under the
        // stricter spread ceiling even though the same source/viewport combination requested as an ordinary
        // single-page request would be allowed to use the looser single-page ceiling.
        val source = 6000 to 6000
        val viewport = PageRenderRequest(3000, 3000, fit = FitMode.PAGE, spreadSlot = true)
        val spreadTarget = resolveRenderTarget(source.first, source.second, viewport)
        assertTrue("spread slot estimated ${spreadTarget.estimatedBytes}B exceeds ${RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES}B",
            spreadTarget.estimatedBytes <= RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES)
        val singleTarget = resolveRenderTarget(source.first, source.second, viewport.copy(spreadSlot = false))
        assertTrue("single-page target should be allowed at least as large as the spread-slot target here",
            singleTarget.estimatedBytes >= spreadTarget.estimatedBytes)
        assertTrue(singleTarget.estimatedBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    @Test fun everyOrdinaryRequestDefaultsToTheNonSpreadBudget() {
        // spreadSlot defaults to false -- every pre-existing call site (PDF/CBZ full-page reads, thumbnails,
        // DEFAULT) is unaffected by the new stricter ceiling unless it explicitly opts in.
        assertFalse(PageRenderRequest.DEFAULT.spreadSlot)
        assertFalse(PageRenderRequest.thumbnail().spreadSlot)
        assertFalse(PageRenderRequest().spreadSlot)
    }

    @Test fun resolveRenderTargetStillPreservesThe3ASinglePageByteBudgetGuaranteeForOrdinaryRequests() {
        // Sanity re-proof (not a new behavior) that an ordinary (non-spread) oversized square request is still
        // bounded by MAX_BITMAP_BYTES exactly as before -- the spread remediation must not have loosened or
        // broken the original single-page guarantee.
        val target = resolveRenderTarget(8000, 8000, PageRenderRequest(4000, 4000, fit = FitMode.PAGE))
        assertTrue(target.estimatedBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }
}
