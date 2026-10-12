// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.annotations.KnowledgePolicy
import com.d4guilar.shelfos.feature.library.PublicationDetails
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4A disclosed what removal deletes; Phase 4D makes the knowledge choice explicit: with saved items there are two
 * distinct actions (keep / delete) and no implied default; without any, removal is the single unchanged action.
 * EN strings; ES/PT parity is checked by the localization policy test.
 */
class RemovalDialogKnowledgeTest {
    @get:Rule val compose = createComposeRule()
    private val item = OriginalFixtures.epub(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun show(counts: AnnotationCounts?, onRemove: (KnowledgePolicy) -> Unit = {}) {
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                PublicationDetails(item, false, {}, onRead = {}, onEdit = { _, _, _ -> }, onRemove = onRemove, knowledgeCounts = counts)
            }
        }
        compose.onNodeWithTag("remove_action").performScrollTo().performClick()
    }

    @Test fun noAnnotationsKeepsTheSimpleDialog() {
        var policy: KnowledgePolicy? = null
        show(AnnotationCounts.None) { policy = it }
        compose.onNodeWithText("Remove from ShelfOS?").assertExists()
        compose.onNodeWithText("The original file stays untouched.", substring = true).assertExists()
        compose.onAllNodesWithText("saved items", substring = true).assertCountEquals(0)
        compose.onAllNodesWithTag("confirm_remove_keep").assertCountEquals(0)
        compose.onAllNodesWithTag("confirm_remove_delete").assertCountEquals(0)
        compose.onNodeWithTag("confirm_remove").assertIsEnabled().performClick()
        assertEquals("nothing to keep, so this is the plain removal", KnowledgePolicy.DELETE, policy)
    }

    @Test fun countsAndBothConsequencesAreDisclosedWithPluralsAndZeroKindsAreOmitted() {
        show(AnnotationCounts(bookmarks = 1, highlights = 0, notes = 3, other = 2))
        compose.onNodeWithText("This publication has saved items in ShelfOS:").assertExists()
        compose.onNodeWithText("1 bookmark").assertExists()
        compose.onNodeWithText("3 notes").assertExists()
        compose.onNodeWithText("2 other saved items").assertExists()
        compose.onAllNodesWithText("highlight", substring = true).assertCountEquals(0)
        compose.onNodeWithText("The original file stays untouched.", substring = true).assertExists()
        compose.onNodeWithText("Keep notes:", substring = true).assertExists()
        compose.onNodeWithText("Delete notes:", substring = true).assertExists()
    }

    @Test fun withSavedItemsTheChoiceIsExplicitAndHasNoImpliedDefault() {
        var policy: KnowledgePolicy? = null
        show(AnnotationCounts(notes = 2)) { policy = it }
        compose.onAllNodesWithTag("confirm_remove").assertCountEquals(0)
        compose.onNodeWithTag("confirm_remove_keep").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithTag("confirm_remove_delete").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("Remove, keep notes").assertExists()
        compose.onNodeWithText("Remove, delete notes").assertExists()
        // Cancelling removes nothing and chooses nothing.
        compose.onNodeWithTag("cancel_remove").performClick()
        assertNull(policy)
        compose.onAllNodesWithText("Remove from ShelfOS?").assertCountEquals(0)
    }

    @Test fun keepAndDeleteEachReportTheirOwnPolicy() {
        var policy: KnowledgePolicy? = null
        show(AnnotationCounts(bookmarks = 2)) { policy = it }
        compose.onNodeWithTag("confirm_remove_keep").performClick()
        assertEquals(KnowledgePolicy.KEEP, policy)
        policy = null
        compose.onNodeWithTag("remove_action").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_remove_delete").performClick()
        assertEquals(KnowledgePolicy.DELETE, policy)
    }

    @Test fun gamepadConfirmActivatesTheFocusedChoiceOnly() {
        var policy: KnowledgePolicy? = null
        show(AnnotationCounts(notes = 1)) { policy = it }
        compose.onNodeWithTag("confirm_remove_keep").requestFocus()
        compose.onNodeWithTag("confirm_remove_keep").performKeyInput { pressKey(Key.ButtonA) }
        assertEquals(KnowledgePolicy.KEEP, policy)
    }

    @Test fun removalCannotBeConfirmedWhileTheCountIsStillUnknown() {
        var policy: KnowledgePolicy? = null
        show(null) { policy = it }
        compose.onNodeWithTag("confirm_remove").assertIsNotEnabled()
        compose.onNodeWithTag("confirm_remove").performClick()
        assertNull(policy)
    }
}
