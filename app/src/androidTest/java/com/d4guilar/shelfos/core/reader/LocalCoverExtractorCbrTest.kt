// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.NativeRarEntry
import com.d4guilar.shelfos.core.files.NativeRarEntryType
import com.d4guilar.shelfos.core.files.NativeRarError
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.files.RarArchiveSession
import com.d4guilar.shelfos.core.files.RarFixtures
import com.d4guilar.shelfos.core.files.openRarArchiveSession
import com.d4guilar.shelfos.domain.library.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Phase 4.5A.1 CBR cover extraction through the production [LocalCoverExtractor.extract] CBR branch.
 *
 * - Real native RAR (libarchive via [openRarArchiveSession]): the upstream libarchive fixtures (no images: plain RAR4,
 *   encrypted RAR4/RAR5) for the failure paths, and the ShelfOS-authored image-bearing RAR5 fixtures (non-solid and
 *   solid; see `assets/libarchive_fixtures/README.md`) for a real native success path.
 * - Injected fake sessions (the `openSession` seam) with real PNG bytes for FrontCover fallback, derived artwork,
 *   storage admission and corrupt pages.
 *
 * Decoded fixtures live in `targetContext.cacheDir` ([RarFixtures]), so [PublicationFiles] is rooted there and the
 * items are private copies resolved through the production `PublicationFiles.open`.
 */
class LocalCoverExtractorCbrTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root: File get() = context.cacheDir
    private val files by lazy { PublicationFiles(context, root) }
    private val cleanup = mutableListOf<File>()
    @After fun tearDown() { cleanup.forEach { it.delete() } }

    /** Wraps a real or fake session so the test sees which physical entries were extracted and that it was closed. */
    private class TrackingSession(private val delegate: RarArchiveSession) : RarArchiveSession {
        val extracted = mutableListOf<Int>()
        var closes = 0
        override val entryCount get() = delegate.entryCount
        override fun entryAt(index: Int) = delegate.entryAt(index)
        override fun extractEntry(index: Int, destination: File, maxBytes: Long): NativeRarError? {
            extracted += index
            return delegate.extractEntry(index, destination, maxBytes)
        }
        override fun close() { closes++; delegate.close() }
    }

    private class FakeSession(private val entries: List<Triple<String, ByteArray, Long?>>) : RarArchiveSession {
        override val entryCount get() = entries.size
        override fun entryAt(index: Int) = entries.getOrNull(index)?.let { (name, _, declared) ->
            NativeRarEntry(index, name, nameEncodingConfirmedUtf8 = true, type = NativeRarEntryType.REGULAR_FILE, size = declared)
        }
        override fun extractEntry(index: Int, destination: File, maxBytes: Long): NativeRarError? {
            destination.writeBytes(entries[index].second); return null
        }
        override fun close() = Unit
    }

    private val opened = mutableListOf<TrackingSession>()
    private var openCalls = 0
    private fun extractor(open: (ParcelFileDescriptor) -> RarArchiveSession = ::openRarArchiveSession) =
        LocalCoverExtractor(files, FixedReaderFactory(files)) { pfd -> openCalls++; TrackingSession(open(pfd)).also { opened += it } }

    private fun item(file: File) = LibraryItem("cbr-${UUID.randomUUID()}", "CBR", category = MediaCategory.COMIC, sourceUri = "test:${file.name}",
        format = PublicationFormat.CBR, fileName = file.name, byteSize = file.length(), managedPath = file.name)
    private fun fixture(asset: String) = RarFixtures.decodeFixtureToTempFile(asset, "cover-${UUID.randomUUID()}.cbr").also { cleanup += it }
    private fun output() = File(root, "cover-out-${UUID.randomUUID()}.tmp").also { cleanup += it }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun failure(block: () -> Unit) = try { block(); null } catch (e: CoverExtractionFailure) { e.reason }
    private fun openFds() = File("/proc/self/fd").list()!!.size
    private fun pixel(file: File): Int = BitmapFactory.decodeFile(file.path)!!.let { it.getPixel(it.width / 2, it.height / 2).also { _ -> it.recycle() } }
    private fun near(expected: Int, actual: Int) = listOf(Color::red, Color::green, Color::blue).all { Math.abs(it(expected) - it(actual)) < 24 }

    // ---- Real native RAR: failure paths (upstream fixtures without images) ----

    @Test fun plainRar4WithoutImagesIsNoSuitableArtworkAndClosesTheNativeSession() {
        val archive = fixture("test_read_format_rar.rar.uu")
        val before = sha(archive)
        assertEquals(CoverFailureReason.NO_SUITABLE_ARTWORK, failure { extractor().extract(item(archive), output()) { true } })
        assertEquals("the real native open ran", 1, openCalls)
        assertEquals("the native session was closed once", 1, opened.single().closes)
        assertTrue("no entry extracted: the archive has no image page", opened.single().extracted.isEmpty())
        assertEquals(before, sha(archive))
    }

    @Test fun encryptedRar4AndRar5AreUnsupportedRepresentation() {
        for (asset in listOf("test_read_format_rar4_encrypted.rar.uu", "test_read_format_rar5_encrypted.rar.uu")) {
            val archive = fixture(asset)
            val before = sha(archive)
            openCalls = 0
            assertEquals(asset, CoverFailureReason.UNSUPPORTED_REPRESENTATION, failure { extractor().extract(item(archive), output()) { true } })
            assertEquals("$asset reached the native open", 1, openCalls)
            assertTrue("$asset: whole-archive open is refused, so no session exists to leak", opened.isEmpty())
            assertEquals(before, sha(archive))
        }
    }

    @Test fun repeatedNativeFailuresStayCorrectAndLeakNoDescriptors() {
        val plain = fixture("test_read_format_rar.rar.uu")
        val encrypted = fixture("test_read_format_rar5_encrypted.rar.uu")
        val hashes = listOf(sha(plain), sha(encrypted))
        fun round() {
            assertEquals(CoverFailureReason.NO_SUITABLE_ARTWORK, failure { extractor().extract(item(plain), output()) { true } })
            assertEquals(CoverFailureReason.UNSUPPORTED_REPRESENTATION, failure { extractor().extract(item(encrypted), output()) { true } })
        }
        repeat(3) { round() } // warm-up: lazy native/library state settles before the baseline
        val baseline = openFds()
        repeat(25) { round() }
        // Cleanup-aware: 50 failed opens would leak at least 50 descriptors; allow a few unrelated async ones.
        assertTrue("descriptors ${openFds()} vs baseline $baseline", openFds() <= baseline + 5)
        assertTrue("every opened native session was closed exactly once", opened.all { it.closes == 1 })
        assertEquals(hashes, listOf(sha(plain), sha(encrypted)))
    }

    // ---- Real native RAR: success on the ShelfOS-authored image-bearing RAR5 fixtures ----

    @Test fun realNativeRar5FixturesStoreTheDeclaredFrontCoverByteExact() {
        // FrontCover Image="1" = logical page 1 of the natural order p1, p2, p10 = p2.png (64x96 green).
        for ((asset, physical) in listOf("shelfos_cover_rar5.rar.uu" to listOf(1, 2), "shelfos_cover_rar5_solid.rar.uu" to listOf(0, 3))) {
            val archive = fixture(asset)
            val before = sha(archive)
            opened.clear(); openCalls = 0
            val needs = mutableListOf<Long>()
            val out = output()
            val cover = extractor().extract(item(archive), out) { needs += it; true }
            assertEquals(asset, listOf(CoverLocalSource.COMICINFO_FRONT_COVER, CoverSourceChoice.DECLARED, CoverRepresentation.ORIGINAL, "page:1"),
                listOf(cover.localSource, cover.choice, cover.representation, cover.sourceRef))
            assertEquals(asset, listOf(64, 96, 64, 96), listOf(cover.sourceWidth, cover.sourceHeight, cover.storedWidth, cover.storedHeight))
            assertEquals("image/png", cover.mimeType); assertEquals(out.length(), cover.bytes)
            assertTrue("$asset stored the green p2 page", near(Color.rgb(30, 200, 30), pixel(out)))
            assertEquals("$asset: ComicInfo then only the chosen page, by physical index", physical, opened.single().extracted)
            assertEquals(1, opened.single().closes)
            assertEquals("fixed reserve for ComicInfo, then the page's declared size", listOf(CoverStoragePolicy.UNKNOWN_TEMP_RESERVE_BYTES, 160L), needs)
            assertEquals(before, sha(archive))
        }
    }

    // ---- Injected fake sessions through the same production CBR branch ----

    private fun png(w: Int, h: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
    }
    private fun info(index: Int) = """<ComicInfo><Pages><Page Image="$index" Type="FrontCover"/></Pages></ComicInfo>""".toByteArray()
    private fun source() = File(root, "fake-${UUID.randomUUID()}.cbr").also { it.writeBytes("not a rar; the session is injected".toByteArray()); cleanup += it }

    @Test fun injectedSessionInvalidFrontCoverFallsBackToTheFirstLogicalPage() {
        val red = png(60, 90, Color.RED); val green = png(60, 90, Color.GREEN)
        val src = source(); val before = sha(src)
        val needs = mutableListOf<Long>(); val out = output()
        val cover = extractor { FakeSession(listOf(Triple("p2.png", green, green.size.toLong()), Triple("ComicInfo.xml", info(9), null),
            Triple("p1.png", red, red.size.toLong()))) }.extract(item(src), out) { needs += it; true }
        assertEquals(1, openCalls)
        assertEquals(listOf(CoverLocalSource.ARCHIVE_FIRST_PAGE, CoverSourceChoice.INFERRED, "page:0"), listOf(cover.localSource, cover.choice, cover.sourceRef))
        assertTrue(near(Color.RED, pixel(out)))
        assertEquals("ComicInfo (1) then p1 (physical 2) only", listOf(1, 2), opened.single().extracted)
        assertEquals(listOf(CoverStoragePolicy.UNKNOWN_TEMP_RESERVE_BYTES, red.size.toLong()), needs)
        assertEquals(1, opened.single().closes); assertEquals(before, sha(src))
    }

    @Test fun injectedSessionLargePageIsABoundedDerivativeAndAnUndeclaredSizeUsesTheReserve() {
        val big = png(1800, 2700, Color.BLUE) // small bytes, but its 2700 px edge exceeds the 1200 px bound
        val needs = mutableListOf<Long>(); val out = output()
        val cover = extractor { FakeSession(listOf(Triple("001.png", big, null))) }.extract(item(source()), out) { needs += it; true }
        assertEquals(CoverRepresentation.DERIVED, cover.representation)
        assertEquals(listOf(1800, 2700), listOf(cover.sourceWidth, cover.sourceHeight))
        assertTrue(maxOf(cover.storedWidth, cover.storedHeight) <= CoverStoragePolicy.MAX_EDGE_PX)
        assertTrue(out.length() <= CoverStoragePolicy.MAX_COVER_BYTES); assertEquals(out.length(), cover.bytes)
        assertTrue(near(Color.BLUE, pixel(out)))
        assertEquals("unknown page size -> the fixed reserve", listOf(CoverStoragePolicy.UNKNOWN_TEMP_RESERVE_BYTES, CoverStoragePolicy.UNKNOWN_TEMP_RESERVE_BYTES), needs)
    }

    @Test fun injectedSessionRefusedAdmissionOrCorruptPageFailsWithoutExtractingArtwork() {
        val page = png(60, 90, Color.RED)
        val refused = extractor { FakeSession(listOf(Triple("p1.png", page, 50L * 1024 * 1024))) }
        assertEquals(CoverFailureReason.STORAGE_FULL, failure { refused.extract(item(source()), output()) { it < 40L * 1024 * 1024 } })
        assertTrue("the page is never extracted when storage is refused", opened.single().extracted.isEmpty())
        assertEquals(1, opened.single().closes)
        opened.clear()
        val out = output()
        val corrupt = extractor { FakeSession(listOf(Triple("p1.jpg", ByteArray(4000) { (it * 31).toByte() }, 4000L))) }
        assertEquals(CoverFailureReason.UNDECODABLE_CONTENT, failure { corrupt.extract(item(source()), out) { true } })
        assertEquals("a failed extraction leaves no artwork to commit", 0L, out.length())
        assertEquals(1, opened.single().closes)
    }
}
