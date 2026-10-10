// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.annotations

import com.d4guilar.shelfos.data.library.BookmarkRepository
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.library.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Compatibility adapter: the pre-Phase-4 [BookmarkRepository] contract over the canonical annotation table (kind
 * BOOKMARK). Bookmark.id/itemId/locator/progress/label/createdAt map to Annotation.id/libraryItemId/locatorJson/
 * progressSnapshot/title/createdAt. Bookmarks are EPUB-only today, so new rows are READIUM_LOCATOR_1; the legacy
 * `bookmark` table is never read or written. Ordering stays `progress, createdAt, id` and the duplicate rule stays
 * "same item + exact same locator string" (both inherited from the repository). Retired once its last caller moves.
 */
class RoomBookmarkRepository(private val annotations: AnnotationRepository) : BookmarkRepository {
    override fun bookmarks(itemId: String): Flow<List<Bookmark>> =
        annotations.observeForPublication(itemId, setOf(AnnotationKind.BOOKMARK), AnnotationOrder.BOOKMARK_COMPAT)
            .map { listing -> listing.annotations.map { Bookmark(it.id, it.libraryItemId, it.locatorJson, it.progressSnapshot, it.title, it.createdAt) } }

    // validateLocator = false keeps the legacy contract: the string is stored opaquely (see AnnotationRepository.create).
    override suspend fun addBookmark(itemId: String, locator: String, progress: Int, label: String?) {
        annotations.create(AnnotationDraft(itemId, AnnotationKind.BOOKMARK, LocatorFormat.READIUM_LOCATOR_1, locator,
            progress.coerceIn(0, 100), title = label), validateLocator = false)
    }

    override suspend fun deleteBookmark(id: String) { annotations.delete(id, AnnotationKind.BOOKMARK) }
}
