// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

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

/**
 * Phase 4.5B coordinator and local analysis with fakes only (no network): identifier-first lookup, provider
 * eligibility, safe application, user edits preserved, covers untouched, removal never resurrected, bounded backfill.
 */
class MetadataCoordinatorTest {
    private val isbn = PublicationIdentifier(IdentifierType.ISBN_13, "9780306406157")

    /** The store's precedence is the real domain rule; persistence itself is covered by the Room instrumentation. */
    private class FakeStore : MetadataStore {
        val items = LinkedHashMap<String, ResolvedMetadata>()
        val versions = HashMap<String, Int>()
        val writes = ArrayList<String>()
        fun add(id: String, title: String, titleOrigin: MetadataOrigin = MetadataOrigin.FILENAME_INFERENCE, creator: String = "") {
            items[id] = ResolvedMetadata(title, creator, provenance = mapOf(MetadataField.TITLE to FieldProvenance(titleOrigin),
                MetadataField.CREATOR to FieldProvenance(if (creator.isBlank()) MetadataOrigin.UNKNOWN else MetadataOrigin.EMBEDDED)))
        }
        override suspend fun resolved(id: String) = items[id]
        override suspend fun apply(id: String, proposals: List<Proposed>, analysisVersion: Int?): Boolean {
            val current = items[id] ?: return false
            items[id] = resolveMetadata(current, proposals)
            analysisVersion?.let { versions[id] = it }
            writes += id
            return true
        }
        override fun observe(id: String): Flow<StoredMetadata?> = emptyFlow()
        override suspend fun pendingLocal(version: Int, exclude: Collection<String>, limit: Int) =
            items.keys.filter { (versions[it] ?: 0) < version && it !in exclude }.take(limit)
    }

    private class FakeProvider(override val id: String, override val categories: Set<MediaCategory>,
        override val capabilities: Set<MetadataCapability> = setOf(MetadataCapability.IDENTIFIER_LOOKUP, MetadataCapability.SEARCH),
        val byIdentifier: List<MetadataCandidate> = emptyList(), val bySearch: List<MetadataCandidate> = emptyList(), val fail: Boolean = false) : MetadataProvider {
        val calls = ArrayList<String>()
        override suspend fun lookup(identifier: PublicationIdentifier): List<MetadataCandidate> { calls += "lookup"; if (fail) throw IOException(); return byIdentifier }
        override suspend fun search(query: MetadataQuery): List<MetadataCandidate> { calls += "search"; if (fail) throw IOException(); return bySearch }
    }

    private fun item(id: String, category: MediaCategory = MediaCategory.BOOK, fileName: String = "$id.epub") =
        LibraryItem(id, fileName.substringBeforeLast('.'), category = category, sourceUri = "test:$id", format = PublicationFormat.EPUB, fileName = fileName, byteSize = 1)

    private val match = MetadataCandidate("fake", "ed/1", "The Real Title", "Jane Author", MediaCategory.BOOK,
        MetadataValues(publisher = "Example Press", identifiers = listOf(isbn)), covers = listOf(CoverCandidate("fake", "cover/1", 1200, 1800)))

