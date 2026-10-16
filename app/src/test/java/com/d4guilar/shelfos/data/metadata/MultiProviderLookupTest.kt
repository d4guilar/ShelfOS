// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.core.database.LocalCursor
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** The orchestration over fakes: eligible providers only, ISBN first, bounded search, isolation, safe application. */
class MultiProviderLookupTest {
    private val isbn13 = PublicationIdentifier(IdentifierType.ISBN_13, "9780306406157")
    private val isbn10 = PublicationIdentifier(IdentifierType.ISBN_10, "0306406152")

    private class Store : MetadataStore {
        val items = LinkedHashMap<String, ResolvedMetadata>()
        override suspend fun resolved(id: String) = items[id]
        override suspend fun apply(id: String, proposals: List<Proposed>, analysisVersion: Int?): Boolean {
            val c = items[id] ?: return false; items[id] = resolveMetadata(c, proposals); return true
        }
        override fun observe(id: String): Flow<StoredMetadata?> = emptyFlow()
        override suspend fun pendingLocal(version: Int, after: LocalCursor?, limit: Int) = emptyList<LocalCursor>()
    }

    private class Fake(override val id: String, override val categories: Set<MediaCategory> = setOf(MediaCategory.BOOK),
        override val capabilities: Set<MetadataCapability> = setOf(MetadataCapability.IDENTIFIER_LOOKUP, MetadataCapability.SEARCH),
        val byId: List<MetadataCandidate> = emptyList(), val bySearch: List<MetadataCandidate> = emptyList(), val failure: Exception? = null) : MetadataProvider {
        val lookups = ArrayList<PublicationIdentifier>(); var searches = 0
        override suspend fun lookup(identifier: PublicationIdentifier): List<MetadataCandidate> { lookups += identifier; failure?.let { throw it }; return byId }
        override suspend fun search(query: MetadataQuery): List<MetadataCandidate> { searches++; failure?.let { throw it }; return bySearch }
    }

    private fun item(category: MediaCategory = MediaCategory.BOOK) =
        LibraryItem("i", "i", category = category, sourceUri = "test:i", format = PublicationFormat.EPUB, fileName = "i.epub", byteSize = 1)

    private fun store(title: String = "i", origin: MetadataOrigin = MetadataOrigin.FILENAME_INFERENCE, ids: List<PublicationIdentifier> = emptyList(), creator: String = "") = Store().apply {
        items["i"] = ResolvedMetadata(title, creator, MetadataValues(identifiers = ids),
            mapOf(MetadataField.TITLE to FieldProvenance(origin), MetadataField.CREATOR to FieldProvenance(if (creator.isBlank()) MetadataOrigin.UNKNOWN else MetadataOrigin.EMBEDDED),
                MetadataField.IDENTIFIERS to FieldProvenance(MetadataOrigin.EMBEDDED)))
    }
    private fun match(p: String, ref: String = "e/$p") = MetadataCandidate(p, ref, "The Real Title", "Jane Author", MediaCategory.BOOK,
        MetadataValues(publisher = "Press $p", identifiers = listOf(isbn13)), listOf(CoverCandidate(p, "cover/$p", 1200, 1800)))
    private fun service(store: MetadataStore, vararg ps: Fake, settings: ProviderSettings = FakeSettings(enableAll = true),
        retry: RetryPolicy = RetryPolicy(maxAttempts = 1), rate: RatePolicy = RatePolicy.perSecond(1_000)): MetadataEnrichmentService {
        val reg = ProviderRegistry(ps.map { RegisteredProvider(testDescriptor(it.id, it.categories, it.capabilities, rate = rate, retry = retry), it) }, settings)
        return MetadataEnrichmentService(ProviderLookup(reg, ProviderPolicyEngine({ System.nanoTime() / 1_000_000 })), store) { 7 }
    }

    @Test fun zeroActiveProvidersMeansNoOnlineWorkAndNoChange() = runBlocking {
        val st = store(ids = listOf(isbn13)); val before = st.items.getValue("i")
        val p = Fake("a", byId = listOf(match("a")))
        for (s in listOf(FakeSettings(onlineEnabled = false, enableAll = true), FakeSettings(), DisabledProviderSettings)) {
            assertEquals(MetadataEnrichmentService.Outcome.SKIPPED, service(st, p, settings = s).enrich(item()).outcome)
        }
        assertEquals(MetadataEnrichmentService.Outcome.SKIPPED, MetadataEnrichmentService(ProviderLookup(ProviderRegistry(), ProviderPolicyEngine({ 0 })), st).enrich(item()).outcome)
        assertTrue(p.lookups.isEmpty()); assertEquals(0, p.searches)
        assertEquals(before, st.items.getValue("i"))
    }

