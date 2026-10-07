// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.FoldOrientation
import com.d4guilar.shelfos.core.reader.FoldRect
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 3D: the required `FixedReaderFoldableUiTest` instrumented class. Drives the real
 * [FixedReaderScreen]/[FixedReaderViewModel] production code directly (via [createComposeRule], not the full
 * [MainActivity]) against an INJECTED [ReaderFoldDescriptor] -- `fold` is a plain parameter of
 * [FixedReaderScreen], so this test seam needs no debug-only backdoor and no real hardware/emulator posture
 * event. Fold bounds are computed from the reader surface's OWN measured window bounds (captured once via
 * `SemanticsNode.boundsInWindow` with no fold active), rather than an assumed device/window size, so this test
 * is accurate regardless of the actual test-host window dimensions.
 */
class FixedReaderFoldableUiTest {
    @get:Rule val compose = createComposeRule()
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

    private val portrait = 300 to 450

    private fun cbzFixture(name: String, pageSizes: List<Pair<Int, Int>>, corruptPages: Set<Int> = emptySet(),
        category: MediaCategory = MediaCategory.COMIC, spreadMode: SpreadMode = SpreadMode.SPREAD): LibraryItem {
        val file = File(context.filesDir, "publications/$name").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            pageSizes.forEachIndexed { index, (w, h) ->
                zip.putNextEntry(ZipEntry("page${index + 1}.png"))
                if ((index + 1) in corruptPages) zip.write(byteArrayOf(1, 2, 3, 4))
                else {
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle()
                }
                zip.closeEntry()
            }
        }
        // Explicit SPREAD (never AUTO) for these fixtures: a small test-host emulator window, once genuinely
        // split by a vertical fold, often yields panes narrower than AUTO's fold-aware eligibility floor
        // (verticalFoldSpreadEligibleForAuto) -- correctly resolving to SINGLE, which is the AUTO policy working
        // as intended, not a bug, but not what these specific pane-PLACEMENT tests want to exercise. Explicit
        // SPREAD's more permissive "two real usable panes" check (verticalFoldHasTwoUsablePanes) still requires
        // real positive-width panes, so it remains a meaningful assertion, not a bypass of fold-awareness itself.
        return LibraryItem(name, "Fold $name", "ShelfOS test", category, "test:$name",
            PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path,
            preferences = ReaderPreferences(fit = FitMode.PAGE, spreadMode = spreadMode).json())
    }

    private fun viewModel(item: LibraryItem): FixedReaderViewModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = ViewModelStore().also { stores += it }
        val repository = FakeLibraryRepository(item)
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

    /** Centers a vertical, separating hinge on the current reader surface's measured window bounds (captured
     * with no fold active), [halfWidth] px wide on either side. */
    private fun centeredVerticalFold(readerBounds: androidx.compose.ui.geometry.Rect, halfWidth: Float = 10f) =
        ReaderFoldDescriptor(FoldRect(readerBounds.left + readerBounds.width / 2 - halfWidth, readerBounds.top,
            readerBounds.left + readerBounds.width / 2 + halfWidth, readerBounds.bottom),
            FoldOrientation.VERTICAL, isSeparating = true, occludesFully = false)

    // ---- (A) vertical LTR spread: logical pair [1,2], page 1 entirely left of hinge, page 2 entirely right ----

    @Test fun verticalLtrSpreadPlacesPagesInTheirOwnPanesWithNoHingeIntersection() {
        val vm = viewModel(cbzFixture("fold-ltr", listOf(portrait, portrait, portrait)))
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, fold = fold) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        fold = centeredVerticalFold(readerBounds)
        compose.waitForIdle()
        vm.turn(1) // -> logical pair [1, 2]
        awaitSettled(vm, 1)
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        val hinge = FoldRect(readerBounds.left + readerBounds.width / 2 - 10f, readerBounds.top,
            readerBounds.left + readerBounds.width / 2 + 10f, readerBounds.bottom)
        val slot1 = node("spread_slot_1").boundsInWindow
        val slot2 = node("spread_slot_2").boundsInWindow
        assertTrue("LTR: logical page 1 must be physically LEFT of the hinge", slot1.right <= hinge.left + 1f)
        assertTrue("LTR: logical page 2 must be physically RIGHT of the hinge", slot2.left >= hinge.right - 1f)
        assertFalse("page 1 must never intersect the hinge", slot1.right > hinge.left && slot1.left < hinge.right)
        assertFalse("page 2 must never intersect the hinge", slot2.right > hinge.left && slot2.left < hinge.right)
    }

    // ---- (B) vertical RTL Manga: same pair, physical placement mirrors, logical tags stay 1 and 2 ----

    @Test fun verticalRtlMangaMirrorsPhysicalPlacementWithoutReversingLogicalIdentity() {
        val vm = viewModel(cbzFixture("fold-rtl", listOf(portrait, portrait, portrait), category = MediaCategory.MANGA))
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, fold = fold) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        fold = centeredVerticalFold(readerBounds)
        compose.waitForIdle()
        vm.turn(1)
        awaitSettled(vm, 1)
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        val hinge = FoldRect(readerBounds.left + readerBounds.width / 2 - 10f, readerBounds.top,
            readerBounds.left + readerBounds.width / 2 + 10f, readerBounds.bottom)
        val slot1 = node("spread_slot_1").boundsInWindow // logical page 1 (lower index)
        val slot2 = node("spread_slot_2").boundsInWindow // logical page 2 (higher index)
        // RTL: higher logical index physically LEFT, lower logical index physically RIGHT -- mirrored from LTR.
        assertTrue("RTL: logical page 2 must be physically LEFT of the hinge", slot2.right <= hinge.left + 1f)
        assertTrue("RTL: logical page 1 must be physically RIGHT of the hinge", slot1.left >= hinge.right - 1f)
        // Logical identity (the tags themselves, state.page) is never reversed by RTL physical mirroring.
        assertEquals(1, vm.state.value.page)
    }

    // ---- (C) single/cover: wholly inside ONE safe pane, never intersecting the hinge ----

    @Test fun soloCoverPageRendersWhollyInsideOneSafePane() {
        val vm = viewModel(cbzFixture("fold-cover", listOf(portrait, portrait, portrait)))
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, fold = fold) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        fold = centeredVerticalFold(readerBounds)
        compose.waitForIdle()
        awaitTag("reader_pane_content")
        val hinge = FoldRect(readerBounds.left + readerBounds.width / 2 - 10f, readerBounds.top,
            readerBounds.left + readerBounds.width / 2 + 10f, readerBounds.bottom)
        val pane = node("reader_pane_content").boundsInWindow
        assertTrue("the solo cover page's pane must sit entirely on one side of the hinge",
            pane.right <= hinge.left + 1f || pane.left >= hinge.right - 1f)
        assertEquals(0, vm.state.value.page)
    }

    // ---- (D) corrupt member: placeholder stays in its own pane, healthy sibling stays in its own pane,
    // neither intersects the hinge ----

    @Test fun corruptSpreadMemberPlaceholderStaysInItsOwnHingeSafePane() {
        val vm = viewModel(cbzFixture("fold-corrupt", listOf(portrait, portrait, portrait), corruptPages = setOf(2)))
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, fold = fold) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        fold = centeredVerticalFold(readerBounds)
        compose.waitForIdle()
        vm.turn(1)
        awaitSettled(vm, 1)
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        val hinge = FoldRect(readerBounds.left + readerBounds.width / 2 - 10f, readerBounds.top,
            readerBounds.left + readerBounds.width / 2 + 10f, readerBounds.bottom)
        val corrupt = node("spread_slot_1").boundsInWindow // page index 1 (0-based) is the corrupt page
        val healthy = node("spread_slot_2").boundsInWindow
        assertTrue("corrupt placeholder must stay in its own left pane", corrupt.right <= hinge.left + 1f)
        assertTrue("healthy sibling must stay in its own right pane", healthy.left >= hinge.right - 1f)
    }

    // ---- (E) zoom/pan: artwork stays clipped away from the hinge under a real transform gesture ----

    @Test fun zoomAndPanGestureNeverMovesArtworkUnderTheHinge() {
        val vm = viewModel(cbzFixture("fold-zoom", listOf(portrait, portrait, portrait)))
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, fold = fold) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        fold = centeredVerticalFold(readerBounds)
        compose.waitForIdle()
        vm.turn(1)
        awaitSettled(vm, 1)
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2")
        val hinge = FoldRect(readerBounds.left + readerBounds.width / 2 - 10f, readerBounds.top,
            readerBounds.left + readerBounds.width / 2 + 10f, readerBounds.bottom)
        // Double-tap zooms (scale -> 2f) through the SAME reader_page pointerInput region the pinch/pan
        // transform loop lives in -- once scale > 1f, a single-pointer drag also routes through that real
        // awaitEachGesture/calculateZoom/calculatePan branch (isTransformGesture := pointerCount > 1 || scale > 1f).
        compose.onNodeWithTag("reader_page").performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("reader_page").performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX + 100_000f, centerY), durationMillis = 120)
        }
        compose.waitForIdle()
        val slot1 = node("spread_slot_1").boundsInWindow
        val slot2 = node("spread_slot_2").boundsInWindow
        assertFalse("page 1 must never be dragged under the hinge, even zoomed/panned",
            slot1.right > hinge.left && slot1.left < hinge.right)
        assertFalse("page 2 must never be dragged under the hinge, even zoomed/panned",
            slot2.right > hinge.left && slot2.left < hinge.right)
    }

    // ---- (F) fold -> flat -> fold: same logical state.page throughout, no duplication/skipping ----

    @Test fun foldFlatFoldPreservesTheSameLogicalPageThroughout() {
        val vm = viewModel(cbzFixture("fold-continuity", listOf(portrait, portrait, portrait)))
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, fold = fold) { } }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        fold = centeredVerticalFold(readerBounds) // flat -> fold
        compose.waitForIdle()
        vm.turn(1)
        awaitSettled(vm, 1)
        val locatorFolded = vm.state.value.page
        assertEquals(1, locatorFolded)
        fold = null // fold -> flat
        compose.waitForIdle()
        assertEquals("unfolding must never change the authoritative logical page", 1, vm.state.value.page)
        fold = centeredVerticalFold(readerBounds) // flat -> fold again
        compose.waitForIdle()
        assertEquals("re-folding must never change the authoritative logical page", 1, vm.state.value.page)
        awaitTag("spread_slot_1"); awaitTag("spread_slot_2") // presentation correctly cycles back to a fold-aware spread
    }
}
