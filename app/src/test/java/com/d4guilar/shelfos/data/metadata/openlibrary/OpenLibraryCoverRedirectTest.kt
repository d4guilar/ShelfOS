// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata.openlibrary

import com.d4guilar.shelfos.core.network.HttpUrlConnectionTransport
import com.d4guilar.shelfos.core.network.RequestPacer
import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A scripted HttpURLConnection: no socket is ever opened. */
private class JsonStub(url: URL, val status: Int, val location: String? = null, val body: String = "{}") : HttpURLConnection(url) {
    override fun getResponseCode() = status
    override fun getHeaderField(name: String?): String? = when (name?.lowercase()) {
        "location" -> location
        "content-type" -> "application/json; charset=utf-8"
        else -> null
    }
    override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
    override fun getErrorStream(): InputStream? = ByteArrayInputStream(body.toByteArray())
    override fun disconnect() {}
    override fun usingProxy() = false
    override fun connect() {}
}

/**
 * Finding F-1: the REAL transport (not a fake provider answering `/isbn/` directly) must follow Open Library's normal
 * `/isbn/{ISBN}.json` -> `/books/OL…M.json` redirect, and still refuse everything else.
 */
class OpenLibraryCoverRedirectTest {
    private val isbn = "9780140328721"
    private val seen = ArrayList<String>()

    private fun transport(maxRedirects: Int = 2, script: (URL) -> JsonStub) = HttpUrlConnectionTransport(
        OpenLibraryCoverRoutes.urlPolicy, "ShelfOS/test", maxBytes = 64 * 1024, maxRedirects = maxRedirects,
        pacer = RequestPacer(0, { 0L }, { }), check = OpenLibraryCoverRoutes::check,
        open = { u -> seen += u.toString(); script(u) })

    private val edition = editionJson(key = "OL12345M")
    private fun realisticSite(location: String = "/books/OL12345M.json", alternatives: String? = null) = { u: URL ->
        val path = u.path + (u.query?.let { "?$it" } ?: "")
        when {
            path == "/isbn/$isbn.json" -> JsonStub(u, 302, location)
            path == "/books/OL12345M.json" -> JsonStub(u, 200, body = edition)
            path == "/works/OL45804W/editions.json?limit=8" ->
                if (alternatives != null) JsonStub(u, 200, body = alternatives) else JsonStub(u, 404)
            else -> JsonStub(u, 404)
        }
    }

    @Test fun `the real transport follows the isbn to books redirect and coverEditions accepts the record`() = runTest {
        val editions = OpenLibraryProvider(transport(script = realisticSite())).coverEditions(isbn)
        assertEquals(listOf("https://openlibrary.org/isbn/$isbn.json", "https://openlibrary.org/books/OL12345M.json", "https://openlibrary.org/works/OL45804W/editions.json?limit=8"), seen)
        val exact = editions.single()
        assertEquals("OL12345M", exact.ref); assertTrue(exact.exact)
        val query = MetadataQuery(MediaCategory.BOOK, listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), title = "Matilda", language = "en")
        val c = OpenLibraryCoverCandidates.build(query, isbn, editions)
        assertEquals(CoverMatchKind.EXACT_EDITION, c.first().match); assertEquals(OpenLibraryCoverRef.Id(240726), c.first().ref)
    }

    @Test fun `the bounded editions page is read through the same gate`() = runTest {
        val alt = """{"entries":[{"key":"/books/OL9M","title":"Matilda","isbn_13":["9780140328738"],"covers":[111],"languages":[{"key":"/languages/spa"}]}]}"""
        val editions = OpenLibraryProvider(transport(script = realisticSite(alternatives = alt))).coverEditions(isbn)
        assertEquals(listOf("OL12345M", "OL9M"), editions.map { it.ref })
    }

    private fun failsWith(location: String): ProviderException {
        seen.clear()
        val e = assertThrows(ProviderException::class.java) { runBlocking { OpenLibraryProvider(transport(script = realisticSite(location))).coverEditions(isbn) } }
        assertEquals(ProviderFailureKind.MALFORMED, e.kind)
        assertEquals("only the first hop was opened for $location", 1, seen.size)
        return e
    }

    @Test fun `redirects to anything outside the three routes are refused`() {
        for (loc in listOf(
            "https://evil.example/books/OL12345M.json", "https://www.openlibrary.org/books/OL12345M.json", "http://openlibrary.org/books/OL12345M.json",
            "https://user@openlibrary.org/books/OL12345M.json", "https://openlibrary.org:8443/books/OL12345M.json",
            "/books/OL12345W.json", "/books/OL12345M", "/books/OLabcM.json", "/books/OL1234567890123M.json", "/books/OLM.json",
            "/books/../authors/OL1A.json", "/books/OL12345M.json/../../x", "/books/OL12345M.json?x=1", "/books/OL12345M.json?default=false",
            "/search.json?q=x", "/authors/OL1A.json", "/works/OL1W.json", "/works/OL1W/editions.json?limit=100", "/works/OL1W/editions.json",
            "/isbn/12345.json", "/isbn/9780140328721.json?x=1", "//evil.example/books/OL12345M.json",
        )) failsWith(loc)
    }

    @Test fun `too many redirects are refused`() {
        seen.clear()
        val loop = { u: URL -> JsonStub(u, 302, "/books/OL1M.json") }
        val e = assertThrows(ProviderException::class.java) { runBlocking { OpenLibraryProvider(transport(maxRedirects = 2, script = loop)).coverEditions(isbn) } }
        assertEquals(ProviderFailureKind.MALFORMED, e.kind)
        assertEquals(3, seen.size)
    }

    @Test fun `the route gate itself`() {
        val ok = listOf("https://openlibrary.org/isbn/$isbn.json", "https://openlibrary.org/books/OL1M.json", "https://openlibrary.org/works/OL999999999999W/editions.json?limit=8")
        ok.forEach { OpenLibraryCoverRoutes.check(OpenLibraryCoverRoutes.urlPolicy.check(it)) }
        for (bad in listOf("https://openlibrary.org/works/OL1W/editions.json?limit=8&x=1", "https://openlibrary.org/works/OL1W/editions.json?limit=9",
            "https://openlibrary.org/isbn/${isbn}x.json"))
            assertThrows(bad, UrlNotAllowedException::class.java) { OpenLibraryCoverRoutes.check(OpenLibraryCoverRoutes.urlPolicy.check(bad)) }
    }
}

