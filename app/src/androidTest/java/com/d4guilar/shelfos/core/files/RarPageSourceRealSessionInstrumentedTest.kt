// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3E-C: proves [NativeRarArchiveSession]/`RarPageSource` against the REAL accepted Phase 3E-B native engine
 * (not a fake) for exactly two things this checkpoint's fake-based tests cannot prove on their own:
 * 1. source-file immutability through a full enumerate/materialize/render-access cycle, and
 * 2. that [NativeRarArchiveSession] genuinely drives [NativeRarSession] end to end (open -> entries -> extract ->
 *    close) without needing a multi-image comic fixture -- the vendored upstream `.uu` fixtures (plain
 *    text/binary entries, not images) are sufficient for this, since no image decode is exercised here. Real
 *    comic-image rendering through the same pipeline is proven separately (with a fake session carrying real PNG
 *    bytes, since no upstream fixture contains one) by `RarPageSourceRenderInstrumentedTest`.
 */
class RarPageSourceRealSessionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val tempFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    @Test
    fun sourceArchiveIsByteForByteUnchangedAfterAFullEnumerateExtractCloseCycle() {
        val archive = RarFixtures.decodeFixtureToTempFile(
            "test_read_format_rar.rar.uu",
            "immutability-rar4-${System.nanoTime()}.rar",
        )
        tempFiles.add(archive)
        val beforeHash = sha256(archive)

        val cacheRoot = File(context.cacheDir, "rar-immutability-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            // RarPageSource.open requires at least one page-image-named entry to succeed (EMPTY_ARCHIVE
            // otherwise); this fixture's entries are plain text/dir/symlink, so this test drives
            // NativeRarArchiveSession directly through the real NativeRarSession rather than through
            // RarPageSource's page-filtering policy, which is proven separately against a fake in
            // RarPageSourceTest.
            val fd = RarFixtures.detachedReadFd(archive)
            val opened = NativeRarSession.open(fd)
            assertTrue("expected open() to succeed, got: $opened", opened is NativeRarResult.Success)
            val nativeSession = (opened as NativeRarResult.Success).value
            val session: RarArchiveSession = NativeRarArchiveSession(nativeSession)
            try {
                val count = session.entryCount
                assertEquals(5, count)
                for (i in 0 until count) {
                    val entry = session.entryAt(i)
                    if (entry?.type == NativeRarEntryType.REGULAR_FILE) {
                        val dest = File(cacheRoot, "$i.bin")
                        val error = session.extractEntry(i, dest)
                        assertEquals(null, error)
                    }
                }
            } finally {
                session.close()
            }
        } finally {
            cacheRoot.deleteRecursively()
        }

        val afterHash = sha256(archive)
        assertArrayEquals(
            "the source .rar must never be modified by open/enumerate/extract/close",
            beforeHash,
            afterHash,
        )
    }
}
