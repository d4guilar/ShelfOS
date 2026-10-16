// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.d4guilar.shelfos.domain.library.*
import com.d4guilar.shelfos.feature.home.coverExpandTarget
import androidx.compose.ui.geometry.lerp
import org.junit.Assert.*
import org.junit.Test

/** Phase 4.5A.2 shared-renderer geometry (plan §7 rules 3-8): slot/frame per mode, crop/fit math, never stretched. */
class CoverLayoutTest {
    private fun p(shape: CoverShape, fit: CoverFit = CoverFit.CROP) = CoverPresentation(shape, fit)
    private val eps = 1e-3f

    @Test fun shapedModesUseTheShapeForSlotAndFrame() {
        assertEquals(CoverLayout(2f / 3, 2f / 3, crop = true, neutralFill = false), coverLayout(p(CoverShape.RATIO_2_3), 0.9f))
        assertEquals(CoverLayout(1f, 1f, crop = false, neutralFill = true), coverLayout(p(CoverShape.RATIO_1_1, CoverFit.FIT), 0.5f))
        assertEquals("generated fallback: drawn at the shape, no neutral fill", CoverLayout(0.8f, 0.8f, false, false),
            coverLayout(p(CoverShape.RATIO_4_5, CoverFit.FIT), null))
        assertEquals(0.75f, coverLayout(CoverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 3, 4), 1f).frame, 0f)
    }

    @Test fun originalReservesA23SlotAndFramesTheClampedNativeRatio() {
        for (fit in CoverFit.entries) {
            assertEquals("fit is irrelevant in Original", CoverLayout(2f / 3, 0.9f, false, false), coverLayout(p(CoverShape.ORIGINAL, fit), 0.9f))
        }
        assertEquals(0.5f, coverLayout(p(CoverShape.ORIGINAL), 0.1f).frame, 0f)
        assertEquals(2f, coverLayout(p(CoverShape.ORIGINAL), 9f).frame, 0f)
        assertEquals("generated Original uses the 2:3 slot", CoverLayout(2f / 3, 2f / 3, false, false), coverLayout(p(CoverShape.ORIGINAL), null))
        assertEquals(CoverLayout(2f / 3, 2f / 3, false, false), coverLayout(p(CoverShape.ORIGINAL), Float.NaN))
    }

    @Test fun wholeModeIgnoresThePreferenceAndShowsTheNativeRatio() {
        for (pref in listOf(p(CoverShape.RATIO_1_1), p(CoverShape.RATIO_2_3, CoverFit.FIT), p(CoverShape.ORIGINAL)))
            assertEquals(CoverLayout(1.4f, 1.4f, false, false), coverLayout(pref, 1.4f, whole = true))
        assertEquals("generated details cover uses the effective shape", 1f, coverLayout(p(CoverShape.RATIO_1_1), null, whole = true).frame, 0f)
    }

    @Test fun cropFillsTheFrameAndCentersTheOverflow() {
        val r = artworkRect(Size(200f, 300f), 1f, crop = true)
        assertEquals(Rect(-50f, 0f, 250f, 300f), r)
        val tall = artworkRect(Size(200f, 300f), 0.5f, crop = true)
        assertEquals(Rect(0f, -50f, 200f, 350f), tall)
    }

    @Test fun fitShowsTheWholeArtworkInsideTheFrame() {
        assertEquals(Rect(0f, 50f, 200f, 250f), artworkRect(Size(200f, 300f), 1f, crop = false))
        assertEquals("Original stands the artwork on the bottom edge", Rect(0f, 100f, 200f, 300f), artworkRect(Size(200f, 300f), 1f, crop = false, bottom = true))
        assertEquals(Rect(25f, 0f, 175f, 300f), artworkRect(Size(200f, 300f), 0.5f, crop = false, bottom = true))
    }

    @Test fun neverStretchedAcrossShapesRatiosAndModes() {
        val frames = listOf(Size(100f, 150f), Size(300f, 300f), Size(400f, 100f), Size(37f, 91f))
        val ratios = listOf(0.1f, 0.5f, 0.68f, 1f, 1.5f, 2f, 7f)
        for (frame in frames) for (ratio in ratios) for (crop in listOf(true, false)) for (bottom in listOf(true, false)) {
            val r = artworkRect(frame, ratio, crop, bottom)
            assertEquals("aspect preserved for $frame $ratio crop=$crop", ratio, r.width / r.height, ratio * eps)
            if (crop) assertTrue("crop covers the frame", r.left <= eps && r.top <= eps && r.right >= frame.width - eps && r.bottom >= frame.height - eps)
            else assertTrue("fit stays inside", r.left >= -eps && r.top >= -eps && r.right <= frame.width + eps && r.bottom <= frame.height + eps)
            assertTrue("one axis matches exactly", kotlin.math.abs(r.width - frame.width) < eps || kotlin.math.abs(r.height - frame.height) < eps)
        }
        assertEquals("an invalid ratio fills the frame instead of dividing by zero", Rect(0f, 0f, 100f, 150f), artworkRect(Size(100f, 150f), 0f, crop = false))
    }

    @Test fun derivedCoverDimensionsKeepTheSourceRatio() {
        // A DERIVED copy (≤ 1200 px) records source dimensions; layout uses the stored ratio, which equals it within rounding.
        val source = 1800f / 2700f; val stored = 800f / 1200f
        assertEquals(coverLayout(p(CoverShape.ORIGINAL), source).frame, coverLayout(p(CoverShape.ORIGINAL), stored).frame, eps)
    }

    @Test fun coverExpansionKeepsOneRatioAndEndsWithTheWholeArtworkFittedInTheReader() {
        val start = Rect(40f, 100f, 140f, 250f)                 // details cover, 2:3
        val area = Rect(0f, 80f, 1000f, 680f)                   // reader area, landscape
        val end = coverExpandTarget(start, area)
        assertEquals(start.width / start.height, end.width / end.height, eps)
        assertEquals(area.height, end.height, eps)
        assertEquals(area.center.x, end.center.x, eps)
        for (i in 0..10) {
            val r = lerp(start, end, i / 10f)
            assertEquals("frame $i keeps the artwork ratio (no distorted intermediates)", start.width / start.height, r.width / r.height, eps)
        }
        assertEquals("degenerate start falls back to the area", area, coverExpandTarget(Rect.Zero, area))
    }
}
