// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.files.openPdf
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationProblem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3F regression for the Android 7.0/7.1 (API 24/25) `PdfRenderer` platform bug: a constructor whose native
 * open fails (corrupt or protected PDF) leaves a finalizable half-built object whose finalizer later decrements
 * pdfium's process-wide init count a second time, so the next open dies with SIGSEGV in `libpdfium.so`. ShelfOS
 * avoids ever constructing such an object (see `LegacyPdfiumProbe`), so these tests exercise every finalization
 * ordering around failed opens: finalization before the next open, concurrent with it, and after a valid document
 * is already live. Every "valid" step really renders a page into a Bitmap. Passes trivially on API 26+.
 */
class PdfFailedOpenFinalizationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir by lazy { File(context.cacheDir, "pdf-finalization-test").apply { deleteRecursively(); mkdirs() } }
    private val corrupt by lazy { File(dir, "corrupt.pdf").apply { writeBytes("%PDF-1.4\nthis is not a real pdf body\n".toByteArray()) } }
    private val protectedPdf by lazy { File(dir, "protected.pdf").apply { writeBytes(Base64.decode(PROTECTED_PDF_BASE64, Base64.DEFAULT)) } }
    private val validFile by lazy { File(OriginalFixtures.pdf(context).managedPath!!) }

    @After fun cleanup() { dir.deleteRecursively() }

    private fun open(file: File) = openPdf(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))

    private fun forceFinalization() = repeat(4) {
        Runtime.getRuntime().gc()
        System.runFinalization()
    }

    private fun problemOf(file: File): PublicationProblem? =
        try { open(file).close(); null } catch (e: PublicationException) { e.problem }

    private fun renderFirstPage(renderer: PdfRenderer) {
        assertEquals(3, renderer.pageCount)
        renderer.openPage(0).use { page ->
            val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
                assertTrue("rendered page has drawn content", pixels.any { it != Color.WHITE })
            } finally { bitmap.recycle() }
        }
    }

    private fun validOpensAndRenders() { open(validFile).use(::renderFirstPage) }

    @Test fun damagedAndProtectedPdfsKeepTruthfulProblems() {
        repeat(3) {
            assertEquals(PublicationProblem.CORRUPT, problemOf(corrupt))
            assertEquals(PublicationProblem.PROTECTED, problemOf(protectedPdf))
        }
        validOpensAndRenders()
    }

    /** Finalization strictly after each failure and before the next open. */
    @Test fun validFailValidSequencesSurviveFinalizationBetweenSteps() {
        repeat(4) {
            validOpensAndRenders()
            assertEquals(PublicationProblem.CORRUPT, problemOf(corrupt))
            forceFinalization()
            validOpensAndRenders()
            assertEquals(PublicationProblem.PROTECTED, problemOf(protectedPdf))
            forceFinalization()
        }
        validOpensAndRenders()
    }

    /** Finalization running concurrently with failures and valid opens: no ordering may be required for correctness. */
    @Test fun finalizationInterleavedWithFailuresAndValidOpens() {
        val running = AtomicBoolean(true)
        val finalizer = Thread {
            while (running.get()) { Runtime.getRuntime().gc(); System.runFinalization() }
        }
        finalizer.start()
        try {
            repeat(30) { cycle ->
                assertEquals(PublicationProblem.CORRUPT, problemOf(corrupt))
                if (cycle % 3 == 0) assertEquals(PublicationProblem.PROTECTED, problemOf(protectedPdf))
                validOpensAndRenders()
            }
        } finally { running.set(false); finalizer.join() }
        forceFinalization()
        validOpensAndRenders()
    }

    /** A valid document that is already live must survive failures and their finalization, then still render. */
    @Test fun liveValidDocumentSurvivesFailuresFinalizedAfterwards() {
        open(validFile).use { live ->
            repeat(10) {
                assertEquals(PublicationProblem.CORRUPT, problemOf(corrupt))
                assertEquals(PublicationProblem.PROTECTED, problemOf(protectedPdf))
            }
            forceFinalization()
            renderFirstPage(live)
            repeat(3) { assertEquals(PublicationProblem.CORRUPT, problemOf(corrupt)) }
            forceFinalization()
            renderFirstPage(live)
        }
        validOpensAndRenders()
    }

    @Test fun repeatedFailuresLeakNoDescriptorsOrFilesAndLeaveOpeningUsable() {
        check(corrupt.exists() && protectedPdf.exists()) // fixtures exist before the baseline is taken
        validOpensAndRenders()
        val fdsBefore = openDescriptorCount()
        val cacheBefore = context.cacheDir.walkTopDown().count()
        repeat(20) {
            assertEquals(PublicationProblem.CORRUPT, problemOf(corrupt))
            assertEquals(PublicationProblem.PROTECTED, problemOf(protectedPdf))
        }
        forceFinalization()
        val fdsAfter = openDescriptorCount()
        android.util.Log.i("PdfFailedOpen", "fd before=$fdsBefore after=$fdsAfter failures=40")
        assertEquals("no temp files created by failures", cacheBefore, context.cacheDir.walkTopDown().count())
        assertTrue("descriptors before=$fdsBefore after=$fdsAfter", fdsAfter <= fdsBefore + 2)
        repeat(3) { validOpensAndRenders() }
    }

    private fun openDescriptorCount() = File("/proc/self/fd").list()?.size ?: 0

    private companion object {
        /** Tiny synthetic RC4-40 encrypted one-page PDF whose user password is non-empty; contains no real content. */
        val PROTECTED_PDF_BASE64 =
            "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL1R5cGUg" +
            "L1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2JqCjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAg" +
            "UiAvTWVkaWFCb3ggWzAgMCA1MCA1MF0gPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0ZpbHRlciAvU3RhbmRhcmQgL1YgMSAvUiAyIC9P" +
            "IDw5NEU4MDk0NDE5NjYyQTc3NDQ0MkZCMDcyRTNEOUYxOUU5RDEzMEVDMDlBNEQwMDYxRTc4RkU5MjBGN0FCNjJGPiAvVSA8NTZB" +
            "MzlERTU4Rjg2RkE0NTY2NDJEQjE2NzRDMzA2MEYzMjdBNEUwMzgyNzU4NDM5OTdEQ0NGRDU4RUE3OTAwRT4gL1AgLTQgPj4KZW5k" +
            "b2JqCnhyZWYKMCA1CjAwMDAwMDAwMDAgNjU1MzUgZiAKMDAwMDAwMDAwOSAwMDAwMCBuIAowMDAwMDAwMDU4IDAwMDAwIG4gCjAw" +
            "MDAwMDAxMTUgMDAwMDAgbiAKMDAwMDAwMDE4NCAwMDAwMCBuIAp0cmFpbGVyCjw8IC9TaXplIDUgL1Jvb3QgMSAwIFIgL0VuY3J5" +
            "cHQgNCAwIFIgL0lEIFs8MDAwMTAyMDMwNDA1MDYwNzA4MDkwQTBCMEMwRDBFMEY+PDAwMDEwMjAzMDQwNTA2MDcwODA5MEEwQjBD" +
            "MEQwRTBGPl0gPj4Kc3RhcnR4cmVmCjM3OQolJUVPRgo="
    }
}
