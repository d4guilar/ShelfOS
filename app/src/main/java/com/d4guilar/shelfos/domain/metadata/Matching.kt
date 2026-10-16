// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import java.text.Normalizer
import java.util.Locale

/**
 * Provider-neutral enrichment contract (plan §10). One [MetadataProvider] abstraction for every category: Comics and
 * Manga differ by [MetadataProvider.categories] and the series/volume/issue a [MetadataQuery] carries. Providers return
 * raw candidates; only [MetadataMatcher] assigns confidence, and only the coordinator writes Room.
 */
enum class MetadataCapability { IDENTIFIER_LOOKUP, SEARCH, COVERS }

data class MetadataQuery(
    val category: MediaCategory, val identifiers: List<PublicationIdentifier> = emptyList(), val title: String? = null,
    val creator: String? = null, val series: String? = null, val volume: String? = null, val issue: String? = null,
    val year: Int? = null, val language: String? = null, val publisher: String? = null,
    /** The title came from the file name: weak evidence that may agree but never contradicts a candidate. */
    val titleIsInferred: Boolean = false,
)

/** A provider's cover reference; URL resolution stays inside the provider. Never applied automatically in 4.5B. */
data class CoverCandidate(val providerId: String, val ref: String, val widthPx: Int? = null, val heightPx: Int? = null, val editionLabel: String? = null)

/** What a provider found. It carries no confidence: [MetadataMatcher] decides that. */
data class MetadataCandidate(
    val providerId: String, val providerRef: String, val title: String? = null, val creator: String? = null,
    val category: MediaCategory? = null, val values: MetadataValues = MetadataValues(), val covers: List<CoverCandidate> = emptyList(),
)

interface MetadataProvider {
    val id: String
    val categories: Set<MediaCategory>
    val capabilities: Set<MetadataCapability>
    suspend fun lookup(identifier: PublicationIdentifier): List<MetadataCandidate>
    suspend fun search(query: MetadataQuery): List<MetadataCandidate>
}

enum class MatchSignal { IDENTIFIER, CATEGORY, TITLE, CREATOR, SERIES, VOLUME, ISSUE, PUBLISHER, YEAR, LANGUAGE, EDITION, AMBIGUOUS }

/** Explainable evidence: which signals agreed, which contradicted, and whether a hard rule rejected the candidate. */
data class MatchEvidence(val tier: MatchConfidence, val agreements: Set<MatchSignal>, val contradictions: Set<MatchSignal>, val rejected: Boolean = false)

data class ScoredCandidate(val candidate: MetadataCandidate, val evidence: MatchEvidence)

/** [apply] is the single candidate safe to apply automatically (VERY_HIGH, unambiguous), else null. */
data class MatchDecision(val apply: ScoredCandidate?, val ranked: List<ScoredCandidate>)

/**
 * Deterministic matching (§12, §15). VERY_HIGH needs independent identification and no contradiction: an exact
 * identifier, or (Comics only) series + issue plus two of creator/publisher/year. Title equality alone is never
 * VERY_HIGH. A different volume/issue or category is a hard rejection; any other contradiction caps at MEDIUM. Manga
 * without an identifier and Documents without one never reach VERY_HIGH. Two disagreeing VERY_HIGH candidates both
 * drop to MEDIUM, so nothing applies.
 */
object MetadataMatcher {
    private val editionWords = setOf("omnibus", "collection", "collected", "complete", "box", "boxset", "deluxe", "compendium", "tpb", "trade")

