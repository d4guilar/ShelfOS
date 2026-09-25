// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.importing

import com.d4guilar.shelfos.domain.library.*

/** An inspected source awaiting review; nothing is committed to the library yet. */
data class PreparedImport(val item: LibraryItem, val acquiredGrant: Boolean)

/** Platform access used by single-file import. Implementations never modify the source. */
interface PublicationImporter {
    /** The prepared item's source is [sourceUri]. */
    suspend fun prepare(sourceUri: String, copy: Boolean, stage: (String) -> Unit): PreparedImport
    /**
     * Discards abandoned work: its private copy, and its grant unless [sourceStillUsed] (a library item or another
     * import still depends on the source). Never called for work the library holds.
     */
    fun discard(prepared: PreparedImport, sourceStillUsed: Boolean)
}

/**
 * Work the library already references is committed, even if its save reported a failure after writing: only the
 * library may release it, and import cleanup must leave its private copy and grant alone.
 */
fun isCommitted(library: List<LibraryItem>, prepared: PreparedImport): Boolean {
    val copy = prepared.item.managedPath?.substringAfterLast('/')
    return library.any { it.id == prepared.item.id || (copy != null && it.managedPath?.substringAfterLast('/') == copy) }
}

/**
 * Classifies a ZIP container from entry names and its optional `mimetype` content.
 * EPUB evidence always wins over images, so an EPUB is never mistaken for a CBZ.
 */
fun classifyArchive(names: Collection<String>, mimetype: String?): PublicationFormat {
    val container = "META-INF/container.xml" in names
    if (mimetype?.trim() == "application/epub+zip" || container) {
        if ("META-INF/rights.xml" in names || names.any { it.endsWith("license.lcpl", true) })
            throw PublicationException(PublicationProblem.PROTECTED)
        if (!container) throw PublicationException(PublicationProblem.CORRUPT, "This EPUB has no publication container.")
        return PublicationFormat.EPUB
    }
    if (names.none(::isPageImage)) throw PublicationException(PublicationProblem.EMPTY_ARCHIVE)
    return PublicationFormat.CBZ
}

private val pageImageTypes = setOf("jpg", "jpeg", "png", "webp")

/** Image entries that count as pages; platform metadata folders and hidden files are ignored. */
fun isPageImage(name: String) = name.substringAfterLast('.').lowercase() in pageImageTypes &&
    !name.startsWith("__MACOSX/") && !name.substringAfterLast('/').startsWith('.')

/** Automatic classification is only a suggestion; users correct it during review. */
fun suggestedCategory(format: PublicationFormat, rightToLeftManga: Boolean = false) = when {
    format == PublicationFormat.CBZ && rightToLeftManga -> MediaCategory.MANGA
    format == PublicationFormat.CBZ -> MediaCategory.COMIC
    else -> MediaCategory.BOOK
}

/** Exact repeated source references are idempotent: the existing item is reused. */
fun existingSource(library: List<LibraryItem>, sourceUri: String): LibraryItem? = library.find { it.sourceUri == sourceUri }

/** Startup maintenance: grants no item references are released; referenced items without access become unavailable. */
data class SourceMaintenance(val releaseGrants: Set<String>, val unavailableItems: Set<String>)

fun planSourceMaintenance(items: List<LibraryItem>, readGrants: Set<String>): SourceMaintenance {
    val referenced = items.mapTo(HashSet()) { it.sourceUri }
    // Items without a private copy need a persisted grant for content:// documents; missing access never deletes them.
    val unavailable = items.filter { it.managedPath == null && it.sourceUri.startsWith("content:") && it.sourceUri !in readGrants }
    return SourceMaintenance(readGrants - referenced, unavailable.mapTo(HashSet()) { it.id })
}

/** Similar name and size is only a warning; distinct sources are never merged automatically. */
fun isPossibleDuplicate(library: List<LibraryItem>, candidate: LibraryItem) = library.any {
    it.sourceUri != candidate.sourceUri && it.fileName.equals(candidate.fileName, ignoreCase = true) &&
        it.byteSize != null && it.byteSize == candidate.byteSize
}
