// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/** Rate limits, concurrency, retry, Retry-After and the circuit breaker, on virtual time: no test sleeps or touches a network. */
class ProviderPolicyEngineTest {
    private val ok = ProviderPolicyEngine.Outcome.Success::class
    private fun ProviderPolicyEngine.Outcome<*>.kind() = (this as? ProviderPolicyEngine.Outcome.Failure)?.kind
    private fun once(retry: RetryPolicy = RetryPolicy(), rate: RatePolicy = RatePolicy.perSecond(1_000)) = testDescriptor("p", rate = rate, retry = retry.copy(maxAttempts = 1))

    // ---- token bucket ----

    @Test fun threePerSecondAllowsTheBurstThenPaces() = runTest {
        val engine = testScheduler.engine()
        val d = testDescriptor("p", rate = RatePolicy.perSecond(3))
        val times = (1..5).map { engine.execute(d) { testScheduler.currentTime }.let { (it as ProviderPolicyEngine.Outcome.Success).value } }
        assertEquals(listOf(0L, 0L, 0L), times.take(3))
        assertTrue("4th waits for one token (~333 ms): ${times[3]}", times[3] in 333..334)
        assertTrue("5th waits for the next: ${times[4]}", times[4] in 666..668)
    }

    @Test fun tenPerMinuteCapacityReplenishesWithTime() = runTest {
        val engine = testScheduler.engine()
        val d = testDescriptor("p", rate = RatePolicy.perMinute(10, burst = 2))
        repeat(2) { assertEquals(0L, (engine.execute(d) { testScheduler.currentTime } as ProviderPolicyEngine.Outcome.Success).value) }
        val refused = engine.execute(d) { "x" }          // next permit is 6 s away, beyond the default 5 s bounded wait
        assertEquals(ProviderFailureKind.RATE_LIMITED, refused.kind())
        assertEquals(6_000L, (refused as ProviderPolicyEngine.Outcome.Failure).retryAfterMs)
        delay(12_000)                                    // two permits come back
        repeat(2) { assertEquals(12_000L, (engine.execute(d) { testScheduler.currentTime } as ProviderPolicyEngine.Outcome.Success).value) }
    }

    @Test fun waitingForATokenIsOneBoundedPauseNotABusyLoop() = runTest {
        val pauses = ArrayList<Long>()
        val engine = ProviderPolicyEngine({ testScheduler.currentTime }, { pauses += it; delay(it) }, Random(1))
        val d = testDescriptor("p", rate = RatePolicy(1, 4_000, burst = 1))
        engine.execute(d) { 1 }
        val second = engine.execute(d) { testScheduler.currentTime }
        assertEquals(4_000L, (second as ProviderPolicyEngine.Outcome.Success).value)
        assertEquals("exactly one pause for the whole wait", listOf(4_000L), pauses)
    }

    // ---- concurrency ----

    private class Gauge { var now = 0; var max = 0
        suspend fun track(ms: Long) { now++; max = maxOf(max, now); try { delay(ms) } finally { now-- } } }

    @Test fun perProviderConcurrencyDefaultsToOne() = runTest {
        val engine = testScheduler.engine(); val g = Gauge(); val d = testDescriptor("p")
        (1..4).map { async { engine.execute(d) { g.track(100) } } }.awaitAll().forEach { assertEquals(ok, it::class) }
        assertEquals(1, g.max)
    }

    @Test fun globalConcurrencyIsBoundedAtTwoAcrossProviders() = runTest {
        val engine = testScheduler.engine(); val g = Gauge()
        val ds = listOf("a", "b", "c").map { testDescriptor(it, rate = RatePolicy(1_000, 1_000, maxConcurrency = 2)) }
        ds.flatMap { d -> (1..2).map { async { engine.execute(d) { g.track(100) } } } }.awaitAll()
        assertEquals(2, g.max)
    }

    @Test fun cancellationReleasesThePermitAndPropagates() = runTest {
        val engine = testScheduler.engine(); val d = testDescriptor("p")
        val blocked = launch { engine.execute(d) { delay(5_000) } }
        runCurrent()
        blocked.cancelAndJoin()
        assertTrue(blocked.isCancelled)
        assertEquals("the slot is free again at the same instant", 0L, (engine.execute(d) { testScheduler.currentTime } as ProviderPolicyEngine.Outcome.Success).value)
        assertThrows(CancellationException::class.java) { runBlocking { engine.execute(d) { throw CancellationException("stop") } } }
    }

