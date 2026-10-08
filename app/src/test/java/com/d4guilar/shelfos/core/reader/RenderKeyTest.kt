// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3D Codex R1 remediation, finding 2 (R2 remediation, finding A): pure JVM coverage for
 * [renderKeyBucket]/[acceptedRenderKeyBucket]/[effectiveRenderKey]/[resolveRenderTargets] -- the quantization/
 * hysteresis/comparison policy [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]
 * uses to decide whether a new decode is actually warranted, and the shape it then actually decodes at. See
 * [EffectiveRenderKey]'s own class doc for exactly what "materially different" means, and
 * [acceptedRenderKeyBucket]'s own doc for the hysteresis policy that replaces R1's plain nearest-bucket-only
 * comparison.
 */
class RenderKeyTest {

    private fun single(widthPx: Int, heightPx: Int, widthDp: Int = 0) = ReaderRenderGeometry.flat(widthPx, heightPx, widthDp)
    private fun spreadGeometry(leftW: Int, leftH: Int, rightW: Int, rightH: Int, leftDp: Float = 0f, rightDp: Float = 0f) =
        ReaderRenderGeometry(FoldPresentation.VERTICAL_SPLIT,
            ReaderRenderGeometry.SingleTarget(rightW, rightH, rightDp.toInt()),
            ReaderRenderGeometry.SpreadTarget(leftW, leftH, rightW, rightH, leftDp, rightDp))

    // ---- renderKeyBucket: the stateless quantization policy --------------------------------------------------

    @Test fun bucketIsStableForSmallDriftAroundACenteredValue() {
        // 32px bucket width, centered on a value with a full +-16px margin (800 == 25*32 exactly).
        val base = renderKeyBucket(800)
        assertEquals(base, renderKeyBucket(806))
        assertEquals(base, renderKeyBucket(794))
        assertEquals(base, renderKeyBucket(812))
        assertEquals(base, renderKeyBucket(788))
    }

    @Test fun bucketChangesOnceDriftCrossesTheBucketBoundary() {
        val base = renderKeyBucket(800) // 25
        assertNotEquals(base, renderKeyBucket(820)) // crosses into the next bucket (26)
        assertNotEquals(base, renderKeyBucket(780)) // crosses into the previous bucket (24)
    }

    @Test fun bucketDistinguishesAMaterialPaneResize() {
        // The finding's own example: a pane growing from ~500px to ~780px, comfortably many buckets apart.
        assertNotEquals(renderKeyBucket(500), renderKeyBucket(780))
    }

    @Test fun bucketOfNonPositivePxIsZero() {
        assertEquals(0, renderKeyBucket(0))
        assertEquals(0, renderKeyBucket(-10))
    }

    // ---- acceptedRenderKeyBucket: Codex R2 remediation, finding A part 2 -- hysteresis around an old boundary --

    @Test fun acceptedBucketNeverThrashesAcrossTheOldPlainBoundary() {
        // 783/784 straddle renderKeyBucket's own 24/25 boundary (784.0 == 24.5 * 32): plain nearest-rounding
        // flips on every single oscillation. Hysteresis must hold the ACCEPTED bucket once settled.
        val firstAccepted = acceptedRenderKeyBucket(783, null) // no prior state -> plain nearest rounding
        assertEquals(renderKeyBucket(783), firstAccepted)
        var accepted = firstAccepted
        val oscillation = listOf(784, 783, 784, 783, 784, 783, 784, 783)
        var changes = 0
        oscillation.forEach { px ->
            val next = acceptedRenderKeyBucket(px, accepted)
            if (next != accepted) changes++
            accepted = next
        }
        assertEquals("oscillating 1px across the OLD plain-rounding boundary must never change the accepted bucket " +
            "once it has settled", 0, changes)
        assertEquals(firstAccepted, accepted)
    }

