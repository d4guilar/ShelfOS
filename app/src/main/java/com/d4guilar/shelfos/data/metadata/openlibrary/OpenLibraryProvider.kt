// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata.openlibrary

import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.network.HttpResponse
import com.d4guilar.shelfos.core.network.HttpTransport
import com.d4guilar.shelfos.core.network.Json
import com.d4guilar.shelfos.core.network.JsonException
import com.d4guilar.shelfos.core.network.ResponseTooLargeException
import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.core.network.UrlPolicy
import com.d4guilar.shelfos.core.network.asList
import com.d4guilar.shelfos.core.network.asObject
import com.d4guilar.shelfos.core.network.asText
import com.d4guilar.shelfos.data.metadata.RetryAfter
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import java.io.IOException
import java.net.URLEncoder

/**
 * Open Library public, keyless book metadata (Phase 4.5C.2A, plan D1-M; BOOK_PROVIDERS §7). Textual metadata only: cover
 * identifiers and URLs in a response are never read into a [MetadataCandidate], so the metadata path cannot fetch artwork (D1-C stays unresolved).
 * [OpenLibraryProvider.coverEditions] is the separate reader of an edition's cover ids for the Cover Lab (D1-C-DEV) and the manual cover picker (D1-C-PROD).
 * Endpoints (all GET, HTTPS, `openlibrary.org`): `/isbn/{isbn13}.json` (redirects to the edition record), `/authors/{olid}.json`
 * (at most [MAX_AUTHORS] per lookup) and `/search.json` (title/author, minimal `fields`, [SEARCH_LIMIT] results).
 *
 * A search result is a *work*; it becomes a candidate with only a title and creator (ref ends in `W`) and never carries an
 * edition's publisher, ISBN, date or language. An unresolved `/authors/…` reference is never shown as a name.
 */
object OpenLibrary {
    const val ID = "openlibrary"
    const val HOST = "openlibrary.org"
    const val MAX_AUTHORS = 2
    const val SEARCH_LIMIT = 10
    const val MAX_RESPONSE_BYTES = 512 * 1024
    const val MAX_DESCRIPTION = 4_000
    /** The Cover Lab's whole candidate set: the exact edition plus at most seven alternatives. */
    const val COVER_EDITIONS_MAX = 8

    /** Only the documented API paths; a redirect anywhere else is refused. */
    val urlPolicy = UrlPolicy(setOf(HOST), listOf("/isbn/", "/books/", "/authors/", "/search.json"))

    /** ShelfOS's own budget (not Open Library's published 1 req/s anonymous / 3 req/s identified): one request in flight, at least 2 s apart. */
    const val MIN_INTERVAL_MS = 2_000L

    fun descriptor(): ProviderDescriptor = ProviderDescriptor(
        id = ID, displayNameRes = R.string.provider_openlibrary_name, categories = setOf(MediaCategory.BOOK),
        capabilities = setOf(MetadataCapability.IDENTIFIER_LOOKUP, MetadataCapability.SEARCH), credential = CredentialRequirement.NONE,
        termsStatus = TermsStatus.CONDITIONAL, termsCheckedOn = "2026-10-19",
        rate = RatePolicy(permits = 1, perMillis = MIN_INTERVAL_MS, burst = 1, maxConcurrency = 1, maxWaitMs = 10_000, maxQueued = 4),
        retry = RetryPolicy(maxAttempts = 2, attemptTimeoutMs = 45_000, breakerThreshold = 3, breakerCooldownMs = 15 * 60_000L),
        attribution = ProviderAttribution(R.string.provider_openlibrary_attribution, "https://openlibrary.org"),
        privacyDisclosureRes = R.string.provider_openlibrary_disclosure,
        grant = ScopedGrant(ID, "D1-M", setOf(MediaCategory.BOOK), setOf(MetadataCapability.IDENTIFIER_LOOKUP, MetadataCapability.SEARCH)),
    )

    /** A search-result (work-level) reference ends in `W`; an edition reference ends in `M`. */
    fun isWorkLevel(providerRef: String) = providerRef.endsWith("W")
}

