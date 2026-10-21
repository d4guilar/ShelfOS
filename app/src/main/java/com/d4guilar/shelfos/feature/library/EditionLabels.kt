// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import com.d4guilar.shelfos.data.metadata.openlibrary.CoverMatchKind
import com.d4guilar.shelfos.data.metadata.openlibrary.CoverWarning
import com.d4guilar.shelfos.domain.metadata.MatchConfidence
import com.d4guilar.shelfos.domain.metadata.MatchSignal

/**
 * What a provider candidate's identifier evidence lets the UI say. An ISBN match is evidence about the identifier only; a provider
 * record can carry that ISBN yet describe a different printing, so the strong wording is reserved for evidence with no contradiction.
 */
internal enum class IsbnLabel {
    /** The ISBN matches and nothing contradicts it. Never "exact edition": unreported publisher/year are simply unknown. */
    ISBN_MATCHES,

    /** The ISBN matches but other details conflict or confidence is below the top tier: the person must verify the edition. */
    ISBN_MATCHES_VERIFY,

    /** No identifier agreement. */
    NO_ISBN_MATCH,
}

/** Cover candidates: [CoverMatchKind.EXACT_EDITION] records the identifier agreed; the warnings decide how strongly to say so. */
internal fun coverIsbnLabel(match: CoverMatchKind, tier: MatchConfidence, warnings: Set<CoverWarning>): IsbnLabel = when {
    match != CoverMatchKind.EXACT_EDITION -> IsbnLabel.NO_ISBN_MATCH
    tier == MatchConfidence.VERY_HIGH && warnings.none { it != CoverWarning.NO_COVER_RECORDED } -> IsbnLabel.ISBN_MATCHES
    else -> IsbnLabel.ISBN_MATCHES_VERIFY
}

/** Metadata candidates, from the matcher's own evidence (read, never recomputed). */
internal fun metadataIsbnLabel(tier: MatchConfidence, agreements: Set<MatchSignal>, contradictions: Set<MatchSignal>, rejected: Boolean): IsbnLabel = when {
    rejected || MatchSignal.IDENTIFIER !in agreements -> IsbnLabel.NO_ISBN_MATCH
    tier == MatchConfidence.VERY_HIGH && contradictions.isEmpty() -> IsbnLabel.ISBN_MATCHES
    else -> IsbnLabel.ISBN_MATCHES_VERIFY
}
