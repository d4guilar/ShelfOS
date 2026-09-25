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

@Database(entities = [AppearancePreference::class, LibraryEntity::class, ReadingEntity::class, ReaderPreferenceEntity::class], version = 2, exportSchema = true)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun appearance(): AppearanceDao
    abstract fun library(): LibraryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS library_item (id TEXT NOT NULL PRIMARY KEY, sourceUri TEXT NOT NULL, title TEXT NOT NULL, creator TEXT NOT NULL, category TEXT NOT NULL, format TEXT NOT NULL, fileName TEXT NOT NULL, byteSize INTEGER, favorite INTEGER NOT NULL, addedAt INTEGER NOT NULL, available INTEGER NOT NULL, managedPath TEXT, titleOrigin TEXT NOT NULL, creatorOrigin TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_library_item_sourceUri ON library_item(sourceUri)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reading_state (itemId TEXT NOT NULL PRIMARY KEY, locator TEXT NOT NULL, progress INTEGER NOT NULL, lastRead INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES library_item(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS reader_preference (itemId TEXT NOT NULL PRIMARY KEY, json TEXT NOT NULL)")
            }
        }
        fun create(context: Context): ShelfDatabase = Room.databaseBuilder(
            context.applicationContext, ShelfDatabase::class.java, "shelfos.db",
        ).addMigrations(MIGRATION_1_2).build()
    }
}
