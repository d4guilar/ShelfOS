// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.d4guilar.shelfos.core.reader.FoldOrientation
import com.d4guilar.shelfos.core.reader.FoldRect
import com.d4guilar.shelfos.core.reader.ReaderFoldDescriptor
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FixedReader
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.PageGeometry
import com.d4guilar.shelfos.core.reader.PageRenderRequest
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.reader.FixedAnnotationViewModel
import com.d4guilar.shelfos.feature.reader.FixedReaderScreen
import com.d4guilar.shelfos.feature.reader.FixedReaderViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4C.1 CBR parity. The project has no RAR-writing tooling (see `CbrProductIntegrationTest`), so there is no
 * synthetic image CBR to open through the real native engine. This hosts the REAL [FixedReaderScreen],
 * [FixedReaderViewModel] and [FixedAnnotationViewModel] over a [PublicationFormat.CBR] library item whose page source is a
 * deterministic in-memory [FixedReader] (the same `open` seam the fold tests use). It proves the annotation layer binds to
 * `FixedReaderState.page` for a CBR item through the shared reader; it does NOT prove the native RAR page decoding, which
 * CBR's own accepted tests cover.
 */
class FixedReaderCbrAnnotationHostedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scopes = mutableListOf<CoroutineScope>()
    private val stores = mutableListOf<ViewModelStore>()
    private var db: ShelfDatabase? = null
    private var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
    private var backOwner: androidx.activity.OnBackPressedDispatcherOwner? = null

    @After fun tearDown() {
        stores.forEach { it.clear() }
        scopes.forEach { it.cancel() }
        db?.close()
    }

    private class MemoryPages(override val pageCount: Int) : FixedReader {
        override fun render(index: Int, request: PageRenderRequest): Bitmap =
            Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(if (index % 2 == 0) Color.RED else Color.BLUE) }
        override fun pageGeometry(index: Int) = if (index in 0 until pageCount) PageGeometry(200, 300) else null
        override fun close() {}
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
        override suspend fun remove(id: String, policy: com.d4guilar.shelfos.domain.annotations.KnowledgePolicy) {}
        override suspend fun available(id: String, available: Boolean) {}
        override suspend fun reading(id: String, locator: String, progress: Int) {}
        override suspend fun preferences(id: String, json: String) {}
    }

    private val item = LibraryItem("cbr-hosted", "CBR hosted", "ShelfOS test", MediaCategory.COMIC, "test:cbr-hosted",
        PublicationFormat.CBR, "cbr-hosted.cbr", 0, managedPath = "unused")

    private fun host(pages: Int = 3, expanded: Boolean = false): Pair<FixedReaderViewModel, AnnotationRepositoryHandle> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = ViewModelStore().also { stores += it }
        val database = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build().also { db = it }
        runBlocking { RoomLibraryRepository(database.library()).add(OriginalFixtures.cbz(context).copy(id = item.id, sourceUri = item.sourceUri, format = PublicationFormat.CBR)) }
        val repo = RoomAnnotationRepository(database.annotations(), DefaultAnnotationLocatorValidator { false })
        val factory = object : FixedReaderFactory(PublicationFiles(context)) { override fun open(item: LibraryItem): FixedReader = MemoryPages(pages) }
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                FixedReaderViewModel::class.java -> FixedReaderViewModel(item.id, FakeLibraryRepository(item), factory, scope) as T
                else -> FixedAnnotationViewModel(item.id, repo) as T
            }
        })
        val reader = provider[FixedReaderViewModel::class.java]
        val annotations = provider[FixedAnnotationViewModel::class.java]
        compose.setContent {
            backOwner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
            if (expanded) CompositionLocalProvider(LocalDensity provides Density(1f)) {
                FixedReaderScreen(reader, folds = listOfNotNull(fold), annotations = annotations) { }
            } else FixedReaderScreen(reader, folds = listOfNotNull(fold), annotations = annotations) { }
        }
        return reader to AnnotationRepositoryHandle(repo)
    }

    private class AnnotationRepositoryHandle(val repo: RoomAnnotationRepository) {
        fun all(): List<Annotation> = runBlocking { repo.observeForPublication("cbr-hosted").first().annotations }
        fun add(draft: AnnotationDraft, validate: Boolean = true) = runBlocking { repo.create(draft, validate) }
    }

    private fun awaitPage(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitTag(tag: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun cbrBookmarkIsCreatedOnTheSourcePageAndToggledOff() {
        val (_, handle) = host()
        awaitPage("1 / 3")
        compose.onNodeWithText("Next").performClick(); awaitPage("2 / 3")
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        compose.waitUntil(10_000) { handle.all().size == 1 }
        val bookmark = handle.all().single()
        assertEquals(AnnotationKind.BOOKMARK, bookmark.kind); assertEquals(LocatorFormat.FIXED_PAGE_1, bookmark.locatorFormat)
        assertEquals("{\"version\":1,\"page\":1}", bookmark.locatorJson)
        compose.onNodeWithTag("reader_bookmark").performScrollTo().performClick()
        compose.waitUntil(10_000) { handle.all().isEmpty() }
    }

    @Test fun cbrStrictJumpMovesOnlyForARealPageAndNeverClamps() {
        val (_, handle) = host()
        handle.add(fixedPageDraft(item.id, AnnotationKind.BOOKMARK, 2, 3, 100))
        handle.add(fixedPageDraft(item.id, AnnotationKind.NOTE, 7, 3, 100, "Beyond the end"))
        awaitPage("1 / 3")
        compose.onNodeWithTag("reader_notebook").performScrollTo().performClick()
        awaitTag("notebook_panel")
        compose.onNode(hasText("Beyond the end", substring = true) and hasAnyAncestor(hasTestTag("notebook_list"))).assertIsNotEnabled()
        awaitPage("1 / 3") // the out-of-range row did not move or clamp the reader
        compose.onNode(hasText("Page 3", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("notebook_list"))).performClick()
        awaitPage("3 / 3")
        assertEquals(2, handle.all().size)
    }

    @Test fun notebookUsesTheHingeSafeOverlayUnderAVerticalFoldAndBackEscapeAndGamepadBDismissIt() {
        host()
        awaitPage("1 / 3")
        val reader = compose.onNodeWithTag("reader_page").fetchSemanticsNode().boundsInWindow
        fold = ReaderFoldDescriptor(FoldRect(reader.left + reader.width / 2 - 10f, reader.top, reader.left + reader.width / 2 + 10f, reader.bottom),
            FoldOrientation.VERTICAL, isSeparating = true, occludesFully = false)
        compose.waitForIdle()
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B)) {
            compose.onNodeWithTag("reader_notebook").performScrollTo(); compose.waitForIdle(); compose.onNodeWithTag("reader_notebook").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_hinge_safe_content", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val content = compose.onNode(hasTestTag("notebook_hinge_safe_content"), useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val hinge = FoldRect(reader.left + reader.width / 2 - 10f, reader.top, reader.left + reader.width / 2 + 10f, reader.bottom)
            assertTrue("the Notebook must sit entirely on one side of the hinge", content.right <= hinge.left + 1f || content.left >= hinge.right - 1f)
            if (code == KeyEvent.KEYCODE_BACK) compose.runOnUiThread { backOwner!!.onBackPressedDispatcher.onBackPressed() } else InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
            try { compose.waitUntil(10_000) { compose.onAllNodesWithTag("notebook_hinge_safe_content", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } } catch (e: Throwable) { fail("key $code did not dismiss") }
            compose.onNodeWithTag("reader_screen").assertExists()
        }
    }

    @Test fun expandedNotebookIsAnEndPanePreservesPageAndFilterAndBackClosesOnlyPane() {
        val (_, handle) = host(expanded = true)
        handle.add(fixedPageDraft(item.id, AnnotationKind.NOTE, 0, 3, 0, "Pane note"))
        awaitPage("1 / 3")
        val fullWidth = compose.onNodeWithTag("reader_page").fetchSemanticsNode().boundsInWindow.width
        compose.onNodeWithTag("reader_notebook").performScrollTo().performClick()
        awaitTag("notebook_pane")
        val pane = compose.onNodeWithTag("notebook_pane").fetchSemanticsNode().boundsInWindow
        val reader = compose.onNodeWithTag("reader_page").fetchSemanticsNode().boundsInWindow
        assertTrue("reader shrinks for the pane", reader.width < fullWidth)
        assertTrue("Notebook is placed at the physical end edge", pane.left >= reader.right - 1f)
        compose.onNodeWithTag("notebook_filter_notes").performClick().assertIsSelected()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_R1); awaitPage("2 / 3")
        compose.runOnUiThread { backOwner!!.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("notebook_pane").fetchSemanticsNodes().isEmpty() }
        awaitPage("2 / 3")
        compose.onNodeWithTag("reader_notebook").performScrollTo().performClick(); awaitTag("notebook_pane")
        compose.onNodeWithTag("notebook_filter_notes").assertIsSelected()
    }
}
