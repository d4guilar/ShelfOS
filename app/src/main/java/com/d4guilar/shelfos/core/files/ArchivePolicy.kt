// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.os.ParcelFileDescriptor
import com.d4guilar.shelfos.domain.importing.isPageImage
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationProblem

object ArchivePolicy {
    const val MAX_ENTRIES = 100_000
    const val MAX_IMAGE_BYTES = 128L * 1024 * 1024
    fun safeName(name: String) = !name.startsWith('/') && !name.contains('\\') &&
        !name.contains(':') && name.split('/').none { it == ".." }

    /**
     * Reads the archive through a duplicate of the granted descriptor using positional reads; the caller
     * keeps ownership of [descriptor]. Works for provider-backed shared storage that cannot be reopened by path.
     */
    fun open(descriptor: ParcelFileDescriptor): SeekableZip =
        SeekableZip.open(ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).channel)

    fun entries(zip: SeekableZip): List<SeekableZip.Entry> {
        var expanded = 0L
        return zip.entries.onEach { entry ->
            if (!safeName(entry.name)) throw PublicationException(PublicationProblem.CORRUPT, "The archive contains an unsafe entry path.")
            if (entry.size > 8L * 1024 * 1024 * 1024)
                throw PublicationException(PublicationProblem.TOO_LARGE, "The archive contains an unsupported entry size.")
            expanded += entry.size
            if (expanded > 64L * 1024 * 1024 * 1024 ||
                entry.size > 1024 * 1024 && entry.size / entry.compressedSize.coerceAtLeast(1) > 1000)
                throw PublicationException(PublicationProblem.TOO_LARGE, "The archive expands beyond safe limits.")
        }
    }

    fun pages(zip: SeekableZip): List<SeekableZip.Entry> = entries(zip).filter { !it.isDirectory && isPageImage(it.name) }
        .also { pages ->
            if (pages.isEmpty()) throw PublicationException(PublicationProblem.EMPTY_ARCHIVE)
            if (pages.any { it.encrypted }) throw PublicationException(PublicationProblem.PROTECTED)
            if (pages.any { it.size > MAX_IMAGE_BYTES }) throw PublicationException(PublicationProblem.TOO_LARGE, "An image page exceeds the supported size.")
        }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
}

/** Numeric comparison without Int/Long overflow; deterministic for equal numeric runs. */
fun naturalCompare(a: String, b: String): Int {
    val parts = Regex("[0-9]+|[^0-9]+")
    val left = parts.findAll(a).map { it.value }.toList()
    val right = parts.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]; val y = right[i]
        val comparison = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
        } else x.compareTo(y, ignoreCase = true)
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
}
