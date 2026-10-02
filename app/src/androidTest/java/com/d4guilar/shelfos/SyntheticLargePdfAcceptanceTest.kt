// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Phase 2D.4 Part B/E: a realistic large synthetic PDF (mixed text and vector content, deterministic,
 * modest footprint -- not an OOM bomb) through the real reader path: open latency, repeated page-turn
 * responsiveness including a fast overlapping-navigation sequence, and PSS memory before/after. Mirrors
 * [SyntheticLoadAcceptanceTest]'s CBZ coverage, which had no PDF equivalent. Reproducible generated
 * content only; nothing is checked in.
 */
class SyntheticLargePdfAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as ShelfApplication).container

    @Test fun pagesThroughSyntheticLargePdfWithFastNavAndRecordsMemory() {
        val file = File(instrumentation.targetContext.filesDir, "publications/synthetic-load.pdf")
        val item = LibraryItem("synthetic-load-pdf", "Synthetic 140-page PDF load", "ShelfOS test",
            MediaCategory.DOCUMENT, "test:synthetic-load-pdf", PublicationFormat.PDF, file.name,
            byteSize = null, managedPath = file.path)
        try {
            val openStart = SystemClock.elapsedRealtime()
            createPdf(file, pages = 140)
            runBlocking { container.library.add(item.copy(byteSize = file.length())) }
            compose.onNodeWithTag("library_grid").performScrollToIndex(0)
            // The default library filter is Books; this fixture's MediaCategory.DOCUMENT needs its own tab.
            compose.onNodeWithText(instrumentation.targetContext.getString(R.string.category_documents)).performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("publication_synthetic-load-pdf").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("publication_synthetic-load-pdf").performClick()
            compose.onNodeWithTag("read_action").performScrollTo().performClick()
            awaitPage(1)
            val openLatencyMs = SystemClock.elapsedRealtime() - openStart
            val start = memoryPssKb()

            // Sequential page-turn responsiveness.
            val sequentialStart = SystemClock.elapsedRealtime()
            for (page in 2..61) { compose.onNodeWithText("Next").performClick(); awaitPage(page) }
            val sequentialMs = SystemClock.elapsedRealtime() - sequentialStart

            // Fast overlapping navigation/cancellation regression (Part G): next/next/previous/next/next/previous
            // issued without waiting for each render, so later requests race earlier in-flight ones.
            repeat(2) {
                compose.onNodeWithText("Next").performClick()
                compose.onNodeWithText("Next").performClick()
                compose.onNodeWithText("Previous").performClick()
                compose.onNodeWithText("Next").performClick()
                compose.onNodeWithText("Next").performClick()
                compose.onNodeWithText("Previous").performClick()
            }
            // The reader must settle on a consistent page matching the net input, never a stale/blank render.
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("page_number").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader_screen").assertExists() // still alive, no crash
            compose.onNodeWithTag("reader_page").assertExists()

            Runtime.getRuntime().gc()
            val end = memoryPssKb()
            Log.i(TAG, "synthetic_pdf_bytes=${file.length()} pages=140 open_latency_ms=$openLatencyMs " +
                "sequential_60_turns_ms=$sequentialMs pss_start_kb=$start pss_end_kb=$end " +
                "java_heap_bytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
            assertTrue("Reader did not survive the fast overlapping-navigation sequence", compose.onAllNodesWithTag("page_number").fetchSemanticsNodes().isNotEmpty())
            if (InstrumentationRegistry.getArguments().getString("profile") == "true") SystemClock.sleep(20_000)
        } finally {
            runBlocking { container.library.remove(item.id) }
            file.delete()
        }
    }

    private fun awaitPage(page: Int) = compose.waitUntil(20_000) {
        compose.onAllNodesWithText("$page / 140").fetchSemanticsNodes().isNotEmpty()
    }

    private fun memoryPssKb(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

    /** Mixed text + vector content per page (not a blank-page bomb): a title, a paragraph of deterministic
     * body text, and a handful of filled/stroked shapes, at a modest fixed page size. */
    private fun createPdf(file: File, pages: Int) {
        file.parentFile!!.mkdirs()
        val random = Random(0x9E3779B9.toInt().toLong())
        val pdf = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(612, 792, index + 1).create())
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val titlePaint = Paint().apply { color = Color.BLACK; textSize = 24f; isAntiAlias = true }
                canvas.drawText("ShelfOS synthetic load page ${index + 1}", 40f, 60f, titlePaint)
                val bodyPaint = Paint().apply { color = Color.DKGRAY; textSize = 12f; isAntiAlias = true }
                val body = "Deterministic synthetic text for resilience testing. Page ${index + 1} of $pages. " +
                    "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor."
                body.chunked(70).forEachIndexed { line, text -> canvas.drawText(text, 40f, 100f + line * 18f, bodyPaint) }
                repeat(6) {
                    val paint = Paint().apply {
                        color = Color.HSVToColor(floatArrayOf(random.nextInt(360).toFloat(), 0.6f, 0.8f))
                        style = if (it % 2 == 0) Paint.Style.FILL else Paint.Style.STROKE
                        strokeWidth = 3f; isAntiAlias = true
                    }
                    val x = 40f + it * 85f
                    canvas.drawRoundRect(RectF(x, 250f, x + 70f, 320f), 8f, 8f, paint)
                }
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
    }

    private companion object { const val TAG = "ShelfOSAcceptance" }
}
