// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.d4guilar.shelfos.core.reader.FoldRect
import com.d4guilar.shelfos.core.reader.ReaderCapabilities
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.feature.reader.ReaderAppearance
import com.d4guilar.shelfos.feature.reader.ThumbnailNavigator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Phase 3D Codex R1 remediation, finding 4: Appearance/Pages are not actually proven hinge-safe before this
 * remediation (a centered `AlertDialog`/`BasicAlertDialog` has no fold awareness at all). Mounts the REAL
 * production [ReaderAppearance]/[ThumbnailNavigator] composables directly (no [FixedReaderScreen] host needed --
 * `safePane` is a plain parameter of each), confirming the resulting
 * [HingeSafeDialogOverlay][com.d4guilar.shelfos.feature.reader.HingeSafeDialogOverlay] surface stays entirely
 * within the given safe pane and never crosses the simulated hinge, with its primary action reachable.
 */
class ReaderDialogFoldSafetyTest {
    @get:Rule val compose = createComposeRule()

    // A 1000x800 reader-local space with a vertical hinge band at x in [500, 520) -- the RIGHT pane (the one a
    // vertical fold's own chromePane/selectSoloPane would hand to these dialogs under a wide-right-pane layout).
    private val hingeLeft = 500f
    private val hingeRight = 520f
    private val rightPane = FoldRect(hingeRight, 0f, 1000f, 800f)
    private val leftPane = FoldRect(0f, 0f, hingeLeft, 800f) // the LEFT pane, as an RTL tie-break might select

    private fun capabilities() = ReaderCapabilities(typography = false, fit = true, zoom = true, direction = true, spread = true)

    @Test fun appearanceDialogStaysEntirelyInsideTheSelectedSafePaneUnderAVerticalFold() {
        compose.setContent {
            ReaderAppearance(ReaderPreferences.DEFAULT, capabilities(), onDismiss = {}, onApply = { _, _, _ -> }, onReset = {},
                safePane = rightPane)
        }
        compose.waitForIdle()
        val surface = compose.onNodeWithTag("hinge_safe_dialog_surface").fetchSemanticsNode().boundsInWindow
        assertTrue("dialog surface left edge must stay inside the selected safe pane", surface.left >= rightPane.left - 1f)
        assertTrue("dialog surface right edge must stay inside the selected safe pane", surface.right <= rightPane.right + 1f)
        assertFalse("dialog must never intersect the hinge band",
            surface.right > hingeLeft && surface.left < hingeRight)
        compose.onNodeWithTag("appearance_hinge_safe_apply").assertIsDisplayed() // the critical action stays reachable
    }

    @Test fun pagesDialogStaysEntirelyInsideTheSelectedSafePaneUnderAVerticalFold() {
        compose.setContent {
            ThumbnailNavigator(pageCount = 6, currentPage = 0, rtl = false, loader = null,
                onSelect = {}, onDismiss = {}, safePane = rightPane)
        }
        compose.waitForIdle()
        val surface = compose.onNodeWithTag("hinge_safe_dialog_surface").fetchSemanticsNode().boundsInWindow
        assertTrue("dialog surface left edge must stay inside the selected safe pane", surface.left >= rightPane.left - 1f)
        assertTrue("dialog surface right edge must stay inside the selected safe pane", surface.right <= rightPane.right + 1f)
        assertFalse("dialog must never intersect the hinge band",
            surface.right > hingeLeft && surface.left < hingeRight)
        compose.onNodeWithTag("thumbnail_close").assertIsDisplayed() // the critical action stays reachable
    }

    @Test fun appearanceDialogFollowsAnRtlSelectedLeftPaneInstead() {
        // Not re-testing selectSoloPane's own RTL tie-break (that's FoldLayoutTest's job) -- confirms THIS
        // dialog correctly follows whichever pane it is handed, including the opposite (left) side.
        compose.setContent {
            ReaderAppearance(ReaderPreferences.DEFAULT, capabilities(), onDismiss = {}, onApply = { _, _, _ -> }, onReset = {},
                safePane = leftPane)
        }
        compose.waitForIdle()
        val surface = compose.onNodeWithTag("hinge_safe_dialog_surface").fetchSemanticsNode().boundsInWindow
        assertTrue("dialog surface right edge must stay inside the LEFT pane", surface.right <= leftPane.right + 1f)
        assertFalse("dialog must never intersect the hinge band",
            surface.right > hingeLeft && surface.left < hingeRight)
    }

    @Test fun appearanceDialogWithNoFoldUsesTheOrdinaryUnconstrainedPlatformDialog() {
        // safePane == null (flat/no vertical fold): the ordinary AlertDialog path, never the hinge-safe overlay --
        // confirms the common, unfolded case is completely untouched by this remediation.
        compose.setContent {
            ReaderAppearance(ReaderPreferences.DEFAULT, capabilities(), onDismiss = {}, onApply = { _, _, _ -> }, onReset = {},
                safePane = null)
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("hinge_safe_dialog_surface").fetchSemanticsNodes().isEmpty())
    }
}
