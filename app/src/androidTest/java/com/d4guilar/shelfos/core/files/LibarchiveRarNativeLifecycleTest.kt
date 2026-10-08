// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3E-B FD ownership, native handle, and lifecycle/failure-path
 * coverage for [NativeRarSession]: invalid/closed source fd, lifecycle
 * (normal close / double close / use-after-close / failed-open cleanup),
 * and destination-write failure. See [LibarchiveRarNativeTest] for format/
 * extraction/encryption coverage.
 */
class LibarchiveRarNativeLifecycleTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tempFiles = mutableListOf<File>()

    private fun tempFile(name: String): File {
        val file = File(context.cacheDir, name)
        tempFiles.add(file)
        return file
    }

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    /** A crude but effective open-fd-count signal: this process's own `/proc/self/fd` entries. */
    private fun currentOpenFdCount(): Int = File("/proc/self/fd").list()?.size ?: -1

    private fun openPlainRar4(): NativeRarSession {
        val archive = RarFixtures.decodeFixtureToTempFile(
            "test_read_format_rar.rar.uu",
            "lifecycle-rar4-${System.nanoTime()}.rar",
        )
        tempFiles.add(archive)
        val fd = RarFixtures.detachedReadFd(archive)
        val result = NativeRarSession.open(fd)
        assertTrue("expected open() to succeed, got: $result", result is NativeRarResult.Success)
        return (result as NativeRarResult.Success).value
    }

    @Test
    fun invalidFdFailsDeterministicallyWithoutLeakingOrCrashing() {
        val before = currentOpenFdCount()

        // A fd number essentially guaranteed not to be open in this process.
        val result = NativeRarSession.open(999_999)
        assertTrue(
            "expected a deterministic failure, got: $result",
            result is NativeRarResult.Failure,
        )
        assertEquals(NativeRarError.IO, (result as NativeRarResult.Failure).error)

        val after = currentOpenFdCount()
        if (before >= 0 && after >= 0) {
            assertEquals("open-fd count must not grow from a failed open() attempt", before, after)
        }
    }

    @Test
    fun closedSourceFdFailsDeterministicallyWithoutLeakingOrCrashing() {
        val archive = RarFixtures.decodeFixtureToTempFile(
            "test_read_format_rar.rar.uu",
            "closed-fd-source.rar",
        )
        tempFiles.add(archive)
        val pfd = ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY)
        val fd = pfd.detachFd()
        // Close the fd ourselves out from under the contract (simulating a
        // caller bug / an already-invalid descriptor), via adoptFd() - the
        // documented way to close a raw fd number through
        // ParcelFileDescriptor without reflection.
        ParcelFileDescriptor.adoptFd(fd).close()

        // Native must still fail deterministically on this now-invalid fd
        // number, never crash, and must not attempt to double-close it.
        val result = NativeRarSession.open(fd)
        assertTrue(
            "expected a deterministic failure, got: $result",
            result is NativeRarResult.Failure,
        )
        assertEquals(NativeRarError.IO, (result as NativeRarResult.Failure).error)
    }

    @Test
    fun normalCloseSucceeds() {
        val session = openPlainRar4()
        session.close()
        // No crash, no exception - success is simply "it returned".
    }

    @Test
    fun doubleCloseIsHarmless() {
        val session = openPlainRar4()
        session.close()
        session.close() // Must be a no-op, not a crash/UB (native handle already freed).
    }

    @Test
    fun useAfterCloseIsDeterministicNeverCrashes() {
        val session = openPlainRar4()
        session.close()

        assertEquals(0, session.entryCount)
        assertNull(session.entryAt(0))

        val outFile = tempFile("use-after-close-out.bin")
        val pfd = ParcelFileDescriptor.open(
            outFile,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
        )
        val error = session.extractEntry(0, pfd.fd)
        pfd.close()
        assertEquals(NativeRarError.INVALID_ARGUMENT, error)

        session.close() // Still idempotent after use-after-close attempts.
    }

    @Test
    fun openClosesTransferredFdWhenNativeBackendIsUnavailable() {
        val archive = RarFixtures.decodeFixtureToTempFile(
            "test_read_format_rar.rar.uu",
            "native-unavailable-${System.nanoTime()}.rar",
        )
        tempFiles.add(archive)
        val fd = RarFixtures.detachedReadFd(archive)

        val before = currentOpenFdCount()

        // Internal test-only overload (see NativeRarSession.open's doc
        // comment): forces the native-backend-unavailable path
        // deterministically, without any global mutable flag.
        val result = NativeRarSession.open(fd) { false }
        assertTrue(
            "expected NATIVE_INTERNAL when the native backend is unavailable, got: $result",
            result is NativeRarResult.Failure,
        )
        assertEquals(NativeRarError.NATIVE_INTERNAL, (result as NativeRarResult.Failure).error)

        val after = currentOpenFdCount()
        if (before >= 0 && after >= 0) {
            assertEquals(
                "open() must close the transferred fd exactly once when the " +
                    "native backend is unavailable, never leak it",
                before,
                after,
            )
        }
    }

    @Test
    fun failedOpenDueToWrongFormatDoesNotLeakTheSourceFd() {
        val before = currentOpenFdCount()

        val file = tempFile("wrong-format-for-leak-check.bin")
        file.writeBytes(ByteArray(64) { it.toByte() })
        val fd = RarFixtures.detachedReadFd(file)

        val result = NativeRarSession.open(fd)
        assertTrue(result is NativeRarResult.Failure)
        assertEquals(NativeRarError.UNSUPPORTED, (result as NativeRarResult.Failure).error)

        val after = currentOpenFdCount()
        if (before >= 0 && after >= 0) {
            assertEquals(
                "a failed open() must close the fd it took ownership of",
                before,
                after,
            )
        }
    }

    @Test
    fun destinationWriteFailureIsIoAndSessionRemainsClosable() {
        val session = openPlainRar4()
        try {
            val outFile = tempFile("destination-failure-out.bin")
            val pfd = ParcelFileDescriptor.open(
                outFile,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
            )
            val destFd = pfd.fd
            pfd.close() // Destination fd is now closed/invalid before extraction even starts.

            val error = session.extractEntry(0, destFd)
            assertEquals(NativeRarError.IO, error)

            // The session itself must remain usable/closable - a destination
            // failure must never corrupt or leak the session's own source fd.
            assertEquals(5, session.entryCount)
            assertNotNull(session.entryAt(0))
        } finally {
            session.close()
        }
    }
}
