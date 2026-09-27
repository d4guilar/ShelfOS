// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

/**
 * A discrete, user-created saved reading location — distinct from [LibraryItem.locator], the automatic resume
 * position. [locator] (a serialized Readium `Locator`) is the sole authoritative source of where a bookmark
 * points; [progress] is a display/sort snapshot taken at creation time only, never a second source of truth to
 * reconcile against the locator. [label] is reserved for future user labeling; this phase never sets or edits it.
 */
data class Bookmark(
    val id: String, val itemId: String, val locator: String, val progress: Int,
    val label: String? = null, val createdAt: Long = System.currentTimeMillis(),
)
