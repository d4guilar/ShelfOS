// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.domain.library.*
import java.io.Closeable

/** Original-page reading session. Page identity is the stored sequence, independent of reading direction. */
interface FixedReader : Closeable {
    val pageCount: Int
    fun render(index: Int): Bitmap
}

class FixedReaderFactory(private val files: PublicationFiles) {
    fun open(item: LibraryItem): FixedReader {
        val descriptor = files.open(item)
        try {
            return when (item.format) {
                PublicationFormat.PDF -> PdfPages(descriptor)
                PublicationFormat.CBZ -> ArchivePages(descriptor)
                PublicationFormat.EPUB -> throw PublicationException(PublicationProblem.UNSUPPORTED_FORMAT, "Use the EPUB reader for this publication.")
            }
        } catch (error: Throwable) { descriptor.close(); throw error }
    }
}

private class PdfPages(descriptor: ParcelFileDescriptor) : FixedReader {
    private val renderer = openPdf(descriptor)
    override val pageCount get() = renderer.pageCount
    override fun render(index: Int): Bitmap = renderer.openPage(index).use { page ->
        val scale = MAX_PAGE_PIXELS.toFloat() / maxOf(page.width, page.height)
        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try { page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap }
        catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    override fun close() = renderer.close()
}

/** Streams one page at a time from the archive; nothing is extracted to storage. */
private class ArchivePages(private val descriptor: ParcelFileDescriptor) : FixedReader {
    private val zip = ArchivePolicy.open(descriptor)
    private val entries = try { ArchivePolicy.pages(zip) } catch (e: Throwable) { zip.close(); throw e }
    override val pageCount get() = entries.size
    override fun render(index: Int): Bitmap {
        val entry = entries[index]
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.open(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000)
            throw PublicationException(PublicationProblem.CORRUPT, "This page image is damaged or exceeds supported dimensions.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PAGE_PIXELS) sample *= 2
        return zip.open(entry).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw PublicationException(PublicationProblem.CORRUPT, "This page image could not be decoded.")
    }
    override fun close() { try { zip.close() } finally { descriptor.close() } }
}

/** Longest rendered edge; bounds each page bitmap independently of the publication's size. */
private const val MAX_PAGE_PIXELS = 2048
