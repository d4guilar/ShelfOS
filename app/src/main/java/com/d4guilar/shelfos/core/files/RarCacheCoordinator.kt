// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 3E-C R1A (HIGH-1 remediation): the ONE process-wide coordination/accounting owner for every RAR
 * extraction cache namespace sharing the same [root] (in production, `context.cacheDir/cbr`). Before this class
 * existed, each [RarExtractionCache] instance owned its own independent slots/bytes/locks/leases, so a reopened
 * source never actually reused an earlier materialization (despite the old docs implying it did), each new
 * `RarPageSource.open` effectively got its own fresh 256MiB/64-entry budget, and retired namespace payload sat
 * on disk forever outside any budget -- `cacheDir/cbr` could grow unboundedly in aggregate even though each
 * individual old-style cache looked bounded on its own. This class fixes that: production limits ([maxBytes]/
 * [maxEntries]) apply to the WHOLE [root] tree as one domain, spanning every source namespace a caller ever
 * opens in this process.
 *
 * ## Identity / sharing (one coordinator per root, per process)
 *
 * [getInstance] returns the SAME instance for the SAME canonical [root] path within this process (a
 * [ConcurrentHashMap]-backed registry keyed by [File.getCanonicalPath], never a single ambient "current book"
 * global and never one coarse lock held across I/O). Every [RarPageSource]/[RarExtractionCache] that targets the
 * same cache root therefore shares the identical accounting/lock/lease state, even across independently
 * constructed `RarPageSource` instances -- this is what makes same-namespace-multiple-clients coordination and
 * cross-reopen reuse real rather than aspirational. The first caller to create a root's coordinator decides its
 * [maxBytes]/[maxEntries] for that root's lifetime in this process; a later [getInstance] call for the same root
 * with different budget arguments reuses the existing instance's budgets unchanged (documented, not enforced,
 * since ShelfOS has exactly one production call site for this today -- see `RarPageSource.open`). There is no
 * cross-process locking: nothing in this codebase shares one cache root across processes (single ShelfOS
 * process owns its own `context.cacheDir`), so this coordinator only ever needs to be correct within one JVM.
 *
 * ## Namespace model (what moved, what did not)
 *
 * Source namespace -- `RarPageSource`'s hashed `sourceKey` (or a per-open random id when no stable key is
 * available; see `RarPageSource.open`'s doc for that contract, unchanged by this remediation) -- is still part of
 * every cache entry's identity, it is simply no longer a SEPARATE accounting domain: [Key] is
 * `(namespace, physicalIndex)`, and eviction/byte-budget/entry-count accounting spans every [Key] in [slots]
 * regardless of namespace. On disk, each namespace keeps its own subdirectory directly under [root] (e.g.
 * `cbr/<namespaceHash>/<physicalIndex>.bin`) purely so two namespaces' files can never collide by name --
 * exactly the layout the pre-remediation per-namespace [RarExtractionCache] already wrote, which is precisely
 * what makes lazy disk discovery (below) able to find and trust pre-existing files.
 *
 * ## Lazy disk discovery (real cross-reopen reuse, not just documented)
 *
 * On the FIRST [acquire] call this coordinator instance ever serves (never in a constructor, never an
 * always-on startup scan, and never anything wider than [root] itself), [discoverLocked] walks exactly one
 * level of subdirectories under [root] and recognizes ONLY the ShelfOS-generated layout this class itself
 * writes: a namespace subdirectory directly under [root], containing files named either `<index>.bin` (a
 * finished, trustworthy final -- folded into [slots]/[usedBytes] accounting immediately) or `<index>.tmp-...`
 * (an abandoned temp file from a process that died mid-extraction -- deleted, never trusted). Anything else
 * encountered (an unrecognized file name, a non-directory entry directly under [root], a symlink that would
 * resolve outside [root]) is treated conservatively: deleted rather than silently left as invisible,
 * unaccounted disk payload, which is the exact failure mode this remediation exists to close. Discovery never
 * follows a symlink whose target's canonical path escapes [root] -- [isWithinRoot] guards every discovered
 * file and every namespace directory before either is trusted or deleted. This walk is bounded to [root]'s own
 * two levels (namespace dirs, then their direct file children) and is never recursive beyond that, so it stays
 * cheap even as a lazy, first-use-triggered scan; callers are expected to invoke [acquire] off the main thread
 * (the same expectation [RarArchiveSession.extractEntry] already carries, since both are blocking disk/native
 * I/O) -- this class does not and cannot enforce that itself without a coroutines/threading dependency this
 * module does not otherwise take.
 *
 * Once discovered, a later [acquire] for the SAME `(namespace, physicalIndex)` a previous process already
 * materialized is a genuine cache hit against the discovered file -- no re-extraction, exactly the behavior the
 * old per-instance cache's documentation claimed but never actually delivered across a reopen.
 *
 * ## Source-revision safety (unchanged contract, restated)
 *
 * This class makes no attempt to detect a changed source itself: a reusable `sourceKey`/namespace MUST change
 * when the underlying source's content/revision changes -- that is entirely the caller's responsibility (see
 * `RarPageSource.open`'s doc). A later slice with real product/import integration (3E-D+) is expected to derive
 * a revision-sensitive key from a real `LibraryItem`; this checkpoint only preserves the contract, it does not
 * fulfill it.
 *
 * ## Ephemeral/random namespaces
 *
 * When `RarPageSource.open` has no stable `sourceKey`, it falls back to a fresh random namespace per open (see
 * its own doc). Chosen policy (b): an ephemeral namespace's entries are accounted and evicted by this
 * coordinator EXACTLY like any other namespace's -- there is no separate "clean up on source close" path. This
 * is deliberately the simpler of the two policies the remediation brief allows, and it is still sufficient to
 * close the "unbounded accumulation" defect: because ephemeral-namespace entries live in the SAME global
 * [slots]/[usedBytes]/[maxEntries] accounting as everything else, they can never sit on disk unaccounted, and
 * once nothing requests them again they simply become the least-recently-used victims of ordinary global
 * eviction the next time ANY namespace's [acquire] call pushes the total over budget. The tradeoff: a long-lived
 * process that only ever opens sources with no stable key will, at steady state, have its 256MiB/64-entry
 * budget shared across live ephemeral working-set entries and aging-out ephemeral leftovers, rather than
 * reclaiming ephemeral space immediately on source close -- an accepted, documented cost, not an oversight.
 *
 * ## Eviction
 *
 * Deterministic LRU, bounded by BOTH [maxBytes] and [maxEntries], tie-broken by an injectable monotonic
 * [accessCounter] (never wall-clock time) -- spanning every namespace, exactly like [slots] itself. A [Slot]
 * with `activeReaders > 0` (an [acquire] whose returned handle has not yet been [release]d, across ANY client
 * sharing this coordinator) is NEVER evicted, even if that temporarily leaves the cache over budget.
 *
 * ## Locking / lock ordering
 *
 * [stateLock] guards only [slots]/[usedBytes]/[accessCounter]/[discovered] bookkeeping and is held ONLY for
 * short, allocation-free critical sections -- it is NEVER held across [extract] (native extraction, Bitmap
 * decode, or a long filesystem copy all happen OUTSIDE [stateLock]). One [Any] lock per [Key] (via [keyLocks],
 * never one coarse cross-key lock) serializes two concurrent [acquire] calls for the SAME key so the second
 * caller observes the first caller's now-cached result rather than racing it into a redundant extraction or a
 * partially-overwritten final file; different keys may proceed concurrently at this layer. Lock ordering
 * relative to [NativeRarSession]'s own per-session monitor: this coordinator always acquires a key lock (and,
 * briefly, [stateLock]) BEFORE ever calling into [extract] (which may, transitively, acquire a
 * [NativeRarSession] instance's own lock) -- never the reverse, and [stateLock] is never held while [extract]
 * runs. [NativeRarSession] never calls back into this coordinator. That one-directional dependency
 * (coordinator lock -> session lock, never session lock -> coordinator lock) makes a deadlock between the two
 * structurally impossible, not merely untested.
 */
class RarCacheCoordinator private constructor(
    private val root: File,
    private val maxBytes: Long,
    private val maxEntries: Int,
) {
    /** Cache entry identity: source namespace plus the RAR entry's own physical ordinal -- see the class doc's
     * "Namespace model" section for why namespace is part of identity but no longer a separate accounting
     * domain. */
    data class Key(val namespace: String, val physicalIndex: Int)

    private class Slot(val file: File, val bytes: Long, var lastAccess: Long, var activeReaders: Int)

    private val stateLock = Any()
    private val slots = HashMap<Key, Slot>()
    private var accessCounter = 0L
    private var usedBytes = 0L
    private val keyLocks = ConcurrentHashMap<Key, Any>()
    private val tempFileSequence = AtomicLong(0L)

    @Volatile private var discovered = false

    /**
     * Returns a handle to `(namespace, physicalIndex)`'s materialized bytes, extracting via [extract] exactly
     * once per distinct key (cache miss, including a cache miss discovered-but-never-requested-before on a
     * fresh process) and reusing the same on-disk file for every later call for that same key (cache hit --
     * including, now, a hit against a file [discoverLocked] found on disk from an EARLIER process/instance; see
     * the class doc's "Lazy disk discovery" section). The returned [CachedExtraction] MUST be released exactly
     * once. Throws [RarExtractionException] (never leaving a partial file behind) if [extract] reports a
     * [NativeRarError], the temp file does not end up existing, or its size exceeds
     * [RarExtractionCache.MAX_ENTRY_BYTES].
     */
    fun acquire(namespace: String, physicalIndex: Int, extract: (File) -> NativeRarError?): CachedExtraction {
        ensureDiscovered()
        val key = Key(namespace, physicalIndex)
        val keyLock = keyLocks.computeIfAbsent(key) { Any() }
        synchronized(keyLock) {
            synchronized(stateLock) {
                slots[key]?.let { slot ->
                    slot.activeReaders++
                    slot.lastAccess = ++accessCounter
                    return CachedExtraction(slot.file) { release(key) }
                }
            }

            val namespaceDir = File(root, namespace).apply { mkdirs() }
            val finalFile = File(namespaceDir, "$physicalIndex.bin")
            val tempFile = File(
                namespaceDir,
                "$physicalIndex.tmp-${System.nanoTime()}-${tempFileSequence.incrementAndGet()}",
            )
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
            if (bytes > RarExtractionCache.MAX_ENTRY_BYTES) {
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

    private fun release(key: Key) {
        synchronized(stateLock) {
            slots[key]?.let { it.activeReaders = (it.activeReaders - 1).coerceAtLeast(0) }
        }
    }

    /** Must be called with [stateLock] held. Spans every namespace's entries -- see the class doc's "Eviction"
     * section. */
    private fun evictLocked() {
        while (usedBytes > maxBytes || slots.size > maxEntries) {
            val victim = slots.entries.filter { it.value.activeReaders == 0 }.minByOrNull { it.value.lastAccess }
                ?: break // every remaining entry is actively referenced -- never evict one out from under a reader.
            usedBytes -= victim.value.bytes
            victim.value.file.delete()
            slots.remove(victim.key)
        }
    }

    private fun ensureDiscovered() {
        if (discovered) return
        synchronized(stateLock) {
            if (discovered) return
            discoverLocked()
            discovered = true
        }
    }

    /** Must be called with [stateLock] held, and only once (guarded by [discovered]). See the class doc's "Lazy
     * disk discovery" section for the exact recognized layout and conservative-deletion policy. */
    private fun discoverLocked() {
        root.mkdirs()
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return
        val topLevel = root.listFiles() ?: return
        for (entry in topLevel) {
            if (!isWithinRoot(entry, rootCanonical)) continue
            if (!entry.isDirectory) {
                // Nothing valid is ever written directly under root -- only namespace subdirectories are.
                runCatching { entry.delete() }
                continue
            }
            val namespace = entry.name
            val files = entry.listFiles() ?: continue
            for (file in files) {
                if (!isWithinRoot(file, rootCanonical)) continue
                if (!file.isFile) continue
                if (TEMP_NAME.matches(file.name)) {
                    runCatching { file.delete() }
                    continue
                }
                val match = FINAL_NAME.matchEntire(file.name)
                val index = match?.groupValues?.get(1)?.toIntOrNull()
                if (match == null || index == null) {
                    // Unrecognized file inside a namespace directory: never trust it, never let it sit as
                    // invisible/unaccounted payload -- delete conservatively.
                    runCatching { file.delete() }
                    continue
                }
                val bytes = file.length()
                slots[Key(namespace, index)] = Slot(file, bytes, ++accessCounter, activeReaders = 0)
                usedBytes += bytes
            }
        }
        evictLocked() // discovered total may already exceed budget on a fresh process.
    }

    /** Defends discovery against a symlink (namespace dir or file) whose target resolves outside [root] --
     * discovery must never follow or trust anything beyond [root]'s own tree. */
    private fun isWithinRoot(file: File, rootCanonical: File): Boolean {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return canonical == rootCanonical || canonical.path.startsWith(rootCanonical.path + File.separator)
    }

    // Test-observability surface only (no production caller) -- see RarCacheCoordinatorTest.
    internal val entryCountForTest: Int get() = synchronized(stateLock) { slots.size }
    internal val usedBytesForTest: Long get() = synchronized(stateLock) { usedBytes }
    internal fun containsForTest(namespace: String, key: Int): Boolean =
        synchronized(stateLock) { slots.containsKey(Key(namespace, key)) }
    internal fun activeReadersForTest(namespace: String, key: Int): Int =
        synchronized(stateLock) { slots[Key(namespace, key)]?.activeReaders ?: 0 }

    companion object {
        private val TEMP_NAME = Regex("""-?\d+\.tmp-.*""")
        private val FINAL_NAME = Regex("""(-?\d+)\.bin""")

        /** Disk, not RAM -- see [RarExtractionCache]'s own constant doc for the sizing rationale, unchanged by
         * this remediation other than now applying globally instead of per-instance. */
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024 * 1024

        /** See [RarExtractionCache]'s own constant doc for the sizing rationale, unchanged by this remediation
         * other than now applying globally instead of per-instance. */
        const val DEFAULT_MAX_ENTRIES: Int = 64

        private val registry = ConcurrentHashMap<String, RarCacheCoordinator>()

        /**
         * Test seam only: constructs a standalone coordinator that is NEVER registered in [registry], letting a
         * test simulate "a different process re-opening the same on-disk cache root" -- i.e. a coordinator with
         * no in-memory state at all, so the only way it can know about a prior materialization is through its
         * own lazy [discoverLocked] walk of [root]. Real production code always goes through [getInstance]'s
         * shared-singleton path; this overload exists purely so [RarCacheCoordinatorTest] can prove cross-reopen
         * disk discovery without relying on JVM-wide registry state leaking between test methods.
         */
        internal fun createForTest(
            root: File,
            maxBytes: Long = DEFAULT_MAX_BYTES,
            maxEntries: Int = DEFAULT_MAX_ENTRIES,
        ): RarCacheCoordinator {
            root.mkdirs()
            return RarCacheCoordinator(root, maxBytes, maxEntries)
        }

        /**
         * Returns the single [RarCacheCoordinator] for [root]'s canonical path within this process, creating one
         * on first call. See the class doc's "Identity / sharing" section for the first-caller-wins budget
         * policy and why no cross-process coordination is needed here.
         */
        fun getInstance(
            root: File,
            maxBytes: Long = DEFAULT_MAX_BYTES,
            maxEntries: Int = DEFAULT_MAX_ENTRIES,
        ): RarCacheCoordinator {
            root.mkdirs()
            val canonicalPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            return registry.computeIfAbsent(canonicalPath) { RarCacheCoordinator(root, maxBytes, maxEntries) }
        }
    }
}
