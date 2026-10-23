// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.network.ImageTransport
import com.d4guilar.shelfos.core.network.ResponseTooLargeException
import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.data.metadata.RetryAfter
import com.d4guilar.shelfos.data.metadata.openlibrary.*
import com.d4guilar.shelfos.domain.library.CoverFailureReason
import com.d4guilar.shelfos.domain.library.CoverOrigin
import com.d4guilar.shelfos.domain.library.CoverOriginKind
import com.d4guilar.shelfos.domain.library.CoverStoragePolicy
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.MetadataQuery
import com.d4guilar.shelfos.domain.metadata.ProviderException
import com.d4guilar.shelfos.domain.metadata.ProviderFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** What a person-facing failure of a manual online cover step can be; none of these is a [com.d4guilar.shelfos.domain.library.PublicationProblem]. */
enum class OnlineCoverFailure {
    NOT_AUTHORIZED, NO_ISBN, NOT_FOUND, OFFLINE, TIMEOUT, RATE_LIMITED, ACCESS_BLOCKED, SERVER_FAILURE, MALFORMED, REDIRECT_BLOCKED, TOO_LARGE,
    NOT_AN_IMAGE, MIME_MISMATCH, UNSUPPORTED_IMAGE, INVALID_IMAGE, STORAGE_FULL, BUDGET_EXHAUSTED, BUSY, ITEM_GONE, STALE, SUPERSEDED, FAILED,
}

sealed interface OnlineCoverResult<out T> {
    data class Ok<T>(val value: T) : OnlineCoverResult<T>
    data class Failed(val reason: OnlineCoverFailure, val retryAfterMs: Long? = null) : OnlineCoverResult<Nothing>
}

/** What discovery found. [candidates] are only offered; nothing is applied. */
data class OnlineCoverDiscovery(val candidates: List<OnlineCoverCandidate>, val isbn13: String)

/**
 * App-private, evictable scratch space of the picker: `previews/<itemId>/pv-<key>.<ext>` (validated M-size previews, reused instead of requested again)
 * and a flat `staging/` directory holding a selected image between download and commit. Both live under `cacheDir`, so the system may reclaim them; the
 * saved cover itself is stored by [UserCoverStore] under `filesDir/covers`, never here. File names are drawn locally, never taken from remote text.
 */
class OnlineCoverFiles(private val previewRoot: File, val stagingRoot: File) {
    private val random = SecureRandom()

    // Construction happens once per process, before any transfer can start, so whatever a killed process left behind is abandoned.
    init { sweepTemporaries() }
    private fun token() = "%016x".format(random.nextLong())
    private fun itemDir(itemId: String): File { require(ID.matches(itemId)) { "unsafe library item id" }; return File(previewRoot, itemId) }

    fun newPreviewTemporary(itemId: String): File = File(itemDir(itemId).also { it.mkdirs() }, "${token()}.tmp")
    fun newStagingTemporary(): File = File(stagingRoot.also { it.mkdirs() }, "${token()}.tmp")

    fun previewFile(itemId: String, key: String): File? {
        require(ID.matches(key))
        return itemDir(itemId).listFiles()?.firstOrNull { it.name.startsWith("pv-$key.") && it.length() > 0 }
    }

    fun commitPreview(itemId: String, temporary: File, key: String, extension: String): File {
        require(ID.matches(key) && EXT.matches(extension))
        val dir = itemDir(itemId).also { it.mkdirs() }
        dir.listFiles()?.filter { it.name.startsWith("pv-$key.") }?.forEach { it.delete() }
        val target = File(dir, "pv-$key.$extension")
        if (!temporary.renameTo(target)) throw IOException("cannot finalize preview")
        dir.listFiles()?.filter { it.name.startsWith("pv-") }?.sortedByDescending { it.lastModified() }?.drop(MAX_PREVIEWS)?.forEach { it.delete() }
        return target
    }

    fun clearPreviews(itemId: String) { runCatching { itemDir(itemId).deleteRecursively() } }

    /** Removes anything a killed process left behind: every staged download and every unfinished preview. Committed previews are kept. */
    fun sweepTemporaries() {
        runCatching { stagingRoot.listFiles()?.forEach { it.delete() } }
        runCatching { previewRoot.listFiles()?.forEach { d -> d.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() } } }
    }

    companion object {
        const val MAX_PREVIEWS = 8
        private val ID = Regex("[A-Za-z0-9-]{1,64}")
        private val EXT = Regex("[a-z]{3,4}")
    }
}

