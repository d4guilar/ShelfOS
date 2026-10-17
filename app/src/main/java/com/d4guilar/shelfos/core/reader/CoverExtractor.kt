// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.domain.library.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.roundToInt

/** The artwork written to a temporary file by one extraction, with the LOCAL provenance to record (§23.1). */
data class ExtractedCover(
    val mimeType: String, val extension: String, val representation: CoverRepresentation,
    val sourceWidth: Int, val sourceHeight: Int, val storedWidth: Int, val storedHeight: Int, val bytes: Long,
    val localSource: CoverLocalSource, val sourceRef: String, val choice: CoverSourceChoice, val sourceRevision: String?,
)

/** An explained extraction outcome without artwork; [reason] decides permanence and retry (§23.3). */
class CoverExtractionFailure(val reason: CoverFailureReason, cause: Throwable? = null) : Exception(reason.name, cause)

/** Writes one publication's local cover into [output]. [admitTemporary] is the §18.1 guard for extra temporary bytes. */
fun interface CoverExtractor {
    /** Throws [CoverExtractionFailure] for an explained outcome; never modifies the source. */
    fun extract(item: LibraryItem, output: File, admitTemporary: (Long) -> Boolean): ExtractedCover
}

/**
 * Production extractor (Phase 4.5A.1). Reuses the reader's own infrastructure: [PublicationFiles.open] (read-only),
 * [ArchivePolicy.pages] / [RarContainer] (the ONE logical page order the reader also uses), [EmbeddedMetadataReader]
 * for the OPF and ComicInfo, and [FixedReaderFactory] for the PDF first page (white background, API 24/25 probe).
 * Exactly one page or image is read per publication. Bumping [VERSION] requalifies failed/parked rows (§23.3).
 */
internal class LocalCoverExtractor(
    private val files: PublicationFiles,
    private val fixedReaders: FixedReaderFactory,
    /** Test seam: injects a RAR session factory. Production defaults to the real [openRarArchiveSession]. */
    private val openSession: (ParcelFileDescriptor) -> RarArchiveSession = ::openRarArchiveSession,
) : CoverExtractor {
    override fun extract(item: LibraryItem, output: File, admitTemporary: (Long) -> Boolean): ExtractedCover = mapFailures {
        val revision = files.revision(item)
        when (item.format) {
            PublicationFormat.EPUB -> epub(item, output)
            PublicationFormat.CBZ -> cbz(item, output)
            PublicationFormat.CBR -> cbr(item, output, admitTemporary)
            PublicationFormat.PDF -> pdf(item, output)
        }.copy(sourceRevision = revision)
    }

    private fun epub(item: LibraryItem, output: File): ExtractedCover = files.open(item).use { descriptor ->
        ArchivePolicy.open(descriptor).use { zip ->
            ArchivePolicy.entries(zip)
            val cover = EmbeddedMetadataReader.epubCover(zip) ?: fail(CoverFailureReason.NO_SUITABLE_ARTWORK)
            // An SVG (or other non-raster) cover exists but cannot be rendered by this extractor: truthful, permanent.
            if (cover.mediaType != null && !cover.mediaType.startsWith("image/") || cover.mediaType == "image/svg+xml")
                fail(CoverFailureReason.UNSUPPORTED_REPRESENTATION)
            val entry = zip.entry(cover.path) ?: fail(CoverFailureReason.NO_SUITABLE_ARTWORK)
            if (entry.size > ArchivePolicy.MAX_IMAGE_BYTES) fail(CoverFailureReason.UNSUPPORTED_REPRESENTATION)
            CoverImageWriter.store({ zip.open(entry) }, output)
                .provenance(CoverLocalSource.EPUB_COVER, cover.path.take(MAX_REF), CoverSourceChoice.DECLARED)
        }
    }

    private fun cbz(item: LibraryItem, output: File): ExtractedCover = files.open(item).use { descriptor ->
        ArchivePolicy.open(descriptor).use { zip ->
            val pages = ArchivePolicy.pages(zip)
            val choice = chooseArchiveCover(EmbeddedMetadataReader.comicInfo(zip).frontCoverImage, pages.size)
                ?: fail(CoverFailureReason.NO_SUITABLE_ARTWORK)
            CoverImageWriter.store({ zip.open(pages[choice.pageIndex]) }, output).provenance(choice)
        }
    }

    /**
     * CBR: one native session, the container's own index, then exactly the chosen page. The page is materialized through
     * the shared extraction cache under [rarCacheSourceKey] (the reader's own key), so a page the reader already
     * extracted from the same immutable private copy is reused only when it is exactly the chosen physical page;
     * a linked source gets an ephemeral namespace (no reuse). The cache lease is released right after the copy.
     */
    private fun cbr(item: LibraryItem, output: File, admitTemporary: (Long) -> Boolean): ExtractedCover = files.open(item).use { descriptor ->
        val container = RarContainer.open(openSession(descriptor), files.rarCacheRoot, item.rarCacheSourceKey())
        try {
            // ComicInfo.xml is materialized too; its declared size is not exposed, so the fixed reserve covers it.
            if (!admitTemporary(CoverStoragePolicy.UNKNOWN_TEMP_RESERVE_BYTES)) fail(CoverFailureReason.STORAGE_FULL)
            val choice = chooseArchiveCover(container.comicInfo()?.frontCoverImage, container.pageCount)
                ?: fail(CoverFailureReason.NO_SUITABLE_ARTWORK)
            val need = CoverStoragePolicy.temporaryNeed(container.declaredPageBytes(choice.pageIndex), RarExtractionCache.MAX_ENTRY_BYTES)
            if (!admitTemporary(need)) fail(CoverFailureReason.STORAGE_FULL)
            val extraction = container.extractPage(choice.pageIndex)
            try { CoverImageWriter.store({ FileInputStream(extraction.file) }, output).provenance(choice) }
            finally { extraction.release() }
        } finally { container.close() }
    }

    /** A bounded first-page render (D12): ≈1200 px longest edge, white background, DERIVED, never "original artwork". */
    private fun pdf(item: LibraryItem, output: File): ExtractedCover = fixedReaders.open(item).use { reader ->
        if (reader.pageCount <= 0) fail(CoverFailureReason.NO_SUITABLE_ARTWORK)
        val page = reader.pageGeometry(0)
        val bitmap = reader.render(0, PageRenderRequest(maxDimension = CoverStoragePolicy.MAX_EDGE_PX))
        try {
            // PDF pages have no pixel size; the recorded source dimensions are the page size in points.
            CoverImageWriter.storeDerived(bitmap, page?.width ?: bitmap.width, page?.height ?: bitmap.height, output)
                .provenance(CoverLocalSource.PDF_FIRST_PAGE, "page:0", CoverSourceChoice.INFERRED)
        } finally { bitmap.recycle() }
    }

    private fun StoredImage.provenance(choice: ArchiveCoverChoice) = provenance(choice.source, "page:${choice.pageIndex}", choice.choice)
    private fun StoredImage.provenance(source: CoverLocalSource, ref: String, choice: CoverSourceChoice) = ExtractedCover(
        mimeType, extension, representation, sourceWidth, sourceHeight, storedWidth, storedHeight, bytes, source, ref, choice, null)

    companion object {
        const val VERSION = 1
        private const val MAX_REF = 300
    }
}

