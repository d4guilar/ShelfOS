// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.d4guilar.shelfos.domain.library.LibraryItem

/** A Library item's cover through the one shared renderer, in the item's own category presentation. */
@Composable
fun PublicationCover(item: LibraryItem, modifier: Modifier = Modifier, frame: Modifier = Modifier, whole: Boolean = false) {
    com.d4guilar.shelfos.core.designsystem.PublicationCover(item.id, item.title, modifier, item.category, frame, whole)
}
