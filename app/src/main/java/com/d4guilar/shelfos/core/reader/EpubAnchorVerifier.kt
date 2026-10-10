// SPDX-License-Identifier: MPL-2.0
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.d4guilar.shelfos.core.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeVisitor
import org.readium.r2.shared.publication.Locator

/*
 * Stale-anchor protection (Phase 4B). Readium anchors a selection decoration by fuzzy text-quote matching and gives
 * NO signal when the quoted text is missing: spike evidence showed it silently drawing a highlight over the wrong
 * paragraph. ShelfOS therefore verifies, in Kotlin and outside the WebView, that a stored selection locator's text
 * (before / highlight / after) still exists EXACTLY in the same sanitized rendition ShelfOS exposes to Readium,
 * before anything is drawn. No JavaScript, no schema change, nothing is rewritten or deleted on failure.
 *
 * IMPORTANT: verification runs against the SANITIZED rendition (`sanitizeEpubHtml` output served by the publication
 * container), because that is what Readium's WebView renders and what the locator text was captured from. A future
 * change to the sanitizer that alters visible text is therefore potentially anchor-affecting; the regression test
 * `EpubAnchorVerifierInstrumentedTest.currentSanitizerKeepsPersistedSelectionLocatorsVerifiable` must stay green.
 *
 * How Readium builds the text triplet (verified in the pinned readium-reflowable.js): `before` and `after` are
 * slices of `document.body.textContent` (whitespace intact, NO separators between blocks), trimmed to word
 * boundaries; `highlight` is `Selection.toString()` (rendered text: block breaks become newlines). The two therefore
 * disagree about whitespace, so comparison drops all whitespace and invisible format characters on BOTH sides.
 */

/** The text part of a stored Readium locator. [progression] is a secondary hint only and is never proof. */
data class SelectionAnchor(val href: String, val before: String?, val highlight: String?, val after: String?, val progression: Double?)

enum class AnchorStatus {
    /** Exactly one occurrence of the highlighted text matches its stored context in the current rendition. */
    PLACEABLE,
    /** The text is missing, its context changed, the match is ambiguous, or the resource cannot be read. Never drawn, never deleted. */
    UNPLACED,
    /** The locator is not a usable text anchor (unparseable, or carries no highlight text). */
    MALFORMED,
}

/** [textFraction] is the match's offset within the resource's comparable text (0..1); a derived ordering hint only. */
data class AnchorVerdict(val status: AnchorStatus, val textFraction: Double? = null)

/** Comparable-text rules shared by both sides of the comparison. Pure and JVM-testable. */
object AnchorText {
    private fun ignorable(c: Char): Boolean =
        Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u00AD' || c == '\u200B' || c == '\u200C' ||
            c == '\u200D' || c == '\u2060' || c == '\uFEFF'

    /** Drops every whitespace and invisible format character; the rest is compared exactly (case, accents, punctuation). */
    fun compact(text: CharSequence?): String {
        if (text.isNullOrEmpty()) return ""
        val out = StringBuilder(text.length)
        for (i in text.indices) if (!ignorable(text[i])) out.append(text[i])
        return out.toString()
    }

    /**
     * The comparable text of a sanitized content document: every text node under `body` in document order, like the
     * DOM's `body.textContent`. Null when the markup has no body at all (nothing can be verified).
     */
    fun visibleText(markup: String, xml: Boolean): String? {
        val document = if (xml) Jsoup.parse(markup, "", Parser.xmlParser()) else Jsoup.parse(markup)
        val body = document.selectFirst("body") ?: return null
        val out = StringBuilder()
        body.traverse(NodeVisitor { node, _ ->
            when (node) {
                is TextNode -> out.append(node.wholeText)
                is DataNode -> out.append(node.wholeData)
            }
        })
        return compact(out)
    }