internal fun fail(reason: CoverFailureReason): Nothing = throw CoverExtractionFailure(reason)

/** Maps reader/platform failures to the §23.3 vocabulary; anything unexplained propagates to the scheduler. */
private inline fun <T> mapFailures(block: () -> T): T = try { block() } catch (e: CoverExtractionFailure) { throw e }
    catch (e: PublicationException) {
        throw CoverExtractionFailure(when (e.problem) {
            PublicationProblem.SOURCE_UNAVAILABLE -> CoverFailureReason.SOURCE_UNAVAILABLE
            PublicationProblem.PERMISSION_LOST -> CoverFailureReason.PERMISSION_UNAVAILABLE
            PublicationProblem.EMPTY_ARCHIVE -> CoverFailureReason.NO_SUITABLE_ARTWORK
            PublicationProblem.CORRUPT -> CoverFailureReason.UNDECODABLE_CONTENT
            PublicationProblem.PROTECTED, PublicationProblem.TOO_LARGE, PublicationProblem.NEEDS_COPY,
            PublicationProblem.UNSUPPORTED_FORMAT, PublicationProblem.UNSUPPORTED_LAYOUT -> CoverFailureReason.UNSUPPORTED_REPRESENTATION
            PublicationProblem.INSUFFICIENT_STORAGE -> CoverFailureReason.STORAGE_FULL
            else -> CoverFailureReason.IO_ERROR
        }, e)
    } catch (e: SecurityException) { throw CoverExtractionFailure(CoverFailureReason.PERMISSION_UNAVAILABLE, e)
    } catch (e: OutOfMemoryError) { throw CoverExtractionFailure(CoverFailureReason.RESOURCE_EXHAUSTED, e)
    } catch (e: IOException) {
        throw CoverExtractionFailure(if (e.message.orEmpty().contains("ENOSPC")) CoverFailureReason.STORAGE_FULL else CoverFailureReason.IO_ERROR, e)
    }

internal data class StoredImage(val mimeType: String, val extension: String, val representation: CoverRepresentation,
    val sourceWidth: Int, val sourceHeight: Int, val storedWidth: Int, val storedHeight: Int, val bytes: Long)

/**
 * The §7 rule 1 storage bound: byte-exact ORIGINAL when small and within the edge/pixel limits, otherwise ONE sampled
 * decode and the fixed JPEG ladder (alpha composited on white), never above [CoverStoragePolicy.MAX_COVER_BYTES].
 */
