// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.os.ParcelFileDescriptor
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationProblem
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
internal interface RarArchiveSession {
    val entryCount: Int

    /** Metadata for the physical entry at [index], or null if out of range -- mirrors [NativeRarSession.entryAt]. */
    fun entryAt(index: Int): NativeRarEntry?

    /**
     * Extracts physical entry [index]'s bytes into [destination] (a plain file this call creates/overwrites --
     * never a path derived from untrusted archive content). Returns `null` on success, or the failure category
     * otherwise -- mirrors [NativeRarSession.extractEntry]'s contract exactly, just File-shaped instead of
     * fd-shaped, including its [maxBytes] hard streaming ceiling (see that method's doc; defaults to no
     * ceiling so existing callers are unaffected).
     */
    fun extractEntry(index: Int, destination: File, maxBytes: Long = Long.MAX_VALUE): NativeRarError?

    /** Releases the underlying session exactly once; idempotent, mirroring [NativeRarSession.close]. */
    fun close()
}

/**
 * Production [RarArchiveSession]: owns and closes [session] exactly once, the same ownership contract
 * [NativeRarSession] itself documents -- this class never duplicates or detaches the native handle, and never
 * closes [session] more than once even if [close] is called repeatedly (delegated to [NativeRarSession.close]'s
 * own idempotency).
 */
internal class NativeRarArchiveSession(private val session: NativeRarSession) : RarArchiveSession {
    override val entryCount: Int get() = session.entryCount
    override fun entryAt(index: Int): NativeRarEntry? = session.entryAt(index)

    /** Opens [destination] itself for this one call (never reuses or shares a caller fd) -- [RarExtractionCache]
     * always calls this with a fresh, ShelfOS-generated temp file, so there is never a pre-existing fd to borrow. */
    override fun extractEntry(index: Int, destination: File, maxBytes: Long): NativeRarError? {
        val pfd = ParcelFileDescriptor.open(
            destination,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
        return try {
            session.extractEntry(index, pfd.fd, maxBytes)
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
internal class RarExtractionException(val error: NativeRarError) : IOException(error.name)

/** Typed internal cause for a whole-archive native session-open failure. */
internal class RarOpenException(val error: NativeRarError) : IOException(error.name)

/**
 * Internal-only, UI/localization-free mapping of [NativeRarError] onto ShelfOS's existing
 * [PublicationProblem] model -- never a raw native code, never a new [PublicationProblem]/
 * `PublicationExceptionDetail` value (both are UI-mapped elsewhere by exhaustive `when`s this checkpoint
 * deliberately does not touch), and never surfaced as localized text from this checkpoint.
 * [NativeRarError.NOT_SEEKABLE] maps to the existing actionable [PublicationProblem.NEEDS_COPY]. [NativeRarError.
 * IO] and [NativeRarError.NATIVE_INTERNAL] both use [PublicationProblem.UNREADABLE], while a thrown
 * [PublicationException] retains the originating typed cause (e.g. [RarExtractionException]) so callers can still
 * distinguish source/cache I/O from an internal native/program failure without string parsing or raw codes.
 * [NativeRarError.TOO_LARGE] reuses the existing [PublicationProblem.TOO_LARGE] -- the SAME problem CBZ's own
 * oversized-page-image policy ([ArchivePolicy]) already reports -- rather than collapsing into
 * [PublicationProblem.UNREADABLE] or inventing a new value.
 *
 * Moved here (Phase 3E-D) from `core.reader`'s `RarPageSource.kt`, where it originated in 3E-C: this mapping is
 * purely about [NativeRarError] (a `core.files` type), and 3E-D's own [openRarArchiveSession] below (also
 * `core.files`, used directly from `PublicationFiles`' import-time magic/metadata inspection) needs it too --
 * living next to [NativeRarError] avoids a `core.files` -> `core.reader` reverse dependency that importing it from
 * its old location would otherwise create. No behavior change from the 3E-C version.
 */
internal fun NativeRarError.toPublicationProblem(): PublicationProblem = when (this) {
    NativeRarError.PROTECTED -> PublicationProblem.PROTECTED
    NativeRarError.UNSUPPORTED -> PublicationProblem.UNSUPPORTED_FORMAT
    NativeRarError.CORRUPT, NativeRarError.INVALID_ARGUMENT -> PublicationProblem.CORRUPT
    NativeRarError.TOO_LARGE -> PublicationProblem.TOO_LARGE
    NativeRarError.NOT_SEEKABLE -> PublicationProblem.NEEDS_COPY
    NativeRarError.IO, NativeRarError.NATIVE_INTERNAL -> PublicationProblem.UNREADABLE
}

/** Preserves an archive-open failure's typed native category without changing its product-facing problem. */
internal fun NativeRarResult.Failure.toRarOpenPublicationException(): PublicationException =
    PublicationException(error.toPublicationProblem()).apply { initCause(RarOpenException(error)) }

/**
 * Phase 3E-D: opens a production [RarArchiveSession] over [descriptor] for product code (import-time
 * inspection in [PublicationFiles], and the reading-session route in `core.reader.FixedReader`'s `RarPages`).
 * Transfers ownership of a DUPLICATE of [descriptor]'s fd to the native engine -- mirroring exactly how
 * [ArchivePolicy.open] hands `SeekableZip` a duplicate for CBZ -- so [descriptor] itself is left open and still
 * owned by the caller on every path, success or failure. A whole-archive open failure (including every entry
 * being encrypted, or a non-seekable source) is mapped immediately onto [PublicationException] via
 * [toPublicationProblem], with [RarOpenException] retaining the typed [NativeRarError] as its internal cause.
 */
internal fun openRarArchiveSession(descriptor: ParcelFileDescriptor): RarArchiveSession {
    val fd = ParcelFileDescriptor.dup(descriptor.fileDescriptor).detachFd()
    return when (val opened = NativeRarSession.open(fd)) {
        is NativeRarResult.Success -> NativeRarArchiveSession(opened.value)
        is NativeRarResult.Failure -> throw opened.toRarOpenPublicationException()
    }
}

/** RAR4/RAR5 magic signatures (see `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3E-D record): a full, exact prefix
 * match is import-format evidence regardless of filename; a short/partial prefix (1-6 bytes, e.g. a truncated
 * download) is deliberately NOT treated as valid evidence here -- that is a 3E-B-level damaged-archive
 * classification concern for AFTER import, never an import-detection one. RAR5's magic shares its first six bytes
 * with RAR4's but diverges at the 7th, so checking either exact-length prefix independently can never
 * misclassify one as the other. */
private val RAR4_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
private val RAR5_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

/** True only when [head] (with [count] valid leading bytes) contains a COMPLETE RAR4 or RAR5 magic prefix. */
internal fun isRarMagic(head: ByteArray, count: Int): Boolean =
    (count >= RAR4_MAGIC.size && head.copyOf(RAR4_MAGIC.size).contentEquals(RAR4_MAGIC)) ||
        (count >= RAR5_MAGIC.size && head.copyOf(RAR5_MAGIC.size).contentEquals(RAR5_MAGIC))
