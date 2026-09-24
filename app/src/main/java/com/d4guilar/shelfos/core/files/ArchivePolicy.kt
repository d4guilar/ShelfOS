// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class PublicationException(message: String) : IOException(message)
class CopyRequired : IOException("This provider needs a private local copy for reliable offline access.")

object ArchivePolicy {
    const val MAX_ENTRIES = 100_000
    const val MAX_IMAGE_BYTES = 128L * 1024 * 1024
    private val images = setOf("jpg", "jpeg", "png", "webp")
    fun isImage(name: String) = name.substringAfterLast('.').lowercase() in images &&
        !name.startsWith("__MACOSX/") && !name.substringAfterLast('/').startsWith('.')
    fun safeName(name: String) = !name.startsWith('/') && !name.contains('\\') &&
        !name.contains(':') && name.split('/').none { it == ".." }

    fun entries(zip: ZipFile): List<ZipEntry> {
        val result = ArrayList<ZipEntry>()
        var expanded = 0L
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            if (result.size >= MAX_ENTRIES) throw PublicationException("The archive has too many entries.")
            val entry = enumeration.nextElement()
            if (!safeName(entry.name)) throw PublicationException("The archive contains an unsafe entry path.")
            if (entry.size < 0 || entry.size > 8L * 1024 * 1024 * 1024) throw PublicationException("Unsupported archive entry size.")
            expanded += entry.size
            if (expanded > 64L * 1024 * 1024 * 1024 ||
                entry.size > 1024 * 1024 && entry.size / entry.compressedSize.coerceAtLeast(1) > 1000)
                throw PublicationException("The archive expands beyond safe limits.")
            result.add(entry)
        }
        return result
    }

    fun pages(zip: ZipFile): List<ZipEntry> = entries(zip).filter { !it.isDirectory && isImage(it.name) }
        .also { pages ->
            if (pages.isEmpty()) throw PublicationException("The archive contains no supported image pages.")
            if (pages.any { it.size > MAX_IMAGE_BYTES }) throw PublicationException("An image page exceeds the supported size.")
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