    @Test fun aSlowProviderQueuesAtMostItsBoundAndRefusesTheRest() = runTest {
        val engine = testScheduler.engine()
        val d = testDescriptor("slow", rate = RatePolicy(1_000, 1_000, maxQueued = 2))
        val results = (1..5).map { async { engine.execute(d) { delay(1_000); it } } }.awaitAll()
        assertEquals(2, results.count { it is ProviderPolicyEngine.Outcome.Success })
        assertEquals(3, results.count { it.kind() == ProviderFailureKind.BUSY })
    }

    @Test fun aRateLimitedProviderDoesNotHoldUpAnother() = runTest {
        val engine = testScheduler.engine()
        val slow = testDescriptor("slow", rate = RatePolicy(1, 4_000, burst = 1, maxWaitMs = 10_000))
        engine.execute(slow) { 1 }
        val waiting = async { engine.execute(slow) { testScheduler.currentTime } }
        runCurrent()
        val other = engine.execute(testDescriptor("fast")) { testScheduler.currentTime }
        assertEquals("the other provider ran immediately", 0L, (other as ProviderPolicyEngine.Outcome.Success).value)
        assertEquals(4_000L, (waiting.await() as ProviderPolicyEngine.Outcome.Success).value)
    }

    // ---- failures, retry, Retry-After, breaker ----

    @Test fun timeoutIsRetriedBoundedAndReported() = runTest {
        val engine = testScheduler.engine(); var calls = 0
        val d = testDescriptor("p", retry = RetryPolicy(maxAttempts = 3, attemptTimeoutMs = 1_000, breakerThreshold = 10))
        val r = engine.execute(d) { calls++; delay(60_000) }
        assertEquals(ProviderFailureKind.TIMEOUT, r.kind())
        assertEquals(3, calls); assertEquals(3, (r as ProviderPolicyEngine.Outcome.Failure).attempts)
        assertTrue("bounded: ${testScheduler.currentTime}", testScheduler.currentTime <= 3 * 1_000 + 2 * 8_000)
    }

    @Test fun socketTimeoutAndNetworkErrorsAreCategorised() = runTest {
        val engine = testScheduler.engine(); val d = once(RetryPolicy(breakerThreshold = 99))
        assertEquals(ProviderFailureKind.TIMEOUT, engine.execute(d) { throw SocketTimeoutException() }.kind())
        assertEquals(ProviderFailureKind.OFFLINE, engine.execute(d) { throw IOException("no route") }.kind())
        assertEquals(ProviderFailureKind.MALFORMED, engine.execute(d) { throw IllegalStateException("bad json") }.kind())
        assertEquals(ProviderFailureKind.SERVER_FAILURE, engine.execute(d) { throw ProviderException(ProviderFailureKind.SERVER_FAILURE) }.kind())
    }

    @Test fun malformedAndOfflineAreNotRetriedAndOfflineNeverTripsTheBreaker() = runTest {
        val engine = testScheduler.engine(); var calls = 0
        val d = testDescriptor("p", retry = RetryPolicy(maxAttempts = 5, breakerThreshold = 2))
        repeat(6) { assertEquals(ProviderFailureKind.OFFLINE, engine.execute(d) { calls++; throw IOException() }.kind()) }
        assertEquals("one call each, never CIRCUIT_OPEN", 6, calls)
        assertEquals(ProviderFailureKind.MALFORMED, engine.execute(d) { calls++; throw IllegalStateException() }.kind())
        assertEquals(7, calls)
    }

    @Test fun retryAfterWithinTheBoundIsHonouredExactly() = runTest {
        val engine = testScheduler.engine(); var calls = 0
        val d = testDescriptor("p", retry = RetryPolicy(maxAttempts = 3))
        val r = engine.execute(d) { if (calls++ == 0) throw ProviderException(ProviderFailureKind.RATE_LIMITED, 2_000) else testScheduler.currentTime }
        assertEquals(2_000L, (r as ProviderPolicyEngine.Outcome.Success).value)
        assertEquals(2, r.attempts)
    }

    @Test fun aLongRetryAfterStopsCallingUntilItPasses() = runTest {
        val engine = testScheduler.engine(); var calls = 0
        val d = testDescriptor("p", retry = RetryPolicy(maxAttempts = 5, maxBackoffMs = 8_000))
        val first = engine.execute(d) { calls++; throw ProviderException(ProviderFailureKind.RATE_LIMITED, 60_000) }
        assertEquals(ProviderFailureKind.RATE_LIMITED, first.kind()); assertEquals(1, calls)
        assertEquals("not hammered", ProviderFailureKind.CIRCUIT_OPEN, engine.execute(d) { calls++ }.kind())
        assertEquals(1, calls)
        delay(60_000)
        assertEquals(ok, engine.execute(d) { calls++ }::class)
        assertEquals(2, calls)
    }

