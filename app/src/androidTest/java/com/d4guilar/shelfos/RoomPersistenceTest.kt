// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.preferences.RoomThemeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomPersistenceTest {
    @Test fun themeSurvivesDatabaseCloseAndReopen() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "theme-persistence-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ShelfDatabase::class.java, name).build()
        try {
            val first = open()
            try {
                val repository = RoomThemeRepository(first.appearance())
                assertEquals(ThemeId.CLASSIC, repository.theme.first())
                repository.select(ThemeId.DARK)
            } finally { first.close() }
            val reopened = open()
            try {
                assertEquals(ThemeId.DARK, RoomThemeRepository(reopened.appearance()).theme.first())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
