// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata.openlibrary

import com.d4guilar.shelfos.core.network.ResponseTooLargeException
import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** The Open Library adapter against deterministic fake responses. No test makes a network request. */
class OpenLibraryProviderTest {
    private val isbn13 = PublicationIdentifier(IdentifierType.ISBN_13, "9780140328721")
    private val isbn10 = PublicationIdentifier(IdentifierType.ISBN_10, "0140328726")

    private fun provider(http: FakeHttp) = OpenLibraryProvider(http) { 1_000_000L }
    private fun happy(edition: String = editionJson(), author: String = authorJson("Roald Dahl")) = FakeHttp { url ->
        when {
            url.contains("/isbn/") -> json(200, edition)
            url.contains("/authors/") -> json(200, author)
            else -> json(404, "{}")
        }
    }
    private fun failure(block: () -> Unit): ProviderException = try { block(); fail("expected a ProviderException"); error("unreachable") } catch (e: ProviderException) { e }

    @Test fun isbn13LookupBuildsAnEditionCandidateFromEvidenceOnly() = runBlocking {
        val http = happy()
        val c = provider(http).lookup(isbn13).single()
        assertEquals(listOf("https://openlibrary.org/isbn/9780140328721.json", "https://openlibrary.org/authors/OL34184A.json"), http.requests)
        assertEquals(OpenLibrary.ID, c.providerId); assertEquals("OL7353617M", c.providerRef); assertEquals(MediaCategory.BOOK, c.category)
        assertEquals("Matilda", c.title); assertEquals("Roald Dahl", c.creator)
        assertEquals("Puffin", c.values.publisher); assertEquals("1998", c.values.publishedDate); assertEquals("en", c.values.language)
        assertEquals(setOf("9780140328721"), c.values.identifiers.mapNotNull { it.isbn13 }.toSet())
        assertTrue("no cover reference ever leaves the adapter", c.covers.isEmpty())
        assertFalse(OpenLibrary.isWorkLevel(c.providerRef))
    }

    @Test fun isbn10LookupUsesTheIsbn13EndpointAndNoRequestTouchesCovers() = runBlocking {
        val http = happy()
        provider(http).lookup(isbn10).single()
        assertEquals("https://openlibrary.org/isbn/9780140328721.json", http.requests.first())
        assertTrue(http.requests.none { it.contains("covers") || it.endsWith(".jpg") })
    }

