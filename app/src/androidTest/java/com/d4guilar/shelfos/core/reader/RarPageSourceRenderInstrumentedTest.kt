// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.NativeRarEntry
import com.d4guilar.shelfos.core.files.NativeRarEntryType
import com.d4guilar.shelfos.core.files.NativeRarError
import com.d4guilar.shelfos.core.files.RarArchiveSession
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3E-C real-decode proof that [RarPageSource] feeds the SAME, unmodified [ImagePageRenderer]/[PageSource]
 * pipeline [ZipPageSource] already uses for CBZ -- `BitmapFactory` needs a real Android runtime (same reason
 * `FixedReaderRenderRequestTest` is instrumented, not a local JVM test), so this cannot be a pure-JVM test like
 * `RarPageSourceTest`. Uses a local [FakeRarArchiveSession] (duplicated here rather than shared with the
 * `app/src/test` fake of the same name -- `test` and `androidTest` are independent compilations in this project)
 * so real PNG bytes can be synthesized without any real archive or native call -- see that fake's doc and
 * `RarPageSourceTest`'s for the same honesty boundary: this proves the RENDER-REUSE wiring, never real
 * libarchive/RAR parsing.
 */
class RarPageSourceRenderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var cacheRoot: File

    @After
    fun tearDown() {
        if (::cacheRoot.isInitialized) cacheRoot.deleteRecursively()
    }

    private fun pngBytes(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private class FakeRarArchiveSession(
        private val entries: List<NativeRarEntry>,
        private val content: Map<Int, ByteArray>,
    ) : RarArchiveSession {
        var extractionCount = 0
            private set
        override val entryCount get() = entries.size
        override fun entryAt(index: Int) = entries.getOrNull(index)
        override fun extractEntry(index: Int, destination: File, maxBytes: Long): NativeRarError? {
            extractionCount++
            val bytes = content[index] ?: return NativeRarError.INVALID_ARGUMENT
            destination.writeBytes(bytes)
            return null
        }
        override fun close() {}
    }

    private fun entry(index: Int, name: String) =
        NativeRarEntry(index, name, nameEncodingConfirmedUtf8 = true, type = NativeRarEntryType.REGULAR_FILE, size = null)

    @Test
    fun imagePageRendererBoundsThenFullDecodeOfTheSamePageReusesOneMaterialization() {
        cacheRoot = File(context.cacheDir, "rar-render-test-${System.nanoTime()}").apply { mkdirs() }
        val fake = FakeRarArchiveSession(
            listOf(entry(0, "page1.png")),
            mapOf(0 to pngBytes(400, 300, Color.RED)),
        )
        val source = RarPageSource.open(fake, cacheRoot, "render-reuse-source")
        try {
            val geometry = ImagePageRenderer.bounds(source, 0)
            assertEquals(400, geometry?.width)
            assertEquals(300, geometry?.height)
            val bitmap = ImagePageRenderer.render(source, 0, PageRenderRequest.DEFAULT)
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
            bitmap.recycle()

            assertEquals(
                "a bounds decode followed by a full decode of the SAME page must cost exactly one native extraction",
                1,
                fake.extractionCount,
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun imagePageRendererAcrossTwoDifferentPagesCostsTwoExtractions() {
        cacheRoot = File(context.cacheDir, "rar-render-test-${System.nanoTime()}").apply { mkdirs() }
        val fake = FakeRarArchiveSession(
            listOf(entry(0, "page1.png"), entry(1, "page2.png")),
            mapOf(0 to pngBytes(200, 200, Color.RED), 1 to pngBytes(200, 200, Color.BLUE)),
        )
        val source = RarPageSource.open(fake, cacheRoot, "two-page-source")
        try {
            ImagePageRenderer.render(source, 0, PageRenderRequest.DEFAULT).recycle()
            ImagePageRenderer.render(source, 1, PageRenderRequest.DEFAULT).recycle()
            assertEquals(2, fake.extractionCount)
            // Re-rendering page 0 again must stay a cache hit.
            ImagePageRenderer.render(source, 0, PageRenderRequest.DEFAULT).recycle()
            assertEquals(2, fake.extractionCount)
        } finally {
            source.close()
        }
    }

    @Test
    fun aCorruptPageNeverPoisonsItsSiblingPage() {
        cacheRoot = File(context.cacheDir, "rar-render-test-${System.nanoTime()}").apply { mkdirs() }
        val fake = FakeRarArchiveSession(
            listOf(entry(0, "page1.png"), entry(1, "page2.png")),
            mapOf(0 to byteArrayOf(1, 2, 3, 4), 1 to pngBytes(100, 100, Color.GREEN)), // page0 is not a real image
        )
        val source = RarPageSource.open(fake, cacheRoot, "corrupt-sibling-source")
        try {
            var failed = false
            try {
                ImagePageRenderer.render(source, 0, PageRenderRequest.DEFAULT)
            } catch (_: Throwable) {
                failed = true
            }
            assertTrue("expected page 0's malformed bytes to fail decode", failed)
            val healthy = ImagePageRenderer.render(source, 1, PageRenderRequest.DEFAULT)
            assertEquals(Color.GREEN, healthy.getPixel(0, 0))
            healthy.recycle()
        } finally {
            source.close()
        }
    }
}
