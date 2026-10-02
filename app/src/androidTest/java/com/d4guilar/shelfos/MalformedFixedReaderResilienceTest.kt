// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 2D.4 Part J/K: malformed PDF/CBZ publications fed through the real [com.d4guilar.shelfos.core.reader.FixedReaderFactory]
 * path (not just import-time classification, which [ImportPolicyTest]/[ImportViewModelTest] already cover) must fail
 * gracefully -- a localized error, a usable Retry or Back-to-library action, no crash, no infinite loading loop.
 * Entirely original, deterministic, non-copyrighted fixtures; nothing is checked in.
 */
class MalformedFixedReaderResilienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as ShelfApplication).container
    private val addedIds = mutableListOf<String>()

    @After fun removeFixtures() = runBlocking<Unit> { addedIds.forEach { container.library.remove(it) } }

    private fun seed(id: String, fileName: String, format: PublicationFormat, write: (File) -> Unit): LibraryItem {
        val file = File(instrumentation.targetContext.filesDir, "publications/$fileName").also { it.parentFile!!.mkdirs() }
        write(file)
        val item = LibraryItem(id, "Malformed $id", "ShelfOS test", MediaCategory.BOOK, "test:$id", format, file.name,
            byteSize = file.length(), managedPath = file.path)
        runBlocking { container.library.add(item) }
        addedIds += id
        return item
    }

    private fun open(id: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("publication_$id").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("publication_$id").performClick()
        compose.onNodeWithTag("read_action").performScrollTo().performClick()
    }

    /** A truncated PDF: a real header but no cross-reference table, so [android.graphics.pdf.PdfRenderer]'s
     * constructor itself throws -- the open-level (not per-page) failure path. Expected: Back-to-library, no crash. */
    @Test fun truncatedPdfFailsGracefullyAtOpenWithBackToLibrary() {
        seed("malformed-pdf-truncated", "malformed-truncated.pdf", PublicationFormat.PDF) { file ->
            file.writeBytes("%PDF-1.4\n% ShelfOS deliberately truncated test fixture, no xref table follows.\n".toByteArray())
        }
        open("malformed-pdf-truncated")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reader_screen").fetchSemanticsNodes().isNotEmpty() }
        val backLabel = instrumentation.targetContext.getString(R.string.action_back_to_library)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(backLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(backLabel).assertIsDisplayed().performClick()
        // onBack returns to the publication's detail view (same destination as the reader chrome's "Library"
        // control; see NavigationSmokeTest), not necessarily straight past it to the bare grid -- "read_action"
        // reappearing is the real no-crash, no-stuck-screen signal.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read_action").fetchSemanticsNodes().isNotEmpty() }
    }

    /** A structurally valid CBZ whose only page entry is not a real image: the archive opens and reports a
     * page count, but rendering page 1 fails -- the per-page (not open-level) failure path. Expected: a
     * usable Retry action (since the page count is known), no crash, and Retry re-attempts the same failure
     * without wedging the reader in a permanent loading spinner. */
    @Test fun cbzWithInvalidImagePageFailsGracefullyAtRenderWithRetry() {
        seed("malformed-cbz-badpage", "malformed-badpage.cbz", PublicationFormat.CBZ) { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page-001.jpg"))
                zip.write("not actually a jpeg".toByteArray())
                zip.closeEntry()
            }
        }
        open("malformed-cbz-badpage")
        val retryLabel = instrumentation.targetContext.getString(R.string.action_retry_page)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(retryLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader_screen").assertExists() // still alive, no crash
        compose.onNodeWithText(retryLabel).performClick()
        // Retry re-attempts and surfaces the same graceful error again rather than hanging or crashing.
        compose.waitUntil(10_000) { compose.onAllNodesWithText(retryLabel).fetchSemanticsNodes().isNotEmpty() }
    }

    /** A valid CBZ with one good page followed by one bad page: Next from the good page must still fail
     * gracefully on the bad one, and Previous must recover cleanly back to the good page (no stale bitmap,
     * no crash) -- the mixed-page-quality case Part K calls out. */
    @Test fun cbzNavigationRecoversAfterABadPageWithoutStaleBitmap() {
        seed("malformed-cbz-mixed", "malformed-mixed.cbz", PublicationFormat.CBZ) { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page-001.png"))
                val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle(); zip.closeEntry()
                zip.putNextEntry(ZipEntry("page-002.png"))
                zip.write("garbage, not a real png".toByteArray())
                zip.closeEntry()
            }
        }
        open("malformed-cbz-mixed")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("page_number").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next").performClick()
        val retryLabel = instrumentation.targetContext.getString(R.string.action_retry_page)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(retryLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Previous").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("page_number") and hasText("1 / 2")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** A CBZ truncated mid-archive (central directory cut off): the archive itself fails to open --
     * another open-level failure path, distinct from the per-page one above. Expected: Back-to-library, no crash. */
    @Test fun truncatedCbzArchiveFailsGracefullyAtOpenWithBackToLibrary() {
        seed("malformed-cbz-truncated", "malformed-truncated.cbz", PublicationFormat.CBZ) { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page-001.png"))
                val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip); bitmap.recycle(); zip.closeEntry()
            }
            // Cut the file well before the end-of-central-directory record so the archive cannot be opened at all.
            RandomAccessFile(file, "rw").use { it.setLength((file.length() / 2).coerceAtLeast(10)) }
        }
        open("malformed-cbz-truncated")
        val backLabel = instrumentation.targetContext.getString(R.string.action_back_to_library)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(backLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(backLabel).assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read_action").fetchSemanticsNodes().isNotEmpty() }
    }
}
