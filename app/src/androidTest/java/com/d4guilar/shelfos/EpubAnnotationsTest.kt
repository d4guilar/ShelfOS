// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.AnnotationStyles
import com.d4guilar.shelfos.core.reader.PagePalette
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.debug.SyntheticAnnotationEpub
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationDraft
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.KnowledgePolicy
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.feature.reader.EpubActivity
import com.d4guilar.shelfos.feature.reader.EpubLegacyWebViewTestActivity
import com.d4guilar.shelfos.feature.reader.saveAppearance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4B (EPUB highlights and notes) against the real EpubActivity, real Readium navigator, real WebView, real Room
 * repository and the synthetic fixture. Touch is injected as real pointer events (long press through the real WebView);
 * DOM probes in [AnnotationUiProbe] are read-only measurements. Needs a WebView that passes the capability gate (the RP5's
 * WebView 109 does); the legacy-WebView behavior is exercised through the debug-only [EpubLegacyWebViewTestActivity].
 */
class EpubAnnotationsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val container get() = (ctx.applicationContext as ShelfApplication).container
    private val id = OriginalFixtures.ANNOTATION_ITEM_ID
    private val args get() = InstrumentationRegistry.getArguments()

    @Before fun seed() = runBlocking<Unit> { if (args.getString("deathPhase") != "restore") { container.library.remove(id); container.library.add(OriginalFixtures.epubAnnotations(ctx)) } }
    @After fun cleanup() = runBlocking<Unit> { if (args.getString("deathPhase") == null) container.library.remove(id) }

    // ---------------------------------------------------------------- helpers

    private fun reseed(variant: String = "BASE", prefs: ReaderPreferences? = null) = runBlocking<Unit> {
        container.library.remove(id); container.library.add(OriginalFixtures.epubAnnotations(ctx, variant))
        // LibraryRepository.add ignores LibraryItem.preferences; appearance is saved the way the Appearance dialog saves it.
        if (prefs != null) container.library.saveAppearance(id, "{}", null, ReaderPreferences.DEFAULT, prefs, globally = false)
    }
    private fun launch() = ActivityScenario.launch<EpubActivity>(EpubActivity.intent(ctx, id))
    private fun launchLegacy() = ActivityScenario.launch<EpubLegacyWebViewTestActivity>(EpubLegacyWebViewTestActivity.intent(ctx, id))
    private fun probe(scenario: ActivityScenario<out android.app.Activity>) = AnnotationUiProbe(inst, scenario) { compose.waitForIdle() }
    private fun awaitReader(p: AnnotationUiProbe) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("epub_notes").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("page ready", p.awaitReady())
    }
    private fun stored(): List<Annotation> = runBlocking { container.annotations.observeForPublication(id).first().annotations }
    private fun awaitStored(timeoutMs: Long = 10_000, predicate: (List<Annotation>) -> Boolean): List<Annotation> {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) { stored().let { if (predicate(it)) return it }; compose.waitForIdle(); SystemClock.sleep(150) }
        fail("stored annotations never matched: ${stored()}"); return emptyList()
    }
    private fun awaitUntil(p: AnnotationUiProbe, timeoutMs: Long = 15_000, what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; p.settle(200) }
        fail("timed out waiting for $what")
    }
    private fun awaitDecorations(p: AnnotationUiProbe, n: Int, timeoutMs: Long = 15_000) = awaitUntil(p, timeoutMs, "$n decorations (have ${p.decorationCount()})") { p.decorationCount() == n }
    private fun chromeVisible() = compose.onAllNodesWithTag("epub_notes").fetchSemanticsNodes().isNotEmpty()
    private fun selectionText(p: AnnotationUiProbe) = p.jsString("window.getSelection().toString()").orEmpty()
    private fun key(code: Int) { inst.sendKeyDownUpSync(code); compose.waitForIdle() }
    private fun gamepadKey(code: Int) {
        val downTime = SystemClock.uptimeMillis()
        inst.sendKeySync(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        inst.sendKeySync(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        compose.waitForIdle()
    }
    private fun editorOpen() = compose.onAllNodesWithTag("annotation_body").fetchSemanticsNodes().isNotEmpty()
    private fun awaitEditor() = compose.waitUntil(10_000) { editorOpen() }

    private fun locator(before: String, highlight: String, after: String, href: String = "chapter1.xhtml") = JSONObject()
        .put("href", href).put("type", "application/xhtml+xml").put("title", "Chapter 1")
        .put("locations", JSONObject().put("progression", 0.0).put("position", 1).put("totalProgression", 0.0))
        .put("text", JSONObject().put("before", before).put("highlight", highlight).put("after", after)).toString()

    private fun insert(kind: AnnotationKind, locatorJson: String, selected: String?, body: String? = null, style: String? = null, progress: Int = 0): Annotation =
        runBlocking { container.annotations.create(AnnotationDraft(id, kind, LocatorFormat.READIUM_LOCATOR_1, locatorJson, progress, null, selectedText = selected, body = body, styleKey = style)) }

    private fun pageUntilVisible(p: AnnotationUiProbe, token: String, maxPages: Int = 40) {
        repeat(maxPages) {
            if (p.tokenVisible(token)) return
            compose.onNodeWithText("Next").performClick(); p.settle(700)
        }
        assertTrue("token $token reachable", p.tokenVisible(token))
    }
    private fun openChapter(p: AnnotationUiProbe, title: String) {
        compose.onNodeWithText("Chapters").performClick()
        compose.onNode(hasText(title) and hasClickAction() and !hasSetTextAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size == 1 }
        assertTrue(p.awaitReady())
    }
    private fun highlightByLongPress(p: AnnotationUiProbe, token: String) {
        assertTrue("long press $token", p.longPressToken(token))
        assertTrue("toolbar offers Highlight", p.tapText("Highlight"))
    }
    private fun closeDialog() { compose.onNodeWithText("Close").performClick(); compose.waitForIdle() }

    // ---------------------------------------------------------------- creation

    @Test fun positionNoteIsCreatedListedAndNeedsNoSelection() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            compose.onNodeWithTag("epub_notes").performClick()
            compose.onNodeWithTag("notebook_empty").assertExists()
            compose.onNodeWithTag("notebook_add_note").performClick()
            awaitEditor()
            compose.onNodeWithTag("annotation_editor_save").assertIsNotEnabled()   // a blank body cannot be saved
            compose.onNodeWithTag("annotation_body").performTextInput("A position note about the opening")
            compose.onNodeWithTag("annotation_editor_save").assertIsEnabled().performClick()
            val saved = awaitStored { it.size == 1 }.single()
            assertEquals(AnnotationKind.NOTE, saved.kind)
            assertNull("a position note carries no selected text", saved.selectedText)
            assertEquals("A position note about the opening", saved.body)
            compose.onNodeWithTag("epub_notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText("Note at position").assertExists()
            assertEquals("no inline decoration for a position note", 0, p.decorationCount())
        }
    }

    @Test fun highlightFromRealSelectionIsStoredDrawnAndClearsTheSelection() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            assertTrue(p.longPressToken("quillfeather1x1"))
            assertTrue("toolbar also offers Add note and Copy", p.findByText("Add note", 2_000) != null && p.findByText("Copy", 2_000) != null)
            assertTrue("toolbar offers Highlight", p.tapText("Highlight"))
            val saved = awaitStored { it.size == 1 }.single()
            assertEquals(AnnotationKind.HIGHLIGHT, saved.kind)
            assertEquals("amber", saved.styleKey)
            assertEquals("quillfeather1x1", saved.selectedText)
            assertNull(saved.body)
            awaitDecorations(p, 1)
            assertTrue(p.tokenDecorated("quillfeather1x1"))
            assertEquals(listOf("org.readium.r2.navigator.Decoration.Style.Highlight"), p.decorationStyles())
            awaitUntil(p, what = "selection cleared") { selectionText(p).isEmpty() }
        }
    }

    @Test fun selectionNoteStoresSelectedTextDrawsAnUnderlineAndCancelCreatesNothing() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            assertTrue(p.longPressToken("quillfeather1x3")); assertTrue(p.tapText("Add note"))
            awaitEditor()
            compose.onNodeWithTag("annotation_editor_quote").assertTextContains("quillfeather1x3", substring = true)
            compose.onNodeWithTag("annotation_body").performTextInput("An annotated marker")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            val note = awaitStored { it.size == 1 }.single()
            assertEquals(AnnotationKind.NOTE, note.kind)
            assertEquals("quillfeather1x3", note.selectedText)
            assertEquals("An annotated marker", note.body)
            awaitDecorations(p, 1)
            assertEquals(listOf("org.readium.r2.navigator.Decoration.Style.Underline"), p.decorationStyles())
            awaitUntil(p, what = "selection cleared") { selectionText(p).isEmpty() }
            // Cancelling creates nothing and also leaves no selection behind.
            assertTrue(p.longPressToken("quillfeather1x4")); assertTrue(p.tapText("Add note"))
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextInput("never saved")
            compose.onNodeWithText("Cancel").performClick(); compose.waitForIdle()
            p.settle(800)
            assertEquals(1, stored().size)
            assertEquals(1, p.decorationCount())
            assertEquals("", selectionText(p))
        }
    }

    @Test fun anOverLongNoteBodyCannotBeSavedAndIsNeverTruncated() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            compose.onNodeWithTag("epub_notes").performClick()
            compose.onNodeWithTag("notebook_add_note").performClick()
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextInput("n".repeat(20_001))
            compose.onNodeWithTag("annotation_editor_save").assertIsNotEnabled()
            compose.onNodeWithText("20001 of 20000 characters", substring = true).assertExists()
            compose.onNodeWithTag("annotation_body").performTextReplacement("n".repeat(20_000))
            compose.onNodeWithTag("annotation_editor_save").assertIsEnabled().performClick()
            val saved = awaitStored { it.size == 1 }.single()
            assertEquals(20_000, saved.body!!.length)
        }
    }

    // ---------------------------------------------------------------- tap, edit, delete

    @Test fun tappingADecorationOpensTheEditorWithoutTogglingChromeAndEditsStyleBodyAndDeletes() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            highlightByLongPress(p, "quillfeather1x1")
            awaitStored { it.size == 1 }; awaitDecorations(p, 1)
            assertTrue(chromeVisible())
            val (x, y) = p.centerOf(p.tokenScreenRect("quillfeather1x1")!!)
            p.tap(x, y)
            awaitEditor()
            compose.onNodeWithText("Edit highlight").assertExists()
            compose.onNodeWithText("Cancel").performClick(); compose.waitForIdle(); p.settle(500)
            assertTrue("consuming the activation must not toggle reader chrome", chromeVisible())

            // Change style and add an optional body: it stays a HIGHLIGHT.
            p.tap(x, y)
            awaitEditor()
            compose.onNodeWithTag("annotation_style_blue").performClick()
            compose.onNodeWithTag("annotation_body").performTextInput("Why this matters")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            val edited = awaitStored { it.single().styleKey == "blue" }.single()
            assertEquals(AnnotationKind.HIGHLIGHT, edited.kind)
            assertEquals("Why this matters", edited.body)
            assertEquals("quillfeather1x1", edited.selectedText)
            awaitUntil(p, what = "blue tint") { p.js("document.querySelector('[data-group=\"shelfos.annotations\"]').innerHTML.indexOf('30, 136, 229')>=0") == "true" }
            assertEquals(listOf("org.readium.r2.navigator.Decoration.Style.Highlight"), p.decorationStyles())

            // Remove the body: still a highlight. Then delete with confirmation.
            p.tap(x, y)
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextReplacement("")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            val cleared = awaitStored { it.single().body == null }.single()
            assertEquals(AnnotationKind.HIGHLIGHT, cleared.kind)
            p.tap(x, y)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("annotation_editor_delete").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("annotation_editor_delete").performClick()
            compose.onNodeWithTag("annotation_delete_confirm").performClick()
            awaitStored { it.isEmpty() }
            awaitDecorations(p, 0)
        }
    }

    @Test fun editingASelectionNoteChangesItsBodyAndANoteCannotBeSavedBlank() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            assertTrue(p.longPressToken("quillfeather1x3")); assertTrue(p.tapText("Add note"))
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextInput("first")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            awaitStored { it.size == 1 }; awaitDecorations(p, 1)
            val (x, y) = p.centerOf(p.tokenScreenRect("quillfeather1x3")!!)
            p.tap(x, y)
            awaitEditor()
            compose.onNodeWithText("Edit note").assertExists()
            compose.onNodeWithTag("annotation_body").performTextReplacement("")
            compose.onNodeWithTag("annotation_editor_save").assertIsNotEnabled()
            compose.onNodeWithTag("annotation_body").performTextReplacement("second")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            val edited = awaitStored { it.single().body == "second" }.single()
            assertEquals(AnnotationKind.NOTE, edited.kind)
            assertEquals("quillfeather1x3", edited.selectedText)
        }
    }

    @Test fun listShowsKindsExcerptsAndDeletesWithConfirmationAndBookmarksKeepTheirSemantics() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            highlightByLongPress(p, "quillfeather1x1"); awaitStored { it.size == 1 }
            assertTrue(p.longPressToken("quillfeather1x3")); assertTrue(p.tapText("Add note"))
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextInput("note body text")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            awaitStored { it.size == 2 }
            compose.onNodeWithText("Bookmarks").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Add bookmark").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add bookmark").performClick()
            awaitStored { it.size == 3 }
            closeDialog()
            compose.onNodeWithTag("epub_notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 3 }
            compose.onNode(hasContentDescription("Highlight, Amber, quillfeather1x1", substring = true) and hasClickAction()).assertExists()
            compose.onNode(hasContentDescription("Note, quillfeather1x3, note body text", substring = true) and hasClickAction()).assertExists()
            compose.onNode(hasContentDescription("Bookmark, ", substring = true) and hasClickAction()).assertExists()
            // Accepted 4B separation: the row SHOWS "N% through book" but SPEAKS "N percent through book".
            compose.onNode(hasText("% through book", substring = true) and hasContentDescription("Bookmark, ", substring = true) and hasClickAction()).assertExists()
            compose.onNode(hasContentDescription("Bookmark, ", substring = true) and hasContentDescription(" percent through book", substring = true) and hasClickAction()).assertExists()
            compose.onAllNodes(hasContentDescription("%", substring = true)).assertCountEquals(0)
            // Filters.
            compose.onNode(hasText("Highlights") and hasClickAction()).performClick(); compose.waitUntil(5_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("notebook_filter_bookmarks").performClick(); compose.waitUntil(5_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
            compose.onNode(hasText("All") and hasClickAction()).performClick(); compose.waitUntil(5_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 3 }
            // A bookmark row is deleted directly (existing semantics); other kinds ask first.
            compose.onNode(hasContentDescription("Delete Bookmark", substring = true)).performClick()
            awaitStored { it.size == 2 }
            compose.onNode(hasContentDescription("Delete Highlight", substring = true)).performClick()
            compose.onNodeWithText("Delete this item?").assertExists()
            compose.onNodeWithText("Cancel").performClick(); compose.waitForIdle()
            assertEquals(2, stored().size)
            compose.onNode(hasContentDescription("Delete Highlight", substring = true)).performClick()
            compose.onNodeWithTag("annotation_delete_confirm").performClick()
            awaitStored { it.size == 1 && it.single().kind == AnnotationKind.NOTE }
            awaitUntil(p, what = "highlight decoration removed") { p.decorationCount() == 1 }
            // The list's Edit opens the editor for the note.
            compose.onNode(hasContentDescription("Edit Note", substring = true)).performClick()
            awaitEditor()
            compose.onNodeWithText("Edit note").assertExists()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Test fun annotationsRestoreAfterRecreationAndAFreshOpen() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            highlightByLongPress(p, "quillfeather1x1"); awaitStored { it.size == 1 }
            assertTrue(p.longPressToken("quillfeather1x3")); assertTrue(p.tapText("Add note"))
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextInput("persisted")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            awaitStored { it.size == 2 }; awaitDecorations(p, 2)
            scenario.recreate()
            val after = probe(scenario); awaitReader(after)
            awaitDecorations(after, 2)
            assertTrue(after.tokenDecorated("quillfeather1x1")); assertTrue(after.tokenDecorated("quillfeather1x3"))
            assertEquals(setOf("Highlight", "Underline"), after.decorationStyles().map { it.substringAfterLast('.') }.toSet())
        }
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            awaitDecorations(p, 2)
            assertTrue(p.tokenDecorated("quillfeather1x1")); assertTrue(p.tokenDecorated("quillfeather1x3"))
        }
    }

    @Test fun decorationsFollowScrollModeLargeTypographyAndDarkPalette() {
        val base = ReaderPreferences.DEFAULT
        val cases = listOf("scroll" to base.copy(scroll = true), "large font" to base.copy(fontSize = 1.8),
            "dark" to base.copy(palette = PagePalette.DARK), "paper" to base.copy(palette = PagePalette.PAPER))
        cases.forEach { (label, prefs) ->
            reseed(prefs = prefs)
            insert(AnnotationKind.HIGHLIGHT, GoldenSelectionLocators.WORD_FIRST_PARAGRAPH, "quillfeather1x1", style = "amber")
            launch().use { scenario ->
                val p = probe(scenario); awaitReader(p)
                awaitDecorations(p, 1)
                assertTrue("$label: decoration sits on the exact text", p.tokenDecorated("quillfeather1x1"))
                val tint = when (label) { "dark" -> "255, 202, 40"; "paper" -> "230, 149, 0"; else -> "255, 179, 0" }
                assertTrue("$label: tint $tint prefs=" + runBlocking { container.library.publication(id).first()?.preferences } + " in " + p.jsString("document.querySelector('[data-group=\"shelfos.annotations\"]').innerHTML.substring(0,400)"), p.js("document.querySelector('[data-group=\"shelfos.annotations\"]').innerHTML.indexOf('$tint')>=0") == "true")
            }
            runBlocking { container.annotations.deleteForPublication(id, KnowledgePolicy.DELETE) }
        }
    }

    @Test fun processDeathSeedPhase() {
        if (!(args.getString("deathPhase") == "seed")) { android.util.Log.i("EpubAnnotationsTest", "process-death pair only runs when started with -e deathPhase seed"); return }
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            highlightByLongPress(p, "quillfeather1x1"); awaitStored { it.size == 1 }
            assertTrue(p.longPressToken("quillfeather1x3")); assertTrue(p.tapText("Add note"))
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextInput("survives process death")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            awaitStored { it.size == 2 }; awaitDecorations(p, 2)
        }
    }

    @Test fun processDeathRestorePhase() {
        if (!(args.getString("deathPhase") == "restore")) { android.util.Log.i("EpubAnnotationsTest", "process-death pair only runs when started with -e deathPhase restore"); return }
        assertEquals("annotations were persisted by the previous process", 2, stored().size)
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            awaitDecorations(p, 2)
            assertTrue(p.tokenDecorated("quillfeather1x1")); assertTrue(p.tokenDecorated("quillfeather1x3"))
        }
        runBlocking { container.library.remove(id) }
    }

    // ---------------------------------------------------------------- stale anchors in the real reader

    @Test fun unplacedAnnotationStaysStoredAndListedAndIsNeverDrawnOnOtherText() {
        reseed("MISSING")
        val placeable = insert(AnnotationKind.HIGHLIGHT, GoldenSelectionLocators.WORD_FIRST_PARAGRAPH, "quillfeather1x1", style = "amber")
        val stale = insert(AnnotationKind.HIGHLIGHT, GoldenSelectionLocators.REPEATED_PHRASE_PARAGRAPH_6, "Marker quillfeather1x6 stands alone in this line. The quiet harbor lies", style = "green")
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            awaitDecorations(p, 1)
            p.settle(2_500)
            assertEquals("the stale annotation is not drawn anywhere (Readium would approximate it)", 1, p.decorationCount())
            assertTrue(p.tokenDecorated("quillfeather1x1"))
            compose.onNodeWithTag("epub_notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 2 }
            assertEquals(1, compose.onAllNodesWithTag("notebook_unavailable", useUnmergedTree = true).fetchSemanticsNodes().size)
            compose.onNodeWithText("Text location unavailable", useUnmergedTree = true).assertExists()
            // Untouched in storage; listed and marked unavailable; NOT jumpable (the controller is never asked); editable; deletable.
            assertEquals(stale.locatorJson, stored().first { it.id == stale.id }.locatorJson)
            val staleRow = hasContentDescription("quillfeather1x6", substring = true)
            val placeableRow = hasContentDescription("quillfeather1x1", substring = true)
            compose.onNode(staleRow).assertIsNotEnabled()
            assertTrue("the unavailable location is part of the row's accessibility description",
                compose.onAllNodes(staleRow and hasContentDescription("Text location unavailable", substring = true)).fetchSemanticsNodes().size == 1)
            compose.onNode(placeableRow).assertIsEnabled()
            val visibleBefore = p.tokenVisible("quillfeather1x1")
            compose.onNode(staleRow).performClick()
            p.settle(1_500)
            assertEquals("the Notebook did not close as if navigation succeeded", 2, compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size)
            assertTrue("no 'could not be opened' message: nothing was attempted", compose.onAllNodesWithText("could not be opened", substring = true).fetchSemanticsNodes().isEmpty())
            assertFalse("reader did not move to the stale text", p.tokenVisible("quillfeather1x6"))
            assertEquals("reader location unchanged", visibleBefore, p.tokenVisible("quillfeather1x1"))
            assertEquals("stored locator untouched after the attempt", stale.locatorJson, stored().first { it.id == stale.id }.locatorJson)
            // Edit stays reachable on the unavailable row; cancelling changes nothing.
            compose.onAllNodes(hasContentDescription("Edit Highlight", substring = true))[1].performClick()
            awaitEditor()
            compose.onNodeWithText("Cancel").performClick(); compose.waitForIdle()
            assertEquals(2, stored().size)
            compose.onNodeWithTag("epub_notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 2 }
            compose.onAllNodes(hasContentDescription("Delete Highlight", substring = true))[1].performClick()
            compose.onNodeWithTag("annotation_delete_confirm").performClick()
            awaitStored { it.size == 1 && it.single().id == placeable.id }
        }
    }

    @Test fun aRepeatedPhraseIsDrawnOnlyInItsOwnParagraph() {
        insert(AnnotationKind.HIGHLIGHT, locator("Paragraph 6 of chapter 1. Marker quillfeather1x6 stands alone in this line. ", "The quiet harbor lies", " beyond the old lighthouse, and the quiet harbor waits for the tide."),
            "The quiet harbor lies", style = "rose")
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            pageUntilVisible(p, "quillfeather1x6")
            awaitDecorations(p, 1)
            val (occurrences, covered) = p.coveredOccurrences("quiet harbor lies")
            assertTrue("several paragraphs repeat the phrase on this page ($occurrences)", occurrences >= 2)
            assertEquals(listOf("quillfeather1x6"), covered)
        }
    }

    // ---------------------------------------------------------------- jump

    @Test fun jumpingToAnAnnotationIsDeterministicInPaginatedMode_thirtyRepeatedJumps() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            // Real selections as targets: first page, a later page and a page in each of chapters 2 and 3.
            val targets = listOf("quillfeather1x1", "quillfeather1x20", "quillfeather2x1", "quillfeather3x12")
            highlightByLongPress(p, "quillfeather1x1"); awaitStored { it.size == 1 }
            pageUntilVisible(p, "quillfeather1x20"); highlightByLongPress(p, "quillfeather1x20"); awaitStored { it.size == 2 }
            openChapter(p, "Chapter 2"); pageUntilVisible(p, "quillfeather2x1"); highlightByLongPress(p, "quillfeather2x1"); awaitStored { it.size == 3 }
            openChapter(p, "Chapter 3"); pageUntilVisible(p, "quillfeather3x12"); highlightByLongPress(p, "quillfeather3x12"); awaitStored { it.size == 4 }
            // A fixed, varied order with no immediate repeat, so every jump leaves a different page or chapter behind.
            val order = listOf(0, 2, 1, 3, 0, 3, 2, 1, 0, 1, 3, 2, 0, 2, 3, 1, 2, 0, 3, 1, 0, 2, 1, 3, 1, 0, 3, 0, 2, 1)
            assertEquals(30, order.size)
            val misses = mutableListOf<String>()
            order.forEachIndexed { n, index ->
                val token = targets[index]
                compose.onNodeWithTag("epub_notes").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size >= 1 }
                val row = hasContentDescription(token, substring = true) and hasClickAction()
                compose.onNodeWithTag("notebook_list").performScrollToNode(row)
                compose.onNode(row).performClick()
                // Compact: the Notebook dialog closes on arrival. Expanded (RP5 landscape): the supporting pane stays open beside the reader.
                val pane = compose.onAllNodesWithTag("notebook_pane").fetchSemanticsNodes().isNotEmpty()
                try { if (!pane) compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_list").fetchSemanticsNodes().isEmpty() } }
                catch (e: Throwable) { fail("jump ${n + 1} to $token did not close the list; open-failed message shown=" + compose.onAllNodesWithText("could not be opened", substring = true).fetchSemanticsNodes().isNotEmpty() + " landed=" + p.tokenVisible(token)) }
                var landed = false
                val end = SystemClock.uptimeMillis() + 4_000
                while (!landed && SystemClock.uptimeMillis() < end) { landed = p.tokenVisible(token); if (!landed) p.settle(150) }
                if (!landed) misses += "jump ${n + 1} to $token"
            }
            android.util.Log.i("JUMP_REGRESSION", "30 jumps, misses=${misses.size} $misses")
            assertTrue("every jump must land on its annotated text; missed: $misses", misses.isEmpty())
        }
    }

    @Test fun aMalformedPersistedLocatorFailsTheJumpWithAMessageAndStaysDeletable() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            runBlocking { BookmarkTestView(container.annotations).addBookmark(id, "this is not a locator", 10) }
            compose.onNodeWithTag("epub_notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
            compose.onNode(hasContentDescription("Bookmark, ", substring = true) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("This location could not be opened", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasContentDescription("Delete Bookmark", substring = true)).performClick()
            awaitStored { it.isEmpty() }
        }
    }

    @Test fun aPositionNoteIsJumpableFromTheList() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            openChapter(p, "Chapter 2"); pageUntilVisible(p, "quillfeather2x1")
            compose.onNodeWithTag("epub_notes").performClick()
            compose.onNodeWithTag("notebook_add_note").performClick()
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextInput("remember chapter two")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            awaitStored { it.size == 1 }
            openChapter(p, "Chapter 3")
            assertFalse(p.tokenVisible("quillfeather2x1"))
            compose.onNodeWithTag("epub_notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
            compose.onNode(hasContentDescription("Note at position, remember chapter two", substring = true) and hasClickAction()).performClick()
            awaitUntil(p, what = "back at chapter 2") { p.tokenVisible("quillfeather2x1") }
        }
    }

    // ---------------------------------------------------------------- Back, selection and taps

    @Test fun backDismissesAnActiveSelectionBeforeChromeOrExit_visibleChrome() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            assertTrue(p.longPressToken("quillfeather1x1"))
            assertTrue("a selection is active", selectionText(p).isNotEmpty())
            key(KeyEvent.KEYCODE_BACK)
            awaitUntil(p, what = "selection dismissed") { selectionText(p).isEmpty() }
            assertTrue("chrome is unchanged", chromeVisible())
            assertNotEquals("still in the reader", Lifecycle.State.DESTROYED, scenario.state)
            p.settle(500)
            assertTrue(chromeVisible())
            // With nothing selected, the next Back leaves the reader (chrome visible).
            key(KeyEvent.KEYCODE_BACK)
            awaitUntil(p, 10_000, "reader exited") { scenario.state == Lifecycle.State.DESTROYED }
        }
    }

    @Test fun backDismissesAnActiveSelectionBeforeChromeOrExit_hiddenChrome() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            val wv = p.webView()!!; val loc = IntArray(2); inst.runOnMainSync { wv.getLocationOnScreen(loc) }
            p.tap(loc[0] + wv.width * 0.5f, loc[1] + wv.height * 0.96f)           // centre tap on empty page area toggles chrome
            awaitUntil(p, what = "chrome hidden") { !chromeVisible() }
            assertTrue(p.awaitReady())
            assertTrue(p.longPressToken("quillfeather1x1"))
            assertTrue(selectionText(p).isNotEmpty())
            key(KeyEvent.KEYCODE_BACK)
            awaitUntil(p, what = "selection dismissed") { selectionText(p).isEmpty() }
            p.settle(600)
            assertFalse("one Back during a selection must not reveal chrome", chromeVisible())
            key(KeyEvent.KEYCODE_BACK)
            awaitUntil(p, what = "chrome revealed by the second Back") { chromeVisible() }
            assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun escapeAndGamepadBDismissASelectionTheSameWay() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B).forEach { code ->
                assertTrue(p.longPressToken("quillfeather1x1"))
                assertTrue(selectionText(p).isNotEmpty())
                key(code)
                awaitUntil(p, what = "selection dismissed by key $code") { selectionText(p).isEmpty() }
                assertTrue("chrome unchanged after key $code", chromeVisible())
                assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
                p.settle(400)
            }
        }
    }

    @Test fun keyboardBTogglesCurrentBookmarkButDoesNotLeakIntoSearch() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            key(KeyEvent.KEYCODE_B)
            awaitStored { list -> list.count { it.kind == AnnotationKind.BOOKMARK } == 1 }
            key(KeyEvent.KEYCODE_B)
            awaitStored { list -> list.none { it.kind == AnnotationKind.BOOKMARK } }

            compose.onNodeWithText("Search").performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            key(KeyEvent.KEYCODE_B)
            compose.onNode(hasSetTextAction()).assertTextContains("b", substring = true)
            assertTrue(stored().none { it.kind == AnnotationKind.BOOKMARK })
            assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test fun escapeAndGamepadBDismissTheNotesList() {
        insert(AnnotationKind.HIGHLIGHT, GoldenSelectionLocators.WORD_FIRST_PARAGRAPH, "quillfeather1x1", style = "amber")
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B).forEach { code ->
                compose.onNodeWithTag("epub_notes").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
                key(code)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_list").fetchSemanticsNodes().isEmpty() }
                assertNotEquals("reader survives key $code", Lifecycle.State.DESTROYED, scenario.state)
                assertTrue("reader chrome still present after key $code", chromeVisible())
            }
        }
    }

    @Test fun sharedEditorSupportsControllerSaveAndBackEscapeGamepadBCancel() {
        insert(AnnotationKind.HIGHLIGHT, GoldenSelectionLocators.WORD_FIRST_PARAGRAPH, "quillfeather1x1", style = "amber")
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            compose.onNodeWithTag("epub_notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
            compose.onNode(hasContentDescription("Edit Highlight", substring = true)).performClick()
            awaitEditor()
            compose.onNodeWithTag("annotation_body").assertIsFocused().performTextInput("controller saved")
            gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
            compose.onNodeWithTag("annotation_editor_save").assertIsFocused()
            assertNull("the first A must not save", stored().single().body)
            gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
            compose.waitUntil(10_000) { !editorOpen() }
            awaitStored { it.singleOrNull()?.body == "controller saved" }

            listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B).forEach { code ->
                compose.onNodeWithTag("epub_notes").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
                compose.onNode(hasContentDescription("Edit Highlight", substring = true)).performClick()
                awaitEditor()
                compose.onNodeWithTag("annotation_body").assertIsFocused().performTextInput("unsaved")
                if (code == KeyEvent.KEYCODE_BACK) compose.hideSoftKeyboardIfShown() // the keyboard's own Back comes first
                key(code)
                try { compose.waitUntil(10_000) { !editorOpen() } } catch (e: Throwable) { fail("editor not dismissed by key $code") }
                assertEquals("cancelling by key $code changed nothing", 1, stored().size)
                assertEquals("cancelling by key $code must not save the draft", "controller saved", stored().single().body)
                assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
                assertTrue("reader chrome still present after key $code", chromeVisible())
            }
        }
    }

    @Test fun androidBackEscapeAndGamepadBDismissDeleteConfirmationWithoutDeleting() {
        insert(AnnotationKind.HIGHLIGHT, GoldenSelectionLocators.WORD_FIRST_PARAGRAPH, "quillfeather1x1", style = "amber")
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            compose.onNodeWithTag("epub_notes").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }
            listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B).forEach { code ->
                compose.onNode(hasContentDescription("Delete Highlight", substring = true)).performClick()
                compose.onNodeWithTag("annotation_delete_confirm").assertExists()
                key(code)
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("annotation_delete_confirm").fetchSemanticsNodes().isEmpty() }
                assertEquals("dismissal by key $code must not delete", 1, stored().size)
                compose.onNodeWithTag("notebook_list").assertExists()
                assertNotEquals("reader survives key $code", Lifecycle.State.DESTROYED, scenario.state)
            }
        }
    }

    @Test fun edgeTapsStillTurnPagesCenterTapStillTogglesChromeAndSelectionStillWorksWithDecorationsPresent() {
        launch().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            highlightByLongPress(p, "quillfeather1x1"); awaitStored { it.size == 1 }; awaitDecorations(p, 1)
            val wv = p.webView()!!; val loc = IntArray(2); inst.runOnMainSync { wv.getLocationOnScreen(loc) }
            // Right edge turns the page: the first paragraph leaves the screen.
            p.tap(loc[0] + wv.width * 0.97f, loc[1] + wv.height * 0.5f)
            awaitUntil(p, what = "page turned") { !p.tokenVisible("quillfeather1x1") }
            assertTrue("an edge tap does not toggle chrome", chromeVisible())
            p.tap(loc[0] + wv.width * 0.03f, loc[1] + wv.height * 0.5f)
            awaitUntil(p, what = "page turned back") { p.tokenVisible("quillfeather1x1") }
            // Centre tap on plain (undecorated) text toggles chrome.
            p.tap(loc[0] + wv.width * 0.5f, loc[1] + wv.height * 0.96f)
            awaitUntil(p, what = "chrome toggled by centre tap") { !chromeVisible() }
            assertTrue(p.awaitReady())
            // Selection still works next to a decoration.
            assertTrue(p.longPressToken("quillfeather1x5"))
            assertTrue(selectionText(p).isNotEmpty())
        }
    }

    // ---------------------------------------------------------------- legacy WebView fallback

    @Test fun legacyWebViewKeepsPositionNotesAndBookmarksButOffersNoInlineAnnotations() {
        insert(AnnotationKind.HIGHLIGHT, GoldenSelectionLocators.WORD_FIRST_PARAGRAPH, "quillfeather1x1", style = "amber")
        launchLegacy().use { scenario ->
            val p = probe(scenario); awaitReader(p)
            p.settle(3_000)
            assertEquals("no decoration is ever applied on a legacy WebView", 0, p.decorationCount())
            // No inline Highlight / Add note in the selection toolbar; the platform's own selection remains.
            assertTrue(p.longPressToken("quillfeather1x2"))
            assertNull("Highlight must not be offered", p.findByText("Highlight", 1_500))
            assertNull("Add note must not be offered", p.findByText("Add note", 1_000))
            // Back still dismisses the (platform) selection first.
            key(KeyEvent.KEYCODE_BACK)
            awaitUntil(p, what = "selection dismissed") { selectionText(p).isEmpty() }
            assertTrue(chromeVisible())
            // Notes explains the limitation and still creates position notes.
            compose.onNodeWithTag("epub_notes").performClick()
            compose.onNodeWithTag("annotation_inline_unavailable").assertExists()
            compose.onNodeWithText("Inline text annotations require a newer compatible Android System WebView", substring = true).assertExists()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_row").fetchSemanticsNodes().size == 1 }   // the stored highlight is still listed
            compose.onNodeWithTag("notebook_add_note").performClick()
            awaitEditor()
            compose.onNodeWithTag("annotation_body").performTextInput("works without inline support")
            compose.onNodeWithTag("annotation_editor_save").performClick()
            awaitStored { it.size == 2 }
            // Bookmarks are unaffected.
            compose.onNode(hasText("Bookmarks") and hasClickAction() and !hasAnyAncestor(hasTestTag("notebook_panel"))).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Add bookmark").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Add bookmark").performClick()
            awaitStored { s -> s.count { it.kind == AnnotationKind.BOOKMARK } == 1 }
            assertEquals(0, p.decorationCount())
        }
    }

    // ---------------------------------------------------------------- scale (production path)

    private fun seedLarge(count: Int, chapters: Int = 1): List<String> {
        reseed("LARGE")
        val perChapter = count / chapters
        val tokens = mutableListOf<String>()
        (1..chapters).forEach { c ->
            SyntheticAnnotationEpub.largeChapterLocators(c, perChapter).forEachIndexed { i, (token, json) ->
                tokens += token
                insert(AnnotationKind.HIGHLIGHT, json, token, style = AnnotationStyles.keys[i % 4])
            }
        }
        return tokens
    }

    /**
     * Measures main-thread responsiveness from a BACKGROUND thread (it posts a no-op to the main looper every 20 ms and records
     * how late it ran), so the main looper still idles between probes and the Compose test rule can synchronize.
     */
    private class FrameGapSampler {
        @Volatile var maxGapMs = 0L; @Volatile var gapsOver100 = 0
        @Volatile private var running = false
        private var thread: Thread? = null
        fun start() {
            running = true
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            thread = Thread {
                while (running) {
                    val posted = SystemClock.uptimeMillis()
                    handler.post { val late = SystemClock.uptimeMillis() - posted; if (late > maxGapMs) maxGapMs = late; if (late > 100) gapsOver100++ }
                    SystemClock.sleep(20)
                }
            }.also { it.isDaemon = true; it.start() }
        }
        fun stop() { running = false; thread?.join(500) }
    }

    @Test fun fiveHundredStoredHighlightsAreVerifiedAndDrawnThroughTheProductionPath() {
        val tokens = seedLarge(500)
        val sampler = FrameGapSampler()
        val started = SystemClock.uptimeMillis()
        sampler.start()
        try { launch().use { scenario ->
            val p = probe(scenario)
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("epub_notes").fetchSemanticsNodes().isNotEmpty() }
            awaitUntil(p, 60_000, "500 decorations (have ${p.decorationCount()})") { p.decorationCount() >= 500 }
            val ms = SystemClock.uptimeMillis() - started
            sampler.stop()
            android.util.Log.i("SCALE_500", "500 stored highlights: all drawn $ms ms after launch call; main-thread max gap ${sampler.maxGapMs} ms; gaps>100ms=${sampler.gapsOver100}")
            assertEquals(500, p.decorationCount())
            assertTrue("main thread stayed responsive (max gap ${sampler.maxGapMs} ms)", sampler.maxGapMs < 1_000)
            assertTrue(p.tokenDecorated(tokens[0]))
            // The reader remains usable: a page turn still works with 500 decorations applied.
            compose.onNodeWithText("Next").performClick(); p.settle(800)
            assertTrue(p.awaitReady())
        } } finally { sampler.stop() }
    }

    @Test fun twoThousandStoredHighlightsAcrossFourChaptersStressEvidence() {
        if (!(args.getString("stress2000") == "true")) { android.util.Log.i("EpubAnnotationsTest", "2,000-annotation stress evidence only runs with -e stress2000 true"); return }
        val tokens = seedLarge(2_000, chapters = 4)
        val sampler = FrameGapSampler(); val started = SystemClock.uptimeMillis(); sampler.start()
        try { launch().use { scenario ->
            val p = probe(scenario)
            compose.waitUntil(60_000) { compose.onAllNodesWithTag("epub_notes").fetchSemanticsNodes().isNotEmpty() }
            awaitUntil(p, 120_000, "chapter 1 decorations (have ${p.decorationCount()})") { p.decorationCount() >= 500 }
            val ms = SystemClock.uptimeMillis() - started
            sampler.stop()
            android.util.Log.i("SCALE_2000", "2,000 stored highlights (4 chapters): chapter 1 drawn $ms ms after launch; main-thread max gap ${sampler.maxGapMs} ms; gaps>100ms=${sampler.gapsOver100}; tokens=${tokens.size}")
            assertEquals(500, p.decorationCount())
        } } finally { sampler.stop() }
    }
}
