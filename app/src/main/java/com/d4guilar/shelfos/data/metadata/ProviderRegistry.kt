// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*

/** A compiled-in provider and its descriptor. The descriptor is the authority for eligibility, never the provider's own claims. */
data class RegisteredProvider(val descriptor: ProviderDescriptor, val provider: MetadataProvider) {
    init { require(descriptor.id == provider.id) { "descriptor ${descriptor.id} does not describe provider ${provider.id}" } }
}

/** Injected enablement state. 4.5C.1 has no Settings UI or persistence; a later slice supplies a real implementation. */
interface ProviderSettings {
    val onlineEnabled: Boolean
    fun providerEnabled(id: String): Boolean
    fun hasToken(id: String): Boolean
    fun disabledByPolicy(id: String): Boolean
}

/** Online enrichment off: the only state production has until Settings exist. */
object DisabledProviderSettings : ProviderSettings {
    override val onlineEnabled = false
    override fun providerEnabled(id: String) = false
    override fun hasToken(id: String) = false
    override fun disabledByPolicy(id: String) = true
}

enum class Eligibility { ACTIVE, NOT_APPROVED, DISABLED_BY_POLICY, ONLINE_DISABLED, PROVIDER_DISABLED, TOKEN_MISSING, CATEGORY_UNSUPPORTED, CAPABILITY_UNSUPPORTED }

/**
 * Registration order is provider priority. Every activation condition is evaluated here and nowhere else; `CONDITIONAL`
 * and `REJECTED` providers cannot activate whatever the toggles say. An empty registry is valid: no online work happens.
 */
class ProviderRegistry(entries: List<RegisteredProvider> = emptyList(), private val settings: ProviderSettings = DisabledProviderSettings) {
    private val entries = entries.toList()
    init { require(this.entries.map { it.descriptor.id }.toSet().size == this.entries.size) { "duplicate provider id" } }

    fun eligibility(entry: RegisteredProvider, category: MediaCategory, capability: MetadataCapability): Eligibility {
        val d = entry.descriptor
        return when {
            // APPROVED, or CONDITIONAL inside a scoped grant (D1-M); never COVERS under a grant, never REJECTED.
            d.termsStatus == TermsStatus.REJECTED -> Eligibility.NOT_APPROVED
            d.termsStatus == TermsStatus.CONDITIONAL && !(d.grant != null && category in d.grant.categories && capability in d.grant.capabilities) -> Eligibility.NOT_APPROVED
            settings.disabledByPolicy(d.id) -> Eligibility.DISABLED_BY_POLICY
            !settings.onlineEnabled -> Eligibility.ONLINE_DISABLED
            !settings.providerEnabled(d.id) -> Eligibility.PROVIDER_DISABLED
            d.credential == CredentialRequirement.USER_TOKEN && !settings.hasToken(d.id) -> Eligibility.TOKEN_MISSING
            category !in d.categories -> Eligibility.CATEGORY_UNSUPPORTED
            capability !in d.capabilities -> Eligibility.CAPABILITY_UNSUPPORTED
            else -> Eligibility.ACTIVE
        }
    }

    fun active(category: MediaCategory, capability: MetadataCapability): List<RegisteredProvider> =
        entries.filter { eligibility(it, category, capability) == Eligibility.ACTIVE }

    /** Re-evaluated at the moment of a call, so a toggle switched off after a lookup began is honoured. */
    fun isActive(entry: RegisteredProvider, category: MediaCategory, capability: MetadataCapability) =
        eligibility(entry, category, capability) == Eligibility.ACTIVE

    /** Lower is higher priority; unknown ids sort last. */
    fun priority(providerId: String): Int = entries.indexOfFirst { it.descriptor.id == providerId }.let { if (it < 0) Int.MAX_VALUE else it }
}
