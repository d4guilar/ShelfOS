// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

/** NAV-1: a label is drawn whole on one line or not at all; the widths always add up to the bar. */
class NavigationLabelPolicyTest {
    private val pad = 4.dp
    private val target = 48.dp
    private fun layout(bar: Float, widths: List<Float>, selected: Int) = navLabelLayout(bar.dp, widths.map { it.dp }, selected, pad, target)

    @Test fun `short labels all fit on a comfortable bar`() {
        val l = layout(393f, listOf(40f, 36f, 32f, 44f, 48f), 0)
        assertEquals(NavLabelMode.ALL, l.mode)
        assertEquals(5, l.itemWidths.size)
    }

    @Test fun `one long label on a narrow bar shows only the selected label`() {
        val l = layout(320f, listOf(45f, 40f, 32f, 44f, 78f), 4)
        assertEquals(NavLabelMode.SELECTED_ONLY, l.mode)
        assertEquals(78f + 8f, l.itemWidths[4].value, 0.01f)
        assertTrue(l.itemWidths.all { it >= target })
        assertEquals(320f, l.itemWidths.sumOf { it.value.toDouble() }.toFloat(), 0.01f)
    }

    @Test fun `a selected label that cannot fit leaves icons only`() {
        val l = layout(320f, listOf(45f, 40f, 32f, 44f, 150f), 4)
        assertEquals(NavLabelMode.NONE, l.mode)
        assertTrue(l.itemWidths.all { it >= target })
    }

    @Test fun `no selection never hides behind selected-only`() {
        assertEquals(NavLabelMode.NONE, layout(320f, listOf(45f, 40f, 32f, 44f, 78f), -1).mode)
    }
}
