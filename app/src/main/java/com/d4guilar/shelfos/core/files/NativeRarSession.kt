// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.os.ParcelFileDescriptor

/**
 * Phase 3E-B: a generic internal native RAR4/RAR5 archive engine, built on
 * the Phase 3E-A `shelfos_cbr` JNI foundation (see [LibarchiveNative] and
 * `docs/adr/0024-native-cbr-libarchive.md`).
 *
 * This is a generic archive engine. It knows nothing about
 * [com.d4guilar.shelfos.domain.library] types, `PublicationFormat`, the
 * reader, Compose, or ComicInfo.xml - those belong to later Phase 3E
 * slices. It does NOT implement a page cache, image/page filtering, or any
 * product policy; see `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3E-B record.
 *
 * ## Session ownership and handle model
 *
 * Each [NativeRarSession] wraps exactly one opaque native handle (a
 * `reinterpret_cast` of a heap-allocated native `Session*`, never a value
 * meaningful outside this class). There is no process-global "current
 * archive": multiple independent sessions are supported, even though
 * ShelfOS today only ever uses one at a time. The handle is never logged,
 * persisted, or exposed outside this class.
 *
 * [close] is idempotent and safe to call more than once: the handle is
 * cleared to `0L` (and the native `close` call made) under [lock] the
 * first time, and every later call - including a concurrent one - observes
 * `closed == true` and returns immediately without touching native code
 * again. Every other operation checks [closed] first and fails
 * deterministically (see each method's return contract) rather than
 * calling into native code with a handle that may already be freed.
 *
 * ## Thread-safety contract
 *
 * One operation at a time per session: every public operation synchronizes
 * on this instance's own [lock]. This is a per-session lock, not a global
 * cross-archive lock - a second [NativeRarSession] instance is never
 * blocked by this one. The native engine itself performs no internal
 * synchronization; it relies entirely on this contract never being
 * violated (see `shelfos_rar_session_jni.cpp`'s header comment).
 *
 * ## Source FD ownership
 *
 * [open] takes ownership of [fd] the instant it is called, on every path -
 * success AND every failure path closes or otherwise disposes of it
 * exactly once. The caller MUST have already relinquished Java-level
 * ownership before calling (e.g. via
 * `android.os.ParcelFileDescriptor.detachFd()`) and must never close [fd]
 * itself afterward. On success, [close] closes the fd exactly once; on
 * failure, [open] itself closes it before returning. This is the
 * "preferred direction" ownership model: a single owned descriptor, never
 * duplicated, so there is no `dup()`-shared-offset subtlety to reason
 * about.
 *
 * ## Seek/restart (no cache)
 *
 * RAR - especially solid RAR - cannot be treated as randomly seekable per
 * entry. This checkpoint implements NO page cache. [open]'s metadata pass
 * and every later [extractEntry] call independently restart a fresh
 * archive reader from the beginning of the owned fd and sequentially
 * skip/decode to the target entry. If the fd is not seekable at all (e.g.
 * a pipe), [open] fails with [NativeRarError.NOT_SEEKABLE] - this engine
 * never falls back to a managed/forced copy; that policy mapping (e.g. to
 * ShelfOS's existing NEEDS_COPY semantics) is explicitly deferred to a
 * later slice.
 *
 * ## Destination FD (extraction)
 *
 * [extractEntry]'s `destinationFd` is BORROWED, never owned: this class
 * and the native engine never close it, on any path. The caller retains
 * ownership and is responsible for closing it once done.
 */
class NativeRarSession private constructor(initialHandle: Long) {

    private val lock = Any()
    private var handle: Long = initialHandle
    private var closed: Boolean = false

    /**
     * Number of physical entries in the archive, or 0 if this session is
     * already [closed]. Never negative.
     */
    val entryCount: Int
        get() = synchronized(lock) {
            if (closed) return 0
            nativeEntryCount(handle).coerceAtLeast(0)
        }