    @Test fun nonIsbnIdentifiersAndGarbageSendNothing() = runBlocking {
        val http = happy()
        assertTrue(provider(http).lookup(PublicationIdentifier(IdentifierType.UPC_A, "036000291452")).isEmpty())
        assertTrue(provider(http).lookup(PublicationIdentifier(IdentifierType.ISBN_13, "9780140328721/../x")).isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test fun partialRecordsKeepUnknownFieldsUnknown() = runBlocking {
        val c = provider(happy(editionJson(authors = emptyList(), publishers = emptyList(), date = null, language = null, isbn10 = null))).lookup(isbn13).single()
        assertNull(c.creator); assertNull(c.values.publisher); assertNull(c.values.publishedDate); assertNull(c.values.language); assertNull(c.values.description)
        assertEquals("Matilda", c.title)
    }

    @Test fun descriptionComesOnlyFromTheEditionAndAcceptsBothShapes() = runBlocking {
        assertEquals("A girl.", provider(happy(editionJson(extra = """"description":"A girl.""""))).lookup(isbn13).single().values.description)
        assertEquals("A girl.", provider(happy(editionJson(extra = """"description":{"type":"/type/text","value":"A girl."}"""))).lookup(isbn13).single().values.description)
    }

    @Test fun anUnresolvedAuthorReferenceIsNeverShownAsAName() = runBlocking {
        val missing = FakeHttp { url -> if (url.contains("/isbn/")) json(200, editionJson()) else json(404, "{}") }
        assertNull(provider(missing).lookup(isbn13).single().creator)
        val pathLike = FakeHttp { url -> if (url.contains("/isbn/")) json(200, editionJson()) else json(200, authorJson("/authors/OL34184A")) }
        assertNull(provider(pathLike).lookup(isbn13).single().creator)
        val blank = FakeHttp { url -> if (url.contains("/isbn/")) json(200, editionJson()) else json(200, """{"name":"  "}""") }
        assertNull(provider(blank).lookup(isbn13).single().creator)
    }

    @Test fun authorRequestsAreBoundedAndStoppingSignalsAreNotSwallowed() = runBlocking {
        val many = happy(editionJson(authors = listOf("OL1A", "OL2A", "OL3A", "OL4A")))
        provider(many).lookup(isbn13)
        assertEquals("edition + at most two authors", 3, many.requests.size)
        val limited = FakeHttp { url -> if (url.contains("/isbn/")) json(200, editionJson()) else json(429, "", "Retry-After" to "120") }
        val e = failure { runBlocking { provider(limited).lookup(isbn13) } }
        assertEquals(ProviderFailureKind.RATE_LIMITED, e.kind); assertEquals(120_000L, e.retryAfterMs)
        val blocked = FakeHttp { url -> if (url.contains("/isbn/")) json(200, editionJson()) else json(403, "") }
        assertEquals(ProviderFailureKind.ACCESS_BLOCKED, failure { runBlocking { provider(blocked).lookup(isbn13) } }.kind)
    }

    @Test fun aRecordThatDoesNotListTheRequestedIsbnIsAnotherEditionAndIsDropped() = runBlocking {
        val other = FakeHttp { json(200, editionJson(isbn13 = "9780140328738", isbn10 = null)) }
        assertTrue(provider(other).lookup(isbn13).isEmpty())
        val unlisted = provider(happy(editionJson(isbn13 = null, isbn10 = null))).lookup(isbn13).single()
        assertTrue("no identifier is invented for the candidate", unlisted.values.identifiers.isEmpty())
    }

    @Test fun invalidChecksumsInTheRecordAreIgnored() = runBlocking {
        val c = provider(happy(editionJson(isbn13 = "9780140328720", isbn10 = "0140328726"))).lookup(isbn13).single()
        assertEquals(setOf("9780140328721"), c.values.identifiers.mapNotNull { it.isbn13 }.toSet())
    }

    @Test fun unicodeMetadataSurvivesUntranslated() = runBlocking {
        val c = provider(happy(editionJson(title = "Cien años de soledad", publishers = listOf("Editorial Sudamericana")), authorJson("Gabriel García Márquez"))).lookup(isbn13).single()
        assertEquals("Cien años de soledad", c.title); assertEquals("Gabriel García Márquez", c.creator)
        assertEquals("吾輩は猫である", provider(happy(editionJson(title = "吾輩は猫である"), authorJson("夏目漱石"))).lookup(isbn13).single().title)
    }

    @Test fun datesAreReadOnlyInClearForms() = runBlocking {
        for ((raw, want) in listOf("1998" to "1998", "1998-03" to "1998-03", "March 1998" to "1998-03", "Mar 1, 1998" to "1998-03-01", "March 1, 1998" to "1998-03-01",
            "c1998" to null, "1998?" to null, "circa 1990" to null, "Feb 30, 2001" to null, "" to null))
            assertEquals(raw, want, provider(happy(editionJson(date = raw))).lookup(isbn13).single().values.publishedDate)
    }

    @Test fun languageIsTakenOnlyWhenTheEditionNamesExactlyOne() = runBlocking {
        assertEquals("es", provider(happy(editionJson(language = "spa"))).lookup(isbn13).single().values.language)
        assertNull(provider(happy(editionJson(language = "xxx"))).lookup(isbn13).single().values.language)
        assertNull(provider(happy(editionJson(language = null, extra = """"languages":[{"key":"/languages/eng"},{"key":"/languages/spa"}]"""))).lookup(isbn13).single().values.language)
    }

    @Test fun malformedMissingOrWrongRecordsAreProviderFailuresNotCandidates() {
        fun kind(body: String, type: String = "application/json") = failure { runBlocking { provider(FakeHttp { json(200, body, "Content-Type" to type) }).lookup(isbn13) } }.kind
        assertEquals(ProviderFailureKind.MALFORMED, kind("<html>nope</html>", "text/html"))
        assertEquals(ProviderFailureKind.MALFORMED, kind("not json"))
        assertEquals(ProviderFailureKind.MALFORMED, kind("[]"))
        assertEquals(ProviderFailureKind.MALFORMED, kind("""{"title":"No key"}"""))
        assertEquals(ProviderFailureKind.MALFORMED, kind("""{"key":"/books/OL1M"}"""))
        assertEquals(ProviderFailureKind.MALFORMED, kind("""{"key":"/books/evil","title":"x"}"""))
    }

    @Test fun httpStatusesMapToTheClosedFailureSet() {
        fun lookup(r: com.d4guilar.shelfos.core.network.HttpResponse) = runBlocking { provider(FakeHttp { r }).lookup(isbn13) }
        assertTrue("a 404 is a valid empty answer", lookup(json(404, "{}")).isEmpty())
        assertEquals(ProviderFailureKind.ACCESS_BLOCKED, failure { lookup(json(403, "")) }.kind)
        val limited = failure { lookup(json(429, "", "Retry-After" to "60")) }
        assertEquals(ProviderFailureKind.RATE_LIMITED, limited.kind); assertEquals(60_000L, limited.retryAfterMs)
        assertNull(failure { lookup(json(429, "")) }.retryAfterMs)
        assertEquals(ProviderFailureKind.SERVER_FAILURE, failure { lookup(json(503, "")) }.kind)
        assertEquals(ProviderFailureKind.MALFORMED, failure { lookup(json(400, "")) }.kind)
    }

    @Test fun transportProblemsMapToOfflineTimeoutAndMalformed() {
        fun lookup(e: Exception) = runBlocking { provider(FakeHttp { throw e }).lookup(isbn13) }
        assertEquals(ProviderFailureKind.OFFLINE, failure { lookup(java.net.UnknownHostException("x")) }.kind)
        assertEquals(ProviderFailureKind.OFFLINE, failure { lookup(IOException("x")) }.kind)
        assertEquals(ProviderFailureKind.TIMEOUT, failure { lookup(SocketTimeoutException("x")) }.kind)
        assertEquals(ProviderFailureKind.MALFORMED, failure { lookup(ResponseTooLargeException(10)) }.kind)
        assertEquals(ProviderFailureKind.MALFORMED, failure { lookup(UrlNotAllowedException("x")) }.kind)
    }

    // ---- search ----

    private fun docs(vararg d: String) = """{"numFound":${d.size},"docs":[${d.joinToString(",")}]}"""
    private fun doc(key: String, title: String, vararg authors: String) = """{"key":"/works/$key","title":"$title","author_name":[${authors.joinToString(",") { "\"$it\"" }}]}"""

    @Test fun searchIsOneBoundedRequestWithMinimalEncodedFieldsAndNeverWildcards() = runBlocking {
        val http = FakeHttp { json(200, docs(doc("OL1W", "Cien años de soledad", "Gabriel García Márquez"))) }
        provider(http).search(MetadataQuery(MediaCategory.BOOK, title = "Cien años de soledad & más", creator = "García Márquez"))
        val url = http.requests.single()
        assertTrue(url, url.startsWith("https://openlibrary.org/search.json?title=Cien+a%C3%B1os+de+soledad+%26+m%C3%A1s&author=Garc%C3%ADa+M%C3%A1rquez&fields=key,title,author_name&limit=10"))
        assertFalse(url.contains("fields=*"))
    }

    @Test fun searchResultsAreWorkLevelCandidatesWithoutAnyEditionFacts() = runBlocking {
        val r = provider(FakeHttp { json(200, docs(doc("OL45804W", "Matilda", "Roald Dahl"), doc("OL2W", "Matilda Jane"), """{"key":"/works/bad","title":"x"}""", """{"title":"no key"}""", "5")) })
            .search(MetadataQuery(MediaCategory.BOOK, title = "Matilda"))
        assertEquals(listOf("OL45804W", "OL2W"), r.map { it.providerRef })
        r.forEach { c ->
            assertTrue(OpenLibrary.isWorkLevel(c.providerRef)); assertTrue(c.covers.isEmpty())
            assertNull(c.values.publisher); assertNull(c.values.publishedDate); assertNull(c.values.language); assertTrue(c.values.identifiers.isEmpty())
        }
        assertEquals("Roald Dahl", r[0].creator); assertNull(r[1].creator)
    }

    @Test fun searchWithoutATitleSendsNothingAndEmptyOrMalformedResultsAreHandled() = runBlocking {
        val none = FakeHttp()
        assertTrue(provider(none).search(MetadataQuery(MediaCategory.BOOK, title = "  ")).isEmpty()); assertTrue(provider(none).search(MetadataQuery(MediaCategory.BOOK)).isEmpty())
        assertTrue(none.requests.isEmpty())
        assertTrue(provider(FakeHttp { json(200, docs()) }).search(MetadataQuery(MediaCategory.BOOK, title = "x")).isEmpty())
        assertEquals(ProviderFailureKind.MALFORMED, failure { runBlocking { provider(FakeHttp { json(200, """{"numFound":0}""") }).search(MetadataQuery(MediaCategory.BOOK, title = "x")) } }.kind)
    }

    @Test fun searchResultCountIsCappedAtTen() = runBlocking {
        val many = (1..30).map { doc("OL${it}W", "T$it") }.toTypedArray()
        assertEquals(10, provider(FakeHttp { json(200, docs(*many)) }).search(MetadataQuery(MediaCategory.BOOK, title = "T")).size)
    }

    @Test fun descriptorIsConditionalWithAMetadataOnlyBookGrantAndPublishesNoSecrets() {
        val d = OpenLibrary.descriptor()
        assertEquals(TermsStatus.CONDITIONAL, d.termsStatus); assertEquals(CredentialRequirement.NONE, d.credential)
        assertEquals(setOf(MediaCategory.BOOK), d.grant!!.categories); assertEquals("D1-M", d.grant!!.basis)
        assertTrue(MetadataCapability.COVERS !in d.capabilities && MetadataCapability.COVERS !in d.grant!!.capabilities)
        assertEquals(2_000L, d.rate.perMillis); assertEquals(1, d.rate.permits); assertEquals(1, d.rate.burst); assertEquals(1, d.rate.maxConcurrency)
        assertTrue("slower than Open Library's published 1 request per second", d.rate.perMillis > 1_000)
        assertTrue(OpenLibrary.MIN_INTERVAL_MS >= d.rate.perMillis)
    }
}
