// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.foundation.layout.Box
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
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.library.PublicationDetails
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * R4-1: the primary Read action sits right after the title block and reading progress, above the (possibly long) bibliographic
 * metadata. Measured against the viewport the pane really gets, not just "exists".
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

    @Test fun landscapeKeepsReadInReach() {
        show(640.dp, 360.dp)
        rule.onNodeWithTag("read_action").performScrollTo().assertIsDisplayed()
    }
}
