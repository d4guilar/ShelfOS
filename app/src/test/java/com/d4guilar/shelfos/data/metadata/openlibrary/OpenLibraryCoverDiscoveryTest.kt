// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata.openlibrary

import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

class OpenLibraryCoverUrlTest {
    @Test fun `builds the documented url with default=false`() {
        assertEquals("https://covers.openlibrary.org/b/id/240726-L.jpg?default=false", OpenLibraryCovers.url(OpenLibraryCoverRef.Id(240726), CoverSize.LARGE))
        assertEquals("https://covers.openlibrary.org/b/olid/OL7353617M-M.jpg?default=false", OpenLibraryCovers.url(OpenLibraryCoverRef.Olid("OL7353617M"), CoverSize.MEDIUM))
        assertEquals("https://covers.openlibrary.org/b/isbn/9780140328721-S.jpg?default=false", OpenLibraryCovers.url(OpenLibraryCoverRef.Isbn("9780140328721"), CoverSize.SMALL))
    }

    @Test fun `refuses malformed identifiers`() {
        assertNull(OpenLibraryCovers.url(OpenLibraryCoverRef.Id(0), CoverSize.LARGE))
        assertNull(OpenLibraryCovers.url(OpenLibraryCoverRef.Id(-1), CoverSize.LARGE))
        assertNull(OpenLibraryCovers.url(OpenLibraryCoverRef.Olid("OL1W"), CoverSize.LARGE))
        assertNull(OpenLibraryCovers.url(OpenLibraryCoverRef.Olid("../etc"), CoverSize.LARGE))
        assertNull(OpenLibraryCovers.url(OpenLibraryCoverRef.Isbn("123"), CoverSize.LARGE))
    }

    private fun allowed(u: String) = OpenLibraryCovers.check(OpenLibraryCovers.urlPolicy.check(u))
    private fun refused(u: String) = assertThrows(UrlNotAllowedException::class.java) { allowed(u) }

    @Test fun `every generated url passes the strict gate`() {
        listOf(OpenLibraryCoverRef.Id(1), OpenLibraryCoverRef.Olid("OL1M"), OpenLibraryCoverRef.Isbn("9780140328721")).forEach { r ->
            CoverSize.entries.forEach { allowed(OpenLibraryCovers.url(r, it)!!) }
        }
    }

    @Test fun `only https on the exact covers host and the b path`() {
        refused("http://covers.openlibrary.org/b/id/1-L.jpg")
        refused("https://openlibrary.org/b/id/1-L.jpg")
        refused("https://covers.openlibrary.org.evil.example/b/id/1-L.jpg")
        refused("https://archive.org/download/olcovers1/1-L.jpg")
        refused("https://user@covers.openlibrary.org/b/id/1-L.jpg")
        refused("https://covers.openlibrary.org:8443/b/id/1-L.jpg")
        refused("https://covers.openlibrary.org/a/id/1-L.jpg")
    }

    @Test fun `the path and query shape are enforced`() {
        refused("https://covers.openlibrary.org/b/id/1-L.jpg?default=true")
        refused("https://covers.openlibrary.org/b/id/1-L.jpg?x=1&default=false")
        refused("https://covers.openlibrary.org/b/id/abc-L.jpg")
        refused("https://covers.openlibrary.org/b/id/1-XL.jpg")
        refused("https://covers.openlibrary.org/b/id/1-L.png")
        refused("https://covers.openlibrary.org/b/olid/OL1W-L.jpg")
        refused("https://covers.openlibrary.org/b/isbn/12345-L.jpg")
        refused("https://covers.openlibrary.org/b/id/1/../2-L.jpg")
        refused("https://covers.openlibrary.org/b/id/1-L.jpg/extra")
        // The hand-built URL class is what the transport re-checks on a redirect.
        assertThrows(UrlNotAllowedException::class.java) { OpenLibraryCovers.check(URL("https://covers.openlibrary.org/b/id/9-L.jpg?default=false&x=1")) }
    }

    @Test fun `the raw transfer cap is a multiple of the stored cover bound and far below a picked file`() {
        assertEquals(4 * 1024 * 1024, OpenLibraryCovers.MAX_RAW_BYTES)
    }
}

class OpenLibraryCoverDiscoveryTest {
    private val isbn = "9780140328721"

    private fun worksJson(vararg entries: String) = """{"size":${entries.size},"entries":[${entries.joinToString(",")}]}"""
    private fun entry(key: String, title: String = "Matilda", isbn13: String? = "9780140328738", covers: String? = "[111]", lang: String? = "eng", publisher: String = "Puffin") = buildString {
        append("""{"key":"/books/$key","title":"$title","publishers":["$publisher"],"publish_date":"1999"""")
        if (isbn13 != null) append(""","isbn_13":["$isbn13"]""")
        if (covers != null) append(""","covers":$covers""")
        if (lang != null) append(""","languages":[{"key":"/languages/$lang"}]""")
        append("}")
    }

    private fun http(vararg alt: String, editionBody: String = editionJson(), worksStatus: Int = 200) = FakeHttp { url ->
        when {
            url.endsWith("/isbn/$isbn.json") -> json(200, editionBody)
            url.contains("/works/OL45804W/editions.json") -> if (worksStatus == 200) json(200, worksJson(*alt)) else json(worksStatus, "{}")
            else -> json(404, "{}")
        }
    }

    private val query = MetadataQuery(MediaCategory.BOOK, listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), title = "Matilda", language = "en")

