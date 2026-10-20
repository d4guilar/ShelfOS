// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** JSON reader, URL policy, the HttpURLConnection transport (through a fake connection: no socket is ever opened) and the pacer. */
class NetworkTest {
    // ---- JSON ----

    @Test fun jsonReadsObjectsArraysNumbersEscapesAndUnicode() {
        val v = Json.parse("""{"a":[1,2.5,-3e2,true,false,null],"s":"x\n\"\u00e9\u5409","o":{"k":"v"}}""").asObject()!!
        assertEquals(listOf(1L, 2.5, -300.0, true, false, null), v["a"].asList())
        assertEquals("x\n\"é吉", v["s"]); assertEquals("v", v["o"].asObject()!!["k"])
        assertEquals("Cien años de soledad", Json.parse("\"Cien años de soledad\""))
    }

    @Test fun jsonRejectsEverythingMalformed() {
        for (bad in listOf("", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "[1 2]", "tru", "01", "1.", "\"a", "\"\\x\"", "\"\\u12\"", "{\"a\":1} x", "'a'", "[\"a\nb\"]", "NaN", "{1:2}"))
            assertThrows(bad, JsonException::class.java) { Json.parse(bad) }
    }

    @Test fun jsonIsBounded() {
        assertThrows(JsonException::class.java) { Json.parse("[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5)) }
        assertThrows(JsonException::class.java) { Json.parse("[" + "0,".repeat(Json.MAX_NODES) + "0]") }
        assertThrows(JsonException::class.java) { Json.parse("\"" + "a".repeat(Json.MAX_STRING + 1) + "\"") }
    }

    @Test fun typedAccessReadsAWrongTypeAsAbsent() {
        assertNull(5.asText()); assertNull("  ".asText()); assertEquals("a", " a ".asText()); assertNull("x".asList()); assertNull(listOf(1).asObject())
        assertEquals(3, 3L.asInt()); assertNull(Long.MAX_VALUE.asInt())
    }

    // ---- URL policy ----

    private val policy = UrlPolicy(setOf("openlibrary.org"), listOf("/isbn/", "/books/", "/authors/", "/search.json"))

    @Test fun onlyHttpsExactHostAndDocumentedPathsAreAllowed() {
        policy.check("https://openlibrary.org/isbn/9780140328721.json")
        policy.check("https://OpenLibrary.org/search.json?title=a&fields=key")
        for (bad in listOf("http://openlibrary.org/isbn/1.json", "https://openlibrary.org.evil.com/isbn/1.json", "https://evilopenlibrary.org/isbn/1.json",
            "https://covers.openlibrary.org/b/id/1-L.jpg", "https://openlibrary.org/works/OL1W.json", "https://user@openlibrary.org/isbn/1.json",
            "https://openlibrary.org:8443/isbn/1.json", "ftp://openlibrary.org/isbn/1", "https://openlibrary.org/account/login", "not a url", "https://openlibrary.org/../etc/passwd"))
            assertThrows(bad, UrlNotAllowedException::class.java) { policy.check(bad) }
    }

    // ---- transport through a fake connection ----

    private class FakeConnection(url: URL, val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0),
        val failWith: java.io.IOException? = null) : HttpURLConnection(url) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getResponseCode(): Int { failWith?.let { throw it }; return status }
        override fun getHeaderField(name: String?): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(body)
    }

    private fun transport(vararg responses: (URL) -> FakeConnection, pacer: RequestPacer? = null, maxBytes: Int = 1_000, redirects: Int = 2): Pair<HttpUrlConnectionTransport, MutableList<FakeConnection>> {
        val opened = ArrayList<FakeConnection>(); var i = 0
        return HttpUrlConnectionTransport(policy, "ShelfOS/test (+https://example.org)", maxBytes = maxBytes, maxRedirects = redirects, pacer = pacer,
            open = { u -> responses[i++](u).also { opened += it } }) to opened
    }

    @Test fun getSendsAnIdentifiedGetAndReturnsTheBoundedBody() = runBlocking {
        val (t, opened) = transport({ FakeConnection(it, 200, mapOf("Content-Type" to "application/json"), """{"a":1}""".toByteArray()) })
        val r = t.get("https://openlibrary.org/search.json?title=a")
        assertEquals(200, r.status); assertEquals("""{"a":1}""", r.text()); assertEquals("application/json", r.header("Content-Type"))
        val c = opened.single()
        assertEquals("GET", c.requestMethod); assertEquals("ShelfOS/test (+https://example.org)", c.getRequestProperty("User-Agent"))
        assertFalse(c.instanceFollowRedirects); assertFalse(c.useCaches); assertNull(c.getRequestProperty("Cookie")); assertNull(c.getRequestProperty("Authorization"))
        assertTrue(c.disconnected)
        Unit
    }

    @Test fun anErrorStatusIsAResponseNotAnException() = runBlocking {
        val (t, _) = transport({ FakeConnection(it, 429, mapOf("Retry-After" to "30"), "slow down".toByteArray()) })
        val r = t.get("https://openlibrary.org/isbn/9780140328721.json")
        assertEquals(429, r.status); assertEquals("30", r.header("retry-after"))
        Unit
    }

