// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.*
import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File-mutating cases use an isolated private-copy root so they never touch the installed app's real copies,
 * grants or library database.
 */
class LibraryPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var isolated: File

    @Before fun createIsolatedStorage() { isolated = File(context.cacheDir, "publication-tests").apply { deleteRecursively(); mkdirs() } }
    @After fun removeIsolatedStorage() { isolated.deleteRecursively() }

    @Test fun migrationPreservesAppearanceAndLibrarySurvivesReopen() = runBlocking<Unit> {
        val name = "library-migration-test.db"
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE appearance_preference (id INTEGER NOT NULL PRIMARY KEY, themeKey TEXT NOT NULL)")
            db.execSQL("INSERT INTO appearance_preference VALUES(0, 'dark')")
            db.version = 1
        }
        fun open() = Room.databaseBuilder(context, ShelfDatabase::class.java, name).addMigrations(ShelfDatabase.MIGRATION_1_2).build()
        try {
            val db = open()
            try {
                assertEquals("dark", db.appearance().observeTheme().first())
                val repository = RoomLibraryRepository(db.library())
                val item = OriginalFixtures.pdf(context)
                assertEquals(item.id, repository.add(item))
                assertEquals(item.id, repository.add(item.copy(id = "duplicate")))
                repository.favorite(item.id)
                repository.edit(item.id, "Edited title", "Editor", MediaCategory.MANGA)
                repository.reading(item.id, pageLocator(1), 66)
                repository.preferences(item.id, ReaderPreferences(direction = ReadingDirection.LTR).json())
            } finally { db.close() }
            val reopened = open()
            try {
                val repository = RoomLibraryRepository(reopened.library())
                val item = repository.publications.first().single()
                assertEquals("Edited title", item.title); assertTrue(item.favorite)
                assertEquals("user", item.titleOrigin)
                assertEquals(MediaCategory.MANGA, item.category); assertEquals(1, restorePage(item.locator, 3))
                assertEquals(ReadingDirection.LTR, ReaderPreferences.parse(item.preferences).direction)
                repository.remove(item.id)
                assertTrue(repository.publications.first().isEmpty())
                assertTrue(File(item.managedPath!!).exists())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun removalKeepsPrivateCopiesUntilExplicitCleanupAndPreservesOtherTitles() = runBlocking<Unit> {
        val name = "library-removal-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, ShelfDatabase::class.java, name).build()
        try {
            val files = PublicationFiles(context, isolated)
            val repository = RoomLibraryRepository(db.library(), files)
            val removed = privateCopy(OriginalFixtures.pdf(context), "removed-copy.pdf")
            val kept = privateCopy(OriginalFixtures.cbz(context), "kept-copy.cbz")
            listOf(removed, kept).forEach { item ->
                repository.add(item)
                repository.reading(item.id, pageLocator(2), 100)
                repository.preferences(item.id, ReaderPreferences(fit = FitMode.WIDTH).json())
            }
            repository.remove(removed.id)

            val remaining = repository.publications.first().single()
            assertEquals(kept.id, remaining.id)
            assertEquals(2, restorePage(remaining.locator, 3))
            assertEquals(FitMode.WIDTH, ReaderPreferences.parse(remaining.preferences).fit)
            assertNull(db.library().byId(removed.id))
            // Non-destructive by default: the private copy stays until the user explicitly cleans up.
            val removedFile = File(isolated, "removed-copy.pdf")
            val keptFile = File(isolated, "kept-copy.cbz")
            assertTrue(removedFile.exists())
            assertEquals(PrivateCopyUsage(1, removedFile.length()), repository.unusedPrivateCopies())
            assertEquals(PrivateCopyUsage(0, 0), repository.deleteUnusedPrivateCopies())
            assertFalse(removedFile.exists())
            assertTrue(keptFile.exists())
            assertEquals(3, FixedReaderFactory(files).open(remaining).use { it.pageCount })
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun copyImportDetectsRealFormatsReadsEmbeddedMetadataAndLeavesOriginalUntouched() = runBlocking<Unit> {
        val files = PublicationFiles(context, isolated)
        listOf(OriginalFixtures.pdf(context), OriginalFixtures.cbz(context), OriginalFixtures.epub(context)).forEach { fixture ->
            val original = File(fixture.managedPath!!)
            val bytes = original.readBytes()
            val prepared = files.prepare(Uri.fromFile(original).toString(), copy = true) {}
            val copy = File(isolated, prepared.item.managedPath!!)
            try {
                assertEquals(fixture.format, prepared.item.format)
                assertArrayEquals(bytes, original.readBytes())
                // Private copies are referenced by name inside ShelfOS storage, never by absolute path.
                assertFalse(prepared.item.managedPath.contains('/'))
                assertTrue(copy.exists())
                if (fixture.format == PublicationFormat.EPUB) {
                    assertEquals("Original Reading Room", prepared.item.title)
                    assertEquals("embedded", prepared.item.titleOrigin)
                }
            } finally { files.discard(prepared, false) }
            assertFalse(copy.exists())
            assertArrayEquals(bytes, original.readBytes())
        }
    }

    @Test fun rejectedImportsReportSpecificProblemsAndLeaveNoPartialCopy() = runBlocking<Unit> {
        val files = PublicationFiles(context, isolated)
        val cases = mapOf(
            "notes.txt" to PublicationProblem.UNSUPPORTED_FORMAT,
            "broken.pdf" to PublicationProblem.CORRUPT,
            "empty.cbz" to PublicationProblem.EMPTY_ARCHIVE,
            "protected.epub" to PublicationProblem.PROTECTED,
        )
        cases.forEach { (fileName, expected) ->
            val source = File(context.cacheDir, fileName)
            when (fileName) {
                "notes.txt" -> source.writeText("Plain text is not a publication.")
                "broken.pdf" -> source.writeText("%PDF-1.7\nthis is not a readable document")
                "empty.cbz" -> zip(source, "readme.txt" to "no pages")
                else -> zip(source, "mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container/>", "META-INF/rights.xml" to "<rights/>")
            }
            val error = try { files.prepare(Uri.fromFile(source).toString(), copy = true) {}; null } catch (e: PublicationException) { e }
            assertEquals(fileName, expected, error?.problem)
            source.delete()
        }
        assertTrue(isolated.list().isNullOrEmpty())
    }

    // Grant release is planned by a pure function (unit-tested); device tests never touch the app's real grants.
    @Test fun startupMaintenanceRemovesInterruptedPartialCopiesOnly() {
        val partial = File(isolated, "interrupted.part").apply { writeText("partial") }
        val complete = File(isolated, "complete.pdf").apply { writeText("kept") }
        PublicationFiles(context, isolated).deletePartialCopies()
        assertFalse(partial.exists())
        assertTrue(complete.exists())
    }

    @Test fun pdfAndArchiveRenderAndArchiveUsesNaturalPageOrder() = runBlocking<Unit> {
        val factory = FixedReaderFactory(PublicationFiles(context))
        factory.open(OriginalFixtures.pdf(context)).use { reader ->
            assertEquals(3, reader.pageCount)
            reader.render(2).also { assertTrue(it.width <= 2048); it.recycle() }
        }
        factory.open(OriginalFixtures.cbz(context)).use { reader ->
            assertEquals(3, reader.pageCount)
            reader.render(0).also { assertEquals(android.graphics.Color.RED, it.getPixel(20, 20)); it.recycle() }
            reader.render(2).also { assertEquals(android.graphics.Color.BLUE, it.getPixel(20, 20)); it.recycle() }
        }
    }

    @Test fun missingSourcesReportUnavailableInsteadOfCrashing() {
        val missing = OriginalFixtures.pdf(context).copy(id = "gone", managedPath = "never-created.pdf")
        val error = try { FixedReaderFactory(PublicationFiles(context, isolated)).open(missing); null } catch (e: PublicationException) { e }
        assertEquals(PublicationProblem.SOURCE_UNAVAILABLE, error?.problem)
        assertTrue(error!!.problem.unavailable)
    }

    @Test fun nonSeekableProviderCopiesCommitsReopensAndCleansWithoutTouchingTheSource() = runBlocking<Unit> {
        val providerContext = context
        val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        val files = PublicationFiles(providerContext, isolated)
        val repository = RoomLibraryRepository(db.library(), files)
        try {
            val source = "content://com.d4guilar.shelfos.test.documents/publication"
            val prepared = files.prepare(source, copy = true) {}
            assertEquals(PublicationFormat.PDF, prepared.item.format)
            assertTrue(File(isolated, prepared.item.managedPath!!).exists())
            repository.add(prepared.item)
            assertEquals(3, FixedReaderFactory(files).open(repository.publications.first().single()).use { it.pageCount })
            repository.remove(prepared.item.id)
            assertTrue(File(isolated, prepared.item.managedPath).exists())
            assertEquals(PrivateCopyUsage(0, 0), repository.deleteUnusedPrivateCopies())
            assertFalse(File(isolated, prepared.item.managedPath).exists())
            // The provider remains readable after ShelfOS cleanup: its source was never deleted or opened for writing.
            assertNotNull(providerContext.contentResolver.openFileDescriptor(Uri.parse(source), "r")?.use { it })
        } finally { db.close() }
    }

    @Test fun lowStorageAndInterruptedCopiesLeaveNoCommittedItemOrPartialAndCanRetry() = runBlocking<Unit> {
        val providerContext = context
        val source = "content://com.d4guilar.shelfos.test.documents/publication"
        val lowStorage = PublicationFiles(providerContext, isolated) { 0L }
        val lowError = runCatching { lowStorage.prepare(source, copy = true) {} }.exceptionOrNull()
        assertEquals(PublicationProblem.INSUFFICIENT_STORAGE, lowError?.publicationProblem())
        assertTrue(isolated.list().isNullOrEmpty())

        val files = PublicationFiles(providerContext, isolated)
        val interrupted = runCatching {
            files.prepare("content://com.d4guilar.shelfos.test.documents/interrupted", copy = true) {}
        }.exceptionOrNull()
        assertEquals(PublicationProblem.COPY_FAILED, interrupted?.publicationProblem())
        assertTrue(isolated.list().isNullOrEmpty())

        val retry = files.prepare(source, copy = true) {}
        assertTrue(File(isolated, retry.item.managedPath!!).exists())
        files.discard(retry, sourceStillUsed = false)
        assertTrue(isolated.list().isNullOrEmpty())
    }

    @Test fun roomTransactionRollsBackRelatedStateWhenRemovalFails() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val repository = RoomLibraryRepository(db.library())
            val item = OriginalFixtures.pdf(context).copy(id = "rollback", sourceUri = "test:rollback")
            repository.add(item)
            repository.reading(item.id, pageLocator(1), 66)
            repository.preferences(item.id, ReaderPreferences(fit = FitMode.WIDTH).json())
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_library_delete BEFORE DELETE ON library_item " +
                    "BEGIN SELECT RAISE(ABORT, 'injected rollback'); END"
            )

            assertTrue(runCatching { repository.remove(item.id) }.isFailure)
            val restored = repository.publications.first().single()
            assertEquals(item.id, restored.id)
            assertEquals(1, restorePage(restored.locator, 3))
            assertEquals(FitMode.WIDTH, ReaderPreferences.parse(restored.preferences).fit)
        } finally { db.close() }
    }

    private fun privateCopy(fixture: LibraryItem, name: String): LibraryItem {
        File(fixture.managedPath!!).copyTo(File(isolated, name), overwrite = true)
        return fixture.copy(id = name, sourceUri = "test:$name", managedPath = name)
    }

    private fun zip(target: File, vararg entries: Pair<String, String>) = ZipOutputStream(target.outputStream()).use { zip ->
        entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
    }
}
