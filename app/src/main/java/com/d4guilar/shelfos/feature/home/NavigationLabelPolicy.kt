// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.home

/** How much of each bottom-navigation label is drawn; the full name is always the accessibility label. */
internal enum class NavLabelMode {
    /** Every destination shows its label on one line. */
    ALL,

    /** Only the selected destination shows its label, in extra width taken from the others. */
    SELECTED_ONLY,

    /** Icons only; nothing fits without breaking a word. */
    NONE,
}

/** The bar's decision: a mode plus the per-item widths in whole pixels (same order as the labels), summing to the bar. */
internal data class NavLabelLayout(val mode: NavLabelMode, val itemWidths: List<Int>)

/**
 * Pixels an item needs to draw a label [labelPx] wide whole, never below the touch target. Everything is whole pixels:
 * [labelPx] is the measured width already rounded up, and [paddingPx] is rounded exactly as `Modifier.padding` rounds it, so
 * an item given this width leaves its text at least [labelPx] (summing dp first can lose a pixel at fractional densities).
 */
internal fun navItemRequiredPx(labelPx: Int, paddingPx: Int, minTargetPx: Int): Int = maxOf(minTargetPx, labelPx + paddingPx * 2)

/**
 * Chooses the richest label presentation whose labels each fit on ONE line, from the real measured label widths in pixels
 * ([labelWidths], already reflecting the locale and font scale) rather than any assumed string length or device. A word is
 * never split: labels either fit whole or are not drawn. [selectedIndex] is -1 when no destination is selected.
 *
 * All labels show whenever their required widths together fit the bar, however unequal they are; the spare pixels are shared
 * evenly. Those widths ignore the selection, so changing tabs never moves an icon in that mode.
 */
internal fun navLabelLayout(available: Int, labelWidths: List<Int>, selectedIndex: Int, paddingPx: Int, minTargetPx: Int): NavLabelLayout {
    val count = labelWidths.size
    val required = labelWidths.map { navItemRequiredPx(it, paddingPx, minTargetPx) }
    if (required.sum() <= available) {
        val spare = split(available - required.sum(), count)
        return NavLabelLayout(NavLabelMode.ALL, required.mapIndexed { i, w -> w + spare[i] })
    }
    if (selectedIndex in labelWidths.indices) {
        // Never narrower than an equal share: a short selected label then keeps the geometry of an even bar.
        val selected = maxOf(required[selectedIndex], available / count)
        val others = split(available - selected, count - 1)
        if (others.all { it >= minTargetPx }) {
            return NavLabelLayout(NavLabelMode.SELECTED_ONLY, List(count) { if (it == selectedIndex) selected else others[if (it < selectedIndex) it else it - 1] })
        }
    }
    return NavLabelLayout(NavLabelMode.NONE, split(available, count))
}

/** [total] pixels in [parts] whole shares that differ by at most one; the extra pixels go to the first shares. */
private fun split(total: Int, parts: Int): List<Int> = List(parts) { total / parts + if (it < total % parts) 1 else 0 }
