// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.debug

import com.d4guilar.shelfos.AppContainer
import com.d4guilar.shelfos.debug.coverlab.CoverLabSession
import com.d4guilar.shelfos.debug.coverlab.DebugCoverLabEntry
import com.d4guilar.shelfos.feature.library.CoverLabEntry

private var entry: DebugCoverLabEntry? = null

/**
 * Debug builds only (Phase 4.5C.2B, D1-C-DEV): the Cover Lab entry. One per process, so its per-Book state outlives an activity recreation.
 * The release source set returns null here.
 */
@Synchronized
fun coverLabEntry(container: AppContainer): CoverLabEntry? = entry ?: DebugCoverLabEntry(CoverLabSession.create(container)).also { entry = it }
