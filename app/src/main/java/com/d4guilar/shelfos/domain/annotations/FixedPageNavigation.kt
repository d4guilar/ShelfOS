// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations

/**
 * Whether a stored fixed-page annotation may move the reader. Deliberately strict and separate from the reader's
 * resume restoration (which clamps): an annotation whose page no longer exists is never clamped to the last page.
 */
sealed interface FixedPageNavigation {
    /** The locator is not a valid `{"version":1,"page":N}`. */
    data object Malformed : FixedPageNavigation
    /** A valid locator whose page is not within the open publication ([page] is 0-based). */
    data class OutOfRange(val page: Int, val pageCount: Int) : FixedPageNavigation
    /** The publication's page count is not known yet, so nothing can be confirmed. */
    data object Pending : FixedPageNavigation
    /** [page] is a real 0-based source page of the open publication. */
    data class Navigable(val page: Int) : FixedPageNavigation
}

/** Pure decision for [locatorJson] against the open publication's [pageCount] (0 while still unknown). Never clamps. */
fun fixedPageNavigation(locatorJson: String, pageCount: Int): FixedPageNavigation {
    val page = FixedPageLocator.parse(locatorJson) ?: return FixedPageNavigation.Malformed
    return when {
        pageCount <= 0 -> FixedPageNavigation.Pending
        page >= pageCount -> FixedPageNavigation.OutOfRange(page, pageCount)
        else -> FixedPageNavigation.Navigable(page)
    }
}

/** `page / pageCount` on the repository's normalized 0..1 reading-order scale; never used to navigate. */
fun fixedPageOrderKey(page: Int, pageCount: Int): Double =
    if (pageCount <= 0) 0.0 else (page.toDouble() / pageCount).coerceIn(0.0, 1.0)

/** A fixed-page BOOKMARK or NOTE draft for 0-based [page]; the locator is always the canonical [FixedPageLocator.format]. */
fun fixedPageDraft(libraryItemId: String, kind: AnnotationKind, page: Int, pageCount: Int, progressSnapshot: Int, body: String? = null) =
    AnnotationDraft(libraryItemId, kind, LocatorFormat.FIXED_PAGE_1, FixedPageLocator.format(page), progressSnapshot,
        fixedPageOrderKey(page, pageCount), body = body)

/** Ids of the fixed-page bookmarks anchored exactly at [page]; empty means the toggle should create one. */
fun fixedBookmarkIdsAt(annotations: List<Annotation>, page: Int): List<String> =
    annotations.filter { it.kind == AnnotationKind.BOOKMARK && it.locatorFormat == LocatorFormat.FIXED_PAGE_1 && FixedPageLocator.parse(it.locatorJson) == page }
        .map { it.id }

/** The set of 0-based pages carrying a fixed-page bookmark. */
fun fixedBookmarkedPages(annotations: List<Annotation>): Set<Int> =
    annotations.mapNotNullTo(HashSet()) { a ->
        if (a.kind == AnnotationKind.BOOKMARK && a.locatorFormat == LocatorFormat.FIXED_PAGE_1) FixedPageLocator.parse(a.locatorJson) else null
    }
