// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.library.RoomCoverRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.data.library.UserCoverOutcome
import com.d4guilar.shelfos.data.library.UserCoverStore
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.library.LocalUserCovers
import com.d4guilar.shelfos.feature.library.PublicationDetails
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Phase 4.5F.0 Cover action and dialog on a real store: reachable from the details pane, custom-cover state and the
 * restore consequence shown, progress/errors announced, close and keyboard focus, and the open dialog surviving a
 * saved-state restoration. The system picker is not driven (it is not part of the app); its result path is the store.
 */
class UserCoverUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
    private val root = File(context.cacheDir, "user-cover-ui-${System.nanoTime()}")
    private val files = CoverFiles(root, { Long.MAX_VALUE })
    private val store = UserCoverStore(RoomCoverRepository(db.covers(), throttleMillis = 0), files, Mutex(), { FileInputStream(it) })
    private val item = LibraryItem("a", "A Book", "An Author", MediaCategory.BOOK, "test:a", PublicationFormat.EPUB, "a.epub", 1000)
    @After fun close() { db.close(); root.deleteRecursively() }

    private fun content(): @Composable () -> Unit = {
        ShelfTheme(ThemeId.CLASSIC) {
            CompositionLocalProvider(LocalUserCovers provides store) {
                Box(Modifier.size(360.dp, 640.dp).testTag("window")) {
                    PublicationDetails(item, false, {}, Modifier.fillMaxSize(), onRead = {}, onEdit = { _, _, _ -> }, onRemove = {}, knowledgeCounts = AnnotationCounts.None)
                }
            }
        }
    }
    private fun show(restoration: StateRestorationTester? = null) {
        runBlocking { RoomLibraryRepository(db.library()).add(item) }
        if (restoration != null) restoration.setContent(content()) else compose.setContent(content())
    }
    private fun pickFile(): String {
        val f = File(root.also { it.mkdirs() }, "ui-pick-${System.nanoTime()}.jpg")
        val b = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.path
    }
    private fun openDialog() { compose.onNodeWithTag("cover_action").performScrollTo().performClick(); compose.onNodeWithTag("cover_status").assertIsDisplayed() }

    @Test fun theCoverActionOpensADialogThatShowsThePreviewStateAndCloses() {
        show()
        openDialog()
        compose.onNodeWithTag("cover_preview").assertIsDisplayed()
        compose.onNodeWithTag("cover_status").assertTextContains(context.getString(R.string.cover_file_active))
        compose.onNodeWithTag("cover_restore").assertDoesNotExist()
        compose.onNodeWithTag("cover_choose").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        compose.onNodeWithTag("cover_close").performClick()
        compose.onNodeWithTag("cover_status").assertDoesNotExist()
    }

    @Test fun aChosenCoverIsReportedAndRestoringExplainsItsConsequence() {
        show(); openDialog()
        assertEquals(UserCoverOutcome.SAVED, runBlocking { store.set("a", pickFile()) })
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("cover_restore").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cover_status").assertTextContains(context.getString(R.string.cover_custom_active))
        // The dialog content scrolls (short landscape windows): bring the explanation and the button into view first.
        compose.onNodeWithText(context.getString(R.string.cover_restore_explain)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cover_restore").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("cover_restore").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("cover_status").assertTextContains(context.getString(R.string.cover_file_active))
        assertTrue("the chosen file was deleted by the restore", root.walkTopDown().none { it.isFile && it.name.startsWith("user-") })
    }

    @Test fun theDialogIsKeyboardReachableAndItsButtonsAreFocusable() {
        show(); openDialog()
        for (tag in listOf("cover_choose", "cover_close")) compose.onNodeWithTag(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
    }

    @Test fun theOpenDialogSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        show(restoration); openDialog()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("cover_status").assertIsDisplayed()
        compose.onNodeWithTag("cover_close").performClick()
        compose.onNodeWithTag("cover_status").assertDoesNotExist()
    }

    @Test fun theDetailsLineSaysWhenTheCoverIsTheUsersOwn() {
        show()
        runBlocking { store.set("a", pickFile()) }
        compose.onNodeWithText(context.getString(R.string.details_expand)).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.detail_cover_user), substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
