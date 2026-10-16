// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.designsystem.CoverArtwork
import com.d4guilar.shelfos.core.designsystem.LocalCoverArtwork
import com.d4guilar.shelfos.core.designsystem.PublicationCover
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.library.*
import com.d4guilar.shelfos.feature.library.LibraryScreen
import com.d4guilar.shelfos.feature.library.LibraryUiState
import com.d4guilar.shelfos.feature.library.PublicationDetails
import com.d4guilar.shelfos.feature.library.PublicationTile
import com.d4guilar.shelfos.feature.settings.LibraryAppearanceSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 4.5A.2 cover presentation on device: Settings → Library appearance (radio rows, Custom steppers, category
 * override/reset, Original disables fit, D-pad focus and confirm), the shared renderer's shapes with real JPEGs (Original's
 * frame follows the visible artwork, bottom-aligned; a shape change keeps focus and never requests extraction), the
 * generated fallback at the effective shape, and the details pane's whole artwork on compact and tall windows.
 */
class CoverPresentationUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val dir = File(instrumentation.targetContext.cacheDir, "cover-presentation-${System.nanoTime()}").apply { mkdirs() }
    @After fun cleanUp() { dir.deleteRecursively() }

    private fun jpeg(name: String, w: Int, h: Int) = File(dir, name).also { file ->
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 120, 200)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
    private fun item(id: String, category: MediaCategory = MediaCategory.BOOK) =
        LibraryItem(id, "Title $id", "Creator", category, "test:$id", PublicationFormat.EPUB, "$id.epub", 1000)
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun Rect.ratio() = width / height
    private fun artworkBounds() = compose.onAllNodesWithTag("cover_artwork", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private val requested = mutableListOf<String>()
    private val presentations = MutableStateFlow(CoverPresentations())
    private fun artwork(map: Map<String, CoverRef>) = CoverArtwork(MutableStateFlow<Map<String, CoverRef>?>(map), { File(dir, it.fileName) },
        { synchronized(requested) { requested += it } }, {}, { flowOf(null) }, presentations = presentations)

    @Test fun settingsShapeFitCustomStepperAndCategoryOverride() {
        var covers by mutableStateOf(CoverPresentations())
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LibraryAppearanceSection(covers, { covers = covers.copy(global = it) }, { c, p ->
                        covers = covers.copy(overrides = if (p == null) covers.overrides - c else covers.overrides + (c to p))
                    })
                }
            }
        }
        fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
        node("cover_global_shape_ratio_2_3").assertIsSelected()
        node("cover_global_fit_crop").assertIsSelected().assertIsEnabled()
        node("cover_global_shape_ratio_1_1").performClick().assertIsSelected()
        node("cover_global_shape_ratio_2_3").assertIsNotSelected()
        node("cover_global_fit_fit").performClick()
        assertEquals(CoverPresentation(CoverShape.RATIO_1_1, CoverFit.FIT), covers.global)

        // Original: fit is irrelevant, disabled and explained.
        node("cover_global_shape_original").performClick()
        node("cover_global_fit_crop").assertIsNotEnabled().assertIsNotSelected()
        node("cover_global_fit_fit").assertIsNotEnabled()
        node("cover_global_fit_note").assertIsDisplayed()

        // Custom starts at 2:3 and steps within 1…20 and 1:2…2:1; out-of-range steps are disabled.
        node("cover_global_shape_custom").performClick()
        assertEquals(CoverPresentation(CoverShape.CUSTOM, CoverFit.FIT, 2, 3), covers.global)
        compose.onNodeWithTag("cover_global_width_value", useUnmergedTree = true).assertContentDescriptionEquals("Width: 2")
        node("cover_global_width_decrease").assertIsNotEnabled() // 1:3 is narrower than 1:2
        node("cover_global_height_increase").performClick()          // 2:4
        node("cover_global_width_decrease").assertIsNotEnabled()
        repeat(10) { if (covers.global.customWidth!! < 8) node("cover_global_width_increase").performClick() }
        assertEquals(CoverPresentation(CoverShape.CUSTOM, CoverFit.FIT, 8, 4), covers.global)
        node("cover_global_width_increase").assertIsNotEnabled()     // 9:4 is wider than 2:1
        node("cover_global_shape_custom").assertTextContains("8:4", substring = true)

        // Per category: default → explicit override (starting from what it shows) → change → Use default.
        node("cover_category_manga").performClick()
        node("cover_category_manga_default").assertIsSelected()
        node("cover_category_manga_override").performClick().assertIsSelected()
        assertEquals(covers.global, covers.overrides[MediaCategory.MANGA])
        node("cover_category_manga_shape_ratio_3_4").performClick()
        assertEquals(CoverShape.RATIO_3_4, covers.effective(MediaCategory.MANGA).shape)
        assertEquals("other categories keep the global", covers.global, covers.effective(MediaCategory.COMIC))
        node("cover_category_manga_default").performClick()
        assertNull(covers.overrides[MediaCategory.MANGA])
        node("cover_category_document").performClick()
        node("cover_category_document_override").performClick()
        assertEquals("Documents' explicit override starts from Original", CoverShape.ORIGINAL, covers.overrides[MediaCategory.DOCUMENT]!!.shape)
        node("cover_category_document_fit_note").assertIsDisplayed()
        node("cover_category_document_default").performClick()
        assertEquals(CoverShape.ORIGINAL, covers.effective(MediaCategory.DOCUMENT).shape)
    }

    @Test fun settingsRowsAreReachableAndConfirmableWithDpad() {
        var covers by mutableStateOf(CoverPresentations())
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LibraryAppearanceSection(covers, { covers = covers.copy(global = it) }, { _, _ -> })
                }
            }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB) // leave touch mode as a real keyboard/controller would
        compose.waitForIdle()
        compose.onNodeWithTag("cover_global_shape_ratio_4_5").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("cover_global_shape_ratio_4_5").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("cover_global_shape_ratio_1_1").assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(CoverShape.RATIO_1_1, covers.global.shape)
        compose.onNodeWithTag("cover_global_shape_ratio_1_1").assertIsFocused().assertIsSelected()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BUTTON_A) // controller confirm
        compose.waitForIdle()
        assertEquals("Custom selected with a controller, no text entry", CoverShape.CUSTOM, covers.global.shape)
        compose.onNodeWithTag("cover_global_width_increase").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(3, covers.global.customWidth)
    }

    @Test fun rendererShapesOriginalBoundsFocusAndGeneratedFallback() {
        jpeg("local-00000000000000a1.jpg", 900, 450); jpeg("local-00000000000000a2.jpg", 600, 900); jpeg("local-00000000000000a3.jpg", 300, 900)
        val map = mapOf("wide" to CoverRef("wide", "local-00000000000000a1.jpg", 900, 450),
            "regular" to CoverRef("regular", "local-00000000000000a2.jpg", 600, 900),
            "tall" to CoverRef("tall", "local-00000000000000a3.jpg", 300, 900))
        presentations.value = CoverPresentations(global = CoverPresentation(CoverShape.ORIGINAL, CoverFit.CROP))
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalCoverArtwork provides artwork(map)) {
                    Row(Modifier.width(640.dp)) {
                        for (id in listOf("wide", "regular", "tall")) PublicationTile(item(id), selected = id == "wide", Modifier.width(150.dp), onFocus = {}, onOpen = {})
                        PublicationCover("generated", "No artwork", Modifier.width(120.dp).testTag("generated_slot"), MediaCategory.BOOK)
                    }
                }
            }
        }
        compose.waitUntil(5_000) { artworkBounds().size == 3 }
        // Original: the frame (where the selection border is drawn) follows the visible, clamped artwork, bottom-aligned in a 2:3 slot.
        val (wide, regular, tall) = artworkBounds().sortedBy { it.left }
        assertEquals(2f, wide.ratio(), 0.12f)
        assertEquals(2f / 3, regular.ratio(), 0.04f)
        assertEquals("1:3 is clamped to 1:2 for layout", 0.5f, tall.ratio(), 0.04f)
        assertEquals("bottom-aligned like objects on a shelf", regular.bottom, wide.bottom, 1f)
        assertEquals(regular.bottom, tall.bottom, 1f)
        assertTrue("the wide frame is shorter than the slot", wide.height < regular.height * 0.6f)
        assertEquals("generated Original uses the 2:3 slot", 2f / 3, bounds("generated_slot").ratio(), 0.02f)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB) // keyboard/controller mode: tiles are focusable
        compose.waitForIdle()
        compose.onNodeWithTag("publication_regular").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("publication_regular").assertIsFocused()
        for (p in listOf(CoverPresentation(CoverShape.RATIO_1_1, CoverFit.CROP), CoverPresentation(CoverShape.RATIO_1_1, CoverFit.FIT),
            CoverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 4, 5))) {
            presentations.value = CoverPresentations(global = p)
            compose.waitForIdle()
            compose.waitUntil(5_000) { artworkBounds().size == 3 }
            artworkBounds().forEach { assertEquals("$p frame", p.aspect!!, it.ratio(), 0.05f) }
            assertEquals("generated cover drawn at the shape", p.aspect!!, bounds("generated_slot").ratio(), 0.02f)
            compose.onNodeWithTag("publication_regular").assertIsFocused()
        }
        assertEquals("a shape change never asks for extraction; only the artwork-less item asked", listOf("generated"), synchronized(requested) { requested.distinct() })
    }

    @Test fun detailsShowTheWholeArtworkAtItsRealRatioAndKeepReadReachable() {
        jpeg("local-00000000000000b1.jpg", 900, 450); jpeg("local-00000000000000b2.jpg", 300, 900)
        val map = mapOf("wide" to CoverRef("wide", "local-00000000000000b1.jpg", 900, 450), "tall" to CoverRef("tall", "local-00000000000000b2.jpg", 300, 900))
        // The grid preference (1:1 crop) must not affect the details cover.
        presentations.value = CoverPresentations(global = CoverPresentation(CoverShape.RATIO_1_1, CoverFit.CROP))
        var shown by mutableStateOf("wide")
        var size by mutableStateOf(360.dp to 420.dp) // short landscape handheld
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalCoverArtwork provides artwork(map)) {
                    Box(Modifier.size(size.first, size.second).testTag("window")) {
                        PublicationDetails(item(shown), false, {}, Modifier.fillMaxSize(), onRead = {}, onEdit = { _, _, _ -> },
                            onRemove = {}, knowledgeCounts = AnnotationCounts.None)
                    }
                }
            }
        }
        for ((id, ratio) in listOf("wide" to 2f, "tall" to 0.5f)) for (window in listOf(360.dp to 420.dp, 300.dp to 900.dp, 840.dp to 360.dp)) {
            shown = id; size = window
            compose.waitForIdle()
            compose.waitUntil(5_000) { artworkBounds().size == 1 }
            val cover = bounds("details_cover"); val root = bounds("window")
            assertEquals("$id in $window: whole artwork at its real (clamped) ratio", ratio, cover.ratio(), 0.03f)
            assertTrue("$id in $window: height-first, ≤ 40% of the window", cover.height <= root.height * 0.4f + 1f)
            assertTrue("$id in $window: inside the pane", cover.left >= root.left - 1f && cover.right <= root.right + 1f)
            val read = bounds("read_action")
            assertTrue("$id in $window: Read is in view without scrolling", read.bottom <= root.bottom + 1f && read.top >= cover.bottom)
        }
    }

    private fun keyboardMode() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB); compose.waitForIdle() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); compose.waitForIdle() }

    /** L-1: a stepper at its limit keeps D-pad focus, ignores further activation, is exposed as disabled, and focus moves on. */
    @Test fun stepperKeepsDpadFocusAtItsLimitAndNeverWritesAnInvalidValue() {
        var covers by mutableStateOf(CoverPresentations(global = CoverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 2, 4)))
        val writes = mutableListOf<CoverPresentation>()
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LibraryAppearanceSection(covers, { writes += it; covers = covers.copy(global = it) }, { _, _ -> })
                }
            }
        }
        keyboardMode()
        val increase = compose.onNodeWithTag("cover_global_width_increase")
        increase.performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(6) { key(KeyEvent.KEYCODE_DPAD_CENTER) }            // 2:4 → 8:4, the 2:1 limit
        assertEquals(8, covers.global.customWidth)
        increase.assertIsFocused().assertIsNotEnabled()             // focus kept on the now-unavailable step
        compose.onNodeWithTag("cover_global_width_value", useUnmergedTree = true).assertContentDescriptionEquals("Width: 8")
        val before = writes.size
        key(KeyEvent.KEYCODE_DPAD_CENTER); key(KeyEvent.KEYCODE_BUTTON_A); increase.performClick()
        assertEquals("activating an unavailable step writes nothing", before, writes.size)
        assertTrue("every write was valid", writes.all { coverPresentation(it.shape, it.fit, it.customWidth, it.customHeight) == it })
        increase.assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithTag("cover_global_width_decrease").assertIsFocused().assertIsEnabled()
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(7, covers.global.customWidth)
    }

    /** L-3: expand/collapse state, polite live regions, spoken stepper value and disabled steps. */
    @Test fun appearanceControlsExposeExpandedStateValuesAndUnavailableSteps() {
        var covers by mutableStateOf(CoverPresentations(global = CoverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 1, 2)))
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LibraryAppearanceSection(covers, { covers = covers.copy(global = it) }, { c, p ->
                        covers = covers.copy(overrides = if (p == null) covers.overrides - c else covers.overrides + (c to p))
                    })
                }
            }
        }
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        compose.onNodeWithTag("cover_global_width_value", useUnmergedTree = true).assertContentDescriptionEquals("Width: 1").assert(polite)
        compose.onNodeWithTag("cover_global_width_decrease").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            .assertContentDescriptionEquals("Decrease Width")
        compose.onNodeWithTag("cover_global_width_increase").performScrollTo().performClick()
        compose.onNodeWithTag("cover_global_width_value", useUnmergedTree = true).assertContentDescriptionEquals("Width: 2")

        val row = compose.onNodeWithTag("cover_category_comic").performScrollTo()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand)).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Collapse))
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)) // never presented as a selected option
        row.performSemanticsAction(SemanticsActions.Expand)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        val summary = compose.onNodeWithTag("cover_category_comic_summary", useUnmergedTree = true).assert(polite)
        summary.assertTextContains("Use default", substring = true)
        compose.onNodeWithTag("cover_category_comic_override").performScrollTo().performClick()
        summary.assertTextContains("Category override", substring = true)
        row.performSemanticsAction(SemanticsActions.Collapse)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onAllNodesWithTag("cover_category_comic_default").assertCountEquals(0)
    }

    /** L-2 (D10): in Original the visible focus ring is on the artwork's bounds, not the empty slot or the tile's edge. */
    @Test fun originalFocusRingFollowsTheVisibleArtwork() {
        jpeg("local-00000000000000c1.jpg", 900, 450)
        presentations.value = CoverPresentations(global = CoverPresentation(CoverShape.ORIGINAL, CoverFit.CROP))
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalCoverArtwork provides artwork(mapOf("wide" to CoverRef("wide", "local-00000000000000c1.jpg", 900, 450)))) {
                    PublicationTile(item("wide"), selected = false, Modifier.width(160.dp), onFocus = {}, onOpen = {})
                }
            }
        }
        compose.waitUntil(5_000) { artworkBounds().size == 1 }
        keyboardMode()
        compose.onNodeWithTag("publication_wide").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("publication_wide").assertIsFocused()
        compose.waitForIdle()
        // captureToImage needs API 26; on the API 24 emulator the screen is captured and read in window coordinates.
        android.os.SystemClock.sleep(300)
        val tile = compose.onNodeWithTag("publication_wide").fetchSemanticsNode().boundsInWindow
        val art = compose.onNodeWithTag("cover_artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val image = instrumentation.uiAutomation.takeScreenshot()
        val px = with(compose.density) { 1.dp.toPx() }
        fun dark(x: Float, y: Float) = image.getPixel(x.toInt(), y.toInt()).let {
            android.graphics.Color.red(it) < 90 && android.graphics.Color.green(it) < 90 && android.graphics.Color.blue(it) < 90 }
        // The 2 dp focus border is drawn on the frame's edge, 3 dp outside the artwork (inside the frame's padding).
        assertTrue("focus ring above the artwork", dark(art.center.x, art.top - 2 * px))
        assertTrue("focus ring left of the artwork", dark(art.left - 2 * px, art.center.y))
        assertFalse("no ring in the empty part of the 2:3 slot", dark(art.center.x, tile.top + 8 * px))
        assertFalse("no ring on the tile's outer edge", dark(tile.left + 1 * px, tile.top + 8 * px))
    }

    /** L-4: the real Library LazyVerticalGrid keeps selection, scroll position and D-pad focus across shape changes. */
    @Test fun libraryGridKeepsSelectionScrollAndFocusAcrossPresentationChanges() {
        jpeg("local-00000000000000d1.jpg", 600, 900); jpeg("local-00000000000000d2.jpg", 900, 600)
        val items = (0 until 40).map { item("item-$it") }
        val map = items.associate { it.id to CoverRef(it.id, if (it.id.last().digitToInt() % 2 == 0) "local-00000000000000d1.jpg" else "local-00000000000000d2.jpg",
            if (it.id.last().digitToInt() % 2 == 0) 600 else 900, if (it.id.last().digitToInt() % 2 == 0) 900 else 600) }
        var state by mutableStateOf(LibraryUiState(filter = LibraryFilter.BOOKS, items = items, all = items, loading = false))
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalCoverArtwork provides artwork(map)) {
                    Box(Modifier.size(400.dp, 700.dp)) {
                        LibraryScreen(state, expanded = false, onFilter = {}, onSelect = { id -> state = state.copy(selected = items.first { it.id == id }) },
                            onOpen = {}, onFavorite = {}, onRead = { _, _ -> }, onEdit = { _, _, _, _ -> }, onRemove = { _, _ -> },
                            knowledgeCounts = AnnotationCounts.None)
                    }
                }
            }
        }
        keyboardMode()
        compose.onNodeWithTag("library_grid").performScrollToKey("item-25")
        compose.onNodeWithTag("publication_item-25").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("publication_item-25").assertIsFocused()
        assertEquals("item-25", state.selected?.id)
        for (p in listOf(CoverPresentation(CoverShape.RATIO_1_1, CoverFit.CROP), CoverPresentation(CoverShape.ORIGINAL, CoverFit.CROP),
            CoverPresentation(CoverShape.CUSTOM, CoverFit.FIT, 3, 5))) {
            presentations.value = CoverPresentations(global = p)
            compose.waitForIdle()
            compose.onNodeWithTag("publication_item-25").assertIsDisplayed().assertIsFocused().assertIsSelected()
            assertEquals("$p keeps the selection", "item-25", state.selected?.id)
            assertTrue("$p keeps the scroll position (the first rows stay scrolled away)",
                compose.onAllNodesWithTag("publication_item-0").fetchSemanticsNodes().none { it.boundsInRoot.bottom > 0f && it.boundsInRoot.top < 700f * compose.density.density })
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithTag("publication_item-26").assertIsFocused()
        assertEquals("D-pad traversal still works after the changes", "item-26", state.selected?.id)
        assertTrue("a presentation change never asks for extraction", synchronized(requested) { requested.isEmpty() })
    }
}
