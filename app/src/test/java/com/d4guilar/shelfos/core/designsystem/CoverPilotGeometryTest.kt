// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import androidx.compose.ui.geometry.Size
import com.d4guilar.shelfos.domain.library.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * The Cover Lab shows downloaded artwork through the production geometry (`coverLayout`, `artworkRect`, `coverSampleSize`), so this
 * pins the pilot's required shape x fit matrix against the fixture ratios. A presentation change is a pure function of the stored image's
 * size: none of it can request or re-encode anything.
 */
class CoverPilotGeometryTest {
    private val shapes: List<Pair<String, CoverPresentation?>> = listOf(
        "Original" to CoverPresentation(CoverShape.ORIGINAL, CoverFit.CROP),
        "2:3" to CoverPresentation(CoverShape.RATIO_2_3, CoverFit.CROP), "3:4" to CoverPresentation(CoverShape.RATIO_3_4, CoverFit.CROP),
        "4:5" to CoverPresentation(CoverShape.RATIO_4_5, CoverFit.CROP), "1:1" to CoverPresentation(CoverShape.RATIO_1_1, CoverFit.CROP),
        "5:8" to coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 5, 8), "16:9" to coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 16, 9),
        "1:2" to coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 1, 2), "2:1" to coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 2, 1),
    )

    /** width x height of the fixtures: paperback, tall manga, square, landscape, low-res, large, extremely narrow. */
    private val fixtures = mapOf("paperback" to (400 to 600), "manga" to (350 to 560), "square" to (500 to 500), "landscape" to (800 to 500),
        "lowres" to (64 to 96), "large" to (4000 to 6000), "narrow" to (10 to 400))

    private fun fit(p: CoverPresentation) = p.copy(fit = CoverFit.FIT)

    @Test fun `every custom preset is a valid ratio`() {
        shapes.forEach { (name, p) -> assertNotNull(name, p) }
        assertNull(coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 1, 3)); assertNull(coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 21, 20))
    }

    @Test fun `artwork is never stretched in any shape or fit`() {
        for ((shape, base) in shapes) for ((name, dim) in fixtures) for (p in listOf(base!!, fit(base))) {
            val aspect = dim.first.toFloat() / dim.second
            val layout = coverLayout(p, aspect)
            val frame = Size(240f * layout.frame, 240f)
            val r = artworkRect(frame, aspect, layout.crop, bottom = !layout.crop && !layout.neutralFill)
            assertEquals("$shape/$name/${p.fit}", aspect, r.width / r.height, 0.001f * aspect)
        }
    }

    @Test fun `crop fills the frame and fit keeps the whole image inside it`() {
        for ((shape, base) in shapes.drop(1)) for ((name, dim) in fixtures) {
            val aspect = dim.first.toFloat() / dim.second
            val frame = Size(base!!.aspect!! * 300f, 300f)
            val crop = coverLayout(base, aspect)
            val cr = artworkRect(frame, aspect, crop.crop)
            assertTrue("$shape/$name crop covers", cr.width >= frame.width - 0.5f && cr.height >= frame.height - 0.5f)
            val fitted = coverLayout(fit(base), aspect)
            assertFalse(fitted.crop); assertTrue("fit fills the unused space with the neutral surface", fitted.neutralFill)
            val fr = artworkRect(frame, aspect, fitted.crop)
            assertTrue("$shape/$name fit inside", fr.width <= frame.width + 0.5f && fr.height <= frame.height + 0.5f)
            assertTrue("$shape/$name fit touches one axis", abs(fr.width - frame.width) < 0.5f || abs(fr.height - frame.height) < 0.5f)
        }
    }

    @Test fun `original follows the native ratio inside the 1 to 2 clamp and never crops`() {
        val original = CoverPresentation(CoverShape.ORIGINAL, CoverFit.CROP)
        for ((name, dim) in fixtures) {
            val aspect = dim.first.toFloat() / dim.second
            val l = coverLayout(original, aspect)
            assertFalse(name, l.crop); assertEquals(name, clampCoverAspect(aspect), l.frame, 0.0001f)
            assertEquals(name, ORIGINAL_SLOT_ASPECT, l.slot, 0.0001f)
            assertTrue(l.frame in MIN_COVER_ASPECT..MAX_COVER_ASPECT)
        }
        assertEquals(MIN_COVER_ASPECT, coverLayout(original, 10f / 400f).frame, 0f)   // the extremely narrow image
    }

    @Test fun `the whole view keeps the complete artwork whatever the preference`() {
        for ((_, base) in shapes) for ((name, dim) in fixtures) {
            val aspect = dim.first.toFloat() / dim.second
            val l = coverLayout(base!!, aspect, whole = true)
            assertFalse(name, l.crop); assertEquals(clampCoverAspect(aspect), l.frame, 0.0001f)
        }
    }

    @Test fun `custom bounds are exactly 1 to 2 and 2 to 1`() {
        assertEquals(0.5f, coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 1, 2)!!.aspect!!, 0f)
        assertEquals(2f, coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 2, 1)!!.aspect!!, 0f)
        assertEquals(5f / 8, coverPresentation(CoverShape.CUSTOM, CoverFit.FIT, 5, 8)!!.aspect!!, 0f)
    }

    @Test fun `changing the shape is a pure layout change that needs the same decode`() {
        // The decode is keyed by stored file and sample size; the sample depends on the frame size, never on which shape produced it.
        val a = coverSampleSize(4000, 6000, 240, 360)
        assertEquals(a, coverSampleSize(4000, 6000, 240, 360))
        assertEquals(1, coverSampleSize(64, 96, 240, 360))      // low-resolution art is never upsampled by the decoder
        assertEquals(8, coverSampleSize(4000, 6000, 400, 600))  // large art is subsampled, bounded memory
        assertTrue(coverSampleSize(10, 400, 240, 360) >= 1)
    }
}
