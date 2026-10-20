// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.network.ImageTransport
import com.d4guilar.shelfos.core.network.ResponseTooLargeException
import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.core.reader.CoverImageWriter
import com.d4guilar.shelfos.data.metadata.RetryAfter
import com.d4guilar.shelfos.data.metadata.openlibrary.*
import com.d4guilar.shelfos.data.preferences.BooleanStore
import com.d4guilar.shelfos.domain.library.CoverFailureReason
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The Cover Lab's own consent (Phase 4.5C.2B, D1-C-DEV). Separate from online-metadata consent, which never authorizes image requests.
 * OFF by default; persisted in its own preference file; and meaningful only in a debuggable build ([debugBuild]), so a release build can
 * never be authorized even if the preference were set. Only Open Library and only Books.
 */
class CoverPilotAuthorization(private val store: BooleanStore, private val debugBuild: Boolean) {
    private val flag = MutableStateFlow(debugBuild && store.get(KEY, false))
    val enabled: StateFlow<Boolean> = flag.asStateFlow()

    fun permits(providerId: String, category: MediaCategory): Boolean = debugBuild && flag.value && providerId == OpenLibrary.ID && category == MediaCategory.BOOK

    fun enable() { if (debugBuild) { store.put(KEY, true); flag.value = true } }
    fun disable() { store.put(KEY, false); flag.value = false }

    companion object { const val KEY = "cover_pilot_enabled" }
}

enum class PilotFailure {
    NOT_AUTHORIZED, NO_ISBN, NOT_FOUND, OFFLINE, TIMEOUT, RATE_LIMITED, ACCESS_BLOCKED, SERVER_FAILURE, MALFORMED, REDIRECT_BLOCKED, TOO_LARGE,
    NOT_AN_IMAGE, MIME_MISMATCH, UNSUPPORTED_IMAGE, INVALID_IMAGE, STORAGE_FULL, BUDGET_EXHAUSTED, BUSY, ITEM_GONE, FAILED,
}

sealed interface PilotResult<out T> {
    data class Ok<T>(val value: T) : PilotResult<T>
    data class Failed(val reason: PilotFailure, val retryAfterMs: Long? = null) : PilotResult<Nothing>
}

/** A preview or pick on disk, with the dimensions that were actually decoded. */
data class PilotArtwork(val file: File, val image: PilotImage, val fromCache: Boolean)

/** What discovery found; [candidates] are never applied to anything. */
data class PilotDiscovery(val candidates: List<OnlineCoverCandidate>, val isbn13: String)

/** Turns a downloaded raw file into a bounded, validated, upright image at [output]. The default reuses the production cover writer. */
fun interface PilotImageNormalizer {
    fun normalize(raw: File, output: File): PilotImage
}

/** The same sampled-decode, bounded-size, EXIF-aware ladder every ShelfOS cover goes through ([CoverImageWriter]); throws [CoverExtractionFailure]. */
object CoverWriterNormalizer : PilotImageNormalizer {
    override fun normalize(raw: File, output: File): PilotImage {
        val stored = CoverImageWriter.store({ FileInputStream(raw) }, output, CoverImageWriter.exifOrientation(raw))
        return PilotImage(stored.mimeType, stored.extension, stored.storedWidth, stored.storedHeight, stored.sourceWidth, stored.sourceHeight, stored.bytes)
    }
}

/** Header-only dimensions of a stored image (never a full decode). */
fun inspectImageBounds(f: File): PilotImage? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.path, bounds)
    if (bounds.outWidth > 0 && bounds.outHeight > 0) PilotImage(bounds.outMimeType ?: "image/jpeg", f.extension, bounds.outWidth, bounds.outHeight, bounds.outWidth, bounds.outHeight, f.length()) else null
} catch (_: Throwable) { null }

