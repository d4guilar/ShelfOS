// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Fixtures for the multi-provider tests (4.5C.1). Production registers no provider; these exist only here. */
fun testDescriptor(
    id: String, categories: Set<MediaCategory> = setOf(MediaCategory.BOOK),
    capabilities: Set<MetadataCapability> = setOf(MetadataCapability.IDENTIFIER_LOOKUP, MetadataCapability.SEARCH),
    terms: TermsStatus = TermsStatus.APPROVED, credential: CredentialRequirement = CredentialRequirement.NONE,
    rate: RatePolicy = RatePolicy.perSecond(1_000), retry: RetryPolicy = RetryPolicy(),
) = ProviderDescriptor(id, 0, categories, capabilities, credential, terms, "2026-10-16", rate, retry, ProviderAttribution(0), 0)

class FakeSettings(
    override var onlineEnabled: Boolean = true, val enabled: MutableSet<String> = HashSet(), val tokens: MutableSet<String> = HashSet(),
    val policyDisabled: MutableSet<String> = HashSet(), private val enableAll: Boolean = false,
) : ProviderSettings {
    override fun providerEnabled(id: String) = enableAll || id in enabled
    override fun hasToken(id: String) = id in tokens
    override fun disabledByPolicy(id: String) = id in policyDisabled
}

/** Registers each provider with a descriptor derived from it, online enabled and every provider switched on. */
fun registryOf(providers: List<MetadataProvider>, rate: RatePolicy = RatePolicy.perSecond(1_000)) = ProviderRegistry(
    providers.map { RegisteredProvider(testDescriptor(it.id, it.categories, it.capabilities, rate = rate), it) }, FakeSettings(enableAll = true))

fun enrichment(providers: List<MetadataProvider>, store: MetadataStore, clock: () -> Long = { 0 }) =
    MetadataEnrichmentService(ProviderLookup(registryOf(providers), ProviderPolicyEngine({ System.nanoTime() / 1_000_000 })), store, clock)

fun TestCoroutineScheduler.engine(global: Int = 2, random: kotlin.random.Random = kotlin.random.Random(1)) =
    ProviderPolicyEngine({ currentTime }, { kotlinx.coroutines.delay(it) }, random, global)
