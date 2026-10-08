// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

/**
 * Thin internal wrapper around the ShelfOS-owned `shelfos_cbr` JNI library
 * (see `app/src/main/cpp`), which statically links a vendored, pinned build
 * of libarchive (see `docs/adr/0024-native-cbr-libarchive.md`).
 *
 * This is the Phase 3E-A "native dependency foundation" checkpoint only:
 * it proves the native library loads and that the linked libarchive can
 * register RAR4/RAR5 read support. It intentionally knows nothing about
 * [com.d4guilar.shelfos.domain.library] types, publication formats, reader
 * state, Compose, or page sources - those belong to later Phase 3E slices
 * that implement actual archive I/O (CBR import, extraction, page caching).
 *
 * Do not add archive-opening, entry-enumeration, or extraction entry points
 * to this object. This object's surface is deliberately limited to a
 * version query and a side-effect-free capability probe.
 */
object LibarchiveNative {

    private val loadError: Throwable? = runCatching {
        System.loadLibrary("shelfos_cbr")
    }.exceptionOrNull()

    /** True if the native `shelfos_cbr` library loaded successfully. */
    val isLoaded: Boolean get() = loadError == null

    /** The error that occurred while loading the native library, if any. */
    val loadFailure: Throwable? get() = loadError

    /**
     * Returns the linked libarchive version/details string (e.g.
     * "libarchive 3.8.9 ..."), or null if the native library failed to load.
     */
    fun backendVersionOrNull(): String? {
        if (!isLoaded) return null
        return nativeBackendVersion()
    }

    /** Bit flags returned by [probeRarCapabilityOrNull]. */
    object RarCapabilityFlags {
        const val ARCHIVE_READ_CREATED = 1
        const val RAR4_REGISTERED = 2
        const val RAR5_REGISTERED = 4
    }

    /**
     * Performs a side-effect-free capability probe: creates and frees an
     * `archive_read` object and registers the RAR4 and RAR5 format
     * handlers. Does not open, read, or touch any archive data.
     *
     * Returns a bitmask of [RarCapabilityFlags], or null if the native
     * library failed to load.
     */
    fun probeRarCapabilityOrNull(): Int? {
        if (!isLoaded) return null
        return nativeProbeRarCapability()
    }

    @JvmStatic
    private external fun nativeBackendVersion(): String

    @JvmStatic
    private external fun nativeProbeRarCapability(): Int
}
