// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A response of any HTTP status. A transport throws only for failures below HTTP (no network, timeout, TLS, an oversized body, a refused redirect). */
class HttpResponse(val status: Int, private val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun text(): String = String(body, Charsets.UTF_8)
}

/** A body larger than the transport's cap was refused; it is not truncated and not parsed. */
class ResponseTooLargeException(val limit: Int) : IOException("response larger than $limit bytes")

/** A redirect (or request) that left the allowed hosts or leaves HTTPS. Never followed. */
class UrlNotAllowedException(message: String) : IOException(message)

interface HttpTransport {
    /** GET only. Cancellation closes the connection. */
    suspend fun get(url: String): HttpResponse
}

/**
 * Which URLs a transport may request: HTTPS, an exact host from [hosts], the default port, no user-info, and one of the
 * [pathPrefixes]. Applied to the first request and to every redirect target.
 */
class UrlPolicy(private val hosts: Set<String>, private val pathPrefixes: List<String>) {
    fun check(url: String): URL {
        val uri = try { URI(url) } catch (_: Exception) { throw UrlNotAllowedException("malformed URL") }
        if (uri.scheme != "https") throw UrlNotAllowedException("only https is allowed")
        if (uri.host?.lowercase() !in hosts || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) throw UrlNotAllowedException("host not allowed")
        if (pathPrefixes.none { (uri.rawPath ?: "").startsWith(it) }) throw UrlNotAllowedException("path not allowed")
        return uri.toURL()
    }
}

/**
 * Platform `HttpURLConnection` (plan D14): GET only, no cookies, no credentials, bounded connect/read timeouts, bounded body
 * ([maxBytes]), redirects followed manually (at most [maxRedirects], each re-checked by [policy]), UTF-8. Runs on [Dispatchers.IO].
 * Every HTTP request, each redirect hop included, goes through [pacer] when one is given. It never logs a URL or a body.
 * [open] is injectable for tests.
 */
class HttpUrlConnectionTransport(
    private val policy: UrlPolicy, private val userAgent: String,
    private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 10_000,
    private val maxBytes: Int = 512 * 1024, private val maxRedirects: Int = 2,
    private val pacer: RequestPacer? = null,
    /** A stricter per-URL gate than [policy]'s prefixes, applied to the first request and to every redirect hop. */
    private val check: (URL) -> Unit = {},
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : HttpTransport {
    init { require(userAgent.isNotBlank() && maxBytes > 0 && maxRedirects >= 0) }

    private sealed interface Hop {
        class Final(val response: HttpResponse) : Hop
        class Redirect(val location: String) : Hop
    }

    override suspend fun get(url: String): HttpResponse = withContext(Dispatchers.IO) {
        var target = policy.check(url).also(check)
        var hops = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val hop = pacer?.paced { once(target) } ?: once(target)
            when (hop) {
                is Hop.Final -> return@withContext hop.response
                is Hop.Redirect -> {
                    if (++hops > maxRedirects) throw UrlNotAllowedException("too many redirects")
                    target = policy.check(target.toURI().resolve(hop.location).toString()).also(check)
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun once(target: URL): Hop {
        val c = open(target)
        try {
            c.requestMethod = "GET"
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.doInput = true
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Encoding", "identity")
            val status = c.responseCode
            if (status in REDIRECTS) return Hop.Redirect(c.getHeaderField("Location") ?: throw UrlNotAllowedException("redirect without a target"))
            val declared = c.getHeaderField("Content-Length")?.toLongOrNull()
            if (declared != null && declared > maxBytes) throw ResponseTooLargeException(maxBytes)
            val stream = (if (status >= 400) c.errorStream else c.inputStream)
            val body = stream?.use { readBounded(it) } ?: ByteArray(0)
            val headers = HashMap<String, String>()
            c.getHeaderField("Retry-After")?.let { headers["retry-after"] = it }
            c.getHeaderField("Content-Type")?.let { headers["content-type"] = it }
            return Hop.Final(HttpResponse(status, headers, body))
        } finally {
            c.disconnect()
        }
    }

    private suspend fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw ResponseTooLargeException(maxBytes)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object { val REDIRECTS = setOf(301, 302, 303, 307, 308) }
}
