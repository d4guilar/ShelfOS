// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.FixedReader
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.FoldOrientation
import com.d4guilar.shelfos.core.reader.FoldRect
import com.d4guilar.shelfos.core.reader.PageRenderRequest
import com.d4guilar.shelfos.core.reader.ReaderFoldDescriptor
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.core.reader.SpreadMode
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.reader.FixedReaderScreen
import com.d4guilar.shelfos.feature.reader.FixedReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 3D Codex R2 remediation, finding A: the required PRODUCTION-screen-driven proof that render geometry no
 * longer depends on `state.slots.size`. Drives the REAL [FixedReaderScreen]/[FixedReaderViewModel] through a
 * genuine solo->spread and spread->solo transition, recording the actual [PageRenderRequest] each logical page is
 * decoded with -- never manufacturing the correction by manually calling `updateViewport` a second time with a
 * corrective geometry the way a unit-level test could. If this ever regresses back to deciding geometry from
 * `state.slots.size`, these tests must fail against that code exactly as they fail against `ba391c9`.
 */
class FixedReaderFoldRenderGeometryUiTest {
    @get:Rule val compose = createComposeRule()
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

    /** Records EVERY [PageRenderRequest] each logical page index was decoded with, in order -- deliberately a
     * LIST per page, not a last-write-wins map. A first pure unit-level check (recorded.last()) cannot tell "one
     * correct decode" apart from "a wrong decode immediately followed by an incidental corrective re-render" --
     * exactly the defect this remediation closes (see this file's own class doc: "Only after publishing two
     * slots can another incidental layout callback correct it" was the OLD, unacceptable behavior). Asserting
     * `recorded[page]?.size == 1` is what actually proves no such incidental second decode ever happened. */
    private class RecordingFixedReaderFactory(files: PublicationFiles, private val recorded: ConcurrentHashMap<Int, MutableList<PageRenderRequest>>) :
        FixedReaderFactory(files) {
        override fun open(item: LibraryItem): FixedReader {
            val real = super.open(item)
            return object : FixedReader by real {
                override fun render(index: Int, request: PageRenderRequest): Bitmap {
                    recorded.getOrPut(index) { java.util.Collections.synchronizedList(mutableListOf()) }.add(request)
                    return real.render(index, request)
                }
            }
        }
    }

