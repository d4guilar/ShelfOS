// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.preferences

import com.d4guilar.shelfos.core.database.AppearanceDao
import com.d4guilar.shelfos.core.database.AppearancePreference
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.core.theme.ThemeRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface ThemeRepository {
    val theme: Flow<ThemeId>
    suspend fun select(id: ThemeId)
}

class RoomThemeRepository(private val dao: AppearanceDao) : ThemeRepository {
    override val theme = dao.observeTheme().map(ThemeRegistry::resolve).distinctUntilChanged()
    override suspend fun select(id: ThemeId) {
        require(ThemeRegistry.isAvailable(id)) { "Theme is not implemented" }
        dao.save(AppearancePreference(themeKey = id.storageKey))
    }
}
