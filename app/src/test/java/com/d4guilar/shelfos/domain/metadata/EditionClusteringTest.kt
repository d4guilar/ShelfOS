// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import org.junit.Assert.*
import org.junit.Test

/** Edition-aware clustering and ranking (4.5C.1, D30): agreement ranks, it never lifts a tier the matcher would cap. */
class EditionClusteringTest {
    private fun isbn13(n: Int): PublicationIdentifier { val p = "978" + "%09d".format(n); return PublicationIdentifier(IdentifierType.ISBN_13, p + ean13CheckDigit(p)) }
    private fun cand(provider: String, ref: String, title: String, creator: String? = "Frank Herbert", vararg ids: PublicationIdentifier,
        category: MediaCategory = MediaCategory.BOOK, values: MetadataValues = MetadataValues(), covers: List<CoverCandidate> = emptyList()) =
        MetadataCandidate(provider, ref, title, creator, category, values.copy(identifiers = ids.toList()), covers)
    private val dune = isbn13(1)
    private val bookQuery = MetadataQuery(MediaCategory.BOOK, listOf(dune), "Dune", "Frank Herbert")

    @Test fun isbn10AndIsbn13OfTheSameEditionFormOneClusterAcrossProviders() {
        val ten = PublicationIdentifier(IdentifierType.ISBN_10, "0306406152")
        val thirteen = PublicationIdentifier(IdentifierType.ISBN_13, "9780306406157")
        val clusters = EditionClustering.cluster(listOf(cand("a", "1", "Dune", "Frank Herbert", ten), cand("b", "x", "Dune", "Frank Herbert", thirteen)))
        assertEquals(1, clusters.size)
        assertEquals(setOf("a", "b"), clusters.single().providerIds)
    }

    @Test fun twoProvidersOnOneEditionKeepEveryReferenceAndCoverAndStayInTheirOwnTier() {
        val ca = CoverCandidate("a", "ca", 600, 900); val cb = CoverCandidate("b", "cb", 1200, 1800)
        val r = EditionClustering.rank(bookQuery, listOf(cand("b", "ref-b", "Dune", "Frank Herbert", dune, covers = listOf(cb)),
            cand("a", "ref-a", "Dune", "Frank Herbert", dune, covers = listOf(ca))))
        val e = r.apply!!
        assertEquals(MatchConfidence.VERY_HIGH, e.evidence.tier)
        assertEquals(setOf(ProviderRef("a", "ref-a"), ProviderRef("b", "ref-b")), e.cluster.refs.toSet())
        assertEquals(setOf("a", "b"), e.corroborating)
        assertEquals(setOf(ca, cb), e.cluster.covers.toSet())
        assertEquals("the original candidates survive", 2, e.cluster.members.size)
    }

    @Test fun differentIsbnsOfTheSameTitleAreNeverCollapsed() {
        val other = isbn13(2)
        val r = EditionClustering.rank(bookQuery, listOf(cand("a", "1", "Dune", "Frank Herbert", dune), cand("b", "2", "Dune", "Frank Herbert", other)))
        assertEquals(2, r.ranked.size)
        assertEquals("only the queried edition applies", dune, r.apply!!.representative.values.identifiers!!.single())
        assertEquals("a different ISBN is a contradiction, never VERY_HIGH", MatchConfidence.LOW, r.ranked.last().evidence.tier)
        assertEquals(2, EditionClustering.cluster(listOf(cand("a", "1", "Dune", "x", dune), cand("a", "2", "Dune", "x", isbn13(3)))).size)
    }

    @Test fun noTransitiveMergeThroughACandidateListingSeveralIsbns() {
        val both = cand("a", "1", "Dune", "Frank Herbert", dune, isbn13(2))
        val clusters = EditionClustering.cluster(listOf(both, cand("b", "2", "Dune", "Frank Herbert", dune), cand("c", "3", "Dune", "Frank Herbert", isbn13(2))))
        assertEquals("the multi-ISBN record is its own candidate", 3, clusters.size)
    }

