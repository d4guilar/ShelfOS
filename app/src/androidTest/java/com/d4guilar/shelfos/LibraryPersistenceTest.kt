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
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LibraryPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
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
                assertEquals(MediaCategory.MANGA, item.category); assertEquals(1, restorePage(item.locator, 3))
                assertEquals(ReadingDirection.LTR, ReaderPreferences.parse(item.preferences).direction)
                repository.remove(item.id)
                assertTrue(repository.publications.first().isEmpty())
                assertTrue(File(item.managedPath!!).exists()) // Repository without source ownership never deletes files.
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun copyImportDetectsRealFormatsAndLeavesOriginalUntouched() = runBlocking<Unit> {
        val files = PublicationFiles(context)
        listOf(OriginalFixtures.pdf(context), OriginalFixtures.cbz(context), OriginalFixtures.epub(context)).forEach { fixture ->
            val original = File(fixture.managedPath!!)
            val bytes = original.readBytes()
            val prepared = files.prepare(Uri.fromFile(original), copy = true) {}
            try {
                assertEquals(fixture.format, prepared.item.format)
                assertArrayEquals(bytes, original.readBytes())
                assertNotEquals(original.path, prepared.item.managedPath)
            } finally { files.discard(prepared, false) }
            assertTrue(original.exists())
        }
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
}