    /**
     * Verifies a highlighted passage against a resource's comparable text [resource]. PLACEABLE only when exactly one
     * occurrence of the highlight has [before] immediately in front of it and [after] immediately behind it. Several
     * context-identical occurrences are ambiguous and therefore UNPLACED: progression is page-level and cannot prove
     * which one the reader meant.
     */
    fun verify(resource: String, before: String?, highlight: String?, after: String?): AnchorVerdict {
        val h = compact(highlight)
        if (h.isEmpty()) return AnchorVerdict(AnchorStatus.MALFORMED)
        val b = compact(before)
        val a = compact(after)
        var matches = 0
        var first = -1
        var from = 0
        while (true) {
            val at = resource.indexOf(h, from)
            if (at < 0) break
            val beforeOk = b.isEmpty() || (at >= b.length && resource.regionMatches(at - b.length, b, 0, b.length))
            val afterOk = a.isEmpty() || resource.startsWith(a, at + h.length)
            if (beforeOk && afterOk) { matches++; if (first < 0) first = at; if (matches > 1) break }
            from = at + 1
        }
        return when {
            matches == 1 -> AnchorVerdict(AnchorStatus.PLACEABLE, if (resource.isEmpty()) null else first.toDouble() / resource.length)
            else -> AnchorVerdict(AnchorStatus.UNPLACED)
        }
    }
}

/** The sanitized markup of one resource as Readium serves it, and whether it must be parsed as XML. */
data class ResourceMarkup(val markup: String, val xml: Boolean)

/**
 * Verifies selection locators against the session's sanitized rendition. [loadResource] returns the sanitized markup
 * for an href (or null when unreadable). Comparable text is cached per href for the reader session in a small LRU,
 * and verdicts are cached per locator string, so verifying hundreds of annotations parses each chapter once.
 */
class EpubAnchorVerifier(private val loadResource: suspend (href: String) -> ResourceMarkup?, private val cacheSize: Int = 24) {
    private val mutex = Mutex()
    private val texts = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > cacheSize
    }
    private val verdicts = HashMap<String, AnchorVerdict>()
    /** How many times a resource was actually parsed (not served from cache); exposed for the performance regression test. */
    @Volatile var parseCount: Int = 0; private set

    suspend fun verify(locatorJson: String): AnchorVerdict {
        val anchor = parseAnchor(locatorJson) ?: return AnchorVerdict(AnchorStatus.MALFORMED)
        return mutex.withLock {
            verdicts[locatorJson]?.let { return@withLock it }
            verdict(anchor).also { verdicts[locatorJson] = it }
        }
    }

    suspend fun verify(anchor: SelectionAnchor): AnchorVerdict = mutex.withLock { verdict(anchor) }

    /**
     * Verifies many locators, grouped by resource so each chapter is parsed once however many annotations it holds
     * (500 annotations never parse the same chapter 500 times). Keys are the locator strings.
     */
    suspend fun verifyAll(locators: Collection<String>): Map<String, AnchorVerdict> {
        val distinct = locators.distinct()
        val parsed = distinct.map { it to parseAnchor(it) }
        val result = HashMap<String, AnchorVerdict>(distinct.size * 2)
        parsed.filter { it.second == null }.forEach { result[it.first] = AnchorVerdict(AnchorStatus.MALFORMED) }
        parsed.filter { it.second != null }.groupBy { it.second!!.href }.forEach { (_, group) ->
            group.forEach { (json, _) -> result[json] = verify(json) }
        }
        return result
    }

    private suspend fun verdict(anchor: SelectionAnchor): AnchorVerdict {
        if (AnchorText.compact(anchor.highlight).isEmpty()) return AnchorVerdict(AnchorStatus.MALFORMED)
        val text = textFor(anchor.href) ?: return AnchorVerdict(AnchorStatus.UNPLACED)
        return withContext(Dispatchers.Default) { AnchorText.verify(text, anchor.before, anchor.highlight, anchor.after) }
    }

    private suspend fun textFor(href: String): String? {
        if (texts.containsKey(href)) return texts[href]
        val computed = withContext(Dispatchers.Default) {
            try { loadResource(href)?.let { AnchorText.visibleText(it.markup, it.xml) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        parseCount++
        texts[href] = computed
        return computed
    }

    companion object {
        /** The `locations.totalProgression` of a stored locator, or null when absent or unparseable. */
        fun totalProgressionOf(locatorJson: String): Double? =
            runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull()?.locations?.totalProgression

        /** Extracts the text triplet through Readium's own `Locator.fromJSON`. Null when it is not a parseable locator. */
        fun parseAnchor(locatorJson: String): SelectionAnchor? {
            val locator = runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull() ?: return null
            return SelectionAnchor(locator.href.toString(), locator.text.before, locator.text.highlight, locator.text.after, locator.locations.progression)
        }
    }
}