    @Test fun acceptedBucketStillMovesOnceDriftGenuinelyExitsTheHysteresisBand() {
        val accepted = acceptedRenderKeyBucket(783, null)
        // Comfortably past the widened band (accepted bucket's center +- (16 + 12) == 28px): a genuine move,
        // not boundary noise, must still correct the accepted bucket exactly once.
        val moved = acceptedRenderKeyBucket(820, accepted)
        assertNotEquals("a genuinely material move must still update the accepted bucket", accepted, moved)
        assertEquals(renderKeyBucket(820), moved)
    }

    @Test fun acceptedBucketWithNoPriorStateFallsBackToPlainNearestRounding() {
        assertEquals(renderKeyBucket(900), acceptedRenderKeyBucket(900, null))
    }

    @Test fun acceptedBucketOfNonPositivePxIsZeroRegardlessOfPriorState() {
        assertEquals(0, acceptedRenderKeyBucket(0, 25))
        assertEquals(0, acceptedRenderKeyBucket(-5, 25))
    }

    // ---- effectiveRenderKey: hysteresis end-to-end, via the real key-building entry point ---------------------

    @Test fun boundaryJitterAroundTheOldBucketBoundaryProducesZeroKeyChangesAfterStabilizing() {
        // Simulates a real fold/unfold posture animation jittering by 1px around the OLD plain-bucket boundary:
        // FixedReaderViewModel would call render() exactly once per key CHANGE -- this proves that count is zero
        // once the accepted key has settled, for both Single and Spread shapes.
        var key: EffectiveRenderKey? = effectiveRenderKey(single(783, 1200), 1, spreadActive = false, previous = null)
        var renderCount = 1 // the initial settle
        listOf(784, 783, 784, 783, 784, 783).forEach { px ->
            val next = effectiveRenderKey(single(px, 1200), 1, spreadActive = false, previous = key)
            if (next != key) renderCount++
            key = next
        }
        assertEquals("1px jitter across the old boundary must never trigger a render after the first settle",
            1, renderCount)
    }

    @Test fun boundaryJitterOnASpreadPaneAlsoProducesZeroExtraRendersAfterStabilizing() {
        var key: EffectiveRenderKey? = effectiveRenderKey(spreadGeometry(783, 1200, 1100, 1200), 2, spreadActive = true, previous = null)
        var renderCount = 1
        listOf(784, 783, 784, 783).forEach { px ->
            val next = effectiveRenderKey(spreadGeometry(px, 1200, 1100, 1200), 2, spreadActive = true, previous = key)
            if (next != key) renderCount++
            key = next
        }
        assertEquals("1px jitter on a spread pane across the old boundary must never trigger a render after " +
            "the first settle", 1, renderCount)
    }

    @Test fun materialResizeProducesExactlyOneRenderCorrection() {
        // The finding's own named example: ~500px -> ~780px pane, a single genuine one-shot resize (never an
        // animation of many intermediate frames -- each of THOSE is its own separate accept/reject decision).
        // This proves the transition itself corrects exactly once: never silently absorbed (zero), never
        // over-triggered by the wider hysteresis band (more than one).
        val before = effectiveRenderKey(single(500, 1200), groupSize = 1, spreadActive = false)
        val after = effectiveRenderKey(single(780, 1200), groupSize = 1, spreadActive = false, previous = before)
        assertNotEquals("a genuinely material pane growth must still produce a render correction", before, after)
        // Once the correction settles, a harmless 1px drift AROUND THE NEW value must not immediately thrash
        // back -- proving this is a real accepted-state transition, not a one-off coincidence of these two exact
        // numbers.
        val afterDrift = effectiveRenderKey(single(781, 1200), groupSize = 1, spreadActive = false, previous = after)
        assertEquals("after the correction settles, a 1px drift around the NEW value must not re-trigger",
            after, afterDrift)
    }

    // ---- effectiveRenderKey: Single vs Spread shape (Codex R2: shape now driven by groupSize, not geometry type) -

