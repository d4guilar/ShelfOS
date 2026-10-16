// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import org.junit.Assert.*
import org.junit.Test

/** Eligibility is decided centrally: approval, global and own toggles, token, category, capability, policy. */
class ProviderRegistryTest {
    private class Stub(override val id: String) : MetadataProvider {
        override val categories = setOf(MediaCategory.BOOK)
        override val capabilities = setOf(MetadataCapability.IDENTIFIER_LOOKUP)
        override suspend fun lookup(identifier: PublicationIdentifier) = emptyList<MetadataCandidate>()
        override suspend fun search(query: MetadataQuery) = emptyList<MetadataCandidate>()
    }
    private fun entry(id: String, d: ProviderDescriptor = testDescriptor(id)) = RegisteredProvider(d, Stub(id))
    private fun registry(settings: ProviderSettings, vararg entries: RegisteredProvider) = ProviderRegistry(entries.toList(), settings)
    private fun ProviderRegistry.ids(c: MediaCategory = MediaCategory.BOOK, cap: MetadataCapability = MetadataCapability.IDENTIFIER_LOOKUP) = active(c, cap).map { it.descriptor.id }

    @Test fun anEmptyRegistryAndTheProductionDefaultHaveNoActiveProvider() {
        assertTrue(ProviderRegistry().ids().isEmpty())
        assertTrue("production settings keep online off even for an approved entry", registry(DisabledProviderSettings, entry("a")).ids().isEmpty())
        val lookup = ProviderLookup(ProviderRegistry(), ProviderPolicyEngine({ 0 }))
        assertFalse(lookup.hasEligible(MediaCategory.BOOK))
    }

    @Test fun everyConditionIsRequired() {
        val e = entry("a")
        assertEquals(Eligibility.ACTIVE, registry(FakeSettings(enableAll = true), e).eligibility(e, MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP))
        fun why(s: ProviderSettings, entry: RegisteredProvider = e, c: MediaCategory = MediaCategory.BOOK, cap: MetadataCapability = MetadataCapability.IDENTIFIER_LOOKUP) =
            registry(s, entry).eligibility(entry, c, cap)
        assertEquals("global switch off", Eligibility.ONLINE_DISABLED, why(FakeSettings(onlineEnabled = false, enableAll = true)))
        assertEquals("own switch off", Eligibility.PROVIDER_DISABLED, why(FakeSettings()))
        assertEquals("another provider's switch is not enough", Eligibility.PROVIDER_DISABLED, why(FakeSettings(enabled = mutableSetOf("b"))))
        assertEquals("policy", Eligibility.DISABLED_BY_POLICY, why(FakeSettings(enableAll = true, policyDisabled = mutableSetOf("a"))))
        assertEquals("wrong category", Eligibility.CATEGORY_UNSUPPORTED, why(FakeSettings(enableAll = true), c = MediaCategory.COMIC))
        assertEquals("missing capability", Eligibility.CAPABILITY_UNSUPPORTED, why(FakeSettings(enableAll = true), cap = MetadataCapability.COVERS))
    }

    @Test fun aUserTokenProviderNeedsItsToken() {
        val e = entry("keyed", testDescriptor("keyed", credential = CredentialRequirement.USER_TOKEN))
        val s = FakeSettings(enableAll = true)
        assertEquals(Eligibility.TOKEN_MISSING, registry(s, e).eligibility(e, MediaCategory.BOOK, MetadataCapability.SEARCH))
        s.tokens += "keyed"
        assertEquals(Eligibility.ACTIVE, registry(s, e).eligibility(e, MediaCategory.BOOK, MetadataCapability.SEARCH))
    }

    @Test fun conditionalAndRejectedProvidersNeverActivateWhateverIsToggled() {
        val s = FakeSettings(enableAll = true, tokens = mutableSetOf("c", "r"))
        val c = entry("c", testDescriptor("c", terms = TermsStatus.CONDITIONAL))
        val r = entry("r", testDescriptor("r", terms = TermsStatus.REJECTED))
        val reg = registry(s, c, r, entry("ok"))
        assertEquals(Eligibility.NOT_APPROVED, reg.eligibility(c, MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP))
        assertEquals(Eligibility.NOT_APPROVED, reg.eligibility(r, MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP))
        assertEquals(listOf("ok"), reg.ids())
    }

    @Test fun severalEligibleProvidersKeepRegistrationOrderAsPriority() {
        val reg = registry(FakeSettings(enableAll = true), entry("second-registered-first"), entry("a"), entry("z"))
        assertEquals(listOf("second-registered-first", "a", "z"), reg.ids())
        assertTrue(reg.priority("a") < reg.priority("z"))
        assertEquals(Int.MAX_VALUE, reg.priority("unknown"))
    }

    @Test fun duplicateIdsAndMismatchedDescriptorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProviderRegistry(listOf(entry("a"), entry("a"))) }
        assertThrows(IllegalArgumentException::class.java) { RegisteredProvider(testDescriptor("a"), Stub("b")) }
    }

    @Test fun descriptorsCarryTheApprovedMetadata() {
        val d = testDescriptor("a", rate = RatePolicy.perMinute(10, burst = 2))
        assertEquals("2026-10-16", d.termsCheckedOn)
        assertEquals(60_000L, d.rate.perMillis); assertEquals(1, d.rate.maxConcurrency)
        assertNotNull(d.attribution); assertEquals(CredentialRequirement.NONE, d.credential)
    }
}
