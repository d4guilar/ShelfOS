// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat

/** What an annotation is. A kind never changes implicitly: adding a body to a [HIGHLIGHT] keeps it a highlight. */
enum class AnnotationKind { BOOKMARK, HIGHLIGHT, NOTE }

/** Versioned token describing how [Annotation.locatorJson] is interpreted. Persisted by name. */
enum class LocatorFormat {
    /** A serialized Readium `Locator` (EPUB). Parsed only behind `core.reader`; domain and data never parse it. */
    READIUM_LOCATOR_1,
    /** `{"version":1,"page":N}`, N a 0-based source-page index (PDF, CBZ, CBR). See [FixedPageLocator]. */
    FIXED_PAGE_1,
}

/**
 * What happens to a library item's annotations when the item is removed. The choice is always explicit, never implicit.
 * [DELETE] removes them with the item. [KEEP] (Phase 4D, plan section 7.3) retains them as orphans under a publication
 * snapshot (display title, creator, category and format only) that the Notes hub presents; orphans are never reattached.
 */
enum class KnowledgePolicy { DELETE, KEEP }

/**
 * One saved reading place, highlight or note. [locatorJson] is the sole authority on where it points; [progressSnapshot]
 * (0..100) and [orderKey] are display/sort conveniences and are never used to navigate. No Room or Readium types here.
 */
data class Annotation(
    val id: String, val libraryItemId: String, val kind: AnnotationKind, val locatorFormat: LocatorFormat,
    val locatorJson: String, val progressSnapshot: Int, val orderKey: Double?,
    val title: String?, val selectedText: String?, val body: String?, val styleKey: String?,
    val createdAt: Long, val updatedAt: Long,
)

/** The content of an annotation about to be created; the repository assigns id and timestamps. */
data class AnnotationDraft(
    val libraryItemId: String, val kind: AnnotationKind, val locatorFormat: LocatorFormat, val locatorJson: String,
    val progressSnapshot: Int, val orderKey: Double? = null,
    val title: String? = null, val selectedText: String? = null, val body: String? = null, val styleKey: String? = null,
)

/** Replaces the four editable content fields wholesale. Kind, locator, item and creation time are never editable. */
data class AnnotationEdit(val title: String?, val selectedText: String?, val body: String?, val styleKey: String?)

/** Per-publication counts of canonical annotation rows. [other] counts rows whose persisted kind this build does not know. */
data class AnnotationCounts(val bookmarks: Int = 0, val highlights: Int = 0, val notes: Int = 0, val other: Int = 0) {
    val total: Int get() = bookmarks + highlights + notes + other
    companion object { val None = AnnotationCounts() }
}

/** Annotations of a read, plus how many persisted rows were skipped because their kind or locator format is unknown. */
data class AnnotationListing(val annotations: List<Annotation>, val skippedUnknown: Int = 0)

/** Deterministic orderings (see the DAO for the exact SQL). */
enum class AnnotationOrder {
    /** `COALESCE(orderKey, progressSnapshot / 100.0) ASC, createdAt ASC, id ASC`. */
    READING,
    /** The legacy bookmark order: `progressSnapshot ASC, createdAt ASC, id ASC`. */
    BOOKMARK_COMPAT,
}

class InvalidAnnotationException(message: String) : IllegalArgumentException(message)

/** Validates a locator string for a format. Implementations must not mutate or normalize the string. */
fun interface AnnotationLocatorValidator { fun isValid(format: LocatorFormat, locatorJson: String): Boolean }

/**
 * Fixed-page strictness is owned here (pure Kotlin); Readium locator parsing is injected from `core.reader`
 * so the domain stays free of Readium types (ADR-0003).
 */
class DefaultAnnotationLocatorValidator(private val readiumLocator: (String) -> Boolean) : AnnotationLocatorValidator {
    override fun isValid(format: LocatorFormat, locatorJson: String) = when (format) {
        LocatorFormat.FIXED_PAGE_1 -> FixedPageLocator.parse(locatorJson) != null
        LocatorFormat.READIUM_LOCATOR_1 -> readiumLocator(locatorJson)
    }
}

/** Size caps for annotation content (Phase 4B, plan section 6.1). Text is counted in UTF-16 code units. */
object AnnotationLimits {
    /** A serialized locator larger than this (UTF-8 bytes) is rejected, never truncated. */
    const val MAX_LOCATOR_BYTES = 64 * 1024
    /** Normalized selected-text copy; produced by cutting on a code-point boundary, so stored text never exceeds it. */
    const val MAX_SELECTED_TEXT = 2_000
    /** User-authored body (note text, optional highlight body). Rejected, never silently truncated. */
    const val MAX_BODY = 20_000
}

