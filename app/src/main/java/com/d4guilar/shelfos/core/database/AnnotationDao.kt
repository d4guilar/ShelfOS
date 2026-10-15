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

/**
 * Display context preserved ONLY when a library item is removed while its annotations are kept (Phase 4D, plan 7.3), so
 * the Notes hub can present the resulting orphans. It is a copy of four display fields and nothing else: no source URI,
 * private-copy path, file name, size, hash or any other way to find or re-identify the original, and nothing here is ever
 * used to reattach orphans. One row per removed item that still owns annotations; no foreign key (the item is gone).
 */
@Entity(tableName = "annotation_source_snapshot")
data class AnnotationSourceSnapshotEntity(
    @PrimaryKey val libraryItemId: String, val title: String, val creator: String, val category: String, val format: String,
)

/** One global Notes row: the annotation plus its publication context, resolved by joins (never a per-row lookup). */
data class NoteRowEntity(
    @Embedded val annotation: AnnotationEntity,
    val srcTitle: String?, val srcCreator: String?, val srcCategory: String?, val srcFormat: String?,
    val live: Boolean, val available: Boolean, val sortTitle: String, val sortOrder: Double,
)

/** One publication (live or removed) owning annotations, with its count. */
data class NotePublicationEntity(
    val libraryItemId: String, val srcTitle: String?, val srcCreator: String?, val srcCategory: String?, val srcFormat: String?,
    val live: Boolean, val available: Boolean, val total: Int,
)

/** Safe publication context for portable knowledge. Deliberately excludes every source/file field. */
data class PortableLibraryContextEntity(
    val id: String, val title: String, val creator: String, val category: String, val format: String,
)

// The global Notes read surface. Rows of a kind or locator format this build does not know are left out (they stay in the
// table untouched). Search is a plain LIKE over the user-visible knowledge fields and the publication title/creator; the
// pattern is built and escaped by the repository (ESCAPE '\'). No FTS (plan section 11).
private const val SOURCE_JOIN = "FROM annotation a LEFT JOIN library_item li ON li.id = a.libraryItemId " +
    "LEFT JOIN annotation_source_snapshot s ON s.libraryItemId = a.libraryItemId "
private const val SORT_TITLE = "lower(COALESCE(li.title, s.title, ''))"
private const val SORT_ORDER = "COALESCE(a.orderKey, a.progressSnapshot / 100.0)"
private const val NOTE_COLUMNS = "SELECT a.*, COALESCE(li.title, s.title) AS srcTitle, COALESCE(li.creator, s.creator) AS srcCreator, " +
    "COALESCE(li.category, s.category) AS srcCategory, COALESCE(li.format, s.format) AS srcFormat, (li.id IS NOT NULL) AS live, " +
    "COALESCE(li.available, 0) AS available, $SORT_TITLE AS sortTitle, $SORT_ORDER AS sortOrder "
