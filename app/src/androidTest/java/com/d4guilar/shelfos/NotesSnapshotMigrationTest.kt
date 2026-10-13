// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.LegacyDatabases.V4Annotation
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.AnnotationLocatorValidator
import com.d4guilar.shelfos.domain.annotations.KnowledgePolicy
import com.d4guilar.shelfos.domain.annotations.NotesQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4D: Room v4 -> v5 (additive `annotation_source_snapshot`). Real older database files built with raw SQL (the repo's
 * migration convention, no `MigrationTestHelper`), opened through Room with the real migration chain. Asserts that every
 * existing annotation (legacy 4A bookmarks, 4B highlights/notes, 4C fixed bookmarks/notes) and library row survives byte for
 * byte, that the new table has exactly the planned columns and no foreign key, and that a failed step rolls back to v4.
 */
class NotesSnapshotMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val readium = """{"href":"/OEBPS/ch2.xhtml","type":"application/xhtml+xml","title":"Chapter \"2\" é中","locations":{"progression":0.5,"position":12,"totalProgression":0.25},"text":{"highlight":"it's \"quoted\""}}"""
    private val items = listOf("item-epub" to "EPUB", "item-pdf" to "PDF", "item-cbz" to "CBZ")
    private val annotations = listOf(
        V4Annotation("a-bm-epub", "item-epub", "BOOKMARK", "READIUM_LOCATOR_1", """{"href":"c1.xhtml"}""", 10, null, "label", null, null, null, 1_000, 1_000),
        V4Annotation("a-hl", "item-epub", "HIGHLIGHT", "READIUM_LOCATOR_1", readium, 25, 0.25, null, "it's \"quoted\"", "my body", "amber", 2_000, 2_500),
        V4Annotation("a-note-sel", "item-epub", "NOTE", "READIUM_LOCATOR_1", readium, 25, 0.26, null, "quoted", "a note", null, 3_000, 3_000),
        V4Annotation("a-note-pos", "item-epub", "NOTE", "READIUM_LOCATOR_1", """{"href":"c3.xhtml"}""", 60, null, null, null, "position note", null, 4_000, 4_000),
        V4Annotation("a-bm-pdf", "item-pdf", "BOOKMARK", "FIXED_PAGE_1", """{"version":1,"page":3}""", 30, 0.3, null, null, null, null, 5_000, 5_000),
        V4Annotation("a-note-pdf", "item-pdf", "NOTE", "FIXED_PAGE_1", """{"version":1,"page":7}""", 70, 0.7, null, null, "page note", null, 6_000, 6_100),
        V4Annotation("a-bm-cbz", "item-cbz", "BOOKMARK", "FIXED_PAGE_1", """{"version":1,"page":0}""", 0, 0.0, null, null, null, null, 7_000, 7_000),
        V4Annotation("a-unknown", "item-cbz", "FUTURE_KIND", "FUTURE_FORMAT", "{\"x\":1}", 1, null, null, null, null, null, 8_000, 8_000),
    )
    private val tables = listOf("library_item" to "id", "reading_state" to "itemId", "reader_preference" to "itemId", "bookmark" to "id",
        "appearance_preference" to "id", "annotation" to "id")

    private fun snapshotOf(name: String) = context.openOrCreateDatabase(name, 0, null).use { db ->
        assertEquals(4, db.version)
        assertFalse(LegacyDatabases.tableExists(db, "annotation_source_snapshot"))
        tables.associate { (table, order) -> table to LegacyDatabases.dump(db, table, order) }
    }

    @Test fun realV4DatabaseMigratesToV5KeepingEveryRowAndCreatingTheSnapshotTable() = runBlocking<Unit> {
        val name = "snapshot-migration-v4.db"
        LegacyDatabases.createV4(context, name, items, annotations)
        val before = snapshotOf(name)
        assertEquals(annotations.size, before.getValue("annotation").size)

        val db = LegacyDatabases.open(context, name)
        try {
            val sql = db.openHelper.writableDatabase
            // The new table exists with exactly the planned display-only columns, empty, and no foreign key.
            sql.query("PRAGMA table_info(annotation_source_snapshot)").use { c ->
                val columns = LegacyDatabases.rows(c).map { it[1] to it[2] }
                assertEquals(listOf("libraryItemId" to "TEXT", "title" to "TEXT", "creator" to "TEXT", "category" to "TEXT", "format" to "TEXT"), columns)
            }
            sql.query("PRAGMA table_info(annotation_source_snapshot)").use { c -> assertEquals(listOf("1", "0", "0", "0", "0"), LegacyDatabases.rows(c).map { it[5] }) }
            sql.query("PRAGMA foreign_key_list(annotation_source_snapshot)").use { assertEquals(0, it.count) }
            sql.query("SELECT COUNT(*) FROM annotation_source_snapshot").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            // Every pre-existing table is unchanged, row for row (library, reading state, preferences, legacy bookmarks, annotations).
            val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
            raw.use {
                assertEquals(5, it.version)
                for ((table, order) in tables) assertEquals("$table must be unchanged by v4 -> v5", before.getValue(table), LegacyDatabases.dump(it, table, order))
            }
            // Legacy locators are still exactly what 4A/4B/4C wrote, and the repository reads them.
            val repo = RoomAnnotationRepository(db.annotations(), AnnotationLocatorValidator { _, _ -> true })
            val page = repo.notes(NotesQuery(), null, 100)
            assertEquals("known kinds only; the unknown row stays untouched in the table", annotations.size - 1, page.rows.size)
            assertEquals(readium, repo.get("a-hl")!!.locatorJson)
            assertEquals("""{"version":1,"page":7}""", repo.get("a-note-pdf")!!.locatorJson)
            assertTrue(page.rows.all { it.source.live })
            assertEquals(0, repo.countOrphans())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun migratedDatabaseReopensCleanlyAndKeepRemovalWorksOnIt() = runBlocking<Unit> {
        val name = "snapshot-migration-keep.db"
        LegacyDatabases.createV4(context, name, items, annotations)
        LegacyDatabases.open(context, name).also { it.openHelper.writableDatabase; it.close() }
        val db = LegacyDatabases.open(context, name)
        try {
            val library = RoomLibraryRepository(db.library())
            val repo = RoomAnnotationRepository(db.annotations(), AnnotationLocatorValidator { _, _ -> true })
            val before = repo.observeForPublication("item-epub").first().annotations
            library.remove("item-epub", KnowledgePolicy.KEEP)
            assertTrue(library.publications.first().none { it.id == "item-epub" })
            val kept = repo.observeForPublication("item-epub").first().annotations
            assertEquals("kept annotations are identical, locators included", before, kept)
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT libraryItemId, title, creator, category, format FROM annotation_source_snapshot").use { c ->
                assertEquals(listOf(listOf("item-epub", "Title item-epub", "Creator", "BOOK", "EPUB")), LegacyDatabases.rows(c))
            }
            assertEquals(before.size, repo.countOrphans())
            // Removing a publication whose knowledge was KEPT does not touch another publication's annotations.
            assertEquals(2, repo.observeForPublication("item-pdf").first().annotations.size)
            assertTrue(repo.notes(NotesQuery(publicationId = "item-epub"), null, 100).rows.all { !it.source.live && it.source.title == "Title item-epub" })
            assertEquals(AnnotationKind.HIGHLIGHT, repo.get("a-hl")!!.kind)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    /** A failure after the snapshot table was created rolls everything back; the file stays a v4 database with all rows. */
    @Test fun failureAfterTheSnapshotTableIsCreatedRollsBackToV4() = runBlocking<Unit> {
        val name = "snapshot-migration-failure.db"
        LegacyDatabases.createV4(context, name, items, annotations)
        val before = snapshotOf(name)
        val failing = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                ShelfDatabase.MIGRATION_4_5.migrate(db)
                db.query("SELECT COUNT(*) FROM annotation_source_snapshot").use { it.moveToFirst(); check(it.getInt(0) == 0) }
                throw IllegalStateException("injected failure after the table was created")
            }
        }
        val failed = Room.databaseBuilder(context, ShelfDatabase::class.java, name)
            .addMigrations(ShelfDatabase.MIGRATION_1_2, ShelfDatabase.MIGRATION_2_3, ShelfDatabase.MIGRATION_3_4, failing).build()
        try { assertTrue("migration must fail", runCatching { failed.openHelper.writableDatabase }.isFailure) } finally { runCatching { failed.close() } }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals("database must remain at v4", 4, raw.version)
            assertFalse(LegacyDatabases.tableExists(raw, "annotation_source_snapshot"))
            for ((table, order) in tables) assertEquals(before.getValue(table), LegacyDatabases.dump(raw, table, order))
        }
        // The real migration still succeeds on the untouched file afterwards (no destructive fallback ran).
        val db = LegacyDatabases.open(context, name)
        try { db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM annotation").use { it.moveToFirst(); assertEquals(annotations.size, it.getInt(0)) } }
        finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun chainFromV3ReachesV5WithBookmarksAsAnnotationsAndAnEmptySnapshotTable() = runBlocking<Unit> {
        val name = "snapshot-chain-v3.db"
        LegacyDatabases.createV3(context, name, items, listOf(LegacyDatabases.V3Bookmark("b1", "item-epub", """{"href":"x.xhtml"}""", 5, null, 100)))
        val db = LegacyDatabases.open(context, name)
        try {
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT COUNT(*) FROM annotation").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM annotation_source_snapshot").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(5, it.getInt(0)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
