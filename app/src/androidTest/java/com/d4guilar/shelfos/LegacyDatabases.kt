// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.addEpubBookmark
import com.d4guilar.shelfos.data.annotations.observeEpubBookmarks

/**
 * Raw-SQL builders for REAL pre-4A database files, matching the exported schemas 1.json..3.json statement for
 * statement (the repo's migration-test convention: no `room-testing`/`MigrationTestHelper`). Never instantiates the
 * current Room schema, so a migration test cannot accidentally start from a v4 database.
 */
object LegacyDatabases {
    const val APPEARANCE = "CREATE TABLE appearance_preference (id INTEGER NOT NULL, themeKey TEXT NOT NULL, PRIMARY KEY(id))"
    const val LIBRARY_ITEM = "CREATE TABLE library_item (id TEXT NOT NULL, sourceUri TEXT NOT NULL, title TEXT NOT NULL, creator TEXT NOT NULL, category TEXT NOT NULL, format TEXT NOT NULL, fileName TEXT NOT NULL, byteSize INTEGER, favorite INTEGER NOT NULL, addedAt INTEGER NOT NULL, available INTEGER NOT NULL, managedPath TEXT, titleOrigin TEXT NOT NULL, creatorOrigin TEXT NOT NULL, PRIMARY KEY(id))"
    const val LIBRARY_ITEM_INDEX = "CREATE UNIQUE INDEX index_library_item_sourceUri ON library_item (sourceUri)"
    const val READING_STATE = "CREATE TABLE reading_state (itemId TEXT NOT NULL, locator TEXT NOT NULL, progress INTEGER NOT NULL, lastRead INTEGER NOT NULL, PRIMARY KEY(itemId), FOREIGN KEY(itemId) REFERENCES library_item(id) ON UPDATE NO ACTION ON DELETE CASCADE )"
    const val READER_PREFERENCE = "CREATE TABLE reader_preference (itemId TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(itemId))"
    const val BOOKMARK = "CREATE TABLE bookmark (id TEXT NOT NULL, itemId TEXT NOT NULL, locator TEXT NOT NULL, progress INTEGER NOT NULL, label TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(itemId) REFERENCES library_item(id) ON UPDATE NO ACTION ON DELETE CASCADE )"
    const val BOOKMARK_INDEX = "CREATE INDEX index_bookmark_itemId ON bookmark (itemId)"

    /** One library row: id, format, plus fixed synthetic metadata. */
    fun libraryRow(id: String, format: String, category: String = "BOOK") =
        "INSERT INTO library_item VALUES('$id', 'test:$id', 'Title $id', 'Creator', '$category', '$format', '$id.${format.lowercase()}', 1000, 0, 1000, 1, NULL, 'filename', 'unknown')"

    /** SQL-escapes a string literal. */
    fun q(s: String?) = if (s == null) "NULL" else "'" + s.replace("'", "''") + "'"

