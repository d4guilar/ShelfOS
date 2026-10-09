// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.core.files.EmbeddedMetadata
import com.d4guilar.shelfos.core.files.RarArchiveSession
import com.d4guilar.shelfos.core.files.RarCacheCoordinator
import com.d4guilar.shelfos.core.files.RarContainer
import java.io.File
import java.io.InputStream

/**
 * Reader adapter used by `PublicationFormat.CBR` through `FixedReaderFactory`'s CBR route. It adapts the lower
 * [RarContainer] into the shared [PageSource] -> [ImagePageRenderer] image-sequence path; CBR has no separate
 * reader engine. Container inspection, ordering, safety policy, caching, and ComicInfo parsing stay in
 * [RarContainer], independently reusable by import code.
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
