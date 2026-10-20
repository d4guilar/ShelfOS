// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.core.network.HttpResponse
import com.d4guilar.shelfos.data.metadata.openlibrary.*
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The manual path end to end over the real policy engine, registry, matcher and precedence rule, with fake HTTP. Nothing is written until apply. */
class ManualMetadataLookupTest {
    private val isbn = "9780140328721"
    private class Rig(val http: FakeHttp, val store: InMemoryStore, val settings: OnlineMetadataSettings, val manual: ManualMetadataLookup)

    private fun TestScope.rig(handler: suspend (String) -> HttpResponse = standard(), enabled: Boolean = true, clock: Long = 5_000): Rig {
        val http = FakeHttp(handler); val store = InMemoryStore()
        val settings = OnlineMetadataSettings(MapBooleanStore()).also { if (enabled) it.enableProvider(OpenLibrary.ID) }
        val registry = ProviderRegistry(listOf(RegisteredProvider(OpenLibrary.descriptor(), OpenLibraryProvider(http) { 1_000_000L })), settings)
        return Rig(http, store, settings, ManualMetadataLookup(ProviderLookup(registry, testScheduler.engine()), store) { clock })
    }

    private fun standard(edition: String = editionJson(), searchDocs: String = """{"docs":[{"key":"/works/OL45804W","title":"Matilda","author_name":["Roald Dahl"]}]}"""): suspend (String) -> HttpResponse = { url ->
        when {
            url.contains("/isbn/") -> json(200, edition)
            url.contains("/authors/") -> json(200, authorJson("Roald Dahl"))
            url.contains("/search.json") -> json(200, searchDocs)
            else -> json(404, "{}")
        }
    }

    private suspend fun Rig.found(item: com.d4guilar.shelfos.domain.library.LibraryItem, mode: LookupMode = LookupMode.ISBN) = (manual.find(item, mode) as LookupOutcome.Found).candidates

    @Test fun anExactIsbnEditionIsVeryHighAndPreviewsExactlyWhatWouldChangeWithoutWriting() = runTest {
        val r = rig(); r.store.addBook(isbn = isbn)
        val c = r.found(bookItem()).single()
        assertEquals(MatchConfidence.VERY_HIGH, c.tier); assertFalse(c.rejected); assertTrue(c.applicable); assertTrue(MatchSignal.IDENTIFIER in c.agreements); assertFalse(c.workLevel)
        assertEquals(setOf(MetadataField.CREATOR, MetadataField.PUBLISHER, MetadataField.PUBLISHED_DATE, MetadataField.LANGUAGE), c.changes.map { it.field }.toSet())
        assertEquals("a preview performs no database write", 0, r.store.writes)
        assertEquals("exactly: isbn, one author", 2, r.http.requests.size)
    }

    @Test fun applyWritesOnlyAfterConfirmationWithTheMatcherEvidenceAsProvenance() = runTest {
        val r = rig(); r.store.addBook(isbn = isbn)
        val item = bookItem(); val c = r.found(item).single()
        val out = r.manual.apply(item, c) as ApplyOutcome.Applied
        assertTrue(MetadataField.PUBLISHER in out.changed)
        val m = r.store.items.getValue("b1")
        assertEquals("Roald Dahl", m.creator); assertEquals("Puffin", m.values.publisher); assertEquals("en", m.values.language)
        val p = m.provenance.getValue(MetadataField.PUBLISHER)
        assertEquals(MetadataOrigin.EXACT_IDENTIFIER_MATCH, p.origin); assertEquals(OpenLibrary.ID, p.providerId); assertEquals("OL7353617M", p.providerRef)
        assertEquals(MatchConfidence.VERY_HIGH, p.confidence); assertEquals(5_000L, p.resolvedAt)
        assertFalse("a chosen remote candidate is not USER-authored", m.provenance.values.any { it.origin == MetadataOrigin.USER })
        assertEquals(1, r.store.writes)
    }

    @Test fun userAndEmbeddedFieldsAreNeverReplacedAndAnAllUserBookReportsNothingChanged() = runTest {
        val r = rig(); val item = bookItem()
        r.store.addBook(isbn = isbn, creator = "Embedded Author", embedded = MetadataValues(publisher = "Embedded Press"))
        r.store.items["b1"] = r.store.items.getValue("b1").let { it.copy(provenance = it.provenance + (MetadataField.LANGUAGE to FieldProvenance(MetadataOrigin.USER)), values = it.values.copy(language = "fr")) }
        val c = r.found(item).single()
        assertFalse(c.changes.any { it.field == MetadataField.PUBLISHER || it.field == MetadataField.CREATOR || it.field == MetadataField.LANGUAGE })
        r.manual.apply(item, c)
        val m = r.store.items.getValue("b1")
        assertEquals("Embedded Press", m.values.publisher); assertEquals("Embedded Author", m.creator); assertEquals("fr", m.values.language)
        assertEquals(MetadataOrigin.USER, m.provenance.getValue(MetadataField.LANGUAGE).origin)
        // A second apply of the same candidate has nothing left to do, and says so.
        assertEquals(ApplyOutcome.NothingChanged, r.manual.apply(item, c))
    }

