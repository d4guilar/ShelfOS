// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import com.d4guilar.shelfos.domain.library.CoverLocalSource
import com.d4guilar.shelfos.domain.library.chooseArchiveCover
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 4.5A.1 cover source selection on the JVM: EPUB2/EPUB3 OPF cover references (safe OPF-relative resolution),
 * ComicInfo FrontCover parsing, and the one shared logical page order the reader and the cover extractor both use
 * (CBZ: [ArchivePolicy.pages]; CBR: [RarContainer]), including reuse of a reader-extracted page only for that page.
 */
class CoverSourceSelectionTest {
    private val temporary = mutableListOf<File>()
    @After fun cleanUp() { temporary.forEach { it.deleteRecursively() } }

    private fun opf(manifest: String, metadata: String = "") = EmbeddedMetadataReader.parse(
        """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
           <dc:title>T</dc:title>$metadata</metadata><manifest>$manifest</manifest></package>""")!!

    @Test fun epub3CoverImagePropertyResolvesRelativeToTheOpf() {
        val doc = opf("""<item id="c" href="../images/My%20Cover.jpg" media-type="image/jpeg" properties="svg cover-image"/>""")
        assertEquals(EpubCover("images/My Cover.jpg", "image/jpeg"), EmbeddedMetadataReader.epubCover(doc, "OEBPS/content.opf"))
    }

    @Test fun epub2MetaCoverResolvesByIdThenByHref() {
        val byId = opf("""<item id="cover-img" href="img/c.png" media-type="image/png"/>""", """<meta name="cover" content="cover-img"/>""")
        assertEquals(EpubCover("OPS/img/c.png", "image/png"), EmbeddedMetadataReader.epubCover(byId, "OPS/package.opf"))
        val byHref = opf("""<item id="x" href="c.gif" media-type="image/gif"/>""", """<meta name="cover" content="c.gif"/>""")
        assertEquals(EpubCover("c.gif", "image/gif"), EmbeddedMetadataReader.epubCover(byHref, "content.opf"))
    }

    @Test fun epub3PropertyWinsOverEpub2MetaAndSvgKeepsItsMediaType() {
        val doc = opf("""<item id="a" href="a.jpg" media-type="image/jpeg"/><item id="b" href="b.svg" media-type="image/svg+xml" properties="cover-image"/>""",
            """<meta name="cover" content="a"/>""")
        assertEquals(EpubCover("b.svg", "image/svg+xml"), EmbeddedMetadataReader.epubCover(doc, "content.opf"))
    }

    @Test fun invalidOrUnsafeCoverReferencesAreRejected() {
        for (href in listOf("../../escape.jpg", "/abs.jpg", "http://example.com/c.jpg", "a\\b.jpg", "bad%zz.jpg", "")) {
            val doc = opf("""<item id="c" href="$href" media-type="image/jpeg" properties="cover-image"/>""")
            assertNull(href, EmbeddedMetadataReader.epubCover(doc, "OEBPS/content.opf"))
        }
        assertNull("no declaration", EmbeddedMetadataReader.epubCover(opf("""<item id="c" href="c.jpg" media-type="image/jpeg"/>"""), "content.opf"))
        assertNull("dangling id", EmbeddedMetadataReader.epubCover(opf("", """<meta name="cover" content="missing"/>"""), "content.opf"))
        assertEquals("fragments are dropped and dot segments normalized", "OEBPS/i/c.jpg", EmbeddedMetadataReader.resolveHref("OEBPS", "./x/../i/c.jpg#frag"))
    }

    private fun comicInfo(pages: String) = EmbeddedMetadataReader.comicInfo(EmbeddedMetadataReader.parse(
        """<?xml version="1.0"?><ComicInfo><Title>T</Title><Pages>$pages</Pages></ComicInfo>""")!!)

