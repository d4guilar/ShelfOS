// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4B stale-anchor protection. Verification must be exact (never approximate), run against the SANITIZED rendition,
 * and never place an annotation on other text. Readium's own text-quote anchoring silently approximates; ShelfOS must not.
 */
class AnchorTextTest {
    // The same shape the instrumented fixture uses: script and form text exist in the source but not in the rendition.
    private fun paragraph(p: Int): String = when (p) {
        2 -> "<p>Before script.<script>var trap = 1;</script> After script, marker quillfeather1x2 stands alone.</p>"
        3 -> "<p>Form lead <form><span>formtext inside the form</span></form> form tail, marker quillfeather1x3 stands alone.</p>"
        4 -> "<p>A <em>hidden emphasis phrase</em> spans inline markup, marker quillfeather1x4 stands alone.</p>"
        else -> "<p>Paragraph $p of chapter 1. Marker quillfeather1x$p stands alone in this line. The quiet harbor lies beyond the old lighthouse, and the quiet harbor waits for the tide.</p>"
    }
    private fun chapter(mutate: (Int, String) -> String = { _, p -> p }): String =
        """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 1</title></head><body><h1>Chapter 1</h1><p>First words of chapter 1: the opening tide.</p>""" +
            (1..30).joinToString("") { mutate(it, paragraph(it)) } + "<p>Last words of chapter 1: the closing tide.</p></body></html>"

    /** The comparable text of the CURRENT sanitizer's output, exactly what the verifier sees at runtime. */
    private fun rendition(source: String = chapter()): String =
        AnchorText.visibleText(String(sanitizeEpubHtml(source.toByteArray(), xml = true), Charsets.UTF_8), xml = true)!!

    private val first = rendition()

    @Test fun exactTripletIsPlaceableAndReportsItsRelativePosition() {
        val v = AnchorText.verify(first, "First words of chapter 1: the opening tide.Paragraph 1 of chapter 1. Marker ", "quillfeather1x1", " stands alone in this line. The quiet harbor lies")
        assertEquals(AnchorStatus.PLACEABLE, v.status)
        assertTrue(v.textFraction!! in 0.0..0.1)
        val later = AnchorText.verify(first, "Paragraph 20 of chapter 1. Marker ", "quillfeather1x20", " stands alone in this line.")
        assertEquals(AnchorStatus.PLACEABLE, later.status)
        assertTrue(later.textFraction!! > v.textFraction!!)
    }

    @Test fun blockBreaksInHighlightButNotInContextStillMatch() {
        // Selection.toString() puts newlines at block breaks; body.textContent (used for before/after) does not.
        val v = AnchorText.verify(first, "Paragraph 5 of chapter 1. Marker quillfeather1x5 stands alone in this line. The quiet harbor lies beyond the old lighthouse, and the quiet harbor waits for the ",
            "tide.\n\nParagraph 6 of chapter 1.", " Marker quillfeather1x6 stands")
        assertEquals(AnchorStatus.PLACEABLE, v.status)
    }

    @Test fun repeatedPhraseIsPlacedOnlyWhereItsContextMatches() {
        val inParagraph7 = AnchorText.verify(first, "Paragraph 7 of chapter 1. Marker quillfeather1x7 stands alone in this line. ", "The quiet harbor lies", " beyond the old lighthouse, and the quiet harbor waits")
        assertEquals(AnchorStatus.PLACEABLE, inParagraph7.status)
        val inParagraph9 = AnchorText.verify(first, "Paragraph 9 of chapter 1. Marker quillfeather1x9 stands alone in this line. ", "The quiet harbor lies", " beyond the old lighthouse, and the quiet harbor waits")
        assertEquals(AnchorStatus.PLACEABLE, inParagraph9.status)
        assertTrue(inParagraph9.textFraction!! > inParagraph7.textFraction!!)
        // The same phrase with context from text that does not precede it anywhere is rejected, not approximated.
        val wrongContext = AnchorText.verify(first, "Paragraph 99 of chapter 1. Marker quillfeather1x99 stands alone in this line. ", "The quiet harbor lies", " beyond the old lighthouse")
        assertEquals(AnchorStatus.UNPLACED, wrongContext.status)
    }