    @Test fun breakerTripsAfterConsecutiveFailuresThenRecoversThroughOneProbe() = runTest {
        val engine = testScheduler.engine(); var calls = 0
        val d = once(RetryPolicy(breakerThreshold = 2, breakerCooldownMs = 30_000))
        repeat(2) { assertEquals(ProviderFailureKind.SERVER_FAILURE, engine.execute(d) { calls++; throw ProviderException(ProviderFailureKind.SERVER_FAILURE) }.kind()) }
        assertEquals(ProviderFailureKind.CIRCUIT_OPEN, engine.execute(d) { calls++ }.kind()); assertEquals(2, calls)
        delay(30_000)
        assertEquals("a failing probe reopens it", ProviderFailureKind.SERVER_FAILURE, engine.execute(d) { calls++; throw ProviderException(ProviderFailureKind.SERVER_FAILURE) }.kind())
        assertEquals(ProviderFailureKind.CIRCUIT_OPEN, engine.execute(d) { calls++ }.kind()); assertEquals(3, calls)
        delay(30_000)
        assertEquals(ok, engine.execute(d) { calls++ }::class)
        assertEquals("closed again", ok, engine.execute(d) { calls++ }::class)
    }

    @Test fun accessBlockedTripsImmediately() = runTest {
        val engine = testScheduler.engine(); var calls = 0
        val d = testDescriptor("p", retry = RetryPolicy(breakerThreshold = 99))
        assertEquals(ProviderFailureKind.ACCESS_BLOCKED, engine.execute(d) { calls++; throw ProviderException(ProviderFailureKind.ACCESS_BLOCKED) }.kind())
        assertEquals(ProviderFailureKind.CIRCUIT_OPEN, engine.execute(d) { calls++ }.kind()); assertEquals(1, calls)
    }

    @Test fun aCancelledProbeDoesNotLeaveTheBreakerStuck() = runTest {
        val engine = testScheduler.engine()
        val d = once(RetryPolicy(breakerThreshold = 1, breakerCooldownMs = 1_000))
        engine.execute(d) { throw ProviderException(ProviderFailureKind.SERVER_FAILURE) }
        delay(1_000)
        val probe = launch { engine.execute(d) { delay(5_000) } }
        runCurrent(); probe.cancelAndJoin()
        assertEquals(ok, engine.execute(d) { 1 }::class)
    }

    @Test fun backoffGrowsIsJitteredAndNeverExceedsTheMaximum() {
        val plain = RetryPolicy(baseBackoffMs = 100, maxBackoffMs = 1_000, jitterFraction = 0.0)
        assertEquals(listOf(100L, 200L, 400L, 800L, 1_000L, 1_000L), (1..6).map { backoffMs(plain, it, Random(1)) })
        val jittered = plain.copy(jitterFraction = 0.5)
        for (n in 1..40) assertTrue(backoffMs(jittered, n, Random(n)) in 1..1_000)
        assertTrue("jitter adds delay", backoffMs(jittered, 1, Random(3)) in 100..150)
    }

    @Test fun retryAfterParsesSecondsAndHttpDatesAndRejectsGarbage() {
        assertEquals(120_000L, RetryAfter.parse("120", 0))
        assertEquals(30_000L, RetryAfter.parse("Wed, 21 Oct 2015 07:28:30 GMT", java.time.Instant.parse("2015-10-21T07:28:00Z").toEpochMilli()))
        assertEquals("a past date means retry now", 0L, RetryAfter.parse("Wed, 21 Oct 2015 07:28:00 GMT", java.time.Instant.parse("2020-01-01T00:00:00Z").toEpochMilli()))
        for (bad in listOf(null, "", "  ", "-5", "soon", "99999999999999999999")) assertNull(bad, RetryAfter.parse(bad, 0))
    }

    @Test fun anUnapprovedDescriptorIsNeverCalled() = runTest {
        val engine = testScheduler.engine(); var calls = 0
        for (t in listOf(TermsStatus.CONDITIONAL, TermsStatus.REJECTED))
            assertEquals(ProviderFailureKind.POLICY_DISABLED, engine.execute(testDescriptor("p", terms = t)) { calls++ }.kind())
        assertEquals(0, calls)
    }
}
