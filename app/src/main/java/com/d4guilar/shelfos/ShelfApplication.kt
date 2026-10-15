// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.app.Application
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.annotations.PortableKnowledgeFiles
import com.d4guilar.shelfos.data.annotations.RoomPortableKnowledgeRepository
import com.d4guilar.shelfos.core.reader.isReadiumLocatorJson
import com.d4guilar.shelfos.domain.annotations.DefaultAnnotationLocatorValidator
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.core.reader.LocalCoverExtractor
import com.d4guilar.shelfos.data.library.CoverScheduler
import com.d4guilar.shelfos.data.library.RoomCoverRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import com.d4guilar.shelfos.core.designsystem.CoverArtwork
import com.d4guilar.shelfos.domain.library.CoverRef
import com.d4guilar.shelfos.core.reader.FixedReaderFactory
import com.d4guilar.shelfos.core.reader.EpubReaderFactory
import com.d4guilar.shelfos.core.reader.ManagedFontRepository
import com.d4guilar.shelfos.domain.importing.ImportLeases
import kotlinx.coroutines.*
import com.d4guilar.shelfos.data.preferences.RoomThemeRepository
import com.d4guilar.shelfos.data.preferences.AppCompatLanguageRepository

class ShelfApplication : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database by lazy { ShelfDatabase.create(application) }
    val themes by lazy { RoomThemeRepository(database.appearance()) }
    val languages by lazy { AppCompatLanguageRepository() }
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val files = PublicationFiles(application)
    /** Shared by every import: grants are process-wide, and import cleanup outlives the screen that started it. */
    val importLeases = ImportLeases()
    val coverFiles = CoverFiles(application)
    val library by lazy { RoomLibraryRepository(database.library(), files, coverFiles) }
    val covers by lazy { RoomCoverRepository(database.covers()) }
    /** Canonical annotation store; the single owner of knowledge persistence (ADR-0025). */
    val annotations by lazy { RoomAnnotationRepository(database.annotations(), DefaultAnnotationLocatorValidator(::isReadiumLocatorJson)) }
    val portableKnowledge by lazy {
        val version = application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "unknown"
        PortableKnowledgeFiles(application.contentResolver, annotations, RoomPortableKnowledgeRepository(database), version)
    }
    val fixedReaders = FixedReaderFactory(files)
    val fonts = ManagedFontRepository(application)
    val epubs = EpubReaderFactory(application, files, fonts::epubResources)
    /**
     * Cleans state an interrupted import may have left (partial copies, unneeded grants) and marks items whose
     * grant is gone as unavailable. Imports wait for it, so it can never release an in-flight import's grant.
     */
    val sourceMaintenance: Job = backgroundScope.launch {
        try { library.reconcileSources() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Retried next launch. */ }
    }
    /** Local cover backfill (Phase 4.5A.1): reconcile → sweep → then idle, gated scheduling. Never blocks startup. */
    val coverScheduler by lazy { CoverScheduler(covers, coverFiles, { id -> library.publication(id).first() }, LocalCoverExtractor(files, fixedReaders)) }
    /** What the shared cover renderer reads; one instance (one bitmap cache) per process. */
    val coverArtwork by lazy {
        CoverArtwork(covers.covers.stateIn<Map<String, CoverRef>?>(backgroundScope, SharingStarted.Eagerly, null),
            { ref -> coverFiles.file(ref.libraryItemId, ref.fileName) }, coverScheduler::request, coverScheduler::reportMissing, covers::provenance)
    }
    val coverWork: Job = backgroundScope.launch {
        sourceMaintenance.join()
        try { coverScheduler.run() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Covers stay generated; next launch reconciles. */ }
    }
}