    @Test fun comicInfoFrontCoverImageIsParsedAndAnythingElseIsAbsent() {
        assertEquals(3, comicInfo("""<Page Image="0" Type="Story"/><Page Image="3" Type="FrontCover"/>""").frontCoverImage)
        assertEquals(1, comicInfo("""<page Image=" 1 " Type="frontcover"/>""").frontCoverImage)
        assertNull(comicInfo("""<Page Image="x" Type="FrontCover"/>""").frontCoverImage)
        assertNull(comicInfo("""<Page Image="2" Type="Story"/>""").frontCoverImage)
        assertNull(comicInfo("").frontCoverImage)
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): File = Files.createTempFile("cover", ".cbz").toFile().also { file ->
        temporary += file
        ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() } }
    }

    @Test fun cbzFrontCoverIndexesTheNaturalLogicalPageListNotTheRawEntryOrder() {
        val info = """<ComicInfo><Pages><Page Image="1" Type="FrontCover"/></Pages></ComicInfo>""".toByteArray()
        // Raw entry order differs from natural order: logical pages are p1, p2, p10.
        val file = zip(listOf("p10.jpg" to byteArrayOf(10), "ComicInfo.xml" to info, "p2.jpg" to byteArrayOf(2), "notes.txt" to byteArrayOf(0), "p1.jpg" to byteArrayOf(1)))
        SeekableZip.open(RandomAccessFile(file, "r").channel).use { zip ->
            val pages = ArchivePolicy.pages(zip)
            assertEquals(listOf("p1.jpg", "p2.jpg", "p10.jpg"), pages.map { it.name })
            val choice = chooseArchiveCover(EmbeddedMetadataReader.comicInfo(zip).frontCoverImage, pages.size)!!
            assertEquals(CoverLocalSource.COMICINFO_FRONT_COVER, choice.source)
            assertEquals("p2.jpg", pages[choice.pageIndex].name)
        }
        val outOfRange = zip(listOf("b.png" to byteArrayOf(2), "a.png" to byteArrayOf(1),
            "ComicInfo.xml" to """<ComicInfo><Pages><Page Image="9" Type="FrontCover"/></Pages></ComicInfo>""".toByteArray()))
        SeekableZip.open(RandomAccessFile(outOfRange, "r").channel).use { zip ->
            val pages = ArchivePolicy.pages(zip)
            val choice = chooseArchiveCover(EmbeddedMetadataReader.comicInfo(zip).frontCoverImage, pages.size)!!
            assertEquals(CoverLocalSource.ARCHIVE_FIRST_PAGE, choice.source)
            assertEquals("a.png", pages[choice.pageIndex].name)
        }
    }

    private fun rarEntries() = listOf(
        FakeRarArchiveSession.entry(0, "p10.jpg", size = 3), FakeRarArchiveSession.entry(1, "ComicInfo.xml"),
        FakeRarArchiveSession.entry(2, "p2.jpg", size = 3), FakeRarArchiveSession.entry(3, "p1.jpg", size = 3))
    private val rarContent = mapOf(0 to byteArrayOf(10, 10, 10), 2 to byteArrayOf(2, 2, 2), 3 to byteArrayOf(1, 1, 1),
        1 to """<ComicInfo><Pages><Page Image="1" Type="FrontCover"/></Pages></ComicInfo>""".toByteArray())

    @Test fun cbrUsesTheContainerPageOrderAndReusesOnlyTheExactReaderPage() {
        val cacheRoot = Files.createTempDirectory("cover-cbr").toFile().also { temporary += it }
        // The "reader" session extracted logical page 1 (p2.jpg, physical 2) of a managed copy.
        val reader = FakeRarArchiveSession(rarEntries(), rarContent)
        RarContainer.open(reader, cacheRoot, "managed:item").let { c -> c.extractPage(1).release(); c.close() }
        assertEquals(listOf(2), reader.extractedIndices)

        // The cover extractor's own session: ComicInfo names logical page 1, which the reader already materialized.
        val cover = FakeRarArchiveSession(rarEntries(), rarContent)
        val container = RarContainer.open(cover, cacheRoot, "managed:item")
        val choice = chooseArchiveCover(container.comicInfo()?.frontCoverImage, container.pageCount)!!
        assertEquals(1, choice.pageIndex)
        assertEquals(3L, container.declaredPageBytes(choice.pageIndex))
        val page = container.extractPage(choice.pageIndex)
        assertArrayEquals(byteArrayOf(2, 2, 2), page.file.readBytes())
        page.release()
        assertEquals("only ComicInfo was extracted; the chosen page was reused", listOf(1), cover.extractedIndices)
        // A different page (e.g. the reader's resume page) is never substituted: page 0 needs its own extraction.
        container.extractPage(0).release()
        assertEquals(listOf(1, 3), cover.extractedIndices)
        container.close()

        // A linked source (null key) gets an ephemeral namespace: no reuse across sessions.
        val linked = FakeRarArchiveSession(rarEntries(), rarContent)
        RarContainer.open(linked, cacheRoot, null).let { c -> c.extractPage(1).release(); c.close() }
        assertEquals(listOf(2), linked.extractedIndices)
    }
}