    private val portrait = 300 to 450

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
        return LibraryItem(name, "Fold geometry $name", "ShelfOS test", MediaCategory.COMIC, "test:$name",
            PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path,
            preferences = ReaderPreferences(fit = FitMode.PAGE, spreadMode = spreadMode).json())
    }

    private fun viewModel(item: LibraryItem, recorded: ConcurrentHashMap<Int, MutableList<PageRenderRequest>>): FixedReaderViewModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = ViewModelStore().also { stores += it }
        val repository = FakeLibraryRepository(item)
        val factory = RecordingFixedReaderFactory(PublicationFiles(context), recorded)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FixedReaderViewModel(item.id, repository, factory, scope) as T
        })
        return provider[FixedReaderViewModel::class.java]
    }

    private fun awaitSettled(vm: FixedReaderViewModel, targetPage: Int? = null, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = vm.state.value
            if (!s.loading && s.slots.isNotEmpty() && (targetPage == null || s.page == targetPage)) return
            Thread.sleep(25)
        }
        fail("Reader did not settle on page $targetPage within ${timeoutMs}ms")
    }

    private fun node(tag: String): SemanticsNode = compose.onNodeWithTag(tag).fetchSemanticsNode()
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** An OFF-CENTER vertical, separating hinge (unlike [FixedReaderFoldableUiTest]'s centered helper): the left
     * pane ends up far narrower than the right, so a stale solo-pane-halved request (the pre-remediation defect)
     * is numerically distinguishable from each pane's own real, asymmetric width -- a centered/symmetric fold
     * would make the two indistinguishable by coincidence. */
    private fun offCenterVerticalFold(readerBounds: androidx.compose.ui.geometry.Rect, hingeFraction: Float = 0.2f, halfWidth: Float = 8f): ReaderFoldDescriptor {
        val hingeX = readerBounds.left + readerBounds.width * hingeFraction
        return ReaderFoldDescriptor(FoldRect(hingeX - halfWidth, readerBounds.top, hingeX + halfWidth, readerBounds.bottom),
            FoldOrientation.VERTICAL, isSeparating = true, occludesFully = false)
    }

    private fun centeredHorizontalFold(readerBounds: androidx.compose.ui.geometry.Rect, halfHeight: Float = 10f) =
        ReaderFoldDescriptor(FoldRect(readerBounds.left, readerBounds.top + readerBounds.height / 2 - halfHeight,
            readerBounds.right, readerBounds.top + readerBounds.height / 2 + halfHeight),
            FoldOrientation.HORIZONTAL, isSeparating = true, occludesFully = false)

    // ---- Solo -> Spread: the FIRST decode of a newly-eligible spread must already use each pane's own target ---

    @Test fun soloToSpreadFirstDecodeAlreadyUsesCorrectPerPaneTargetsWithNoIncidentalSecondLayout() {
        val recorded = ConcurrentHashMap<Int, MutableList<PageRenderRequest>>()
        val big = 3000 to 4000
        val vm = viewModel(cbzFixture("geom-solo-to-spread", listOf(big, big, big)), recorded)
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, folds = listOfNotNull(fold)) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        // Off-center vertical fold while page 0 (solo) is showing -- state.slots.size == 1 at the moment this
        // layout pass runs. Under the pre-remediation defect, the geometry reported HERE would be Single-shaped
        // (sized to the one solo pane), never carrying the real per-pane spread target at all.
        fold = offCenterVerticalFold(readerBounds)
        compose.waitForIdle()
        awaitSettled(vm, 0) // solo page 0 fully decoded inside its own fold-safe pane before the transition below
        recorded.clear() // only the UPCOMING transition's first decode matters to this test

        vm.turn(1) // -> logical pair [1, 2]: navigates directly off the SAME geometry captured above, no new layout pass.
        awaitSettled(vm, 1)
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        // Settling can still race a LATE incidental onGloballyPositioned triggered by the slot-count change
        // itself; give it a moment, then freeze the evidence -- the assertions below require EXACTLY one decode
        // per page, so a late incidental correction would show up as size 2, not silently disappear.
        Thread.sleep(300)

        val requestsForPage1 = recorded[1] // physical LEFT in LTR (narrow pane)
        val requestsForPage2 = recorded[2] // physical RIGHT in LTR (wide pane)
        assertNotNull("page 1 must have been decoded after the solo->spread transition", requestsForPage1)
        assertNotNull("page 2 must have been decoded after the solo->spread transition", requestsForPage2)
        requestsForPage1!!; requestsForPage2!!

        // The critical proof Codex's own defect description calls out: "only after publishing two slots can
        // another INCIDENTAL layout callback correct it." A bare "the final recorded request is correct" check
        // cannot distinguish one correct decode from a wrong decode immediately corrected by exactly that
        // incidental callback -- only the COUNT can. Each page must have been decoded EXACTLY ONCE.
        assertEquals("page 1 must be decoded exactly once for this transition -- more than one means a wrong " +
            "first decode was silently corrected by an incidental second layout callback", 1, requestsForPage1.size)
        assertEquals("page 2 must be decoded exactly once for this transition -- more than one means a wrong " +
            "first decode was silently corrected by an incidental second layout callback", 1, requestsForPage2.size)

        val requestForPage1 = requestsForPage1.single()
        val requestForPage2 = requestsForPage2.single()
        // The defining proof: the two pane widths must be MEANINGFULLY asymmetric (never close to equal) on this
        // ONE-AND-ONLY decode. The pre-remediation defect would request BOTH slots at the same (incorrect)
        // width -- half of the single solo pane captured while only one slot existed.
        assertTrue("the narrow (left) pane's request (${requestForPage1.viewportWidth}px) must be meaningfully " +
            "smaller than the wide (right) pane's (${requestForPage2.viewportWidth}px) -- proving neither used a " +
            "stale, symmetric solo-pane-halved approximation",
            requestForPage2.viewportWidth > requestForPage1.viewportWidth * 2)
        assertTrue("both slots must still be flagged as an active spread's slots",
            requestForPage1.spreadSlot && requestForPage2.spreadSlot)
    }

    // ---- Spread -> Solo: the FIRST decode of the new solo page must already use the solo-pane target -----------

    @Test fun spreadToSoloFirstDecodeAlreadyUsesTheSoloPaneTarget() {
        val recorded = ConcurrentHashMap<Int, MutableList<PageRenderRequest>>()
        val big = 3000 to 4000
        val vm = viewModel(cbzFixture("geom-spread-to-solo", listOf(big, big, big)), recorded)
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, folds = listOfNotNull(fold)) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        fold = offCenterVerticalFold(readerBounds)
        compose.waitForIdle()
        vm.turn(1) // -> logical pair [1, 2]: now 2 slots are published, geometry.spread is active in production use.
        awaitSettled(vm, 1)
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        recorded.clear() // only the UPCOMING spread->solo transition's first decode matters

        vm.turn(-1) // -> page 0 (always solo -- the cover): the exact spread->solo transition this test proves.
        awaitSettled(vm, 0)
        awaitTag("reader_pane_content")
        Thread.sleep(300) // let any late incidental correction show up as a second recorded request, if one exists

        val requestsForPage0 = recorded[0]
        assertNotNull("page 0 must have been decoded after the spread->solo transition", requestsForPage0)
        requestsForPage0!!
        assertEquals("page 0 must be decoded exactly once for this transition -- more than one means a wrong " +
            "first decode was silently corrected by an incidental second layout callback", 1, requestsForPage0.size)
        val requestForPage0 = requestsForPage0.single()
        // The solo pane (selectSoloPane's own "larger area wins" pick, here the wide RIGHT pane since the fold
        // is off-center) must drive this request -- never a spread-shaped half-pane width.
        assertTrue("page 0 must never be decoded as a spread slot once solo", !requestForPage0.spreadSlot)
        assertTrue("the solo page's FIRST decode must use the solo pane's own real width (got " +
            "${requestForPage0.viewportWidth}px), not a narrow spread-slot-sized approximation",
            requestForPage0.viewportWidth > 500)
    }

    // ---- Horizontal fold: request evidence, not just visual bounds ---------------------------------------------

    @Test fun horizontalFoldRequestUsesTheSafePaneDimensionsNeverTheWholeReaderSurface() {
        val recorded = ConcurrentHashMap<Int, MutableList<PageRenderRequest>>()
        val big = 3000 to 4000
        val vm = viewModel(cbzFixture("geom-horizontal", listOf(big, big), spreadMode = SpreadMode.SINGLE), recorded)
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, folds = listOfNotNull(fold)) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        recorded.clear()
        fold = centeredHorizontalFold(readerBounds) // layout change alone must drive a fresh decode via updateViewport's own key check
        compose.waitForIdle()
        awaitTag("reader_pane_content")
        awaitSettled(vm, 0)

        val pane = node("reader_pane_content").boundsInWindow
        val request = recorded[0]?.lastOrNull()
        assertNotNull("page 0's decode request under the horizontal fold must be recorded", request)
        request!!

        // Codex R3 remediation, task 3: the prior assertion here only checked that the request's height was
        // under a loose 75%-of-whole-reader threshold -- true of the correct value, but also true of plenty of
        // WRONG values, so it was too weak to actually prove "the request uses the selected safe pane's own
        // dimensions." Tightened to measure the real, selected safe pane ([pane], already fetched above) and
        // assert the recorded request matches it on BOTH dimensions, not just height. A couple of px of integer
        // rounding tolerance is allowed between the Compose-measured pane bounds and the Int pixel request the
        // production code actually builds; anything beyond that would mean the request is not really sized from
        // this pane.
        val roundingToleranceLx = 2
        assertTrue("the horizontal-fold request's width (${request.viewportWidth}px) must match the selected " +
            "safe pane's own measured width (${pane.width.toInt()}px), within integer rounding tolerance",
            Math.abs(request.viewportWidth - pane.width.toInt()) <= roundingToleranceLx)
        assertTrue("the horizontal-fold request's height (${request.viewportHeight}px) must match the selected " +
            "safe pane's own measured height (${pane.height.toInt()}px), within integer rounding tolerance",
            Math.abs(request.viewportHeight - pane.height.toInt()) <= roundingToleranceLx)
        // The safe pane is at most half the reader's full height (plus the hinge gutter) -- re-asserted directly
        // against the whole reader's own measured bounds, so a request that accidentally matched the WHOLE
        // reader surface (never the selected pane) would still be caught even if some coincidence made the two
        // numeric checks above pass.
        assertTrue("the horizontal-fold request's height (${request.viewportHeight}px) must never equal the " +
            "whole reader surface's own full height (${readerBounds.height.toInt()}px) -- that would mean the " +
            "safe-pane confinement was bypassed entirely",
            Math.abs(request.viewportHeight - readerBounds.height.toInt()) > roundingToleranceLx)
    }
}
