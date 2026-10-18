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

/**
 * Phase 4.5D.0 matrix J: the real [MetadataEnrichmentService] (query construction, all-ISBN-provider lookup, bounded
 * search, ranking and provenance-aware application) over fake providers and a store that applies the real precedence
 * rule. Synthetic candidates only; nothing here measures real provider coverage.
 */
class MangaEnrichmentIntegrationTest {
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

    private class Fake(override val id: String, val byId: List<MetadataCandidate> = emptyList(), val bySearch: List<MetadataCandidate> = emptyList()) : MetadataProvider {
        override val categories = setOf(MediaCategory.MANGA)
        override val capabilities = setOf(MetadataCapability.IDENTIFIER_LOOKUP, MetadataCapability.SEARCH)
        val lookups = ArrayList<PublicationIdentifier>(); var searches = 0
        override suspend fun lookup(identifier: PublicationIdentifier): List<MetadataCandidate> { lookups += identifier; return byId }
        override suspend fun search(query: MetadataQuery): List<MetadataCandidate> { searches++; return bySearch }
    }

    private fun item(fileName: String) = LibraryItem("m", fileName.substringBeforeLast('.'), category = MediaCategory.MANGA, sourceUri = "test:m",
        format = PublicationFormat.CBZ, fileName = fileName, byteSize = 1)

    /** A Manga item the way local analysis leaves it: filename evidence, plus any embedded fields given. */
    private fun store(fileName: String, embedded: MetadataValues = MetadataValues(), title: String? = null, titleOrigin: MetadataOrigin = MetadataOrigin.FILENAME_INFERENCE,
        creator: String = ""): Store = Store().apply {
        items["m"] = ResolvedMetadata(title ?: fileName.substringBeforeLast('.'), creator, MetadataValues(),
            mapOf(MetadataField.TITLE to FieldProvenance(titleOrigin), MetadataField.CREATOR to FieldProvenance(if (creator.isBlank()) MetadataOrigin.UNKNOWN else MetadataOrigin.EMBEDDED)))
        runBlocking {
            apply("m", FilenameInference.infer(fileName, MediaCategory.MANGA).proposals(), null)
            apply("m", embedded.proposals(FieldProvenance(MetadataOrigin.EMBEDDED)), null)
        }
    }

    private fun candidate(ref: String = "e/1", title: String = "One Piece", volume: String? = "3", ids: List<PublicationIdentifier> = listOf(isbn13), creator: String? = "Eiichiro Oda",
        language: String? = null, publisher: String? = "Viz Media", provider: String = "a") =
        MetadataCandidate(provider, ref, title, creator, MediaCategory.MANGA, MetadataValues(series = "One Piece", volume = volume, publisher = publisher, language = language, identifiers = ids),
            listOf(CoverCandidate(provider, "cover/$ref", 800, 1200, volume?.let { "Volume $it" })))

    private fun service(st: Store, vararg ps: Fake) = enrichment(ps.toList(), st) { 7 }

    @Test fun theQueryCarriesEveryLegitimateFieldAndNeverInventsAVolume() {
        val st = store("One Piece Vol. 3.cbz", MetadataValues(publisher = "Viz Media", language = "en", publishedDate = "2003-06-10", identifiers = listOf(isbn10)), creator = "Eiichiro Oda")
        val q = enrichment(emptyList(), st).query(item("One Piece Vol. 3.cbz"), runBlocking { st.resolved("m")!! })
        assertEquals(MediaCategory.MANGA, q.category)
        assertEquals(listOf(isbn10), q.identifiers)
        assertEquals(listOf("One Piece", "One Piece", "3", "Eiichiro Oda", "Viz Media", "en", 2003), listOf(q.title, q.series, q.volume, q.creator, q.publisher, q.language, q.year))
        assertTrue("a file-name title is weak evidence", q.titleIsInferred); assertFalse(q.isCollection)
        val none = store("One Piece.cbz"); val noVolume = enrichment(emptyList(), none).query(item("One Piece.cbz"), runBlocking { none.resolved("m")!! })
        assertNull("no volume is invented", noVolume.volume)
        val omnibus = store("Berserk Vols. 1-3.cbz"); val oq = enrichment(emptyList(), omnibus).query(item("Berserk Vols. 1-3.cbz"), runBlocking { omnibus.resolved("m")!! })
        assertTrue(oq.isCollection); assertNull(oq.volume); assertEquals("Berserk", oq.title)
        // A reliable (embedded) title is not replaced by the weaker file name.
        val embeddedTitle = store("whatever v09.cbz", title = "Monster", titleOrigin = MetadataOrigin.EMBEDDED)
        val eq = enrichment(emptyList(), embeddedTitle).query(item("whatever v09.cbz"), runBlocking { embeddedTitle.resolved("m")!! })
        assertEquals("Monster", eq.title); assertFalse(eq.titleIsInferred)
    }

