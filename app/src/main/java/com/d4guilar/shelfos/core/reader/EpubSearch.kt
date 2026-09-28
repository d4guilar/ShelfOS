// SPDX-License-Identifier: MPL-2.0
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.d4guilar.shelfos.core.reader

import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.search.SearchIterator
import org.readium.r2.shared.publication.services.search.SearchService

/** ShelfOS-owned, serializable-by-value search data. The locator JSON remains the navigation authority. */
data class EpubSearchResult(
    val locator: String,
    val href: String,
    val title: String?,
    val progression: Double?,
    val before: String,
    val highlight: String,
    val after: String,
)

sealed interface EpubSearchRead {
    data class Page(val results: List<EpubSearchResult>) : EpubSearchRead
    data object Complete : EpubSearchRead
    data object Error : EpubSearchRead
}

/** Small ShelfOS boundary around Readium's session-bound, explicitly closeable search iterator. */
interface EpubSearchCursor : AutoCloseable {
    suspend fun next(): EpubSearchRead
}

/**
 * Uses the SearchService EpubParser already attaches to every opened EPUB. It does not register a service, build an
 * index or retain Readium objects in saved UI state. The returned cursor must be closed before this session closes.
 */
suspend fun EpubSession.search(query: String): EpubSearchCursor {
    val service = publication.findService(SearchService::class)
        ?: throw IllegalStateException("This EPUB does not provide publication search.")
    return ReadiumEpubSearchCursor(service.search(query))
}

private class ReadiumEpubSearchCursor(private val iterator: SearchIterator) : EpubSearchCursor {
    override suspend fun next(): EpubSearchRead {
        val read = iterator.next()
        if (read.failureOrNull() != null) return EpubSearchRead.Error
        val collection = read.getOrNull() ?: return EpubSearchRead.Complete
        return EpubSearchRead.Page(collection.locators.map(Locator::toShelfSearchResult))
    }

    override fun close() = iterator.close()
}

private fun Locator.toShelfSearchResult() = EpubSearchResult(
    locator = toJSON().toString(),
    href = href.toString(),
    title = title?.trim()?.takeIf(String::isNotEmpty),
    progression = locations.totalProgression ?: locations.progression,
    before = text.before.orEmpty(),
    highlight = text.highlight.orEmpty(),
    after = text.after.orEmpty(),
)

internal fun normalizeSearchQuery(query: String): String = normalizeSearchText(query)

internal fun normalizeSearchText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

internal fun searchResultSnippet(result: EpubSearchResult): String =
    normalizeSearchText(result.before + result.highlight + result.after)

internal fun searchResultAccessibilityText(result: EpubSearchResult): String = buildList {
    result.title?.let(::add)
    add(searchResultSnippet(result).ifBlank { "Match in this publication" })
    result.progression?.takeIf { it.isFinite() && it in 0.0..1.0 }
        ?.let { add("${(it * 100).toInt()} percent through book") }
}.joinToString(", ")
