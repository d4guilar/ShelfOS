// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

/**
 * Phase 3D Codex R1 remediation, finding 2: the complete, stable decode-target SHAPE
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]`.render` actually needs for the
 * CURRENT fold presentation -- a strict superset of 3C's "one `viewportWidth`/`viewportHeight` for the whole
 * group" model. [Single] is every case with exactly one render-target box for the current group: FLAT (the box is
 * the whole reader surface, byte-for-byte 3C's own sizing), [FoldPresentation.HORIZONTAL_SPLIT] (the box is the
 * SELECTED safe pane, never the whole window), or a [FoldPresentation.VERTICAL_SPLIT] solo page (the box is the
 * SELECTED solo pane). [Spread] is the one case needing two independent boxes: an active two-page
 * [FoldPresentation.VERTICAL_SPLIT] spread, where each physical pane has its own width AND height (never an
 * average/flat split of the whole window). Produced by
 * [FixedReaderScreen][com.d4guilar.shelfos.feature.reader.FixedReaderScreen]'s own `onGloballyPositioned`
 * callback, from the SAME freshly-resolved [ReaderFoldLayout] the Compose layer renders into (never a second,
 * independently-recomputed geometry).
 */
sealed class ReaderRenderGeometry {
    /** [widthDp] is the box's own width in dp -- for FLAT this is exactly 3C's pre-3D `viewportWidthDp` (the AUTO
     * window-width threshold unit); for HORIZONTAL_SPLIT/VERTICAL_SPLIT-solo it is the SELECTED pane's own width
     * in dp, never the whole surface's -- so a solo page's AUTO/width-based decisions are also pane-aware. */
    data class Single(val widthPx: Int, val heightPx: Int, val widthDp: Int) : ReaderRenderGeometry()

    /** [leftDp]/[rightDp] drive [verticalFoldSpreadEligibleForAuto] exactly as `FoldPaneWidths` already did --
     * this type does not replace [FoldPaneWidths] (that stays the AUTO/SPREAD-eligibility input, tracked
     * independently of the CURRENT render's geometry shape -- see [FixedReaderViewModel][com.d4guilar.shelfos
     * .feature.reader.FixedReaderViewModel]'s own field docs for why those two concerns stay separate fields). */
    data class Spread(val leftWidthPx: Int, val leftHeightPx: Int, val rightWidthPx: Int, val rightHeightPx: Int,
        val leftDp: Float, val rightDp: Float) : ReaderRenderGeometry()
}

/**
 * The quantization bucket width, in px, [renderKeyBucket] rounds to. A small, named policy (never a raw
 * per-pixel comparison, never a percentage-of-current-value comparison that would make the SAME absolute drift
 * mean different things at different viewport sizes): harmless sub-bucket drift from continuous layout
 * recomputation (Compose re-measuring every frame, a hinge gap recomputing by a pixel, a posture-transition
 * animation's intermediate frames) must never trigger a decode; a change large enough to cross a bucket boundary
 * -- i.e. large enough to plausibly matter for decode resolution/quality -- always does. Chosen as a round,
 * easily-reasoned-about density-independent-ish px granularity; not derived from any existing memory/layout
 * constant because this is a RERENDER-COALESCING policy, not a decode-size/memory-budget one (those stay exactly
 * [RenderMemoryPolicy]'s concern, untouched by this file).
 */
internal const val RENDER_KEY_BUCKET_PX = 32

/**
 * Quantizes one raw decode-target px dimension into its [RENDER_KEY_BUCKET_PX]-wide bucket, via ROUNDING (not
 * flooring): flooring would put two values a few px apart but straddling a bucket boundary into different
 * buckets while two values dozens of px apart but centered within the same bucket collapse together -- rounding
 * to the NEAREST bucket index is the simpler, more predictable policy for "small drift around a stable value
 * stays in one bucket." A non-positive input (not yet measured) buckets to `0`.
 */
internal fun renderKeyBucket(px: Int): Int {
    if (px <= 0) return 0
    return Math.round(px.toDouble() / RENDER_KEY_BUCKET_PX).toInt()
}

/**
 * Phase 3D Codex R1 remediation, finding 2: the small, STABLE key
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]`.updateViewport` compares
 * across calls to decide whether the decode-target geometry has materially changed -- replacing `3550484`'s own
 * "only compare `spreadActive()`'s boolean result" model, which cannot distinguish flat<->asymmetric-vertical-
 * fold, vertical<->horizontal, a safe-pane-selection change, or a material same-spread pane resize from each
 * other whenever none of them happens to flip that one boolean, silently leaving a stale, wrong-resolution bitmap
 * on screen.
 *
 * Two keys compare `equal` (via the generated `data class`/`List` `equals`) exactly when [isSpread] matches AND
 * every [SlotTarget] in [slots] (same order, same count) matches -- which happens exactly when: the presentation
 * SHAPE is the same (a [ReaderRenderGeometry.Single] key always has exactly one [SlotTarget]; a
 * [ReaderRenderGeometry.Spread] key always has exactly two, so [Single]<->[Spread] transitions always differ by
 * list size alone, regardless of any coincidental bucket collision), AND every relevant slot's own bucketed
 * width/height ([renderKeyBucket] -- never a raw per-pixel comparison, see its own doc for the quantization
 * policy) is unchanged, AND [SlotTarget.spreadSlot] (the [PageRenderRequest.spreadSlot] flag
 * [RenderMemoryPolicy]'s byte-budget routing depends on) is unchanged.
 *
 * Deliberately NOT keyed on raw `FoldingFeature` identity, a raw [ReaderFoldLayout] value, every one-pixel hinge
 * movement, or any device-model signal -- only on what [FixedReaderViewModel.render] actually turns into
 * [PageRenderRequest] sizing.
 */
data class EffectiveRenderKey(val isSpread: Boolean, val slots: List<SlotTarget>) {
    data class SlotTarget(val widthBucket: Int, val heightBucket: Int, val spreadSlot: Boolean)
}

/**
 * Builds the current [EffectiveRenderKey] from [geometry] and the current [spreadActive] decision -- the ONE
 * place "what counts as materially different decode geometry" is decided, so
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel] never keeps a second,
 * independently-maintained copy of this policy that could drift out of sync with its own `render()` sizing.
 */
fun effectiveRenderKey(geometry: ReaderRenderGeometry, spreadActive: Boolean): EffectiveRenderKey = when (geometry) {
    is ReaderRenderGeometry.Single -> EffectiveRenderKey(spreadActive, listOf(
        EffectiveRenderKey.SlotTarget(renderKeyBucket(geometry.widthPx), renderKeyBucket(geometry.heightPx), spreadSlot = spreadActive)))
    is ReaderRenderGeometry.Spread -> EffectiveRenderKey(true, listOf(
        EffectiveRenderKey.SlotTarget(renderKeyBucket(geometry.leftWidthPx), renderKeyBucket(geometry.leftHeightPx), spreadSlot = true),
        EffectiveRenderKey.SlotTarget(renderKeyBucket(geometry.rightWidthPx), renderKeyBucket(geometry.rightHeightPx), spreadSlot = true)))
}
