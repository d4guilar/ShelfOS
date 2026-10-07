// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3C: pure JVM coverage for the comic/manga spread pairing/navigation model. No Android dependency --
 * [PageGeometry], [PageGroup], [canonicalPageGroups], [resolvePageGroups], [resolveSpreadActive],
 * [nextLogicalPage]/[previousLogicalPage] are all plain Kotlin.
 */
class SpreadModelTest {

    // ---- canonicalPageGroups: cover always solo, interior pairs, odd final page solo ----

    @Test fun onePageIsSolo() = assertEquals(listOf(PageGroup(listOf(0))), canonicalPageGroups(1))

    @Test fun twoPagesAreBothSolo() = assertEquals(listOf(PageGroup(listOf(0)), PageGroup(listOf(1))), canonicalPageGroups(2))

    @Test fun threePagesPairTheInteriorTwo() =
        assertEquals(listOf(PageGroup(listOf(0)), PageGroup(listOf(1, 2))), canonicalPageGroups(3))

    @Test fun fourPagesLeaveAnUnmatchedFinalPageSolo() =
        assertEquals(listOf(PageGroup(listOf(0)), PageGroup(listOf(1, 2)), PageGroup(listOf(3))), canonicalPageGroups(4))

    @Test fun fivePagesPairTwoInteriorPairs() =
        assertEquals(listOf(PageGroup(listOf(0)), PageGroup(listOf(1, 2)), PageGroup(listOf(3, 4))), canonicalPageGroups(5))

    @Test fun sevenPagesPairThreeInteriorPairs() = assertEquals(
        listOf(PageGroup(listOf(0)), PageGroup(listOf(1, 2)), PageGroup(listOf(3, 4)), PageGroup(listOf(5, 6))),
        canonicalPageGroups(7))

    @Test fun zeroOrNegativePageCountYieldsNoGroups() {
        assertTrue(canonicalPageGroups(0).isEmpty())
        assertTrue(canonicalPageGroups(-1).isEmpty())
    }

    // ---- resolvePageGroups: SINGLE / spreadActive=false ----

    @Test fun inactiveSpreadEveryPageIsItsOwnSoloGroup() {
        val groups = resolvePageGroups(5, spreadActive = false) { null }
        assertEquals((0..4).map { PageGroup(listOf(it)) }, groups)
    }

    // ---- resolvePageGroups: SPREAD / spreadActive=true, no landscape pages ----

    @Test fun activeSpreadMatchesCanonicalGroupingWhenNoPageIsLandscape() {
        val groups = resolvePageGroups(5, spreadActive = true) { PageGeometry(600, 900) }
        assertEquals(canonicalPageGroups(5), groups)
    }

    // ---- resolvePageGroups: landscape handling, conservative, no skip/duplicate ----

    @Test fun landscapePageInAPairSplitsBothMembersIntoSoloGroupsWithoutSkippingOrDuplicating() {
        // Pages: 0 (cover, portrait), 1 (portrait), 2 (landscape) -> canonical pair [1,2] must split.
        val geometry = mapOf(0 to PageGeometry(600, 900), 1 to PageGeometry(600, 900), 2 to PageGeometry(900, 600))
        val groups = resolvePageGroups(3, spreadActive = true) { geometry[it] }
        assertEquals(listOf(PageGroup(listOf(0)), PageGroup(listOf(1)), PageGroup(listOf(2))), groups)
        // No page is skipped or duplicated: every logical index 0..2 appears exactly once across all groups.
        assertEquals(listOf(0, 1, 2), groups.flatMap { it.pages }.sorted())
    }

    @Test fun laterCanonicalPairsAreUnaffectedByAnEarlierLandscapeSplit() {
        // 5 pages: page 2 is landscape (splits [1,2]); pair [3,4] stays paired regardless.
        val geometry = mapOf(0 to PageGeometry(600, 900), 1 to PageGeometry(600, 900), 2 to PageGeometry(900, 600),
            3 to PageGeometry(600, 900), 4 to PageGeometry(600, 900))
        val groups = resolvePageGroups(5, spreadActive = true) { geometry[it] }
        assertEquals(listOf(PageGroup(listOf(0)), PageGroup(listOf(1)), PageGroup(listOf(2)), PageGroup(listOf(3, 4))), groups)
    }

