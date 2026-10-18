// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory

/** Artwork the item already has; D7 and the USER rule depend on it. */
data class ExistingCovers(val user: Boolean = false, val local: Boolean = false)

enum class CoverEligibility { AUTOMATIC, CANDIDATE_ONLY, REJECTED }

/**
 * Whether an online cover reference could ever be applied without asking (pure policy, 4.5D.0; nothing downloads or
 * writes here). Never over a USER cover; never over an existing LOCAL cover of a Comic or Manga (D7); the cover must
 * come from the very edition that was matched; and that edition must be VERY_HIGH with an exact identifier, no
 * contradiction and, for sequential media, an agreeing volume or issue. A series-level image (an edition without a
 * volume) or a cover labelled for another volume stays a candidate or is rejected.
 */
object CoverEligibilityPolicy {
    fun assess(query: MetadataQuery, edition: RankedEdition, cover: CoverCandidate, existing: ExistingCovers): CoverEligibility {
        if (cover !in edition.cluster.covers) return CoverEligibility.REJECTED
        val e = edition.evidence
        if (e.rejected || e.contradictions.isNotEmpty()) return CoverEligibility.REJECTED
        if (existing.user) return CoverEligibility.CANDIDATE_ONLY
        val sequential = query.category == MediaCategory.COMIC || query.category == MediaCategory.MANGA
        if (cover.editionLabel != null && query.volume != null && labelNamesAnotherVolume(cover.editionLabel, query.volume)) return CoverEligibility.REJECTED
        val specific = e.tier == MatchConfidence.VERY_HIGH && MatchSignal.IDENTIFIER in e.agreements &&
            (!sequential || MatchSignal.VOLUME in e.agreements || MatchSignal.ISSUE in e.agreements)
        if (!specific) return CoverEligibility.CANDIDATE_ONLY
        if (sequential && existing.local) return CoverEligibility.CANDIDATE_ONLY
        return CoverEligibility.AUTOMATIC
    }

    private val number = Regex("""\p{Nd}+(?:[.,]\p{Nd}+)?""")
    private fun labelNamesAnotherVolume(label: String, volume: String) =
        number.findAll(label).map { normalizeSequence(it.value) }.toList().let { it.isNotEmpty() && normalizeSequence(volume) !in it }
}
