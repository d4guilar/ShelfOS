// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.PageRenderRequest
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

/**
 * Phase 3A: real decode/rasterization evidence for the new [PageRenderRequest] contract that replaces the old flat
 * `render(index): Bitmap` / `MAX_PAGE_PIXELS = 2048` ceiling. Uses [FixedReaderFactory] directly against real
 * fixtures (same pattern as `LibraryPersistenceTest.pdfAndArchiveRenderAndArchiveUsesNaturalPageOrder`) rather than
 * driving the Compose UI, since this is a decode-layer contract, not a presentation one. `BitmapFactory`/
 * `PdfRenderer` need a real Android runtime, so this cannot be a local JVM test -- see `PageRenderRequestTest` for
 * the pure request-resolution math.
 */
class FixedReaderRenderRequestTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val factory get() = FixedReaderFactory(PublicationFiles(context))

    private fun cbzFixture(name: String, width: Int, height: Int, color: Int = Color.RED): LibraryItem {
        val file = File(context.filesDir, "publications/$name").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("page1.png"))
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle(); zip.closeEntry()
        }
        return LibraryItem(name, "RenderRequest $name", "ShelfOS test", MediaCategory.BOOK, "test:$name",
            PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path)
    }

    private fun pdfFixture(name: String, width: Int, height: Int): LibraryItem {
        val file = File(context.filesDir, "publications/$name").also { it.parentFile!!.mkdirs() }
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, 1).create())
            page.canvas.drawColor(Color.WHITE)
            pdf.finishPage(page)
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        return LibraryItem(name, "RenderRequest $name", "ShelfOS test", MediaCategory.BOOK, "test:$name",
            PublicationFormat.PDF, file.name, file.length(), managedPath = file.path)
    }

    private fun longestEdge(bitmap: Bitmap) = max(bitmap.width, bitmap.height)

    // ---- CBZ: a high-resolution synthetic page exercising sampling at several request sizes ----

    @Test fun cbzDefaultRequestPreservesTheOldTwoThousandFortyEightCap() {
        val item = cbzFixture("render-cbz-default.cbz", 6000, 4000)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0)
            assertTrue(longestEdge(bitmap) <= 2048)
            bitmap.recycle()
        }
    }

    @Test fun cbzLargerViewportRequestExceedsTheOldCapOnAHighResolutionSource() {
        val item = cbzFixture("render-cbz-large.cbz", 6000, 4000)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 3000, viewportHeight = 3000))
            assertTrue("expected > 2048, got ${longestEdge(bitmap)}", longestEdge(bitmap) > 2048)
            assertTrue(longestEdge(bitmap) <= 3000)
            bitmap.recycle()
        }
    }

    @Test fun cbzThumbnailLikeRequestProducesAMuchSmallerBitmap() {
        val item = cbzFixture("render-cbz-thumb.cbz", 6000, 4000)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 200, viewportHeight = 200, maxDimension = 200))
            assertTrue(longestEdge(bitmap) <= 200)
            bitmap.recycle()
        }
    }

    @Test fun cbzOversizedRequestIsBoundedBySafeMaximumNotRequestedViewport() {
        val item = cbzFixture("render-cbz-oversized.cbz", 6000, 4000)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 20_000, viewportHeight = 20_000))
            assertTrue(longestEdge(bitmap) <= PageRenderRequest.SAFE_MAX_DIMENSION)
            bitmap.recycle()
        }
    }

    @Test fun cbzSourceSmallerThanRequestedTargetIsNeverUpscaled() {
        val item = cbzFixture("render-cbz-small-source.cbz", 100, 150)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 3000, viewportHeight = 3000))
            // The source is only 100x150: a larger request must not synthesize extra pixels.
            assertEquals(100, bitmap.width)
            assertEquals(150, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test fun cbzMalformedImagePageStillFailsGracefullyWithANewStyleRequest() {
        val file = File(context.filesDir, "publications/render-cbz-malformed.cbz").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("page1.jpg")); zip.write("not actually an image".toByteArray()); zip.closeEntry()
        }
        val item = LibraryItem("render-cbz-malformed", "Malformed", "ShelfOS test", MediaCategory.BOOK,
            "test:render-cbz-malformed", PublicationFormat.CBZ, file.name, file.length(), managedPath = file.path)
        factory.open(item).use { reader ->
            val error = try { reader.render(0, PageRenderRequest(viewportWidth = 2560, viewportHeight = 1600)); null }
            catch (e: com.d4guilar.shelfos.domain.library.PublicationException) { e }
            assertNotNull(error)
        }
    }

    // ---- PDF: request-aware rasterization dimensions ----

    @Test fun pdfDefaultRequestPreservesTheOldTwoThousandFortyEightCap() {
        val item = pdfFixture("render-pdf-default.pdf", 400, 600)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0)
            assertTrue(longestEdge(bitmap) <= 2048)
            bitmap.recycle()
        }
    }

    @Test fun pdfLargerViewportRequestExceedsTheOldCap() {
        val item = pdfFixture("render-pdf-large.pdf", 400, 600)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 2200, viewportHeight = 3300))
            assertTrue("expected > 2048, got ${longestEdge(bitmap)}", longestEdge(bitmap) > 2048)
            bitmap.recycle()
        }
    }

    @Test fun pdfOversizedRequestIsBoundedBySafeMaximum() {
        val item = pdfFixture("render-pdf-oversized.pdf", 400, 600)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 50_000, viewportHeight = 50_000))
            assertTrue(longestEdge(bitmap) <= PageRenderRequest.SAFE_MAX_DIMENSION)
            bitmap.recycle()
        }
    }

    @Test fun pdfPageOrderingIsUnaffectedByTheRequestShapeChange() {
        val file = File(context.filesDir, "publications/render-pdf-order.pdf").also { it.parentFile!!.mkdirs() }
        val pdf = PdfDocument()
        try {
            listOf(Color.RED, Color.GREEN, Color.BLUE).forEachIndexed { index, color ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(200, 300, index + 1).create())
                page.canvas.drawColor(color)
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val item = LibraryItem("render-pdf-order", "Order", "ShelfOS test", MediaCategory.BOOK,
            "test:render-pdf-order", PublicationFormat.PDF, file.name, file.length(), managedPath = file.path)
        factory.open(item).use { reader ->
            assertEquals(3, reader.pageCount)
            val first = reader.render(0, PageRenderRequest(viewportWidth = 1000, viewportHeight = 1000))
            assertEquals(Color.RED, first.getPixel(20, 20)); first.recycle()
            val last = reader.render(2, PageRenderRequest(viewportWidth = 1000, viewportHeight = 1000))
            assertEquals(Color.BLUE, last.getPixel(20, 20)); last.recycle()
        }
    }
}
