// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.debug.SyntheticAnnotationEpub
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.publication.Locator

/**
 * Phase 4B stale-anchor protection against the REAL session: the real publication container, the real
 * [sanitizeEpubHtml] transform, real Readium `Locator` JSON. Selection locators persisted from real device selections
 * ([GoldenSelectionLocators]) must stay verifiable, changed content must never be placed approximately, and verifying
 * hundreds of annotations must parse each chapter once. No UI, no WebView.
 */
class EpubAnchorVerifierInstrumentedTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (ctx.applicationContext as ShelfApplication).container
    private val sessions = mutableListOf<EpubSession>()

    @After fun close() { sessions.forEach { it.close() }; sessions.clear() }

    private fun session(variant: String): EpubSession = runBlocking { container.epubs.open(OriginalFixtures.epubAnnotations(ctx, variant)) }.also { sessions += it }
    private fun verdicts(variant: String, locators: List<String> = GoldenSelectionLocators.ALL) =
        runBlocking { session(variant).anchorVerifier.verifyAll(locators) }.mapValues { it.value.status }
    private fun status(variant: String, locator: String) = verdicts(variant, listOf(locator)).getValue(locator)

    @Test fun locatorJsonRoundTripsLosslesslyAndMapsToADraft() {
        GoldenSelectionLocators.ALL.forEach { json ->
            val locator = Locator.fromJSON(JSONObject(json))!!
            assertEquals("lossless round trip", json, locator.toJSON().toString())
            val draft = (buildSelectionDraft(EpubSelectionRead(json, locator.text.highlight, locator.locations.totalProgression)) as SelectionDraftResult.Ready).draft
            assertEquals("locatorJson stays verbatim", json, draft.locatorJson)
            assertEquals(SelectedText.normalize(locator.text.highlight), draft.selectedText)
            assertFalse(draft.selectedText.contains("  "))
        }
        val nonAscii = Locator.fromJSON(JSONObject(GoldenSelectionLocators.NON_ASCII_PHRASE))!!
        assertEquals("Café naïve “quoted words", nonAscii.text.highlight)
    }

    @Test fun currentSanitizerKeepsPersistedSelectionLocatorsVerifiable() {
        val result = verdicts("BASE")
        GoldenSelectionLocators.ALL.forEach { assertEquals("golden must stay PLACEABLE: ${JSONObject(it).getJSONObject("text").getString("highlight")}", AnchorStatus.PLACEABLE, result.getValue(it)) }
    }

    @Test fun removedTextIsUnplacedNeverApproximated() {
        // MISSING rewrites paragraph 7. Readium would silently draw these elsewhere (spike evidence); ShelfOS must not.
        val missing = verdicts("MISSING")
        assertEquals(AnchorStatus.UNPLACED, missing.getValue(GoldenSelectionLocators.REPEATED_PHRASE_PARAGRAPH_6))   // its trailing context named paragraph 7
        assertEquals(AnchorStatus.UNPLACED, missing.getValue(GoldenSelectionLocators.SENTENCE_PARAGRAPH_8))          // its leading context named paragraph 7
        // Annotations whose text and context are untouched are still placed.
        assertEquals(AnchorStatus.PLACEABLE, missing.getValue(GoldenSelectionLocators.WORD_FIRST_PARAGRAPH))
        assertEquals(AnchorStatus.PLACEABLE, missing.getValue(GoldenSelectionLocators.WORD_LATER_PARAGRAPH))
        // A locator for text that exists nowhere in this publication.
        val ghost = GoldenSelectionLocators.WORD_FIRST_PARAGRAPH.replace("quillfeather1x1", "quillfeather9x9")
        assertEquals(AnchorStatus.UNPLACED, status("BASE", ghost))
    }

    @Test fun insertedContentElsewhereKeepsAnchorsWhoseContextIsIntact() {
        val shifted = verdicts("SHIFTED")
        GoldenSelectionLocators.ALL.forEach { assertEquals(AnchorStatus.PLACEABLE, shifted.getValue(it)) }
    }

    @Test fun aWeakerSanitizerThatKeepsFormTextChangesContextAndUnplacesAffectedAnchors() {
        // Documented sanitizer coupling: the rendition text changes around the form, so anchors that captured the old
        // context are reported unplaced (never silently moved); anchors elsewhere are unaffected.
        val kept = verdicts("FORMKEPT")
        assertEquals(AnchorStatus.UNPLACED, kept.getValue(GoldenSelectionLocators.WORD_AFTER_FORM))
        assertEquals(AnchorStatus.PLACEABLE, kept.getValue(GoldenSelectionLocators.WORD_LATER_PARAGRAPH))
    }

    @Test fun unknownResourceMalformedJsonAndTextlessLocatorsAreClassifiedNotCrashed() {
        val elsewhere = GoldenSelectionLocators.WORD_FIRST_PARAGRAPH.replace("chapter1.xhtml", "no-such-chapter.xhtml")
        assertEquals(AnchorStatus.UNPLACED, status("BASE", elsewhere))
        val result = verdicts("BASE", listOf("not json at all", """{"href":"chapter1.xhtml","locations":{"progression":0.2}}""", "{}"))
        assertEquals(AnchorStatus.MALFORMED, result.getValue("not json at all"))
        assertEquals(AnchorStatus.MALFORMED, result.getValue("""{"href":"chapter1.xhtml","locations":{"progression":0.2}}"""))   // a position locator carries no text anchor
        assertEquals(AnchorStatus.MALFORMED, result.getValue("{}"))
    }

    @Test fun fiveHundredAnnotationsInOneChapterParseThatChapterOnceAndStayFast() {
        val session = session("LARGE")
        val locators = SyntheticAnnotationEpub.largeChapterLocators(chapter = 1, count = 500).map { it.second }
        val t0 = System.nanoTime()
        val result = runBlocking { session.anchorVerifier.verifyAll(locators) }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        android.util.Log.i("ANCHOR_PERF", "500 anchors verified in $elapsedMs ms, resource parses=${session.anchorVerifier.parseCount}")
        assertEquals(500, result.size)
        assertTrue("every synthetic anchor is exact", result.values.all { it.status == AnchorStatus.PLACEABLE })
        assertEquals("the chapter is parsed once, not once per annotation", 1, session.anchorVerifier.parseCount)
        // A second pass is served entirely from cache.
        runBlocking { session.anchorVerifier.verifyAll(locators) }
        assertEquals(1, session.anchorVerifier.parseCount)
        assertTrue("500 verifications took $elapsedMs ms", elapsedMs < 5_000)
        // Orders every annotation by reading position: fractions are strictly increasing along the document.
        val fractions = locators.map { runBlocking { session.anchorVerifier.verify(it) }.textFraction!! }
        assertEquals(fractions.sorted(), fractions)
    }

    @Test fun annotationsAcrossFourChaptersParseEachChapterOnce() {
        val session = session("LARGE")
        val locators = (1..4).flatMap { c -> SyntheticAnnotationEpub.largeChapterLocators(chapter = c, count = 120).map { it.second } }
        val result = runBlocking { session.anchorVerifier.verifyAll(locators) }
        assertTrue(result.values.all { it.status == AnchorStatus.PLACEABLE })
        assertEquals(4, session.anchorVerifier.parseCount)
    }

    @Test fun resourceRangeAndOrderKeyDerivationAreConsistent() {
        val session = session("BASE")
        val ranges = listOf("chapter1.xhtml", "chapter2.xhtml", "chapter3.xhtml").map { runBlocking { session.resourceRange(it) } }
        assertTrue(ranges.all { it != null })
        assertEquals(0.0, ranges[0]!!.start, 0.0)
        assertEquals("ranges tile the book", ranges[0]!!.endInclusive, ranges[1]!!.start, 1e-9)
        assertEquals(1.0, ranges[2]!!.endInclusive, 0.0)
        assertNull(runBlocking { session.resourceRange("missing.xhtml") })
    }
}
