// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, on-disk, atomic extraction cache for `RarPageSource` (Phase 3E-C). RAR -- especially solid RAR -- is
 * not cheaply re-seekable (see [NativeRarSession]'s "Seek/restart" doc): every [RarArchiveSession.extractEntry]
 * call independently restarts and re-scans the archive from the beginning. [acquire] materializes one physical
 * entry's bytes into a ShelfOS-generated file exactly once per distinct `key` and reuses that file for every
 * later [acquire] of the same `key` -- so a bounds-decode followed by a full-decode of the same logical page (the
 * normal `ImagePageRenderer` path) costs exactly one native extraction, not two.
 *
 * ## Cache identity / keying
 *
 * `key` is the RAR entry's own physical ordinal (an [Int]), never a filename -- duplicate filenames at different
 * ordinals, or the same ordinal reused across different namespaces (see below), can never collide, because the
 * on-disk filename this cache writes (`"$key.bin"`) is derived entirely from that ordinal, never from any
 * archive-controlled string. [root] itself is the source-namespace boundary: a caller that wants two different
 * archives (or two revisions of the same archive) to never share cached bytes constructs two [RarExtractionCache]
 * instances rooted at two different directories -- this class makes no attempt to detect a changed source itself
 * (see `RarPageSource`'s doc for how it derives [root] from a caller-supplied source key, or falls back to a
 * session-scoped random one when none is available).
 *
 * ## Atomicity
 *
 * [acquire]'s cache-miss path extracts into a ShelfOS-generated TEMPORARY sibling file, verifies the extraction
 * succeeded (native error `null`, file present, within [MAX_ENTRY_BYTES]), and only then atomically renames it to
 * the final cache filename ([File.renameTo], same-directory rename -- POSIX-atomic on every filesystem Android
 * actually ships). A failed/aborted extraction deletes the temp file and never produces (or leaves behind) a
 * final-named file; a prior leftover temp file from an abandoned process is lazily cleaned up the first time this
 * cache is constructed for its [root] (see [cleanupStaleTempFiles]), never at any wider/always-on startup scope.
 *
 * Per-entry byte budget: [MAX_ENTRY_BYTES] reuses [ArchivePolicy.MAX_IMAGE_BYTES] (128MiB) -- the same per-page-
 * image ceiling CBZ already enforces -- checked AFTER extraction completes by measuring the real temp file size.
 * **Honesty note**: this is a post-hoc check, not a true mid-stream abort -- [RarArchiveSession.extractEntry] (and,
 * beneath it, [NativeRarSession.extractEntry]) is one blocking native call with no interruption point, and adding
 * one would mean changing 3E-B's native C++, which this checkpoint deliberately does not do (see
 * `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3E-C record). An oversized entry still always fails -- cleanly, with its
 * temp file deleted and never promoted to a cache hit -- just not before the OS has finished writing the bytes.
 *
 * ## Concurrency
 *
 * One lock object per `key` (never a single process-global archive lock): two concurrent [acquire] calls for the
 * SAME `key` serialize on that key's lock, so the second caller simply observes the first caller's now-cached
 * result instead of racing it into a second redundant extraction or a partially-overwritten final file. Calls for
 * DIFFERENT keys use different lock objects and may proceed concurrently at this layer (though
 * [NativeRarSession] still only ever processes one operation at a time per session underneath -- see its own
 * "Thread-safety contract" doc -- so this cache's own locking is about correctness/deduplication, not about
 * unlocking real native parallelism).
 *
 * ## Eviction
 *
 * Deterministic LRU, bounded by BOTH [maxBytes] and [maxEntries] (eviction runs whenever either is exceeded,
 * mirroring [com.d4guilar.shelfos.core.reader.ByteBudgetedLruCache]'s own two-bound philosophy), tie-broken by an
 * injectable monotonic [accessCounter] -- never wall-clock time, so eviction order is fully deterministic and
 * testable with tiny budgets and no `Thread.sleep()`. A [Slot] with `activeReaders > 0` (an [acquire] whose
 * returned handle has not yet been released) is NEVER evicted, even if that temporarily leaves the cache over
 * budget -- an active reader is never pulled out from under a renderer mid-decode.
 */
class RarExtractionCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private class Slot(val file: File, val bytes: Long, var lastAccess: Long, var activeReaders: Int)

    private val stateLock = Any()
    private val slots = HashMap<Int, Slot>()
    private var accessCounter = 0L
    private var usedBytes = 0L
    private val keyLocks = ConcurrentHashMap<Int, Any>()
    private val tempFileSequence = AtomicLong(0L)

    init {
        root.mkdirs()
        cleanupStaleTempFiles()
    }

    /** Lazy, construction-time-only cleanup, bounded to exactly [root]'s own immediate contents (never a wider
     * filesystem scan) -- handles a temp file abandoned by a previous process death mid-extraction. */
    private fun cleanupStaleTempFiles() {
        root.listFiles()?.forEach { file -> if (TEMP_NAME.matches(file.name)) runCatching { file.delete() } }
    }

    /**
     * Returns a handle to [key]'s materialized bytes, extracting via [extract] exactly once per [key] (cache
     * miss) and reusing the same on-disk file for every later call (cache hit). The returned [CachedExtraction]
     * MUST be released exactly once -- either via [CachedExtraction.release] directly, or implicitly by closing
     * the [InputStream] returned by [CachedExtraction.open].
     *
     * Throws [RarExtractionException] (never leaving a partial file behind) if [extract] reports a
     * [NativeRarError], the temp file does not end up existing, or its size exceeds [MAX_ENTRY_BYTES].
     */
    fun acquire(key: Int, extract: (File) -> NativeRarError?): CachedExtraction {
        val keyLock = keyLocks.computeIfAbsent(key) { Any() }
        synchronized(keyLock) {
            synchronized(stateLock) {
                slots[key]?.let { slot ->
                    slot.activeReaders++
                    slot.lastAccess = ++accessCounter
                    return CachedExtraction(slot.file) { release(key) }
                }
            }

            val finalFile = finalFileFor(key)
            val tempFile = File(root, "$key.tmp-${System.nanoTime()}-${tempFileSequence.incrementAndGet()}")
            val error = try {
                extract(tempFile)
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
            if (error != null) {
                tempFile.delete()
                throw RarExtractionException(error)
            }
            if (!tempFile.isFile) {
                tempFile.delete()
                throw RarExtractionException(NativeRarError.NATIVE_INTERNAL)
            }
            val bytes = tempFile.length()
            if (bytes > MAX_ENTRY_BYTES) {
                tempFile.delete()
                throw RarExtractionException(NativeRarError.INVALID_ARGUMENT)
            }
            // Defensive: a stale final file (e.g. from a corrupted prior cache generation) must never be trusted
            // merely because a rename target with this name exists.
            finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                throw RarExtractionException(NativeRarError.IO)
            }

            synchronized(stateLock) {
                val slot = Slot(finalFile, bytes, ++accessCounter, activeReaders = 1)
                slots[key] = slot
                usedBytes += bytes
                evictLocked()
            }
            return CachedExtraction(finalFile) { release(key) }
        }
    }

    private fun release(key: Int) {
        synchronized(stateLock) {
            slots[key]?.let { it.activeReaders = (it.activeReaders - 1).coerceAtLeast(0) }
        }
    }

    /** Must be called with [stateLock] held. */
    private fun evictLocked() {
        while (usedBytes > maxBytes || slots.size > maxEntries) {
            val victim = slots.entries.filter { it.value.activeReaders == 0 }.minByOrNull { it.value.lastAccess }
                ?: break // every remaining entry is actively referenced -- never evict one out from under a reader.
            usedBytes -= victim.value.bytes
            victim.value.file.delete()
            slots.remove(victim.key)
        }
    }

    private fun finalFileFor(key: Int) = File(root, "$key.bin")

    // Test-observability surface only (no production caller) -- see RarExtractionCacheTest.
    internal val entryCountForTest: Int get() = synchronized(stateLock) { slots.size }
    internal val usedBytesForTest: Long get() = synchronized(stateLock) { usedBytes }
    internal fun containsForTest(key: Int): Boolean = synchronized(stateLock) { slots.containsKey(key) }
    internal fun activeReadersForTest(key: Int): Int = synchronized(stateLock) { slots[key]?.activeReaders ?: 0 }

    companion object {
        private val TEMP_NAME = Regex("""-?\d+\.tmp-.*""")

        /** Reuses [ArchivePolicy.MAX_IMAGE_BYTES] -- see the class doc's "Atomicity" section for why this is a
         * post-hoc, not mid-stream, check. */
        const val MAX_ENTRY_BYTES: Long = ArchivePolicy.MAX_IMAGE_BYTES

        /** Disk, not RAM: deliberately far larger than
         * [com.d4guilar.shelfos.core.reader.RenderMemoryPolicy.SESSION_BUDGET_BYTES] (~96MiB in-memory) or
         * [com.d4guilar.shelfos.core.reader.ThumbnailLoader.DEFAULT_BUDGET_BYTES] (16MiB), since an on-disk
         * artifact in `cacheDir` is not competing for the same scarce resource those two budgets protect, and
         * `cacheDir` is OS-reclaimable under storage pressure regardless. 256MiB comfortably holds several
         * full-resolution comic pages at [MAX_ENTRY_BYTES]'s own 128MiB per-entry ceiling (including a spread
         * pair plus the bounds+full-decode reuse this cache exists for), while remaining a small, explicitly
         * bounded footprint rather than an unbounded one. */
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024

        /** Mirrors [com.d4guilar.shelfos.core.reader.ByteBudgetedLruCache.DEFAULT_MAX_ENTRIES] (Phase 3B's
         * thumbnail cache uses the identical 64-entry reasoning): substantially larger than any realistic
         * single-session working set of concurrently relevant pages (a spread pair, a thumbnail-adjacent
         * prefetch window), while still bounding a degenerate all-tiny-entries case the byte budget alone would
         * not catch. */
        const val DEFAULT_MAX_ENTRIES: Int = 64
    }
}

/**
 * A reusable handle to one [RarExtractionCache] slot's materialized file. Exactly one of [open] or [release] must
 * eventually be called to balance the reference [RarExtractionCache.acquire] created. [release] is idempotent
 * (safe to call more than once; only the first call has any effect) so a caller can never double-release by
 * accident.
 */
class CachedExtraction internal constructor(val file: File, private val onRelease: () -> Unit) {
    @Volatile private var released = false

    /** Opens a fresh [InputStream] over [file]; closing the returned stream releases this handle exactly once.
     * Safe to call more than once (each call opens its own independent stream), but [RarPageSource] never does
     * so in practice -- one [acquire] -> one [open] -> one close. */
    fun open(): InputStream = ReleasingInputStream(FileInputStream(file)) { release() }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

private class ReleasingInputStream(
    private val delegate: InputStream,
    private val onClose: () -> Unit,
) : InputStream() {
    private var closed = false
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun close() {
        if (closed) return
        closed = true
        try {
            delegate.close()
        } finally {
            onClose()
        }
    }
}
