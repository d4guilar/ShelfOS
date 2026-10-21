// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.network.ImageFetch
import com.d4guilar.shelfos.core.network.ImageTransport
import com.d4guilar.shelfos.core.network.ResponseTooLargeException
import com.d4guilar.shelfos.core.network.UrlNotAllowedException
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.data.metadata.ProviderPolicyEngine
import com.d4guilar.shelfos.data.metadata.ProviderRegistry
import com.d4guilar.shelfos.data.metadata.ProviderSettings
import com.d4guilar.shelfos.data.metadata.RegisteredProvider
import com.d4guilar.shelfos.data.metadata.openlibrary.*
import com.d4guilar.shelfos.data.preferences.BooleanStore
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.library.CoverFailureReason
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

private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1)
private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52)
private val WEBP = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
private val HTML = "<html><body>Not found</body></html>".toByteArray()

class MemoryBooleans : BooleanStore {
    val map = HashMap<String, Boolean>()
    override fun get(key: String, default: Boolean) = map[key] ?: default
    override fun put(key: String, value: Boolean) { map[key] = value }
}

/** Never touches a network: serves scripted responses and records every request. */
class FakeImages : ImageTransport {
    val requests = ArrayList<String>()
    var respond: suspend (String, File) -> ImageFetch = { _, f -> f.writeBytes(JPEG); ImageFetch(200, "image/jpeg", null, JPEG.size.toLong()) }
    override suspend fun fetch(url: String, destination: File, maxBytes: Int): ImageFetch { requests += url; return respond(url, destination) }
}

class CoverPilotServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val isbn = "9780140328721"
    private val auth = CoverPilotAuthorization(MemoryBooleans(), debugBuild = true)
    private val images = FakeImages()
    private var now = 1_000_000L
    private var free = 10L * 1024 * 1024 * 1024
    private var exists = true
    private var onExists: (() -> Unit)? = null
    private var editionCalls = 0
    private var width = 400
    private var height = 600
    private var query: MetadataQuery? = MetadataQuery(MediaCategory.BOOK, listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), title = "Matilda")
    private lateinit var store: CoverPilotStore
    private val item = LibraryItem("b1", "Matilda", category = MediaCategory.BOOK, sourceUri = "test:b1", format = PublicationFormat.EPUB, fileName = "Matilda.epub", byteSize = 1)

    private fun root() = File(tmp.root, "files/cover_lab")
    private fun previews() = File(tmp.root, "cache/cover_lab")

    private fun candidate(id: Long = 5, edition: String = "OL1M", exact: Boolean = true) = OnlineCoverCandidate(OpenLibrary.ID, edition, OpenLibraryCoverRef.Id(id), isbn,
        "Matilda", null, "Puffin", 1998, "en", if (exact) CoverMatchKind.EXACT_EDITION else CoverMatchKind.OTHER_EDITION, MatchConfidence.VERY_HIGH, emptySet())

    private fun service(budget: PilotBudget = PilotBudget(), normalizer: PilotImageNormalizer? = null): CoverPilotService {
        store = CoverPilotStore(root(), previews(), { now })
        val norm = normalizer ?: PilotImageNormalizer { raw, out -> out.writeBytes(raw.readBytes()); PilotImage("image/jpeg", "jpg", width, height, width, height, out.length()) }
        return CoverPilotService(auth, store, images, { editionCalls++; listOf(CoverEdition("OL1M", "Matilda", listOf(5L), listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn)), "Puffin", "1998", "en", true)) },
            { query }, { onExists?.invoke(); exists }, { free }, norm, { f -> if (f.length() > 0) PilotImage("image/jpeg", f.extension, width, height, width, height, f.length()) else null }, budget, { now })
    }

    private fun failed(r: PilotResult<*>) = (r as PilotResult.Failed).reason
    private fun <T> ok(r: PilotResult<T>) = (r as PilotResult.Ok<T>).value
    private fun leftovers() = (root().walkTopDown() + previews().walkTopDown()).filter { it.isFile && it.name.endsWith(".tmp") }.toList()

    // ---- consent and scope ----

    @Test fun `nothing is requested before the pilot is enabled`() = runTest {
        val s = service()
        assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.discover(item)))
        assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.preview(item, candidate())))
        assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.download(item, candidate())))
        assertTrue(images.requests.isEmpty()); assertEquals(0, editionCalls)
        assertNull(s.selection(item.id))
    }

    @Test fun `online metadata consent does not enable cover requests`() = runTest {
        val metadata = OnlineMetadataSettings(MemoryBooleans())
        metadata.enableProvider(OpenLibrary.ID)
        assertTrue(metadata.onlineEnabled && metadata.providerEnabled(OpenLibrary.ID))
        assertFalse(auth.permits(OpenLibrary.ID, MediaCategory.BOOK))
        assertEquals(PilotFailure.NOT_AUTHORIZED, failed(service().discover(item)))
    }

    @Test fun `only Books and only Open Library are permitted even when enabled`() = runTest {
        auth.enable()
        val s = service()
        assertTrue(auth.permits(OpenLibrary.ID, MediaCategory.BOOK))
        assertFalse(auth.permits("inventaire", MediaCategory.BOOK))
        for (c in listOf(MediaCategory.COMIC, MediaCategory.MANGA, MediaCategory.DOCUMENT)) {
            assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.discover(item.copy(category = c))))
            assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.download(item.copy(category = c), candidate())))
        }
        assertTrue(images.requests.isEmpty()); assertEquals(0, editionCalls)
    }

    @Test fun `a non-debug build can never be authorized`() {
        val release = CoverPilotAuthorization(MemoryBooleans().also { it.put(CoverPilotAuthorization.KEY, true) }, debugBuild = false)
        release.enable()
        assertFalse(release.enabled.value); assertFalse(release.permits(OpenLibrary.ID, MediaCategory.BOOK))
    }

    @Test fun `consent is off by default and persists`() {
        val store = MemoryBooleans()
        assertFalse(CoverPilotAuthorization(store, true).enabled.value)
        CoverPilotAuthorization(store, true).enable()
        assertTrue(CoverPilotAuthorization(store, true).enabled.value)
        CoverPilotAuthorization(store, true).disable()
        assertFalse(CoverPilotAuthorization(store, true).enabled.value)
    }

    // ---- the D1-M metadata grant cannot be used for covers ----

    @Test fun `the D1-M grant never authorizes covers anywhere`() = runTest {
        val d = OpenLibrary.descriptor()
        assertTrue(d.authorizes(MediaCategory.BOOK, MetadataCapability.IDENTIFIER_LOOKUP))
        assertFalse(d.authorizes(MediaCategory.BOOK, MetadataCapability.COVERS))
        assertThrows(IllegalArgumentException::class.java) { ScopedGrant(OpenLibrary.ID, "D1-M", setOf(MediaCategory.BOOK), setOf(MetadataCapability.COVERS)) }
        assertEquals(TermsStatus.CONDITIONAL, d.termsStatus)
        val allOn = object : ProviderSettings {
            override val onlineEnabled = true
            override fun providerEnabled(id: String) = true
            override fun hasToken(id: String) = false
            override fun disabledByPolicy(id: String) = false
        }
        val registry = ProviderRegistry(listOf(RegisteredProvider(d, OpenLibraryProvider(FakeHttp()))), allOn)
        assertTrue(registry.active(MediaCategory.BOOK, MetadataCapability.COVERS).isEmpty())
        val engine = ProviderPolicyEngine({ now })
        val outcome = engine.execute(d, MediaCategory.BOOK, MetadataCapability.COVERS) { "never" }
        assertTrue(outcome is ProviderPolicyEngine.Outcome.Failure && outcome.kind == ProviderFailureKind.POLICY_DISABLED)
    }

    // ---- discovery ----

    @Test fun `discovery returns edition candidates and sends only the isbn lookup`() = runTest {
        auth.enable()
        val found = ok(service().discover(item))
        assertEquals(isbn, found.isbn13)
        assertEquals(CoverMatchKind.EXACT_EDITION, found.candidates.single().match)
        assertEquals(1, editionCalls); assertTrue(images.requests.isEmpty())
    }

    @Test fun `a book without an isbn asks nothing`() = runTest {
        auth.enable(); query = query!!.copy(identifiers = emptyList())
        assertEquals(PilotFailure.NO_ISBN, failed(service().discover(item)))
        assertEquals(0, editionCalls)
    }

    @Test fun `a removed book is reported and nothing is requested`() = runTest {
        auth.enable(); query = null
        assertEquals(PilotFailure.ITEM_GONE, failed(service().discover(item)))
        assertEquals(0, editionCalls)
    }

    @Test fun `discovery is capped per session`() = runTest {
        auth.enable()
        val s = service(PilotBudget(maxDiscoveries = 2))
        ok(s.discover(item)); ok(s.discover(item))
        assertEquals(PilotFailure.BUDGET_EXHAUSTED, failed(s.discover(item)))
        assertEquals(2, editionCalls)
    }

    // ---- preview and download ----

    @Test fun `preview downloads the medium image once and reuses it without a request`() = runTest {
        auth.enable()
        val s = service()
        val first = ok(s.preview(item, candidate()))
        assertFalse(first.fromCache)
        assertEquals(listOf("https://covers.openlibrary.org/b/id/5-M.jpg?default=false"), images.requests)
        val second = ok(s.preview(item, candidate()))
        assertTrue(second.fromCache)
        assertEquals(1, images.requests.size)
        assertEquals(400, second.image.width)
    }

    @Test fun `download uses the large image, stores it privately and survives a restart`() = runTest {
        auth.enable()
        val s = service()
        val art = ok(s.download(item, candidate()))
        assertEquals("https://covers.openlibrary.org/b/id/5-L.jpg?default=false", images.requests.single())
        assertTrue(art.file.isFile && art.file.startsWith(root()))
        // A new process: new store and service over the same directories.
        val reopened = CoverPilotStore(root(), previews(), { now }).selection(item.id)!!
        assertEquals("OL1M", reopened.editionRef); assertEquals("id", reopened.refKind); assertEquals("L", reopened.size)
        assertEquals(400, reopened.width); assertEquals(600, reopened.height)
        assertTrue(CoverPilotStore(root(), previews()).file(reopened).readBytes().contentEquals(JPEG))
        assertTrue(leftovers().isEmpty())
    }

    @Test fun `downloading the current pick again makes no request`() = runTest {
        auth.enable()
        val s = service()
        ok(s.download(item, candidate()))
        assertTrue(ok(s.download(item, candidate())).fromCache)
        assertEquals(1, images.requests.size)
    }

    @Test fun `a failed download keeps the previous pick and leaves no partial file`() = runTest {
        auth.enable()
        val s = service()
        val first = ok(s.download(item, candidate(5)))
        images.respond = { _, f -> f.writeBytes(byteArrayOf(1, 2, 3)); throw IOException("connection reset") }
        assertEquals(PilotFailure.OFFLINE, failed(s.download(item, candidate(6))))
        val kept = store.selection(item.id)!!
        assertEquals("id", kept.refKind); assertEquals("5", kept.refValue)
        assertTrue(store.file(kept).readBytes().contentEquals(JPEG))
        assertEquals(listOf("selection.properties", first.file.name).sorted(), root().resolve(item.id).list()!!.sorted())
    }

    @Test fun `a new pick replaces the old file without leaking it`() = runTest {
        auth.enable()
        val s = service()
        val a = ok(s.download(item, candidate(5)))
        val b = ok(s.download(item, candidate(6)))
        assertFalse(a.file.exists()); assertTrue(b.file.exists())
        assertEquals(2, root().resolve(item.id).list()!!.size)
    }

    @Test fun `removing the pick deletes everything`() = runTest {
        auth.enable()
        val s = service()
        ok(s.preview(item, candidate())); ok(s.download(item, candidate()))
        s.removeSelection(item.id)
        assertNull(s.selection(item.id)); assertFalse(root().resolve(item.id).exists()); assertFalse(previews().resolve(item.id).exists())
    }

    // ---- failures ----

    @Test fun `a missing image is not found and changes nothing`() = runTest {
        auth.enable(); images.respond = { _, _ -> ImageFetch(404, "text/html", null, 0) }
        val s = service()
        assertEquals(PilotFailure.NOT_FOUND, failed(s.download(item, candidate())))
        assertNull(s.selection(item.id)); assertTrue(leftovers().isEmpty())
    }

    @Test fun `429 pauses every further request until Retry-After passes`() = runTest {
        auth.enable(); images.respond = { _, _ -> ImageFetch(429, null, "120", 0) }
        val s = service()
        val r = s.preview(item, candidate()) as PilotResult.Failed
        assertEquals(PilotFailure.RATE_LIMITED, r.reason); assertEquals(120_000L, r.retryAfterMs)
        images.respond = { _, f -> f.writeBytes(JPEG); ImageFetch(200, "image/jpeg", null, JPEG.size.toLong()) }
        assertEquals(PilotFailure.RATE_LIMITED, failed(s.preview(item, candidate(7))))
        assertEquals(PilotFailure.RATE_LIMITED, failed(s.discover(item)))
        assertEquals(1, images.requests.size); assertEquals(0, editionCalls)
        now += 121_000
        ok(s.preview(item, candidate(7)))
        assertEquals(2, images.requests.size)
    }

    @Test fun `403 is a block with a default pause`() = runTest {
        auth.enable(); images.respond = { _, _ -> ImageFetch(403, null, null, 0) }
        val s = service()
        assertEquals(PilotFailure.ACCESS_BLOCKED, failed(s.download(item, candidate())))
        assertEquals(PilotFailure.RATE_LIMITED, failed(s.download(item, candidate())))
        assertEquals(1, images.requests.size)
    }

    @Test fun `a server error is reported and not retried`() = runTest {
        auth.enable(); images.respond = { _, _ -> ImageFetch(503, null, null, 0) }
        assertEquals(PilotFailure.SERVER_FAILURE, failed(service().download(item, candidate())))
        assertEquals(1, images.requests.size)
    }

    @Test fun `network and transport failures map to a closed set`() = runTest {
        auth.enable(); val s = service()
        images.respond = { _, _ -> throw UrlNotAllowedException("host not allowed") }
        assertEquals(PilotFailure.REDIRECT_BLOCKED, failed(s.download(item, candidate())))
        images.respond = { _, _ -> throw ResponseTooLargeException(10) }
        assertEquals(PilotFailure.TOO_LARGE, failed(s.download(item, candidate())))
        images.respond = { _, _ -> throw SocketTimeoutException() }
        assertEquals(PilotFailure.TIMEOUT, failed(s.download(item, candidate())))
        images.respond = { _, _ -> throw IOException("no route") }
        assertEquals(PilotFailure.OFFLINE, failed(s.download(item, candidate())))
        assertNull(s.selection(item.id)); assertTrue(leftovers().isEmpty())
    }

    private fun serve(bytes: ByteArray, type: String?) { images.respond = { _, f -> f.writeBytes(bytes); ImageFetch(200, type, null, bytes.size.toLong()) } }

    @Test fun `content decides, not the header`() = runTest {
        auth.enable(); val s = service()
        serve(PNG, "image/jpeg"); assertEquals(PilotFailure.MIME_MISMATCH, failed(s.download(item, candidate())))
        serve(JPEG, "image/png"); assertEquals(PilotFailure.MIME_MISMATCH, failed(s.download(item, candidate())))
        serve(HTML, "image/jpeg"); assertEquals(PilotFailure.NOT_AN_IMAGE, failed(s.download(item, candidate())))
        serve(JPEG, "text/html"); assertEquals(PilotFailure.NOT_AN_IMAGE, failed(s.download(item, candidate())))
        serve(byteArrayOf(1, 2), null); assertEquals(PilotFailure.NOT_AN_IMAGE, failed(s.download(item, candidate())))
        assertNull(s.selection(item.id)); assertTrue(leftovers().isEmpty())
    }

    @Test fun `matching or unstated types are accepted`() = runTest {
        auth.enable(); val s = service()
        serve(JPEG, "image/jpeg; charset=binary"); ok(s.download(item, candidate(1)))
        serve(PNG, "image/png"); ok(s.download(item, candidate(2)))
        serve(WEBP, null); ok(s.download(item, candidate(3)))
        serve(JPEG, "application/octet-stream"); ok(s.download(item, candidate(4)))
        serve(JPEG, "image/jpg"); ok(s.download(item, candidate(5)))
    }

    @Test fun `corrupt or unsupported content is rejected by the decoder stage`() = runTest {
        auth.enable()
        var reason = CoverFailureReason.UNDECODABLE_CONTENT
        val s = service(normalizer = PilotImageNormalizer { _, _ -> throw CoverExtractionFailure(reason) })
        assertEquals(PilotFailure.INVALID_IMAGE, failed(s.download(item, candidate())))
        reason = CoverFailureReason.UNSUPPORTED_REPRESENTATION
        assertEquals(PilotFailure.UNSUPPORTED_IMAGE, failed(s.download(item, candidate())))
        reason = CoverFailureReason.STORAGE_FULL
        assertEquals(PilotFailure.STORAGE_FULL, failed(s.download(item, candidate())))
        assertNull(s.selection(item.id)); assertTrue(leftovers().isEmpty())
    }

    @Test fun `an image with no decodable size is rejected`() = runTest {
        auth.enable(); width = 0
        assertEquals(PilotFailure.INVALID_IMAGE, failed(service().download(item, candidate())))
    }

    @Test fun `low storage fails before any request`() = runTest {
        auth.enable(); free = 100L * 1024 * 1024
        assertEquals(PilotFailure.STORAGE_FULL, failed(service().download(item, candidate())))
        assertTrue(images.requests.isEmpty())
    }

    @Test fun `image transfers are capped per session`() = runTest {
        auth.enable()
        val s = service(PilotBudget(maxImages = 2))
        ok(s.preview(item, candidate(1))); ok(s.preview(item, candidate(2)))
        assertEquals(PilotFailure.BUDGET_EXHAUSTED, failed(s.preview(item, candidate(3))))
        assertEquals(2, images.requests.size)
    }

    @Test fun `a book removed during the transfer gets no pick`() = runTest {
        auth.enable()
        images.respond = { _, f -> exists = false; f.writeBytes(JPEG); ImageFetch(200, "image/jpeg", null, JPEG.size.toLong()) }
        val s = service()
        assertEquals(PilotFailure.ITEM_GONE, failed(s.download(item, candidate())))
        assertNull(s.selection(item.id)); assertTrue(leftovers().isEmpty())
    }

    // ---- concurrency and cancellation ----

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a second press while a transfer runs starts nothing`() = runTest {
        auth.enable()
        val gate = CompletableDeferred<Unit>()
        images.respond = { _, f -> gate.await(); f.writeBytes(JPEG); ImageFetch(200, "image/jpeg", null, JPEG.size.toLong()) }
        val s = service()
        val first = launch { ok(s.download(item, candidate())) }
        advanceUntilIdle()
        assertEquals(PilotFailure.BUSY, failed(s.download(item, candidate())))
        assertEquals(PilotFailure.BUSY, failed(s.preview(item, candidate(9))))
        assertEquals(PilotFailure.BUSY, failed(s.discover(item)))
        gate.complete(Unit); first.join()
        assertEquals(1, images.requests.size)
        assertNotNull(s.selection(item.id))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `cancelling publishes nothing, cleans up and frees the slot`() = runTest {
        auth.enable()
        val gate = CompletableDeferred<Unit>()
        images.respond = { _, f -> f.writeBytes(byteArrayOf(1)); gate.await(); ImageFetch(200, "image/jpeg", null, 1) }
        val s = service()
        val job = launch { s.download(item, candidate()) }
        advanceUntilIdle()
        job.cancel(); job.join()
        assertNull(s.selection(item.id)); assertTrue(leftovers().isEmpty())
        images.respond = { _, f -> f.writeBytes(JPEG); ImageFetch(200, "image/jpeg", null, JPEG.size.toLong()) }
        ok(s.download(item, candidate()))
    }

    // ---- storage ----

    @Test fun `picks are bounded and the oldest is evicted`() = runTest {
        auth.enable()
        val s = service()
        for (i in 1..(CoverPilotStore.MAX_SELECTIONS + 2)) { now += 1000; ok(s.download(item.copy(id = "b$i"), candidate(i.toLong()))) }
        val kept = root().list()!!.toSet()
        assertEquals(CoverPilotStore.MAX_SELECTIONS, kept.size)
        assertFalse("b1" in kept); assertTrue("b10" in kept)
    }

    @Test fun `the production cover sweep neither sees nor deletes pilot files`() = runTest {
        auth.enable()
        val s = service()
        val art = ok(s.download(item, candidate()))
        val covers = File(tmp.root, "files/covers").also { it.mkdirs() }
        File(covers, "orphan").mkdirs(); File(covers, "orphan/x.jpg").writeBytes(JPEG)
        CoverFiles(covers).sweep(emptyMap())
        assertTrue(art.file.exists()); assertNotNull(s.selection(item.id))
        assertFalse(File(covers, "orphan").exists())
    }

    @Test fun `unsafe ids and keys never reach a path`() {
        service()
        assertThrows(IllegalArgumentException::class.java) { store.newTemporary("../x") }
        assertThrows(IllegalArgumentException::class.java) { store.previewFile("b1", "../../x") }
        assertThrows(IllegalArgumentException::class.java) { store.file(PilotSelection("b1", "../evil.jpg", "k", "p", "e", "id", "1", "L", "image/jpeg", 1, 1, 1, 1, "EXACT_EDITION")) }
    }

    @Test fun `an unreadable manifest or a missing image is no pick`() = runTest {
        auth.enable()
        val s = service()
        val art = ok(s.download(item, candidate()))
        art.file.delete()
        assertNull(s.selection(item.id))
        File(root(), "${item.id}/selection.properties").writeText("garbage\u0000")
        assertNull(s.selection(item.id))
    }

    // ---- R1 F-3: consent and item are re-verified immediately before the commit ----

    @Test fun `consent revoked during the transfer commits nothing and keeps the previous pick`() = runTest {
        auth.enable()
        val s = service()
        val first = ok(s.download(item, candidate(5)))
        images.respond = { _, f -> auth.disable(); f.writeBytes(JPEG); ImageFetch(200, "image/jpeg", null, JPEG.size.toLong()) }
        assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.download(item, candidate(6))))
        val kept = store.selection(item.id)!!
        assertEquals("5", kept.refValue); assertTrue(store.file(kept).exists())
        assertEquals(listOf("selection.properties", first.file.name).sorted(), root().resolve(item.id).list()!!.sorted())
        assertTrue(leftovers().isEmpty())
    }

    @Test fun `consent revoked during a preview commits no preview`() = runTest {
        auth.enable()
        images.respond = { _, f -> auth.disable(); f.writeBytes(JPEG); ImageFetch(200, "image/jpeg", null, JPEG.size.toLong()) }
        val s = service()
        assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.preview(item, candidate())))
        assertNull(s.cachedPreview(item.id, candidate())); assertTrue(leftovers().isEmpty())
    }

    @Test fun `the book removed during the transfer keeps the previous pick of that book intact`() = runTest {
        auth.enable()
        val s = service()
        ok(s.download(item, candidate(5)))
        images.respond = { _, f -> exists = false; f.writeBytes(JPEG); ImageFetch(200, "image/jpeg", null, JPEG.size.toLong()) }
        assertEquals(PilotFailure.ITEM_GONE, failed(s.download(item, candidate(6))))
        assertEquals("5", store.selection(item.id)!!.refValue); assertTrue(leftovers().isEmpty())
    }

    @Test fun `a candidate from another provider is rejected before any request, budget use or file`() = runTest {
        auth.enable()
        val s = service(PilotBudget(maxImages = 1))
        val first = ok(s.download(item, candidate(5)))
        val foreign = candidate(6).copy(providerId = "inventaire")
        assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.download(item, foreign)))
        assertEquals(PilotFailure.NOT_AUTHORIZED, failed(s.preview(item, foreign)))
        assertEquals("only the first, legitimate transfer was requested", 1, images.requests.size)
        assertEquals("5", store.selection(item.id)!!.refValue); assertTrue(first.file.exists()); assertTrue(leftovers().isEmpty())
        assertNull(s.cachedPreview(item.id, foreign))
        // The budget was not spent by the rejected attempts: with maxImages = 1 it is exhausted only by the first transfer.
        assertEquals(PilotFailure.BUDGET_EXHAUSTED, failed(s.download(item, candidate(7))))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `cancellation just before the commit publishes nothing and keeps the previous pick intact`() = runTest {
        auth.enable()
        val s = service()
        val first = ok(s.download(item, candidate(5)))
        val job = launch { s.download(item, candidate(6)) }
        onExists = { job.cancel() }   // the last suspension point before the commit: the caller is cancelled here
        advanceUntilIdle(); job.join()
        assertTrue(job.isCancelled)
        val kept = store.selection(item.id)!!
        assertEquals("5", kept.refValue); assertTrue(store.file(kept).readBytes().contentEquals(JPEG))
        assertEquals(listOf("selection.properties", first.file.name).sorted(), root().resolve(item.id).list()!!.sorted())
        assertTrue(leftovers().isEmpty())
        onExists = null
        ok(s.download(item, candidate(6)))   // the slot was released and the store is healthy
    }
}
