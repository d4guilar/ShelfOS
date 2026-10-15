// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.library.MediaCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4C.1: fixed-layout bookmarks, page notes, strict annotation navigation and the reader Notebook, driven through
 * the real [MainActivity] against the real PDF and CBZ readers (synthetic, license-clean fixtures). CBR is covered by
 * [FixedReaderCbrAnnotationHostedTest] because no RAR-writing tooling exists in this project.
 */
class FixedReaderAnnotationsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container
    private val seeded = mutableListOf<String>()

    @Before fun seedOriginalFixtures() = runBlocking<Unit> {
        listOf(OriginalFixtures.pdf(context), OriginalFixtures.cbz(context)).forEach { container.library.add(it); seeded += it.id }
    }
    @After fun removeFixtures() = runBlocking<Unit> {
        seeded.forEach { container.annotations.deleteForPublication(it, KnowledgePolicy.DELETE); container.library.remove(it) }
        container.library.preferences("", "{}")
    }

    private fun awaitLibrary() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitTag(tag: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitNoTag(tag: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    private fun awaitPage(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun read(id: String, category: MediaCategory = MediaCategory.BOOK) {
        awaitLibrary()
        when (category) {
            MediaCategory.MANGA -> compose.onNodeWithText("Manga").performClick()
            MediaCategory.COMIC -> compose.onNodeWithText("Comics").performClick()
            else -> Unit
        }
        awaitTag("publication_$id")
        compose.onNodeWithTag("publication_$id").performClick()
        compose.onNodeWithTag("read_action").performScrollTo().performClick()
        awaitTag("page_number")
        compose.waitForIdle()
    }

    private fun stored(id: String): List<Annotation> = runBlocking { container.annotations.observeForPublication(id).first().annotations }
    private fun awaitStored(id: String, count: Int) = compose.waitUntil(10_000) { stored(id).size == count }
    private fun seed(id: String, kind: AnnotationKind, page: Int, count: Int, body: String? = null, locator: String? = null): Annotation = runBlocking {
        val draft = fixedPageDraft(id, kind, page, count, 0, body)
        container.annotations.create(if (locator == null) draft else draft.copy(locatorJson = locator), validateLocator = locator == null)
    }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); compose.waitForIdle() }
    private fun gamepadKey(code: Int) {
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        instrumentation.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        compose.waitForIdle()
    }
    private fun isFocused(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true

    private fun openNotebook() {
        compose.onNodeWithTag("reader_notebook").performScrollTo().performClick()
        awaitTag("notebook_panel")
    }
    private fun rowNodes() = compose.onAllNodes(hasTestTag("notebook_row"))
    private fun rowClickable(text: String) = compose.onNode(hasAnyAncestor(hasTestTag("notebook_list")) and hasClickAction() and hasText(text, substring = true))
    private fun rowByText(text: String) = compose.onNode(hasAnyAncestor(hasTestTag("notebook_list")) and hasText(text, substring = true))
    private fun nextPage() { compose.onNodeWithText("Next").performClick() }
    private fun isBookmarkSelected(): Boolean = compose.onNodeWithTag("reader_bookmark").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    // ---- A / B: bookmark create at the exact page, toggle removes, recreation keeps ----

    @Test fun pdfBookmarkIsCreatedAtTheExactPageToggledOffAndSurvivesRecreation() {
        read("test-pdf")
        nextPage(); awaitPage("2 / 3")
        compose.onNodeWithTag("reader_bookmark").assertTextContains("Page 2", substring = true)
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        awaitStored("test-pdf", 1)
        val bookmark = stored("test-pdf").single()
        assertEquals(AnnotationKind.BOOKMARK, bookmark.kind); assertEquals(LocatorFormat.FIXED_PAGE_1, bookmark.locatorFormat)
        assertEquals("{\"version\":1,\"page\":1}", bookmark.locatorJson)
        assertNull(bookmark.body); assertNull(bookmark.selectedText); assertNull(bookmark.styleKey); assertNull(bookmark.title)
        compose.waitUntil(5_000) { isBookmarkSelected() }
        Thread.sleep(800)
        compose.activityRule.scenario.recreate()
        awaitPage("2 / 3")
        compose.waitUntil(5_000) { isBookmarkSelected() }
        assertEquals(1, stored("test-pdf").size)
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        awaitStored("test-pdf", 0)
        compose.waitUntil(5_000) { !isBookmarkSelected() }
    }

    @Test fun cbzBookmarkCreateAndStrictJumpUseTheSameSharedPath() {
        seed("test-cbz", AnnotationKind.BOOKMARK, 2, 3)
        read("test-cbz", MediaCategory.MANGA)
        nextPage(); awaitPage("2 / 3")
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        awaitStored("test-cbz", 2)
        assertEquals(listOf(1, 2), stored("test-cbz").map { FixedPageLocator.parse(it.locatorJson) })
        openNotebook()
        rowClickable("Page 3").performClick()
        awaitNoTag("notebook_panel"); awaitPage("3 / 3")
    }

    // ---- C / D: keyboard B toggles; gamepad B is Back and never toggles; gamepad A confirms the focused control ----

    @Test fun keyboardBTogglesTheBookmarkOnTheCurrentSourcePage() {
        read("test-pdf")
        nextPage(); awaitPage("2 / 3")
        key(KeyEvent.KEYCODE_B)
        awaitStored("test-pdf", 1)
        assertEquals(1, FixedPageLocator.parse(stored("test-pdf").single().locatorJson))
        compose.waitUntil(5_000) { isBookmarkSelected() }
        key(KeyEvent.KEYCODE_B)
        awaitStored("test-pdf", 0)
    }

    @Test fun gamepadBIsBackAndNeverTogglesWhileGamepadAActivatesTheFocusedBookmarkControl() {
        read("test-pdf")
        // Follow the user's real route from the reader page into the chrome. Focus traversal scrolls the compact
        // horizontal row until the initially off-screen Bookmark control is visible and focused.
        compose.waitUntil(5_000) { isFocused("reader_page") }
        gamepadKey(KeyEvent.KEYCODE_DPAD_UP)
        repeat(8) {
            if (isFocused("reader_bookmark")) return@repeat
            gamepadKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        }
        compose.onNodeWithTag("reader_bookmark").assertIsFocused()
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        awaitStored("test-pdf", 1)
        compose.waitUntil(5_000) { isBookmarkSelected() }
        // Gamepad B is the existing semantic BACK: with chrome visible it leaves the reader and must not touch bookmarks.
        key(KeyEvent.KEYCODE_BUTTON_B)
        awaitTag("details_screen")
        assertEquals(1, stored("test-pdf").size)
    }

    // ---- E / F: notes anchor to the current page; editing elsewhere keeps the original anchor ----

    @Test fun addNoteAnchorsToTheCurrentSourcePageAndNeverAutoSavesAnAbandonedDraft() {
        read("test-pdf")
        nextPage(); awaitPage("2 / 3")
        openNotebook()
        compose.onNodeWithTag("notebook_add_note").assertTextContains("page 2", substring = true).performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_editor_save").assertIsNotEnabled() // blank body cannot be saved
        compose.onNodeWithTag("annotation_body").performTextInput("abandoned")
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag("annotation_body").assertTextContains("abandoned")
        compose.onNodeWithTag("annotation_editor_save").assertIsFocused()
        compose.onNodeWithTag("annotation_body").assertExists()
        assertEquals("the first A only moves focus and never saves", 0, stored("test-pdf").size)
        gamepadKey(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("annotation_editor_cancel").assertIsFocused()
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        awaitNoTag("annotation_body")
        assertEquals("a cancelled draft is never saved", 0, stored("test-pdf").size)
        compose.onNodeWithTag("notebook_add_note").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").performTextInput("Page two thought")
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag("annotation_editor_save").assertIsFocused()
        assertEquals("focus transfer is not persistence", 0, stored("test-pdf").size)
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        awaitStored("test-pdf", 1)
        val note = stored("test-pdf").single()
        assertEquals(AnnotationKind.NOTE, note.kind); assertEquals("Page two thought", note.body)
        assertEquals("{\"version\":1,\"page\":1}", note.locatorJson)
        assertNull(note.selectedText); assertNull(note.styleKey); assertNull(note.title)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("notebook_row")).fetchSemanticsNodes().size == 1 }
        rowByText("Page two thought").assertExists()
    }

    @Test fun editingAnOldNoteWhileReadingElsewhereKeepsItsOriginalAnchor() {
        val old = seed("test-pdf", AnnotationKind.NOTE, 0, 3, "Old body")
        read("test-pdf")
        nextPage(); nextPage(); awaitPage("3 / 3")
        openNotebook()
        compose.onNodeWithTag("notebook_edit").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").assertTextContains("Old body")
        compose.onNodeWithTag("annotation_body").performTextClearance()
        compose.onNodeWithTag("annotation_body").performTextInput("New body")
        compose.onNodeWithTag("annotation_editor_save").performClick()
        compose.waitUntil(5_000) { stored("test-pdf").single().body == "New body" }
        val edited = stored("test-pdf").single()
        assertEquals(old.locatorJson, edited.locatorJson); assertEquals(old.createdAt, edited.createdAt)
        assertEquals(AnnotationKind.NOTE, edited.kind); assertEquals(old.libraryItemId, edited.libraryItemId)
        awaitPage("3 / 3") // editing never moves the reader
    }

    // ---- G / H / I: strict navigation ----

    @Test fun validRowJumpsToTheExactPageAndClosesTheNotebook() {
        seed("test-pdf", AnnotationKind.BOOKMARK, 2, 3)
        read("test-pdf")
        openNotebook()
        rowClickable("Page 3").performClick()
        awaitNoTag("notebook_panel"); awaitPage("3 / 3")
    }

    @Test fun outOfRangeAnnotationNeverNavigatesClampsOrDisappears() {
        val far = seed("test-pdf", AnnotationKind.NOTE, 3, 3, "Beyond the end") // page index 3 of a 3-page publication
        val farther = seed("test-pdf", AnnotationKind.BOOKMARK, 9999, 3)
        read("test-pdf")
        nextPage(); awaitPage("2 / 3")
        openNotebook()
        compose.onAllNodesWithTag("notebook_unavailable", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("Saved on page 4. This publication now ends at page 3.", substring = true).assertExists()
        rowByText("Beyond the end").assertIsNotEnabled()
        rowByText("Page 10000").assertIsNotEnabled()
        compose.onNodeWithTag("notebook_edit").assertExists() // the note stays editable
        compose.onAllNodesWithTag("notebook_delete").assertCountEquals(2)
        awaitPage("2 / 3") // no movement, no clamp to the last page
        assertEquals(far.locatorJson, stored("test-pdf").first { it.id == far.id }.locatorJson)
        assertEquals(farther.locatorJson, stored("test-pdf").first { it.id == farther.id }.locatorJson)
    }

    @Test fun malformedLocatorIsListedUnavailableDeletableAndNeverRewritten() {
        val badNote = seed("test-pdf", AnnotationKind.NOTE, 0, 3, "Orphaned note", locator = "{\"version\":1}")
        val badMark = seed("test-pdf", AnnotationKind.BOOKMARK, 0, 3, locator = "{\"version\":1,\"page\":-4}")
        read("test-pdf")
        openNotebook()
        compose.onAllNodesWithTag("notebook_unavailable", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("Page location unavailable", substring = true).assertCountEquals(2)
        rowByText("Orphaned note").assertIsNotEnabled()
        compose.onNodeWithTag("notebook_edit").assertExists()
        awaitPage("1 / 3")
        assertEquals("{\"version\":1}", stored("test-pdf").first { it.id == badNote.id }.locatorJson)
        compose.onAllNodesWithTag("notebook_delete")[1].performClick() // the bookmark deletes directly
        awaitStored("test-pdf", 1)
        assertEquals(badNote.id, stored("test-pdf").single().id)
        assertTrue(stored("test-pdf").none { it.id == badMark.id })
    }

    // ---- Notebook UI: filters, empty state, delete confirmation, current publication only ----

    @Test fun notebookFiltersEmptyStateAndDeleteConfirmationWorkAndShowOnlyThisPublication() {
        seed("test-pdf", AnnotationKind.NOTE, 0, 3, "Pdf note")
        seed("test-pdf", AnnotationKind.BOOKMARK, 1, 3)
        seed("test-cbz", AnnotationKind.NOTE, 0, 3, "Other publication note")
        read("test-pdf")
        openNotebook()
        rowNodes().assertCountEquals(2)
        compose.onNodeWithText("Other publication note").assertDoesNotExist()
        compose.onNodeWithTag("notebook_filter_notes").performClick()
        rowNodes().assertCountEquals(1)
        compose.onNodeWithTag("notebook_filter_bookmarks").performClick()
        rowNodes().assertCountEquals(1)
        compose.onNodeWithTag("notebook_filter_highlights").assertDoesNotExist() // no disabled Highlights chip in fixed readers
        compose.onNodeWithTag("notebook_filter_all").performClick()
        // Deleting a note needs an explicit confirmation; Cancel keeps it.
        compose.onAllNodesWithTag("notebook_delete")[0].performClick()
        awaitTag("annotation_delete_confirm")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, stored("test-pdf").size)
        compose.onAllNodesWithTag("notebook_delete")[0].performClick()
        awaitTag("annotation_delete_confirm")
        compose.onNodeWithTag("annotation_delete_confirm").performClick()
        awaitStored("test-pdf", 1)
        // The bookmark removes directly, then the empty state shows.
        compose.onNodeWithTag("notebook_delete").performClick()
        awaitStored("test-pdf", 0)
        awaitTag("notebook_empty")
        assertEquals("the other publication is untouched", 1, stored("test-cbz").size)
    }

    // ---- Recreation: Notebook, draft, no duplicates ----

    @Test fun rotationKeepsTheOpenNotebookAndTheUnsavedDraftAndNeverDuplicatesTheNote() {
        read("test-pdf")
        nextPage(); awaitPage("2 / 3")
        openNotebook()
        compose.activityRule.scenario.recreate()
        awaitTag("notebook_panel")
        awaitPage("2 / 3")
        compose.onNodeWithTag("notebook_add_note").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").performTextInput("Draft survives")
        compose.activityRule.scenario.recreate()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").assertTextContains("Draft survives")
        assertEquals("nothing is saved by recreation", 0, stored("test-pdf").size)
        compose.onNodeWithTag("annotation_editor_save").performClick()
        awaitStored("test-pdf", 1)
        compose.activityRule.scenario.recreate()
        awaitTag("notebook_panel")
        compose.activityRule.scenario.recreate()
        awaitTag("notebook_panel")
        Thread.sleep(500)
        assertEquals("no duplicate after repeated recreation", 1, stored("test-pdf").size)
        assertEquals(1, FixedPageLocator.parse(stored("test-pdf").single().locatorJson))
    }

    @Test fun committedAnnotationsSurviveLeavingAndReopeningTheReader() {
        seed("test-pdf", AnnotationKind.NOTE, 1, 3, "Kept")
        read("test-pdf")
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        awaitStored("test-pdf", 2)
        key(KeyEvent.KEYCODE_BACK); awaitTag("details_screen")
        key(KeyEvent.KEYCODE_BACK); awaitLibrary()
        read("test-pdf")
        openNotebook()
        rowNodes().assertCountEquals(2)
        assertEquals(2, stored("test-pdf").size)
    }

    // ---- Back / Escape / gamepad B (ADR-0023): editor -> Notebook -> chrome -> exit ----

    @Test fun backEscapeAndGamepadBDismissTheNotebookEquivalentlyAndStayInTheReader() {
        read("test-pdf")
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B)) {
            openNotebook()
            key(code)
            awaitNoTag("notebook_panel")
            compose.onNodeWithTag("reader_screen").assertExists()
        }
    }

    @Test fun escapeAndGamepadBWithAFocusedEditorFieldCloseOnlyTheEditorThenTheNotebook() {
        read("test-pdf")
        for (code in listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK)) {
            openNotebook()
            compose.onNodeWithTag("notebook_add_note").performClick()
            awaitTag("annotation_body")
            compose.waitForIdle()
            if (code == KeyEvent.KEYCODE_BACK) compose.hideSoftKeyboardIfShown() // the keyboard's own Back comes first
            key(code)
            awaitNoTag("annotation_body")
            compose.onNodeWithTag("notebook_panel").assertExists() // the first press closed only the editor
            assertEquals(0, stored("test-pdf").size)
            key(code)
            awaitNoTag("notebook_panel")
            compose.onNodeWithTag("reader_screen").assertExists()
        }
    }

    @Test fun theLetterBTypedInANoteNeverTogglesABookmark() {
        read("test-pdf")
        openNotebook()
        compose.onNodeWithTag("notebook_add_note").performClick()
        awaitTag("annotation_body")
        compose.waitForIdle()
        key(KeyEvent.KEYCODE_B); key(KeyEvent.KEYCODE_B)
        assertEquals("B typed into the editor must reach the text field only", 0, stored("test-pdf").size)
        compose.onNodeWithTag("annotation_body").assertTextContains("bb", ignoreCase = true)
    }

    // ---- J / K / L: page identity ----

    @Test fun spreadAnchorsToTheAuthoritativeStatePageAndThumbnailPickKeepsTheExactSourcePage() {
        val item = OriginalFixtures.spreadCbz(context, "ann-spread", List(5) { 300 to 450 })
        runBlocking { container.library.add(item); container.library.preferences(item.id, item.preferences) }
        seeded += item.id
        read(item.id, MediaCategory.COMIC)
        nextPage()
        awaitTag("spread_slot_2")
        awaitPage("2 / 5") // state.page is the lowest page of the visible pair, never the pair's second member
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        awaitStored(item.id, 1)
        assertEquals("the spread's authoritative page, not a group or slot index", 1, FixedPageLocator.parse(stored(item.id).single().locatorJson))
        // Pages: selecting the pair's second member keeps exactly that source page as the anchor.
        compose.onNodeWithTag("reader_thumbnails").performClick()
        awaitTag("thumbnail_strip")
        compose.onNodeWithTag("thumbnail_2").performScrollTo().performClick()
        awaitPage("3 / 5")
        compose.onNodeWithTag("reader_bookmark").assertTextContains("Page 3", substring = true)
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        awaitStored(item.id, 2)
        assertEquals(listOf(1, 2), stored(item.id).map { FixedPageLocator.parse(it.locatorJson) })
        openNotebook()
        compose.onNodeWithTag("notebook_add_note").assertTextContains("page 3", substring = true)
    }

    @Test fun rtlMangaStoresTheSamePlainSourcePageAsLtr() {
        read("test-cbz", MediaCategory.MANGA)
        nextPage(); awaitPage("2 / 3")
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        awaitStored("test-cbz", 1)
        assertEquals("{\"version\":1,\"page\":1}", stored("test-cbz").single().locatorJson)
    }

    @Test fun thumbnailAndSliderSelectionsBindTheExactSourcePageForBookmarksAndNotes() {
        read("test-pdf")
        compose.onNodeWithTag("reader_thumbnails").performClick()
        awaitTag("thumbnail_strip")
        compose.onNodeWithTag("thumbnail_2").performScrollTo().performClick()
        awaitPage("3 / 3")
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        awaitStored("test-pdf", 1)
        assertEquals(2, FixedPageLocator.parse(stored("test-pdf").single().locatorJson))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        awaitPage("2 / 3")
        openNotebook()
        compose.onNodeWithTag("notebook_add_note").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").performTextInput("From the slider")
        compose.onNodeWithTag("annotation_editor_save").performClick()
        awaitStored("test-pdf", 2)
        assertEquals(setOf(1, 2), stored("test-pdf").map { FixedPageLocator.parse(it.locatorJson) }.toSet())
    }
}
