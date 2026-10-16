// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.LegacyDatabases.V4Annotation
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.preferences.RoomCoverPresentationRepository
import com.d4guilar.shelfos.domain.library.CoverFit
import com.d4guilar.shelfos.domain.library.CoverPresentation
import com.d4guilar.shelfos.domain.library.CoverPresentations
import com.d4guilar.shelfos.domain.library.CoverShape
import com.d4guilar.shelfos.domain.library.MediaCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4.5A.2: Room v6 -> v7 (additive `cover_presentation_preference`). A real v6 file built with raw SQL (repo
 * convention, no `MigrationTestHelper`) holding READY, parked and open-attempt cover rows, annotations, an orphan snapshot,
 * reader/appearance preferences and a legacy bookmark is opened through the real chain. Every pre-existing table must be
 * byte-identical, the new table must match a fresh v7 database and the exported createSql, and saved preferences must
 * survive closing and reopening the database. Older files (v1, v5) also reach v7.
 */
class CoverPresentationMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val items = listOf("item-epub" to "EPUB", "item-cbz" to "CBZ", "item-pdf" to "PDF")
    private val annotations = listOf(
        V4Annotation("a-hl", "item-epub", "HIGHLIGHT", "READIUM_LOCATOR_1", """{"href":"c.xhtml","text":{"highlight":"it's"}}""", 25, 0.25, null, "it's", "body", "amber", 2_000, 2_500),
        V4Annotation("a-orphan", "gone-item", "NOTE", "FIXED_PAGE_1", """{"version":1,"page":1}""", 1, null, null, null, "kept", null, 6_000, 6_000),
    )
    private val tables = listOf("library_item" to "id", "reading_state" to "itemId", "reader_preference" to "itemId", "bookmark" to "id",
        "appearance_preference" to "id", "annotation" to "id", "annotation_source_snapshot" to "libraryItemId", "publication_cover" to "libraryItemId, slot")

    private fun schema(db: androidx.sqlite.db.SupportSQLiteDatabase): List<Any> = listOf(
        db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'cover_presentation_preference'").use { it.moveToFirst(); it.getString(0) },
        db.query("PRAGMA table_info(cover_presentation_preference)").use(LegacyDatabases::rows),
        db.query("PRAGMA index_list(cover_presentation_preference)").use(LegacyDatabases::rows),
    )

    @Test fun realV6DatabaseMigratesToV7KeepingEveryRowAndMatchingAFreshV7Schema() = runBlocking<Unit> {
        val name = "presentation-migration-v6.db"
        LegacyDatabases.createV6(context, name, items, annotations)
        val before = context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("INSERT INTO bookmark VALUES('legacy-bm', 'item-epub', '{\"href\":\"c.xhtml\"}', 12, 'legacy', 1500)")
            assertEquals(6, db.version)
            assertFalse(LegacyDatabases.tableExists(db, "cover_presentation_preference"))
            tables.associate { (table, order) -> table to LegacyDatabases.dump(db, table, order) }
        }
        assertEquals("fixture has READY, parked and open-attempt cover rows", 3, before.getValue("publication_cover").size)
        val migrated = LegacyDatabases.open(context, name)
        val fresh = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val sql = migrated.openHelper.writableDatabase
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(8, raw.version)
                for ((table, order) in tables) assertEquals("$table must be unchanged by v6 -> current", before.getValue(table), LegacyDatabases.dump(raw, table, order))
            }
            sql.query("SELECT COUNT(*) FROM cover_presentation_preference").use { it.moveToFirst(); assertEquals("no backfill: empty = defaults", 0, it.getInt(0)) }
            val columns = sql.query("PRAGMA table_info(cover_presentation_preference)").use(LegacyDatabases::rows)
            // cid, name, type, notnull, dflt_value, pk
            assertEquals(listOf(listOf("scope", "TEXT", "1", "1"), listOf("shape", "TEXT", "1", "0"), listOf("customWidth", "INTEGER", "0", "0"),
                listOf("customHeight", "INTEGER", "0", "0"), listOf("fit", "TEXT", "1", "0")), columns.map { listOf(it[1], it[2], it[3], it[5]) })
            assertFalse(schema(sql)[0].toString().contains("CHECK", ignoreCase = true))
            assertEquals("fresh install and upgrade have the same table", schema(fresh.openHelper.writableDatabase), schema(sql))
            assertEquals(ShelfDatabase.COVER_PRESENTATION_CREATE_SQL.replace("IF NOT EXISTS ", ""), schema(sql)[0])
            assertEquals(CoverPresentations(), RoomCoverPresentationRepository(migrated.coverPresentation()).presentations.first())
        } finally { migrated.close(); fresh.close(); context.deleteDatabase(name) }
    }

    @Test fun preferencesSurviveReopeningTheDatabaseAndNeverTouchCovers() = runBlocking<Unit> {
        val name = "presentation-reopen.db"
        LegacyDatabases.createV6(context, name, items, annotations)
        val custom = CoverPresentation(CoverShape.CUSTOM, CoverFit.FIT, 5, 7)
        val covers = LegacyDatabases.open(context, name).let { db ->
            try {
                val repo = RoomCoverPresentationRepository(db.coverPresentation())
                repo.setGlobal(CoverPresentation(CoverShape.RATIO_1_1, CoverFit.FIT))
                repo.setOverride(MediaCategory.MANGA, custom)
                repo.setOverride(MediaCategory.DOCUMENT, CoverPresentation(CoverShape.RATIO_3_4, CoverFit.CROP))
                repo.setOverride(MediaCategory.DOCUMENT, null)
                db.openHelper.writableDatabase.query("SELECT * FROM publication_cover ORDER BY libraryItemId").use(LegacyDatabases::rows)
            } finally { db.close() }
        }
        val reopened = LegacyDatabases.open(context, name)
        try {
            val p = RoomCoverPresentationRepository(reopened.coverPresentation()).presentations.first()
            assertEquals(CoverPresentation(CoverShape.RATIO_1_1, CoverFit.FIT), p.effective(MediaCategory.BOOK))
            assertEquals(custom, p.effective(MediaCategory.MANGA))
            assertEquals("cleared override returns Documents to Original", CoverShape.ORIGINAL, p.effective(MediaCategory.DOCUMENT).shape)
            assertEquals(covers, reopened.openHelper.writableDatabase.query("SELECT * FROM publication_cover ORDER BY libraryItemId").use(LegacyDatabases::rows))
            // An unexpected stored value (e.g. written by a future version) falls back safely instead of crashing.
            reopened.openHelper.writableDatabase.execSQL("UPDATE cover_presentation_preference SET shape = 'HEXAGON' WHERE scope = 'MANGA'")
            assertEquals(p.global, RoomCoverPresentationRepository(reopened.coverPresentation()).presentations.first().effective(MediaCategory.MANGA))
        } finally { reopened.close(); context.deleteDatabase(name) }
    }

    @Test fun olderDatabasesReachV7ThroughTheWholeChain() = runBlocking<Unit> {
        for ((version, create) in listOf<Pair<Int, (String) -> Unit>>(
            1 to { n -> LegacyDatabases.createV1(context, n) },
            5 to { n -> LegacyDatabases.createV5(context, n, items, annotations) },
        )) {
            val name = "presentation-chain-v$version.db"
            create(name)
            val db = LegacyDatabases.open(context, name)
            try {
                val sql = db.openHelper.writableDatabase
                sql.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals("v$version", 8, it.getInt(0)) }
                assertEquals(listOf("cover_presentation_preference", "publication_cover"), sql.query(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('cover_presentation_preference', 'publication_cover') ORDER BY name",
                ).use(LegacyDatabases::rows).map { it[0] })
                sql.query("SELECT themeKey FROM appearance_preference").use { it.moveToFirst(); assertNotNull("v$version theme kept", it.getString(0)) }
                assertEquals(CoverPresentations(), RoomCoverPresentationRepository(db.coverPresentation()).presentations.first())
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }
}
