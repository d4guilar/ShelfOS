// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import com.d4guilar.shelfos.domain.importing.isPageImage
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationExceptionDetail
import com.d4guilar.shelfos.domain.library.PublicationProblem
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * One logical comic page's identity, retaining the RAR entry's own [physicalIndex] (never just [name]) as its
 * extraction/cache key -- see [RarContainer]'s class doc for why display names alone can never be trusted for
 * this.
 */
internal data class RarPageEntry(val physicalIndex: Int, val name: String)

/**
 * Phase 3E-D R1A layering fix: the lower-level, format-independent RAR container abstraction that BOTH import-time
 * inspection ([PublicationFiles], `core.files`) and the reading adapter (`core.reader.RarPageSource`) depend on --
 * neither of those two ever depends on the other, closing the `core.files -> core.reader -> core.files` inversion
 * Codex found in the original Phase 3E-D integration (where `PublicationFiles` imported `core.reader.RarPageSource`
 * directly).
 *
 * This class owns every piece of RAR knowledge that is genuinely container-level rather than reader-specific:
 * opening a session via [RarArchiveSession], safe entry enumeration ([ArchivePolicy.safeName]), [ArchivePolicy.
 * MAX_ENTRIES] enforcement, image-only filtering ([isPageImage]), natural logical page ordering ([naturalCompare])
 * tie-broken by physical ordinal, `ComicInfo.xml` entry identification, empty-archive validation, and cached page
 * materialization through [RarExtractionCache]/[RarCacheCoordinator]. [RarPageSource] (the reader's [PageSource]
 * adapter) and [PublicationFiles] (import validation) both consume this ONE index/materialization model --
 * neither independently enumerates/sorts/filters the archive with its own divergent copy of this policy.
 *
 * ## Why extraction is cached on disk, not re-read per call
 *
 * Unlike `ZipPageSource`'s true random access (`SeekableZip`'s positional reads), the underlying RAR engine
 * explicitly promises no cheap re-seek -- especially for solid RAR, every [RarArchiveSession.extractEntry] call
 * independently restarts and re-scans the archive from the beginning (see `NativeRarSession`'s "Seek/restart"
 * doc). [extractPage] materializes each page's bytes into a ShelfOS-generated on-disk file exactly once (keyed by
 * physical ordinal, see [RarPageEntry]) and reuses that file for every subsequent call for the same page -- see
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
 * materialized cache files or evict them from the cache -- the whole point of the on-disk cache is to let a later
 * reopen of the same source namespace reuse earlier materializations rather than re-extracting (see [open]'s doc
 * for the source-namespace model). [extractPage]/[comicInfo] both throw [IllegalStateException] once [close] has
 * been called, rather than silently returning stale data.
 */
internal class RarContainer private constructor(
    private val session: RarArchiveSession,
    private val cache: RarExtractionCache,
    private val pages: List<RarPageEntry>,
    private val comicInfoEntry: RarPageEntry?,
) {
    @Volatile private var closed = false

    val pageCount: Int get() = pages.size

    /** Materializes logical page [index]'s bytes (see class doc's "disk cache" section) and returns a handle to
     * read them. Reader-specific: [RarPageSource] is the only caller that needs this to satisfy [PageSource].
     * Import-time inspection never needs this -- it only needs [comicInfo]. */
    fun extractPage(index: Int): CachedExtraction {
        check(!closed) { "RarContainer is closed" }
        return extract(pages[index].physicalIndex)
    }

    /**
     * Container-level `ComicInfo.xml` exposure. Reuses [EmbeddedMetadataReader]'s existing CBZ parser/model
     * completely unchanged -- no second XML parser. Identification mirrors CBZ's own multiple-entry policy: the
     * first physical entry whose name equals `"ComicInfo.xml"` case-insensitively, exactly as [EmbeddedMetadataReader
     * .comicInfo] already selects for a ZIP container -- never a subdirectory match, never a merge of several
     * candidates. Returns `null` if no such entry exists, it is oversized, or it fails to parse -- metadata is
     * always best-effort, never blocking. Both [PublicationFiles] (import-time) and [RarPageSource] (reader-time)
     * call this directly -- there is no second ComicInfo lookup/parse anywhere else.
     */
    fun comicInfo(): EmbeddedMetadata? {
        check(!closed) { "RarContainer is closed" }
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
        throw PublicationException(e.error.toPublicationProblem()).apply { initCause(e) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: PublicationException) {
        throw e
    } catch (e: IOException) {
        throw PublicationException(PublicationProblem.UNREADABLE).apply { initCause(e) }
    } catch (e: SecurityException) {
        throw PublicationException(PublicationProblem.PERMISSION_LOST).apply { initCause(e) }
    }

    fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    companion object {
        private const val MAX_COMIC_INFO_BYTES = 1L * 1024 * 1024

        /**
         * Opens a [RarContainer] over an already-open [session] (encryption is already rejected wholesale by
         * `NativeRarSession.open`, before any [RarArchiveSession] reaches here -- see its "ANY entry encrypted"
         * doc, so [comicInfo]/[extractPage] never need their own per-entry encrypted check).
         *
         * ## Cache namespace / source-key model
         *
         * [sourceKey], when non-null, is the strongest safe source identity available to this checkpoint (see
         * `com.d4guilar.shelfos.domain.library.rarCacheSourceKey` for the production policy deciding when a
         * non-null key is actually safe to hand in -- Phase 3E-D R1A HIGH-2). It is hashed ([UUID.
         * nameUUIDFromBytes]) into a deterministic, filesystem-safe cache subdirectory name -- never used as a raw
         * path segment, so arbitrary caller content can never reach the filesystem unsanitized. When [sourceKey]
         * is null, this falls back to a fresh per-call random namespace: correctness over cache persistence --
         * a caller that cannot supply a reliable stable identity sacrifices cross-reopen cache reuse rather than
         * risk serving stale bytes under a reused key for content that may have changed. Either way,
         * [maxCacheBytes]/[maxCacheEntries] are not this one source's own private budget: [cacheRoot]/`cbr` is
         * shared, process-wide, global coordination/accounting domain spanning every namespace ever opened against
         * it -- see [RarCacheCoordinator]'s class doc for the full architecture, including why a reopen of the SAME
         * [sourceKey] can genuinely reuse an earlier materialization rather than merely claim to.
         */
        fun open(
            session: RarArchiveSession,
            cacheRoot: File,
            sourceKey: String?,
            maxCacheBytes: Long = RarCacheCoordinator.DEFAULT_MAX_BYTES,
            maxCacheEntries: Int = RarCacheCoordinator.DEFAULT_MAX_ENTRIES,
        ): RarContainer {
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
            return RarContainer(session, cache, index.pages, index.comicInfo)
        }

        private data class Index(val pages: List<RarPageEntry>, val comicInfo: RarPageEntry?)

        /** Mirrors [ArchivePolicy.pages]/[ArchivePolicy.entries] for a RAR-backed archive: reuses the exact same
         * format-independent safety/filtering/ordering primitives ([ArchivePolicy.safeName], [isPageImage],
         * [naturalCompare]) CBZ already uses, applied to [RarArchiveSession]'s physical entry model instead of
         * `SeekableZip.Entry`. The ONE place either caller's logical page/ComicInfo view is ever computed. */
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
