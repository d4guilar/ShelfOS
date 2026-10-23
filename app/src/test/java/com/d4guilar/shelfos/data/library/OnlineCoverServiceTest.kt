// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.network.ImageFetch
import com.d4guilar.shelfos.core.network.ResponseTooLargeException
import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.data.metadata.openlibrary.*
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.library.CoverOrigin
import com.d4guilar.shelfos.domain.library.CoverOriginKind
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

private val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1)
private val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52)

/** D1-C-PROD service behavior with fake provider, transport and persistence: no network, no Android. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineCoverServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val isbn = "9780140328721"
    private val metadata = OnlineMetadataSettings(MemoryBooleans())
    private val consent = OnlineCoverConsent(MemoryBooleans())
    private val auth = OnlineCoverAuthorization(metadata, consent)
    private val images = FakeImages()
    private var now = 1_000_000L
    private var free = 10L * 1024 * 1024 * 1024
    private var exists = true
    private var editionCalls = 0
    private var epoch = 0L
    private var query: MetadataQuery? = MetadataQuery(MediaCategory.BOOK, listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), title = "Matilda")
    private val commits = ArrayList<Triple<String, CoverOrigin, Long>>()
    private var commitResult: ((() -> Boolean) -> UserCoverOutcome)? = null
    private var onEditions: (() -> Unit)? = null
    private val item = LibraryItem("b1", "Matilda", category = MediaCategory.BOOK, sourceUri = "test:b1", format = PublicationFormat.EPUB, fileName = "Matilda.epub", byteSize = 1)

    private fun enableAll() { metadata.enableProvider(OpenLibrary.ID); consent.set(true) }
    private fun files() = OnlineCoverFiles(File(tmp.root, "previews"), File(tmp.root, "staging"))

    private fun service(budget: OnlineCoverBudget = OnlineCoverBudget(nowMs = { now }), files: OnlineCoverFiles = files(), cooldown: OnlineCoverCooldown = cooldown()) = OnlineCoverService(auth, files, images,
        { onEditions?.invoke(); editionCalls++; listOf(
            CoverEdition("OL1M", "Matilda", listOf(5L), listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), "Puffin", "1998", "en", true),
            CoverEdition("OL2M", "Matilda", listOf(6L), listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9780141322643")), "Penguin", "2001", "en", false)) },
        { query }, { if (exists) item else null }, { free }, { epoch },
        { id, file, origin, ep, guard ->
            commits += Triple(id, origin, ep)
            assertTrue("the staged file is present when committing", file.isFile)
            commitResult?.invoke(guard) ?: if (guard()) UserCoverOutcome.SAVED else UserCoverOutcome.NOT_PERMITTED
        },
        PilotImageNormalizer { raw, out -> out.writeBytes(raw.readBytes()); PilotImage("image/jpeg", "jpg", 400, 600, 400, 600, out.length()) },
        { f -> if (f.length() > 0) PilotImage("image/jpeg", f.extension, 400, 600, 400, 600, f.length()) else null }, budget, { now }, cooldown)

    private fun cooldown(store: LongStore = longs) = OnlineCoverCooldown(store, { now }, { now })
    private val longs = MemoryLongStore()
    private fun tick(ms: Long) { now += ms }

    private fun failed(r: OnlineCoverResult<*>) = (r as OnlineCoverResult.Failed).reason
    private fun <T> ok(r: OnlineCoverResult<T>) = (r as OnlineCoverResult.Ok<T>).value
    private fun leftovers() = tmp.root.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.toList()
    private suspend fun OnlineCoverService.discoverOk() = ok(discover(item)).candidates
    private val exactKey = "OL1M-id-5"

    // ---- authorization: D1-C-PROD scope + separate consent + metadata switches ----

    @Test fun `no consent means no request of any kind`() = runTest {
        val s = service()
        assertEquals(OnlineCoverAccess.BOTH_OFF, auth.access())
        assertEquals(OnlineCoverFailure.NOT_AUTHORIZED, failed(s.discover(item)))
        assertEquals(OnlineCoverFailure.STALE, failed(s.preview(item, exactKey)))
        assertEquals(OnlineCoverFailure.STALE, failed(s.select(item, exactKey)))
        assertTrue(images.requests.isEmpty()); assertEquals(0, editionCalls); assertTrue(commits.isEmpty())
    }

    @Test fun `the metadata switches alone never authorize artwork`() = runTest {
        metadata.enableProvider(OpenLibrary.ID)
        assertEquals(OnlineCoverAccess.COVERS_OFF, auth.access())
        assertFalse(auth.permits(OpenLibrary.ID, MediaCategory.BOOK))
        assertEquals(OnlineCoverFailure.NOT_AUTHORIZED, failed(service().discover(item)))
        assertEquals(0, editionCalls)
    }

    @Test fun `the cover consent alone never authorizes artwork`() = runTest {
        consent.set(true)
        assertEquals(OnlineCoverAccess.METADATA_OFF, auth.access())
        assertEquals(OnlineCoverFailure.NOT_AUTHORIZED, failed(service().discover(item)))
        metadata.setOnlineEnabled(true)   // the master alone is still not the provider switch
        assertEquals(OnlineCoverAccess.METADATA_OFF, auth.access())
        assertEquals(0, editionCalls)
    }

    @Test fun `the provider switch off blocks covers whatever the stored cover consent says`() = runTest {
        enableAll(); metadata.setProviderEnabled(OpenLibrary.ID, false)
        assertTrue(consent.enabled.value)
        assertEquals(OnlineCoverFailure.NOT_AUTHORIZED, failed(service().discover(item)))
        assertEquals(0, editionCalls)
    }

    @Test fun `the grant covers only Open Library Books`() = runTest {
        enableAll()
        assertTrue(auth.permits(OpenLibrary.ID, MediaCategory.BOOK))
        assertFalse(auth.permits("inventaire", MediaCategory.BOOK))
        assertFalse(auth.permits("googlebooks", MediaCategory.BOOK))
        val s = service()
        for (c in listOf(MediaCategory.COMIC, MediaCategory.MANGA, MediaCategory.DOCUMENT)) {
            assertFalse(auth.permits(OpenLibrary.ID, c))
            assertEquals(OnlineCoverFailure.NOT_AUTHORIZED, failed(s.discover(item.copy(category = c))))
        }
        assertEquals(0, editionCalls); assertTrue(images.requests.isEmpty())
    }

    @Test fun `D1-M and D1-C-DEV cannot authorize production artwork and D1-C-PROD is not a metadata grant`() {
        val d = OpenLibrary.descriptor()
        assertEquals(TermsStatus.CONDITIONAL, d.termsStatus)
        assertFalse("D1-M never authorizes covers", d.authorizes(MediaCategory.BOOK, MetadataCapability.COVERS))
        assertThrows(IllegalArgumentException::class.java) { ScopedGrant(OpenLibrary.ID, "D1-M", setOf(MediaCategory.BOOK), setOf(MetadataCapability.COVERS)) }
        assertThrows(IllegalArgumentException::class.java) { ScopedGrant(OpenLibrary.ID, ProductionCoverGrant.DECISION, setOf(MediaCategory.BOOK), setOf(MetadataCapability.COVERS)) }
        // The debug pilot's consent is a different object and means nothing to production.
        val pilot = CoverPilotAuthorization(MemoryBooleans(), debugBuild = true).also { it.enable() }
        assertFalse(auth.permits(OpenLibrary.ID, MediaCategory.BOOK))
        assertEquals("D1-C-PROD", ProductionCoverGrant.DECISION)
    }

    @Test fun `cover consent is off by default and persists`() {
        val store = MemoryBooleans()
        assertFalse(OnlineCoverConsent(store).enabled.value)
        OnlineCoverConsent(store).set(true)
        assertTrue(OnlineCoverConsent(store).enabled.value)
        OnlineCoverConsent(store).set(false)
        assertFalse(OnlineCoverConsent(store).enabled.value)
    }

    // ---- discovery ----

    @Test fun `discovery offers candidates and applies nothing`() = runTest {
        enableAll()
        val s = service()
        val found = s.discoverOk()
        assertEquals(listOf(exactKey, "OL2M-id-6"), found.map { it.key })
        assertEquals(CoverMatchKind.EXACT_EDITION, found[0].match)
        assertEquals("a different ISBN is a different edition, never presented as exact", CoverMatchKind.OTHER_EDITION, found[1].match)
        assertTrue(CoverWarning.DIFFERENT_ISBN in found[1].warnings)
        assertTrue("discovery alone requests no image and commits nothing", images.requests.isEmpty() && commits.isEmpty())
        assertEquals(1, editionCalls)
        assertEquals(found.map { it.key }, s.known(item.id).map { it.key })
    }

    @Test fun `a book without an ISBN is told so and costs no request`() = runTest {
        enableAll(); query = MetadataQuery(MediaCategory.BOOK, emptyList(), title = "Matilda")
        assertEquals(OnlineCoverFailure.NO_ISBN, failed(service().discover(item)))
        assertEquals(0, editionCalls)
    }

    @Test fun `consent withdrawn during discovery discards the result`() = runTest {
        enableAll()
        onEditions = { consent.set(false) }
        val s = service()
        assertEquals(OnlineCoverFailure.NOT_AUTHORIZED, failed(s.discover(item)))
        assertTrue("nothing is kept for a later screen", s.known(item.id).isEmpty())
    }

    @Test fun `a provider failure is categorized and a rate limit pauses everything`() = runTest {
        enableAll()
        var fail: ProviderException? = ProviderException(ProviderFailureKind.RATE_LIMITED, 120_000)
        val s = OnlineCoverService(auth, files(), images, { throw fail!! }, { query }, { item }, { free }, { 0 }, { _, _, _, _, _ -> UserCoverOutcome.SAVED }, nowMs = { now })
        val first = s.discover(item) as OnlineCoverResult.Failed
        assertEquals(OnlineCoverFailure.RATE_LIMITED, first.reason); assertEquals(120_000L, first.retryAfterMs)
        fail = ProviderException(ProviderFailureKind.OFFLINE)
        assertEquals("paused: no second call", OnlineCoverFailure.RATE_LIMITED, failed(s.discover(item)))
        now += 130_000
        assertEquals(OnlineCoverFailure.OFFLINE, failed(s.discover(item)))
    }

    // ---- preview ----

    @Test fun `a preview is only fetched when asked and is reused afterwards`() = runTest {
        enableAll()
        val s = service(); s.discoverOk()
        val p = ok(s.preview(item, exactKey))
        assertFalse(p.fromCache); assertEquals(1, images.requests.size)
        assertTrue(images.requests.single().endsWith("/b/id/5-M.jpg?default=false"))
        val again = ok(s.preview(item, exactKey))
        assertTrue(again.fromCache); assertEquals("a validated preview is reused with no request", 1, images.requests.size)
        assertTrue(commits.isEmpty()); assertTrue(leftovers().isEmpty())
    }

    @Test fun `a candidate the service did not produce cannot be previewed or selected`() = runTest {
        enableAll()
        val s = service(); s.discoverOk()
        for (key in listOf("OL9M-id-77", "../etc", "OL1M-isbn-9780140328721")) {
            assertEquals(OnlineCoverFailure.STALE, failed(s.preview(item, key)))
            assertEquals(OnlineCoverFailure.STALE, failed(s.select(item, key)))
        }
        assertTrue(images.requests.isEmpty() && commits.isEmpty())
    }

    @Test fun `a stale or foreign candidate cannot consume the request budget`() = runTest {
        enableAll()
        val budget = OnlineCoverBudget(maxDiscoveries = 8, maxImages = 1, nowMs = { now })
        val s = service(budget); s.discoverOk()
        repeat(5) { assertEquals(OnlineCoverFailure.STALE, failed(s.preview(item, "OL9M-id-1"))) }
        ok(s.preview(item, exactKey))   // the one allowed image is still available
        assertEquals(OnlineCoverFailure.BUDGET_EXHAUSTED, failed(s.preview(item, "OL2M-id-6")))
        assertEquals(1, images.requests.size)
    }

    // ---- select ----

    @Test fun `use this cover downloads the large image and commits it as a provider-selected USER cover`() = runTest {
        enableAll(); epoch = 7
        val s = service(); s.discoverOk()
        ok(s.select(item, exactKey))
        assertTrue(images.requests.single().endsWith("/b/id/5-L.jpg?default=false"))
        val (id, origin, ep) = commits.single()
        assertEquals("b1", id); assertEquals(7L, ep)
        assertEquals(CoverOriginKind.PROVIDER_SELECTED, origin.kind)
        assertEquals(listOf("openlibrary", "OL1M", "id", "5"), listOf(origin.providerId, origin.editionRef, origin.refKind, origin.refValue))
        assertEquals(now, origin.selectedAtMs)
        assertTrue("the staged download is removed", leftovers().isEmpty())
    }

    @Test fun `consent withdrawn while the image downloads commits nothing`() = runTest {
        enableAll()
        images.respond = { _, f -> consent.set(false); f.writeBytes(JPEG_BYTES); ImageFetch(200, "image/jpeg", null, JPEG_BYTES.size.toLong()) }
        val s = service(); s.discoverOk()
        assertEquals(OnlineCoverFailure.NOT_AUTHORIZED, failed(s.select(item, exactKey)))
        assertTrue("the guard refused the commit", commits.size == 1 && commitResult == null)   // commit was asked; its guard said no
        assertTrue(leftovers().isEmpty())
    }

    @Test fun `provider switch withdrawn between download and commit is refused by the guard`() = runTest {
        enableAll()
        commitResult = { guard -> metadata.setProviderEnabled(OpenLibrary.ID, false); if (guard()) UserCoverOutcome.SAVED else UserCoverOutcome.NOT_PERMITTED }
        val s = service(); s.discoverOk()
        assertEquals(OnlineCoverFailure.NOT_AUTHORIZED, failed(s.select(item, exactKey)))
    }

    @Test fun `a newer cover choice supersedes this selection`() = runTest {
        enableAll()
        commitResult = { UserCoverOutcome.SUPERSEDED }
        val s = service(); s.discoverOk()
        assertEquals(OnlineCoverFailure.SUPERSEDED, failed(s.select(item, exactKey)))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun `a removed book receives nothing`() = runTest {
        enableAll()
        val s = service(); s.discoverOk()
        exists = false
        assertEquals(OnlineCoverFailure.ITEM_GONE, failed(s.select(item, exactKey)))
        assertTrue(images.requests.isEmpty() && commits.isEmpty())
    }

    @Test fun `a second press while a transfer runs starts nothing`() = runTest {
        enableAll()
        val gate = CompletableDeferred<Unit>()
        images.respond = { _, f -> gate.await(); f.writeBytes(JPEG_BYTES); ImageFetch(200, "image/jpeg", null, JPEG_BYTES.size.toLong()) }
        val s = service(); s.discoverOk()
        val running = launch { s.preview(item, exactKey) }
        advanceUntilIdle()
        assertEquals(OnlineCoverFailure.BUSY, failed(s.select(item, exactKey)))
        assertEquals(OnlineCoverFailure.BUSY, failed(s.preview(item, "OL2M-id-6")))
        gate.complete(Unit); running.join()
        assertEquals(1, images.requests.size)
    }

    @Test fun `cancelling a transfer removes its temporary files`() = runTest {
        enableAll()
        val gate = CompletableDeferred<Unit>()
        images.respond = { _, f -> f.writeBytes(JPEG_BYTES); gate.await(); ImageFetch(200, "image/jpeg", null, JPEG_BYTES.size.toLong()) }
        val s = service(); s.discoverOk()
        val running = launch { s.select(item, exactKey) }
        advanceUntilIdle()
        running.cancel(); running.join()
        assertTrue(leftovers().isEmpty()); assertTrue(commits.isEmpty())
        images.respond = { _, f -> f.writeBytes(JPEG_BYTES); ImageFetch(200, "image/jpeg", null, JPEG_BYTES.size.toLong()) }
        ok(s.select(item, exactKey))   // the slot was released
    }

    // ---- transport outcomes ----

    private suspend fun selectWith(response: suspend (String, File) -> ImageFetch): OnlineCoverResult<Unit> {
        enableAll(); images.respond = response
        val s = service(); s.discoverOk()
        return s.select(item, exactKey)
    }

    @Test fun `a redirect to an unlisted host is blocked and nothing else is followed`() = runTest {
        assertEquals(OnlineCoverFailure.REDIRECT_BLOCKED, failed(selectWith { _, _ -> throw UrlNotAllowedException("host not allowed") }))
        assertEquals("exactly one attempt, no retry of the blocked destination", 1, images.requests.size)
        assertTrue(commits.isEmpty() && leftovers().isEmpty())
    }

    @Test fun `oversized timeout offline and server failures are classified`() = runTest {
        assertEquals(OnlineCoverFailure.TOO_LARGE, failed(selectWith { _, _ -> throw ResponseTooLargeException(1) }))
        assertEquals(OnlineCoverFailure.TIMEOUT, failed(selectWith { _, _ -> throw SocketTimeoutException() }))
        assertEquals(OnlineCoverFailure.OFFLINE, failed(selectWith { _, _ -> throw IOException("unreachable") }))
        assertEquals(OnlineCoverFailure.STORAGE_FULL, failed(selectWith { _, _ -> throw IOException("ENOSPC") }))
        assertEquals(OnlineCoverFailure.SERVER_FAILURE, failed(selectWith { _, _ -> ImageFetch(503, null, null, 0) }))
        assertEquals(OnlineCoverFailure.NOT_FOUND, failed(selectWith { _, _ -> ImageFetch(404, "text/html", null, 0) }))
        assertTrue(commits.isEmpty())
    }

    @Test fun `wrong content type or bytes are rejected before anything is stored`() = runTest {
        assertEquals(OnlineCoverFailure.NOT_AN_IMAGE, failed(selectWith { _, f -> f.writeBytes("<html>no</html>".toByteArray(Charsets.ISO_8859_1) + ByteArray(20)); ImageFetch(200, "image/jpeg", null, 35) }))
        assertEquals(OnlineCoverFailure.NOT_AN_IMAGE, failed(selectWith { _, f -> f.writeBytes(JPEG_BYTES); ImageFetch(200, "text/html", null, JPEG_BYTES.size.toLong()) }))
        assertEquals(OnlineCoverFailure.MIME_MISMATCH, failed(selectWith { _, f -> f.writeBytes(PNG_BYTES); ImageFetch(200, "image/jpeg", null, PNG_BYTES.size.toLong()) }))
        assertTrue(commits.isEmpty() && leftovers().isEmpty())
    }

    @Test fun `a 429 with Retry-After pauses all further requests until it passes`() = runTest {
        enableAll(); images.respond = { _, _ -> ImageFetch(429, null, "90", 0) }
        val s = service(); s.discoverOk()
        val r = s.select(item, exactKey) as OnlineCoverResult.Failed
        assertEquals(OnlineCoverFailure.RATE_LIMITED, r.reason); assertEquals(90_000L, r.retryAfterMs)
        assertEquals(OnlineCoverFailure.RATE_LIMITED, failed(s.preview(item, exactKey)))
        assertEquals(OnlineCoverFailure.RATE_LIMITED, failed(s.discover(item)))
        assertEquals("no retry storm: one request in total", 1, images.requests.size)
        now += 95_000; images.respond = { _, f -> f.writeBytes(JPEG_BYTES); ImageFetch(200, "image/jpeg", null, JPEG_BYTES.size.toLong()) }
        ok(s.select(item, exactKey))
    }

    @Test fun `a 403 pauses like a rate limit`() = runTest {
        enableAll(); images.respond = { _, _ -> ImageFetch(403, null, null, 0) }
        val s = service(); s.discoverOk()
        assertEquals(OnlineCoverFailure.ACCESS_BLOCKED, failed(s.select(item, exactKey)))
        assertEquals(OnlineCoverFailure.RATE_LIMITED, failed(s.preview(item, exactKey)))
        assertEquals(1, images.requests.size)
    }

    @Test fun `low storage is refused before any request`() = runTest {
        enableAll(); free = 1024
        val s = service(); s.discoverOk()
        assertEquals(OnlineCoverFailure.STORAGE_FULL, failed(s.select(item, exactKey)))
        assertTrue(images.requests.isEmpty())
    }

    // ---- the persisted provider cooldown (D31) ----

    private val okImage: suspend (String, File) -> ImageFetch = { _, f -> f.writeBytes(JPEG_BYTES); ImageFetch(200, "image/jpeg", null, JPEG_BYTES.size.toLong()) }

    @Test fun `a 429 cooldown survives a restart and a new service cannot request before it expires`() = runTest {
        enableAll(); images.respond = { _, _ -> ImageFetch(429, null, "90", 0) }
        val s = service(); s.discoverOk()
        assertEquals(OnlineCoverFailure.RATE_LIMITED, failed(s.select(item, exactKey)))
        assertEquals(now + 90_000L, longs.get(OnlineCoverCooldown.KEY, 0L))
        val requests = images.requests.size; val calls = editionCalls
        tick(30_000)   // the process died; 30 s later a new one starts with the same store
        val restarted = service(cooldown = cooldown())
        val r = restarted.discover(item) as OnlineCoverResult.Failed
        assertEquals(OnlineCoverFailure.RATE_LIMITED, r.reason); assertEquals(60_000L, r.retryAfterMs)
        assertEquals(calls, editionCalls); assertEquals(requests, images.requests.size)
    }

    @Test fun `an expired persisted deadline permits a request and is cleared`() = runTest {
        enableAll(); longs.put(OnlineCoverCooldown.KEY, now - 1)
        val s = service(cooldown = cooldown())
        assertEquals(0L, longs.get(OnlineCoverCooldown.KEY, -1L))
        assertEquals(2, s.discoverOk().size); assertEquals(1, editionCalls)
    }

    @Test fun `a clock moved backwards cannot restore a pause longer than the maximum`() = runTest {
        enableAll(); longs.put(OnlineCoverCooldown.KEY, now + 10 * 3_600_000L)
        val s = service(cooldown = cooldown())
        val r = s.discover(item) as OnlineCoverResult.Failed
        assertEquals(OnlineCoverCooldown.MAX_PAUSE_MS, r.retryAfterMs)
        tick(OnlineCoverCooldown.MAX_PAUSE_MS + 1)
        assertEquals(2, s.discoverOk().size)
    }

    @Test fun `switching consent off and on does not clear the cooldown`() = runTest {
        enableAll(); images.respond = { _, _ -> ImageFetch(429, null, "600", 0) }
        val s = service(); s.discoverOk(); s.select(item, exactKey)
        consent.set(false); consent.set(true); metadata.setProviderEnabled(OpenLibrary.ID, false); metadata.setProviderEnabled(OpenLibrary.ID, true)
        val requests = images.requests.size; val calls = editionCalls
        assertEquals(OnlineCoverFailure.RATE_LIMITED, failed(service(cooldown = cooldown()).discover(item)))
        assertEquals(OnlineCoverFailure.RATE_LIMITED, failed(s.preview(item, exactKey)))
        assertEquals(calls, editionCalls); assertEquals(requests, images.requests.size)
    }

    @Test fun `a 403 without Retry-After persists the default pause`() = runTest {
        enableAll(); images.respond = { _, _ -> ImageFetch(403, null, null, 0) }
        val s = service(); s.discoverOk()
        assertEquals(OnlineCoverFailure.ACCESS_BLOCKED, failed(s.select(item, exactKey)))
        assertEquals(now + OnlineCoverCooldown.DEFAULT_PAUSE_MS, longs.get(OnlineCoverCooldown.KEY, 0L))
        assertEquals(OnlineCoverFailure.RATE_LIMITED, failed(service(cooldown = cooldown()).discover(item)))
    }

    @Test fun `a discovery rate limit or block persists its pause`() = runTest {
        enableAll()
        for ((kind, wait, expected) in listOf(Triple(ProviderFailureKind.RATE_LIMITED, 120_000L, 120_000L), Triple(ProviderFailureKind.ACCESS_BLOCKED, null, OnlineCoverCooldown.DEFAULT_PAUSE_MS))) {
            longs.put(OnlineCoverCooldown.KEY, 0L)
            val s = OnlineCoverService(auth, files(), images, { throw ProviderException(kind, wait) }, { query }, { item }, { free }, { 0 }, { _, _, _, _, _ -> UserCoverOutcome.SAVED }, nowMs = { now }, cooldown = cooldown())
            assertEquals(fromKind(kind), failed(s.discover(item)))
            assertEquals(now + expected, longs.get(OnlineCoverCooldown.KEY, 0L))
        }
    }
    private fun fromKind(k: ProviderFailureKind) = if (k == ProviderFailureKind.RATE_LIMITED) OnlineCoverFailure.RATE_LIMITED else OnlineCoverFailure.ACCESS_BLOCKED

    @Test fun `a shorter pause never reduces a longer one`() {
        val c = cooldown()
        c.pause(1_800_000L); val stored = longs.get(OnlineCoverCooldown.KEY, 0L)
        c.pause(5_000L); c.pause(null)
        assertEquals(1_800_000L, c.remainingMs()); assertEquals(stored, longs.get(OnlineCoverCooldown.KEY, 0L))
        tick(1_000_000L); c.pause(60_000L)   // 800 s left, so 60 s changes nothing
        assertEquals(800_000L, c.remainingMs())
        c.pause(OnlineCoverCooldown.MAX_PAUSE_MS)   // a longer one extends
        assertEquals(OnlineCoverCooldown.MAX_PAUSE_MS, c.remainingMs())
    }

    @Test fun `expiry starts nothing by itself`() = runTest {
        enableAll(); images.respond = { _, _ -> ImageFetch(429, null, "60", 0) }
        val s = service(); s.discoverOk(); s.select(item, exactKey)
        val requests = images.requests.size; val calls = editionCalls
        tick(OnlineCoverCooldown.MAX_PAUSE_MS * 2); testScheduler.advanceUntilIdle()
        assertEquals(requests, images.requests.size); assertEquals(calls, editionCalls); assertTrue(commits.isEmpty())
        images.respond = okImage
        ok(s.select(item, exactKey))   // only a person's press resumes
    }

    @Test fun `the files manager removes abandoned temporaries at construction and keeps real files`() {
        val staging = File(tmp.root, "staging").apply { mkdirs() }; val previews = File(tmp.root, "previews/b1").apply { mkdirs() }
        val strayStage = File(staging, "aaaa.tmp").apply { writeText("x") }; val strayPreview = File(previews, "bbbb.tmp").apply { writeText("x") }
        val keep = File(previews, "pv-OL1M-id-5.jpg").apply { writeBytes(JPEG_BYTES) }
        val covers = File(tmp.root, "covers").apply { mkdirs() }; val userCover = File(covers, "b1.jpg").apply { writeBytes(JPEG_BYTES) }
        files()
        assertFalse(strayStage.exists()); assertFalse(strayPreview.exists())
        assertTrue(keep.exists()); assertTrue(userCover.exists())
    }

    // ---- the finite budget ----

    @Test fun `the discovery and image budgets are finite refill over time and a denied request costs nothing`() {
        val budget = OnlineCoverBudget(maxDiscoveries = 2, maxImages = 3, windowMs = 1_000, nowMs = { now })
        assertTrue(budget.takeDiscovery()); assertTrue(budget.takeDiscovery()); assertFalse(budget.takeDiscovery())
        repeat(3) { assertTrue(budget.takeImage()) }; assertFalse(budget.takeImage()); assertFalse(budget.takeImage())
        now += 1_001
        assertTrue(budget.takeDiscovery()); assertTrue(budget.takeImage())
        val defaults = OnlineCoverBudget(nowMs = { now })
        repeat(8) { assertTrue(defaults.takeDiscovery()) }; assertFalse("production default: 8 discoveries per hour", defaults.takeDiscovery())
        repeat(30) { assertTrue(defaults.takeImage()) }; assertFalse("production default: 30 image transfers per hour", defaults.takeImage())
    }

    @Test fun `an exhausted discovery budget stops before the provider is called`() = runTest {
        enableAll()
        val s = service(OnlineCoverBudget(maxDiscoveries = 1, nowMs = { now }))
        s.discoverOk()
        assertEquals(OnlineCoverFailure.BUDGET_EXHAUSTED, failed(s.discover(item)))
        assertEquals(1, editionCalls)
    }
}
