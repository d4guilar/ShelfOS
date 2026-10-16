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
 * the [ProviderPolicyEngine]. [stop] ends a phase once the candidates gathered so far are enough, so providers are not
 * queried needlessly. Results are untrusted: candidates claiming another provider, lacking a reference, or beyond
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

    suspend fun byIdentifier(query: MetadataQuery, stop: (List<MetadataCandidate>) -> Boolean): LookupBatch {
        val ids = lookupIdentifiers(query)
        if (ids.isEmpty()) return LookupBatch.EMPTY
        return collect(registry.active(query.category, MetadataCapability.IDENTIFIER_LOOKUP), stop) { p ->
            val found = ArrayList<MetadataCandidate>()
            for (id in ids) when (val o = engine.execute(p.descriptor) { p.provider.lookup(id) }) {
                is ProviderPolicyEngine.Outcome.Success -> found += o.value
                is ProviderPolicyEngine.Outcome.Failure -> return@collect o
            }
            ProviderPolicyEngine.Outcome.Success(found, 1)
        }
    }

    /** Documents are never searched, whatever providers claim. */
    suspend fun bySearch(query: MetadataQuery, stop: (List<MetadataCandidate>) -> Boolean): LookupBatch {
        if (query.category == MediaCategory.DOCUMENT) return LookupBatch.EMPTY
        return collect(registry.active(query.category, MetadataCapability.SEARCH), stop) { p -> engine.execute(p.descriptor) { p.provider.search(query) } }
    }

    private suspend fun collect(
        providers: List<RegisteredProvider>, stop: (List<MetadataCandidate>) -> Boolean,
        call: suspend (RegisteredProvider) -> ProviderPolicyEngine.Outcome<List<MetadataCandidate>>,
    ): LookupBatch {
        val candidates = ArrayList<MetadataCandidate>(); val failures = ArrayList<ProviderFailure>(); val answered = LinkedHashSet<String>()
        for (p in providers) {
            val id = p.descriptor.id
            when (val o = call(p)) {
                is ProviderPolicyEngine.Outcome.Failure -> failures += ProviderFailure(id, o.kind, o.retryAfterMs)
                is ProviderPolicyEngine.Outcome.Success -> {
                    val valid = o.value.filter { it.providerId == id && it.providerRef.isNotBlank() }.take(maxResults)
                    if (o.value.isNotEmpty() && valid.isEmpty()) failures += ProviderFailure(id, ProviderFailureKind.MALFORMED, null)
                    else { answered += id; candidates += valid }
                }
            }
            if (stop(candidates)) break
        }
        return LookupBatch(candidates, failures, answered)
    }

    private companion object { const val MAX_IDENTIFIERS = 3 }
}
