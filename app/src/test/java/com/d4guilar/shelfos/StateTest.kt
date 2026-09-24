// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.domain.library.LibraryFilter
import com.d4guilar.shelfos.data.preferences.ThemeRepository
import com.d4guilar.shelfos.feature.library.LibraryViewModel
import com.d4guilar.shelfos.feature.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

@OptIn(ExperimentalCoroutinesApi::class)
class StateTest {
    @Before fun installMainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }

    @Test fun restoredLibraryKeepsCategorySelectionQueryAndFavorites() = runTest {
        val saved = SavedStateHandle()
        val repository = TestLibrary()
        val vm = LibraryViewModel(repository, saved)
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            vm.selectFilter(LibraryFilter.DOCUMENTS)
            vm.select("field")
            vm.search("notes")
            vm.toggleFavorite("field")
            advanceUntilIdle()
            // A new handle with only saved primitives simulates state restoration.
            val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
            val recreated = LibraryViewModel(repository, restored)
            advanceUntilIdle()
            assertEquals(LibraryFilter.DOCUMENTS, recreated.state.value.filter)
            assertEquals("field", recreated.state.value.selected?.id)
            assertEquals("notes", recreated.state.value.query)
            assertFalse("field" in recreated.state.value.favorites)
            recreated.viewModelScope.cancel()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun failedThemeWriteLeavesCurrentAppearanceAndReportsFailure() = runTest {
        val repository = object : ThemeRepository {
            override val theme = MutableStateFlow(ThemeId.CLASSIC)
            override suspend fun select(id: ThemeId) { throw java.io.IOException("test disk failure") }
        }
        val vm = SettingsViewModel(repository)
        try {
            advanceUntilIdle()
            vm.select(ThemeId.DARK)
            advanceUntilIdle()
            assertEquals(ThemeId.CLASSIC, vm.theme.value)
            assertNotNull(vm.error.value)
        } finally { vm.viewModelScope.cancel() }
    }
}
