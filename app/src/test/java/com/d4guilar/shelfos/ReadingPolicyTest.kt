// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.domain.library.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.*
import kotlin.io.path.createTempFile

class ReadingPolicyTest {
    @Test fun mangaDefaultsAndExplicitOverridesAreFormatIndependent() {
        assertEquals(ReadingDirection.RTL, readingDirection(MediaCategory.MANGA, null))
        assertEquals(ReadingDirection.LTR, readingDirection(MediaCategory.COMIC, null))
        assertEquals(ReadingDirection.LTR, readingDirection(MediaCategory.MANGA, ReadingDirection.LTR))
    }
    @Test fun rtlChangesArrowsButNotSemanticNextOrLibraryFocus() {
        assertEquals(ShelfCommand.NEXT_PAGE, InputMapper.command(KeyStroke(InputKey.LEFT), InputContext.READER, true))
        assertEquals(ShelfCommand.PREVIOUS_PAGE, InputMapper.command(KeyStroke(InputKey.RIGHT), InputContext.READER, true))
        assertEquals(ShelfCommand.NEXT_PAGE, InputMapper.command(KeyStroke(InputKey.R1), InputContext.READER, true))
        assertNull(InputMapper.command(KeyStroke(InputKey.LEFT), InputContext.LIBRARY, true))
    }
    @Test fun preferencesResolvePerFieldWithoutLosingOverrides() {
        val item = ReaderPreferences(font = BookFont.SANS)
        val global = ReaderPreferences(font = BookFont.SERIF, lineHeight = 1.9)
        val resolved = item.over(global).over(ReaderPreferences.DEFAULT)
        assertEquals(BookFont.SANS, resolved.font)
        assertEquals(1.9, resolved.lineHeight!!, .001)
        assertEquals(PagePalette.THEME, resolved.palette)
        assertNull(resolved.direction)
    }
    @Test fun naturalPageOrderHandlesLongNumbersAndLeadingZeros() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "999999999999999999999999.jpg", "02.jpg")
        assertEquals(listOf("1.jpg", "02.jpg", "2.jpg", "10.jpg", "999999999999999999999999.jpg"), names.sortedWith(::naturalCompare))
    }
    @Test fun unsafeArchiveNamesAreRejected() {
        listOf("../page.jpg", "/page.jpg", "C:/page.jpg", "dir/../../page.jpg", "dir\\page.jpg").forEach { assertFalse(ArchivePolicy.safeName(it)) }
        assertTrue(ArchivePolicy.safeName("chapter 1/page 02.jpg"))
    }
    @Test fun archiveIgnoresMetadataAndFindsPagesWithoutExtracting() {
        val file = createTempFile(suffix = ".cbz").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                listOf("page10.png", "page2.png", "__MACOSX/page1.png", "ComicInfo.xml").forEach { name ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
                }
            }
            ZipFile(file).use { assertEquals(listOf("page2.png", "page10.png"), ArchivePolicy.pages(it).map { page -> page.name }) }
        } finally { file.delete() }
    }
    @Test fun emptyArchiveReportsAnActionableFailure() {
        val file = createTempFile(suffix = ".cbz").toFile()
        try {
            ZipOutputStream(file.outputStream()).close()
            ZipFile(file).use { zip -> assertThrows(PublicationException::class.java) { ArchivePolicy.pages(zip) } }
        } finally { file.delete() }
    }
    @Test fun renditionSanitizingPreservesTextAndRemovesActiveContent() {
        val cleaned = sanitizeEpubHtml("<html><body onload='bad()'><h1>Original heading</h1><p>Original text</p><script>bad()</script><a href='https://example.org'>Link</a></body></html>".toByteArray()).toString(Charsets.UTF_8)
        assertTrue(cleaned.contains("Original heading")); assertTrue(cleaned.contains("Original text"))
        assertFalse(cleaned.contains("<script")); assertFalse(cleaned.contains("onload")); assertFalse(cleaned.contains("https://example.org"))
    }
}
