// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import org.junit.Assert.*
import org.junit.Test

/** Phase 4.5B field-level precedence (§9, §16) and the stored-value codec. */
class MetadataPrecedenceTest {
    private val user = FieldProvenance(MetadataOrigin.USER, resolvedAt = 1)
    private val embedded = FieldProvenance(MetadataOrigin.EMBEDDED)
    private val filename = FieldProvenance(MetadataOrigin.FILENAME_INFERENCE)
    private val exact = FieldProvenance(MetadataOrigin.EXACT_IDENTIFIER_MATCH, "fake", "ed/1", MatchConfidence.VERY_HIGH, 5)
    private val medium = FieldProvenance(MetadataOrigin.INFERRED_EXTERNAL_MATCH, "fake", "ed/2", MatchConfidence.MEDIUM, 5)
    private fun item(title: String = "Saga v01", titleOrigin: FieldProvenance = filename, values: MetadataValues = MetadataValues(),
        provenance: Map<MetadataField, FieldProvenance> = emptyMap()) =
        ResolvedMetadata(title, "", values, provenance + (MetadataField.TITLE to titleOrigin) + (MetadataField.CREATOR to FieldProvenance(MetadataOrigin.UNKNOWN)))

    @Test fun userIsNeverReplacedAutomaticallyEvenWhenCleared() {
        val current = item(values = MetadataValues(publisher = null, series = "Mine"),
            provenance = mapOf(MetadataField.PUBLISHER to user, MetadataField.SERIES to user))
        val next = resolveMetadata(current, listOf(Proposed(MetadataField.PUBLISHER, "Image", embedded),
            Proposed(MetadataField.SERIES, "Saga", exact), Proposed(MetadataField.SERIES, "Saga", embedded)))
        assertEquals(current, next)
        assertEquals("a user edit replaces anything", "Theirs",
            resolveMetadata(current, listOf(Proposed(MetadataField.SERIES, "Theirs", user))).values.series)
    }

    @Test fun embeddedOutranksFilenameAndExternalNeverReplacesEmbedded() {
        val current = item(values = MetadataValues(series = "saga", volume = "1"),
            provenance = mapOf(MetadataField.SERIES to filename, MetadataField.VOLUME to filename))
        val embeddedNext = resolveMetadata(current, listOf(Proposed(MetadataField.SERIES, "Saga", embedded)))
        assertEquals("Saga", embeddedNext.values.series)
        assertEquals(MetadataOrigin.EMBEDDED, embeddedNext.origin(MetadataField.SERIES))
        assertEquals("filename never replaces embedded", embeddedNext,
            resolveMetadata(embeddedNext, listOf(Proposed(MetadataField.SERIES, "x", filename))))
        assertEquals("external never replaces embedded", embeddedNext,
            resolveMetadata(embeddedNext, listOf(Proposed(MetadataField.SERIES, "Saga Deluxe", exact))))
        assertEquals("external fills a filename-inferred field", "2",
            resolveMetadata(embeddedNext, listOf(Proposed(MetadataField.VOLUME, "2", exact))).values.volume)
    }

    @Test fun missingMetadataNeverDeletesAndReanalysisMayRefreshEmbedded() {
        val current = item(values = MetadataValues(publisher = "Image"), provenance = mapOf(MetadataField.PUBLISHER to embedded))
        assertEquals(current, resolveMetadata(current, listOf(Proposed(MetadataField.PUBLISHER, "", embedded),
            Proposed(MetadataField.PUBLISHER, "  ", exact))))
        assertEquals("Image Comics", resolveMetadata(current, listOf(Proposed(MetadataField.PUBLISHER, "Image Comics", embedded))).values.publisher)
    }

