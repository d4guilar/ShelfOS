// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A floor on the time between the starts of consecutive HTTP requests to one service, shared by every call (redirect hops and
 * follow-up requests included). The provider policy engine admits and retries whole lookups; this guarantees that no two actual
 * HTTP requests are closer than [minIntervalMs], and with a single holder at a time that only one is in flight. Cancellation while
 * waiting releases the lock without consuming the slot.
 */
class RequestPacer(private val minIntervalMs: Long, private val nowMs: () -> Long, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    init { require(minIntervalMs >= 0) }
    private val lock = Mutex()
    private var lastStart = Long.MIN_VALUE

    /** Runs [request] no sooner than [minIntervalMs] after the previous request started; requests never overlap. */
    suspend fun <T> paced(request: suspend () -> T): T = lock.withLock {
        if (lastStart != Long.MIN_VALUE) {
            val wait = lastStart + minIntervalMs - nowMs()
            if (wait > 0) pause(wait)
        }
        lastStart = nowMs()
        request()
    }
}
