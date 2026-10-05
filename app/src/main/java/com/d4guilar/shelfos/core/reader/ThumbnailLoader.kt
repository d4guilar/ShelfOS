// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Byte-budgeted (not item-count-bounded) least-recently-used cache. Generic over the cached value type so this is
 * independently unit-testable in a plain JVM test with a trivial fake value and a controllable size -- this
 * project's local unit tests cannot construct a real `android.graphics.Bitmap` (see [PageRenderRequestTest]'s doc;
 * `BitmapFactory`/`PdfRenderer` are unavailable there), so [ThumbnailLoader] below stays generic for the same
 * reason rather than hard-coding `Bitmap` into the part of this file that most needs direct test coverage (eviction
 * correctness, replacement byte accounting).
 *
 * Backed by a `LinkedHashMap` in access-order mode, so [get] itself counts as a "use" for LRU purposes, matching
 * the standard LRU contract (`LinkedHashMap(initialCapacity, loadFactor, accessOrder = true)`).
 */
class ByteBudgetedLruCache<K, V>(private val budgetBytes: Long, private val sizeOf: (V) -> Long) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var usedBytes = 0L

    @Synchronized fun get(key: K): V? = entries[key]
    @Synchronized fun contains(key: K): Boolean = entries.containsKey(key)

    /** Replacing an existing [key] correctly subtracts its old size before adding the new one -- a value held at
     * the same key never double-counts its own previous occupancy. */
    @Synchronized fun put(key: K, value: V) {
        entries.remove(key)?.let { old -> usedBytes -= sizeOf(old) }
        entries[key] = value
        usedBytes += sizeOf(value)
        evict()
    }

    /** Evicts least-recently-used entries (eldest-first iteration order, since this map is access-order) until
     * back within [budgetBytes]. A cache size that never grows proportionally with how many distinct keys have
     * ever been put -- only with how many distinct values currently fit the budget -- is exactly this loop's job. */
    private fun evict() {
        val iterator = entries.entries.iterator()
        while (usedBytes > budgetBytes && iterator.hasNext()) {
            val removed = iterator.next()
            usedBytes -= sizeOf(removed.value)
            iterator.remove()
        }
    }

    @Synchronized fun clear() { entries.clear(); usedBytes = 0L }
    val sizeBytes: Long @Synchronized get() = usedBytes
    val count: Int @Synchronized get() = entries.size
}

/**
 * Pure scheduling policy: which page (if any) in [lo]..[hi] should be decoded next, nearest-to-[center] first,
 * skipping any page already [cached] or already [inFlight]. Stateless and side-effect-free on purpose, so the
 * "lazy, bounded, center-out, no duplicate work" policy [ThumbnailLoader] relies on is independently unit-testable
 * without any real decode, coroutine, or cache instance. Returns `null` once every page in range is accounted for
 * (already cached or already being decoded) -- the loader's own worker loop uses that `null` as its "nothing left
 * to do for this range" stop condition.
 */
fun nextThumbnailToLoad(lo: Int, hi: Int, center: Int, cached: (Int) -> Boolean, inFlight: (Int) -> Boolean): Int? {
    if (hi < lo) return null
    return (lo..hi).filterNot { cached(it) || inFlight(it) }.minByOrNull { abs(it - center) }
}

/** One cached thumbnail outcome. [Failed] deliberately carries no bitmap/payload -- a decode failure must never
 * retain a large decoded object (AGENTS.md's "do not invent a second page-numbering model" cousin here is "do not
 * let a failure look like a cache hit worth real bytes"). */
sealed class ThumbnailResult<out T> {
    data class Loaded<T>(val value: T) : ThumbnailResult<T>()
    object Failed : ThumbnailResult<Nothing>()
}

