// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations

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
 * What happens to a library item's annotations when the item is removed. Only [DELETE] exists in Phase 4A; a keep
 * policy needs the Notes hub to be able to show orphans (ADR-0025, plan section 7.3) and is deliberately absent.
 */
enum class KnowledgePolicy { DELETE }

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

/** Kind invariants, enforced at the repository boundary on every write. The schema itself stays permissive. */
object AnnotationRules {
    fun validate(draft: AnnotationDraft, validator: AnnotationLocatorValidator?) {
        validateContent(draft.kind, draft.locatorFormat, draft.selectedText, draft.body, draft.styleKey)
        if (draft.libraryItemId.isBlank()) fail("libraryItemId must not be blank")
        if (draft.locatorJson.isBlank()) fail("locatorJson must not be blank")
        if (draft.progressSnapshot !in 0..100) fail("progressSnapshot must be within 0..100")
        if (draft.orderKey != null && !draft.orderKey.isFinite()) fail("orderKey must be finite")
        if (validator != null && !validator.isValid(draft.locatorFormat, draft.locatorJson)) fail("malformed ${draft.locatorFormat} locator")
    }

    /** Content-only checks, shared by create and edit. */
    fun validateContent(kind: AnnotationKind, format: LocatorFormat, selectedText: String?, body: String?, styleKey: String?) {
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

    private fun fail(message: String): Nothing = throw InvalidAnnotationException(message)
}
