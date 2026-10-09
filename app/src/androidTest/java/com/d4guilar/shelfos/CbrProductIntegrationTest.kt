// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.files.RarFixtures
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.library.PublicationProblem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3E-D: product-integration proof that `PublicationFormat.CBR` is genuinely wired end to end through the
 * REAL accepted Phase 3E-B native engine -- never a fake -- for import detection, metadata/zero-page rejection,
 * and [FixedReaderFactory] routing. Deliberately reuses the already-vendored upstream `.uu` RAR fixtures
 * [RarFixtures] decodes (`test_read_format_rar.rar.uu`, the RAR4/RAR5 encrypted fixtures) rather than
 * manufacturing a new one: per `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3E-D record, no RAR-writing tooling is
 * added to this project, and none of these upstream fixtures contain an image-named entry -- which this
 * checkpoint's own "zero safe supported image pages must fail truthfully" requirement actually needs anyway
 * (the real comic-image reading/render/progress/spread path is proven structurally -- [RarPages] in
 * `core.reader.FixedReader` reuses the exact same [com.d4guilar.shelfos.core.reader.ImagePageRenderer]/
 * [com.d4guilar.shelfos.core.reader.PageSource] contract CBZ's `ArchivePages` already does, both fully covered by
 * existing CBZ instrumented tests -- plus the owner's separate local real-CBR manual acceptance pass; see
 * `docs/VALIDATION.md`'s 3E-D record).
 */
class CbrProductIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var isolated: File
    private val tempFiles = mutableListOf<File>()

    @After fun cleanup() {
        if (::isolated.isInitialized) isolated.deleteRecursively()
        tempFiles.forEach { it.delete() }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    @Test fun realRarMagicIsDetectedAsCbrAndAZeroImagePageArchiveFailsTruthfullyLikeAnEquivalentEmptyCbz() = runBlocking {
        isolated = File(context.cacheDir, "cbr-import-test").apply { deleteRecursively(); mkdirs() }
        val files = PublicationFiles(context, isolated)
        val archive = RarFixtures.decodeFixtureToTempFile("test_read_format_rar.rar.uu", "zero-image-${System.nanoTime()}.cbr")
        tempFiles.add(archive)
        val beforeHash = sha256(archive)

        val error = try {
            files.prepare(Uri.fromFile(archive).toString(), copy = true) {}
            null
        } catch (e: PublicationException) { e }

        // Real RAR magic bytes classify as CBR (not UNSUPPORTED_FORMAT) regardless of the ".cbr" extension being
        // the only filename evidence -- the container itself has zero page-image-named entries, so it must fail
        // the SAME way an equivalent empty CBZ already does: EMPTY_ARCHIVE, never a silently "imported" zero-page
        // comic, and never collapsed into a generic UNREADABLE/CORRUPT.
        assertEquals(PublicationProblem.EMPTY_ARCHIVE, error?.problem)
        assertTrue("no partial private copy must remain after a rejected import", isolated.list().isNullOrEmpty())
        assertArrayEquals("the source archive must never be modified by a rejected import", beforeHash, sha256(archive))
    }

    @Test fun fixedReaderFactoryRoutesCbrThroughTheRealNativeEngineNeverThroughTheZipOrPdfPaths() {
        isolated = File(context.cacheDir, "cbr-reader-test").apply { deleteRecursively(); mkdirs() }
        val files = PublicationFiles(context, isolated)
        val archive = RarFixtures.decodeFixtureToTempFile("test_read_format_rar.rar.uu", "reader-route-${System.nanoTime()}.cbr")
        tempFiles.add(archive)
        val managed = File(isolated, archive.name).also { archive.copyTo(it) }
        val item = LibraryItem(
            id = "cbr-route-test", title = "CBR route test", category = MediaCategory.COMIC,
            sourceUri = "test:cbr-route", format = PublicationFormat.CBR, fileName = managed.name,
            byteSize = managed.length(), managedPath = managed.name,
        )

        // A real RAR archive opened through FixedReaderFactory's CBR branch must reach RarPageSource's own
        // EMPTY_ARCHIVE check (proving the native RAR engine actually parsed it) -- CBZ's ZIP path would instead
        // throw a ZipException-derived CORRUPT on these same non-ZIP bytes, and the PDF path never even matches
        // (no "%PDF-" header), so this specific problem is only reachable via genuine CBR routing.
        val error = try { FixedReaderFactory(files).open(item); null } catch (e: PublicationException) { e }
        assertEquals(PublicationProblem.EMPTY_ARCHIVE, error?.problem)
    }

    @Test fun passwordProtectedRar4AndRar5BothMapToProtectedThroughTheRealImportPath() = runBlocking {
        isolated = File(context.cacheDir, "cbr-protected-test").apply { deleteRecursively(); mkdirs() }
        val files = PublicationFiles(context, isolated)
        listOf("test_read_format_rar4_encrypted.rar.uu", "test_read_format_rar5_encrypted.rar.uu").forEach { asset ->
            val archive = RarFixtures.decodeFixtureToTempFile(asset, "protected-${System.nanoTime()}.cbr")
            tempFiles.add(archive)
            val error = try {
                files.prepare(Uri.fromFile(archive).toString(), copy = true) {}
                null
            } catch (e: PublicationException) { e }
            // A password-protected CBR must map to the existing, truthful PROTECTED problem -- never CORRUPT, never
            // a password prompt, never a crash -- exactly like an EPUB with DRM evidence already does.
            assertEquals(asset, PublicationProblem.PROTECTED, error?.problem)
        }
        assertTrue(isolated.list().isNullOrEmpty())
    }
}
