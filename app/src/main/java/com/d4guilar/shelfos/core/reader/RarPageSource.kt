// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.core.files.EmbeddedMetadata
import com.d4guilar.shelfos.core.files.RarArchiveSession
import com.d4guilar.shelfos.core.files.RarCacheCoordinator
import com.d4guilar.shelfos.core.files.RarContainer
import java.io.File
import java.io.InputStream

/**
 * Phase 3E-C container adapter, narrowed to a thin reader-only shape by the Phase 3E-D R1A layering fix: the only
 * production bridge between the accepted Phase 3E-B native RAR engine (wrapped behind [RarArchiveSession] for
 * testability) and the existing [PageSource] contract `ZipPageSource` already satisfies for CBZ. Architecture
 * goal, exactly as `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3E-C record states it: **RAR native engine ->
 * [RarContainer] -> [RarPageSource] -> [PageSource] -> [ImagePageRenderer]** -- the identical shape `ZipPageSource`
 * already has, so [ImagePageRenderer] (bounds-then-sample decode, [FixedReader.pageGeometry] support) is reused
 * completely unchanged.
 *
 * ## What moved to [RarContainer] (Phase 3E-D R1A HIGH-1)
 *
 * This class used to own entry enumeration/safety/filtering/ordering/ComicInfo-identification directly (and
 * `core.files`' `PublicationFiles` imported this reader-layer class for import-time inspection, creating a real
 * `core.files -> core.reader -> core.files` layering inversion Codex found). ALL of that format-independent
 * container knowledge now lives in [RarContainer] (`core.files`), which both this class and `PublicationFiles`
 * depend on independently -- neither of those two ever depends on the other. This class now owns only the
 * reader-specific [PageSource] contract surface ([pageCount]/[openPage]) and nothing else; it holds no entry
 * index, no safety policy and no ComicInfo parsing of its own -- every one of those questions is delegated to
 * [container].
 */
internal class RarPageSource private constructor(private val container: RarContainer) : PageSource {

    override val pageCount: Int get() = container.pageCount

    override fun openPage(index: Int): InputStream = container.extractPage(index).open()

    /** Delegates to [RarContainer.comicInfo] -- see its doc. Kept as a method on this class (rather than requiring
     * callers to reach into [container] themselves) only so existing call sites (and tests) that read a CBR's
     * embedded metadata through a [RarPageSource] instance keep working unchanged. */
    fun comicInfo(): EmbeddedMetadata? = container.comicInfo()

    override fun close() = container.close()

    companion object {
        /** See [RarContainer.open] for the full cache-namespace/source-key model this simply forwards to. */
        fun open(
            session: RarArchiveSession,
            cacheRoot: File,
            sourceKey: String?,
            maxCacheBytes: Long = RarCacheCoordinator.DEFAULT_MAX_BYTES,
            maxCacheEntries: Int = RarCacheCoordinator.DEFAULT_MAX_ENTRIES,
        ): RarPageSource = RarPageSource(RarContainer.open(session, cacheRoot, sourceKey, maxCacheBytes, maxCacheEntries))
    }
}
