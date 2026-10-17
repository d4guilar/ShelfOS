// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import java.util.TreeSet

/** Where a contributing provider reported an edition; survives clustering so provenance is never lost. */
data class ProviderRef(val providerId: String, val providerRef: String)

/** Candidates that share exactly the same ISBN-13 set. Cover references stay attached to their original provider. */
data class EditionCluster(val key: String, val members: List<MetadataCandidate>) {
    val providerIds: Set<String> get() = members.mapTo(LinkedHashSet()) { it.providerId }
    val refs: List<ProviderRef> get() = members.map { ProviderRef(it.providerId, it.providerRef) }
    val covers: List<CoverCandidate> get() = members.flatMap { it.covers }
}

/** [representative] is the member [MetadataMatcher] trusts most; [corroborating] are distinct providers whose member matched without rejection. */
data class RankedEdition(val cluster: EditionCluster, val representative: MetadataCandidate, val evidence: MatchEvidence, val corroborating: Set<String>)

data class EditionRanking(val apply: RankedEdition?, val ranked: List<RankedEdition>) {
    fun toDecision() = MatchDecision(apply?.let { ScoredCandidate(it.representative, it.evidence) }, ranked.map { ScoredCandidate(it.representative, it.evidence) })
}

/**
 * Edition-aware clustering and deterministic ranking (BOOK_PROVIDERS §3.5, D30). Provider results are evidence: clusters
 * form only from an identical ISBN-13 set (ISBN-10 converted), never transitively and never by title, author, series or
 * cover, so another ISBN, volume, issue or omnibus stays a separate candidate. Each cluster takes the tier [MetadataMatcher]
 * gives its best single member; agreement ranks and corroborates but never raises that tier. Members that disagree about
 * the edition (title, volume, issue: [MetadataMatcher.demoteAmbiguous]) drop to MEDIUM and do not corroborate, and a
 * member rejected against the query also caps the cluster at MEDIUM, so a disagreeing cluster is never applied. Distinct disagreeing VERY_HIGH editions demote exactly as in 4.5B.
 */
object EditionClustering {
    fun cluster(candidates: List<MetadataCandidate>): List<EditionCluster> =
        candidates.distinctBy { it.providerId to it.providerRef }.groupBy(::key).map { (k, ms) ->
            EditionCluster(k, ms.sortedWith(compareBy({ it.providerId }, { it.providerRef })))
        }.sortedBy { it.key }

    private fun key(c: MetadataCandidate): String {
        val isbns = c.values.identifiers.mapNotNull { it.isbn13 }.distinct().sorted()
        return if (isbns.isEmpty()) "ref:${c.providerId}:${c.providerRef}" else "isbn13:" + isbns.joinToString(",")
    }

    /** [priority] orders providers (lower first) for tie-breaking; it never changes a tier. */
    fun rank(query: MetadataQuery, candidates: List<MetadataCandidate>, priority: (String) -> Int = { 0 }): EditionRanking {
        val pre = cluster(candidates).map { cl ->
            val raw = cl.members.map { ScoredCandidate(it, MetadataMatcher.score(query, it)) }
            // Members that are not the same edition (title, volume, issue) cannot corroborate each other: the 4.5B ambiguity rule applies inside the cluster too.
            val scored = MetadataMatcher.demoteAmbiguous(raw)
            val best = scored.minWith(compareBy<ScoredCandidate>({ it.evidence.rejected }, { it.evidence.tier.ordinal }, { -it.evidence.agreements.size },
                { priority(it.candidate.providerId) }, { it.candidate.providerId }, { it.candidate.providerRef }))
            val contested = !best.evidence.rejected && scored.any { it.evidence.rejected }
            val evidence = if (contested && best.evidence.tier == MatchConfidence.VERY_HIGH)
                best.evidence.copy(tier = MatchConfidence.MEDIUM, contradictions = best.evidence.contradictions + MatchSignal.AMBIGUOUS) else best.evidence
            val corroborating = scored.filter { !it.evidence.rejected && it.evidence.tier != MatchConfidence.LOW && MatchSignal.AMBIGUOUS !in it.evidence.contradictions }
                .mapTo(TreeSet()) { it.candidate.providerId }
            Triple(cl, ScoredCandidate(best.candidate, evidence), corroborating)
        }
        val demoted = MetadataMatcher.demoteAmbiguous(pre.map { it.second })
        val ranked = pre.indices.map { i -> RankedEdition(pre[i].first, demoted[i].candidate, demoted[i].evidence, pre[i].third) }
            .sortedWith(compareBy<RankedEdition>({ it.evidence.rejected }, { it.evidence.tier.ordinal }, { MatchSignal.IDENTIFIER !in it.evidence.agreements },
                { it.evidence.contradictions.size }, { -it.corroborating.size }, { -it.evidence.agreements.size },
                { priority(it.representative.providerId) }, { it.cluster.key }))
        return EditionRanking(ranked.firstOrNull { it.evidence.tier == MatchConfidence.VERY_HIGH && !it.evidence.rejected }, ranked)
    }
}