    @Test fun flatAndFoldSpreadKeysAreNeverEqualRegardlessOfBucketCoincidence() {
        // Codex R1 finding 2's required "flat <-> asymmetric vertical fold ... must rerender" case: even if a
        // Single box's bucket happened to coincide with one Spread slot's bucket, the key STRUCTURE (one
        // SlotTarget vs two) already differs, so the two keys can never compare equal.
        val flat = effectiveRenderKey(single(800, 1200, 400), groupSize = 1, spreadActive = true)
        val foldSpread = effectiveRenderKey(spreadGeometry(800, 1200, 800, 1200, 400f, 400f), groupSize = 2, spreadActive = true)
        assertNotEquals("flat and fold-spread keys must never collide even with identical pane numbers", flat, foldSpread)
        assertEquals(1, flat.slots.size)
        assertEquals(2, foldSpread.slots.size)
    }

    @Test fun sameGeometryDifferentGroupSizeNeverCollidesEitherSinceShapeFollowsGroupSizeNow() {
        // Codex R2 remediation, finding A: a geometry that CAN produce a spread (spread != null) but whose
        // resolved group is currently solo (groupSize == 1) must use the Single shape -- proving the shape
        // decision now genuinely follows the resolved PageGroup, not geometry type alone.
        val geometry = spreadGeometry(600, 1000, 900, 1000, 300f, 450f)
        val soloKey = effectiveRenderKey(geometry, groupSize = 1, spreadActive = false)
        val spreadKey = effectiveRenderKey(geometry, groupSize = 2, spreadActive = true)
        assertEquals(1, soloKey.slots.size)
        assertEquals(2, spreadKey.slots.size)
        assertNotEquals(soloKey, spreadKey)
    }

    @Test fun verticalFoldSpreadToFlatIsTheSymmetricCaseAndAlsoNeverEqual() {
        val foldSpread = effectiveRenderKey(spreadGeometry(600, 1000, 900, 1000, 300f, 450f), groupSize = 2, spreadActive = true)
        val flat = effectiveRenderKey(single(1500, 1000, 750), groupSize = 1, spreadActive = true)
        assertNotEquals(foldSpread, flat)
    }

    @Test fun verticalToHorizontalDiffersWhenPaneGeometryDiffers() {
        // Both resolve to the Single shape (one safe pane each), but a vertical-split solo pane and a horizontal-
        // split safe pane have materially different own dimensions in this scenario -> different buckets -> differ.
        val verticalSolo = effectiveRenderKey(single(900, 1600, 450), groupSize = 1, spreadActive = false)
        val horizontalSafe = effectiveRenderKey(single(1600, 700, 800), groupSize = 1, spreadActive = false)
        assertNotEquals(verticalSolo, horizontalSafe)
    }

    @Test fun horizontalSafePaneGeometryChangeIsDetected() {
        val before = effectiveRenderKey(single(1600, 700, 800), groupSize = 1, spreadActive = false)
        val after = effectiveRenderKey(single(1600, 500, 800), groupSize = 1, spreadActive = false) // height shrank materially
        assertNotEquals("a material safe-pane height change must be detected", before, after)
    }

    // ---- effectiveRenderKey: harmless drift vs material resize, for both Single and Spread --------------------

    @Test fun singleGeometryTinyDriftProducesAnEqualKey() {
        // 800 (25*32) and 1216 (38*32) are exact bucket centers, each with a full +-16px margin, so the small
        // drift below cannot straddle a boundary the way a value close to an exact half-bucket could.
        val before = effectiveRenderKey(single(800, 1216, 400), groupSize = 1, spreadActive = false)
        val after = effectiveRenderKey(single(806, 1210, 400), groupSize = 1, spreadActive = false, previous = before)
        assertEquals(before, after)
    }