/**
 * The production request budget (Phase 4.5C.3A), on top of the shared 2 s pacer and the single transfer slot: at most [maxDiscoveries] discoveries and
 * [maxImages] image transfers in any [windowMs] window. A human action starts each one; unlike the pilot's per-process counter this refills as time
 * passes, so it limits bursts without disabling the feature for the life of a long-running process. A denied request consumes nothing.
 */
class OnlineCoverBudget(private val maxDiscoveries: Int = 8, private val maxImages: Int = 30, private val windowMs: Long = 3_600_000L, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val discoveries = ArrayDeque<Long>()
    private val images = ArrayDeque<Long>()
    private fun take(q: ArrayDeque<Long>, max: Int): Boolean {
        val now = nowMs()
        while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
        if (q.size >= max) return false
        q.addLast(now); return true
    }
    @Synchronized fun takeDiscovery() = take(discoveries, maxDiscoveries)
    @Synchronized fun takeImage() = take(images, maxImages)
}

/** A single persisted number (the cooldown deadline); a fake in JVM tests. */
interface LongStore {
    fun get(key: String, default: Long): Long
    fun put(key: String, value: Long)
}

class SharedPreferencesLongStore(context: Context, name: String = OnlineCoverConsent.PREFERENCES) : LongStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String, default: Long) = prefs.getLong(key, default)
    override fun put(key: String, value: Long) { prefs.edit { putLong(key, value) } }
}

class MemoryLongStore : LongStore {
    private val values = HashMap<String, Long>()
    @Synchronized override fun get(key: String, default: Long) = values[key] ?: default
    @Synchronized override fun put(key: String, value: Long) { values[key] = value }
}

/**
 * The provider-imposed pause after a 403/429 (D31), kept across process restarts. The deadline is stored as wall-clock time (the only clock that means
 * anything after a restart) and, on construction, turned into a remaining duration clamped to [MAX_PAUSE_MS] so a clock moved backwards can never
 * create a longer pause; within the process expiry is measured on [monotonicMs]. A new pause never shortens a running one, and nothing here is cleared
 * by consent changes. All access is synchronized, so concurrent updates cannot lose or shorten each other.
 */
class OnlineCoverCooldown(
    private val store: LongStore,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private var untilMono = 0L

    init {
        val stored = store.get(KEY, 0L)
        val remaining = (stored - wallMs()).coerceAtMost(MAX_PAUSE_MS)
        if (remaining > 0) untilMono = monotonicMs() + remaining else if (stored != 0L) store.put(KEY, 0L)
    }

    /** Milliseconds left of the pause, or 0 when requests may start. */
    @Synchronized fun remainingMs(): Long = (untilMono - monotonicMs()).coerceAtLeast(0L)

    /** Starts a pause of [retryAfterMs] (default and bounds apply) unless a longer one is already running. */
    @Synchronized fun pause(retryAfterMs: Long?) {
        val wait = (retryAfterMs ?: DEFAULT_PAUSE_MS).coerceIn(MIN_PAUSE_MS, MAX_PAUSE_MS)
        val mono = monotonicMs()
        if (mono + wait <= untilMono) return
        untilMono = mono + wait
        store.put(KEY, wallMs() + wait)
    }

    companion object {
        const val KEY = "open_library_cover_pause_until"
        const val MIN_PAUSE_MS = 1_000L
        const val DEFAULT_PAUSE_MS = 5 * 60_000L
        const val MAX_PAUSE_MS = 60 * 60_000L
    }
}

/**
 * Manual online cover selection for Books (Phase 4.5C.3A, D1-C-PROD): discover → preview → explicit select. Every public step is started by a person,
 * is refused unless [authorization] holds at that moment (checked before any request, again under the slot, and again immediately before the commit),
 * handles one operation at a time ([slot]), never retries by itself, and pauses all requests after a 403/429 until `Retry-After` ([cooldown], which survives a restart). The UI hands over a
 * candidate KEY only: the candidate itself is the one this service produced in the item's latest discovery, so a forged, stale or foreign candidate cannot
 * name a URL. The selected image becomes the Book's USER cover through [commit] (production: [UserCoverStore.setFromPrivateFile]) and nothing here writes
 * the ONLINE slot or any other cover row, runs in the background or fetches on its own.
 */