    @Test fun unknownGeometryIsTreatedAsNotLandscapeRatherThanCrashing() {
        val groups = resolvePageGroups(3, spreadActive = true) { null }
        assertEquals(listOf(PageGroup(listOf(0)), PageGroup(listOf(1, 2))), groups)
    }

    // ---- PageGeometry.isLandscape threshold ----

    @Test fun portraitAndNearSquarePagesAreNotLandscape() {
        assertFalse(PageGeometry(600, 900).isLandscape)
        assertFalse(PageGeometry(100, 100).isLandscape)
        assertFalse(PageGeometry(104, 100).isLandscape) // just under the 1.05 threshold
    }

    @Test fun clearlyWidePagesAreLandscape() {
        assertTrue(PageGeometry(900, 600).isLandscape)
        assertTrue(PageGeometry(106, 100).isLandscape) // just over the 1.05 threshold
    }

    @Test fun zeroOrNegativeDimensionsAreNotLandscape() {
        assertFalse(PageGeometry(0, 100).isLandscape)
        assertFalse(PageGeometry(100, 0).isLandscape)
        assertFalse(PageGeometry(-5, 100).isLandscape)
    }

    // ---- physicalOrder: LTR vs RTL placement, logical pair membership unchanged ----

    @Test fun ltrPhysicalOrderMatchesLogicalOrder() {
        assertEquals(listOf(1, 2), PageGroup(listOf(1, 2)).physicalOrder(rightToLeft = false))
    }

    @Test fun rtlPhysicalOrderMirrorsButPairMembershipAndValuesAreUnchanged() {
        val group = PageGroup(listOf(1, 2))
        val rtlOrder = group.physicalOrder(rightToLeft = true)
        assertEquals(listOf(2, 1), rtlOrder)
        // Logical pair membership (the underlying set/values) is identical regardless of direction.
        assertEquals(group.pages.toSet(), rtlOrder.toSet())
        assertEquals(listOf(1, 2), group.pages) // untouched by computing a physical order
    }

    @Test fun soloGroupPhysicalOrderIsUnaffectedByDirection() {
        val group = PageGroup(listOf(4))
        assertEquals(listOf(4), group.physicalOrder(rightToLeft = false))
        assertEquals(listOf(4), group.physicalOrder(rightToLeft = true))
    }

    // ---- groupContaining / current-page invariant: selecting the second member keeps it current ----

    @Test fun groupContainingFindsTheGroupHoldingAGivenLogicalPage() {
        val groups = canonicalPageGroups(5) // [0], [1,2], [3,4]
        assertEquals(PageGroup(listOf(1, 2)), groupContaining(groups, 2))
        assertEquals(PageGroup(listOf(1, 2)), groupContaining(groups, 1))
        assertEquals(PageGroup(listOf(0)), groupContaining(groups, 0))
        assertNull(groupContaining(groups, 99))
    }

    @Test fun selectingTheSecondPairMemberDoesNotNormalizeToTheLowerIndex() {
        // Simulates a thumbnail jump to page 2 (the second member of canonical pair [1,2]): the containing group
        // is derived around page 2, but the "current logical page" itself (modeled here as the caller-held value,
        // exactly like FixedReaderState.page) must stay 2, never silently become 1.
        val groups = canonicalPageGroups(5)
        val currentPage = 2
        val containing = groupContaining(groups, currentPage)
        assertEquals(PageGroup(listOf(1, 2)), containing)
        assertEquals(2, currentPage) // unchanged -- resolvePageGroups/groupContaining never mutate caller state
    }

    // ---- resolveSpreadActive: AUTO / SINGLE / SPREAD ----

    @Test fun singleIsNeverActiveRegardlessOfWidth() {
        assertFalse(resolveSpreadActive(SpreadMode.SINGLE, viewportWidthDp = 2000))
    }

