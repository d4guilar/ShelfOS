// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata.openlibrary

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*

/**
 * Turns edition records into cover candidates (Phase 4.5C.2B). Edition identity is judged by the production [MetadataMatcher], not by
 * title similarity: an edition is [CoverMatchKind.EXACT_EDITION] only if its own record lists the requested ISBN-13 (and the matcher agrees
 * on the identifier); every other edition keeps `OTHER_EDITION` with its contradictions spelled out, and a hard-rejected one is dropped.
 * An edition without a recorded cover yields no candidate, except the exact edition, which is offered by OLID (a 404 then means "no artwork").
 */
object OpenLibraryCoverCandidates {
    const val MAX_CANDIDATES = OpenLibrary.COVER_EDITIONS_MAX

    fun build(query: MetadataQuery, isbn13: String, editions: List<CoverEdition>): List<OnlineCoverCandidate> {
        val out = ArrayList<OnlineCoverCandidate>()
        for (edition in editions) {
            val candidate = MetadataCandidate(OpenLibrary.ID, edition.ref, edition.title, null, MediaCategory.BOOK,
                MetadataValues(publisher = edition.publisher, publishedDate = edition.publishedDate, language = edition.language, identifiers = edition.ids))
            val evidence = MetadataMatcher.score(query, candidate)
            if (evidence.rejected) continue
            val exact = edition.exact && MatchSignal.IDENTIFIER in evidence.agreements
            val warnings = LinkedHashSet<CoverWarning>()
            if (!exact) warnings += CoverWarning.DIFFERENT_ISBN
            if (MatchSignal.LANGUAGE in evidence.contradictions) warnings += CoverWarning.DIFFERENT_LANGUAGE
            if (MatchSignal.PUBLISHER in evidence.contradictions) warnings += CoverWarning.DIFFERENT_PUBLISHER
            if (evidence.tier != MatchConfidence.VERY_HIGH) warnings += CoverWarning.LOW_CONFIDENCE
            val refs: List<OpenLibraryCoverRef> = when {
                edition.coverIds.isNotEmpty() -> edition.coverIds.map { OpenLibraryCoverRef.Id(it) }.let { if (exact) it else it.take(1) }
                exact -> { warnings += CoverWarning.NO_COVER_RECORDED; listOf(OpenLibraryCoverRef.Olid(edition.ref)) }
                else -> emptyList()
            }
            for (ref in refs) out += OnlineCoverCandidate(OpenLibrary.ID, edition.ref, ref, edition.ids.firstOrNull { it.isbn13 != null }?.isbn13, edition.title, null,
                edition.publisher, edition.publishedDate?.take(4)?.toIntOrNull(), edition.language,
                if (exact) CoverMatchKind.EXACT_EDITION else CoverMatchKind.OTHER_EDITION, evidence.tier, warnings)
        }
        return out.distinctBy { it.key }.take(MAX_CANDIDATES)
    }
}
