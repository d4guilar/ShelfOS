// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.LocalCursor
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.library.RoomCoverRepository
import com.d4guilar.shelfos.data.library.UserCoverStore
import com.d4guilar.shelfos.data.metadata.*
import com.d4guilar.shelfos.data.preferences.BooleanStore
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.annotations.KnowledgePolicy
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import com.d4guilar.shelfos.feature.library.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Phase 4.5C.2B R4: the real Publication Details at realistic pane constraints. The action row used to be one `Row`, so on a
 * phone the last actions (Remove) got the leftover width and collapsed into a sliver with a letter-by-letter label. Now a wrapping group.
 * Also pins the portrait hero cover and the metadata/description separation. No network is touched.
 */
class PublicationDetailsResponsiveUiTest {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext

    private class Prefs : BooleanStore {
        val map = HashMap<String, Boolean>()
        override fun get(key: String, default: Boolean) = map[key] ?: default
        override fun put(key: String, value: Boolean) { map[key] = value }
    }

    private class Store : MetadataStore {
        override suspend fun resolved(id: String): ResolvedMetadata? = null
        override suspend fun apply(id: String, proposals: List<Proposed>, analysisVersion: Int?) = false
        override fun observe(id: String): Flow<StoredMetadata?> = emptyFlow()
        override suspend fun pendingLocal(version: Int, after: LocalCursor?, limit: Int) = emptyList<LocalCursor>()
    }

    private val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
    private val root = File(context.cacheDir, "details-responsive-${System.nanoTime()}")
    private val userCovers = UserCoverStore(RoomCoverRepository(db.covers(), throttleMillis = 0), CoverFiles(root, { Long.MAX_VALUE }), Mutex(), { FileInputStream(it) })
    private val online = OnlineMetadataAccess(OnlineMetadataSettings(Prefs()), ManualMetadataLookup(ProviderLookup(ProviderRegistry(), ProviderPolicyEngine({ 0L })), Store()))
    @After fun cleanUp() { db.close(); root.deleteRecursively() }

    private fun item(category: MediaCategory = MediaCategory.BOOK, title: String = "Crooked House") =
        LibraryItem("a", title, "Agatha Christie", category, "test:a", PublicationFormat.EPUB, "a.epub", 1000)

    private val coverLabEntry = object : CoverLabEntry {
        @Composable override fun Action(item: LibraryItem) { TextButton({}, Modifier.testTag("cover_lab_action")) { Text(stringResource(R.string.action_cover) + " Lab (dev)") } }
    }
    @Composable private fun stringResource(id: Int) = androidx.compose.ui.res.stringResource(id)

    private class Setup(var width: Dp, var height: Dp, var fontScale: Float = 1f, var locale: Locale? = null, var onlineOn: Boolean = true,
        var coverOn: Boolean = true, var labOn: Boolean = false, var category: MediaCategory = MediaCategory.BOOK, var removed: Int = 0)

    private var setup by mutableStateOf(Setup(360.dp, 800.dp))
    private var tick by mutableStateOf(0)   // bumps to re-read the mutable Setup