    fun createV1(context: Context, name: String) {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL(APPEARANCE); db.execSQL("INSERT INTO appearance_preference VALUES(0, 'dark')")
            db.version = 1
        }
    }

    fun createV2(context: Context, name: String, withData: Boolean = true) {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL(APPEARANCE); db.execSQL("INSERT INTO appearance_preference VALUES(0, 'classic')")
            db.execSQL(LIBRARY_ITEM); db.execSQL(LIBRARY_ITEM_INDEX); db.execSQL(READING_STATE); db.execSQL(READER_PREFERENCE)
            if (withData) {
                db.execSQL(libraryRow("v2-item", "EPUB"))
                db.execSQL("INSERT INTO reading_state VALUES('v2-item', '{\"href\":\"c1.xhtml\"}', 40, 2000)")
                db.execSQL("INSERT INTO reader_preference VALUES('v2-item', '{\"scroll\":true}')")
            }
            db.version = 2
        }
    }

    /** A bookmark row to insert into a v3 database. */
    data class V3Bookmark(val id: String, val itemId: String, val locator: String, val progress: Int, val label: String?, val createdAt: Long)

    /** Creates a real v3 database (all five legacy tables + indices) with the given content. */
    fun createV3(context: Context, name: String, items: List<Pair<String, String>>, bookmarks: List<V3Bookmark>) {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL(APPEARANCE); db.execSQL("INSERT INTO appearance_preference VALUES(0, 'dark')")
            db.execSQL(LIBRARY_ITEM); db.execSQL(LIBRARY_ITEM_INDEX); db.execSQL(READING_STATE); db.execSQL(READER_PREFERENCE)
            db.execSQL(BOOKMARK); db.execSQL(BOOKMARK_INDEX)
            for ((id, format) in items) {
                db.execSQL(libraryRow(id, format))
                db.execSQL("INSERT INTO reading_state VALUES('$id', '{\"href\":\"resume-$id.xhtml\"}', 33, 5000)")
                db.execSQL("INSERT INTO reader_preference VALUES('$id', '{\"scroll\":true,\"owner\":\"$id\"}')")
            }
            for (b in bookmarks) db.execSQL("INSERT INTO bookmark VALUES(${q(b.id)}, ${q(b.itemId)}, ${q(b.locator)}, ${b.progress}, ${q(b.label)}, ${b.createdAt})")
            db.version = 3
        }
    }

    const val ANNOTATION_V4 = "CREATE TABLE annotation (id TEXT NOT NULL, libraryItemId TEXT NOT NULL, kind TEXT NOT NULL, locatorFormat TEXT NOT NULL, locatorJson TEXT NOT NULL, progressSnapshot INTEGER NOT NULL, orderKey REAL, title TEXT, selectedText TEXT, body TEXT, styleKey TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"

    /** An annotation row for a v4 database (the exact columns of 4.json). */
    data class V4Annotation(val id: String, val itemId: String, val kind: String, val format: String, val locator: String, val progress: Int,
        val orderKey: Double?, val title: String?, val selectedText: String?, val body: String?, val styleKey: String?, val createdAt: Long, val updatedAt: Long)

    /** Creates a real v4 database (everything v3 has, the legacy bookmark table, plus the annotation table and its two indices). */
    fun createV4(context: Context, name: String, items: List<Pair<String, String>>, annotations: List<V4Annotation>) {
        createV3(context, name, items, emptyList())
        context.openOrCreateDatabase(name, 0, null).use { db ->
            db.execSQL(ANNOTATION_V4)
            db.execSQL("CREATE INDEX index_annotation_libraryItemId_kind_createdAt ON annotation (libraryItemId, kind, createdAt)")
            db.execSQL("CREATE INDEX index_annotation_createdAt_id ON annotation (createdAt, id)")
            for (a in annotations) db.execSQL("INSERT INTO annotation VALUES(${q(a.id)}, ${q(a.itemId)}, ${q(a.kind)}, ${q(a.format)}, ${q(a.locator)}, ${a.progress}, " +
                "${a.orderKey ?: "NULL"}, ${q(a.title)}, ${q(a.selectedText)}, ${q(a.body)}, ${q(a.styleKey)}, ${a.createdAt}, ${a.updatedAt})")
            db.version = 4
        }
    }

    /** All rows of [table] as lists of column values, ordered by [orderBy], read through a raw SQLite connection. */
    fun dump(db: SQLiteDatabase, table: String, orderBy: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY $orderBy", null).use(::rows)

    fun rows(c: Cursor): List<List<String?>> {
        val out = ArrayList<List<String?>>()
        while (c.moveToNext()) out += (0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }
        return out
    }

    fun tableExists(db: SQLiteDatabase, table: String) =
        db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst(); it.getInt(0) == 1 }

    fun open(context: Context, name: String) = Room.databaseBuilder(context, ShelfDatabase::class.java, name)
        .addMigrations(ShelfDatabase.MIGRATION_1_2, ShelfDatabase.MIGRATION_2_3, ShelfDatabase.MIGRATION_3_4, ShelfDatabase.MIGRATION_4_5).build()
}

/**
 * Test-side view of the EPUB bookmark helpers (`observeEpubBookmarks`, `addEpubBookmark`, kind-scoped delete) that replaced
 * the retired `BookmarkRepository` adapter. The behavior under test is the production code; this only keeps the old call shape.
 */
class BookmarkTestView(private val annotations: com.d4guilar.shelfos.data.annotations.AnnotationRepository) {
    fun bookmarks(itemId: String) = annotations.observeEpubBookmarks(itemId)
    suspend fun addBookmark(itemId: String, locator: String, progress: Int, label: String? = null) =
        annotations.addEpubBookmark(itemId, locator, progress, label)
    suspend fun deleteBookmark(id: String) { annotations.delete(id, com.d4guilar.shelfos.domain.annotations.AnnotationKind.BOOKMARK) }
}
