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
 * Phase 3E-D R1A (HIGH-2): the ONE production factory deciding whether a persistent RAR extraction-cache
 * namespace key is safe for this item, or whether the caller must fall back to an ephemeral/random namespace (see
 * `RarContainer.open`'s "cache namespace / source-key model" doc). Every CBR cache-namespace decision -- import,
 * reader, thumbnail -- MUST go through this function (or [LibraryItem.rarCacheSourceKey]) rather than deriving a
 * key independently, so no caller can make a divergent unsafe decision on its own.
 *
 * ## Why the old `"$id:$byteSize"` key was unsafe
 *
 * The original Phase 3E-D key additionally mixed in [LibraryItem.byteSize]. For a [managedPath] (ShelfOS-owned
 * private copy) that is genuinely safe -- the copy is written once at import and never mutated afterward (see
 * `PublicationFiles.copyToPrivateStorage`/`managedFile`), so its size truly cannot change under the same [id].
 * But for a source WITHOUT a [managedPath] (a referenced, durable-URI SAF source ShelfOS never owns), [byteSize]
 * is import-time-persisted metadata that is never refreshed on reopen -- Codex proved this is NOT revision-safe:
 * if the underlying provider content changes behind that URI (same size OR different size), the persisted
 * [byteSize] stays stale, so the old key could collide with an earlier materialization of DIFFERENT bytes,
 * most dangerously when the replacement happens to be the exact same size.
 *
 * ## Current policy
 *
 * [managedPath] is the only managed-vs-external distinction ShelfOS's data model actually has today (see
 * `PublicationDetails`'s "linked" vs "private copy" UI copy, which already surfaces this same split to the user).
 * - Non-null [managedPath]: this is a ShelfOS-owned immutable private copy. Its bytes cannot silently change
 *   under this [id] (see above), so a stable, persistent key derived from [id] alone is safe -- cross-reopen cache
 *   reuse is preserved. [byteSize] is deliberately NOT included: for a managed copy it adds no revision
 *   sensitivity (the copy is already immutable under [id]), and for consistency this factory never reads it.
 * - Null [managedPath] (a referenced external source): ShelfOS has no revision token for this source that is
 *   actually refreshed (re-queried from the current provider/file) at open time -- only the stale, persisted
 *   [LibraryItem.byteSize] from import, which is exactly the signal just shown to be unsafe. Per Codex's own
 *   recommendation, this returns `null`, which `RarContainer.open` treats as "give me a fresh ephemeral/random
 *   namespace" -- intentionally sacrificing cross-reopen cache reuse for this source in favor of correctness. The
 *   already-accepted [RarCacheCoordinator] guarantees this ephemeral payload stays globally bounded regardless,
 *   so this is architecturally safe, not a regression.
 *
 * [LibraryItem.title]/[LibraryItem.fileName] are NEVER consulted (both are mutable, user-editable display text,
 * never a content-revision signal). If ShelfOS later gains a genuinely-refreshed-at-open-time provider revision
 * signal (e.g. a currently-queried document id + lastModified + size, not a persisted stale copy) for external
 * sources, THIS is the one function that should start using it -- no other caller should independently invent
 * its own revision check.
 */
fun rarCacheSourceKey(id: String, managedPath: String?): String? = managedPath?.let { "managed:$id" }

fun LibraryItem.rarCacheSourceKey(): String? = rarCacheSourceKey(id, managedPath)

fun filterPublications(items: List<LibraryItem>, filter: LibraryFilter, query: String = "") = items.filter {
    (if (filter == LibraryFilter.FAVORITES) it.favorite else it.category == filter.category) &&
        (query.isBlank() || it.title.contains(query.trim(), true) || it.creator.contains(query.trim(), true))
}

fun readingDirection(category: MediaCategory, override: ReadingDirection?): ReadingDirection =
    override ?: if (category == MediaCategory.MANGA) ReadingDirection.RTL else ReadingDirection.LTR
