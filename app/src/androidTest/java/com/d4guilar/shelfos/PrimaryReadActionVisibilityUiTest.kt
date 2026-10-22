// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.metadata.StoredMetadata
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.IdentifierType
import com.d4guilar.shelfos.domain.metadata.MetadataValues
import com.d4guilar.shelfos.domain.metadata.PublicationIdentifier
import com.d4guilar.shelfos.feature.home.BottomNavigation
import com.d4guilar.shelfos.feature.library.LocalMetadataAccess
import com.d4guilar.shelfos.feature.library.MetadataAccess
import com.d4guilar.shelfos.feature.library.PublicationDetails
import kotlinx.coroutines.flow.flowOf
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * R4-1: the primary Read action sits right after the title block and reading progress, above the (possibly long) bibliographic
 * metadata. Measured against the viewport the pane really gets, not just "exists"; the metadata-rich cases read a stored
 * description through the normal [LocalMetadataAccess] path, in a window shared with the real bottom navigation.
 */
class PrimaryReadActionVisibilityUiTest {
    @get:Rule val rule = createComposeRule()

    private var w by mutableStateOf(393.dp)
    private var h by mutableStateOf(700.dp)
    private var scale by mutableStateOf(1f)
    private var category by mutableStateOf(MediaCategory.BOOK)
    private var title by mutableStateOf("Crooked House")
    private var lastRead by mutableStateOf(0L)
    private var available by mutableStateOf(true)
    private var started = false

    private fun item() = LibraryItem("a", title, "Agatha Christie", category, "test:a", PublicationFormat.EPUB, "a.epub", 1000,
        lastRead = lastRead, available = available)

