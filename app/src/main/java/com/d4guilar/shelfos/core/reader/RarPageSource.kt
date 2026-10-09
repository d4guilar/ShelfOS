// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.core.files.ArchivePolicy
import com.d4guilar.shelfos.core.files.CachedExtraction
import com.d4guilar.shelfos.core.files.EmbeddedMetadata
import com.d4guilar.shelfos.core.files.EmbeddedMetadataReader
import com.d4guilar.shelfos.core.files.NativeRarEntryType
import com.d4guilar.shelfos.core.files.NativeRarError
import com.d4guilar.shelfos.core.files.RarArchiveSession
import com.d4guilar.shelfos.core.files.RarCacheCoordinator
import com.d4guilar.shelfos.core.files.RarExtractionCache
import com.d4guilar.shelfos.core.files.RarExtractionException
import com.d4guilar.shelfos.core.files.naturalCompare
import com.d4guilar.shelfos.domain.importing.isPageImage
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationExceptionDetail
import com.d4guilar.shelfos.domain.library.PublicationProblem
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * One logical comic page's identity, retaining the RAR entry's own [physicalIndex] (never just [name]) as its
 * extraction/cache key -- see [RarPageSource]'s class doc for why display names alone can never be trusted for
 * this.
 */
internal data class RarPageEntry(val physicalIndex: Int, val name: String)

/**
 * Phase 3E-C container adapter: the only production bridge between the accepted Phase 3E-B native RAR engine
 * (wrapped behind [RarArchiveSession] for testability) and the existing [PageSource] contract [ZipPageSource]
 * already satisfies for CBZ. Architecture goal, exactly as `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3E-C record
 * states it: **RAR native engine -> [RarPageSource] -> [PageSource] -> [ImagePageRenderer]** -- the identical
 * shape [ZipPageSource] already has, so [ImagePageRenderer] (bounds-then-sample decode, [FixedReader.pageGeometry]
 * support) is reused completely unchanged. This class is NOT wired into [FixedReaderFactory] or any
 * `PublicationFormat`/import routing -- no `PublicationFormat.CBR` exists yet; that is explicitly later Phase 3E
 * scope (3E-D/3E-E).
 *
 * ## Why extraction is cached on disk, not re-read per call
 *
 * Unlike [ZipPageSource]'s true random access (`SeekableZip`'s positional reads), the underlying RAR engine
 * explicitly promises no cheap re-seek -- especially for solid RAR, every [RarArchiveSession.extractEntry] call
 * independently restarts and re-scans the archive from the beginning (see `NativeRarSession`'s "Seek/restart"
 * doc). [ImagePageRenderer] calls [PageSource.openPage] at least twice per page in the normal reading path (once
 * for [ImagePageRenderer.bounds]'s decode-bounds-only pass, again for [ImagePageRenderer.render]'s full decode),
 * so without a cache a single logical page read would always cost at least two full archive re-scans. [cache]
 * materializes each page's bytes into a ShelfOS-generated on-disk file exactly once (keyed by physical ordinal,
 * see [RarPageEntry]) and reuses that file for every subsequent [openPage] call for the same page -- see
 * [RarExtractionCache]'s doc for the full atomicity/concurrency/eviction contract.
 *
 * ## Physical ordinal vs. logical page identity
 *
 * [pages] is the RAR entries' image-only, natural-sorted subset -- logical page `i` is `pages[i]`, and its
 * extraction/cache identity is always `pages[i].physicalIndex`, never `pages[i].name`. Two entries that happen to
 * share a filename (same basename in different folders, or genuinely duplicate names) therefore can never be
 * confused with each other: they sort by name like any other entries (tie-broken deterministically by physical
 * ordinal when names compare equal), but each still extracts/caches under its own distinct physical ordinal.
 *
 * ## Safe-name policy
 *
 * Even though native extraction only ever writes to a caller-supplied destination file and cannot path-traverse
 * on its own, an entry whose declared name fails [ArchivePolicy.safeName] (`../`, an absolute path, a backslash,
 * a drive-letter colon) is rejected before it can ever become a logical page or a [comicInfo] candidate -- the
 * exact same policy CBZ already applies via [ArchivePolicy.entries].
 *
 * ## Source input / ownership / close / post-close behavior
 *
 * Takes ownership of [session] (closed exactly once by [close]). [close] does NOT delete this archive's
 * materialized cache files or evict them from [cache] -- the whole point of the on-disk cache is to let a later
 * reopen of the same source key reuse earlier materializations rather than re-extracting (see [open]'s doc for
 * the source-namespace model). [openPage] and [comicInfo] both throw [IllegalStateException] once [close] has
 * been called, rather than silently returning stale data.
 */