    @Test fun anIsbn10IsLookedUpAsIsbn13AndOnlyWhenAnIsbnExistsIsSearchSkipped() = runBlocking {
        val st = store(ids = listOf(isbn10))
        val p = Fake("a", byId = emptyList(), bySearch = listOf(match("a")))
        val r = service(st, p).enrich(item())
        assertEquals(listOf(isbn13), p.lookups)
        assertEquals("an item with an ISBN is never title-searched", 0, p.searches)
        assertEquals(MetadataEnrichmentService.Outcome.NO_MATCH, r.outcome)
    }

    @Test fun anItemWithoutAnIsbnGetsABoundedSearchAndNoLookup() = runBlocking {
        val st = store(title = "The Real Title", origin = MetadataOrigin.EMBEDDED, creator = "Jane Author")
        val candidates = (1..30).map { match("a", "s$it").copy(values = MetadataValues(publisher = "P$it")) }
        val p = Fake("a", bySearch = candidates)
        val r = service(st, p).enrich(item())
        assertTrue(p.lookups.isEmpty()); assertEquals(1, p.searches)
        assertEquals("at most the lookup's result cap is considered", 10, r.ranking!!.ranked.size)
        assertEquals(MetadataEnrichmentService.Outcome.NEEDS_REVIEW, r.outcome)
    }

    @Test fun documentsAreNeverTitleSearched() = runBlocking {
        val st = store(); val p = Fake("d", categories = setOf(MediaCategory.DOCUMENT), bySearch = listOf(match("d")))
        service(st, p).enrich(item(MediaCategory.DOCUMENT))
        assertEquals(0, p.searches)
        assertTrue(ProviderLookup(registryOf(listOf(p)), ProviderPolicyEngine({ 0 })).bySearch(MetadataQuery(MediaCategory.DOCUMENT, title = "x"), { false }).candidates.isEmpty())
    }

