// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.LegacyDatabases.V3Bookmark
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.annotations.RoomBookmarkRepository
import com.d4guilar.shelfos.domain.annotations.AnnotationLocatorValidator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4A: Room v3 -> v4 (bookmark -> annotation). Every database here is a REAL older file built with raw SQL
 * matching the exported schemas, opened through Room with the real migration chain (no `MigrationTestHelper`, the
 * repo convention; see [BookmarkPersistenceTest]). This is a data-safety test: it asserts zero bookmark loss, exact
 * field preservation, legacy-table retention, and that a failed migration rolls back instead of rebuilding.
 */
class AnnotationMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val epubLocatorA = """{"href":"/OEBPS/ch2.xhtml","type":"application/xhtml+xml","title":"Chapter \"2\" é中","locations":{"progression":0.5,"position":12,"totalProgression":0.25},"text":{"highlight":"it's \"quoted\""}}"""
    private val epubLocatorB = """{"href":"ch1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.1}}"""
    private val epubLocatorC = """{ "href" : "ch3.xhtml", "type":"application/xhtml+xml" }"""
    private val pdfLocator = """{"version":1,"page":3}"""
    private val cbzLocator = """{"version":1,"page":0}"""
    private val cbrLocator = """{"version":1,"page":7}"""
    private val neverValidated = AnnotationLocatorValidator { _, _ -> true }

    private val items = listOf("item-epub" to "EPUB", "item-pdf" to "PDF", "item-cbz" to "CBZ", "item-cbr" to "CBR")
    private val bookmarks = listOf(
        V3Bookmark("b-c", "item-epub", epubLocatorA, 50, "Keep this", 1_000),
        V3Bookmark("b-a", "item-epub", epubLocatorB, 50, null, 1_000),         // ties b-c on progress and createdAt
        V3Bookmark("b-b", "item-epub", epubLocatorC, 50, "", 1_000),            // empty (non-null) label survives as empty
        V3Bookmark("b-d", "item-epub", """{"href":"early.xhtml"}""", 50, null, 500), // same progress, earlier createdAt
        V3Bookmark("b-e", "item-epub", """{"href":"front.xhtml"}""", 10, "Front", 9_000),
        V3Bookmark("p-1", "item-pdf", pdfLocator, 30, "Page four", 2_000),
        V3Bookmark("p-2", "item-pdf", """{"version":1,"page":0}""", 0, null, 2_000),
        V3Bookmark("z-1", "item-cbz", cbzLocator, 99, null, 3_000),
        V3Bookmark("r-1", "item-cbr", cbrLocator, 70, "Page eight", 4_000),
    )

    @Test fun realV3DatabaseMigratesEveryBookmarkLosslesslyAndKeepsLegacyAndOtherTables() = runBlocking<Unit> {
        val name = "annotation-migration-v3.db"
        LegacyDatabases.createV3(context, name, items, bookmarks)
        // Pre-migration snapshots, read straight from the v3 file.
        val before = context.openOrCreateDatabase(name, 0, null).use { db ->
            assertEquals(3, db.version)
            assertFalse(LegacyDatabases.tableExists(db, "annotation"))
            mapOf(
                "library_item" to LegacyDatabases.dump(db, "library_item", "id"),
                "reading_state" to LegacyDatabases.dump(db, "reading_state", "itemId"),
                "reader_preference" to LegacyDatabases.dump(db, "reader_preference", "itemId"),
                "bookmark" to LegacyDatabases.dump(db, "bookmark", "id"),
                "appearance_preference" to LegacyDatabases.dump(db, "appearance_preference", "id"),
            ) to db.rawQuery("SELECT id FROM bookmark WHERE itemId = 'item-epub' ORDER BY progress ASC, createdAt ASC, id ASC", null).use { c ->
                generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
            }
        }
        val (tables, legacyOrder) = before
        assertEquals(listOf("b-e", "b-d", "b-a", "b-b", "b-c"), legacyOrder)

        val db = LegacyDatabases.open(context, name)
        try {
            val sql = db.openHelper.writableDatabase
            // Every source bookmark exists as a BOOKMARK annotation with identical content.
            val annotationRows = sql.query("SELECT id, libraryItemId, kind, locatorFormat, locatorJson, progressSnapshot, orderKey, title, selectedText, body, styleKey, createdAt, updatedAt FROM annotation ORDER BY id").use { c ->
                LegacyDatabases.rows(c)
            }
            assertEquals(bookmarks.size, annotationRows.size)
            val byId = annotationRows.associateBy { it[0]!! }
            for (b in bookmarks) {
                val row = requireNotNull(byId[b.id]) { "bookmark ${b.id} was lost" }
                assertEquals(b.itemId, row[1])
                assertEquals("BOOKMARK", row[2])
                assertEquals(if (b.itemId == "item-epub") "READIUM_LOCATOR_1" else "FIXED_PAGE_1", row[3])
                assertEquals("locator must be byte-for-byte identical", b.locator, row[4])
                assertEquals(b.progress.toString(), row[5])
                assertNull("orderKey is not set by the migration", row[6])
                assertEquals(b.label, row[7])            // NULL stays NULL, "" stays ""
                assertNull(row[8]); assertNull(row[9]); assertNull(row[10])
                assertEquals(b.createdAt.toString(), row[11])
                assertEquals("updatedAt = createdAt", b.createdAt.toString(), row[12])
            }

            // Legacy bookmark table is kept physically with its rows; no other table changed.
            sql.query("SELECT COUNT(*) FROM bookmark").use { it.moveToFirst(); assertEquals(bookmarks.size, it.getInt(0)) }
            val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
            raw.use {
                for ((table, order) in listOf("library_item" to "id", "reading_state" to "itemId", "reader_preference" to "itemId", "bookmark" to "id", "appearance_preference" to "id"))
                    assertEquals("$table must be unchanged by the migration", tables.getValue(table), LegacyDatabases.dump(it, table, order))
                assertEquals(4, it.version)
            }

            // The compatibility BookmarkRepository returns equivalent data in the exact legacy order.
            val compat = RoomBookmarkRepository(RoomAnnotationRepository(db.annotations(), neverValidated))
            val epub = compat.bookmarks("item-epub").first()
            assertEquals(legacyOrder, epub.map { it.id })
            val source = bookmarks.associateBy { it.id }
            for (b in epub) {
                val s = source.getValue(b.id)
                assertEquals(s.itemId, b.itemId); assertEquals(s.locator, b.locator); assertEquals(s.progress, b.progress)
                assertEquals(s.label, b.label); assertEquals(s.createdAt, b.createdAt)
            }
            assertEquals(listOf("p-2", "p-1"), compat.bookmarks("item-pdf").first().map { it.id })
            assertEquals(listOf("z-1"), compat.bookmarks("item-cbz").first().map { it.id })
            assertEquals(listOf("r-1"), compat.bookmarks("item-cbr").first().map { it.id })
            // Bookmarks written after migration go only to the canonical table.
            compat.addBookmark("item-epub", """{"href":"new.xhtml"}""", 77, null)
            sql.query("SELECT COUNT(*) FROM annotation").use { it.moveToFirst(); assertEquals(bookmarks.size + 1, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM bookmark").use { it.moveToFirst(); assertEquals("legacy table is never written", bookmarks.size, it.getInt(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migratedAnnotationSqlMatchesWhatRoomExpectsAndReopensCleanly() = runBlocking<Unit> {
        // Room validates the migrated tables/indices against the generated v4 schema when it opens; a second open
        // (no migration this time) must also pass, and the indices must exist under their exact names.
        val name = "annotation-migration-reopen.db"
        LegacyDatabases.createV3(context, name, items, bookmarks)
        LegacyDatabases.open(context, name).also { it.openHelper.writableDatabase; it.close() }
        val db = LegacyDatabases.open(context, name)
        try {
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'annotation' AND name LIKE 'index_annotation_%' ORDER BY name").use { c ->
                assertEquals(listOf("index_annotation_createdAt_id", "index_annotation_libraryItemId_kind_createdAt"), LegacyDatabases.rows(c).map { it[0] })
            }
            sql.query("SELECT COUNT(*) FROM annotation").use { it.moveToFirst(); assertEquals(bookmarks.size, it.getInt(0)) }
            // The annotation table has no foreign key (ADR-0025 decision 8).
            sql.query("PRAGMA foreign_key_list(annotation)").use { assertEquals(0, it.count) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun chainFromV1MigratesToV4WithEmptyAnnotationTable() = runBlocking<Unit> {
        val name = "annotation-chain-v1.db"
        LegacyDatabases.createV1(context, name)
        val db = LegacyDatabases.open(context, name)
        try {
            assertEquals("dark", db.appearance().observeTheme().first())
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT COUNT(*) FROM annotation").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM bookmark").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun chainFromV2KeepsDataAndMigratesToV4() = runBlocking<Unit> {
        val name = "annotation-chain-v2.db"
        LegacyDatabases.createV2(context, name)
        val db = LegacyDatabases.open(context, name)
        try {
            assertEquals("classic", db.appearance().observeTheme().first())
            val item = com.d4guilar.shelfos.data.library.RoomLibraryRepository(db.library()).publications.first().single()
            assertEquals("v2-item", item.id); assertEquals(40, item.progress)
            db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM annotation").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    /** A bookmark whose item format cannot be classified fails the migration; the file must stay a v3 database with
     * every bookmark intact (the migration's DDL rolled back), never be recreated empty or silently reinterpreted. */
    @Test fun unclassifiableFormatFailsMigrationAndLeavesTheV3DatabaseUntouched() = runBlocking<Unit> {
        val name = "annotation-failure-format.db"
        LegacyDatabases.createV3(context, name, items + ("item-odd" to "MOBI"),
            bookmarks + V3Bookmark("odd-1", "item-odd", """{"href":"x.xhtml"}""", 5, null, 4_000))
        assertFailsAndStaysV3(name, bookmarks.size + 1) { LegacyDatabases.open(context, name) }
        // A second attempt fails identically: no silent recovery state was left behind.
        assertFailsAndStaysV3(name, bookmarks.size + 1) { LegacyDatabases.open(context, name) }
        context.deleteDatabase(name)
    }

    /** The harder case: the real migration has already created the table AND copied every row when a later step
     * throws. Everything must still roll back, proving the migration runs transactionally. */
    @Test fun failureAfterTheFullCopyRollsEverythingBack() = runBlocking<Unit> {
        val name = "annotation-failure-late.db"
        LegacyDatabases.createV3(context, name, items, bookmarks)
        val failingLate = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ShelfDatabase.MIGRATION_3_4.migrate(db)
                db.query("SELECT COUNT(*) FROM annotation").use { it.moveToFirst(); check(it.getInt(0) == bookmarks.size) }
                throw IllegalStateException("injected failure after a complete copy")
            }
        }
        assertFailsAndStaysV3(name, bookmarks.size) {
            Room.databaseBuilder(context, ShelfDatabase::class.java, name)
                .addMigrations(ShelfDatabase.MIGRATION_1_2, ShelfDatabase.MIGRATION_2_3, failingLate).build()
        }
        // And the real migration still succeeds on that same untouched file afterwards.
        val db = LegacyDatabases.open(context, name)
        try { db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM annotation").use { it.moveToFirst(); assertEquals(bookmarks.size, it.getInt(0)) } }
        finally { db.close(); context.deleteDatabase(name) }
    }

    private fun assertFailsAndStaysV3(name: String, expectedBookmarks: Int, open: () -> ShelfDatabase) {
        val db = open()
        try {
            assertTrue("migration must fail", runCatching { db.openHelper.writableDatabase }.isFailure)
        } finally { runCatching { db.close() } }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals("database must remain at v3", 3, raw.version)
            assertFalse("annotation DDL must have rolled back", LegacyDatabases.tableExists(raw, "annotation"))
            raw.rawQuery("SELECT COUNT(*) FROM bookmark", null).use { it.moveToFirst(); assertEquals(expectedBookmarks, it.getInt(0)) }
            raw.rawQuery("SELECT COUNT(*) FROM library_item", null).use { it.moveToFirst(); assertTrue(it.getInt(0) >= items.size) }
        }
    }
}
