// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.LegacyDatabases.V4Annotation
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.library.RoomCoverRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4.5C.3A: Room v8 -> v9 (six nullable origin columns on `publication_cover`). A real v8 file with LOCAL and legacy USER rows is opened through
 * the real chain: every existing value is unchanged, the new columns are NULL (unknown origin, never invented), the table's columns/keys equal a fresh
 * v9 database, and a provider-selected USER cover written afterwards keeps its origin.
 */
class CoverOriginMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val items = listOf("item-epub" to "EPUB", "item-cbz" to "CBZ", "item-pdf" to "PDF")
    private val annotations = listOf(V4Annotation("a-bm", "item-cbz", "BOOKMARK", "FIXED_PAGE_1", """{"version":1,"page":3}""", 30, 0.3, null, null, null, null, 5_000, 5_000))
    private val originColumns = listOf("originKind", "originProvider", "originEdition", "originRefKind", "originRefValue", "originSelectedAt")

    @Test fun realV8DatabaseMigratesToV9KeepingEveryCoverRowAndLeavingOriginUnknown() = runBlocking<Unit> {
        val name = "cover-origin-v8.db"
        LegacyDatabases.createV8(context, name, items, annotations)
        val before = context.openOrCreateDatabase(name, 0, null).use { db ->
            assertEquals(8, db.version)
            db.execSQL("INSERT INTO publication_cover (libraryItemId, slot, status, fileName, mimeType, representation, sourceWidthPx, sourceHeightPx, storedWidthPx, storedHeightPx, storedBytes, extractorVersion, updatedAt) " +
                "VALUES ('item-epub', 'USER', 'READY', 'user-0123456789abcdef.jpg', 'image/jpeg', 'ORIGINAL', 400, 600, 400, 600, 50000, 0, 8000)")
            LegacyDatabases.dump(db, "publication_cover", "libraryItemId, slot") to listOf("library_item" to "id", "reading_state" to "itemId", "annotation" to "id", "publication_metadata" to "libraryItemId")
                .associate { (t, o) -> t to LegacyDatabases.dump(db, t, o) }
        }
        val migrated = LegacyDatabases.open(context, name)
        val fresh = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val sql = migrated.openHelper.writableDatabase
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(9, raw.version)
                for ((table, rows) in before.second) assertEquals("$table must be unchanged", rows, LegacyDatabases.dump(raw, table, if (table == "annotation") "id" else if (table == "reading_state") "itemId" else if (table == "publication_metadata") "libraryItemId" else "id"))
            }
            val oldColumns = before.first.first().size
            val after = sql.query("SELECT * FROM publication_cover ORDER BY libraryItemId, slot").use(LegacyDatabases::rows)
            assertEquals(before.first.size, after.size)
            assertEquals("every pre-existing value is byte-identical", before.first, after.map { it.take(oldColumns) })
            assertTrue("every new column is NULL = unknown origin", after.all { row -> row.drop(oldColumns).all { it == null } })
            val columns = sql.query("PRAGMA table_info(publication_cover)").use(LegacyDatabases::rows)
            assertEquals(originColumns, columns.drop(oldColumns).map { it[1] })
            fun info(db: androidx.sqlite.db.SupportSQLiteDatabase) = db.query("PRAGMA table_info(publication_cover)").use(LegacyDatabases::rows)
            assertEquals("fresh and migrated columns are identical", info(fresh.openHelper.writableDatabase), info(sql))
            assertEquals(ShelfDatabase.COVER_ORIGIN_COLUMNS.map { it.first }, originColumns)

            // The legacy USER row reads back as unknown origin; a later explicit online choice keeps its origin across a reopen.
            val repo = RoomCoverRepository(migrated.covers())
            assertNull("legacy USER cover: unknown origin", repo.userOrigin("item-epub"))
            sql.execSQL("UPDATE publication_cover SET originKind = 'PROVIDER_SELECTED', originProvider = 'openlibrary', originEdition = 'OL1M', originRefKind = 'id', originRefValue = '5', originSelectedAt = 9000 WHERE libraryItemId = 'item-epub' AND slot = 'USER'")
            val origin = repo.userOrigin("item-epub")!!
            assertEquals(listOf("openlibrary", "OL1M", "id", "5", 9000L), listOf(origin.providerId, origin.editionRef, origin.refKind, origin.refValue, origin.selectedAtMs))
        } finally { migrated.close(); fresh.close(); context.deleteDatabase(name) }
    }
}
