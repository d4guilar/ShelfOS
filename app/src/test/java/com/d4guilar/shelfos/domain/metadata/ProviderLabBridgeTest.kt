// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Phase 4.5C.P0 research bridge (tools/provider-lab). Reads normalized provider records from the TSV named by the
 * SHELFOS_LAB_IN environment variable, scores them with the PRODUCTION [MetadataMatcher] / [EditionClustering], and
 * writes one decision per (fixture, probe) to SHELFOS_LAB_OUT. Skipped when the variables are absent, so it is inert in
 * the normal suite. It adds no behaviour and mirrors no matching rule.
 */
class ProviderLabBridgeTest {
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8").ifEmpty { null }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    @Test fun scoreLabRecordsWithTheProductionMatcher() {
        val input = System.getenv("SHELFOS_LAB_IN"); val output = System.getenv("SHELFOS_LAB_OUT")
        assumeTrue("lab bridge not requested", input != null && output != null)
        val groups = LinkedHashMap<Pair<String, String>, MutableList<List<String?>>>()
        File(input!!).readLines().filter { it.isNotBlank() }.forEach { line ->
            val c = line.split("\t").map(::dec)
            groups.getOrPut(c[0]!! to c[1]!!) { ArrayList() }.add(c)
        }
        val lines = ArrayList<String>()
        for ((key, rows) in groups) {
            val f = rows.first()
            val category = MediaCategory.valueOf(f[2]!!)
            val ids = listOf(PublicationIdentifier(IdentifierType.ISBN_13, f[3]!!))
            val query = MetadataQuery(category, ids, f[4], f[5], f[6], f[7], null, null, f[8], f[9])
            val candidates = rows.map { r ->
                MetadataCandidate(r[10]!!, r[11] ?: "none", r[12], r[13], category,
                    MetadataValues(series = r[14], volume = r[15], language = r[16], publisher = r[17], publishedDate = r[18],
                        identifiers = (r[19] ?: "").split(",").filter { it.length == 13 }.map { PublicationIdentifier(IdentifierType.ISBN_13, it) }),
                    if (r[20] == "1") listOf(CoverCandidate(r[10]!!, "cover/" + (r[11] ?: "none"))) else emptyList())
            }
            val ranking = EditionClustering.rank(query, candidates)
            val top = ranking.apply ?: ranking.ranked.first()
            val e = top.evidence
            lines += listOf(key.first, key.second, e.tier.name, e.rejected.toString(), (ranking.apply != null).toString(),
                e.agreements.joinToString(",") { it.name }, e.contradictions.joinToString(",") { it.name }).joinToString("\t") { enc(it) }
        }
        File(output!!).writeText(lines.joinToString("\n") + "\n")
    }
}
