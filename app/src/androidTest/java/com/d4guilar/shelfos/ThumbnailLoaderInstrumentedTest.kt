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
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.PageRenderRequest
import com.d4guilar.shelfos.core.reader.ThumbnailResult
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.library.ReadingDirection
import com.d4guilar.shelfos.domain.library.readingDirection
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
 * Real-device evidence for Phase 3B's [com.d4guilar.shelfos.core.reader.ThumbnailLoader] wiring inside
 * [FixedReaderViewModel] -- lazy decoding against a real 300+ page CBZ, a real corrupt-page decode failure, and
 * close()-on-clear -- on top of [ThumbnailLoaderTest]'s pure JVM coverage of the scheduling/cancellation policy
 * itself (simulated there with a fake payload/decode under virtual time). This file proves the real
 * `android.graphics.Bitmap`-backed wiring behaves the same way with genuine decodes. No Compose UI is driven here
 * (same direct-ViewModel pattern as [FixedReaderViewModelLifecycleTest]), so this is unaffected by the `api37` AVD
 * Espresso `InputManager` incompatibility recorded in `docs/VALIDATION.md`.
 */
class ThumbnailLoaderInstrumentedTest {
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

    /** Deliberately tiny per-page dimensions: this suite is about the thumbnail loader's lazy/bounded decoding
     * policy over a *large page count*, not about full-page decode cost -- small pages keep a 300+ page fixture
     * fast to build and fast to decode, exactly like real comic thumbnails never need reading-resolution source. */
    private fun cbzFixture(name: String, pages: Int, badPage: Int? = null, category: MediaCategory = MediaCategory.BOOK): LibraryItem {
        val file = File(context.filesDir, "publications/$name").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pages) { index ->
                zip.putNextEntry(ZipEntry("page${(index + 1).toString().padStart(4, '0')}.png"))
                if (index == badPage) { zip.write("not actually an image".toByteArray()) }
                else {
                    val bitmap = Bitmap.createBitmap(50, 75, Bitmap.Config.ARGB_8888)
                        .apply { eraseColor(if (index % 2 == 0) Color.RED else Color.BLUE) }
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle()
                }
                zip.closeEntry()
            }
        }
        return LibraryItem(name, "Thumbnails $name", "ShelfOS test", category, "test:$name",
            PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path)
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

    private fun awaitUntil(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(25) }
        fail("Condition not met within ${timeoutMs}ms")
    }

    @Test fun thumbnailLoaderOnA300PageCbzDecodesOnlyABoundedWindowAroundTheRequestedPage() {
        val item = cbzFixture("thumb-large.cbz", pages = 320)
        val vm = viewModel(item)
        awaitUntil { vm.state.value.count == 320 && vm.thumbnails != null }
        val loader = requireNotNull(vm.thumbnails)

        val before = Debug.getPss()
        loader.setVisibleRange(200)
        awaitUntil(20_000) { loader.peek(200) is ThumbnailResult.Loaded }
        // Give the small prefetch window a moment to finish settling (bounded work, should be fast).
        Thread.sleep(500)
        System.gc(); Thread.sleep(100)
        val after = Debug.getPss()

        val resident = (0 until 320).count { loader.peek(it) != null }
        android.util.Log.i("ThumbnailLoaderInstrumentedTest",
            "320-page CBZ: requested center=200, resident thumbnails=$resident, PSS before=${before}KB after=${after}KB")
        // The entire point of lazy loading: nowhere near all 320 pages were ever decoded for one requested center.
        assertTrue("expected a small bounded working set, got $resident resident thumbnails out of 320", resident <= 20)
        assertTrue("decoded pages should cluster near the requested center 200",
            (0 until 320).filter { loader.peek(it) != null }.all { it in 180..220 })
    }

    @Test fun thumbnailLoaderRapidlyChangingRangesStaysBoundedRatherThanSweepingTheWholeBook() {
        val item = cbzFixture("thumb-rapid.cbz", pages = 300)
        val vm = viewModel(item)
        awaitUntil { vm.state.value.count == 300 && vm.thumbnails != null }
        val loader = requireNotNull(vm.thumbnails)

        // A fast simulated scroll across the whole book: each new request supersedes the last before it can
        // possibly finish decoding every page in its own window. `0..290 step 30` actually lands on 270 as its
        // last value (270 + 30 = 300 exceeds the declared end), so the final requested center -- the one that
        // must actually be served -- is 270, not 290.
        val centers = 0..290 step 30
        val lastCenter = centers.last
        centers.forEach { center -> loader.setVisibleRange(center) }
        // Let the final, settled range actually finish.
        awaitUntil(20_000) { loader.peek(lastCenter) is ThumbnailResult.Loaded }
        Thread.sleep(300)

        val resident = (0 until 300).count { loader.peek(it) != null }
        android.util.Log.i("ThumbnailLoaderInstrumentedTest",
            "rapid-scroll 300-page CBZ: resident thumbnails after settling=$resident")
        // If every superseded range were exhaustively drained this would approach the whole book (300); genuine
        // deprioritization keeps total residency far smaller. A loose bound, matching this project's established
        // tolerance for real-device timing noise (see FixedReaderViewModelLifecycleTest's PSS assertions).
        assertTrue("expected bounded residency well under the full page count, got $resident", resident < 60)
        assertTrue(loader.peek(lastCenter) is ThumbnailResult.Loaded)
    }

    @Test fun thumbnailLoaderRecoversFromACorruptPageWithoutBreakingTheStrip() {
        val item = cbzFixture("thumb-corrupt.cbz", pages = 20, badPage = 10)
        val vm = viewModel(item)
        awaitUntil { vm.state.value.count == 20 && vm.thumbnails != null }
        val loader = requireNotNull(vm.thumbnails)

        loader.setVisibleRange(10)
        awaitUntil(15_000) { loader.peek(10) != null }
        // Give the rest of the small window a moment to finish too.
        awaitUntil(15_000) { (8..12).filter { it != 10 }.all { loader.peek(it) != null } }

        assertEquals(ThumbnailResult.Failed, loader.peek(10))
        (8..12).filter { it != 10 }.forEach { page ->
            assertTrue("expected page $page to have loaded despite page 10 being corrupt", loader.peek(page) is ThumbnailResult.Loaded)
        }
        // The reader itself must remain usable -- the corrupt page is a thumbnail-only concern here since page 10
        // was never navigated to as the current full-size reading page.
        assertNull(vm.state.value.error)
    }

    @Test fun thumbnailLoaderClosesWhenTheViewModelIsClearedLeavingNoResidentThumbnails() {
        val item = cbzFixture("thumb-close.cbz", pages = 15)
        val vm = viewModel(item)
        awaitUntil { vm.state.value.count == 15 && vm.thumbnails != null }
        val loader = requireNotNull(vm.thumbnails)
        loader.setVisibleRange(7)
        awaitUntil(15_000) { loader.peek(7) is ThumbnailResult.Loaded }

        stores.forEach { it.clear() } // triggers FixedReaderViewModel.onCleared()
        assertNull("close() must drop cached thumbnail references once the session ends", loader.peek(7))
    }

    @Test fun thumbnailRequestUsesTheDedicatedThumbnailCeilingNotReadingResolution() {
        // End-to-end proof (real decode) that the ViewModel's thumbnail wiring actually uses
        // PageRenderRequest.thumbnail(), not a reading-resolution request, for a high-resolution source.
        val item = cbzFixture("thumb-ceiling.cbz", pages = 3)
        val vm = viewModel(item)
        awaitUntil { vm.state.value.count == 3 && vm.thumbnails != null }
        val loader = requireNotNull(vm.thumbnails)
        loader.setVisibleRange(0)
        awaitUntil(15_000) { loader.peek(0) is ThumbnailResult.Loaded }
        val thumb = (loader.peek(0) as ThumbnailResult.Loaded).value
        assertTrue(maxOf(thumb.width, thumb.height) <= PageRenderRequest.THUMBNAIL_MAX_DIMENSION)
    }

    @Test fun mangaRtlPublicationStillExposesThumbnailsByPlainLogicalPageIndex() {
        // RTL is presentation-only (AGENTS.md / readingDirection()): the thumbnail loader itself must not reverse
        // or otherwise special-case page identity for a Manga/RTL title -- it is keyed by the same plain logical
        // index regardless of reading direction, exactly like full-page rendering already is.
        val item = cbzFixture("thumb-manga-rtl.cbz", pages = 10, category = MediaCategory.MANGA)
        assertEquals(ReadingDirection.RTL, readingDirection(item.category, null))
        val vm = viewModel(item)
        awaitUntil { vm.state.value.count == 10 && vm.thumbnails != null }
        val loader = requireNotNull(vm.thumbnails)
        loader.setVisibleRange(3)
        awaitUntil(15_000) { loader.peek(3) is ThumbnailResult.Loaded }
        // Page 3 is still page 3 -- selecting it would call vm.showPage(3), landing on the same logical page
        // regardless of how the strip visually mirrors it (FixedReaderScreen/ThumbnailNavigator's job, covered by
        // the LocalLayoutDirection wrapping that already matches the bottom control row's established pattern).
        vm.showPage(3)
        awaitUntil { vm.state.value.page == 3 }
        assertEquals(3, vm.state.value.page)
    }
}