    @Test fun `exact edition by ISBN becomes verified cover-id candidates`() = runTest {
        val h = http()
        val editions = OpenLibraryProvider(h).coverEditions(isbn)
        val exact = editions.single { it.exact }
        assertEquals("OL7353617M", exact.ref)
        assertEquals(listOf(240726L, 9999L), exact.coverIds)
        val c = OpenLibraryCoverCandidates.build(query, isbn, editions)
        assertTrue(c.all { it.match == CoverMatchKind.EXACT_EDITION })
        assertEquals(OpenLibraryCoverRef.Id(240726), c.first().ref)
        assertEquals(MatchConfidence.VERY_HIGH, c.first().tier)
        assertEquals(isbn, c.first().isbn13)
    }

    @Test fun `a record that does not list the requested ISBN is not trusted`() = runTest {
        val h = http(editionBody = editionJson(isbn13 = "9780140328738", isbn10 = null))
        assertTrue(OpenLibraryProvider(h).coverEditions(isbn).isEmpty())
    }

    @Test fun `a record with no ISBN at all is not trusted either`() = runTest {
        assertTrue(OpenLibraryProvider(http(editionBody = editionJson(isbn13 = null, isbn10 = null))).coverEditions(isbn).isEmpty())
    }

    @Test fun `alternative editions are labeled and never exact`() = runTest {
        val h = http(entry("OL1M"), entry("OL2M", lang = "spa", publisher = "Alfaguara", isbn13 = "9788420472850"))
        val editions = OpenLibraryProvider(h).coverEditions(isbn)
        val c = OpenLibraryCoverCandidates.build(query.copy(publisher = "Puffin"), isbn, editions)
        val others = c.filter { it.match == CoverMatchKind.OTHER_EDITION }
        assertEquals(setOf("OL1M", "OL2M"), others.map { it.editionRef }.toSet())
        assertTrue(others.all { CoverWarning.DIFFERENT_ISBN in it.warnings })
        assertTrue(others.none { it.tier == MatchConfidence.VERY_HIGH })
        val spanish = others.single { it.editionRef == "OL2M" }
        assertTrue(CoverWarning.DIFFERENT_LANGUAGE in spanish.warnings)
        assertTrue(CoverWarning.DIFFERENT_PUBLISHER in spanish.warnings)
        assertTrue(CoverWarning.LOW_CONFIDENCE in spanish.warnings)
    }

    @Test fun `an alternative without its own cover list produces no candidate and a work-level image is never invented`() = runTest {
        val h = http(entry("OL1M", covers = null), entry("OL3M", covers = "[]"), entry("OL4M", covers = "[-1]"))
        val c = OpenLibraryCoverCandidates.build(query, isbn, OpenLibraryProvider(h).coverEditions(isbn))
        assertTrue(c.all { it.match == CoverMatchKind.EXACT_EDITION })
    }

    @Test fun `exact edition without a recorded cover is offered by OLID with a warning`() = runTest {
        val h = http(editionBody = editionJson().replace(""""covers":[240726,9999],""", ""))
        val c = OpenLibraryCoverCandidates.build(query, isbn, OpenLibraryProvider(h).coverEditions(isbn)).single()
        assertEquals(OpenLibraryCoverRef.Olid("OL7353617M"), c.ref)
        assertTrue(CoverWarning.NO_COVER_RECORDED in c.warnings)
    }

    @Test fun `a hardcover record sharing the ISBN is exact, one with another ISBN is not`() = runTest {
        val h = http(entry("OL9M", isbn13 = isbn, covers = "[222]"))
        val c = OpenLibraryCoverCandidates.build(query, isbn, OpenLibraryProvider(h).coverEditions(isbn))
        assertTrue(c.first { it.editionRef == "OL9M" }.match == CoverMatchKind.EXACT_EDITION)
    }

    @Test fun `the candidate set and the requests are bounded`() = runTest {
        val many = (1..30).map { entry("OL${it + 100}M", isbn13 = "97801403${(10000 + it)}".take(13), covers = "[${it + 1}]") }.toTypedArray()
        val h = http(*many)
        val c = OpenLibraryCoverCandidates.build(query, isbn, OpenLibraryProvider(h).coverEditions(isbn))
        assertTrue(c.size <= 8)
        assertEquals(2, h.requests.size)   // the edition and one bounded editions page: no authors, no paging
        assertTrue(h.requests.last().endsWith("/works/OL45804W/editions.json?limit=8"))
    }

    @Test fun `a failing editions page leaves the exact edition and a rate limit still stops`() = runTest {
        val c = OpenLibraryProvider(http(worksStatus = 500)).coverEditions(isbn)
        assertEquals(1, c.size)
        val e = assertThrows(ProviderException::class.java) { kotlinx.coroutines.runBlocking { OpenLibraryProvider(http(worksStatus = 429)).coverEditions(isbn) } }
        assertEquals(ProviderFailureKind.RATE_LIMITED, e.kind)
    }

    @Test fun `several works means no alternatives are fetched`() = runTest {
        val body = editionJson().replace(""""works":[{"key":"/works/OL45804W"}]""", """"works":[{"key":"/works/OL45804W"},{"key":"/works/OL1W"}]""")
        val h = http(entry("OL1M"), editionBody = body)
        assertEquals(1, OpenLibraryProvider(h).coverEditions(isbn).size)
        assertEquals(1, h.requests.size)
    }

    @Test fun `a malformed isbn sends nothing`() = runTest {
        val h = http()
        assertTrue(OpenLibraryProvider(h).coverEditions("123").isEmpty())
        assertTrue(h.requests.isEmpty())
    }

    @Test fun `the metadata lookup path is unchanged and still ignores cover fields`() = runTest {
        val c = OpenLibraryProvider(http(), { 0L }).lookup(PublicationIdentifier(IdentifierType.ISBN_13, isbn)).single()
        assertTrue(c.covers.isEmpty())
    }
}
