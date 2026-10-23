// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.core.net.toUri
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
import com.d4guilar.shelfos.data.library.UserCoverStore
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
import com.d4guilar.shelfos.data.metadata.LocalMetadataAnalyzer
import com.d4guilar.shelfos.data.metadata.LocalMetadataEvidence
import com.d4guilar.shelfos.data.metadata.RoomMetadataRepository
import com.d4guilar.shelfos.data.preferences.RoomCoverPresentationRepository
import com.d4guilar.shelfos.domain.library.CoverPresentations
import com.d4guilar.shelfos.data.preferences.AppCompatLanguageRepository
import android.os.SystemClock
import com.d4guilar.shelfos.core.network.HttpUrlConnectionTransport
import com.d4guilar.shelfos.core.network.RequestPacer
import com.d4guilar.shelfos.core.network.HttpUrlConnectionImageTransport
import com.d4guilar.shelfos.data.library.OnlineCoverAuthorization
import com.d4guilar.shelfos.data.library.OnlineCoverConsent
import com.d4guilar.shelfos.data.library.OnlineCoverCooldown
import com.d4guilar.shelfos.data.library.SharedPreferencesLongStore
import com.d4guilar.shelfos.data.library.OnlineCoverFiles
import com.d4guilar.shelfos.data.library.OnlineCoverService
import com.d4guilar.shelfos.data.metadata.metadataQuery
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibraryCoverRoutes
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibraryCovers
import com.d4guilar.shelfos.feature.library.OnlineCoverController
import java.io.File
import com.d4guilar.shelfos.data.metadata.ManualMetadataLookup
import com.d4guilar.shelfos.data.metadata.ProviderLookup
import com.d4guilar.shelfos.data.metadata.ProviderPolicyEngine
import com.d4guilar.shelfos.data.metadata.ProviderRegistry
import com.d4guilar.shelfos.data.metadata.RegisteredProvider
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibrary
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibraryProvider
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.data.preferences.SharedPreferencesBooleanStore

class ShelfApplication : Application() {
    val container by lazy { AppContainer(this) }
}

class AppContainer(val application: Application) {
    private val database by lazy { ShelfDatabase.create(application) }
    val themes by lazy { RoomThemeRepository(database.appearance()) }
    val coverPresentation by lazy { RoomCoverPresentationRepository(database.coverPresentation()) }
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
    /** Phase 4.5B local metadata: the only metadata writer; analysis is offline and never blocks import or reading. */
    val metadata by lazy { RoomMetadataRepository(database) }
    val localMetadata by lazy {
        LocalMetadataAnalyzer(metadata, { id -> library.publication(id).first() }) { item ->
            withContext(Dispatchers.IO) { files.embeddedMetadata(item).let { LocalMetadataEvidence(it.title, it.creator, it.values) } }
        }
    }
    /** Phase 4.5C.2A: optional, default-OFF online metadata. The registry reads these settings at call time; nothing runs from startup, import or the Library. */
    val onlineSettings by lazy { OnlineMetadataSettings(SharedPreferencesBooleanStore(application)) }
    private val versionName: String by lazy {
        try { application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "dev" } catch (_: Exception) { "dev" }
    }
    /** One floor on request starts to every Open Library service (metadata and, in debug builds, the Cover Lab), so the two can never add up. */
    val openLibraryPacer by lazy { RequestPacer(OpenLibrary.MIN_INTERVAL_MS, SystemClock::elapsedRealtime) }
    val userAgent: String get() = "ShelfOS/$versionName (+https://github.com/d4guilar/ShelfOS)"
    private val providerRegistry by lazy {
        // Identified by the real repository URL; no email is configured, so no claim to Open Library's higher identified-request tier is made.
        val http = HttpUrlConnectionTransport(OpenLibrary.urlPolicy, userAgent,
            maxBytes = OpenLibrary.MAX_RESPONSE_BYTES, pacer = openLibraryPacer)
        ProviderRegistry(listOf(RegisteredProvider(OpenLibrary.descriptor(), OpenLibraryProvider(http))), onlineSettings)
    }
    val manualMetadata by lazy { ManualMetadataLookup(ProviderLookup(providerRegistry, ProviderPolicyEngine(SystemClock::elapsedRealtime)), metadata) }
    val coverScheduler by lazy {
        CoverScheduler(covers, coverFiles, { id -> library.publication(id).first() }, LocalCoverExtractor(files, fixedReaders),
            idleWork = localMetadata::analyzeNext)
    }
    /** Durable user-chosen covers (4.5F.0): offline; the picked image is copied into app storage, never kept as a URI. */
    val userCovers by lazy {
        UserCoverStore(covers, coverFiles, coverScheduler.lock, { uri -> application.contentResolver.openInputStream(uri.toUri()) },
            stagingRoot = onlineCoverFiles.stagingRoot)
    }
    /**
     * Phase 4.5C.3A (D1-C-PROD): manual Open Library cover selection for Books. Nothing here runs from startup, import, the Library or a theme change; a
     * person's button press starts each request. Cover consent is its own default-OFF preference, separate from [onlineSettings].
     */
    val onlineCoverFiles by lazy { OnlineCoverFiles(File(application.cacheDir, "cover_picker/previews"), File(application.cacheDir, "cover_picker/staging")) }
    val onlineCoverAuthorization by lazy {
        OnlineCoverAuthorization(onlineSettings, OnlineCoverConsent(SharedPreferencesBooleanStore(application, OnlineCoverConsent.PREFERENCES)))
    }
    val onlineCoverService by lazy {
        val ua = userAgent
        // Exact hosts, documented routes only, every redirect hop re-checked (F-2: an unlisted redirect target fails closed as REDIRECT_BLOCKED).
        val json = HttpUrlConnectionTransport(OpenLibraryCoverRoutes.urlPolicy, ua, maxBytes = OpenLibrary.MAX_RESPONSE_BYTES, pacer = openLibraryPacer, check = OpenLibraryCoverRoutes::check)
        val images = HttpUrlConnectionImageTransport(OpenLibraryCovers.urlPolicy, ua, openLibraryPacer, OpenLibraryCovers::check)
        OnlineCoverService(onlineCoverAuthorization, onlineCoverFiles, images, OpenLibraryProvider(json)::coverEditions,
            queryFor = { item -> metadata.resolved(item.id)?.let { metadataQuery(item, it) } },
            currentItem = { id -> library.publication(id).first() }, freeBytes = coverFiles::freeBytes,
            epochOf = userCovers::epoch, commit = { id, file, origin, epoch, guard -> userCovers.setFromPrivateFile(id, file, origin, epoch, guard) },
            cooldown = OnlineCoverCooldown(SharedPreferencesLongStore(application)))
    }
    val onlineCovers by lazy {
        OnlineCoverController(onlineCoverAuthorization, onlineCoverAuthorization.consent, onlineSettings, onlineCoverService, backgroundScope, coverArtwork.presentations)
    }
    /** What the shared cover renderer reads; one instance (one bitmap cache) per process. */
    val coverArtwork by lazy {
        CoverArtwork(covers.covers.stateIn<Map<String, CoverRef>?>(backgroundScope, SharingStarted.Eagerly, null),
            { ref -> coverFiles.file(ref.libraryItemId, ref.fileName) }, coverScheduler::request, coverScheduler::reportMissing, covers::provenance,
            presentations = coverPresentation.presentations.stateIn(backgroundScope, SharingStarted.Eagerly, CoverPresentations()))
    }
    val coverWork: Job = backgroundScope.launch {
        sourceMaintenance.join()
        try { coverScheduler.run() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Covers stay generated; next launch reconciles. */ }
    }
}
