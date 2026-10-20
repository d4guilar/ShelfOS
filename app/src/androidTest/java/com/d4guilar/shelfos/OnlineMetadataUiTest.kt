// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.d4guilar.shelfos.core.database.LocalCursor
import com.d4guilar.shelfos.core.network.HttpResponse
import com.d4guilar.shelfos.core.network.HttpTransport
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.metadata.*
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibrary
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibraryProvider
import com.d4guilar.shelfos.data.preferences.BooleanStore
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.annotations.AnnotationCounts
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import com.d4guilar.shelfos.feature.library.LocalOnlineMetadata
import com.d4guilar.shelfos.feature.library.OnlineMetadataAccess
import com.d4guilar.shelfos.feature.library.PublicationDetails
import com.d4guilar.shelfos.feature.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Phase 4.5C.2A on device: the opt-in flow in Settings and in Book details, driven by a fake transport (no real network is touched).
 * Disclosure before the first request, Enable and search, Cancel without enabling, results, explicit Apply, disable during a request,
 * no accidental request from navigation, and keyboard/D-pad confirm.
 */
class OnlineMetadataUiTest {
    @get:Rule val rule = createComposeRule()

    private class Prefs : BooleanStore {
        val map = HashMap<String, Boolean>()
        override fun get(key: String, default: Boolean) = map[key] ?: default
        override fun put(key: String, value: Boolean) { map[key] = value }
    }

    private class Http : HttpTransport {
        val requests = ArrayList<String>()
        override suspend fun get(url: String): HttpResponse {
            requests += url
            val body = when {
                url.contains("/isbn/") -> """{"key":"/books/OL1M","title":"Matilda","publishers":["Puffin"],"isbn_13":["9780140328721"],"publish_date":"1998","languages":[{"key":"/languages/eng"}],"covers":[1]}"""
                else -> "{}"
            }
            return HttpResponse(200, mapOf("content-type" to "application/json"), body.toByteArray())
        }
    }

    private class Store : MetadataStore {
        var current = ResolvedMetadata("Matilda", "", MetadataValues(identifiers = listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9780140328721"))),
            mapOf(MetadataField.TITLE to FieldProvenance(MetadataOrigin.FILENAME_INFERENCE), MetadataField.IDENTIFIERS to FieldProvenance(MetadataOrigin.EMBEDDED)))
        override suspend fun resolved(id: String) = current
        override suspend fun apply(id: String, proposals: List<Proposed>, analysisVersion: Int?): Boolean { current = resolveMetadata(current, proposals); return true }
        override fun observe(id: String): Flow<StoredMetadata?> = emptyFlow()
        override suspend fun pendingLocal(version: Int, after: LocalCursor?, limit: Int) = emptyList<LocalCursor>()
    }

    private val book = LibraryItem("b1", "Matilda", category = MediaCategory.BOOK, sourceUri = "test:b1", format = PublicationFormat.EPUB, fileName = "Matilda.epub", byteSize = 1)

    private class Rig(val http: Http, val settings: OnlineMetadataSettings, val access: OnlineMetadataAccess, val store: Store)

    private fun rig(enabled: Boolean = false): Rig {
        val http = Http(); val store = Store(); val settings = OnlineMetadataSettings(Prefs()).also { if (enabled) it.enableProvider(OpenLibrary.ID) }
        val registry = ProviderRegistry(listOf(RegisteredProvider(OpenLibrary.descriptor(), OpenLibraryProvider(http))), settings)
        val manual = ManualMetadataLookup(ProviderLookup(registry, ProviderPolicyEngine({ System.nanoTime() / 1_000_000 })), store)
        return Rig(http, settings, OnlineMetadataAccess(settings, manual), store)
    }

    private val inst get() = InstrumentationRegistry.getInstrumentation()

    /** Scrolls the node into the visible viewport (details and Settings scroll; short landscape screens hide controls) and then clicks it. */
    private fun clickVisible(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    }

    /** Dialog action buttons sit outside the dialog's scrolling body, so they are only checked for visibility. */
    private fun clickDialogButton(tag: String) {
        rule.onNodeWithTag(tag).assertIsDisplayed().performClick()
    }

