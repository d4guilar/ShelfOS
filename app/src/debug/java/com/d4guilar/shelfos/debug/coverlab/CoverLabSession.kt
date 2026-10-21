// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.debug.coverlab

import android.content.pm.ApplicationInfo
import com.d4guilar.shelfos.AppContainer
import com.d4guilar.shelfos.core.designsystem.CoverArtwork
import com.d4guilar.shelfos.core.network.HttpUrlConnectionImageTransport
import com.d4guilar.shelfos.core.network.HttpUrlConnectionTransport
import com.d4guilar.shelfos.data.library.*
import com.d4guilar.shelfos.data.metadata.metadataQuery
import com.d4guilar.shelfos.data.metadata.openlibrary.*
import com.d4guilar.shelfos.data.preferences.SharedPreferencesBooleanStore
import com.d4guilar.shelfos.domain.library.CoverPresentation
import com.d4guilar.shelfos.domain.library.CoverPresentations
import com.d4guilar.shelfos.domain.library.CoverRef
import com.d4guilar.shelfos.domain.library.LibraryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One Book's Cover Lab state. Held per library item id, so another Book never shows this one's candidates or pick. */
data class LabUi(
    val candidates: List<OnlineCoverCandidate>? = null, val discovering: Boolean = false,
    /** The candidate key a preview or download is running for, and which. */
    val busyKey: String? = null, val busyDownload: Boolean = false,
    val previews: Map<String, PilotImage> = emptyMap(), val selection: PilotSelection? = null,
    val failure: PilotFailure? = null, val retryAfterMs: Long? = null,
)

/**
 * The debug-only Cover Lab engine (Phase 4.5C.2B, D1-C-DEV): wires the pilot pipeline to the app, owns per-Book UI state and the lab's own
 * [CoverArtwork]. That artwork feeds the SAME shared renderer (`PublicationCover`) through a lab-local map and presentation flow, so the
 * pilot's images never enter the production cover map, cache or `publication_cover`. Requests run on the app scope, so a rotation does not
 * cancel a transfer; closing the dialog cancels it ([cancel]). Cancellation, revoked consent and the item's existence are checked immediately before the final
 * persistence; a narrow race remains during the last synchronous commit, so an already-started commit can complete, but image and manifest are
 * replaced atomically and a partial or corrupt selection is never published.
 */
