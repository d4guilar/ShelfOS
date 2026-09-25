// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.domain.library.*
import com.d4guilar.shelfos.feature.reader.ReaderPreferencesSaver
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test
import java.io.RandomAccessFile
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
            SeekableZip.open(RandomAccessFile(file, "r").channel).use { assertEquals(listOf("page2.png", "page10.png"), ArchivePolicy.pages(it).map { page -> page.name }) }
        } finally { file.delete() }
    }
    @Test fun emptyArchiveReportsAnActionableFailure() {
        val file = createTempFile(suffix = ".cbz").toFile()
        try {
            ZipOutputStream(file.outputStream()).close()
            // An archive with no entries has only an end record; it opens, then reports that no pages exist.
            SeekableZip.open(RandomAccessFile(file, "r").channel).use { zip ->
                assertEquals(PublicationProblem.EMPTY_ARCHIVE, assertThrows(PublicationException::class.java) { ArchivePolicy.pages(zip) }.problem)
            }
        } finally { file.delete() }
    }
    @Test fun titleAppearanceChangesRecordOnlyTouchedFields() {
        val title = ReaderPreferences(margins = 2.0)
        val global = ReaderPreferences(font = BookFont.SANS, lineHeight = 1.9)
        val before = resolveReaderPreferences(title, global)
        val update = appearanceUpdate(title, global, before, before.copy(fontSize = 1.4), globally = false)
        assertNull(update.global)
        assertEquals(ReaderPreferences(fontSize = 1.4, margins = 2.0), update.title)
        // Untouched fields keep inheriting, so a later global change still reaches this title.
        val resolved = resolveReaderPreferences(update.title, global.copy(lineHeight = 2.2))
        assertEquals(2.2, resolved.lineHeight!!, .001); assertEquals(BookFont.SANS, resolved.font)
    }

    @Test fun globalAppearanceChangesBecomeDefaultsAndDirectionStaysPerTitle() {
        val title = ReaderPreferences(font = BookFont.SANS, margins = 2.0)
        val global = ReaderPreferences(lineHeight = 1.9)
        val before = resolveReaderPreferences(title, global)
        val after = before.copy(font = BookFont.SERIF, fontSize = 1.2, direction = ReadingDirection.RTL)
        val update = appearanceUpdate(title, global, before, after, globally = true)
        assertEquals(ReaderPreferences(font = BookFont.SERIF, fontSize = 1.2, lineHeight = 1.9), update.global)
        // The title drops its conflicting font override so the change is visible here, keeps margins, and owns direction.
        assertEquals(ReaderPreferences(margins = 2.0, direction = ReadingDirection.RTL), update.title)
        assertNull(resolveReaderPreferences(ReaderPreferences(), ReaderPreferences(direction = ReadingDirection.RTL)).direction)
    }

    @Test fun unappliedAppearanceDraftsSaveAsPlainValues() {
        val scope = SaverScope { true }
        val full = ReaderPreferences(BookFont.SANS, 1.3, 1.9, 0.5, true, false, PagePalette.PAPER, ReadingDirection.RTL, FitMode.WIDTH)
        listOf(full, ReaderPreferences(), ReaderPreferences(fontSize = 2.1, direction = ReadingDirection.LTR)).forEach { draft ->
            val saved = with(ReaderPreferencesSaver) { scope.save(draft) }!!
            assertEquals(draft, ReaderPreferencesSaver.restore(saved))
        }
        // A name saved by another build restores as unset rather than failing recreation.
        assertEquals(ReaderPreferences(), ReaderPreferencesSaver.restore(listOf("COMIC_SANS", null, null, null, null, null, "NEON", "UP", "ZOOM")))
    }

    @Test fun resetClearsOnlyTheChosenLayer() {
        val title = ReaderPreferences(font = BookFont.SANS, direction = ReadingDirection.LTR)
        assertEquals(AppearanceUpdate(ReaderPreferences(), null), appearanceReset(title, globally = false))
        assertEquals(AppearanceUpdate(title, ReaderPreferences()), appearanceReset(title, globally = true))
    }

    @Test fun controlsFollowRenderingCapabilities() {
        assertEquals(ReaderCapabilities(typography = true, fit = false, zoom = false), capabilities(PublicationFormat.EPUB))
        listOf(PublicationFormat.PDF, PublicationFormat.CBZ).forEach { format ->
            val fixed = capabilities(format)
            assertFalse(fixed.typography); assertTrue(fixed.fit && fixed.zoom && fixed.direction)
        }
    }

    // Locator/preference JSON uses the platform org.json, so serialization is covered by ReaderStateTest on a device.
    @Test fun pageProgressIsBoundedAndCountsTheVisiblePage() {
        assertEquals(10, pageProgress(0, 10)); assertEquals(100, pageProgress(9, 10))
        assertEquals(0, pageProgress(0, 0)); assertEquals(100, pageProgress(50, 10))
    }

    @Test fun focusedReaderControlsKeepNavigationKeysButPageKeysStillTurn() {
        fun command(key: InputKey, focused: Boolean, rtl: Boolean = false) = InputMapper.readerCommand(KeyStroke(key), rtl, focused)
        assertEquals(ShelfCommand.NEXT_PAGE, command(InputKey.RIGHT, focused = false))
        assertEquals(ShelfCommand.NEXT_PAGE, command(InputKey.LEFT, focused = false, rtl = true))
        listOf(InputKey.LEFT, InputKey.RIGHT, InputKey.UP, InputKey.DOWN, InputKey.TAB, InputKey.ENTER, InputKey.SPACE, InputKey.GAMEPAD_A)
            .forEach { assertNull(it.name, command(it, focused = true)) }
        assertEquals(ShelfCommand.NEXT_PAGE, command(InputKey.PAGE_DOWN, focused = true))
        assertEquals(ShelfCommand.PREVIOUS_PAGE, command(InputKey.L1, focused = true, rtl = true))
        assertEquals(ShelfCommand.BACK, command(InputKey.ESCAPE, focused = true))
        assertEquals(ShelfCommand.OPEN_MENU, command(InputKey.START, focused = true))
        assertNull(command(InputKey.BACK, focused = false)) // System Back is never consumed by the mapper.
    }

    @Test fun renditionSanitizingPreservesTextAndRemovesActiveContent() {
        val cleaned = sanitizeEpubHtml("<html><body onload='bad()'><h1>Original heading</h1><p>Original text</p><script>bad()</script><a href='https://example.org'>Link</a></body></html>".toByteArray()).toString(Charsets.UTF_8)
        assertTrue(cleaned.contains("Original heading")); assertTrue(cleaned.contains("Original text"))
        assertFalse(cleaned.contains("<script")); assertFalse(cleaned.contains("onload")); assertFalse(cleaned.contains("https://example.org"))
    }
}
