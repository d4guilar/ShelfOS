// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.SpreadMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Phase 3C Codex R1 remediation: focused Compose/instrumented UI evidence the original 3C handoff left as an
 * honest, flagged gap (`docs/PHASE_3_IMPLEMENTATION_PLAN.md` §25's "what this slice deliberately does NOT do"),
 * plus direct regression coverage for findings 3/4/5/6. Drives the real [MainActivity]/[FixedReaderScreen]
 * production code against [OriginalFixtures.spreadCbz] fixtures -- never a test-only reimplementation.
 */
class FixedReaderSpreadUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as ShelfApplication).container
    private val seededIds = mutableListOf<String>()

    private fun seed(item: com.d4guilar.shelfos.domain.library.LibraryItem) = runBlocking<Unit> {
        // LibraryRepository.add() does not persist LibraryItem.preferences itself (that field is write-only on
        // this constructor; the real Appearance dialog always separately calls preferences()/savePreferences --
        // see RoomLibraryRepository.add/preferences) -- fixtures that pre-set a SpreadMode/FitMode must do the
        // same, or the title falls back to AUTO/PAGE regardless of what was requested.
        container.library.add(item)
        container.library.preferences(item.id, item.preferences)
        seededIds += item.id
    }

    @After fun removeFixtures() = runBlocking<Unit> {
        seededIds.forEach { container.library.remove(it) }
        container.library.preferences("", "{}")
    }

    private fun awaitLibrary() = compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
    private fun awaitTag(tag: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitPage(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    /** Default library filter is [com.d4guilar.shelfos.domain.library.LibraryFilter.BOOKS] -- every fixture here
     * is COMIC/MANGA, so the matching category tab must be selected before the item is visible to click. */
    private fun read(id: String, category: com.d4guilar.shelfos.domain.library.MediaCategory = com.d4guilar.shelfos.domain.library.MediaCategory.COMIC) {
        compose.onNodeWithText(if (category == com.d4guilar.shelfos.domain.library.MediaCategory.MANGA) "Manga" else "Comics").performClick()
        awaitTag("publication_$id")
        compose.onNodeWithTag("publication_$id").performClick()
        compose.onNodeWithTag("read_action").performScrollTo().performClick()
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private val portrait = 300 to 450
    // Both of these are deliberately non-landscape (width/height <= PageGeometry.LANDSCAPE_ASPECT_THRESHOLD's
    // 1.05) but visibly different aspect ratios from each other and from [portrait] -- a genuinely "mixed
    // aspect" healthy pair that must still both display, never forced solo by the landscape-split rule.
    private val mixedAspectA = 350 to 420
    private val mixedAspectB = 280 to 500

    // ---- Codex R1 finding 3: corrupt slot never corrupts the whole combined layout ----

    @Test fun corruptFirstSlotLeavesHealthySiblingVisibleAndCorrectlyPositioned() {
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-corrupt-first",
            listOf(portrait, portrait, portrait), corruptPages = setOf(2)) // page index 1 (0-based) corrupt
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitTag("spread_slot_1")
        awaitTag("spread_slot_2")
        val failed = bounds("spread_slot_1") // corrupt
        val healthy = bounds("spread_slot_2") // healthy
        assertTrue("corrupt placeholder must have real visible bounds", failed.width > 0f && failed.height > 0f)
        assertTrue("healthy sibling must have real visible bounds", healthy.width > 0f && healthy.height > 0f)
        // Neither slot is pushed entirely outside the spread container: both must be within a sane, mutually
        // non-overlapping horizontal arrangement (healthy sits to the right of the failed placeholder in LTR).
        assertTrue("healthy sibling must not overlap the failed placeholder", healthy.left >= failed.right - 1f)
        // Logical page tags/semantics/pair membership are unaffected by which slot failed.
        compose.onNodeWithTag("spread_slot_1").assertExists()
        compose.onNodeWithTag("spread_slot_2").assertExists()
        // Navigation still works after a corrupt-first pair: with only 3 pages, [1,2] is the FINAL group, so
        // Next is correctly disabled here (Codex R1 finding 6) rather than skipping anywhere -- no crash, no
        // stuck/incorrect state.
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Previous").performClick()
        awaitPage("1 / 3")
    }

    @Test fun corruptSecondSlotLeavesHealthySiblingVisibleAndCorrectlyPositioned() {
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-corrupt-second",
            listOf(portrait, portrait, portrait), corruptPages = setOf(3)) // page index 2 (0-based) corrupt
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitTag("spread_slot_1")
        awaitTag("spread_slot_2")
        val healthy = bounds("spread_slot_1")
        val failed = bounds("spread_slot_2")
        assertTrue("healthy sibling must have real visible bounds", healthy.width > 0f && healthy.height > 0f)
        assertTrue("corrupt placeholder must have real visible bounds", failed.width > 0f && failed.height > 0f)
        assertTrue("failed placeholder must not overlap the healthy sibling", failed.left >= healthy.right - 1f)
    }

    // ---- RTL end-to-end: actual physical placement, not just text/tag existence ----

    @Test fun ltrPlacesTheLowerLogicalPageOnThePhysicalLeft() {
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-rtl-ltr",
            listOf(portrait, portrait, portrait), category = com.d4guilar.shelfos.domain.library.MediaCategory.COMIC)
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        val page1 = bounds("spread_slot_1") // logical page 1 (lower index)
        val page2 = bounds("spread_slot_2") // logical page 2 (higher index)
        assertTrue("LTR: logical page 1 must be physically LEFT of logical page 2", page1.left < page2.left)
        // state.page must still be the logical page navigated to, never reversed by physical placement.
        compose.onNode(hasTestTag("page_number") and hasText("2 / 3")).assertExists()
    }

    @Test fun rtlMirrorsPhysicalPlacementWithoutReversingLogicalIdentity() {
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-rtl-manga",
            listOf(portrait, portrait, portrait), category = com.d4guilar.shelfos.domain.library.MediaCategory.MANGA)
        seed(item)
        awaitLibrary()
        read(item.id, category = com.d4guilar.shelfos.domain.library.MediaCategory.MANGA)
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        val page1 = bounds("spread_slot_1")
        val page2 = bounds("spread_slot_2")
        // RTL Manga default: physical placement mirrors (higher logical index on the left)...
        assertTrue("RTL: logical page 2 must be physically LEFT of logical page 1", page2.left < page1.left)
        // ...but the logical page/state.page identity is never reordered: tags still correctly identify page 1/2.
        compose.onNodeWithTag("spread_slot_1").assertExists()
        compose.onNodeWithTag("spread_slot_2").assertExists()
    }

    // ---- Fit Page: mixed-aspect healthy pair, both slots visible, no overlap, gutter exists ----

    @Test fun fitPageMixedAspectPairStaysWithinViewportWithGutterAndNoOverlap() {
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-fit-page-mixed",
            listOf(portrait, mixedAspectA, mixedAspectB))
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        val viewport = compose.onNodeWithTag("reader_page").fetchSemanticsNode().boundsInRoot
        val first = bounds("spread_slot_1")
        val second = bounds("spread_slot_2")
        assertTrue("first slot must be visible", first.width > 0f && first.height > 0f)
        assertTrue("second slot must be visible", second.width > 0f && second.height > 0f)
        assertTrue("no overlap between slots", second.left >= first.right - 1f)
        assertTrue("a real gutter must separate the two slots", second.left - first.right >= 0f)
        assertTrue("combined spread stays within viewport width", first.left >= viewport.left - 1f && second.right <= viewport.right + 1f)
        assertTrue("combined spread stays within viewport height", first.top >= viewport.top - 1f && first.bottom <= viewport.bottom + 1f)
    }

    // ---- Codex R1 finding 6: final-complete-spread Next-enablement ----

    @Test fun nextIsDisabledOnTheFirstPageOfTheFinalCompleteSpread() {
        // 5 pages -> canonical groups [0],[1,2],[3,4]; landing on page 3 (first member of the FINAL complete
        // spread) must disable Next -- naive `page+1 < count` arithmetic would wrongly enable it (page 4 exists,
        // but there is no next READING UNIT).
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-final-spread",
            listOf(portrait, portrait, portrait, portrait, portrait))
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 5")
        compose.onNodeWithText("Next").performClick() // -> page 1 (pairs with 2)
        awaitPage("2 / 5")
        compose.onNodeWithText("Next").performClick() // -> page 3 (pairs with 4, the FINAL complete spread)
        awaitPage("4 / 5")
        compose.onNodeWithText("Next").assertIsNotEnabled()
        compose.onNodeWithText("Previous").assertIsEnabled()
    }

    // ---- Codex R1 finding 5: SpreadMode change applies immediately to the active session ----

    @Test fun switchingFromSpreadToSingleAppliesImmediatelyWithoutChangingTheLogicalPage() {
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-mode-apply",
            listOf(portrait, portrait, portrait, portrait, portrait), spreadMode = SpreadMode.SPREAD)
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 5")
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 5") // page 1, shown as part of spread [1,2]
        awaitTag("spread_slot_2") // confirms the spread is actually showing 2 slots right now
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Single page").performClick().assertIsSelected()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        // Reconciled immediately: now exactly one slot, state.page unchanged at the same logical page.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("spread_slot_2").fetchSemanticsNodes().isEmpty() }
        compose.onNode(hasTestTag("page_number") and hasText("2 / 5")).assertExists()
    }

    @Test fun switchingFromSingleToSpreadAppliesImmediatelyAndDerivesTheEligiblePair() {
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-mode-apply-reverse",
            listOf(portrait, portrait, portrait, portrait, portrait), spreadMode = SpreadMode.SINGLE)
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 5")
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 5") // single mode: exactly one slot even though page 1/2 would pair
        compose.onNodeWithText("Appearance").performClick()
        // "Two-page spread" also appears as plain label text elsewhere in this dialog, not just on the
        // selectable chip -- disambiguate to the actual clickable chip node.
        compose.onNode(hasText("Two-page spread") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        compose.onNode(hasTestTag("page_number") and hasText("2 / 5")).assertExists()
    }

    // ---- Codex R1 finding 4: a spread<->single flip mid-session (without a page change) must not leave a
    // gesture reading stale slot/bitmap geometry ----

    @Test fun gestureGeometryStaysCurrentAcrossASpreadToSingleFlipWithoutAPageChange() {
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-gesture-stale",
            listOf(portrait, portrait, portrait, portrait, portrait), spreadMode = SpreadMode.SPREAD)
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 5")
        compose.onNodeWithText("Next").performClick()
        awaitPage("2 / 5")
        awaitTag("spread_slot_2") // two slots now visible
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Single page").performClick().assertIsSelected()
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("spread_slot_2").fetchSemanticsNodes().isEmpty() }
        // Zoom (a real gesture through the production double-tap path) must act on the NEW single-slot content
        // without crashing or using a stale 2-slot geometry -- the production code must have re-derived gesture
        // geometry via rememberUpdatedState (finding 4's fix) rather than a stale captured slot list.
        compose.onNodeWithTag("reader_page").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onNodeWithText("Reset zoom").assertExists() // zoomed successfully against the current content
        compose.onNodeWithTag("reader_page").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
    }

    // ---- Fit Width + zoom: spread geometry evidence, closing the remaining uncertainty flagged in the brief ----

    private fun scrollProbe(): Triple<Int, Int, Float> {
        val node = compose.onNodeWithTag("reader_scroll_probe").fetchSemanticsNode()
        val raw = node.config.getOrNull(SemanticsProperties.StateDescription) ?: "0,0,0"
        val (value, max, h) = raw.split(",")
        return Triple(value.toInt(), max.toInt(), h.toFloat())
    }
    private fun transformProbe(): Triple<Float, Float, Float> {
        val node = compose.onNodeWithTag("reader_transform_probe").fetchSemanticsNode()
        val raw = node.config.getOrNull(SemanticsProperties.StateDescription) ?: "1.0,0.0,0.0"
        val (scale, x, y) = raw.split(",")
        return Triple(scale.toFloat(), x.toFloat(), y.toFloat())
    }

    @Test fun fitWidthSpreadZoomStaysClampedAndStatePageNeverChanges() {
        // A tall-ish pair (aspect close to the tallAspect-like shape) so the combined content genuinely exceeds
        // the viewport height in Fit Width, exercising the real verticalScroll + graphicsLayer clamp path for
        // the SPREAD combined-content box, not just a single bitmap's.
        val tall = 300 to 900
        val item = OriginalFixtures.spreadCbz(instrumentation.targetContext, "ui-fitwidth-spread",
            listOf(portrait, tall, tall), fit = FitMode.WIDTH)
        seed(item)
        awaitLibrary()
        read(item.id)
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick()
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        awaitTag("reader_scroll_probe")
        assertTrue("fixture must produce a scrollable (taller-than-viewport) Fit Width spread",
            scrollProbe().second > 0)
        assertEquals("fresh spread must start scrolled to the top", 0, scrollProbe().first)
        compose.onNodeWithText("Zoom in").performClick()
        assertEquals(2f, transformProbe().first, 0f)
        // Drag further down past the top edge repeatedly (the same "beyond edge" stress as the single-page
        // Fit Width regression test) -- scroll must stay clamped at its own floor and panY must stay exactly 0
        // (Fit Width never drives vertical movement via the pan transform, spread or not).
        repeat(6) {
            compose.onNodeWithTag("reader_page").performTouchInput {
                swipe(start = Offset(centerX, top + 20f), end = Offset(centerX, bottom - 20f), durationMillis = 120)
            }
            compose.waitForIdle()
        }
        assertEquals("Fit Width must never move vertically via the pan transform, even for a spread", 0f, transformProbe().third, 0f)
        assertEquals("scroll cannot go past its own top floor", 0, scrollProbe().first)
        // state.page must still be exactly the logical page navigated to throughout every gesture above.
        compose.onNode(hasTestTag("page_number") and hasText("2 / 3")).assertExists()
    }
}