    @Test fun contextlessRepeatedPhraseIsAmbiguousAndUnplaced() {
        val v = AnchorText.verify(first, "", "The quiet harbor lies", "")
        assertEquals(AnchorStatus.UNPLACED, v.status)
        val uniqueWithoutContext = AnchorText.verify(first, "", "quillfeather1x11", "")
        assertEquals(AnchorStatus.PLACEABLE, uniqueWithoutContext.status)
    }

    @Test fun missingHighlightedTextIsUnplacedNotApproximated() {
        // Paragraph 7's marker was rewritten: Readium would fuzzy-match this elsewhere; ShelfOS refuses.
        val rewritten = rendition(chapter { n, p -> if (n == 7) "<p>This paragraph was rewritten completely and no longer holds the old marker.</p>" else p })
        val v = AnchorText.verify(rewritten, "Paragraph 7 of chapter 1. Marker ", "quillfeather1x7", " stands alone in this line.")
        assertEquals(AnchorStatus.UNPLACED, v.status)
        assertNull(v.textFraction)
        // Neighbouring annotations are unaffected.
        assertEquals(AnchorStatus.PLACEABLE, AnchorText.verify(rewritten, "Paragraph 8 of chapter 1. Marker ", "quillfeather1x8", " stands alone in this line.").status)
    }

    @Test fun changedContextAroundUnchangedTextIsUnplaced() {
        val changed = rendition(chapter { n, p -> if (n == 12) p.replace("Marker quillfeather1x12", "Marker, newly worded, quillfeather1x12") else p })
        assertEquals(AnchorStatus.UNPLACED, AnchorText.verify(changed, "Paragraph 12 of chapter 1. Marker ", "quillfeather1x12", " stands alone in this line.").status)
        assertEquals(AnchorStatus.PLACEABLE, AnchorText.verify(first, "Paragraph 12 of chapter 1. Marker ", "quillfeather1x12", " stands alone in this line.").status)
    }

    @Test fun insertedTextElsewhereDoesNotBreakAnchorsThatKeepTheirContext() {
        val shifted = rendition(chapter().replace("<h1>Chapter 1</h1>", "<h1>Chapter 1</h1><p>Inserted opening paragraph.</p><p>Another inserted paragraph.</p>"))
        val v = AnchorText.verify(shifted, "Paragraph 20 of chapter 1. Marker ", "quillfeather1x20", " stands alone in this line.")
        assertEquals(AnchorStatus.PLACEABLE, v.status)
    }

    @Test fun anchorMustSitExactlyBetweenItsBeforeAndAfterContext() {
        // Right text, right neighbours individually, but not adjacent to each other: no match.
        assertEquals(AnchorStatus.UNPLACED, AnchorText.verify(first, "Paragraph 3 of chapter 1.", "quillfeather1x10", "stands alone in this line.").status)
    }

    @Test fun invisibleCharactersAndWhitespaceKindsDoNotAffectComparison() {
        val markup = "<html><body><p>Soft\u00ADhyphen word and\u00A0nbsp   spaced\u200B text.</p></body></html>"
        val text = AnchorText.visibleText(markup, xml = false)!!
        assertEquals("Softhyphenwordandnbspspacedtext.", text)
        assertEquals(AnchorStatus.PLACEABLE, AnchorText.verify(text, "Soft hyphen ", "word and nbsp", " spaced text.").status)
    }

    @Test fun blankHighlightIsMalformedNotUnplaced() {
        assertEquals(AnchorStatus.MALFORMED, AnchorText.verify(first, "a", " \n ", "b").status)
        assertEquals(AnchorStatus.MALFORMED, AnchorText.verify(first, "a", null, "b").status)
    }

    // ---- sanitizer coupling ----

