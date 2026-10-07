// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

/**
 * Phase 3D: pure, Compose-free, Android-WindowManager-free fold/hinge geometry for the fixed reader. Nothing in
 * this file imports `androidx.window.*` or any Android type -- the platform mapping from a real
 * `androidx.window.layout.FoldingFeature` into [ReaderFoldDescriptor] lives in the app layer (see
 * `MainActivity.kt`'s fold-descriptor mapping), exactly the same separation [SpreadModel.kt] already keeps
 * between pure pairing logic and the Compose screen that calls it. This lets every rule below be proven with a
 * plain JVM unit test (no emulator, no Robolectric) -- see `FoldLayoutTest.kt`.
 *
 * All bounds in this file are axis-aligned rectangles expressed as plain `Float` edges (`left/top/right/bottom`),
 * never `android.graphics.Rect`/`androidx.compose.ui.geometry.Rect`, so this file never needs to parse those
 * types' own coordinate conventions -- callers do that conversion once, at the boundary.
 */

/** One axis-aligned rectangle, left/top/right/bottom, in whatever coordinate space the caller established (this
 * file only ever receives WINDOW-space bounds for [fold]/[readerBoundsWindow] and only ever PRODUCES reader-LOCAL
 * bounds for the resolved panes -- see [resolveReaderFoldLayout]'s doc for exactly where that conversion happens). */
data class FoldRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    /** The overlapping rectangle of `this` and [other], or `null` if they do not overlap at all. Pure
     * axis-aligned-rectangle intersection; order-independent. Non-strict (`>=`) so a genuinely zero-width/
     * zero-height fold rect (a real platform-reported zero-width separating crease) still counts as "at this
     * position" rather than being discarded as "no intersection" -- only a true gap (no shared point at all)
     * degrades to `null`. */
    fun intersect(other: FoldRect): FoldRect? {
        val l = maxOf(left, other.left); val t = maxOf(top, other.top)
        val r = minOf(right, other.right); val b = minOf(bottom, other.bottom)
        return if (r >= l && b >= t) FoldRect(l, t, r, b) else null
    }

    /** Translates this rect by `(-dx, -dy)` -- used to move a WINDOW-space rect into a space whose origin is
     * `(dx, dy)` in that same window space (i.e. reader-local coordinates once `dx/dy` is the reader's own
     * window-space top-left). */
    fun translated(dx: Float, dy: Float) = FoldRect(left - dx, top - dy, right - dx, bottom - dy)

    companion object {
        fun ofSize(width: Float, height: Float) = FoldRect(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f))
    }
}

/** Orientation of the one fold/hinge relevant to reader layout. */
enum class FoldOrientation { VERTICAL, HORIZONTAL }

/**
 * Small, app-owned description of the one fold/hinge relevant to reader layout -- deliberately NOT the raw
 * `androidx.window.layout.FoldingFeature` (never leaked into `core.reader`; see this file's class doc). [bounds]
 * is in WINDOW coordinates, exactly the space `FoldingFeature.bounds` itself reports (the one documented,
 * HIGH-RISK coordinate detail this slice must get right -- see [resolveReaderFoldLayout]). Never persisted (no
 * Room, no SavedState) -- re-derived fresh from the platform on every composition/recreation.
 */
data class ReaderFoldDescriptor(val bounds: FoldRect, val orientation: FoldOrientation, val isSeparating: Boolean,
    val occludesFully: Boolean) {
    /** A fold only constrains reader layout when it actually separates the screen into independent areas, or
     * fully occludes content under it -- a merely-visible, non-separating, non-occluding crease must never split
     * the reader (AGENTS.md / the 3D contract's explicit non-goal). */
    val isRelevant: Boolean get() = isSeparating || occludesFully
}

/** How the reader's available area is currently divided by a relevant fold. */
enum class FoldPresentation { FLAT, VERTICAL_SPLIT, HORIZONTAL_SPLIT }

/**
 * The resolved, ready-to-render fold-aware layout for one reader screen, always in READER-LOCAL coordinates
 * (origin at the reader's own top-left, NOT the window's -- see [resolveReaderFoldLayout]). Exactly one of
 * [flatPane] (FLAT), ([leftPane], [rightPane]) (VERTICAL_SPLIT), or [safePane] (HORIZONTAL_SPLIT) is non-null,
 * matching [presentation].
 */
data class ReaderFoldLayout(val presentation: FoldPresentation, val flatPane: FoldRect? = null,
    val leftPane: FoldRect? = null, val rightPane: FoldRect? = null, val hingeGapPx: Float = 0f,
    val safePane: FoldRect? = null) {
    /** `true` only for [FoldPresentation.VERTICAL_SPLIT] with two genuinely positive-area panes -- the only
     * presentation a spread's two physical slots can ever map onto (see [selectSoloPane] for the solo-page case,
     * which applies to every presentation). */
    val hasTwoPanes: Boolean get() = presentation == FoldPresentation.VERTICAL_SPLIT &&
        leftPane != null && rightPane != null && leftPane.width > 0f && rightPane.width > 0f

    companion object {
        fun flat(bounds: FoldRect) = ReaderFoldLayout(FoldPresentation.FLAT, flatPane = bounds)
    }
}

