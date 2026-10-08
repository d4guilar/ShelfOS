// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

/**
 * Phase 3E-C seam: the minimal operations `RarPageSource`/[RarExtractionCache] need from a RAR session, expressed
 * over a destination [File] rather than a raw fd so the cache/ordering/safety logic above this boundary is
 * pure-JVM unit-testable (see `FakeRarArchiveSession`, under this module's `test` source set) without ever
 * touching `android.os.ParcelFileDescriptor` or a real native archive. Production code satisfies this through
 * [NativeRarArchiveSession], a thin, behavior-preserving adapter over the accepted Phase 3E-B [NativeRarSession].
 * This interface adds NO new JNI operation and NO new native surface at all -- it only reshapes
 * [NativeRarSession.extractEntry]'s existing fd-based call into a File-based one for testability; every actual
 * extraction still ultimately goes through the same `nativeExtractEntry` 3E-B already exposes.
 *
 * Honesty note for whoever reads this later: a test built against [RarArchiveSession] (e.g. a fake) proves
 * `RarPageSource`/[RarExtractionCache]'s own ordering/caching/safety logic -- never real libarchive/RAR parsing
 * correctness. That remains proven only by the real Phase 3E-B instrumented tests (`LibarchiveRarNativeTest`/
 * `LibarchiveRarNativeLifecycleTest`) against [NativeRarSession] directly, plus this checkpoint's own
 * `RarPageSourceRealSessionInstrumentedTest`, which drives the real native session end to end.
 */
interface RarArchiveSession {
    val entryCount: Int

    /** Metadata for the physical entry at [index], or null if out of range -- mirrors [NativeRarSession.entryAt]. */
    fun entryAt(index: Int): NativeRarEntry?

    /**
     * Extracts physical entry [index]'s bytes into [destination] (a plain file this call creates/overwrites --
     * never a path derived from untrusted archive content). Returns `null` on success, or the failure category
     * otherwise -- mirrors [NativeRarSession.extractEntry]'s contract exactly, just File-shaped instead of
     * fd-shaped.
     */
    fun extractEntry(index: Int, destination: File): NativeRarError?

    /** Releases the underlying session exactly once; idempotent, mirroring [NativeRarSession.close]. */
    fun close()
}

/**
 * Production [RarArchiveSession]: owns and closes [session] exactly once, the same ownership contract
 * [NativeRarSession] itself documents -- this class never duplicates or detaches the native handle, and never
 * closes [session] more than once even if [close] is called repeatedly (delegated to [NativeRarSession.close]'s
 * own idempotency).
 */
class NativeRarArchiveSession(private val session: NativeRarSession) : RarArchiveSession {
    override val entryCount: Int get() = session.entryCount
    override fun entryAt(index: Int): NativeRarEntry? = session.entryAt(index)

    /** Opens [destination] itself for this one call (never reuses or shares a caller fd) -- [RarExtractionCache]
     * always calls this with a fresh, ShelfOS-generated temp file, so there is never a pre-existing fd to borrow. */
    override fun extractEntry(index: Int, destination: File): NativeRarError? {
        val pfd = ParcelFileDescriptor.open(
            destination,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
        return try {
            session.extractEntry(index, pfd.fd)
        } finally {
            pfd.close()
        }
    }

    override fun close() = session.close()
}

/**
 * Raised when [RarExtractionCache] materialization fails. [error] preserves the originating [NativeRarError]
 * category (never collapsed into a raw message or a native code) so callers above the cache -- today only
 * `RarPageSource` -- can map it onto ShelfOS's existing [com.d4guilar.shelfos.domain.library.PublicationProblem]
 * model. Never thrown across any UI-facing boundary directly.
 */
class RarExtractionException(val error: NativeRarError) : IOException(error.name)
