// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.NativeRarError
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.files.RarCacheCoordinator
import com.d4guilar.shelfos.core.files.RarExtractionCache
import com.d4guilar.shelfos.core.files.RarFixtures
import com.d4guilar.shelfos.core.files.RarOpenException
import com.d4guilar.shelfos.core.files.openRarArchiveSession
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.library.PublicationProblem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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

    /** Phase 3E-E: writes [bytes] to a temp source file tracked for cleanup. */
    private fun sourceFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, "$name-${System.nanoTime()}.cbr").also { it.writeBytes(bytes); tempFiles.add(it) }

    @Test fun malformedValidMagicCbrFailsTruthfullyAsCorruptThroughTheRealImportAndReaderRoutes() = runBlocking {
        // Phase 3E-E: archives that pass RAR magic detection but are structurally broken must reach the REAL native
        // engine and come back as CORRUPT with the typed native cause retained -- never UNSUPPORTED, never a crash,
        // never a partial private copy, never a modified source.
        isolated = File(context.cacheDir, "cbr-malformed-test").apply { deleteRecursively(); mkdirs() }
        val files = PublicationFiles(context, isolated)
        val solid = RarFixtures.decodeFixtureToTempFile("test_read_format_rar5_solid.rar.uu", "solid-${System.nanoTime()}.rar")
        tempFiles.add(solid)
        val corrupt = mapOf(
            // 100 bytes is the accepted 3E-B cut point that lands inside header/data, not on an entry boundary.
            "truncated-rar5-solid" to solid.readBytes().copyOf(100),
            "rar4-signature-only" to byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00),
            "rar5-signature-only" to byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00),
        )
        for ((label, bytes) in corrupt) {
            val source = sourceFile(label, bytes)
            val before = sha256(source)
            val error = try { files.prepare(Uri.fromFile(source).toString(), copy = true) {}; null } catch (e: PublicationException) { e }
            assertEquals(label, PublicationProblem.CORRUPT, error?.problem)
            assertEquals(label, NativeRarError.CORRUPT, (error?.cause as? RarOpenException)?.error)
            assertTrue("$label: no partial private copy", isolated.list().isNullOrEmpty())
            assertArrayEquals("$label: source must be unchanged", before, sha256(source))

            val managed = File(isolated, source.name).also { source.copyTo(it) }
            val item = LibraryItem(id = "malformed-$label", title = label, category = MediaCategory.COMIC,
                sourceUri = "test:$label", format = PublicationFormat.CBR, fileName = managed.name,
                byteSize = managed.length(), managedPath = managed.name)
            val readerError = try { FixedReaderFactory(files).open(item).close(); null } catch (e: PublicationException) { e }
            assertEquals("$label via reader route", PublicationProblem.CORRUPT, readerError?.problem)
            managed.delete()
        }

        // Prefixes that are NOT complete RAR magic, and unrelated bytes, never reach the RAR path at all.
        val unsupported = mapOf(
            "six-byte-marker" to byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07),
            "random-bytes" to ByteArray(512) { (it * 37 + 11).toByte() },
        )
        for ((label, bytes) in unsupported) {
            val source = sourceFile(label, bytes)
            val error = try { files.prepare(Uri.fromFile(source).toString(), copy = true) {}; null } catch (e: PublicationException) { e }
            assertEquals(label, PublicationProblem.UNSUPPORTED_FORMAT, error?.problem)
            assertTrue("$label: no partial private copy", isolated.list().isNullOrEmpty())
        }
    }

    @Test fun nonSeekableSourceMapsToNeedsCopyAtTheProductBoundaryAndTheCallerKeepsItsDescriptor() {
        // Phase 3E-E: the real native NOT_SEEKABLE result is surfaced as the existing actionable NEEDS_COPY problem
        // (ShelfOS offers a private copy; no automatic copy subsystem exists or is built here).
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val error = try { openRarArchiveSession(pipe[0]).close(); null } catch (e: PublicationException) { e }
            assertEquals(PublicationProblem.NEEDS_COPY, error?.problem)
            assertEquals(NativeRarError.NOT_SEEKABLE, (error?.cause as? RarOpenException)?.error)
            assertTrue("the caller's descriptor must remain valid", pipe[0].fileDescriptor.valid())
        } finally {
            pipe.forEach { it.close() }
        }
    }

    @Test fun repeatedOpenExtractCloseCyclesDoNotGrowTheProcessFdCount() {
        // Phase 3E-E FD-leak sanity: a bounded number of real native open -> materialize -> cached re-read -> close
        // cycles, plus the product reader route's failure path, must not show monotonic descriptor growth.
        isolated = File(context.cacheDir, "cbr-fd-cycle-test").apply { deleteRecursively(); mkdirs() }
        val files = PublicationFiles(context, isolated)
        val archive = RarFixtures.decodeFixtureToTempFile("test_read_format_rar.rar.uu", "fd-cycle-${System.nanoTime()}.rar")
        tempFiles.add(archive)
        val managed = File(isolated, "fd-cycle.cbr").also { archive.copyTo(it) }
        val emptyItem = LibraryItem(id = "fd-cycle", title = "fd", category = MediaCategory.COMIC, sourceUri = "test:fd",
            format = PublicationFormat.CBR, fileName = managed.name, byteSize = managed.length(), managedPath = managed.name)
        val coordinator = RarCacheCoordinator.createForTest(File(isolated, "cbr"), maxEntries = 4)

        fun cycle() {
            ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val session = openRarArchiveSession(pfd)
                try {
                    val namespace = UUID.randomUUID().toString() // ephemeral, like an external source
                    coordinator.acquire(namespace, 0) { session.extractEntry(0, it, RarExtractionCache.MAX_ENTRY_BYTES) }
                        .open().use { assertTrue(it.readBytes().isNotEmpty()) }
                    coordinator.acquire(namespace, 0) { error("cache hit expected") }.open().use { it.readBytes() }
                } finally {
                    session.close()
                }
            }
            try { FixedReaderFactory(files).open(emptyItem).close() } catch (_: PublicationException) { }
        }

        fun fdCount() = File("/proc/self/fd").list()?.size ?: -1
        repeat(3) { cycle() } // warm-up: lazy class/library/coordinator initialization
        val before = fdCount()
        repeat(40) { cycle() }
        val after = fdCount()
        android.util.Log.i("CbrProductIntegrationTest", "fd count before=$before after=$after (40 cycles)")
        assertTrue("fd count must be measurable", before > 0 && after > 0)
        assertTrue("fd count grew from $before to $after across 40 CBR cycles", after - before <= 2)
    }
}
