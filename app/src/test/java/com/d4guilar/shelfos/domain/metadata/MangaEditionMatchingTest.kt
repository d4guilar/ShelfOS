// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 4.5D.0 offline Manga edition and volume matching, matrices A-I (J is the service test in
 * `MangaEnrichmentIntegrationTest`). Every candidate here is SYNTHETIC: ISBNs are check-digit-valid values built by
 * [isbn13], titles are invented, and no result measures real provider coverage.
 */
class MangaEditionMatchingTest {
    private fun isbn13(n: Int): PublicationIdentifier { val p = "978" + "%09d".format(n); return PublicationIdentifier(IdentifierType.ISBN_13, p + ean13CheckDigit(p)) }
    private fun isbn10Of(id: PublicationIdentifier): PublicationIdentifier {
        val nine = id.value.substring(3, 12); return PublicationIdentifier(IdentifierType.ISBN_10, nine + isbn10CheckDigit(nine))
    }
    private fun query(vararg ids: PublicationIdentifier, title: String = "One Piece", volume: String? = "3", series: String? = "One Piece", creator: String? = "Eiichiro Oda",
        publisher: String? = null, language: String? = null, inferred: Boolean = false, collection: Boolean = false) =
        MetadataQuery(MediaCategory.MANGA, ids.toList(), title, creator, series, volume, null, null, language, publisher, inferred, collection)
    private fun cand(ref: String, vararg ids: PublicationIdentifier, title: String = "One Piece", creator: String? = "Eiichiro Oda", volume: String? = "3",
        series: String? = "One Piece", language: String? = null, publisher: String? = null, provider: String = "fake", covers: List<CoverCandidate> = emptyList()) =
        MetadataCandidate(provider, ref, title, creator, MediaCategory.MANGA, MetadataValues(series = series, volume = volume, language = language, publisher = publisher,
            identifiers = ids.toList()), covers)
    private fun tier(q: MetadataQuery, c: MetadataCandidate) = MetadataMatcher.score(q, c).tier
    private val a = isbn13(100); private val b = isbn13(200)

    // ---- A: exact edition
    @Test fun anExactIsbnEditionWithAgreeingSeriesVolumeLanguageAndPublisherIsVeryHigh() {
        val q = query(a, language = "en", publisher = "Viz Media")
        assertEquals(MatchConfidence.VERY_HIGH, tier(q, cand("1", a, language = "en", publisher = "Viz Media")))
        assertEquals("ISBN-10 of the same edition is the same edition", MatchConfidence.VERY_HIGH, tier(q, cand("1", isbn10Of(a), language = "en-US", publisher = "VIZ Media")))
        assertEquals("a query holding the ISBN-10 matches the ISBN-13 candidate", MatchConfidence.VERY_HIGH, tier(query(isbn10Of(a)), cand("1", a)))
    }

    // ---- B: no ISBN
    @Test fun seriesVolumeAndCreatorWithoutAnIdentifierIsOnlyAReviewCandidate() {
        for (inferred in listOf(false, true)) {
            val q = query(inferred = inferred)
            val c = cand("1", title = if (inferred) "One Piece, Vol. 3" else "One Piece")
            assertEquals("inferred=$inferred", MatchConfidence.MEDIUM, tier(q, c))
            val decision = MetadataMatcher.decide(q, listOf(c))
            assertNull("never automatic", decision.apply)
        }
        assertEquals("without a creator it is weaker still", MatchConfidence.LOW, tier(query(creator = null, inferred = true), cand("1", title = "One Piece, Vol. 3")))
    }

    // ---- C: filename only
    @Test fun filenameEvidenceAloneNeverReachesVeryHigh() {
        val e = FilenameInference.infer("One Piece Vol. 3.cbz", MediaCategory.MANGA)
        assertEquals(listOf("One Piece", "3"), listOf(e.series, e.volume))
        val q = MetadataQuery(MediaCategory.MANGA, title = e.title, series = e.series, volume = e.volume, titleIsInferred = true)
        for (c in listOf(cand("1"), cand("2", title = "One Piece, Vol. 3", creator = null), cand("3", isbn13(5))))
            assertNotEquals(MatchConfidence.VERY_HIGH, tier(q, c))
    }