/**
 * The core fold-aware layout resolver. [readerBoundsWindow] is the reader surface's own bounds, in WINDOW
 * coordinates (exactly what `LayoutCoordinates.boundsInWindow()` reports); [fold] (if any) is also in window
 * coordinates ([ReaderFoldDescriptor.bounds]) -- this is the one place those two window-space rectangles are
 * intersected and the result translated into reader-LOCAL coordinates (origin at [readerBoundsWindow]'s own
 * top-left), which is what every returned pane is expressed in. Getting this translation right (rather than
 * comparing window-space fold bounds directly against local Compose coordinates) is called out as the single
 * highest-risk area of this slice.
 *
 * Degrades to [FoldPresentation.FLAT] whenever: [fold] is `null`; [fold] is not [ReaderFoldDescriptor.isRelevant];
 * [fold]'s bounds do not intersect [readerBoundsWindow] at all (a crease elsewhere in the window, e.g. behind a
 * navigation rail, must never split the reader); or [readerBoundsWindow] itself has no positive area (not yet
 * measured). All of these are "malformed/non-intersecting geometry degrades safely to ordinary flat behavior."
 *
 * [minGutterPx] is the existing 3C visual gutter token (never a new preference): for a [FoldOrientation.VERTICAL]
 * fold whose actual intersected hinge width is narrower than this (including the zero-width case), the hinge gap
 * is widened, symmetrically around the hinge's own center line, to at least [minGutterPx] -- "a hard page
 * boundary" always exists even when the platform reports a literal zero-width separating crease.
 */
fun resolveReaderFoldLayout(readerBoundsWindow: FoldRect, fold: ReaderFoldDescriptor?, minGutterPx: Float): ReaderFoldLayout {
    val local = FoldRect.ofSize(readerBoundsWindow.width, readerBoundsWindow.height)
    if (readerBoundsWindow.isEmpty || fold == null || !fold.isRelevant) return ReaderFoldLayout.flat(local)
    val intersection = fold.bounds.intersect(readerBoundsWindow) ?: return ReaderFoldLayout.flat(local)
    val hingeLocal = intersection.translated(readerBoundsWindow.left, readerBoundsWindow.top)
    val safeGutter = minGutterPx.coerceAtLeast(0f)
    return when (fold.orientation) {
        FoldOrientation.VERTICAL -> {
            val centerX = (hingeLocal.left + hingeLocal.right) / 2f
            val half = maxOf(hingeLocal.width, safeGutter) / 2f
            val leftPane = FoldRect(0f, 0f, (centerX - half).coerceIn(0f, local.width), local.height)
            val rightPane = FoldRect((centerX + half).coerceIn(0f, local.width), 0f, local.width, local.height)
            ReaderFoldLayout(FoldPresentation.VERTICAL_SPLIT, leftPane = leftPane, rightPane = rightPane,
                hingeGapPx = half * 2f)
        }
        FoldOrientation.HORIZONTAL -> {
            val centerY = (hingeLocal.top + hingeLocal.bottom) / 2f
            val half = maxOf(hingeLocal.height, safeGutter) / 2f
            val topPane = FoldRect(0f, 0f, local.width, (centerY - half).coerceIn(0f, local.height))
            val bottomPane = FoldRect(0f, (centerY + half).coerceIn(0f, local.height), local.width, local.height)
            // Deterministic tie-break: the larger unobstructed region wins; "top" wins an exact tie.
            val safe = if (topPane.height >= bottomPane.height) topPane else bottomPane
            ReaderFoldLayout(FoldPresentation.HORIZONTAL_SPLIT, safePane = safe)
        }
    }
}

/**
 * Where a single visible logical page (cover, landscape split, explicit [SpreadMode.SINGLE], AUTO-resolved-
 * single, unmatched final page) must render: wholly inside ONE unobstructed pane, never stretched across or
 * hidden behind a hinge. [FoldPresentation.FLAT]/[FoldPresentation.HORIZONTAL_SPLIT] have exactly one candidate
 * pane already. [FoldPresentation.VERTICAL_SPLIT] picks by: (1) the pane with the greater usable area; (2) on an
 * (effectively) exact tie, the reading-direction START pane -- LTR -> left, RTL -> right.
 */
fun selectSoloPane(layout: ReaderFoldLayout, rightToLeft: Boolean): FoldRect = when (layout.presentation) {
    FoldPresentation.FLAT -> requireNotNull(layout.flatPane)
    FoldPresentation.HORIZONTAL_SPLIT -> requireNotNull(layout.safePane)
    FoldPresentation.VERTICAL_SPLIT -> {
        val left = requireNotNull(layout.leftPane); val right = requireNotNull(layout.rightPane)
        val leftArea = left.width.toDouble() * left.height
        val rightArea = right.width.toDouble() * right.height
        when {
            leftArea > rightArea -> left
            rightArea > leftArea -> right
            else -> if (rightToLeft) right else left
        }
    }
}

