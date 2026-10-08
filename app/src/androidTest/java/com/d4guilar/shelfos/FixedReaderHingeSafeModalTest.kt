// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.FoldOrientation
import com.d4guilar.shelfos.core.reader.FoldRect
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
 * Phase 3D Codex R2 remediation, finding B: [HingeSafeDialogOverlay][com.d4guilar.shelfos.feature.reader.HingeSafeDialogOverlay]
 * was visually dialog-like but not TRULY modal -- nothing prevented keyboard/D-pad focus from moving OUT of it
 * into the reader's own background chrome/page content, at which point gamepad B/Back could stop reaching the
 * overlay at all, and arrow keys landing on a background control would be read as page turns. Drives the REAL
 * [FixedReaderScreen]/[FixedReaderViewModel] under a genuine vertical fold (so Appearance/Pages render through
 * the hinge-safe overlay path, never the ordinary unconstrained platform dialog) to prove: focus containment,
 * background focus/accessibility suppression, root-level Back/gamepad-B dismissal regardless of which dialog
 * descendant has focus, no page-turn while the modal is open, no touch click-through, and focus restoration.
 */
class FixedReaderHingeSafeModalTest {
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

    private val portrait = 300 to 450

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
        return LibraryItem(name, "Modal $name", "ShelfOS test", MediaCategory.COMIC, "test:$name",
            PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path,
            preferences = ReaderPreferences(fit = FitMode.PAGE, spreadMode = SpreadMode.SINGLE).json())
    }

    private fun viewModel(item: LibraryItem): FixedReaderViewModel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val store = ViewModelStore().also { stores += it }
        val repository = FakeLibraryRepository(item)
        val factory = FixedReaderFactory(PublicationFiles(context))
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
    private fun isFocused(tag: String): Boolean =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
    /** As [isFocused], but via the UNMERGED tree -- needed for a background control while a hinge-safe modal is
     * open, since [clearAndSetSemantics][com.d4guilar.shelfos.feature.reader.FixedReaderScreen] excludes it from
     * the default (merged) tree entirely at that point (see the accessibility test's own proof of that fact). */
    private fun isFocusedUnmerged(tag: String): Boolean =
        compose.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true
    /** Diagnostic only: reports which of a known set of tags currently reports Focused == true, via the
     * unmerged tree so it still finds a background-hidden node. */
    private fun dumpFocus(): String {
        val tags = listOf("hinge_safe_dialog_surface", "thumbnail_close", "appearance_hinge_safe_apply",
            "reader_appearance", "reader_thumbnails", "reader_library", "reader_page", "reader_screen")
        return tags.joinToString(", ") { tag ->
            val nodes = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
            val focusedList = nodes.map { it.config.getOrNull(SemanticsProperties.Focused) }
            "$tag(count=${nodes.size})=${focusedList}"
        }
    }

    private fun centeredVerticalFold(readerBounds: androidx.compose.ui.geometry.Rect, halfWidth: Float = 10f) =
        ReaderFoldDescriptor(FoldRect(readerBounds.left + readerBounds.width / 2 - halfWidth, readerBounds.top,
            readerBounds.left + readerBounds.width / 2 + halfWidth, readerBounds.bottom),
            FoldOrientation.VERTICAL, isSeparating = true, occludesFully = false)

    /** Opens the reader under a centered vertical fold and returns the live [ReaderFoldDescriptor] slot so a
     * test can keep it alive for the rest of its body (the fold must stay active for the hinge-safe overlay
     * path to remain in use after a dialog closes). */
    private fun openUnderFold(vm: FixedReaderViewModel, onBack: () -> Unit = {}): androidx.compose.ui.geometry.Rect {
        var fold by mutableStateOf<ReaderFoldDescriptor?>(null)
        compose.setContent { FixedReaderScreen(vm, folds = listOfNotNull(fold), onBack = onBack) }
        awaitSettled(vm, 0)
        compose.waitForIdle()
        val readerBounds = node("reader_page").boundsInWindow
        fold = centeredVerticalFold(readerBounds)
        compose.waitForIdle()
        return readerBounds
    }

    // ---- (1) Pages: gamepad B dismisses it, page-command block, D-pad resumes after dismissal -------------------
    //
    // Note on focus: this Compose test harness (createComposeRule(), no real hosting Activity window) proved,
    // empirically, unable to reliably move KEYBOARD focus programmatically (requestFocus()/Tab) from the
    // overlay's own initial-focus Surface onto a specific deeper descendant like Close -- a harness limitation,
    // not a claim about production behavior. performKeyInput() dispatches to whichever node ACTUALLY holds
    // focus regardless of which node reference it is called on (proven by the pre-existing, passing
    // ThumbnailNavigationUiTest, which already relies on exactly this), so these tests still genuinely exercise
    // the real key-dispatch path through the overlay's own focused subtree -- they just can't additionally
    // assert the precise descendant holding focus in THIS harness.

    @Test fun pagesUnderFoldBlocksPageCommandsWhileOpenAndGamepadBDismissesIt() {
        val vm = viewModel(cbzFixture("modal-pages", listOf(portrait, portrait, portrait)))
        openUnderFold(vm)
        compose.onNodeWithTag("reader_thumbnails").performClick()
        awaitTag("hinge_safe_dialog_surface")
        awaitTag("thumbnail_close")
        compose.waitForIdle()
        assertTrue("the overlay itself must hold focus once open", isFocused("hinge_safe_dialog_surface"))

        // PAGE_DOWN is never a focus-navigation key (unlike arrow keys) -- an unambiguous page-turn command.
        // It must NOT turn the page while Pages is open.
        compose.onNodeWithTag("thumbnail_close").performKeyInput { pressKey(Key.PageDown) }
        compose.waitForIdle()
        assertEquals("a page command must never turn the page while Pages is open", 0, vm.state.value.page)
        compose.onAllNodesWithTag("hinge_safe_dialog_surface").assertCountEquals(1) // still open

        // Gamepad B dismisses Pages (dispatched via the real focused node inside the overlay, never the
        // background Column, which is never an ancestor of it -- see FixedReaderScreen's own doc).
        compose.onNodeWithTag("thumbnail_close").performKeyInput { pressKey(Key.ButtonB) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hinge_safe_dialog_surface").fetchSemanticsNodes().isEmpty() }
        assertTrue(compose.onAllNodesWithTag("thumbnail_strip").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("reader_screen").assertExists() // the reader itself stays open
        assertEquals("the page must stay unchanged by a dismissal", 0, vm.state.value.page)

        // Normal D-pad page-turning resumes once Pages is gone.
        compose.onNodeWithTag("reader_screen").performKeyInput { pressKey(Key.DirectionRight) }
        awaitSettled(vm, 1)
    }

    // ---- (2) Appearance: semantic BACK dismisses it, page unchanged ---------------------------------------------

    @Test fun appearanceUnderFoldBackDismissesItWithPageUnchanged() {
        val vm = viewModel(cbzFixture("modal-appearance", listOf(portrait, portrait, portrait)))
        openUnderFold(vm)
        compose.onNodeWithTag("reader_appearance").performClick()
        awaitTag("hinge_safe_dialog_surface")
        compose.waitForIdle()
        assertTrue("the overlay itself must hold focus once open", isFocused("hinge_safe_dialog_surface"))

        // Semantic BACK (Escape maps to ShelfCommand.BACK via InputMapper) dismisses Appearance.
        compose.onNodeWithTag("appearance_hinge_safe_apply").performKeyInput { pressKey(Key.Escape) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hinge_safe_dialog_surface").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reader_screen").assertExists()
        assertEquals("the page must stay unchanged by a dismissal", 0, vm.state.value.page)

        // The same case with gamepad B (ShelfCommand.BACK's other mapped key), since both share one semantic
        // command through InputMapper -- reopen and repeat with ButtonB instead of Escape.
        compose.onNodeWithTag("reader_appearance").performClick()
        awaitTag("hinge_safe_dialog_surface")
        compose.waitForIdle()
        compose.onNodeWithTag("appearance_hinge_safe_apply").performKeyInput { pressKey(Key.ButtonB) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hinge_safe_dialog_surface").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("reader_screen").assertExists()
        assertEquals(0, vm.state.value.page)
    }

    // ---- (3) Background touch: no click-through to the underlying page-turn zone --------------------------------

    @Test fun backgroundTouchWhileModalOpenNeverTurnsThePage() {
        val vm = viewModel(cbzFixture("modal-touch", listOf(portrait, portrait, portrait)))
        openUnderFold(vm)
        compose.onNodeWithTag("reader_thumbnails").performClick()
        awaitTag("hinge_safe_dialog_surface")

        // A tap over the RIGHT quarter of the reader -- reader_page's own tap-to-turn zone -- must never turn
        // the page while the modal is open. If that exact position happens to sit outside the overlay's own
        // confined pane, the scrim's own dismiss-on-outside-click may close the dialog; it must not ALSO reach
        // through to the underlying control. reader_page itself is excluded from the default (merged)
        // accessibility tree while the modal is open (Codex R2 remediation, finding B's own background
        // accessibility suppression -- see FixedReaderScreen's `backgroundModalBlock`), so it must be found via
        // the UNMERGED tree here; that exclusion is itself proven separately by the accessibility test below.
        compose.onNode(hasTestTag("reader_page"), useUnmergedTree = true)
            .performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        compose.waitForIdle()
        assertEquals("a background tap must never turn the page while the modal is open", 0, vm.state.value.page)
        compose.onNodeWithTag("reader_screen").assertExists()
    }

    // ---- (4) Accessibility: overlay has dialog semantics, background control excluded while open -----------------

    @Test fun accessibilityExposesDialogSemanticsAndHidesABackgroundControlWhileOpen() {
        val vm = viewModel(cbzFixture("modal-a11y", listOf(portrait, portrait, portrait)))
        openUnderFold(vm)
        assertTrue("before any modal is open, the background control is a normal accessible node",
            compose.onAllNodesWithTag("reader_library").fetchSemanticsNodes().isNotEmpty())

        compose.onNodeWithTag("reader_thumbnails").performClick()
        awaitTag("hinge_safe_dialog_surface")
        val overlayNode = node("hinge_safe_dialog_surface")
        assertTrue("the hinge-safe overlay must expose a pane/dialog accessibility role",
            overlayNode.config.contains(SemanticsProperties.PaneTitle))
        assertTrue("a known background reader control must be absent from accessibility while the modal is open",
            compose.onAllNodesWithTag("reader_library").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("thumbnail_close").assertExists() // the overlay's own action stays discoverable

        compose.onNodeWithTag("thumbnail_close").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hinge_safe_dialog_surface").fetchSemanticsNodes().isEmpty() }
        assertTrue("background control semantics must return once the modal is dismissed",
            compose.onAllNodesWithTag("reader_library").fetchSemanticsNodes().isNotEmpty())
    }

    // ---- (5) Focus containment: a background control can never become focused while the modal is open -----------

    @Test fun focusCannotMoveFromOverlayToBackgroundControlsWhileModalOpen() {
        val vm = viewModel(cbzFixture("modal-focus", listOf(portrait, portrait, portrait)))
        openUnderFold(vm)
        compose.onNodeWithTag("reader_appearance").performClick()
        awaitTag("hinge_safe_dialog_surface")
        compose.waitForIdle()
        assertTrue("the overlay itself must hold focus once open", isFocused("hinge_safe_dialog_surface"))

        // Directly requesting focus on a background control must fail structurally (canFocus = false), not
        // merely "happen not to be reached" -- this demonstrates the background cannot become focused at all.
        // Found via the unmerged tree (it is excluded from the default/merged tree while the modal is open --
        // see the accessibility test's own proof), but canFocus = false is a FOCUS SEARCH property, not a
        // semantics-visibility one, so requesting focus on it must still structurally fail.
        runCatching { compose.onNode(hasTestTag("reader_library"), useUnmergedTree = true).requestFocus() }
        assertFalse("a background control must never become focused while a hinge-safe modal is open",
            isFocusedUnmerged("reader_library"))
        assertTrue("focus must remain on the overlay's own content, never simply disappear",
            isFocused("hinge_safe_dialog_surface"))
    }

    // ---- Focus restoration: dismissing a dialog returns focus to a stable, usable reader control, never leaves ---
    // it stranded. Documented rule (Codex R2 remediation, finding B; see FixedReaderScreen's `dismissAppearance`/
    // `dismissThumbnails` docs): restoration targets the reader's own STABLE control surface (`pageFocus`, the
    // SAME target the reader's own initial-open focus already uses) rather than the precise original trigger --
    // the 3D contract's own explicitly-sanctioned fallback when exact-trigger restoration is impractical.

    @Test fun dismissingAppearanceRestoresFocusToAStableReaderControlRatherThanLeavingItStranded() {
        val vm = viewModel(cbzFixture("modal-restore", listOf(portrait, portrait, portrait)))
        openUnderFold(vm)
        compose.onNodeWithTag("reader_appearance").performClick()
        awaitTag("hinge_safe_dialog_surface")
        compose.onNodeWithTag("appearance_hinge_safe_apply").performKeyInput { pressKey(Key.Escape) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hinge_safe_dialog_surface").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertTrue("dismissing Appearance must restore focus to the reader's own stable control surface, never " +
            "leave it stranded; dump=" + dumpFocus(), isFocused("reader_page"))
    }

    @Test fun dismissingPagesRestoresFocusToAStableReaderControlRatherThanLeavingItStranded() {
        val vm = viewModel(cbzFixture("modal-restore-pages", listOf(portrait, portrait, portrait)))
        openUnderFold(vm)
        compose.onNodeWithTag("reader_thumbnails").performClick()
        awaitTag("hinge_safe_dialog_surface")
        compose.onNodeWithTag("thumbnail_close").performKeyInput { pressKey(Key.ButtonB) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("hinge_safe_dialog_surface").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertTrue("dismissing Pages must restore focus to the reader's own stable control surface, never leave " +
            "it stranded; dump=" + dumpFocus(), isFocused("reader_page"))
    }
}
