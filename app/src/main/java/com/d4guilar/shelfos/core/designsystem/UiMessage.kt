// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A user-facing message a ViewModel can hold without baking English text into its state, since a ViewModel has
 * no Composable scope to resolve a string resource in. [Literal] is a narrow escape hatch for the rare specific
 * detail a deep file/archive-parsing failure carries (see PublicationException's override message) — threading
 * Android resource access into that otherwise pure, low-level parsing code is out of scope for this pass; see
 * the localization foundation handoff's intentional-exceptions list.
 */
sealed interface UiMessage {
    data class Resource(@StringRes val id: Int) : UiMessage
    data class Literal(val text: String) : UiMessage
}

@Composable
fun UiMessage.resolve(): String = when (this) {
    is UiMessage.Resource -> stringResource(id)
    is UiMessage.Literal -> text
}