/**
 * AUTO's fold-aware spread-eligibility policy for a [FoldPresentation.VERTICAL_SPLIT]. Preserves the 3C AUTO
 * philosophy ([AUTO_SPREAD_MIN_WIDTH_DP]) but must not decide from total window width alone -- a two-pane spread
 * is only offered when BOTH resulting panes are individually useful. Policy: the combined usable pane width
 * (excluding the fixed hinge gap) still satisfies the existing [AUTO_SPREAD_MIN_WIDTH_DP] threshold, AND each
 * pane is at least roughly half of that minimum (derived from the existing constant, not a new unrelated magic
 * number -- for the existing 600dp rule this conceptually means ~300dp per pane).
 */
fun verticalFoldSpreadEligibleForAuto(leftPaneWidthDp: Float, rightPaneWidthDp: Float,
    minSpreadWidthDp: Int = AUTO_SPREAD_MIN_WIDTH_DP): Boolean {
    val halfMin = minSpreadWidthDp / 2f
    return leftPaneWidthDp >= halfMin && rightPaneWidthDp >= halfMin &&
        (leftPaneWidthDp + rightPaneWidthDp) >= minSpreadWidthDp
}

/** Explicit [SpreadMode.SPREAD] under a vertical fold split: a spread may still be requested whenever two real,
 * positive-area panes exist -- no width-floor beyond "actually usable" (unlike AUTO's more conservative policy
 * above). Explicit [SpreadMode.SINGLE] never calls this; it always stays single regardless of fold geometry. */
fun verticalFoldHasTwoUsablePanes(layout: ReaderFoldLayout): Boolean = layout.hasTwoPanes

/**
 * Pre-3D legacy "larger unobstructed region" inset math, extracted unchanged from `MainActivity`'s original
 * Phase-0 behavior (byte-for-byte the same formula, including [bounds] and [hinge] both staying in the SAME
 * window-coordinate space, exactly as the original inline code compared them) so non-reader screens (Library/
 * Search/Shelves/Settings/Details/import dialogs) keep their existing, conservative fold behavior through a
 * small, independently PURE-testable function instead of relying only on visual inspection -- the explicit
 * "non-reader regression guard" this slice must not break while evolving `MainActivity`'s reader-specific
 * behavior. Returns the (left, top, right, bottom) padding, in the same unit as the inputs (px), to subtract the
 * smaller region away; exactly one pair of opposite edges is ever non-zero, matching the original implementation.
 */
fun legacySafePaneInset(bounds: FoldRect, hinge: FoldRect?, vertical: Boolean): FoldInset {
    if (hinge == null || bounds.isEmpty) return FoldInset(0f, 0f, 0f, 0f)
    return if (vertical) {
        val left = (hinge.left - bounds.left).coerceAtLeast(0f)
        val right = (bounds.right - hinge.right).coerceAtLeast(0f)
        if (left >= right) FoldInset(0f, 0f, (bounds.width - left).coerceAtLeast(0f), 0f)
        else FoldInset((bounds.width - right).coerceAtLeast(0f), 0f, 0f, 0f)
    } else {
        val top = (hinge.top - bounds.top).coerceAtLeast(0f)
        val bottom = (bounds.bottom - hinge.bottom).coerceAtLeast(0f)
        if (top >= bottom) FoldInset(0f, 0f, 0f, (bounds.height - top).coerceAtLeast(0f))
        else FoldInset(0f, (bounds.height - bottom).coerceAtLeast(0f), 0f, 0f)
    }
}

/** Plain (left, top, right, bottom) padding amounts, in px -- intentionally not `androidx.compose.foundation
 * .layout.PaddingValues` so [legacySafePaneInset] stays Compose-free/pure-JVM-testable; `MainActivity` converts
 * this to `PaddingValues` at the call site. */
data class FoldInset(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The fold-aware pane width information [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader
 * .FixedReaderViewModel] needs for a [FoldPresentation.VERTICAL_SPLIT] -- `null` (not passed at all) means no
 * vertical fold split is active, so the ordinary flat/horizontal-fold width-based AUTO policy and whole-viewport
 * slot sizing apply exactly as 3C already did. [leftPx]/[rightPx] (pixels) drive per-slot render-request sizing
 * (see [FixedReaderViewModel.render]); [leftDp]/[rightDp] (density-independent) drive the fold-aware AUTO policy
 * ([verticalFoldSpreadEligibleForAuto]) -- the same "px for decode, dp for the AUTO threshold" split 3C already
 * established for the flat case ([FixedReaderViewModel]'s `viewportWidth`/`viewportWidthDp`).
 */
data class FoldPaneWidths(val leftPx: Int, val rightPx: Int, val leftDp: Float, val rightDp: Float) {
    /** Explicit [SpreadMode.SPREAD]'s more permissive eligibility check: both panes have genuinely positive
     * width, independent of the stricter AUTO threshold. */
    val bothPanesUsable: Boolean get() = leftPx > 0 && rightPx > 0
}