class OpenLibraryProvider(private val http: HttpTransport, private val wallClockMs: () -> Long = System::currentTimeMillis) : MetadataProvider {
    override val id = OpenLibrary.ID
    override val categories = setOf(MediaCategory.BOOK)
    override val capabilities = setOf(MetadataCapability.IDENTIFIER_LOOKUP, MetadataCapability.SEARCH)

    override suspend fun lookup(identifier: PublicationIdentifier): List<MetadataCandidate> {
        val isbn = identifier.isbn13 ?: return emptyList()
        if (!IDENTIFIER.matches(isbn)) return emptyList()
        val response = get("https://${OpenLibrary.HOST}/isbn/$isbn.json") ?: return emptyList()
        val edition = parseObject(response)
        val ref = edition.asText("key")?.substringAfterLast('/')?.takeIf { EDITION_KEY.matches(it) } ?: throw malformed()
        val ids = isbns(edition)
        // The record must name the ISBN that was asked for. One that lists other ISBNs only is a different edition: dropped, not trusted.
        if (ids.isNotEmpty() && ids.none { it.isbn13 == isbn }) return emptyList()
        val title = edition.asText("title") ?: throw malformed()
        val authors = authorNames(edition)
        return listOf(MetadataCandidate(OpenLibrary.ID, ref, title, authors.firstOrNull(), MediaCategory.BOOK,
            MetadataValues(subtitle = edition.asText("subtitle"), description = description(edition), publisher = edition.asList("publishers")?.firstNotNullOfOrNull { it.asText() },
                publishedDate = editionDate(edition.asText("publish_date")), language = language(edition), identifiers = ids), emptyList()))
    }

    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        val title = query.title?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val author = query.creator?.trim()?.take(120)?.takeIf { it.isNotEmpty() }
        val url = buildString {
            append("https://${OpenLibrary.HOST}/search.json?title=").append(enc(title))
            if (author != null) append("&author=").append(enc(author))
            append("&fields=key,title,author_name&limit=").append(OpenLibrary.SEARCH_LIMIT)
        }
        val response = get(url) ?: return emptyList()
        val docs = parseObject(response).asList("docs") ?: throw malformed()
        return docs.take(OpenLibrary.SEARCH_LIMIT).mapNotNull { d ->
            val o = d.asObject() ?: return@mapNotNull null
            val ref = o.asText("key")?.substringAfterLast('/')?.takeIf { WORK_KEY.matches(it) } ?: return@mapNotNull null
            val t = o.asText("title") ?: return@mapNotNull null
            MetadataCandidate(OpenLibrary.ID, ref, t, o.asList("author_name")?.firstNotNullOfOrNull { it.asText() }, MediaCategory.BOOK, MetadataValues(), emptyList())
        }
    }

    /**
     * Cover discovery for the Cover Lab (Phase 4.5C.2B) and the production manual picker (Phase 4.5C.3A). The edition must name [isbn13] itself (stricter than [lookup], which
     * tolerates a record with no ISBNs): that record is the exact edition. If it belongs to exactly one work, that work's first editions
     * (one bounded request, at most [maxEditions] in total) are offered as alternatives, each only if its own record lists a cover.
     * A work-level `cover_i` is never read. Does not change [lookup] or [search].
     */
    suspend fun coverEditions(isbn13: String, maxEditions: Int = OpenLibrary.COVER_EDITIONS_MAX): List<CoverEdition> {
        if (!IDENTIFIER.matches(isbn13) || maxEditions < 1) return emptyList()
        val response = get("https://${OpenLibrary.HOST}/isbn/$isbn13.json") ?: return emptyList()
        val record = parseObject(response)
        val ref = record.asText("key")?.substringAfterLast('/')?.takeIf { EDITION_KEY.matches(it) } ?: throw malformed()
        val ids = isbns(record)
        if (ids.none { it.isbn13 == isbn13 }) return emptyList()
        val exact = coverEdition(record, ref, ids, exact = true)
        val works = record.asList("works")?.mapNotNull { it.asObject()?.asText("key")?.substringAfterLast('/')?.takeIf { k -> WORK_KEY.matches(k) } }?.distinct().orEmpty()
        val work = works.singleOrNull() ?: return listOf(exact)
        if (maxEditions == 1) return listOf(exact)
        val alternatives = try {
            // One bounded editions page (`limit` = maxEditions); the exact edition is dropped from it if listed again, and the combined list is capped at maxEditions.
            val r = get("https://${OpenLibrary.HOST}/works/$work/editions.json?limit=$maxEditions") ?: return listOf(exact)
            parseObject(r).asList("entries").orEmpty().take(maxEditions).mapNotNull { e ->
                val o = e.asObject() ?: return@mapNotNull null
                val k = o.asText("key")?.substringAfterLast('/')?.takeIf { EDITION_KEY.matches(it) && it != ref } ?: return@mapNotNull null
                val eids = isbns(o)
                coverEdition(o, k, eids, exact = eids.any { it.isbn13 == isbn13 }).takeIf { it.coverIds.isNotEmpty() }
            }
        } catch (e: ProviderException) {
            // Stopping signals must not be swallowed; a bad alternative list just leaves the exact edition alone.
            if (e.kind == ProviderFailureKind.ACCESS_BLOCKED || e.kind == ProviderFailureKind.RATE_LIMITED || e.kind == ProviderFailureKind.OFFLINE || e.kind == ProviderFailureKind.TIMEOUT) throw e
            emptyList()
        }
        return (listOf(exact) + alternatives).distinctBy { it.ref }.take(maxEditions)
    }

    private fun coverEdition(o: Map<String, Any?>, ref: String, ids: List<PublicationIdentifier>, exact: Boolean) = CoverEdition(
        ref, o.asText("title"), o.asList("covers").orEmpty().mapNotNull { (it as? Number)?.toLong()?.takeIf { id -> id > 0 } }.distinct().take(MAX_COVERS_PER_EDITION),
        ids, o.asList("publishers")?.firstNotNullOfOrNull { it.asText() }, editionDate(o.asText("publish_date")), language(o), exact)

    /** A GET mapped to the closed failure set. Null means "no such record" (404): a valid, empty answer, not a failure. */
    private suspend fun get(url: String): HttpResponse? {
        val r = try { http.get(url) } catch (e: ResponseTooLargeException) { throw ProviderException(ProviderFailureKind.MALFORMED, null, e)
        } catch (e: UrlNotAllowedException) { throw ProviderException(ProviderFailureKind.MALFORMED, null, e)
        } catch (e: java.net.SocketTimeoutException) { throw ProviderException(ProviderFailureKind.TIMEOUT, null, e)
        } catch (e: IOException) { throw ProviderException(ProviderFailureKind.OFFLINE, null, e) }
        return when (r.status) {
            200 -> r
            404 -> null
            403 -> throw ProviderException(ProviderFailureKind.ACCESS_BLOCKED)
            429 -> throw ProviderException(ProviderFailureKind.RATE_LIMITED, RetryAfter.parse(r.header("retry-after"), wallClockMs()))
            in 500..599 -> throw ProviderException(ProviderFailureKind.SERVER_FAILURE)
            else -> throw malformed()
        }
    }

    private fun parseObject(r: HttpResponse): Map<String, Any?> {
        if (r.header("content-type")?.contains("json", ignoreCase = true) != true) throw malformed()   // an HTML error page is not JSON
        return try { Json.parse(r.text()).asObject() ?: throw malformed() } catch (e: JsonException) { throw ProviderException(ProviderFailureKind.MALFORMED, null, e) }
    }

    /** Author names from the edition's own `authors` references: at most [OpenLibrary.MAX_AUTHORS] requests; only a real name is used. */
    private suspend fun authorNames(edition: Map<String, Any?>): List<String> {
        val keys = edition.asList("authors")?.mapNotNull { it.asObject()?.asText("key")?.substringAfterLast('/')?.takeIf { k -> AUTHOR_KEY.matches(k) } }
            ?.distinct()?.take(OpenLibrary.MAX_AUTHORS).orEmpty()
        val names = ArrayList<String>()
        for (key in keys) {
            val r = try { get("https://${OpenLibrary.HOST}/authors/$key.json") } catch (e: ProviderException) {
                // Stopping signals must not be swallowed; any other author problem just leaves that creator unresolved.
                if (e.kind == ProviderFailureKind.ACCESS_BLOCKED || e.kind == ProviderFailureKind.RATE_LIMITED || e.kind == ProviderFailureKind.OFFLINE || e.kind == ProviderFailureKind.TIMEOUT) throw e
                continue
            } ?: continue
            val name = try { parseObject(r).asText("name") } catch (_: ProviderException) { null }
            if (name != null && !name.startsWith("/")) names += name
        }
        return names
    }

    private fun isbns(edition: Map<String, Any?>): List<PublicationIdentifier> =
        (edition.asList("isbn_13").orEmpty() + edition.asList("isbn_10").orEmpty()).mapNotNull { IdentifierDetector.isbn(it.asText() ?: return@mapNotNull null, schemeSaysIsbn = true) }.distinct()

    private fun description(edition: Map<String, Any?>): String? {
        val raw = edition["description"].let { it.asText() ?: it.asObject()?.asText("value") } ?: return null
        return raw.take(OpenLibrary.MAX_DESCRIPTION).trim().takeIf { it.isNotEmpty() }
    }

    private fun language(edition: Map<String, Any?>): String? {
        val keys = edition.asList("languages")?.mapNotNull { it.asObject()?.asText("key")?.substringAfterLast('/') }?.distinct().orEmpty()
        return keys.singleOrNull()?.let { LANGUAGES[it] }
    }

    private fun Map<String, Any?>.asText(key: String): String? = this[key].asText()
    private fun Map<String, Any?>.asList(key: String): List<Any?>? = this[key].asList()
    private fun malformed() = ProviderException(ProviderFailureKind.MALFORMED)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val MAX_COVERS_PER_EDITION = 3
        val IDENTIFIER = Regex("^97[89]\\d{10}$")
        val EDITION_KEY = Regex("^OL\\d{1,12}M$")
        val WORK_KEY = Regex("^OL\\d{1,12}W$")
        val AUTHOR_KEY = Regex("^OL\\d{1,12}A$")
        val LANGUAGES = mapOf("eng" to "en", "spa" to "es", "por" to "pt", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "ita" to "it",
            "jpn" to "ja", "cat" to "ca", "glg" to "gl", "eus" to "eu", "baq" to "eu", "rus" to "ru", "chi" to "zh", "zho" to "zh", "kor" to "ko",
            "lat" to "la", "dut" to "nl", "nld" to "nl", "pol" to "pl", "swe" to "sv")
        val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        val YEAR = Regex("^(\\d{4})$")
        val ISO = Regex("^\\d{4}-\\d{2}(-\\d{2})?$")
        val MONTH_YEAR = Regex("^([A-Za-z]{3,9})\\.? (\\d{4})$")
        val MONTH_DAY_YEAR = Regex("^([A-Za-z]{3,9})\\.? (\\d{1,2}),? (\\d{4})$")

        /** Open Library dates are free text. Only the clear forms are read ("2003", "2003-06", "June 2003", "June 23, 2003"); anything else is left unknown. */
        fun editionDate(raw: String?): String? {
            val t = raw?.trim() ?: return null
            if (YEAR.matches(t) || ISO.matches(t)) return normalizedDate(t)
            fun month(name: String) = MONTHS.indexOfFirst { it.startsWith(name.lowercase()) && name.length >= 3 }.takeIf { it >= 0 }?.plus(1)
            MONTH_YEAR.matchEntire(t)?.let { m -> return month(m.groupValues[1])?.let { normalizedDate("%s-%02d".format(m.groupValues[2], it)) } }
            MONTH_DAY_YEAR.matchEntire(t)?.let { m -> return month(m.groupValues[1])?.let { normalizedDate("%s-%02d-%02d".format(m.groupValues[3], it, m.groupValues[2].toInt())) } }
            return null
        }
    }
}

/** An edition record as the Cover Lab reads it. [exact] means its own record lists the requested ISBN-13; only then is it the book's edition. */
data class CoverEdition(
    val ref: String, val title: String?, val coverIds: List<Long>, val ids: List<PublicationIdentifier>, val publisher: String?,
    val publishedDate: String?, val language: String?, val exact: Boolean,
)
