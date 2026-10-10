// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.AnnotationLimits

/*
 * Phase 4B: pure, Readium-free and Android-free ShelfOS value logic for EPUB annotations (JVM-testable). Everything
 * that touches a Readium type lives in [EpubAnnotationBridge]; this file only maps between ShelfOS types.
 */

/** What the reader supplied for one text selection, in ShelfOS terms. [locatorJson] is `Locator.toJSON().toString()`. */
data class EpubSelectionRead(val locatorJson: String, val highlight: String?, val totalProgression: Double?)

/** A validated selection ready to become an annotation draft. [selectedText] is the normalized display/search copy. */
data class EpubSelectionDraft(val locatorJson: String, val selectedText: String, val progressSnapshot: Int, val orderKey: Double?)

enum class SelectionRejection {
    /** The selection yielded no usable text; ShelfOS never stores a coarse position pretending to be a highlight. */
    NO_TEXT,
    /** The serialized locator exceeds [AnnotationLimits.MAX_LOCATOR_BYTES]. */
    LOCATOR_TOO_LARGE,
}

sealed interface SelectionDraftResult {
    data class Ready(val draft: EpubSelectionDraft) : SelectionDraftResult
    data class Rejected(val reason: SelectionRejection) : SelectionDraftResult
}

object SelectedText {
    /** True for ordinary Unicode whitespace and the non-breaking spaces `Char.isWhitespace` excludes. */
    private fun isSpace(codePoint: Int) = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    /**
     * Display/search copy of a selection's raw `text.highlight`: trimmed, every whitespace run collapsed to one space,
     * capped at [AnnotationLimits.MAX_SELECTED_TEXT] UTF-16 code units on a code-point boundary (no ellipsis; the UI
     * adds one). Null when nothing but whitespace remains. The raw triplet inside `locatorJson` is never touched.
     */
    fun normalize(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val out = StringBuilder(minOf(raw.length, AnnotationLimits.MAX_SELECTED_TEXT + 2))
        var pendingSpace = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val width = Character.charCount(cp)
            if (isSpace(cp)) pendingSpace = out.isNotEmpty()
            else {
                if (pendingSpace) { out.append(' '); pendingSpace = false }
                // Stop before exceeding the cap; never split a surrogate pair.
                if (out.length + width > AnnotationLimits.MAX_SELECTED_TEXT) break
                out.appendCodePoint(cp)
            }
            i += width
        }
        // A pending separator space before the cap was reached may be the last char; trim it.
        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
        return out.toString().takeIf { it.isNotEmpty() }
    }
}

/** 0..100 snapshot from a Readium `totalProgression` (0.0..1.0); 0 when absent or not finite. */
fun progressSnapshotOf(totalProgression: Double?): Int =
    totalProgression?.takeIf { it.isFinite() }?.let { (it * 100).toInt().coerceIn(0, 100) } ?: 0

/** A finite 0..1 total progression usable as a sort hint, or null. */
fun orderKeyOf(totalProgression: Double?): Double? = totalProgression?.takeIf { it.isFinite() && it in 0.0..1.0 }

private fun utf8Bytes(text: String): Int {
    var bytes = 0; var i = 0
    while (i < text.length) {
        val c = text[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return bytes
}

/**
 * Maps a reader selection to an annotation-ready draft. The text is trustworthy only as supplied in
 * `locator.text.highlight` (spike evidence); a selection with no usable text, or a locator over the cap, is rejected
 * instead of stored. [orderKey] is supplied by the caller (derived from verified text offsets) and is optional.
 */
fun buildSelectionDraft(read: EpubSelectionRead, orderKey: Double? = null): SelectionDraftResult {
    if (utf8Bytes(read.locatorJson) > AnnotationLimits.MAX_LOCATOR_BYTES) return SelectionDraftResult.Rejected(SelectionRejection.LOCATOR_TOO_LARGE)
    val text = SelectedText.normalize(read.highlight) ?: return SelectionDraftResult.Rejected(SelectionRejection.NO_TEXT)
    return SelectionDraftResult.Ready(EpubSelectionDraft(read.locatorJson, text, progressSnapshotOf(read.totalProgression), orderKey))
}

/** True when a position-anchored note's locator fits the stored cap. */
fun locatorWithinCap(locatorJson: String) = utf8Bytes(locatorJson) <= AnnotationLimits.MAX_LOCATOR_BYTES

/** The inline (in-text) shapes ShelfOS draws. Position-anchored notes and bookmarks have no inline shape. */
enum class EpubMarkShape { HIGHLIGHT, UNDERLINE }

/** One inline mark to draw: [id] is the Annotation id (the Decoration id), [locatorJson] the persisted locator. */
data class EpubMark(val id: String, val locatorJson: String, val shape: EpubMarkShape, val styleKey: String?)

/**
 * HIGHLIGHT -> filled highlight in its palette style; selection-anchored NOTE (selectedText present) -> underline;
 * position NOTE and BOOKMARK -> no inline mark. The kind is never changed by the presence of a body.
 */
fun Annotation.toMarkOrNull(): EpubMark? = when {
    kind == AnnotationKind.HIGHLIGHT -> EpubMark(id, locatorJson, EpubMarkShape.HIGHLIGHT, styleKey)
    kind == AnnotationKind.NOTE && selectedText != null -> EpubMark(id, locatorJson, EpubMarkShape.UNDERLINE, null)
    else -> null
}

/** True for annotations whose inline placement must be verified against the sanitized rendition. */
val Annotation.isSelectionBacked: Boolean get() = toMarkOrNull() != null

/**
 * The single navigation-eligibility rule for annotations (Phase 4B). The anchor verdict controls BOTH inline placement and
 * navigation, because Readium approximates an unmatched text locator and can land on unrelated text:
 *  - BOOKMARK and position NOTE (no selectedText): navigable through the persisted locator, no text verification;
 *  - HIGHLIGHT and selection NOTE (selectedText present): navigable ONLY when [status] is [AnchorStatus.PLACEABLE].
 * A missing verdict (not verified yet) is not PLACEABLE, so selection-backed annotations stay unnavigable until proven.
 * Based on model semantics ([isSelectionBacked]), never on UI labels.
 */
fun Annotation.canNavigate(status: AnchorStatus?): Boolean = !isSelectionBacked || status == AnchorStatus.PLACEABLE

/** Result of [navigateToAnnotation]. */
enum class AnnotationNavigation { ARRIVED, FAILED, BLOCKED }

/**
 * The only path from the annotation list to the navigator. [jump] (the controller's `jumpTo`) is invoked ONLY when
 * [canNavigate] allows it; a blocked annotation never reaches the navigator, with no href, progress or approximate fallback.
 */
suspend fun navigateToAnnotation(annotation: Annotation, status: AnchorStatus?, jump: suspend (String) -> JumpResult): AnnotationNavigation {
    if (!annotation.canNavigate(status)) return AnnotationNavigation.BLOCKED
    return if (jump(annotation.locatorJson) == JumpResult.ARRIVED) AnnotationNavigation.ARRIVED else AnnotationNavigation.FAILED
}
