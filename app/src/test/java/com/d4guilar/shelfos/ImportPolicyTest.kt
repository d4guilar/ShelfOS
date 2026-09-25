// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.files.EmbeddedMetadataReader
import com.d4guilar.shelfos.domain.importing.*
import com.d4guilar.shelfos.domain.library.*
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.util.zip.ZipException

class ImportPolicyTest {
    private fun problem(block: () -> Unit) = try { block(); null } catch (e: PublicationException) { e.problem }

    @Test fun epubEvidenceWinsOverImagesSoEpubsAreNeverMistakenForComics() {
        val epub = listOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf", "OEBPS/cover.jpg", "OEBPS/images/1.png")
        assertEquals(PublicationFormat.EPUB, classifyArchive(epub, "application/epub+zip"))
        // A missing or damaged mimetype entry still has EPUB container evidence.
        assertEquals(PublicationFormat.EPUB, classifyArchive(epub - "mimetype", null))
        assertEquals(PublicationProblem.CORRUPT, problem { classifyArchive(listOf("mimetype", "cover.jpg"), "application/epub+zip") })
    }

    @Test fun drmProtectedEpubsAreRejectedExplicitly() {
        val base = listOf("mimetype", "META-INF/container.xml", "content.opf")
        assertEquals(PublicationProblem.PROTECTED, problem { classifyArchive(base + "META-INF/rights.xml", "application/epub+zip") })
        assertEquals(PublicationProblem.PROTECTED, problem { classifyArchive(base + "META-INF/license.lcpl", "application/epub+zip") })
    }

    @Test fun comicArchivesNeedImagePagesAndIgnorePlatformMetadata() {
        assertEquals(PublicationFormat.CBZ, classifyArchive(listOf("ComicInfo.xml", "01.jpg", "02.webp"), null))
        // A Calibre-style metadata.opf beside images is not EPUB container evidence.
        assertEquals(PublicationFormat.CBZ, classifyArchive(listOf("metadata.opf", "page1.png"), null))
        assertEquals(PublicationProblem.EMPTY_ARCHIVE, problem { classifyArchive(listOf("__MACOSX/01.jpg", "notes.txt", ".hidden.png"), null) })
        assertFalse(isPageImage("__MACOSX/page.jpg")); assertFalse(isPageImage("pages/.thumb.jpg")); assertTrue(isPageImage("pages/Page 10.JPEG"))
    }

    @Test fun categorySuggestionsFollowFormatAndTrustedMangaEvidence() {
        assertEquals(MediaCategory.BOOK, suggestedCategory(PublicationFormat.EPUB))
        assertEquals(MediaCategory.BOOK, suggestedCategory(PublicationFormat.PDF))
        assertEquals(MediaCategory.COMIC, suggestedCategory(PublicationFormat.CBZ))
        assertEquals(MediaCategory.MANGA, suggestedCategory(PublicationFormat.CBZ, rightToLeftManga = true))
    }

    @Test fun repeatedSourcesAreIdempotentAndSimilarFilesOnlyWarn() {
        val library = testItems()
        assertEquals("book", existingSource(library, "test:book")?.id)
        assertNull(existingSource(library, "content://other/book.epub"))
        val candidate = library.first().copy(id = "new", sourceUri = "content://other/book.epub")
        assertTrue(isPossibleDuplicate(library, candidate))
        assertFalse(isPossibleDuplicate(library, candidate.copy(byteSize = 101)))
        assertFalse(isPossibleDuplicate(library, candidate.copy(byteSize = null)))
        assertFalse(isPossibleDuplicate(library, library.first()))
    }

    @Test fun startupMaintenanceReleasesOnlyUnreferencedGrantsAndNeverDeletesItems() {
        val linked = LibraryItem("linked", "Linked", category = MediaCategory.BOOK, sourceUri = "content://docs/linked", format = PublicationFormat.PDF, fileName = "a.pdf", byteSize = 1)
        val revoked = linked.copy(id = "revoked", sourceUri = "content://docs/revoked")
        val copied = linked.copy(id = "copied", sourceUri = "content://docs/copied", managedPath = "copied.pdf")
        val fixture = linked.copy(id = "fixture", sourceUri = "test:fixture")
        val plan = planSourceMaintenance(listOf(linked, revoked, copied, fixture), setOf("content://docs/linked", "content://docs/abandoned"))
        assertEquals(setOf("content://docs/abandoned"), plan.releaseGrants)
        assertEquals(setOf("revoked"), plan.unavailableItems)
    }

    @Test fun platformFailuresMapToDistinctProblems() {
        assertEquals(PublicationProblem.PERMISSION_LOST, SecurityException().publicationProblem())
        assertEquals(PublicationProblem.SOURCE_UNAVAILABLE, FileNotFoundException().publicationProblem())
        assertEquals(PublicationProblem.CORRUPT, ZipException().publicationProblem())
        assertEquals(PublicationProblem.UNREADABLE, IllegalStateException().publicationProblem())
        assertTrue(PublicationProblem.PERMISSION_LOST.unavailable && PublicationProblem.SOURCE_UNAVAILABLE.unavailable)
        assertFalse(PublicationProblem.PROTECTED.unavailable || PublicationProblem.CORRUPT.unavailable)
        assertEquals("Detail.", PublicationException(PublicationProblem.CORRUPT, "Detail.").readerMessage())
        assertEquals(PublicationProblem.entries.size, PublicationProblem.entries.map { it.message }.toSet().size)
    }

    @Test fun epubPackageMetadataIsBoundedUntrustedEvidence() {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>  Frankenstein;
              or, the Modern Prometheus </dc:title><dc:creator>Mary Shelley</dc:creator><dc:creator>Second</dc:creator></metadata></package>"""
        val metadata = EmbeddedMetadataReader.epubPackage(EmbeddedMetadataReader.parse(opf)!!)
        assertEquals("Frankenstein; or, the Modern Prometheus", metadata.title)
        assertEquals("Mary Shelley", metadata.creator)
        assertNull(EmbeddedMetadataReader.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/hosts\">]><package>&e;</package>"))
        val long = EmbeddedMetadataReader.epubPackage(EmbeddedMetadataReader.parse("<package><metadata><dc:title>${"x".repeat(500)}</dc:title></metadata></package>")!!)
        assertEquals(300, long.title!!.length)
        assertNull(EmbeddedMetadataReader.epubPackage(EmbeddedMetadataReader.parse("<package><metadata><dc:title> </dc:title></metadata></package>")!!).title)
    }

    @Test fun comicInfoSuppliesTitleWriterAndOnlyExplicitRightToLeftManga() {
        fun read(xml: String) = EmbeddedMetadataReader.comicInfo(EmbeddedMetadataReader.parse(xml)!!)
        val manga = read("<ComicInfo><Series>Original Series</Series><Number>3</Number><Writer>A. Author</Writer><Manga>YesAndRightToLeft</Manga></ComicInfo>")
        assertEquals("Original Series 3", manga.title); assertEquals("A. Author", manga.creator); assertTrue(manga.rightToLeftManga)
        val titled = read("<ComicInfo><Title>Issue Title</Title><Series>Series</Series><Manga>Yes</Manga></ComicInfo>")
        assertEquals("Issue Title", titled.title); assertFalse(titled.rightToLeftManga)
        assertNull(read("<Other/>").title)
    }
}
