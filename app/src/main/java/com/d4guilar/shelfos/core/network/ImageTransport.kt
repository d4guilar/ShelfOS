// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** What a fetch learned. The body, if any, is in the caller's destination file; [bytes] is how much was written (0 unless [status] is 200). */
class ImageFetch(val status: Int, val contentType: String?, val retryAfter: String?, val bytes: Long)

/** Image-capable counterpart of [HttpTransport] (Cover Lab pilot, D1-C-DEV): the body goes to a file, never into memory. */
interface ImageTransport {
    /**
     * GET [url]. On HTTP 200 the body is streamed into [destination] and at most [maxBytes] are accepted, otherwise
     * [ResponseTooLargeException] and [destination] is deleted. Any other status writes nothing. Cancellation closes the connection
     * and removes the partial file.
     */
    suspend fun fetch(url: String, destination: File, maxBytes: Int): ImageFetch
}

/**
 * Platform `HttpURLConnection` for artwork: GET only, HTTPS and exact hosts from [policy] (first request and every redirect hop),
 * [check] as the strict per-URL gate, no cookies or credentials, bounded timeouts, bounded body, `Accept-Encoding: identity`. Each
 * request goes through [pacer]. It never logs a URL, a header or a body. [open] is injectable for tests.
 */
class HttpUrlConnectionImageTransport(
    private val policy: UrlPolicy, private val userAgent: String, private val pacer: RequestPacer?,
    private val check: (URL) -> Unit = {},
    private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 15_000, private val maxRedirects: Int = 2,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : ImageTransport {
    init { require(userAgent.isNotBlank() && maxRedirects >= 0) }

    private sealed interface Hop {
        class Final(val fetch: ImageFetch) : Hop
        class Redirect(val location: String) : Hop
    }

    private fun allowed(url: String): URL = policy.check(url).also(check)

    override suspend fun fetch(url: String, destination: File, maxBytes: Int): ImageFetch = withContext(Dispatchers.IO) {
        require(maxBytes > 0)
        var target = allowed(url)
        var hops = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val t = target
                val hop = pacer?.paced { once(t, destination, maxBytes) } ?: once(t, destination, maxBytes)
                when (hop) {
                    is Hop.Final -> return@withContext hop.fetch
                    is Hop.Redirect -> {
                        if (++hops > maxRedirects) throw UrlNotAllowedException("too many redirects")
                        target = allowed(target.toURI().resolve(hop.location).toString())
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } catch (e: Throwable) {
            destination.delete()
            throw e
        }
    }

    private suspend fun once(target: URL, destination: File, maxBytes: Int): Hop {
        val c = open(target)
        try {
            c.requestMethod = "GET"
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.doInput = true
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
            c.setRequestProperty("Accept-Encoding", "identity")
            val status = c.responseCode
            if (status in REDIRECTS) return Hop.Redirect(c.getHeaderField("Location") ?: throw UrlNotAllowedException("redirect without a target"))
            val type = c.getHeaderField("Content-Type")
            val retry = c.getHeaderField("Retry-After")
            if (status != 200) return Hop.Final(ImageFetch(status, type, retry, 0))   // an error body is never read
            val declared = c.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > maxBytes) throw ResponseTooLargeException(maxBytes)
            var total = 0L
            c.inputStream.use { input ->
                FileOutputStream(destination).use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw ResponseTooLargeException(maxBytes)
                        out.write(buffer, 0, n)
                    }
                    out.fd.sync()
                }
            }
            return Hop.Final(ImageFetch(status, type, retry, total))
        } finally {
            c.disconnect()
        }
    }

    private companion object { val REDIRECTS = setOf(301, 302, 303, 307, 308) }
}
