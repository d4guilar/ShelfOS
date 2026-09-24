// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After
import kotlinx.coroutines.runBlocking

class NavigationSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun seedOriginalFixture() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        (context.applicationContext as ShelfApplication).container.library.add(OriginalFixtures.pdf(context))
        (context.applicationContext as ShelfApplication).container.library.add(OriginalFixtures.epub(context))
    }
    @After fun removeFixture() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        (context.applicationContext as ShelfApplication).container.library.remove("test-pdf")
        (context.applicationContext as ShelfApplication).container.library.remove("test-epub")
    }

    private fun awaitLibrary() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun allDestinationsNavigateAndThemeSurvivesRecreation() {
        awaitLibrary()
        listOf("search", "notes", "collections", "settings").forEach {
            compose.onNodeWithTag("nav_$it").performClick().assertIsSelected()
        }
        compose.onNodeWithTag("theme_dark").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("theme_dark") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("theme_dark").assertIsSelected()
        compose.onNodeWithTag("theme_classic").performClick()
        compose.onNodeWithTag("nav_library").performClick()
        compose.onNodeWithTag("library_grid").assertExists()
    }

    @Test fun keyboardFocusCanActivateNavigation() {
        awaitLibrary()
        enterKeyboardMode()
        compose.onNodeWithTag("nav_search").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        compose.onNodeWithTag("nav_search").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("search_field").assertExists()
    }

    @Test fun publicationSelectionShowsDetails() {
        awaitLibrary()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication_test-pdf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication_test-pdf").performClick()
        compose.onNodeWithTag("favorite_action").assertExists()
    }

    @Test fun originalPdfOpensTurnsPagesAndReturnsToLibrary() {
        awaitLibrary()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication_test-pdf").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication_test-pdf").performClick()
        compose.onNodeWithTag("read_action").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithTag("page_number").assertTextEquals("2 / 3")
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("favorite_action").assertExists()
    }

    @Test fun originalEpubOpensAndOffersTypographyAndChapters() {
        awaitLibrary()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication_test-epub").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication_test-epub").performClick()
        compose.onNodeWithTag("read_action").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("epub_reader").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Clean").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithText("Chapters").performClick()
        compose.onNodeWithText("Reading Room").performClick()
        compose.onNodeWithText("Library", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("favorite_action").assertExists()
    }

    @Test fun dpadMovesBetweenGlobalDestinations() {
        awaitLibrary()
        enterKeyboardMode()
        compose.onNodeWithTag("nav_library").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        val rail = compose.onAllNodesWithTag("navigation_rail").fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithTag("nav_library").performKeyInput {
            pressKey(if (rail) Key.DirectionDown else Key.DirectionRight)
        }
        compose.onNodeWithTag("nav_search").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("search_field").assertExists()
    }

    private fun enterKeyboardMode() {
        // Real key injection leaves Android touch mode before requesting focus.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
        compose.waitForIdle()
    }
}
