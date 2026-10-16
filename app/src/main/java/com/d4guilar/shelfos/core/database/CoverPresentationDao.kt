// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.d4guilar.shelfos.domain.library.StoredCoverPresentation
import kotlinx.coroutines.flow.Flow

/** Phase 4.5A.2 (plan §23.6): one row per scope (GLOBAL or a category). No row = that scope's default. Enum names, no CHECK. */
@Entity(tableName = "cover_presentation_preference")
data class CoverPresentationPreferenceEntity(@PrimaryKey val scope: String, val shape: String, val customWidth: Int?, val customHeight: Int?, val fit: String)

@Dao
interface CoverPresentationDao {
    @Query("SELECT scope, shape, customWidth, customHeight, fit FROM cover_presentation_preference")
    fun observeAll(): Flow<List<StoredCoverPresentation>>

    @Upsert
    suspend fun save(preference: CoverPresentationPreferenceEntity)

    @Query("DELETE FROM cover_presentation_preference WHERE scope = :scope")
    suspend fun delete(scope: String)
}
