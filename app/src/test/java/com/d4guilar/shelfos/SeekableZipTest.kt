// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.files.SeekableZip
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationProblem
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempFile

class SeekableZipTest {
    private val files = mutableListOf<File>()
    @After fun deleteTemporaryArchives() { files.forEach { it.delete() } }

    private fun archive(build: ZipOutputStream.() -> Unit) = createTempFile(suffix = ".zip").toFile().also { file ->
        files += file
        ZipOutputStream(file.outputStream()).use(build)
    }

    private fun open(file: File) = SeekableZip.open(RandomAccessFile(file, "r").channel)
    private fun problem(block: () -> Unit) = try { block(); null } catch (e: PublicationException) { e.problem }

    @Test fun storedAndDeflatedEntriesStreamTheirOriginalBytes() {
        val text = "Original ShelfOS page text. ".repeat(4000).toByteArray()
        val stored = ByteArray(5000) { (it % 251).toByte() }
        val file = archive {
            putNextEntry(ZipEntry("chapter/deflated.txt")); write(text); closeEntry() // Written with a data descriptor.
            putNextEntry(ZipEntry("stored.bin").apply {
                method = ZipEntry.STORED; size = stored.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(stored) }.value
            }); write(stored); closeEntry()
            putNextEntry(ZipEntry("folder/")); closeEntry()
        }
        open(file).use { zip ->
            assertEquals(listOf("chapter/deflated.txt", "stored.bin", "folder/"), zip.entries.map { it.name })
            val deflated = zip.entry("chapter/deflated.txt")!!
            assertEquals(SeekableZip.DEFLATED, deflated.method)
            assertArrayEquals(text, zip.open(deflated).use { it.readBytes() })
            assertArrayEquals(stored, zip.open(zip.entry("stored.bin")!!).use { it.readBytes() })
            assertTrue(zip.entry("folder/")!!.isDirectory)
            // Reading entries in any order uses positional reads only.
            assertArrayEquals(text, zip.open(deflated).use { it.readBytes() })
        }
    }

    @Test fun zip64DirectoriesAreRead() {
        // More than 65,535 entries forces ZIP64 end-of-directory records.
        val file = archive { repeat(70_000) { putNextEntry(ZipEntry("p$it.txt")); write(it % 256); closeEntry() } }
        open(file).use { zip ->
            assertEquals(70_000, zip.entries.size)
            assertEquals(69_999 % 256, zip.open(zip.entry("p69999.txt")!!).use { it.read() })
        }
        assertEquals(PublicationProblem.TOO_LARGE, problem { SeekableZip.open(RandomAccessFile(file, "r").channel, maxEntries = 1000) })
    }

    /** Codex review R4 regression, kept verbatim. */
    @Test fun validZipCommentMustNotReplaceTheDirectory() {
        val file = createTempFile(suffix = ".cbz").toFile()
        try {
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("page.jpg")); it.write(byteArrayOf(1,2,3)); it.closeEntry()
                it.setComment("PK\u0005\u0006" + "\u0000".repeat(18))
            }
            SeekableZip.open(RandomAccessFile(file, "r").channel).use {
                assertEquals(listOf("page.jpg"), it.entries.map { entry -> entry.name })
            }
        } finally { file.delete() }
    }

    @Test fun commentsCannotReplaceAZip64Directory() {
        val file = archive { putNextEntry(ZipEntry("page.jpg")); write(byteArrayOf(1, 2, 3)); closeEntry() }
        rewriteAsZip64(file, comment = byteArrayOf(0x50, 0x4b, 5, 6) + ByteArray(18))
        open(file).use { zip ->
            assertEquals(listOf("page.jpg"), zip.entries.map { it.name })
            assertArrayEquals(byteArrayOf(1, 2, 3), zip.open(zip.entries.single()).use { it.readBytes() })
        }
        // The same 22 bytes are a valid end record where the directory really ends: an empty archive.
        val empty = createTempFile(suffix = ".zip").toFile().also { files += it; it.writeBytes(byteArrayOf(0x50, 0x4b, 5, 6) + ByteArray(18)) }
        open(empty).use { assertTrue(it.entries.isEmpty()) }
    }

    /** Moves a comment-free archive's directory totals into ZIP64 end records, as some writers always do, and adds [comment]. */
    private fun rewriteAsZip64(file: File, comment: ByteArray) {
        val bytes = file.readBytes()
        val end = bytes.size - 22
        val original = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = original.getShort(end + 10).toLong() and 0xffff
        val size = original.getInt(end + 12).toLong() and 0xffffffffL
        val offset = original.getInt(end + 16).toLong() and 0xffffffffL
        val out = ByteBuffer.allocate(end + 56 + 20 + 22 + comment.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes, 0, end)
        out.putInt(0x06064b50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
            .putLong(count).putLong(count).putLong(size).putLong(offset)
        out.putInt(0x07064b50).putInt(0).putLong(end.toLong()).putInt(1)
        out.putInt(0x06054b50).putShort(0).putShort(0).putShort(-1).putShort(-1).putInt(-1).putInt(-1)
            .putShort(comment.size.toShort()).put(comment)
        file.writeBytes(out.array())
    }

    @Test fun damagedOrForeignFilesAreReportedAsCorrupt() {
        val notZip = createTempFile(suffix = ".bin").toFile().also { files += it; it.writeText("not an archive at all, just text") }
        assertEquals(PublicationProblem.CORRUPT, problem { open(notZip) })
        val whole = archive { putNextEntry(ZipEntry("a.jpg")); write(ByteArray(10_000) { 7 }); closeEntry() }
        val truncated = createTempFile(suffix = ".zip").toFile().also { files += it; it.writeBytes(whole.readBytes().copyOf(whole.length().toInt() - 40)) }
        assertEquals(PublicationProblem.CORRUPT, problem { open(truncated) })
    }

    @Test fun encryptedEntriesAreRefusedAsProtected() {
        val file = archive { putNextEntry(ZipEntry("page.jpg")); write(ByteArray(64)); closeEntry() }
        // Set the "encrypted" general-purpose flag in the central directory record.
        val bytes = file.readBytes()
        val central = (0 until bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        bytes[central + 8] = (bytes[central + 8].toInt() or 1).toByte()
        file.writeBytes(bytes)
        open(file).use { zip ->
            assertTrue(zip.entries.single().encrypted)
            assertEquals(PublicationProblem.PROTECTED, problem { zip.open(zip.entries.single()) })
        }
    }

    @Test fun boundedReadsNeverExceedTheirLimit() {
        val file = archive { putNextEntry(ZipEntry("big.xml")); write(ByteArray(200_000) { 'x'.code.toByte() }); closeEntry() }
        open(file).use { zip ->
            val entry = zip.entries.single()
            assertNull(zip.readBytes(entry, 1000))
            // A header that understates the size still cannot read past the limit.
            assertNull(zip.readBytes(entry.copy(size = 10), 1000))
            assertEquals(200_000, zip.readBytes(entry, 1_000_000)!!.size)
        }
    }
}
