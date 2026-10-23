// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.runtime.staticCompositionLocalOf
import com.d4guilar.shelfos.core.designsystem.CoverArtwork
import com.d4guilar.shelfos.data.library.*
import com.d4guilar.shelfos.data.metadata.openlibrary.OnlineCoverCandidate
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibrary
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.library.CoverPresentations
import com.d4guilar.shelfos.domain.library.CoverRef
import com.d4guilar.shelfos.domain.library.LibraryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Provided once by the app; null (previews, isolated tests) hides Find cover online. */
val LocalOnlineCovers = staticCompositionLocalOf<OnlineCoverController?> { null }

/** One Book's picker state. Held per library item id, so another Book never shows this one's candidates. */
data class OnlineCoverUi(
    val candidates: List<OnlineCoverCandidate>? = null, val discovering: Boolean = false,
    /** The candidate a preview or the final download is running for, and which of the two. */
    val busyKey: String? = null, val busyUse: Boolean = false,
    val previews: Map<String, PilotImage> = emptyMap(), val saved: Boolean = false,
    val failure: OnlineCoverFailure? = null, val retryAfterMs: Long? = null,
)

/**
 * Presentation state of the manual online-cover picker (Phase 4.5C.3A). App-scoped, so a rotation does not lose the candidates or cancel a transfer;
 * closing the dialog cancels ([cancel]). Nothing runs unless a person presses a button, and withdrawing consent cancels everything at once and drops the
 * results ([OnlineCoverAuthorization.accessFlow]). Previews are drawn by the unchanged shared renderer through a picker-local map and the app's real
 * presentation flow, so a preview never enters the Library's cover map and a shape change never re-downloads.
 */
class OnlineCoverController(
    val authorization: OnlineCoverAuthorization, private val consent: OnlineCoverConsent, private val metadata: OnlineMetadataSettings,
    private val service: OnlineCoverService, private val scope: CoroutineScope, presentations: StateFlow<CoverPresentations>,
) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<OnlineCoverUi>>()
    private val jobs = ConcurrentHashMap<String, Job>()

    val access: StateFlow<OnlineCoverAccess> = authorization.accessFlow.stateIn(scope, SharingStarted.Eagerly, authorization.access())
    val coverConsent: StateFlow<Boolean> = consent.enabled

    private val covers = MutableStateFlow<Map<String, CoverRef>>(emptyMap())
    private val files = ConcurrentHashMap<String, File>()
    val artwork: CoverArtwork = CoverArtwork(covers, { ref -> files.getValue(ref.fileName) }, {}, {}, { emptyFlow() }, budgetBytes = 8L * 1024 * 1024, presentations = presentations)

    init {
        scope.launch { authorization.accessFlow.collect { if (it != OnlineCoverAccess.AVAILABLE) revoke() } }
    }

    fun ui(itemId: String): StateFlow<OnlineCoverUi> = states.getOrPut(itemId) { MutableStateFlow(OnlineCoverUi()) }.asStateFlow()
    private fun update(itemId: String, f: (OnlineCoverUi) -> OnlineCoverUi) { states.getOrPut(itemId) { MutableStateFlow(OnlineCoverUi()) }.update(f) }
    fun artworkId(itemId: String, key: String) = "$itemId/$key"

    /** The person's explicit consent. Each requirement is granted only if it was missing and the person ticked it; neither implies the other. */
    fun enable(metadataToo: Boolean, covers: Boolean) {
        if (metadataToo) metadata.enableProvider(OpenLibrary.ID)
        if (covers) consent.set(true)
    }
    fun setCoverConsent(enabled: Boolean) = consent.set(enabled)

    private fun revoke() {
        jobs.values.forEach { it.cancel() }; jobs.clear()
        states.keys.forEach { id -> update(id) { OnlineCoverUi() }; service.forget(id) }
        covers.value = emptyMap(); files.clear()
    }

    private fun launch(item: LibraryItem, block: suspend () -> Unit) {
        if (jobs[item.id]?.isActive == true) return   // a repeated press while one operation runs starts nothing
        jobs[item.id] = scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { update(item.id) { it.copy(failure = OnlineCoverFailure.FAILED) } }
            finally { update(item.id) { it.copy(discovering = false, busyKey = null, busyUse = false) } }
        }
    }

    private fun register(itemId: String, key: String, file: File, image: PilotImage) {
        files[file.name] = file
        covers.update { it + (artworkId(itemId, key) to CoverRef(artworkId(itemId, key), file.name, image.width, image.height)) }
    }

    /** Explicit "Find covers": the only thing that starts a discovery. */
    fun find(item: LibraryItem) = launch(item) {
        update(item.id) { it.copy(discovering = true, failure = null, retryAfterMs = null, saved = false) }
        when (val r = service.discover(item)) {
            is OnlineCoverResult.Ok -> {
                r.value.candidates.forEach { c -> service.cachedPreview(item.id, c.key)?.let { f -> inspectImageBounds(f)?.let { img ->
                    register(item.id, c.key, f, img); update(item.id) { u -> u.copy(previews = u.previews + (c.key to img)) } } } }
                update(item.id) { it.copy(candidates = r.value.candidates) }
            }
            is OnlineCoverResult.Failed -> update(item.id) { it.copy(failure = r.reason, retryAfterMs = r.retryAfterMs) }
        }
    }

    fun preview(item: LibraryItem, key: String) = launch(item) {
        update(item.id) { it.copy(busyKey = key, busyUse = false, failure = null, retryAfterMs = null) }
        when (val r = service.preview(item, key)) {
            is OnlineCoverResult.Ok -> { register(item.id, key, r.value.file, r.value.image); update(item.id) { it.copy(previews = it.previews + (key to r.value.image)) } }
            is OnlineCoverResult.Failed -> update(item.id) { it.copy(failure = r.reason, retryAfterMs = r.retryAfterMs) }
        }
    }

    /** The explicit "Use this cover". */
    fun use(item: LibraryItem, key: String) = launch(item) {
        update(item.id) { it.copy(busyKey = key, busyUse = true, failure = null, retryAfterMs = null, saved = false) }
        when (val r = service.select(item, key)) {
            is OnlineCoverResult.Ok -> update(item.id) { it.copy(saved = true) }
            is OnlineCoverResult.Failed -> update(item.id) { it.copy(failure = r.reason, retryAfterMs = r.retryAfterMs) }
        }
    }

    /** Dialog closed: stop any running transfer for this Book so a stale result is never published into a later session. */
    fun cancel(itemId: String) { jobs.remove(itemId)?.cancel(); update(itemId) { it.copy(discovering = false, busyKey = null, busyUse = false) } }
}
