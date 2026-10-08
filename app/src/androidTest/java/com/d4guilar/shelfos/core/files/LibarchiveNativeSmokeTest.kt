// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3E-A native dependency foundation smoke test.
 *
 * Proves only that:
 *  - the ShelfOS-owned `shelfos_cbr` JNI library loads on-device,
 *  - the libarchive it statically links reports the expected version family,
 *  - a side-effect-free archive_read create/free round-trip succeeds, and
 *  - RAR4 and RAR5 format support register successfully on that object.
 *
 * This test intentionally does NOT open, read, or parse any actual RAR
 * archive. Real archive I/O is out of scope for this checkpoint; see
 * docs/adr/0024-native-cbr-libarchive.md.
 */
class LibarchiveNativeSmokeTest {

    @Test
    fun nativeLibraryLoads() {
        assertTrue(
            "shelfos_cbr failed to load: ${LibarchiveNative.loadFailure}",
            LibarchiveNative.isLoaded,
        )
    }

    @Test
    fun backendVersionReportsLibarchive389() {
        val version = LibarchiveNative.backendVersionOrNull()
        assertTrue("expected a non-null version string", version != null)
        assertTrue(
            "expected version string to mention libarchive 3.8.9, got: $version",
            version!!.contains("libarchive") && version.contains("3.8.9"),
        )
    }

    @Test
    fun rarAndRar5CapabilityRegisterCleanly() {
        val flags = LibarchiveNative.probeRarCapabilityOrNull()
        assertTrue("expected a non-null capability result", flags != null)

        val expected = LibarchiveNative.RarCapabilityFlags.ARCHIVE_READ_CREATED or
            LibarchiveNative.RarCapabilityFlags.RAR4_REGISTERED or
            LibarchiveNative.RarCapabilityFlags.RAR5_REGISTERED
        assertEquals(
            "expected archive_read create + RAR4 + RAR5 registration to all succeed",
            expected,
            flags,
        )
    }
}
