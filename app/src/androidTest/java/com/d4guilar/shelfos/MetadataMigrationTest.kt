// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.LegacyDatabases.V4Annotation
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.data.metadata.RoomMetadataRepository
import com.d4guilar.shelfos.domain.annotations.KnowledgePolicy
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4.5B: Room v7 -> v8 (additive `publication_metadata`). A real raw-SQL v7 file (covers with READY/parked/open
 * attempt rows, cover presentation preferences, annotations, an orphan snapshot, reader state and a legacy bookmark) is
 * opened through the real chain: every pre-existing table is byte-identical and the new table matches a fresh v8 database
 * and `8.json`. Then the real repository: provenance round-trips through Room, user edits survive reopening, a removed
 * item is never resurrected, and both removal policies delete the metadata row.
 */
class MetadataMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val items = listOf("item-epub" to "EPUB", "item-cbz" to "CBZ", "item-pdf" to "PDF")
    private val annotations = listOf(
        V4Annotation("a-hl", "item-epub", "HIGHLIGHT", "READIUM_LOCATOR_1", """{"href":"c.xhtml"}""", 25, 0.25, null, "it's", "body", "amber", 2_000, 2_500),
        V4Annotation("a-orphan", "gone-item", "NOTE", "FIXED_PAGE_1", """{"version":1,"page":1}""", 1, null, null, null, "kept", null, 6_000, 6_000),
    )
    private val tables = listOf("library_item" to "id", "reading_state" to "itemId", "reader_preference" to "itemId", "bookmark" to "id",
        "appearance_preference" to "id", "annotation" to "id", "annotation_source_snapshot" to "libraryItemId",
        "publication_cover" to "libraryItemId, slot", "cover_presentation_preference" to "scope")

    private fun schema(db: androidx.sqlite.db.SupportSQLiteDatabase): List<Any> = listOf(
        db.query("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'publication_metadata'").use { it.moveToFirst(); it.getString(0) },
        db.query("PRAGMA table_info(publication_metadata)").use(LegacyDatabases::rows),
        db.query("PRAGMA foreign_key_list(publication_metadata)").use(LegacyDatabases::rows),
        db.query("PRAGMA index_list(publication_metadata)").use(LegacyDatabases::rows),
    )

    @Test fun realV7DatabaseMigratesToV8KeepingEveryRowAndMatchingAFreshV8Schema() = runBlocking<Unit> {
        val name = "metadata-migration-v7.db"
        LegacyDatabases.createV7(context, name, items, annotations)
        val before = context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL("INSERT INTO bookmark VALUES('legacy-bm', 'item-epub', '{\"href\":\"c.xhtml\"}', 12, 'legacy', 1500)")
            assertEquals(7, db.version)
            assertFalse(LegacyDatabases.tableExists(db, "publication_metadata"))
            tables.associate { (table, order) -> table to LegacyDatabases.dump(db, table, order) }
        }
        assertEquals(3, before.getValue("publication_cover").size)
        assertEquals(2, before.getValue("cover_presentation_preference").size)
        val migrated = LegacyDatabases.open(context, name)
        val fresh = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        try {
            val sql = migrated.openHelper.writableDatabase
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                assertEquals(8, raw.version)
                for ((table, order) in tables) assertEquals("$table must be unchanged by v7 -> v8", before.getValue(table), LegacyDatabases.dump(raw, table, order))
            }
            sql.query("SELECT COUNT(*) FROM publication_metadata").use { it.moveToFirst(); assertEquals("never opens publications during migration", 0, it.getInt(0)) }
            val fk = sql.query("PRAGMA foreign_key_list(publication_metadata)").use(LegacyDatabases::rows).single()
            assertEquals(listOf("library_item", "libraryItemId", "id", "CASCADE"), listOf(fk[2], fk[3], fk[4], fk[6]))
            assertFalse(schema(sql)[0].toString().contains("CHECK", ignoreCase = true))
            assertEquals("fresh install and upgrade have the same table", schema(fresh.openHelper.writableDatabase), schema(sql))
            assertEquals(ShelfDatabase.PUBLICATION_METADATA_CREATE_SQL.replace("IF NOT EXISTS ", ""), schema(sql)[0])
        } finally { migrated.close(); fresh.close(); context.deleteDatabase(name) }
    }

    @Test fun olderDatabasesReachV8ThroughTheWholeChain() = runBlocking<Unit> {
        for ((version, create) in listOf<Pair<Int, (String) -> Unit>>(
            1 to { n -> LegacyDatabases.createV1(context, n) },
            3 to { n -> LegacyDatabases.createV3(context, n, items, emptyList()) },
            6 to { n -> LegacyDatabases.createV6(context, n, items, annotations) },
        )) {
            val name = "metadata-chain-v$version.db"
            create(name)
            val db = LegacyDatabases.open(context, name)
            try {
                val sql = db.openHelper.writableDatabase
                sql.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals("v$version", 8, it.getInt(0)) }
                assertTrue(sql.query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'publication_metadata'").use { it.moveToFirst(); it.getInt(0) == 1 })
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun provenanceAndUserEditsSurviveReopeningAndRemovedItemsAreNeverResurrected() = runBlocking<Unit> {
        val name = "metadata-repo.db"
        LegacyDatabases.createV7(context, name, items, annotations)
        val isbn = PublicationIdentifier(IdentifierType.ISBN_13, "9780306406157")
        val exact = FieldProvenance(MetadataOrigin.EXACT_IDENTIFIER_MATCH, "fake", "ed/1", MatchConfidence.VERY_HIGH, 77)
        LegacyDatabases.open(context, name).let { db ->
            try {
                val repo = RoomMetadataRepository(db) { 1_000 }
                val embedded = FieldProvenance(MetadataOrigin.EMBEDDED)
                assertTrue(repo.apply("item-epub", listOf(Proposed(MetadataField.PUBLISHER, "Example Press", embedded),
                    Proposed(MetadataField.IDENTIFIERS, listOf(isbn), embedded), Proposed(MetadataField.SERIES, "S", FieldProvenance(MetadataOrigin.FILENAME_INFERENCE)),
                    Proposed(MetadataField.CREDITS, listOf(Credit(CreditRole.WRITER, "Jane")), embedded)), analysisVersion = 1))
                assertTrue(repo.apply("item-epub", listOf(Proposed(MetadataField.SERIES, "The Series", exact), Proposed(MetadataField.TITLE, "Exact Title", exact))))
                assertTrue(repo.editByUser("item-epub", MetadataValues(publisher = "Example Press"), MetadataValues(publisher = "My Press", language = "pt-BR"), 5))
                assertFalse("a removed item's late result is dropped", repo.apply("never-existed", listOf(Proposed(MetadataField.PUBLISHER, "X", embedded)), 1))
            } finally { db.close() }
        }
        val reopened = LegacyDatabases.open(context, name)
        try {
            val repo = RoomMetadataRepository(reopened)
            val m = repo.resolved("item-epub")!!
            assertEquals("My Press", m.values.publisher)
            assertEquals(MetadataOrigin.USER, m.origin(MetadataField.PUBLISHER))
            assertEquals("pt-BR", m.values.language)
            assertEquals(exact, m.provenance[MetadataField.SERIES])
            assertEquals("Exact Title", m.title)
            assertEquals(exact, m.provenance[MetadataField.TITLE])
            assertEquals(listOf(isbn), m.values.identifiers)
            assertEquals(listOf(Credit(CreditRole.WRITER, "Jane")), m.values.credits)
            val sql = reopened.openHelper.writableDatabase
            sql.query("SELECT titleOrigin FROM library_item WHERE id = 'item-epub'").use { it.moveToFirst(); assertEquals("exact_identifier_match", it.getString(0)) }
            sql.query("SELECT localAnalysisVersion FROM publication_metadata WHERE libraryItemId = 'item-epub'").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            assertNull(repo.resolved("never-existed"))
            sql.query("SELECT COUNT(*) FROM publication_metadata WHERE libraryItemId = 'never-existed'").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            // An automatic pass after the edit leaves every USER field alone.
            repo.apply("item-epub", listOf(Proposed(MetadataField.PUBLISHER, "Example Press", FieldProvenance(MetadataOrigin.EMBEDDED))), 2)
            assertEquals("My Press", repo.resolved("item-epub")!!.values.publisher)
            // Unknown stored values degrade safely instead of crashing.
            sql.execSQL("UPDATE publication_metadata SET identifiersJson = '{broken', provenanceJson = '[[\"SERIES\",\"FUTURE\",null,null,null,null]]', publishedDate = 'soon' WHERE libraryItemId = 'item-epub'")
            val degraded = repo.resolved("item-epub")!!
            assertTrue(degraded.values.identifiers.isEmpty())
            assertEquals(MetadataOrigin.UNKNOWN, degraded.origin(MetadataField.SERIES))
            assertNull(degraded.values.publishedDate)
            // Both removal policies delete the metadata row; annotations follow their own policy.
            repo.apply("item-cbz", listOf(Proposed(MetadataField.ISSUE, "1", FieldProvenance(MetadataOrigin.EMBEDDED))), 1)
            val library = RoomLibraryRepository(reopened.library())
            library.remove("item-epub", KnowledgePolicy.KEEP)
            library.remove("item-cbz", KnowledgePolicy.DELETE)
            sql.query("SELECT COUNT(*) FROM publication_metadata").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("SELECT COUNT(*) FROM annotation WHERE libraryItemId = 'item-epub'").use { it.moveToFirst(); assertEquals("KEEP keeps notes", 1, it.getInt(0)) }
            assertEquals(listOf("item-pdf"), repo.pendingLocal(1, null, 10).map { it.id })
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
