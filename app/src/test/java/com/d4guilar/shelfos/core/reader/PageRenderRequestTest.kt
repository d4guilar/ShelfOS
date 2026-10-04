// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3A: pure JVM coverage for [resolveRenderTargetLongestEdge], the render-request policy that replaces the
 * old flat `MAX_PAGE_PIXELS = 2048` ceiling with a viewport-aware, still-bounded target. No Android classes are
 * involved (the function is plain `Int` math), so this runs as an ordinary local unit test -- the actual bitmap
 * decode/rasterization behavior that consumes this value is covered separately by instrumented tests, since
 * `BitmapFactory`/`PdfRenderer` are not available in a local JVM test for this project.
 */
class PageRenderRequestTest {

    @Test fun unknownViewportFallsBackToTheOldFlatDefault() {
        // The pre-layout case: no real viewport known yet. Must reproduce Phase 1/2's exact flat ceiling.
        assertEquals(PageRenderRequest.DEFAULT_MAX_DIMENSION, resolveRenderTargetLongestEdge(PageRenderRequest.DEFAULT))
        assertEquals(PageRenderRequest.DEFAULT_MAX_DIMENSION, resolveRenderTargetLongestEdge(PageRenderRequest(0, 0)))
    }

    @Test fun smallViewportRequestsLessThanTheOldDefault() {
        // A small phone viewport should not decode larger than it needs to.
        val target = resolveRenderTargetLongestEdge(PageRenderRequest(720, 1280))
        assertEquals(1280, target)
        assertTrue(target < PageRenderRequest.DEFAULT_MAX_DIMENSION)
    }

    @Test fun largerViewportRequestExceedsTheOldTwoThousandFortyEightCap() {
        // A tablet-class viewport must be able to exceed the old flat 2048 cap -- the whole point of this slice.
        val target = resolveRenderTargetLongestEdge(PageRenderRequest(2560, 1600))
        assertEquals(2560, target)
        assertTrue(target > 2048)
    }

    @Test fun thumbnailLikeRequestIsBoundedBySmallMaxDimension() {
        // The contract must already be capable of a deliberately tiny request (future thumbnails), even though
        // no thumbnail UI exists yet: a small maxDimension wins over a larger viewport.
        val target = resolveRenderTargetLongestEdge(PageRenderRequest(viewportWidth = 1080, viewportHeight = 1920, maxDimension = 200))
        assertEquals(200, target)
    }

    @Test fun oversizedRequestIsBoundedByTheAbsoluteSafeMaximum() {
        // No request, however large, may exceed the hard memory-safety ceiling.
        val target = resolveRenderTargetLongestEdge(PageRenderRequest(50_000, 50_000))
        assertEquals(PageRenderRequest.SAFE_MAX_DIMENSION, target)
    }

    @Test fun requestedMaxDimensionCanNeverRaiseTheResultAboveTheSafeMaximum() {
        // A viewport at/above the safe ceiling, paired with an even larger requested maxDimension: the ceiling
        // -- not the inflated maxDimension -- must win.
        val target = resolveRenderTargetLongestEdge(PageRenderRequest(viewportWidth = 10_000, viewportHeight = 10_000, maxDimension = 100_000))
        assertEquals(PageRenderRequest.SAFE_MAX_DIMENSION, target)
    }

    @Test fun malformedNegativeOrZeroInputsAreTreatedAsUnspecifiedRatherThanPropagated() {
        assertEquals(PageRenderRequest.DEFAULT_MAX_DIMENSION, resolveRenderTargetLongestEdge(PageRenderRequest(-1, -1)))
        assertEquals(PageRenderRequest.DEFAULT_MAX_DIMENSION, resolveRenderTargetLongestEdge(PageRenderRequest(0, 0, maxDimension = -5)))
        // A huge but structurally valid Int viewport (e.g. a malformed layout pass) must clamp, never throw/overflow.
        assertEquals(PageRenderRequest.SAFE_MAX_DIMENSION, resolveRenderTargetLongestEdge(PageRenderRequest(Int.MAX_VALUE, Int.MAX_VALUE)))
    }

    @Test fun resultIsAlwaysAtLeastOnePixel() {
        assertTrue(resolveRenderTargetLongestEdge(PageRenderRequest(1, 1, maxDimension = 0)) >= 1)
    }

    @Test fun resolutionIsDeterministicForTheSameRequest() {
        val request = PageRenderRequest(1234, 987, maxDimension = 3000)
        val first = resolveRenderTargetLongestEdge(request)
        val second = resolveRenderTargetLongestEdge(request)
        assertEquals(first, second)
    }
}
