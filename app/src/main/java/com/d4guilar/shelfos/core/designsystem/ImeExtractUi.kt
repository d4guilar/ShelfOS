// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/** Applies [IME_FLAG_NO_EXTRACT_UI] to every Compose text field inside [content]. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoImeExtractUi(content: @Composable () -> Unit) = InterceptPlatformTextInput({ request, session ->
    session.startInputMethod(object : PlatformTextInputMethodRequest {
        override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
            request.createInputConnection(outAttributes).also { outAttributes.imeOptions = outAttributes.imeOptions or IME_FLAG_NO_EXTRACT_UI }
    })
}, content)

internal const val IME_FLAG_NO_EXTRACT_UI = EditorInfo.IME_FLAG_NO_EXTRACT_UI