    @Test fun sameTitleAndAuthorWithoutAnIsbnStaysSeparateAndCanNeverBeVeryHigh() {
        val q = MetadataQuery(MediaCategory.BOOK, title = "Dune", creator = "Frank Herbert")
        val r = EditionClustering.rank(q, listOf(cand("a", "1", "Dune", "Frank Herbert", values = MetadataValues(publisher = "Ace", publishedDate = "1990")),
            cand("b", "2", "Dune", "Frank Herbert", values = MetadataValues(publisher = "Gollancz", publishedDate = "2010"))))
        assertEquals(2, r.ranked.size)
        assertNull(r.apply)
        assertTrue(r.ranked.all { it.evidence.tier == MatchConfidence.MEDIUM })
    }

    @Test fun agreementBetweenProvidersNeverRaisesAnUnsafeTier() {
        val q = MetadataQuery(MediaCategory.BOOK, title = "Dune", creator = "Frank Herbert")
        val same = (1..4).map { cand("p$it", "r$it", "Dune", "Frank Herbert", values = MetadataValues(publisher = "Ace")) }
        val r = EditionClustering.rank(q, same)
        assertNull("four providers agreeing on a title-and-author match is still not automatic", r.apply)
        assertEquals(MatchConfidence.MEDIUM, r.ranked.first().evidence.tier)
    }

    @Test fun languageContradictionBlocksVeryHighEvenWhenAnotherProviderAgrees() {
        val q = MetadataQuery(MediaCategory.BOOK, listOf(dune), "Dune", "Frank Herbert", language = "en")
        val r = EditionClustering.rank(q, listOf(cand("a", "1", "Dune", "Frank Herbert", dune, values = MetadataValues(language = "fr")),
            cand("b", "2", "Dune", "Frank Herbert", dune, values = MetadataValues(language = "fr"))))
        assertNull(r.apply)
        assertEquals(MatchConfidence.MEDIUM, r.ranked.single().evidence.tier)
    }

    @Test fun membersOfOneEditionThatContradictEachOtherCapTheClusterAtMedium() {
        val q = MetadataQuery(MediaCategory.MANGA, listOf(dune), "Berserk", "Kentaro Miura", volume = "1")
        val r = EditionClustering.rank(q, listOf(cand("a", "1", "Berserk", "Kentaro Miura", dune, category = MediaCategory.MANGA, values = MetadataValues(volume = "1")),
            cand("b", "2", "Berserk", "Kentaro Miura", dune, category = MediaCategory.MANGA, values = MetadataValues(volume = "10"))))
        assertNull(r.apply)
        assertEquals(MatchConfidence.MEDIUM, r.ranked.single().evidence.tier)
        assertTrue(MatchSignal.AMBIGUOUS in r.ranked.single().evidence.contradictions)
    }

    @Test fun mangaVolumeOneAndTenAreDifferentCandidatesAndTheWrongVolumeIsRejected() {
        val v1 = isbn13(11); val v10 = isbn13(20)
        val q = MetadataQuery(MediaCategory.MANGA, listOf(v1), "Berserk", "Kentaro Miura", volume = "1")
        val r = EditionClustering.rank(q, listOf(cand("a", "1", "Berserk", "Kentaro Miura", v1, category = MediaCategory.MANGA, values = MetadataValues(volume = "1")),
            cand("a", "10", "Berserk", "Kentaro Miura", v10, category = MediaCategory.MANGA, values = MetadataValues(volume = "10"))))
        assertEquals(2, r.ranked.size)
        assertEquals("1", r.apply!!.representative.values.volume)
        assertTrue(r.ranked.last().evidence.rejected)
    }

    @Test fun comicIssueAnnualAndCollectedEditionAreNotInterchangeable() {
        val q = MetadataQuery(MediaCategory.COMIC, title = "Saga", series = "Saga", issue = "1", publisher = "Image", year = 2012)
        val issue = cand("m", "i1", "Saga", null, category = MediaCategory.COMIC, values = MetadataValues(series = "Saga", issue = "1", publisher = "Image", publishedDate = "2012-03-14"))
        val annual = cand("m", "an1", "Saga Annual", null, category = MediaCategory.COMIC, values = MetadataValues(series = "Saga Annual", issue = "1", publisher = "Image", publishedDate = "2012-03-14"))
        val tpb = cand("m", "tp1", "Saga Collected Edition", null, isbn13(5), category = MediaCategory.COMIC, values = MetadataValues(series = "Saga", volume = "1", publisher = "Image", publishedDate = "2012"))
        val r = EditionClustering.rank(q, listOf(tpb, annual, issue))
        assertEquals(3, r.ranked.size)
        assertEquals("i1", r.apply!!.representative.providerRef)
        assertTrue(r.ranked.filter { it.representative.providerRef != "i1" }.none { it.evidence.tier == MatchConfidence.VERY_HIGH })
    }

