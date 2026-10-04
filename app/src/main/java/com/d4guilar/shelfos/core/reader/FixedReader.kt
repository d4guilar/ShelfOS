// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.domain.library.*
import java.io.Closeable
import java.io.InputStream

/**
 * A page render request: the actual presentation need (viewport size, a safety ceiling) rather than a single
 * format-specific decode parameter. [viewportWidth]/[viewportHeight] describe the space the page will actually be
 * displayed in (post-fit, at 1x) -- asking for a larger viewport than the publication's current on-screen size is
 * how a future caller requests more detail (a higher-resolution zoom re-render), and a smaller one is how a future
 * caller requests less (a thumbnail). [maxDimension] is a per-request ceiling the caller may tighten below the
 * absolute safety limit (e.g. a future thumbnail strip asking for at most 200px regardless of layout); it can
 * never raise the result above [SAFE_MAX_DIMENSION].
 *
 * Deliberately format- and Compose-free: [com.d4guilar.shelfos.feature.reader.FixedReaderScreen] translates its
 * own measured pixel size into this before calling [FixedReader.render]; no core reader type here depends on
 * `androidx.compose`. This slice (Phase 3A) only ever constructs a request from the reader's own display viewport
 * at page-open/page-turn time -- it does not wire a live pinch-zoom-triggered re-render or a thumbnail UI; see
 * `docs/PHASE_3_IMPLEMENTATION_PLAN.md` for what remains for later slices.
 */
data class PageRenderRequest(val viewportWidth: Int = 0, val viewportHeight: Int = 0, val maxDimension: Int = SAFE_MAX_DIMENSION) {
    companion object {
        /** Longest-edge target used when no real viewport is known yet (e.g. before Compose has measured the page
         * surface). Equal to Phase 1/2's flat `MAX_PAGE_PIXELS`, preserved as the pre-layout fallback so default/
         * early rendering is byte-for-byte unchanged from before this contract existed. */
        const val DEFAULT_MAX_DIMENSION = 2048

        /** Absolute per-bitmap longest-edge ceiling. No request, however large its viewport or [maxDimension], can
         * cause a decode/rasterization past this -- the hard memory-safety bound. At `ARGB_8888`, a
         * [SAFE_MAX_DIMENSION] square bitmap is ~64MB; only the single currently-displayed page ever holds a
         * bitmap this large (no multi-page cache, no spread rendering in this slice). */
        const val SAFE_MAX_DIMENSION = 4096

        /** The pre-layout fallback request: an unknown (zero) viewport resolves to [DEFAULT_MAX_DIMENSION] below. */
        val DEFAULT = PageRenderRequest()
    }
}

/**
 * The longest-edge resolution a [PageRenderRequest] should decode/rasterize at: driven by the caller's actual
 * viewport when known, falling back to [PageRenderRequest.DEFAULT_MAX_DIMENSION] otherwise, and always bounded by
 * both the request's own [PageRenderRequest.maxDimension] and the absolute [PageRenderRequest.SAFE_MAX_DIMENSION].
 * Pure and deterministic: the same request always yields the same target.
 *
 * Defensive: a non-positive viewport axis or [PageRenderRequest.maxDimension] is treated as "not specified" rather
 * than propagated (there is nothing sensible to size against before real layout), so this never throws and always
 * returns a value in `1..SAFE_MAX_DIMENSION`. Large but valid `Int` inputs (e.g. a malformed `Int.MAX_VALUE`
 * viewport) are clamped by the same `coerceIn`, never overflowed.
 */
fun resolveRenderTargetLongestEdge(request: PageRenderRequest): Int {
    val requestedEdge = maxOf(request.viewportWidth, request.viewportHeight)
    val base = if (requestedEdge > 0) requestedEdge else PageRenderRequest.DEFAULT_MAX_DIMENSION
    val ceiling = (if (request.maxDimension > 0) request.maxDimension else PageRenderRequest.DEFAULT_MAX_DIMENSION)
        .coerceAtMost(PageRenderRequest.SAFE_MAX_DIMENSION)
    return base.coerceIn(1, ceiling)
}

/**
 * Original-page reading session. Page identity is the stored sequence, independent of reading direction. [render]
 * accepts a [PageRenderRequest] describing the actual presentation need (defaulting to the pre-layout fallback
 * resolution) rather than a single fixed resolution -- see [PageRenderRequest] and [resolveRenderTargetLongestEdge].
 */
