// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "library_item", indices = [Index(value = ["sourceUri"], unique = true)])
data class LibraryEntity(
    @PrimaryKey val id: String, val sourceUri: String, val title: String, val creator: String,
    val category: String, val format: String, val fileName: String, val byteSize: Long?,
    val favorite: Boolean, val addedAt: Long, val available: Boolean, val managedPath: String?,
    val titleOrigin: String, val creatorOrigin: String,
)

@Entity(tableName = "reading_state", foreignKeys = [ForeignKey(entity = LibraryEntity::class,
    parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)])
data class ReadingEntity(@PrimaryKey val itemId: String, val locator: String, val progress: Int, val lastRead: Long)

// An empty item key represents global defaults; item keys are UUIDs.
@Entity(tableName = "reader_preference")
data class ReaderPreferenceEntity(@PrimaryKey val itemId: String, val json: String)

data class LibraryRecord(
    @Embedded val item: LibraryEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId") val reading: ReadingEntity?,
    @Relation(parentColumn = "id", entityColumn = "itemId") val preferences: ReaderPreferenceEntity?,
)

@Dao
abstract class LibraryDao {
    @Transaction @Query("SELECT * FROM library_item ORDER BY addedAt DESC, id")
    abstract fun observe(): Flow<List<LibraryRecord>>
    @Query("SELECT * FROM library_item WHERE sourceUri = :uri LIMIT 1")
    abstract suspend fun bySource(uri: String): LibraryEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(item: LibraryEntity): Long
    @Query("UPDATE library_item SET favorite = NOT favorite WHERE id = :id") abstract suspend fun favorite(id: String)
    @Query("UPDATE library_item SET title = :title, creator = :creator, category = :category, titleOrigin = 'user', creatorOrigin = 'user' WHERE id = :id")
    abstract suspend fun edit(id: String, title: String, creator: String, category: String)
    @Query("UPDATE library_item SET available = :available WHERE id = :id") abstract suspend fun available(id: String, available: Boolean)
    @Query("DELETE FROM library_item WHERE id = :id") protected abstract suspend fun deleteItem(id: String)
    @Query("DELETE FROM reader_preference WHERE itemId = :id") protected abstract suspend fun deletePreferences(id: String)
    @Transaction open suspend fun remove(id: String) { deletePreferences(id); deleteItem(id) }
    @Upsert abstract suspend fun saveReading(state: ReadingEntity)
    @Upsert abstract suspend fun savePreferences(state: ReaderPreferenceEntity)
    @Query("SELECT json FROM reader_preference WHERE itemId = ''") abstract fun globalPreferences(): Flow<String?>
}