    @Test fun aStaleCandidateCannotOverwriteAnEditMadeAfterTheLookup() = runTest {
        val r = rig(); val item = bookItem(); r.store.addBook(isbn = isbn)
        val c = r.found(item).single()
        r.store.apply("b1", listOf(Proposed(MetadataField.PUBLISHER, "My Press", FieldProvenance(MetadataOrigin.USER))))
        r.manual.apply(item, c)
        assertEquals("My Press", r.store.items.getValue("b1").values.publisher)
    }

    @Test fun aRemovedPublicationIsNeverRecreated() = runTest {
        val r = rig(); val item = bookItem(); r.store.addBook(isbn = isbn)
        val c = r.found(item).single()
        r.store.items.remove("b1")
        assertEquals(ApplyOutcome.ItemGone, r.manual.apply(item, c)); assertTrue(r.store.items.isEmpty())
        assertEquals(LookupOutcome.ItemGone, r.manual.find(item, LookupMode.ISBN))
    }

    @Test fun nonApplicableCandidatesCanNeverBeApplied() = runTest {
        val r = rig(); val item = bookItem(); r.store.addBook(isbn = isbn)
        val base = r.found(item).single()
        for (bad in listOf(base.copy(rejected = true), base.copy(tier = MatchConfidence.LOW), base.copy(rejected = true, tier = MatchConfidence.LOW))) {
            assertFalse(bad.applicable); assertEquals(ApplyOutcome.NotApplicable, r.manual.apply(item, bad))
        }
        assertEquals(0, r.store.writes)
    }

    @Test fun anIsbnMissIsANoMatchAndNeverFallsBackToATitleSearch() = runTest {
        val r = rig({ json(404, "{}") }); r.store.addBook(isbn = isbn)
        assertEquals(LookupOutcome.NoMatch(LookupMode.ISBN), r.manual.find(bookItem(), LookupMode.ISBN))
        assertTrue(r.http.requests.none { it.contains("/search.json") }); assertEquals(1, r.http.requests.size)
    }

    @Test fun aBookWithoutAnIsbnSendsNothingInIsbnModeAndSearchesOnlyWhenAsked() = runTest {
        val r = rig(); r.store.addBook(creator = "Roald Dahl")
        assertEquals(LookupOutcome.NoIdentifier, r.manual.find(bookItem(), LookupMode.ISBN)); assertTrue(r.http.requests.isEmpty())
        val c = r.found(bookItem(), LookupMode.SEARCH).single()
        assertTrue(r.http.requests.single().startsWith("https://openlibrary.org/search.json?title=Matilda&author=Roald+Dahl"))
        assertTrue(c.workLevel); assertNotEquals("a title match alone is never exact", MatchConfidence.VERY_HIGH, c.tier)
        assertEquals(MatchConfidence.MEDIUM, c.tier); assertTrue(c.agreements.none { it == MatchSignal.IDENTIFIER })
        assertTrue("a work-level result carries no edition facts", c.changes.none { it.field in setOf(MetadataField.PUBLISHER, MetadataField.PUBLISHED_DATE, MetadataField.LANGUAGE, MetadataField.IDENTIFIERS) })
    }

    @Test fun severalSimilarWorksStayReviewableAndNothingIsAppliedAutomatically() = runTest {
        val docs = """{"docs":[{"key":"/works/OL1W","title":"Matilda","author_name":["Roald Dahl"]},{"key":"/works/OL2W","title":"Matilda","author_name":["Roald Dahl"]}]}"""
        val r = rig(standard(searchDocs = docs)); r.store.addBook(creator = "Roald Dahl")
        val list = r.found(bookItem(), LookupMode.SEARCH)
        assertEquals(2, list.size); assertTrue(list.none { it.tier == MatchConfidence.VERY_HIGH })
        assertEquals(0, r.store.writes)
    }

    @Test fun aConflictingIsbnRecordIsDroppedNotAcceptedAndAWrongTitleIsNotApplicable() = runTest {
        val other = rig({ json(200, editionJson(isbn13 = "9780140328738", isbn10 = null)) }); other.store.addBook(isbn = isbn)
        assertEquals(LookupOutcome.NoMatch(LookupMode.ISBN), other.manual.find(bookItem(), LookupMode.ISBN))
        val wrongTitle = rig(standard(editionJson(title = "A Completely Different Book"))); wrongTitle.store.addBook(isbn = isbn, titleOrigin = MetadataOrigin.EMBEDDED)
        val c = wrongTitle.found(bookItem()).single()
        assertNotEquals(MatchConfidence.VERY_HIGH, c.tier); assertTrue(MatchSignal.TITLE in c.contradictions)
    }

    @Test fun onlyBooksAreEverLookedUp() = runTest {
        val r = rig(); r.store.addBook("m1", isbn = isbn)
        for (c in listOf(MediaCategory.MANGA, MediaCategory.COMIC, MediaCategory.DOCUMENT))
            assertEquals(c.name, LookupOutcome.Unavailable, r.manual.find(bookItem("m1", category = c), LookupMode.ISBN))
        assertTrue(r.http.requests.isEmpty())
    }

