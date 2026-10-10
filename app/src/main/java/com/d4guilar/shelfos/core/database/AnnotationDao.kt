// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * The canonical knowledge record (ADR-0025). Deliberately has NO foreign key to `library_item`: referential
 * integrity is enforced by the repository and by [LibraryDao.removeDeletingKnowledge] inside one transaction, so a
 * future keep-on-removal policy needs no table rebuild. [kind] and [locatorFormat] are persisted by enum name and
 * may hold values this build does not know; readers must tolerate that.
 */
@Entity(tableName = "annotation", indices = [
    Index(value = ["libraryItemId", "kind", "createdAt"], name = "index_annotation_libraryItemId_kind_createdAt"),
    Index(value = ["createdAt", "id"], name = "index_annotation_createdAt_id"),
])
data class AnnotationEntity(
    @PrimaryKey val id: String, val libraryItemId: String, val kind: String, val locatorFormat: String,
    val locatorJson: String, val progressSnapshot: Int, val orderKey: Double?,
    val title: String?, val selectedText: String?, val body: String?, val styleKey: String?,
    val createdAt: Long, val updatedAt: Long,
)

data class AnnotationKindCount(val kind: String, val total: Int)

@Dao
abstract class AnnotationDao {
    @Query("SELECT * FROM annotation WHERE id = :id") abstract suspend fun byId(id: String): AnnotationEntity?

    /** Reading order. [orderKey] is a derived hint; the percent snapshot is the fallback; createdAt then id break ties. */
    @Query("SELECT * FROM annotation WHERE libraryItemId = :itemId AND (:allKinds = 1 OR kind IN (:kinds)) " +
        "ORDER BY COALESCE(orderKey, progressSnapshot / 100.0) ASC, createdAt ASC, id ASC")
    abstract fun observeReadingOrder(itemId: String, allKinds: Int, kinds: List<String>): Flow<List<AnnotationEntity>>

    /** The legacy bookmark order, kept so user-visible bookmark order is unchanged: progress, createdAt, then id. */
    @Query("SELECT * FROM annotation WHERE libraryItemId = :itemId AND (:allKinds = 1 OR kind IN (:kinds)) " +
        "ORDER BY progressSnapshot ASC, createdAt ASC, id ASC")
    abstract fun observeBookmarkOrder(itemId: String, allKinds: Int, kinds: List<String>): Flow<List<AnnotationEntity>>

    /** Global recent-first read for later Notes work; `(createdAt, id)` is covered by an index. */
    @Query("SELECT * FROM annotation ORDER BY createdAt DESC, id DESC") abstract fun observeRecent(): Flow<List<AnnotationEntity>>

    @Query("SELECT kind, COUNT(*) AS total FROM annotation WHERE libraryItemId = :itemId GROUP BY kind")
    abstract fun observeKindCounts(itemId: String): Flow<List<AnnotationKindCount>>

    /** Integrity query: rows whose library item no longer exists. Always 0 under the DELETE policy. */
    @Query("SELECT COUNT(*) FROM annotation WHERE libraryItemId NOT IN (SELECT id FROM library_item)")
    abstract suspend fun countOrphans(): Int

    @Query("SELECT COUNT(*) FROM library_item WHERE id = :id") protected abstract suspend fun itemCount(id: String): Int
    @Query("SELECT * FROM annotation WHERE libraryItemId = :itemId AND kind = 'BOOKMARK' AND locatorJson = :locator LIMIT 1")
    protected abstract suspend fun bookmarkAt(itemId: String, locator: String): AnnotationEntity?
    @Insert protected abstract suspend fun insert(entity: AnnotationEntity)
    @Update protected abstract suspend fun update(entity: AnnotationEntity)

    /**
     * Inserts [entity] after checking, in the same transaction, that its library item exists (the integrity the removed
     * foreign key used to provide). A BOOKMARK at the exact same locator string the item already has is a safe no-op
     * that returns the existing row (the legacy dedupe rule, chosen over a UNIQUE constraint on opaque JSON).
     */
    @Transaction open suspend fun create(entity: AnnotationEntity): AnnotationEntity {
        require(itemCount(entity.libraryItemId) > 0) { "Unknown library item: ${entity.libraryItemId}" }
        if (entity.kind == "BOOKMARK") bookmarkAt(entity.libraryItemId, entity.locatorJson)?.let { return it }
        insert(entity)
        return entity
    }

    /** Read-modify-write in one transaction; [transform] may throw to abort, or return null for "no change needed". */
    @Transaction open suspend fun edit(id: String, transform: (AnnotationEntity) -> AnnotationEntity): AnnotationEntity? {
        val current = byId(id) ?: return null
        val next = transform(current)
        update(next)
        return next
    }

    @Query("DELETE FROM annotation WHERE id = :id AND (:anyKind = 1 OR kind = :kind)")
    abstract suspend fun delete(id: String, anyKind: Int, kind: String): Int
    @Query("DELETE FROM annotation WHERE libraryItemId = :itemId") abstract suspend fun deleteForItem(itemId: String): Int
}
