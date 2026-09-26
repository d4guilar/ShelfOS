// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.reader.EpubChapter
import com.d4guilar.shelfos.core.reader.matchChapter
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2B.1: current-chapter matching must not be simple href equality — several TOC entries can share one
 * resource at different fragments, and the current position may carry no fragment at all. [EpubChapter.resource]/
 * [.fragment] are precomputed once (via `Publication.locatorFromLink`, in `EpubSession`) from the *same*
 * resolution Readium's own navigator uses for `currentLocator`, so these tests exercise `matchChapter` directly
 * with already-normalized resource/fragment strings rather than raw hrefs — that normalization step itself needs
 * a real `Publication`/`Locator` and is covered by instrumented tests instead (see EpubChapterHighlightTest).
 */
class EpubChapterMatchTest {
    private fun chapter(title: String, resource: String, fragment: String? = null, depth: Int = 0) =
        EpubChapter(title, href = "$resource${fragment?.let { "#$it" } ?: ""}", depth = depth, resource = resource, fragment = fragment)

    @Test fun chapterHrefWithoutFragmentMatchesByResourceAlone() {
        val chapters = listOf(chapter("Chapter One", "chapter1.xhtml"), chapter("Chapter Two", "chapter2.xhtml"))
        assertEquals("Chapter One", matchChapter(chapters, "chapter1.xhtml", emptyList())?.title)
    }

    @Test fun tocHrefWithFragmentMatchesWhenTheLocatorFragmentAgrees() {
        val chapters = listOf(chapter("Section", "chapter1.xhtml", fragment = "section2"))
        assertEquals("Section", matchChapter(chapters, "chapter1.xhtml", listOf("section2"))?.title)
    }

    @Test fun severalTocEntriesSharingOneResourcePickTheMatchingFragmentNotAllOfThem() {
        val chapters = listOf(
            chapter("Intro", "chapter3.xhtml"),
            chapter("Section A", "chapter3.xhtml", fragment = "sectionA"),
            chapter("Section B", "chapter3.xhtml", fragment = "sectionB"),
        )
        assertEquals("Section B", matchChapter(chapters, "chapter3.xhtml", listOf("sectionB"))?.title)
        assertEquals("Section A", matchChapter(chapters, "chapter3.xhtml", listOf("sectionA"))?.title)
    }

    @Test fun nestedTocEntryMatchesTheSameWayAsATopLevelOne() {
        // EpubSession.chapters is already the flattened depth-walked list; matchChapter only ever sees that flat
        // list, so a deeply nested entry (depth > 0) is not a special case for the matching rule itself.
        val chapters = listOf(
            chapter("Part One", "part1.xhtml", depth = 0),
            chapter("Chapter One", "chapter1.xhtml", depth = 1),
            chapter("Section 1.1", "chapter1.xhtml", fragment = "s1", depth = 2),
        )
        assertEquals("Section 1.1", matchChapter(chapters, "chapter1.xhtml", listOf("s1"))?.title)
    }

    @Test fun locatorWithNoUsableFragmentFallsBackToTheResourceLevelEntryWhenOneExists() {
        // Honest fallback: with no fragment, we cannot know "which sub-heading" the position is under without
        // parsing document content (not attempted here — see matchChapter's doc comment). When one of the
        // same-resource entries is itself resource-level (no fragment), that is the safest, most defensible
        // choice — it is literally the entry that represents "the whole chapter," not a guessed sub-position.
        val chapters = listOf(
            chapter("Chapter Three", "chapter3.xhtml"),
            chapter("Section A", "chapter3.xhtml", fragment = "sectionA"),
            chapter("Section B", "chapter3.xhtml", fragment = "sectionB"),
        )
        assertEquals("Chapter Three", matchChapter(chapters, "chapter3.xhtml", emptyList())?.title)
    }

    @Test fun locatorWithNoUsableFragmentAndNoResourceLevelEntryFallsBackToTheFirstSameResourceEntry() {
        // No entry represents "the whole resource" here (every same-resource entry has its own fragment) and no
        // positional data exists to prefer one sub-heading over another, so this deterministically takes the
        // first in TOC order rather than an arbitrary or unstable choice — documented, not claimed to be exact.
        val chapters = listOf(
            chapter("Section A", "chapter3.xhtml", fragment = "sectionA"),
            chapter("Section B", "chapter3.xhtml", fragment = "sectionB"),
        )
        assertEquals("Section A", matchChapter(chapters, "chapter3.xhtml", emptyList())?.title)
    }

    @Test fun aDifferentResourceNeverMatches() {
        val chapters = listOf(chapter("Chapter One", "chapter1.xhtml"))
        assertNull(matchChapter(chapters, "chapter2.xhtml", emptyList()))
    }

    @Test fun emptyChapterListNeverMatches() {
        assertNull(matchChapter(emptyList(), "chapter1.xhtml", emptyList()))
    }

    @Test fun aFragmentThatMatchesNoSameResourceEntryFallsBackRatherThanReturningNull() {
        // The locator's own fragment (e.g. an anchor mid-resource Readium tracks but the TOC never named) simply
        // isn't one of the TOC's own fragments; the resource itself is still known, so the resource-level/first
        // fallback still applies rather than reporting no chapter at all.
        val chapters = listOf(chapter("Chapter Three", "chapter3.xhtml"), chapter("Section A", "chapter3.xhtml", fragment = "sectionA"))
        assertEquals("Chapter Three", matchChapter(chapters, "chapter3.xhtml", listOf("unlisted-anchor"))?.title)
    }

    @Test fun matchingIsDeterministicAcrossRepeatedCalls() {
        val chapters = listOf(chapter("Section A", "chapter3.xhtml", fragment = "sectionA"), chapter("Section B", "chapter3.xhtml", fragment = "sectionB"))
        val first = matchChapter(chapters, "chapter3.xhtml", emptyList())
        val second = matchChapter(chapters, "chapter3.xhtml", emptyList())
        assertEquals(first, second)
    }

    @Test fun onlyTheFirstLocatorFragmentIsConsidered() {
        // Locator.Locations.fragments can carry more than one entry; matching uses the first, matching how the
        // rest of this codebase treats it as the primary fragment (there is no documented meaning for "second").
        val chapters = listOf(chapter("Section A", "chapter3.xhtml", fragment = "sectionA"), chapter("Section B", "chapter3.xhtml", fragment = "sectionB"))
        assertEquals("Section A", matchChapter(chapters, "chapter3.xhtml", listOf("sectionA", "sectionB"))?.title)
    }
}
