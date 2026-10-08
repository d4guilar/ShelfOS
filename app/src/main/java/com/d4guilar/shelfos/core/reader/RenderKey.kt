// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

/**
 * Phase 3D Codex R2 remediation, finding A: the complete, stable decode-target SHAPE
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]`.render` needs for the CURRENT
 * fold presentation -- reporting BOTH the solo-page target ([single], always populated) AND the independent
 * two-pane spread target ([spread], populated whenever two real, positive-area vertical-fold panes exist),
 * regardless of how many slots are CURRENTLY published in `FixedReaderState.slots`.
 *
 * This replaces R1's sealed `Single`/`Spread` design, whose single critical defect R2 found: the CALLER (
 * [FixedReaderScreen][com.d4guilar.shelfos.feature.reader.FixedReaderScreen]'s `onGloballyPositioned`) had to pick
 * ONE of those two shapes before calling [FixedReaderViewModel.updateViewport], and it picked using the
 * ALREADY-PUBLISHED `state.slots.size` -- circular, because the whole point of reporting geometry is to let the
 * ViewModel decide how to render the NEXT group, which may have a different size than the CURRENTLY published
 * one (e.g. navigating solo -> spread). A solo page under a two-pane-capable vertical fold would report `Single`
 * geometry (sized to the one solo pane) right up until the moment a second slot happened to be published, so the
 * FIRST decode of a newly-eligible spread was sized from the stale solo-pane box rather than each pane's own real
 * size.
 *
 * The fix: [single] and [spread] are BOTH always derived purely from the current [ReaderFoldLayout] (never from
 * slot count) -- exactly the same "independent of slots" discipline [FoldPaneWidths] (the AUTO/SPREAD eligibility
 * input) already observed from Phase 3D's very first cut. [FixedReaderViewModel.render] then resolves the actual
 * [PageGroup] for the page it is about to show and picks [spread] when that group has 2 pages (and [spread] is
 * non-null), or [single] (split evenly by slot count, exactly 3C's own flat-spread approximation) otherwise --
 * selecting the correct target BEFORE decoding, every time, with no dependency on a second `onGloballyPositioned`
 * call, a later recomposition, a retry, or a page turn.
 */
