// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.reader.isXmlContentDocument
import com.d4guilar.shelfos.core.reader.sanitizeEpubHtml
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.util.mediatype.MediaType
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Post-Phase-2 EPUB XHTML regression, at the transform boundary: the rendition sanitizer must keep a well-formed XHTML
 * content document well-formed XML (the WebView parses `application/xhtml+xml` as XML) and structurally unchanged,
 * while still stripping active content. Device-level proof lives in `EpubStrictXhtmlRenderingTest`.
 */
class EpubXhtmlSanitizerTest {
    private val strictXhtml = """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en" lang="en"><head>
<meta charset="utf-8"/><title>Original Chapter</title>
<link href="style.css" rel="stylesheet" type="text/css"/>
<meta name="generator" content="ShelfOS original fixture"/>
<meta http-equiv="refresh" content="0; url=https://example.org"/>
<script>document.title = 'injected';</script>
</head>
<body><section epub:type="chapter"><h2><a id="start"/>Original Chapter</h2>
<p onclick="bad()">First line<br/>second line &amp; more.</p>
<hr/>
<p><a href="https://example.org">External</a> <a href="#start">Internal</a></p>
</section></body></html>"""

    private fun parseXml(text: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(text.byteInputStream())

    private fun sanitizeXml(source: String) = sanitizeEpubHtml(source.toByteArray(), xml = true).toString(Charsets.UTF_8)

    @Test fun sourceFixtureIsItselfWellFormed() { parseXml(strictXhtml) }

    @Test fun xhtmlStaysWellFormedXmlAfterSanitizing() {
        val out = sanitizeXml(strictXhtml)
        val document = try { parseXml(out) } catch (error: Exception) { throw AssertionError("not well-formed XML:\n$out", error) }
        assertEquals("html", document.documentElement.localName)
        assertEquals("http://www.w3.org/1999/xhtml", document.documentElement.namespaceURI)
        assertTrue(out.startsWith("<?xml"))
    }

    @Test fun selfClosedEmptyAnchorStaysOneEmptyElement() {
        val document = parseXml(sanitizeXml(strictXhtml))
        val anchors = document.getElementsByTagNameNS("*", "a")
        val start = (0 until anchors.length).map { anchors.item(it) as Element }.filter { it.getAttribute("id") == "start" }
        assertEquals("an HTML parser duplicates this id", 1, start.size)
        assertEquals("an HTML parser wraps the heading text in it", 0, start.single().childNodes.length)
        assertEquals("Original Chapter", document.getElementsByTagNameNS("*", "h2").item(0).textContent)
    }

    @Test fun activeContentIsStillStrippedInXmlMode() {
        val out = sanitizeXml(strictXhtml)
        val document = parseXml(out)
        assertEquals(0, document.getElementsByTagNameNS("*", "script").length)
        assertFalse("onclick" in out)
        assertFalse("http-equiv" in out)
        assertFalse("https://example.org" in out)
        assertTrue("same-publication links survive", "href=\"#start\"" in out)
    }

    @Test fun publicationHeadAndTextArePreserved() {
        val out = sanitizeXml(strictXhtml)
        val document = parseXml(out)
        assertEquals(1, document.getElementsByTagNameNS("*", "link").length)
        assertEquals(2, document.getElementsByTagNameNS("*", "meta").length) // charset + generator; http-equiv removed
        assertEquals(1, document.getElementsByTagNameNS("*", "br").length)
        assertEquals(1, document.getElementsByTagNameNS("*", "hr").length)
        assertTrue("epub:type=\"chapter\"" in out)
        assertTrue("xml:lang=\"en\"" in out)
        assertTrue("second line &amp; more." in out)
    }

    @Test fun xmlDeclarationAlwaysMatchesTheUtf8OutputBytes() {
        val latin1 = """<?xml version="1.0" encoding="ISO-8859-1"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head><body><p>Caf""".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0xE9.toByte()) + "</p></body></html>".toByteArray(Charsets.ISO_8859_1)
        val out = sanitizeEpubHtml(latin1, xml = true).toString(Charsets.UTF_8)
        assertTrue(out, out.startsWith("<?xml") && "encoding=\"UTF-8\"" in out)
        assertEquals("Café", parseXml(out).getElementsByTagNameNS("*", "p").item(0).textContent)
    }

    @Test fun xhtmlWithoutDeclarationOrUsingHtmlNamedEntitiesStillComesOutWellFormed() {
        val out = sanitizeXml("""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>T</title></head><body><p>a&nbsp;b<br/>c</p></body></html>""")
        assertEquals("a bc", parseXml(out).getElementsByTagNameNS("*", "p").item(0).textContent)
    }

    @Test fun textHtmlDocumentsKeepTheHtmlPath() {
        val out = sanitizeEpubHtml("<html><head><meta charset=utf-8></head><body><p>a<br>b</p><script>x()</script></body></html>".toByteArray())
            .toString(Charsets.UTF_8)
        assertTrue(out, "<br>" in out && "<meta charset=\"utf-8\">" in out)
        assertFalse("<script" in out)
    }

    @Test fun xmlParsingFollowsTheDeclaredMediaTypeNotTheFileName() {
        assertTrue(isXmlContentDocument(MediaType.XHTML, "html")) // .html declared application/xhtml+xml
        assertTrue(isXmlContentDocument(MediaType.XHTML, "htm"))
        assertTrue(isXmlContentDocument(MediaType.SVG, "svg"))
        assertFalse(isXmlContentDocument(MediaType.HTML, "xhtml")) // served as text/html, parsed as HTML
        // Not in the manifest: fall back to the extension.
        assertTrue(isXmlContentDocument(null, "xhtml"))
        assertTrue(isXmlContentDocument(null, "svg"))
        assertFalse(isXmlContentDocument(null, "html"))
        assertFalse(isXmlContentDocument(null, "htm"))
    }
}
