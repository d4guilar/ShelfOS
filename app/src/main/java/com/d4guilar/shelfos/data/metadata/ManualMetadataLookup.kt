// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibrary
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*

/** One field a candidate would change, as the user will see it before confirming. */
data class FieldChange(val field: MetadataField, val before: String?, val after: String)

/**
 * A provider candidate scored by the production matcher, ready for review. Nothing here is written anywhere. [applicable] is false for a
 * hard-rejected or LOW candidate, which can never be applied, however the user taps. [changes] is what [resolveMetadata] would actually
 * change right now (it respects USER and EMBEDDED precedence); it can be empty.
 */
data class ReviewCandidate(
    val candidate: MetadataCandidate, val tier: MatchConfidence, val rejected: Boolean,
    val agreements: Set<MatchSignal>, val contradictions: Set<MatchSignal>, val changes: List<FieldChange>,
) {
    val providerId get() = candidate.providerId
    val providerRef get() = candidate.providerRef
    /** A search result is a work, not an edition: its edition-specific facts were never read. */
    val workLevel get() = candidate.providerId == OpenLibrary.ID && OpenLibrary.isWorkLevel(candidate.providerRef)
    val applicable get() = !rejected && tier != MatchConfidence.LOW
}

enum class LookupMode { ISBN, SEARCH }

sealed interface LookupOutcome {
    data class Found(val candidates: List<ReviewCandidate>, val mode: LookupMode) : LookupOutcome
    /** The provider answered and nothing matched (a valid answer, not a failure). */
    data class NoMatch(val mode: LookupMode) : LookupOutcome
    /** The item has no ISBN to look up. Nothing was sent. */
    data object NoIdentifier : LookupOutcome
    /** No provider is eligible (disabled, or not for this category). Nothing was sent. */
    data object Unavailable : LookupOutcome
    data class Failed(val kind: ProviderFailureKind, val retryAfterMs: Long?) : LookupOutcome
    /** The publication was removed. */
    data object ItemGone : LookupOutcome
}

sealed interface ApplyOutcome {
    data class Applied(val changed: List<MetadataField>) : ApplyOutcome
    /** The write was valid but every field is already set at equal or higher precedence (or by the user). Reported as such, never as success. */
    data object NothingChanged : ApplyOutcome
    data object NotApplicable : ApplyOutcome
    data object ItemGone : ApplyOutcome
}

/**
 * The manual path (Phase 4.5C.2A): look up, score, show, and write only when the user confirms. It never calls
 * [MetadataEnrichmentService.enrich] (whose automatic apply is for later) and never runs from import, startup or the Library.
 * ISBN lookup does not fall back to a title search; the user starts that explicitly ([LookupMode.SEARCH]). Cover candidates are never read.
 */
class ManualMetadataLookup(
    private val lookup: ProviderLookup, private val store: MetadataStore, private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun find(item: LibraryItem, mode: LookupMode): LookupOutcome {
        val current = store.resolved(item.id) ?: return LookupOutcome.ItemGone
        if (item.category != MediaCategory.BOOK || !lookup.hasEligible(item.category)) return LookupOutcome.Unavailable
        val query = metadataQuery(item, current)
        val batch = when (mode) {
            LookupMode.ISBN -> if (lookup.lookupIdentifiers(query).isEmpty()) return LookupOutcome.NoIdentifier else lookup.byIdentifier(query)
            LookupMode.SEARCH -> lookup.bySearch(query) { false }
        }
        if (batch.candidates.isEmpty()) {
            val f = batch.failures.firstOrNull()
            return if (batch.answered.isEmpty() && f != null) LookupOutcome.Failed(f.kind, f.retryAfterMs) else LookupOutcome.NoMatch(mode)
        }
        // The item may have been edited or removed while the request ran: score against what is stored now.
        val fresh = store.resolved(item.id) ?: return LookupOutcome.ItemGone
        val ranking = EditionClustering.rank(query, batch.candidates, lookup::priority)
        return LookupOutcome.Found(ranking.ranked.map { r ->
            ReviewCandidate(r.representative, r.evidence.tier, r.evidence.rejected, r.evidence.agreements, r.evidence.contradictions,
                if (!r.evidence.rejected && r.evidence.tier != MatchConfidence.LOW) changes(fresh, proposals(r.representative, r.evidence)) else emptyList())
        }, mode)
    }

    /** Writes the candidate only if it is applicable, through the store's precedence rule, against the item's current state. */
    suspend fun apply(item: LibraryItem, review: ReviewCandidate): ApplyOutcome {
        if (!review.applicable) return ApplyOutcome.NotApplicable
        val before = store.resolved(item.id) ?: return ApplyOutcome.ItemGone
        val evidence = MatchEvidence(review.tier, review.agreements, review.contradictions, review.rejected)
        if (!store.apply(item.id, proposals(review.candidate, evidence))) return ApplyOutcome.ItemGone
        val after = store.resolved(item.id) ?: return ApplyOutcome.ItemGone
        val changed = MetadataField.entries.filter { before.value(it) != after.value(it) }
        return if (changed.isEmpty()) ApplyOutcome.NothingChanged else ApplyOutcome.Applied(changed)
    }

    private fun proposals(c: MetadataCandidate, e: MatchEvidence): List<Proposed> {
        // Identifier equality is exact-identifier evidence; the confidence recorded is what the matcher actually found, not a claim.
        val origin = when {
            MatchSignal.IDENTIFIER in e.agreements -> MetadataOrigin.EXACT_IDENTIFIER_MATCH
            e.tier == MatchConfidence.VERY_HIGH -> MetadataOrigin.HIGH_CONFIDENCE_EXTERNAL_MATCH
            else -> MetadataOrigin.INFERRED_EXTERNAL_MATCH
        }
        val provenance = FieldProvenance(origin, c.providerId, c.providerRef, e.tier, clock())
        return listOfNotNull(c.title?.let { Proposed(MetadataField.TITLE, it, provenance) }, c.creator?.let { Proposed(MetadataField.CREATOR, it, provenance) }) +
            c.values.proposals(provenance)
    }

    private fun changes(current: ResolvedMetadata, proposals: List<Proposed>): List<FieldChange> {
        val next = resolveMetadata(current, proposals)
        return MetadataField.entries.mapNotNull { f ->
            val a = current.value(f); val b = next.value(f)
            if (a == b || isBlankValue(b)) null else FieldChange(f, show(a), show(b)!!)
        }
    }

    private fun show(v: Any?): String? = when (v) {
        null -> null
        is String -> v.takeIf { it.isNotBlank() }
        is List<*> -> v.joinToString(", ") { if (it is PublicationIdentifier) it.value else it.toString() }.takeIf { it.isNotBlank() }
        else -> v.toString()
    }
}
