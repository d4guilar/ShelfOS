// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import com.d4guilar.shelfos.data.metadata.openlibrary.*
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import org.junit.Assert.*
import org.junit.Test

/** META-1: identifier agreement is stated separately from how far the rest of the provider's record can be trusted. */
class EditionLabelsTest {
    private val isbn = "9780062073532"
    private val ids = listOf(PublicationIdentifier(IdentifierType.ISBN_13, isbn))
    // The real Cover Lab finding: a 2011 Harper Paperbacks file, an ISBN-associated record describing 1949 Dodd, Mead.
    private val query = MetadataQuery(MediaCategory.BOOK, ids, title = "Crooked House", publisher = "Harper Paperbacks", year = 2011, language = "en")

    private fun edition(ref: String = "OL1M", publisher: String? = "Harper Paperbacks", date: String? = "2011", exact: Boolean = true,
        language: String? = "en", editionIds: List<PublicationIdentifier> = ids) =
        CoverEdition(ref, "Crooked House", listOf(1L), editionIds, publisher, date, language, exact)

    private fun label(e: CoverEdition): Pair<OnlineCoverCandidate, IsbnLabel> {
        val c = OpenLibraryCoverCandidates.build(query, isbn, listOf(e)).single()
        return c to coverIsbnLabel(c.match, c.tier, c.warnings)
    }

    @Test fun `isbn and publisher and year agree`() {
        val (c, l) = label(edition())
        assertEquals(CoverMatchKind.EXACT_EDITION, c.match)
        assertEquals(IsbnLabel.ISBN_MATCHES, l)
        assertTrue(c.warnings.isEmpty())
    }

    @Test fun `isbn agrees but the publisher conflicts`() {
        val (c, l) = label(edition(publisher = "Dodd, Mead"))
        assertEquals(CoverMatchKind.EXACT_EDITION, c.match)
        assertTrue(CoverWarning.DIFFERENT_PUBLISHER in c.warnings)
        assertEquals(IsbnLabel.ISBN_MATCHES_VERIFY, l)
    }

    @Test fun `isbn agrees but the year conflicts`() {
        val (c, l) = label(edition(date = "1949"))
        assertTrue(CoverWarning.DIFFERENT_YEAR in c.warnings)
        assertEquals(IsbnLabel.ISBN_MATCHES_VERIFY, l)
    }

    @Test fun `the crooked house record with both conflicts is shown as verify with both warnings`() {
        val (c, l) = label(edition(publisher = "Dodd, Mead", date = "1949"))
        assertEquals(setOf(CoverWarning.DIFFERENT_PUBLISHER, CoverWarning.DIFFERENT_YEAR, CoverWarning.LOW_CONFIDENCE), c.warnings)
        assertEquals(IsbnLabel.ISBN_MATCHES_VERIFY, l)
    }

    @Test fun `isbn agrees but confidence is below the top tier`() {
        assertEquals(IsbnLabel.ISBN_MATCHES_VERIFY, coverIsbnLabel(CoverMatchKind.EXACT_EDITION, MatchConfidence.MEDIUM, setOf(CoverWarning.LOW_CONFIDENCE)))
        assertEquals(IsbnLabel.ISBN_MATCHES_VERIFY, coverIsbnLabel(CoverMatchKind.EXACT_EDITION, MatchConfidence.LOW, emptySet()))
    }

    @Test fun `another edition with a different isbn never claims an isbn match`() {
        val other = listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9780007234455"))
        val (c, l) = label(edition(ref = "OL2M", exact = false, editionIds = other))
        assertEquals(CoverMatchKind.OTHER_EDITION, c.match)
        assertTrue(CoverWarning.DIFFERENT_ISBN in c.warnings)
        assertEquals(IsbnLabel.NO_ISBN_MATCH, l)
    }

    @Test fun `a record without publisher or year does not claim an exact edition`() {
        val (c, l) = label(edition(publisher = null, date = null, language = null))
        // Nothing contradicts the ISBN, so it is the plain identifier match: the label is "ISBN matches", never "exact edition".
        assertEquals(IsbnLabel.ISBN_MATCHES, l)
        assertTrue(CoverWarning.DIFFERENT_PUBLISHER !in c.warnings && CoverWarning.DIFFERENT_YEAR !in c.warnings)
    }

    @Test fun `a no-cover-recorded warning alone does not weaken the identifier label`() {
        assertEquals(IsbnLabel.ISBN_MATCHES, coverIsbnLabel(CoverMatchKind.EXACT_EDITION, MatchConfidence.VERY_HIGH, setOf(CoverWarning.NO_COVER_RECORDED)))
    }

    @Test fun `metadata candidates read the matcher evidence`() {
        val id = setOf(MatchSignal.IDENTIFIER)
        assertEquals(IsbnLabel.ISBN_MATCHES, metadataIsbnLabel(MatchConfidence.VERY_HIGH, id, emptySet(), false))
        assertEquals(IsbnLabel.ISBN_MATCHES_VERIFY, metadataIsbnLabel(MatchConfidence.MEDIUM, id, setOf(MatchSignal.PUBLISHER, MatchSignal.YEAR), false))
        assertEquals(IsbnLabel.ISBN_MATCHES_VERIFY, metadataIsbnLabel(MatchConfidence.LOW, id, setOf(MatchSignal.PUBLISHER), false))
    }

    @Test fun `a work-level or rejected metadata candidate has no isbn claim`() {
        assertEquals(IsbnLabel.NO_ISBN_MATCH, metadataIsbnLabel(MatchConfidence.MEDIUM, setOf(MatchSignal.TITLE, MatchSignal.CREATOR), emptySet(), false))
        assertEquals(IsbnLabel.NO_ISBN_MATCH, metadataIsbnLabel(MatchConfidence.LOW, setOf(MatchSignal.IDENTIFIER), setOf(MatchSignal.CATEGORY), true))
    }
}
