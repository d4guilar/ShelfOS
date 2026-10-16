// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.MediaCategory.*
import org.junit.Assert.*
import org.junit.Test

/** Phase 4.5B matching (§12, §15): a wrong match is worse than no match. */
class MetadataMatcherTest {
    private val isbn = PublicationIdentifier(IdentifierType.ISBN_13, "9780306406157")
    private val otherIsbn = PublicationIdentifier(IdentifierType.ISBN_13, "9784088827247")
    private val upc = PublicationIdentifier(IdentifierType.UPC_A, "036000291452-00111")
    private fun c(ref: String, title: String?, creator: String? = null, category: MediaCategory? = null, values: MetadataValues = MetadataValues()) =
        MetadataCandidate("fake", ref, title, creator, category, values)
    private fun tier(q: MetadataQuery, candidate: MetadataCandidate) = MetadataMatcher.score(q, candidate).tier

    @Test fun exactIsbnWithoutContradictionIsVeryHighAndIsbn10MatchesIsbn13() {
        val q = MetadataQuery(BOOK, listOf(PublicationIdentifier(IdentifierType.ISBN_10, "0306406152")), "The Book", "Jane Author")
        val match = c("ed/1", "The Book", "Author, Jane", BOOK, MetadataValues(identifiers = listOf(isbn)))
        assertEquals(MatchConfidence.VERY_HIGH, tier(q, match))
        assertEquals("ed/1", MetadataMatcher.decide(q, listOf(match)).apply?.candidate?.providerRef)
    }

    @Test fun anIdentifierNeverAuthorizesAContradictoryEdition() {
        val q = MetadataQuery(BOOK, listOf(isbn), "The Book", "Jane Author", language = "en")
        assertEquals("translated edition", MatchConfidence.MEDIUM, tier(q, c("x", "The Book", "Jane Author", values = MetadataValues(language = "es", identifiers = listOf(isbn)))))
        assertEquals("different author", MatchConfidence.MEDIUM, tier(q, c("x", "The Book", "John Writer", values = MetadataValues(identifiers = listOf(isbn)))))
        assertEquals("different ISBN = different edition", MatchConfidence.LOW,
            tier(q, c("x", "The Book", "Jane Author", values = MetadataValues(identifiers = listOf(otherIsbn)))))
        assertTrue(MetadataMatcher.decide(q, listOf(c("x", "The Book", "Jane Author", values = MetadataValues(language = "es", identifiers = listOf(isbn))))).apply == null)
    }

    @Test fun titleEqualityAloneIsNeverVeryHighAndSameTitleDifferentAuthorIsLow() {
        val q = MetadataQuery(BOOK, title = "Saga", creator = "Brian K. Vaughan")
        assertEquals(MatchConfidence.MEDIUM, tier(q, c("x", "Saga", "Vaughan, Brian K.")))
        assertEquals(MatchConfidence.LOW, tier(q, c("x", "Saga", "Jane Smith")))
        assertEquals(MatchConfidence.LOW, tier(MetadataQuery(BOOK, title = "Saga"), c("x", "Saga")))
        assertNull(MetadataMatcher.decide(q, listOf(c("x", "Saga", "Vaughan, Brian K."))).apply)
    }