/** What the bytes are, from their magic numbers alone; independent of any header or file name. */
enum class SniffedImage(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp") }

fun sniffImage(file: File): SniffedImage? {
    val head = ByteArray(12)
    val n = try { file.inputStream().use { it.read(head) } } catch (_: IOException) { return null }
    if (n < 12) return null
    fun at(i: Int, vararg b: Int) = b.indices.all { head[i + it] == b[it].toByte() }
    return when {
        at(0, 0xFF, 0xD8, 0xFF) -> SniffedImage.JPEG
        at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> SniffedImage.PNG
        at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> SniffedImage.WEBP
        else -> null
    }
}

/**
 * Per-process request budget for the pilot (a ceiling on top of the pacer's 2 s spacing): discovery calls and image transfers. A human
 * action starts each one; nothing refills it, so a stuck loop cannot become a crawl.
 */
class PilotBudget(private val maxDiscoveries: Int = 6, private val maxImages: Int = 24) {
    private var discoveries = 0
    private var images = 0
    @Synchronized fun takeDiscovery(): Boolean = if (discoveries < maxDiscoveries) { discoveries++; true } else false
    @Synchronized fun takeImage(): Boolean = if (images < maxImages) { images++; true } else false
}

/**
 * The debug-only online-cover pilot pipeline (Phase 4.5C.2B): provider candidate → edition verification → cover URL → bounded download →
 * image validation → private storage. It is reachable only from the debug source set, only for Books, only after [authorization], one transfer at a
 * time ([slot]), with no automatic retry; a 403/429 pauses every further request until `Retry-After` (default [DEFAULT_PAUSE_MS]).
 * It never touches `publication_cover`, `CoverSlot.USER`/`ONLINE`, the production cover precedence or the metadata store.
 */
class CoverPilotService(
    private val authorization: CoverPilotAuthorization,
    private val store: CoverPilotStore,
    private val images: ImageTransport,
    /** Reads an edition's cover records for an ISBN-13 (production: [OpenLibraryProvider.coverEditions]). */
    private val editions: suspend (String) -> List<CoverEdition>,
    /** The matching query for an item (production: the shared `metadataQuery`), or null if the item is gone. */
    private val queryFor: suspend (LibraryItem) -> MetadataQuery?,
    private val itemExists: suspend (String) -> Boolean,
    private val freeBytes: () -> Long,
    private val normalizer: PilotImageNormalizer = CoverWriterNormalizer,
    /** Decoded dimensions of a stored file (header only), or null if it is not a readable image. */
    private val inspect: (File) -> PilotImage? = ::inspectImageBounds,
    private val budget: PilotBudget = PilotBudget(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val slot = Mutex()
    @Volatile private var pausedUntil = 0L

    fun selection(itemId: String): PilotSelection? = store.selection(itemId)
    fun cachedPreview(itemId: String, candidate: OnlineCoverCandidate): File? = store.previewFile(itemId, candidate.key)
    fun file(selection: PilotSelection): File = store.file(selection)
    fun removeSelection(itemId: String) = store.remove(itemId)

    private fun authorized(item: LibraryItem) = item.category == MediaCategory.BOOK && authorization.permits(OpenLibrary.ID, item.category)

    private fun pauseFailure(): PilotResult.Failed? = (pausedUntil - nowMs()).takeIf { it > 0 }?.let { PilotResult.Failed(PilotFailure.RATE_LIMITED, it) }

    suspend fun discover(item: LibraryItem): PilotResult<PilotDiscovery> {
        if (!authorized(item)) return PilotResult.Failed(PilotFailure.NOT_AUTHORIZED)
        val query = queryFor(item) ?: return PilotResult.Failed(PilotFailure.ITEM_GONE)
        val isbn = query.identifiers.firstNotNullOfOrNull { it.isbn13 }?: return PilotResult.Failed(PilotFailure.NO_ISBN)
        pauseFailure()?.let { return it }
        if (!slot.tryLock()) return PilotResult.Failed(PilotFailure.BUSY)
        try {
            if (!authorized(item)) return PilotResult.Failed(PilotFailure.NOT_AUTHORIZED)
            if (!budget.takeDiscovery()) return PilotResult.Failed(PilotFailure.BUDGET_EXHAUSTED)
            val found = try { editions(isbn) } catch (e: ProviderException) {
                if (e.kind == ProviderFailureKind.RATE_LIMITED || e.kind == ProviderFailureKind.ACCESS_BLOCKED) pause(e.retryAfterMs)
                return PilotResult.Failed(fromProvider(e.kind), e.retryAfterMs)
            }
            if (!itemExists(item.id)) return PilotResult.Failed(PilotFailure.ITEM_GONE)
            return PilotResult.Ok(PilotDiscovery(OpenLibraryCoverCandidates.build(query, isbn, found), isbn))
        } finally { slot.unlock() }
    }

    /** A small (M) look at one candidate. A preview already on disk is reused without any request. */
    suspend fun preview(item: LibraryItem, candidate: OnlineCoverCandidate): PilotResult<PilotArtwork> {
        if (!authorized(item)) return PilotResult.Failed(PilotFailure.NOT_AUTHORIZED)
        store.previewFile(item.id, candidate.key)?.let { f -> readCached(f)?.let { return PilotResult.Ok(PilotArtwork(f, it, true)) } }
        return transfer(item, candidate, CoverSize.MEDIUM, preview = true)
    }

    /**
     * Downloads the large (L) artwork of [candidate] and makes it the Book's pilot pick. A candidate that is already the pick is not requested
     * again. The previous pick is replaced only after the new image validated and the manifest is written.
     */
    suspend fun download(item: LibraryItem, candidate: OnlineCoverCandidate): PilotResult<PilotArtwork> {
        if (!authorized(item)) return PilotResult.Failed(PilotFailure.NOT_AUTHORIZED)
        store.selection(item.id)?.let { s -> if (s.candidateKey == candidate.key && s.size == CoverSize.LARGE.letter)
            return PilotResult.Ok(PilotArtwork(store.file(s), PilotImage(s.mime, s.fileName.substringAfterLast('.'), s.width, s.height, s.width, s.height, s.bytes), true)) }
        return transfer(item, candidate, CoverSize.LARGE, preview = false)
    }

    private fun readCached(f: File): PilotImage? = inspect(f)

    private suspend fun transfer(item: LibraryItem, candidate: OnlineCoverCandidate, size: CoverSize, preview: Boolean): PilotResult<PilotArtwork> {
        val url = OpenLibraryCovers.url(candidate.ref, size) ?: return PilotResult.Failed(PilotFailure.MALFORMED)
        pauseFailure()?.let { return it }
        if (!slot.tryLock()) return PilotResult.Failed(PilotFailure.BUSY)   // a second press while a transfer runs starts nothing
        var raw: File? = null
        var out: File? = null
        try {
            if (!authorized(item)) return PilotResult.Failed(PilotFailure.NOT_AUTHORIZED)
            if (!CoverStoragePolicy.admits(freeBytes(), OpenLibraryCovers.MAX_RAW_BYTES.toLong())) return PilotResult.Failed(PilotFailure.STORAGE_FULL)
            if (!budget.takeImage()) return PilotResult.Failed(PilotFailure.BUDGET_EXHAUSTED)
            val tmpRaw = (if (preview) store.newPreviewTemporary(item.id) else store.newTemporary(item.id)).also { raw = it }
            val tmpOut = (if (preview) store.newPreviewTemporary(item.id) else store.newTemporary(item.id)).also { out = it }
            val fetch = try { images.fetch(url, tmpRaw, OpenLibraryCovers.MAX_RAW_BYTES) } catch (e: CancellationException) { throw e
            } catch (_: ResponseTooLargeException) { return PilotResult.Failed(PilotFailure.TOO_LARGE)
            } catch (_: UrlNotAllowedException) { return PilotResult.Failed(PilotFailure.REDIRECT_BLOCKED)
            } catch (_: SocketTimeoutException) { return PilotResult.Failed(PilotFailure.TIMEOUT)
            } catch (e: IOException) { return PilotResult.Failed(if (e.message.orEmpty().contains("ENOSPC")) PilotFailure.STORAGE_FULL else PilotFailure.OFFLINE) }
            when (fetch.status) {
                200 -> Unit
                404 -> return PilotResult.Failed(PilotFailure.NOT_FOUND)
                403, 429 -> {
                    val wait = RetryAfter.parse(fetch.retryAfter, nowMs())
                    pause(wait)
                    return PilotResult.Failed(if (fetch.status == 429) PilotFailure.RATE_LIMITED else PilotFailure.ACCESS_BLOCKED, wait)
                }
                in 500..599 -> return PilotResult.Failed(PilotFailure.SERVER_FAILURE)
                else -> return PilotResult.Failed(PilotFailure.MALFORMED)
            }
            // The content decides, not the header or the URL: unknown bytes are not an image, and a header that names another format is a mismatch.
            val sniffed = sniffImage(tmpRaw) ?: return PilotResult.Failed(PilotFailure.NOT_AN_IMAGE)
            val declared = fetch.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (declared != null && declared != "application/octet-stream") {
                if (!declared.startsWith("image/")) return PilotResult.Failed(PilotFailure.NOT_AN_IMAGE)
                if (declared.replace("image/jpg", "image/jpeg") != sniffed.mime) return PilotResult.Failed(PilotFailure.MIME_MISMATCH)
            }
            val image = try { normalizer.normalize(tmpRaw, tmpOut) } catch (e: CancellationException) { throw e
            } catch (e: CoverExtractionFailure) { return PilotResult.Failed(when (e.reason) {
                CoverFailureReason.UNDECODABLE_CONTENT, CoverFailureReason.NO_SUITABLE_ARTWORK -> PilotFailure.INVALID_IMAGE
                CoverFailureReason.UNSUPPORTED_REPRESENTATION -> PilotFailure.UNSUPPORTED_IMAGE
                CoverFailureReason.STORAGE_FULL -> PilotFailure.STORAGE_FULL
                else -> PilotFailure.FAILED })
            } catch (_: OutOfMemoryError) { return PilotResult.Failed(PilotFailure.UNSUPPORTED_IMAGE) }
            if (image.width <= 0 || image.height <= 0) return PilotResult.Failed(PilotFailure.INVALID_IMAGE)
            currentCoroutineContext().ensureActive()
            if (!itemExists(item.id)) return PilotResult.Failed(PilotFailure.ITEM_GONE)
            // Past this point the result is published: a cancelled dialog cannot leave a half-committed pick.
            return withContext(NonCancellable) {
                if (preview) {
                    val f = store.commitPreview(item.id, tmpOut, candidate.key, image.extension)
                    PilotResult.Ok(PilotArtwork(f, image, false))
                } else {
                    val s = store.commit(item.id, tmpOut, image, candidate.key, candidate.providerId, candidate.editionRef, candidate.ref.key, candidate.ref.value, size.letter, candidate.match.name)
                    PilotResult.Ok(PilotArtwork(store.file(s), image, false))
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: IOException) { return PilotResult.Failed(PilotFailure.FAILED)
        } finally {
            withContext(NonCancellable) { raw?.delete(); out?.delete(); slot.unlock() }
        }
    }

    private fun pause(retryAfterMs: Long?) { pausedUntil = nowMs() + (retryAfterMs ?: DEFAULT_PAUSE_MS).coerceIn(1_000L, MAX_PAUSE_MS) }

    private fun fromProvider(kind: ProviderFailureKind) = when (kind) {
        ProviderFailureKind.OFFLINE -> PilotFailure.OFFLINE
        ProviderFailureKind.TIMEOUT -> PilotFailure.TIMEOUT
        ProviderFailureKind.RATE_LIMITED -> PilotFailure.RATE_LIMITED
        ProviderFailureKind.ACCESS_BLOCKED -> PilotFailure.ACCESS_BLOCKED
        ProviderFailureKind.SERVER_FAILURE -> PilotFailure.SERVER_FAILURE
        else -> PilotFailure.MALFORMED
    }

    companion object {
        const val DEFAULT_PAUSE_MS = 5 * 60_000L
        const val MAX_PAUSE_MS = 60 * 60_000L
    }
}