    @Test fun identifierLookupFirstAppliesAnUnambiguousVeryHighMatchWithProvenanceAndNeverTouchesCovers() = runBlocking {
        val store = FakeStore().apply { add("b", "the book") }
        store.apply("b", listOf(Proposed(MetadataField.IDENTIFIERS, listOf(isbn), FieldProvenance(MetadataOrigin.EMBEDDED))))
        val provider = FakeProvider("fake", setOf(MediaCategory.BOOK), byIdentifier = listOf(match), bySearch = listOf(match.copy(providerRef = "never")))
        val result = MetadataEnrichmentService(listOf(provider), store) { 42 }.enrich(item("b"))
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, result.outcome)
        assertEquals("search is not needed after a safe identifier match", listOf("lookup"), provider.calls)
        val m = store.items.getValue("b")
        assertEquals("filename title replaced by a VERY_HIGH identifier match", "The Real Title", m.title)
        assertEquals(FieldProvenance(MetadataOrigin.EXACT_IDENTIFIER_MATCH, "fake", "ed/1", MatchConfidence.VERY_HIGH, 42), m.provenance[MetadataField.PUBLISHER])
        assertEquals("embedded identifiers are kept", MetadataOrigin.EMBEDDED, m.origin(MetadataField.IDENTIFIERS))
        // MetadataValues has no cover field and the service has no cover writer: artwork can only come from the cover pipeline.
        assertEquals(listOf(isbn), m.values.identifiers)
    }

    @Test fun userEditsSurviveEnrichmentAndRepeatedAnalysis() = runBlocking {
        val store = FakeStore().apply { add("b", "My Title", MetadataOrigin.USER) }
        store.apply("b", userEdits(MetadataValues(), MetadataValues(publisher = "Mine", identifiers = listOf(isbn)), 1) +
            listOf(Proposed(MetadataField.IDENTIFIERS, listOf(isbn), FieldProvenance(MetadataOrigin.EMBEDDED))))
        val before = store.items.getValue("b")
        MetadataEnrichmentService(listOf(FakeProvider("fake", setOf(MediaCategory.BOOK), byIdentifier = listOf(match))), store).enrich(item("b"))
        val after = store.items.getValue("b")
        assertEquals("My Title", after.title)
        assertEquals("Mine", after.values.publisher)
        assertEquals(before.provenance[MetadataField.PUBLISHER], after.provenance[MetadataField.PUBLISHER])
        val analyzer = LocalMetadataAnalyzer(store, { item("b") }) { LocalMetadataEvidence("Embedded Title", null, MetadataValues(publisher = "Embedded Press")) }
        store.versions.clear()
        analyzer.analyzeNext()
        assertEquals("My Title", store.items.getValue("b").title)
        assertEquals("Mine", store.items.getValue("b").values.publisher)
    }

    @Test fun ambiguityMediumAndLowAreNeverApplied() = runBlocking {
        val store = FakeStore().apply { add("b", "Saga", MetadataOrigin.EMBEDDED, "Brian K. Vaughan") }
        val medium = MetadataCandidate("fake", "s1", "Saga", "Vaughan, Brian K.", MediaCategory.BOOK, MetadataValues(publisher = "Image"))
        val before = store.items.getValue("b")
        val result = MetadataEnrichmentService(listOf(FakeProvider("fake", setOf(MediaCategory.BOOK), bySearch = listOf(medium))), store).enrich(item("b"))
        assertEquals(MetadataEnrichmentService.Outcome.NEEDS_REVIEW, result.outcome)
        assertEquals(before, store.items.getValue("b"))
        val low = MetadataCandidate("fake", "s2", "Saga", "Someone Else")
        assertEquals(MetadataEnrichmentService.Outcome.NO_MATCH,
            MetadataEnrichmentService(listOf(FakeProvider("fake", setOf(MediaCategory.BOOK), bySearch = listOf(low))), store).enrich(item("b")).outcome)
        assertEquals(before, store.items.getValue("b"))
    }

    @Test fun eligibilityDocumentsFailingProvidersAndRemovedItems() = runBlocking {
        val store = FakeStore().apply { add("d", "Report"); add("b", "Book") }
        val comicsOnly = FakeProvider("comics", setOf(MediaCategory.COMIC))
        assertEquals(MetadataEnrichmentService.Outcome.SKIPPED, MetadataEnrichmentService(listOf(comicsOnly), store).enrich(item("b")).outcome)
        assertTrue("a provider for another category is never asked", comicsOnly.calls.isEmpty())
        val docs = FakeProvider("docs", setOf(MediaCategory.DOCUMENT), bySearch = listOf(MetadataCandidate("docs", "r", "Report", category = MediaCategory.DOCUMENT)))
        assertEquals(MetadataEnrichmentService.Outcome.NO_MATCH, MetadataEnrichmentService(listOf(docs), store).enrich(item("d", MediaCategory.DOCUMENT)).outcome)
        assertTrue("Documents are never searched by title", "search" !in docs.calls)
        val failing = FakeProvider("down", setOf(MediaCategory.BOOK), fail = true)
        val working = FakeProvider("fake", setOf(MediaCategory.BOOK), byIdentifier = listOf(match))
        store.apply("b", listOf(Proposed(MetadataField.IDENTIFIERS, listOf(isbn), FieldProvenance(MetadataOrigin.EMBEDDED))))
        assertEquals("one failing provider never fails the others", MetadataEnrichmentService.Outcome.APPLIED,
            MetadataEnrichmentService(listOf(failing, working), store).enrich(item("b")).outcome)
        assertEquals(MetadataEnrichmentService.Outcome.SKIPPED, MetadataEnrichmentService(listOf(working), store).enrich(item("gone")).outcome)
        assertFalse("a late result for a removed item is dropped", "gone" in store.items)
    }

    @Test fun localAnalysisIsBoundedIdempotentAndIsolatesFailures() = runBlocking {
        val store = FakeStore().apply { add("ok", "Saga v01"); add("broken", "Broken"); add("missing", "Missing") }
        val read = ArrayList<String>()
        val analyzer = LocalMetadataAnalyzer(store, { id -> if (id == "missing") null else item(id, MediaCategory.COMIC, "$id v01.cbz") }) { item ->
            read += item.id
            if (item.id == "broken") throw IOException("unreadable")
            LocalMetadataEvidence(null, "Brian K. Vaughan", MetadataValues(series = "Saga", publisher = "Image"))
        }
        repeat(3) { assertNotNull(analyzer.analyzeNext()) }
        assertNull("nothing left this launch", analyzer.analyzeNext())
        assertEquals(listOf("ok", "broken"), read)
        assertEquals("only the readable item is marked analysed", mapOf("ok" to LocalMetadataAnalyzer.VERSION), store.versions)
        val ok = store.items.getValue("ok")
        assertEquals("Saga", ok.values.series)
        assertEquals(MetadataOrigin.EMBEDDED, ok.origin(MetadataField.SERIES))
        assertEquals("filename volume kept where the file declares none", "1", ok.values.volume)
        assertEquals(MetadataOrigin.FILENAME_INFERENCE, ok.origin(MetadataField.VOLUME))
        assertEquals("Brian K. Vaughan", ok.creator)
        val writes = store.writes.size
        assertNull("idempotent: an analysed item is not analysed again", LocalMetadataAnalyzer(store, { item(it) }) { error("not read") }
            .also { store.items.remove("broken"); store.items.remove("missing") }.analyzeNext())
        assertEquals(writes, store.writes.size)
    }

    @Test fun importRecordsTheEvidenceItAlreadyReadWithoutASecondPass() = runBlocking {
        val store = FakeStore().apply { add("n", "One Piece v03", MetadataOrigin.EMBEDDED) }
        LocalMetadataAnalyzer(store, { null }) { error("import must not re-read the source") }
            .recordImport(item("n", MediaCategory.MANGA, "One Piece v03.cbz"), MetadataValues(publisher = "Shueisha", language = "ja"))
        val m = store.items.getValue("n")
        assertEquals(LocalMetadataAnalyzer.VERSION, store.versions["n"])
        assertEquals("Shueisha", m.values.publisher)
        assertEquals("3", m.values.volume)
        assertEquals("One Piece v03", m.title)
    }
}
