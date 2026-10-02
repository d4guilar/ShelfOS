// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

/**
 * Pure, Compose-free, Context-free geometry for [FixedReaderScreen][com.d4guilar.shelfos.feature.reader
 * .FixedReaderScreen]'s pinch/pan transform. Shared by PDF and CBZ (both render through the same screen) and
 * deliberately has no reading-direction (RTL/LTR) input: pan bounds are purely geometric, independent of which
 * edge turns the page.
 *
 * Phase 2D.1: the previous inline clamp bounded translation to `±viewportSize * scale`, which is unrelated to
 * how far the actual *fitted, scaled* content extends past the viewport — it permitted dragging a page
 * completely off-screen. The correct model, implemented here: `maxPan = max(0, (scaledContent - viewport) / 2)`.
 *
 * Phase 2D.1 remediation (Fit Width tall-content blocker, found by independent QA after the first 2D.1 pass):
 * the first pass's own docs claimed Fit Width's `verticalScroll` was "independent of this pan model" and that
 * this was "confirmed, not redesigned" — that claim was false. [fixedReaderMaxPan] has no fit-mode concept, so
 * nothing stopped [FixedReaderScreen] from also feeding it Fit Width's often-much-taller-than-viewport content
 * height and using the result to drive a *second*, independent vertical-movement system (`graphicsLayer`
 * `translationY`) on top of `verticalScroll`. Two systems moving the same one-finger drag at once is itself a
 * defect (the page moves twice as fast as the finger), and worse, the formula's excess was measured only
 * against the viewport, not against the already-tall fitted content, so the computed bound could be large
 * enough to push the page out of view. The fix lives entirely in [FixedReaderScreen]: Fit Width now forces
 * `panY`/`translationY` to `0` and lets `verticalScroll` own all vertical movement; this file's pan-bound
 * functions remain fit-mode-agnostic by design; only the width axis's pan bound is still used for Fit Width.
 *
 * Phase 2D.1 remediation, round two (zoomed Fit Width top/bottom reachability, an independent QA finding after
 * `06bcc14`): forcing `translationY` to `0` fixed the gray-escape/double-movement defects but exposed a third
 * one. `graphicsLayer`'s `scaleX`/`scaleY` visually scale the `Image` around its own layout center without
 * changing its *layout* size — `verticalScroll` only ever measures the unscaled fitted height `H`, so its scroll
 * range stays `max(0, H - viewport)` when the visually-scaled content actually needs `max(0, scale*H -
 * viewport)`. At `scale == 2`, the outer ~25% of the page at each end becomes permanently unreachable; at `scale
 * == 5`, ~40% at each end. [fixedReaderVerticalScaleOverflow] is the fix's pure half: a center-origin scale by
 * `scale` bulges the visual content by `(scale-1)*contentHeight/2` above and below the Image's own layout
 * bounds. [FixedReaderScreen] reserves exactly that much blank layout space (spacers) above and below the Image,
 * *outside* its `graphicsLayer`, so the scrollable column's measured height becomes `H + 2*overflow ==
 * scale*H` — exactly matching the visual extent and giving `verticalScroll` the correct range for free, with no
 * second vertical-movement mechanism and no change to the `translationY == 0` invariant above.
 */

/** One axis's content size already fitted (at 1x zoom) inside its viewport. */
data class FixedReaderContentSize(val width: Float, val height: Float)

/**
 * The unscaled (1x) fitted content size for [bitmapWidth]x[bitmapHeight] inside [viewportWidth]x[viewportHeight].
 *
 * Fit Page ([fitWidth] = false) mirrors `ContentScale.Fit`: the bitmap is scaled down uniformly so both axes fit
 * inside the viewport, letterboxing whichever axis has slack. Fit Width ([fitWidth] = true) mirrors
 * `Modifier.fillMaxWidth().aspectRatio(...)`: width always equals the viewport width, and height follows the
 * bitmap's aspect ratio — it may exceed the viewport height. [FixedReaderScreen]'s `verticalScroll` is what
 * actually moves that excess height into view; this returned height is informational for Fit Width (the pan-
 * bound helpers below are only applied to the width axis in that mode, by [FixedReaderScreen]'s own choice, not
 * by anything in this file).
 *
 * Defensive: a non-finite or non-positive input on either axis yields a `0x0` result rather than propagating
 * NaN/Infinity, since there is nothing sensible to fit before the bitmap/viewport is actually measured.
 */
