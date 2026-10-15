// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.files.RarFixtures
import com.d4guilar.shelfos.core.reader.CoverExtractor
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.domain.library.*
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Instrumented CBR cover extraction tests against real vendored RAR fixtures.
 *
 * The vendored `.uu` fixtures contain no image entries, so they exercise the native failure paths:
 * - `test_read_format_rar.rar.uu` — plain RAR4 with no images → `NO_SUITABLE_ARTWORK`
 * - RAR4/RAR5 encrypted → whole-archive open fails `PROTECTED` → `UNSUPPORTED_REPRESENTATION`
 *
 * The success path (real image in a RAR) requires an image-bearing RAR fixture that does not exist
 * in the vendored set and cannot be synthesized without a RAR creation tool; that gap is recorded
 * in the validation evidence and deferred to a manual emulator run (§M1).
 */
class LocalCoverExtractorCbrTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val files = PublicationFiles(context)
    private val extractor: CoverExtractor = LocalCoverExtractor(files, FixedReaderFactory(files))

    private val tempFiles = mutableListOf<File>()

    private fun tempFile(name: String): File {
        val file = File(context.cacheDir, name)
        tempFiles.add(file)
        return file
    }

    private fun item(managedPath: String, format: PublicationFormat = PublicationFormat.CBR): LibraryItem =
        LibraryItem(
            id = "cbr-test", title = "CBR Test", creator = "", category = MediaCategory.COMIC,
            sourceUri = "source:cbr-test", format = format, fileName = managedPath, byteSize = null,
            managedPath = managedPath, available = true,
        )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun plainRarWithoutImagesFailsWithNoSuitableArtwork() {
        val archive = RarFixtures.decodeFixtureToTempFile(
            "test_read_format_rar.rar.uu",
            "rar4-plain.rar",
        )
        tempFiles.add(archive)
        val item = item(archive.name)
        val output = tempFile("cover-out.jpg")

        var caught: CoverExtractionFailure? = null
        try {
            extractor.extract(item, output) { true }
        } catch (e: CoverExtractionFailure) {
            caught = e
        }
        assertNotNull("expected CoverExtractionFailure", caught)
        assertEquals(CoverFailureReason.NO_SUITABLE_ARTWORK, caught!!.reason)
        // Source unchanged.
        assertTrue(archive.isFile)
    }

    @Test
    fun encryptedRar4FailsWithUnsupportedRepresentation() {
        val archive = RarFixtures.decodeFixtureToTempFile(
            "test_read_format_rar4_encrypted.rar.uu",
            "rar4-encrypted.rar",
        )
        tempFiles.add(archive)
        val item = item(archive.name)
        val output = tempFile("cover-out.jpg")

        var caught: CoverExtractionFailure? = null
        try {
            extractor.extract(item, output) { true }
        } catch (e: CoverExtractionFailure) {
            caught = e
        }
        assertNotNull("expected CoverExtractionFailure", caught)
        assertEquals(CoverFailureReason.UNSUPPORTED_REPRESENTATION, caught!!.reason)
        assertTrue(archive.isFile)
    }

    @Test
    fun encryptedRar5FailsWithUnsupportedRepresentation() {
        val archive = RarFixtures.decodeFixtureToTempFile(
            "test_read_format_rar5_encrypted.rar.uu",
            "rar5-encrypted.rar",
        )
        tempFiles.add(archive)
        val item = item(archive.name)
        val output = tempFile("cover-out.jpg")

        var caught: CoverExtractionFailure? = null
        try {
            extractor.extract(item, output) { true }
        } catch (e: CoverExtractionFailure) {
            caught = e
        }
        assertNotNull("expected CoverExtractionFailure", caught)
        assertEquals(CoverFailureReason.UNSUPPORTED_REPRESENTATION, caught!!.reason)
        assertTrue(archive.isFile)
    }

    @Test
    fun noCrashOrResourceLeakOnRepeatedFailedAttempts() {
        val archive = RarFixtures.decodeFixtureToTempFile(
            "test_read_format_rar.rar.uu",
            "rar-repeat.rar",
        )
        tempFiles.add(archive)
        val item = item(archive.name)
        val before = sha256(archive)

        repeat(5) {
            val output = tempFile("cover-repeat-$it.jpg")
            try {
                extractor.extract(item, output) { true }
                fail("expected CoverExtractionFailure on iteration $it")
            } catch (_: CoverExtractionFailure) {
                // Expected
            }
        }

        // Input SHA-256 unchanged after repeated failed extractions.
        assertEquals(before, sha256(archive))
    }
}