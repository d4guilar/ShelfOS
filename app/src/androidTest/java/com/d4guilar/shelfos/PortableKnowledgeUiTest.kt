// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PortableKnowledgeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun notesPortableActionsAndExportChoicesAreAccessibleWithoutImpossiblePublicationScope() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav_notes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_notes").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("knowledge_export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("knowledge_export").assertHasClickAction().assertIsEnabled()
        compose.onNodeWithTag("knowledge_import").assertHasClickAction().assertIsEnabled()
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(compose.onNodeWithTag("knowledge_export").fetchSemanticsNode().size.height >= 47 * density)
        compose.onNodeWithTag("knowledge_export").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("knowledge_export").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("knowledge_format_json").assertExists().assertIsSelected()
        compose.onNodeWithTag("knowledge_format_markdown").assertExists().performClick().assertIsSelected()
        compose.onNodeWithTag("knowledge_scope_all").assertExists()
        compose.onNodeWithTag("knowledge_scope_filter").assertExists()
        compose.onAllNodesWithTag("knowledge_scope_publication").assertCountEquals(0)
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithTag("knowledge_export_confirm").assertCountEquals(0)
    }
}