/** Kind invariants, enforced at the repository boundary on every write. The schema itself stays permissive. */
object AnnotationRules {
    fun validate(draft: AnnotationDraft, validator: AnnotationLocatorValidator?) {
        validateContent(draft.kind, draft.locatorFormat, draft.selectedText, draft.body, draft.styleKey)
        if (draft.libraryItemId.isBlank()) fail("libraryItemId must not be blank")
        if (draft.locatorJson.isBlank()) fail("locatorJson must not be blank")
        if (utf8Length(draft.locatorJson) > AnnotationLimits.MAX_LOCATOR_BYTES) fail("locatorJson exceeds ${AnnotationLimits.MAX_LOCATOR_BYTES} bytes")
        if (draft.progressSnapshot !in 0..100) fail("progressSnapshot must be within 0..100")
        if (draft.orderKey != null && !draft.orderKey.isFinite()) fail("orderKey must be finite")
        if (validator != null && !validator.isValid(draft.locatorFormat, draft.locatorJson)) fail("malformed ${draft.locatorFormat} locator")
    }

    /** Content-only checks, shared by create and edit. */
    fun validateContent(kind: AnnotationKind, format: LocatorFormat, selectedText: String?, body: String?, styleKey: String?) {
        if (selectedText != null && selectedText.length > AnnotationLimits.MAX_SELECTED_TEXT) fail("selectedText exceeds ${AnnotationLimits.MAX_SELECTED_TEXT} characters")
        if (body != null && body.length > AnnotationLimits.MAX_BODY) fail("body exceeds ${AnnotationLimits.MAX_BODY} characters")
        when (kind) {
            AnnotationKind.BOOKMARK -> if (selectedText != null || body != null || styleKey != null)
                fail("a BOOKMARK carries no selectedText, body or styleKey")
            AnnotationKind.HIGHLIGHT -> {
                if (format != LocatorFormat.READIUM_LOCATOR_1) fail("a HIGHLIGHT requires READIUM_LOCATOR_1")
                if (styleKey.isNullOrBlank()) fail("a HIGHLIGHT requires a styleKey")
            }
            AnnotationKind.NOTE -> {
                if (body.isNullOrBlank()) fail("a NOTE requires a non-blank body")
                if (format == LocatorFormat.FIXED_PAGE_1 && selectedText != null) fail("a fixed-page NOTE carries no selectedText")
            }
        }
    }

    private fun utf8Length(text: String): Int {
        var bytes = 0; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code < 0x80 -> bytes += 1
                c.code < 0x800 -> bytes += 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { bytes += 4; i++ }
                else -> bytes += 3
            }
            i++
        }
        return bytes
    }

    private fun fail(message: String): Nothing = throw InvalidAnnotationException(message)
}

/** Ordering of the global Notes list. */
enum class NotesSort {
    /** Grouped by publication (title, then item id), each group in reading order: `COALESCE(orderKey, progressSnapshot / 100.0), createdAt, id`. */
    PUBLICATION,
    /** Newest first: `createdAt DESC, id DESC`. */
    DATE,
}

/** A global Notes read. [kinds] null means every kind; [text] matches literally (case-insensitive for ASCII); blank means no text filter. */
data class NotesQuery(
    val kinds: Set<AnnotationKind>? = null, val publicationId: String? = null, val text: String = "", val sort: NotesSort = NotesSort.PUBLICATION,
)

/**
 * Where a retained annotation came from. A [live] source is a current library item; otherwise the annotation is an orphan
 * whose [title]/[creator] come from the removal snapshot (null when no snapshot exists). Never carries a URI, path or
 * any way to reach the original file.
 */
data class AnnotationSource(
    val libraryItemId: String, val title: String?, val creator: String?, val category: MediaCategory?, val format: PublicationFormat?,
    val live: Boolean, val available: Boolean,
)

/** An annotation with its publication context, as shown by the global Notes list. */
data class AnnotationWithSource(val annotation: Annotation, val source: AnnotationSource)

/** Opaque keyset position of a row; only the repository interprets it. */
data class NotesCursor(val sortTitle: String, val libraryItemId: String, val order: Double, val createdAt: Long, val id: String)

/** One bounded page of the global Notes list; [next] is non-null exactly when more rows follow. */
data class NotesPage(val rows: List<AnnotationWithSource>, val next: NotesCursor?)

/** A publication (live or removed) that owns at least one annotation, for the publication filter and group headers. */
data class NotesPublication(val source: AnnotationSource, val total: Int)