interface FixedReader : Closeable {
    val pageCount: Int
    fun render(index: Int, request: PageRenderRequest = PageRenderRequest.DEFAULT): Bitmap
}

class FixedReaderFactory(private val files: PublicationFiles) {
    fun open(item: LibraryItem): FixedReader {
        val descriptor = files.open(item)
        try {
            return when (item.format) {
                PublicationFormat.PDF -> PdfPages(descriptor)
                PublicationFormat.CBZ -> ArchivePages(descriptor)
                PublicationFormat.EPUB -> throw PublicationException(PublicationProblem.UNSUPPORTED_FORMAT, PublicationExceptionDetail.USE_EPUB_READER_INSTEAD)
            }
        } catch (error: Throwable) { descriptor.close(); throw error }
    }
}

private class PdfPages(descriptor: ParcelFileDescriptor) : FixedReader {
    private val renderer = openPdf(descriptor)
    override val pageCount get() = renderer.pageCount
    override fun render(index: Int, request: PageRenderRequest): Bitmap = renderer.openPage(index).use { page ->
        val targetLongestEdge = resolveRenderTargetLongestEdge(request)
        val scale = targetLongestEdge.toFloat() / maxOf(page.width, page.height)
        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try { page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap }
        catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    override fun close() = renderer.close()
}

/**
 * One page's bytes from a paged image-sequence container, independent of the container's archive format. A
 * container need not support random access to satisfy this contract: [openPage] is indexed by logical page
 * position, but whether (or how efficiently) an implementation serves pages out of order is entirely its own
 * concern. [ArchivePageSource] is backed by true random access (`SeekableZip`'s positional reads); a future CBR
 * adapter over a "solid" RAR archive that cannot offer ZIP-style random access could instead serve this from a
 * one-time sequential index or a bounded extract-to-cache, without this interface -- or [ImagePageRenderer], which
 * is written only against it -- changing at all. This is the Phase 3A container/page-source boundary; no CBR
 * adapter exists yet (see `docs/PHASE_3_IMPLEMENTATION_PLAN.md` section 6/12).
 */
private interface PageSource : Closeable {
    val pageCount: Int
    fun openPage(index: Int): InputStream
}

/** [PageSource] backed by [SeekableZip]'s true random access; today's only container implementation. */
private class ArchivePageSource(private val zip: SeekableZip, private val entries: List<SeekableZip.Entry>) : PageSource {
    override val pageCount get() = entries.size
    override fun openPage(index: Int): InputStream = zip.open(entries[index])
    override fun close() = zip.close()
}

/**
 * Decodes one page image from any [PageSource] at a resolution driven by a [PageRenderRequest], bounded by the
 * same safety ceilings regardless of container format. Used by [ArchivePages] today; a future CBR [PageSource]
 * reuses this unchanged, rather than duplicating the bounds-then-sample decode policy per container format.
 */
private object ImagePageRenderer {
    fun render(source: PageSource, index: Int, request: PageRenderRequest): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.openPage(index).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000)
            throw PublicationException(PublicationProblem.CORRUPT, PublicationExceptionDetail.PAGE_IMAGE_DAMAGED_OR_TOO_LARGE)
        val targetLongestEdge = resolveRenderTargetLongestEdge(request)
        var sample = 1
        // sample only ever increases from 1, so a source already at or below the target decodes at its own
        // native resolution -- never upscaled, however large the request's viewport/maxDimension is.
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetLongestEdge) sample *= 2
        return source.openPage(index).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw PublicationException(PublicationProblem.CORRUPT, PublicationExceptionDetail.PAGE_IMAGE_DECODE_FAILED)
    }
}

/** Streams one page at a time from the archive; nothing is extracted to storage. */
private class ArchivePages(private val descriptor: ParcelFileDescriptor) : FixedReader {
    private val zip = ArchivePolicy.open(descriptor)
    private val entries = try { ArchivePolicy.pages(zip) } catch (e: Throwable) { zip.close(); throw e }
    private val source: PageSource = ArchivePageSource(zip, entries)
    override val pageCount get() = entries.size
    override fun render(index: Int, request: PageRenderRequest): Bitmap = ImagePageRenderer.render(source, index, request)
    override fun close() { try { zip.close() } finally { descriptor.close() } }
}