    @Test fun onlyCapableProvidersAreQueriedAndTheFirstSafeMatchStopsTheRest() = runBlocking {
        val st = store(ids = listOf(isbn13))
        val searchOnly = Fake("s", capabilities = setOf(MetadataCapability.SEARCH))
        val comics = Fake("c", categories = setOf(MediaCategory.COMIC), byId = listOf(match("c")))
        val first = Fake("a", byId = listOf(match("a"))); val second = Fake("b", byId = listOf(match("b")))
        val r = service(st, searchOnly, comics, first, second).enrich(item())
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome)
        assertTrue(searchOnly.lookups.isEmpty() && comics.lookups.isEmpty())
        assertEquals(1, first.lookups.size); assertTrue("not queried unnecessarily", second.lookups.isEmpty())
        assertEquals("a", st.items.getValue("i").provenance.getValue(MetadataField.PUBLISHER).providerId)
    }

    @Test fun exhaustiveLookupCorroboratesAcrossProvidersWithoutChangingTheTier() = runBlocking {
        val st = store(ids = listOf(isbn13))
        val r = service(st, Fake("a", byId = listOf(match("a"))), Fake("b", byId = listOf(match("b")))).enrich(item(), exhaustive = true)
        val e = r.ranking!!.apply!!
        assertEquals(setOf("a", "b"), e.corroborating)
        assertEquals(setOf(ProviderRef("a", "e/a"), ProviderRef("b", "e/b")), e.cluster.refs.toSet())
        assertEquals(MatchConfidence.VERY_HIGH, e.evidence.tier)
        assertEquals("the higher-priority provider supplies the applied values", "Press a", st.items.getValue("i").values.publisher)
        assertEquals("covers are references only, never applied", 2, e.cluster.covers.size)
    }

    @Test fun oneProviderFailingNeverStopsAnotherAndIsReported() = runBlocking {
        val st = store(ids = listOf(isbn13))
        val r = service(st, Fake("down", failure = ProviderException(ProviderFailureKind.TIMEOUT)), Fake("up", byId = listOf(match("up")))).enrich(item())
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome)
        assertEquals(listOf(ProviderFailure("down", ProviderFailureKind.TIMEOUT, null)), r.failures)
    }

    @Test fun noResultsAndFailedLookupsAreDistinguishable() = runBlocking {
        val st = store(ids = listOf(isbn13))
        val empty = service(st, Fake("a")).enrich(item())
        assertEquals(MetadataEnrichmentService.Outcome.NO_MATCH, empty.outcome); assertTrue(empty.failures.isEmpty())
        val failed = service(store(ids = listOf(isbn13)), Fake("a", failure = IOException()), Fake("b", failure = ProviderException(ProviderFailureKind.SERVER_FAILURE))).enrich(item())
        assertEquals(MetadataEnrichmentService.Outcome.LOOKUP_FAILED, failed.outcome)
        assertEquals(setOf(ProviderFailureKind.OFFLINE, ProviderFailureKind.SERVER_FAILURE), failed.failures.map { it.kind }.toSet())
    }

    @Test fun malformedCandidatesAreIsolatedFromGoodOnes() = runBlocking {
        val st = store(ids = listOf(isbn13))
        val liar = Fake("liar", byId = listOf(match("someone-else")))                     // claims another provider's id
        val blank = Fake("blank", byId = listOf(match("blank", ref = " ")))
        val r = service(st, liar, blank, Fake("good", byId = listOf(match("good")))).enrich(item())
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome)
        assertEquals(setOf("liar", "blank"), r.failures.filter { it.kind == ProviderFailureKind.MALFORMED }.map { it.providerId }.toSet())
        assertEquals("good", r.ranking!!.apply!!.representative.providerId)
    }

    @Test fun userAndEmbeddedMetadataAreNeverOverwritten() = runBlocking {
        val st = store(title = "My Title", origin = MetadataOrigin.USER, ids = listOf(isbn13), creator = "Embedded Author")
        service(st, Fake("a", byId = listOf(match("a").copy(title = "My Title", creator = "Embedded Author")))).enrich(item())
        val m = st.items.getValue("i")
        assertEquals("My Title", m.title); assertEquals(MetadataOrigin.USER, m.origin(MetadataField.TITLE))
        assertEquals("Embedded Author", m.creator); assertEquals(MetadataOrigin.EMBEDDED, m.origin(MetadataField.CREATOR))
        assertEquals("Press a", m.values.publisher)
    }

    @Test fun aRemovedItemIsNeverRecreatedAndNoProviderIsCalled() = runBlocking {
        val p = Fake("a", byId = listOf(match("a")))
        assertEquals(MetadataEnrichmentService.Outcome.SKIPPED, service(Store(), p).enrich(item()).outcome)
        assertTrue(p.lookups.isEmpty())
        val st = store(ids = listOf(isbn13))
        val vanishing = object : Fake2(st) {}
        assertEquals(MetadataEnrichmentService.Outcome.SKIPPED, service(vanishing, Fake("b", byId = listOf(match("b")))).enrich(item()).outcome)
        assertFalse("not resurrected", vanishing.items.containsKey("i"))
    }

    /** The item disappears between lookup and write: `apply` reports it gone and nothing is created. */
    private open class Fake2(private val inner: Store) : MetadataStore by inner {
        val items get() = inner.items
        override suspend fun apply(id: String, proposals: List<Proposed>, analysisVersion: Int?): Boolean { inner.items.remove(id); return inner.apply(id, proposals, analysisVersion) }
    }

    @Test fun aRateLimitedProviderIsNotRetriedOutOfItsBudgetAndIsReportedNotHidden() = runBlocking {
        val st = store(ids = listOf(isbn13))
        val limited = Fake("a", failure = ProviderException(ProviderFailureKind.RATE_LIMITED, 600_000))
        val s = service(st, limited, Fake("b", byId = listOf(match("b"))))
        val first = s.enrich(item())
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, first.outcome)
        assertEquals(ProviderFailureKind.RATE_LIMITED, first.failures.single().kind)
        val second = s.enrich(item(), exhaustive = true)
        assertEquals("the breaker keeps the provider quiet", ProviderFailureKind.CIRCUIT_OPEN, second.failures.single().kind)
        assertEquals(1, limited.lookups.size)
    }
}
