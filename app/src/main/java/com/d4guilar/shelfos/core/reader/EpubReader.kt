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
import org.readium.r2.shared.publication.services.positions
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
    /** The TOC entry the reader's current position unambiguously belongs to, or null if the TOC has no matching
     * resource *or* if more than one same-resource entry is equally plausible and none can be ruled out — see
     * [matchChapter]'s doc comment for the exact fallback contract. Null is a legitimate result, not an error. */
    fun currentChapter(locator: Locator): EpubChapter? = matchChapter(chapters, locator.href.toString(), locator.locations.fragments)
    private var epubPositionsCache: List<EpubPosition>? = null
    /**
     * Lazily computes and caches Readium's publication-wide position list (see [EpubPosition]) for this session's
     * lifetime — at most one pass over the reading order's resource metadata. The pinned Readium 3.4.0
     * `EpubParser` wires a `PositionsService` (`EpubPositionsService`) onto every EPUB `Publication` by default; it
     * is Container/Resource-based, not `HttpClient`-based, so this works fully offline exactly like every other
     * publication access in this reader. This function itself does not choose a dispatcher — `EpubReaderViewModel`
     * (its only caller) dispatches it onto `Dispatchers.IO` after the session is already open, so it never delays
     * showing the reader or blocks the caller's dispatcher; see that call site for the actual off-Main guarantee.
     */
    suspend fun epubPositions(): List<EpubPosition> = epubPositionsCache ?: publication.positions()
        .mapNotNull { locator -> locator.locations.position?.let { EpubPosition(locator.href.toString(), locator.locations.progression ?: 0.0, it) } }
        .also { epubPositionsCache = it }
    override fun close() = publication.close()
}

/**
 * Matches the reader's current position to a TOC entry, or returns null when no entry can be identified *without
 * guessing* — this is a legitimate, expected result, not an error (see [EpubSession.currentChapter]/
 * [currentChapterId]'s callers). Not simple href equality: several chapters can share one resource at different
 * fragments, and the current position may carry no fragment at all. `Locator.Locations.fragments` can itself
 * carry more than one candidate — a pinned-navigator locator is not guaranteed to put the fragment that actually
 * corresponds to a TOC entry first — so every locator fragment is checked, in order, before falling back; the
 * first one that exactly matches a same-resource chapter's own fragment wins.
 *
 * **Fallback contract, once *none* of the locator's fragments produce an exact match (Codex R2, corrected
 * 2026-09-26 after an earlier version of this fallback silently picked an arbitrary same-resource entry):**
 * 1. If exactly one same-resource chapter carries no fragment of its own (a genuine "whole chapter" TOC entry),
 *    that one is unambiguous and wins — this is a real, defensible choice, not a guessed position.
 * 2. Otherwise, if there is exactly one same-resource candidate at all (regardless of whether it has a fragment),
 *    it is unambiguous by elimination and wins.
 * 3. Otherwise — multiple same-resource candidates, none identifiable as *the* one (several fragment-only
 *    entries with no locator fragment to distinguish them, or several indistinguishable resource-level entries)
 *    — this returns **null**, deliberately. Arbitrarily picking "the first one" here would present a specific,
 *    named chapter as current when that is not actually known; a caller correctly showing *no* current chapter is
 *    more honest than one confidently showing the wrong one.
 *
 * A real position *within* the resource (e.g. "the nearest preceding heading") cannot be determined from
 * Locator/Link data alone without parsing the resource's own HTML content — TOC order and resource-relative
 * progression are not the same axis, and Link carries no position of its own — so this never attempts that
 * finer-grained inference either as a match or as a fallback; see docs/PHASE_2_PLAN.md's 2B.1 section for the
 * full assessment. Pure/plain so it is directly unit-testable without any Readium or Android type, mirroring
 * `core.input`'s `resolveInputSources`/`isGamepadSource`.
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
 * `currentLocator` fragments. Concretely, for a same-resource TOC with more than one fragment-only entry and no
 * resource-level entry (exactly `epubWithFragmentedChapter`'s shape), real in-app navigation today lands on this
 * function's ambiguous/null case rather than tracking the actually-navigated-to fragment — which, per the
 * contract above, is the correct, honest outcome given what the navigator actually reports, not a bug to route
 * around by guessing. See `EpubChapterHighlightTest`'s fragmented-fixture test for what is verified today.
 */
internal fun matchChapter(chapters: List<EpubChapter>, currentResource: String, currentFragments: List<String>): EpubChapter? {
    val sameResource = chapters.filter { it.resource == currentResource }
    if (sameResource.isEmpty()) return null
    currentFragments.forEach { fragment -> sameResource.find { it.fragment == fragment }?.let { return it } }
    val resourceLevel = sameResource.filter { it.fragment == null }
    if (resourceLevel.size == 1) return resourceLevel.first()
    if (sameResource.size == 1) return sameResource.first()
    return null
}

