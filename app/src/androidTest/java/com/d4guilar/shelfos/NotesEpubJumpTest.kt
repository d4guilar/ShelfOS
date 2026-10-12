// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.debug.SyntheticAnnotationEpub
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4D: opening the EPUB reader from the Notes hub at one annotation (the `annotationId` extra of [EpubActivity]).
 * The reader resolves the locator from the repository and navigates through the same verified path as a Notebook jump:
 * a PLACEABLE selection-backed annotation or a position note arrives; an UNPLACED or MALFORMED one, or an unknown id,
 * does not move the reader, shows the explanation and leaves the annotation stored and deletable.
 */
class NotesEpubJumpTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val container get() = (ctx.applicationContext as ShelfApplication).container
    private val id = OriginalFixtures.ANNOTATION_ITEM_ID

    @Before fun seed() = runBlocking<Unit> { reseed("BASE") }
    @After fun cleanup() = runBlocking<Unit> { container.annotations.deleteForPublication(id, KnowledgePolicy.DELETE); container.library.remove(id) }

    private fun reseed(variant: String) = runBlocking<Unit> {
        container.annotations.deleteForPublication(id, KnowledgePolicy.DELETE)
        container.library.remove(id); container.library.add(OriginalFixtures.epubAnnotations(ctx, variant))
    }
    private fun launch(annotationId: String?) = ActivityScenario.launch<EpubActivity>(EpubActivity.intent(ctx, id, annotationId))
    private fun probe(scenario: ActivityScenario<out android.app.Activity>) = AnnotationUiProbe(inst, scenario) { compose.waitForIdle() }
    private fun awaitReader(p: AnnotationUiProbe) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("epub_notes").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("page ready", p.awaitReady())
    }
    private fun awaitUntil(p: AnnotationUiProbe, timeoutMs: Long = 20_000, what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; p.settle(200) }
        fail("timed out waiting for $what")
    }
    private fun create(draft: AnnotationDraft): Annotation = runBlocking { container.annotations.create(draft, validateLocator = false) }
    private fun stored(annotationId: String) = runBlocking { container.annotations.get(annotationId) }
    private val notOpened = "That spot could not be opened. The note is still saved."

    private fun placeableHighlight(): Pair<String, Annotation> {
        reseed("LARGE")
        val (token, locator) = SyntheticAnnotationEpub.largeChapterLocators(3, 3)[0]
        return token to create(AnnotationDraft(id, AnnotationKind.HIGHLIGHT, LocatorFormat.READIUM_LOCATOR_1, locator, 60, selectedText = token, styleKey = "amber"))
    }
    private fun savedHref(): String = runBlocking { container.library.publication(id).first() }?.locator.orEmpty()

    /**
     * A selection-backed highlight at the first paragraph of chapter 3 (LARGE fixture, exact text triplet): the verifier must call it
     * PLACEABLE before the reader may follow it. Device-agnostic claim: the reader ends up in chapter 3 with no failure message.
     */
    @Test fun aPlaceableHighlightIsFollowedFromAColdLaunchToItsChapter() {
        val (_, target) = placeableHighlight()
        launch(target.id).use { scenario ->
            val p = probe(scenario); awaitReader(p)
            awaitUntil(p, 30_000, "the saved position to move into chapter 3 (saved=${savedHref()})") { savedHref().contains("chapter3.xhtml") }
            assertEquals("a successful jump shows no failure message", 0, compose.onAllNodesWithText(notOpened).fetchSemanticsNodes().size)
        }
    }

    /** The page-exact claim needs a current Chromium WebView (the RP5's); the API 24 emulator's old WebView lands one page late, so this is skipped there rather than weakened. */
    @Test fun aPlaceableHighlightLandsOnItsHighlightedText() {
        org.junit.Assume.assumeTrue("needs a current WebView (API 26+)", android.os.Build.VERSION.SDK_INT >= 26)
        val (token, target) = placeableHighlight()
        launch(target.id).use { scenario ->
            val p = probe(scenario); awaitReader(p)
            awaitUntil(p, 30_000, "the highlighted text of chapter 3") { p.tokenVisible(token) }
            assertEquals(0, compose.onAllNodesWithText(notOpened).fetchSemanticsNodes().size)
        }
    }

    @Test fun aPositionNoteIsReachedWithoutAnAnchorVerdict() {
        val note = create(AnnotationDraft(id, AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1,
            """{"href":"chapter2.xhtml","type":"application/xhtml+xml","title":"Chapter 2","locations":{"progression":0.0,"position":2,"totalProgression":0.34}}""", 34, body = "remember chapter two"))
        launch(note.id).use { scenario ->
            val p = probe(scenario); awaitReader(p)
            awaitUntil(p, 30_000, "chapter 2 start") { p.tokenVisible("quillfeather2x1") }
        }
    }

    @Test fun anUnplacedHighlightDoesNotMoveTheReaderAndExplainsWhy() {
        reseed("MISSING")
        val stale = create(AnnotationDraft(id, AnnotationKind.HIGHLIGHT, LocatorFormat.READIUM_LOCATOR_1, GoldenSelectionLocators.REPEATED_PHRASE_PARAGRAPH_6, 5,
            selectedText = "Marker quillfeather1x6 stands alone in this line. The quiet harbor lies", styleKey = "green"))
        launch(stale.id).use { scenario ->
            val p = probe(scenario); awaitReader(p)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(notOpened).fetchSemanticsNodes().isNotEmpty() }
            assertFalse("the reader never approximated the stale text", p.tokenVisible("quillfeather1x6"))
            assertEquals("stored locator untouched", stale.locatorJson, stored(stale.id)!!.locatorJson)
        }
    }

    @Test fun aMalformedLocatorAndAnUnknownIdExplainAndKeepTheAnnotation() {
        val bad = create(AnnotationDraft(id, AnnotationKind.BOOKMARK, LocatorFormat.READIUM_LOCATOR_1, "this is not a locator", 10))
        launch(bad.id).use { scenario ->
            val p = probe(scenario); awaitReader(p)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(notOpened).fetchSemanticsNodes().isNotEmpty() }
            assertNotNull(stored(bad.id))
        }
        launch("deleted-annotation-id").use { scenario ->
            val p = probe(scenario); awaitReader(p)
            compose.waitUntil(30_000) { compose.onAllNodesWithText(notOpened).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun anAnnotationOfAnotherPublicationIsNeverFollowed() {
        val other = "notes-jump-other"
        runBlocking { container.library.add(OriginalFixtures.epub(ctx).copy(id = other, sourceUri = "test:$other")) }
        try {
            val foreign = runBlocking { container.annotations.create(AnnotationDraft(other, AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1,
                """{"href":"chapter3.xhtml","type":"application/xhtml+xml","locations":{"progression":0.0,"position":3,"totalProgression":0.67}}""", 67, body = "elsewhere")) }
            launch(foreign.id).use { scenario ->
                val p = probe(scenario); awaitReader(p)
                compose.waitUntil(30_000) { compose.onAllNodesWithText(notOpened).fetchSemanticsNodes().isNotEmpty() }
                assertFalse(p.tokenVisible("quillfeather3x1"))
            }
        } finally { runBlocking { container.annotations.deleteForPublication(other, KnowledgePolicy.DELETE); container.library.remove(other) } }
    }

    @Test fun recreationDoesNotJumpAgain() {
        val note = create(AnnotationDraft(id, AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1,
            """{"href":"chapter3.xhtml","type":"application/xhtml+xml","title":"Chapter 3","locations":{"progression":0.0,"position":3,"totalProgression":0.67}}""", 67, body = "chapter three"))
        launch(note.id).use { scenario ->
            val p = probe(scenario); awaitReader(p)
            awaitUntil(p, 30_000, "chapter 3 start") { p.tokenVisible("quillfeather3x1") }
            // Move away on purpose, then rotate/recreate: the one-shot jump must not pull the reader back.
            compose.onNodeWithText("Chapters").performClick()
            compose.onNode(hasText("Chapter 1") and hasClickAction() and !hasSetTextAction()).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size == 1 }
            awaitUntil(p, 20_000, "chapter 1 start") { p.tokenVisible("quillfeather1x1") }
            scenario.recreate()
            val after = probe(scenario); awaitReader(after)
            after.settle(2_500)
            assertFalse("the reader stayed where the user left it", after.tokenVisible("quillfeather3x1"))
        }
    }
}
