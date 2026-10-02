// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import com.d4guilar.shelfos.domain.library.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Entirely original test publications; no private samples or artwork are packaged. */
object OriginalFixtures {
    fun pdf(context: Context): LibraryItem {
        val file = File(context.filesDir, "publications/test-original.pdf").also { it.parentFile!!.mkdirs() }
        val pdf = PdfDocument()
        try {
            repeat(3) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("ShelfOS original test page ${index + 1}", 20f, 80f, Paint().apply { color = Color.BLACK; textSize = 18f })
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        return item(file, "test-pdf", PublicationFormat.PDF)
    }
    /** A deliberately tall page (1:6 aspect) for Phase 2D.1's Fit Width remediation: in a landscape viewport,
     * Fit Width's fitted content height (`viewportWidth * bitmapHeight / bitmapWidth`) comfortably exceeds the
     * viewport height even without zooming, reproducing the "tall content" geometry the original 2D.1 pass
     * never exercised.
     *
     * Phase 2D.1 remediation round two (zoomed top/bottom reachability): the top/bottom marker baselines are
     * placed at `y=30`/`y=2380` (close to, but inside, the page's own edges) rather than the original pass's
     * `y=80`/`y=2340` -- at 2x zoom on this extreme 1:6-aspect page in a wide landscape viewport, a single
     * screenful only shows roughly the nearest 3-5% of the page, so a marker further from the true edge than
     * this would sit just outside the first/last screenful even though the true edge itself is fully reachable
     * (see [FixedReaderTransformBoundsTest] for why the 5x stress case proves reachability via the scroll-range
     * formula directly instead of this marker, whose remaining field-of-view at 5x is too thin to contain any
     * fixed marker position reliably across two different devices' exact aspect ratios). */
    fun tallPdf(context: Context): LibraryItem {
        val file = File(context.filesDir, "publications/test-original-tall.pdf").also { it.parentFile!!.mkdirs() }
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 2400, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("ShelfOS tall test page top ${index + 1}", 20f, 30f, Paint().apply { color = Color.BLACK; textSize = 18f })
                page.canvas.drawText("ShelfOS tall test page bottom ${index + 1}", 20f, 2380f, Paint().apply { color = Color.BLACK; textSize = 18f })
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        return item(file, "test-pdf-tall", PublicationFormat.PDF)
    }
    fun cbz(context: Context): LibraryItem {
        val file = File(context.filesDir, "publications/test-original.cbz").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf(10, 2, 1).forEach { page ->
                zip.putNextEntry(ZipEntry("page$page.png"))
                val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (page == 1) Color.RED else if (page == 2) Color.GREEN else Color.BLUE)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle(); zip.closeEntry()
            }
        }
        return item(file, "test-cbz", PublicationFormat.CBZ).copy(category = MediaCategory.MANGA)
    }
    fun epub(context: Context): LibraryItem {
        val file = File(context.filesDir, "publications/test-original.epub").also { it.parentFile!!.mkdirs() }
        val paragraphs = (1..80).joinToString("") { "<p>Original ShelfOS paragraph $it. A quiet reading room has space for every reader. These words were written for this test publication.</p>" }
        val entries = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:shelfos-original-test</dc:identifier><dc:title>Original Reading Room</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-23T00:00:00Z</meta></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Reading Room</title></head><body><h1>Reading Room</h1>$paragraphs</body></html>""",
            "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Reading Room</a></li></ol></nav></body></html>""",
        )
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return item(file, "test-epub", PublicationFormat.EPUB)
    }
    /** Ten real chapters (distinct resources, no fragments) for Phase 2B.1 chapter-navigation/highlight/filter
     * coverage — more chapters than [epub] needs, and above the Chapters dialog's filter-field threshold. */
    fun epubWithChapters(context: Context): LibraryItem {
        val file = File(context.filesDir, "publications/test-original-chapters.epub").also { it.parentFile!!.mkdirs() }
        val chapterCount = 10
        val manifestItems = (1..chapterCount).joinToString("") { n -> """<item id="chapter$n" href="chapter$n.xhtml" media-type="application/xhtml+xml"/>""" } +
            """<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>"""
        val spineItems = (1..chapterCount).joinToString("") { n -> """<itemref idref="chapter$n"/>""" }
        val navEntries = (1..chapterCount).joinToString("") { n -> """<li><a href="chapter$n.xhtml">Chapter $n</a></li>""" }
        val entries = mutableMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:shelfos-chapters-test</dc:identifier><dc:title>Chapters Test Book</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-26T00:00:00Z</meta></metadata><manifest>$manifestItems</manifest><spine>$spineItems</spine></package>""",
            "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>$navEntries</ol></nav></body></html>""",
        )
        (1..chapterCount).forEach { n ->
            val paragraphs = (1..40).joinToString("") { "<p>Chapter $n paragraph $it. Original ShelfOS test content for chapter navigation.</p>" }
            entries["chapter$n.xhtml"] = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $n</title></head><body><h1>Chapter $n</h1>$paragraphs</body></html>"""
        }
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return item(file, "test-epub-chapters", PublicationFormat.EPUB)
    }
    /** One resource, two real TOC entries pointing into it at different anchors — for Phase 2B.1's secondary-
     * fragment/duplicate-row-identity coverage. Exercises the real Readium path (`locatorFromLink`/navigator
     * `currentLocator`) for the case a synthetic string-only unit test cannot: whether Readium's own real
     * fragment resolution actually agrees between a TOC entry and the position reached by navigating to it. */
    fun epubWithFragmentedChapter(context: Context): LibraryItem {
        val file = File(context.filesDir, "publications/test-original-fragments.epub").also { it.parentFile!!.mkdirs() }
        val sectionOne = (1..30).joinToString("") { "<p>Section One paragraph $it. Original ShelfOS test content before the second section.</p>" }
        val sectionTwo = (1..30).joinToString("") { "<p>Section Two paragraph $it. Original ShelfOS test content after the first section.</p>" }
        val entries = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:shelfos-fragments-test</dc:identifier><dc:title>Fragments Test Book</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-26T00:00:00Z</meta></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "chapter.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter</title></head><body><h1 id="section-one">Section One</h1>$sectionOne<h1 id="section-two">Section Two</h1>$sectionTwo</body></html>""",
            "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter.xhtml#section-one">Section One</a></li><li><a href="chapter.xhtml#section-two">Section Two</a></li></ol></nav></body></html>""",
        )
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return item(file, "test-epub-fragments", PublicationFormat.EPUB)
    }
    /** One resource with enough high-entropy, poorly-compressible content that Readium's default `EpubPositionsService`
     * strategy (1024 bytes/position, keyed off the archive-stored/compressed length) yields several real positions
     * for it — unlike `epubWithChapters`' repetitive text, which DEFLATE compresses down to a single position per
     * chapter. Needed for Phase 2B.2.1's [resolveEpubLocation] floor/segment-start semantics to be provable against
     * a real, non-fabricated Readium position catalog rather than only a synthetic `EpubPosition` list. The random
     * text is seeded, so its size and content — and therefore the resulting position count — are deterministic. */
    fun epubWithLongChapter(context: Context): LibraryItem {
        val file = File(context.filesDir, "publications/test-original-long-chapter.epub").also { it.parentFile!!.mkdirs() }
        val random = kotlin.random.Random(42)
        val body = (1..16000).map { ('a' + random.nextInt(26)) }.joinToString("")
        val entries = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:shelfos-long-chapter-test</dc:identifier><dc:title>Long Chapter Test Book</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-09-27T00:00:00Z</meta></metadata><manifest><item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter1"/></spine></package>""",
            "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter1.xhtml">Chapter 1</a></li></ol></nav></body></html>""",
            "chapter1.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 1</title></head><body><h1>Chapter 1</h1><p>$body</p></body></html>""",
        )
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
        return item(file, "test-epub-long-chapter", PublicationFormat.EPUB)
    }
    private fun item(file: File, id: String, format: PublicationFormat) = LibraryItem(id, "Original ${format.name} fixture", "ShelfOS test",
        MediaCategory.BOOK, "test:$id", format, file.name, file.length(), managedPath = file.path)
}
