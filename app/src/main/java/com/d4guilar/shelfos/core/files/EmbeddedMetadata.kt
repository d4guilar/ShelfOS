// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Untrusted embedded evidence proposed during import review. User corrections always win. */
data class EmbeddedMetadata(val title: String? = null, val creator: String? = null, val rightToLeftManga: Boolean = false)

/**
 * Bounded, offline reads of EPUB package metadata and CBZ `ComicInfo.xml`.
 * jsoup's XML parser never resolves DTDs or external entities; oversized documents are ignored.
 * Metadata is best-effort: any failure falls back to filename evidence and never blocks import.
 */
object EmbeddedMetadataReader {
    private const val MAX_XML_BYTES = 1024L * 1024

    fun epub(zip: SeekableZip): EmbeddedMetadata = runCatching {
        val container = xml(zip, "META-INF/container.xml") ?: return EmbeddedMetadata()
        val packagePath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?.takeIf { it.isNotBlank() && ArchivePolicy.safeName(it) } ?: return EmbeddedMetadata()
        epubPackage(xml(zip, packagePath) ?: return EmbeddedMetadata())
    }.getOrDefault(EmbeddedMetadata())

    fun comicInfo(zip: SeekableZip): EmbeddedMetadata = runCatching {
        val name = zip.entries.firstOrNull { it.name.equals("ComicInfo.xml", true) }?.name ?: return EmbeddedMetadata()
        comicInfo(xml(zip, name) ?: return EmbeddedMetadata())
    }.getOrDefault(EmbeddedMetadata())

    internal fun epubPackage(document: Document): EmbeddedMetadata {
        val metadata = document.allElements.firstOrNull { it.localName() == "metadata" } ?: return EmbeddedMetadata()
        return EmbeddedMetadata(
            metadata.firstLocal("title")?.let(::clean),
            metadata.firstLocal("creator")?.let(::clean),
        )
    }

    internal fun comicInfo(document: Document): EmbeddedMetadata {
        val info = document.allElements.firstOrNull { it.localName().equals("ComicInfo", true) } ?: return EmbeddedMetadata()
        fun field(name: String) = info.children().firstOrNull { it.localName().equals(name, true) }?.let(::clean)
        val series = field("Series")
        val number = field("Number")
        val title = field("Title") ?: series?.let { if (number != null) "$it $number" else it }
        return EmbeddedMetadata(title, field("Writer"), field("Manga").equals("YesAndRightToLeft", ignoreCase = true))
    }

    internal fun parse(xml: String): Document? =
        if (xml.contains("<!ENTITY", true)) null else Jsoup.parse(xml, "", Parser.xmlParser())

    private fun xml(zip: SeekableZip, name: String): Document? {
        val entry = zip.entry(name) ?: return null
        return parse((zip.readBytes(entry, MAX_XML_BYTES) ?: return null).toString(Charsets.UTF_8))
    }

    private fun Element.localName() = tagName().substringAfter(':')
    private fun Element.firstLocal(name: String) = children().firstOrNull { it.localName() == name }
    private fun clean(element: Element): String? = element.text().replace(Regex("\\s+"), " ").trim().take(300).ifBlank { null }
}
