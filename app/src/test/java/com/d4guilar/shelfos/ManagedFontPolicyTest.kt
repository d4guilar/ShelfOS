// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.reader.FontImportException
import com.d4guilar.shelfos.core.reader.ManagedFontFormat
import com.d4guilar.shelfos.core.reader.ManagedFontRepository
import com.d4guilar.shelfos.core.reader.safeFontResourceId
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.io.path.createTempFile

class ManagedFontPolicyTest {
    @Test fun acceptsStructurallyBoundedTtfAndOtfHeaders() {
        val ttf = fontFile(0x00010000)
        val otf = fontFile(0x4F54544F)
        try {
            assertEquals(ManagedFontFormat.TTF, ManagedFontRepository.validateSfnt(ttf, "Reading Serif.ttf"))
            assertEquals(ManagedFontFormat.OTF, ManagedFontRepository.validateSfnt(otf, "Reading Serif.otf"))
        } finally { ttf.delete(); otf.delete() }
    }

    @Test fun rejectsTruncatedCorruptCollectionAndExtensionMismatch() {
        val truncated = createTempFile(suffix = ".ttf").toFile().apply { writeBytes(byteArrayOf(0, 1, 0, 0)) }
        val collection = fontFile(0x74746366)
        val ttf = fontFile(0x00010000)
        try {
            assertThrows(FontImportException::class.java) { ManagedFontRepository.validateSfnt(truncated, "bad.ttf") }
            assertThrows(FontImportException::class.java) { ManagedFontRepository.validateSfnt(collection, "collection.ttf") }
            assertThrows(FontImportException::class.java) { ManagedFontRepository.validateSfnt(ttf, "disguised.otf") }
            assertThrows(FontImportException::class.java) { ManagedFontRepository.checkFontExtension("font.woff2") }
        } finally { truncated.delete(); collection.delete(); ttf.delete() }
    }

    @Test fun idsAndNamesCannotEscapeTheReservedNamespace() {
        assertEquals("user-123", safeFontResourceId("user:123"))
        listOf("../font", "font/path", "font\\path", "", ".hidden").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { safeFontResourceId(id) }
        }
        assertEquals("My Reading Font", ManagedFontRepository.normalizedFontName("My_Reading--Font.ttf"))
        assertNull(ManagedFontRepository.userUuid("builtin:serif"))
    }

    private fun fontFile(signature: Int): File = createTempFile(suffix = ".font").toFile().apply {
        val bytes = ByteBuffer.allocate(64)
        bytes.putInt(signature).putShort(3).putShort(0).putShort(0).putShort(0)
        listOf("name", "cmap", "head").forEachIndexed { index, tag ->
            bytes.put(tag.toByteArray(Charsets.ISO_8859_1)).putInt(0).putInt(60 + index).putInt(1)
        }
        while (bytes.position() < 64) bytes.put(0)
        writeBytes(bytes.array())
    }
}
