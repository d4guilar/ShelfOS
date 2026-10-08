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
import com.d4guilar.shelfos.core.reader.FoldPresentation
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
 *
 * Codex R2 remediation, finding A: [ReaderRenderGeometry] now reports BOTH the solo target and the two-pane
 * spread target unconditionally from a single [FixedReaderViewModel.updateViewport] call -- it no longer needs a
 * SECOND call carrying a corrective [ReaderRenderGeometry] once a spread's slots happen to already be published
 * (R1's own test here used to simulate exactly that two-pass sequence; see each test's own updated doc below for
 * why a single call is now both sufficient and the actually-correct production behavior).
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

    private fun cbzFixture(name: String, pageSizes: List<Pair<Int, Int>>, spreadMode: SpreadMode = SpreadMode.SPREAD): LibraryItem {
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
            preferences = ReaderPreferences(fit = FitMode.PAGE, spreadMode = spreadMode).json())
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

    /** Shared setup for the two R3 remediation tests below: a REAL [FixedReaderViewModel] wired to a
     * [RecordingFixedReaderFactory] that counts every full-page decode per logical page index, so the tests can
     * assert an actual decode-count DELTA through the real `updateViewport` -> `effectiveRenderKey` ->
     * `render()` -> `FixedReader.render(PageRenderRequest)` path, never a synthetic/local counter and never a
     * direct comparison of two keys in isolation (Codex R3's own deficiency: the prior pure test proved the
     * ACCEPTED-KEY MATH doesn't change across 783<->784, but never proved the real integration actually avoids
     * an extra decode, or that a real material resize changes the real request by exactly the expected amount). */
    private fun viewModel(item: LibraryItem, recorded: ConcurrentHashMap<Int, PageRenderRequest>,
        counts: ConcurrentHashMap<Int, Int>): FixedReaderViewModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = ViewModelStore().also { stores += it }
        val repository = FakeLibraryRepository(item)
        val factory = RecordingFixedReaderFactory(PublicationFiles(context), recorded, counts)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FixedReaderViewModel(item.id, repository, factory, scope) as T
        })
        return provider[FixedReaderViewModel::class.java]
    }

    /**
     * Codex R3 remediation, task 1: real decode boundary-jitter integration evidence. The pure `RenderKeyTest`
     * hysteresis math (783<->784 never changing the ACCEPTED key) is necessary but not sufficient -- it only
     * proves a key-equality comparison, never that the real `FixedReaderViewModel.updateViewport` path (which
     * conditionally calls `render()`, which alone actually decodes) avoids an extra decode. This test drives
     * that REAL path directly: a STABLE single-page group (explicit `SpreadMode.SINGLE`, so AUTO can never flip
     * the group shape mid-test and confound the evidence) is first settled at raw width 784px -- exactly the
     * half-bucket boundary (`RENDER_KEY_BUCKET_PX` == 32, so 784.0 == 24.5*32 is the exact plain-rounding
     * boundary between buckets 24 and 25) -- then driven through 784->783->784->783->784->783, a single-px
     * oscillation straddling that exact old boundary, exactly as a continuous layout recomputation would
     * produce. Once the first call accepts a bucket, `acceptedRenderKeyBucket`'s hysteresis band (+-12px beyond
     * the bucket's own nearest-rounding span) comfortably contains both 783 and 784, so none of these six calls
     * may ever change `effectiveRenderKey` -- and since `updateViewport` only calls `render()` (the only place
     * that decodes) when the key actually changes, the real recorded decode COUNT for page 0 must stay exactly
     * where it was after the initial settle.
     */
    @Test fun boundaryJitterThroughTheRealViewModelPathNeverTriggersAnExtraDecode() {
        val recorded = ConcurrentHashMap<Int, PageRenderRequest>()
        val counts = ConcurrentHashMap<Int, Int>()
        val big = 3000 to 4000
        // SpreadMode.SINGLE keeps this a stable one-page group for the whole test -- never AUTO, so there is no
        // risk of the group shape itself flipping and confounding the jitter evidence with a real shape change.
        val item = cbzFixture("fold-jitter-real", listOf(big, big), spreadMode = SpreadMode.SINGLE)
        val vm = viewModel(item, recorded, counts)
        awaitSettled(vm, 0) // the session's own initial open() decode, before any updateViewport call below.

        // Establish the accepted bucket at the exact half-bucket boundary (784px, the real raw dimension used
        // throughout) through the REAL updateViewport entry point -- this is the call whose decode COUNT the
        // jitter below must never increase beyond.
        vm.updateViewport(ReaderRenderGeometry.flat(784, 3000, 400))
        Thread.sleep(300) // let this baseline-establishing decode (if any) actually finish before counting it.
        val initialCount = counts[0] ?: 0
        assertTrue("page 0 must have been decoded at least once to establish the baseline", initialCount >= 1)

        val jitterSequence = listOf(784, 783, 784, 783, 784, 783)
        jitterSequence.forEach { px -> vm.updateViewport(ReaderRenderGeometry.flat(px, 3000, 400)) }
        Thread.sleep(300) // give any (wrongly) triggered extra decode a chance to actually run before asserting it didn't.

        val finalCount = counts[0] ?: 0
        assertEquals("boundary jitter (784<->783, straddling the exact old half-bucket boundary at px 784.0) " +
            "through the REAL updateViewport->effectiveRenderKey->render() path must never trigger an extra " +
            "decode once the bucket is accepted -- got $initialCount initial decodes and $finalCount after jitter",
            initialCount, finalCount)
    }

    /**
     * Codex R3 remediation, task 2: real material same-shape resize integration evidence. Companion to the
     * jitter test above -- proves the OTHER half of the hysteresis contract through the same real path: a
     * genuinely material resize (well past `acceptedRenderKeyBucket`'s widened hysteresis band) must still
     * correct, through the REAL `updateViewport`/`render()` path, exactly once -- never zero (silently
     * suppressed) and never more than once (thrashing) -- while the group SHAPE stays unchanged throughout
     * (the exact R3 deficiency being closed: a prior resize proof must never be confounded with an AUTO
     * single<->spread flip). Also inspects the final recorded [PageRenderRequest] directly: both `viewportWidth`
     * and `viewportHeight` must equal the NEW raw geometry exactly (production already uses raw, not bucketed,
     * dimensions for the actual request -- `resolveRenderTargets`'s own doc -- this test proves that delivery,
     * not merely the bucket-level re-render decision).
     */
    @Test fun materialResizeThroughTheRealViewModelPathCorrectsExactlyOnceAndDeliversTheNewRawDimensions() {
        val recorded = ConcurrentHashMap<Int, PageRenderRequest>()
        val counts = ConcurrentHashMap<Int, Int>()
        val big = 3000 to 4000
        // Same stable single-page-group discipline as the jitter test: explicit SINGLE, never AUTO, so this
        // resize can never be confused with, or ride along with, a shape flip.
        val item = cbzFixture("fold-resize-real", listOf(big, big), spreadMode = SpreadMode.SINGLE)
        val vm = viewModel(item, recorded, counts)
        awaitSettled(vm, 0)

        // Before: settle at 783px (bucket 24 under plain rounding -- renderKeyBucket(783) == round(24.46) == 24;
        // accepted range once settled: center 24*32=768, +-(16+12)=28 -> [740, 796)).
        vm.updateViewport(ReaderRenderGeometry.flat(783, 3000, 400))
        Thread.sleep(300)
        val countBeforeResize = counts[0] ?: 0
        assertTrue("page 0 must have been decoded at least once before the material resize", countBeforeResize >= 1)

        // After: a genuinely material resize to 900px (renderKeyBucket(900) == round(28.125) == 28) -- far
        // outside the [740, 796) accepted range above, and far outside RENDER_KEY_BUCKET_PX (32) +
        // RENDER_KEY_HYSTERESIS_PX (12) combined margin from the old bucket by any measure. Height also changes
        // (3000 -> 4500) so BOTH dimensions' delivery can be checked below, not just width. Still SpreadMode.SINGLE
        // -- the group shape (one page, no spread) is byte-for-byte unchanged across this resize.
        vm.updateViewport(ReaderRenderGeometry.flat(900, 4500, 450))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && (counts[0] ?: 0) == countBeforeResize) Thread.sleep(25)
        Thread.sleep(300) // settle, then confirm no FURTHER (thrashing) decode follows.

        val countAfterResize = counts[0] ?: 0
        assertEquals("a genuinely material resize (783px -> 900px, well past the accepted-bucket hysteresis " +
            "band) through the REAL updateViewport->effectiveRenderKey->render() path must correct the " +
            "single-page group's decode EXACTLY once -- never silently suppressed, never thrashed",
            countBeforeResize + 1, countAfterResize)

        val finalRequest = recorded[0]
        assertNotNull("page 0's post-resize request must have been recorded", finalRequest)
        finalRequest!!
        // The actual delivery proof: the recorded PageRenderRequest must carry the NEW raw pane dimensions
        // exactly (single-page group, so resolveRenderTargets's even-split-by-slot-count degenerates to the
        // whole geometry.single box unchanged) -- never a bucketed/quantized approximation of them.
        assertEquals("the final request's viewportWidth must equal the new raw geometry width exactly " +
            "(production request sizing always uses raw, never bucketed, dimensions)", 900, finalRequest.viewportWidth)
        assertEquals("the final request's viewportHeight must equal the new raw geometry height exactly",
            4500, finalRequest.viewportHeight)
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
        // A deliberately asymmetric vertical fold split: the left pane is far narrower than the right. Codex R2
        // remediation, finding A: ONE updateViewport call now carries BOTH the solo target (for page 0, still
        // solo) AND the two-pane spread target -- exactly what the real FixedReaderScreen.onGloballyPositioned
        // now reports unconditionally, independent of `state.slots.size` (see ReaderRenderGeometry's own doc).
        // No second, corrective updateViewport call is needed or performed here.
        val foldPaneWidths = FoldPaneWidths(leftPx = 150, rightPx = 1700, leftDp = 75f, rightDp = 850f)
        vm.updateViewport(verticalSpreadGeometry(150, 3000, 1700, 3000, 75f, 850f), foldPaneWidths)
        vm.turn(1) // -> logical pair [1, 2]; the FIRST decode of this pair must already use each pane's own width.
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

    /** Codex R2 remediation, finding A: a VERTICAL_SPLIT [ReaderRenderGeometry] carrying both the solo target
     * (the wider of the two panes, mirroring [selectSoloPane][com.d4guilar.shelfos.core.reader.selectSoloPane]'s
     * own "larger pane wins" rule closely enough for a test where `single` is never actually exercised while
     * `groupSize == 2`) and the two-pane spread target -- the single call shape the real
     * `FixedReaderScreen.onGloballyPositioned` now always reports, replacing the pre-remediation two-call
     * Single-then-Spread sequence these tests used to simulate. */
    private fun verticalSpreadGeometry(leftPx: Int, leftHeightPx: Int, rightPx: Int, rightHeightPx: Int, leftDp: Float, rightDp: Float) =
        ReaderRenderGeometry(presentation = FoldPresentation.VERTICAL_SPLIT,
            single = if (rightPx >= leftPx) ReaderRenderGeometry.SingleTarget(rightPx, rightHeightPx, rightDp.toInt())
                else ReaderRenderGeometry.SingleTarget(leftPx, leftHeightPx, leftDp.toInt()),
            spread = ReaderRenderGeometry.SpreadTarget(leftPx, leftHeightPx, rightPx, rightHeightPx, leftDp, rightDp))

    /**
     * Phase 3D render-storm guard: a continuous stream of [FixedReaderViewModel.updateViewport] calls carrying
     * DIFFERENT fold pane widths that never actually change the [SpreadMode.AUTO] single/spread DECISION (the
     * "effective layout key" this slice reuses from 3C's own AUTO-width coalescing, see `updateViewport`'s doc)
     * must never trigger a new decode -- only the one call that actually flips that decision may. Simulates what
     * a real fold/unfold posture animation looks like (many layout events, most not materially different).
     *
     * Codex R2 remediation: this test's final assertion used to only check `vm.state.value.slots.size == 1`
     * (whether the flip is VISIBLE), which a comment claimed proved "exactly one render" without the assertion
     * itself ever counting a render. It now asserts the actual recorded decode COUNT delta directly, closing
     * that assertion-by-comment gap Codex flagged.
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
        // combined width comfortably over it) and land on the pair [1, 2]. Codex R2 remediation, finding A: ONE
        // updateViewport call (never a corrective second one) carries the full geometry from the start.
        // Pane PX widths are chosen at exact RENDER_KEY_BUCKET_PX (32) centers (25*32=800, 34*32=1088) so the
        // drift below has a full +-16px margin before crossing into a neighboring bucket on either side -- dp
        // values stay fixed at comfortably-AUTO-eligible 400/550 throughout (this test is about the render-key
        // bucket guard, not about re-deriving dp from px at some assumed density).
        val initialFold = FoldPaneWidths(leftPx = 800, rightPx = 1088, leftDp = 400f, rightDp = 550f)
        vm.updateViewport(verticalSpreadGeometry(800, 3000, 1088, 3000, 400f, 550f), initialFold)
        vm.turn(1)
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
            vm.updateViewport(verticalSpreadGeometry(leftPx, 3000, rightPx, 3000, 400f, 550f),
                FoldPaneWidths(leftPx = leftPx, rightPx = rightPx, leftDp = 400f, rightDp = 550f))
        }
        Thread.sleep(300) // give any (wrongly) triggered render a chance to actually run before asserting it didn't.
        assertEquals("a non-decision-changing pane-width drift must never trigger a new decode for page 1",
            initialCount1, counts[1] ?: 0)
        assertEquals("a non-decision-changing pane-width drift must never trigger a new decode for page 2",
            initialCount2, counts[2] ?: 0)

        // The ONE call that actually flips the decision (both panes now far below the AUTO floor) must trigger
        // exactly one new render -- proving the guard coalesces continuous noise WITHOUT silently suppressing a
        // genuine, required re-render. Codex R2 remediation: asserts the ACTUAL recorded decode count delta for
        // the page that flips to solo (page 1 must be decoded exactly once more; page 2, no longer part of any
        // visible group, must never be decoded again), not merely that one slot ends up visible.
        val countsBeforeFlip1 = counts[1] ?: 0
        val countsBeforeFlip2 = counts[2] ?: 0
        vm.updateViewport(verticalSpreadGeometry(100, 3000, 100, 3000, 50f, 50f),
            FoldPaneWidths(leftPx = 100, rightPx = 100, leftDp = 50f, rightDp = 50f))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && vm.state.value.slots.size != 1) Thread.sleep(25)
        assertEquals("the genuine AUTO flip to SINGLE must actually re-render (never silently suppressed)",
            1, vm.state.value.slots.size)
        // The actual decode-count proof Codex flagged as missing: page 1 (now solo) must have been decoded
        // EXACTLY ONCE more than before the flip; page 2 (no longer part of any visible group) must never be
        // decoded again. A bare "one slot visible" check cannot distinguish a real new decode from the old
        // bitmap simply being re-published, or from an extra, wasted re-render beyond the one genuinely needed.
        assertEquals("the flip to SINGLE must decode page 1 exactly once more, never zero and never more than once",
            countsBeforeFlip1 + 1, counts[1] ?: 0)
        assertEquals("page 2 must never be decoded again once it is no longer part of any visible group",
            countsBeforeFlip2, counts[2] ?: 0)
    }
}
