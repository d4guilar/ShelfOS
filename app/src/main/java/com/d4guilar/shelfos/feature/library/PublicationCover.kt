// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.d4guilar.shelfos.core.designsystem.PublicationCoverVisual
import com.d4guilar.shelfos.domain.library.LibraryItem

/** Original geometric artwork rendered locally. Never extracts reference-image artwork. */
@Composable
fun PublicationCover(item: LibraryItem, modifier: Modifier = Modifier) {
    PublicationCoverVisual(item.id, item.title, modifier)
}
