// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Rect
import androidx.window.layout.FoldingFeature
import com.d4guilar.shelfos.core.reader.FoldOrientation
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3D Codex R1 remediation, finding 3: the missing production-mapper test. `3550484` had no test at all of
 * the REAL `FoldingFeature.toReaderFoldDescriptor()` mapping function (in `MainActivity.kt`, now `internal`
 * rather than `private` specifically so this test can call it directly -- see that function's own doc). This
 * exercises the REAL function, never a reimplementation of its mapping logic.
 *
 * `androidx.window:window-testing` is not a dependency of this project (and the remediation contract explicitly
 * forbids adding one without a stop-and-report), so there is no official `FoldingFeature` test builder available.
 * `androidx.window.layout.FoldingFeature` is a plain public Kotlin interface, so this test instead implements it
 * directly with a minimal, real (non-mocking-library) fake -- the smallest way to drive the actual production
 * extension function with a real `FoldingFeature`-typed receiver.
 */
class FoldDescriptorMapperTest {

    private class FakeFoldingFeature(
        override val bounds: Rect,
        override val orientation: FoldingFeature.Orientation,
        override val isSeparating: Boolean,
        override val occlusionType: FoldingFeature.OcclusionType,
        override val state: FoldingFeature.State = FoldingFeature.State.HALF_OPENED,
    ) : FoldingFeature

    @Test fun mapsBoundsFromAndroidRectToFoldRectUnchanged() {
        val feature = FakeFoldingFeature(Rect(50, 20, 1030, 820), FoldingFeature.Orientation.VERTICAL, true, FoldingFeature.OcclusionType.NONE)
        val descriptor = feature.toReaderFoldDescriptor()
        assertEquals(50f, descriptor.bounds.left, 0.01f)
        assertEquals(20f, descriptor.bounds.top, 0.01f)
        assertEquals(1030f, descriptor.bounds.right, 0.01f)
        assertEquals(820f, descriptor.bounds.bottom, 0.01f)
    }

    @Test fun mapsVerticalOrientation() {
        val feature = FakeFoldingFeature(Rect(0, 0, 10, 10), FoldingFeature.Orientation.VERTICAL, true, FoldingFeature.OcclusionType.NONE)
        assertEquals(FoldOrientation.VERTICAL, feature.toReaderFoldDescriptor().orientation)
    }

    @Test fun mapsHorizontalOrientation() {
        val feature = FakeFoldingFeature(Rect(0, 0, 10, 10), FoldingFeature.Orientation.HORIZONTAL, true, FoldingFeature.OcclusionType.NONE)
        assertEquals(FoldOrientation.HORIZONTAL, feature.toReaderFoldDescriptor().orientation)
    }

    @Test fun mapsIsSeparatingTrueAndFalse() {
        val separating = FakeFoldingFeature(Rect(0, 0, 10, 10), FoldingFeature.Orientation.VERTICAL, true, FoldingFeature.OcclusionType.NONE)
        val nonSeparating = FakeFoldingFeature(Rect(0, 0, 10, 10), FoldingFeature.Orientation.VERTICAL, false, FoldingFeature.OcclusionType.NONE)
        assertTrue(separating.toReaderFoldDescriptor().isSeparating)
        assertFalse(nonSeparating.toReaderFoldDescriptor().isSeparating)
    }

    @Test fun mapsFullOcclusionToOccludesFullyTrue() {
        val feature = FakeFoldingFeature(Rect(0, 0, 10, 10), FoldingFeature.Orientation.VERTICAL, false, FoldingFeature.OcclusionType.FULL)
        assertTrue(feature.toReaderFoldDescriptor().occludesFully)
    }

    @Test fun mapsNoOcclusionToOccludesFullyFalse() {
        val feature = FakeFoldingFeature(Rect(0, 0, 10, 10), FoldingFeature.Orientation.VERTICAL, true, FoldingFeature.OcclusionType.NONE)
        assertFalse(feature.toReaderFoldDescriptor().occludesFully)
    }

    @Test fun irrelevantCreaseMapsToADescriptorThatIsItselfNotRelevant() {
        // Mapping never filters -- `isRelevant` is computed on the RESULT, confirming the mapper itself preserves
        // every flag faithfully (including the combination that makes a descriptor irrelevant downstream) rather
        // than silently normalizing a non-separating, non-occluding crease into something else.
        val feature = FakeFoldingFeature(Rect(0, 0, 10, 10), FoldingFeature.Orientation.VERTICAL, false, FoldingFeature.OcclusionType.NONE)
        val descriptor = feature.toReaderFoldDescriptor()
        assertFalse(descriptor.isSeparating)
        assertFalse(descriptor.occludesFully)
        assertFalse("a non-separating, non-occluding mapped descriptor must not be relevant", descriptor.isRelevant)
    }

    @Test fun relevantSeparatingCreaseMapsToARelevantDescriptor() {
        val feature = FakeFoldingFeature(Rect(0, 0, 10, 10), FoldingFeature.Orientation.VERTICAL, true, FoldingFeature.OcclusionType.NONE)
        assertTrue(feature.toReaderFoldDescriptor().isRelevant)
    }

    @Test fun multipleRealFeaturesEachMapIndependentlyWithoutLosingAny() {
        // The actual production entry point maps a List<FoldingFeature> via `.map { it.toReaderFoldDescriptor() }`
        // (MainActivity's own `foldingFlow`) -- confirms mapping a real list preserves every element's own
        // identity/flags independently, with none dropped or merged, before any relevance/intersection filtering.
        val vertical = FakeFoldingFeature(Rect(100, 0, 120, 800), FoldingFeature.Orientation.VERTICAL, true, FoldingFeature.OcclusionType.NONE)
        val horizontal = FakeFoldingFeature(Rect(0, 300, 1000, 320), FoldingFeature.Orientation.HORIZONTAL, true, FoldingFeature.OcclusionType.NONE)
        val features: List<FoldingFeature> = listOf(vertical, horizontal)
        val descriptors = features.map { it.toReaderFoldDescriptor() }
        assertEquals(2, descriptors.size)
        assertEquals(FoldOrientation.VERTICAL, descriptors[0].orientation)
        assertEquals(FoldOrientation.HORIZONTAL, descriptors[1].orientation)
        assertEquals(100f, descriptors[0].bounds.left, 0.01f)
        assertEquals(300f, descriptors[1].bounds.top, 0.01f)
    }
}