internal class RarPageSource private constructor(
    private val session: RarArchiveSession,
    private val cache: RarExtractionCache,
    private val pages: List<RarPageEntry>,
    private val comicInfoEntry: RarPageEntry?,
) : PageSource {

    @Volatile private var closed = false

    override val pageCount: Int get() = pages.size

    override fun openPage(index: Int): InputStream {
        check(!closed) { "RarPageSource is closed" }
        return extract(pages[index].physicalIndex).open()
    }

    /**
     * Container-level `ComicInfo.xml` exposure (3E-C scope only -- never wired to `LibraryEntity`/import; see the
     * class doc). Reuses [EmbeddedMetadataReader]'s existing CBZ parser/model completely unchanged -- no second
     * XML parser. Identification mirrors CBZ's own (previously undefined, now made explicit here) multiple-entry
     * policy: the first physical entry whose name equals `"ComicInfo.xml"` case-insensitively, exactly as
     * [EmbeddedMetadataReader.comicInfo] already selects for a ZIP container -- never a subdirectory match, never
     * a merge of several candidates. Returns `null` if no such entry exists, it is oversized, or it fails to
     * parse -- metadata is always best-effort, never blocking.
     */
    fun comicInfo(): EmbeddedMetadata? {
        check(!closed) { "RarPageSource is closed" }
        val entry = comicInfoEntry ?: return null
        val extraction = try {
            extract(entry.physicalIndex)
        } catch (_: PublicationException) {
            return null
        }
        return try {
            if (extraction.file.length() > MAX_COMIC_INFO_BYTES) return null
            val xml = extraction.file.readBytes().toString(Charsets.UTF_8)
            EmbeddedMetadataReader.parse(xml)?.let(EmbeddedMetadataReader::comicInfo)
        } catch (_: Throwable) {
            null
        } finally {
            extraction.release()
        }
    }

    private fun extract(physicalIndex: Int): CachedExtraction = try {
        cache.acquire(physicalIndex) { destination ->
            // Phase 3E-C R1A (HIGH-2): a known-honest declared size is already precchecked in index() before an
            // entry ever becomes a logical page; this ceiling additionally bounds the unknown/dishonestly-sized
            // case DURING extraction itself, never only after the fact.
            session.extractEntry(physicalIndex, destination, RarExtractionCache.MAX_ENTRY_BYTES)
        }
    } catch (e: RarExtractionException) {
        throw PublicationException(e.error.toPublicationProblem())
    }

    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    companion object {
        private const val MAX_COMIC_INFO_BYTES = 1L * 1024 * 1024

        /**
         * Opens a [RarPageSource] over an already-open [session] (encryption is already rejected wholesale by
         * `NativeRarSession.open`, before any [RarArchiveSession] reaches here -- see its "ANY entry encrypted"
         * doc, so [comicInfo]/[openPage] never need their own per-entry encrypted check).
         *
         * ## Cache namespace / source-key model
         *
         * [sourceKey], when non-null, is the strongest safe source identity available to this checkpoint (a
         * caller-supplied stable identifier for the underlying archive -- e.g. a future `LibraryItem` id plus a
         * content-change signal, left entirely to the caller since 3E-C has no product/import integration to
         * derive one from yet). It is hashed ([UUID.nameUUIDFromBytes]) into a deterministic, filesystem-safe
         * cache subdirectory name -- never used as a raw path segment, so arbitrary caller content can never
         * reach the filesystem unsanitized. When [sourceKey] is null, this falls back to a fresh per-call random
         * namespace: correctness over cache persistence, exactly as the brief requires -- a caller that cannot
         * supply a reliable stable identity sacrifices cross-reopen cache reuse rather than risk serving stale
         * bytes under a reused key for a different archive. Either way, [maxCacheBytes]/[maxCacheEntries] are no
         * longer this one source's own private budget (Phase 3E-C R1A): [cacheRoot]/`cbr` is shared, process-wide,
         * global coordination/accounting domain spanning every namespace ever opened against it -- see
         * [RarCacheCoordinator]'s class doc for the full architecture, including why a reopen of the SAME
         * [sourceKey] can now genuinely reuse an earlier materialization rather than merely claim to.
         */
        fun open(
            session: RarArchiveSession,
            cacheRoot: File,
            sourceKey: String?,
            maxCacheBytes: Long = RarCacheCoordinator.DEFAULT_MAX_BYTES,
            maxCacheEntries: Int = RarCacheCoordinator.DEFAULT_MAX_ENTRIES,
        ): RarPageSource {
            val index = try {
                index(session)
            } catch (e: Throwable) {
                session.close()
                throw e
            }
            val namespace = sourceKey?.let { UUID.nameUUIDFromBytes(it.toByteArray(Charsets.UTF_8)).toString() }
                ?: UUID.randomUUID().toString()
            val coordinatorRoot = File(cacheRoot, "cbr")
            val coordinator = RarCacheCoordinator.getInstance(coordinatorRoot, maxCacheBytes, maxCacheEntries)
            val cache = RarExtractionCache(coordinator, namespace)
            return RarPageSource(session, cache, index.pages, index.comicInfo)
        }

        private data class Index(val pages: List<RarPageEntry>, val comicInfo: RarPageEntry?)

        /** Mirrors [ArchivePolicy.pages]/[ArchivePolicy.entries] for a RAR-backed archive: reuses the exact same
         * format-independent safety/filtering/ordering primitives ([ArchivePolicy.safeName], [isPageImage],
         * [naturalCompare]) CBZ already uses, applied to [RarArchiveSession]'s physical entry model instead of
         * `SeekableZip.Entry`. */
        private fun index(session: RarArchiveSession): Index {
            val count = session.entryCount
            if (count > ArchivePolicy.MAX_ENTRIES) {
                throw PublicationException(PublicationProblem.TOO_LARGE, PublicationExceptionDetail.TOO_MANY_ENTRIES)
            }

            val entries = (0 until count).mapNotNull(session::entryAt)
            entries.forEach { entry ->
                if (!ArchivePolicy.safeName(entry.name)) {
                    throw PublicationException(PublicationProblem.CORRUPT, PublicationExceptionDetail.UNSAFE_ENTRY_PATH)
                }
            }
            val files = entries.filter { it.type == NativeRarEntryType.REGULAR_FILE }

            val comicInfo = files.firstOrNull { it.name.equals("ComicInfo.xml", ignoreCase = true) }
                ?.let { RarPageEntry(it.physicalIndex, it.name) }

            val sortedPages = files.filter { isPageImage(it.name) }
                .onEach { entry ->
                    if ((entry.size ?: 0L) > ArchivePolicy.MAX_IMAGE_BYTES) {
                        throw PublicationException(PublicationProblem.TOO_LARGE, PublicationExceptionDetail.IMAGE_PAGE_TOO_LARGE)
                    }
                }
                .sortedWith { a, b ->
                    val byName = naturalCompare(a.name, b.name)
                    if (byName != 0) byName else a.physicalIndex.compareTo(b.physicalIndex)
                }
            if (sortedPages.isEmpty()) throw PublicationException(PublicationProblem.EMPTY_ARCHIVE)

            return Index(sortedPages.map { RarPageEntry(it.physicalIndex, it.name) }, comicInfo)
        }
    }
}

