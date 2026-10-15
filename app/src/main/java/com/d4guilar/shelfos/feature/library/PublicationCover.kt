// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.d4guilar.shelfos.domain.library.LibraryItem

/** A Library item's cover through the one shared renderer (local artwork, else the generated visual). */
@Composable
fun PublicationCover(item: LibraryItem, modifier: Modifier = Modifier) {
    com.d4guilar.shelfos.core.designsystem.PublicationCover(item.id, item.title, modifier)
}
