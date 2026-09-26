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
    private fun item(file: File, id: String, format: PublicationFormat) = LibraryItem(id, "Original ${format.name} fixture", "ShelfOS test",
        MediaCategory.BOOK, "test:$id", format, file.name, file.length(), managedPath = file.path)
}
