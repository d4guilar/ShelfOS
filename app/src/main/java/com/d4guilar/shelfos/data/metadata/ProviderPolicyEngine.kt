// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Token bucket in integer arithmetic: one permit costs [RatePolicy.perMillis] units and refills at `permits` units per millisecond. */
class TokenBucket(private val rate: RatePolicy, now: Long) {
    private val capacity = rate.burst * rate.perMillis
    private var level = capacity
    private var last = now

    /** 0 when a permit was taken, otherwise the milliseconds until one is available (nothing is reserved). */
    @Synchronized fun take(now: Long): Long {
        level = minOf(capacity, level + maxOf(0, now - last) * rate.permits); last = now
        if (level >= rate.perMillis) { level -= rate.perMillis; return 0 }
        return (rate.perMillis - level + rate.permits - 1) / rate.permits
    }
}

/** Closed → open (cool-down) → half-open (one probe) → closed. Time is the injected monotonic clock. */
class CircuitBreaker(private val threshold: Int, private val cooldownMs: Long) {
    private var failures = 0
    private var openUntil = -1L
    private var probing = false

    /** 0 when the call may proceed (and, after a cool-down, becomes the single probe); else the wait in ms. */
    @Synchronized fun permit(now: Long): Long = when {
        openUntil < 0 -> 0
        now < openUntil -> openUntil - now
        probing -> cooldownMs
        else -> { probing = true; 0 }
    }
    @Synchronized fun isOpen(now: Long) = openUntil >= 0 && (now < openUntil || probing)
    @Synchronized fun success() { failures = 0; openUntil = -1; probing = false }
    @Synchronized fun failure(now: Long) {
        if (probing || ++failures >= threshold) trip(now + cooldownMs)
    }
    @Synchronized fun trip(until: Long) { openUntil = until; probing = false; failures = 0 }
    /** A probe that ended without a verdict (cancelled, offline) must not leave the breaker stuck half-open. */
    @Synchronized fun release() { probing = false }
}

object RetryAfter {
    /** `Retry-After` as delta-seconds or an HTTP-date, as a relative delay; null if absent or invalid, 0 if the date has passed. */
    fun parse(header: String?, wallNowMs: Long): Long? {
        val v = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return if (it < 0 || it > Long.MAX_VALUE / 1_000) null else it * 1_000 }
        val at = try { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() } catch (_: Exception) { return null }
        return maxOf(0, at.toEpochMilli() - Instant.ofEpochMilli(wallNowMs).toEpochMilli())
    }
}

/** Exponential backoff for the n-th failed attempt (1-based) plus bounded jitter, never above [RetryPolicy.maxBackoffMs]. */
fun backoffMs(policy: RetryPolicy, attempt: Int, random: Random): Long {
    val exp = minOf(policy.maxBackoffMs, policy.baseBackoffMs shl (attempt - 1).coerceIn(0, 20))
    return minOf(policy.maxBackoffMs, exp + (exp * policy.jitterFraction * random.nextDouble()).toLong())
}

/**
 * Transport-independent request policy (§3.3): per-provider token bucket and concurrency, global concurrency, bounded
 * queueing and waiting, retry with backoff for transient failures, and a per-provider circuit breaker. Time comes from
 * [nowMs] (monotonic) and waiting from [pause], so tests never sleep. A rate wait holds only the provider's own slot,
 * never the global permit; the global permit is taken just before the call, and the breaker probe only after that.
 * Cancellation always propagates and frees what it held.
 */