    // ---- D: wrong volume
    @Test fun volumeOneNeverMatchesVolumeTenInAnyOrderAndZeroPaddedAndFullWidthDigitsCompareEqual() {
        val v1 = cand("v1", isbn13(11), volume = "1"); val v10 = cand("v10", isbn13(12), volume = "10")
        val q = query(isbn13(11), volume = "1")
        for (order in listOf(listOf(v1, v10), listOf(v10, v1))) {
            val r = EditionClustering.rank(q, order)
            assertEquals("v1", r.apply!!.representative.providerRef)
            assertTrue(r.ranked.single { it.representative.providerRef == "v10" }.evidence.rejected)
        }
        val wrongVolumeSameIsbn = MetadataMatcher.score(query(a, volume = "1"), cand("x", a, volume = "10"))
        assertTrue("a matching ISBN cannot save a different volume", wrongVolumeSameIsbn.rejected)
        assertEquals(MatchConfidence.VERY_HIGH, tier(query(a, volume = "01"), cand("x", a, volume = "1")))
        assertEquals(MatchConfidence.VERY_HIGH, tier(query(a, volume = "３"), cand("x", a, volume = "3")))
        assertTrue(MetadataMatcher.score(query(a, volume = "1"), cand("x", a, volume = "1.5")).rejected)
        assertNotEquals("a missing candidate volume proves nothing", MatchConfidence.VERY_HIGH, tier(query(a), cand("x", a, volume = null)))
        assertTrue(MatchSignal.VOLUME in MetadataMatcher.score(query(a), cand("x", a, volume = null)).contradictions)
    }

