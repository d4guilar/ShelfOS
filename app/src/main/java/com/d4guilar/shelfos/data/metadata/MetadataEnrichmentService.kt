// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.CancellationException

/**
 * The enrichment coordinator (plan §10, §19 step 3): query from local metadata → eligible providers (category and
 * capability) → identifier lookup first, search only when that found nothing safe → [MetadataMatcher] → only an
 * unambiguous VERY_HIGH candidate is applied, field by field, through the store's precedence rule (never over `USER`,
 * never over `EMBEDDED`). Cover candidates are never applied: local artwork is untouched. Documents are never searched.
 *
 * 4.5B ships no production provider (no network permission, no HTTP); this class is exercised with fake providers.
 */
class MetadataEnrichmentService(
    private val providers: List<MetadataProvider>,
    private val store: MetadataStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { APPLIED, NEEDS_REVIEW, NO_MATCH, SKIPPED }
    data class Result(val outcome: Outcome, val decision: MatchDecision? = null)

    suspend fun enrich(item: LibraryItem): Result {
        val current = store.resolved(item.id) ?: return Result(Outcome.SKIPPED)
        val eligible = providers.filter { item.category in it.categories }
        if (eligible.isEmpty()) return Result(Outcome.SKIPPED)
        val query = query(item, current)
        val candidates = ArrayList<MetadataCandidate>()
        for (p in eligible) if (MetadataCapability.IDENTIFIER_LOOKUP in p.capabilities) for (id in query.identifiers) candidates += safely { p.lookup(id) }
        var decision = MetadataMatcher.decide(query, candidates)
        if (decision.apply == null && item.category != MediaCategory.DOCUMENT) {
            for (p in eligible) if (MetadataCapability.SEARCH in p.capabilities) candidates += safely { p.search(query) }
            decision = MetadataMatcher.decide(query, candidates)
        }
        val chosen = decision.apply
            ?: return Result(if (decision.ranked.any { it.evidence.tier == MatchConfidence.MEDIUM && !it.evidence.rejected }) Outcome.NEEDS_REVIEW else Outcome.NO_MATCH, decision)
        val c = chosen.candidate
        val origin = if (MatchSignal.IDENTIFIER in chosen.evidence.agreements) MetadataOrigin.EXACT_IDENTIFIER_MATCH else MetadataOrigin.HIGH_CONFIDENCE_EXTERNAL_MATCH
        val provenance = FieldProvenance(origin, c.providerId, c.providerRef, MatchConfidence.VERY_HIGH, clock())
        val proposals = listOfNotNull(c.title?.let { Proposed(MetadataField.TITLE, it, provenance) },
            c.creator?.let { Proposed(MetadataField.CREATOR, it, provenance) }) + c.values.proposals(provenance)
        return Result(if (store.apply(item.id, proposals)) Outcome.APPLIED else Outcome.SKIPPED, decision)
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

    // One failing provider never fails enrichment or the others.
    private suspend fun safely(block: suspend () -> List<MetadataCandidate>): List<MetadataCandidate> =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }
}
