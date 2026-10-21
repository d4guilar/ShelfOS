// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.home

import androidx.compose.ui.unit.Dp

/** How much of each bottom-navigation label is drawn; the full name is always the accessibility label. */
internal enum class NavLabelMode {
    /** Every destination shows its label on one line. */
    ALL,

    /** Only the selected destination shows its label, in extra width taken from the others. */
    SELECTED_ONLY,

    /** Icons only; nothing fits without breaking a word. */
    NONE,
}

/** The bar's decision: a mode plus the per-item widths (same order as the labels) that make it work. */
internal data class NavLabelLayout(val mode: NavLabelMode, val itemWidths: List<Dp>)

/**
 * Chooses the richest label presentation whose labels each fit on ONE line, from the real measured label widths
 * ([labelWidths], already reflecting the locale and font scale) rather than any assumed string length or device. A word is
 * never split: labels either fit whole or are not drawn. [selectedIndex] is -1 when no destination is selected.
 */
internal fun navLabelLayout(available: Dp, labelWidths: List<Dp>, selectedIndex: Int, itemPadding: Dp, minTarget: Dp): NavLabelLayout {
    val count = labelWidths.size
    val equal = available / count
    if (labelWidths.all { it + itemPadding * 2 <= equal }) return NavLabelLayout(NavLabelMode.ALL, List(count) { equal })
    if (selectedIndex in labelWidths.indices) {
        val selected = maxOf(labelWidths[selectedIndex] + itemPadding * 2, minTarget)
        val other = (available - selected) / (count - 1)
        if (other >= minTarget) return NavLabelLayout(NavLabelMode.SELECTED_ONLY, List(count) { if (it == selectedIndex) selected else other })
    }
    return NavLabelLayout(NavLabelMode.NONE, List(count) { equal })
}