    @Test fun redirectsToAllowedPathsAreFollowedAndEachHopIsRequested() = runBlocking {
        val (t, opened) = transport({ FakeConnection(it, 302, mapOf("Location" to "/books/OL1M.json")) }, { FakeConnection(it, 200, body = "{}".toByteArray()) })
        val r = t.get("https://openlibrary.org/isbn/9780140328721.json")
        assertEquals(200, r.status); assertEquals(2, opened.size); assertEquals("/books/OL1M.json", opened[1].url.path)
        assertTrue(opened.all { it.disconnected })
        Unit
    }

    @Test fun redirectsLeavingTheAllowedSetAreRefused() = runBlocking {
        for (target in listOf("http://openlibrary.org/books/OL1M.json", "https://evil.example/x", "https://covers.openlibrary.org/b/id/1-L.jpg", "/works/OL1W.json")) {
            val (t, opened) = transport({ FakeConnection(it, 301, mapOf("Location" to target)) })
            assertThrows(target, UrlNotAllowedException::class.java) { runBlocking { t.get("https://openlibrary.org/isbn/9780140328721.json") } }
            assertEquals("no request to the refused target", 1, opened.size)
        }
        val (loop, _) = transport({ FakeConnection(it, 302, mapOf("Location" to "/isbn/1.json")) }, { FakeConnection(it, 302, mapOf("Location" to "/isbn/2.json")) },
            { FakeConnection(it, 302, mapOf("Location" to "/isbn/3.json")) }, redirects = 2)
        assertThrows(UrlNotAllowedException::class.java) { runBlocking { loop.get("https://openlibrary.org/isbn/0.json") } }
        val (noLocation, _) = transport({ FakeConnection(it, 302) })
        assertThrows(UrlNotAllowedException::class.java) { runBlocking { noLocation.get("https://openlibrary.org/isbn/0.json") } }
        Unit
    }

    @Test fun anOversizedBodyIsRefusedBeforeParsingByDeclaredAndByActualSize() = runBlocking {
        val (declared, _) = transport({ FakeConnection(it, 200, mapOf("Content-Length" to "5000"), ByteArray(10)) }, maxBytes = 1_000)
        assertThrows(ResponseTooLargeException::class.java) { runBlocking { declared.get("https://openlibrary.org/isbn/0.json") } }
        val (actual, _) = transport({ FakeConnection(it, 200, body = ByteArray(1_001)) }, maxBytes = 1_000)
        assertThrows(ResponseTooLargeException::class.java) { runBlocking { actual.get("https://openlibrary.org/isbn/0.json") } }
        val (exact, _) = transport({ FakeConnection(it, 200, body = ByteArray(1_000)) }, maxBytes = 1_000)
        assertEquals(1_000, exact.get("https://openlibrary.org/isbn/0.json").body.size)
        Unit
    }

    @Test fun networkFailuresPropagateAsIoExceptionsAndTheConnectionIsClosed() = runBlocking {
        val (t, opened) = transport({ FakeConnection(it, 0, failWith = java.net.UnknownHostException("offline")) })
        assertThrows(java.io.IOException::class.java) { runBlocking { t.get("https://openlibrary.org/isbn/0.json") } }
        assertTrue(opened.single().disconnected)
        assertThrows(UrlNotAllowedException::class.java) { runBlocking { t.get("http://openlibrary.org/isbn/0.json") } }   // checked before any connection
        assertEquals(1, opened.size)
        Unit
    }

    @Test fun utf8BodiesAreDecodedAsUtf8() = runBlocking {
        val (t, _) = transport({ FakeConnection(it, 200, body = "{\"t\":\"Cien años de soledad 吾輩\"}".toByteArray(Charsets.UTF_8)) })
        assertEquals("Cien años de soledad 吾輩", Json.parse(t.get("https://openlibrary.org/search.json?title=a").text()).asObject()!!["t"])
        Unit
    }

    // ---- pacer ----

    @Test fun pacerKeepsRequestStartsAtLeastTheIntervalApartAndNeverOverlaps() = runTest {
        val pacer = RequestPacer(2_000, { testScheduler.currentTime }, { delay(it) })
        val starts = ArrayList<Long>(); var inFlight = 0; var max = 0
        (1..4).map { async { pacer.paced { starts += testScheduler.currentTime; inFlight++; max = maxOf(max, inFlight); delay(300); inFlight-- } } }.awaitAll()
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L), starts); assertEquals(1, max)
    }

    @Test fun everyRedirectHopGoesThroughThePacer() = runBlocking {
        // Real time on purpose: the transport runs on Dispatchers.IO, which virtual test time does not control.
        val now = { System.nanoTime() / 1_000_000 }
        val pacer = RequestPacer(300, now)
        val starts = ArrayList<Long>()
        val (t, _) = transport({ starts += now(); FakeConnection(it, 302, mapOf("Location" to "/books/OL1M.json")) },
            { starts += now(); FakeConnection(it, 200, body = "{}".toByteArray()) }, pacer = pacer)
        t.get("https://openlibrary.org/isbn/9780140328721.json")
        assertEquals(2, starts.size); assertTrue("hops ${starts[1] - starts[0]} ms apart", starts[1] - starts[0] >= 290)
        Unit
    }
}
