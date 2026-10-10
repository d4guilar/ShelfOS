// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.annotations

import com.d4guilar.shelfos.core.database.AnnotationDao
import com.d4guilar.shelfos.core.database.AnnotationEntity
import com.d4guilar.shelfos.core.database.AnnotationKindCount
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * ShelfOS-owned boundary over the canonical annotation table. Features never touch DAOs. Domain and data treat
 * locators as opaque strings; only [AnnotationLocatorValidator] (backed by `core.reader` for Readium) inspects them.
 *
 * Integrity that used to come from a foreign key lives here: [create] requires the library item to exist (checked in
 * the same transaction as the insert), and library removal deletes annotations explicitly under [KnowledgePolicy.DELETE].
 */
interface AnnotationRepository {
    /**
     * Validates kind invariants and, unless [validateLocator] is false, the locator for its format; then inserts. A
     * BOOKMARK at the exact locator string the item already has returns the existing annotation (no second row).
     * [validateLocator] = false exists only for the legacy `BookmarkRepository` adapter, whose contract has always
     * accepted opaque locator strings (a malformed one is shown as an unopenable bookmark that can still be deleted).
     * @throws InvalidAnnotationException on a violated invariant; IllegalArgumentException for an unknown library item.
     */
    suspend fun create(draft: AnnotationDraft, validateLocator: Boolean = true): Annotation
    /** Null when absent, or when the persisted kind/format is unknown to this build. */
    suspend fun get(id: String): Annotation?
    /** Replaces title/selectedText/body/styleKey after re-checking the kind invariants. Returns null if [id] is absent. */
    suspend fun update(id: String, edit: AnnotationEdit): Annotation?
    /** Deletes one annotation of any kind (or only of [kind], when given). Returns whether a row was deleted. */
    suspend fun delete(id: String, kind: AnnotationKind? = null): Boolean
    /** Deletes every annotation of a publication; only [KnowledgePolicy.DELETE] exists. Returns the number deleted. */
    suspend fun deleteForPublication(libraryItemId: String, policy: KnowledgePolicy): Int
    /** One publication's annotations, optionally limited to [kinds], in a deterministic [order]. Rows with an unknown kind or format are skipped and counted, never reinterpreted. */
    fun observeForPublication(libraryItemId: String, kinds: Set<AnnotationKind>? = null, order: AnnotationOrder = AnnotationOrder.READING): Flow<AnnotationListing>
    /** Every annotation, newest first (`createdAt DESC, id DESC`), for later Notes work. */
    fun observeRecent(): Flow<AnnotationListing>
    /** Canonical annotation rows per kind for one publication (what removal will delete). */
    fun counts(libraryItemId: String): Flow<AnnotationCounts>
    /** Integrity check: annotations whose library item is missing. Must be 0 under [KnowledgePolicy.DELETE]. */
    suspend fun countOrphans(): Int
}

class RoomAnnotationRepository(
    private val dao: AnnotationDao,
    private val validator: AnnotationLocatorValidator,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AnnotationRepository {
    override suspend fun create(draft: AnnotationDraft, validateLocator: Boolean): Annotation {
        AnnotationRules.validate(draft, validator.takeIf { validateLocator })
        val now = clock()
        val saved = dao.create(AnnotationEntity(newId(), draft.libraryItemId, draft.kind.name, draft.locatorFormat.name,
            draft.locatorJson, draft.progressSnapshot, draft.orderKey, draft.title, draft.selectedText, draft.body,
            draft.styleKey, now, now))
        return requireNotNull(saved.toDomainOrNull()) { "created annotation is not representable" }
    }

    override suspend fun get(id: String) = dao.byId(id)?.toDomainOrNull()

    override suspend fun update(id: String, edit: AnnotationEdit): Annotation? {
        val updated = dao.edit(id) { current ->
            // An unknown persisted kind/format can never be edited into something else: refuse rather than guess.
            val kind = requireNotNull(current.kind.toKind()) { "Unknown annotation kind: ${current.kind}" }
            val format = requireNotNull(current.locatorFormat.toFormat()) { "Unknown locator format: ${current.locatorFormat}" }
            AnnotationRules.validateContent(kind, format, edit.selectedText, edit.body, edit.styleKey)
            current.copy(title = edit.title, selectedText = edit.selectedText, body = edit.body, styleKey = edit.styleKey, updatedAt = maxOf(clock(), current.updatedAt))
        }
        return updated?.toDomainOrNull()
    }

    override suspend fun delete(id: String, kind: AnnotationKind?) = dao.delete(id, if (kind == null) 1 else 0, kind?.name.orEmpty()) > 0

    override suspend fun deleteForPublication(libraryItemId: String, policy: KnowledgePolicy) = when (policy) {
        KnowledgePolicy.DELETE -> dao.deleteForItem(libraryItemId)
    }

    override fun observeForPublication(libraryItemId: String, kinds: Set<AnnotationKind>?, order: AnnotationOrder): Flow<AnnotationListing> {
        val all = if (kinds == null) 1 else 0
        val names = kinds.orEmpty().map { it.name }
        val rows = when (order) {
            AnnotationOrder.READING -> dao.observeReadingOrder(libraryItemId, all, names)
            AnnotationOrder.BOOKMARK_COMPAT -> dao.observeBookmarkOrder(libraryItemId, all, names)
        }
        return rows.map(::listing)
    }

    override fun observeRecent() = dao.observeRecent().map(::listing)

    override fun counts(libraryItemId: String) = dao.observeKindCounts(libraryItemId).map(::counts)
    override suspend fun countOrphans() = dao.countOrphans()

    private fun listing(rows: List<AnnotationEntity>): AnnotationListing {
        val known = rows.mapNotNull { it.toDomainOrNull() }
        return AnnotationListing(known, rows.size - known.size)
    }

    private fun counts(rows: List<AnnotationKindCount>): AnnotationCounts {
        var bookmarks = 0; var highlights = 0; var notes = 0; var other = 0
        for (row in rows) when (row.kind.toKind()) {
            AnnotationKind.BOOKMARK -> bookmarks += row.total
            AnnotationKind.HIGHLIGHT -> highlights += row.total
            AnnotationKind.NOTE -> notes += row.total
            null -> other += row.total
        }
        return AnnotationCounts(bookmarks, highlights, notes, other)
    }
}

private fun String.toKind() = AnnotationKind.entries.firstOrNull { it.name == this }
private fun String.toFormat() = LocatorFormat.entries.firstOrNull { it.name == this }

/** Null for an unknown persisted kind or locator format: callers skip and report, never coerce. */
private fun AnnotationEntity.toDomainOrNull(): Annotation? {
    val k = kind.toKind() ?: return null
    val f = locatorFormat.toFormat() ?: return null
    return Annotation(id, libraryItemId, k, f, locatorJson, progressSnapshot, orderKey, title, selectedText, body, styleKey, createdAt, updatedAt)
}
