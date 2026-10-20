// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.data.metadata.openlibrary.FakeHttp
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibrary
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibraryProvider
import com.d4guilar.shelfos.data.preferences.BooleanStore
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MapBooleanStore(val map: MutableMap<String, Boolean> = HashMap()) : BooleanStore {
    override fun get(key: String, default: Boolean) = map[key] ?: default
    override fun put(key: String, value: Boolean) { map[key] = value }
}

/** D1-M: the scoped grant is the only way a CONDITIONAL provider activates, and it can never reach covers, other categories or other providers. */
class ScopedAuthorizationTest {
    private val ol = OpenLibrary.descriptor()
    private fun registry(settings: ProviderSettings, d: ProviderDescriptor = ol) = ProviderRegistry(listOf(RegisteredProvider(d, OpenLibraryProvider(FakeHttp()))), settings)
    private fun on() = OnlineMetadataSettings(MapBooleanStore()).also { it.enableProvider(OpenLibrary.ID) }
    private fun ProviderRegistry.elig(d: ProviderDescriptor, c: MediaCategory, cap: MetadataCapability) =
        eligibility(RegisteredProvider(d, OpenLibraryProvider(FakeHttp())), c, cap)

    @Test fun freshSettingsAreOffAndNothingIsEligible() {
        val fresh = OnlineMetadataSettings(MapBooleanStore())
        assertFalse(fresh.onlineEnabled); assertFalse(fresh.providerEnabled(OpenLibrary.ID))
        assertEquals(Eligibility.ONLINE_DISABLED, registry(fresh).elig(ol, MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP))
        assertTrue(registry(fresh).active(MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP).isEmpty())
    }

    @Test fun bothTheGlobalAndTheProviderSwitchAreRequired() {
        val s = OnlineMetadataSettings(MapBooleanStore())
        s.setOnlineEnabled(true)
        assertEquals(Eligibility.PROVIDER_DISABLED, registry(s).elig(ol, MediaCategory.BOOK, MetadataCapability.SEARCH))
        s.setOnlineEnabled(false); s.setProviderEnabled(OpenLibrary.ID, true)
        assertEquals(Eligibility.ONLINE_DISABLED, registry(s).elig(ol, MediaCategory.BOOK, MetadataCapability.SEARCH))
        s.setOnlineEnabled(true)
        assertEquals(Eligibility.ACTIVE, registry(s).elig(ol, MediaCategory.BOOK, MetadataCapability.SEARCH))
    }

    @Test fun onlyBookIdentifierLookupAndSearchAreEligibleUnderTheGrant() {
        val r = registry(on())
        assertEquals(Eligibility.ACTIVE, r.elig(ol, MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP))
        assertEquals(Eligibility.ACTIVE, r.elig(ol, MediaCategory.BOOK, MetadataCapability.SEARCH))
        assertEquals("covers are never eligible", Eligibility.NOT_APPROVED, r.elig(ol, MediaCategory.BOOK, MetadataCapability.COVERS))
        for (c in listOf(MediaCategory.MANGA, MediaCategory.COMIC, MediaCategory.DOCUMENT))
            for (cap in MetadataCapability.entries) assertEquals("$c/$cap", Eligibility.NOT_APPROVED, r.elig(ol, c, cap))
        assertTrue(r.active(MediaCategory.MANGA, MetadataCapability.IDENTIFIER_LOOKUP).isEmpty())
    }

    @Test fun aGrantCanNeverCoverArtwork() {
        assertThrows(IllegalArgumentException::class.java) { ScopedGrant(OpenLibrary.ID, "D1-M", setOf(MediaCategory.BOOK), setOf(MetadataCapability.COVERS)) }
        assertThrows(IllegalArgumentException::class.java) { ScopedGrant(OpenLibrary.ID, "", setOf(MediaCategory.BOOK), setOf(MetadataCapability.SEARCH)) }
        assertThrows(IllegalArgumentException::class.java) { ScopedGrant(OpenLibrary.ID, "D1-M", emptySet(), setOf(MetadataCapability.SEARCH)) }
    }

