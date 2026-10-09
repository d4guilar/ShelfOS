// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Phase 3E-C R1A: a thin, single-namespace-scoped FACADE over the real cache owner, [RarCacheCoordinator].
 * RAR -- especially solid RAR -- is not cheaply re-seekable (see [NativeRarSession]'s "Seek/restart" doc): every
 * [RarArchiveSession.extractEntry] call independently restarts and re-scans the archive from the beginning.
 * [acquire] materializes one physical entry's bytes into a ShelfOS-generated file exactly once per distinct
 * `key` within this cache's [namespace] and reuses that file for every later [acquire] of the same `key` -- so a
 * bounds-decode followed by a full-decode of the same logical page (the normal `ImagePageRenderer` path) costs
 * exactly one native extraction, not two.
 *
 * ## Why this class is now a facade, not the cache owner
 *
 * Before Phase 3E-C R1A, each instance of this class owned its OWN independent slots/bytes/locks -- so the
 * 256MiB/64-entry budget below was really "256MiB/64 entries PER NAMESPACE PER INSTANCE", not a real global
 * bound, and a reopened source's earlier materializations were never actually rediscovered across process
 * restarts despite the old docs implying they would be. [RarCacheCoordinator] now owns ALL of that state,
 * shared across every namespace and every `RarExtractionCache`/`RarPageSource` instance targeting the same
 * cache root within this process -- see its class doc for the full architecture. This class exists only so
 * `RarPageSource`'s call site (`cache.acquire(key, extract)`) stays simple and namespace-scoped, without every
 * caller needing to pass its own namespace string on every call.
 *
 * ## Cache identity / keying
 *
 * `key` is the RAR entry's own physical ordinal (an [Int]), never a filename -- duplicate filenames at different
 * ordinals can never collide, because the on-disk filename ultimately written (`"$key.bin"`) is derived
 * entirely from that ordinal, never from any archive-controlled string. [namespace] is the source-namespace
 * boundary: two different archives (or two revisions of the same archive) sharing the same coordinator but
 * different namespaces never share cached bytes (see [RarCacheCoordinator]'s "Namespace model" section, and
 * `RarPageSource`'s doc for how a namespace is derived from a caller-supplied source key, or falls back to a
 * random one when none is available).
 *
 * ## Atomicity / per-entry byte budget / concurrency / eviction
 *
 * All real behavior now lives in [RarCacheCoordinator] -- see its class doc for atomicity (temp-then-atomic-
 * rename), the per-entry [MAX_ENTRY_BYTES] budget (now enforced DURING extraction by a real native streaming
 * ceiling as of Phase 3E-C R1A's HIGH-2 fix -- see `NativeRarSession.extractEntry`'s `maxOutputBytes`
 * parameter, no longer only a post-hoc check), per-key lock deduplication, and global LRU eviction across every
 * namespace sharing the same coordinator.
 */
class RarExtractionCache(
    private val coordinator: RarCacheCoordinator,
    private val namespace: String,
) {
    /**
     * Returns a handle to [key]'s materialized bytes within this cache's [namespace], extracting via [extract]
     * exactly once per distinct `(namespace, key)` (cache miss -- including a miss resolved by
     * [RarCacheCoordinator]'s lazy disk discovery of an earlier process's materialization) and reusing the same
     * on-disk file for every later call (cache hit). The returned [CachedExtraction] MUST be released exactly
     * once -- either via [CachedExtraction.release] directly, or implicitly by closing the [InputStream]
     * returned by [CachedExtraction.open].
     *
     * Throws [RarExtractionException] (never leaving a partial file behind) if [extract] reports a
     * [NativeRarError], the temp file does not end up existing, or its size exceeds [MAX_ENTRY_BYTES].
     */
    fun acquire(key: Int, extract: (File) -> NativeRarError?): CachedExtraction =
        coordinator.acquire(namespace, key, extract)

    // Test-observability surface only (no production caller) -- see RarExtractionCacheTest/RarCacheCoordinatorTest.
    internal val entryCountForTest: Int get() = coordinator.entryCountForTest
    internal val usedBytesForTest: Long get() = coordinator.usedBytesForTest
    internal fun containsForTest(key: Int): Boolean = coordinator.containsForTest(namespace, key)
    internal fun activeReadersForTest(key: Int): Int = coordinator.activeReadersForTest(namespace, key)

    companion object {
        /** Reuses [ArchivePolicy.MAX_IMAGE_BYTES] -- as of Phase 3E-C R1A's HIGH-2 fix, this is the same value
         * `RarPageSource` passes as `NativeRarSession.extractEntry`'s real, mid-stream `maxOutputBytes` ceiling,
         * not only a post-hoc check against the finished temp file's length (the latter remains as defense in
         * depth). */
        const val MAX_ENTRY_BYTES: Long = ArchivePolicy.MAX_IMAGE_BYTES

        /** Forwards to [RarCacheCoordinator.DEFAULT_MAX_BYTES] -- kept here too since `RarPageSource.open`'s
         * default parameter historically referenced this name; the real constant (and its sizing rationale) now
         * lives on the class that actually enforces it globally. */
        const val DEFAULT_MAX_BYTES: Long = RarCacheCoordinator.DEFAULT_MAX_BYTES

        /** Forwards to [RarCacheCoordinator.DEFAULT_MAX_ENTRIES] -- see [DEFAULT_MAX_BYTES]'s doc. */
        const val DEFAULT_MAX_ENTRIES: Int = RarCacheCoordinator.DEFAULT_MAX_ENTRIES
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
