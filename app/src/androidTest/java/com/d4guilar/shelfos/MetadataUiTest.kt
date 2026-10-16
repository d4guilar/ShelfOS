// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.data.metadata.RoomMetadataRepository
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import com.d4guilar.shelfos.feature.library.LocalMetadataAccess
import com.d4guilar.shelfos.feature.library.MetadataAccess
import com.d4guilar.shelfos.feature.library.PublicationDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4.5B details and editor on a real in-memory Room database: category-appropriate fields, manual edits recorded
 * as `USER` (and only the changed ones), invalid input rejected without rewriting user text, conservative Documents,
 * malformed stored values degrading safely, Read kept in view, and the editor reachable with a keyboard/D-pad.
 */
class MetadataUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, ShelfDatabase::class.java).build()
    private val repo = RoomMetadataRepository(db)
    private val embedded = FieldProvenance(MetadataOrigin.EMBEDDED)
    @After fun close() { db.close() }

    private fun item(id: String, category: MediaCategory) = LibraryItem(id, "Title $id", "Brian K. Vaughan", category, "test:$id",
        PublicationFormat.CBZ, "$id.cbz", 1000, titleOrigin = "embedded", creatorOrigin = "embedded")

    private fun seed(item: LibraryItem, values: MetadataValues) = runBlocking {
        RoomLibraryRepository(db.library()).add(item)
        repo.apply(item.id, values.proposals(embedded), 1)
    }

    /** Metadata saves the editor issued (H-1 double-save check). */
    private val saves = java.util.concurrent.atomic.AtomicInteger()

    private fun show(initial: LibraryItem, restoration: StateRestorationTester? = null) {
        var item by mutableStateOf(initial)
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalMetadataAccess provides MetadataAccess(repo::observe)) {
                    Box(Modifier.size(360.dp, 640.dp).testTag("window")) {
                        PublicationDetails(item, false, {}, Modifier.fillMaxSize(), onRead = {}, onEdit = { _, _, _ -> }, onRemove = {},
                            knowledgeCounts = AnnotationCounts.None,
                            onEditMetadata = { original, edited ->
                                saves.incrementAndGet()
                                CoroutineScope(Dispatchers.IO).launch { repo.editByUser(item.id, original, edited) }
                            })
                    }
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
    }

    // The write's Flow emission is let settle on the UI thread before the test touches the editor again.
    private fun background(id: String, field: MetadataField, value: String) {
        runBlocking { assertTrue(repo.apply(id, listOf(Proposed(field, value, embedded)), 2)) }
        compose.waitForIdle()
    }

    private fun openEditor() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("edit_action").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) } }
        compose.onNodeWithTag("edit_action").performScrollTo()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("edit_action").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("edit_action").performClick()
        compose.onNodeWithTag("editor_save").assertExists()
    }

    private fun save() {
        compose.onNodeWithTag("editor_save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor_save").fetchSemanticsNodes().isEmpty() }
    }

    private fun row(id: String) = runBlocking { repo.resolved(id)!! }

    private fun waitFor(id: String, check: (ResolvedMetadata) -> Boolean) =
        compose.waitUntil(5_000) { runBlocking { repo.resolved(id)?.let(check) == true } }

    @Test fun comicDetailsShowSeriesIssueCreditsAndEditsBecomeUser() {
        val comic = item("c1", MediaCategory.COMIC)
        seed(comic, MetadataValues(subtitle = "Hidden for comics", description = "Two soldiers from a war.", publisher = "Image",
            series = "Saga", issue = "1", identifiers = listOf(PublicationIdentifier(IdentifierType.UPC_A, "036000291452")),
            credits = listOf(Credit(CreditRole.WRITER, "Brian K. Vaughan"), Credit(CreditRole.PENCILLER, "Fiona Staples"))))
        show(comic)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("metadata_series").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("metadata_series").assertTextEquals("Series: Saga")
        compose.onNodeWithTag("metadata_issue").assertTextEquals("Issue: 1")
        compose.onNodeWithTag("metadata_publisher").assertTextEquals("Publisher: Image")
        compose.onNodeWithText("Penciller: Fiona Staples").assertExists()
        compose.onNodeWithTag("metadata_description").assertExists()
        compose.onAllNodesWithTag("metadata_subtitle").assertCountEquals(0)
        compose.onNodeWithTag("read_action").assertIsDisplayed()

        compose.onNodeWithTag("edit_action").performScrollTo().assertIsEnabled().performClick()
        compose.onAllNodesWithTag("edit_subtitle").assertCountEquals(0)
        compose.onNodeWithTag("edit_issue").assertExists()
        compose.onNodeWithTag("edit_publisher").performScrollTo().performTextReplacement("  Image Comics ")
        compose.onNodeWithTag("edit_published_date").performScrollTo().performTextReplacement("2012-99")
        compose.onNodeWithText("Use YYYY, YYYY-MM or YYYY-MM-DD.").assertExists()
        compose.onNodeWithTag("editor_save").assertIsNotEnabled()
        compose.onNodeWithTag("edit_published_date").performTextReplacement("2012-03")
        compose.onNodeWithTag("edit_language").performScrollTo().performTextReplacement("English")
        compose.onNodeWithTag("editor_save").assertIsNotEnabled()
        compose.onNodeWithTag("edit_language").performTextReplacement("en")
        compose.onNodeWithTag("editor_save").assertIsEnabled().performClick()

        waitFor("c1") { it.values.publisher == "Image Comics" }
        val m = runBlocking { repo.resolved("c1")!! }
        assertEquals(MetadataOrigin.USER, m.origin(MetadataField.PUBLISHER))
        assertEquals("2012-03", m.values.publishedDate); assertEquals(MetadataOrigin.USER, m.origin(MetadataField.PUBLISHED_DATE))
        assertEquals("en", m.values.language)
        assertEquals("untouched fields keep their origin", MetadataOrigin.EMBEDDED, m.origin(MetadataField.SERIES))
        assertEquals("a hidden field is never cleared by editing", "Hidden for comics", m.values.subtitle)
        assertEquals(MetadataOrigin.EMBEDDED, m.origin(MetadataField.SUBTITLE))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Publisher: Image Comics").fetchSemanticsNodes().isNotEmpty() }
        // A later automatic pass never overwrites the edit.
        runBlocking { repo.apply("c1", listOf(Proposed(MetadataField.PUBLISHER, "Image", embedded)), 2) }
        assertEquals("Image Comics", runBlocking { repo.resolved("c1")!!.values.publisher })
    }

    @Test fun documentsStayConservativeAndMalformedValuesDegradeSafely() {
        val doc = item("d1", MediaCategory.DOCUMENT)
        seed(doc, MetadataValues(subtitle = "Q3", publisher = "ACME", series = "Should not show", volume = "9", language = "es"))
        db.openHelper.writableDatabase.execSQL("UPDATE publication_metadata SET identifiersJson = '[[broken', creditsJson = 'nope' WHERE libraryItemId = 'd1'")
        show(doc)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("metadata_subtitle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("metadata_subtitle").assertTextEquals("Q3")
        compose.onNodeWithTag("metadata_language").assertTextContains("Language: ", substring = true)
        compose.onAllNodesWithTag("metadata_series").assertCountEquals(0)
        compose.onAllNodesWithTag("metadata_volume").assertCountEquals(0)
        compose.onNodeWithTag("read_action").assertIsDisplayed()
        compose.onNodeWithTag("edit_action").performScrollTo().performClick()
        compose.onAllNodesWithTag("edit_series").assertCountEquals(0)
        compose.onNodeWithTag("edit_subtitle").assertExists()
    }

    @Test fun editorIsReachableAndConfirmableWithKeyboardAndDpad() {
        val book = item("b1", MediaCategory.BOOK)
        seed(book, MetadataValues(publisher = "Example Press"))
        show(book)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("metadata_publisher").fetchSemanticsNodes().isNotEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB); compose.waitForIdle()
        compose.onNodeWithTag("edit_action").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("edit_action").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER); compose.waitForIdle()
        compose.onNodeWithTag("edit_subtitle").assertExists()
        compose.onNodeWithTag("editor_save").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("editor_save").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER); compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("edit_subtitle").fetchSemanticsNodes().isEmpty() }
        assertEquals("saving without changes writes no USER field", MetadataOrigin.EMBEDDED,
            runBlocking { repo.resolved("b1")!!.origin(MetadataField.PUBLISHER) })
    }

    // ---- H-1: the editor diffs against the snapshot taken when editing began, never against live Room values ----

    @Test fun h1aBackgroundWriteWhileEditingIsKeptAndOnlyTheEditedFieldBecomesUser() {
        seed(item("a", MediaCategory.COMIC), MetadataValues(series = "Saga"))
        show(item("a", MediaCategory.COMIC))
        openEditor()
        background("a", MetadataField.PUBLISHER, "Image Comics")   // analysis lands while the editor shows an empty Publisher
        compose.waitForIdle()
        compose.onNodeWithTag("edit_description").performScrollTo().performTextReplacement("Two soldiers")
        save()
        waitFor("a") { it.values.description == "Two soldiers" }
        val m = row("a")
        assertEquals("Image Comics", m.values.publisher)
        assertEquals(MetadataOrigin.EMBEDDED, m.origin(MetadataField.PUBLISHER))
        assertEquals(MetadataOrigin.USER, m.origin(MetadataField.DESCRIPTION))
        assertEquals(MetadataOrigin.EMBEDDED, m.origin(MetadataField.SERIES))
    }

    @Test fun h1bSnapshotAndDraftSurviveSavedStateRestoration() {
        seed(item("b", MediaCategory.COMIC), MetadataValues(series = "Saga"))
        val restoration = StateRestorationTester(compose)
        show(item("b", MediaCategory.COMIC), restoration)
        openEditor()
        compose.onNodeWithTag("edit_issue").performScrollTo().performTextReplacement("7")
        background("b", MetadataField.PUBLISHER, "Image Comics")
        restoration.emulateSavedInstanceStateRestore()                // activity/editor recreation
        compose.onNodeWithTag("edit_issue").performScrollTo().assertTextContains("7")   // the draft survived
        compose.onNodeWithTag("edit_description").performScrollTo().performTextReplacement("After recreation")
        save()
        waitFor("b") { it.values.description == "After recreation" }
        val m = row("b")
        assertEquals("the background value survives recreation", "Image Comics", m.values.publisher)
        assertEquals(MetadataOrigin.EMBEDDED, m.origin(MetadataField.PUBLISHER))
        assertEquals("7", m.values.issue); assertEquals(MetadataOrigin.USER, m.origin(MetadataField.ISSUE))
    }

    @Test fun h1cDeliberateClearIsUserAndNeverRefilled() {
        seed(item("c", MediaCategory.COMIC), MetadataValues(publisher = "Image"))
        show(item("c", MediaCategory.COMIC))
        openEditor()
        compose.onNodeWithTag("edit_publisher").performScrollTo().performTextReplacement("")
        save()
        waitFor("c") { it.origin(MetadataField.PUBLISHER) == MetadataOrigin.USER }
        assertNull(row("c").values.publisher)
        background("c", MetadataField.PUBLISHER, "Image")
        assertNull("analysis never refills a user clear", row("c").values.publisher)
        assertEquals(MetadataOrigin.USER, row("c").origin(MetadataField.PUBLISHER))
    }

    @Test fun h1dSameFieldConflictTakesTheUsersValue() {
        seed(item("d", MediaCategory.COMIC), MetadataValues(publisher = "A"))
        show(item("d", MediaCategory.COMIC))
        openEditor()
        background("d", MetadataField.PUBLISHER, "B")
        compose.onNodeWithTag("edit_publisher").performScrollTo().performTextReplacement("C")
        save()
        waitFor("d") { it.values.publisher == "C" }
        assertEquals(MetadataOrigin.USER, row("d").origin(MetadataField.PUBLISHER))
    }

    @Test fun h1eNoOpSaveWritesNothingAndCreatesNoUserOrigin() {
        seed(item("e", MediaCategory.COMIC), MetadataValues(publisher = "A", series = "S"))
        show(item("e", MediaCategory.COMIC))
        openEditor()
        background("e", MetadataField.SERIES, "S2")
        save()
        compose.waitForIdle(); Thread.sleep(300)
        assertEquals("no metadata save was issued", 0, saves.get())
        val m = row("e")
        assertEquals("S2", m.values.series)
        assertTrue("no field became USER", MetadataField.entries.none { m.origin(it) == MetadataOrigin.USER })
    }

    @Test fun h1RemovedWhileEditingIsNeverRecreatedAndSaveRunsOnce() {
        seed(item("r", MediaCategory.COMIC), MetadataValues(publisher = "A"))
        show(item("r", MediaCategory.COMIC))
        openEditor()
        runBlocking { RoomLibraryRepository(db.library()).remove("r") }
        compose.onNodeWithTag("edit_description").performScrollTo().performTextReplacement("Too late")
        val button = compose.onNodeWithTag("editor_save")
        button.performClick(); runCatching { button.performClick() }   // a second tap before the dialog closes
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor_save").fetchSemanticsNodes().isEmpty() }
        Thread.sleep(300)
        assertEquals("exactly one save per editor session", 1, saves.get())
        assertNull("the removed item is not resurrected", runBlocking { repo.resolved("r") })
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM publication_metadata WHERE libraryItemId = 'r'").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }
}