/** Finding F-2 specification: pure first-hop derivation from the original cover id; nothing here is wired into a transport. */
class OpenLibraryCoverStorageTest {
    @Test fun `family A is derived from the cover id and size`() {
        assertEquals(listOf("https://archive.org/download/olcovers12/olcovers12-L.zip/123456-L.jpg"), OpenLibraryCoverStorage.firstHops(123456, CoverSize.LARGE))
        assertTrue(OpenLibraryCoverStorage.firstHops(123456, CoverSize.MEDIUM).isEmpty())   // only L / unsized are redirected to the cluster
    }

    @Test fun `family B is derived from item and batch`() {
        val hops = OpenLibraryCoverStorage.firstHops(8_000_123, CoverSize.LARGE)
        assertEquals(listOf("https://archive.org/download/olcovers800/olcovers800-L.zip/8000123-L.jpg", "https://archive.org/download/l_covers_0008/l_covers_0008_00.zip/0008000123-L.jpg"), hops)
        assertTrue("an empty prefix is not a sized-L form", hops.none { it.contains("/covers_0008/") })
        assertEquals(listOf("https://archive.org/download/s_covers_0008/s_covers_0008_00.zip/0008000123-S.jpg"), OpenLibraryCoverStorage.firstHops(8_000_123, CoverSize.SMALL))
        assertTrue(OpenLibraryCoverStorage.firstHops(987_654_321, CoverSize.MEDIUM).single().contains("m_covers_0987/m_covers_0987_65.zip/0987654321-M.jpg"))
    }

    @Test fun `only an exact derived url matches`() {
        val id = 123456L
        val good = "https://archive.org/download/olcovers12/olcovers12-L.zip/123456-L.jpg"
        assertTrue(OpenLibraryCoverStorage.isExpectedFirstHop(good, id, CoverSize.LARGE))
        for (bad in listOf(good.replace("123456-L", "123457-L"), good.replace("olcovers12", "olcovers13"), good.replace("archive.org", "evil.example"),
            good.replace("https", "http"), "$good?x=1", good.replace("/download/", "/download/../"), "https://ia800000.us.archive.org/12/items/olcovers12/olcovers12-L.zip/123456-L.jpg",
            "https://archive.org/download/olcovers12/other.zip/123456-L.jpg", "https://archive.org/download/anything/../olcovers12/olcovers12-L.zip/123456-L.jpg"))
            assertFalse(bad, OpenLibraryCoverStorage.isExpectedFirstHop(bad, id, CoverSize.LARGE))
        assertTrue(OpenLibraryCoverStorage.firstHops(0, CoverSize.LARGE).isEmpty() && OpenLibraryCoverStorage.firstHops(-5, CoverSize.LARGE).isEmpty())
    }
}
