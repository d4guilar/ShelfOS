// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory

/**
 * Provider descriptors and request policies (Phase 4.5C.1, ADR-0026, BOOK_PROVIDERS §3.1/§3.3). Pure data: no transport,
 * no Android. Production registers none yet; descriptors exist for fakes until an approved provider slice adds one.
 */
enum class CredentialRequirement { NONE, USER_TOKEN }

/** Only [APPROVED] can ever activate; it is set by an approved decision row, never by a user toggle. */
enum class TermsStatus { APPROVED, CONDITIONAL, REJECTED }

/** [permits] per [perMillis] with a [burst] bucket; at most [maxConcurrency] calls in flight, [maxQueued] callers in flight or waiting. */
data class RatePolicy(
    val permits: Int, val perMillis: Long, val burst: Int = permits, val maxConcurrency: Int = 1,
    val maxWaitMs: Long = 5_000, val maxQueued: Int = 8,
) {
    init { require(permits > 0 && perMillis > 0 && burst >= 1 && maxConcurrency >= 1 && maxWaitMs >= 0 && maxQueued >= 1) }
    companion object {
        fun perSecond(n: Int, burst: Int = n) = RatePolicy(n, 1_000, burst)
        fun perMinute(n: Int, burst: Int = n) = RatePolicy(n, 60_000, burst)
    }
}

data class RetryPolicy(
    val maxAttempts: Int = 3, val baseBackoffMs: Long = 500, val maxBackoffMs: Long = 8_000, val jitterFraction: Double = 0.2,
    val attemptTimeoutMs: Long = 10_000, val breakerThreshold: Int = 5, val breakerCooldownMs: Long = 60_000, val maxRetryAfterMs: Long = 3_600_000,
) {
    init {
        require(maxAttempts >= 1 && baseBackoffMs > 0 && maxBackoffMs >= baseBackoffMs && jitterFraction in 0.0..1.0)
        require(attemptTimeoutMs > 0 && breakerThreshold >= 1 && breakerCooldownMs > 0 && maxRetryAfterMs >= 0)
    }
}

/** Display text lives in string resources; [textRes] and [url] are what a later settings/credits screen shows. */
data class ProviderAttribution(val textRes: Int, val url: String? = null)

/**
 * A narrow, named administrator authorization for a provider that is not (and may never be) globally [TermsStatus.APPROVED]
 * (Phase 4.5C.2A, plan D1-M). It lists exactly the categories and capabilities it covers and the decision it rests on.
 * [MetadataCapability.COVERS] can never be granted: artwork needs its own decision (D1-C).
 */
data class ScopedGrant(val basis: String, val categories: Set<MediaCategory>, val capabilities: Set<MetadataCapability>) {
    init {
        require(basis.isNotBlank() && categories.isNotEmpty() && capabilities.isNotEmpty())
        require(MetadataCapability.COVERS !in capabilities) { "a scoped grant can never cover artwork" }
    }
}

data class ProviderDescriptor(
    val id: String, val displayNameRes: Int, val categories: Set<MediaCategory>, val capabilities: Set<MetadataCapability>,
    val credential: CredentialRequirement, val termsStatus: TermsStatus, val termsCheckedOn: String,
    val rate: RatePolicy, val retry: RetryPolicy = RetryPolicy(), val attribution: ProviderAttribution, val privacyDisclosureRes: Int,
    val grant: ScopedGrant? = null,
) {
    /**
     * The single authorization rule, used by the registry and the policy engine alike. An APPROVED provider is authorized for what its
     * descriptor declares; a CONDITIONAL one only inside its [grant] (and the descriptor); a REJECTED one never.
     */
    fun authorizes(category: MediaCategory, capability: MetadataCapability): Boolean = category in categories && capability in capabilities &&
        when (termsStatus) {
            TermsStatus.APPROVED -> true
            TermsStatus.CONDITIONAL -> grant != null && category in grant.categories && capability in grant.capabilities
            TermsStatus.REJECTED -> false
        }
}

/** Closed failure set (§3.10): a future UI can tell "no results" (an empty success) from every one of these. */
enum class ProviderFailureKind { OFFLINE, TIMEOUT, RATE_LIMITED, ACCESS_BLOCKED, SERVER_FAILURE, MALFORMED, POLICY_DISABLED, CIRCUIT_OPEN, BUSY }

/** What a transport throws to report a categorized failure; [retryAfterMs] is a relative delay already derived from `Retry-After`. */
class ProviderException(val kind: ProviderFailureKind, val retryAfterMs: Long? = null, cause: Throwable? = null) : Exception(kind.name, cause)
