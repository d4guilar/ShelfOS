// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.theme.*
import com.d4guilar.shelfos.data.library.*
import com.d4guilar.shelfos.feature.home.*
import org.junit.Assert.*
import org.junit.Test

class FoundationTest {
    @Test fun unavailableAndUnknownThemesFallBackToClassic() {
        assertEquals(5, ThemeRegistry.entries.size)
        assertEquals(5, ThemeRegistry.entries.map { it.id.storageKey }.toSet().size)
        assertEquals(listOf(ThemeId.CLASSIC, ThemeId.DARK), ThemeRegistry.entries.filter { it.available }.map { it.id })
        listOf(null, "retired-theme", "paper", "retro_light", "retro_dark").forEach {
            assertEquals(ThemeId.CLASSIC, ThemeRegistry.resolve(it))
        }
        assertEquals(ThemeId.DARK, ThemeRegistry.resolve("dark"))
    }

    @Test fun navigationUsesWindowDimensionsAtBoundaries() {
        assertEquals(NavigationLayout.BOTTOM, adaptiveLayout(599f, 800f).navigation)
        assertEquals(NavigationLayout.RAIL, adaptiveLayout(600f, 800f).navigation)
        assertEquals(NavigationLayout.RAIL, adaptiveLayout(550f, 360f).navigation)
        assertEquals(NavigationLayout.BOTTOM, adaptiveLayout(400f, 360f).navigation)
        assertFalse(adaptiveLayout(999f, 800f).showDetails)
        assertTrue(adaptiveLayout(1000f, 800f).showDetails)
        assertFalse(adaptiveLayout(1200f, 479f).showDetails)
    }

    @Test fun focusKeysDoNotBecomeReaderCommandsInLibrary() {
        assertNull(InputMapper.command(KeyStroke(InputKey.RIGHT), InputContext.LIBRARY))
        assertEquals(ShelfCommand.NEXT_PAGE, InputMapper.command(KeyStroke(InputKey.RIGHT), InputContext.READER))
        assertEquals(ShelfCommand.PREVIOUS_PAGE, InputMapper.command(KeyStroke(InputKey.SPACE, shift = true), InputContext.READER))
        assertNull(InputMapper.command(KeyStroke(InputKey.B), InputContext.LIBRARY))
        assertEquals(ShelfCommand.TOGGLE_BOOKMARK, InputMapper.command(KeyStroke(InputKey.B), InputContext.READER))
    }

    @Test fun systemNavigationAndTextShortcutsRemainAvailable() {
        InputContext.entries.forEach { context ->
            assertNull(InputMapper.command(KeyStroke(InputKey.HOME), context))
            assertNull(InputMapper.command(KeyStroke(InputKey.BACK), context))
            assertNull(InputMapper.command(KeyStroke(InputKey.B, control = true), context))
            assertNull(InputMapper.command(KeyStroke(InputKey.LEFT, alt = true), context))
            assertEquals(ShelfCommand.SEARCH, InputMapper.command(KeyStroke(InputKey.F, control = true), context))
            assertEquals(ShelfCommand.BACK, InputMapper.command(KeyStroke(InputKey.GAMEPAD_B), context))
            assertEquals(ShelfCommand.CONFIRM, InputMapper.command(KeyStroke(InputKey.GAMEPAD_A), context))
        }
    }

    @Test fun favoritesAreIndependentOfMediaCategory() {
        val items = DemoLibraryRepository().publications
        val favorites = setOf("forest", "field")
        val result = filterPublications(items, LibraryFilter.FAVORITES, favorites)
        assertEquals(setOf(MediaCategory.MANGA, MediaCategory.DOCUMENT), result.map { it.category }.toSet())
        assertEquals(setOf("forest"), filterPublications(items, LibraryFilter.MANGA, emptySet(), " SORA ").map { it.id }.toSet())
        assertTrue(filterPublications(items, LibraryFilter.BOOKS, favorites).all { it.category == MediaCategory.BOOK })
    }
}