    /**
     * Returns metadata for the physical entry at [index], or null if this
     * session is closed or [index] is out of range. Never touches archive
     * data (no decode, no extraction).
     */
    fun entryAt(index: Int): NativeRarEntry? = synchronized(lock) {
        if (closed) return null
        val type = nativeEntryType(handle, index)
        if (type < 0) return null

        val nameBytes = nativeEntryName(handle, index)
        val nameIsUtf8 = nativeEntryIsNameUtf8(handle, index)
        // Entry name encoding: libarchive's archive_entry_pathname_utf8()
        // (used natively whenever it returns non-null) already performs a
        // correct charset conversion to UTF-8; JNI's own
        // NewStringUTF/Modified-UTF-8 pitfall is avoided entirely by
        // returning raw bytes across the JNI boundary and decoding here.
        // When pathname_utf8() was unavailable natively (nameIsUtf8 ==
        // false), the bytes are the raw, locale-dependent
        // archive_entry_pathname() bytes - decoding them as ISO-8859-1
        // (every byte value is a valid code point; this call can never
        // throw) is a deliberate, documented best-effort fallback, not a
        // claim of correctness for arbitrary non-ASCII legacy filenames.
        // Broader filename normalization is out of this checkpoint's scope.
        val name = if (nameIsUtf8) {
            String(nameBytes, Charsets.UTF_8)
        } else {
            String(nameBytes, Charsets.ISO_8859_1)
        }

        val size = nativeEntrySize(handle, index)
        NativeRarEntry(
            physicalIndex = index,
            name = name,
            nameEncodingConfirmedUtf8 = nameIsUtf8,
            type = when (type) {
                0 -> NativeRarEntryType.REGULAR_FILE
                1 -> NativeRarEntryType.DIRECTORY
                else -> NativeRarEntryType.OTHER
            },
            // -1 is the engine-wide "unknown size" sentinel (see
            // shelfos_rar_session_jni.cpp): a real entry size is never
            // negative, so this is unambiguous.
            size = if (size >= 0) size else null,
        )
    }

    /**
     * Extracts physical entry [index]'s data into [destinationFd] (a
     * BORROWED fd - never closed by this call). Returns null on success,
     * or the [NativeRarError] category on failure. Only
     * [NativeRarEntryType.REGULAR_FILE] entries can be extracted; any
     * other entry (directory/other-special) or an out-of-range index
     * fails with [NativeRarError.INVALID_ARGUMENT].
     */
    fun extractEntry(index: Int, destinationFd: Int): NativeRarError? = synchronized(lock) {
        if (closed) return NativeRarError.INVALID_ARGUMENT
        val code = nativeExtractEntry(handle, index, destinationFd)
        return if (code == 0) null else NativeRarError.fromCode(code)
    }

