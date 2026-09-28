// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubSearchPresentationTest {
    @Test fun queryAndSnippetWhitespaceIsNormalizedWithoutInventingContent() {
        assertEquals("alpha beta", normalizeSearchQuery("  alpha\n\t beta  "))
        assertEquals("", normalizeSearchQuery(" \n\t "))
        assertEquals("before matched words after", searchResultSnippet(result(
            before = " before  ", highlight = "matched\nwords", after = "  after ",
        )))
    }

    @Test fun accessibilityDescriptionUsesReadableSnippetAndOptionalContext() {
        assertEquals("Chapter Five, before match after, 42 percent through book",
            searchResultAccessibilityText(result(title = "Chapter Five", progression = .42)))
        assertEquals("Match in this publication",
            searchResultAccessibilityText(result(before = "", highlight = "", after = "", progression = null)))
    }

    private fun result(
        title: String? = null,
        progression: Double? = null,
        before: String = "before ",
        highlight: String = "match",
        after: String = " after",
    ) = EpubSearchResult("{}", "chapter.xhtml", title, progression, before, highlight, after)
}
