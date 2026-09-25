// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.library.ReadingDirection
import com.d4guilar.shelfos.feature.reader.ReaderAppearance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Appearance editor shared by the fixed-layout and EPUB readers. */
class AppearanceRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unappliedChangesTheirBaselineAndScopeSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        var committed by mutableStateOf(ReaderPreferences.DEFAULT)
        var applied: Triple<ReaderPreferences, ReaderPreferences, Boolean>? = null
        restoration.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                ReaderAppearance(committed, capabilities(PublicationFormat.PDF), onDismiss = {},
                    onApply = { before, after, globally -> applied = Triple(before, after, globally) }, onReset = {})
            }
        }
        compose.onNodeWithText("Fit width").performScrollTo().performClick()
        compose.onNodeWithText("Right to left").performScrollTo().performClick()
        compose.onNodeWithTag("appearance_scope").performScrollTo().performClick()
        // Committed values change while the editor is open; Apply must still compare against what the user started from.
        committed = ReaderPreferences.DEFAULT.copy(fontSize = 1.4)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Fit width").assertIsSelected()
        compose.onNodeWithText("Right to left").assertIsSelected()
        compose.onNodeWithTag("appearance_scope").assertIsOn()
        compose.onNodeWithText("Apply").performClick()
        assertEquals(Triple(ReaderPreferences.DEFAULT, ReaderPreferences.DEFAULT.copy(fit = FitMode.WIDTH, direction = ReadingDirection.RTL), true),
            applied)
    }
}
