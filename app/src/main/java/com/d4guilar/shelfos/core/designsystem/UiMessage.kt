// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.res.stringResource

/**
 * A user-facing message a ViewModel can hold without baking English text into its state, since a ViewModel has
 * no Composable scope to resolve a string resource in. [args] carries format values (numbers, already-resolved
 * strings) for a resource with placeholders. [Literal] is a narrow escape hatch for text that is already
 * resolved/locale-correct by the time it reaches here (for example a value `Context.getString` already
 * formatted in the active locale) rather than something this layer should resolve again.
 */
sealed interface UiMessage {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage
    data class Literal(val text: String) : UiMessage
}

@Composable
fun UiMessage.resolve(): String = when (this) {
    is UiMessage.Resource -> stringResource(id, *args.toTypedArray())
    is UiMessage.Literal -> text
}

/** Saves a [UiMessage] as plain values, the same pattern as ReaderPreferencesSaver, so it survives process
 * recreation inside `rememberSaveable`. */
val UiMessageSaver = Saver<UiMessage?, Any>(
    save = { message -> when (message) {
        null -> false
        is UiMessage.Resource -> listOf("resource", message.id, ArrayList(message.args))
        is UiMessage.Literal -> listOf("literal", message.text)
    } },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        (saved as? List<Any?>)?.let { entry -> when (entry.getOrNull(0)) {
            "resource" -> UiMessage.Resource(entry[1] as Int, entry[2] as List<Any>)
            "literal" -> UiMessage.Literal(entry[1] as String)
            else -> null
        } }
    },
)
