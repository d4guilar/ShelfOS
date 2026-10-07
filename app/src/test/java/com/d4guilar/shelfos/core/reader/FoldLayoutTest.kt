// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/** Pure JVM coverage for Phase 3D's fold-aware layout math -- no Android/Compose types involved anywhere in
 * [FoldLayout.kt], so every case below runs without an emulator. Matches the required test matrix from
 * `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3D contract. */
class FoldLayoutTest {
    private val reader = FoldRect(0f, 0f, 1000f, 800f) // reader bounds in WINDOW coordinates
    private val gutter = 16f

    @Test fun noFoldIsIdenticalToFlat3C() {
        val layout = resolveReaderFoldLayout(reader, null, gutter)
        assertEquals(FoldPresentation.FLAT, layout.presentation)
        assertEquals(FoldRect(0f, 0f, 1000f, 800f), layout.flatPane)
    }

    @Test fun nonSeparatingNonOccludingCreaseIsIgnored() {
        val fold = ReaderFoldDescriptor(FoldRect(490f, 0f, 510f, 800f), FoldOrientation.VERTICAL,
            isSeparating = false, occludesFully = false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        assertEquals(FoldPresentation.FLAT, layout.presentation)
    }

    @Test fun featureOutsideReaderBoundsIsIgnored() {
        // Hinge sits entirely to the right of the reader surface (e.g. behind a navigation rail elsewhere).
        val fold = ReaderFoldDescriptor(FoldRect(1200f, 0f, 1220f, 800f), FoldOrientation.VERTICAL,
            isSeparating = true, occludesFully = false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        assertEquals(FoldPresentation.FLAT, layout.presentation)
    }

    @Test fun verticalCenterHingeProducesCorrectLeftHingeRight() {
        // Reader window-space origin offset by (50, 20); hinge centered at local x=500.
        val readerWin = FoldRect(50f, 20f, 1050f, 820f)
        val fold = ReaderFoldDescriptor(FoldRect(540f, 20f, 560f, 820f), FoldOrientation.VERTICAL,
            isSeparating = true, occludesFully = false)
        val layout = resolveReaderFoldLayout(readerWin, fold, gutter)
        assertEquals(FoldPresentation.VERTICAL_SPLIT, layout.presentation)
        val left = requireNotNull(layout.leftPane); val right = requireNotNull(layout.rightPane)
        assertEquals(0f, left.left, 0f); assertEquals(490f, left.right, 0.01f)
        assertEquals(510f, right.left, 0.01f); assertEquals(1000f, right.right, 0.01f)
        assertEquals(20f, layout.hingeGapPx, 0.01f) // actual 20px hinge exceeds the 16px gutter floor
    }

    @Test fun verticalAsymmetricHingeProducesUnequalPanes() {
        val fold = ReaderFoldDescriptor(FoldRect(200f, 0f, 220f, 800f), FoldOrientation.VERTICAL,
            isSeparating = true, occludesFully = false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        val left = requireNotNull(layout.leftPane); val right = requireNotNull(layout.rightPane)
        assertEquals(200f, left.width, 0.01f)
        assertEquals(780f, right.width, 0.01f)
        assertTrue(left.width != right.width)
    }

    @Test fun verticalFullOcclusionLeavesNoPaneIntersectingHinge() {
        val fold = ReaderFoldDescriptor(FoldRect(0f, 0f, 1000f, 800f), FoldOrientation.VERTICAL,
            isSeparating = false, occludesFully = true)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        val left = requireNotNull(layout.leftPane); val right = requireNotNull(layout.rightPane)
        // Every point of both panes stays within [0, left.right] / [right.left, 1000]; neither intersects the
        // other, i.e. no content region overlaps the hinge band.
        assertTrue(left.right <= right.left)
    }

    @Test fun zeroWidthSeparatingFoldKeepsAHardBoundaryAtTheMinimumGutter() {
        val fold = ReaderFoldDescriptor(FoldRect(500f, 0f, 500f, 800f), FoldOrientation.VERTICAL,
            isSeparating = true, occludesFully = false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        assertEquals(gutter, layout.hingeGapPx, 0.01f)
        val left = requireNotNull(layout.leftPane); val right = requireNotNull(layout.rightPane)
        assertEquals(492f, left.right, 0.01f) // 500 - gutter/2
        assertEquals(508f, right.left, 0.01f) // 500 + gutter/2
    }

    @Test fun horizontalFoldChoosesTheLargerSafePaneNoFakeTopBottomSpread() {
        val fold = ReaderFoldDescriptor(FoldRect(0f, 300f, 1000f, 320f), FoldOrientation.HORIZONTAL,
            isSeparating = true, occludesFully = false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        assertEquals(FoldPresentation.HORIZONTAL_SPLIT, layout.presentation)
        val safe = requireNotNull(layout.safePane)
        // Top region (0..292) is smaller than bottom (328..800) -> bottom wins.
        assertTrue(safe.height > 400f)
        assertEquals(800f, safe.bottom, 0.01f)
        assertNull(layout.leftPane); assertNull(layout.rightPane)
    }

    @Test fun horizontalFoldExactTieBreaksToTop() {
        val fold = ReaderFoldDescriptor(FoldRect(0f, 400f, 1000f, 400f), FoldOrientation.HORIZONTAL,
            isSeparating = true, occludesFully = false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        val safe = requireNotNull(layout.safePane)
        assertEquals(0f, safe.top, 0.01f) // top pane selected on an exact tie
    }

    @Test fun malformedOutOfRangeBoundsDegradeSafelyToFlat() {
        val fold = ReaderFoldDescriptor(FoldRect(Float.NaN, 0f, 10f, 800f), FoldOrientation.VERTICAL,
            isSeparating = true, occludesFully = false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        assertEquals(FoldPresentation.FLAT, layout.presentation)
    }

    @Test fun emptyReaderBoundsDegradeSafelyToFlat() {
        val fold = ReaderFoldDescriptor(FoldRect(0f, 0f, 500f, 800f), FoldOrientation.VERTICAL, true, false)
        val layout = resolveReaderFoldLayout(FoldRect(0f, 0f, 0f, 0f), fold, gutter)
        assertEquals(FoldPresentation.FLAT, layout.presentation)
    }

    // --- Solo-page pane selection -------------------------------------------------------------------------

    @Test fun soloPageInFlatUsesTheWholeBounds() {
        val layout = resolveReaderFoldLayout(reader, null, gutter)
        assertEquals(layout.flatPane, selectSoloPane(layout, rightToLeft = false))
    }

    @Test fun soloPageInHorizontalFoldUsesTheSafePane() {
        val fold = ReaderFoldDescriptor(FoldRect(0f, 300f, 1000f, 320f), FoldOrientation.HORIZONTAL, true, false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        assertEquals(layout.safePane, selectSoloPane(layout, rightToLeft = false))
    }

    @Test fun soloPageInVerticalSplitPrefersTheLargerPane() {
        val fold = ReaderFoldDescriptor(FoldRect(200f, 0f, 220f, 800f), FoldOrientation.VERTICAL, true, false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        // Left pane is 200px wide, right pane is 780px wide -> right (the larger) wins regardless of direction.
        assertEquals(layout.rightPane, selectSoloPane(layout, rightToLeft = false))
        assertEquals(layout.rightPane, selectSoloPane(layout, rightToLeft = true))
    }

    @Test fun soloPageTieBreaksToReadingDirectionStartPane() {
        val fold = ReaderFoldDescriptor(FoldRect(490f, 0f, 510f, 800f), FoldOrientation.VERTICAL, true, false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter) // symmetric -> tie
        assertEquals(layout.leftPane, selectSoloPane(layout, rightToLeft = false)) // LTR -> left
        assertEquals(layout.rightPane, selectSoloPane(layout, rightToLeft = true)) // RTL -> right
    }

    // --- AUTO / SPREAD policy -----------------------------------------------------------------------------

    @Test fun autoRejectsASpreadWhenOnePaneIsATinySliverEvenIfTotalWidthIsLarge() {
        // Total 900dp usable, but left pane is only 50dp -- individually useless despite ample total width.
        assertFalse(verticalFoldSpreadEligibleForAuto(50f, 850f, 600))
    }

    @Test fun autoAcceptsASpreadWhenBothPanesAreIndividuallyUseful() {
        assertTrue(verticalFoldSpreadEligibleForAuto(320f, 320f, 600))
    }

    @Test fun autoRejectsWhenCombinedWidthIsBelowTheExistingThreshold() {
        assertFalse(verticalFoldSpreadEligibleForAuto(250f, 250f, 600))
    }

    @Test fun explicitSpreadAcceptsAnyTwoUsablePanesEvenBelowAutoThreshold() {
        val fold = ReaderFoldDescriptor(FoldRect(100f, 0f, 120f, 800f), FoldOrientation.VERTICAL, true, false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        assertTrue(verticalFoldHasTwoUsablePanes(layout))
    }

    @Test fun explicitSpreadRejectsWhenAPaneHasNoAreaLeft() {
        // Hinge intersection spans the entire reader width -> one pane collapses to zero width.
        val fold = ReaderFoldDescriptor(FoldRect(-500f, 0f, 1500f, 800f), FoldOrientation.VERTICAL, true, false)
        val layout = resolveReaderFoldLayout(reader, fold, gutter)
        assertFalse(verticalFoldHasTwoUsablePanes(layout))
    }

    // --- Non-reader legacy safe-pane regression guard -----------------------------------------------------

    @Test fun legacyInsetMatchesPreexistingVerticalBehavior() {
        // Left region (100px) is smaller than the right region (880px) -> the original formula keeps the LARGER
        // right-hand region visible by padding away the smaller left region (+hinge) from the LEFT side.
        val bounds = FoldRect(0f, 0f, 1000f, 800f)
        val hinge = FoldRect(100f, 0f, 120f, 800f)
        val inset = legacySafePaneInset(bounds, hinge, vertical = true)
        assertEquals(0f, inset.top, 0.01f); assertEquals(0f, inset.right, 0.01f); assertEquals(0f, inset.bottom, 0.01f)
        assertEquals(120f, inset.left, 0.01f) // == hinge.right - bounds.left (the left region + hinge)
    }

    @Test fun legacyInsetHandlesHorizontalOrientationAndNullHinge() {
        val bounds = FoldRect(0f, 0f, 1000f, 800f)
        val hinge = FoldRect(0f, 600f, 1000f, 620f)
        val inset = legacySafePaneInset(bounds, hinge, vertical = false)
        assertTrue(inset.top > 0f || inset.bottom > 0f)
        val none = legacySafePaneInset(bounds, null, vertical = true)
        assertEquals(FoldInset(0f, 0f, 0f, 0f), none)
    }
}
