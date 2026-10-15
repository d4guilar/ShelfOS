// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.core.reader.ExtractedCover
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.LocalCoverExtractor
import com.d4guilar.shelfos.domain.library.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Phase 4.5A.1 real extraction corpus on device: [LocalCoverExtractor] over generated EPUB2/EPUB3/CBZ/PDF fixtures held as
 * private copies. Checks byte-exact ORIGINAL storage, bounded DERIVED storage (≤ 1200 px, ≤ 512 KiB, source dimensions
 * recorded), FrontCover selection, truthful permanent failures (SVG, missing/invalid references, malformed and oversized
 * images), the bounded white-background PDF preview, and that every source file is byte-identical afterwards.
 * CBR uses the same image writer behind RarContainer; no RAR image fixture can be authored here (see VALIDATION).
 */
class CoverExtractionCorpusTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var files: PublicationFiles
    private lateinit var extractor: LocalCoverExtractor
    private val digests = mutableMapOf<File, String>()

    @Before fun setUp() {
        root = File(context.cacheDir, "cover-corpus-${System.nanoTime()}").apply { mkdirs() }
        files = PublicationFiles(context, root)
        extractor = LocalCoverExtractor(files, FixedReaderFactory(files))
    }
    @After fun tearDown() {
        for ((file, digest) in digests) assertEquals("source ${file.name} must be byte-identical", digest, sha(file))
        root.deleteRecursively()
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun image(w: Int, h: Int, format: Bitmap.CompressFormat, quality: Int = 90, paint: (Bitmap) -> Unit = { it.eraseColor(Color.RED) }): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also(paint)
        return ByteArrayOutputStream().also { bitmap.compress(format, quality, it); bitmap.recycle() }.toByteArray()
    }
    private fun publication(name: String, format: PublicationFormat, write: (File) -> Unit): LibraryItem {
        val file = File(root, name).also(write)
        digests[file] = sha(file)
        return LibraryItem(name, name, category = MediaCategory.BOOK, sourceUri = "test:$name", format = format, fileName = name,
            byteSize = file.length(), managedPath = name)
    }
    private fun zip(name: String, format: PublicationFormat, entries: List<Pair<String, ByteArray>>) = publication(name, format) { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            for ((entry, bytes) in entries) {
                if (entry == "mimetype") out.putNextEntry(ZipEntry(entry).apply {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value
                }) else out.putNextEntry(ZipEntry(entry))
                out.write(bytes); out.closeEntry()
            }
        }
    }
    private fun epub(name: String, manifest: String, metadata: String = "", resources: List<Pair<String, ByteArray>> = emptyList()) =
        zip(name, PublicationFormat.EPUB, listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(),
            "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title>$metadata</metadata><manifest><item id="t" href="t.xhtml" media-type="application/xhtml+xml"/>$manifest</manifest><spine><itemref idref="t"/></spine></package>""".toByteArray(),
            "OEBPS/t.xhtml" to "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>x</p></body></html>".toByteArray(),
        ) + resources)

    private fun extract(item: LibraryItem, admit: (Long) -> Boolean = { true }): Pair<ExtractedCover, File> {
        val out = File(root, "out-${item.id}.tmp")
        return extractor.extract(item, out, admit) to out
    }
    private fun failure(item: LibraryItem) = try { extract(item); null } catch (e: CoverExtractionFailure) { e.reason }
    private fun pixel(file: File, x: Float, y: Float): Int {
        val bitmap = BitmapFactory.decodeFile(file.path)!!
        return bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt()).also { bitmap.recycle() }
    }
    private fun close(a: Int, b: Int) = listOf(Color::red, Color::green, Color::blue).all { Math.abs(it(a) - it(b)) < 24 }

    @Test fun epub3SmallCoverIsStoredByteForByte() {
        val jpeg = image(300, 450, Bitmap.CompressFormat.JPEG)
        val item = epub("e3.epub", """<item id="c" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""",
            resources = listOf("OEBPS/images/cover.jpg" to jpeg))
        val (cover, out) = extract(item)
        assertArrayEquals(jpeg, out.readBytes())
        assertEquals(listOf(CoverRepresentation.ORIGINAL, CoverLocalSource.EPUB_COVER, CoverSourceChoice.DECLARED), listOf(cover.representation, cover.localSource, cover.choice))
        assertEquals(listOf(300, 450, 300, 450), listOf(cover.sourceWidth, cover.sourceHeight, cover.storedWidth, cover.storedHeight))
        assertEquals("managed", cover.sourceRevision)
    }

    @Test fun epub2LargeCoverIsABoundedDerivativeThatRecordsTheOriginalDimensions() {
        val png = image(1800, 2700, Bitmap.CompressFormat.PNG) { it.eraseColor(Color.BLUE) }
        val item = epub("e2.epub", """<item id="cover-img" href="../cover.png" media-type="image/png"/>""", """<meta name="cover" content="cover-img"/>""",
            listOf("cover.png" to png))
        val (cover, out) = extract(item)
        assertEquals(listOf(CoverRepresentation.DERIVED, "image/jpeg", "jpg"), listOf(cover.representation, cover.mimeType, cover.extension))
        assertEquals(listOf(1800, 2700, 800, 1200), listOf(cover.sourceWidth, cover.sourceHeight, cover.storedWidth, cover.storedHeight))
        assertTrue(out.length() <= CoverStoragePolicy.MAX_COVER_BYTES); assertEquals(cover.bytes, out.length())
        assertTrue(close(Color.BLUE, pixel(out, .5f, .5f)))
    }

    @Test fun unusableEpubCoversFailTruthfully() {
        assertEquals(CoverFailureReason.UNSUPPORTED_REPRESENTATION, failure(epub("svg.epub",
            """<item id="c" href="c.svg" media-type="image/svg+xml" properties="cover-image"/>""", resources = listOf("OEBPS/c.svg" to "<svg/>".toByteArray()))))
        assertEquals(CoverFailureReason.NO_SUITABLE_ARTWORK, failure(epub("none.epub", "")))
        assertEquals(CoverFailureReason.NO_SUITABLE_ARTWORK, failure(epub("escape.epub",
            """<item id="c" href="../../outside.jpg" media-type="image/jpeg" properties="cover-image"/>""")))
        assertEquals(CoverFailureReason.NO_SUITABLE_ARTWORK, failure(epub("missing.epub",
            """<item id="c" href="nothere.jpg" media-type="image/jpeg" properties="cover-image"/>""")))
        assertEquals(CoverFailureReason.UNDECODABLE_CONTENT, failure(epub("garbage.epub",
            """<item id="c" href="c.jpg" media-type="image/jpeg" properties="cover-image"/>""", resources = listOf("OEBPS/c.jpg" to Random(1).nextBytes(4096)))))
    }

    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    private fun pages() = colors.mapIndexed { i, c -> "page${i + 1}.png" to image(60, 90, Bitmap.CompressFormat.PNG) { it.eraseColor(c) } }
    private fun info(index: Int) = "ComicInfo.xml" to """<ComicInfo><Pages><Page Image="$index" Type="FrontCover"/></Pages></ComicInfo>""".toByteArray()

    @Test fun cbzUsesAValidFrontCoverElseTheFirstLogicalPage() {
        val (declared, out) = extract(zip("fc.cbz", PublicationFormat.CBZ, pages().reversed() + info(2)))
        assertEquals(listOf(CoverLocalSource.COMICINFO_FRONT_COVER, CoverSourceChoice.DECLARED, CoverRepresentation.ORIGINAL), listOf(declared.localSource, declared.choice, declared.representation))
        assertEquals("page:2", declared.sourceRef)
        assertTrue(close(Color.BLUE, pixel(out, .5f, .5f)))
        for ((name, index) in listOf("oor.cbz" to 4, "neg.cbz" to -1)) {
            val (first, firstOut) = extract(zip(name, PublicationFormat.CBZ, pages() + info(index)))
            assertEquals(listOf(CoverLocalSource.ARCHIVE_FIRST_PAGE, CoverSourceChoice.INFERRED), listOf(first.localSource, first.choice))
            assertTrue(close(Color.RED, pixel(firstOut, .5f, .5f)))
        }
    }

    @Test fun malformedOversizedAndNoisyComicPagesStayBounded() {
        assertEquals(CoverFailureReason.UNDECODABLE_CONTENT, failure(zip("bad.cbz", PublicationFormat.CBZ, listOf("p1.jpg" to Random(2).nextBytes(10_000)))))
        // A PNG header declaring 20000x20000 (400 Mpx) is rejected from its header, before any allocation.
        val ihdr = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0x4E, 0x20, 0, 0, 0x4E, 0x20, 8, 6, 0, 0, 0, 0x1C.toByte(), 0x7A, 0x5C.toByte(), 0x2C)
        val huge = failure(zip("huge.cbz", PublicationFormat.CBZ, listOf("p1.png" to ihdr)))
        assertTrue("$huge", huge == CoverFailureReason.UNSUPPORTED_REPRESENTATION || huge == CoverFailureReason.UNDECODABLE_CONTENT)
        val noise = image(2400, 3600, Bitmap.CompressFormat.JPEG, 100) { b ->
            val r = Random(3); val row = IntArray(b.width)
            for (y in 0 until b.height) { for (x in row.indices) row[x] = r.nextInt() or 0xFF000000.toInt(); b.setPixels(row, 0, b.width, 0, y, b.width, 1) }
        }
        val result = try { extract(zip("noise.cbz", PublicationFormat.CBZ, listOf("p1.jpg" to noise))) } catch (e: CoverExtractionFailure) { null to e.reason }
        when (val cover = result.first) {
            null -> assertEquals("ladder exhausted: truthful, never oversized", CoverFailureReason.UNSUPPORTED_REPRESENTATION, result.second)
            else -> {
                assertTrue(cover.bytes <= CoverStoragePolicy.MAX_COVER_BYTES)
                assertTrue(maxOf(cover.storedWidth, cover.storedHeight) in CoverStoragePolicy.MIN_EDGE_PX..CoverStoragePolicy.MAX_EDGE_PX)
                assertEquals(listOf(2400, 3600), listOf(cover.sourceWidth, cover.sourceHeight))
            }
        }
    }

    @Test fun pdfFirstPageIsABoundedWhiteBackgroundPreview() {
        val item = publication("doc.pdf", PublicationFormat.PDF) { file ->
            val document = PdfDocument()
            val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
            page.canvas.drawRect(200f, 300f, 400f, 500f, Paint().apply { color = Color.BLACK })
            document.finishPage(page)
            document.startPage(PdfDocument.PageInfo.Builder(600, 800, 2).create()).also { document.finishPage(it) }
            file.outputStream().use(document::writeTo); document.close()
        }
        val (cover, out) = extract(item)
        assertEquals(listOf(CoverLocalSource.PDF_FIRST_PAGE, CoverSourceChoice.INFERRED, CoverRepresentation.DERIVED, "image/jpeg"),
            listOf(cover.localSource, cover.choice, cover.representation, cover.mimeType))
        assertEquals(listOf(600, 800, 900, 1200), listOf(cover.sourceWidth, cover.sourceHeight, cover.storedWidth, cover.storedHeight))
        assertTrue(out.length() <= CoverStoragePolicy.MAX_COVER_BYTES)
        assertTrue("white background", close(Color.WHITE, pixel(out, .05f, .05f)))
        assertTrue(close(Color.BLACK, pixel(out, .5f, .5f)))
    }

    @Test fun anUnavailableSourceIsATemporaryWaitNotAPermanentFailure() {
        val item = LibraryItem("gone", "gone", category = MediaCategory.BOOK, sourceUri = "test:gone", format = PublicationFormat.EPUB,
            fileName = "gone.epub", byteSize = 1, managedPath = "gone.epub")
        assertEquals(CoverFailureReason.SOURCE_UNAVAILABLE, failure(item))
    }
}