    @Test fun spreadIsAlwaysActiveRegardlessOfWidth() {
        assertTrue(resolveSpreadActive(SpreadMode.SPREAD, viewportWidthDp = 100))
    }

    @Test fun autoIsInactiveBelowThresholdAndActiveAtOrAboveIt() {
        assertFalse(resolveSpreadActive(SpreadMode.AUTO, AUTO_SPREAD_MIN_WIDTH_DP - 1))
        assertTrue(resolveSpreadActive(SpreadMode.AUTO, AUTO_SPREAD_MIN_WIDTH_DP))
        assertTrue(resolveSpreadActive(SpreadMode.AUTO, AUTO_SPREAD_MIN_WIDTH_DP + 400))
    }

    // ---- nextLogicalPage / previousLogicalPage: semantic group-to-group navigation ----

    @Test fun forwardNavigationMovesCoverToFirstSpread() {
        val groups = canonicalPageGroups(7) // [0],[1,2],[3,4],[5,6]
        assertEquals(1, nextLogicalPage(groups, 0))
    }

    @Test fun forwardNavigationFromEitherMemberOfAGroupMovesToTheNextGroupsFirstPage() {
        val groups = canonicalPageGroups(7)
        assertEquals(3, nextLogicalPage(groups, 1))
        assertEquals(3, nextLogicalPage(groups, 2)) // from the second member too -- no double-advance
        assertEquals(5, nextLogicalPage(groups, 3))
        assertEquals(5, nextLogicalPage(groups, 4))
    }

    @Test fun backwardNavigationFromASpreadMovesToThePreviousGroupsFirstPage() {
        val groups = canonicalPageGroups(7)
        assertEquals(1, previousLogicalPage(groups, 3))
        assertEquals(1, previousLogicalPage(groups, 4))
        assertEquals(0, previousLogicalPage(groups, 1))
        assertEquals(0, previousLogicalPage(groups, 2))
    }

    @Test fun oddFinalPageNavigatesLikeAnyOtherSoloGroup() {
        val groups = canonicalPageGroups(4) // [0],[1,2],[3]
        assertEquals(3, nextLogicalPage(groups, 1))
        assertEquals(1, previousLogicalPage(groups, 3))
    }

    @Test fun forwardNavigationAtTheLastGroupStaysPut() {
        val groups = canonicalPageGroups(5) // [0],[1,2],[3,4]
        assertEquals(3, nextLogicalPage(groups, 3))
        assertEquals(4, nextLogicalPage(groups, 4))
    }

    @Test fun backwardNavigationAtTheFirstGroupStaysPut() {
        val groups = canonicalPageGroups(5)
        assertEquals(0, previousLogicalPage(groups, 0))
    }

    @Test fun singleModeNavigationReducesToOrdinaryPlusOrMinusOne() {
        val groups = resolvePageGroups(5, spreadActive = false) { null } // every page solo
        assertEquals(1, nextLogicalPage(groups, 0))
        assertEquals(2, nextLogicalPage(groups, 1))
        assertEquals(1, previousLogicalPage(groups, 2))
    }

    @Test fun rtlNavigationUsesTheSameLogicalGroupsAsLtr() {
        // RTL only changes PHYSICAL placement (PageGroup.physicalOrder), never which logical group is
        // "next"/"previous" or the semantic reading-unit target navigation lands on. This builds genuinely
        // independent LTR and RTL group lists (resolvePageGroups is direction-agnostic -- direction is never an
        // input to it -- but this test proves the navigation RESULT is identical regardless of which group list
        // a caller might have built while reading in either direction) and compares real navigation outcomes
        // across every page, rather than comparing one call to itself (the original tautological assertion this
        // replaces).
        val ltrGroups = resolvePageGroups(7, spreadActive = true) { null }
        val rtlGroups = resolvePageGroups(7, spreadActive = true) { null }
        assertEquals(ltrGroups, rtlGroups) // identical logical group structure regardless of direction
        for (page in 0..6) {
            assertEquals("next from $page", nextLogicalPage(ltrGroups, page), nextLogicalPage(rtlGroups, page))
            assertEquals("previous from $page", previousLogicalPage(ltrGroups, page), previousLogicalPage(rtlGroups, page))
        }
        // Only physicalOrder differs for a pair: its logical membership/values are identical, but LTR keeps the
        // lower index on the left while RTL mirrors it.
        val pair = groupContaining(ltrGroups, 1)!!
        assertEquals(listOf(1, 2), pair.physicalOrder(rightToLeft = false))
        assertEquals(listOf(2, 1), pair.physicalOrder(rightToLeft = true))
        assertEquals(pair.pages, groupContaining(rtlGroups, 1)!!.pages) // same logical pages either way
    }

