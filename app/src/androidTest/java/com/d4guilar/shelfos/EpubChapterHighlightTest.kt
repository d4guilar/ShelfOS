// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 2B.1: the Chapters dialog's current-chapter highlight, chapter jump and the (above-threshold) chapter
 * title filter, against real EPUB fixtures opened and navigated through the real Readium navigator/`EpubSession`
 * wiring — the matching *algorithm* itself is covered by `EpubChapterMatchTest` (plain JVM); this class exists to
 * prove what the real `Publication.locatorFromLink`/`navigator.currentLocator` pipeline actually does in
 * practice, which a synthetic-string JVM test cannot — including a real, verified navigator limitation this
 * pass's fragmented-fixture test documents rather than assumes away (see that test's own doc comment).
 */
class EpubChapterHighlightTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    @Before fun seedOriginalEpub() = runBlocking<Unit> {
        container.library.add(OriginalFixtures.epubWithChapters(context))
        container.library.add(OriginalFixtures.epubWithFragmentedChapter(context))
    }
    @After fun removeOriginalEpub() = runBlocking<Unit> {
        container.library.remove("test-epub-chapters")
        container.library.remove("test-epub-fragments")
    }

    private fun awaitReader() = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    /** A chapter row's title exactly — not a substring match, since e.g. "Chapter 1" is a substring of both
     * "Chapter 10" and the filter field's own typed text (which could even equal a title exactly, hence also
     * excluding the field's own `SetText` action). The checkmark is its own sibling node when current (see
     * EpubActivity), so this matches the title whether or not the row is currently marked. */
    private fun chapterRow(title: String) = hasText(title) and hasClickAction() and !hasSetTextAction()

    @Test fun currentChapterIsMarkedAndUpdatesAfterNavigatingElsewhere() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            // A fresh item has no persisted locator, so the reader opens at the first spine item, Chapter 1.
            compose.onNodeWithText("Chapters").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Chapter 1, current chapter").fetchSemanticsNodes().isNotEmpty() }
            // Tapping/activating another chapter navigates exactly as before this slice, then closes the dialog.
            compose.onNode(chapterRow("Chapter 5")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size == 1 }
            // Reopen: the current chapter recomputes from the resulting locator, not from stored state.
            compose.onNodeWithText("Chapters").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Chapter 5, current chapter").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Chapter 1, current chapter").assertCountEquals(0)
        }
    }

    @Test fun chapterTitleFilterNarrowsClearsAndHandlesNoMatches() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-chapters")).use {
            awaitReader()
            compose.onNodeWithText("Chapters").performClick()
            // The fixture's 10 entries are above CHAPTER_FILTER_THRESHOLD, so the filter field must be present.
            compose.onNodeWithContentDescription("Filter chapters").assertExists()
            compose.onNodeWithTag("chapters_list").performScrollToNode(chapterRow("Chapter 10"))
            compose.onNodeWithContentDescription("Filter chapters").performTextInput("Chapter 7")
            compose.onNode(chapterRow("Chapter 7")).assertExists()
            compose.onAllNodes(chapterRow("Chapter 1")).assertCountEquals(0)
            compose.onAllNodes(chapterRow("Chapter 10")).assertCountEquals(0)
            // Clearing restores the full, original-order list.
            compose.onNodeWithContentDescription("Filter chapters").performTextClearance()
            compose.onNode(chapterRow("Chapter 1")).assertExists()
            compose.onNodeWithTag("chapters_list").performScrollToNode(chapterRow("Chapter 10"))
            // A query matching nothing is a plain, non-error empty state, not a crash or a stale list.
            compose.onNodeWithContentDescription("Filter chapters").performTextInput("no such chapter")
            compose.onNodeWithText("No chapters match “no such chapter”.").assertExists()
            compose.onAllNodes(chapterRow("Chapter 1")).assertCountEquals(0)
        }
    }

    /**
     * Codex R2 (second remediation round): two real TOC entries pointing into the *same* XHTML resource at
     * different fragments (`OriginalFixtures.epubWithFragmentedChapter`, neither entry is a "whole chapter"
     * resource-level entry) — exercising the real `Publication.locatorFromLink` pipeline a synthetic-string JVM
     * test cannot.
     *
     * **Why this asserts zero current rows, not one:** direct investigation during the first remediation round
     * (real `Locator` JSON logged from `EpubSurface`'s `onLocation`, for both the `Navigator.go(Link, animated)`
     * and `Navigator.go(Locator, animated)` overloads) showed the pinned Readium 3.4.0 EPUB navigator's own
     * `currentLocator`, in its default paginated (non-scroll) mode, never populates `Locations.fragments` after
     * navigating to a fragment — only `progression`/`position`/`totalProgression`. With two fragment-only
     * same-resource candidates and no locator fragment to distinguish them, `matchChapter`'s corrected fallback
     * (see its doc comment in `core.reader.EpubReader.kt`) correctly returns null — genuinely ambiguous — rather
     * than arbitrarily picking one. Showing *no* current chapter here is the intended, honest behavior, not a
     * gap: a confidently-wrong single highlighted row would be worse than an unmarked list. This is a verified
     * Readium/navigator-configuration constraint, not a ShelfOS matching defect — the multi-fragment algorithm
     * itself is proven correct by `EpubChapterMatchTest`'s synthetic-locator JVM tests, ready for if/when a real
     * locator ever does carry fragments. Building an HTML-position heuristic to work around this remains out of
     * scope. See `docs/PHASE_2_PLAN.md`'s 2B.1 section for the same assessment recorded for Codex re-review.
     * `currentChapterIsMarkedAndUpdatesAfterNavigatingElsewhere` (above) retains coverage of the *unambiguous*
     * case — every chapter there is the sole entry for its own resource — showing exactly one current row.
     */
    @Test fun ambiguousSameResourceFragmentsShowNoCurrentRowButNavigationAndTheDialogStillWork() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-fragments")).use {
            awaitReader()
            // No persisted locator yet, and the real navigator's first-reported locator carries no fragment
            // either (verified during the first remediation round) — with two fragment-only, no-resource-level
            // candidates, this is ambiguous from the start: no row should be marked current.
            compose.onNodeWithText("Chapters").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(chapterRow("Section One")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasContentDescription("current chapter", substring = true)).assertCountEquals(0)
            // Selecting the other same-resource entry still navigates and closes the dialog exactly as any other
            // chapter jump does — the interaction/wiring is unaffected by the ambiguity or by the two rows
            // sharing one resource.
            compose.onNode(chapterRow("Section Two")).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapters").fetchSemanticsNodes().size == 1 }
            // Reopening remains stable and still honestly shows no current row (the live locator still carries no
            // fragment after this navigation, per the documented Readium limitation) — never a wrong guess.
            compose.onNodeWithText("Chapters").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(chapterRow("Section Two")).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasContentDescription("current chapter", substring = true)).assertCountEquals(0)
        }
    }
}
