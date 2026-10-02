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
 */

/** One axis's content size already fitted (at 1x zoom) inside its viewport. */
data class FixedReaderContentSize(val width: Float, val height: Float)

/**
 * The unscaled (1x) fitted content size for [bitmapWidth]x[bitmapHeight] inside [viewportWidth]x[viewportHeight].
 *
 * Fit Page ([fitWidth] = false) mirrors `ContentScale.Fit`: the bitmap is scaled down uniformly so both axes fit
 * inside the viewport, letterboxing whichever axis has slack. Fit Width ([fitWidth] = true) mirrors
 * `Modifier.fillMaxWidth().aspectRatio(...)`: width always equals the viewport width, and height follows the
 * bitmap's aspect ratio — it may exceed the viewport height (handled by [FixedReaderScreen]'s own
 * `verticalScroll`, independent of this pan model).
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