    @Test fun titleNeedsAVeryHighExternalMatchAndOnlyReplacesAFilenameTitle() {
        assertEquals("Saga v01", resolveMetadata(item(), listOf(Proposed(MetadataField.TITLE, "Saga, Volume 1", medium))).title)
        val replaced = resolveMetadata(item(), listOf(Proposed(MetadataField.TITLE, "Saga, Volume 1", exact)))
        assertEquals("Saga, Volume 1", replaced.title)
        assertEquals(exact, replaced.provenance[MetadataField.TITLE])
        assertEquals("never over an embedded title", "Saga", resolveMetadata(item("Saga", embedded),
            listOf(Proposed(MetadataField.TITLE, "Other", exact))).title)
        assertEquals("never over a user title", "Mine", resolveMetadata(item("Mine", user), listOf(Proposed(MetadataField.TITLE, "Other", exact))).title)
    }

    @Test fun userEditsMarkOnlyChangedFieldsAndClearingIsAUserChoice() {
        val original = MetadataValues(publisher = "Image", series = "Saga", description = "Text")
        val edits = userEdits(original, original.copy(publisher = " Image ", series = "Saga Deluxe", description = ""), 9)
        assertEquals(listOf(MetadataField.DESCRIPTION, MetadataField.SERIES), edits.map { it.field }.sorted())
        val next = resolveMetadata(item(values = original, provenance = mapOf(MetadataField.DESCRIPTION to embedded)), edits)
        assertNull(next.values.description)
        assertEquals(MetadataOrigin.USER, next.origin(MetadataField.DESCRIPTION))
        assertEquals("Saga Deluxe", next.values.series)
        assertEquals("a confirmed external value is not USER", MetadataOrigin.EXACT_IDENTIFIER_MATCH,
            resolveMetadata(item(), listOf(Proposed(MetadataField.ISSUE, "1", exact))).origin(MetadataField.ISSUE))
    }

    @Test fun legacyOriginStringsStayReadableAndUnknownFailsSafe() {
        assertEquals(MetadataOrigin.USER, MetadataOrigin.fromStorage("user"))
        assertEquals(MetadataOrigin.EMBEDDED, MetadataOrigin.fromStorage("embedded"))
        assertEquals(MetadataOrigin.FILENAME_INFERENCE, MetadataOrigin.fromStorage("filename"))
        assertEquals(MetadataOrigin.UNKNOWN, MetadataOrigin.fromStorage("future_origin"))
        assertEquals(MetadataOrigin.UNKNOWN, MetadataOrigin.fromStorage(null))
    }

    @Test fun codecRoundTripsAndDropsMalformedOrUnknownValues() {
        val ids = listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9780306406157"), PublicationIdentifier(IdentifierType.UPC_A, "036000291452-00111"))
        assertEquals(ids, MetadataCodec.identifiers(MetadataCodec.identifiers(ids)))
        val credits = listOf(Credit(CreditRole.WRITER, "Brian K. Vaughan"), Credit(CreditRole.PENCILLER, "Fiona \"Q\" Staples\\"))
        assertEquals(credits, MetadataCodec.credits(MetadataCodec.credits(credits)))
        val provenance = mapOf(MetadataField.SERIES to exact, MetadataField.VOLUME to filename, MetadataField.PUBLISHER to user)
        assertEquals(provenance, MetadataCodec.provenance(MetadataCodec.provenance(provenance)))
        assertNull(MetadataCodec.identifiers(emptyList()))
        for (bad in listOf("{", "[[\"ISBN_13\"", "not json", "[[1,2]]", "[[\"x\"]] trailing", "[[\"ISBN_13\",\"\\q\"]]"))
            assertEquals(bad, emptyList<PublicationIdentifier>(), MetadataCodec.identifiers(bad))
        assertEquals("checksum re-validated on read", emptyList<PublicationIdentifier>(),
            MetadataCodec.identifiers("[[\"ISBN_13\",\"9780306406158\"],[\"MYSTERY\",\"1\"]]"))
        assertEquals("unknown origin degrades to UNKNOWN, unknown field is dropped",
            mapOf(MetadataField.SERIES to FieldProvenance(MetadataOrigin.UNKNOWN)),
            MetadataCodec.provenance("[[\"SERIES\",\"FROM_THE_FUTURE\",null,null,null,null],[\"NEW_FIELD\",\"USER\",null,null,null,null]]"))
    }

