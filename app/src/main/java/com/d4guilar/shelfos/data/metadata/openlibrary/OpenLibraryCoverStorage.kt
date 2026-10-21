// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata.openlibrary

/**
 * What Open Library's cover store is documented (by its source, `coverstore/code.py` and `coverstore/archive.py`) to redirect a cover
 * request to, derived ONLY from the original cover id and size (Phase 4.5C.2B R1, finding F-2). This is a pure, unit-tested specification.
 * **It is not wired into any transport**: the pilot's image policy still refuses every redirect off `covers.openlibrary.org`, and nothing here
 * enables an Archive.org request. It exists so a later, separately authorized change can accept exactly these first hops and nothing broader.
 *
 * - Family A (legacy cluster, sizes L/unsized only, ids below an operator-configured bound that is not public):
 *   `https://archive.org/download/olcovers{id/10000}/olcovers{id/10000}-{SIZE}.zip/{id}-{SIZE}.jpg`.
 * - Family B (batched uploads, ids >= 8,000,000): `https://archive.org/download/{p}covers_{id/1e6:04}/{p}covers_{id/1e6:04}_{(id%1e6)/1e4:02}.zip/{id:010}-{SIZE}.jpg`
 *   where `p` is `s_`/`m_`/`l_` for a sized request.
 * The size prefix is the lowercased requested size plus an underscore (`l_`, `m_`, `s_`); the empty prefix belongs to unsized originals, which this
 * pilot never requests. Unknown: the second hop (archive.org -> a data-serving host).
 */
object OpenLibraryCoverStorage {
    private const val FAMILY_B_MIN_ID = 8_000_000L

    /** The exact first-hop URLs this cover id/size could redirect to. Empty for an invalid id. Strict equality is the only accepted match. */
    fun firstHops(coverId: Long, size: CoverSize): List<String> {
        if (coverId !in 1..999_999_999_999L) return emptyList()
        val out = ArrayList<String>()
        if (size == CoverSize.LARGE) {
            val item = "olcovers${coverId / 10_000}"
            out += "https://archive.org/download/$item/$item-L.zip/$coverId-L.jpg"
        }
        if (coverId >= FAMILY_B_MIN_ID) {
            val millions = "%04d".format(coverId / 1_000_000)
            val batch = "%02d".format((coverId % 1_000_000) / 10_000)
            val pcid = "%010d".format(coverId)
            val folder = "${size.letter.lowercase()}_covers_$millions"
            out += "https://archive.org/download/$folder/${folder}_$batch.zip/$pcid-${size.letter}.jpg"
        }
        return out
    }

    fun isExpectedFirstHop(url: String, coverId: Long, size: CoverSize) = url in firstHops(coverId, size)
}
