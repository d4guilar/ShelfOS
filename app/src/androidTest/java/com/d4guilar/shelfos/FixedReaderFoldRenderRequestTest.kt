// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.FixedReader
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.FoldPaneWidths
import com.d4guilar.shelfos.core.reader.PageRenderRequest
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.core.reader.ReaderRenderGeometry
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 3D: the required focused "render request" test -- proves that under an active vertical-fold spread with
 * ASYMMETRIC panes, [FixedReaderViewModel.render] requests each physical slot at its OWN pane's pixel width
 * (never the flat `viewportWidth / slotCount` approximation this slice replaces for the fold case), while
 * [RenderMemoryPolicy]'s spread-slot byte-budget ceiling and [PageRenderRequest.spreadSlot] flag both remain
 * exactly as 3C established them. Wraps the real [FixedReader] the same way
 * [FixedReaderSpreadViewModelTest]'s `GatedGeometryFixedReaderFactory` wraps it for a different purpose, so the
 * actual [PageRenderRequest] each logical page is decoded with can be inspected directly, rather than only
 * inferring it from the decoded bitmap's own (possibly budget-reduced) size.
 */
class FixedReaderFoldRenderRequestTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
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

    /** Records the actual [PageRenderRequest] each logical page index was decoded with, delegating every other
     * call straight through to the real session -- no behavior is faked beyond this one recording seam. */
    private class RecordingFixedReaderFactory(files: PublicationFiles, private val recorded: ConcurrentHashMap<Int, PageRenderRequest>,
        private val renderCounts: ConcurrentHashMap<Int, Int>? = null) : FixedReaderFactory(files) {
        override fun open(item: LibraryItem): FixedReader {
            val real = super.open(item)
            return object : FixedReader by real {
                override fun render(index: Int, request: PageRenderRequest): Bitmap {
                    recorded[index] = request
                    renderCounts?.merge(index, 1, Int::plus)
                    return real.render(index, request)
                }
            }
        }
    }

    private fun cbzFixture(name: String, pageSizes: List<Pair<Int, Int>>): LibraryItem {
        val file = File(context.filesDir, "publications/$name").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            pageSizes.forEachIndexed { index, (w, h) ->
                zip.putNextEntry(ZipEntry("page${index + 1}.png"))
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle()
                zip.closeEntry()
            }
        }
        return LibraryItem(name, "Fold render $name", "ShelfOS test", MediaCategory.COMIC, "test:$name",
            PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path,
            preferences = ReaderPreferences(fit = FitMode.PAGE, spreadMode = SpreadMode.SPREAD).json())
    }

    private fun awaitSettled(vm: FixedReaderViewModel, targetPage: Int, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = vm.state.value
            if (!s.loading && s.slots.isNotEmpty() && s.page == targetPage) return
            Thread.sleep(25)
        }
        fail("Reader did not settle on page $targetPage within ${timeoutMs}ms")
    }

    @Test fun verticalFoldSpreadRequestsEachSlotAtItsOwnAsymmetricPaneWidth() {
        val recorded = ConcurrentHashMap<Int, PageRenderRequest>()
        // Large source pages so the requested width actually drives a distinguishable sampled result (a small
        // source page would be capped by its own pixel size regardless of the requested width).
        val big = 3000 to 4000
        val item = cbzFixture("fold-render-request", listOf(big, big, big))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = ViewModelStore().also { stores += it }
        val repository = FakeLibraryRepository(item)
        val factory = RecordingFixedReaderFactory(PublicationFiles(context), recorded)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FixedReaderViewModel(item.id, repository, factory, scope) as T
        })
        val vm = provider[FixedReaderViewModel::class.java]
        awaitSettled(vm, 0)
        // A deliberately asymmetric vertical fold split: the left pane is far narrower than the right. Mirrors
        // the real FixedReaderScreen.onGloballyPositioned sequence: only ONE slot exists before turn(1), so the
        // FIRST layout pass is Single-shaped (the eligibility input, FoldPaneWidths, is still recorded so the
        // upcoming render's spreadActive() decision already sees it).
        val foldPaneWidths = FoldPaneWidths(leftPx = 150, rightPx = 1700, leftDp = 75f, rightDp = 850f)
        vm.updateViewport(ReaderRenderGeometry.Single(2000, 3000, 1000), foldPaneWidths)
        vm.turn(1) // -> logical pair [1, 2]
        awaitSettled(vm, 1)
        // Second layout pass: now that 2 slots are actually visible, the real screen would recompute a
        // Spread-shaped geometry from the SAME two panes -- mirrored here, which triggers the corrective
        // re-render using each pane's own real width/height (see EffectiveRenderKey's own "route-entry
        // correction" doc for why a stale first-pass geometry is always corrected by the next effective-key
        // change, never left stale).
        vm.updateViewport(ReaderRenderGeometry.Spread(150, 3000, 1700, 3000, 75f, 850f), foldPaneWidths)
        awaitSettled(vm, 1)

        val requestForPage1 = recorded[1] // physical LEFT in LTR (narrow pane)
        val requestForPage2 = recorded[2] // physical RIGHT in LTR (wide pane)
        assertNotNull("page 1 must have been decoded with a recorded request", requestForPage1)
        assertNotNull("page 2 must have been decoded with a recorded request", requestForPage2)
        requestForPage1!!; requestForPage2!!

        // Both slots are correctly flagged as an active spread's slots -- the spreadSlot budget routing (3C's
        // own invariant) must never be weakened by fold-aware sizing.
        assertTrue("page 1's request must stay flagged as a spread slot", requestForPage1.spreadSlot)
        assertTrue("page 2's request must stay flagged as a spread slot", requestForPage2.spreadSlot)

        // The core 3D assertion: each slot's REQUEST carries its own pane's width, not an equal
        // viewportWidth/slotCount split of the flat window -- the narrow pane's request must be meaningfully
        // smaller than the wide pane's, and neither should simply equal the flat approximation (2000/2 == 1000).
        assertTrue("page 1 (narrow pane) must be requested at roughly its own pane width, not the flat half-window " +
            "approximation, got ${requestForPage1.viewportWidth}", requestForPage1.viewportWidth in 100..300)
        assertTrue("page 2 (wide pane) must be requested at roughly its own pane width, not the flat half-window " +
            "approximation, got ${requestForPage2.viewportWidth}", requestForPage2.viewportWidth in 1500..1900)
        assertTrue("the wide pane's request must be meaningfully larger than the narrow pane's",
            requestForPage2.viewportWidth > requestForPage1.viewportWidth * 2)

        // The spread-slot memory ceiling remains enforced regardless of the asymmetric width hint.
        val slot1 = vm.state.value.slots.first { it.page == 1 }.bitmap
        val slot2 = vm.state.value.slots.first { it.page == 2 }.bitmap
        assertNotNull(slot1); assertNotNull(slot2)
        assertTrue("page 1's decoded bitmap must stay within the spread-slot byte budget",
            slot1!!.byteCount <= RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES)
        assertTrue("page 2's decoded bitmap must stay within the spread-slot byte budget",
            slot2!!.byteCount <= RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES)
    }

    /**
     * Phase 3D render-storm guard: a continuous stream of [FixedReaderViewModel.updateViewport] calls carrying
     * DIFFERENT fold pane widths that never actually change the [SpreadMode.AUTO] single/spread DECISION (the
     * "effective layout key" this slice reuses from 3C's own AUTO-width coalescing, see `updateViewport`'s doc)
     * must never trigger a new decode -- only the one call that actually flips that decision may. Simulates what
     * a real fold/unfold posture animation looks like (many layout events, most not materially different).
     */
    @Test fun continuousFoldPaneWidthChangesThatNeverFlipSpreadActiveNeverTriggerANewDecode() {
        val recorded = ConcurrentHashMap<Int, PageRenderRequest>()
        val counts = ConcurrentHashMap<Int, Int>()
        val big = 3000 to 4000
        val item = cbzFixture("fold-render-storm", listOf(big, big, big, big, big)).let {
            // AUTO (not explicit SPREAD) is the mode whose single/spread DECISION this test is about.
            LibraryItem(it.id, it.title, it.creator, it.category, it.sourceUri, it.format, it.fileName, it.byteSize,
                managedPath = it.managedPath, preferences = ReaderPreferences(fit = FitMode.PAGE, spreadMode = SpreadMode.AUTO).json())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = ViewModelStore().also { stores += it }
        val repository = FakeLibraryRepository(item)
        val factory = RecordingFixedReaderFactory(PublicationFiles(context), recorded, counts)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FixedReaderViewModel(item.id, repository, factory, scope) as T
        })
        val vm = provider[FixedReaderViewModel::class.java]
        awaitSettled(vm, 0)
        // Establish an AUTO-eligible vertical split (each pane comfortably >= half of AUTO_SPREAD_MIN_WIDTH_DP,
        // combined width comfortably over it) and land on the pair [1, 2] -- mirroring the real two-pass
        // Single-then-Spread sequence (see the sibling test's own comment for why).
        // Pane PX widths are chosen at exact RENDER_KEY_BUCKET_PX (32) centers (25*32=800, 34*32=1088) so the
        // drift below has a full +-16px margin before crossing into a neighboring bucket on either side -- dp
        // values stay fixed at comfortably-AUTO-eligible 400/550 throughout (this test is about the render-key
        // bucket guard, not about re-deriving dp from px at some assumed density).
        val initialFold = FoldPaneWidths(leftPx = 800, rightPx = 1088, leftDp = 400f, rightDp = 550f)
        vm.updateViewport(ReaderRenderGeometry.Single(2000, 3000, 1000), initialFold)
        vm.turn(1)
        awaitSettled(vm, 1)
        vm.updateViewport(ReaderRenderGeometry.Spread(800, 3000, 1088, 3000, 400f, 550f), initialFold)
        awaitSettled(vm, 1)
        val initialCount1 = counts[1] ?: 0
        val initialCount2 = counts[2] ?: 0
        assertTrue("page 1 must have been decoded at least once", initialCount1 >= 1)
        assertTrue("page 2 must have been decoded at least once", initialCount2 >= 1)

        // A stream of layout events, as a real fold animation would produce -- pane PX widths drift by a few px
        // (well within the +-16px bucket margin established above, so renderKeyBucket never flips), while BOTH
        // panes stay comfortably AUTO-eligible throughout (never below verticalFoldSpreadEligibleForAuto's
        // floor), so the single/spread decision itself never changes either.
        val driftingPanes = listOf(806 to 1082, 794 to 1094, 804 to 1084, 796 to 1092, 800 to 1088)
        driftingPanes.forEach { (leftPx, rightPx) ->
            vm.updateViewport(ReaderRenderGeometry.Spread(leftPx, 3000, rightPx, 3000, 400f, 550f),
                FoldPaneWidths(leftPx = leftPx, rightPx = rightPx, leftDp = 400f, rightDp = 550f))
        }
        Thread.sleep(300) // give any (wrongly) triggered render a chance to actually run before asserting it didn't.
        assertEquals("a non-decision-changing pane-width drift must never trigger a new decode for page 1",
            initialCount1, counts[1] ?: 0)
        assertEquals("a non-decision-changing pane-width drift must never trigger a new decode for page 2",
            initialCount2, counts[2] ?: 0)

        // The ONE call that actually flips the decision (both panes now far below the AUTO floor) must trigger
        // exactly one new render -- proving the guard coalesces continuous noise WITHOUT silently suppressing a
        // genuine, required re-render. Mirrors production's own stale-geometry self-correction: the VM sees a
        // still-Spread-shaped geometry (the layout pass that would reclassify it to Single hasn't happened yet)
        // but a freshly-false spreadActive() from the updated FoldPaneWidths -- resolveCurrentGroup still
        // collapses the group to 1 page, proving the flip is driven by the real AUTO decision, not merely by a
        // geometry-shape change.
        vm.updateViewport(ReaderRenderGeometry.Spread(100, 3000, 100, 3000, 50f, 50f),
            FoldPaneWidths(leftPx = 100, rightPx = 100, leftDp = 50f, rightDp = 50f))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && vm.state.value.slots.size != 1) Thread.sleep(25)
        assertEquals("the genuine AUTO flip to SINGLE must actually re-render (never silently suppressed)",
            1, vm.state.value.slots.size)
    }
}