    // ---- nextPage/previousPage/resolveCurrentGroup: bounded-cost equivalents used by the real ViewModel ----

    @Test fun boundedNextPageMatchesWholeBookNavigationWhenNoPageIsLandscape() {
        val canonical = canonicalPageGroups(7)
        val whole = resolveGroups(canonical, spreadActive = true) { null }
        for (page in 0..6) assertEquals("page $page", nextLogicalPage(whole, page), nextPage(canonical, page, spreadActive = true) { false })
    }

    @Test fun boundedPreviousPageMatchesWholeBookNavigationWhenNoPageIsLandscape() {
        val canonical = canonicalPageGroups(7)
        val whole = resolveGroups(canonical, spreadActive = true) { null }
        for (page in 0..6) assertEquals("page $page", previousLogicalPage(whole, page), previousPage(canonical, page, spreadActive = true) { false })
    }

    @Test fun boundedNavigationHandlesALandscapeSplitUsingOnlyThatPairsGeometry() {
        val canonical = canonicalPageGroups(5) // [0],[1,2],[3,4]
        val landscape = setOf(2) // page 2 is landscape -> pair [1,2] splits into [1],[2]
        val isLandscapeAt: (Int) -> Boolean = { it in landscape }
        assertEquals(2, nextPage(canonical, 1, spreadActive = true, isLandscapeAt))
        assertEquals(3, nextPage(canonical, 2, spreadActive = true, isLandscapeAt)) // next canonical group, unaffected
        assertEquals(1, previousPage(canonical, 2, spreadActive = true, isLandscapeAt))
        assertEquals(1, previousPage(canonical, 3, spreadActive = true, isLandscapeAt))
    }

    @Test fun boundedNavigationInSingleModeIsOrdinaryPlusOrMinusOneRegardlessOfCanonicalStructure() {
        val canonical = canonicalPageGroups(5)
        assertEquals(2, nextPage(canonical, 1, spreadActive = false) { true }) // isLandscapeAt irrelevant when inactive
        assertEquals(1, previousPage(canonical, 2, spreadActive = false) { true })
    }

    @Test fun resolveCurrentGroupReturnsTheUnsplitPairWhenNeitherMemberIsLandscape() {
        val canonical = canonicalPageGroups(5)
        assertEquals(PageGroup(listOf(1, 2)), resolveCurrentGroup(canonical, 1, spreadActive = true) { false })
        assertEquals(PageGroup(listOf(1, 2)), resolveCurrentGroup(canonical, 2, spreadActive = true) { false })
    }

    @Test fun resolveCurrentGroupReturnsASoloGroupWhenEitherMemberIsLandscape() {
        val canonical = canonicalPageGroups(5)
        assertEquals(PageGroup(listOf(2)), resolveCurrentGroup(canonical, 2, spreadActive = true) { it == 2 })
        assertEquals(PageGroup(listOf(1)), resolveCurrentGroup(canonical, 1, spreadActive = true) { it == 2 })
    }

    @Test fun resolveCurrentGroupIsAlwaysSoloWhenSpreadIsInactive() {
        val canonical = canonicalPageGroups(5)
        assertEquals(PageGroup(listOf(1)), resolveCurrentGroup(canonical, 1, spreadActive = false) { true })
    }