    @Test fun sanitizedRenditionExcludesScriptAndFormTextSoAnchorsCannotDependOnThem() {
        assertFalse(first.contains("vartrap"))
        assertFalse(first.contains("formtextinsidetheform"))
        // An anchor captured against a weaker sanitizer that kept form text no longer verifies against this one.
        val weakerSanitizerContext = AnchorText.verify(first, "Form lead formtext inside the form form tail, marker ", "quillfeather1x3", " stands alone")
        assertEquals(AnchorStatus.UNPLACED, weakerSanitizerContext.status)
        // The context actually present in the sanitized rendition verifies.
        assertEquals(AnchorStatus.PLACEABLE, AnchorText.verify(first, "Form lead  form tail, marker ", "quillfeather1x3", " stands alone").status)
    }

    /**
     * Regression: selection locators persisted from real Readium selections (device-captured text triplets, see the
     * instrumented golden test for the Locator JSON form) stay verifiable against the CURRENT sanitizer. A future
     * `sanitizeEpubHtml` change that alters visible text makes this fail, which is the signal that persisted annotations
     * could silently become unplaced and that the change needs a restoration migration plan.
     */
    @Test fun currentSanitizerKeepsPersistedSelectionTripletsVerifiable() {
        val goldens = listOf(
            Triple("First words of chapter 1: the opening tide.Paragraph 1 of chapter 1. Marker ", "quillfeather1x1", " stands alone in this line. The quiet harbor lies beyond the old lighthouse, and the quiet harbor waits for the tide.Before script. After script, marker quillfeather1x2 stands alone.Form lead  form"),
            Triple("Before script. After script, marker ", "quillfeather1x2", " stands alone.Form lead  form tail, marker quillfeather1x3 stands alone.A hidden emphasis phrase spans inline markup, marker quillfeather1x4"),
            Triple("A ", "hidden emphasis phrase", " spans inline markup, marker quillfeather1x4 stands alone.Paragraph 5 of chapter 1."),
        )
        goldens.forEach { (before, highlight, after) ->
            assertEquals("golden $highlight", AnchorStatus.PLACEABLE, AnchorText.verify(first, before, highlight, after).status)
        }
    }

    // ---- verifier cache ----

    @Test fun fiveHundredAnchorsInOneChapterParseTheChapterOnce() = runTest {
        var loads = 0
        val verifier = EpubAnchorVerifier({ loads++; ResourceMarkup(chapter(), xml = true) })
        repeat(500) { i ->
            val p = (i % 30) + 1
            if (p in 2..4) return@repeat
            verifier.verify(SelectionAnchor("chapter1.xhtml", "Paragraph $p of chapter 1. Marker ", "quillfeather1x$p", " stands alone in this line.", 0.1))
        }
        assertEquals(1, loads)
        assertEquals(1, verifier.parseCount)
    }

    @Test fun unreadableResourceIsUnplacedAndNeverThrows() = runTest {
        val missing = EpubAnchorVerifier({ null })
        assertEquals(AnchorStatus.UNPLACED, missing.verify(SelectionAnchor("gone.xhtml", "a", "b", "c", null)).status)
        val throwing = EpubAnchorVerifier({ error("read failed") })
        assertEquals(AnchorStatus.UNPLACED, throwing.verify(SelectionAnchor("x.xhtml", "a", "b", "c", null)).status)
        val bodyless = EpubAnchorVerifier({ ResourceMarkup("<root xmlns='urn:x'>no body</root>", xml = true) })
        assertEquals(AnchorStatus.UNPLACED, bodyless.verify(SelectionAnchor("x.xhtml", "a", "b", "c", null)).status)
    }

    @Test fun cacheIsBoundedAndEvictsLeastRecentlyUsedResources() = runTest {
        var loads = 0
        val verifier = EpubAnchorVerifier({ loads++; ResourceMarkup("<html><body>text $it</body></html>", xml = false) }, cacheSize = 2)
        suspend fun check(href: String) = verifier.verify(SelectionAnchor(href, "", "text", "", null))
        check("a"); check("b"); check("a"); check("c")   // evicts b (least recently used)
        assertEquals(3, loads)
        check("a")                                        // still cached
        assertEquals(3, loads)
        check("b")                                        // reloaded
        assertEquals(4, loads)
    }
}
