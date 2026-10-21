// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.network.ImageFetch
import com.d4guilar.shelfos.core.network.ImageTransport
import com.d4guilar.shelfos.core.network.ResponseTooLargeException
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.library.*
import com.d4guilar.shelfos.data.metadata.openlibrary.CoverEdition
import com.d4guilar.shelfos.data.preferences.BooleanStore
import com.d4guilar.shelfos.debug.coverlab.CoverLabSession
import com.d4guilar.shelfos.debug.coverlab.DebugCoverLabEntry
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import com.d4guilar.shelfos.feature.library.LocalCoverLab
import com.d4guilar.shelfos.feature.library.LocalUserCovers
import com.d4guilar.shelfos.feature.library.PublicationDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * Phase 4.5C.2B on device: the debug-only Cover Lab, driven by a scripted image transport and scripted edition records (no network is
 * touched). Real pieces under test: the Compose dialog, the pilot service/store, the production image writer (real JPEG decoding) and the
 * shared cover renderer's geometry. Fixtures are generated locally.
 */
class OnlineCoverPilotUiTest {
    @get:Rule val rule = createComposeRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = inst.targetContext

    private class Prefs : BooleanStore {
        val map = HashMap<String, Boolean>()
        override fun get(key: String, default: Boolean) = map[key] ?: default
        override fun put(key: String, value: Boolean) { map[key] = value }
    }

    /** URL → (status, content type, bytes). Records every request; an unscripted URL is a 404. */
    private class Images : ImageTransport {
        val requests = ArrayList<String>()
        val script = HashMap<String, () -> Triple<Int, String?, ByteArray>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: Throwable? = null
        override suspend fun fetch(url: String, destination: File, maxBytes: Int): ImageFetch {
            requests += url
            gate?.await()
            failure?.let { throw it }
            val (status, type, bytes) = script[url.substringBefore('?')]?.invoke() ?: Triple(404, "text/html", ByteArray(0))
            if (status == 200) destination.writeBytes(bytes)
            return ImageFetch(status, type, null, if (status == 200) bytes.size.toLong() else 0)
        }
    }

