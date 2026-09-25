// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationProblem
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Minimal read-only ZIP reader over a seekable channel using positional reads.
 *
 * Document providers grant a descriptor, not a path: shared-storage files cannot be reopened by path
 * (including through procfs), so `java.util.zip.ZipFile` cannot read them. This reads the central
 * directory once and streams individual entries on demand; archives are never extracted. STORED and
 * DEFLATE entries and ZIP64 directories are supported; encrypted entries and other methods are refused.
 */
class SeekableZip private constructor(private val channel: FileChannel, val entries: List<Entry>) : Closeable {
    data class Entry(val name: String, val method: Int, val flags: Int, val compressedSize: Long, val size: Long, val localHeaderOffset: Long) {
        val isDirectory get() = name.endsWith('/')
        val encrypted get() = flags and 1 != 0
    }

    private val byName = entries.associateBy { it.name }

    fun entry(name: String): Entry? = byName[name]

    /** Streams one entry's uncompressed bytes. */
    fun open(entry: Entry): InputStream {
        if (entry.encrypted) throw PublicationException(PublicationProblem.PROTECTED)
        val header = readFully(channel, entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (header.int(0) != LOCAL_SIGNATURE) throw corrupt()
        val start = entry.localHeaderOffset + LOCAL_HEADER_SIZE + header.short(26) + header.short(28)
        if (start + entry.compressedSize > channel.size()) throw corrupt()
        val raw = RangeInputStream(channel, start, entry.compressedSize)
        return when (entry.method) {
            STORED -> raw
            DEFLATED -> EntryInflaterStream(raw)
            else -> throw PublicationException(PublicationProblem.UNSUPPORTED_FORMAT, "The archive uses an unsupported compression method.")
        }
    }

    /**
     * Small entries only: returns null rather than reading more than [limit] bytes. The limit applies to the
     * actual inflated output, so an entry whose header understates its size cannot exhaust memory.
     */
    fun readBytes(entry: Entry, limit: Long): ByteArray? {
        if (entry.size !in 0..limit) return null
        open(entry).use { input ->
            val output = ByteArrayOutputStream(entry.size.toInt())
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                total += count
                if (total > limit) return null
                output.write(buffer, 0, count)
            }
        }
    }

    override fun close() = channel.close()

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
        private const val LOCAL_SIGNATURE = 0x04034b50
        private const val CENTRAL_SIGNATURE = 0x02014b50
        private const val END_SIGNATURE = 0x06054b50
        private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
        private const val ZIP64_END_SIGNATURE = 0x06064b50
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val END_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_END_SIZE = 56
        private const val MAX_DIRECTORY_BYTES = 64L * 1024 * 1024
        private const val MASK_32 = 0xffffffffL

        /** Takes ownership of [channel]; it is closed on failure or when the archive is closed. */
        fun open(channel: FileChannel, maxEntries: Int = ArchivePolicy.MAX_ENTRIES): SeekableZip = try {
            SeekableZip(channel, readDirectory(channel, maxEntries))
        } catch (error: Throwable) {
            channel.close()
            throw when (error) {
                is PublicationException -> error
                is IOException, is IndexOutOfBoundsException, is IllegalArgumentException -> corrupt()
                else -> error
            }
        }

        private fun readDirectory(channel: FileChannel, maxEntries: Int): List<Entry> {
            val size = channel.size()
            if (size < END_SIZE) throw corrupt()
            val tailLength = minOf(size, END_SIZE + 0xffffL).toInt()
            val tail = readFully(channel, size - tailLength, tailLength)
            // An archive comment may contain the end signature itself, so candidates are tried from the end backwards
            // and only one that agrees with the directory it describes is used.
            val (count, directorySize, directoryOffset) = (tailLength - END_SIZE downTo 0).asSequence()
                .filter { tail.int(it) == END_SIGNATURE }
                .firstNotNullOfOrNull { endRecord(channel, tail, it, size - tailLength + it) } ?: throw corrupt()
            if (count > maxEntries) throw PublicationException(PublicationProblem.TOO_LARGE, "The archive has too many entries.")
            if (directorySize > MAX_DIRECTORY_BYTES) throw corrupt()
            val directory = readFully(channel, directoryOffset, directorySize.toInt())
            val entries = ArrayList<Entry>(count.toInt())
            var position = 0
            repeat(count.toInt()) {
                if (directory.int(position) != CENTRAL_SIGNATURE) throw corrupt()
                val nameLength = directory.short(position + 28)
                val extraLength = directory.short(position + 30)
                val commentLength = directory.short(position + 32)
                val nameStart = position + CENTRAL_HEADER_SIZE
                var compressed = directory.uint(position + 20)
                var uncompressed = directory.uint(position + 24)
                var offset = directory.uint(position + 42)
                if (compressed == MASK_32 || uncompressed == MASK_32 || offset == MASK_32) {
                    // ZIP64 extended information holds only the fields whose 32-bit values overflowed, in this order.
                    var field = zip64Extra(directory, nameStart + nameLength, extraLength)
                    fun next(): Long { if (field < 0) throw corrupt(); return directory.long(field).also { field += 8 } }
                    if (uncompressed == MASK_32) uncompressed = next()
                    if (compressed == MASK_32) compressed = next()
                    if (offset == MASK_32) offset = next()
                }
                if (compressed < 0 || uncompressed < 0 || offset < 0 || offset >= directoryOffset) throw corrupt()
                entries += Entry(String(directory, nameStart, nameLength, Charsets.UTF_8), directory.short(position + 10),
                    directory.short(position + 8), compressed, uncompressed, offset)
                position = nameStart + nameLength + extraLength + commentLength
            }
            return entries
        }

