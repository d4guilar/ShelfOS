// SPDX-License-Identifier: MPL-2.0
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.d4guilar.shelfos.core.reader

import android.content.Context
import android.net.Uri
import androidx.fragment.app.FragmentFactory
import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.preferences.*
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.*
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.*
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.TransformingResource
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import java.io.IOException

class EpubReaderFactory(private val context: Context, private val files: PublicationFiles) {
    private val offlineClient = object : HttpClient {
        override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> = Try.failure(HttpError.IO(IOException("Offline publication reader")))
    }
    suspend fun open(item: LibraryItem): EpubSession = withContext(Dispatchers.IO) {
        files.open(item).use { descriptor -> ArchivePolicy.open(descriptor).use { zip ->
            val entries = ArchivePolicy.entries(zip)
            val markup = entries.filter { it.name.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm", "svg", "xml", "opf", "ncx", "css") }
            if (markup.any { it.size > 8 * 1024 * 1024 } || markup.sumOf { it.size } > 128 * 1024 * 1024)
                throw PublicationException(PublicationProblem.TOO_LARGE, "This EPUB has exceptionally large content resources and is not supported yet.")
            entries.filter { it.name.endsWith(".opf", true) || it.name.endsWith(".xml", true) || it.name.endsWith(".ncx", true) }.forEach { entry ->
                val xml = zip.readBytes(entry, 8L * 1024 * 1024)?.toString(Charsets.UTF_8)
                    ?: throw PublicationException(PublicationProblem.TOO_LARGE, "This EPUB has exceptionally large content resources and is not supported yet.")
                if (xml.contains("<!ENTITY", true)) throw PublicationException(PublicationProblem.UNSUPPORTED_FORMAT, "This EPUB contains unsupported entity declarations.")
            }
        } }
        val url = item.managedPath?.let { files.managedFile(it).toUrl(isDirectory = false) }
            ?: Uri.parse(item.sourceUri).toAbsoluteUrl() ?: throw PublicationException(PublicationProblem.SOURCE_UNAVAILABLE)
        val asset = AssetRetriever(context.contentResolver, offlineClient).retrieve(url)
            .getOrElse { throw PublicationException(PublicationProblem.UNREADABLE) }
        try {
            val publication = PublicationOpener(EpubParser(offlineClient), onCreatePublication = {
                container = TransformingContainer(container) { resourceUrl, resource ->
                    if (resourceUrl.toString().substringBefore('?').substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm", "svg"))
                        TransformingResource(resource) { data -> Try.success(sanitizeEpubHtml(data)) }
                    else resource
                }
            }).open(asset, allowUserInteraction = false).getOrElse { error ->
                throw if (error is PublicationOpener.OpenError.FormatNotSupported) PublicationException(PublicationProblem.CORRUPT, "This EPUB is invalid or unsupported.")
                else PublicationException(PublicationProblem.UNREADABLE)
            }
            if (publication.isRestricted) {
                publication.close()
                throw PublicationException(PublicationProblem.PROTECTED)
            }
            if (publication.metadata.layout == Layout.FIXED) {
                publication.close()
                throw PublicationException(PublicationProblem.UNSUPPORTED_LAYOUT)
            }
            EpubSession(publication)
        } catch (error: Throwable) { asset.close(); throw error }
    }
}

/** Only the rendition is transformed; no bytes are written back to the publication. */
internal fun sanitizeEpubHtml(bytes: ByteArray): ByteArray {
    val document = Jsoup.parse(bytes.inputStream(), null, "")
    document.select("script, iframe, object, embed, form, base, meta[http-equiv]").remove()
    document.allElements.forEach { element ->
        element.attributes().asList().forEach { attribute ->
            val key = attribute.key.lowercase()
            val value = attribute.value.trim().lowercase()
            if (key.startsWith("on") || ((key == "href" || key == "src" || key == "xlink:href") &&
                        (value.startsWith("javascript:") || value.startsWith("file:") || value.startsWith("http:") || value.startsWith("https:") || value.startsWith("//"))))
                element.removeAttr(attribute.key)
        }
    }
    document.outputSettings().charset(Charsets.UTF_8).prettyPrint(false)
    return document.outerHtml().toByteArray(Charsets.UTF_8)
}

class EpubSession internal constructor(internal val publication: Publication) : AutoCloseable {
    val chapters: List<Pair<String, String>> = buildList {
        fun addLinks(links: List<Link>, depth: Int = 0) { links.forEach { link ->
            add(("  ".repeat(depth) + (link.title ?: "Chapter ${size + 1}")) to link.href.toString())
            addLinks(link.children, (depth + 1).coerceAtMost(4))
        } }
        addLinks(publication.tableOfContents.ifEmpty { publication.readingOrder })
    }
    fun fragmentFactory(locator: String?, preferences: ReaderPreferences, dark: Boolean, category: MediaCategory): FragmentFactory =
        EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = try { locator?.let { Locator.fromJSON(JSONObject(it)) } } catch (_: Exception) { null },
            initialPreferences = epubPreferences(preferences, dark, category),
            configuration = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false),
        )
    fun chapter(href: String): Link? {
        fun find(links: List<Link>): Link? = links.firstNotNullOfOrNull { if (it.href.toString() == href) it else find(it.children) }
        return find(publication.tableOfContents) ?: find(publication.readingOrder)
    }
    override fun close() = publication.close()
}

internal fun epubPreferences(p: ReaderPreferences, dark: Boolean, category: MediaCategory): EpubPreferences {
    val palette = p.palette ?: PagePalette.THEME
    val night = palette == PagePalette.DARK || (palette == PagePalette.THEME && dark)
    return EpubPreferences(
        fontFamily = FontFamily(if (p.font == BookFont.SANS) "sans-serif" else "serif"),
        fontSize = p.fontSize, lineHeight = p.lineHeight, pageMargins = p.margins,
        textAlign = if (p.justified == true) TextAlign.JUSTIFY else TextAlign.START,
        publisherStyles = false, scroll = p.scroll, columnCount = ColumnCount.ONE,
        theme = if (night) Theme.DARK else if (palette == PagePalette.PAPER) Theme.SEPIA else Theme.LIGHT,
        backgroundColor = Color(if (night) 0xFF0B0B0B.toInt() else if (palette == PagePalette.PAPER) 0xFFF2E8D0.toInt() else 0xFFF7F7F5.toInt()),
        textColor = Color(if (night) 0xFFF3F3EF.toInt() else 0xFF111111.toInt()),
        readingProgression = if (readingDirection(category, p.direction) == ReadingDirection.RTL) ReadingProgression.RTL else ReadingProgression.LTR,
    )
}
