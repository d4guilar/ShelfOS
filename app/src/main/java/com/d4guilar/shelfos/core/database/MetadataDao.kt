// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Phase 4.5B (plan §23.7): locally analysed and user-edited metadata beyond title/creator, which stay on `library_item`
 * with their origin columns. Values are typed text; `identifiersJson`/`creditsJson`/`provenanceJson` are written and
 * validated by `domain.metadata.MetadataCodec`. Removed with the item (FK cascade and an explicit delete).
 */
@Entity(tableName = "publication_metadata", foreignKeys = [ForeignKey(entity = LibraryEntity::class,
    parentColumns = ["id"], childColumns = ["libraryItemId"], onDelete = ForeignKey.CASCADE)])
data class PublicationMetadataEntity(
    @PrimaryKey val libraryItemId: String, val subtitle: String?, val description: String?, val publisher: String?,
    val publishedDate: String?, val language: String?, val seriesName: String?, val volume: String?, val issue: String?,
    val identifiersJson: String?, val creditsJson: String?, val provenanceJson: String?,
    val localAnalysisVersion: Int, val updatedAt: Long,
)

/** The `library_item` columns metadata resolution reads and may write. */
data class MetadataHead(val id: String, val title: String, val creator: String, val titleOrigin: String, val creatorOrigin: String)

@Dao
abstract class MetadataDao {
    @Query("SELECT * FROM publication_metadata WHERE libraryItemId = :id") abstract suspend fun get(id: String): PublicationMetadataEntity?
    @Query("SELECT * FROM publication_metadata WHERE libraryItemId = :id") abstract fun observe(id: String): Flow<PublicationMetadataEntity?>
    @Query("SELECT id, title, creator, titleOrigin, creatorOrigin FROM library_item WHERE id = :id") abstract suspend fun head(id: String): MetadataHead?
    @Upsert abstract suspend fun upsert(row: PublicationMetadataEntity)

    /** Writes only a real change, so an unchanged title/creator never invalidates the Library list. */
    @Query("UPDATE library_item SET title = :title, titleOrigin = :titleOrigin, creator = :creator, creatorOrigin = :creatorOrigin " +
        "WHERE id = :id AND (title != :title OR titleOrigin != :titleOrigin OR creator != :creator OR creatorOrigin != :creatorOrigin)")
    abstract suspend fun updateHead(id: String, title: String, titleOrigin: String, creator: String, creatorOrigin: String)

    /** Available items whose local analysis is missing or older than [version], newest imports first. */
    @Query("SELECT li.id FROM library_item li LEFT JOIN publication_metadata pm ON pm.libraryItemId = li.id " +
        "WHERE li.available = 1 AND (pm.libraryItemId IS NULL OR pm.localAnalysisVersion < :version) AND li.id NOT IN (:exclude) " +
        "ORDER BY li.addedAt DESC LIMIT :limit")
    abstract suspend fun pendingLocal(version: Int, exclude: List<String>, limit: Int): List<String>
}