class OnlineCoverService(
    private val authorization: OnlineCoverAuthorization,
    private val files: OnlineCoverFiles,
    private val images: ImageTransport,
    /** An ISBN-13's cover editions (production: [OpenLibraryProvider.coverEditions]). */
    private val editions: suspend (String) -> List<CoverEdition>,
    private val queryFor: suspend (LibraryItem) -> MetadataQuery?,
    /** The current library item, or null once it is gone. */
    private val currentItem: suspend (String) -> LibraryItem?,
    private val freeBytes: () -> Long,
    /** The Book's current cover-choice epoch ([UserCoverStore.epoch]). */
    private val epochOf: (String) -> Long,
    /** Saves a validated private file as the USER cover ([UserCoverStore.setFromPrivateFile]). */
    private val commit: suspend (itemId: String, file: File, origin: CoverOrigin, expectedEpoch: Long, guard: () -> Boolean) -> UserCoverOutcome,
    private val normalizer: PilotImageNormalizer = CoverWriterNormalizer,
    private val inspect: (File) -> PilotImage? = ::inspectImageBounds,
    private val budget: OnlineCoverBudget = OnlineCoverBudget(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val cooldown: OnlineCoverCooldown = OnlineCoverCooldown(MemoryLongStore(), nowMs, nowMs),
) {
    private val slot = Mutex()
    private val discovered = ConcurrentHashMap<String, Map<String, OnlineCoverCandidate>>()

    private fun permitted(item: LibraryItem) = item.category == MediaCategory.BOOK && authorization.permits(OpenLibrary.ID, item.category)
    private fun pauseFailure() = cooldown.remainingMs().takeIf { it > 0 }?.let { OnlineCoverResult.Failed(OnlineCoverFailure.RATE_LIMITED, it) }
    private fun pause(retryAfterMs: Long?) = cooldown.pause(retryAfterMs)

    /** The candidates the latest discovery produced for [itemId], or empty after a restart (the person searches again). */
    fun known(itemId: String): List<OnlineCoverCandidate> = discovered[itemId]?.values?.toList().orEmpty()
    fun cachedPreview(itemId: String, key: String): File? = if (discovered[itemId]?.containsKey(key) == true) files.previewFile(itemId, key) else null
    fun forget(itemId: String) { discovered.remove(itemId); files.clearPreviews(itemId) }

    suspend fun discover(item: LibraryItem): OnlineCoverResult<OnlineCoverDiscovery> {
        if (!permitted(item)) return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AUTHORIZED)
        val query = queryFor(item) ?: return OnlineCoverResult.Failed(OnlineCoverFailure.ITEM_GONE)
        val isbn = query.identifiers.firstNotNullOfOrNull { it.isbn13 } ?: return OnlineCoverResult.Failed(OnlineCoverFailure.NO_ISBN)
        pauseFailure()?.let { return it }
        if (!slot.tryLock()) return OnlineCoverResult.Failed(OnlineCoverFailure.BUSY)
        try {
            if (!permitted(item)) return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AUTHORIZED)
            if (!budget.takeDiscovery()) return OnlineCoverResult.Failed(OnlineCoverFailure.BUDGET_EXHAUSTED)
            val found = try { editions(isbn) } catch (e: ProviderException) {
                if (e.kind == ProviderFailureKind.RATE_LIMITED || e.kind == ProviderFailureKind.ACCESS_BLOCKED) pause(e.retryAfterMs)
                return OnlineCoverResult.Failed(fromProvider(e.kind), e.retryAfterMs)
            }
            // Consent withdrawn or the Book removed while waiting: the result is discarded, never shown or kept.
            if (!permitted(item)) return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AUTHORIZED)
            if (currentItem(item.id) == null) return OnlineCoverResult.Failed(OnlineCoverFailure.ITEM_GONE)
            val candidates = OpenLibraryCoverCandidates.build(query, isbn, found)
            discovered[item.id] = candidates.associateBy { it.key }
            return OnlineCoverResult.Ok(OnlineCoverDiscovery(candidates, isbn))
        } finally { slot.unlock() }
    }

    /** A small (M) look at one candidate, only because the person asked. A preview already on disk is reused with no request. */
    suspend fun preview(item: LibraryItem, key: String): OnlineCoverResult<PilotArtwork> {
        val candidate = discovered[item.id]?.get(key) ?: return OnlineCoverResult.Failed(OnlineCoverFailure.STALE)
        if (!owned(item, candidate)) return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AUTHORIZED)
        files.previewFile(item.id, key)?.let { f -> inspect(f)?.let { return OnlineCoverResult.Ok(PilotArtwork(f, it, true)) } }
        return withSlot(item, candidate) {
            val raw = files.newPreviewTemporary(item.id); val out = files.newPreviewTemporary(item.id)
            try {
                fetchValidated(candidate, CoverSize.MEDIUM, raw)?.let { return@withSlot it }
                val image = try { normalizer.normalize(raw, out) } catch (e: CancellationException) { throw e
                } catch (e: CoverExtractionFailure) { return@withSlot OnlineCoverResult.Failed(imageFailure(e)) } catch (_: OutOfMemoryError) { return@withSlot OnlineCoverResult.Failed(OnlineCoverFailure.UNSUPPORTED_IMAGE) }
                if (image.width <= 0 || image.height <= 0) return@withSlot OnlineCoverResult.Failed(OnlineCoverFailure.INVALID_IMAGE)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    if (!owned(item, candidate)) return@withContext OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AUTHORIZED)
                    OnlineCoverResult.Ok(PilotArtwork(files.commitPreview(item.id, out, key, image.extension), image, false))
                }
            } finally { withContext(NonCancellable) { raw.delete(); out.delete() } }
        }
    }

    /**
     * The person's explicit "Use this cover". Downloads the large (L) image, validates it, and saves it as the Book's USER cover with the candidate's
     * provenance. Superseded by any newer cover choice for the Book (including one made while this runs) and committed only while consent still holds.
     */
    suspend fun select(item: LibraryItem, key: String): OnlineCoverResult<Unit> {
        val candidate = discovered[item.id]?.get(key) ?: return OnlineCoverResult.Failed(OnlineCoverFailure.STALE)
        if (!owned(item, candidate)) return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AUTHORIZED)
        val epoch = epochOf(item.id)
        return withSlot(item, candidate) {
            val raw = files.newStagingTemporary()
            try {
                fetchValidated(candidate, CoverSize.LARGE, raw)?.let { return@withSlot it }
                currentCoroutineContext().ensureActive()
                if (currentItem(item.id)?.category != MediaCategory.BOOK) return@withSlot OnlineCoverResult.Failed(OnlineCoverFailure.ITEM_GONE)
                val origin = CoverOrigin(CoverOriginKind.PROVIDER_SELECTED, candidate.providerId, candidate.editionRef, candidate.ref.key, candidate.ref.value, nowMs())
                when (commit(item.id, raw, origin, epoch) { owned(item, candidate) }) {
                    UserCoverOutcome.SAVED -> { files.clearPreviews(item.id); OnlineCoverResult.Ok(Unit) }
                    UserCoverOutcome.ITEM_GONE -> OnlineCoverResult.Failed(OnlineCoverFailure.ITEM_GONE)
                    UserCoverOutcome.INVALID_IMAGE -> OnlineCoverResult.Failed(OnlineCoverFailure.INVALID_IMAGE)
                    UserCoverOutcome.UNSUPPORTED_IMAGE -> OnlineCoverResult.Failed(OnlineCoverFailure.UNSUPPORTED_IMAGE)
                    UserCoverOutcome.STORAGE_FULL -> OnlineCoverResult.Failed(OnlineCoverFailure.STORAGE_FULL)
                    UserCoverOutcome.SUPERSEDED -> OnlineCoverResult.Failed(OnlineCoverFailure.SUPERSEDED)
                    UserCoverOutcome.NOT_PERMITTED -> OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AUTHORIZED)
                    else -> OnlineCoverResult.Failed(OnlineCoverFailure.FAILED)
                }
            } finally { withContext(NonCancellable) { raw.delete() } }
        }
    }

    /** Whether [candidate] may be requested at all: an authorized Book and a candidate of this provider. Checked before any URL, slot, budget or request. */
    private fun owned(item: LibraryItem, candidate: OnlineCoverCandidate) = permitted(item) && candidate.providerId == OpenLibrary.ID

    private suspend fun <T> withSlot(item: LibraryItem, candidate: OnlineCoverCandidate, block: suspend () -> OnlineCoverResult<T>): OnlineCoverResult<T> {
        pauseFailure()?.let { return it }
        if (!slot.tryLock()) return OnlineCoverResult.Failed(OnlineCoverFailure.BUSY)   // a second press while a transfer runs starts nothing
        try {
            if (!owned(item, candidate)) return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AUTHORIZED)
            if (currentItem(item.id) == null) return OnlineCoverResult.Failed(OnlineCoverFailure.ITEM_GONE)
            if (!CoverStoragePolicy.admits(freeBytes(), OpenLibraryCovers.MAX_RAW_BYTES.toLong())) return OnlineCoverResult.Failed(OnlineCoverFailure.STORAGE_FULL)
            if (!budget.takeImage()) return OnlineCoverResult.Failed(OnlineCoverFailure.BUDGET_EXHAUSTED)
            return block()
        } catch (e: CancellationException) { throw e
        } catch (_: IOException) { return OnlineCoverResult.Failed(OnlineCoverFailure.FAILED)
        } finally { slot.unlock() }
    }

    /** Downloads [candidate]'s artwork into [raw] and checks status, content type and magic bytes; null = a valid image file is at [raw]. */
    private suspend fun fetchValidated(candidate: OnlineCoverCandidate, size: CoverSize, raw: File): OnlineCoverResult.Failed? {
        val url = OpenLibraryCovers.url(candidate.ref, size) ?: return OnlineCoverResult.Failed(OnlineCoverFailure.MALFORMED)
        val fetch = try { images.fetch(url, raw, OpenLibraryCovers.MAX_RAW_BYTES) } catch (e: CancellationException) { throw e
        } catch (_: ResponseTooLargeException) { return OnlineCoverResult.Failed(OnlineCoverFailure.TOO_LARGE)
        } catch (_: UrlNotAllowedException) { return OnlineCoverResult.Failed(OnlineCoverFailure.REDIRECT_BLOCKED)   // F-2: fail closed, nothing else is followed
        } catch (_: SocketTimeoutException) { return OnlineCoverResult.Failed(OnlineCoverFailure.TIMEOUT)
        } catch (e: IOException) { return OnlineCoverResult.Failed(if (e.message.orEmpty().contains("ENOSPC")) OnlineCoverFailure.STORAGE_FULL else OnlineCoverFailure.OFFLINE) }
        when (fetch.status) {
            200 -> Unit
            404 -> return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_FOUND)
            403, 429 -> {
                val wait = RetryAfter.parse(fetch.retryAfter, nowMs())
                pause(wait)
                return OnlineCoverResult.Failed(if (fetch.status == 429) OnlineCoverFailure.RATE_LIMITED else OnlineCoverFailure.ACCESS_BLOCKED, wait)
            }
            in 500..599 -> return OnlineCoverResult.Failed(OnlineCoverFailure.SERVER_FAILURE)
            else -> return OnlineCoverResult.Failed(OnlineCoverFailure.MALFORMED)
        }
        // The content decides, not the header or the URL.
        val sniffed = sniffImage(raw) ?: return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AN_IMAGE)
        val declared = fetch.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (declared != null && declared != "application/octet-stream") {
            if (!declared.startsWith("image/")) return OnlineCoverResult.Failed(OnlineCoverFailure.NOT_AN_IMAGE)
            if (declared.replace("image/jpg", "image/jpeg") != sniffed.mime) return OnlineCoverResult.Failed(OnlineCoverFailure.MIME_MISMATCH)
        }
        return null
    }

    private fun imageFailure(e: CoverExtractionFailure) = when (e.reason) {
        CoverFailureReason.UNDECODABLE_CONTENT, CoverFailureReason.NO_SUITABLE_ARTWORK -> OnlineCoverFailure.INVALID_IMAGE
        CoverFailureReason.UNSUPPORTED_REPRESENTATION -> OnlineCoverFailure.UNSUPPORTED_IMAGE
        CoverFailureReason.STORAGE_FULL -> OnlineCoverFailure.STORAGE_FULL
        else -> OnlineCoverFailure.FAILED
    }

    private fun fromProvider(kind: ProviderFailureKind) = when (kind) {
        ProviderFailureKind.OFFLINE -> OnlineCoverFailure.OFFLINE
        ProviderFailureKind.TIMEOUT -> OnlineCoverFailure.TIMEOUT
        ProviderFailureKind.RATE_LIMITED -> OnlineCoverFailure.RATE_LIMITED
        ProviderFailureKind.ACCESS_BLOCKED -> OnlineCoverFailure.ACCESS_BLOCKED
        ProviderFailureKind.SERVER_FAILURE -> OnlineCoverFailure.SERVER_FAILURE
        else -> OnlineCoverFailure.MALFORMED
    }

    companion object {
        const val DEFAULT_PAUSE_MS = OnlineCoverCooldown.DEFAULT_PAUSE_MS
        const val MAX_PAUSE_MS = OnlineCoverCooldown.MAX_PAUSE_MS
    }
}