    private fun show(width: Dp, height: Dp, fontScale: Float = 1f, c: MediaCategory = MediaCategory.BOOK, t: String = "Crooked House", read: Long = 0L, ok: Boolean = true) {
        w = width; h = height; scale = fontScale; category = c; title = t; lastRead = read; available = ok
        if (started) { rule.waitForIdle(); return }
        started = true
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                ShelfTheme(ThemeId.CLASSIC) {
                    Box(Modifier.size(w, h).testTag("window")) {
                        PublicationDetails(item(), false, {}, Modifier, onRead = {}, onEdit = { _, _, _ -> }, onRemove = {}, knowledgeCounts = AnnotationCounts.None)
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private val density get() = rule.density.density
    /** A metadata-rich Book as stored after import or a reviewed lookup: every summary field, a series, an ISBN and real prose. */
    private val richMetadata = StoredMetadata(MetadataValues(
        description = "Charles Hayward returns from the war to find that his fiancÃ©e's grandfather, the wealthy Aristide Leonides, " +
            "has been poisoned in his own home, a sprawling house shared by three generations of a family with every reason to want him gone.\n\n" +
            "As Charles helps his father's friends at Scotland Yard, he is drawn into the household's rivalries: the young widow, the " +
            "disappointed sons, their wives and the watchful children. Each interview narrows the circle, and each answer seems to " +
            "point somewhere the family would rather not look.\n\n" +
            "The author later named this one of her own favourites, a study of a family closed in on itself.",
        publisher = "Collins Crime Club", publishedDate = "1949-05-23", language = "en", series = "Standalone mysteries",
        identifiers = listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9780007527526"))), emptyMap())

    /** Details above the real bottom bar in a [width] x [height] window, as the portrait app lays them out. */
    private fun showWithNavigation(width: Dp, height: Dp, read: Long, ok: Boolean, t: String = "Crooked House") {
        w = width; h = height; lastRead = read; available = ok; title = t
        if (started) { rule.waitForIdle(); return }
        started = true
        rule.setContent {
            CompositionLocalProvider(LocalMetadataAccess provides MetadataAccess { flowOf(richMetadata) }) {
                ShelfTheme(ThemeId.CLASSIC) {
                    Column(Modifier.size(w, h).testTag("window")) {
                        Box(Modifier.weight(1f).testTag("pane")) {
                            PublicationDetails(item(), false, {}, Modifier, onRead = {}, onEdit = { _, _, _ -> }, onRemove = {}, knowledgeCounts = AnnotationCounts.None)
                        }
                        BottomNavigation("library", Modifier) {}
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("metadata_description").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun bottomOf(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }
    private fun topOf(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    @Test fun readIsInTheFirstViewportAtTypicalPortraitWidths() {
        for (c in MediaCategory.entries) {
            show(393.dp, 700.dp, c = c)
            val window = bottomOf("window")
            assertTrue("$c 393: Read fully visible without scrolling (bottom ${bottomOf("read_action") / density} <= ${window / density})", bottomOf("read_action") <= window + 1f)
            rule.onNodeWithTag("read_action").assertIsDisplayed()
        }
    }

    @Test fun readComesBeforeTheOtherActions() {
        show(393.dp, 700.dp)
        assertTrue(bottomOf("details_cover") <= topOf("read_action"))
        assertTrue(bottomOf("read_action") <= topOf("favorite_action"))
        assertTrue(bottomOf("favorite_action") <= topOf("details_actions"))
    }

    @Test fun readLabelFollowsTheReadingState() {
        show(393.dp, 700.dp); rule.onNodeWithTag("read_action").assertTextContains("Read")
        show(393.dp, 700.dp, read = 5L); rule.onNodeWithTag("read_action").assertTextContains("Continue reading")
        show(393.dp, 700.dp, ok = false); rule.onNodeWithTag("read_action").assertTextContains("Try to open")
    }

    @Test fun narrowerAndLargerTextStayReachableByScrolling() {
        for ((width, height, s) in listOf(Triple(320, 520, 1f), Triple(360, 620, 1f), Triple(393, 700, 1.5f), Triple(393, 700, 2f))) {
            show(width.dp, height.dp, s, t = "The Extraordinarily Long and Winding Title of a Remarkably Wordy Publication")
            rule.onNodeWithTag("read_action").performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("remove_action").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun readStaysAboveAStoredDescriptionInA640DpPortraitWindow() {
        // 393 x 640 dp including the bottom bar: Read must fit in what is left above the navigation.
        // Modifier.size is clamped by the parent: a landscape host (RP5 forced landscape, ~456 dp tall) cannot provide a 640 dp
        // portrait window, so the measured window decides eligibility instead of the requested size.
        showWithNavigation(393.dp, 640.dp, 0L, ok = true)
        val actual = rule.onNodeWithTag("window").fetchSemanticsNode().size
        val actualW = actual.width / density
        val actualH = actual.height / density
        assumeTrue("host provides only ${actualW} x ${actualH} dp, needs a 393 x 640 dp portrait window", actualW >= 392.5f && actualH >= 639.5f && actualH > actualW)
        for (read in listOf(0L, 5L)) {
            showWithNavigation(393.dp, 640.dp, read, ok = true)
            val navTop = topOf("bottom_navigation")
            assertTrue("pane ends where the navigation begins", bottomOf("pane") <= navTop + 1f)
            assertTrue("read $read: Read fully visible above the navigation (bottom ${bottomOf("read_action") / density} <= ${navTop / density} dp)",
                bottomOf("read_action") <= navTop + 1f)
            assertTrue("read $read: Read is not above the pane", topOf("read_action") >= topOf("pane") - 1f)
            rule.onNodeWithTag("read_action").assertIsDisplayed()
            // The stored metadata really rendered, and all of it (fields and the multi-paragraph description) comes after Read.
            for (tag in listOf("metadata_summary", "metadata_publisher", "metadata_published_date", "metadata_language", "metadata_series", "metadata_description"))
                assertTrue("read $read: $tag sits below Read", topOf(tag) >= bottomOf("read_action") - 1f)
            rule.onNodeWithTag("metadata_description").assertTextContains("Charles Hayward", substring = true)
            // Metadata and the remaining actions are reached by scrolling, and come to rest clear of the navigation.
            for (tag in listOf("metadata_description", "favorite_action", "remove_action")) {
                rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                assertTrue("read $read: $tag scrolled clear of the navigation", bottomOf(tag) <= topOf("bottom_navigation") + 1f)
            }
            rule.onNodeWithTag("read_action").performScrollTo().assertIsDisplayed()
        }
        // The fixture's ISBN-13 lives in the expandable Details section, not the summary: expand it and check the exact identifier.
        val expand = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.details_expand)
        rule.onNodeWithText(expand).performScrollTo().performClick()
        rule.onNodeWithText("9780007527526", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun anUnavailableFileKeepsTryToOpenReachableAboveTheDescription() {
        // Accepted LOW limitation (plan Â§43.5): with a long title and the unavailable-file warning, Try to open can start just below
        // the first viewport at 393 x 640. It must stay reachable by scrolling, above the metadata and clear of the navigation.
        showWithNavigation(393.dp, 640.dp, 5L, ok = false, t = "The Extraordinarily Long and Winding Title of a Remarkably Wordy Publication")
        rule.onNodeWithTag("read_action").performScrollTo().assertIsDisplayed().assertTextContains("Try to open")
        assertTrue("Try to open scrolled clear of the navigation", bottomOf("read_action") <= topOf("bottom_navigation") + 1f)
        assertTrue("the description stays below Try to open", topOf("metadata_description") >= bottomOf("read_action") - 1f)
    }

    @Test fun landscapeKeepsReadInReach() {
        show(640.dp, 360.dp)
        rule.onNodeWithTag("read_action").performScrollTo().assertIsDisplayed()
    }
}