private const val KNOWN = "a.kind IN ('NOTE', 'HIGHLIGHT', 'BOOKMARK') AND a.locatorFormat IN ('READIUM_LOCATOR_1', 'FIXED_PAGE_1') "
private const val NOTE_FILTERS = "WHERE $KNOWN AND (:allKinds = 1 OR a.kind IN (:kinds)) AND (:itemId IS NULL OR a.libraryItemId = :itemId) " +
    "AND (:pattern IS NULL OR a.body LIKE :pattern ESCAPE '\\' OR a.selectedText LIKE :pattern ESCAPE '\\' OR a.title LIKE :pattern ESCAPE '\\' " +
    "OR li.title LIKE :pattern ESCAPE '\\' OR s.title LIKE :pattern ESCAPE '\\' OR li.creator LIKE :pattern ESCAPE '\\' OR s.creator LIKE :pattern ESCAPE '\\') "

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
    protected abstract suspend fun deleteRow(id: String, anyKind: Int, kind: String): Int
    @Query("SELECT libraryItemId FROM annotation WHERE id = :id") protected abstract suspend fun itemOf(id: String): String?
    @Query("DELETE FROM annotation WHERE libraryItemId = :itemId") protected abstract suspend fun deleteRowsForItem(itemId: String): Int
    /** Drops an item's removal snapshot once it owns no annotation any more, so no snapshot outlives its last orphan. */
    @Query("DELETE FROM annotation_source_snapshot WHERE libraryItemId = :itemId AND NOT EXISTS (SELECT 1 FROM annotation WHERE libraryItemId = :itemId)")
    protected abstract suspend fun dropUnusedSnapshot(itemId: String)

    /** Deletes one annotation (of [kind], unless [anyKind]) and, in the same transaction, tidies the item's snapshot if that was its last. */
    @Transaction open suspend fun delete(id: String, anyKind: Int, kind: String): Int {
        val itemId = itemOf(id)
        val deleted = deleteRow(id, anyKind, kind)
        if (deleted > 0 && itemId != null) dropUnusedSnapshot(itemId)
        return deleted
    }

    @Transaction open suspend fun deleteForItem(itemId: String): Int { val deleted = deleteRowsForItem(itemId); dropUnusedSnapshot(itemId); return deleted }

    /** Explicit "delete the notes of a removed publication": refuses (0) while the library item still exists. Removes the snapshot too. */
    @Transaction open suspend fun deleteOrphanGroup(itemId: String): Int = if (itemCount(itemId) > 0) 0 else deleteForItem(itemId)

    @Query("SELECT COUNT(*) FROM annotation_source_snapshot s WHERE NOT EXISTS (SELECT 1 FROM annotation a WHERE a.libraryItemId = s.libraryItemId)")
    abstract suspend fun countUnusedSnapshots(): Int

    @Query("SELECT * FROM annotation") abstract suspend fun portableAnnotations(): List<AnnotationEntity>
    @Query("SELECT id, title, creator, category, format FROM library_item")
    abstract suspend fun portableLibraryContexts(): List<PortableLibraryContextEntity>
    @Query("SELECT * FROM annotation_source_snapshot")
    abstract suspend fun portableSnapshots(): List<AnnotationSourceSnapshotEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPortableAnnotations(rows: List<AnnotationEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPortableSnapshots(rows: List<AnnotationSourceSnapshotEntity>)

    /** Fires on any write to annotations, snapshots or library items; the value itself is meaningless. */
    @Query("SELECT (SELECT COUNT(*) FROM annotation) + (SELECT COUNT(*) FROM annotation_source_snapshot) + (SELECT COUNT(*) FROM library_item)")
    abstract fun observeChanges(): Flow<Int>

    /** First page (no cursor). Newest first. */
    @Query(NOTE_COLUMNS + SOURCE_JOIN + NOTE_FILTERS +
        "AND (:hasCursor = 0 OR a.createdAt < :cCreated OR (a.createdAt = :cCreated AND a.id < :cId)) " +
        "ORDER BY a.createdAt DESC, a.id DESC LIMIT :limit")
    abstract suspend fun notesByDate(allKinds: Int, kinds: List<String>, itemId: String?, pattern: String?,
        hasCursor: Int, cCreated: Long, cId: String, limit: Int): List<NoteRowEntity>

    /** By publication title then item id, each publication in reading order; keyset over (title, item, order, createdAt, id) without row values (API 24 SQLite). */
    @Query(NOTE_COLUMNS + SOURCE_JOIN + NOTE_FILTERS +
        "AND (:hasCursor = 0 OR $SORT_TITLE > :cTitle OR ($SORT_TITLE = :cTitle AND (a.libraryItemId > :cItem OR (a.libraryItemId = :cItem AND " +
        "($SORT_ORDER > :cOrder OR ($SORT_ORDER = :cOrder AND (a.createdAt > :cCreated OR (a.createdAt = :cCreated AND a.id > :cId)))))))) " +
        "ORDER BY $SORT_TITLE ASC, a.libraryItemId ASC, $SORT_ORDER ASC, a.createdAt ASC, a.id ASC LIMIT :limit")
    abstract suspend fun notesByPublication(allKinds: Int, kinds: List<String>, itemId: String?, pattern: String?,
        hasCursor: Int, cTitle: String, cItem: String, cOrder: Double, cCreated: Long, cId: String, limit: Int): List<NoteRowEntity>

    /** Portable export walks one bounded page in canonical id order, independent of the visible Notes sort. */
    @Query(NOTE_COLUMNS + SOURCE_JOIN + NOTE_FILTERS +
        "AND (:hasCursor = 0 OR a.libraryItemId > :cItem OR (a.libraryItemId = :cItem AND a.id > :cId)) " +
        "ORDER BY a.libraryItemId COLLATE BINARY ASC, a.id COLLATE BINARY ASC LIMIT :limit")
    abstract suspend fun portableExportPage(allKinds: Int, kinds: List<String>, itemId: String?, pattern: String?,
        hasCursor: Int, cItem: String, cId: String, limit: Int): List<NoteRowEntity>

    /** One bounded lookup per export page, only for groups whose source format is unavailable. */
    @Query("SELECT DISTINCT a.libraryItemId " + SOURCE_JOIN + NOTE_FILTERS +
        "AND a.libraryItemId IN (:ids) AND a.locatorFormat != 'READIUM_LOCATOR_1'")
    abstract suspend fun portableGroupsWithNonReadium(allKinds: Int, kinds: List<String>, itemId: String?,
        pattern: String?, ids: List<String>): List<String>

    /** How many rows match the same filters (one cheap read, so the hub can say how many results there are). */
    @Query("SELECT COUNT(*) " + SOURCE_JOIN + NOTE_FILTERS)
    abstract suspend fun countNotes(allKinds: Int, kinds: List<String>, itemId: String?, pattern: String?): Int

    @Query("SELECT a.libraryItemId AS libraryItemId, COALESCE(li.title, s.title) AS srcTitle, COALESCE(li.creator, s.creator) AS srcCreator, " +
        "COALESCE(li.category, s.category) AS srcCategory, COALESCE(li.format, s.format) AS srcFormat, (li.id IS NOT NULL) AS live, " +
        "COALESCE(li.available, 0) AS available, COUNT(*) AS total " + SOURCE_JOIN + "WHERE " + KNOWN +
        "GROUP BY a.libraryItemId ORDER BY $SORT_TITLE ASC, a.libraryItemId ASC")
    abstract fun observePublications(): Flow<List<NotePublicationEntity>>
}