    @Test fun otherConditionalAndRejectedProvidersStayInactiveWhateverTheToggles() {
        val settings = FakeSettings(enableAll = true)
        val conditionalNoGrant = ol.copy(grant = null)
        val rejectedWithGrant = ol.copy(termsStatus = TermsStatus.REJECTED)
        val otherCategoryGrant = ol.copy(grant = ScopedGrant(OpenLibrary.ID, "X", setOf(MediaCategory.MANGA), setOf(MetadataCapability.SEARCH)))
        for (d in listOf(conditionalNoGrant, rejectedWithGrant, otherCategoryGrant))
            assertEquals(Eligibility.NOT_APPROVED, registry(settings, d).elig(d, MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP))
        // A grant narrower than the descriptor narrows the provider; it does not widen it.
        val narrow = ol.copy(grant = ScopedGrant(OpenLibrary.ID, "D1-M", setOf(MediaCategory.BOOK), setOf(MetadataCapability.IDENTIFIER_LOOKUP)))
        assertEquals(Eligibility.NOT_APPROVED, registry(settings, narrow).elig(narrow, MediaCategory.BOOK, MetadataCapability.SEARCH))
    }

    @Test fun theGrantIsBoundToItsProvider() = runTest {
        assertThrows(IllegalArgumentException::class.java) { ScopedGrant(" ", "D1-M", setOf(MediaCategory.BOOK), setOf(MetadataCapability.SEARCH)) }
        val copied = testDescriptor("other", terms = TermsStatus.CONDITIONAL).copy(grant = ol.grant)
        assertFalse(copied.authorizes(MediaCategory.BOOK, MetadataCapability.SEARCH))
        val foreignGrant = ol.copy(grant = ol.grant!!.copy(providerId = "other"))
        assertEquals(Eligibility.NOT_APPROVED, registry(FakeSettings(enableAll = true), foreignGrant).elig(foreignGrant, MediaCategory.BOOK, MetadataCapability.SEARCH))
        val o = testScheduler.engine().execute(copied, MediaCategory.BOOK, MetadataCapability.SEARCH) { 1 }
        assertTrue(o is ProviderPolicyEngine.Outcome.Failure)
        val rejected = copied.copy(id = OpenLibrary.ID, termsStatus = TermsStatus.REJECTED)
        assertFalse(rejected.authorizes(MediaCategory.BOOK, MetadataCapability.SEARCH))
    }

    @Test fun authorizesIsTheOneRuleForRegistryAndEngine() {
        assertTrue(ol.authorizes(MediaCategory.BOOK, MetadataCapability.SEARCH))
        assertFalse(ol.authorizes(MediaCategory.BOOK, MetadataCapability.COVERS))
        assertFalse(ol.authorizes(MediaCategory.MANGA, MetadataCapability.SEARCH))
        assertTrue(testDescriptor("p").authorizes(MediaCategory.BOOK, MetadataCapability.SEARCH))
        assertFalse(testDescriptor("p", terms = TermsStatus.CONDITIONAL).authorizes(MediaCategory.BOOK, MetadataCapability.SEARCH))
        assertFalse(testDescriptor("p", terms = TermsStatus.REJECTED).authorizes(MediaCategory.BOOK, MetadataCapability.SEARCH))
    }

