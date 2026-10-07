// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.RenderMemoryPolicy
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.core.reader.ReaderRenderGeometry
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Drives [FixedReaderViewModel] directly (no Compose UI, no Espresso event injection -- so, like
 * [FixedReaderRenderRequestTest], unaffected by the `api37` AVD `InputManager` incompatibility recorded in
 * `docs/VALIDATION.md`) to cover two of the Codex R1 remediation's required, narrowly-scoped proofs that the pure
 * [com.d4guilar.shelfos.core.reader.resolveRenderTarget] math alone cannot give:
 *
 * 1. **Viewport plumbing end-to-end** -- that a viewport reported the way [com.d4guilar.shelfos.feature.reader
 *    .FixedReaderScreen] actually reports it (`vm.updateViewport(width, height)`, from `Modifier.onSizeChanged`)
 *    really does reach the [com.d4guilar.shelfos.core.reader.PageRenderRequest] used by the *next* real render,
 *    not just that the request-resolution math is correct in isolation.
 * 2. **Peak-memory/lifecycle evidence** (finding 4) for the render/cancellation tightening in
 *    [FixedReaderViewModel.render]: a representative high-resolution render transition sequence (displayed
 *    bitmap -> rapid page-turns/cancellation -> settle) produces no unbounded growth and no stale-bitmap
 *    accumulation proportional to the number of page turns.
 *
 * This is intentionally not a broad regression suite: it does not re-exercise pan/zoom/Fit-Width geometry
 * ([FixedReaderTransformBoundsTest]'s job), recreation ([FixedReaderRecreationTest]'s job), or malformed-file
 * resilience ([MalformedFixedReaderResilienceTest]'s job) -- see `docs/VALIDATION.md` for why that broader matrix
 * stays deferred to Phase 3F.
 */
class FixedReaderViewModelLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val factory get() = FixedReaderFactory(PublicationFiles(context))
    private val scopes = mutableListOf<CoroutineScope>()
    private val stores = mutableListOf<ViewModelStore>()

    @After fun tearDown() {
        stores.forEach { it.clear() } // Invokes each ViewModel's onCleared(), closing its FixedReader session.
        scopes.forEach { it.cancel() }
    }

    /** Minimal, self-contained fake (this instrumented test has no access to `app/src/test`'s `TestLibrary`,
     * a different source set) -- just enough of [LibraryRepository] for [FixedReaderViewModel] to open one fixed
     * item and ignore position/appearance writes, which this test does not exercise. */
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

    private fun cbzFixture(name: String, width: Int, height: Int, pages: Int, fit: FitMode = FitMode.PAGE): LibraryItem {
        val file = File(context.filesDir, "publications/$name").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pages) { index ->
                zip.putNextEntry(ZipEntry("page${index + 1}.png"))
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(if (index % 2 == 0) Color.RED else Color.BLUE) }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle(); zip.closeEntry()
            }
        }
        return LibraryItem(name, "Lifecycle $name", "ShelfOS test", MediaCategory.BOOK, "test:$name",
            PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path,
            preferences = ReaderPreferences(fit = fit).json())
    }

    /** Builds a real [FixedReaderViewModel] through a [ViewModelStore] (like production DI would), so
     * [ViewModelStore.clear] in [tearDown] exercises the same public `onCleared()` path the app uses to close the
     * underlying [com.d4guilar.shelfos.core.reader.FixedReader] session. */
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

    private fun awaitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        fail("Condition not met within ${timeoutMs}ms")
    }

    private fun awaitStableBitmap(vm: FixedReaderViewModel, targetPage: Int? = null, timeoutMs: Long = 15_000): Bitmap {
        awaitUntil(timeoutMs) { val s = vm.state.value; s.bitmap != null && !s.loading && s.error == null && (targetPage == null || s.page == targetPage) }
        return vm.state.value.bitmap!!
    }

    // ---- Viewport plumbing end-to-end ----

    @Test fun viewportReportedByScreenReachesTheActualRenderRequest() {
        // A tall (1:6) page, opened with no viewport reported yet: the first render must use the pre-layout
        // fallback (longest edge 2048, independent of fit). Then updateViewport() + retry() -- the exact call
        // pattern FixedReaderScreen's onSizeChanged + the ViewModel's own render() use -- must change the *next*
        // decode's real dimensions, proving the reported viewport genuinely reaches FixedReader.render's request,
        // not just that resolveRenderTarget's math is correct in isolation.
        val item = cbzFixture("lifecycle-plumbing.cbz", 1200, 7200, pages = 1, fit = FitMode.WIDTH)
        val vm = viewModel(item)
        val before = awaitStableBitmap(vm)
        val beforeWidth = before.width
        val beforeHeight = before.height

        vm.updateViewport(ReaderRenderGeometry.Single(1920, 1080, 960))
        vm.retry()
        awaitUntil { vm.state.value.loading || vm.state.value.bitmap !== before }
        val after = awaitStableBitmap(vm)

        assertTrue("expected a wider decode once a real Fit Width viewport was reported " +
            "(before=${beforeWidth}x$beforeHeight, after=${after.width}x${after.height})", after.width > beforeWidth)
        assertTrue("expected a taller decode too", after.height > beforeHeight)
        assertTrue(after.width.toLong() * after.height.toLong() * 4L <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
    }

    // ---- Codex R1 finding 4: peak-memory/lifecycle evidence ----

    @Test fun rapidPageTurnsDoNotAccumulateStaleBitmapsOrGrowMemoryUnboundedly() {
        // A representative high-resolution source (well above the old flat 2048 cap, so each render is a
        // genuinely large bitmap) with enough pages to page-turn across repeatedly.
        val item = cbzFixture("lifecycle-memory.cbz", 6000, 4000, pages = 6)
        val vm = viewModel(item)
        val first = awaitStableBitmap(vm, targetPage = 0)
        val firstBytes = first.width.toLong() * first.height.toLong() * 4L
        android.util.Log.i("FixedReaderLifecycleTest", "displayed page 0: ${first.width}x${first.height} ($firstBytes bytes)")

        System.gc(); Thread.sleep(200)
        val pssBefore = Debug.getPss()

        // Rapid, un-awaited page turns: each one cancels the in-flight render for the previous page before its
        // decode may even have started (exactly the "cancellation during a page transition" case Codex flagged).
        // This is the sequence finding 3's lifecycle tightening targets: decode -> resolve-fate (publish or
        // recycle) -> only then may the next render begin, all inside one lock -- so no page-turn burst here can
        // leave more than one just-decoded, not-yet-resolved large bitmap alive at a time.
        repeat(5) { vm.turn(1); Thread.sleep(10) }
        // Each queued render is mutex-serialized (by design -- see finding 3) and this is a genuinely large
        // decode (~2-3s each on this environment), so draining 5 queued page-turns can take well past the
        // default poll timeout; this is query time, not evidence of a defect.
        val settled = awaitStableBitmap(vm, timeoutMs = 60_000)
        val settledBytes = settled.width.toLong() * settled.height.toLong() * 4L
        android.util.Log.i("FixedReaderLifecycleTest", "settled at page ${vm.state.value.page}: " +
            "${settled.width}x${settled.height} ($settledBytes bytes)")
        assertNull("the rapid-turn sequence must leave the reader in a clean, non-error state", vm.state.value.error)

        // Further rapid back-and-forth navigation (the "or rapid navigation" half of finding 4's required
        // sequence), then let it fully settle again.
        repeat(6) { vm.turn(if (it % 2 == 0) -1 else 1); Thread.sleep(10) }
        val final = awaitStableBitmap(vm, timeoutMs = 60_000)
        val finalBytes = final.width.toLong() * final.height.toLong() * 4L

        System.gc(); Thread.sleep(300)
        val pssAfter = Debug.getPss()
        android.util.Log.i("FixedReaderLifecycleTest",
            "PSS before rapid-navigation sequence: ${pssBefore}KB, after settling + GC: ${pssAfter}KB " +
                "(11 total page-turns, final page ${vm.state.value.page}, final bitmap ${final.width}x${final.height})")

        // Acceptance (Codex R1 finding 4): every single bitmap observed, including mid-sequence, stays within the
        // explicit per-bitmap byte budget -- not proportional to how many page-turns happened.
        assertTrue(firstBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
        assertTrue(settledBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
        assertTrue(finalBytes <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
        // Process-level PSS after 11 page-turns and a settle+GC must not have grown by anything resembling
        // several uncollected max-cost bitmaps' worth (a real "unbounded growth"/"accumulation" signal) -- a
        // loose, environment-tolerant bound, since exact PSS is inherently noisy on a shared emulator, but a
        // process genuinely accumulating stale large bitmaps across 11 page-turns would blow far past this.
        val maxBitmapKb = RenderMemoryPolicy.MAX_BITMAP_BYTES / 1024
        assertTrue("PSS grew by ${pssAfter - pssBefore}KB across 11 page-turns, more than 2 max-cost bitmaps " +
            "worth (${2 * maxBitmapKb}KB) -- possible stale-bitmap accumulation", pssAfter - pssBefore < 2 * maxBitmapKb)
    }
}