    fun score(query: MetadataQuery, c: MetadataCandidate): MatchEvidence {
        val agree = HashSet<MatchSignal>(); val contra = HashSet<MatchSignal>(); var rejected = false
        fun compare(signal: MatchSignal, a: String?, b: String?, equal: (String, String) -> Boolean = { x, y -> normalizeText(x) == normalizeText(y) }) {
            if (a.isNullOrBlank() || b.isNullOrBlank()) return
            if (equal(a, b)) agree += signal else contra += signal
        }
        val ids = c.values.identifiers
        when {
            query.identifiers.any { q -> ids.any(q::sameAs) } -> agree += MatchSignal.IDENTIFIER
            // Both sides name an ISBN, and they differ: a different edition, never the same publication.
            query.identifiers.any { it.isbn13 != null } && ids.any { it.isbn13 != null } -> contra += MatchSignal.IDENTIFIER
        }
        if (c.category != null && c.category != query.category) { rejected = true; contra += MatchSignal.CATEGORY }
        if (query.titleIsInferred) { if (!query.title.isNullOrBlank() && !c.title.isNullOrBlank() && normalizeText(query.title) == normalizeText(c.title)) agree += MatchSignal.TITLE }
        else compare(MatchSignal.TITLE, query.title, c.title)
        compare(MatchSignal.CREATOR, query.creator, c.creator, ::sameCreator)
        compare(MatchSignal.SERIES, query.series, c.values.series)
        compare(MatchSignal.PUBLISHER, query.publisher, c.values.publisher)
        compare(MatchSignal.YEAR, query.year?.toString(), c.values.publishedDate?.take(4))
        compare(MatchSignal.LANGUAGE, query.language, c.values.language) { a, b -> a.substringBefore('-').equals(b.substringBefore('-'), true) }
        for ((signal, q, v) in listOf(Triple(MatchSignal.VOLUME, query.volume, c.values.volume), Triple(MatchSignal.ISSUE, query.issue, c.values.issue))) {
            when {
                q.isNullOrBlank() -> Unit
                v.isNullOrBlank() -> if (query.category.sequential) contra += signal   // a collection or series record, not this item
                normalizeSequence(q) == normalizeSequence(v) -> agree += signal
                else -> { rejected = true; contra += signal }
            }
        }
        if (!query.titleIsInferred && editionKind(query.title) != editionKind(c.title) && !query.title.isNullOrBlank() && !c.title.isNullOrBlank()) contra += MatchSignal.EDITION
        val tier = when {
            rejected -> MatchConfidence.LOW
            MatchSignal.IDENTIFIER in agree && contra.isEmpty() -> MatchConfidence.VERY_HIGH
            query.category == MediaCategory.DOCUMENT -> if (MatchSignal.IDENTIFIER in agree) MatchConfidence.MEDIUM else MatchConfidence.LOW
            query.category == MediaCategory.COMIC && contra.isEmpty() && MatchSignal.SERIES in agree && MatchSignal.ISSUE in agree &&
                listOf(MatchSignal.CREATOR, MatchSignal.PUBLISHER, MatchSignal.YEAR).count { it in agree } >= 2 -> MatchConfidence.VERY_HIGH
            MatchSignal.IDENTIFIER in agree -> MatchConfidence.MEDIUM
            contra.isEmpty() && MatchSignal.TITLE in agree && MatchSignal.CREATOR in agree &&
                (!query.category.sequential || MatchSignal.VOLUME in agree || MatchSignal.ISSUE in agree) -> MatchConfidence.MEDIUM
            else -> MatchConfidence.LOW
        }
        return MatchEvidence(tier, agree, contra, rejected)
    }

    fun decide(query: MetadataQuery, candidates: List<MetadataCandidate>): MatchDecision {
        val scored = candidates.map { ScoredCandidate(it, score(query, it)) }
        val top = scored.filter { it.evidence.tier == MatchConfidence.VERY_HIGH }
        val agreeing = top.all { t -> sameEdition(t.candidate, top.first().candidate) }
        val final = if (top.size > 1 && !agreeing) scored.map { s ->
            if (s.evidence.tier != MatchConfidence.VERY_HIGH) s
            else s.copy(evidence = s.evidence.copy(tier = MatchConfidence.MEDIUM, contradictions = s.evidence.contradictions + MatchSignal.AMBIGUOUS))
        } else scored
        val ranked = final.sortedWith(compareBy<ScoredCandidate>({ it.evidence.rejected }, { it.evidence.tier.ordinal }, { -it.evidence.agreements.size }))
        return MatchDecision(ranked.firstOrNull { it.evidence.tier == MatchConfidence.VERY_HIGH }, ranked)
    }

    private fun sameEdition(a: MetadataCandidate, b: MetadataCandidate) =
        normalizeText(a.title.orEmpty()) == normalizeText(b.title.orEmpty()) &&
            a.values.volume?.let(::normalizeSequence) == b.values.volume?.let(::normalizeSequence) &&
            a.values.issue?.let(::normalizeSequence) == b.values.issue?.let(::normalizeSequence) &&
            (a.values.identifiers.isEmpty() || b.values.identifiers.isEmpty() || a.values.identifiers.any { x -> b.values.identifiers.any(x::sameAs) })

    /** Same person, compared as normalized name-token sets so "Vaughan, Brian K." = "Brian K. Vaughan" but "Jane Smith" ≠ "John Smith". */
    private fun sameCreator(a: String, b: String): Boolean {
        val x = normalizeText(a).split(' ').filter { it.isNotEmpty() }.toSet()
        val y = normalizeText(b).split(' ').filter { it.isNotEmpty() }.toSet()
        return x.isNotEmpty() && y.isNotEmpty() && (x.containsAll(y) || y.containsAll(x))
    }

    private fun editionKind(title: String?) = normalizeText(title.orEmpty()).split(' ').any { it in editionWords }
}

private val MediaCategory.sequential get() = this == MediaCategory.COMIC || this == MediaCategory.MANGA

/** Script-neutral comparison key: NFKC, case-folded, punctuation and symbols removed, whitespace collapsed. Never stored or shown. */
fun normalizeText(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    .replace(Regex("[\\p{P}\\p{S}]+"), " ").replace(Regex("\\s+"), " ").trim()

/** Issue/volume comparison key: "#001" = "1", "0" stays "0", "1.5" ≠ "1"; full-width digits fold via NFKC. */
fun normalizeSequence(s: String): String {
    val t = Normalizer.normalize(s, Normalizer.Form.NFKC).trim().removePrefix("#").trim().replace(',', '.')
    val whole = t.substringBefore('.').trimStart('0').ifEmpty { "0" }
    return if ('.' in t) "$whole.${t.substringAfter('.')}" else whole
}
