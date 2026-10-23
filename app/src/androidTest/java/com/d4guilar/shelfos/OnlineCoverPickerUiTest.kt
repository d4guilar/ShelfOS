// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.library.*
import com.d4guilar.shelfos.data.metadata.openlibrary.CoverEdition
import com.d4guilar.shelfos.data.preferences.BooleanStore
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.library.CoverPresentations
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import com.d4guilar.shelfos.feature.library.LocalOnlineCovers
import com.d4guilar.shelfos.feature.library.LocalUserCovers
import com.d4guilar.shelfos.feature.library.OnlineCoverController
import com.d4guilar.shelfos.feature.library.PublicationDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Phase 4.5C.3A on device: the production Find cover online flow, driven by scripted editions and a scripted image transport (no network). Real
 * pieces: the Compose dialogs, the service, controller, [UserCoverStore] on a real Room database, and real JPEG decoding.
 */
class OnlineCoverPickerUiTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class Prefs : BooleanStore {
        val map = HashMap<String, Boolean>()
        override fun get(key: String, default: Boolean) = map[key] ?: default
        override fun put(key: String, value: Boolean) { map[key] = value }
    }

    private class Images : ImageTransport {
        val requests = ArrayList<String>()
        val script = HashMap<String, () -> Triple<Int, String?, ByteArray>>()
        var failure: Throwable? = null
        override suspend fun fetch(url: String, destination: File, maxBytes: Int): ImageFetch {
            requests += url
            failure?.let { throw it }
            val (status, type, bytes) = script[url.substringBefore('?')]?.invoke() ?: Triple(404, "text/html", ByteArray(0))
            if (status == 200) destination.writeBytes(bytes)
            return ImageFetch(status, type, null, if (status == 200) bytes.size.toLong() else 0)
        }
    }

    private fun image(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.RED, Color.BLUE, Shader.TileMode.CLAMP) })
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 85, it); b.recycle() }.toByteArray()
    }

    private val isbn = "9780140328721"
    private val book = LibraryItem("b1", "Matilda", category = MediaCategory.BOOK, sourceUri = "test:b1", format = PublicationFormat.EPUB, fileName = "Matilda.epub", byteSize = 1)
    private val comic = LibraryItem("c1", "Comic", category = MediaCategory.COMIC, sourceUri = "test:c1", format = PublicationFormat.CBZ, fileName = "c.cbz", byteSize = 1)
    private fun url(id: Long, size: String) = "https://covers.openlibrary.org/b/id/$id-$size.jpg"

    private val workRoot = File(context.cacheDir, "cover-picker-ui-${System.nanoTime()}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
    private val coverFiles = CoverFiles(File(workRoot, "covers"), { Long.MAX_VALUE })
    private val library = RoomLibraryRepository(db.library(), covers = coverFiles)
    private val onlineFiles = OnlineCoverFiles(File(workRoot, "previews"), File(workRoot, "staging"))
    private val userCovers = UserCoverStore(RoomCoverRepository(db.covers(), throttleMillis = 0), coverFiles, Mutex(), { FileInputStream(it) }, stagingRoot = onlineFiles.stagingRoot)
    private val metadata = OnlineMetadataSettings(Prefs())
    private val consent = OnlineCoverConsent(Prefs())
    private val auth = OnlineCoverAuthorization(metadata, consent)
    private val images = Images()
    private var editionCalls = 0
    private val editions = listOf(
        CoverEdition("OL1M", "Matilda", listOf(101L), listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), "Puffin", "1998", "en", true),
        CoverEdition("OL2M", "Matilda", listOf(202L), listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9788420472850")), "Alfaguara", "2001", "es", false),
    )
    private val service = OnlineCoverService(auth, onlineFiles, images, { editionCalls++; editions },
        { MetadataQuery(MediaCategory.BOOK, listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), title = "Matilda", language = "en") },
        { id -> library.publication(id).first() }, { Long.MAX_VALUE }, userCovers::epoch,
        { id, file, origin, epoch, guard -> userCovers.setFromPrivateFile(id, file, origin, epoch, guard) })
    private val controller = OnlineCoverController(auth, consent, metadata, service, scope, MutableStateFlow(CoverPresentations()))

    init { runBlocking { library.add(book); library.add(comic) } }

    @After fun cleanUp() { scope.cancel(); db.close(); workRoot.deleteRecursively() }

    private fun script() {
        for (id in listOf(101L, 202L)) for (size in listOf("M", "L")) images.script[url(id, size)] = { Triple(200, "image/jpeg", image(400, 600)) }
    }

    private fun show(item: LibraryItem = book, restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalUserCovers provides userCovers, LocalOnlineCovers provides controller) {
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
    /** Scrolls when the node is inside a scrolling area; dialog buttons sit outside it and are simply displayed. */
    private fun click(tag: String) {
        val node = rule.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed().performClick()
    }
    private fun openPicker() { click("cover_action"); waitFor("cover_find_online"); click("cover_find_online"); waitFor("cover_online_dialog") }
    private fun enable() { click("cover_online_allow_metadata"); click("cover_online_allow_covers"); click("cover_online_enable"); waitFor("cover_online_find") }
    private fun find() { click("cover_online_find"); waitFor("cover_online_candidate_0") }
    private fun userRow(id: String = "b1") = db.openHelper.readableDatabase.query(
        "SELECT fileName, originKind, originProvider, originEdition, originRefKind, originRefValue FROM publication_cover WHERE libraryItemId = '$id' AND slot = 'USER'").use { c ->
        if (c.moveToFirst()) (0..5).map { c.getString(it) } else null }
    private fun rowCount(slot: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM publication_cover WHERE slot = '$slot'").use { it.moveToFirst(); it.getInt(0) }

    @Test fun theActionIsOnBooksOnlyAndOpeningTheDisclosureSendsNothing() {
        script(); show()
        click("cover_action"); waitFor("cover_find_online")
        click("cover_find_online"); waitFor("cover_online_disclosure")
        rule.onNodeWithTag("cover_online_disclosure").assertIsDisplayed()
        assertTrue("no request before consent", images.requests.isEmpty() && editionCalls == 0)
        assertFalse(consent.enabled.value)
    }

    @Test fun otherCategoriesHaveNoFindCoverOnline() {
        show(comic)
        click("cover_action"); waitFor("cover_choose")
        rule.onNodeWithTag("cover_find_online").assertDoesNotExist()
    }

    @Test fun eachMissingConsentIsExplicitAndEnablingStillSendsNothing() {
        script(); show(); openPicker()
        rule.onNodeWithTag("cover_online_enable").assertIsNotEnabled()
        click("cover_online_allow_metadata"); rule.onNodeWithTag("cover_online_enable").assertIsNotEnabled()
        click("cover_online_allow_covers"); rule.onNodeWithTag("cover_online_enable").assertIsEnabled()
        click("cover_online_enable"); waitFor("cover_online_find")
        assertTrue(consent.enabled.value && metadata.onlineEnabled)
        assertTrue("enabling alone sends nothing", images.requests.isEmpty() && editionCalls == 0)
    }

    @Test fun metadataAlreadyOnStillNeedsTheSeparateCoverConsent() {
        metadata.enableProvider("openlibrary")
        show(); openPicker()
        rule.onNodeWithTag("cover_online_allow_metadata").assertDoesNotExist()
        rule.onNodeWithTag("cover_online_allow_covers").assertIsDisplayed()
        assertFalse(consent.enabled.value); assertTrue(images.requests.isEmpty() && editionCalls == 0)
    }

    @Test fun findShowsEditionCandidatesWithHonestLabelsAndFetchesNoImages() {
        script(); show(); openPicker(); enable(); find()
        assertEquals(1, editionCalls); assertTrue("no artwork requested by discovery", images.requests.isEmpty())
        rule.onNodeWithText(str(R.string.cover_online_label_isbn)).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText(str(R.string.cover_online_label_other))[0].performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("⚠ " + str(R.string.cover_online_warn_language))[0].assertExists()
        rule.onAllNodesWithText("⚠ " + str(R.string.cover_online_warn_isbn))[0].assertExists()
    }

    @Test fun previewIsNotConfirmationAndUseThisCoverSavesADurableUserCoverWithProvenance() {
        script(); show(); openPicker(); enable(); find()
        click("cover_online_preview_0"); waitFor("cover_online_whole")
        assertEquals(listOf(url(101, "M") + "?default=false"), images.requests)
        assertNull("a preview applies nothing", userRow()); assertEquals(0, rowCount("USER")); assertEquals(0, rowCount("ONLINE"))
        click("cover_online_back"); waitFor("cover_online_candidate_0")
        assertNull(userRow())
        click("cover_online_preview_0"); waitFor("cover_online_use")
        click("cover_online_use"); waitFor("cover_online_saved")
        assertEquals(listOf(url(101, "M") + "?default=false", url(101, "L") + "?default=false"), images.requests)
        val row = userRow()!!
        assertTrue(row[0]!!.matches(Regex("user-[0-9a-f]{16}\\.[a-z]{3,4}")))
        assertEquals(listOf("PROVIDER_SELECTED", "openlibrary", "OL1M", "id", "101"), row.drop(1))
        assertEquals("never the ONLINE slot", 0, rowCount("ONLINE"))
        assertTrue(coverFiles.file("b1", row[0]!!).isFile)
        assertTrue("the staged download is gone", onlineFiles.stagingRoot.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancelAndCloseNeverApplyACover() {
        script(); show(); openPicker(); enable(); find()
        click("cover_online_preview_1"); waitFor("cover_online_use")
        click("cover_online_back"); waitFor("cover_online_candidate_0")
        click("cover_online_close"); waitGone("cover_online_dialog")
        assertNull(userRow()); assertEquals(0, rowCount("USER"))
        assertFalse("no large image was ever requested", images.requests.any { it.contains("-L.jpg") })
    }

    @Test fun aBlockedRedirectIsExplainedAndKeepsTheCurrentCover() {
        script(); show(); openPicker(); enable(); find()
        click("cover_online_preview_0"); waitFor("cover_online_use")
        images.failure = UrlNotAllowedException("host not allowed")
        click("cover_online_use")
        rule.waitUntil(10_000) { exists("cover_online_error") && !exists("cover_online_busy") }
        rule.onNodeWithTag("cover_online_error").assertTextContains(str(R.string.cover_online_err_redirect))
        assertNull(userRow()); rule.onNodeWithTag("cover_online_back").assertIsEnabled()
        images.failure = null
    }

    @Test fun theSelectedCandidateSurvivesRotation() {
        script()
        val restoration = StateRestorationTester(rule)
        show(restoration = restoration); openPicker(); enable(); find()
        click("cover_online_preview_1"); waitFor("cover_online_inspector")
        val requests = images.requests.size
        restoration.emulateSavedInstanceStateRestore()
        waitFor("cover_online_inspector")
        assertEquals("rotation requests nothing", requests, images.requests.size); assertNull(userRow())
    }

    @Test fun withdrawingConsentReturnsToTheDisclosureAndDropsTheResults() {
        script(); show(); openPicker(); enable(); find()
        consent.set(false)
        waitFor("cover_online_disclosure"); assertFalse(exists("cover_online_candidate_0"))
        assertNull(userRow())
    }

    @Test fun anEnabledBookNeverRequestsArtworkUntilAPersonAsks() {
        script(); metadata.enableProvider("openlibrary"); consent.set(true)
        show()
        click("cover_action"); waitFor("cover_find_online")
        click("cover_close")
        waitGone("cover_status")
        rule.waitForIdle()
        assertTrue("details, the cover dialog and closing it make no request", images.requests.isEmpty() && editionCalls == 0)
    }
}
