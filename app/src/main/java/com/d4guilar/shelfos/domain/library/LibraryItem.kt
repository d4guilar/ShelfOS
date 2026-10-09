// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

enum class PublicationFormat { PDF, EPUB, CBZ, CBR }
enum class ReadingDirection { LTR, RTL }
// Display labels are a UI concern (see AGENTS.md's localization rules) and live in feature.library's
// labelRes()/singularRes() extensions, resolved through Android string resources, not here.
enum class MediaCategory { BOOK, COMIC, MANGA, DOCUMENT }
enum class LibraryFilter(val category: MediaCategory?) {
    FAVORITES(null), BOOKS(MediaCategory.BOOK), COMICS(MediaCategory.COMIC),
    MANGA(MediaCategory.MANGA), DOCUMENTS(MediaCategory.DOCUMENT)
}

data class LibraryItem(
    val id: String, val title: String, val creator: String = "", val category: MediaCategory,
    val sourceUri: String, val format: PublicationFormat, val fileName: String, val byteSize: Long?,
    val favorite: Boolean = false, val addedAt: Long = System.currentTimeMillis(),
    val available: Boolean = true, val managedPath: String? = null,
    val titleOrigin: String = "filename", val creatorOrigin: String = "unknown",
    val progress: Int = 0, val lastRead: Long = 0, val locator: String? = null,
    val preferences: String = "{}",
) {
    val coverColor: Long get() = listOf(0xFFAD6758L, 0xFF304856L, 0xFF426455L, 0xFF72516DL)[(id.hashCode() and Int.MAX_VALUE) % 4]
    val coverMotif: Int get() = (id.hashCode() and Int.MAX_VALUE) % 3
}

/**
 * Phase 3E-D: a stable, revision-sensitive cache-namespace key for the RAR extraction cache (see
 * `RarPageSource.open`'s "cache namespace / source-key model" doc). Derived only from [id] (this item's own
 * stable UUID, never its mutable, user-editable [LibraryItem.title]/[LibraryItem.fileName]) and [byteSize] (the
 * one revision-sensitive signal already persisted for a publication): a reopen of the SAME source with an
 * unchanged byte size reuses earlier materializations, while a changed byte size produces a different key rather
 * than risk serving stale bytes. ShelfOS has no finer-grained content-revision signal (e.g. a provider
 * last-modified timestamp) persisted today; a `null` [byteSize] folds to a fixed sentinel so two items that
 * somehow both lack a known size still don't collide with each other's actual content (their differing [id]
 * already prevents that on its own).
 */
fun rarCacheSourceKey(id: String, byteSize: Long?): String = "$id:${byteSize ?: -1}"

fun LibraryItem.rarCacheSourceKey(): String = rarCacheSourceKey(id, byteSize)

fun filterPublications(items: List<LibraryItem>, filter: LibraryFilter, query: String = "") = items.filter {
    (if (filter == LibraryFilter.FAVORITES) it.favorite else it.category == filter.category) &&
        (query.isBlank() || it.title.contains(query.trim(), true) || it.creator.contains(query.trim(), true))
}

fun readingDirection(category: MediaCategory, override: ReadingDirection?): ReadingDirection =
    override ?: if (category == MediaCategory.MANGA) ReadingDirection.RTL else ReadingDirection.LTR
