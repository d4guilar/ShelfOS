// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.d4guilar.shelfos.domain.library.LibraryItem

/**
 * The only seam the production UI has to the debug-only Cover Lab (Phase 4.5C.2B, D1-C-DEV). It is null unless the debug source set
 * provides an entry (`coverLabEntry`, whose release counterpart returns null), so a release build shows no Cover Lab action and has
 * no code path that can request provider artwork.
 */
interface CoverLabEntry {
    /** The details-pane action for [item] and the dialog it opens. Never starts a request by itself. */
    @Composable fun Action(item: LibraryItem)
}

val LocalCoverLab = staticCompositionLocalOf<CoverLabEntry?> { null }