    private var shown = false
    private fun show(s: Setup) {
        setup = s
        if (shown) { tick++; rule.waitForIdle(); return }
        shown = true
        rule.setContent {
            val st = setup; tick.let { }
            val base = LocalContext.current
            val ctx = if (st.locale != null) base.createConfigurationContext(Configuration(LocalConfiguration.current).apply { setLocale(st.locale) }) else base
            val config = ctx.resources.configuration
            CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides config, LocalDensity provides Density(LocalDensity.current.density, st.fontScale),
                LocalUserCovers provides (if (st.coverOn) userCovers else null), LocalOnlineMetadata provides (if (st.onlineOn) online else null),
                LocalCoverLab provides (if (st.labOn) coverLabEntry else null)) {
                ShelfTheme(ThemeId.CLASSIC) {
                    Box(Modifier.size(st.width, st.height).testTag("window")) {
                        PublicationDetails(item(st.category), false, {}, Modifier, onRead = {}, onEdit = { _, _, _ -> }, onRemove = { st.removed++ }, knowledgeCounts = AnnotationCounts.None)
                    }
                }
            }
        }
    }

    private fun bounds(tag: String): Rect {
        val n = rule.onNodeWithTag(tag).fetchSemanticsNode()
        return Rect(n.positionInRoot, androidx.compose.ui.geometry.Size(n.size.width.toFloat(), n.size.height.toFloat()))
    }
    private val density get() = rule.density.density
    private val actionTags get() = listOf("edit_action", "cover_action", "cover_lab_action", "find_metadata_online_action", "remove_action")
    private fun present(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun assertActionsSane(label: String, expectedAbsent: Set<String> = emptySet()) {
        val window = bounds("window")
        val shown = actionTags.filter { present(it) }
        for (t in expectedAbsent) assertFalse("$label: $t must be absent", present(t))
        assertTrue("$label: remove is shown", "remove_action" in shown)
        val rects = shown.associateWith { bounds(it) }
        for ((tag, r) in rects) {
            assertTrue("$label: $tag inside the pane width ($r vs $window)", r.left >= window.left - 1f && r.right <= window.right + 1f)
            assertTrue("$label: $tag is at least 48 dp wide, not a sliver (${r.width / density})", r.width >= 48f * density - 1f)
            assertTrue("$label: $tag keeps a real touch height (${r.height / density} dp)", r.height >= 40f * density - 1f)
            assertTrue("$label: $tag is not a tall narrow block (${r.height / density} dp)", r.height <= 2.2f * 48f * density * maxOf(1f, setup.fontScale))
        }
        val list = rects.entries.toList()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i].value; val b = list[j].value
            val overlap = a.intersect(b)
            assertTrue("$label: ${list[i].key} and ${list[j].key} overlap", overlap.width <= 1f || overlap.height <= 1f)
        }
        rule.onNodeWithTag("remove_action").performScrollTo().assertIsDisplayed()
    }

    // A
    @Test fun narrowPortraitKeepsEveryActionReadable() {
        show(Setup(320.dp, 640.dp, labOn = true)); assertActionsSane("320x640")
    }

    // B
    @Test fun standardPortraitWrapsTheActionGroupWithoutCrowding() {
        show(Setup(360.dp, 800.dp, labOn = true)); assertActionsSane("360x800")
        // Five labelled actions cannot share one 360 dp line: the group wraps (more than one distinct top edge).
        val tops = actionTags.filter { present(it) }.map { bounds(it).top }
        assertTrue("wrapped into rows: $tops", tops.max() - tops.min() >= 24f * density)
    }

    // C
    @Test fun largerFontKeepsActionsApartAndInsideThePane() {
        for (scale in listOf(1.3f, 1.5f, 2.0f)) { show(Setup(360.dp, 800.dp, fontScale = scale, labOn = true)); assertActionsSane("font $scale") }
    }

    // D
    @Test fun longerTranslationsDoNotCompressTheButtons() {
        for (l in listOf(Locale("es"), Locale.forLanguageTag("pt-BR"), Locale.ENGLISH)) { show(Setup(320.dp, 700.dp, locale = l, labOn = true)); assertActionsSane("locale $l") }
    }

    // E
    @Test fun everyActionCombinationLaysOutWithoutGaps() {
        show(Setup(360.dp, 800.dp)); assertActionsSane("book, online, cover", setOf("cover_lab_action"))
        show(Setup(360.dp, 800.dp, onlineOn = false)); assertActionsSane("book, online wiring absent", setOf("find_metadata_online_action", "cover_lab_action"))
        show(Setup(360.dp, 800.dp, coverOn = false)); assertActionsSane("no user-cover store", setOf("cover_action", "cover_lab_action"))
        for (c in listOf(MediaCategory.COMIC, MediaCategory.MANGA, MediaCategory.DOCUMENT)) {
            show(Setup(360.dp, 800.dp, category = c, labOn = true)); assertActionsSane("$c", setOf("find_metadata_online_action", "cover_lab_action"))
        }
        // Remaining actions sit together from the start edge: no empty slot where a hidden action would be.
        show(Setup(360.dp, 800.dp, category = MediaCategory.DOCUMENT, coverOn = false))
        assertTrue(bounds("remove_action").left <= bounds("edit_action").right + 8f * density)
    }

    // F
    @Test fun removeOnlyAsksForConfirmationAndCancelDeletesNothing() {
        val s = Setup(360.dp, 800.dp); show(s)
        rule.onNodeWithTag("remove_action").performScrollTo().performClick()
        rule.onNodeWithTag("confirm_remove").assertIsDisplayed()
        assertEquals("nothing is removed by the first tap", 0, s.removed)
        rule.onNodeWithTag("cancel_remove").performClick()
        rule.onNodeWithTag("confirm_remove").assertDoesNotExist()
        assertEquals(0, s.removed)
    }

    // G
    @Test fun reflowingBetweenNarrowAndWideKeepsEveryActionUsable() {
        val s = Setup(320.dp, 640.dp, labOn = true); show(s); assertActionsSane("narrow")
        // Roomy pane (as wide as the host window allows) and the three always-present actions: they share one line.
        s.width = 460.dp; s.height = 500.dp; s.onlineOn = false; s.labOn = false; tick++; rule.waitForIdle(); assertActionsSane("wide")
        val wideTops = actionTags.filter { present(it) }.map { bounds(it).top }
        assertTrue("five short actions share one line when there is room: $wideTops", wideTops.max() - wideTops.min() <= 8f * density)
        s.width = 320.dp; s.height = 640.dp; s.onlineOn = true; s.labOn = true; tick++; rule.waitForIdle(); assertActionsSane("narrow again")
        rule.onNodeWithTag("remove_action").performScrollTo().performClick(); rule.onNodeWithTag("cancel_remove").assertIsDisplayed().performClick()
        assertEquals(0, s.removed)
    }

    // H
    private fun gamepadKey(code: Int) {
        val down = SystemClock.uptimeMillis()
        inst.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        inst.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        rule.waitForIdle()
    }

    @Test fun controllerReachesEveryWrappedActionAndAConfirmsOnlyTheDialog() {
        val s = Setup(360.dp, 800.dp, labOn = true); show(s)
        gamepadKey(KeyEvent.KEYCODE_DPAD_DOWN)   // a controller key switches the window to keyboard mode
        val reached = HashSet<String>()
        fun record() { for (t in actionTags) if (present(t) && runCatching { rule.onNodeWithTag(t).assertIsFocused() }.isSuccess) reached += t }
        // Down from Favorite lands on the wrapped group; then sweep each row start to end with left/right and step down.
        repeat(6) { record(); if (reached.isEmpty()) gamepadKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(4) {
            repeat(7) { gamepadKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            record(); repeat(7) { gamepadKey(KeyEvent.KEYCODE_DPAD_RIGHT); record() }
            gamepadKey(KeyEvent.KEYCODE_DPAD_DOWN); record()
        }
        repeat(2) { gamepadKey(KeyEvent.KEYCODE_DPAD_UP); record(); repeat(7) { gamepadKey(KeyEvent.KEYCODE_DPAD_LEFT); record() } }
        assertEquals("every action received focus by controller", actionTags.filter { present(it) }.toSet(), reached)
        // Land on Remove and confirm with A: it opens the confirmation, it does not delete.
        fun removeFocused() = runCatching { rule.onNodeWithTag("remove_action").assertIsFocused() }.isSuccess
        // Walk rows with left/right and step down until Remove has focus (only D-pad keys, never touch).
        run { repeat(5) { repeat(7) { gamepadKey(KeyEvent.KEYCODE_DPAD_LEFT) }; repeat(7) { if (removeFocused()) return@run; gamepadKey(KeyEvent.KEYCODE_DPAD_RIGHT) }; if (removeFocused()) return@run; gamepadKey(KeyEvent.KEYCODE_DPAD_DOWN) } }
        rule.onNodeWithTag("remove_action").assertIsFocused()
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("confirm_remove").assertIsDisplayed()
        assertEquals(0, s.removed)
        gamepadKey(KeyEvent.KEYCODE_BUTTON_B)
        rule.waitUntil(5_000) { !present("confirm_remove") }
        assertEquals(0, s.removed)
    }

    // hero cover and metadata
    private fun rootSize() = bounds("window")

    /**
     * The fixture asks for 360 x 800 dp, but Compose clamps it to the host window (a landscape RP5 is only ~480 dp tall), so the rule that
     * applies must come from the window the pane really got. These constants restate the production rule on purpose (a tall portrait pane:
     * taller than wide, narrower than 600 dp, at least 600 dp tall) instead of calling the function under test.
     */
    private fun assertCoverFollowsTheRuleForTheActualWindow(label: String) {
        rule.waitUntil(5_000) { present("details_cover") }
        val w = rootSize(); val c = bounds("details_cover")
        val wDp = w.width / density; val hDp = w.height / density
        val hero = hDp > wDp && wDp < 600f && hDp >= 600f
        if (hero) {
            assertTrue("$label ($wDp x $hDp dp): hero larger than the old 40% rule (${c.height / density} dp)", c.height > w.height * 0.4f + 1f)
            assertTrue("$label: at most 46% of the pane and 460 dp", c.height <= minOf(w.height * 0.46f, 460f * density) + 1f)
        } else {
            assertTrue("$label ($wDp x $hDp dp): conservative, at most 40% of the pane (${c.height / density} dp)", c.height <= w.height * 0.4f + 1f)
            assertTrue("$label: never above the old 352 dp ceiling", c.height <= 352f * density + 1f)
        }
        assertTrue("$label: inside the pane", c.left >= w.left - 1f && c.right <= w.right + 1f)
        assertTrue("$label: centered", Math.abs((c.left + c.right) / 2 - (w.left + w.right) / 2) <= 2f)
        assertTrue("$label: real size (not collapsed)", c.height >= 40f * density && c.width >= 40f * density)
        rule.onNodeWithTag("read_action").performScrollTo().assertIsDisplayed()
    }

    @Test fun theCoverFollowsTheSizingRuleOfTheWindowItActuallyGot() {
        show(Setup(360.dp, 800.dp)); assertCoverFollowsTheRuleForTheActualWindow("360x800 requested")
    }

    /** The positive hero assertion needs a genuinely tall portrait host; on a short or landscape host it is reported as SKIPPED, never faked. */
    @Test fun portraitHeroCoverIsLargerThanTheOldRule() {
        show(Setup(360.dp, 800.dp))
        rule.waitUntil(5_000) { present("details_cover") }
        val w = rootSize(); val wDp = w.width / density; val hDp = w.height / density
        org.junit.Assume.assumeTrue("host window is $wDp x $hDp dp; a tall portrait pane (taller than wide, under 600 dp wide, at least 600 dp tall) is needed", hDp > wDp && wDp < 600f && hDp >= 600f)
        val c = bounds("details_cover")
        assertTrue("hero larger than the old 40% rule (${c.height / density} dp of $hDp)", c.height > w.height * 0.4f + 1f)
        assertTrue("at most 46% of the pane / 460 dp", c.height <= minOf(w.height * 0.46f, 460f * density) + 1f)
        assertTrue("inside the pane", c.left >= w.left - 1f && c.right <= w.right + 1f)
    }

    @Test fun landscapeKeepsTheConservativeCoverHeightAndReadInView() {
        show(Setup(840.dp, 360.dp))
        rule.waitUntil(5_000) { present("details_cover") }
        val w = rootSize(); val c = bounds("details_cover")
        assertTrue("height-first, at most 40% (${c.height / density} dp)", c.height <= w.height * 0.4f + 1f)
        rule.onNodeWithTag("read_action").performScrollTo().assertIsDisplayed()   // this fixture has a creator and a progress row, so Read is reached by scrolling
    }

    // metadata / description separation
    private fun showWithMetadata(values: MetadataValues) {
        rule.setContent {
            CompositionLocalProvider(LocalMetadataAccess provides MetadataAccess { kotlinx.coroutines.flow.flowOf(StoredMetadata(values, emptyMap())) }) {
                ShelfTheme(ThemeId.CLASSIC) {
                    Box(Modifier.size(360.dp, 800.dp).testTag("window")) {
                        PublicationDetails(item(), false, {}, Modifier, onRead = {}, onEdit = { _, _, _ -> }, onRemove = {}, knowledgeCounts = AnnotationCounts.None)
                    }
                }
            }
        }
        rule.waitUntil(5_000) { present("metadata_summary") }
    }

    @Test fun descriptionIsSetApartFromTheMetadataLinesButFieldsStayCompact() {
        showWithMetadata(MetadataValues(publisher = "Puffin", publishedDate = "1998", language = "en", description = "A long descriptive paragraph. ".repeat(12)))
        val publisher = bounds("metadata_publisher"); val published = bounds("metadata_published_date"); val language = bounds("metadata_language")
        val description = bounds("metadata_description")
        val fieldGap = (published.top - publisher.bottom) / density
        val proseGap = (description.top - language.bottom) / density
        assertTrue("fields stay compact ($fieldGap dp)", fieldGap in 0f..5f)
        assertTrue("the description is set apart by ~12 dp ($proseGap dp)", proseGap in 10f..16f)
    }

    @Test fun noGapWhenThereAreNoFieldsAbovePlusNoEmptyGapWithoutDescription() {
        showWithMetadata(MetadataValues(description = "Only a description."))
        assertTrue("no leading gap", Math.abs(bounds("metadata_description").top - bounds("metadata_summary").top) <= 1f)
    }

    @Test fun noDescriptionLeavesNoEmptyBlock() {
        showWithMetadata(MetadataValues(publisher = "Puffin"))
        assertFalse(present("metadata_description"))
        val summary = bounds("metadata_summary"); val publisher = bounds("metadata_publisher")
        assertTrue("the summary ends with its last field", summary.bottom - publisher.bottom <= 1f)
    }
}
