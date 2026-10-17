// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*

data class ProviderFailure(val providerId: String, val kind: ProviderFailureKind, val retryAfterMs: Long?)

/**
 * What one lookup phase produced. [answered] are providers that replied (possibly with nothing), so "no results" is
 * distinguishable from [failures]; a provider that failed never hides what the others returned.
 */
data class LookupBatch(val candidates: List<MetadataCandidate>, val failures: List<ProviderFailure>, val answered: Set<String>) {
    operator fun plus(o: LookupBatch) = LookupBatch(candidates + o.candidates, failures + o.failures, answered + o.answered)
    companion object { val EMPTY = LookupBatch(emptyList(), emptyList(), emptySet()) }
}

/**
 * Provider-neutral lookup (§3.2): only registry-eligible providers, one at a time in priority order, every call through
 * the [ProviderPolicyEngine]. ISBN lookup consults every eligible provider; only the search fallback uses [stop] to end
 * a phase once the candidates gathered so far are safe. Results are untrusted: candidates claiming another provider, lacking a reference, or beyond
 * [maxResults] are dropped, and a reply with nothing usable is a MALFORMED failure, not "no match".
 */
class ProviderLookup(private val registry: ProviderRegistry, private val engine: ProviderPolicyEngine, private val maxResults: Int = 10) {
    fun hasEligible(category: MediaCategory) = registry.active(category, MetadataCapability.IDENTIFIER_LOOKUP).isNotEmpty() ||
        registry.active(category, MetadataCapability.SEARCH).isNotEmpty()

    fun priority(providerId: String) = registry.priority(providerId)

    /** ISBNs (ISBN-10 as ISBN-13) win; other identifiers are used only when the item has no ISBN. */
    fun lookupIdentifiers(query: MetadataQuery): List<PublicationIdentifier> {
        val isbns = query.identifiers.mapNotNull { it.isbn13 }.distinct().map { PublicationIdentifier(IdentifierType.ISBN_13, it) }
        return (isbns.ifEmpty { query.identifiers.distinct() }).take(MAX_IDENTIFIERS)
    }

    /** Every eligible ISBN-capable provider is consulted (no early stop) so contradictions are seen before anything is applied. */
    suspend fun byIdentifier(query: MetadataQuery): LookupBatch {
        val ids = lookupIdentifiers(query)
        if (ids.isEmpty()) return LookupBatch.EMPTY
        return collect(registry.active(query.category, MetadataCapability.IDENTIFIER_LOOKUP), { false }) { p ->
            val found = ArrayList<MetadataCandidate>()
            var failure: ProviderPolicyEngine.Outcome.Failure? = null
            for (id in ids) {
                when (val o = engine.execute(p.descriptor) { p.provider.lookup(id) }) {
                    is ProviderPolicyEngine.Outcome.Success -> found += o.value
                    is ProviderPolicyEngine.Outcome.Failure -> { failure = o; break }
                }
            }
            Reply(found, failure)
        }
    }

    /** Documents are never searched, whatever providers claim. */
    suspend fun bySearch(query: MetadataQuery, stop: (List<MetadataCandidate>) -> Boolean): LookupBatch {
        if (query.category == MediaCategory.DOCUMENT) return LookupBatch.EMPTY
        return collect(registry.active(query.category, MetadataCapability.SEARCH), stop) { p ->
            when (val o = engine.execute(p.descriptor) { p.provider.search(query) }) {
                is ProviderPolicyEngine.Outcome.Success -> Reply(o.value, null)
                is ProviderPolicyEngine.Outcome.Failure -> Reply(emptyList(), o)
            }
        }
    }

    /** What one provider gave: candidates from the calls that succeeded, and the failure that ended the rest (kept separately). */
    private class Reply(val candidates: List<MetadataCandidate>, val failure: ProviderPolicyEngine.Outcome.Failure?)

    private suspend fun collect(providers: List<RegisteredProvider>, stop: (List<MetadataCandidate>) -> Boolean, call: suspend (RegisteredProvider) -> Reply): LookupBatch {
        val candidates = ArrayList<MetadataCandidate>(); val failures = ArrayList<ProviderFailure>(); val answered = LinkedHashSet<String>()
        for (p in providers) {
            val id = p.descriptor.id
            val r = call(p)
            val valid = r.candidates.filter { it.providerId == id && it.providerRef.isNotBlank() }.take(maxResults)
            candidates += valid
            when {
                r.failure != null -> failures += ProviderFailure(id, r.failure.kind, r.failure.retryAfterMs)
                r.candidates.isNotEmpty() && valid.isEmpty() -> failures += ProviderFailure(id, ProviderFailureKind.MALFORMED, null)
                else -> answered += id
            }
            if (r.failure != null && valid.isNotEmpty()) answered += id   // partial success still counts as an answer
            if (stop(candidates)) break
        }
        return LookupBatch(candidates, failures, answered)
    }

    private companion object { const val MAX_IDENTIFIERS = 3 }
}