    @Test fun singleGeometryMaterialWidthGrowthProducesADifferentKey() {
        // The finding's own named example: ~500px -> ~780px pane, same spread decision (false both times).
        val before = effectiveRenderKey(single(500, 1200, 250), groupSize = 1, spreadActive = false)
        val after = effectiveRenderKey(single(780, 1200, 390), groupSize = 1, spreadActive = false, previous = before)
        assertNotEquals(before, after)
    }

    @Test fun spreadGeometryTinyDriftOnBothPanesProducesAnEqualKey() {
        // 800 (25*32) and 1088 (34*32) are exact bucket centers; heights held constant at 3008 (94*32, also an
        // exact center) in both calls to isolate the width-drift assertion from any height-boundary quirk.
        val before = effectiveRenderKey(spreadGeometry(800, 3008, 1088, 3008, 400f, 550f), groupSize = 2, spreadActive = true)
        val after = effectiveRenderKey(spreadGeometry(806, 3008, 1082, 3008, 400f, 550f), groupSize = 2, spreadActive = true, previous = before)
        assertEquals(before, after)
    }

    @Test fun spreadGeometryMaterialPaneGrowthStillActiveProducesADifferentKey() {
        // "Meaningful same-spread pane growth ... spread still active ... must rerender exactly once."
        val before = effectiveRenderKey(spreadGeometry(500, 1200, 500, 1200, 250f, 250f), groupSize = 2, spreadActive = true)
        val after = effectiveRenderKey(spreadGeometry(780, 1200, 500, 1200, 390f, 250f), groupSize = 2, spreadActive = true, previous = before)
        assertNotEquals(before, after)
    }

    @Test fun spreadSlotClassificationIsPreservedInTheKey() {
        val key = effectiveRenderKey(spreadGeometry(800, 1200, 900, 1200, 400f, 450f), groupSize = 2, spreadActive = true)
        assertTrue(key.slots.all { it.spreadSlot })
    }

    @Test fun singleSpreadSlotClassificationMatchesTheSpreadActiveFlagPassedIn() {
        val activeKey = effectiveRenderKey(single(1600, 1200, 800), groupSize = 1, spreadActive = true)
        val inactiveKey = effectiveRenderKey(single(1600, 1200, 800), groupSize = 1, spreadActive = false)
        assertTrue(activeKey.slots.single().spreadSlot)
        assertFalse(inactiveKey.slots.single().spreadSlot)
        // Buckets are identical (same geometry) -- only the spreadSlot classification differs -- so the two
        // keys must still differ overall, proving a bare spreadActive() flip (3C's own original signal) is
        // never silently absorbed by this coarser-looking key.
        assertNotEquals(activeKey, inactiveKey)
    }

    // ---- resolveRenderTargets: the actual per-logical-page decode dimensions render() uses ---------------------

    @Test fun resolveRenderTargetsUsesPerPaneDimsForATwoPageGroupWhenSpreadGeometryExists() {
        val geometry = spreadGeometry(300, 1000, 900, 1000, 150f, 450f)
        val targets = resolveRenderTargets(geometry, groupSize = 2)
        assertEquals(listOf(300 to 1000, 900 to 1000), targets)
    }

    @Test fun resolveRenderTargetsFallsBackToTheSingleBoxWhenGroupIsSoloEvenIfSpreadGeometryExists() {
        // Codex R2 remediation, finding A: a solo page must use the SINGLE box, never the spread's own panes,
        // regardless of whether the current fold layout also happens to support a spread right now.
        val geometry = spreadGeometry(300, 1000, 900, 1000, 150f, 450f) // .single here is the larger (right) pane
        val targets = resolveRenderTargets(geometry, groupSize = 1)
        assertEquals(1, targets.size)
        assertEquals(900 to 1000, targets[0])
    }

    @Test fun resolveRenderTargetsSplitsTheSingleBoxEvenlyForAFlatTwoSlotSpread() {
        val geometry = single(2000, 1000, 1000)
        val targets = resolveRenderTargets(geometry, groupSize = 2)
        assertEquals(listOf(1000 to 1000, 1000 to 1000), targets)
    }
}
