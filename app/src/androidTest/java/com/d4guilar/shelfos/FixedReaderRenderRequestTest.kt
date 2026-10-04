// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.PageRenderRequest
import com.d4guilar.shelfos.core.reader.RenderMemoryPolicy
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
 * Real decode/rasterization evidence for the [PageRenderRequest] contract, including the Codex R1 remediation of
 * Phase 3A (`docs/PHASE_3_IMPLEMENTATION_PLAN.md` §22, `docs/VALIDATION.md`'s "PHASE 3A" entry): the
 * reading-quality floor (finding 1), Fit Width's aspect-aware resolution (finding 2), and the explicit
 * byte-budget peak-memory policy (finding 3), on top of Phase 3A's original viewport-aware/no-upscale/safety-
 * ceiling coverage. Uses [FixedReaderFactory] directly against real fixtures (same pattern as
 * `LibraryPersistenceTest.pdfAndArchiveRenderAndArchiveUsesNaturalPageOrder`) rather than driving the Compose UI,
 * since this is a decode-layer contract, not a presentation one -- and, incidentally, this direct-factory pattern
 * does not touch Espresso's gesture/key event injection, so it is unaffected by the `api37` AVD's
 * `InputManager` incompatibility recorded in `docs/VALIDATION.md`. `BitmapFactory`/`PdfRenderer` need a real
 * Android runtime, so this cannot be a local JVM test -- see `PageRenderRequestTest` for the pure
 * request-resolution math.
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
    private fun estimatedBytes(bitmap: Bitmap) = bitmap.width.toLong() * bitmap.height.toLong() * 4L

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
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 3000, viewportHeight = 3000, fit = FitMode.PAGE))
            assertTrue("expected > 2048, got ${longestEdge(bitmap)}", longestEdge(bitmap) > 2048)
            assertTrue(longestEdge(bitmap) <= 3000)
            bitmap.recycle()
        }
    }

    @Test fun cbzThumbnailLikeRequestProducesAMuchSmallerBitmap() {
        val item = cbzFixture("render-cbz-thumb.cbz", 6000, 4000)
        factory.open(item).use { reader ->
            // A deliberately tightened maxDimension opts out of the reading floor (Codex R1 finding 1), exactly
            // as a future thumbnail-strip request would.
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 200, viewportHeight = 200, maxDimension = 200))
            assertTrue(longestEdge(bitmap) <= 200)
            bitmap.recycle()
        }
    }

    @Test fun cbzOversizedRequestIsBoundedBySafeMaximumNotRequestedViewport() {
        val item = cbzFixture("render-cbz-oversized.cbz", 6000, 4000)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 20_000, viewportHeight = 20_000, fit = FitMode.PAGE))
            assertTrue(longestEdge(bitmap) <= PageRenderRequest.SAFE_MAX_DIMENSION)
            // Codex R1 finding 3: bounded by the explicit byte budget too, not just the per-axis ceiling.
            assertTrue("expected <= ${RenderMemoryPolicy.MAX_BITMAP_BYTES} bytes, got ${estimatedBytes(bitmap)}",
                estimatedBytes(bitmap) <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
            bitmap.recycle()
        }
    }

    @Test fun cbzSquareOversizedRequestIsBoundedByByteBudgetBelowTheFullSafeMaxSquare() {
        // The exact "no concrete justification" case Codex flagged: a square source/viewport whose naive
        // longest-edge-only target would be a full 4096x4096 (~64MiB) square. The real decode must come in
        // meaningfully smaller than that square, within the explicit byte budget.
        val item = cbzFixture("render-cbz-square-oversized.cbz", 10_000, 10_000)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 20_000, viewportHeight = 20_000, fit = FitMode.PAGE))
            assertTrue("expected the byte budget to bind below the full safe-max square, got ${bitmap.width}x${bitmap.height}",
                longestEdge(bitmap) < PageRenderRequest.SAFE_MAX_DIMENSION)
            assertTrue(estimatedBytes(bitmap) <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
            bitmap.recycle()
        }
    }

    @Test fun cbzSourceSmallerThanRequestedTargetIsNeverUpscaled() {
        val item = cbzFixture("render-cbz-small-source.cbz", 100, 150)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 3000, viewportHeight = 3000, fit = FitMode.PAGE))
            // The source is only 100x150: a larger request must not synthesize extra pixels.
            assertEquals(100, bitmap.width)
            assertEquals(150, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test fun cbzRequestAtExactlyTheSamplingTransitionDecodesAtNativeResolution() {
        // A 3000x2000 source (longest edge 3000, safely under SAFE_MAX_DIMENSION so sample=1 is reachable at
        // all) with maxDimension=3000 exactly: the resolved target longest edge lands exactly on native
        // resolution, so BitmapFactory's sample loop (`while (native/sample > target) sample *= 2`) must stay at
        // sample=1 -- `3000/1 == 3000` is not `> 3000`, so it never doubles.
        val item = cbzFixture("render-cbz-at-transition.cbz", 3000, 2000)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 3000, viewportHeight = 3000, fit = FitMode.PAGE, maxDimension = 3000))
            assertEquals(3000, bitmap.width)
            assertEquals(2000, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test fun cbzRequestOnePixelBelowTheSamplingTransitionDropsAFullPowerOfTwoStep() {
        // The same source with maxDimension=2999 -- one pixel below the previous test -- can no longer decode at
        // native resolution under BitmapFactory's power-of-two-only sampling steps, so it must drop a full step
        // to inSampleSize=2 (half resolution, 1500x1000) rather than landing anywhere in between.
        val item = cbzFixture("render-cbz-below-transition.cbz", 3000, 2000)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 3000, viewportHeight = 3000, fit = FitMode.PAGE, maxDimension = 2999))
            assertEquals(1500, bitmap.width)
            assertEquals(1000, bitmap.height)
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

    // ---- CBZ / PDF: Fit Width is aspect-aware (Codex R1 finding 2) ----

    @Test fun cbzFitWidthOnATallPageIsNotSizedFromRawViewportLongestEdgeAlone() {
        // A 1:6 tall page (1200x7200) in a 1920x1080 landscape viewport. The pre-remediation defect sized this
        // off the viewport's own longest edge (1920, independent of fit mode or source aspect) against the
        // source's longest edge (7200): `inSampleSize` would land on 4 (7200/4=1800 <= 1920), decoding a
        // 300x1800 bitmap for content actually displayed 1920px wide. The fix resolves a target that reflects
        // Fit Width's real (aspect-derived) content box before any safety ceiling applies, bounded by the
        // explicit SAFE_MAX_DIMENSION/byte-budget policy (finding 3): the resolved target's longest edge is the
        // 4096 ceiling (not 1920), so `inSampleSize` lands on 2 (7200/2=3600 <= 4096) -- an exact, deterministic
        // 600x3600, double the pre-remediation width/height on both axes, and still safely within budget.
        val item = cbzFixture("render-cbz-fitwidth-tall.cbz", 1200, 7200)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 1920, viewportHeight = 1080, fit = FitMode.WIDTH))
            assertEquals(600, bitmap.width)
            assertEquals(3600, bitmap.height)
            assertTrue(longestEdge(bitmap) <= PageRenderRequest.SAFE_MAX_DIMENSION)
            assertTrue(estimatedBytes(bitmap) <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
            bitmap.recycle()
        }
    }

    @Test fun cbzFitPageAndFitWidthProduceDifferentBitmapsForTheSameTallSourceAndViewport() {
        val pageItem = cbzFixture("render-cbz-fit-compare-page.cbz", 1200, 7200)
        val widthItem = cbzFixture("render-cbz-fit-compare-width.cbz", 1200, 7200)
        val pageBitmap = factory.open(pageItem).use { it.render(0, PageRenderRequest(1920, 1080, fit = FitMode.PAGE)) }
        val widthBitmap = factory.open(widthItem).use { it.render(0, PageRenderRequest(1920, 1080, fit = FitMode.WIDTH)) }
        try {
            assertTrue("Fit Width should keep more horizontal resolution than Fit Page for a tall page, " +
                "got page=${pageBitmap.width}x${pageBitmap.height} width=${widthBitmap.width}x${widthBitmap.height}",
                widthBitmap.width > pageBitmap.width)
        } finally { pageBitmap.recycle(); widthBitmap.recycle() }
    }

    @Test fun pdfFitWidthOnATallPageIsNotSizedFromRawViewportLongestEdgeAlone() {
        val item = pdfFixture("render-pdf-fitwidth-tall.pdf", 400, 2400) // 1:6 aspect, same shape as the CBZ case above.
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 1920, viewportHeight = 1080, fit = FitMode.WIDTH))
            // The old defect sized this off the viewport's own longest edge (1920) against the source's longest
            // edge (2400) -- a ~320px-wide result for content displayed 1920px wide. The fix must preserve
            // meaningfully more horizontal fidelity than that, even though the explicit safe/byte-budget ceilings
            // (finding 3) still correctly prevent reaching the full 1920px width for this extreme an aspect ratio
            // -- a truthfully-represented, aspect-correct degradation, not an absurd full-width allocation.
            val naiveLongestEdgeOnlyWidth = (400.0 * (1920.0 / 2400.0)).toInt()
            assertTrue("expected meaningfully wider than the old longest-edge-only defect ($naiveLongestEdgeOnlyWidth), got ${bitmap.width}",
                bitmap.width > naiveLongestEdgeOnlyWidth * 2)
            assertTrue(longestEdge(bitmap) <= PageRenderRequest.SAFE_MAX_DIMENSION)
            assertTrue(estimatedBytes(bitmap) <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
            bitmap.recycle()
        }
    }

    @Test fun pdfFitPageAndFitWidthProduceDifferentBitmapsForTheSameTallSourceAndViewport() {
        val pageItem = pdfFixture("render-pdf-fit-compare-page.pdf", 400, 2400)
        val widthItem = pdfFixture("render-pdf-fit-compare-width.pdf", 400, 2400)
        val pageBitmap = factory.open(pageItem).use { it.render(0, PageRenderRequest(1920, 1080, fit = FitMode.PAGE)) }
        val widthBitmap = factory.open(widthItem).use { it.render(0, PageRenderRequest(1920, 1080, fit = FitMode.WIDTH)) }
        try {
            assertTrue("Fit Width should keep more horizontal resolution than Fit Page for a tall PDF page, " +
                "got page=${pageBitmap.width}x${pageBitmap.height} width=${widthBitmap.width}x${widthBitmap.height}",
                widthBitmap.width > pageBitmap.width)
        } finally { pageBitmap.recycle(); widthBitmap.recycle() }
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
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 2200, viewportHeight = 3300, fit = FitMode.PAGE))
            assertTrue("expected > 2048, got ${longestEdge(bitmap)}", longestEdge(bitmap) > 2048)
            bitmap.recycle()
        }
    }

    @Test fun pdfOversizedRequestIsBoundedBySafeMaximum() {
        val item = pdfFixture("render-pdf-oversized.pdf", 400, 600)
        factory.open(item).use { reader ->
            val bitmap = reader.render(0, PageRenderRequest(viewportWidth = 50_000, viewportHeight = 50_000, fit = FitMode.PAGE))
            assertTrue(longestEdge(bitmap) <= PageRenderRequest.SAFE_MAX_DIMENSION)
            assertTrue(estimatedBytes(bitmap) <= RenderMemoryPolicy.MAX_BITMAP_BYTES)
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
            val first = reader.render(0, PageRenderRequest(viewportWidth = 1000, viewportHeight = 1000, fit = FitMode.PAGE))
            assertEquals(Color.RED, first.getPixel(20, 20)); first.recycle()
            val last = reader.render(2, PageRenderRequest(viewportWidth = 1000, viewportHeight = 1000, fit = FitMode.PAGE))
            assertEquals(Color.BLUE, last.getPixel(20, 20)); last.recycle()
        }
    }
}
