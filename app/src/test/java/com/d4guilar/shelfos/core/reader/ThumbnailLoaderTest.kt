// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM coverage for the Phase 3B thumbnail-navigation primitives: [ByteBudgetedLruCache] (byte-budgeted
 * eviction), [nextThumbnailToLoad] (lazy, center-out, dedup scheduling policy) and [ThumbnailLoader] itself (the
 * coroutine-driven lazy/cancellation-aware orchestration). None of this needs a real `android.graphics.Bitmap` --
 * see [PageRenderRequestTest]'s doc for why this project's local unit tests cannot construct one -- [ThumbnailLoader]
 * is generic specifically so this real async scheduling/cancellation behavior can be driven here with a trivial
 * fake payload and a controllable fake decode, under `kotlinx-coroutines-test`'s virtual time, rather than only
 * being provable on a real device/emulator. The real `android.graphics.Bitmap`-backed wiring
 * ([com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]) and real CBZ/PDF decode evidence are covered
 * separately by instrumented tests.
 */
class ByteBudgetedLruCacheTest {
    @Test fun entriesWithinBudgetAreAllRetained() {
        val cache = ByteBudgetedLruCache<Int, Int>(100) { it.toLong() }
        cache.put(1, 10); cache.put(2, 20); cache.put(3, 30)
        assertEquals(3, cache.count)
        assertEquals(60L, cache.sizeBytes)
    }

    @Test fun exceedingTheBudgetEvictsLeastRecentlyUsedFirst() {
        val cache = ByteBudgetedLruCache<Int, Int>(25) { it.toLong() } // each value's byte size equals itself here
        cache.put(1, 10); cache.put(2, 10); cache.put(3, 10) // 30 > 25 budget -> the oldest (1) must go
        assertFalse(cache.contains(1))
        assertTrue(cache.contains(2) && cache.contains(3))
        assertTrue(cache.sizeBytes <= 25)
    }

    @Test fun accessingAnEntryProtectsItFromBeingTheNextEviction() {
        val cache = ByteBudgetedLruCache<Int, Int>(25) { it.toLong() }
        cache.put(1, 10); cache.put(2, 10)
        cache.get(1) // touch 1 -- 2 is now the least-recently-used, not 1
        cache.put(3, 10) // 30 > 25 budget -> exactly one eviction is needed
        assertTrue("recently-touched entry should survive an eviction", cache.contains(1))
        assertFalse("the untouched, older entry should be the one evicted", cache.contains(2))
        assertTrue(cache.contains(3))
    }

    @Test fun replacingAnExistingKeyAccountsBytesCorrectlyRatherThanDoubleCounting() {
        val cache = ByteBudgetedLruCache<Int, Int>(100) { it.toLong() }
        cache.put(1, 10)
        cache.put(1, 50) // replacement, not a second entry
        assertEquals(1, cache.count)
        assertEquals(50L, cache.sizeBytes)
    }

    @Test fun cacheSizeStaysBoundedAcrossManyMoreKeysThanTheBudgetAllows() {
        val cache = ByteBudgetedLruCache<Int, Int>(1000) { 10L } // room for ~100 entries
        repeat(10_000) { cache.put(it, 1) }
        assertTrue("sizeBytes=${cache.sizeBytes} exceeded the 1000-byte budget", cache.sizeBytes <= 1000)
        assertTrue("count=${cache.count} grew proportionally with the number of distinct keys ever put", cache.count <= 100)
    }

    @Test fun clearDropsEveryEntryAndResetsAccounting() {
        val cache = ByteBudgetedLruCache<Int, Int>(100) { it.toLong() }
        cache.put(1, 10); cache.put(2, 20)
        cache.clear()
        assertEquals(0, cache.count)
        assertEquals(0L, cache.sizeBytes)
        assertFalse(cache.contains(1))
    }
}

class NextThumbnailToLoadTest {
    @Test fun picksTheNearestUncachedNotInFlightPageToCenter() {
        assertEquals(5, nextThumbnailToLoad(0, 10, center = 5, cached = { false }, inFlight = { false }))
    }

    @Test fun skipsAlreadyCachedPages() {
        assertEquals(4, nextThumbnailToLoad(0, 10, center = 5, cached = { it == 5 }, inFlight = { false }))
    }

    @Test fun skipsInFlightPages() {
        assertEquals(6, nextThumbnailToLoad(0, 10, center = 5, cached = { it == 5 }, inFlight = { it == 4 }))
    }

    @Test fun returnsNullWhenEveryPageInRangeIsAccountedFor() {
        assertNull(nextThumbnailToLoad(0, 2, center = 1, cached = { true }, inFlight = { false }))
    }

    @Test fun returnsNullForAnInvertedOrEmptyRange() {
        assertNull(nextThumbnailToLoad(5, 3, center = 4, cached = { false }, inFlight = { false }))
    }

    @Test fun exactTiesPreferTheLowerIndex() {
        // 4 and 6 are equidistant from center=5 once 5 itself is excluded; iteration order (4..6) decides the tie.
        assertEquals(4, nextThumbnailToLoad(4, 6, center = 5, cached = { it == 5 }, inFlight = { false }))
    }
}

class ThumbnailLoaderTest {
    /** A controllable fake payload -- not a Bitmap -- so this stays a plain JVM test; see this file's class doc. */
    private data class FakeThumbnail(val page: Int)

