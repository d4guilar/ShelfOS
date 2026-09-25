// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.feature.library.PublicationCover
import kotlin.math.roundToInt

/** CLASSIC_UI.md §12's signature transition duration: fast, not a generalized animation system's tunable. */
const val COVER_EXPAND_MILLIS = 220

/**
 * A reader-entry transition in progress: [item]'s cover grows from [start] (its on-screen position when chosen)
 * to fill the incoming reader's area. [proceed] is the navigation this transition is standing in for — a
 * NavHost route change or an Activity launch — run once the expansion completes.
 */
data class PendingCoverTransition(val item: LibraryItem, val start: Rect, val proceed: () -> Unit)

/**
 * How much the surrounding chrome (top bar, navigation rail/bar) has faded at [progress] through the
 * transition. Chrome finishes fading before the expansion itself does, so the reader is never revealed
 * through visible chrome.
 */
fun chromeAlpha(progress: Float): Float = (1f - progress / 0.6f).coerceIn(0f, 1f)

/**
 * Renders [transition]'s cover partway ([progress], 0f..1f) between its starting position and [target], the
 * incoming reader's on-screen area (window coordinates, matching [PendingCoverTransition.start]'s coordinate
 * space). Purely decorative and transient: excluded from the accessibility tree, since the real destination's
 * own content takes over as soon as the transition completes. The caller drives [progress] and decides when
 * that is (see [chromeAlpha], which shares the same value so chrome and cover never disagree).
 */
@Composable
fun CoverExpandOverlay(transition: PendingCoverTransition, target: Rect, progress: Float) {
    val rect = lerp(transition.start, target, progress)
    val density = LocalDensity.current
    Box(Modifier
        .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
        .clearAndSetSemantics { }
    ) { PublicationCover(transition.item, Modifier.fillMaxSize()) }
}
