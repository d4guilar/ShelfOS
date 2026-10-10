// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.AnnotationEntity
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.annotations.RoomBookmarkRepository
import com.d4guilar.shelfos.domain.annotations.DefaultAnnotationLocatorValidator
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2B.2: the Room v2 -> v3 bookmark migration (now continuing to v4, where bookmarks live in the canonical annotation table), and bookmark repository CRUD, ordering, duplicate and
 * isolation behavior. Follows [LibraryPersistenceTest]'s own established migration-testing convention (bootstrap
 * the old schema with raw SQL, open with Room + the real migrations, assert data survives) rather than adding
 * `androidx.room:room-testing`/`MigrationTestHelper` — this project already has a working, dependency-free pattern
 * for exactly this, so a new test-only dependency is not required.
 */
class BookmarkPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Phase 4A: bookmarks are now the BOOKMARK kind of the canonical annotation table, behind the unchanged BookmarkRepository contract. */
    private fun bookmarks(db: ShelfDatabase) = RoomBookmarkRepository(RoomAnnotationRepository(db.annotations(), DefaultAnnotationLocatorValidator { true }))

    /** Bootstraps a real v2 database (matching app/schemas/.../2.json exactly) with one pre-existing library item,
     * its resume state and its preferences, then migrates it to v3 and verifies nothing pre-existing was lost. */
    @Test fun bookmarkMigrationPreservesExistingDataAndRemovalDeletesCanonicalAnnotations() = runBlocking<Unit> {
        val name = "bookmark-migration-test.db"
        context.deleteDatabase(name)
        val itemId = "pre-migration-item"
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("CREATE TABLE appearance_preference (id INTEGER NOT NULL PRIMARY KEY, themeKey TEXT NOT NULL)")
            db.execSQL("INSERT INTO appearance_preference VALUES(0, 'classic')")
            db.execSQL("CREATE TABLE library_item (id TEXT NOT NULL PRIMARY KEY, sourceUri TEXT NOT NULL, title TEXT NOT NULL, creator TEXT NOT NULL, category TEXT NOT NULL, format TEXT NOT NULL, fileName TEXT NOT NULL, byteSize INTEGER, favorite INTEGER NOT NULL, addedAt INTEGER NOT NULL, available INTEGER NOT NULL, managedPath TEXT, titleOrigin TEXT NOT NULL, creatorOrigin TEXT NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX index_library_item_sourceUri ON library_item(sourceUri)")
            db.execSQL("INSERT INTO library_item VALUES('$itemId', 'test:pre-migration', 'Pre-Migration Book', 'Author', 'BOOK', 'EPUB', 'book.epub', 1000, 0, 1000, 1, NULL, 'filename', 'unknown')")
            db.execSQL("CREATE TABLE reading_state (itemId TEXT NOT NULL PRIMARY KEY, locator TEXT NOT NULL, progress INTEGER NOT NULL, lastRead INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES library_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("INSERT INTO reading_state VALUES('$itemId', '{\"href\":\"chapter1.xhtml\"}', 40, 2000)")
            db.execSQL("CREATE TABLE reader_preference (itemId TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)")
            db.execSQL("INSERT INTO reader_preference VALUES('$itemId', '{\"scroll\":true}')")
            db.version = 2
        }
        fun open() = Room.databaseBuilder(context, ShelfDatabase::class.java, name)
            .addMigrations(ShelfDatabase.MIGRATION_1_2, ShelfDatabase.MIGRATION_2_3, ShelfDatabase.MIGRATION_3_4).build()
        try {
            val db = open()
            try {
                // Pre-existing data across every table survives the v2 -> v3 migration untouched.
                assertEquals("classic", db.appearance().observeTheme().first())
                val repository = RoomLibraryRepository(db.library())
                val item = repository.publications.first().single()
                assertEquals(itemId, item.id)
                assertEquals("Pre-Migration Book", item.title)
                assertEquals(40, item.progress)
                assertEquals("""{"href":"chapter1.xhtml"}""", item.locator)
                assertEquals("""{"scroll":true}""", item.preferences)

                // The new bookmark table exists and accepts a row tied to the existing item.
                val locator = """{"href":"chapter2.xhtml","locations":{"progression":0.5}}"""
                bookmarks(db).addBookmark(itemId, locator, 55, null)
                val saved = bookmarks(db).bookmarks(itemId).first()
                assertEquals(1, saved.size)
                assertEquals(locator, saved.single().locator)
                assertEquals(55, saved.single().progress)
                assertNull(saved.single().label)

                // Phase 4A: the annotation table has no foreign key, so removal deletes its rows explicitly and
                // atomically (KnowledgePolicy.DELETE). The pre-4A version of this test asserted the FK cascade.
                repository.remove(itemId)
                assertTrue(bookmarks(db).bookmarks(itemId).first().isEmpty())
                db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM annotation WHERE libraryItemId = ?", arrayOf(itemId)).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun addingTheSameLocatorTwiceDoesNotCreateADuplicateRow() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val repository = RoomLibraryRepository(db.library())
            val item = OriginalFixtures.pdf(context)
            repository.add(item)
            val locator = """{"href":"chapter1.xhtml"}"""
            bookmarks(db).addBookmark(item.id, locator, 10)
            bookmarks(db).addBookmark(item.id, locator, 10)
            bookmarks(db).addBookmark(item.id, locator, 10)
            assertEquals(1, bookmarks(db).bookmarks(item.id).first().size)
            // A different locator for the same item is a genuinely new bookmark, not a duplicate.
            bookmarks(db).addBookmark(item.id, """{"href":"chapter2.xhtml"}""", 20)
            assertEquals(2, bookmarks(db).bookmarks(item.id).first().size)
        } finally { db.close() }
    }

    @Test fun bookmarksAreOrderedByProgressThenCreationTimeDeterministically() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val repository = RoomLibraryRepository(db.library())
            val item = OriginalFixtures.pdf(context)
            repository.add(item)
            // Inserted out of order; the observed list must always come back progress-ascending.
            bookmarks(db).addBookmark(item.id, """{"href":"c3.xhtml"}""", 80)
            bookmarks(db).addBookmark(item.id, """{"href":"c1.xhtml"}""", 10)
            bookmarks(db).addBookmark(item.id, """{"href":"c2.xhtml"}""", 50)
            val ordered = bookmarks(db).bookmarks(item.id).first()
            assertEquals(listOf(10, 50, 80), ordered.map { it.progress })
        } finally { db.close() }
    }

    /** When progress and createdAt are both identical (e.g. two bookmarks added in the same millisecond), id is the
     * final deterministic tie-breaker rather than unspecified SQLite row order. Uses explicit deterministic ids and
     * an explicit shared createdAt inserted directly through the DAO — not [RoomBookmarkRepository.addBookmark],
     * which always generates a random UUID and the current time — so the expected order is explicit, not incidental. */
    @Test fun bookmarksWithIdenticalProgressAndCreatedAtStillSortDeterministicallyById() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val dao = db.annotations()
            val item = OriginalFixtures.pdf(context)
            RoomLibraryRepository(db.library()).add(item)
            // Inserted out of id order; every row shares the same progress and createdAt.
            for ((id, page) in listOf("c-bookmark" to 3, "a-bookmark" to 1, "b-bookmark" to 2))
                dao.create(AnnotationEntity(id, item.id, "BOOKMARK", "READIUM_LOCATOR_1", """{"href":"c$page.xhtml"}""", 50, null, null, null, null, null, 1_000L, 1_000L))
            val ordered = bookmarks(db).bookmarks(item.id).first()
            assertEquals(listOf("a-bookmark", "b-bookmark", "c-bookmark"), ordered.map { it.id })
        } finally { db.close() }
    }

    @Test fun bookmarksForOneItemNeverAppearUnderAnother() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val repository = RoomLibraryRepository(db.library())
            val a = OriginalFixtures.pdf(context)
            val b = OriginalFixtures.cbz(context)
            repository.add(a); repository.add(b)
            bookmarks(db).addBookmark(a.id, """{"href":"a1.xhtml"}""", 10)
            bookmarks(db).addBookmark(b.id, """{"href":"b1.xhtml"}""", 20)
            assertEquals(1, bookmarks(db).bookmarks(a.id).first().size)
            assertEquals(1, bookmarks(db).bookmarks(b.id).first().size)
            assertEquals(a.id, bookmarks(db).bookmarks(a.id).first().single().itemId)
            assertEquals(b.id, bookmarks(db).bookmarks(b.id).first().single().itemId)
        } finally { db.close() }
    }

    @Test fun addingABookmarkForANonexistentItemIsRejectedByTheRepository() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val repository = RoomLibraryRepository(db.library())
            assertTrue(runCatching { bookmarks(db).addBookmark("missing-item", """{"href":"x.xhtml"}""", 10) }.isFailure)
        } finally { db.close() }
    }

    @Test fun deletingOneBookmarkLeavesOthersForTheSameItemIntact() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val repository = RoomLibraryRepository(db.library())
            val item = OriginalFixtures.pdf(context)
            repository.add(item)
            bookmarks(db).addBookmark(item.id, """{"href":"c1.xhtml"}""", 10)
            bookmarks(db).addBookmark(item.id, """{"href":"c2.xhtml"}""", 20)
            val toDelete = bookmarks(db).bookmarks(item.id).first().first { it.progress == 10 }
            bookmarks(db).deleteBookmark(toDelete.id)
            val remaining = bookmarks(db).bookmarks(item.id).first()
            assertEquals(1, remaining.size)
            assertEquals(20, remaining.single().progress)
        } finally { db.close() }
    }
}