    @Test fun comicIssueVersusCollectionAndDifferentRuns() {
        val q = MetadataQuery(COMIC, title = "Saga", creator = "Brian K. Vaughan", series = "Saga", issue = "1", year = 2012, publisher = "Image")
        val issue1 = c("i1", "Saga", "Brian K. Vaughan", COMIC, MetadataValues(series = "Saga", issue = "001", publishedDate = "2012-03", publisher = "Image"))
        assertEquals("series + issue + two corroborations", MatchConfidence.VERY_HIGH, tier(q, issue1))
        val collection = c("tpb", "Saga Volume 1", "Brian K. Vaughan", COMIC, MetadataValues(series = "Saga", volume = "1", publisher = "Image"))
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(q, collection))
        val compendium = c("comp", "Saga Compendium", "Brian K. Vaughan", COMIC, MetadataValues(series = "Saga", issue = "1", publishedDate = "2012", publisher = "Image"))
        assertEquals("edition kind differs", MatchConfidence.LOW, tier(q, compendium))
        val batman = MetadataQuery(COMIC, title = "Batman", series = "Batman", issue = "1", year = 2016, creator = "Tom King", publisher = "DC")
        assertTrue("issue #1 of another run (different year) is not this issue",
            tier(batman, c("b", "Batman", "Bob Kane", COMIC, MetadataValues(series = "Batman", issue = "1", publishedDate = "1940", publisher = "DC"))) != MatchConfidence.VERY_HIGH)
        assertTrue(MetadataMatcher.score(q, c("i2", "Saga", values = MetadataValues(series = "Saga", issue = "2"))).rejected)
    }

    @Test fun issueZeroAndFractionalNumbersCompareExactly() {
        val q = MetadataQuery(COMIC, listOf(upc), "Saga", series = "Saga", issue = "0")
        assertEquals(MatchConfidence.VERY_HIGH, tier(q, c("z", "Saga", values = MetadataValues(series = "Saga", issue = "#000", identifiers = listOf(upc)))))
        assertTrue(MetadataMatcher.score(q.copy(issue = "1"), c("f", "Saga", values = MetadataValues(series = "Saga", issue = "1.5", identifiers = listOf(upc)))).rejected)
        assertEquals("1", normalizeSequence("#001")); assertEquals("0", normalizeSequence("000")); assertEquals("1.5", normalizeSequence("01.5"))
        assertEquals("12", normalizeSequence("１２"))
    }

    @Test fun mangaVolumesOmnibusAndNoIsbnNeverSilent() {
        val q = MetadataQuery(MANGA, title = "One Piece", creator = "Eiichiro Oda", series = "One Piece", volume = "1", language = "en")
        assertTrue("volume 1 vs 10 is rejected", MetadataMatcher.score(q, c("v10", "One Piece", "Eiichiro Oda", MANGA, MetadataValues(series = "One Piece", volume = "10"))).rejected)
        val sameVolume = c("v1", "One Piece", "Eiichiro Oda", MANGA, MetadataValues(series = "One Piece", volume = "1", language = "en"))
        assertEquals("series + volume without ISBN needs confirmation", MatchConfidence.MEDIUM, tier(q, sameVolume))
        assertEquals(MatchConfidence.LOW, tier(q, c("omni", "One Piece Omnibus", "Eiichiro Oda", MANGA, MetadataValues(series = "One Piece", volume = "1"))))
        assertEquals("a series record without a volume is not this volume", MatchConfidence.LOW, tier(q, c("s", "One Piece", "Eiichiro Oda", MANGA, MetadataValues(series = "One Piece"))))
        val jp = c("jp", "One Piece", "Eiichiro Oda", MANGA, MetadataValues(series = "One Piece", volume = "1", language = "ja", identifiers = listOf(otherIsbn)))
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(q.copy(identifiers = listOf(otherIsbn)), jp))
    }

    @Test fun twoDisagreeingVeryHighCandidatesBothDropAndAgreeingOnesApplyOnce() {
        val q = MetadataQuery(BOOK, listOf(isbn), "The Book")
        val a = c("a", "The Book", values = MetadataValues(identifiers = listOf(isbn), volume = null))
        val b = c("b", "The Book: Special", values = MetadataValues(identifiers = listOf(isbn)))
        assertEquals(MatchConfidence.MEDIUM, tier(q, b))   // title contradiction already caps b
        val b2 = c("b2", "The Book", values = MetadataValues(identifiers = listOf(isbn), volume = "2"))
        val decision = MetadataMatcher.decide(q, listOf(a, b2))
        assertNull("two VERY_HIGH candidates that disagree never apply", decision.apply)
        assertTrue(decision.ranked.all { it.evidence.tier != MatchConfidence.VERY_HIGH })
        assertTrue(decision.ranked.first().evidence.contradictions.contains(MatchSignal.AMBIGUOUS))
        assertEquals("a", MetadataMatcher.decide(q, listOf(a, a.copy(providerId = "other"))).apply?.candidate?.providerRef)
    }

    @Test fun documentsAndCategoryMismatchesAreNeverAutomatic() {
        val q = MetadataQuery(DOCUMENT, title = "Annual Report", creator = "ACME")
        assertEquals(MatchConfidence.LOW, tier(q, c("d", "Annual Report", "ACME", DOCUMENT)))
        assertEquals(MatchConfidence.VERY_HIGH, tier(q.copy(identifiers = listOf(isbn)), c("d", "Annual Report", "ACME", DOCUMENT, MetadataValues(identifiers = listOf(isbn)))))
        val book = MetadataQuery(BOOK, listOf(isbn), "The Book")
        assertTrue(MetadataMatcher.score(book, c("x", "The Book", category = COMIC, values = MetadataValues(identifiers = listOf(isbn)))).rejected)
    }

    @Test fun aFilenameTitleMayAgreeButNeverContradicts() {
        val q = MetadataQuery(BOOK, listOf(isbn), "9780306406157 scan", titleIsInferred = true)
        assertEquals(MatchConfidence.VERY_HIGH, tier(q, c("x", "The Real Title", values = MetadataValues(identifiers = listOf(isbn)))))
        assertEquals(MatchConfidence.LOW, tier(MetadataQuery(BOOK, title = "Saga", creator = "Brian K. Vaughan", titleIsInferred = true),
            c("x", "Different", "Brian K. Vaughan")))
    }

    @Test fun normalizationIsScriptNeutralAndNeverTranslates() {
        assertEquals("ワンピース", normalizeText("ワンピース"))
        assertEquals("one piece", normalizeText("ＯＮＥ　ＰＩＥＣＥ!"))
        assertEquals("les misérables", normalizeText("Les Misérables."))
        assertNotEquals(normalizeText("ワンピース"), normalizeText("One Piece"))
    }
}
