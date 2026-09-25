// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Reproducible generated load only; no fixture or publication artwork is checked in. */
class SyntheticLoadAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as ShelfApplication).container

    @Test fun pagesThroughSyntheticLargeArchiveAndRecordsMemory() {
        val file = File(instrumentation.targetContext.filesDir, "publications/synthetic-load.cbz")
        val item = LibraryItem("synthetic-load", "Synthetic 160-page load", "ShelfOS test",
            MediaCategory.COMIC, "test:synthetic-load", PublicationFormat.CBZ, file.name,
            byteSize = null, managedPath = file.path)
        try {
            createArchive(file, pages = 160)
            runBlocking { container.library.add(item.copy(byteSize = file.length())) }
            compose.onNodeWithTag("library_grid").performScrollToIndex(0)
            compose.onNodeWithText("Comics").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("publication_synthetic-load").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("publication_synthetic-load").performClick()
            compose.onNodeWithTag("read_action").performScrollTo().performClick()
            awaitPage(1)
            val start = memoryPssKb()
            for (page in 2..81) {
                compose.onNodeWithText("Next").performClick()
                awaitPage(page)
            }
            Runtime.getRuntime().gc()
            val end = memoryPssKb()
            Log.i(TAG, "synthetic_cbz_bytes=${file.length()} pages=160 visited=81 pss_start_kb=$start pss_end_kb=$end java_heap_bytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
            assertTrue("Reader did not reach the requested synthetic page", compose.onAllNodesWithText("81 / 160").fetchSemanticsNodes().isNotEmpty())
            if (InstrumentationRegistry.getArguments().getString("profile") == "true") SystemClock.sleep(20_000)
        } finally {
            runBlocking { container.library.remove(item.id) }
            file.delete()
        }
    }

    private fun awaitPage(page: Int) = compose.waitUntil(20_000) {
        compose.onAllNodesWithText("$page / 160").fetchSemanticsNodes().isNotEmpty()
    }

    private fun memoryPssKb(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

    private fun createArchive(file: File, pages: Int) {
        file.parentFile!!.mkdirs()
        val random = Random(0x5E1F05)
        val pixels = IntArray(1200 * 1800) { 0xff000000.toInt() or random.nextInt(0x1000000) }
        val bitmap = Bitmap.createBitmap(pixels, 1200, 1800, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
        bitmap.recycle()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            repeat(pages) { index ->
                zip.putNextEntry(ZipEntry("page-${(index + 1).toString().padStart(3, '0')}.jpg"))
                zip.write(jpeg)
                zip.closeEntry()
            }
        }
    }

    private companion object { const val TAG = "ShelfOSAcceptance" }
}
