// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata.openlibrary

import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.core.network.UrlPolicy
import com.d4guilar.shelfos.domain.metadata.MatchConfidence
import java.net.URL

/**
 * Open Library cover artwork for the debug-only Cover Lab pilot (Phase 4.5C.2B, plan D1-C-DEV). Nothing here is reachable from
 * production code: the D1-M grant still cannot authorize [com.d4guilar.shelfos.domain.metadata.MetadataCapability.COVERS], and no
 * caller exists outside the debug source set. URL shape (documented): `https://covers.openlibrary.org/b/{key}/{value}-{S|M|L}.jpg`;
 * `?default=false` makes a missing cover a 404 instead of a placeholder image.
 */
enum class CoverSize(val letter: String) { SMALL("S"), MEDIUM("M"), LARGE("L") }

/** How artwork is addressed, in order of preference: the edition's own cover id, its OLID, then an ISBN tied to the verified edition. */
sealed interface OpenLibraryCoverRef {
    val key: String
    val value: String
    data class Id(val id: Long) : OpenLibraryCoverRef { override val key get() = "id"; override val value get() = id.toString() }
    data class Olid(val olid: String) : OpenLibraryCoverRef { override val key get() = "olid"; override val value get() = olid }
    data class Isbn(val isbn13: String) : OpenLibraryCoverRef { override val key get() = "isbn"; override val value get() = isbn13 }

    fun isValid(): Boolean = when (this) {
        is Id -> id in 1..MAX_COVER_ID
        is Olid -> OLID.matches(olid)
        is Isbn -> ISBN13.matches(isbn13)
    }

    private companion object {
        const val MAX_COVER_ID = 999_999_999_999L
        val OLID = Regex("^OL\\d{1,12}M$")
        val ISBN13 = Regex("^97[89]\\d{10}$")
    }
}

object OpenLibraryCovers {
    const val HOST = "covers.openlibrary.org"

    /** The raw-transfer cap: 8x the stored-cover bound (512 KiB), far below the 32 MiB a picked file may be; Open Library's `-L.jpg` covers are well under it. */
    const val MAX_RAW_BYTES = 4 * 1024 * 1024

    /** Covers only: exact host, `/b/…`. Redirects are held to the same rule, so an unfamiliar CDN host is refused until it has been inspected. */
    val urlPolicy = UrlPolicy(setOf(HOST), listOf("/b/"))

    private val PATH = Regex("^/b/(id|olid|isbn)/([A-Za-z0-9]{1,16})-[SML]\\.jpg$")

    /** Null when [ref] is not a well-formed reference (nothing is ever built from unvalidated remote text). */
    fun url(ref: OpenLibraryCoverRef, size: CoverSize): String? =
        if (ref.isValid()) "https://$HOST/b/${ref.key}/${ref.value}-${size.letter}.jpg?default=false" else null

    /** The strict per-URL gate for the first request and every redirect hop: the documented path shape and no query other than `default=false`. */
    fun check(url: URL) {
        val path = url.path ?: ""
        val match = PATH.matchEntire(path) ?: throw UrlNotAllowedException("cover path not allowed")
        if (url.query != null && url.query != "default=false") throw UrlNotAllowedException("cover query not allowed")
        val key = match.groupValues[1]; val value = match.groupValues[2]
        val ok = when (key) {
            "id" -> value.all { it.isDigit() }
            "olid" -> Regex("^OL\\d{1,12}M$").matches(value)
            else -> Regex("^97[89]\\d{10}$").matches(value)
        }
        if (!ok) throw UrlNotAllowedException("cover identifier not allowed")
    }
}

/** Edition-specific metadata endpoints the pilot may read: ISBN → edition, and one work's first few editions. No other path. */
val OpenLibrary.coverPilotJsonPolicy: UrlPolicy get() = UrlPolicy(setOf(OpenLibrary.HOST), listOf("/isbn/", "/works/"))

/** What a candidate's edition has in common with the book, never more than the evidence supports. */
enum class CoverMatchKind { EXACT_EDITION, OTHER_EDITION }
enum class CoverWarning { DIFFERENT_ISBN, DIFFERENT_LANGUAGE, DIFFERENT_PUBLISHER, LOW_CONFIDENCE, NO_COVER_RECORDED }

/**
 * One piece of artwork and the edition it belongs to. [key] is stable for the pair (edition, artwork) and is safe in a file name.
 * A work-level image is never represented: only an edition's own cover list produces a candidate.
 */
data class OnlineCoverCandidate(
    val providerId: String, val editionRef: String, val ref: OpenLibraryCoverRef, val isbn13: String?, val title: String?, val author: String?,
    val publisher: String?, val year: Int?, val language: String?, val match: CoverMatchKind, val tier: MatchConfidence, val warnings: Set<CoverWarning>,
) {
    val key: String get() = "$editionRef-${ref.key}-${ref.value}"
}
