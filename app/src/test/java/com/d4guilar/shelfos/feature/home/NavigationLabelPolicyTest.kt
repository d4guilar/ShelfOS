// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.home

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

/**
 * NAV-1: a label is drawn whole on one line or not at all; all labels show whenever their widths together fit (not only when each
 * fits a fifth); the widths are whole pixels that always add up to the bar.
 *
 * Label widths are real Compose measurements (labelSmall, wider of SemiBold and Normal, in px) taken on the API 35 emulator at
 * 2.75 (440 dpi) and 2.625 (420 dpi) density for the shipped EN / ES / PT-BR strings, in Destination order
 * Library, Search, Notes, Shelves, Settings.
 */
class NavigationLabelPolicyTest {
    /** Rounds dp to px the way Compose's roundToPx does. */
    private fun px(dp: Float, density: Float) = (dp * density).roundToInt()

    private class Screen(val density: Float) {
        val pad = (4f * density).roundToInt()
        val target = (48f * density).roundToInt()
    }
    private val d275 = Screen(2.75f)
    private val d2625 = Screen(2.625f)

    private val en1 = listOf(94, 94, 79, 105, 111)
    private val en15 = listOf(139, 139, 120, 158, 167)
    private val en2 = listOf(188, 187, 160, 211, 224)
    private val es15 = listOf(201, 140, 120, 175, 151)
    private val pt1 = listOf(135, 94, 79, 116, 197)
    private val pt15 = listOf(201, 140, 120, 175, 292)
    private val pt2 = listOf(269, 187, 161, 234, 391)
    private val pt1At2625 = listOf(125, 88, 74, 108, 184)
    private val en15At2625 = listOf(132, 134, 115, 151, 158)
    private val es15At2625 = listOf(190, 134, 115, 166, 145)

    private fun layout(s: Screen, barPx: Int, widths: List<Int>, selected: Int) = navLabelLayout(barPx, widths, selected, s.pad, s.target)

    /** Asserts every presentation invariant, and for all-label mode that each item keeps at least its label plus padding. */
    private fun check(tag: String, s: Screen, barPx: Int, widths: List<Int>, l: NavLabelLayout) {
        assertEquals("$tag: one width per destination", widths.size, l.itemWidths.size)
        assertEquals("$tag: widths fill the bar exactly", barPx, l.itemWidths.sum())
        if (l.mode == NavLabelMode.ALL) widths.forEachIndexed { i, w ->
            assertTrue("$tag: item $i (${l.itemWidths[i]}px) holds its label ($w px + 2 x ${s.pad}px)", l.itemWidths[i] - 2 * s.pad >= w)
            assertTrue("$tag: item $i is a full touch target", l.itemWidths[i] >= s.target)
        }
        if (l.mode == NavLabelMode.SELECTED_ONLY) assertTrue("$tag: every item a full touch target", l.itemWidths.all { it >= s.target })
    }

    private fun assertAllAtEverySelection(tag: String, s: Screen, barPx: Int, widths: List<Int>) {
        val geometry = (-1..4).map { sel ->
            layout(s, barPx, widths, sel).also { assertEquals("$tag selected $sel", NavLabelMode.ALL, it.mode); check("$tag selected $sel", s, barPx, widths, it) }.itemWidths
        }
        assertEquals("$tag: icons never move when the selection changes", 1, geometry.toSet().size)
    }

    // A: the QA reproduction. "Configurações" alone is wider than a fifth of 393 dp, which hid four labels in 7451484.
    @Test fun `PT-BR at 393 dp and font 1_0 shows every label`() {
        val bar = 1080 // the emulator's real 393 dp screen
        assertTrue("equal-fifth admission would have rejected this", pt1.any { it + 2 * d275.pad > bar / 5 })
        assertAllAtEverySelection("pt-BR 393", d275, bar, pt1)
        assertAllAtEverySelection("pt-BR 393 @420dpi", d2625, px(393f, 2.625f), pt1At2625)
    }

    // B
    @Test fun `PT-BR at 320 and 360 dp shows every label when the measured widths fit`() {
        for (w in listOf(320f, 360f)) assertAllAtEverySelection("pt-BR ${w}dp", d275, px(w, 2.75f), pt1)
        assertAllAtEverySelection("pt-BR 320dp @420dpi", d2625, px(320f, 2.625f), pt1At2625)
    }