    /** A real gamepad-source key press through the Android input pipeline, as the controller delivers it. */
    private fun gamepadKey(code: Int) {
        val down = SystemClock.uptimeMillis()
        inst.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        inst.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD))
        rule.waitForIdle()
    }

    private fun showDetails(r: Rig) = rule.setContent {
        CompositionLocalProvider(LocalOnlineMetadata provides r.access) {
            ShelfTheme(ThemeId.CLASSIC) {
                PublicationDetails(book, false, {}, onRead = {}, onEdit = { _, _, _ -> }, onRemove = {}, knowledgeCounts = AnnotationCounts(0, 0, 0, 0))
            }
        }
    }

    @Test fun navigationAloneNeverSendsARequestAndTheActionIsPresentForBooks() {
        val r = rig(); showDetails(r)
        rule.onNodeWithTag("find_metadata_online_action").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("online_dialog").assertDoesNotExist()
        assertTrue(r.http.requests.isEmpty())
    }

    @Test fun disclosureAppearsBeforeAnyRequestAndCancelKeepsTheProviderOff() {
        val r = rig(); showDetails(r)
        clickVisible("find_metadata_online_action")
        rule.onNodeWithTag("online_enable_search").assertIsDisplayed()
        assertTrue("no request before consent", r.http.requests.isEmpty())
        clickDialogButton("online_cancel")
        rule.onNodeWithTag("online_dialog").assertDoesNotExist()
        assertFalse(r.settings.providerEnabled(OpenLibrary.ID)); assertTrue(r.http.requests.isEmpty())
    }

    @Test fun enableAndSearchRunsTheLookupShowsTheCandidateAndApplyIsExplicit() {
        val r = rig(); showDetails(r)
        clickVisible("find_metadata_online_action")
        clickDialogButton("online_enable_search")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("online_candidate_0").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(r.settings.onlineEnabled && r.settings.providerEnabled(OpenLibrary.ID))
        assertTrue(r.http.requests.all { it.startsWith("https://openlibrary.org/") && !it.contains("covers") })
        assertNull("nothing applied yet", r.store.current.values.publisher)
        clickVisible("online_apply_0")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("online_done").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Puffin", r.store.current.values.publisher)
    }

    @Test fun disablingTheProviderWhileResultsAreOpenClosesThem() {
        val r = rig(enabled = true); showDetails(r)
        clickVisible("find_metadata_online_action")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("online_candidate_0").fetchSemanticsNodes().isNotEmpty() }
        r.settings.setProviderEnabled(OpenLibrary.ID, false)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("online_dialog").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun theDisclosureConfirmWorksFromTheKeyboardAndTheGamepad() {
        val r = rig(); showDetails(r)
        clickVisible("find_metadata_online_action")
        rule.onNodeWithTag("online_cancel").assertIsDisplayed()
        gamepadKey(KeyEvent.KEYCODE_DPAD_DOWN) // a controller key switches the window to keyboard mode; the dialog then shows its focus
        // Initial focus is Cancel, never the consent button; the first A dismisses without enabling anything.
        rule.onNodeWithTag("online_cancel").assertIsFocused()
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        rule.onNodeWithTag("online_dialog").assertDoesNotExist()
        assertFalse(r.settings.providerEnabled(OpenLibrary.ID)); assertTrue(r.http.requests.isEmpty())
        // Reopen: B/Back also dismisses safely.
        clickVisible("find_metadata_online_action")
        rule.onNodeWithTag("online_enable_search").assertIsDisplayed()
        gamepadKey(KeyEvent.KEYCODE_BUTTON_B)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("online_dialog").fetchSemanticsNodes().isEmpty() }
        assertFalse(r.settings.providerEnabled(OpenLibrary.ID)); assertTrue(r.http.requests.isEmpty())
        // Reopen: focus moves from Cancel to Enable and search, and A there persists consent before the lookup.
        clickVisible("find_metadata_online_action")
        rule.onNodeWithTag("online_cancel").assertIsDisplayed()
        gamepadKey(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onNodeWithTag("online_cancel").assertIsFocused()
        gamepadKey(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.onNodeWithTag("online_enable_search").assertIsFocused()
        gamepadKey(KeyEvent.KEYCODE_BUTTON_A)
        rule.waitUntil(10_000) { r.settings.providerEnabled(OpenLibrary.ID) }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("online_candidate_0").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("one activation, one lookup", 1, r.http.requests.count { it.contains("/isbn/") })
    }

    @Test fun settingsShowsBothSwitchesOffAndTheProviderSwitchNeedsTheGlobalOne() {
        val r = rig()
        rule.setContent {
            CompositionLocalProvider(LocalOnlineMetadata provides r.access) { ShelfTheme(ThemeId.CLASSIC) { SettingsScreen(ThemeId.CLASSIC, null, {}) } }
        }
        rule.onNodeWithTag("online_master_switch").performScrollTo().assertIsOff()
        rule.onNodeWithTag("online_openlibrary_switch").performScrollTo().assertIsOff().assertIsNotEnabled()
        clickVisible("online_master_switch")
        rule.onNodeWithTag("online_openlibrary_switch").performScrollTo().assertIsDisplayed().assertIsEnabled()
        clickVisible("online_openlibrary_switch"); rule.onNodeWithTag("online_openlibrary_switch").assertIsOn()
        assertTrue(r.settings.providerEnabled(OpenLibrary.ID))
        clickVisible("online_master_switch")
        assertFalse(r.settings.onlineEnabled)
        assertTrue("no request from Settings", r.http.requests.isEmpty())
    }
}
