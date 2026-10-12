// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.annotations

import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.library.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The EPUB bookmark UI's view of canonical BOOKMARK annotations (it replaces the retired `BookmarkRepository` adapter).
 * Bookmark.id/itemId/locator/progress/label/createdAt map to Annotation.id/libraryItemId/locatorJson/progressSnapshot/
 * title/createdAt. Order stays `progress, createdAt, id` ([AnnotationOrder.BOOKMARK_COMPAT]).
 */
fun AnnotationRepository.observeEpubBookmarks(itemId: String): Flow<List<Bookmark>> =
    observeForPublication(itemId, setOf(AnnotationKind.BOOKMARK), AnnotationOrder.BOOKMARK_COMPAT)
        .map { listing -> listing.annotations.map { Bookmark(it.id, it.libraryItemId, it.locatorJson, it.progressSnapshot, it.title, it.createdAt) } }

/**
 * Adds an EPUB bookmark. `validateLocator = false` keeps the legacy contract: the locator string is stored opaquely (a
 * malformed one is shown as an unopenable bookmark that can still be deleted). The same item and exact same locator
 * string is a safe no-op (the repository dedupe rule).
 */
suspend fun AnnotationRepository.addEpubBookmark(itemId: String, locator: String, progress: Int, label: String? = null) {
    create(AnnotationDraft(itemId, AnnotationKind.BOOKMARK, LocatorFormat.READIUM_LOCATOR_1, locator, progress.coerceIn(0, 100), title = label), validateLocator = false)
}
