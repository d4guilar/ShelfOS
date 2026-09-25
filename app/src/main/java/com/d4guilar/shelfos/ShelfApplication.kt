// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.app.Application
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.EpubReaderFactory
import com.d4guilar.shelfos.domain.importing.ImportLeases
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
    /** Shared by every import: grants are process-wide, and import cleanup outlives the screen that started it. */
    val importLeases = ImportLeases()
    val library by lazy { RoomLibraryRepository(database.library(), files) }
    val fixedReaders = FixedReaderFactory(files)
    val epubs = EpubReaderFactory(application, files)
    /**
     * Cleans state an interrupted import may have left (partial copies, unneeded grants) and marks items whose
     * grant is gone as unavailable. Imports wait for it, so it can never release an in-flight import's grant.
     */
    val sourceMaintenance: Job = backgroundScope.launch {
        try { library.reconcileSources() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retried next launch. */ }
    }
}
