// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.domain.annotations.AnnotationLimits
import org.junit.Assert.*
import org.junit.Test

/** Phase 4B: selection -> draft mapping rules (plan section 6.1). Pure Kotlin; Locator JSON round trips are instrumented. */
class SelectedTextTest {
    @Test fun trimsAndCollapsesEveryWhitespaceRunIncludingNbspAndNewlines() {
        assertEquals("a b c", SelectedText.normalize("  a \n\n b  c \t"))
        assertEquals("one two", SelectedText.normalize("one two"))
    }

    @Test fun blankOrAbsentTextIsNullNotAnEmptyExcerpt() {
        assertNull(SelectedText.normalize(null))
        assertNull(SelectedText.normalize(""))
        assertNull(SelectedText.normalize(" \n\t  "))
    }

    @Test fun capsAtTwoThousandUtf16UnitsWithoutEllipsis() {
        val long = "word ".repeat(1_000)
        val normalized = SelectedText.normalize(long)!!
        assertTrue(normalized.length <= AnnotationLimits.MAX_SELECTED_TEXT)
        assertTrue(normalized.length > AnnotationLimits.MAX_SELECTED_TEXT - 6)
        assertFalse(normalized.endsWith(" "))
        assertFalse(normalized.contains("…"))
        val exact = "x".repeat(AnnotationLimits.MAX_SELECTED_TEXT)
        assertEquals(exact, SelectedText.normalize(exact))
        assertEquals(AnnotationLimits.MAX_SELECTED_TEXT, SelectedText.normalize(exact + "yy")!!.length)
    }

    @Test fun neverSplitsASurrogatePairAtTheCap() {
        // 1,999 BMP chars then an emoji (2 units): the pair would straddle the 2,000 cap and must be dropped whole.
        val text = "a".repeat(AnnotationLimits.MAX_SELECTED_TEXT - 1) + "😀" + "tail"
        val normalized = SelectedText.normalize(text)!!
        assertEquals(AnnotationLimits.MAX_SELECTED_TEXT - 1, normalized.length)
        assertFalse(Character.isHighSurrogate(normalized.last()))
        // A pair that fits exactly is kept intact.
        val fits = "a".repeat(AnnotationLimits.MAX_SELECTED_TEXT - 2) + "😀" + "tail"
        val kept = SelectedText.normalize(fits)!!
        assertEquals(AnnotationLimits.MAX_SELECTED_TEXT, kept.length)
        assertTrue(kept.endsWith("😀"))
    }

    @Test fun rawTextWithNonLatinScriptsIsPreserved() {
        assertEquals("Café naïve “quoted” — dash", SelectedText.normalize("Café   naïve “quoted”\n— dash"))
        assertEquals("日本語 テキスト", SelectedText.normalize("日本語　テキスト"))
    }

    @Test fun progressSnapshotComesFromTotalProgressionOnly() {
        assertEquals(0, progressSnapshotOf(null))
        assertEquals(0, progressSnapshotOf(Double.NaN))
        assertEquals(34, progressSnapshotOf(0.3456))
        assertEquals(100, progressSnapshotOf(7.0))
        assertEquals(0, progressSnapshotOf(-1.0))
        assertNull(orderKeyOf(null)); assertNull(orderKeyOf(1.5)); assertEquals(0.5, orderKeyOf(0.5)!!, 0.0)
    }

    @Test fun buildSelectionDraftMapsValidSelectionAndKeepsRawLocatorUntouched() {
        val locator = """{"href":"c.xhtml","text":{"before":"b","highlight":"  Hello \n world ","after":"a"}}"""
        val result = buildSelectionDraft(EpubSelectionRead(locator, "  Hello \n world ", 0.5), orderKey = 0.25)
        val draft = (result as SelectionDraftResult.Ready).draft
        assertEquals("Hello world", draft.selectedText)
        assertEquals(locator, draft.locatorJson)   // the raw triplet inside the locator is never normalized
        assertEquals(50, draft.progressSnapshot)
        assertEquals(0.25, draft.orderKey!!, 0.0)
    }

    @Test fun selectionWithoutTextIsRejectedNotStoredAsAPosition() {
        val r = buildSelectionDraft(EpubSelectionRead("""{"href":"c.xhtml"}""", "   ", 0.1))
        assertEquals(SelectionRejection.NO_TEXT, (r as SelectionDraftResult.Rejected).reason)
        assertEquals(SelectionRejection.NO_TEXT, (buildSelectionDraft(EpubSelectionRead("""{"href":"c.xhtml"}""", null, null)) as SelectionDraftResult.Rejected).reason)
    }

    @Test fun locatorOverSixtyFourKibIsRejected() {
        val atCap = "x".repeat(AnnotationLimits.MAX_LOCATOR_BYTES)
        assertTrue(locatorWithinCap(atCap))
        assertFalse(locatorWithinCap(atCap + "x"))
        val big = EpubSelectionRead("""{"href":"c","pad":"${"y".repeat(AnnotationLimits.MAX_LOCATOR_BYTES)}"}""", "text", 0.1)
        assertEquals(SelectionRejection.LOCATOR_TOO_LARGE, (buildSelectionDraft(big) as SelectionDraftResult.Rejected).reason)
        // The cap is in UTF-8 bytes, not characters: 3-byte characters reach it sooner.
        assertFalse(locatorWithinCap("€".repeat(AnnotationLimits.MAX_LOCATOR_BYTES / 3 + 1)))
        assertTrue(locatorWithinCap("€".repeat(AnnotationLimits.MAX_LOCATOR_BYTES / 3)))
    }
}
