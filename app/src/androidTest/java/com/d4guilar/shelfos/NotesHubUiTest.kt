// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4D: the real Notes destination of [MainActivity] over the real Room database: browse, kind filters, search,
 * publication filter, sort, orphans (kept after removal), edit, delete, group delete, fixed-page source jump and its strict
 * failures, recreation, keyboard/gamepad operation and the phone/tablet layouts. Seeded rows all contain the marker
 * `hubmark`, and every test searches for it first, so unrelated data on the device never matters.
 */
class NotesHubUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container
    private val pdf by lazy { OriginalFixtures.pdf(context) }
    private val epub by lazy { OriginalFixtures.epub(context) }
    private val goneId = "hub-gone"
    private val readium = """{"href":"c1.xhtml","type":"application/xhtml+xml","title":"Opening Chapter","locations":{"progression":0.4,"position":7,"totalProgression":0.4}}"""
    private lateinit var pdfNote: Annotation
    private lateinit var pdfBookmark: Annotation
    private lateinit var pdfOutOfRange: Annotation
    private lateinit var pdfMalformed: Annotation
    private lateinit var highlight: Annotation
    private lateinit var epubNote: Annotation
    private lateinit var epubBookmark: Annotation
    private lateinit var orphanNote: Annotation

    private fun fixed(page: Int, kind: AnnotationKind, body: String?, progress: Int) =
        AnnotationDraft(pdf.id, kind, LocatorFormat.FIXED_PAGE_1, FixedPageLocator.format(page), progress, page / 3.0, body = body)

    @Before fun seed() = runBlocking<Unit> {
        cleanup()
        container.library.add(pdf); container.library.add(epub)
        container.library.add(LibraryItem(goneId, "Hub Gone Book", "Gone Author", MediaCategory.BOOK, "test:hub-gone", PublicationFormat.EPUB, "gone.epub", 1L))
        val a = container.annotations
        fun settle() = Thread.sleep(4)   // strictly increasing createdAt, so the date sort is deterministic
        pdfNote = a.create(fixed(1, AnnotationKind.NOTE, "hubmark pdf note", 33)); settle()
        pdfBookmark = a.create(AnnotationDraft(pdf.id, AnnotationKind.BOOKMARK, LocatorFormat.FIXED_PAGE_1, FixedPageLocator.format(2), 66, 2 / 3.0, title = "hubmark pdf bookmark")); settle()
        pdfOutOfRange = a.create(fixed(98, AnnotationKind.NOTE, "hubmark out of range", 90)); settle()
        pdfMalformed = a.create(AnnotationDraft(pdf.id, AnnotationKind.NOTE, LocatorFormat.FIXED_PAGE_1, """{"version":1,"page":"x"}""", 10, body = "hubmark malformed"), validateLocator = false); settle()
        highlight = a.create(AnnotationDraft(epub.id, AnnotationKind.HIGHLIGHT, LocatorFormat.READIUM_LOCATOR_1, readium, 40, 0.4, selectedText = "hubmark highlighted words", styleKey = "amber")); settle()
        highlight = requireNotNull(a.update(highlight.id, AnnotationEdit(highlight.title, highlight.selectedText, "hubmark highlight thoughts", highlight.styleKey))); settle()
        epubNote = a.create(AnnotationDraft(epub.id, AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1, readium, 41, 0.41, body = "hubmark epub position note")); settle()
        epubBookmark = a.create(AnnotationDraft(epub.id, AnnotationKind.BOOKMARK, LocatorFormat.READIUM_LOCATOR_1, """{"href":"c2.xhtml","type":"application/xhtml+xml","locations":{"progression":0.1,"totalProgression":0.5}}""", 50, title = "hubmark bookmark label")); settle()
        orphanNote = a.create(AnnotationDraft(goneId, AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1, readium, 77, 0.77, body = "hubmark orphan note"))
        container.library.remove(goneId, KnowledgePolicy.KEEP)
    }
    @After fun cleanupAfter() = runBlocking<Unit> { cleanup() }
    private suspend fun cleanup() {
        container.annotations.deleteOrphanedPublication(goneId)
        listOf(pdf.id, epub.id).forEach { container.annotations.deleteForPublication(it, KnowledgePolicy.DELETE); container.library.remove(it) }
        container.library.remove(goneId, KnowledgePolicy.DELETE)
        container.library.preferences("", "{}")
    }

    // ---------------------------------------------------------------- helpers

    private fun awaitLibrary() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitTag(tag: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitNoTag(tag: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    /** The hub's own result count ("N items"): the list is lazy, so composed rows are not the number of results. */
    private fun countText() = compose.onAllNodes(hasTestTag("notes_count")).fetchSemanticsNodes().joinToString { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty() }
    /** On short windows (below 480 dp high) the controls are the list's first item and scroll away; bring them back before using them. */
    private fun controlsInView() { if (compose.onAllNodesWithTag("notes_list").fetchSemanticsNodes().isNotEmpty()) { compose.onNodeWithTag("notes_list").performScrollToIndex(0); compose.waitForIdle() } }
    private fun awaitRows(n: Int) {
        controlsInView()
        val want = if (n == 1) "1 item" else "$n items"
        try { compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("notes_count") and hasText(want)).fetchSemanticsNodes().isNotEmpty() } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) { fail("expected '$want' but the hub showed '${countText()}'") }
    }
    private fun row(marker: String) = hasTestTag("notes_row") and hasAnyDescendant(hasContentDescription(marker, substring = true))
    private fun inRow(marker: String, tag: String) = hasTestTag(tag) and hasAnyAncestor(row(marker))
    /** The list composes only what is on screen, so bring a row into view before touching it. */
    private fun reveal(marker: String) { compose.onNodeWithTag("notes_list").performScrollToNode(row(marker)); compose.waitForIdle() }
    private fun rowNode(marker: String): SemanticsNodeInteraction { reveal(marker); return compose.onNode(row(marker)) }
    private fun rowButton(marker: String, tag: String): SemanticsNodeInteraction { reveal(marker); return compose.onNode(inRow(marker, tag)) }
    private fun rowButtons(marker: String, tag: String): SemanticsNodeInteractionCollection { reveal(marker); return compose.onAllNodes(inRow(marker, tag)) }
    private fun revealTag(tag: String) { compose.onNodeWithTag("notes_list").performScrollToNode(hasTestTag(tag)); compose.waitForIdle() }
    private fun described(marker: String, vararg more: String): SemanticsNodeInteraction {
        reveal(marker)
        return compose.onNode(more.fold(hasContentDescription(marker, substring = true)) { acc, text -> acc and hasContentDescription(text, substring = true) })
    }

    private fun openNotes(search: String = "hubmark", expectedRows: Int? = 8) {
        awaitLibrary()
        compose.onNodeWithTag("nav_notes").performClick().assertIsSelected()
        awaitTag("notes_screen")
        compose.onNodeWithTag("notes_search").performTextInput(search); compose.hideSoftKeyboardIfShown()
        if (expectedRows != null) awaitRows(expectedRows)
    }

    // ---------------------------------------------------------------- the destination and its rows

    @Test fun notesDestinationOpensTheRealHubInsteadOfThePlaceholder() {
        awaitLibrary()
        compose.onNodeWithTag("nav_notes").performClick()
        awaitTag("notes_screen")
        compose.onAllNodesWithText("planned for a later phase", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("notes_search").assertExists()
        listOf("all", "notes", "highlights", "bookmarks").forEach { compose.onNodeWithTag("notes_filter_$it").assertExists() }
        compose.onNodeWithTag("notes_sort_publication").assertExists(); compose.onNodeWithTag("notes_sort_date").assertExists()
        compose.onNodeWithTag("notes_publication_filter").assertExists()
    }

    @Test fun rowsShowKindPublicationLocationAndOrphanStatusWithoutColorOnlyMeaning() {
        openNotes()
        // Fixed rows say "Page N" (1-based); EPUB rows prefer their stored chapter/title and Location N evidence.
        rowNode("hubmark pdf note").assertExists()
        described("hubmark pdf note", pdf.title, "Page 2").assertExists()
        described("hubmark highlighted words", "Highlight", "Amber", "Opening Chapter", "Location 7", "40 percent through book").assertExists()
        compose.onNode(row("hubmark highlighted words") and hasAnyDescendant(hasText("Opening Chapter", substring = true)), useUnmergedTree = true).assertExists()
        compose.onNode(row("hubmark highlighted words") and hasAnyDescendant(hasText("Location 7", substring = true)), useUnmergedTree = true).assertExists()
        described("hubmark epub position note", "Note at position").assertExists()
        described("hubmark bookmark label", "Bookmark").assertExists()
        described("hubmark pdf bookmark", "Bookmark", "Page 3").assertExists()
        // The orphan row carries a text status and its publication title from the snapshot.
        described("hubmark orphan note", "Hub Gone Book", "From a removed publication").assertExists()
        compose.onNode(inRow("hubmark orphan note", "notes_row_status"), useUnmergedTree = true).assertTextContains("From a removed publication")
        reveal("hubmark pdf note")
        assertTrue(compose.onAllNodesWithTag("notes_cover", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithTag("notes_created", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        reveal("hubmark orphan note")
        assertTrue(compose.onAllNodesWithTag("notes_cover_fallback", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        reveal("hubmark highlighted words")
        assertTrue(compose.onAllNodesWithTag("notes_kind_icon", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithTag("notes_updated", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodes(hasTestTag("notes_row") and hasAnyDescendant(hasContentDescription("test:hub-gone", substring = true))).assertCountEquals(0)
        // A removed publication cannot open anything; live ones can.
        rowButtons("hubmark orphan note", "notes_open").assertCountEquals(0)
        rowButtons("hubmark pdf note", "notes_open").assertCountEquals(1)
        // Bookmarks have nothing to edit; notes and highlights do; everything can be deleted.
        rowButtons("hubmark bookmark label", "notes_edit").assertCountEquals(0)
        rowButtons("hubmark highlighted words", "notes_edit").assertCountEquals(1)
        rowButtons("hubmark orphan note", "notes_edit").assertCountEquals(1)
        rowButtons("hubmark bookmark label", "notes_delete").assertCountEquals(1)
        rowButtons("hubmark orphan note", "notes_delete").assertCountEquals(1)
    }

    @Test fun kindFiltersAndSearchComposeAndClearRestoresTheList() {
        openNotes()
        compose.onNodeWithTag("notes_filter_notes").performClick()
        // Notes = NOTE only: the annotated-or-not highlight and the bookmarks are not in it.
        awaitRows(5)
        compose.onNodeWithTag("notes_filter_highlights").performClick(); awaitRows(1)
        rowNode("hubmark highlighted words").assertExists()
        compose.onNodeWithTag("notes_filter_bookmarks").performClick(); awaitRows(2)
        compose.onNodeWithTag("notes_filter_all").performClick(); awaitRows(8)
        // Search composes with the filter: the marker plus a narrowing word.
        compose.onNodeWithTag("notes_filter_notes").performClick(); awaitRows(5)
        compose.onNodeWithTag("notes_search").performTextReplacement("hubmark orphan"); compose.hideSoftKeyboardIfShown()
        awaitRows(1); rowNode("hubmark orphan note").assertExists()
        compose.onNodeWithTag("notes_search").performTextReplacement("hubmark zzzz-no-match"); compose.hideSoftKeyboardIfShown()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notes_empty").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nothing in this view.").assertExists()
        compose.onNodeWithTag("notes_search_clear").performClick()
        compose.onNodeWithTag("notes_filter_all").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notes_row").fetchSemanticsNodes().size > 8 || compose.onAllNodesWithTag("notes_empty").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun searchMatchesPublicationTitleAndSnapshotTitleAndEscapesPercent() {
        openNotes(search = "Hub Gone Book", expectedRows = 1)
        rowNode("hubmark orphan note").assertExists()
        compose.onNodeWithTag("notes_search").performTextReplacement("hubmark %"); compose.hideSoftKeyboardIfShown()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notes_empty").fetchSemanticsNodes().isNotEmpty() }   // a literal %, not a wildcard
    }

    @Test fun publicationFilterListsLiveAndRemovedPublicationsAndGroupHeadersExplainRemoval() {
        openNotes()
        // Default sort is by publication: a header per publication, the removed one marked in text.
        revealTag("notes_group_removed"); compose.onNodeWithTag("notes_group_removed").assertExists()
        controlsInView(); compose.onNodeWithTag("notes_publication_filter").performClick()
        awaitTag("notes_publication_list")
        compose.onAllNodesWithTag("notes_publication_option").fetchSemanticsNodes().let { assertTrue(it.size >= 3) }
        compose.onNode(hasTestTag("notes_publication_option") and hasText("Hub Gone Book", substring = true) and hasText("removed", substring = true)).performClick()
        awaitRows(1)
        rowNode("hubmark orphan note").assertExists()
        compose.onNodeWithTag("notes_group_delete").assertExists()
        compose.onNodeWithTag("notes_publication_filter").assertTextContains("Hub Gone Book", substring = true)
        // Back to all publications.
        controlsInView(); compose.onNodeWithTag("notes_publication_filter").performClick()
        compose.onNodeWithTag("notes_publication_option_all").performClick()
        awaitRows(8)
    }

    @Test fun dateSortIsNewestFirstAndPublicationSortGroupsInReadingOrder() {
        openNotes()
        // Publication sort: within the PDF, the page-2 note comes before the page-3 bookmark (reading order).
        reveal("hubmark pdf note")
        val byPublication = descriptionsInOrder()
        val pdfNoteAt = byPublication.indexOfFirst { it.contains("hubmark pdf note") }; val pdfBookmarkAt = byPublication.indexOfFirst { it.contains("hubmark pdf bookmark") }
        assertTrue("both PDF rows are adjacent, so both are on screen", pdfNoteAt >= 0 && pdfBookmarkAt >= 0)
        assertTrue(pdfNoteAt < pdfBookmarkAt)
        controlsInView(); compose.onNodeWithTag("notes_sort_date").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notes_header").fetchSemanticsNodes().isEmpty() }
        // Newest first: the orphan note was created last.
        compose.waitUntil(10_000) { descriptionsInOrder().firstOrNull()?.contains("hubmark orphan note") == true }
        controlsInView(); compose.onNodeWithTag("notes_sort_publication").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("notes_header").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun descriptionsInOrder(): List<String> = compose.onAllNodes(hasTestTag("notes_row")).fetchSemanticsNodes().map { node ->
        node.children.firstNotNullOfOrNull { c ->
            c.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
        }.orEmpty()
    }

    // ---------------------------------------------------------------- edit and delete

    @Test fun editingANoteChangesOnlyItsBodyAndAHighlightKeepsItsKind() = runBlocking<Unit> {
        openNotes()
        rowButton("hubmark pdf note", "notes_edit").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").performTextReplacement("hubmark pdf note EDITED"); compose.hideSoftKeyboardIfShown()
        compose.onNodeWithTag("annotation_editor_save").performClick()
        awaitNoTag("annotation_body")
        val edited = requireNotNull(container.annotations.get(pdfNote.id))
        assertEquals("hubmark pdf note EDITED", edited.body); assertEquals(AnnotationKind.NOTE, edited.kind)
        assertEquals(pdfNote.locatorJson, edited.locatorJson); assertEquals(pdfNote.createdAt, edited.createdAt)
        rowNode("hubmark pdf note EDITED").assertExists()
        // A highlight gains a body and keeps its kind, style and selected text.
        rowButton("hubmark highlighted words", "notes_edit").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").performTextReplacement("added thoughts"); compose.hideSoftKeyboardIfShown()
        compose.onNodeWithTag("annotation_editor_save").performClick()
        awaitNoTag("annotation_body")
        val hl = requireNotNull(container.annotations.get(highlight.id))
        assertEquals(AnnotationKind.HIGHLIGHT, hl.kind); assertEquals("added thoughts", hl.body)
        assertEquals("hubmark highlighted words", hl.selectedText); assertEquals(highlight.locatorJson, hl.locatorJson)
    }

    @Test fun anOrphanNoteIsEditableAndACancelledEditChangesNothing() = runBlocking<Unit> {
        openNotes()
        rowButton("hubmark orphan note", "notes_edit").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").performTextReplacement("hubmark orphan note CHANGED"); compose.hideSoftKeyboardIfShown()
        compose.onNodeWithTag("annotation_editor_cancel").performClick()
        awaitNoTag("annotation_body")
        assertEquals("hubmark orphan note", container.annotations.get(orphanNote.id)!!.body)
        rowButton("hubmark orphan note", "notes_edit").performClick()
        awaitTag("annotation_body")
        compose.onNodeWithTag("annotation_body").performTextReplacement("hubmark orphan note CHANGED"); compose.hideSoftKeyboardIfShown()
        compose.onNodeWithTag("annotation_editor_save").performClick()
        awaitNoTag("annotation_body")
        assertEquals("hubmark orphan note CHANGED", container.annotations.get(orphanNote.id)!!.body)
        assertEquals(orphanNote.locatorJson, container.annotations.get(orphanNote.id)!!.locatorJson)
    }

    @Test fun deletingNeedsConfirmationAndCancelKeepsTheAnnotation() = runBlocking<Unit> {
        openNotes()
        rowButton("hubmark bookmark label", "notes_delete").performClick()
        awaitTag("annotation_delete_confirm")
        compose.onNodeWithText("This deletes it from ShelfOS. The original publication file is not changed.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        awaitNoTag("annotation_delete_confirm")
        assertNotNull(container.annotations.get(epubBookmark.id)); awaitRows(8)
        rowButton("hubmark bookmark label", "notes_delete").performClick()
        compose.onNodeWithTag("annotation_delete_confirm").performClick()
        awaitRows(7)
        assertNull(container.annotations.get(epubBookmark.id))
        assertNotNull("other annotations are untouched", container.annotations.get(epubNote.id))
    }

    @Test fun groupDeleteIsExplicitConfirmedAndLeavesLivePublicationsAlone() = runBlocking<Unit> {
        openNotes()
        revealTag("notes_group_delete"); compose.onNodeWithTag("notes_group_delete").performClick()
        compose.onNodeWithText("Delete notes for this removed publication?").assertExists()
        compose.onNode(hasText("Hub Gone Book", substring = true) and hasText("permanently deletes", substring = true)).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertNotNull(container.annotations.get(orphanNote.id))
        revealTag("notes_group_delete"); compose.onNodeWithTag("notes_group_delete").performClick()
        compose.onNodeWithTag("notes_group_delete_confirm").performClick()
        awaitRows(7)
        assertNull(container.annotations.get(orphanNote.id))
        assertTrue(container.annotations.notes(NotesQuery(publicationId = goneId)).rows.isEmpty())
        assertNotNull(container.annotations.get(pdfNote.id)); assertNotNull(container.annotations.get(highlight.id))
    }

    @Test fun anUnavailableOriginalIsMarkedInTextAndItsNotesStayListedAndEditable() = runBlocking<Unit> {
        container.library.available(pdf.id, false)
        openNotes()
        // Unavailable is a live item whose file cannot be reached: still listed, still editable, marked in words, never deleted.
        described("hubmark pdf note", "Original file unavailable").assertExists()
        rowButtons("hubmark pdf note", "notes_edit").assertCountEquals(1)
        rowButtons("hubmark pdf note", "notes_open").assertCountEquals(1)   // the reader then explains the unavailable original itself
        assertNotNull(container.annotations.get(pdfNote.id))
    }

    // ---------------------------------------------------------------- removal choice, end to end

    private fun removeThroughTheLibrary(id: String, choiceTag: String) {
        awaitLibrary()
        awaitTag("publication_$id")
        compose.onNodeWithTag("publication_$id").performClick()
        compose.onNodeWithTag("remove_action").performScrollTo().performClick()
        awaitTag(choiceTag)
        compose.onNodeWithText("The original file stays untouched.", substring = true).assertExists()
        compose.onNodeWithTag(choiceTag).performClick()
        compose.waitUntil(10_000) { runBlocking { container.library.publications.first().none { it.id == id } } }
    }

    @Test fun removingWithKeepNotesLeavesOrphansVisibleInTheHubAndWithoutAnOpenAction() = runBlocking<Unit> {
        removeThroughTheLibrary(pdf.id, "confirm_remove_keep")
        assertEquals("every annotation of the removed publication is kept", 4, container.annotations.notes(NotesQuery(publicationId = pdf.id)).rows.size)
        assertTrue(container.annotations.notes(NotesQuery(publicationId = pdf.id)).rows.all { !it.source.live && it.source.title == pdf.title })
        compose.onNodeWithTag("nav_notes").performClick()
        awaitTag("notes_screen")
        compose.onNodeWithTag("notes_search").performTextInput("hubmark"); compose.hideSoftKeyboardIfShown()
        awaitRows(8)
        described("hubmark pdf note", pdf.title, "From a removed publication").assertExists()
        rowButtons("hubmark pdf note", "notes_open").assertCountEquals(0)
        rowButtons("hubmark pdf note", "notes_edit").assertCountEquals(1)
    }

    @Test fun removingWithDeleteNotesRemovesThePublicationsAnnotationsAndLeavesNoSnapshot() = runBlocking<Unit> {
        removeThroughTheLibrary(epub.id, "confirm_remove_delete")
        assertTrue(container.annotations.notes(NotesQuery(publicationId = epub.id)).rows.isEmpty())
        compose.onNodeWithTag("nav_notes").performClick()
        awaitTag("notes_screen")
        compose.onNodeWithTag("notes_search").performTextInput("hubmark"); compose.hideSoftKeyboardIfShown()
        awaitRows(5)
        assertNotNull("another publication's notes are untouched", container.annotations.get(pdfNote.id))
    }

    // ---------------------------------------------------------------- source jump

    @Test fun openingAFixedPageNoteJumpsStrictlyToItsPageThenBackReturnsToTheHub() {
        openNotes()
        rowButton("hubmark pdf note", "notes_open").performClick()
        awaitTag("page_number")
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("page_number") and hasText("2 / 3")).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("notes_screen")
    }

    @Test fun anOutOfRangeFixedNoteNeverClampsAndExplainsInsteadOfFakingAJump() = runBlocking<Unit> {
        // The reader resumes at page 1 (the saved position); the stale note says page 99 of a 3-page document.
        container.library.reading(pdf.id, FixedPageLocator.format(0), 0)
        openNotes()
        rowButton("hubmark out of range", "notes_open").performClick()
        awaitTag("page_number")
        awaitTag("annotation_notice", 15_000)
        compose.onNodeWithText("That spot could not be opened. The note is still saved.").assertExists()
        compose.onNode(hasTestTag("page_number")).assertTextContains("1 / 3")   // not clamped to the last page
        assertEquals(pdfOutOfRange.locatorJson, container.annotations.get(pdfOutOfRange.id)!!.locatorJson)
    }

    @Test fun aMalformedFixedLocatorExplainsAndStaysDeletable() = runBlocking<Unit> {
        openNotes()
        rowButton("hubmark malformed", "notes_open").performClick()
        awaitTag("annotation_notice", 15_000)
        compose.onNode(hasTestTag("page_number")).assertTextContains("1 / 3")
        assertNotNull(container.annotations.get(pdfMalformed.id))
    }

    @Test fun openingAnEpubRowStartsTheReaderThroughTheSameEpubActivity() {
        openNotes()
        val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        fun query() = monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filter { it.javaClass.simpleName == "EpubActivity" }
        fun resumedEpubActivities(): List<android.app.Activity> {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return query()   // waitUntil conditions run on the main thread
            var found = emptyList<android.app.Activity>()
            instrumentation.runOnMainSync { found = query() }
            return found
        }
        rowButton("hubmark epub position note", "notes_open").performClick()
        compose.waitUntil(30_000) { resumedEpubActivities().isNotEmpty() }
        assertEquals("exactly one reader session was started", 1, resumedEpubActivities().size)
        instrumentation.runOnMainSync { resumedEpubActivities().forEach { it.finish() } }
    }

    // ---------------------------------------------------------------- state, input, layout

    @Test fun searchFilterSortAndPublicationSurviveActivityRecreation() {
        openNotes()
        compose.onNodeWithTag("notes_filter_notes").performClick(); awaitRows(5)
        compose.onNodeWithTag("notes_sort_date").performClick()
        compose.onNodeWithTag("notes_search").performTextReplacement("hubmark pdf"); compose.hideSoftKeyboardIfShown()
        awaitRows(1)
        compose.activityRule.scenario.recreate()
        awaitTag("nav_notes")
        compose.onNodeWithTag("nav_notes").performClick()
        awaitTag("notes_screen")
        compose.onNodeWithTag("notes_search").assertTextContains("hubmark pdf")
        compose.onNodeWithTag("notes_filter_notes").assertIsSelected()
        compose.onNodeWithTag("notes_sort_date").assertIsSelected()
        awaitRows(1)
    }

    @Test fun keyboardAndGamepadReachAndActivateEveryControl() {
        openNotes()
        // Keyboard: focus a filter chip and activate it with Enter.
        // Prime non-touch input mode (a real key event), as a keyboard or gamepad user is in; touch mode refuses Compose focus requests on handhelds.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN); compose.waitForIdle()
        compose.onNodeWithTag("notes_filter_highlights").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("notes_filter_highlights").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        awaitRows(1)
        // Gamepad A (the semantic CONFIRM command) activates the focused chip as well.
        compose.onNodeWithTag("notes_filter_all").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("notes_filter_all").assertIsFocused().performKeyInput { pressKey(Key.ButtonA) }
        awaitRows(8)
        // The row's Open and Delete buttons are focusable and labeled for assistive tech.
        rowButton("hubmark pdf note", "notes_open").performSemanticsAction(SemanticsActions.RequestFocus)
        rowButton("hubmark pdf note", "notes_open").assertIsFocused().assertHasClickAction()
        rowButton("hubmark pdf note", "notes_open").assert(hasContentDescription("Open Note in ${pdf.title}", substring = true))
        rowButton("hubmark pdf note", "notes_delete").assert(hasContentDescription("Delete Note in ${pdf.title}", substring = true))
        rowButton("hubmark pdf note", "notes_edit").assert(hasContentDescription("Edit Note in ${pdf.title}", substring = true))
        // Keyboard Enter on the focused Open button follows the same path as touch.
        rowButton("hubmark pdf note", "notes_open").performKeyInput { pressKey(Key.Enter) }
        awaitTag("page_number")
    }

    @Test fun layoutAdaptsToWidthSingleModelForPhoneAndTablet() {
        openNotes()
        val rail = compose.onAllNodesWithTag("navigation_rail").fetchSemanticsNodes().isNotEmpty()
        val search = compose.onNodeWithTag("notes_search").fetchSemanticsNode().boundsInRoot
        val filter = compose.onNodeWithTag("notes_publication_filter").fetchSemanticsNode().boundsInRoot
        if (rail && compose.onNodeWithTag("notes_screen").fetchSemanticsNode().size.width >= 840 * context.resources.displayMetrics.density) {
            assertTrue("wide: search and publication filter share a row", kotlin.math.abs(search.top - filter.top) < search.height)
            assertTrue(filter.left >= search.right - 1)
        } else assertTrue("compact: the publication filter sits below the search field", filter.top >= search.bottom - 1)
        compose.onAllNodesWithTag("notes_row").fetchSemanticsNodes().forEach { assertTrue("rows stay inside the screen", it.boundsInRoot.right <= compose.onNodeWithTag("notes_screen").fetchSemanticsNode().boundsInRoot.right + 1) }
        listOf("notes_open", "notes_delete").forEach { tag ->
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().forEach { n -> assertTrue("$tag target >= 48dp but is ${n.size.height / context.resources.displayMetrics.density}dp", n.size.height >= 47 * context.resources.displayMetrics.density) }
        }
    }
}
