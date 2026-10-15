// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.LegacyDatabases.V4Annotation
import com.d4guilar.shelfos.core.database.ShelfDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4.5A.1: Room v5 -> v6 (additive `publication_cover`). A real v5 file built with raw SQL (repo convention, no
 * `MigrationTestHelper`) is opened through the real chain. Asserts every pre-existing row is byte-identical, the new
 * table's columns, defaults, composite primary key, cascade foreign key and indices, and that the migrated schema is
 * identical to a database Room creates fresh at v6 (and to the exported createSql).
 */
class CoverMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val items = listOf("item-epub" to "EPUB", "item-cbz" to "CBZ", "item-pdf" to "PDF")
    private val annotations = listOf(
        V4Annotation("a-hl", "item-epub", "HIGHLIGHT", "READIUM_LOCATOR_1", """{"href":"c.xhtml","text":{"highlight":"it's"}}""", 25, 0.25, null, "it's", "body", "amber", 2_000, 2_500),
        V4Annotation("a-bm", "item-cbz", "BOOKMARK", "FIXED_PAGE_1", """{"version":1,"page":3}""", 30, 0.3, null, null, null, null, 5_000, 5_000),
        V4Annotation("a-orphan", "gone-item", "NOTE", "FIXED_PAGE_1", """{"version":1,"page":1}""", 1, null, null, null, "kept", null, 6_000, 6_000),
    )
    private val tables = listOf("library_item" to "id", "reading_state" to "itemId", "reader_preference" to "itemId", "bookmark" to "id",
        "appearance_preference" to "id", "annotation" to "id", "annotation_source_snapshot" to "libraryItemId")

    private fun schema(db: androidx.sqlite.db.SupportSQLiteDatabase): List<Any> = listOf(
        db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'publication_cover'").use { it.moveToFirst(); it.getString(0) },
        db.query("PRAGMA table_info(publication_cover)").use(LegacyDatabases::rows),
        db.query("PRAGMA foreign_key_list(publication_cover)").use(LegacyDatabases::rows),
        db.query("PRAGMA index_list(publication_cover)").use(LegacyDatabases::rows),
    )

    @Test fun realV5DatabaseMigratesToV6KeepingEveryRowAndMatchingAFreshV6Schema() = runBlocking<Unit> {
        val name = "cover-migration-v5.db"
        LegacyDatabases.createV5(context, name, items, annotations)
        val before = context.openOrCreateDatabase(name, 0, null).use { db ->
            assertEquals(5, db.version)
            assertFalse(LegacyDatabases.tableExists(db, "publication_cover"))
            tables.associate { (table, order) -> table to LegacyDatabases.dump(db, table, order) }
        }
        val migrated = LegacyDatabases.open(context, name)
        val fresh = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val sql = migrated.openHelper.writableDatabase
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(6, raw.version)
                for ((table, order) in tables) assertEquals("$table must be unchanged by v5 -> v6", before.getValue(table), LegacyDatabases.dump(raw, table, order))
            }
            sql.query("SELECT COUNT(*) FROM publication_cover").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }

            val columns = sql.query("PRAGMA table_info(publication_cover)").use(LegacyDatabases::rows)
            // cid, name, type, notnull, dflt_value, pk
            assertEquals(listOf("libraryItemId", "slot", "status", "failureReason", "fileName", "mimeType", "representation", "sourceWidthPx",
                "sourceHeightPx", "storedWidthPx", "storedHeightPx", "storedBytes", "localSource", "sourceRef", "sourceChoice", "sourceRevision",
                "extractorVersion", "attempts", "interruptions", "attemptStartedAt", "attemptExtractorVersion", "nextEligibleAt", "parked", "updatedAt"),
                columns.map { it[1] })
            assertEquals("composite primary key in order", listOf("libraryItemId" to "1", "slot" to "2"), columns.filter { it[5] != "0" }.map { it[1] to it[5] })
            assertEquals(listOf("attempts" to "0", "interruptions" to "0", "parked" to "0"), columns.filter { it[4] != null }.map { it[1] to it[4] })
            val fk = sql.query("PRAGMA foreign_key_list(publication_cover)").use(LegacyDatabases::rows).single()
            // id, seq, table, from, to, on_update, on_delete, match
            assertEquals(listOf("library_item", "libraryItemId", "id", "NO ACTION", "CASCADE"), listOf(fk[2], fk[3], fk[4], fk[5], fk[6]))
            val indices = sql.query("PRAGMA index_list(publication_cover)").use(LegacyDatabases::rows)
            assertEquals("only the primary-key autoindex", listOf("pk"), indices.map { it[3] })
            assertFalse(schema(sql)[0].toString().contains("CHECK", ignoreCase = true))
            assertEquals("fresh install and upgrade have the same publication_cover schema", schema(fresh.openHelper.writableDatabase), schema(sql))
            assertEquals(ShelfDatabase.PUBLICATION_COVER_CREATE_SQL.replace("IF NOT EXISTS ", ""), schema(sql)[0])
        } finally { migrated.close(); fresh.close(); context.deleteDatabase(name) }
    }

    @Test fun deletingAnItemCascadesItsCoverRowsButNeverAnnotations() = runBlocking<Unit> {
        val name = "cover-migration-cascade.db"
        LegacyDatabases.createV5(context, name, items, annotations)
        val db = LegacyDatabases.open(context, name)
        try {
            val sql = db.openHelper.writableDatabase
            for (slot in listOf("LOCAL", "USER")) sql.execSQL("INSERT INTO publication_cover (libraryItemId, slot, status, extractorVersion, updatedAt) VALUES ('item-epub', '$slot', 'PENDING', 1, 1)")
            sql.execSQL("INSERT INTO publication_cover (libraryItemId, slot, status, extractorVersion, updatedAt) VALUES ('item-cbz', 'LOCAL', 'PENDING', 1, 1)")
            sql.query("SELECT attempts, interruptions, parked FROM publication_cover WHERE libraryItemId = 'item-cbz'").use {
                it.moveToFirst(); assertEquals(listOf(0, 0, 0), listOf(it.getInt(0), it.getInt(1), it.getInt(2)))
            }
            assertTrue("the FK rejects a cover for a missing item", runCatching {
                sql.execSQL("INSERT INTO publication_cover (libraryItemId, slot, status, extractorVersion, updatedAt) VALUES ('nobody', 'LOCAL', 'PENDING', 1, 1)")
            }.isFailure)
            val annotationsBefore = sql.query("SELECT * FROM annotation ORDER BY id").use(LegacyDatabases::rows)
            sql.execSQL("DELETE FROM library_item WHERE id = 'item-epub'")
            sql.query("SELECT libraryItemId FROM publication_cover").use { assertEquals(listOf(listOf("item-cbz")), LegacyDatabases.rows(it)) }
            assertEquals("annotations have no FK and are never cascaded by covers", annotationsBefore,
                sql.query("SELECT * FROM annotation ORDER BY id").use(LegacyDatabases::rows))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