    /** L-5: real calendar dates only, at the precision written. */
    @Test fun publicationDatesAreStrictCalendarDates() {
        for (valid in listOf("2024-02-29", "2021-12-31", "2000-02-29", "2021-04-30", "2021-04", "2021"))
            assertEquals(valid, valid, normalizedDate(valid))
        for (invalid in listOf("2023-02-29", "2021-02-30", "2021-04-31", "1900-02-29", "2021-00", "2021-13", "2021-00-10", "2021-01-00",
            "2021-01-32", "", "   ", "21-01-01", "year 2021"))
            assertNull(invalid, normalizedDate(invalid))
    }

    /** L-4: a non-blank value whose provenance is missing, malformed or unknown is never replaced automatically. */
    @Test fun untrustedProvenanceProtectsExistingValuesButNotUserEdits() {
        val unknown = item(values = MetadataValues(publisher = "Kept Press"), provenance = mapOf(MetadataField.PUBLISHER to FieldProvenance(MetadataOrigin.UNKNOWN)))
        val missing = item(values = MetadataValues(publisher = "Kept Press", series = "Mine"),
            provenance = mapOf(MetadataField.SERIES to user))   // publisher has no provenance at all (partial/malformed JSON)
        for (current in listOf(unknown, missing)) {
            for (auto in listOf(embedded, exact, filename))
                assertEquals("$auto must not replace an untrusted value", "Kept Press",
                    resolveMetadata(current, listOf(Proposed(MetadataField.PUBLISHER, "Other", auto))).values.publisher)
            val edited = resolveMetadata(current, listOf(Proposed(MetadataField.PUBLISHER, "Mine Press", user)))
            assertEquals("an explicit user edit still succeeds", "Mine Press", edited.values.publisher)
            assertEquals(MetadataOrigin.USER, edited.origin(MetadataField.PUBLISHER))
            assertEquals("the origin is not invented", current.provenance[MetadataField.PUBLISHER],
                resolveMetadata(current, listOf(Proposed(MetadataField.PUBLISHER, "Other", embedded))).provenance[MetadataField.PUBLISHER])
        }
        assertEquals("a valid USER neighbour is untouched", "Mine",
            resolveMetadata(missing, listOf(Proposed(MetadataField.SERIES, "Auto", embedded))).values.series)
        assertEquals("an empty field with unknown origin is still filled", "Image",
            resolveMetadata(item(provenance = mapOf(MetadataField.PUBLISHER to FieldProvenance(MetadataOrigin.UNKNOWN))),
                listOf(Proposed(MetadataField.PUBLISHER, "Image", embedded))).values.publisher)
        val decoded = MetadataCodec.provenance("[[\"PUBLISHER\",\"???\",null,null,null,null],[\"SERIES\",\"USER\",null,null,null,\"5\"]]")
        assertEquals(MetadataOrigin.UNKNOWN, decoded.getValue(MetadataField.PUBLISHER).origin)
        assertEquals(MetadataOrigin.USER, decoded.getValue(MetadataField.SERIES).origin)
    }

    @Test fun datesAndLanguagesAreValidatedNotGuessed() {
        assertEquals("2012-10-10", normalizedDate("2012-10-10T00:00:00Z"))
        assertEquals("2012", normalizedDate("2012"))
        assertNull("an invalid month is rejected, not truncated", normalizedDate("2012-13-01"))
        assertNull(normalizedDate("October 2012"))
        assertNull(normalizedDate("0999"))
        assertEquals("pt-BR", normalizedLanguage("pt_BR"))
        assertEquals("ja", normalizedLanguage(" ja "))
        assertNull(normalizedLanguage("English"))
        assertNull(normalizedLanguage("e"))
    }
}