/**
 * Recomputes the highlighted chapter's stable, in-memory-only row [EpubChapter.id] — never its [EpubChapter.href]
 * — from the same persisted locator JSON `EpubReaderViewModel.location()` already writes, so highlighting needs
 * no separate storage and no second navigator/session reference. Comparing by `href` cannot tell two rows with
 * the same href apart (a redundant/duplicate TOC entry, or two entries sharing one fragment); comparing by this
 * id can, since each flattened row gets its own regardless of what its href looks like. Returns null both when
 * the locator has no persisted value yet and when [EpubSession.currentChapter] cannot identify one row
 * unambiguously — either way, the correct UI response is to highlight nothing, not to guess.
 */
fun EpubSession.currentChapterId(locatorJson: String?): Int? {
    val locator = locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() } ?: return null
    return currentChapter(locator)?.id
}

/** The same total-progression percentage `EpubSurface`'s `onLocation` already reports, recomputed from a stored
 * locator JSON — used only when capturing a bookmark from a locator ShelfOS doesn't already have the live
 * `Locator` object for (bookmark snapshots only ever hold the JSON string, per AGENTS.md's "not independently
 * stored" contract). Malformed JSON yields 0 rather than crashing. */
fun locatorProgress(locatorJson: String): Int =
    runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()
        ?.let { ((it.locations.totalProgression ?: 0.0) * 100).toInt() } ?: 0

/**
 * Stable fields used to decide whether two Readium locator snapshots describe the same EPUB reading location.
 * Readium may add title, total progression, position, text, or other transient fields to a later snapshot without
 * moving the reader, so complete serialized JSON equality is unsuitable for the live "already bookmarked" UI.
 */
internal data class EpubBookmarkLocation(
    val resource: String,
    val fragments: List<String> = emptyList(),
    val progression: Double? = null,
    val position: Int? = null,
)

/**
 * Compares two normalized EPUB locations conservatively. A shared resource is mandatory. Two available Readium
 * publication positions are authoritative; otherwise matching fragments are authoritative; otherwise identical
 * resource-relative progression is the narrow fallback. No tolerance is applied without evidence that Readium
 * jitters progression while the physical reading location remains unchanged.
 */
internal fun sameEpubBookmarkLocation(first: EpubBookmarkLocation, second: EpubBookmarkLocation): Boolean {
    if (first.resource != second.resource) return false
    if (first.position != null && second.position != null) return first.position == second.position
    if (first.fragments.isNotEmpty() && second.fragments.isNotEmpty()) {
        return first.fragments.any(second.fragments::contains)
    }
    return first.progression != null && second.progression != null && first.progression == second.progression
}

/** Parses stored/live Readium 3.4 locator snapshots and applies [sameEpubBookmarkLocation]. */
internal fun sameEpubBookmarkLocation(firstJson: String, secondJson: String): Boolean {
    fun parse(json: String): EpubBookmarkLocation? =
        runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()?.let { locator ->
            EpubBookmarkLocation(
                resource = locator.href.toString(),
                fragments = locator.locations.fragments,
                progression = locator.locations.progression,
                position = locator.locations.position,
            )
        }
    val first = parse(firstJson) ?: return false
    val second = parse(secondJson) ?: return false
    return sameEpubBookmarkLocation(first, second)
}

/**
 * One entry of Readium's publication-wide position list (`Publication.positions()`), reduced to the fields needed
 * to resolve a bookmark's stable "Location N" (see [resolveEpubLocation]). Each entry marks where a segment
 * *starts* within its resource — [progression] is that segment's own starting resource-relative progression, not
 * a midpoint (see [resolveEpubLocation]'s floor/segment-start semantics). The pinned Readium 3.4.0
 * `EpubPositionsService` (wired automatically by `EpubParser`) computes these by dividing each resource into
 * ~1024-byte segments (`ReflowableStrategy.recommended`'s default) using the resource's archive entry length when
 * the container reports one, falling back to the resource's own decoded length otherwise — confirmed via `javap`
 * decompilation of `ArchiveEntryLength.positionCount` on the pinned artifact (no source jar is available for this
 * release): it reads `Resource.properties().archive?.entryLength`, and only calls `Resource.length()` when that is
 * null. Either way this is computed entirely offline from container/resource metadata (no network, no navigator,
 * no rendering) and is a purely structural division of the unchanging EPUB file: unlike the reader's own rendered
 * page, it does not move with font size, line height, margins, orientation, or screen size for a given
 * publication, which is what makes it safe to label "Location N" rather than a fabricated "Page N" for a
 * reflowable EPUB (Phase 2B.2.1). [position] is the global, 1-based index Readium itself assigns across the whole
 * reading order (never reset per resource).
 */
data class EpubPosition(val resource: String, val progression: Double, val position: Int)

