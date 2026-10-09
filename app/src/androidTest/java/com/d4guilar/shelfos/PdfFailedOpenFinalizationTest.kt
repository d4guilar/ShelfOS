// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.openPdf
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationProblem
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 3F regression: on Android 7.0/7.1 (API 24/25) a `PdfRenderer` whose native open fails (corrupt or protected
 * PDF) has already balanced pdfium's process-wide init count, yet the half-constructed object is still finalized
 * later and decrements that count a second time. The count goes negative, the next open skips pdfium
 * initialization, and ShelfOS dies with SIGSEGV inside `libpdfium.so` (`CPDF_Document` constructor). The full
 * connected suite hit exactly this on the API 24 emulator. Damaged PDFs must keep failing truthfully, and a later
 * valid PDF must still open and render after the failed renderers have been finalized. Passes trivially on API 26+.
 */
class PdfFailedOpenFinalizationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir by lazy { File(context.cacheDir, "pdf-finalization-test").apply { deleteRecursively(); mkdirs() } }

    @After fun cleanup() { dir.deleteRecursively() }

    private fun open(file: File) = openPdf(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

    private fun forceFinalization() = repeat(6) {
        Runtime.getRuntime().gc()
        System.runFinalization()
    }

    @Test fun failedPdfOpensThatAreFinalizedDoNotBreakLaterPdfOpens() {
        val corrupt = File(dir, "corrupt.pdf").apply { writeBytes("%PDF-1.4\nthis is not a real pdf body\n".toByteArray()) }
        val valid = OriginalFixtures.pdf(context)
        val validFile = File(valid.managedPath!!)

        repeat(3) { attempt ->
            val problem = try { open(corrupt).close(); null } catch (e: PublicationException) { e.problem }
            assertEquals("attempt $attempt", PublicationProblem.CORRUPT, problem)
        }
        forceFinalization()

        // Opened, rendered and closed more than once so a leftover negative count cannot hide behind the first open.
        repeat(2) {
            open(validFile).use { renderer ->
                assertEquals(3, renderer.pageCount)
                renderer.openPage(0).use { page -> assertEquals(400, page.width) }
            }
            forceFinalization()
        }

        // Failures after successful opens, followed by finalization, still leave PDF opening usable.
        repeat(2) { runCatching { open(corrupt).close() } }
        forceFinalization()
        open(validFile).use { assertEquals(3, it.pageCount) }
    }
}
