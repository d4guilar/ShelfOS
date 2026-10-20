// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.network

import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibraryCovers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A scripted HttpURLConnection: no socket is ever opened. */
private class StubConnection(url: URL, val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0),
    val stream: InputStream? = null) : HttpURLConnection(url) {
    val sent = HashMap<String, String>()
    var disconnected = false
    override fun getResponseCode() = status
    override fun getHeaderField(name: String?): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    override fun getInputStream(): InputStream = stream ?: ByteArrayInputStream(body)
    override fun setRequestProperty(key: String, value: String) { sent[key.lowercase()] = value }
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun connect() {}
}

class ImageTransportTest {
    @get:Rule val tmp = TemporaryFolder()
    private val jpeg = ByteArray(2_000) { it.toByte() }
    private val url = "https://covers.openlibrary.org/b/id/5-L.jpg?default=false"
    private val opened = ArrayList<StubConnection>()

    private fun transport(maxRedirects: Int = 2, pacer: RequestPacer? = null, script: (URL) -> StubConnection) = HttpUrlConnectionImageTransport(
        OpenLibraryCovers.urlPolicy, "ShelfOS/test", pacer, OpenLibraryCovers::check, maxRedirects = maxRedirects, open = { u -> script(u).also { opened += it } })

    private fun dest() = File(tmp.root, "d.tmp")

    @Test fun `a 200 streams into the destination with no credentials or cookies`() = runTest {
        val t = transport { StubConnection(it, 200, mapOf("Content-Type" to "image/jpeg"), jpeg) }
        val r = t.fetch(url, dest(), 4096)
        assertEquals(200, r.status); assertEquals("image/jpeg", r.contentType); assertEquals(jpeg.size.toLong(), r.bytes)
        assertTrue(dest().readBytes().contentEquals(jpeg))
        val sent = opened.single().sent
        assertEquals("ShelfOS/test", sent["user-agent"]); assertEquals("identity", sent["accept-encoding"])
        assertNull(sent["cookie"]); assertNull(sent["authorization"])
        assertTrue(opened.single().disconnected)
        assertFalse(opened.single().instanceFollowRedirects)
    }

    @Test fun `a non-200 writes nothing and reads no body`() = runTest {
        val t = transport { StubConnection(it, 404, mapOf("Content-Type" to "text/html"), "<html>".toByteArray()) }
        val r = t.fetch(url, dest(), 4096)
        assertEquals(404, r.status); assertEquals(0, r.bytes); assertFalse(dest().exists())
    }

    @Test fun `Retry-After is surfaced on 429`() = runTest {
        val t = transport { StubConnection(it, 429, mapOf("Retry-After" to "30")) }
        assertEquals("30", t.fetch(url, dest(), 4096).retryAfter)
    }

    @Test fun `a declared length over the cap is refused before reading`() = runTest {
        val t = transport { StubConnection(it, 200, mapOf("Content-Length" to "999999"), stream = object : InputStream() { override fun read() = throw AssertionError("must not read") }) }
        assertThrows(ResponseTooLargeException::class.java) { kotlinx.coroutines.runBlocking { t.fetch(url, dest(), 4096) } }
        assertFalse(dest().exists())
    }

    @Test fun `a body larger than the cap is cut off, the partial file removed`() = runTest {
        val t = transport { StubConnection(it, 200, body = ByteArray(10_000) { 1 }) }
        assertThrows(ResponseTooLargeException::class.java) { kotlinx.coroutines.runBlocking { t.fetch(url, dest(), 4096) } }
        assertFalse(dest().exists())
    }

    @Test fun `a stream that fails midway leaves no partial file`() = runTest {
        var sent = false
        val broken = object : InputStream() { override fun read() = throw IOException("reset")
            override fun read(b: ByteArray, o: Int, l: Int): Int { if (!sent) { sent = true; b[o] = 1; return 1 }; throw IOException("reset") } }
        val t = transport { StubConnection(it, 200, stream = broken) }
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { t.fetch(url, dest(), 4096) } }
        assertFalse(dest().exists())
    }

    @Test fun `a redirect to an unfamiliar host is refused, not followed`() = runTest {
        val t = transport { u -> if (u.host == "covers.openlibrary.org") StubConnection(u, 302, mapOf("Location" to "https://ia800000.us.archive.org/1/items/olcovers1/5-L.jpg")) else error("followed") }
        assertThrows(UrlNotAllowedException::class.java) { kotlinx.coroutines.runBlocking { t.fetch(url, dest(), 4096) } }
        assertEquals(1, opened.size)
    }

    @Test fun `redirects off https, with credentials, or to a bad path are refused`() = runTest {
        for (loc in listOf("http://covers.openlibrary.org/b/id/5-L.jpg", "https://u:p@covers.openlibrary.org/b/id/5-L.jpg", "https://covers.openlibrary.org/other.jpg",
            "https://covers.openlibrary.org/b/id/5-L.jpg?default=true", "//evil.example/b/id/5-L.jpg")) {
            opened.clear()
            val t = transport { u -> StubConnection(u, 301, mapOf("Location" to loc)) }
            assertThrows(loc, UrlNotAllowedException::class.java) { kotlinx.coroutines.runBlocking { t.fetch(url, dest(), 4096) } }
            assertEquals(1, opened.size)
        }
    }

    @Test fun `a redirect within the allowed shape is followed, but only a bounded number of times`() = runTest {
        var n = 0
        val ok = transport { u -> if (n++ == 0) StubConnection(u, 302, mapOf("Location" to "/b/id/6-L.jpg?default=false")) else StubConnection(u, 200, body = jpeg) }
        assertEquals(200, ok.fetch(url, dest(), 4096).status)
        assertEquals("/b/id/6-L.jpg", opened.last().url.path)
        opened.clear()
        val loop = transport(maxRedirects = 2) { u -> StubConnection(u, 302, mapOf("Location" to "/b/id/7-L.jpg?default=false")) }
        assertThrows(UrlNotAllowedException::class.java) { kotlinx.coroutines.runBlocking { loop.fetch(url, dest(), 4096) } }
        assertEquals(3, opened.size)
    }

    @Test fun `arbitrary urls are never requested`() = runTest {
        val t = transport { error("must not connect") }
        for (u in listOf("https://example.com/b/id/5-L.jpg", "http://covers.openlibrary.org/b/id/5-L.jpg", "file:///etc/passwd", "https://covers.openlibrary.org/b/id/../../x.jpg"))
            assertThrows(u, UrlNotAllowedException::class.java) { kotlinx.coroutines.runBlocking { t.fetch(u, dest(), 4096) } }
        assertTrue(opened.isEmpty())
    }

    @Test fun `every hop goes through the pacer`() = runTest {
        var starts = 0
        val pacer = RequestPacer(2_000, { 0L }, { })
        var n = 0
        val t = transport(pacer = pacer) { u -> starts++; if (n++ == 0) StubConnection(u, 302, mapOf("Location" to "/b/id/6-L.jpg?default=false")) else StubConnection(u, 200, body = jpeg) }
        t.fetch(url, dest(), 4096)
        assertEquals(2, starts)
    }
}
