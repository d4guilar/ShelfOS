// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.library.labelRes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 3B end-to-end Compose UI evidence: opening the thumbnail strip from the real reader chrome, selecting a
 * thumbnail to jump to a logical page, the RTL/Manga page-identity invariant, per-thumbnail accessibility
 * semantics, and corrupt-page resilience -- all through the real app (MainActivity + real library add + real
 * navigation), the same pattern already proven reliable in this environment by
 * [MalformedFixedReaderResilienceTest]'s `createAndroidComposeRule`/`performClick` usage (Compose's own
 * semantics-based click dispatch, not Espresso's raw `UiController` event injection -- see that file's and
 * `docs/VALIDATION.md`'s notes on the `api37` AVD incompatibility being specific to raw `KeyEvent`/motion
 * injection, not Compose test clicks).
 */
class ThumbnailNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as ShelfApplication).container
    private val addedIds = mutableListOf<String>()

    @After fun removeFixtures() = runBlocking<Unit> { addedIds.forEach { container.library.remove(it) } }

    private fun seed(id: String, category: MediaCategory, pageColors: List<Int?>): LibraryItem {
        val file = File(instrumentation.targetContext.filesDir, "publications/$id.cbz").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            pageColors.forEachIndexed { index, color ->
                zip.putNextEntry(ZipEntry("page${(index + 1).toString().padStart(3, '0')}.png"))
                if (color == null) zip.write("not a real image".toByteArray())
                else {
                    val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle()
                }
                zip.closeEntry()
            }
        }
        val item = LibraryItem(id, "Thumbnails $id", "ShelfOS test", category, "test:$id", PublicationFormat.CBZ,
            file.name, byteSize = file.length(), managedPath = file.path)
        runBlocking { container.library.add(item) }
        addedIds += id
        return item
    }

    /** The library defaults to the Books filter tab (`LibraryViewModel`'s saved-state default); a Comic/Manga
     * fixture is invisible until its own category tab is selected -- the same switch
     * `NavigationSmokeTest.keyboardHintsReflectRtlSwapInMangaCbz` already performs for its Manga fixture. */
    private fun open(id: String, category: MediaCategory) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("library_grid").fetchSemanticsNodes().isNotEmpty() }
        val filterLabel = instrumentation.targetContext.getString(category.labelRes())
        compose.onNodeWithText(filterLabel).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication_$id").performClick()
        compose.onNodeWithTag("read_action").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_screen").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page_number").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openThumbnails() {
        compose.onNodeWithTag("reader_thumbnails").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("thumbnail_strip").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun selectingAThumbnailJumpsToThatLogicalPageUsingTheNormalNavigationPath() {
        seed("thumb-ui-ltr", MediaCategory.COMIC, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA))
        open("thumb-ui-ltr", MediaCategory.COMIC)
        openThumbnails()
        compose.onNodeWithTag("thumbnail_2").performScrollTo().performClick()
        // The dialog dismisses and the normal page-turn machinery updates the page counter -- "3 / 5" is the
        // 1-based display of logical page index 2, the same showPage() path a slider drag already uses.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText("3 / 5")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("thumbnail_strip").assertCountEquals(0) // dialog dismissed after selection
    }

    @Test fun mangaRtlThumbnailSelectionStillLandsOnThePlainLogicalPageIndex() {
        seed("thumb-ui-rtl", MediaCategory.MANGA, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA))
        open("thumb-ui-rtl", MediaCategory.MANGA)
        openThumbnails()
        // Selecting the thumbnail keyed "thumbnail_1" (logical page index 1) must land on logical page 1 ("2 / 5")
        // regardless of the strip's RTL-mirrored visual layout direction -- the non-negotiable page-identity
        // invariant (AGENTS.md / docs/PHASE_3_IMPLEMENTATION_PLAN.md section 10).
        compose.onNodeWithTag("thumbnail_1").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText("2 / 5")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun eachThumbnailExposesAPageNumberAndTheCurrentPageIsDistinguishable() {
        seed("thumb-ui-a11y", MediaCategory.COMIC, listOf(Color.RED, Color.GREEN, Color.BLUE))
        open("thumb-ui-a11y", MediaCategory.COMIC)
        openThumbnails()
        val currentPageTemplate = instrumentation.targetContext.getString(R.string.content_desc_thumbnail_current_page)
        val plainPageTemplate = instrumentation.targetContext.getString(R.string.content_desc_thumbnail_page)
        // Page 0 is the current page on open (the reader's restored/initial locator).
        compose.onNodeWithContentDescription(String.format(currentPageTemplate, 1)).assertExists()
        compose.onNodeWithContentDescription(String.format(plainPageTemplate, 2)).assertExists()
    }

    // Codex R1 finding 2: an ordinary Dialog's default Back/Escape handling does not reliably extend to gamepad B
    // once the dialog owns focus/window state -- these prove the dialog-local ShelfCommand.BACK handler added to
    // ThumbnailNavigator's content surface (testTag "thumbnail_dialog_surface") actually dismisses Pages for BOTH
    // keys while leaving the reader itself open and the current page unchanged.

    @Test fun escapeClosesPagesWithoutExitingTheReader() {
        seed("thumb-ui-escape", MediaCategory.COMIC, listOf(Color.RED, Color.GREEN, Color.BLUE))
        open("thumb-ui-escape", MediaCategory.COMIC)
        openThumbnails()
        compose.onNodeWithTag("thumbnail_dialog_surface").performKeyInput { pressKey(Key.Escape) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("thumbnail_strip").fetchSemanticsNodes().isEmpty() }
        // The reader chrome is still present -- Escape dismissed only Pages, not the reader itself.
        compose.onNodeWithTag("reader_screen").assertExists()
        compose.onNodeWithTag("page_number").assertTextEquals("1 / 3")
    }

    @Test fun gamepadButtonBClosesPagesWithoutExitingTheReader() {
        seed("thumb-ui-gamepad-b", MediaCategory.COMIC, listOf(Color.RED, Color.GREEN, Color.BLUE))
        open("thumb-ui-gamepad-b", MediaCategory.COMIC)
        openThumbnails()
        compose.onNodeWithTag("thumbnail_dialog_surface").performKeyInput { pressKey(Key.ButtonB) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("thumbnail_strip").fetchSemanticsNodes().isEmpty() }
        // Gamepad B dismissed Pages only -- it must not have also been treated as "exit the reader" while Pages
        // was open (ADR-0023's chrome-first Back semantics still apply to the reader itself, unaffected here).
        compose.onNodeWithTag("reader_screen").assertExists()
        compose.onNodeWithTag("page_number").assertTextEquals("1 / 3")
    }

    // Codex R2 finding: the prior remediation's onKeyEvent handler lived on a Box inside AlertDialog's `text` slot,
    // a SIBLING of the `confirmButton` slot's Close button -- so once focus moved to Close, the key event no
    // longer reached that handler. ThumbnailNavigator now builds the dialog on BasicAlertDialog with a single
    // Surface wrapping title, thumbnail content AND Close together, with the handler on that shared root. This
    // proves gamepad B dismisses Pages with focus actually ON the Close button, not merely on a thumbnail cell.
    @Test fun gamepadButtonBWithFocusOnCloseButtonClosesPagesWithoutExitingTheReader() {
        seed("thumb-ui-gamepad-b-close", MediaCategory.COMIC, listOf(Color.RED, Color.GREEN, Color.BLUE))
        open("thumb-ui-gamepad-b-close", MediaCategory.COMIC)
        openThumbnails()
        val closeLabel = instrumentation.targetContext.getString(R.string.action_close)
        compose.onNodeWithText(closeLabel).requestFocus()
        compose.onNodeWithText(closeLabel).performKeyInput { pressKey(Key.ButtonB) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("thumbnail_strip").fetchSemanticsNodes().isEmpty() }
        // The reader chrome is still present and the page unchanged -- GAMEPAD_B dismissed only Pages, from the
        // Close button's own focus, without also being treated as "exit the reader".
        compose.onNodeWithTag("reader_screen").assertExists()
        compose.onNodeWithTag("page_number").assertTextEquals("1 / 3")
        // Normal reader-level controller handling resumes once Pages is gone: Next Page (reused as a plain
        // ShelfCommand.NEXT_PAGE reader binding) still advances the page counter afterwards.
        compose.onNodeWithTag("reader_screen").performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText("2 / 3")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aCorruptPageAmongGoodPagesDoesNotBreakTheThumbnailStrip() {
        // The corrupt page (index 2) is deliberately NOT the current page (0), so the main reader page itself
        // renders normally -- this test is specifically about the thumbnail strip's own corrupt-page resilience.
        seed("thumb-ui-corrupt", MediaCategory.COMIC, listOf(Color.RED, Color.GREEN, null, Color.YELLOW, Color.CYAN))
        open("thumb-ui-corrupt", MediaCategory.COMIC)
        openThumbnails()
        val unavailableLabel = instrumentation.targetContext.getString(R.string.content_desc_thumbnail_unavailable)
        compose.onNodeWithTag("thumbnail_2").performScrollTo()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(unavailableLabel).fetchSemanticsNodes().isNotEmpty() }
        // The strip itself, and the thumbnails around the corrupt page, remain alive and usable -- one corrupt
        // page never poisons the whole strip.
        compose.onNodeWithTag("thumbnail_strip").assertExists()
        compose.onNodeWithTag("thumbnail_1").assertExists()
        compose.onNodeWithTag("thumbnail_3").performScrollTo().assertExists()
    }
}