        private data class Directory(val count: Long, val size: Long, val offset: Long)

        /**
         * Reads the end record at [end] in [tail] ([position] in the file), following a ZIP64 locator when one precedes
         * it. Returns null for a candidate that cannot be the archive's own end record: its comment would run past the
         * file, or its directory does not end exactly where the end records begin or is too small for its entry count.
         */
        private fun endRecord(channel: FileChannel, tail: ByteArray, end: Int, position: Long): Directory? {
            if (end + END_SIZE + tail.short(end + 20) > tail.size) return null
            var count = tail.short(end + 10).toLong()
            var size = tail.uint(end + 12)
            var offset = tail.uint(end + 16)
            var recordsStart = position
            if (position >= ZIP64_LOCATOR_SIZE) {
                val locator = readFully(channel, position - ZIP64_LOCATOR_SIZE, ZIP64_LOCATOR_SIZE)
                if (locator.int(0) == ZIP64_LOCATOR_SIGNATURE) {
                    val record = locator.long(8)
                    if (record < 0 || record > position - ZIP64_LOCATOR_SIZE - ZIP64_END_SIZE) return null
                    val zip64 = readFully(channel, record, ZIP64_END_SIZE)
                    if (zip64.int(0) != ZIP64_END_SIGNATURE) return null
                    if (count == 0xffffL || size == MASK_32 || offset == MASK_32) {
                        count = zip64.long(32); size = zip64.long(40); offset = zip64.long(48)
                    }
                    recordsStart = record
                }
            }
            if (count < 0 || size < 0 || offset < 0 || size > recordsStart || offset != recordsStart - size) return null
            if (count > size / CENTRAL_HEADER_SIZE) return null
            return Directory(count, size, offset)
        }

        /** Returns the start of the ZIP64 extra field's data, or -1 when absent. */
        private fun zip64Extra(directory: ByteArray, start: Int, length: Int): Int {
            var position = start
            while (position + 4 <= start + length) {
                val id = directory.short(position)
                val size = directory.short(position + 2)
                if (id == 0x0001) return position + 4
                position += 4 + size
            }
            return -1
        }

        private fun readFully(channel: FileChannel, position: Long, length: Int): ByteArray {
            if (position < 0 || length < 0) throw corrupt()
            val bytes = ByteArray(length)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) throw corrupt()
            }
            return bytes
        }

        private fun corrupt() = PublicationException(PublicationProblem.CORRUPT, "This archive is damaged or incomplete.")
        private fun ByteArray.short(i: Int) = (this[i].toInt() and 0xff) or ((this[i + 1].toInt() and 0xff) shl 8)
        private fun ByteArray.int(i: Int) = short(i) or (short(i + 2) shl 16)
        private fun ByteArray.uint(i: Int) = int(i).toLong() and MASK_32
        private fun ByteArray.long(i: Int) = uint(i) or (uint(i + 4) shl 32)
    }
}

/** Bounded positional reads; never moves a shared file offset. */
private class RangeInputStream(private val channel: FileChannel, private var position: Long, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        if (len == 0) return 0
        val count = channel.read(ByteBuffer.wrap(b, off, minOf(len.toLong(), remaining).toInt()), position)
        if (count < 0) { remaining = 0; return -1 }
        position += count; remaining -= count
        return count
    }

    override fun skip(n: Long): Long {
        val skipped = n.coerceIn(0, remaining)
        position += skipped; remaining -= skipped
        return skipped
    }
}

/** Raw DEFLATE entry stream; releases the native inflater on close and supplies zlib's trailing dummy byte. */
private class EntryInflaterStream(input: InputStream) : InflaterInputStream(input, Inflater(true), 64 * 1024) {
    private var ended = false

    override fun fill() {
        if (ended) throw java.io.EOFException("Unexpected end of archive entry")
        len = `in`.read(buf, 0, buf.size)
        if (len == -1) { buf[0] = 0; len = 1; ended = true }
        inf.setInput(buf, 0, len)
    }

    override fun close() { try { super.close() } finally { inf.end() } }
}