    @Test fun anOmnibusIsDistinguishedFromASingleVolume() {
        val q = MetadataQuery(MediaCategory.BOOK, title = "Saga", creator = "Brian K. Vaughan")
        val r = EditionClustering.rank(q, listOf(cand("a", "omni", "Saga Omnibus", "Brian K. Vaughan", isbn13(7)), cand("a", "one", "Saga", "Brian K. Vaughan", isbn13(8))))
        assertEquals(2, r.ranked.size)
        assertEquals("one", r.ranked.first().representative.providerRef)
        assertTrue(MatchSignal.EDITION in r.ranked.last().evidence.contradictions)
        assertNull(r.apply)
    }

    @Test fun twoDisagreeingVeryHighEditionsStayAmbiguousAndNonApplicable() {
        val q = MetadataQuery(MediaCategory.COMIC, title = "Saga", series = "Saga", issue = "1", publisher = "Image", year = 2012, creator = null)
        fun c(p: String, n: Int) = cand(p, "r$n", "Saga", null, isbn13(n), category = MediaCategory.COMIC,
            values = MetadataValues(series = "Saga", issue = "1", publisher = "Image", publishedDate = "2012-03-14"))
        val r = EditionClustering.rank(q, listOf(c("a", 30), c("b", 31)))
        assertNull(r.apply)
        assertTrue(r.ranked.all { it.evidence.tier == MatchConfidence.MEDIUM && MatchSignal.AMBIGUOUS in it.evidence.contradictions })
    }

    @Test fun rankingIsIdenticalForEveryProviderReturnOrder() {
        val cs = listOf(cand("a", "1", "Dune", "Frank Herbert", dune), cand("b", "2", "Dune", "Frank Herbert", dune), cand("c", "3", "Dune", "Frank Herbert", isbn13(2)),
            cand("a", "4", "Dune Messiah", "Frank Herbert", isbn13(3)), cand("d", "5", "Dune", "Someone Else"))
        val expected = EditionClustering.rank(bookQuery, cs)
        for (shuffle in 0 until 20) assertEquals(expected, EditionClustering.rank(bookQuery, cs.shuffled(java.util.Random(shuffle.toLong()))))
        assertEquals(expected, EditionClustering.rank(bookQuery, cs.reversed()))
    }

    @Test fun providerPriorityOnlyBreaksTiesAndNeverChangesATier() {
        val q = MetadataQuery(MediaCategory.BOOK, title = "Dune", creator = "Frank Herbert")
        val cs = listOf(cand("a", "1", "Dune", "Frank Herbert", isbn13(4)), cand("b", "2", "Dune", "Frank Herbert", isbn13(5)))
        assertEquals("b", EditionClustering.rank(q, cs) { if (it == "b") 0 else 1 }.ranked.first().representative.providerId)
        assertEquals("a", EditionClustering.rank(q, cs) { if (it == "a") 0 else 1 }.ranked.first().representative.providerId)
        assertTrue(EditionClustering.rank(q, cs) { 0 }.ranked.all { it.evidence.tier == MatchConfidence.MEDIUM })
    }

    @Test fun duplicateProviderReferencesCollapseButDistinctOnesAreKept() {
        val a = cand("a", "1", "Dune", "x", dune)
        assertEquals(1, EditionClustering.cluster(listOf(a, a.copy())).single().members.size)
        assertEquals(2, EditionClustering.cluster(listOf(a, a.copy(providerRef = "2"))).single().members.size)
    }

    @Test fun anEmptyResultRanksToNothing() {
        val r = EditionClustering.rank(bookQuery, emptyList())
        assertTrue(r.ranked.isEmpty()); assertNull(r.apply)
    }
}
