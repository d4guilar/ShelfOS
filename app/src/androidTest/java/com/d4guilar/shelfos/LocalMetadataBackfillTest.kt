// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.data.metadata.LocalMetadataAnalyzer
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 4.5B backfill in the real app container: items that existed before 4.5B (no metadata row) are analysed on the
 * cover scheduler's worker once the app is in the foreground; embedded values outrank file-name inference, a user edit
 * made before the backfill survives it, an item whose file is missing is skipped without crashing or looping, and the
 * Library list is not re-emitted per analysed item.
 */
class LocalMetadataBackfillTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container
    private val ids = listOf("test-epub", "bf-comic", "bf-missing")

    @After fun cleanUp() = runBlocking<Unit> {
        container.coverScheduler.setForeground(false)
        ids.forEach { container.library.remove(it) }
    }

    private fun comic(): LibraryItem {
        val file = File(context.filesDir, "publications/bf-comic.cbz").also { it.parentFile!!.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write("""<?xml version="1.0"?><ComicInfo><Series>Saga</Series><Number>2</Number><Year>2012</Year>
                <Writer>Brian K. Vaughan</Writer><Publisher>Image</Publisher><LanguageISO>en</LanguageISO></ComicInfo>""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("page1.png"))
            android.graphics.Bitmap.createBitmap(20, 30, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
            zip.closeEntry()
        }
        return LibraryItem("bf-comic", "Saga 2", "Brian K. Vaughan", MediaCategory.COMIC, "test:bf-comic", PublicationFormat.CBZ,
            "Saga #002 (2012).cbz", file.length(), managedPath = file.path, titleOrigin = "embedded", creatorOrigin = "embedded")
    }

    @Test fun existingItemsAreAnalysedOnceInTheBackgroundWithUserEditsPreserved() = runBlocking<Unit> {
        val missing = LibraryItem("bf-missing", "Gone", category = MediaCategory.BOOK, sourceUri = "test:bf-missing", format = PublicationFormat.EPUB,
            fileName = "Gone.epub", byteSize = 1, managedPath = File(context.filesDir, "publications/bf-missing.epub").path)
        listOf(OriginalFixtures.epub(context), comic(), missing).forEach { container.library.add(it) }
        // A user edit made before the item's first local analysis.
        assertTrue(container.metadata.editByUser("bf-comic", MetadataValues(), MetadataValues(publisher = "My Press")))
        var emissions = 0
        val watcher = launch(Dispatchers.Default) { container.library.publications.drop(1).collect { emissions++ } }
        container.coverScheduler.setForeground(true)
        withTimeout(60_000) {
            while (container.metadata.pendingLocal(LocalMetadataAnalyzer.VERSION, emptyList(), 10).intersect(setOf("test-epub", "bf-comic")).isNotEmpty()) delay(250)
        }
        delay(1_000)
        watcher.cancel()

        val comic = container.metadata.resolved("bf-comic")!!
        assertEquals("Saga", comic.values.series); assertEquals(MetadataOrigin.EMBEDDED, comic.origin(MetadataField.SERIES))
        assertEquals("embedded issue wins over the file name's 002", "2", comic.values.issue)
        assertEquals("2012", comic.values.publishedDate)
        assertEquals("en", comic.values.language)
        assertEquals(listOf(Credit(CreditRole.WRITER, "Brian K. Vaughan")), comic.values.credits)
        assertEquals("the user edit survives the backfill", "My Press", comic.values.publisher)
        assertEquals(MetadataOrigin.USER, comic.origin(MetadataField.PUBLISHER))
        val epub = container.metadata.resolved("test-epub")!!
        assertEquals("en", epub.values.language)
        assertEquals("an embedded title outranks a file-name title", "Original Reading Room", epub.title)
        assertEquals(MetadataOrigin.EMBEDDED, epub.origin(MetadataField.TITLE))
        assertTrue("the missing item is not marked analysed (retried next launch)",
            "bf-missing" in container.metadata.pendingLocal(LocalMetadataAnalyzer.VERSION, emptyList(), 10))
        // Only the EPUB's real title change touches library_item; metadata rows never re-emit the Library list.
        assertTrue("Library re-emissions during backfill: $emissions", emissions <= 2)
    }
}
