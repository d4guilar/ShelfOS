// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.feature.library.PublicationDetails
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Phase 4A: the removal confirmation discloses how many saved items removal also deletes (EN strings; ES/PT parity is checked by the localization policy test). */
class RemovalDialogKnowledgeTest {
    @get:Rule val compose = createComposeRule()
    private val item = OriginalFixtures.epub(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun show(counts: AnnotationCounts?, onRemove: () -> Unit = {}) {
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                PublicationDetails(item, false, {}, onRead = {}, onEdit = { _, _, _ -> }, onRemove = onRemove, knowledgeCounts = counts)
            }
        }
        compose.onNodeWithTag("remove_action").performScrollTo().performClick()
    }

    @Test fun noAnnotationsKeepsTheSimpleDialog() {
        show(AnnotationCounts.None)
        compose.onNodeWithText("Remove from ShelfOS?").assertExists()
        compose.onAllNodesWithText("also permanently deletes", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("confirm_remove").assertIsEnabled()
    }

    @Test fun countsAreDisclosedWithPluralsAndZeroKindsAreOmitted() {
        show(AnnotationCounts(bookmarks = 1, highlights = 0, notes = 3, other = 2))
        compose.onNodeWithText("This also permanently deletes your saved items for this publication:").assertExists()
        compose.onNodeWithText("1 bookmark").assertExists()
        compose.onNodeWithText("3 notes").assertExists()
        compose.onNodeWithText("2 other saved items").assertExists()
        compose.onAllNodesWithText("highlight", substring = true).assertCountEquals(0)
    }

    @Test fun removalCannotBeConfirmedWhileTheCountIsStillUnknown() {
        var removed = false
        show(null) { removed = true }
        compose.onNodeWithTag("confirm_remove").assertIsNotEnabled()
        compose.onNodeWithTag("confirm_remove").performClick()
        assertFalse(removed)
    }

    @Test fun confirmingWithCountsRemoves() {
        var removed = false
        show(AnnotationCounts(bookmarks = 2)) { removed = true }
        compose.onNodeWithTag("confirm_remove").performClick()
        assertTrue(removed)
    }
}
