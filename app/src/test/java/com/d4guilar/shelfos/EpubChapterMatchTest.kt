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
 *
 * Also not simple href equality for a second reason (Codex R2): two rows can share one raw href, so
 * [EpubChapter.id] — a stable, in-memory-only, flattened-position identity — is what a caller must compare, not
 * `href`. And `Locator.Locations.fragments` is not guaranteed to list the matching fragment first, so every
 * locator fragment is checked in order; see `laterLocatorFragmentsAreConsideredWhenEarlierOnesDoNotMatchAnyChapter`.
 *
 * **Fallback contract, corrected 2026-09-26 (Codex R2, second remediation round):** once no locator fragment
 * produces an exact match, `matchChapter` returns a same-resource entry only when exactly one is unambiguous —
 * either the sole no-fragment ("whole chapter") entry, or the sole same-resource candidate overall — and
 * otherwise returns null rather than arbitrarily picking one. An earlier version of this fallback always picked
 * "the first same-resource entry" even when several were equally plausible; `matchChapter`'s own doc comment
 * carries the full contract and rationale. See CASE 1/2/5 below for the now-ambiguous scenarios and CASE 3/4 for
 * the still-unambiguous ones.
 */
class EpubChapterMatchTest {
    private fun chapter(title: String, resource: String, fragment: String? = null, depth: Int = 0, id: Int = 0) =
        EpubChapter(id, title, href = "$resource${fragment?.let { "#$it" } ?: ""}", depth = depth, resource = resource, fragment = fragment)

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

    /** CASE 3: one no-fragment resource-level entry plus fragment-specific children, no usable locator fragment
     * — unambiguous: the resource-level entry represents "the whole chapter," not a guessed sub-position. */
    @Test fun locatorWithNoUsableFragmentFallsBackToTheResourceLevelEntryWhenExactlyOneExists() {
        val chapters = listOf(
            chapter("Chapter Three", "chapter3.xhtml"),
            chapter("Section A", "chapter3.xhtml", fragment = "sectionA"),
            chapter("Section B", "chapter3.xhtml", fragment = "sectionB"),
        )
        assertEquals("Chapter Three", matchChapter(chapters, "chapter3.xhtml", emptyList())?.title)
    }

    /** CASE 4: only one same-resource candidate at all (no resource-level sibling), no locator fragment —
     * unambiguous by elimination, since there is nothing else it could be. */
    @Test fun aSingleFragmentOnlyCandidateWinsByEliminationWhenNoOtherSameResourceEntryExists() {
        val chapters = listOf(chapter("Section A", "chapter3.xhtml", fragment = "sectionA"), chapter("Other Chapter", "other.xhtml"))
        assertEquals("Section A", matchChapter(chapters, "chapter3.xhtml", emptyList())?.title)
    }

    /** CASE 1: two fragment-only same-resource entries, no usable locator fragment at all — genuinely ambiguous;
     * arbitrarily picking "the first" would present a specific, named chapter as current when that is not
     * actually known, so this must return null instead (Codex R2, corrected from an earlier "pick the first"
     * fallback that this test previously encoded as expected behavior). */
    @Test fun twoFragmentOnlyCandidatesWithNoUsableLocatorFragmentAreAmbiguous() {
        val chapters = listOf(
            chapter("Section A", "chapter3.xhtml", fragment = "sectionA"),
            chapter("Section B", "chapter3.xhtml", fragment = "sectionB"),
        )
        assertNull(matchChapter(chapters, "chapter3.xhtml", emptyList()))
    }

    /** CASE 2: same as CASE 1, but the locator does carry a fragment — just not one either candidate has. Still
     * ambiguous for the same reason: a present-but-unrecognized fragment doesn't disambiguate between two
     * equally-plausible named candidates the way it does when only one candidate exists (contrast
     * `aFragmentThatMatchesNoSameResourceEntryFallsBackToTheSoleResourceLevelEntry`, below, where only one
     * candidate is a resource-level entry and the ambiguity does not arise). */
    @Test fun twoFragmentOnlyCandidatesWithAnUnrelatedLocatorFragmentAreStillAmbiguous() {
        val chapters = listOf(
            chapter("Section A", "chapter3.xhtml", fragment = "sectionA"),
            chapter("Section B", "chapter3.xhtml", fragment = "sectionB"),
        )
        assertNull(matchChapter(chapters, "chapter3.xhtml", listOf("unrelated-anchor")))
    }

    @Test fun aDifferentResourceNeverMatches() {
        val chapters = listOf(chapter("Chapter One", "chapter1.xhtml"))
        assertNull(matchChapter(chapters, "chapter2.xhtml", emptyList()))
    }

    @Test fun emptyChapterListNeverMatches() {
        assertNull(matchChapter(emptyList(), "chapter1.xhtml", emptyList()))
    }

    @Test fun aFragmentThatMatchesNoSameResourceEntryFallsBackToTheSoleResourceLevelEntry() {
        // The locator's own fragment (e.g. an anchor mid-resource Readium tracks but the TOC never named) simply
        // isn't one of the TOC's own fragments; unlike CASE 2 above, there is still exactly one resource-level
        // entry here, so it unambiguously applies rather than this returning null.
        val chapters = listOf(chapter("Chapter Three", "chapter3.xhtml"), chapter("Section A", "chapter3.xhtml", fragment = "sectionA"))
        assertEquals("Chapter Three", matchChapter(chapters, "chapter3.xhtml", listOf("unlisted-anchor"))?.title)
    }

    @Test fun matchingIsDeterministicAcrossRepeatedCalls() {
        // Exercises the now-ambiguous (null) path repeatedly: null == null holds, so this still proves determinism
        // even though the result itself is "no exact chapter," not a picked one.
        val chapters = listOf(chapter("Section A", "chapter3.xhtml", fragment = "sectionA"), chapter("Section B", "chapter3.xhtml", fragment = "sectionB"))
        val first = matchChapter(chapters, "chapter3.xhtml", emptyList())
        val second = matchChapter(chapters, "chapter3.xhtml", emptyList())
        assertEquals(first, second)
        assertNull(first)
    }

    @Test fun firstLocatorFragmentWinsWhenItMatches() {
        // When the first fragment does match, it wins immediately — this is not itself proof that later fragments
        // are ever consulted (that regression is the next test), only that the common, unambiguous case works.
        val chapters = listOf(chapter("Section A", "chapter3.xhtml", fragment = "sectionA"), chapter("Section B", "chapter3.xhtml", fragment = "sectionB"))
        assertEquals("Section A", matchChapter(chapters, "chapter3.xhtml", listOf("sectionA", "sectionB"))?.title)
    }

    @Test fun laterLocatorFragmentsAreConsideredWhenEarlierOnesDoNotMatchAnyChapter() {
        // Codex R2: the pinned navigator's Locator.Locations.fragments is not guaranteed to put the fragment that
        // actually corresponds to a TOC entry first (e.g. a synthetic/internal marker can precede it) — matching
        // must check every locator fragment in order, not just the first, or a real match can be missed entirely.
        val chapters = listOf(chapter("Matching Section", "chapter3.xhtml", fragment = "matching-fragment"),
            chapter("Other Section", "chapter3.xhtml", fragment = "sectionB"))
        assertEquals("Matching Section",
            matchChapter(chapters, "chapter3.xhtml", listOf("unrelated-fragment", "matching-fragment"))?.title)
    }

    @Test fun noLocatorFragmentMatchingAnyChapterStillFallsBackToTheSoleResourceLevelEntry() {
        // Several non-matching fragments (not just one) must still land on the same unambiguous, single
        // resource-level entry — unlike CASE 2, this scenario has one, so it is not ambiguous.
        val chapters = listOf(chapter("Chapter Three", "chapter3.xhtml"), chapter("Section A", "chapter3.xhtml", fragment = "sectionA"))
        assertEquals("Chapter Three", matchChapter(chapters, "chapter3.xhtml", listOf("nope-one", "nope-two"))?.title)
    }

    /** CASE 5: two duplicate no-fragment resource-level entries — even though [EpubChapter.id] gives them distinct
     * identities (fixing the original href-equality defect), they remain indistinguishable *from each other* by
     * resource/fragment, so the corrected fallback contract says this is ambiguous, not "id 0 wins." */
    @Test fun duplicateHrefRowsAreAmbiguousAndResolveToNoCurrentRowRatherThanAnArbitraryPick() {
        val first = chapter("First appearance", "chapter1.xhtml", id = 0)
        val second = chapter("Second appearance", "chapter1.xhtml", id = 1)
        assertEquals(first.href, second.href) // the original defect's precondition: identical hrefs
        assertNotEquals(first.id, second.id) // ids remain distinct regardless of href (this part is unchanged)

        val matched = matchChapter(listOf(first, second), "chapter1.xhtml", emptyList())
        assertNull(matched) // two indistinguishable resource-level entries: genuinely ambiguous, not "pick the first"

        // What the old, now-replaced href-based UI comparison would have done, to document why it was wrong:
        val rowsThatWouldHaveBeenCurrentByHrefEquality = listOf(first, second).count { it.href == first.href }
        assertEquals(2, rowsThatWouldHaveBeenCurrentByHrefEquality)
    }
}