class ProviderPolicyEngine(
    private val nowMs: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
    globalConcurrency: Int = 2,
) {
    sealed interface Outcome<out T> {
        data class Success<T>(val value: T, val attempts: Int) : Outcome<T>
        data class Failure(val kind: ProviderFailureKind, val retryAfterMs: Long?, val attempts: Int) : Outcome<Nothing>
    }

    private class State(d: ProviderDescriptor, now: Long) {
        val bucket = TokenBucket(d.rate, now)
        val breaker = CircuitBreaker(d.retry.breakerThreshold, d.retry.breakerCooldownMs)
        val slots = Semaphore(d.rate.maxConcurrency)
        val waiting = AtomicInteger()
    }
    private sealed interface Attempt<out T> {
        class Ok<T>(val value: T) : Attempt<T>
        class Fail(val kind: ProviderFailureKind, val retryAfterMs: Long?) : Attempt<Nothing>
        /** Not executed: refused by the gate (busy, rate wait too long, breaker open). Never retried here. */
        class Refused(val kind: ProviderFailureKind, val retryAfterMs: Long?) : Attempt<Nothing>
    }
    private class Holder<T>(val value: T)

    private val global = Semaphore(globalConcurrency)
    private val states = ConcurrentHashMap<String, State>()

    /**
     * [category] and [capability] state what this call is for. An APPROVED provider needs neither; a CONDITIONAL provider runs only
     * when both are given and its scoped grant covers them (the same [ProviderDescriptor.authorizes] rule the registry uses).
     */
    suspend fun <T> execute(d: ProviderDescriptor, category: MediaCategory? = null, capability: MetadataCapability? = null, block: suspend () -> T): Outcome<T> {
        val allowed = when (d.termsStatus) {
            TermsStatus.APPROVED -> true
            TermsStatus.CONDITIONAL -> category != null && capability != null && d.authorizes(category, capability)
            TermsStatus.REJECTED -> false
        }
        if (!allowed) return Outcome.Failure(ProviderFailureKind.POLICY_DISABLED, null, 0)
        val s = states.computeIfAbsent(d.id) { State(d, nowMs()) }
        var attempt = 0
        while (true) {
            attempt++
            if (s.breaker.isOpen(nowMs())) return Outcome.Failure(ProviderFailureKind.CIRCUIT_OPEN, s.breaker.permit(nowMs()).takeIf { it > 0 }, attempt - 1)
            val r = gated(d, s) { once(d, s, block) }
            when (r) {
                is Attempt.Ok -> { s.breaker.success(); return Outcome.Success(r.value, attempt) }
                is Attempt.Refused -> return Outcome.Failure(r.kind, r.retryAfterMs, attempt - 1)
                is Attempt.Fail -> {
                    val wait = afterFailure(d, s, r, attempt) ?: return Outcome.Failure(r.kind, r.retryAfterMs, attempt)
                    pause(wait)
                }
            }
        }
    }

    /** Records the failure on the breaker and returns the backoff before the next attempt, or null to give up. */
    private fun afterFailure(d: ProviderDescriptor, s: State, f: Attempt.Fail, attempt: Int): Long? {
        val p = d.retry; val now = nowMs()
        when (f.kind) {
            ProviderFailureKind.OFFLINE -> { s.breaker.release(); return null }
            ProviderFailureKind.ACCESS_BLOCKED -> { s.breaker.trip(now + p.breakerCooldownMs); return null }
            ProviderFailureKind.RATE_LIMITED -> {
                val ra = f.retryAfterMs?.coerceIn(0, p.maxRetryAfterMs)
                if (ra != null) {
                    if (ra <= p.maxBackoffMs && attempt < p.maxAttempts) return ra
                    s.breaker.trip(now + maxOf(ra, 1)); return null   // honour a long Retry-After: stop calling until it passes
                }
            }
            else -> Unit
        }
        s.breaker.failure(now)
        val retryable = f.kind == ProviderFailureKind.TIMEOUT || f.kind == ProviderFailureKind.SERVER_FAILURE || f.kind == ProviderFailureKind.RATE_LIMITED
        return if (retryable && attempt < p.maxAttempts && !s.breaker.isOpen(now)) backoffMs(p, attempt, random) else null
    }

    private suspend fun <T> gated(d: ProviderDescriptor, s: State, run: suspend () -> Attempt<T>): Attempt<T> {
        if (s.waiting.incrementAndGet() > d.rate.maxQueued) {
            s.waiting.decrementAndGet(); return Attempt.Refused(ProviderFailureKind.BUSY, null)
        }
        try {
            return s.slots.withPermit {
                val start = nowMs()
                while (true) {
                    val wait = s.bucket.take(nowMs())
                    if (wait == 0L) break
                    if (nowMs() - start + wait > d.rate.maxWaitMs) return@withPermit Attempt.Refused(ProviderFailureKind.RATE_LIMITED, wait)
                    pause(wait)
                }
                global.withPermit {
                    // The probe is claimed only once both permits are held, and handed back on any exit without a verdict (cancellation included).
                    val open = s.breaker.permit(nowMs())
                    if (open > 0) return@withPermit Attempt.Refused(ProviderFailureKind.CIRCUIT_OPEN, open)
                    var verdict = false
                    try { run().also { verdict = true } } finally { if (!verdict) s.breaker.release() }
                }
            }
        } finally { s.waiting.decrementAndGet() }
    }

    private suspend fun <T> once(d: ProviderDescriptor, s: State, block: suspend () -> T): Attempt<T> = try {
        val h = withTimeoutOrNull(d.retry.attemptTimeoutMs) { Holder(block()) }
        if (h == null) Attempt.Fail(ProviderFailureKind.TIMEOUT, null) else Attempt.Ok(h.value)
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()   // a provider's own timeout is a failure; ours being cancelled is not
        Attempt.Fail(ProviderFailureKind.TIMEOUT, null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ProviderException) {
        Attempt.Fail(e.kind, e.retryAfterMs)
    } catch (_: SocketTimeoutException) {
        Attempt.Fail(ProviderFailureKind.TIMEOUT, null)
    } catch (_: IOException) {
        Attempt.Fail(ProviderFailureKind.OFFLINE, null)
    } catch (_: Exception) {
        Attempt.Fail(ProviderFailureKind.MALFORMED, null)   // a parsing bug or bad payload is isolated like any other failure
    }
}