class CoverLabSession(
    val authorization: CoverPilotAuthorization, private val store: CoverPilotStore, private val service: CoverPilotService,
    private val scope: CoroutineScope, private val filesRoot: File, private val itemExists: suspend (String) -> Boolean,
) {
    init {
        // Pilot data of a Book that no longer exists is removed (a pick can never outlive its item); runs once, off the main thread.
        scope.launch {
            for (id in filesRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()) if (!runCatching { itemExists(id) }.getOrDefault(true)) store.remove(id)
        }
    }

    companion object {
        /** The real wiring: the app's shared pacer and user agent, its library and metadata stores, and the debug-gated authorization. */
        fun create(container: AppContainer): CoverLabSession {
            val app = container.application
            val authorization = CoverPilotAuthorization(SharedPreferencesBooleanStore(app, "cover_lab"),
                debugBuild = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            val filesRoot = File(app.filesDir, "cover_lab")
            val store = CoverPilotStore(filesRoot, File(app.cacheDir, "cover_lab"))
            val exists: suspend (String) -> Boolean = { id -> container.library.publication(id).first() != null }
            val ua = container.userAgent
            val json = HttpUrlConnectionTransport(OpenLibraryCoverRoutes.urlPolicy, ua, maxBytes = OpenLibrary.MAX_RESPONSE_BYTES, pacer = container.openLibraryPacer, check = OpenLibraryCoverRoutes::check)
            val images = HttpUrlConnectionImageTransport(OpenLibraryCovers.urlPolicy, ua, container.openLibraryPacer, OpenLibraryCovers::check)
            val service = CoverPilotService(authorization, store, images, OpenLibraryProvider(json)::coverEditions,
                queryFor = { item -> container.metadata.resolved(item.id)?.let { metadataQuery(item, it) } },
                itemExists = exists, freeBytes = container.coverFiles::freeBytes)
            return CoverLabSession(authorization, store, service, container.backgroundScope, filesRoot, exists)
        }
    }

    private val states = ConcurrentHashMap<String, MutableStateFlow<LabUi>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    // ---- the lab's renderer: a private map + files + presentation, read by the unchanged shared renderer ----
    private val labCovers = MutableStateFlow<Map<String, CoverRef>>(emptyMap())
    private val labFiles = ConcurrentHashMap<String, File>()
    private val labPresentations = MutableStateFlow(CoverPresentations())
    val artwork: CoverArtwork = CoverArtwork(labCovers, { ref -> labFiles.getValue(ref.fileName) }, {}, {}, { emptyFlow() },
        budgetBytes = 8L * 1024 * 1024, presentations = labPresentations)

    fun setPresentation(p: CoverPresentation) { labPresentations.value = CoverPresentations(global = p) }

    fun ui(itemId: String): StateFlow<LabUi> = states.getOrPut(itemId) { MutableStateFlow(LabUi()) }.asStateFlow()
    private fun update(itemId: String, f: (LabUi) -> LabUi) { states.getOrPut(itemId) { MutableStateFlow(LabUi()) }.update(f) }

    fun selectionId(itemId: String) = "$itemId/pick"
    fun candidateId(itemId: String, key: String) = "$itemId/$key"

    private fun register(id: String, file: File, image: PilotImage) {
        labFiles[file.name] = file
        labCovers.update { it + (id to CoverRef(id, file.name, image.width, image.height)) }
    }

    /** Reloads the stored pick (offline, no request) and any previews still on disk for [candidates]. */
    fun hydrate(itemId: String) {
        store.sweep(itemId)
        val s = store.selection(itemId)
        if (s != null) register(selectionId(itemId), store.file(s), PilotImage(s.mime, s.fileName.substringAfterLast('.'), s.width, s.height, s.width, s.height, s.bytes))
        else labCovers.update { it - selectionId(itemId) }
        update(itemId) { it.copy(selection = s) }
    }

    private fun launch(item: LibraryItem, block: suspend () -> Unit) {
        if (jobs[item.id]?.isActive == true) return   // one operation per Book at a time; the service also allows one transfer overall
        jobs[item.id] = scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { update(item.id) { it.copy(failure = PilotFailure.FAILED) } }
            finally { update(item.id) { it.copy(discovering = false, busyKey = null) } }
        }
    }

    fun find(item: LibraryItem) = launch(item) {
        update(item.id) { it.copy(discovering = true, failure = null, retryAfterMs = null) }
        when (val r = service.discover(item)) {
            is PilotResult.Ok -> {
                r.value.candidates.forEach { c ->
                    service.cachedPreview(item.id, c)?.let { f -> inspectImageBounds(f)?.let { img -> register(candidateId(item.id, c.key), f, img); update(item.id) { u -> u.copy(previews = u.previews + (c.key to img)) } } }
                }
                update(item.id) { it.copy(candidates = r.value.candidates) }
            }
            is PilotResult.Failed -> update(item.id) { it.copy(failure = r.reason, retryAfterMs = r.retryAfterMs) }
        }
    }

    fun preview(item: LibraryItem, candidate: OnlineCoverCandidate) = launch(item) {
        update(item.id) { it.copy(busyKey = candidate.key, busyDownload = false, failure = null, retryAfterMs = null) }
        when (val r = service.preview(item, candidate)) {
            is PilotResult.Ok -> { register(candidateId(item.id, candidate.key), r.value.file, r.value.image); update(item.id) { it.copy(previews = it.previews + (candidate.key to r.value.image)) } }
            is PilotResult.Failed -> update(item.id) { it.copy(failure = r.reason, retryAfterMs = r.retryAfterMs) }
        }
    }

    fun download(item: LibraryItem, candidate: OnlineCoverCandidate) = launch(item) {
        update(item.id) { it.copy(busyKey = candidate.key, busyDownload = true, failure = null, retryAfterMs = null) }
        when (val r = service.download(item, candidate)) {
            is PilotResult.Ok -> hydrate(item.id)
            is PilotResult.Failed -> update(item.id) { it.copy(failure = r.reason, retryAfterMs = r.retryAfterMs) }
        }
    }

    fun removePick(itemId: String) {
        service.removeSelection(itemId)
        labCovers.update { it - selectionId(itemId) }
        update(itemId) { it.copy(selection = null) }
    }

    /** Dialog closed: stop any running transfer for this Book so a stale result is never published into a later session. */
    fun cancel(itemId: String) { jobs.remove(itemId)?.cancel(); update(itemId) { it.copy(discovering = false, busyKey = null) } }
}
