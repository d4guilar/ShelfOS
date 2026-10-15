// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room v6 (Phase 4.5A.1, plan §23.1): one row per publication and cover slot. Enum-valued columns hold the Kotlin enum
 * NAME (`domain.library` cover enums); the only writer is `data.library.RoomCoverRepository`, which validates every
 * value and combination before SQL runs. No CHECK constraints, so fresh and migrated schemas are identical.
 * `fileName` is relative to `filesDir/covers/<libraryItemId>/`, unique and immutable per successful extraction.
 * `attemptStartedAt` non-null = an attempt began and has not been closed (also the attempt's ownership token).
 */
@Entity(tableName = "publication_cover", primaryKeys = ["libraryItemId", "slot"], foreignKeys = [ForeignKey(entity = LibraryEntity::class,
    parentColumns = ["id"], childColumns = ["libraryItemId"], onDelete = ForeignKey.CASCADE)])
data class PublicationCoverEntity(
    val libraryItemId: String, val slot: String, val status: String, val failureReason: String?,
    val fileName: String?, val mimeType: String?, val representation: String?,
    val sourceWidthPx: Int?, val sourceHeightPx: Int?, val storedWidthPx: Int?, val storedHeightPx: Int?, val storedBytes: Long?,
    val localSource: String?, val sourceRef: String?, val sourceChoice: String?, val sourceRevision: String?,
    val extractorVersion: Int,
    @ColumnInfo(defaultValue = "0") val attempts: Int,
    @ColumnInfo(defaultValue = "0") val interruptions: Int,
    val attemptStartedAt: Long?, val attemptExtractorVersion: Int?, val nextEligibleAt: Long?,
    @ColumnInfo(defaultValue = "0") val parked: Boolean,
    val updatedAt: Long,
)

/** The cover-map projection (§19.1): only what the renderer needs, READY rows only. */
data class ReadyCoverRow(val libraryItemId: String, val slot: String, val fileName: String?, val storedWidthPx: Int?, val storedHeightPx: Int?)

data class CoverFileRow(val libraryItemId: String, val fileName: String)

/** A backfill candidate: an item with no usable LOCAL outcome yet, newest/most recently read first. */
data class CoverCandidate(val id: String, val format: String)

@Dao
abstract class CoverDao {
    @Query("SELECT libraryItemId, slot, fileName, storedWidthPx, storedHeightPx FROM publication_cover WHERE status = 'READY'")
    abstract fun observeReady(): Flow<List<ReadyCoverRow>>

    @Query("SELECT * FROM publication_cover WHERE libraryItemId = :id AND slot = 'LOCAL'")
    abstract suspend fun local(id: String): PublicationCoverEntity?

    @Query("SELECT * FROM publication_cover WHERE attemptStartedAt IS NOT NULL")
    abstract suspend fun openAttempts(): List<PublicationCoverEntity>

    @Query("SELECT libraryItemId, fileName FROM publication_cover WHERE fileName IS NOT NULL")
    abstract suspend fun referencedFiles(): List<CoverFileRow>

    @Query("DELETE FROM publication_cover WHERE libraryItemId NOT IN (SELECT id FROM library_item)")
    abstract suspend fun deleteOrphanRows(): Int

    /**
     * Items whose LOCAL slot may start an automatic attempt now: no row; a PENDING row with no open marker (a cancelled
     * first attempt); or, when [allowRetries] (the per-launch retry cap), a non-parked timer-backed temporary failure
     * whose not-before time has passed (persisted values are clock-skew clamped first, [clampNotBefore]).
     */
    @Query("""SELECT li.id AS id, li.format AS format FROM library_item li
        LEFT JOIN publication_cover pc ON pc.libraryItemId = li.id AND pc.slot = 'LOCAL'
        LEFT JOIN reading_state rs ON rs.itemId = li.id
        WHERE li.available = 1 AND (pc.libraryItemId IS NULL
          OR (pc.attemptStartedAt IS NULL AND pc.parked = 0 AND (pc.status = 'PENDING'
            OR (:allowRetries AND pc.status = 'FAILED_TEMPORARY' AND pc.failureReason IN ('INTERRUPTED', 'IO_ERROR', 'RESOURCE_EXHAUSTED', 'TIMEOUT')
                AND COALESCE(pc.nextEligibleAt, 0) <= :now))))
        ORDER BY COALESCE(rs.lastRead, 0) DESC, li.addedAt DESC, li.id LIMIT :limit""")
    abstract suspend fun candidates(now: Long, allowRetries: Boolean, limit: Int): List<CoverCandidate>

    /** Rows waiting for a source, a grant or storage (no timer); reassessed once per cold start. */
    @Query("""SELECT li.id AS id, li.format AS format FROM library_item li
        JOIN publication_cover pc ON pc.libraryItemId = li.id AND pc.slot = 'LOCAL'
        WHERE li.available = 1 AND pc.attemptStartedAt IS NULL AND pc.parked = 0 AND pc.status = 'FAILED_TEMPORARY'
          AND pc.failureReason IN (:reasons)
        ORDER BY li.addedAt DESC, li.id LIMIT :limit""")
    abstract suspend fun waiting(reasons: List<String>, limit: Int): List<CoverCandidate>

    @Query("SELECT * FROM publication_cover WHERE libraryItemId = :id AND slot = 'LOCAL'")
    abstract fun observeLocal(id: String): Flow<PublicationCoverEntity?>

    /** A trigger (§23.3, e.g. Refresh cover): opens a fresh window; never touches a row with an open attempt. */
    @Query("""UPDATE publication_cover SET attempts = 0, interruptions = 0, parked = 0, nextEligibleAt = NULL, updatedAt = :now
        WHERE libraryItemId = :id AND slot = 'LOCAL' AND attemptStartedAt IS NULL""")
    abstract suspend fun openWindow(id: String, now: Long): Int

    /** Startup reconciliation write for one abandoned attempt (§23.5 step 6); a READY row stays READY. */
    @Query("""UPDATE publication_cover SET attemptStartedAt = NULL, attemptExtractorVersion = NULL, interruptions = :interruptions,
        parked = :parked, nextEligibleAt = :nextEligibleAt, updatedAt = :now,
        failureReason = CASE WHEN status = 'READY' THEN NULL ELSE 'INTERRUPTED' END,
        status = CASE WHEN status = 'READY' THEN 'READY' ELSE 'FAILED_TEMPORARY' END
        WHERE libraryItemId = :id AND slot = :slot AND attemptStartedAt = :token""")
    abstract suspend fun recordInterruption(id: String, slot: String, token: Long, interruptions: Int, parked: Boolean, nextEligibleAt: Long?, now: Long): Int

    /** Clock moved backwards (§23.3): no persisted not-before time may lie beyond now + the largest single delay. */
    @Query("UPDATE publication_cover SET nextEligibleAt = :limit WHERE nextEligibleAt > :limit")
    abstract suspend fun clampNotBefore(limit: Long): Int

    @Query("""INSERT OR IGNORE INTO publication_cover (libraryItemId, slot, status, extractorVersion, attempts, interruptions, parked, updatedAt)
        SELECT :id, 'LOCAL', 'PENDING', :version, 0, 0, 0, :now WHERE EXISTS (SELECT 1 FROM library_item WHERE id = :id)""")
    protected abstract suspend fun insertPending(id: String, version: Int, now: Long)

    @Query("""UPDATE publication_cover SET attemptStartedAt = :token, attemptExtractorVersion = :version, attempts = attempts + 1, updatedAt = :token
        WHERE libraryItemId = :id AND slot = 'LOCAL' AND attemptStartedAt IS NULL AND EXISTS (SELECT 1 FROM library_item WHERE id = :id)""")
    protected abstract suspend fun markAttempt(id: String, token: Long, version: Int): Int

    /** Attempt-begin commit (§23.5 step 2): a guarded PENDING insert plus the marker. False = the item is gone or busy. */
    @Transaction open suspend fun beginAttempt(id: String, token: Long, version: Int): Boolean {
        insertPending(id, version, token)
        return markAttempt(id, token, version) == 1
    }

    @Query("SELECT fileName FROM publication_cover WHERE libraryItemId = :id AND slot = 'LOCAL' AND attemptStartedAt = :token")
    protected abstract suspend fun ownedFileName(id: String, token: Long): String?

    @Query("""UPDATE publication_cover SET status = 'READY', failureReason = NULL, fileName = :fileName, mimeType = :mimeType,
        representation = :representation, sourceWidthPx = :sourceWidth, sourceHeightPx = :sourceHeight, storedWidthPx = :storedWidth,
        storedHeightPx = :storedHeight, storedBytes = :storedBytes, localSource = :localSource, sourceRef = :sourceRef,
        sourceChoice = :sourceChoice, sourceRevision = :sourceRevision, extractorVersion = :version, attempts = 0, interruptions = 0,
        attemptStartedAt = NULL, attemptExtractorVersion = NULL, nextEligibleAt = NULL, parked = 0, updatedAt = :now
        WHERE libraryItemId = :id AND slot = 'LOCAL' AND attemptStartedAt = :token AND EXISTS (SELECT 1 FROM library_item WHERE id = :id)""")
    protected abstract suspend fun updateReady(id: String, token: Long, fileName: String, mimeType: String, representation: String,
        sourceWidth: Int, sourceHeight: Int, storedWidth: Int, storedHeight: Int, storedBytes: Long, localSource: String,
        sourceRef: String, sourceChoice: String, sourceRevision: String?, version: Int, now: Long): Int

    /**
     * Success commit (§23.5 step 4c): reads the previous file name, then the guarded UPDATE that must touch exactly one row.
     * Throws [CoverRejectedException] otherwise (item removed, or the attempt no longer owns the row), rolling back.
     */
    @Transaction open suspend fun commitReady(id: String, token: Long, fileName: String, mimeType: String, representation: String,
        sourceWidth: Int, sourceHeight: Int, storedWidth: Int, storedHeight: Int, storedBytes: Long, localSource: String,
        sourceRef: String, sourceChoice: String, sourceRevision: String?, version: Int, now: Long): String? {
        val previous = ownedFileName(id, token)
        val updated = updateReady(id, token, fileName, mimeType, representation, sourceWidth, sourceHeight, storedWidth, storedHeight,
            storedBytes, localSource, sourceRef, sourceChoice, sourceRevision, version, now)
        if (updated != 1) throw CoverRejectedException(updated)
        return previous
    }

    /** Closes an attempt without artwork. A READY row keeps READY and its file; only counters change (§23.3). */
    @Query("""UPDATE publication_cover SET attemptStartedAt = NULL, attemptExtractorVersion = NULL, attempts = :attempts,
        interruptions = :interruptions, parked = :parked, nextEligibleAt = :nextEligibleAt, extractorVersion = :version, updatedAt = :now,
        failureReason = CASE WHEN status = 'READY' THEN NULL ELSE :reason END,
        status = CASE WHEN status = 'READY' THEN 'READY' ELSE :status END
        WHERE libraryItemId = :id AND slot = 'LOCAL' AND attemptStartedAt = :token""")
    abstract suspend fun closeAttempt(id: String, token: Long, status: String, reason: String?, attempts: Int, interruptions: Int,
        parked: Boolean, nextEligibleAt: Long?, version: Int, now: Long): Int

    /** A cancelled attempt records nothing: marker cleared and the attempt given back; status untouched. */
    @Query("""UPDATE publication_cover SET attemptStartedAt = NULL, attemptExtractorVersion = NULL, attempts = MAX(attempts - 1, 0), updatedAt = :now
        WHERE libraryItemId = :id AND slot = 'LOCAL' AND attemptStartedAt = :token""")
    abstract suspend fun cancelAttempt(id: String, token: Long, now: Long): Int

    @Query("""UPDATE publication_cover SET status = 'FAILED_TEMPORARY', failureReason = :reason, updatedAt = :now
        WHERE libraryItemId = :id AND slot = 'LOCAL' AND attemptStartedAt IS NULL AND status IN ('PENDING', 'FAILED_TEMPORARY')""")
    protected abstract suspend fun markWaiting(id: String, reason: String, now: Long)

    /** The storage guard failed before any work (§18.1 item 3): no attempt, no timer, existing READY artwork untouched. */
    @Transaction open suspend fun recordWaiting(id: String, reason: String, version: Int, now: Long) {
        insertPending(id, version, now)
        markWaiting(id, reason, now)
    }
}

/** A guarded cover write affected [rows] rows instead of exactly one. */
class CoverRejectedException(val rows: Int) : IllegalStateException("guarded cover write affected $rows rows")
