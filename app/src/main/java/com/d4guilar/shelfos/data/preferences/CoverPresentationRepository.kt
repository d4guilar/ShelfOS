// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.preferences

import com.d4guilar.shelfos.core.database.CoverPresentationDao
import com.d4guilar.shelfos.core.database.CoverPresentationPreferenceEntity
import com.d4guilar.shelfos.domain.library.CoverPresentation
import com.d4guilar.shelfos.domain.library.CoverPresentations
import com.d4guilar.shelfos.domain.library.CoverScope
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.coverPresentation
import com.d4guilar.shelfos.domain.library.coverScope
import com.d4guilar.shelfos.domain.library.resolveCoverPresentations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Library cover shape/fit (§8). Theme-independent: no theme code reads or writes it. */
interface CoverPresentationRepository {
    val presentations: Flow<CoverPresentations>
    suspend fun setGlobal(presentation: CoverPresentation)
    /** null clears the override ("Use default"). */
    suspend fun setOverride(category: MediaCategory, presentation: CoverPresentation?)
}

class RoomCoverPresentationRepository(private val dao: CoverPresentationDao) : CoverPresentationRepository {
    override val presentations = dao.observeAll().map(::resolveCoverPresentations).distinctUntilChanged()
    override suspend fun setGlobal(presentation: CoverPresentation) = save(CoverScope.GLOBAL, presentation)
    override suspend fun setOverride(category: MediaCategory, presentation: CoverPresentation?) =
        if (presentation == null) dao.delete(category.coverScope().name) else save(category.coverScope(), presentation)

    private suspend fun save(scope: CoverScope, p: CoverPresentation) {
        // Trust boundary: only a presentation the validated constructor accepts is ever written.
        require(coverPresentation(p.shape, p.fit, p.customWidth, p.customHeight) == p) { "Invalid cover presentation" }
        dao.save(CoverPresentationPreferenceEntity(scope.name, p.shape.name, p.customWidth, p.customHeight, p.fit.name))
    }
}
