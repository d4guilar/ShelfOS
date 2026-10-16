// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*

/**
 * The enrichment coordinator (plan §10, §19 step 3; multi-provider since 4.5C.1): query from local metadata → eligible
 * providers through [ProviderLookup] (registry, rate limits, breaker) → exact ISBN lookup first, bounded title/creator
 * search only for an item without an ISBN (never Documents) → [EditionClustering] over [MetadataMatcher] → only an
 * unambiguous VERY_HIGH edition is applied, field by field, through the store's precedence rule (never over `USER`,
 * never over `EMBEDDED`). Cover candidates are never applied: local artwork is untouched.
 *
 * No production provider exists (no network permission, no HTTP); this class is exercised with fake providers.
 */
class MetadataEnrichmentService(
    private val lookup: ProviderLookup,
    private val store: MetadataStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [LOOKUP_FAILED]: nothing answered and at least one provider failed, which is not the same as "no match". */
    enum class Outcome { APPLIED, NEEDS_REVIEW, NO_MATCH, SKIPPED, LOOKUP_FAILED }
    data class Result(val outcome: Outcome, val decision: MatchDecision? = null, val ranking: EditionRanking? = null, val failures: List<ProviderFailure> = emptyList())

    /** [exhaustive] keeps querying every eligible provider (for corroboration or a picker) instead of stopping at the first safe match. */
    suspend fun enrich(item: LibraryItem, exhaustive: Boolean = false): Result {
        val current = store.resolved(item.id) ?: return Result(Outcome.SKIPPED)
        if (!lookup.hasEligible(item.category)) return Result(Outcome.SKIPPED)
        val query = query(item, current)
        fun rank(c: List<MetadataCandidate>) = EditionClustering.rank(query, c, lookup::priority)
        val stop: (List<MetadataCandidate>) -> Boolean = if (exhaustive) { _ -> false } else { c -> rank(c).apply != null }
        var batch = lookup.byIdentifier(query, stop)
        var ranking = rank(batch.candidates)
        if (ranking.apply == null && item.category != MediaCategory.DOCUMENT && query.identifiers.none { it.isbn13 != null }) {
            batch += lookup.bySearch(query, stop)
            ranking = rank(batch.candidates)
        }
        val decision = ranking.toDecision()
        val chosen = ranking.apply
            ?: return Result(when {
                ranking.ranked.any { it.evidence.tier == MatchConfidence.MEDIUM && !it.evidence.rejected } -> Outcome.NEEDS_REVIEW
                batch.candidates.isEmpty() && batch.answered.isEmpty() && batch.failures.isNotEmpty() -> Outcome.LOOKUP_FAILED
                else -> Outcome.NO_MATCH
            }, decision, ranking, batch.failures)
        val c = chosen.representative
        val origin = if (MatchSignal.IDENTIFIER in chosen.evidence.agreements) MetadataOrigin.EXACT_IDENTIFIER_MATCH else MetadataOrigin.HIGH_CONFIDENCE_EXTERNAL_MATCH
        val provenance = FieldProvenance(origin, c.providerId, c.providerRef, MatchConfidence.VERY_HIGH, clock())
        val proposals = listOfNotNull(c.title?.let { Proposed(MetadataField.TITLE, it, provenance) },
            c.creator?.let { Proposed(MetadataField.CREATOR, it, provenance) }) + c.values.proposals(provenance)
        return Result(if (store.apply(item.id, proposals)) Outcome.APPLIED else Outcome.SKIPPED, decision, ranking, batch.failures)
    }

    /** What the item already knows locally; a file-name title is replaced by its cleaned form for matching only. */
    fun query(item: LibraryItem, m: ResolvedMetadata): MetadataQuery {
        val fromName = m.origin(MetadataField.TITLE) == MetadataOrigin.FILENAME_INFERENCE
        val v = m.values
        return MetadataQuery(item.category, v.identifiers,
            if (fromName) FilenameInference.infer(item.fileName, item.category).title else m.title,
            m.creator.ifBlank { null }, v.series, v.volume, v.issue, v.publishedDate?.take(4)?.toIntOrNull(), v.language, v.publisher,
            titleIsInferred = fromName || m.origin(MetadataField.TITLE) == MetadataOrigin.UNKNOWN)
    }
}