    /**
     * Closes this session, releasing the native handle and the owned
     * source fd exactly once. Idempotent: safe to call multiple times
     * (including concurrently) - only the first call has any effect.
     */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            nativeClose(handle)
            handle = 0L
        }
    }

    companion object {

        /**
         * Opens a RAR/RAR5 session over [fd] (see the class doc for the FD
         * ownership contract). Performs a full sequential metadata pass
         * immediately: on return, [NativeRarSession.entryCount]/[entryAt]
         * reflect every entry without further native calls needing to
         * re-scan. Returns [NativeRarResult.Failure] with
         * [NativeRarError.PROTECTED] if ANY entry in the archive is
         * encrypted (no partial-success interpretation: a mix of
         * encrypted/unencrypted entries is reported as PROTECTED, not as a
         * usable session exposing only the unencrypted entries).
         */
        fun open(fd: Int): NativeRarResult<NativeRarSession> = open(fd, LibarchiveNative::isLoaded)

        /**
         * Test seam only: [isNativeLoaded] lets a test force the
         * native-backend-unavailable path deterministically, without a
         * global mutable flag, product setting, or other production-visible
         * switch. It defaults to [LibarchiveNative.isLoaded] via the public
         * [open] overload above; this overload is `internal` so only code in
         * this module (including this module's test source sets) can
         * override it.
         */
        internal fun open(
            fd: Int,
            isNativeLoaded: () -> Boolean,
        ): NativeRarResult<NativeRarSession> {
            if (!isNativeLoaded()) {
                // Ownership transition: by this point [fd] has already been
                // transferred to us under open()'s FD ownership contract
                // (see the class doc) - the caller has relinquished it and
                // will never close it itself. Since the native backend is
                // unavailable, no native Session can take custody of it, so
                // this failure path must close it itself, exactly once,
                // before returning. ParcelFileDescriptor.adoptFd(fd).close()
                // is this codebase's established, reflection-free way to
                // close a raw already-detached fd number (see
                // LibarchiveRarNativeLifecycleTest's closedSourceFdFails...
                // test for the same idiom used to invalidate a fd).
                ParcelFileDescriptor.adoptFd(fd).close()
                return NativeRarResult.Failure(NativeRarError.NATIVE_INTERNAL)
            }
            val handle = nativeOpen(fd)
            return if (handle > 0) {
                NativeRarResult.Success(NativeRarSession(handle))
            } else {
                NativeRarResult.Failure(NativeRarError.fromCode((-handle).toInt()))
            }
        }

        @JvmStatic private external fun nativeOpen(fd: Int): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeEntryCount(handle: Long): Int
        @JvmStatic private external fun nativeEntryType(handle: Long, index: Int): Int
        @JvmStatic private external fun nativeEntryName(handle: Long, index: Int): ByteArray
        @JvmStatic private external fun nativeEntryIsNameUtf8(handle: Long, index: Int): Boolean
        @JvmStatic private external fun nativeEntrySize(handle: Long, index: Int): Long
        @JvmStatic private external fun nativeExtractEntry(
            handle: Long,
            index: Int,
            destFd: Int,
        ): Int
    }
}

/** Result of a fallible [NativeRarSession] operation. */
sealed class NativeRarResult<out T> {
    data class Success<out T>(val value: T) : NativeRarResult<T>()
    data class Failure(val error: NativeRarError) : NativeRarResult<Nothing>()
}

/**
 * Explicit, product-UI-free error categories for the native RAR engine.
 * Never a raw libarchive numeric code or `archive_error_string()` text -
 * those are internal diagnostic detail only (safe for test assertions/logs,
 * never surfaced as user-facing product text). Mapping these to
 * ShelfOS's `PublicationProblem`/import UX is explicitly deferred to a
 * later Phase 3E slice.
 */
enum class NativeRarError {
    /** Caller-input problem: invalid index, wrong entry type, bad fd, etc. */
    INVALID_ARGUMENT,

    /** A low-level I/O failure (read/write/seek) unrelated to archive content. */
    IO,

    /** The source fd cannot be restarted/seeked; no managed-copy fallback exists here. */
    NOT_SEEKABLE,

    /** The content is RAR/RAR5 but structurally corrupt/truncated. */
    CORRUPT,

    /** Any entry in the archive is encrypted (header and/or data). */
    PROTECTED,

    /** The fd's content is not a RAR/RAR5 archive at all. */
    UNSUPPORTED,

    /** An unexpected internal failure (allocation failure, invariant violation, etc.). */
    NATIVE_INTERNAL,

    ;

    companion object {
        /** Maps a native error ordinal (see shelfos_rar_session_jni.cpp's ErrorCode) back. */
        fun fromCode(code: Int): NativeRarError = when (code) {
            1 -> INVALID_ARGUMENT
            2 -> IO
            3 -> NOT_SEEKABLE
            4 -> CORRUPT
            5 -> PROTECTED
            6 -> UNSUPPORTED
            else -> NATIVE_INTERNAL
        }
    }
}

/** A physical archive entry type. Never implies page/image semantics. */
enum class NativeRarEntryType { REGULAR_FILE, DIRECTORY, OTHER }

/**
 * Metadata for one physical RAR entry. This is the minimal surface a later
 * Phase 3E slice needs for product policy (page ordering, filtering, etc.)
 * - this class makes no such policy decision itself.
 */
data class NativeRarEntry(
    val physicalIndex: Int,
    val name: String,
    val nameEncodingConfirmedUtf8: Boolean,
    val type: NativeRarEntryType,
    /** Null means "unknown declared size" (distinct from a real 0-byte entry). */
    val size: Long?,
)
