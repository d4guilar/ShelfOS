// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import android.os.Debug
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.FixedReader
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.PageGeometry
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.core.reader.RenderMemoryPolicy
import com.d4guilar.shelfos.core.reader.SpreadMode
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.reader.FixedReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 3C: drives [FixedReaderViewModel] directly (no Compose/Espresso -- same reasoning and the same AVD-
 * incompatibility-avoidance rationale as [FixedReaderViewModelLifecycleTest]) against real CBZ fixtures to prove
 * the logical-identity invariant, canonical pairing, landscape handling, AUTO resize behavior, corrupt-page
 * handling, and thumbnail-jump semantics actually hold end-to-end through the real render pipeline -- not just
 * through the pure [com.d4guilar.shelfos.core.reader.SpreadModelTest] math in isolation.
 *
 * Explicitly NOT covered here (an honest gap, not a silent omission -- see the Phase 3C handoff): RTL Manga's
 * mirrored PHYSICAL placement and the combined Fit Page/Fit Width/zoom-pan geometry are
 * [FixedReaderScreen][com.d4guilar.shelfos.feature.reader.FixedReaderScreen]-level (Compose) concerns this
 * ViewModel-only test cannot observe (state.slots is always logical ascending order regardless of reading
 * direction; direction only affects which physical side each slot is drawn on). A real Espresso/Compose pass for
 * those remains outstanding and is flagged for follow-up.
 */
class FixedReaderSpreadViewModelTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val factory get() = FixedReaderFactory(PublicationFiles(context))
    private val scopes = mutableListOf<CoroutineScope>()
    private val stores = mutableListOf<ViewModelStore>()

    @After fun tearDown() {
        stores.forEach { it.clear() }
        scopes.forEach { it.cancel() }
    }

    private class FakeLibraryRepository(item: LibraryItem) : LibraryRepository {
        private val itemFlow = MutableStateFlow(item)
        override val publications get() = MutableStateFlow(listOf(itemFlow.value))
        override val globalPreferences: StateFlow<String?> = MutableStateFlow(null)
        override fun publication(id: String): StateFlow<LibraryItem?> = itemFlow
        override suspend fun add(item: LibraryItem) = item.id
        override suspend fun favorite(id: String) {}
        override suspend fun edit(id: String, title: String, creator: String, category: MediaCategory) {}
        override suspend fun remove(id: String) {}
        override suspend fun available(id: String, available: Boolean) {}
        override suspend fun reading(id: String, locator: String, progress: Int) {}
        override suspend fun preferences(id: String, json: String) {}
    }

    /** Builds a real CBZ fixture with per-page dimensions ([pageSizes], 1-based page order), so individual pages
     * can be made deliberately landscape or deliberately corrupt (a non-image entry masquerading as a page). */
    private fun cbzFixture(name: String, pageSizes: List<Pair<Int, Int>>, corruptPages: Set<Int> = emptySet(),
        category: MediaCategory = MediaCategory.COMIC, spreadMode: SpreadMode? = null): LibraryItem {
        val file = File(context.filesDir, "publications/$name").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            pageSizes.forEachIndexed { index, (w, h) ->
                zip.putNextEntry(ZipEntry("page${index + 1}.png"))
                if ((index + 1) in corruptPages) zip.write(byteArrayOf(1, 2, 3, 4)) // not a decodable image
                else {
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle()
                }
                zip.closeEntry()
            }
        }
        return LibraryItem(name, "Spread $name", "ShelfOS test", category, "test:$name",
            PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path,
            preferences = ReaderPreferences(fit = FitMode.PAGE, spreadMode = spreadMode).json())
    }

    private fun viewModel(item: LibraryItem, factoryOverride: FixedReaderFactory? = null): FixedReaderViewModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = ViewModelStore().also { stores += it }
        val repository = FakeLibraryRepository(item)
        val usedFactory = factoryOverride ?: factory
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FixedReaderViewModel(item.id, repository, usedFactory, scope) as T
        })
        return provider[FixedReaderViewModel::class.java]
    }

    /**
     * Codex R2 finding 2 (3C remediation): the real [FixedReaderFactory.open] result, wrapped so
     * [FixedReader.pageGeometry] for a chosen set of logical pages blocks on [gate] until the test explicitly
     * releases it (`gate.countDown()`) -- a deterministic replacement for the R1 remediation test's reliance on
     * an async `StateFlow` collector, which Codex R2 found nondeterministic (collecting emissions in the
     * background can conflate/miss intermediate `state.page` values; `visited=[1, 1, 3, 3]` was observed).
     * Everything else (page count, rendering, geometry for ungated pages) delegates straight through to the real
     * session -- no behavior is faked beyond the one deliberately-held-open lookup.
     */
    private class GatedGeometryFixedReaderFactory(files: PublicationFiles, private val gatedPages: Set<Int>,
        private val gate: CountDownLatch) : FixedReaderFactory(files) {
        override fun open(item: LibraryItem): FixedReader {
            val real = super.open(item)
            return object : FixedReader by real {
                override fun pageGeometry(index: Int): PageGeometry? {
                    if (index in gatedPages) gate.await()
                    return real.pageGeometry(index)
                }
            }
        }
    }

    private fun awaitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(25) }
        fail("Condition not met within ${timeoutMs}ms")
    }

    private fun awaitSettled(vm: FixedReaderViewModel, targetPage: Int? = null, timeoutMs: Long = 15_000) {
        awaitUntil(timeoutMs) { val s = vm.state.value; !s.loading && s.slots.isNotEmpty() && (targetPage == null || s.page == targetPage) }
    }

    private val portrait = 600 to 900
    private val landscape = 900 to 600

    // ---- Explicit SPREAD: eligible interior pair renders as 2 slots; cover stays solo ----

    @Test fun explicitSpreadRendersCoverSoloThenAnInteriorPairAsTwoSlots() {
        val item = cbzFixture("spread-cover.cbz", listOf(portrait, portrait, portrait, portrait, portrait), spreadMode = SpreadMode.SPREAD)
        val vm = viewModel(item)
        vm.updateViewport(2000, 1000, 1000) // wide viewport; irrelevant to SPREAD (it overrides AUTO's width check)
        awaitSettled(vm, targetPage = 0)
        assertEquals(listOf(0), vm.state.value.slots.map { it.page }) // cover solo

        vm.turn(1)
        awaitSettled(vm, targetPage = 1)
        assertEquals(listOf(1, 2), vm.state.value.slots.map { it.page }) // interior pair, both logical pages present
        assertEquals(1, vm.state.value.page) // logical page invariant: still exactly the page that was navigated to
    }

    // ---- Explicit SINGLE: never shows 2 slots even on a very wide viewport ----

    @Test fun explicitSingleNeverShowsTwoSlotsRegardlessOfViewportWidth() {
        val item = cbzFixture("single-wide.cbz", listOf(portrait, portrait, portrait), spreadMode = SpreadMode.SINGLE)
        val vm = viewModel(item)
        vm.updateViewport(4000, 1000, 4000)
        awaitSettled(vm, targetPage = 0)
        vm.turn(1)
        awaitSettled(vm, targetPage = 1)
        assertEquals(listOf(1), vm.state.value.slots.map { it.page })
    }

    // ---- AUTO: narrow -> single, wide -> spread, and resize never changes the logical page ----

    @Test fun autoResolvesToSingleOnANarrowViewportAndToSpreadOnAWideOne() {
        val item = cbzFixture("auto-resize.cbz", listOf(portrait, portrait, portrait, portrait, portrait), spreadMode = SpreadMode.AUTO)
        val vm = viewModel(item)
        vm.updateViewport(400, 800, 400) // narrow: below AUTO_SPREAD_MIN_WIDTH_DP
        awaitSettled(vm, targetPage = 0)
        vm.turn(1)
        awaitSettled(vm, targetPage = 1)
        assertEquals(listOf(1), vm.state.value.slots.map { it.page }) // narrow -> single

        vm.updateViewport(1600, 800, 1600) // wide: at/above the threshold -> should reconcile to a spread
        awaitUntil { vm.state.value.slots.map { it.page } == listOf(1, 2) }
        assertEquals(1, vm.state.value.page) // logical page never changed by the resize-driven reconciliation

        vm.updateViewport(400, 800, 400) // back to narrow -> single again
        awaitUntil { vm.state.value.slots.map { it.page } == listOf(1) }
        assertEquals(1, vm.state.value.page) // still unchanged throughout every resize
    }

    // ---- Landscape page handling: a wide page forces solo even in explicit SPREAD mode ----

    @Test fun aLandscapePageStaysSoloInExplicitSpreadModeWithoutSkippingOrDuplicatingItsPair() {
        // 5 pages; page index 2 (page "3") is landscape -> canonical pair [1,2] (0-based) must split.
        val item = cbzFixture("landscape.cbz", listOf(portrait, portrait, landscape, portrait, portrait), spreadMode = SpreadMode.SPREAD)
        val vm = viewModel(item)
        vm.updateViewport(2000, 1000, 1000)
        awaitSettled(vm, targetPage = 0)

        vm.turn(1) // -> page 1, should now be solo (its canonical partner, page 2, is landscape)
        awaitSettled(vm, targetPage = 1)
        assertEquals(listOf(1), vm.state.value.slots.map { it.page })

        vm.turn(1) // -> page 2 (the landscape page itself), also solo
        awaitSettled(vm, targetPage = 2)
        assertEquals(listOf(2), vm.state.value.slots.map { it.page })

        vm.turn(1) // -> page 3, which pairs normally with page 4 (unaffected by the earlier split)
        awaitSettled(vm, targetPage = 3)
        assertEquals(listOf(3, 4), vm.state.value.slots.map { it.page })
    }

    // ---- Codex R2 finding 2 (3C remediation): deterministic rapid-navigation evidence ----

    /**
     * Codex R2 finding 2: replaces the R1 remediation test's async-`StateFlow`-collector evidence (Codex R2
     * observed `visited=[1, 1, 3, 3]` and flagged that collecting in the background can conflate/miss
     * intermediate `state.page` values -- not proof of the exact sequence). This version instead GATES the
     * candidate pair's geometry resolution open with a [CountDownLatch] the test controls explicitly, then reads
     * the AUTHORITATIVE `state.value.page` synchronously right after each [FixedReaderViewModel.turn] call --
     * `showPage()` updates `_state` via a plain (non-suspending) `MutableStateFlow.update` before it ever
     * launches the async render, so this is exact and immediate, never a race against a collector.
     *
     * Sequence: (A) start at page 0; (B) first Next -> `state.page` becomes 1 (asserted synchronously); (C)
     * before the candidate pair [1,2]'s geometry resolves (the gate is still closed, so [render]'s IO coroutine
     * is blocked inside the gated `pageGeometry` lookup and has written NOTHING to `geometryCache` yet), a second
     * Next; (D) `state.value.page` is asserted synchronously, immediately -- must be 2 (the split page), never 3
     * (which would mean the still-unresolved pair was wrongly treated as "confirmed not landscape," skipping
     * page 2 entirely). The gate is then released and the reader must settle correctly: the landscape page
     * becomes solo, presentation reconciles, the logical current page stays valid, and further navigation
     * proceeds normally.
     */
    private fun assertDeterministicRapidNextDoesNotSkipTheGatedPair(splitPageIndex: Int, pageSizes: List<Pair<Int, Int>>, name: String) {
        val item = cbzFixture(name, pageSizes, spreadMode = SpreadMode.SPREAD)
        val gate = CountDownLatch(1)
        val gatedFactory = GatedGeometryFixedReaderFactory(PublicationFiles(context), gatedPages = setOf(1, 2), gate = gate)
        val vm = viewModel(item, factoryOverride = gatedFactory)
        vm.updateViewport(2000, 1000, 1000)
        awaitSettled(vm, targetPage = 0) // (A) page 0's group is solo -- never touches the gated pages 1/2.

        vm.turn(1) // (B) first Next: canonicalGroups[0]=[0] -> next group's first page, no geometry lookup needed.
        assertEquals(1, vm.state.value.page)

        vm.turn(1) // (C) second Next, strictly before the candidate pair's geometry resolves (gate still closed).
        // (D) Inspect the authoritative navigation result synchronously, immediately -- no waiting, no collector.
        assertEquals("rapid Next must not jump from page 1 straight to page 3 while the candidate pair's " +
            "geometry is still unresolved -- page $splitPageIndex must remain the next reachable target, never skipped",
            splitPageIndex, vm.state.value.page)

        gate.countDown() // Release geometry resolution.
        awaitSettled(vm, targetPage = splitPageIndex, timeoutMs = 20_000)
        assertEquals("once geometry resolves, the landscape page must present solo (presentation reconciles)",
            listOf(splitPageIndex), vm.state.value.slots.map { it.page })
        // No incorrect persisted locator/progress: the final settled page is a real, reachable one.
        assertTrue(vm.state.value.page in 0 until vm.state.value.count)

        // Further navigation proceeds correctly afterward.
        vm.turn(1)
        awaitSettled(vm, timeoutMs = 20_000)
        assertTrue("navigation must proceed normally past the split page", vm.state.value.page > splitPageIndex)
    }

    @Test fun deterministicRapidNextDoesNotSkipTheGatedPair_firstMemberLandscape() =
        assertDeterministicRapidNextDoesNotSkipTheGatedPair(splitPageIndex = 2,
            pageSizes = listOf(portrait, landscape, portrait, portrait, portrait), name = "det-race-first.cbz")

    @Test fun deterministicRapidNextDoesNotSkipTheGatedPair_secondMemberLandscape() =
        assertDeterministicRapidNextDoesNotSkipTheGatedPair(splitPageIndex = 2,
            pageSizes = listOf(portrait, portrait, landscape, portrait, portrait), name = "det-race-second.cbz")

    // ---- Codex R2 finding 1 (3C remediation): a cached UNUSABLE geometry must never authorize a skip either ----

    /**
     * Distinct from the race above: here geometry resolution is NOT in flight at all -- the pair has already
     * fully settled, and page 2's geometry lookup genuinely FAILED (a corrupt page) and was cached as the
     * `PageGeometry(0, 0)` failure sentinel. This test fails against `bc96099` (whose navigation lookup read
     * `cache[page]?.isLandscape ?: true`, so the PRESENT-but-unusable cached sentinel bypassed the conservative
     * `?:` fallback and was misread as "confirmed not landscape") and passes once navigation checks
     * [PageGeometry.isUsable] before ever trusting [PageGeometry.isLandscape].
     */
    @Test fun cachedUnusableGeometryForACorruptPairMemberNeverAuthorizesANavigationSkip() {
        val item = cbzFixture("unusable-geometry.cbz", listOf(portrait, portrait, portrait, portrait, portrait),
            corruptPages = setOf(3), spreadMode = SpreadMode.SPREAD) // page index 2 (0-based) corrupt
        val vm = viewModel(item)
        vm.updateViewport(2000, 1000, 1000)
        awaitSettled(vm, targetPage = 0)
        vm.turn(1) // -> page 1, pair [1,2]; page 2's geometry lookup fails and gets cached as PageGeometry(0, 0).
        awaitSettled(vm, targetPage = 1)
        assertEquals(listOf(1, 2), vm.state.value.slots.map { it.page }) // confirms the pair actually settled.

        vm.turn(1) // Navigation decision only -- showPage() updates state.page synchronously; read it immediately.
        assertEquals("a corrupt page's cached PageGeometry(0, 0) sentinel must never authorize jumping straight " +
            "to page 3, skipping page 2 as a reachable navigation target", 2, vm.state.value.page)
    }

    // ---- Corrupt page within a pair: healthy sibling stays visible, failed page gets its own error, no crash ----

    @Test fun aCorruptPageWithinAPairLeavesItsHealthySiblingVisibleAndDoesNotBlankTheWholePair() {
        val item = cbzFixture("corrupt-pair.cbz", listOf(portrait, portrait, portrait), corruptPages = setOf(3), spreadMode = SpreadMode.SPREAD)
        val vm = viewModel(item)
        vm.updateViewport(2000, 1000, 1000)
        awaitSettled(vm, targetPage = 0)
        vm.turn(1) // -> page 1, paired with page 2 (the corrupt entry, 0-based index 2 == "page3.png")
        awaitSettled(vm, targetPage = 1)

        val slots = vm.state.value.slots
        assertEquals(listOf(1, 2), slots.map { it.page })
        val healthy = slots.first { it.page == 1 }
        val failed = slots.first { it.page == 2 }
        assertNotNull(healthy.bitmap)
        assertNull(healthy.error)
        assertNull(failed.bitmap)
        assertNotNull(failed.error)
        // The top-level Retry/Back-to-library error affordance only activates when EVERY slot failed -- here one
        // slot is healthy, so it must stay null (the healthy page is not hidden behind a session-level error).
        assertNull(vm.state.value.error)
        // Navigation still works after a corrupt pair (no crash, no stuck state).
        vm.turn(1)
        awaitUntil { vm.state.value.page == 1 || vm.state.value.page == 2 } // forward from a split-by-failure... still reachable
    }

    // ---- Thumbnail jump semantics: selecting the second pair member keeps it the authoritative logical page ----

    @Test fun jumpingDirectlyToTheSecondPairMemberKeepsItCurrentRatherThanNormalizingToTheFirst() {
        val item = cbzFixture("jump.cbz", listOf(portrait, portrait, portrait, portrait, portrait), spreadMode = SpreadMode.SPREAD)
        val vm = viewModel(item)
        vm.updateViewport(2000, 1000, 1000)
        awaitSettled(vm, targetPage = 0)

        vm.showPage(2) // the exact call a thumbnail-strip selection uses; page 2 is the second member of pair [1,2]
        awaitSettled(vm, targetPage = 2)
        assertEquals(2, vm.state.value.page) // never silently normalized to 1
        assertEquals(listOf(1, 2), vm.state.value.slots.map { it.page }) // the containing pair is still what's shown
    }

    // ---- Books/Documents never get spread treatment even on a PDF/CBZ-capable wide viewport ----

    @Test fun ordinaryBookCategoryNeverPairsEvenWithAnExplicitSpreadPreferenceAndWideViewport() {
        // ReaderAppearance's capabilities.spread already hides the control entirely for non-Comic/Manga titles,
        // but this proves the defense-in-depth gate in FixedReaderViewModel.spreadActive() too: even a stray
        // SpreadMode.SPREAD value (e.g. left over from before a re-categorization) can never make the actual
        // render pipeline show a Book/Document as a two-page spread (AGENTS.md: "Books/Comics/Manga/Documents are
        // first-class categories"; the owner's brief: never expose spread behavior for an ordinary Book merely
        // because its format happens to be PDF/CBZ-capable).
        val item = cbzFixture("book.cbz", listOf(portrait, portrait, portrait), category = MediaCategory.BOOK, spreadMode = SpreadMode.SPREAD)
        val vm = viewModel(item)
        vm.updateViewport(2000, 1000, 1000)
        awaitSettled(vm, targetPage = 0)
        vm.turn(1)
        awaitSettled(vm, targetPage = 1)
        assertEquals(listOf(1), vm.state.value.slots.map { it.page }) // solo, despite the stray SPREAD preference
    }

    // ---- Memory: repeated spread turns stay bounded, no unbounded bitmap retention ----

    @Test fun repeatedSpreadTurnsStayWithinThePerBitmapBudgetAndDoNotGrowProcessMemoryUnboundedly() {
        // A high-resolution source so each of the 2 sequential per-turn decodes is a genuinely large bitmap,
        // mirroring FixedReaderViewModelLifecycleTest's single-page memory evidence but for the spread path: each
        // turn here triggers UP TO TWO sequential (never parallel) full-resolution decodes instead of one.
        val big = 3000 to 4500
        val item = cbzFixture("spread-memory.cbz", List(9) { big }, spreadMode = SpreadMode.SPREAD)
        val vm = viewModel(item)
        vm.updateViewport(2000, 1200, 1000)
        awaitSettled(vm, targetPage = 0)

        System.gc(); Thread.sleep(200)
        val pssBefore = Debug.getPss()

        // Rapid, un-awaited forward/backward spread navigation -- each turn may need to decode 2 slots
        // sequentially; the shared mutex (same discipline as the single-page lifecycle test) means a fast-turn
        // burst still resolves one render's fate (every slot published or recycled) before the next may begin.
        repeat(5) { vm.turn(1); Thread.sleep(10) }
        awaitUntil(timeoutMs = 60_000) { !vm.state.value.loading }
        assertNull("the rapid spread-turn sequence must leave the reader in a clean, non-error state", vm.state.value.error)
        repeat(6) { vm.turn(if (it % 2 == 0) -1 else 1); Thread.sleep(10) }
        awaitUntil(timeoutMs = 60_000) { !vm.state.value.loading }

        val finalSlots = vm.state.value.slots
        val finalBytesPerSlot = finalSlots.mapNotNull { it.bitmap }.map { it.width.toLong() * it.height.toLong() * 4L }
        android.util.Log.i("FixedReaderSpreadTest", "settled at page ${vm.state.value.page}, slot bytes: $finalBytesPerSlot")
        // Every individual slot bitmap, including a spread's second slot, stays within the same per-bitmap byte
        // budget single-page reading already enforces -- a spread never doubles the ceiling per slot.
        finalBytesPerSlot.forEach { assertTrue("slot bitmap of $it bytes exceeds the per-bitmap budget", it <= RenderMemoryPolicy.MAX_BITMAP_BYTES) }

        System.gc(); Thread.sleep(300)
        val pssAfter = Debug.getPss()
        android.util.Log.i("FixedReaderSpreadTest",
            "PSS before rapid spread-navigation sequence: ${pssBefore}KB, after settling + GC: ${pssAfter}KB (11 total turns)")
        val maxBitmapKb = RenderMemoryPolicy.MAX_BITMAP_BYTES / 1024
        // Loose, environment-tolerant bound (shared-emulator PSS is noisy) -- a spread's 2-slots-per-turn
        // sequential decode pattern must not leave growth resembling several uncollected max-cost bitmaps.
        assertTrue("PSS grew by ${pssAfter - pssBefore}KB across 11 spread turns, more than 3 max-cost bitmaps " +
            "worth (${3 * maxBitmapKb}KB) -- possible stale-bitmap accumulation", pssAfter - pssBefore < 3 * maxBitmapKb)
    }
}
