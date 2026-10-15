// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.feature.reader.AnnotationEditorDialog
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * The shared note editor on a short landscape screen: the keyboard must not take over the whole window (no extract UI), and
 * the first Back while the keyboard is up belongs to the keyboard, so an unsaved draft survives it.
 *
 * What this proves and what it does not: the no-extract flag is read from the real input connection Compose builds, on any
 * device. The Back test drives the device's real keyboard, so it runs only where that keyboard actually shows (API 30+, the
 * window-insets API that reports it); on a device or emulator without a visible keyboard it is skipped, not passed.
 */
class AnnotationEditorImeTest {
    @get:Rule val compose = createComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private var cancelled = 0
    private var saved: String? = null

    private fun show() = compose.setContent {
        ShelfTheme(ThemeId.CLASSIC) {
            AnnotationEditorDialog("ime-test", "New note", null, "", null, styleEditable = false, bodyRequired = true,
                onSave = { body, _ -> saved = body }, onDelete = null, onCancel = { cancelled++ })
        }
    }

    private fun dialogView(): View = compose.onNode(isDialog()).fetchSemanticsNode().root as View

    private fun imeVisible(): Boolean = ViewCompat.getRootWindowInsets(dialogView())?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test fun noteFieldAsksTheKeyboardNotToTakeOverTheScreen() {
        show()
        compose.onNodeWithTag("annotation_body").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("annotation_body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("annotation_body").performClick() // focus builds the real input connection
        var imeOptions = 0
        compose.waitUntil(10_000) {
            val view = dialogView()
            inst.runOnMainSync { imeOptions = EditorInfo().also { view.findFocus()?.onCreateInputConnection(it) }.imeOptions }
            imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0
        }
        assertTrue("IME_FLAG_NO_EXTRACT_UI must be set on the note field", imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
    }

    @Test fun firstBackWhileTheKeyboardIsUpHidesItAndKeepsTheDraftThenTheNextBackDismisses() {
        assumeTrue("needs the window-insets keyboard signal (API 30+)", Build.VERSION.SDK_INT >= 30)
        show()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("annotation_body").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("annotation_body").performClick().performTextInput("draft that must survive")
        val keyboardShown = runCatching { compose.waitUntil(10_000) { imeVisible() } }.isSuccess
        assumeTrue("the device keyboard did not show; nothing to prove here", keyboardShown)

        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { !imeVisible() }
        compose.waitForIdle()
        assertEquals("the first Back hid the keyboard only", 0, cancelled)
        compose.onNodeWithTag("annotation_body").assertExists()
        compose.onNodeWithTag("annotation_body").assertTextContains("draft that must survive")

        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { cancelled == 1 }
        assertNull("dismissing never saves the draft", saved)
    }
}