    // ---- E: omnibus
    @Test fun aSingleVolumeNeverMatchesAnOmnibusOrCollectionAutomatically() {
        // Structured (non-inferred) title: the existing edition-word rule, with an exact ISBN present.
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(query(a, title = "Berserk", series = "Berserk"), cand("o", a, title = "Berserk Omnibus", series = "Berserk", volume = "3")))
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(query(a, title = "Berserk", series = "Berserk"), cand("d", a, title = "Berserk Deluxe Edition", series = "Berserk", volume = "3")))
        // Inferred title: the explicit collection rule must not be hidden by it.
        val inferredSingle = query(a, title = "Berserk", series = "Berserk", inferred = true)
        val omnibus = cand("o", a, title = "Berserk Omnibus", series = "Berserk", volume = "3")
        assertTrue(MatchSignal.EDITION in MetadataMatcher.score(inferredSingle, omnibus).contradictions)
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(inferredSingle, omnibus))
        assertEquals("the plain single volume still matches", MatchConfidence.VERY_HIGH, tier(inferredSingle, cand("s", a, title = "Berserk", series = "Berserk", volume = "3")))
        // A volume range is a collection, and a different "volume" than 3.
        assertTrue(MetadataMatcher.score(query(a), cand("r", a, volume = "1-3")).rejected)
        // An item that IS a collection matches a collection, not a single volume.
        val collection = query(a, title = "Berserk", series = "Berserk", volume = null, inferred = true, collection = true)
        assertEquals(MatchConfidence.VERY_HIGH, tier(collection, cand("o", a, title = "Berserk Omnibus", series = "Berserk", volume = null)))
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(collection, cand("s", a, title = "Berserk", series = "Berserk", volume = "1")))
        // "Complete" and "deluxe" are not collections for an inferred title with no further evidence.
        assertEquals(MatchConfidence.VERY_HIGH, tier(inferredSingle, cand("c", a, title = "Berserk Complete", series = "Berserk", volume = "3")))
    }

    // ---- F: translation / language
    @Test fun japaneseEnglishAndSpanishEditionsStaySeparate() {
        val en = isbn13(301); val es = isbn13(302); val ja = isbn13(303)
        val q = query(en, language = "en", title = "One Piece")
        val enC = cand("en", en, language = "en", title = "One Piece")
        val esC = cand("es", es, language = "es", title = "One Piece")
        val jaC = cand("ja", ja, language = "ja", title = "ワンピース", series = "ワンピース", creator = "尾田栄一郎")
        val r = EditionClustering.rank(q, listOf(jaC, esC, enC))
        assertEquals(3, r.ranked.size)
        assertEquals("en", r.apply!!.representative.providerRef)
        assertTrue("another language's ISBN is a contradiction", MatchSignal.IDENTIFIER in MetadataMatcher.score(q, esC).contradictions)
        assertEquals("the Japanese title text is kept as written", "ワンピース", jaC.title)
        // Same ISBN but a contradicting language: capped below VERY_HIGH.
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(q, cand("x", en, language = "ja")))
        // No ISBN: same series label and volume in another language is never enough.
        val noIsbn = query(language = "en")
        val spanish = cand("es2", language = "es")
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(noIsbn, spanish)); assertNull(MetadataMatcher.decide(noIsbn, listOf(spanish)).apply)
        assertTrue(MatchSignal.LANGUAGE in MetadataMatcher.score(noIsbn, spanish).contradictions)
        // Title similarity across scripts never overrides contradictions.
        assertNotEquals(MatchConfidence.VERY_HIGH, tier(query(en, language = "en"), cand("ja2", en, language = "ja", title = "ワンピース")))
    }

    // ---- H: ambiguity
    @Test fun twoDisagreeingEditionsStayAtMostMediumInAnyOrderOrProvider() {
        val q = query(a, b, language = null)
        val first = cand("1", a, title = "One Piece"); val second = cand("2", b, title = "One Piece", publisher = "Other Press")
        for (order in listOf(listOf(first, second), listOf(second, first))) {
            val r = EditionClustering.rank(q, order)
            assertNull(r.apply)
            assertTrue(r.ranked.all { it.evidence.tier == MatchConfidence.MEDIUM })
        }
        val otherProvider = second.copy(providerId = "other")
        assertNull(EditionClustering.rank(q, listOf(first, otherProvider)).apply)
        assertNull(MetadataMatcher.decide(q, listOf(first, second)).apply)
    }

    @Test fun agreementBetweenProvidersNeverRaisesAnUnsafeMangaMatch() {
        val q = query(creator = null, inferred = true)
        val many = (1..4).map { cand("r$it", title = "One Piece, Vol. 3", creator = null, provider = "p$it") }
        val r = EditionClustering.rank(q, many)
        assertNull(r.apply)
        assertTrue(r.ranked.none { it.evidence.tier == MatchConfidence.VERY_HIGH })
    }

    // ---- G: cover eligibility
    private fun edition(q: MetadataQuery, c: MetadataCandidate) = EditionClustering.rank(q, listOf(c)).ranked.single()
    private val front = CoverCandidate("fake", "cover/v3", 800, 1200, "Volume 3")

    @Test fun onlyAnExactVolumeSpecificCoverWithNoExistingMangaArtworkCanBeAutomatic() {
        val q = query(a)
        val good = edition(q, cand("1", a, covers = listOf(front)))
        assertEquals(CoverEligibility.AUTOMATIC, CoverEligibilityPolicy.assess(q, good, front, ExistingCovers()))
        assertEquals("D7: a Manga file cover is never replaced automatically", CoverEligibility.CANDIDATE_ONLY, CoverEligibilityPolicy.assess(q, good, front, ExistingCovers(local = true)))
        assertEquals("USER always wins", CoverEligibility.CANDIDATE_ONLY, CoverEligibilityPolicy.assess(q, good, front, ExistingCovers(user = true, local = true)))
        assertEquals("a cover that is not from the matched edition", CoverEligibility.REJECTED, CoverEligibilityPolicy.assess(q, good, CoverCandidate("fake", "other"), ExistingCovers()))
        val labelled = CoverCandidate("fake", "cover/v4", 800, 1200, "Volume 4")
        assertEquals("a cover labelled for another volume", CoverEligibility.REJECTED, CoverEligibilityPolicy.assess(q, edition(q, cand("2", a, covers = listOf(labelled))), labelled, ExistingCovers()))
    }

    @Test fun seriesLevelWrongOrUnidentifiedEditionsNeverGetAnAutomaticCover() {
        val generic = CoverCandidate("fake", "series", 800, 1200)
        val q = query(a)
        val noVolume = edition(q, cand("g", a, volume = null, covers = listOf(generic)))
        assertEquals(CoverEligibility.REJECTED, CoverEligibilityPolicy.assess(q, noVolume, generic, ExistingCovers()))       // missing volume contradicts
        val noIsbn = edition(query(), cand("n", covers = listOf(generic)))
        assertEquals(CoverEligibility.CANDIDATE_ONLY, CoverEligibilityPolicy.assess(query(), noIsbn, generic, ExistingCovers()))
        val wrongIsbn = edition(q, cand("w", b, covers = listOf(generic)))
        assertEquals(CoverEligibility.REJECTED, CoverEligibilityPolicy.assess(q, wrongIsbn, generic, ExistingCovers()))
        val wrongLanguage = edition(query(a, language = "en"), cand("l", a, language = "ja", covers = listOf(generic)))
        assertEquals(CoverEligibility.REJECTED, CoverEligibilityPolicy.assess(query(a, language = "en"), wrongLanguage, generic, ExistingCovers()))
    }

    // ---- G2 (review F-1/F-3/F-4): a cover is judged by the member that supplied it, never by the cluster's representative
    private fun cover(provider: String, ref: String) = CoverCandidate(provider, ref, 800, 1200, "Volume 3")
    private fun cluster(q: MetadataQuery, vararg members: MetadataCandidate): List<RankedEdition> {
        val forward = EditionClustering.rank(q, members.toList()).ranked.also { assertEquals("one ISBN, one cluster", 1, it.size) }
        assertEquals(forward.single().cluster.members.size, EditionClustering.rank(q, members.reversed()).ranked.single().cluster.members.size)
        return listOf(forward.single(), EditionClustering.rank(q, members.reversed()).ranked.single())
    }
    private fun assessAll(q: MetadataQuery, c: CoverCandidate, vararg members: MetadataCandidate, existing: ExistingCovers = ExistingCovers()) =
        cluster(q, *members).map { CoverEligibilityPolicy.assess(q, it, c, existing) }.distinct().single()   // result order must not matter

    @Test fun aJapaneseRecordInAnEnglishClusterNeverInheritsTheEnglishEligibility() {
        val q = query(a, language = "en")
        val en = cand("en", a, language = "en", provider = "p1", covers = listOf(cover("p1", "en")))
        val ja = cand("ja", a, language = "ja", provider = "p2", covers = listOf(cover("p2", "ja")))
        assertEquals(CoverEligibility.AUTOMATIC, assessAll(q, cover("p1", "en"), en, ja))
        assertEquals(CoverEligibility.REJECTED, assessAll(q, cover("p2", "ja"), en, ja))
    }

    @Test fun aSeriesLevelRecordInTheClusterNeverInheritsTheVolumeEligibility() {
        val q = query(a)
        val volume = cand("v", a, provider = "p1", covers = listOf(cover("p1", "vol")))
        val series = cand("s", a, volume = null, provider = "p2", covers = listOf(cover("p2", "series")))
        assertEquals(CoverEligibility.AUTOMATIC, assessAll(q, cover("p1", "vol"), volume, series))
        assertNotEquals(CoverEligibility.AUTOMATIC, assessAll(q, cover("p2", "series"), volume, series))
    }

    @Test fun anOmnibusCoverNeverBecomesAutomaticForASingleVolumeInAnyResultOrder() {
        val q = query(a)
        val volume = cand("v", a, provider = "p1", covers = listOf(cover("p1", "vol")))
        val titled = cand("o1", a, title = "One Piece Omnibus", provider = "p2", covers = listOf(cover("p2", "omni")))
        val ranged = cand("o2", a, volume = "1-3", provider = "p2", covers = listOf(cover("p2", "omni")))
        for (omnibus in listOf(titled, ranged)) assertEquals(CoverEligibility.REJECTED, assessAll(q, cover("p2", "omni"), volume, omnibus))
        assertEquals("the genuine volume keeps its own cover", CoverEligibility.AUTOMATIC, assessAll(q, cover("p1", "vol"), volume, titled))
        // a rejected member contests the cluster (4.5C.1), so the edition is not applied and nothing in it is automatic
        assertEquals(CoverEligibility.CANDIDATE_ONLY, assessAll(q, cover("p1", "vol"), volume, ranged))
    }

    @Test fun recordsFromOneProviderAreJudgedIndividually() {
        val q = query(a, language = "en")
        val good = cand("r1", a, language = "en", provider = "p", covers = listOf(cover("p", "good")))
        val other = cand("r2", a, language = "ja", provider = "p", covers = listOf(cover("p", "other")))
        assertEquals(CoverEligibility.AUTOMATIC, assessAll(q, cover("p", "good"), good, other))
        assertEquals(CoverEligibility.REJECTED, assessAll(q, cover("p", "other"), good, other))
    }

    @Test fun aCoverClaimingAnotherProviderThanItsOwnerIsRejected() {
        val q = query(a)
        val claimed = CoverCandidate("someone-else", "x", 800, 1200, "Volume 3")
        assertEquals(CoverEligibility.REJECTED, assessAll(q, claimed, cand("1", a, provider = "p1", covers = listOf(claimed))))
    }

    @Test fun anIndistinguishableCoverOnSeveralMembersFailsClosed() {
        val q = query(a, language = "en")
        val shared = cover("p", "same")
        val x = cand("x", a, language = "en", provider = "p", covers = listOf(shared))
        val y = cand("y", a, language = "en", provider = "p", covers = listOf(shared))
        assertEquals("both valid but the owner is unknown", CoverEligibility.CANDIDATE_ONLY, assessAll(q, shared, x, y))
        val ja = cand("y", a, language = "ja", provider = "p", covers = listOf(shared))
        assertEquals("eligibility disagrees", CoverEligibility.CANDIDATE_ONLY, assessAll(q, shared, x, ja))
        val bad1 = cand("z1", a, language = "ja", provider = "p", covers = listOf(shared)); val bad2 = cand("z2", a, language = "ja", provider = "p", covers = listOf(shared))
        assertEquals(CoverEligibility.REJECTED, assessAll(q, shared, bad1, bad2))
    }

    @Test fun eachProviderOwnsItsOwnCoverWhenBothRecordsAreTheSameVeryHighEdition() {
        val q = query(a)
        val p1 = cand("1", a, provider = "p1", covers = listOf(cover("p1", "c1"))); val p2 = cand("2", a, provider = "p2", covers = listOf(cover("p2", "c2")))
        assertEquals(CoverEligibility.AUTOMATIC, assessAll(q, cover("p1", "c1"), p1, p2))
        assertEquals(CoverEligibility.AUTOMATIC, assessAll(q, cover("p2", "c2"), p1, p2))
        assertEquals("D7 still protects a Manga file cover", CoverEligibility.CANDIDATE_ONLY, assessAll(q, cover("p1", "c1"), p1, p2, existing = ExistingCovers(local = true)))
    }

    @Test fun aBlankCoverReferenceIsNeverUsableArtwork() {
        val q = query(a)
        for (ref in listOf("", "   ", "\t\n")) {
            val blank = CoverCandidate("p1", ref, 800, 1200, "Volume 3")
            assertEquals("'$ref'", CoverEligibility.REJECTED, assessAll(q, blank, cand("1", a, provider = "p1", covers = listOf(blank))))
        }
    }

    // ---- F-3: ordinary titles that contain a collection-like word
    @Test fun titlesContainingBoxOrCollectionAreNotCollections() {
        val q = query(a, title = "Blue Box", series = "Blue Box", volume = "1", inferred = true)
        assertEquals(MatchConfidence.VERY_HIGH, tier(q, cand("b", a, title = "Blue Box", series = "Blue Box", volume = "1")))
        val ito = query(a, title = "Junji Ito Collection", series = "Junji Ito Collection", volume = "2", inferred = true)
        assertEquals(MatchConfidence.VERY_HIGH, tier(ito, cand("i", a, title = "Junji Ito Collection", series = "Junji Ito Collection", volume = "2")))
        val structured = query(a, title = "Blue Box", series = "Blue Box", volume = "1")
        assertEquals(MatchConfidence.VERY_HIGH, tier(structured, cand("s", a, title = "Blue Box", series = "Blue Box", volume = "1")))
        // real collections are still collections
        val single = query(a, title = "Naruto", series = "Naruto", volume = "1", inferred = true)
        for (title in listOf("Naruto Box Set", "Naruto Omnibus", "Naruto Collection"))
            assertTrue(title, MatchSignal.EDITION in MetadataMatcher.score(single, cand("c", a, title = title, series = "Naruto", volume = null)).contradictions)
        assertTrue(MatchSignal.EDITION in MetadataMatcher.score(single, cand("c", a, title = "Naruto Box Set", series = "Naruto", volume = "1")).contradictions)
    }

    // ---- R-1: shared artwork is identified by (providerId, trimmed ref), never by display data
    @Test fun theSameProviderReferenceIsSharedOwnershipDespiteDifferentDimensionsLabelsOrWhitespace() {
        val q = query(a, language = "en")
        val good = CoverCandidate("p", "same", 800, 1200, "Volume 3")
        val variants = listOf(
            CoverCandidate("p", "same", 100, 150, "Volume 3"),     // different dimensions
            CoverCandidate("p", "same", 800, 1200, "Volume 3 (JP)"), // different label
            CoverCandidate("p", "same", 800, 1200, null),
            CoverCandidate("p", "  same ", 800, 1200, "Volume 3"), // different whitespace
        )
        for (v in variants) {
            val valid = cand("x", a, language = "en", provider = "p", covers = listOf(good))
            val conflicting = cand("y", a, language = "ja", provider = "p", covers = listOf(v))
            assertNotEquals("$v", CoverEligibility.AUTOMATIC, assessAll(q, good, valid, conflicting))
            assertNotEquals("$v (assessed from the other side)", CoverEligibility.AUTOMATIC, assessAll(q, v, valid, conflicting))
            assertEquals("a co-owner that is fine on its own is still an unknown owner", CoverEligibility.CANDIDATE_ONLY,
                assessAll(q, good, valid, cand("y", a, language = "en", provider = "p", covers = listOf(v))))
        }
    }

    @Test fun theSameReferenceTextFromDifferentProvidersIsDistinctOwnership() {
        val q = query(a, language = "en")
        val mine = cover("p1", "same"); val theirs = cover("p2", "same")
        val valid = cand("x", a, language = "en", provider = "p1", covers = listOf(mine))
        val conflicting = cand("y", a, language = "ja", provider = "p2", covers = listOf(theirs))
        assertEquals(CoverEligibility.AUTOMATIC, assessAll(q, mine, valid, conflicting))
        assertEquals(CoverEligibility.REJECTED, assessAll(q, theirs, valid, conflicting))
    }

    @Test fun aUniquelyOwnedCoverStaysAutomaticAndTheOtherProtectionsHold() {
        val q = query(a)
        val c = CoverCandidate("p", "  only ", 800, 1200, "Volume 3")
        val owner = cand("x", a, provider = "p", covers = listOf(c))
        assertEquals(CoverEligibility.AUTOMATIC, assessAll(q, c, owner))
        assertEquals(CoverEligibility.AUTOMATIC, assessAll(q, c.copy(ref = "only"), owner))   // trimmed reference, same owner
        assertEquals(CoverEligibility.CANDIDATE_ONLY, assessAll(q, c, owner, existing = ExistingCovers(user = true)))
        assertEquals(CoverEligibility.CANDIDATE_ONLY, assessAll(q, c, owner, existing = ExistingCovers(local = true)))
        assertEquals(CoverEligibility.REJECTED, assessAll(q, c.copy(ref = " "), owner))
    }
}