    // C
    @Test fun `known excessive fallbacks now show every label`() {
        assertAllAtEverySelection("en 320dp x1.5", d275, px(320f, 2.75f), en15)
        assertAllAtEverySelection("en 320dp x1.5 @420dpi", d2625, px(320f, 2.625f), en15At2625)
        for (w in listOf(360f, 393f)) {
            assertAllAtEverySelection("es ${w}dp x1.5", d275, px(w, 2.75f), es15)
            assertAllAtEverySelection("es ${w}dp x1.5 @420dpi", d2625, px(w, 2.625f), es15At2625)
        }
        // EN x2.0 at 393 dp needs exactly 1080 px: it fits the emulator's 1080 px screen with nothing to spare.
        assertEquals(1080, en2.sumOf { navItemRequiredPx(it, d275.pad, d275.target) })
        assertAllAtEverySelection("en 393dp x2.0", d275, 1080, en2)
        assertAllAtEverySelection("en 320dp x1.0", d275, px(320f, 2.75f), en1)
        assertAllAtEverySelection("pt-BR 393dp x1.5", d275, 1080, pt15)
    }

    // D
    @Test fun `genuine overflow still falls back to the selected label, then icons only`() {
        val bar = px(360f, 2.75f)
        val selectedOnly = layout(d275, bar, pt15, 4)
        assertEquals(NavLabelMode.SELECTED_ONLY, selectedOnly.mode)
        assertEquals(292 + 2 * d275.pad, selectedOnly.itemWidths[4])
        check("pt-BR 360 x1.5 Settings", d275, bar, pt15, selectedOnly)

        val iconsOnly = layout(d275, px(320f, 2.75f), pt2, 4)
        assertEquals("Configurações at x2.0 leaves too little for four targets", NavLabelMode.NONE, iconsOnly.mode)
        check("pt-BR 320 x2.0 Settings", d275, px(320f, 2.75f), pt2, iconsOnly)
        assertTrue(iconsOnly.itemWidths.all { it >= d275.target })

        // With nothing selected there is no label to keep, so a bar that cannot show all shows none.
        assertEquals(NavLabelMode.NONE, layout(d275, bar, pt15, -1).mode)
        // One pixel short of the exact fit is no longer all-label mode.
        assertNotEquals(NavLabelMode.ALL, layout(d275, 1079, en2, 0).mode)
    }

    // E
    @Test fun `every selected state in a fallback keeps the selected label whole and never narrower than the rest`() {
        val bar = px(320f, 2.75f)
        for (sel in 0..4) {
            val l = layout(d275, bar, pt2, sel)
            check("pt-BR 320 x2.0 selected $sel", d275, bar, pt2, l)
            if (l.mode == NavLabelMode.SELECTED_ONLY) {
                assertTrue("selected $sel holds its label", l.itemWidths[sel] >= pt2[sel] + 2 * d275.pad)
                assertTrue("selected $sel is not the narrowest item", l.itemWidths.all { it <= l.itemWidths[sel] })
            }
        }
        // A short selected label gets an even share instead of shrinking to its own width, so the bar stays even.
        val even = px(360f, 2.75f)
        val notes = layout(d275, even, pt15, 2)
        assertEquals(NavLabelMode.SELECTED_ONLY, notes.mode)
        assertEquals(listOf(even / 5), notes.itemWidths.toSet().toList())
    }

    // F
    @Test fun `fractional densities never allocate less than a label needs`() {
        // At 420 dpi a 4 dp padding is 10.5 px and rounds to 11 per side; summing in dp first gave the label one pixel too few.
        val label = 184
        assertEquals(label + 21, px(label / 2.625f + 8f, 2.625f))
        assertEquals(11, d2625.pad)
        for (density in listOf(1f, 1.3312f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f)) {
            val s = Screen(density)
            for (dp in listOf(280, 320, 360, 393, 412)) for (seed in 0..40) {
                val widths = List(5) { (20 + (seed * 37 + it * 53) % 90) * density.toInt().coerceAtLeast(1) }
                val bar = px(dp.toFloat(), density)
                for (sel in -1..4) {
                    val l = layout(s, bar, widths, sel)
                    val tag = "$density ${dp}dp $widths sel $sel"
                    check(tag, s, bar, widths, l)
                    assertEquals("$tag: all labels exactly when they fit", widths.sumOf { navItemRequiredPx(it, s.pad, s.target) } <= bar, l.mode == NavLabelMode.ALL)
                }
            }
        }
    }

    @Test fun `spare width is shared evenly across items`() {
        val l = layout(d275, 1080, pt1, 0)
        val required = pt1.map { navItemRequiredPx(it, d275.pad, d275.target) }
        val extras = l.itemWidths.zip(required) { a, r -> a - r }
        assertTrue("extras differ by at most one pixel: $extras", extras.max() - extras.min() <= 1)
    }
}
