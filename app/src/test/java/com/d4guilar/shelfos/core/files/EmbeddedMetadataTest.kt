// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import com.d4guilar.shelfos.domain.metadata.*
import org.junit.Assert.*
import org.junit.Test

/** Phase 4.5B embedded metadata fixtures: EPUB2/EPUB3 OPF and ComicInfo, including malformed and default values. */
class EmbeddedMetadataTest {
    private fun opf(metadata: String) = EmbeddedMetadataReader.epubPackage(EmbeddedMetadataReader.parse(
        """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" xmlns:opf="http://www.idpf.org/2007/opf" version="3.0">
           <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$metadata</metadata></package>""")!!)
    private fun comicInfo(body: String) = EmbeddedMetadataReader.comicInfo(EmbeddedMetadataReader.parse(
        """<?xml version="1.0"?><ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">$body</ComicInfo>""")!!)

    @Test fun epub3PackageFieldsIdentifiersSubtitleAndCollection() {
        val m = opf("""
            <dc:identifier id="uid">urn:uuid:12345678-1234-1234-1234-123456789012</dc:identifier>
            <dc:identifier id="isbn">978-0-306-40615-7</dc:identifier>
            <dc:title id="t1">The Main Title</dc:title><dc:title id="t2">A Subtitle</dc:title>
            <meta refines="#t2" property="title-type">subtitle</meta>
            <dc:creator>Jane Author</dc:creator><dc:publisher>Example Press</dc:publisher>
            <dc:date>2012-10-10T00:00:00Z</dc:date><dc:language>en-GB</dc:language>
            <dc:description>&lt;p&gt;A &lt;b&gt;fine&lt;/b&gt; book.&lt;/p&gt;</dc:description>
            <meta property="belongs-to-collection" id="c1">The Series</meta>
            <meta refines="#c1" property="collection-type">series</meta><meta refines="#c1" property="group-position">2</meta>""")
        assertEquals("The Main Title", m.title)
        assertEquals("Jane Author", m.creator)
        assertEquals(MetadataValues(subtitle = "A Subtitle", description = "A fine book.", publisher = "Example Press",
            publishedDate = "2012-10-10", language = "en-GB", series = "The Series", volume = "2",
            identifiers = listOf(PublicationIdentifier(IdentifierType.ISBN_13, "9780306406157"))), m.values)
    }

    @Test fun epub2SchemeIsbn10CalibreSeriesAndInvalidValuesDropped() {
        val m = opf("""
            <dc:identifier opf:scheme="ISBN">0-306-40615-2</dc:identifier><dc:identifier opf:scheme="ISBN">123</dc:identifier>
            <dc:identifier>0306406152</dc:identifier>
            <dc:title>Old Book</dc:title><dc:date>sometime</dc:date><dc:language>English</dc:language>
            <meta name="calibre:series" content="Old Series"/><meta name="calibre:series_index" content="3.0"/>""")
        assertEquals(listOf(PublicationIdentifier(IdentifierType.ISBN_10, "0306406152")), m.values.identifiers)
        assertEquals("Old Series", m.values.series)
        assertEquals("3", m.values.volume)
        assertNull(m.values.publishedDate)
        assertNull(m.values.language)
    }

    @Test fun comicInfoFieldsCreditsGtinAndUnknownDefaults() {
        val m = comicInfo("""
            <Title>Chapter One</Title><Series>Saga</Series><Number>001</Number><Volume>2012</Volume>
            <Summary>Two soldiers…</Summary><Year>2012</Year><Month>3</Month><Day>14</Day>
            <Writer>Brian K. Vaughan</Writer><Penciller>Fiona Staples</Penciller><Letterer>Fonografiks, Steven Finch</Letterer>
            <Publisher>Image</Publisher><LanguageISO>en</LanguageISO><GTIN>0 36000 29145 2 00111</GTIN><Manga>No</Manga>""")
        assertEquals("Chapter One", m.title)
        assertEquals("Brian K. Vaughan", m.creator)
        assertFalse(m.rightToLeftManga)
        assertEquals(MetadataValues(description = "Two soldiers…", publisher = "Image", publishedDate = "2012-03-14", language = "en",
            series = "Saga", volume = "2012", issue = "001",
            identifiers = listOf(PublicationIdentifier(IdentifierType.UPC_A, "036000291452-00111")),
            credits = listOf(Credit(CreditRole.WRITER, "Brian K. Vaughan"), Credit(CreditRole.PENCILLER, "Fiona Staples"),
                Credit(CreditRole.LETTERER, "Fonografiks"), Credit(CreditRole.LETTERER, "Steven Finch"))), m.values)
        val defaults = comicInfo("<Series>Berserk</Series><Number>1.5</Number><Volume>-1</Volume><Year>-1</Year><Month>13</Month>" +
            "<GTIN>12345</GTIN><LanguageISO>??</LanguageISO><Manga>YesAndRightToLeft</Manga><Unknown>x</Unknown>")
        assertEquals("Berserk 1.5", defaults.title)
        assertTrue(defaults.rightToLeftManga)
        assertEquals(MetadataValues(series = "Berserk", issue = "1.5"), defaults.values)
    }

    @Test fun malformedOrEntityXmlYieldsNothingAndNeverThrows() {
        assertNull(EmbeddedMetadataReader.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><ComicInfo><Title>&e;</Title></ComicInfo>"))
        val broken = EmbeddedMetadataReader.comicInfo(EmbeddedMetadataReader.parse("<ComicInfo><Title>Unclosed<Series>")!!)
        assertTrue(broken.values.identifiers.isEmpty())
        assertEquals(EmbeddedMetadata(), EmbeddedMetadataReader.comicInfo(EmbeddedMetadataReader.parse("<NotComicInfo/>")!!))
    }
}
