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

@Entity(tableName = "appearance_preference")
data class AppearancePreference(@PrimaryKey val id: Int = 0, val themeKey: String)

@Dao
interface AppearanceDao {
    @Query("SELECT themeKey FROM appearance_preference WHERE id = 0")
    fun observeTheme(): Flow<String?>

    @Upsert
    suspend fun save(preference: AppearancePreference)
}

@Database(entities = [AppearancePreference::class], version = 1, exportSchema = true)
abstract class ShelfDatabase : RoomDatabase() {
    abstract fun appearance(): AppearanceDao

    companion object {
        fun create(context: Context): ShelfDatabase = Room.databaseBuilder(
            context.applicationContext, ShelfDatabase::class.java, "shelfos.db",
        ).build()
    }
}