/**
 * Resolves the stable "Location N" (see [EpubPosition]) for a stored bookmark's location, or null when it cannot
 * be identified without guessing — deliberately mirroring [matchChapter]'s honesty contract (the two are kept
 * separate functions since they resolve different Readium concepts: TOC entries vs. archive-derived positions).
 *
 * **Segment-start / floor semantics (Codex R2, corrected 2026-09-27 after an earlier version picked the
 * numerically *nearest* position instead):** each [EpubPosition] marks where a segment *starts*, not its midpoint
 * or center. The correct Location for a bookmark at some progression within a resource is therefore the position
 * with the greatest `progression` that is still `<=` the bookmark's own progression — the segment the bookmark
 * actually falls inside — never the numerically closest one. For same-resource segment starts `0.0 / 0.4 / 0.8`,
 * a bookmark at progression `0.7` resolves to the `0.4` segment (it has not reached `0.8` yet), even though `0.8`
 * is numerically closer; `0.99` and `1.0` both resolve to the final (`0.8`) segment, since no later segment start
 * exists for that resource.
 *
 * [target]'s progression must be a valid, finite value in `0.0..1.0` inclusive (a real Readium progression can
 * never legitimately fall outside that range); a missing, negative, `>1`, `NaN`, or infinite value yields null
 * rather than a silently clamped or guessed result — this is genuinely missing/invalid evidence, not an edge case
 * to paper over. Candidate [positions] with an invalid `progression` (should not occur in a real Readium catalog,
 * but not assumed) are likewise ignored rather than trusted blindly. No entry sharing [target]'s resource, after
 * that filtering, also yields null. Pure and Readium-free so it is directly unit-testable, mirroring
 * [matchChapter]/[sameEpubBookmarkLocation].
 */
internal fun resolveEpubLocation(positions: List<EpubPosition>, target: EpubBookmarkLocation): Int? {
    val progression = target.progression?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
    return positions.asSequence()
        .filter { it.resource == target.resource && it.progression.isFinite() && it.progression in 0.0..1.0 }
        .filter { it.progression <= progression }
        .maxByOrNull { it.progression }
        ?.position
}

/**
 * The parts available to describe one EPUB bookmark's location to a reader, independent of how they are assembled
 * into text (see [bookmarkDisplayText]/[bookmarkAccessibilityText]). [chapterTitle] and [location] are each
 * independently allowed to be unavailable, per [matchChapter]'s and [resolveEpubLocation]'s own honesty contracts
 * — never fabricated just to fill in a prettier row. [progress] is always present: the bookmark's own stored
 * display/sort snapshot, not recomputed live.
 */
data class EpubBookmarkPresentation(val chapterTitle: String?, val location: Int?, val progress: Int)

/**
 * Builds one bookmark row's [EpubBookmarkPresentation] from its stored locator: the chapter title, via the same
 * 2B.1 [matchChapter]-based lookup already used for the Chapters dialog's highlight, and the stable "Location N"
 * (see [resolveEpubLocation]), each only when unambiguously derivable. Never persisted — the stored
 * [Bookmark.locator] remains authoritative and both are recomputed from it every time, exactly like the Chapters
 * dialog never persists a chapter title either. [positions] is supplied by the caller (see
 * `EpubSession.epubPositions`) rather than fetched here, so this stays a plain, fast, synchronous call usable
 * directly from Compose.
 */
fun EpubSession.presentBookmark(locatorJson: String, progress: Int, positions: List<EpubPosition>): EpubBookmarkPresentation {
    val locator = runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()
    val chapterTitle = locator?.let(::currentChapter)?.title
    val location = locator?.let { resolveEpubLocation(positions, EpubBookmarkLocation(it.href.toString(), progression = it.locations.progression)) }
    return EpubBookmarkPresentation(chapterTitle, location, progress)
}

/**
 * A bookmark row's visible text: a place in the book, not merely a progress meter. Never fabricates a location or
 * chapter that could not be determined (see [EpubBookmarkPresentation]), and never says "Page N" — a reflowable
 * EPUB's visual page count is unstable across typography, margins, screen size and orientation, unlike the
 * structural "Location N" resolved by [resolveEpubLocation]. Two lines when a chapter title is available (it may
 * be long), one line otherwise.
 */
internal fun bookmarkDisplayText(p: EpubBookmarkPresentation): String = when {
    p.chapterTitle != null && p.location != null -> "${p.chapterTitle}\nLocation ${p.location} · ${p.progress}% through book"
    p.chapterTitle != null -> "${p.chapterTitle}\n${p.progress}% through book"
    p.location != null -> "Location ${p.location} · ${p.progress}% through book"
    else -> "${p.progress}% through book"
}

/**
 * A bookmark row's accessible description: one spoken sentence (used verbatim, prefixed with "Bookmark, "/"Delete
 * bookmark, " at the call site) rather than [bookmarkDisplayText]'s two visual lines, with "%" spelled out as
 * "percent" the way a screen reader would otherwise have to expand it anyway.
 */
internal fun bookmarkAccessibilityText(p: EpubBookmarkPresentation): String =
    listOfNotNull(p.chapterTitle, p.location?.let { "Location $it" }, "${p.progress} percent through book").joinToString(", ")

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