data class ReaderRenderGeometry(
    val presentation: FoldPresentation,
    /** The correct solo-page decode target for the CURRENT presentation: FLAT's whole pane, HORIZONTAL_SPLIT's
     * selected safe pane, or a VERTICAL_SPLIT's selected solo pane ([selectSoloPane]) -- always populated,
     * independent of whether a spread happens to be active right now. [SingleTarget.widthDp] drives
     * [FixedReaderViewModel]'s own `currentWidthDp()`/AUTO-width policy exactly as R1's `Single.widthDp` did. */
    val single: SingleTarget,
    /** Each physical vertical-fold pane's own independent (width, height) -- non-null exactly when
     * [ReaderFoldLayout.hasTwoPanes] is true for the CURRENT layout, regardless of [single]'s own presentation or
     * how many slots are currently published. `null` for FLAT/HORIZONTAL_SPLIT, or a VERTICAL_SPLIT with fewer
     * than two usable panes. */
    val spread: SpreadTarget? = null,
) {
    data class SingleTarget(val widthPx: Int, val heightPx: Int, val widthDp: Int)
    data class SpreadTarget(val leftWidthPx: Int, val leftHeightPx: Int, val rightWidthPx: Int, val rightHeightPx: Int,
        val leftDp: Float, val rightDp: Float)

    companion object {
        /** Convenience for the common FLAT/no-two-pane case: a single decode-target box, no independent per-pane
         * spread target. Mirrors R1's single-box `ReaderRenderGeometry.Single(...)` shape that most non-fold
         * call sites (ordinary flat tests, lifecycle tests, non-fold spread-mode tests) only ever needed --
         * named `flat` (not `Single`) so it can never be confused with, or shadow, [SingleTarget]'s own
         * constructor at a fold-aware call site that actually needs to populate [spread] too. */
        fun flat(widthPx: Int, heightPx: Int, widthDp: Int): ReaderRenderGeometry =
            ReaderRenderGeometry(FoldPresentation.FLAT, SingleTarget(widthPx, heightPx, widthDp), null)
    }
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
 * Phase 3D Codex R2 remediation, finding A part 2: the extra margin, in px, added on EACH side of an already-
 * ACCEPTED bucket's own nominal half-width before [acceptedRenderKeyBucket] will move off of it. R1's plain
 * nearest-bucket rounding ([renderKeyBucket] alone) has a hard boundary at every exact half-bucket distance from
 * a bucket center (e.g. with [RENDER_KEY_BUCKET_PX] == 32, the boundary between buckets 24 and 25 sits at
 * px 784.0) -- a raw dimension oscillating by a single px across that one boundary (783, 784, 783, 784...), from
 * nothing more than continuous layout recomputation, flips the bucket every single time and cancels/restarts a
 * full-resolution decode on every flip. [acceptedRenderKeyBucket] fixes this the standard control-theory way:
 * once a bucket is ACCEPTED, it is kept (never recomputed from scratch) as long as the raw px stays within this
 * extra margin of that bucket's own ordinary nearest-rounding span -- only a genuinely larger move, well past the
 * old boundary, is allowed to actually change the accepted bucket. Deliberately smaller than
 * [RENDER_KEY_BUCKET_PX] itself (it widens the ACCEPTED bucket's own range, it does not change the bucket grid),
 * so a truly material resize (per the existing [RenderKeyTest] fixtures, e.g. ~500px -> ~780px) still always
 * crosses it and corrects exactly once.
 */
internal const val RENDER_KEY_HYSTERESIS_PX = 12

/**
 * Quantizes one raw decode-target px dimension into its [RENDER_KEY_BUCKET_PX]-wide bucket, via ROUNDING (not
 * flooring): flooring would put two values a few px apart but straddling a bucket boundary into different
 * buckets while two values dozens of px apart but centered within the same bucket collapse together -- rounding
 * to the NEAREST bucket index is the simpler, more predictable policy for "small drift around a stable value
 * stays in one bucket." A non-positive input (not yet measured) buckets to `0`. This is the STATELESS fallback
 * [acceptedRenderKeyBucket] uses whenever there is no previously-accepted bucket to apply hysteresis against
 * (the very first measurement, or a structural shape change -- see that function's own doc).
 */
internal fun renderKeyBucket(px: Int): Int {
    if (px <= 0) return 0
    return Math.round(px.toDouble() / RENDER_KEY_BUCKET_PX).toInt()
}

/**
 * Phase 3D Codex R2 remediation, finding A part 2: the hysteresis-aware bucket to actually USE for [px] given
 * [lastAccepted] (the bucket this same slot's key last settled on, or `null` if there is none yet -- no prior
 * measurement, or the previous key had a different SHAPE entirely, e.g. a Single<->Spread transition, which never
 * carries over a stale bucket from an unrelated slot). With no [lastAccepted], this is exactly [renderKeyBucket].
 * With one, [lastAccepted] is kept as long as [px] stays within [lastAccepted]'s own ordinary nearest-rounding
 * span widened by [RENDER_KEY_HYSTERESIS_PX] on each side; only once [px] genuinely exits that widened span does
 * this recompute a fresh nearest bucket for the new value (which becomes the next call's [lastAccepted]).
 *
 * This policy decides WHETHER [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]
 * re-renders -- it never feeds the actual [PageRenderRequest] dimensions themselves, which always keep using the
 * CURRENT raw pane size (see [EffectiveRenderKey]'s own doc) -- so this can only ever reduce unnecessary
 * cancel/restart churn near an old boundary, never cause a systematically under-sampled decode.
 */
internal fun acceptedRenderKeyBucket(px: Int, lastAccepted: Int?): Int {
    if (px <= 0) return 0
    if (lastAccepted == null) return renderKeyBucket(px)
    val center = lastAccepted * RENDER_KEY_BUCKET_PX
    val halfSpan = RENDER_KEY_BUCKET_PX / 2.0 + RENDER_KEY_HYSTERESIS_PX
    return if (px >= center - halfSpan && px < center + halfSpan) lastAccepted else renderKeyBucket(px)
}

/**
 * Phase 3D Codex R1 remediation, finding 2 (R2 remediation, finding A: now hysteresis-aware -- see
 * [acceptedRenderKeyBucket]): the small, STABLE key
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]`.updateViewport` compares
 * across calls to decide whether the decode-target geometry has materially changed.
 *
 * Two keys compare `equal` (via the generated `data class`/`List` `equals`) exactly when [isSpread] matches AND
 * every [SlotTarget] in [slots] (same order, same count) matches -- which happens exactly when: the presentation
 * SHAPE is the same (a non-fold-spread key always has exactly one [SlotTarget]; a two-pane vertical-fold-spread
 * key always has exactly two, so a 1<->2-slot transition always differs by list size alone, regardless of any
 * coincidental bucket collision), AND every relevant slot's own ACCEPTED bucketed width/height is unchanged, AND
 * [SlotTarget.spreadSlot] (the [PageRenderRequest.spreadSlot] flag [RenderMemoryPolicy]'s byte-budget routing
 * depends on) is unchanged.
 *
 * Deliberately NOT keyed on raw `FoldingFeature` identity, a raw [ReaderFoldLayout] value, every one-pixel hinge
 * movement, or any device-model signal -- only on what [FixedReaderViewModel.render] actually turns into
 * [PageRenderRequest] sizing.
 */
data class EffectiveRenderKey(val isSpread: Boolean, val slots: List<SlotTarget>) {
    data class SlotTarget(val widthBucket: Int, val heightBucket: Int, val spreadSlot: Boolean)
}

/**
 * Builds the current [EffectiveRenderKey] from [geometry] and the ALREADY-RESOLVED [groupSize] (1 or 2 -- the
 * real [PageGroup.pages].size for the reading unit about to be shown; see
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]'s own `render`/`updateViewport`
 * for how that resolution happens WITHOUT depending on `state.slots` -- the Finding A fix) -- the ONE place "what
 * counts as materially different decode geometry" is decided, so [FixedReaderViewModel] never keeps a second,
 * independently-maintained copy of this policy that could drift out of sync with its own `render()` sizing.
 *
 * The two-pane vertical-fold-spread SHAPE (two [EffectiveRenderKey.SlotTarget]s, from [geometry]'s own [spread])
 * is used exactly when [groupSize] == 2 AND [geometry].[spread][ReaderRenderGeometry.spread] is non-null --
 * mirroring exactly the condition [FixedReaderViewModel.render] itself uses to pick a decode target, so this key
 * and the actual request sizing can never disagree about which shape is "the" current one. Every other case
 * (`groupSize == 1`, or a flat/horizontal-fold 2-slot spread that shares one box) uses [geometry]'s own [single]
 * box as ONE [EffectiveRenderKey.SlotTarget] -- unchanged from R1 (the key intentionally does not mirror
 * `render()`'s even-width-split-by-slot-count approximation for that case; the whole BOX changing is already the
 * correct, sufficient re-render signal for it).
 *
 * [previous] is the last-ACCEPTED key (exactly the field
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel] already kept, `lastEffectiveRenderKey`)
 * -- supplied so each [EffectiveRenderKey.SlotTarget]'s bucket can apply [acceptedRenderKeyBucket]'s hysteresis
 * against the bucket THIS SAME SLOT last settled on. A structural shape change (previous slot count differs from
 * the new one) never carries over a stale bucket from an unrelated slot -- each such transition always falls back
 * to a fresh nearest-bucket computation for every slot, exactly as if [previous] were `null`.
 */
fun effectiveRenderKey(geometry: ReaderRenderGeometry, groupSize: Int, spreadActive: Boolean,
    previous: EffectiveRenderKey? = null): EffectiveRenderKey {
    val useSpreadShape = geometry.spread != null && groupSize == 2
    val previousMatchesShape = previous != null && previous.slots.size == (if (useSpreadShape) 2 else 1)
    fun slotTarget(widthPx: Int, heightPx: Int, previousIndex: Int): EffectiveRenderKey.SlotTarget {
        val prior = previous?.takeIf { previousMatchesShape }?.slots?.get(previousIndex)
        return EffectiveRenderKey.SlotTarget(
            widthBucket = acceptedRenderKeyBucket(widthPx, prior?.widthBucket),
            heightBucket = acceptedRenderKeyBucket(heightPx, prior?.heightBucket),
            spreadSlot = spreadActive)
    }
    return if (useSpreadShape) {
        val spread = requireNotNull(geometry.spread)
        EffectiveRenderKey(true, listOf(
            slotTarget(spread.leftWidthPx, spread.leftHeightPx, 0),
            slotTarget(spread.rightWidthPx, spread.rightHeightPx, 1)))
    } else {
        val single = geometry.single
        EffectiveRenderKey(spreadActive, listOf(slotTarget(single.widthPx, single.heightPx, 0)))
    }
}

/**
 * Phase 3D Codex R2 remediation, finding A: the actual per-logical-page decode target
 * [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]`.render` uses for a resolved
 * group of [groupSize] pages, given [geometry] -- always CURRENT raw pane dimensions (never the quantized/
 * hysteresis-adjusted bucket [EffectiveRenderKey] uses only to decide WHETHER to re-render; see that type's own
 * doc for why those two concerns stay deliberately separate). Mirrors [effectiveRenderKey]'s own shape selection
 * exactly (two independent per-pane boxes when [groupSize] == 2 and [geometry].[spread][ReaderRenderGeometry.spread]
 * is non-null; otherwise [geometry]'s [single] box split evenly across [groupSize] slots, 3C's own flat-spread
 * approximation) so the two can never disagree about which shape is "the" current one.
 */
fun resolveRenderTargets(geometry: ReaderRenderGeometry, groupSize: Int): List<Pair<Int, Int>> {
    val count = groupSize.coerceAtLeast(1)
    val spread = geometry.spread
    return if (spread != null && count == 2) {
        listOf(spread.leftWidthPx to spread.leftHeightPx, spread.rightWidthPx to spread.rightHeightPx)
    } else {
        val single = geometry.single
        val perSlotWidth = if (count > 0) single.widthPx / count else single.widthPx
        List(count) { perSlotWidth to single.heightPx }
    }
}
