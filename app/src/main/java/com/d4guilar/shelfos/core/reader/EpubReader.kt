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

/**
 * One flattened Chapters-dialog row. [id] is this row's stable identity — its ordinal position in the flattened
 * TOC walk, ephemeral/in-memory only (never persisted) — used so current-chapter matching can identify *one*
 * specific row even when two rows share the same [href] (a redundant/duplicate TOC entry, or two entries using
 * the same fragment); comparing by [href] alone cannot distinguish them. [href] is the TOC link's own raw href,
 * used for navigation (matches [EpubSession.chapter]/[EpubController.chapter] exactly as before this existed) —
 * navigation is unaffected by [id]. [resource]/[fragment] are Readium's own canonical resource/fragment split for
 * this link (`Publication.locatorFromLink`), used only for current-chapter matching against a live [Locator].
 * [depth] is nesting depth (capped), used for indentation only.
 */
data class EpubChapter(val id: Int, val title: String, val href: String, val depth: Int, val resource: String, val fragment: String?)

class EpubSession internal constructor(internal val publication: Publication) : AutoCloseable {
    val chapters: List<EpubChapter> = buildList {
        fun addLinks(links: List<Link>, depth: Int = 0) { links.forEach { link ->
            // locatorFromLink is the same resolution Readium's own navigator uses to produce currentLocator, so
            // comparing against it (in matchChapter) compares two values normalized the same way, rather than
            // assuming a raw TOC href string and a live Locator's href always share one format. It can return
            // null for a link Readium cannot resolve; the chapter still appears and is still navigable via its
            // raw href (unaffected), it just falls back to that raw href for matching too, so it simply never
            // matches a live locator rather than crashing or being dropped from the list.
            val canonical = publication.locatorFromLink(link)
            add(EpubChapter(size, link.title ?: "Chapter ${size + 1}", link.href.toString(), depth,
                canonical?.href?.toString() ?: link.href.toString(), canonical?.locations?.fragments?.firstOrNull()))
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
    /** The TOC entry the reader's current position belongs to, or null if the TOC has no matching resource. */
    fun currentChapter(locator: Locator): EpubChapter? = matchChapter(chapters, locator.href.toString(), locator.locations.fragments)
    override fun close() = publication.close()
}

/**
 * Matches the reader's current position to a TOC entry. Not simple href equality: several chapters can share one
 * resource at different fragments, and the current position may carry no fragment at all. `Locator.Locations
 * .fragments` can itself carry more than one candidate — a pinned-navigator locator is not guaranteed to put the
 * fragment that actually corresponds to a TOC entry first — so every locator fragment is checked, in order,
 * before falling back; the first one that exactly matches a same-resource chapter's own fragment wins. Only once
 * *none* of the locator's fragments match anything does the fallback run: this prefers the resource-level entry
 * (no fragment of its own) if one exists, else the first same-resource entry in TOC order. A real position
 * *within* the resource (e.g. "the nearest preceding heading") cannot be determined from Locator/Link data alone
 * without parsing the resource's own HTML content — TOC order and resource-relative progression are not the same
 * axis, and Link carries no position of its own — so this never claims that finer-grained knowledge; see
 * docs/PHASE_2_PLAN.md's 2B.1 section for the full assessment. Pure/plain so it is directly unit-testable without
 * any Readium or Android type, mirroring `core.input`'s `resolveInputSources`/`isGamepadSource`.
 *
 * **Empirically confirmed limitation (2026-09-26, Codex R2/R3 remediation):** `Publication.locatorFromLink(link)`
 * *does* resolve each TOC entry's own fragment correctly (verified directly against the real pinned navigator —
 * a two-fragment fixture's two `Link`s round-tripped to `locations.fragments = ["section-one"]`/`["section-two"]`
 * as expected). But the live navigator's own `currentLocator`, after navigating to either fragment via *either*
 * `Navigator.go(Link, animated)` or `Navigator.go(Locator, animated)` (both were tried), reports only
 * `progression`/`position`/`totalProgression` — `locations.fragments` was empty in both cases, for this pinned
 * Readium 3.4.0 EPUB navigator in its default paginated (non-scroll) mode. This is a real, verified constraint of
 * the current navigator/configuration, not a ShelfOS defect: the multi-fragment matching above is still correct
 * and exercised end-to-end by `EpubChapterMatchTest`'s synthetic-locator JVM tests, and remains the right, honest
 * behavior if a future Readium version, a different reading mode, or a different code path ever does supply
 * `currentLocator` fragments — but it is not yet observably exercised by real in-app navigation, so a same-
 * resource, multi-fragment TOC's *live* current-chapter highlight currently tracks by the fallback rule (the
 * first same-resource entry) regardless of which same-resource fragment was actually navigated to, until that
 * navigator behavior changes. See `EpubChapterHighlightTest`'s fragmented-fixture test for what is honestly
 * verified today given this constraint.
 */
internal fun matchChapter(chapters: List<EpubChapter>, currentResource: String, currentFragments: List<String>): EpubChapter? {
    val sameResource = chapters.filter { it.resource == currentResource }
    if (sameResource.isEmpty()) return null
    currentFragments.forEach { fragment -> sameResource.find { it.fragment == fragment }?.let { return it } }
    return sameResource.find { it.fragment == null } ?: sameResource.first()
}

/**
 * Recomputes the highlighted chapter's stable, in-memory-only row [EpubChapter.id] — never its [EpubChapter.href]
 * — from the same persisted locator JSON `EpubReaderViewModel.location()` already writes, so highlighting needs
 * no separate storage and no second navigator/session reference. Comparing by `href` cannot tell two rows with
 * the same href apart (a redundant/duplicate TOC entry, or two entries sharing one fragment); comparing by this
 * id can, since each flattened row gets its own regardless of what its href looks like.
 */
fun EpubSession.currentChapterId(locatorJson: String?): Int? {
    val locator = locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() } ?: return null
    return currentChapter(locator)?.id
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