    @Test fun aDisabledProviderSendsZeroRequests() = runTest {
        val r = rig(enabled = false); r.store.addBook(isbn = isbn)
        assertEquals(LookupOutcome.Unavailable, r.manual.find(bookItem(), LookupMode.ISBN)); assertEquals(LookupOutcome.Unavailable, r.manual.find(bookItem(), LookupMode.SEARCH))
        assertTrue(r.http.requests.isEmpty())
    }

    @Test fun failuresAreIsolatedAndReported() = runTest {
        suspend fun outcome(h: suspend (String) -> HttpResponse): LookupOutcome { val r = rig(h); r.store.addBook(isbn = isbn); return r.manual.find(bookItem(), LookupMode.ISBN) }
        assertEquals(LookupOutcome.Failed(ProviderFailureKind.OFFLINE, null), outcome { throw java.net.UnknownHostException("x") })
        assertEquals(ProviderFailureKind.MALFORMED, (outcome { json(200, "<html>") } as LookupOutcome.Failed).kind)
        assertEquals(ProviderFailureKind.ACCESS_BLOCKED, (outcome { json(403, "") } as LookupOutcome.Failed).kind)
        val limited = outcome { json(429, "", "Retry-After" to "90") } as LookupOutcome.Failed
        assertEquals(ProviderFailureKind.RATE_LIMITED, limited.kind)
    }

    @Test fun aBlockedProviderIsNotCalledAgainUntilTheCooldownPasses() = runTest {
        val r = rig({ json(403, "") }); r.store.addBook(isbn = isbn)
        assertEquals(ProviderFailureKind.ACCESS_BLOCKED, (r.manual.find(bookItem(), LookupMode.ISBN) as LookupOutcome.Failed).kind)
        val calls = r.http.requests.size
        assertEquals(ProviderFailureKind.CIRCUIT_OPEN, (r.manual.find(bookItem(), LookupMode.ISBN) as LookupOutcome.Failed).kind)
        assertEquals("no request while blocked", calls, r.http.requests.size)
    }

    @Test fun aLongRetryAfterSuspendsFurtherRequests() = runTest {
        val r = rig({ json(429, "", "Retry-After" to "600") }); r.store.addBook(isbn = isbn)
        assertEquals(ProviderFailureKind.RATE_LIMITED, (r.manual.find(bookItem(), LookupMode.ISBN) as LookupOutcome.Failed).kind)
        val calls = r.http.requests.size
        assertEquals(ProviderFailureKind.CIRCUIT_OPEN, (r.manual.find(bookItem(), LookupMode.ISBN) as LookupOutcome.Failed).kind)
        assertEquals(calls, r.http.requests.size)
    }

    @Test fun requestsAreAtLeastTwoSecondsApartWithOneInFlight() = runTest {
        val starts = ArrayList<Long>(); var inFlight = 0; var max = 0
        val r = rig({ url ->
            starts += testScheduler.currentTime; inFlight++; max = maxOf(max, inFlight)
            try { kotlinx.coroutines.delay(100); if (url.contains("/authors/")) json(200, authorJson("Roald Dahl")) else json(200, editionJson()) } finally { inFlight-- }
        })
        r.store.addBook(isbn = isbn)
        val a = launch { r.manual.find(bookItem(), LookupMode.ISBN) }
        val b = launch { r.manual.find(bookItem(), LookupMode.ISBN) }
        a.join(); b.join()
        assertEquals(1, max)
        // Whole-lookup admission is paced by the engine (2 s per call); the transport pacer paces the hops within a call.
        assertTrue("second lookup started ${starts[2] - starts[0]} ms after the first: $starts", starts[2] - starts[0] >= 2_000)
    }

    @Test fun cancellingAnInFlightLookupFreesTheProviderForTheNextOne() = runTest {
        val gate = CompletableDeferred<Unit>(); var blocking = true
        val r = rig({ url -> if (blocking) gate.await(); if (url.contains("/authors/")) json(200, authorJson("Roald Dahl")) else json(200, editionJson()) }); r.store.addBook(isbn = isbn)
        val job = launch { r.manual.find(bookItem(), LookupMode.ISBN) }
        testScheduler.runCurrent()
        job.cancel(); job.join()
        blocking = false
        assertTrue(r.manual.find(bookItem(), LookupMode.ISBN) is LookupOutcome.Found)
    }

    @Test fun noRequestIsEverMadeForCoverArtwork() = runTest {
        val r = rig(standard(editionJson(extra = """"cover":"https://covers.openlibrary.org/b/id/1-L.jpg","covers":[1,2]"""))); r.store.addBook(isbn = isbn)
        r.found(bookItem()).forEach { assertTrue(it.candidate.covers.isEmpty()) }
        assertTrue(r.http.requests.none { it.contains("covers") || it.contains(".jpg") })
    }
}
