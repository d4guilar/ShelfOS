// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.app.Application
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.EpubReaderFactory
import kotlinx.coroutines.*
import com.d4guilar.shelfos.data.preferences.RoomThemeRepository

class ShelfApplication : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database by lazy { ShelfDatabase.create(application) }
    val themes by lazy { RoomThemeRepository(database.appearance()) }
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val files = PublicationFiles(application)
    val library by lazy { RoomLibraryRepository(database.library(), files) }
    val fixedReaders = FixedReaderFactory(files)
    val epubs = EpubReaderFactory(application, files)
}
