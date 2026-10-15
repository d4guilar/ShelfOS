// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry

/**
 * With a text field focused on a real keyboard device the soft keyboard is up, and the first Android Back belongs to it (it
 * hides the keyboard and keeps the draft). Tests that mean "Back dismisses the editor" send that first Back explicitly, so they
 * exercise the real two-step contract instead of racing the keyboard. A no-op when no keyboard is showing. Also used before touching controls the keyboard would cover on a short window.
 */
internal fun ComposeTestRule.hideSoftKeyboardIfShown() {
    fun shown(): Boolean {
        return onAllNodes(isRoot()).fetchSemanticsNodes().any { node ->
            (node.root as? View)?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true
        }
    }
    if (!shown()) return
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    waitUntil(10_000) { !shown() }
}