    private fun image(w: Int, h: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.RED, Color.BLUE, Shader.TileMode.CLAMP) })
        return ByteArrayOutputStream().also { b.compress(format, 85, it); b.recycle() }.toByteArray()
    }

    private val isbn = "9780140328721"
    private val book = LibraryItem("b1", "Matilda", category = MediaCategory.BOOK, sourceUri = "test:b1", format = PublicationFormat.EPUB, fileName = "Matilda.epub", byteSize = 1)
    private val otherBook = LibraryItem("b2", "Another", category = MediaCategory.BOOK, sourceUri = "test:b2", format = PublicationFormat.EPUB, fileName = "Another.epub", byteSize = 1)
    private val comic = LibraryItem("c1", "Comic", category = MediaCategory.COMIC, sourceUri = "test:c1", format = PublicationFormat.CBZ, fileName = "c.cbz", byteSize = 1)
    private fun url(id: Long, size: String) = "https://covers.openlibrary.org/b/id/$id-$size.jpg"

    private val workRoot = File(context.cacheDir, "cover-pilot-ui-${System.nanoTime()}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
    private val coverRoot = File(workRoot, "covers")
    private val userCovers = UserCoverStore(RoomCoverRepository(db.covers(), throttleMillis = 0), CoverFiles(coverRoot, { Long.MAX_VALUE }), Mutex(), { FileInputStream(it) })
    private val prefs = Prefs()
    private val auth = CoverPilotAuthorization(prefs, debugBuild = true)
    private val images = Images()
    private var editionCalls = 0
    private var edition: List<CoverEdition> = listOf(
        CoverEdition("OL1M", "Matilda", listOf(101L), listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), "Puffin", "1998", "eng".let { "en" }, true),
        CoverEdition("OL2M", "Matilda", listOf(202L), listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9788420472850")), "Alfaguara", "2001", "es", false),
        CoverEdition("OL3M", "Matilda", listOf(303L), listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9780140328738")), "Puffin", "1999", "en", false),
    )

    private fun newSession(transport: ImageTransport = images): CoverLabSession {
        val store = CoverPilotStore(File(workRoot, "files/cover_lab"), File(workRoot, "cache/cover_lab"))
        val service = CoverPilotService(auth, store, transport, { editionCalls++; edition },
            { MetadataQuery(MediaCategory.BOOK, listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), title = "Matilda", language = "en") },
            { true }, { Long.MAX_VALUE })
        return CoverLabSession(auth, store, service, scope, File(workRoot, "files/cover_lab"), { true })
    }

    private var session = newSession()

    @After fun cleanUp() { scope.cancel(); db.close(); workRoot.deleteRecursively() }

    private fun script() {
        images.script[url(101, "M")] = { Triple(200, "image/jpeg", image(400, 600)) }
        images.script[url(101, "L")] = { Triple(200, "image/jpeg", image(400, 600)) }
        images.script[url(202, "M")] = { Triple(200, "image/jpeg", image(500, 500)) }
    }

    private fun show(item: LibraryItem = book, entry: DebugCoverLabEntry = DebugCoverLabEntry(session), restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalCoverLab provides entry, LocalUserCovers provides userCovers) {
                    PublicationDetails(item, false, {}, Modifier.fillMaxSize(), onRead = {}, onEdit = { _, _, _ -> }, onRemove = {}, knowledgeCounts = AnnotationCounts.None)
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
    }

    private fun str(id: Int) = context.getString(id)
    private fun exists(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String, ms: Long = 10_000) = rule.waitUntil(ms) { exists(tag) }
    private fun waitGone(tag: String, ms: Long = 10_000) = rule.waitUntil(ms) { !exists(tag) }
    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick() }
    private fun clickButton(tag: String) { rule.onNodeWithTag(tag).assertIsDisplayed().performClick() }
    private fun open() { click("cover_lab_action"); waitFor("cover_lab_dialog") }
    private fun enable() { clickButton("cover_lab_enable"); waitFor("cover_lab_find") }
    private fun find() { click("cover_lab_find"); waitFor("cover_lab_candidate_0") }

    private fun gamepadKey(code: Int) {
        val down = SystemClock.uptimeMillis()
        inst.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        inst.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        rule.waitForIdle()
    }

    private fun pilotFiles(): List<File> = File(workRoot, "files/cover_lab").walkTopDown().filter { it.isFile && !it.name.endsWith(".properties") }.toList()

    // 1
    @Test fun theActionIsOnBooksOnlyAndOpeningTheLabNeverSendsARequest() {
        script(); show()
        rule.onNodeWithTag("cover_lab_action").performScrollTo().assertIsDisplayed()
        open()
        rule.onNodeWithTag("cover_lab_disclosure").assertIsDisplayed()
        assertTrue("no request before consent", images.requests.isEmpty() && editionCalls == 0)
    }

    @Test fun otherCategoriesHaveNoCoverLab() {
        show(comic)
        rule.onNodeWithTag("cover_lab_action").assertDoesNotExist()
    }

    // 2, 3
    @Test fun cancelOnTheDisclosureKeepsThePilotOffAndSendsNothing() {
        show(); open()
        clickButton("cover_lab_close")
        waitGone("cover_lab_dialog")
        assertFalse(auth.enabled.value); assertTrue(images.requests.isEmpty() && editionCalls == 0)
    }

    @Test fun enablingIsExplicitAndStillSendsNothingUntilFind() {
        show(); open(); enable()
        assertTrue(auth.enabled.value)
        assertTrue(images.requests.isEmpty() && editionCalls == 0)
    }

    // 4
    @Test fun findListsEditionCandidatesWithTheirWarningsAndFetchesNoImages() {
        script(); show(); open(); enable(); find()
        assertEquals(1, editionCalls); assertTrue("no artwork requested by discovery", images.requests.isEmpty())
        rule.onNodeWithText(str(R.string.cover_lab_exact)).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(str(R.string.cover_lab_other))[0].performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("⚠ " + str(R.string.cover_lab_warn_language))[0].performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("⚠ " + str(R.string.cover_lab_warn_isbn))[0].assertExists()
    }

    // 5, 6
    @Test fun aPreviewLoadsOnDemandAndAMissingImageIsReportedWithoutChangingAnything() {
        script(); show(); open(); enable(); find()
        click("cover_lab_preview_0"); waitFor("cover_lab_thumb_0")
        assertEquals(listOf(url(101, "M") + "?default=false"), images.requests)
        click("cover_lab_preview_2"); waitFor("cover_lab_error")
        rule.onNodeWithTag("cover_lab_error").assertTextContains(str(R.string.cover_lab_err_not_found))
        assertFalse(exists("cover_lab_thumb_2")); assertTrue(pilotFiles().isEmpty())
    }

    // 7, 8, 14
    @Test fun downloadStoresThePickPrivatelyShowsTheDecodedSizeAndLeavesTheUserCoverAlone() {
        script(); show()
        runBlocking { RoomLibraryRepository(db.library()).add(book) }
        val userFile = File(workRoot.also { it.mkdirs() }, "user-cover-${System.nanoTime()}.jpg").also { it.writeBytes(image(300, 450)) }
        assertEquals(UserCoverOutcome.SAVED, runBlocking { userCovers.set("b1", userFile.path) })
        val before = coverRoot.walkTopDown().filter { it.isFile }.map { it.name to it.length() }.toSet()
        open(); enable(); find()
        click("cover_lab_inspect_0"); waitFor("cover_lab_inspector")
        click("cover_lab_inspector_preview"); waitFor("cover_lab_dims")
        rule.onNodeWithTag("cover_lab_dims").assertTextContains("400 × 600", substring = true)
        click("cover_lab_download"); waitFor("cover_lab_selected_info")
        rule.onNodeWithTag("cover_lab_selected_info").assertTextContains("400 × 600", substring = true)
        assertEquals(listOf(url(101, "M") + "?default=false", url(101, "L") + "?default=false"), images.requests)
        assertEquals(1, pilotFiles().size)
        assertTrue("stored under the pilot directory, not the production cover directory", pilotFiles().single().path.contains("cover_lab"))
        assertEquals("production covers are untouched", before, coverRoot.walkTopDown().filter { it.isFile }.map { it.name to it.length() }.toSet())
        assertTrue(runBlocking { userCovers.hasCustom("b1").first() })
    }

    // 9
    @Test fun theSavedPickIsShownAfterARestartWithNoNetworkAtAll() {
        script()
        var entry by mutableStateOf(DebugCoverLabEntry(session))
        rule.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalCoverLab provides entry) {
                    PublicationDetails(book, false, {}, Modifier.fillMaxSize(), onRead = {}, onEdit = { _, _, _ -> }, onRemove = {}, knowledgeCounts = AnnotationCounts.None)
                }
            }
        }
        open(); enable(); find()
        click("cover_lab_inspect_0"); click("cover_lab_download"); waitFor("cover_lab_selected_info")
        clickButton("cover_lab_close"); waitGone("cover_lab_dialog")
        // A new process: a new session over the same directories, and a transport that fails on any use.
        val offline = Images().apply { failure = java.io.IOException("offline") }
        entry = DebugCoverLabEntry(newSession(offline))
        rule.waitForIdle()
        open()
        waitFor("cover_lab_selected_info")
        rule.onNodeWithTag("cover_lab_selected_info").assertTextContains("400 × 600", substring = true)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("cover_artwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("shown from private storage with no request", offline.requests.isEmpty())
    }

    // 10, 11
    @Test fun shapeAndFitChoicesNeverRequestAndFollowTheProductionGeometry() {
        script(); show(); open(); enable(); find()
        click("cover_lab_inspect_0"); click("cover_lab_inspector_preview"); waitFor("cover_lab_compare")
        val requests = images.requests.size
        fun ratio(): Float {
            val node = rule.onAllNodes(hasTestTag("cover_artwork") and hasAnyAncestor(hasTestTag("cover_lab_compare")), useUnmergedTree = true).onFirst().fetchSemanticsNode()
            return node.size.width.toFloat() / node.size.height
        }
        val expected = listOf(0 to 2f / 3, 1 to 2f / 3, 2 to 3f / 4, 3 to 4f / 5, 4 to 1f, 5 to 5f / 8, 6 to 16f / 9, 7 to 0.5f, 8 to 2f)
        for (fit in listOf(false, true)) {
            click(if (fit) "cover_lab_fit" else "cover_lab_crop")
            for ((i, r) in expected) {
                click("cover_lab_shape_$i")
                rule.waitForIdle()
                assertEquals("shape $i fit=$fit", r, ratio(), 0.03f)
            }
        }
        assertEquals("presentation changes request nothing", requests, images.requests.size)
        assertTrue(pilotFiles().isEmpty())
    }

    // 12
    @Test fun theChosenPresentationSurvivesRecreation() {
        script()
        val restoration = StateRestorationTester(rule)
        show(restoration = restoration); open(); enable(); find()
        click("cover_lab_shape_6"); click("cover_lab_fit")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("cover_lab_dialog")
        rule.onNodeWithTag("cover_lab_shape_6").performScrollTo().assertIsSelected()
        rule.onNodeWithTag("cover_lab_fit").assertIsSelected()
        rule.onNodeWithTag("cover_lab_candidate_0").assertExists()   // the session outlives the composition
    }

    // 13
    @Test fun controllerDpadAAndBDriveTheDisclosureAndTheDialog() {
        script(); show(); open()
        gamepadKey(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("cover_lab_close").assertIsFocused()   // starts on the safe button, never the consent button
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        waitGone("cover_lab_dialog")
        assertFalse(auth.enabled.value); assertTrue(images.requests.isEmpty() && editionCalls == 0)
        open(); gamepadKey(KeyEvent.KEYCODE_BUTTON_B)
        waitGone("cover_lab_dialog")
        assertFalse(auth.enabled.value)
        open(); gamepadKey(KeyEvent.KEYCODE_DPAD_DOWN)
        gamepadKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("cover_lab_enable").assertIsFocused()
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        rule.waitUntil(5_000) { auth.enabled.value }
        waitFor("cover_lab_find")
        assertEquals("enabling alone sends nothing", 0, editionCalls)
    }

    // 15
    @Test fun invalidAndOversizedImagesAreRejectedAndTheLastPickSurvives() {
        script(); show(); open(); enable(); find()
        click("cover_lab_inspect_0"); click("cover_lab_download"); waitFor("cover_lab_selected_info")
        val pick = pilotFiles().single()
        click("cover_lab_back"); waitFor("cover_lab_candidate_1")
        val cases = listOf(
            Triple(Triple(200, "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64) { 7 }), null, R.string.cover_lab_err_invalid),
            Triple(Triple(200, "image/jpeg", image(100, 100, Bitmap.CompressFormat.PNG)), null, R.string.cover_lab_err_mime),
            Triple(Triple(200, "image/jpeg", "<html>nope</html>".toByteArray()), null, R.string.cover_lab_err_not_image),
            Triple(Triple(200, "text/html", image(100, 100)), null, R.string.cover_lab_err_not_image),
            Triple(Triple(200, "image/jpeg", image(100, 100)), ResponseTooLargeException(1), R.string.cover_lab_err_too_large),
        )
        for ((response, failure, message) in cases) {
            images.script[url(202, "L")] = { response }
            images.failure = failure
            click("cover_lab_inspect_1"); waitFor("cover_lab_download"); click("cover_lab_download")
            rule.waitUntil(10_000) { exists("cover_lab_error") && !exists("cover_lab_busy") }
            rule.onNodeWithTag("cover_lab_error").assertTextContains(str(message))
            assertEquals("the last valid pick is untouched", listOf(pick), pilotFiles())
            click("cover_lab_back"); waitFor("cover_lab_candidate_1")
        }
    }

    @Test fun veryLargeAndExtremeFixturesAreBoundedAndKeepTheirProportions() {
        script()
        images.script[url(101, "L")] = { Triple(200, "image/jpeg", image(2400, 3600)) }
        show(); open(); enable(); find()
        click("cover_lab_inspect_0"); click("cover_lab_download"); waitFor("cover_lab_selected_info")
        val info = pilotFiles().single()
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(info.path, o)
        assertTrue("bounded edge", maxOf(o.outWidth, o.outHeight) <= 1200)
        assertEquals(2f / 3, o.outWidth.toFloat() / o.outHeight, 0.01f)
    }

    // 16
    @Test fun anotherBookNeverShowsThisBooksCandidatesOrPick() {
        script()
        var shown by mutableStateOf(book)
        rule.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalCoverLab provides DebugCoverLabEntry(session)) {
                    PublicationDetails(shown, false, {}, Modifier.fillMaxSize(), onRead = {}, onEdit = { _, _, _ -> }, onRemove = {}, knowledgeCounts = AnnotationCounts.None)
                }
            }
        }
        open(); enable(); find()
        click("cover_lab_inspect_0"); click("cover_lab_download"); waitFor("cover_lab_selected_info")
        clickButton("cover_lab_close"); waitGone("cover_lab_dialog")
        shown = otherBook
        rule.waitForIdle()
        open()
        assertFalse(exists("cover_lab_candidate_0")); assertFalse(exists("cover_lab_selected_info"))
    }

    // 17
    @Test fun rapidRepeatedDownloadPressesStartOneTransfer() {
        script(); show(); open(); enable(); find()
        click("cover_lab_inspect_0"); click("cover_lab_inspector_preview"); waitFor("cover_lab_dims")
        val gate = CompletableDeferred<Unit>(); images.gate = gate
        val before = images.requests.size
        click("cover_lab_download")
        rule.waitUntil(5_000) { images.requests.size > before }
        runCatching { rule.onNodeWithTag("cover_lab_download").performClick() }   // disabled while the first runs
        runCatching { rule.onNodeWithTag("cover_lab_download").performClick() }
        gate.complete(Unit)
        waitFor("cover_lab_selected_info")
        assertEquals(1, images.requests.drop(before).count { it.contains("-L.jpg") })
    }

    // 18
    @Test fun closingDuringATransferPublishesNothing() {
        script(); show(); open(); enable(); find()
        click("cover_lab_inspect_0"); click("cover_lab_inspector_preview"); waitFor("cover_lab_dims")
        val gate = CompletableDeferred<Unit>(); images.gate = gate
        click("cover_lab_download")
        rule.waitUntil(5_000) { images.requests.any { it.contains("-L.jpg") } }
        clickButton("cover_lab_close"); waitGone("cover_lab_dialog")
        gate.complete(Unit)
        Thread.sleep(500)
        assertTrue("nothing was committed after the dialog closed", pilotFiles().isEmpty())
        assertNull(session.ui("b1").value.selection)
    }
}
