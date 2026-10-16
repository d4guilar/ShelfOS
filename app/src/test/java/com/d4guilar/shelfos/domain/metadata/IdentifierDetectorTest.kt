// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import org.junit.Assert.*
import org.junit.Test

/** Phase 4.5B identifier detection: checksums, normalization and no false positives from ordinary numbers. */
class IdentifierDetectorTest {
    private fun isbn13(v: String) = PublicationIdentifier(IdentifierType.ISBN_13, v)

    @Test fun isbn13WithHyphensSpacesUrnAndLabelNormalizes() {
        for (raw in listOf("978-0-306-40615-7", "978 0 306 40615 7", "urn:isbn:9780306406157", "ISBN 978-0-306-40615-7", "isbn:9780306406157", "9780306406157"))
            assertEquals(raw, isbn13("9780306406157"), IdentifierDetector.isbn(raw))
        assertEquals(isbn13("9784088827247"), IdentifierDetector.isbn("978-4-08-882724-7"))   // a Japanese-group ISBN
    }

    @Test fun isbn10NeedsALabelOrSchemeAndAValidCheckDigitIncludingX() {
        assertNull("bare 10 digits are never assumed to be an ISBN", IdentifierDetector.isbn("0306406152"))
        assertEquals(PublicationIdentifier(IdentifierType.ISBN_10, "0306406152"), IdentifierDetector.isbn("0-306-40615-2", schemeSaysIsbn = true))
        assertEquals(PublicationIdentifier(IdentifierType.ISBN_10, "080442957X"), IdentifierDetector.isbn("urn:isbn:080442957x"))
        assertNull(IdentifierDetector.isbn("1234567890", schemeSaysIsbn = true))
        assertEquals("ISBN-10 and its ISBN-13 are the same publication", "9780306406157",
            IdentifierDetector.isbn("0306406152", schemeSaysIsbn = true)!!.isbn13)
        assertTrue(IdentifierDetector.isbn("0306406152", true)!!.sameAs(isbn13("9780306406157")))
    }

    @Test fun invalidChecksumsWrongLengthsAndOrdinaryNumbersAreRejected() {
        for (raw in listOf("9780306406158", "978030640615", "97803064061577", "2012", "352", "001", "1234567890128", "9790000000000",
            "Saga 001 (2012)", "uuid:9780306406157-x", "", "ISBN"))
            assertNull(raw, IdentifierDetector.isbn(raw))
    }

    @Test fun upcAWithOptionalSupplementAndGtinFormsWithChecksums() {
        assertEquals(PublicationIdentifier(IdentifierType.UPC_A, "036000291452"), IdentifierDetector.upc("0 36000 29145 2"))
        assertEquals(PublicationIdentifier(IdentifierType.UPC_A, "036000291452-00111"), IdentifierDetector.upc("036000291452 00111"))
        assertEquals(PublicationIdentifier(IdentifierType.UPC_A, "036000291452-01"), IdentifierDetector.gtin("03600029145201"))
        assertNull("bad UPC check digit", IdentifierDetector.upc("036000291453"))
        assertNull("a 3-digit tail is not a supplement", IdentifierDetector.gtin("036000291452001"))
        assertEquals(PublicationIdentifier(IdentifierType.EAN_13, "4006381333931"), IdentifierDetector.gtin("4006381333931"))
        assertEquals("a Bookland GTIN is the ISBN-13 it is", isbn13("9780306406157"), IdentifierDetector.gtin("9780306406157"))
        assertNull(IdentifierDetector.gtin("12-34"))
        assertNull(IdentifierDetector.upc("4006381333931"))
        assertFalse("UPC supplements must match", IdentifierDetector.upc("036000291452 00111")!!.sameAs(IdentifierDetector.upc("036000291452 00211")!!))
    }
}
