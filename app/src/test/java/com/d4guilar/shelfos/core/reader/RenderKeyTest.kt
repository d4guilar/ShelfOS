// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3D Codex R1 remediation, finding 2: pure JVM coverage for [renderKeyBucket]/[effectiveRenderKey] -- the
 * quantization/comparison policy [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]
 * uses to decide whether a new decode is actually warranted, replacing `3550484`'s own "only compare
 * `spreadActive()`'s boolean" model. See [EffectiveRenderKey]'s own class doc for exactly what "materially
 * different" means.
 */
class RenderKeyTest {

    // ---- renderKeyBucket: the quantization policy itself -----------------------------------------------------

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

    // ---- effectiveRenderKey: Single vs Spread shape -----------------------------------------------------------

    @Test fun flatAndFoldSpreadKeysAreNeverEqualRegardlessOfBucketCoincidence() {
        // Codex R1 finding 2's required "flat <-> asymmetric vertical fold ... must rerender" case: even if a
        // Single box's bucket happened to coincide with one Spread slot's bucket, the key STRUCTURE (one
        // SlotTarget vs two) already differs, so the two keys can never compare equal.
        val flat = effectiveRenderKey(ReaderRenderGeometry.Single(800, 1200, 400), spreadActive = true)
        val foldSpread = effectiveRenderKey(ReaderRenderGeometry.Spread(800, 1200, 800, 1200, 400f, 400f), spreadActive = true)
        assertNotEquals("flat and fold-spread keys must never collide even with identical pane numbers", flat, foldSpread)
        assertEquals(1, flat.slots.size)
        assertEquals(2, foldSpread.slots.size)
    }

    @Test fun verticalFoldSpreadToFlatIsTheSymmetricCaseAndAlsoNeverEqual() {
        val foldSpread = effectiveRenderKey(ReaderRenderGeometry.Spread(600, 1000, 900, 1000, 300f, 450f), spreadActive = true)
        val flat = effectiveRenderKey(ReaderRenderGeometry.Single(1500, 1000, 750), spreadActive = true)
        assertNotEquals(foldSpread, flat)
    }

    @Test fun verticalToHorizontalDiffersWhenPaneGeometryDiffers() {
        // Both resolve to Single (one safe pane each), but a vertical-split solo pane and a horizontal-split
        // safe pane have materially different own dimensions in this scenario -> different buckets -> differ.
        val verticalSolo = effectiveRenderKey(ReaderRenderGeometry.Single(900, 1600, 450), spreadActive = false)
        val horizontalSafe = effectiveRenderKey(ReaderRenderGeometry.Single(1600, 700, 800), spreadActive = false)
        assertNotEquals(verticalSolo, horizontalSafe)
    }

    @Test fun horizontalSafePaneGeometryChangeIsDetected() {
        val before = effectiveRenderKey(ReaderRenderGeometry.Single(1600, 700, 800), spreadActive = false)
        val after = effectiveRenderKey(ReaderRenderGeometry.Single(1600, 500, 800), spreadActive = false) // height shrank materially
        assertNotEquals("a material safe-pane height change must be detected", before, after)
    }

    // ---- effectiveRenderKey: harmless drift vs material resize, for both Single and Spread --------------------

    @Test fun singleGeometryTinyDriftProducesAnEqualKey() {
        // 800 (25*32) and 1216 (38*32) are exact bucket centers, each with a full +-16px margin, so the small
        // drift below cannot straddle a boundary the way a value close to an exact half-bucket could.
        val before = effectiveRenderKey(ReaderRenderGeometry.Single(800, 1216, 400), spreadActive = false)
        val after = effectiveRenderKey(ReaderRenderGeometry.Single(806, 1210, 400), spreadActive = false)
        assertEquals(before, after)
    }

    @Test fun singleGeometryMaterialWidthGrowthProducesADifferentKey() {
        // The finding's own named example: ~500px -> ~780px pane, same spread decision (false both times).
        val before = effectiveRenderKey(ReaderRenderGeometry.Single(500, 1200, 250), spreadActive = false)
        val after = effectiveRenderKey(ReaderRenderGeometry.Single(780, 1200, 390), spreadActive = false)
        assertNotEquals(before, after)
    }

    @Test fun spreadGeometryTinyDriftOnBothPanesProducesAnEqualKey() {
        // 800 (25*32) and 1088 (34*32) are exact bucket centers; heights held constant at 3008 (94*32, also an
        // exact center) in both calls to isolate the width-drift assertion from any height-boundary quirk.
        val before = effectiveRenderKey(ReaderRenderGeometry.Spread(800, 3008, 1088, 3008, 400f, 550f), spreadActive = true)
        val after = effectiveRenderKey(ReaderRenderGeometry.Spread(806, 3008, 1082, 3008, 400f, 550f), spreadActive = true)
        assertEquals(before, after)
    }

    @Test fun spreadGeometryMaterialPaneGrowthStillActiveProducesADifferentKey() {
        // "Meaningful same-spread pane growth ... spread still active ... must rerender exactly once."
        val before = effectiveRenderKey(ReaderRenderGeometry.Spread(500, 1200, 500, 1200, 250f, 250f), spreadActive = true)
        val after = effectiveRenderKey(ReaderRenderGeometry.Spread(780, 1200, 500, 1200, 390f, 250f), spreadActive = true)
        assertNotEquals(before, after)
    }

    @Test fun spreadSlotClassificationIsPreservedInTheKey() {
        val key = effectiveRenderKey(ReaderRenderGeometry.Spread(800, 1200, 900, 1200, 400f, 450f), spreadActive = true)
        assertTrue(key.slots.all { it.spreadSlot })
    }

    @Test fun singleSpreadSlotClassificationMatchesTheSpreadActiveFlagPassedIn() {
        val activeKey = effectiveRenderKey(ReaderRenderGeometry.Single(1600, 1200, 800), spreadActive = true)
        val inactiveKey = effectiveRenderKey(ReaderRenderGeometry.Single(1600, 1200, 800), spreadActive = false)
        assertTrue(activeKey.slots.single().spreadSlot)
        assertFalse(inactiveKey.slots.single().spreadSlot)
        // Buckets are identical (same geometry) -- only the spreadSlot classification differs -- so the two
        // keys must still differ overall, proving a bare spreadActive() flip (3C's own original signal) is
        // never silently absorbed by this coarser-looking key.
        assertNotEquals(activeKey, inactiveKey)
    }
}
