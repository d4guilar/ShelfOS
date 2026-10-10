// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations

import org.junit.Assert.*
import org.junit.Test

/** Phase 4A: pure-Kotlin kind invariants and strict fixed-page locator validation (no Android/org.json needed). */
class AnnotationDomainTest {
    private val readium = """{"href":"c1.xhtml"}"""
    private val page = """{"version":1,"page":4}"""
    private val acceptAll = AnnotationLocatorValidator { _, _ -> true }

    private fun draft(kind: AnnotationKind, format: LocatorFormat = LocatorFormat.READIUM_LOCATOR_1,
        locator: String = if (format == LocatorFormat.FIXED_PAGE_1) page else readium, progress: Int = 10,
        title: String? = null, selected: String? = null, body: String? = null, style: String? = null, item: String = "item") =
        AnnotationDraft(item, kind, format, locator, progress, null, title, selected, body, style)

    private fun rejects(d: AnnotationDraft, v: AnnotationLocatorValidator? = acceptAll) =
        runCatching { AnnotationRules.validate(d, v) }.exceptionOrNull() is InvalidAnnotationException

    @Test fun bookmarkAcceptsOptionalTitleOnlyAndRejectsTextBodyOrStyle() {
        AnnotationRules.validate(draft(AnnotationKind.BOOKMARK), acceptAll)
        AnnotationRules.validate(draft(AnnotationKind.BOOKMARK, title = "Label"), acceptAll)
        AnnotationRules.validate(draft(AnnotationKind.BOOKMARK, LocatorFormat.FIXED_PAGE_1), acceptAll)
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, selected = "x")))
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, body = "x")))
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, style = "amber")))
    }

    @Test fun highlightRequiresReadiumFormatAndStyleAndAllowsBodyWithoutBecomingANote() {
        AnnotationRules.validate(draft(AnnotationKind.HIGHLIGHT, style = "amber"), acceptAll)
        AnnotationRules.validate(draft(AnnotationKind.HIGHLIGHT, style = "amber", selected = null), acceptAll)
        AnnotationRules.validate(draft(AnnotationKind.HIGHLIGHT, style = "amber", selected = "t", body = "annotated"), acceptAll)
        assertTrue(rejects(draft(AnnotationKind.HIGHLIGHT)))
        assertTrue(rejects(draft(AnnotationKind.HIGHLIGHT, style = " ")))
        assertTrue(rejects(draft(AnnotationKind.HIGHLIGHT, LocatorFormat.FIXED_PAGE_1, style = "amber")))
    }

    @Test fun noteRequiresNonBlankBodyAndFixedPageNotesCarryNoSelectedText() {
        AnnotationRules.validate(draft(AnnotationKind.NOTE, body = "text"), acceptAll)
        AnnotationRules.validate(draft(AnnotationKind.NOTE, selected = "sel", body = "text"), acceptAll)
        AnnotationRules.validate(draft(AnnotationKind.NOTE, LocatorFormat.FIXED_PAGE_1, body = "page note"), acceptAll)
        assertTrue(rejects(draft(AnnotationKind.NOTE)))
        assertTrue(rejects(draft(AnnotationKind.NOTE, body = "   ")))
        assertTrue(rejects(draft(AnnotationKind.NOTE, LocatorFormat.FIXED_PAGE_1, selected = "sel", body = "x")))
    }

    @Test fun commonFieldsAreValidated() {
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, progress = -1)))
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, progress = 101)))
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, locator = " ")))
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, item = "")))
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK).copy(orderKey = Double.NaN)))
        AnnotationRules.validate(draft(AnnotationKind.BOOKMARK, progress = 0), acceptAll)
        AnnotationRules.validate(draft(AnnotationKind.BOOKMARK, progress = 100), acceptAll)
    }

    @Test fun locatorValidationIsSkippedOnlyWhenNoValidatorIsGiven() {
        val strict = DefaultAnnotationLocatorValidator { it.startsWith("{") }
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, locator = "garbage"), strict))
        AnnotationRules.validate(draft(AnnotationKind.BOOKMARK, locator = "garbage"), null)
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":-3}"""), strict))
        AnnotationRules.validate(draft(AnnotationKind.BOOKMARK, LocatorFormat.FIXED_PAGE_1, locator = page), strict)
    }

    @Test fun fixedPageLocatorAcceptsExactlyVersion1AndANonNegativeIntegerPage() {
        assertEquals(0, FixedPageLocator.parse("""{"version":1,"page":0}"""))
        assertEquals(4, FixedPageLocator.parse(page))
        assertEquals(2_147_483_647, FixedPageLocator.parse("""{"version":1,"page":2147483647}"""))
        assertEquals("key order and whitespace are tolerated", 7, FixedPageLocator.parse(""" { "page" : 7 , "version" : 1 } """))
    }

    @Test fun fixedPageLocatorRejectsEverythingElse() {
        val bad = listOf(
            "", "  ", "not json", "null", "[]", "{}", "{", "}", """{"version":1}""", """{"page":1}""",
            """{"version":2,"page":1}""", """{"version":0,"page":1}""", """{"version":"1","page":1}""",
            """{"version":1,"page":-1}""", """{"version":1,"page":1.0}""", """{"version":1,"page":1.5}""", """{"version":1,"page":1e2}""",
            """{"version":1,"page":"3"}""", """{"version":1,"page":null}""", """{"version":1,"page":true}""", """{"version":1,"page":01}""",
            """{"version":1,"page":+1}""", """{"version":1,"page":2147483648}""", """{"version":1,"page":99999999999999999999}""",
            """{"version":1,"page":1,"extra":2}""", """{"version":1,"page":1,"version":1}""", """{"version":1,"page":1,"page":2}""",
            """{"version":1,"page":1,}""", """{"version":1,,"page":1}""", """{"version":1 "page":1}""", """{"version":1,"page":1} trailing""",
            """{"version":1,"page":1}{""", "{\"ver" + "\\" + "u0073ion\":1,\"page\":1}", """{'version':1,'page':1}""", """{"version":1,"page":-0}""",
        )
        for (json in bad) assertNull("should reject: $json", FixedPageLocator.parse(json))
    }

    // ---- Phase 4B size caps ----

    @Test fun locatorOverSixtyFourKibIsRejectedAtTheRepositoryRulesBoundary() {
        val atCap = "x".repeat(AnnotationLimits.MAX_LOCATOR_BYTES - 2)
        AnnotationRules.validate(draft(AnnotationKind.BOOKMARK, locator = """{"$atCap"""), acceptAll)   // opaque: exactly at the cap is fine
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, locator = "x".repeat(AnnotationLimits.MAX_LOCATOR_BYTES + 1))))
        // The cap counts UTF-8 bytes, so 3-byte characters hit it first.
        assertTrue(rejects(draft(AnnotationKind.BOOKMARK, locator = "€".repeat(AnnotationLimits.MAX_LOCATOR_BYTES / 3 + 1))))
    }

    @Test fun noteBodyOverTwentyThousandCharactersIsRejectedNeverTruncated() {
        AnnotationRules.validate(draft(AnnotationKind.NOTE, body = "n".repeat(AnnotationLimits.MAX_BODY)), acceptAll)
        assertTrue(rejects(draft(AnnotationKind.NOTE, body = "n".repeat(AnnotationLimits.MAX_BODY + 1))))
        // A highlight's optional body obeys the same cap, and an edit is validated identically.
        assertTrue(rejects(draft(AnnotationKind.HIGHLIGHT, style = "amber", body = "n".repeat(AnnotationLimits.MAX_BODY + 1))))
        val edit = runCatching { AnnotationRules.validateContent(AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1, "sel", "n".repeat(AnnotationLimits.MAX_BODY + 1), null) }
        assertTrue(edit.exceptionOrNull() is InvalidAnnotationException)
    }

    @Test fun selectedTextOverTwoThousandUnitsIsRejected() {
        AnnotationRules.validate(draft(AnnotationKind.HIGHLIGHT, style = "amber", selected = "s".repeat(AnnotationLimits.MAX_SELECTED_TEXT)), acceptAll)
        assertTrue(rejects(draft(AnnotationKind.HIGHLIGHT, style = "amber", selected = "s".repeat(AnnotationLimits.MAX_SELECTED_TEXT + 1))))
    }

    @Test fun blankNoteBodyIsStillRejectedAndPositionNotesCarryNoSelectedText() {
        assertTrue(rejects(draft(AnnotationKind.NOTE, body = "  \t ")))
        AnnotationRules.validate(draft(AnnotationKind.NOTE, selected = null, body = "a position note"), acceptAll)
        AnnotationRules.validate(draft(AnnotationKind.NOTE, selected = "quote", body = "a selection note"), acceptAll)
    }
}