    @Test fun theEngineEnforcesTheSameBoundaryAndRunsNothingOutsideIt() = runTest {
        val engine = testScheduler.engine()
        var ran = 0
        suspend fun run(d: ProviderDescriptor, c: MediaCategory?, cap: MetadataCapability?) = engine.execute(d, c, cap) { ran++ }
        assertTrue(run(ol, MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP) is ProviderPolicyEngine.Outcome.Success)
        assertEquals(1, ran)
        for ((c, cap) in listOf<Pair<MediaCategory?, MetadataCapability?>>(null to null, MediaCategory.BOOK to null, null to MetadataCapability.SEARCH, MediaCategory.BOOK to MetadataCapability.COVERS,
            MediaCategory.MANGA to MetadataCapability.SEARCH, MediaCategory.DOCUMENT to MetadataCapability.IDENTIFIER_LOOKUP)) {
            val o = run(ol, c, cap)
            assertEquals("$c/$cap", ProviderFailureKind.POLICY_DISABLED, (o as ProviderPolicyEngine.Outcome.Failure).kind)
        }
        assertEquals("nothing outside the grant ran", 1, ran)
        val rejected = engine.execute(ol.copy(termsStatus = TermsStatus.REJECTED), MediaCategory.BOOK, MetadataCapability.SEARCH) { ran++ }
        assertTrue(rejected is ProviderPolicyEngine.Outcome.Failure); assertEquals(1, ran)
        // The pre-existing call shape still works for an APPROVED provider.
        assertTrue(engine.execute(testDescriptor("fake")) { ran++ } is ProviderPolicyEngine.Outcome.Success)
    }

    @Test fun switchingTheProviderOffMidLookupStopsFurtherRequests() = runTest {
        val settings = on()
        val calls = ArrayList<String>()
        val provider = object : MetadataProvider {
            override val id = OpenLibrary.ID; override val categories = setOf(MediaCategory.BOOK)
            override val capabilities = setOf(MetadataCapability.IDENTIFIER_LOOKUP, MetadataCapability.SEARCH)
            override suspend fun lookup(identifier: PublicationIdentifier): List<MetadataCandidate> { calls += identifier.value; settings.setProviderEnabled(OpenLibrary.ID, false); return emptyList() }
            override suspend fun search(query: MetadataQuery) = emptyList<MetadataCandidate>()
        }
        val lookup = ProviderLookup(ProviderRegistry(listOf(RegisteredProvider(ol, provider)), settings), testScheduler.engine())
        val query = MetadataQuery(MediaCategory.BOOK, listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9780140328721"), PublicationIdentifier(IdentifierType.ISBN_13, "9780306406157")))
        val batch = lookup.byIdentifier(query)
        assertEquals("the second ISBN was never sent", 1, calls.size)
        assertEquals(ProviderFailureKind.POLICY_DISABLED, batch.failures.single().kind)
    }

    @Test fun settingsPersistAcrossInstancesAndIgnoreUnknownProviders() {
        val store = MapBooleanStore()
        OnlineMetadataSettings(store).apply { enableProvider(OpenLibrary.ID); setProviderEnabled("stranger", true) }
        val reloaded = OnlineMetadataSettings(store)
        assertTrue(reloaded.onlineEnabled); assertTrue(reloaded.providerEnabled(OpenLibrary.ID)); assertFalse(reloaded.providerEnabled("stranger"))
        reloaded.setProviderEnabled(OpenLibrary.ID, false)
        assertFalse(OnlineMetadataSettings(store).providerEnabled(OpenLibrary.ID)); assertTrue(OnlineMetadataSettings(store).onlineEnabled)
        reloaded.setOnlineEnabled(false)
        assertFalse(OnlineMetadataSettings(store).onlineEnabled)
        assertFalse(reloaded.hasToken(OpenLibrary.ID)); assertFalse(reloaded.disabledByPolicy(OpenLibrary.ID))
    }

    @Test fun flowsReflectChangesImmediately() {
        val s = OnlineMetadataSettings(MapBooleanStore())
        assertFalse(s.onlineFlow.value); assertTrue(s.providersFlow.value.isEmpty())
        s.enableProvider(OpenLibrary.ID)
        assertTrue(s.onlineFlow.value); assertEquals(setOf(OpenLibrary.ID), s.providersFlow.value)
        s.setProviderEnabled(OpenLibrary.ID, false)
        assertTrue(s.providersFlow.value.isEmpty())
    }
}