    @Test fun lazyLoadingOnlyDecodesTheVisiblePlusPrefetchWindowNotTheWholePublication() = runTest {
        val decoded = mutableListOf<Int>()
        val loader = ThumbnailLoader(scope = this, pageCount = 300, prefetch = 3, sizeOf = { 1L }) { page ->
            decoded += page; FakeThumbnail(page)
        }
        loader.setVisibleRange(150)
        advanceUntilIdle()
        assertTrue("expected a small bounded window, got ${decoded.size} decodes", decoded.size <= 2 * 3 + 1)
        assertTrue("decoded pages should stay near the requested center, got $decoded", decoded.all { it in 147..153 })
        assertTrue(loader.peek(150) is ThumbnailResult.Loaded)
        // The vast majority of a 300-page book must never have been touched.
        assertTrue((0..299).count { loader.peek(it) != null } <= 2 * 3 + 1)
        loader.close() // The worker's collectLatest loop never completes on its own; runTest requires it stopped.
    }

    @Test fun rapidRangeChangesDoNotExhaustivelyProcessEveryHistoricalRange() = runTest {
        val decodeStarted = mutableListOf<Int>()
        val loader = ThumbnailLoader(scope = this, pageCount = 1000, prefetch = 2, sizeOf = { 1L }) { page ->
            decodeStarted += page
            delay(10) // slow enough that a fast scroll genuinely supersedes it before it finishes
            FakeThumbnail(page)
        }
        // Simulate a fast scroll across a 1000-page book: many superseding range changes in quick succession,
        // each one only given a chance to start (not necessarily finish) before the next arrives.
        for (center in 0..900 step 50) { loader.setVisibleRange(center); runCurrent() }
        advanceUntilIdle() // let the final, settled range actually finish.
        // If every historical range were exhaustively drained instead of superseded (collectLatest cancelling the
        // previous one), this would approach 19 ranges * up to 5 pages each (~95); genuine deprioritization keeps
        // it far smaller -- a loose, timing-tolerant bound rather than an exact count, deliberately.
        assertTrue("expected far fewer than an exhaustive sweep of every requested range, got ${decodeStarted.size}",
            decodeStarted.size < 40)
        // The final, settled range must still actually be served -- deprioritizing stale work must never mean
        // losing the result the user actually scrolled to.
        assertTrue(loader.peek(900) is ThumbnailResult.Loaded)
        loader.close()
    }

    @Test fun aFailedDecodeBecomesAPlaceholderAndDoesNotStopOtherPagesFromLoading() = runTest {
        val loader = ThumbnailLoader(scope = this, pageCount = 10, prefetch = 2, sizeOf = { 1L }) { page ->
            if (page == 5) throw RuntimeException("corrupt page fixture") else FakeThumbnail(page)
        }
        loader.setVisibleRange(5)
        advanceUntilIdle()
        assertEquals(ThumbnailResult.Failed, loader.peek(5))
        // Neighboring pages in the same window still loaded successfully -- one corrupt page never poisons the
        // strip or stops the loader's worker loop.
        (3..7).filter { it != 5 }.forEach { page ->
            assertTrue("expected page $page to have loaded despite page 5 failing", loader.peek(page) is ThumbnailResult.Loaded)
        }
        loader.close()
    }

    @Test fun overlappingRangesOnlyDecodeThePagesNotAlreadyCached() = runTest {
        val decodedPages = mutableListOf<Int>()
        val loader = ThumbnailLoader(scope = this, pageCount = 20, prefetch = 2, sizeOf = { 1L }) { page ->
            decodedPages += page; FakeThumbnail(page)
        }
        loader.setVisibleRange(10) // decodes the window 8..12
        advanceUntilIdle()
        assertEquals(setOf(8, 9, 10, 11, 12), decodedPages.toSet())
        val decodedSoFar = decodedPages.size
        loader.setVisibleRange(11) // window 9..13 -- 9..12 already cached, only page 13 is genuinely new
        advanceUntilIdle()
        assertEquals("re-requesting a mostly-overlapping range must only decode the genuinely new page",
            listOf(13), decodedPages.drop(decodedSoFar))
        loader.close()
    }

    @Test fun closeCancelsTheWorkerAndDropsCachedReferences() = runTest {
        val loader = ThumbnailLoader(scope = this, pageCount = 10, prefetch = 2, sizeOf = { 1L }) { page -> FakeThumbnail(page) }
        loader.setVisibleRange(5)
        advanceUntilIdle()
        assertNotNull(loader.peek(5))
        loader.close()
        assertNull(loader.peek(5))
    }

    @Test fun byteBudgetIsRespectedAcrossManyPagesOfAFakeNonTrivialSize() = runTest {
        // Each fake thumbnail "costs" 100 bytes; a 1000-byte budget bounds residency to ~10 entries regardless of
        // how many distinct pages this session has ever scrolled past.
        val loader = ThumbnailLoader(scope = this, pageCount = 500, prefetch = 4, budgetBytes = 1000,
            sizeOf = { 100L }) { page -> FakeThumbnail(page) }
        listOf(10, 100, 200, 300, 400, 490).forEach { center -> loader.setVisibleRange(center); advanceUntilIdle() }
        val resident = (0..499).count { loader.peek(it) is ThumbnailResult.Loaded }
        assertTrue("expected residency bounded by the byte budget (~10), got $resident", resident <= 10)
        loader.close()
    }
}
