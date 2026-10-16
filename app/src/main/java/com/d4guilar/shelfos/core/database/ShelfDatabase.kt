// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "appearance_preference")
data class AppearancePreference(@PrimaryKey val id: Int = 0, val themeKey: String)

@Dao
interface AppearanceDao {
    @Query("SELECT themeKey FROM appearance_preference WHERE id = 0")
    fun observeTheme(): Flow<String?>

    @Upsert
    suspend fun save(preference: AppearancePreference)
}

@Database(entities = [AppearancePreference::class, LibraryEntity::class, ReadingEntity::class, ReaderPreferenceEntity::class, BookmarkEntity::class, AnnotationEntity::class, AnnotationSourceSnapshotEntity::class, PublicationCoverEntity::class, CoverPresentationPreferenceEntity::class, PublicationMetadataEntity::class], version = 8, exportSchema = true)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun appearance(): AppearanceDao
    abstract fun library(): LibraryDao
    abstract fun annotations(): AnnotationDao
    abstract fun covers(): CoverDao
    abstract fun coverPresentation(): CoverPresentationDao
    abstract fun metadata(): MetadataDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS library_item (id TEXT NOT NULL PRIMARY KEY, sourceUri TEXT NOT NULL, title TEXT NOT NULL, creator TEXT NOT NULL, category TEXT NOT NULL, format TEXT NOT NULL, fileName TEXT NOT NULL, byteSize INTEGER, favorite INTEGER NOT NULL, addedAt INTEGER NOT NULL, available INTEGER NOT NULL, managedPath TEXT, titleOrigin TEXT NOT NULL, creatorOrigin TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_library_item_sourceUri ON library_item(sourceUri)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reading_state (itemId TEXT NOT NULL PRIMARY KEY, locator TEXT NOT NULL, progress INTEGER NOT NULL, lastRead INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES library_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reader_preference (itemId TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS bookmark (id TEXT NOT NULL PRIMARY KEY, itemId TEXT NOT NULL, locator TEXT NOT NULL, progress INTEGER NOT NULL, label TEXT, createdAt INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES library_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmark_itemId ON bookmark(itemId)")
            }
        }
        /**
         * v3 -> v4: introduces the canonical `annotation` table and copies EVERY legacy bookmark into it, ids preserved.
         * Locator strings are copied byte-for-byte (no JSON parsing in SQL). The legacy `bookmark` table is kept
         * untouched. Everything runs in Room's migration transaction, so any exception thrown here (unclassifiable item
         * format, row-count or content mismatch) rolls back to the untouched v3 database; there is no destructive fallback.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `annotation` (`id` TEXT NOT NULL, `libraryItemId` TEXT NOT NULL, `kind` TEXT NOT NULL, `locatorFormat` TEXT NOT NULL, `locatorJson` TEXT NOT NULL, `progressSnapshot` INTEGER NOT NULL, `orderKey` REAL, `title` TEXT, `selectedText` TEXT, `body` TEXT, `styleKey` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX `index_annotation_libraryItemId_kind_createdAt` ON `annotation` (`libraryItemId`, `kind`, `createdAt`)")
                db.execSQL("CREATE INDEX `index_annotation_createdAt_id` ON `annotation` (`createdAt`, `id`)")
                // The locator format is derived from the item's publication format; anything unclassifiable fails the
                // migration instead of being guessed.
                val unclassifiable = db.scalar("SELECT COUNT(*) FROM bookmark b LEFT JOIN library_item li ON li.id = b.itemId WHERE li.id IS NULL OR li.format NOT IN ('EPUB', 'PDF', 'CBZ', 'CBR')")
                check(unclassifiable == 0L) { "MIGRATION_3_4: $unclassifiable bookmark(s) reference a missing item or an unknown publication format" }
                db.execSQL("INSERT INTO annotation (id, libraryItemId, kind, locatorFormat, locatorJson, progressSnapshot, orderKey, title, selectedText, body, styleKey, createdAt, updatedAt) " +
                    "SELECT b.id, b.itemId, 'BOOKMARK', CASE WHEN li.format = 'EPUB' THEN 'READIUM_LOCATOR_1' ELSE 'FIXED_PAGE_1' END, " +
                    "b.locator, b.progress, NULL, b.label, NULL, NULL, NULL, b.createdAt, b.createdAt FROM bookmark b JOIN library_item li ON li.id = b.itemId")
                val source = db.scalar("SELECT COUNT(*) FROM bookmark")
                val copied = db.scalar("SELECT COUNT(*) FROM annotation")
                check(source == copied) { "MIGRATION_3_4: copied $copied of $source bookmarks" }
                val diverged = db.scalar("SELECT COUNT(*) FROM bookmark b JOIN library_item li ON li.id = b.itemId " +
                    "WHERE NOT EXISTS (SELECT 1 FROM annotation a WHERE a.id = b.id AND a.libraryItemId = b.itemId " +
                    "AND a.kind = 'BOOKMARK' AND a.locatorFormat = CASE WHEN li.format = 'EPUB' THEN 'READIUM_LOCATOR_1' ELSE 'FIXED_PAGE_1' END " +
                    "AND a.locatorJson = b.locator AND a.progressSnapshot = b.progress AND a.orderKey IS NULL AND a.title IS b.label " +
                    "AND a.selectedText IS NULL AND a.body IS NULL AND a.styleKey IS NULL AND a.createdAt = b.createdAt AND a.updatedAt = b.createdAt)")
                check(diverged == 0L) { "MIGRATION_3_4: $diverged migrated bookmark(s) differ from their source row" }
            }

            private fun SupportSQLiteDatabase.scalar(sql: String): Long = query(sql).use { check(it.moveToFirst()); it.getLong(0) }
        }
        /**
         * v4 -> v5 (Phase 4D): purely additive. Creates `annotation_source_snapshot`, the display-only context kept for orphaned
         * annotations after a keep-notes removal. No existing table, row or index is read or changed, so every library item,
         * reading state, preference, legacy bookmark and annotation (locators included) is untouched. No destructive fallback.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `annotation_source_snapshot` (`libraryItemId` TEXT NOT NULL, `title` TEXT NOT NULL, `creator` TEXT NOT NULL, `category` TEXT NOT NULL, `format` TEXT NOT NULL, PRIMARY KEY(`libraryItemId`))")
            }
        }
        /**
         * v5 -> v6 (Phase 4.5A.1): purely additive. Creates the empty `publication_cover` table with EXACTLY the `createSql`
         * Room exported to `schemas/.../6.json` (no CHECK, no extra index), so fresh and upgraded databases are identical.
         * No existing table, row or index is read or changed; covers arrive later through the background backfill.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PUBLICATION_COVER_CREATE_SQL)
            }
        }
        /**
         * v6 -> v7 (Phase 4.5A.2): purely additive. Creates the empty `cover_presentation_preference` table with EXACTLY the
         * `createSql` Room exported to `schemas/.../7.json`. No existing table, row or index (covers included) is touched;
         * an empty table means every scope uses its default, so nothing is backfilled.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(COVER_PRESENTATION_CREATE_SQL)
            }
        }
        /** Must equal `7.json`'s `cover_presentation_preference.createSql` with the table name substituted (asserted by the migration test). */
        const val COVER_PRESENTATION_CREATE_SQL = "CREATE TABLE IF NOT EXISTS `cover_presentation_preference` (`scope` TEXT NOT NULL, `shape` TEXT NOT NULL, `customWidth` INTEGER, `customHeight` INTEGER, `fit` TEXT NOT NULL, PRIMARY KEY(`scope`))"
        /**
         * v7 -> v8 (Phase 4.5B): purely additive. Creates the empty `publication_metadata` table with EXACTLY the `createSql`
         * Room exported to `schemas/.../8.json`. No existing table, row or index is touched; existing items are analysed
         * later by the bounded local backfill, so the migration never opens a publication.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PUBLICATION_METADATA_CREATE_SQL)
            }
        }
        /** Must equal `8.json`'s `publication_metadata.createSql` with the table name substituted (asserted by the migration test). */
        const val PUBLICATION_METADATA_CREATE_SQL = "CREATE TABLE IF NOT EXISTS `publication_metadata` (`libraryItemId` TEXT NOT NULL, `subtitle` TEXT, `description` TEXT, `publisher` TEXT, `publishedDate` TEXT, `language` TEXT, `seriesName` TEXT, `volume` TEXT, `issue` TEXT, `identifiersJson` TEXT, `creditsJson` TEXT, `provenanceJson` TEXT, `localAnalysisVersion` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`libraryItemId`), FOREIGN KEY(`libraryItemId`) REFERENCES `library_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
        /** Must equal `6.json`'s `publication_cover.createSql` with the table name substituted (asserted by the migration test). */
        const val PUBLICATION_COVER_CREATE_SQL = "CREATE TABLE IF NOT EXISTS `publication_cover` (`libraryItemId` TEXT NOT NULL, `slot` TEXT NOT NULL, `status` TEXT NOT NULL, `failureReason` TEXT, `fileName` TEXT, `mimeType` TEXT, `representation` TEXT, `sourceWidthPx` INTEGER, `sourceHeightPx` INTEGER, `storedWidthPx` INTEGER, `storedHeightPx` INTEGER, `storedBytes` INTEGER, `localSource` TEXT, `sourceRef` TEXT, `sourceChoice` TEXT, `sourceRevision` TEXT, `extractorVersion` INTEGER NOT NULL, `attempts` INTEGER NOT NULL DEFAULT 0, `interruptions` INTEGER NOT NULL DEFAULT 0, `attemptStartedAt` INTEGER, `attemptExtractorVersion` INTEGER, `nextEligibleAt` INTEGER, `parked` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`libraryItemId`, `slot`), FOREIGN KEY(`libraryItemId`) REFERENCES `library_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        fun create(context: Context): ShelfDatabase = Room.databaseBuilder(
            context.applicationContext, ShelfDatabase::class.java, "shelfos.db",
        ).addMigrations(*ALL_MIGRATIONS).build()
    }
}
