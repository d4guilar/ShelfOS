// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Untrusted embedded evidence proposed during import review. User corrections always win. */
data class EmbeddedMetadata(val title: String? = null, val creator: String? = null, val rightToLeftManga: Boolean = false,
    /** ComicInfo `<Page Image="N" Type="FrontCover"/>`, unvalidated; see `domain.library.chooseArchiveCover`. */
    val frontCoverImage: Int? = null)

/** An EPUB's declared cover image: a safe archive entry path resolved against the OPF, and its declared media type. */
data class EpubCover(val path: String, val mediaType: String?)

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

    /**
     * The OPF-declared cover image (EPUB3 `properties="cover-image"`, else EPUB2 `<meta name="cover">`), or null when the
     * package declares none or the reference is unusable (missing, remote, escaping the archive or unsafe).
     */
    fun epubCover(zip: SeekableZip): EpubCover? {
        val container = xml(zip, "META-INF/container.xml") ?: return null
        val packagePath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
            ?.takeIf { it.isNotBlank() && ArchivePolicy.safeName(it) } ?: return null
        return epubCover(xml(zip, packagePath) ?: return null, packagePath)
    }

    internal fun epubCover(document: Document, packagePath: String): EpubCover? {
        val items = document.allElements.filter { it.localName() == "item" }
        val item = items.firstOrNull { el -> el.attr("properties").split(Regex("\\s+")).any { it == "cover-image" } }
            ?: document.allElements.firstOrNull { it.localName() == "meta" && it.attr("name") == "cover" }?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { ref -> items.firstOrNull { it.attr("id") == ref } ?: items.firstOrNull { it.attr("href") == ref } }
            ?: return null
        val path = resolveHref(packagePath.substringBeforeLast('/', ""), item.attr("href")) ?: return null
        return EpubCover(path, item.attr("media-type").trim().lowercase().ifBlank { null })
    }

    /** Resolves an OPF-relative href to an archive entry path; null if remote, absolute, escaping the root or unsafe. */
    internal fun resolveHref(base: String, href: String): String? {
        val raw = href.substringBefore('#').substringBefore('?').trim()
        if (raw.isEmpty() || raw.startsWith('/') || raw.contains(':') || raw.contains('\\')) return null
        val decoded = try { percentDecode(raw) } catch (_: IllegalArgumentException) { return null }
        val segments = ArrayList<String>()
        for (part in (if (base.isEmpty()) emptyList() else base.split('/')) + decoded.split('/')) when (part) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments += part
        }
        return segments.joinToString("/").takeIf { it.isNotEmpty() && ArchivePolicy.safeName(it) }
    }

    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                require(i + 2 <= value.lastIndex)
                out.write(value.substring(i + 1, i + 3).toInt(16)); i += 3
            } else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return out.toString("UTF-8")
    }

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
        val frontCover = info.children().firstOrNull { it.localName().equals("Pages", true) }?.children()
            ?.firstOrNull { it.localName().equals("Page", true) && it.attr("Type").trim().equals("FrontCover", true) }
            ?.attr("Image")?.trim()?.toIntOrNull()
        return EmbeddedMetadata(title, field("Writer"), field("Manga").equals("YesAndRightToLeft", ignoreCase = true), frontCover)
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
