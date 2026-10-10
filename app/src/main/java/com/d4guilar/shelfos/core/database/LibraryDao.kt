// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** [managedPath] names a private offline copy inside ShelfOS storage (early rows stored an absolute path). */
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

/** LEGACY (Room v3 and earlier): superseded by [AnnotationEntity] in v4 and migrated into it by MIGRATION_3_4. Kept
 * declared so the exported schema describes the physical database; nothing reads or writes it at runtime. It is
 * dropped in a later migration after physical acceptance (plan section 8.3). */
@Entity(tableName = "bookmark", foreignKeys = [ForeignKey(entity = LibraryEntity::class,
    parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)], indices = [Index("itemId")])
data class BookmarkEntity(@PrimaryKey val id: String, val itemId: String, val locator: String,
    val progress: Int, val label: String?, val createdAt: Long)

data class LibraryRecord(
    @Embedded val item: LibraryEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId") val reading: ReadingEntity?,
    @Relation(parentColumn = "id", entityColumn = "itemId") val preferences: ReaderPreferenceEntity?,
)

@Dao
abstract class LibraryDao {
    @Transaction @Query("SELECT * FROM library_item ORDER BY addedAt DESC, id")
    abstract fun observe(): Flow<List<LibraryRecord>>
    @Transaction @Query("SELECT * FROM library_item WHERE id = :id")
    abstract fun observeRecord(id: String): Flow<LibraryRecord?>
    @Transaction @Query("SELECT * FROM library_item")
    abstract suspend fun records(): List<LibraryRecord>
    @Query("SELECT * FROM library_item WHERE sourceUri = :uri LIMIT 1")
    abstract suspend fun bySource(uri: String): LibraryEntity?
    @Query("SELECT * FROM library_item WHERE id = :id") abstract suspend fun byId(id: String): LibraryEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(item: LibraryEntity): Long
    @Query("UPDATE library_item SET favorite = NOT favorite WHERE id = :id") abstract suspend fun favorite(id: String)
    @Query("UPDATE library_item SET title = :title, creator = :creator, category = :category, titleOrigin = 'user', creatorOrigin = 'user' WHERE id = :id")
    abstract suspend fun edit(id: String, title: String, creator: String, category: String)
    @Query("UPDATE library_item SET available = :available WHERE id = :id") abstract suspend fun available(id: String, available: Boolean)
    @Query("UPDATE library_item SET available = 0 WHERE id IN (:ids)") abstract suspend fun markUnavailable(ids: List<String>)
    @Query("DELETE FROM library_item WHERE id = :id") protected abstract suspend fun deleteItem(id: String)
    @Query("DELETE FROM reading_state WHERE itemId = :id") protected abstract suspend fun deleteReading(id: String)
    @Query("DELETE FROM reader_preference WHERE itemId = :id") protected abstract suspend fun deletePreferences(id: String)
    @Query("DELETE FROM annotation WHERE libraryItemId = :id") protected abstract suspend fun deleteAnnotations(id: String)
    /**
     * Deletes ShelfOS-owned state for one item under `KnowledgePolicy.DELETE`: preferences, reading state, every
     * canonical annotation row and the item, atomically in one transaction. Other items and all source files are
     * untouched. The annotation table has no foreign key, so it is deleted explicitly here (a failure anywhere rolls
     * the whole removal back). The legacy `bookmark` table keeps its own cascade foreign key and is cleared by the
     * item delete; nothing reads it any more. This is the only code path that removes a `library_item` row.
     */
    @Transaction open suspend fun removeDeletingKnowledge(id: String) { deletePreferences(id); deleteReading(id); deleteAnnotations(id); deleteItem(id) }
    @Upsert abstract suspend fun saveReading(state: ReadingEntity)
    @Upsert abstract suspend fun savePreferences(state: ReaderPreferenceEntity)
    @Query("SELECT json FROM reader_preference WHERE itemId = ''") abstract fun globalPreferences(): Flow<String?>
}