    @Test fun withoutAnIsbnMangaSearchIsBoundedAndNeverApplies() = runBlocking {
        val st = store("One Piece Vol. 3.cbz", creator = "Eiichiro Oda"); val before = st.items.getValue("m")
        val p = Fake("a", bySearch = (1..30).map { candidate("s$it", title = "One Piece, Vol. 3", ids = emptyList()) })
        val r = service(st, p).enrich(item("One Piece Vol. 3.cbz"))
        assertTrue(p.lookups.isEmpty()); assertEquals(1, p.searches)
        assertEquals("series + volume + creator is a review candidate", MetadataEnrichmentService.Outcome.NEEDS_REVIEW, r.outcome)
        assertEquals(10, r.ranking!!.ranked.size)
        assertEquals(before, st.items.getValue("m"))
        val filenameOnly = store("One Piece Vol. 3.cbz"); val r2 = service(filenameOnly, Fake("a", bySearch = listOf(candidate(ids = emptyList(), title = "One Piece, Vol. 3")))).enrich(item("One Piece Vol. 3.cbz"))
        assertNotEquals(MetadataEnrichmentService.Outcome.APPLIED, r2.outcome)
    }

    @Test fun anExistingIsbnConsultsEveryEligibleProviderAsIsbn13AndNeverSearches() = runBlocking {
        val st = store("One Piece Vol. 3.cbz", MetadataValues(identifiers = listOf(isbn10)))
        val a = Fake("a", byId = listOf(candidate())); val b = Fake("b", byId = listOf(candidate(provider = "b", ref = "x/9")))
        val r = service(st, a, b).enrich(item("One Piece Vol. 3.cbz"))
        assertEquals(listOf(isbn13), a.lookups); assertEquals(listOf(isbn13), b.lookups)
        assertEquals(0, a.searches + b.searches)
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome)
        assertEquals(setOf("a", "b"), r.ranking!!.apply!!.corroborating)
    }

    @Test fun aWrongVolumeCandidateNeverWritesMetadata() = runBlocking {
        val st = store("One Piece Vol. 1.cbz", MetadataValues(identifiers = listOf(isbn13))); val before = st.items.getValue("m")
        for (order in listOf(listOf(candidate(volume = "10")), listOf(candidate(volume = "10"), candidate(ref = "e/2", volume = "1", ids = listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9781234567897")))))) {
            val r = service(st, Fake("a", byId = order)).enrich(item("One Piece Vol. 1.cbz"))
            assertNotEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome)
            assertEquals(before, st.items.getValue("m"))
        }
    }

    @Test fun anOmnibusRecordForASingleVolumeFileIsNotApplied() = runBlocking {
        val st = store("Berserk Vol. 3.cbz", MetadataValues(identifiers = listOf(isbn13))); val before = st.items.getValue("m")
        val omnibus = candidate(title = "Berserk Omnibus", volume = "3")
        val r = service(st, Fake("a", byId = listOf(omnibus))).enrich(item("Berserk Vol. 3.cbz"))
        assertNotEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome); assertEquals(before, st.items.getValue("m"))
        val range = service(st, Fake("a", byId = listOf(candidate(volume = "1-3")))).enrich(item("Berserk Vol. 3.cbz"))
        assertNotEquals(MetadataEnrichmentService.Outcome.APPLIED, range.outcome); assertEquals(before, st.items.getValue("m"))
    }

    @Test fun aValidUnambiguousEditionAppliesWithProvenanceAndKeepsUserAndEmbeddedData() = runBlocking {
        val st = store("One Piece Vol. 3.cbz", MetadataValues(identifiers = listOf(isbn10), publisher = "Embedded Press"), title = "Mi título", titleOrigin = MetadataOrigin.USER,
            creator = "Embedded Author")
        val c = candidate(title = "Mi título", creator = "Embedded Author", language = "es", publisher = "Embedded Press")
        val r = service(st, Fake("a", byId = listOf(c))).enrich(item("One Piece Vol. 3.cbz"))
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome)
        val m = st.items.getValue("m")
        assertEquals("Mi título", m.title); assertEquals(MetadataOrigin.USER, m.origin(MetadataField.TITLE))
        assertEquals("Embedded Author", m.creator)
        assertEquals("Embedded Press", m.values.publisher); assertEquals(MetadataOrigin.EMBEDDED, m.origin(MetadataField.PUBLISHER))
        assertEquals("es", m.values.language); assertEquals(MetadataOrigin.EXACT_IDENTIFIER_MATCH, m.origin(MetadataField.LANGUAGE))
        assertEquals(1, r.ranking!!.apply!!.cluster.covers.size)   // cover references are kept, never applied: the store has no cover field
    }

    @Test fun aConflictingPublisherStopsAnExactIsbnMatchInsteadOfBeingOverridden() = runBlocking {
        val st = store("One Piece Vol. 3.cbz", MetadataValues(identifiers = listOf(isbn13), publisher = "Embedded Press")); val before = st.items.getValue("m")
        val r = service(st, Fake("a", byId = listOf(candidate(publisher = "Other Press")))).enrich(item("One Piece Vol. 3.cbz"))
        assertNotEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome)
        assertEquals(before, st.items.getValue("m"))
    }

    @Test fun languageContradictionsStopAnOtherwiseExactIsbnMatch() = runBlocking {
        val st = store("One Piece Vol. 3.cbz", MetadataValues(identifiers = listOf(isbn13), language = "en")); val before = st.items.getValue("m")
        val r = service(st, Fake("a", byId = listOf(candidate(language = "ja", title = "ワンピース")))).enrich(item("One Piece Vol. 3.cbz"))
        assertNotEquals(MetadataEnrichmentService.Outcome.APPLIED, r.outcome); assertEquals(before, st.items.getValue("m"))
    }

    // ---- F-2: filename collection evidence survives an embedded title
    private fun berserk(volume: String?) = MetadataCandidate("a", "e/1", "Berserk", "Kentaro Miura", MediaCategory.MANGA,
        MetadataValues(series = "Berserk", volume = volume, identifiers = listOf(isbn13)), listOf(CoverCandidate("a", "cover/1", 800, 1200)))
    private fun embedded(fileName: String) = store(fileName, MetadataValues(identifiers = listOf(isbn13)), title = "Berserk", titleOrigin = MetadataOrigin.EMBEDDED)

    @Test fun aCollectionFileNameVetoesASingleVolumeMatchEvenWithAnEmbeddedTitle() = runBlocking {
        val st = embedded("Berserk 3-in-1.cbz"); val before = st.items.getValue("m")
        val q = enrichment(emptyList(), st).query(item("Berserk 3-in-1.cbz"), before)
        assertFalse("the embedded title is still trusted", q.titleIsInferred); assertEquals("Berserk", q.title)
        assertTrue(q.isCollection); assertNull(q.volume)
        val r = service(st, Fake("a", byId = listOf(berserk("1")))).enrich(item("Berserk 3-in-1.cbz"))
        assertEquals(MetadataEnrichmentService.Outcome.NEEDS_REVIEW, r.outcome)
        assertEquals("no volume or other field is written", before, st.items.getValue("m")); assertNull(st.items.getValue("m").values.volume)
        assertEquals("the title keeps its embedded provenance", MetadataOrigin.EMBEDDED, st.items.getValue("m").origin(MetadataField.TITLE))
    }

    @Test fun anEmbeddedTitleStillRejectsTheWrongVolumeAndAcceptsTheRightOne() = runBlocking {
        val st = embedded("Berserk Vol. 2.cbz"); val before = st.items.getValue("m")
        assertNotEquals(MetadataEnrichmentService.Outcome.APPLIED, service(st, Fake("a", byId = listOf(berserk("1")))).enrich(item("Berserk Vol. 2.cbz")).outcome)
        assertEquals(before, st.items.getValue("m"))
        val ok = embedded("Berserk Vol. 2.cbz"); assertFalse(enrichment(emptyList(), ok).query(item("Berserk Vol. 2.cbz"), ok.items.getValue("m")).isCollection)
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, service(ok, Fake("a", byId = listOf(berserk("2")))).enrich(item("Berserk Vol. 2.cbz")).outcome)
    }

    @Test fun aNormalSingleVolumeWithAnEmbeddedTitleStillReachesVeryHigh() = runBlocking {
        val st = embedded("Berserk.cbz")
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, service(st, Fake("a", byId = listOf(berserk("1")))).enrich(item("Berserk.cbz")).outcome)
    }

    @Test fun aCollectionFileNameAgreesWithACollectionRecord() = runBlocking {
        val st = store("Berserk 3-in-1.cbz", MetadataValues(identifiers = listOf(isbn13)), title = "Berserk Omnibus", titleOrigin = MetadataOrigin.EMBEDDED)
        val omnibus = berserk(null).copy(title = "Berserk Omnibus")
        assertEquals(MetadataEnrichmentService.Outcome.APPLIED, service(st, Fake("a", byId = listOf(omnibus))).enrich(item("Berserk 3-in-1.cbz")).outcome)
    }

    @Test fun booksAndDocumentsNeverInferCollections() {
        for ((category, name) in listOf(MediaCategory.BOOK to "Berserk 3-in-1.epub", MediaCategory.DOCUMENT to "Berserk 3-in-1.pdf")) {
            val st = store("Berserk.cbz"); val libItem = item(name).copy(category = category)
            assertFalse(name, enrichment(emptyList(), st).query(libItem, runBlocking { st.resolved("m")!! }).isCollection)
        }
    }
}