/**
 * Internal-only, UI/localization-free mapping of [NativeRarError] onto ShelfOS's existing
 * [PublicationProblem] model -- never a raw native code, never a new [PublicationProblem]/
 * [PublicationExceptionDetail] value (both are UI-mapped elsewhere by exhaustive `when`s this checkpoint
 * deliberately does not touch), and never surfaced as localized text from this checkpoint. [NativeRarError.
 * NOT_SEEKABLE], [NativeRarError.IO], and [NativeRarError.NATIVE_INTERNAL] all collapse to the existing
 * [PublicationProblem.UNREADABLE] for now -- a real loss of the three-way distinction the native layer itself
 * still makes (see `NativeRarSession`'s doc), explicitly flagged here for whichever later slice (3E-D) first
 * needs to tell them apart in product UX, rather than inventing a new enum entry speculatively today.
 * [NativeRarError.TOO_LARGE] (Phase 3E-C R1A, HIGH-2's hard extraction-time ceiling) reuses the existing
 * [PublicationProblem.TOO_LARGE] -- the SAME problem CBZ's own oversized-page-image policy
 * ([com.d4guilar.shelfos.core.files.ArchivePolicy]) already reports -- rather than collapsing into
 * [PublicationProblem.UNREADABLE] or inventing a new value; a narrower [PublicationExceptionDetail] for this
 * specific RAR-streamed-ceiling case is left to the reserved error-mapping-cleanup pass.
 */
internal fun NativeRarError.toPublicationProblem(): PublicationProblem = when (this) {
    NativeRarError.PROTECTED -> PublicationProblem.PROTECTED
    NativeRarError.UNSUPPORTED -> PublicationProblem.UNSUPPORTED_FORMAT
    NativeRarError.CORRUPT, NativeRarError.INVALID_ARGUMENT -> PublicationProblem.CORRUPT
    NativeRarError.TOO_LARGE -> PublicationProblem.TOO_LARGE
    NativeRarError.NOT_SEEKABLE, NativeRarError.IO, NativeRarError.NATIVE_INTERNAL -> PublicationProblem.UNREADABLE
}