fun fixedReaderFittedContentSize(bitmapWidth: Float, bitmapHeight: Float, viewportWidth: Float, viewportHeight: Float,
    fitWidth: Boolean): FixedReaderContentSize {
    if (!bitmapWidth.isFinite() || !bitmapHeight.isFinite() || bitmapWidth <= 0f || bitmapHeight <= 0f ||
        !viewportWidth.isFinite() || !viewportHeight.isFinite() || viewportWidth <= 0f || viewportHeight <= 0f)
        return FixedReaderContentSize(0f, 0f)
    return if (fitWidth) {
        val height = viewportWidth * (bitmapHeight / bitmapWidth)
        FixedReaderContentSize(viewportWidth, if (height.isFinite() && height > 0f) height else 0f)
    } else {
        val fit = minOf(viewportWidth / bitmapWidth, viewportHeight / bitmapHeight)
        if (!fit.isFinite() || fit <= 0f) FixedReaderContentSize(0f, 0f)
        else FixedReaderContentSize(bitmapWidth * fit, bitmapHeight * fit)
    }
}

/**
 * The maximum pan magnitude on one axis: how far the [scale]d [contentSize] extends past [viewportSize] on
 * either side of center. `0` whenever the scaled content already fits the viewport on that axis (including the
 * untransformed, default `scale == 1f` case for content that was already fitted to the viewport) — a page that
 * fits can never be dragged inside its own letterboxed margin.
 *
 * Defensive: a non-finite/non-positive [scale] is treated as `1f`; non-finite or non-positive [contentSize]/
 * [viewportSize] yields `0` (nothing to pan against). The result is always finite and non-negative.
 */
fun fixedReaderMaxPan(contentSize: Float, viewportSize: Float, scale: Float): Float {
    val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
    if (!contentSize.isFinite() || contentSize <= 0f || !viewportSize.isFinite() || viewportSize <= 0f) return 0f
    val scaled = contentSize * safeScale
    if (!scaled.isFinite()) return 0f
    return maxOf(0f, (scaled - viewportSize) / 2f)
}

/** Clamps [value] into `[-max, max]`; `max` is defensively floored at `0` so an invalid/negative bound never
 * inverts the range. */
fun fixedReaderClampPan(value: Float, max: Float): Float {
    val safeMax = if (max.isFinite() && max > 0f) max else 0f
    if (!value.isFinite()) return 0f
    return value.coerceIn(-safeMax, safeMax)
}

/**
 * The maximum Y pan magnitude [FixedReaderScreen] may apply via its `graphicsLayer`, given the active fit mode.
 *
 * Fit Width ([fitWidth] = true) always returns `0`, **regardless of [scale]**: that mode's `Image` sits inside
 * `verticalScroll`, which already owns every bit of vertical movement for content taller than the viewport.
 * Letting the pan transform also move vertically — as the first Phase 2D.1 pass did, by feeding this tall
 * [contentSize]'s height into [fixedReaderMaxPan] the same way Fit Page does — let two independent systems move
 * the same one-finger drag at once, and, since the excess there was measured only against the viewport rather
 * than the already-tall content, could compute a bound large enough to push the page into gray. Forcing `0`
 * here (rather than just not calling this function) keeps that invariant in one obviously-correct, pure,
 * directly testable place instead of duplicated inline checks at each call site.
 *
 * Fit Page ([fitWidth] = false) is unaffected: it has no scroll container, so it keeps the standard
 * [fixedReaderMaxPan] bound on [contentSize]'s height.
 */
fun fixedReaderMaxPanY(contentSize: FixedReaderContentSize, viewportHeight: Float, scale: Float, fitWidth: Boolean): Float =
    if (fitWidth) 0f else fixedReaderMaxPan(contentSize.height, viewportHeight, scale)

/**
 * How far a center-origin `graphicsLayer` scale of [scale] visually bulges [contentHeight]-tall content above
 * (and, symmetrically, below) its own unscaled layout bounds: `max(0, (scale-1) * contentHeight / 2)`.
 *
 * [FixedReaderScreen] reserves exactly this much blank layout space above and below Fit Width's `Image` —
 * *outside* its `graphicsLayer`, so the reserved space itself is never also scaled — so the scrollable column's
 * total measured height becomes `contentHeight + 2 * overflow == scale * contentHeight`, matching the visually
 * scaled extent exactly and handing `verticalScroll` the correct `max(0, scale*contentHeight - viewport)` range
 * without any second vertical-movement mechanism. At `scale == 1` this is exactly `0` (no added space, ordinary
 * unzoomed Fit Width is unchanged); it grows linearly with zoom on both sides symmetrically.
 *
 * Defensive: a non-finite/non-positive [scale] is treated as `1f` (yielding `0` overflow); a non-finite or
 * non-positive [contentHeight] yields `0`. The result is always finite and non-negative.
 */
fun fixedReaderVerticalScaleOverflow(contentHeight: Float, scale: Float): Float {
    val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
    if (!contentHeight.isFinite() || contentHeight <= 0f) return 0f
    val overflow = (safeScale - 1f) * contentHeight / 2f
    return if (overflow.isFinite() && overflow > 0f) overflow else 0f
}