internal object CoverImageWriter {
    private val ORIGINAL_TYPES = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/bmp" to "bmp")

    /**
     * [orientation] is an EXIF orientation (1-8). Anything but normal skips the byte-exact copy (which would keep the
     * tag, and the renderer does not read EXIF) and stores upright DERIVED pixels from the same bounded sampled decode.
     * LOCAL extraction never passes it, so its behavior is unchanged.
     */
    fun store(open: () -> InputStream, output: File, orientation: Int = 1): StoredImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth; val height = bounds.outHeight
        if (width <= 0 || height <= 0) fail(CoverFailureReason.UNDECODABLE_CONTENT)
        if (width.toLong() * height > CoverStoragePolicy.MAX_INPUT_PIXELS) fail(CoverFailureReason.UNSUPPORTED_REPRESENTATION)
        val extension = ORIGINAL_TYPES[bounds.outMimeType]
        if (orientation !in 2..8 && extension != null && maxOf(width, height) <= CoverStoragePolicy.MAX_EDGE_PX) {
            val bytes = open().use { readAtMost(it, CoverStoragePolicy.MAX_COVER_BYTES + 1) }
            if (CoverStoragePolicy.storesOriginal(bytes.size.toLong(), width, height)) {
                write(output, bytes)
                if (!decodes(output)) { output.delete(); fail(CoverFailureReason.UNDECODABLE_CONTENT) }
                return StoredImage(bounds.outMimeType, extension, CoverRepresentation.ORIGINAL, width, height, width, height, bytes.size.toLong())
            }
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = CoverStoragePolicy.sampleSize(width, height, CoverStoragePolicy.MAX_EDGE_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: fail(CoverFailureReason.UNDECODABLE_CONTENT)
        try {
            if (orientation !in 2..8) return storeDerived(decoded, width, height, output)
            val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, orientationMatrix(orientation), true)
            try {
                val swapped = orientation >= 5   // the transposing orientations exchange width and height
                return storeDerived(upright, if (swapped) height else width, if (swapped) width else height, output)
            } finally { if (upright !== decoded) upright.recycle() }
        } finally { decoded.recycle() }
    }

    /** The EXIF orientation (1-8) of a staged JPEG; absent, undefined or unreadable metadata is normal (1). */
    // The platform class is enough for orientation on API 24+ (a staged local file); androidx.exifinterface would be a new dependency.
    @Suppress("ExifInterface")
    fun exifOrientation(file: File): Int = try {
        android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1).takeIf { it in 1..8 } ?: 1
    } catch (_: Exception) { 1 }

    private fun orientationMatrix(orientation: Int) = Matrix().apply {
        when (orientation) {
            2 -> postScale(-1f, 1f)
            3 -> postRotate(180f)
            4 -> postScale(1f, -1f)
            5 -> { postRotate(90f); postScale(-1f, 1f) }   // transpose: top-left stays top-left
            6 -> postRotate(90f)
            7 -> { postRotate(270f); postScale(-1f, 1f) }  // transverse: top-left goes to bottom-right
            8 -> postRotate(270f)
        }
    }

    /** Encodes [bitmap] through the fixed ladder; [sourceWidth]x[sourceHeight] are the artwork's native dimensions. */
    fun storeDerived(bitmap: Bitmap, sourceWidth: Int, sourceHeight: Int, output: File): StoredImage {
        var lastEdge = -1
        var scaled: Bitmap? = null
        try {
            for ((edge, quality) in CoverStoragePolicy.LADDER) {
                if (edge != lastEdge) { scaled?.recycle(); scaled = opaqueScaled(bitmap, edge); lastEdge = edge }
                val image = scaled!!
                val encoded = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (encoded.size() <= CoverStoragePolicy.MAX_COVER_BYTES) {
                    write(output, encoded.toByteArray())
                    return StoredImage("image/jpeg", "jpg", CoverRepresentation.DERIVED, sourceWidth, sourceHeight,
                        image.width, image.height, encoded.size().toLong())
                }
            }
        } finally { scaled?.recycle() }
        fail(CoverFailureReason.UNSUPPORTED_REPRESENTATION)
    }

    /** Aspect-preserving, never upscaled, alpha composited on opaque white so the output can be JPEG. */
    private fun opaqueScaled(source: Bitmap, edge: Int): Bitmap {
        val scale = minOf(1.0, edge.toDouble() / maxOf(source.width, source.height))
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val out = createBitmap(w, h)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(source, Rect(0, 0, source.width, source.height), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    private fun decodes(file: File): Boolean {
        val probe = BitmapFactory.Options().apply { inSampleSize = 16 }
        return BitmapFactory.decodeFile(file.path, probe)?.also { it.recycle() } != null
    }

    private fun readAtMost(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - out.size()).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun write(output: File, bytes: ByteArray) {
        FileOutputStream(output).use { it.write(bytes); it.fd.sync() }
    }
}
