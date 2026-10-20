// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import com.d4guilar.shelfos.data.metadata.*
import com.d4guilar.shelfos.data.metadata.openlibrary.*
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The consent and review state machine: nothing is sent before the user enables Open Library, and disabling discards work. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMetadataViewModelTest {
    private val isbn = "9780140328721"
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Rig(val http: FakeHttp, val store: InMemoryStore, val settings: OnlineMetadataSettings, val vm: OnlineMetadataViewModel)

    private fun rig(enabled: Boolean, handler: suspend (String) -> com.d4guilar.shelfos.core.network.HttpResponse = defaultHandler()): Rig {
        val http = FakeHttp(handler); val store = InMemoryStore()
        val settings = OnlineMetadataSettings(com.d4guilar.shelfos.data.metadata.MapBooleanStore()).also { if (enabled) it.enableProvider(OpenLibrary.ID) }
        val registry = ProviderRegistry(listOf(RegisteredProvider(OpenLibrary.descriptor(), OpenLibraryProvider(http))), settings)
        val manual = ManualMetadataLookup(ProviderLookup(registry, ProviderPolicyEngine({ dispatcher.scheduler.currentTime }, { kotlinx.coroutines.delay(it) })), store)
        return Rig(http, store, settings, OnlineMetadataViewModel(OnlineMetadataAccess(settings, manual)))
    }

    private fun defaultHandler(): suspend (String) -> com.d4guilar.shelfos.core.network.HttpResponse = { url ->
        when {
            url.contains("/isbn/") -> json(200, editionJson())
            url.contains("/authors/") -> json(200, authorJson("Roald Dahl"))
            url.contains("/search.json") -> json(200, """{"docs":[{"key":"/works/OL45804W","title":"Matilda","author_name":["Roald Dahl"]}]}""")
            else -> json(404, "{}")
        }
    }

    private fun TestScope.settle() = advanceUntilIdle()

    @Test fun aFreshInstallStartsClosedAndNavigationAloneSendsNothing() = runTest(dispatcher) {
        val r = rig(enabled = false); r.store.addBook(isbn = isbn)
        settle()
        assertEquals(OnlineMetadataUi.Closed, r.vm.ui.value); assertTrue(r.http.requests.isEmpty())
        assertFalse(r.settings.onlineEnabled); assertFalse(r.settings.providerEnabled(OpenLibrary.ID))
    }

    @Test fun choosingFindWhileDisabledShowsTheDisclosureAndSendsNoRequest() = runTest(dispatcher) {
        val r = rig(enabled = false); r.store.addBook(isbn = isbn)
        r.vm.open(bookItem()); settle()
        assertEquals(OnlineMetadataUi.Disclosure, r.vm.ui.value); assertTrue(r.http.requests.isEmpty())
        assertFalse("still disabled", r.settings.providerEnabled(OpenLibrary.ID))
    }

    @Test fun cancellingTheDisclosureLeavesTheProviderDisabledAndSilent() = runTest(dispatcher) {
        val r = rig(enabled = false); r.store.addBook(isbn = isbn)
        r.vm.open(bookItem()); r.vm.close(); settle()
        assertEquals(OnlineMetadataUi.Closed, r.vm.ui.value); assertFalse(r.settings.onlineEnabled); assertTrue(r.http.requests.isEmpty())
        r.vm.enableAndSearch(); settle()                        // not in the disclosure state any more: ignored
        assertFalse(r.settings.onlineEnabled); assertTrue(r.http.requests.isEmpty())
    }

    @Test fun enableAndSearchTurnsBothSwitchesOnThenRunsTheOriginalIsbnLookup() = runTest(dispatcher) {
        val r = rig(enabled = false); r.store.addBook(isbn = isbn)
        r.vm.open(bookItem()); r.vm.enableAndSearch(); settle()
        assertTrue(r.settings.onlineEnabled); assertTrue(r.settings.providerEnabled(OpenLibrary.ID))
        val results = r.vm.ui.value as OnlineMetadataUi.Results
        assertEquals(LookupMode.ISBN, results.mode); assertEquals(MatchConfidence.VERY_HIGH, results.candidates.single().tier)
        assertTrue(r.http.requests.first().contains("/isbn/$isbn.json"))
        assertEquals("nothing is applied automatically", 0, r.store.writes)
    }

    @Test fun anEnabledProviderLooksUpImmediatelyAndApplyIsExplicit() = runTest(dispatcher) {
        val r = rig(enabled = true); r.store.addBook(isbn = isbn); val item = bookItem()
        r.vm.open(item); settle()
        val results = r.vm.ui.value as OnlineMetadataUi.Results
        assertEquals(0, r.store.writes)
        r.vm.apply(results.candidates.single()); settle()
        assertTrue(r.vm.ui.value is OnlineMetadataUi.Done); assertEquals("Puffin", r.store.items.getValue("b1").values.publisher)
    }

    @Test fun aBookWithoutAnIsbnSearchesByTitleAndAuthorInTheSameUserAction() = runTest(dispatcher) {
        val r = rig(enabled = true); r.store.addBook(creator = "Roald Dahl")
        r.vm.open(bookItem()); settle()
        val results = r.vm.ui.value as OnlineMetadataUi.Results
        assertEquals(LookupMode.SEARCH, results.mode); assertTrue(results.candidates.single().workLevel)
        assertTrue(r.http.requests.single().contains("/search.json"))
    }

    @Test fun anIsbnMissOffersAnExplicitSearchAndDoesNotRunItAutomatically() = runTest(dispatcher) {
        val r = rig(enabled = true, { json(404, "{}") }); r.store.addBook(isbn = isbn)
        r.vm.open(bookItem()); settle()
        assertEquals(OnlineMetadataUi.Empty(LookupMode.ISBN), r.vm.ui.value); assertTrue(r.http.requests.none { it.contains("/search.json") })
        r.vm.searchByTitle(); settle()
        assertTrue(r.http.requests.any { it.contains("/search.json") })
    }

    @Test fun switchingTheProviderOffWhileARequestIsInFlightCancelsItAndDiscardsTheResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val r = rig(enabled = true, { url -> gate.await(); defaultHandler()(url) }); r.store.addBook(isbn = isbn)
        r.vm.open(bookItem()); runCurrent()
        assertTrue(r.vm.ui.value is OnlineMetadataUi.Loading)
        r.settings.setProviderEnabled(OpenLibrary.ID, false); runCurrent()
        gate.complete(Unit); settle()
        assertEquals(OnlineMetadataUi.Closed, r.vm.ui.value); assertEquals(0, r.store.writes)
    }

    @Test fun switchingTheGlobalSwitchOffWhileResultsAreShownDiscardsThem() = runTest(dispatcher) {
        val r = rig(enabled = true); r.store.addBook(isbn = isbn)
        r.vm.open(bookItem()); settle()
        assertTrue(r.vm.ui.value is OnlineMetadataUi.Results)
        r.settings.setOnlineEnabled(false); settle()
        assertEquals(OnlineMetadataUi.Closed, r.vm.ui.value)
    }

    @Test fun reEnablingWorksWithoutReinstalling() = runTest(dispatcher) {
        val r = rig(enabled = true); r.store.addBook(isbn = isbn)
        r.settings.setProviderEnabled(OpenLibrary.ID, false)
        r.vm.open(bookItem()); settle()
        assertEquals(OnlineMetadataUi.Disclosure, r.vm.ui.value)
        r.vm.enableAndSearch(); settle()
        assertTrue(r.vm.ui.value is OnlineMetadataUi.Results)
    }

    @Test fun failuresAreShownAndRetryRunsAgain() = runTest(dispatcher) {
        var offline = true
        val r = rig(enabled = true, { url -> if (offline) throw java.net.UnknownHostException("x") else defaultHandler()(url) }); r.store.addBook(isbn = isbn)
        r.vm.open(bookItem()); settle()
        assertEquals(OnlineMetadataUi.Error(ProviderFailureKind.OFFLINE, null, LookupMode.ISBN), r.vm.ui.value)
        offline = false; r.vm.retry(); advanceTimeBy(60_000); settle()
        assertTrue(r.vm.ui.value is OnlineMetadataUi.Results)
    }

    @Test fun applyingANonApplicableCandidateDoesNothing() = runTest(dispatcher) {
        val r = rig(enabled = true); r.store.addBook(isbn = isbn)
        r.vm.open(bookItem()); settle()
        val c = (r.vm.ui.value as OnlineMetadataUi.Results).candidates.single()
        r.vm.apply(c.copy(rejected = true)); settle()
        assertEquals(0, r.store.writes)
    }

    @Test fun openingForOtherCategoriesNeverSendsAnything() = runTest(dispatcher) {
        val r = rig(enabled = true); r.store.addBook("m", isbn = isbn)
        r.vm.open(bookItem("m", category = com.d4guilar.shelfos.domain.library.MediaCategory.MANGA)); settle()
        assertEquals(OnlineMetadataUi.Error(ProviderFailureKind.POLICY_DISABLED, null, LookupMode.ISBN), r.vm.ui.value); assertTrue(r.http.requests.isEmpty())
    }
}
