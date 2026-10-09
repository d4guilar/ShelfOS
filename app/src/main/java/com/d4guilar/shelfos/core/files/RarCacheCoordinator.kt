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
 * since ShelfOS has exactly one production call site for this today -- see [RarContainer.open], moved there from
 * `RarPageSource.open` by the Phase 3E-D R1A layering fix). There is no
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
 * [RarContainer.open]'s doc). Phase 3E-D R1A's HIGH-2 fix is the product/import integration that fulfills this
 * contract: [com.d4guilar.shelfos.domain.library.rarCacheSourceKey] derives a stable key from a real
 * `LibraryItem` only when its `managedPath` proves the source is a ShelfOS-owned immutable private copy, and
 * returns `null` (ephemeral) for every other source -- never a key derived from unrefreshed, potentially stale
 * persisted metadata.
 *
 * ## Ephemeral/random namespaces
 *
 * When [RarContainer.open] has no stable `sourceKey`, it falls back to a fresh random namespace per open (see
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
 * sharing this coordinator) is NEVER evicted, even if that temporarily leaves the cache over budget. Releasing
 * its last reader reruns eviction immediately. A failed final-file deletion stays accounted; other inactive
 * candidates are attempted in LRU order, with one deterministic pass when none can be removed. A final that has
 * already vanished from disk (e.g. the app cache was cleared while ShelfOS was running) is not a failed deletion:
 * its accounting is reclaimed on eviction, and a later [acquire] of that key re-materializes it instead of
 * returning a handle to a missing file (Phase 3E-E).
 *
 * ## Missing finals (Phase 3E-E R1A)
 *
 * The moment [acquire] observes that a key's [Slot] exists but its final is no longer a file, that slot is
 * removed from [slots] and its bytes from [usedBytes] -- BEFORE any re-materialization is attempted, so the
 * phantom payload stops counting toward the byte and entry budgets even if re-materialization then fails,
 * throws, or is never retried. Outstanding leases on the vanished slot are carried in [leaseOnly], a
 * lease-only holder that accounts zero bytes, is never an entry, and is never an eviction candidate. A later
 * successful materialization of the same key folds those leases into its new slot exactly once; otherwise the
 * holder is removed when its last lease is released. [leaseOnly] is therefore bounded by outstanding handles,
 * never by history.
 *
 * ## Empty namespace directories (Phase 3E-E R1A)
 *
 * Eviction deletes finals, and ephemeral (per-open random) namespaces are never requested again, so without
 * pruning `cbr/<namespace>/` directories would accumulate forever. [pruneNamespaceIfIdleLocked] removes a
 * namespace directory only when, under [stateLock], that namespace has no [slots] entry, no [leaseOnly]
 * holder, and no in-flight materialization ([inFlight]). It uses a non-recursive [File.delete], so a
 * non-empty directory (e.g. a final whose deletion failed) is never removed, and only a directory whose
 * canonical path is exactly `<canonical root>/<namespace>` (never a symlink, never outside [root]). Pruning is
 * incremental: after eviction removes a final, after a failed materialization's temp cleanup, after a
 * lease-only holder's last release, and for empty directories met by lazy discovery. There is no per-access
 * root scan. [inFlight] holds one counter per namespace with an active materialization and drops it at zero,
 * so it is bounded by concurrent activity, not by history.
 *
 * ## Locking / lock ordering
 *
 * [stateLock] guards [slots]/[leaseOnly]/[inFlight]/[usedBytes]/[accessCounter]/[discovered] bookkeeping,
 * eviction and namespace-directory pruning; it is NEVER held across [extract] (native extraction, Bitmap
 * decode, or a long filesystem copy all happen OUTSIDE [stateLock]).
 *
 * Same-key materialization is serialized by a FIXED array of [LOCK_STRIPE_COUNT] stripe locks ([lockStripes]),
 * selected deterministically from the key's hash with [Math.floorMod] (never a per-key lock map, which grew
 * without bound under ephemeral-namespace churn before Phase 3E-E R1A). The same key always maps to the same
 * stripe, so a second concurrent [acquire] of that key observes the first caller's published result rather
 * than racing it into a redundant extraction or a partially overwritten final. Two different keys that collide
 * on a stripe merely serialize their materializations; a cache hit takes only [stateLock] and never waits on a
 * stripe. A stripe is held only for one key's miss path (re-check, temp extraction, rename, publish), never for
 * Bitmap decode.
 *
 * Directory creation versus pruning: the materializing path increments [inFlight] for its namespace AND
 * creates the namespace directory inside the same [stateLock] section, and only decrements [inFlight] (on
 * publish, or in the failure path before attempting a prune) under [stateLock] again. A prune decision is made
 * under [stateLock] and refuses any namespace with an [inFlight] counter, so "pruner observes empty ->
 * materializer creates temp in it -> pruner deletes it" cannot interleave.
 *
 * Order: stripe lock -> [stateLock], never the reverse; no path takes two stripes. Relative to
 * [NativeRarSession]'s own per-session monitor: this coordinator always acquires a stripe (and, briefly,
 * [stateLock]) BEFORE ever calling into [extract] (which may, transitively, acquire a [NativeRarSession]
 * instance's own lock) -- never the reverse, and [stateLock] is never held while [extract] runs.
 * [NativeRarSession] never calls back into this coordinator. That one-directional dependency (coordinator lock
 * -> session lock, never session lock -> coordinator lock) makes a deadlock between the two structurally
 * impossible, not merely untested.
 */
internal class RarCacheCoordinator private constructor(
    private val root: File,
    private val maxBytes: Long,
    private val maxEntries: Int,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    /** Cache entry identity: source namespace plus the RAR entry's own physical ordinal -- see the class doc's
     * "Namespace model" section for why namespace is part of identity but no longer a separate accounting
     * domain. */
    private data class Key(val namespace: String, val physicalIndex: Int)

    private class Slot(val file: File, val bytes: Long, var lastAccess: Long, var activeReaders: Int)

    private val stateLock = Any()
    private val slots = HashMap<Key, Slot>()

    /** Lease-only holders for keys whose final vanished: outstanding lease count, zero accounted bytes, never an
     * entry. Guarded by [stateLock]; see the class doc's "Missing finals" section. */
    private val leaseOnly = HashMap<Key, Int>()

    /** Namespace -> number of in-flight materializations; an entry is removed when it reaches zero. Guarded by
     * [stateLock]; see the class doc's "Empty namespace directories" section. */
    private val inFlight = HashMap<String, Int>()
    private var accessCounter = 0L
    private var usedBytes = 0L
    private val lockStripes = Array(LOCK_STRIPE_COUNT) { Any() }
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
        // Fast path: a hit needs only stateLock, never a stripe, so a slow materialization of a colliding key
        // can never delay an already-published page.
        synchronized(stateLock) { leaseExistingLocked(key)?.let { return it } }

        synchronized(lockStripes[stripeIndex(key)]) {
            val namespaceDir: File
            synchronized(stateLock) {
                // Re-check under the stripe: another caller may have published this key meanwhile.
                leaseExistingLocked(key)?.let { return it }
                // Register in-flight work and create the directory in ONE stateLock section, so a concurrent
                // prune (which also runs under stateLock and refuses in-flight namespaces) can never delete the
                // directory between its creation and this caller's temp file.
                // (mkdirs first: if it ever threw, no in-flight count would be left behind.)
                namespaceDir = File(root, namespace).apply { mkdirs() }
                inFlight[namespace] = (inFlight[namespace] ?: 0) + 1
            }

            var published = false
            try {
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
                // Defensive: a stale final file (e.g. from a corrupted prior cache generation) must never be
                // trusted merely because a rename target with this name exists. No slot references this path
                // (a vanished slot was already reconciled away above), so this never deletes an active final.
                finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.delete()
                    throw RarExtractionException(NativeRarError.IO)
                }

                synchronized(stateLock) {
                    // Leases inherited from a vanished final of this key are folded in exactly once.
                    var readers = (leaseOnly.remove(key) ?: 0) + 1
                    // Defensive only: under this stripe nothing else can publish this key, and a vanished slot was
                    // reconciled before extraction, so a previous slot is not expected. Never double-count it.
                    slots.remove(key)?.let { previous ->
                        usedBytes -= previous.bytes
                        readers += previous.activeReaders
                    }
                    slots[key] = Slot(finalFile, bytes, ++accessCounter, readers)
                    usedBytes += bytes
                    endInFlightLocked(namespace)
                    published = true
                    evictLocked()
                }
                return CachedExtraction(finalFile) { release(key) }
            } finally {
                if (!published) {
                    synchronized(stateLock) {
                        endInFlightLocked(namespace)
                        pruneNamespaceIfIdleLocked(namespace)
                    }
                }
            }
        }
    }

    /**
     * Must be called with [stateLock] held. Leases and returns [key]'s published final when it is still a file.
     * When the slot exists but its final vanished (Android or the user clearing the app cache while ShelfOS
     * runs), reconciles immediately -- the slot stops counting toward [usedBytes] and the entry count, and its
     * outstanding leases move to [leaseOnly] -- and returns null so the caller re-materializes. See the class
     * doc's "Missing finals" section.
     */
    private fun leaseExistingLocked(key: Key): CachedExtraction? {
        val slot = slots[key] ?: return null
        if (slot.file.isFile) {
            slot.activeReaders++
            slot.lastAccess = ++accessCounter
            return CachedExtraction(slot.file) { release(key) }
        }
        slots.remove(key)
        usedBytes -= slot.bytes
        if (slot.activeReaders > 0) leaseOnly[key] = (leaseOnly[key] ?: 0) + slot.activeReaders
        return null
    }

    /** Must be called with [stateLock] held. */
    private fun endInFlightLocked(namespace: String) {
        val count = inFlight[namespace] ?: return
        if (count <= 1) inFlight.remove(namespace) else inFlight[namespace] = count - 1
    }

    private fun release(key: Key) {
        synchronized(stateLock) {
            val slot = slots[key]
            if (slot != null) {
                if (slot.activeReaders <= 0) return
                slot.activeReaders--
                if (slot.activeReaders == 0) evictLocked()
                return
            }
            // A lease on a final that vanished and has not (yet) been re-materialized.
            val held = leaseOnly[key] ?: return
            if (held <= 1) {
                leaseOnly.remove(key)
                pruneNamespaceIfIdleLocked(key.namespace)
            } else {
                leaseOnly[key] = held - 1
            }
        }
    }

    private fun stripeIndex(key: Key): Int = Math.floorMod(key.hashCode(), lockStripes.size)

    /**
     * Must be called with [stateLock] held. Removes `root/<namespace>` when the namespace has no slot, no
     * lease-only holder and no in-flight materialization -- see the class doc's "Empty namespace directories"
     * section. Non-recursive: a non-empty directory is left alone. Only a directory whose canonical path is
     * exactly `<canonical root>/<namespace>` is touched, so a symlink or an unexpected path is never deleted.
     */
    private fun pruneNamespaceIfIdleLocked(namespace: String) {
        if (inFlight.containsKey(namespace)) return
        if (slots.keys.any { it.namespace == namespace }) return
        if (leaseOnly.keys.any { it.namespace == namespace }) return
        val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return
        val dir = File(root, namespace)
        if (!isOwnedNamespaceDir(dir, rootCanonical)) return
        runCatching { dir.delete() }
    }

    /** True only for an existing, real (non-symlink) directory directly under the canonical [root]. */
    private fun isOwnedNamespaceDir(dir: File, rootCanonical: File): Boolean {
        if (!dir.isDirectory) return false
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        return canonical.parentFile == rootCanonical && canonical == File(rootCanonical, dir.name)
    }

    /** Must be called with [stateLock] held. Spans every namespace's entries -- see the class doc's "Eviction"
     * section. */
    private fun evictLocked() {
        while (usedBytes > maxBytes || slots.size > maxEntries) {
            val candidates = slots.entries
                .filter { it.value.activeReaders == 0 }
                .sortedBy { it.value.lastAccess }
            var evicted = false
            for (victim in candidates) {
                // A final that already vanished from disk holds no bytes, so its accounting is reclaimed rather
                // than left pinned forever as a "failed deletion".
                if (!deleteFile(victim.value.file) && victim.value.file.exists()) continue
                usedBytes -= victim.value.bytes
                slots.remove(victim.key)
                pruneNamespaceIfIdleLocked(victim.key.namespace)
                evicted = true
                break
            }
            // Every remaining entry is active or every eligible deletion failed. Keep failed deletions accounted
            // and stop deterministically rather than retrying the same undeletable file forever.
            if (!evicted) break
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
            var foundFinal = false
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
                foundFinal = true
            }
            // Phase 3E-E R1A: an empty stale namespace directory (e.g. an old ephemeral namespace) is pruned here.
            // Discovery runs before any materialization can register as in-flight, and the delete is
            // non-recursive, so anything still inside (an unexpected subdirectory) keeps the directory.
            if (!foundFinal && isOwnedNamespaceDir(entry, rootCanonical)) runCatching { entry.delete() }
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
    /** Outstanding leases on the key, whether on its published slot or on a lease-only holder for a vanished
     * final. */
    internal fun activeReadersForTest(namespace: String, key: Int): Int = synchronized(stateLock) {
        val k = Key(namespace, key)
        slots[k]?.activeReaders ?: leaseOnly[k] ?: 0
    }
    internal fun leaseOnlyCountForTest(): Int = synchronized(stateLock) { leaseOnly.size }
    internal fun inFlightNamespaceCountForTest(): Int = synchronized(stateLock) { inFlight.size }
    internal fun liveNamespacesForTest(): Set<String> = synchronized(stateLock) { slots.keys.mapTo(HashSet()) { it.namespace } }
    internal val lockStripeCountForTest: Int get() = lockStripes.size
    internal fun stripeIndexForTest(namespace: String, key: Int): Int = stripeIndex(Key(namespace, key))

    companion object {
        /** Fixed number of same-key materialization stripe locks; see the class doc's "Locking" section. */
        internal const val LOCK_STRIPE_COUNT: Int = 64

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
            deleteFile: (File) -> Boolean = { it.delete() },
        ): RarCacheCoordinator {
            root.mkdirs()
            return RarCacheCoordinator(root, maxBytes, maxEntries, deleteFile)
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
