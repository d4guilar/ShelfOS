// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as ShelfApplication).container

    @Before fun seedOriginalFixtures() = runBlocking<Unit> {
        val context = instrumentation.targetContext
        listOf(OriginalFixtures.pdf(context), OriginalFixtures.epub(context), OriginalFixtures.cbz(context)).forEach { container.library.add(it) }
    }
    @After fun removeFixtures() = runBlocking<Unit> {
        listOf("test-pdf", "test-epub", "test-cbz").forEach { container.library.remove(it) }
        container.library.preferences("", "{}")
    }

    private fun awaitLibrary() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitTag(tag: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitPage(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** Opens a publication's details (compact) or detail pane (expanded) and starts reading. */
    private fun read(id: String) {
        awaitTag("publication_$id")
        compose.onNodeWithTag("publication_$id").performClick()
        compose.onNodeWithTag("read_action").performScrollTo().performClick()
    }

    @Test fun allDestinationsNavigateAndThemeSurvivesRecreation() {
        awaitLibrary()
        listOf("search", "notes", "shelves", "settings").forEach {
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

    @Test fun shelvesIsTheCanonicalDestinationName() {
        awaitLibrary()
        compose.onNodeWithTag("nav_shelves").assertTextContains("Shelves").performClick().assertIsSelected()
        compose.onAllNodesWithText("Collections", substring = true).assertCountEquals(0)
    }

    @Test fun keyboardFocusCanActivateNavigation() {
        awaitLibrary()
        enterKeyboardMode()
        compose.onNodeWithTag("nav_search").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("nav_search").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithTag("search_field").assertExists()
    }

    @Test fun publicationSelectionShowsDetails() {
        awaitLibrary()
        awaitTag("publication_test-pdf")
        compose.onNodeWithTag("publication_test-pdf").performClick()
        compose.onNodeWithTag("favorite_action").assertExists()
        compose.onNodeWithTag("read_action").performScrollTo().assertIsDisplayed()
    }

    private fun runShell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    /** The reader-entry cover transition (CLASSIC_UI.md §12) falls back to its documented no-motion behavior. */
    @Test fun readerEntryTransitionIsSkippedUnderReducedMotion() {
        runShell("settings put global animator_duration_scale 0")
        android.os.SystemClock.sleep(300) // Lets the setting change propagate before it is read.
        try {
            awaitLibrary()
            read("test-pdf")
            awaitPage("1 / 3")
        } finally {
            runShell("settings put global animator_duration_scale 1")
        }
    }

    @Test fun originalPdfOpensTurnsPagesResumesAndReturnsToLibrary() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 3")
        compose.onNodeWithTag("reader_library").performClick()
        // The saved position is visible in the Library before reopening: page 2 of 3.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("66%").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("read_action").performScrollTo().assertTextContains("Continue reading").performClick()
        awaitPage("2 / 3")
    }

    /**
     * Phase 2A fix: from hidden chrome, Back must reveal controls rather than silently exit the reader
     * (the field-reported bug). Only once controls are visible does Back leave the reader.
     */
    @Test fun backRevealsHiddenControlsBeforeLeavingTheReader() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Hide controls").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("page_number").fetchSemanticsNodes().isEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitPage("1 / 3") // Chrome reappeared; the reader is still open, not exited.
        compose.onNodeWithTag("reader_page").assertExists()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader_page").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("favorite_action").assertExists()
    }

    /**
     * Codex review R2: the "show controls" instruction TalkBack announces while chrome is hidden must be
     * backed by a real accessibility action, not just descriptive text. Invokes the semantic OnClick action
     * directly (not a raw tap) and confirms it reveals chrome without exiting the reader.
     */
    @Test fun accessibilityActionRevealsHiddenControlsInFixedReader() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Hide controls").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("page_number").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reader_page").performSemanticsAction(SemanticsActions.OnClick)
        awaitPage("1 / 3") // The accessibility action revealed chrome; the reader is still open.
        compose.onNodeWithTag("reader_page").assertExists()
    }

    /** Same accessibility-action contract as above, for the EPUB reader's chrome-toggle surface. */
    @Test fun accessibilityActionRevealsHiddenControlsInEpubReader() {
        awaitLibrary()
        read("test-epub")
        awaitTag("epub_reader", 30_000)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("epub_library").fetchSemanticsNodes().isNotEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("epub_library").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("epub_page").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("epub_library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("epub_reader").assertExists()
    }

    /** Same Back-reveals-before-leaving contract for the EPUB reader, toggled here via the OPEN_MENU key. */
    @Test fun epubBackRevealsHiddenControlsBeforeLeavingTheReader() {
        awaitLibrary()
        read("test-epub")
        awaitTag("epub_reader", 30_000)
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("epub_library").fetchSemanticsNodes().isNotEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("epub_library").fetchSemanticsNodes().isEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("epub_library").fetchSemanticsNodes().isNotEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("epub_reader").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("favorite_action").assertExists()
    }

    @Test fun mangaReadsRightToLeftWithKeyboardAndPageKeysStaySemantic() {
        awaitLibrary()
        compose.onNodeWithText("Manga").performClick()
        read("test-cbz")
        awaitPage("1 / 3")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitPage("2 / 3")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitPage("1 / 3")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
        awaitPage("2 / 3")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_L1)
        awaitPage("1 / 3")
    }

    @Test fun keyboardReturnFromDetailsRestoresFocusToThePublication() {
        awaitLibrary()
        awaitTag("publication_test-pdf")
        enterKeyboardMode()
        compose.onNodeWithTag("publication_test-pdf").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("publication_test-pdf").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        val compact = compose.onAllNodesWithTag("details_screen").fetchSemanticsNodes().isNotEmpty()
        if (compact) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
            awaitTag("library_grid")
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("publication_test-pdf") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun originalEpubOpensAndOffersTypographyAndChapters() {
        awaitLibrary()
        read("test-epub")
        awaitTag("epub_reader", 30_000)
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Clean").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithText("Chapters").performClick()
        compose.onNodeWithText("Reading Room").performClick()
        compose.onNodeWithTag("epub_library").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("epub_reader").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("favorite_action").assertExists()
    }

    @Test fun dpadMovesBetweenGlobalDestinations() {
        awaitLibrary()
        enterKeyboardMode()
        compose.onNodeWithTag("nav_library").performSemanticsAction(SemanticsActions.RequestFocus)
        val rail = compose.onAllNodesWithTag("navigation_rail").fetchSemanticsNodes().isNotEmpty()
        compose.onNodeWithTag("nav_library").performKeyInput {
            pressKey(if (rail) Key.DirectionDown else Key.DirectionRight)
        }
        compose.onNodeWithTag("nav_search").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("search_field").assertExists()
    }

    @Test fun fixedReaderZoomFitAndGlobalAppearancePersistAcrossRecreation() {
        awaitLibrary()
        read("test-pdf")
        awaitPage("1 / 3")
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("Reset zoom").assertExists()
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Fit width").performClick().assertIsSelected()
        compose.onNodeWithTag("appearance_scope").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Appearance").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Fit width") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.activityRule.scenario.recreate()
        awaitPage("1 / 3")
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Fit width").assertIsSelected()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun libraryScrollRestoresAfterReaderAndRapidTitleSwitchingKeepsTheRightSession() = runBlocking<Unit> {
        val base = OriginalFixtures.pdf(instrumentation.targetContext)
        val extraIds = (0 until 30).map { "scroll-$it" }
        try {
            extraIds.forEachIndexed { index, id ->
                container.library.add(base.copy(id = id, sourceUri = "test:$id", title = "Scroll title ${index.toString().padStart(2, '0')}", addedAt = index.toLong()))
            }
            awaitLibrary()
            // The last valid index: the 30 seeded items plus the two Book fixtures from @Before (test-pdf, test-epub),
            // in one grid alongside the "Continue Reading" carousel item that shares this LazyVerticalGrid's item count.
            compose.onNodeWithTag("library_grid").performScrollToIndex(extraIds.size + 2)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication_scroll-0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("publication_scroll-0").performScrollTo().performClick()
            compose.onNodeWithTag("read_action").performScrollTo().performClick()
            awaitPage("1 / 3")
            compose.onNodeWithText("Next").performClick()
            awaitPage("2 / 3")
            compose.onNodeWithTag("reader_library").performClick()
            if (compose.onAllNodesWithTag("details_screen").fetchSemanticsNodes().isNotEmpty()) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            awaitLibrary()
            compose.onNodeWithTag("publication_scroll-0").assertIsDisplayed()

            compose.onNodeWithTag("library_grid").performScrollToIndex(0)
            compose.onNodeWithText("Manga").performClick()
            read("test-cbz")
            awaitPage("1 / 3")
            compose.onNodeWithText("Next").performClick()
            awaitPage("2 / 3")
            compose.onNodeWithTag("reader_library").performClick()
            if (compose.onAllNodesWithTag("details_screen").fetchSemanticsNodes().isNotEmpty()) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            awaitLibrary()
            compose.onNodeWithTag("library_grid").performScrollToIndex(0)
            compose.onNodeWithText("Books").performClick()
            read("test-pdf")
            awaitPage("1 / 3")
            compose.onNodeWithTag("reader_library").performClick()
            if (compose.onAllNodesWithTag("details_screen").fetchSemanticsNodes().isNotEmpty()) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            awaitLibrary()
            compose.onNodeWithTag("library_grid").assertExists()
        } finally {
            extraIds.forEach { container.library.remove(it) }
        }
    }

    private fun enterKeyboardMode() {
        // Real key injection leaves Android touch mode before requesting focus.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        compose.waitForIdle()
    }
}