/**
 * Lazy, bounded, cancellation-aware page-thumbnail loader for one fixed-reader session (Phase 3B). Scoped to the
 * active reader, not an app-global cache: a disposable, derived presentation artifact exactly like the rest of the
 * fixed-reader render path -- [close] drops every cached reference and stops the worker, and nothing here is ever
 * written to disk or Room. Generic over the decoded value type [T] purely for testability (see
 * [ByteBudgetedLruCache]'s doc); the only real caller
 * ([com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]) instantiates this with `T = android.graphics.Bitmap`.
 *
 * **Concurrency.** [decode] is invoked strictly one page at a time by this loader's own single worker coroutine --
 * there is no internal fan-out to multiple concurrent decodes. The real wiring additionally routes [decode] through
 * the *same* render mutex/session [com.d4guilar.shelfos.feature.reader.FixedReaderViewModel] already uses for
 * full-page reads, because neither `android.graphics.pdf.PdfRenderer` nor the CBZ `PageSource`/`SeekableZip` path
 * documents safety for concurrent access from multiple threads -- a thumbnail decode at
 * [PageRenderRequest.THUMBNAIL_MAX_DIMENSION] is small and fast relative to a full-page decode, so this briefly
 * serializes with (never indefinitely blocks) page-turn rendering rather than risking a concurrent-access defect.
 *
 * **Laziness/prefetch.** [setVisibleRange] records the thumbnail strip's current center page; the worker computes a
 * `[center - prefetch, center + prefetch]` window and decodes only the missing pages in it, nearest-to-center
 * first -- never the whole publication. A 300+ page publication therefore never causes 300 decodes; only pages the
 * UI has actually scrolled near ever get decoded (`docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3B large-publication
 * requirement).
 *
 * **Cancellation.** Each [setVisibleRange] call supersedes the previous one via `collectLatest`: a fast scroll
 * cancels the "keep decoding this range" loop at its next suspension point rather than letting superseded ranges
 * queue up behind it. The one piece of this loader that genuinely cannot be interrupted mid-call is the synchronous
 * decode itself -- `BitmapFactory`/`PdfRenderer` are not cooperatively cancellable once a decode has actually
 * started -- so at most one already-started decode is ever allowed to finish before a newer range takes over; that
 * decode's result is still stored at its own true page key (never "the wrong slot"), so a late-arriving result from
 * an abandoned range is simply a harmless, possibly-unnecessary cache entry, not a correctness defect.
 */
class ThumbnailLoader<T : Any>(
    scope: CoroutineScope,
    private val pageCount: Int,
    private val prefetch: Int = DEFAULT_PREFETCH,
    budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    sizeOf: (T) -> Long,
    private val decode: suspend (page: Int) -> T,
) {
    private val cache = ByteBudgetedLruCache<Int, ThumbnailResult<T>>(budgetBytes) {
        (it as? ThumbnailResult.Loaded<T>)?.value?.let(sizeOf) ?: 0L
    }
    private val inFlight = mutableSetOf<Int>()
    private val wanted = MutableStateFlow(-1)
    private val _revision = MutableStateFlow(0L)

    /** Bumped once per settled decode (success or failure); UI observes this to know when to re-[peek]. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val worker: Job = scope.launch {
        wanted.collectLatest { center ->
            if (center < 0 || pageCount <= 0) return@collectLatest
            val lo = (center - prefetch).coerceAtLeast(0)
            val hi = (center + prefetch).coerceAtMost(pageCount - 1)
            while (true) {
                val next = nextThumbnailToLoad(lo, hi, center, cache::contains, inFlight::contains) ?: break
                inFlight += next
                try {
                    val value = decode(next)
                    cache.put(next, ThumbnailResult.Loaded(value))
                } catch (e: CancellationException) {
                    inFlight -= next
                    throw e
                } catch (e: Exception) {
                    // A single corrupt/failed page must never poison the strip or crash the loader (3B's corrupt-
                    // page requirement); every other page keeps loading normally on the next loop iteration.
                    cache.put(next, ThumbnailResult.Failed)
                }
                inFlight -= next
                _revision.update { it + 1 }
            }
        }
    }

    /** Declares the strip's current center page; out-of-range values are clamped rather than ignored, so a caller
     * never needs to pre-validate against [pageCount]. */
    fun setVisibleRange(center: Int) {
        if (pageCount <= 0) return
        wanted.value = center.coerceIn(0, pageCount - 1)
    }

    fun peek(page: Int): ThumbnailResult<T>? = cache.get(page)

    /** Cancels the worker and drops every cached reference. Safe to call more than once. Never recycles/disposes
     * an individual cached value itself (Compose may still hold its own reference to a just-displayed thumbnail
     * image) -- disposal is left to the garbage collector once nothing else references it, exactly like a
     * currently-displayed full-page bitmap already is (see [com.d4guilar.shelfos.feature.reader.FixedReaderViewModel.render]'s doc). */
    fun close() { worker.cancel(); cache.clear() }

    companion object {
        /** Pages decoded on each side of the strip's center; a 2*6+1 = 13-page working set comfortably covers any
         * realistic visible+near-visible thumbnail window without approaching "decode everything". */
        const val DEFAULT_PREFETCH = 6

        /** ~16MiB: a conservative session-local thumbnail budget, reasoned the same way as
         * [RenderMemoryPolicy.SESSION_BUDGET_BYTES] but proportionally much smaller, since a thumbnail is a small
         * bitmap held in much greater quantity rather than one large bitmap held briefly. Worst case (a perfectly
         * square page at [PageRenderRequest.THUMBNAIL_MAX_DIMENSION]) is `320*320*4 ≈ 410KB`; 16MiB / 410KB ≈ 40
         * worst-case-square thumbnails resident at once -- comfortably more than [DEFAULT_PREFETCH]'s 13-page
         * working set even on a wide foldable/tablet strip, while staying a small fraction of
         * [RenderMemoryPolicy.SESSION_BUDGET_BYTES] (96MiB) for full-page reading. */
        const val DEFAULT_BUDGET_BYTES = 16L * 1024 * 1024
    }
}