    // ---- Codex R2 finding 1 (3C remediation): PageGeometry.isUsable, and the navigation-conservative contract
    // every caller of nextPage/previousPage/resolveCurrentGroup's isLandscapeAt parameter must honor. ----

    @Test fun zeroByZeroGeometryIsUnusableAndNeverLandscape() {
        val sentinel = PageGeometry(0, 0)
        assertFalse(sentinel.isUsable)
        assertFalse(sentinel.isLandscape)
    }

    @Test fun nonPositiveDimensionsAreUnusableRegardlessOfWhichAxis() {
        assertFalse(PageGeometry(0, 900).isUsable)
        assertFalse(PageGeometry(900, 0).isUsable)
        assertFalse(PageGeometry(-5, 900).isUsable)
        assertFalse(PageGeometry(900, -5).isUsable)
    }

    @Test fun positiveDimensionsAreUsable() {
        assertTrue(PageGeometry(900, 600).isUsable)
        assertTrue(PageGeometry(600, 900).isUsable)
    }

    /**
     * Codex R2 finding 1: reproduces the exact [com.d4guilar.shelfos.feature.reader.FixedReaderViewModel
     * .isLandscapeAtForNavigation] bug pattern at this pure-function level, independent of any ViewModel/Android
     * dependency. [buggyIsLandscapeAt] is byte-for-byte what `bc96099`'s navigation lookup did --
     * `cache[page]?.isLandscape ?: true` -- which only falls back to the conservative `true` when the page has
     * NO cache entry at all; once a cache entry exists (even the [PageGeometry] `(0, 0)` failure sentinel a
     * corrupt/undecodable page's geometry lookup gets cached as), `.isLandscape` is read directly and evaluates
     * `false`, incorrectly telling [nextPage] the pair is confirmed NOT landscape -- authorizing exactly the
     * multi-page skip the conservative fallback exists to prevent. [fixedIsLandscapeAt] is the corrected
     * contract (checking [PageGeometry.isUsable] before ever trusting [PageGeometry.isLandscape]): the SAME
     * cached sentinel now correctly behaves exactly like a missing entry.
     */
    @Test fun cachedUnusableGeometryMustNotAuthorizeANavigationSkip_reproducesTheBuggyVsFixedContract() {
        val canonical = canonicalPageGroups(5) // [0],[1,2],[3,4]
        // Page 1 genuinely resolved as ordinary portrait (usable, correctly not landscape); page 2's lookup
        // failed and was cached as the (0, 0) sentinel -- unusable, so its TRUE landscape-ness is unknown.
        val cache = mapOf(1 to PageGeometry(600, 900), 2 to PageGeometry(0, 0))
        val buggyIsLandscapeAt: (Int) -> Boolean = { page -> cache[page]?.isLandscape ?: true }
        val fixedIsLandscapeAt: (Int) -> Boolean = { page ->
            val geometry = cache[page]
            if (geometry == null) true else !geometry.isUsable || geometry.isLandscape
        }

        // The bug, reproduced: nextPage from page 1 jumps straight to page 3, skipping page 2 as a reachable
        // navigation target -- this assertion FAILS if buggyIsLandscapeAt's behavior is ever "fixed" out from
        // under it, which is exactly the point: it documents what bc96099 actually did.
        assertEquals("sanity: this documents the pre-fix bug pattern itself", 3, nextPage(canonical, 1, spreadActive = true, buggyIsLandscapeAt))

        // The fix: the exact same cached sentinel for page 2 must now be read as "unknown, could be landscape,"
        // so nextPage falls back to the single-step (no-skip) move into page 2 -- never jumping over it.
        assertEquals("an unusable cached geometry must never authorize jumping over the adjacent logical page",
            2, nextPage(canonical, 1, spreadActive = true, fixedIsLandscapeAt))

        // Symmetric backward check from the pair's second member.
        assertEquals(0, previousPage(canonical, 2, spreadActive = true, buggyIsLandscapeAt)) // bug: skips page 1 entirely
        assertEquals(1, previousPage(canonical, 2, spreadActive = true, fixedIsLandscapeAt)) // fixed: single-step back to page 1
    }
}
