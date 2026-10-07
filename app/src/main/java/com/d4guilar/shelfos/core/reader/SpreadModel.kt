// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

/**
 * Phase 3C: the user-facing comic/manga spread presentation preference. Persisted per-title only (see
 * [ReaderPreferences.spreadMode] and its "never global" handling in [appearanceUpdate]) -- this is a
 * presentation choice like [ReadingDirection] override, not a shared default. [AUTO] lets the window decide
 * (see [resolveSpreadActive]); [SINGLE] and [SPREAD] are explicit overrides that take priority over the window.
 *
 * Never persisted as anything beyond this preference layer: no derived spread index, no "which pair is active"
 * state, no synthetic page identity. See [resolvePageGroups] for how a mode turns into the actual visible
 * groups, always recomputed from [ReaderPreferences.spreadMode] + the current logical page + current window
 * width + page geometry, never stored itself.
 */
enum class SpreadMode { AUTO, SINGLE, SPREAD }

/**
 * One source page's own undistorted pixel dimensions, as needed for spread-pairing eligibility
 * ([isLandscape]) -- deliberately small and format-neutral (no [android.graphics.Bitmap], no ZIP/PDF types),
 * satisfiable by a bounds-only decode (CBZ) or `PdfRenderer.Page.width`/`height` (PDF) without a full-resolution
 * render, and trivially satisfiable by a future CBR adapter the same way CBZ does today.
 */
data class PageGeometry(val width: Int, val height: Int) {
    /**
     * Codex R2 finding 1 (3C remediation): the single centralized definition of "this geometry is actually safe
     * to make a pairing/navigation decision from." `false` for the [FixedReaderViewModel.geometryCache]
     * [com.d4guilar.shelfos.feature.reader.FixedReaderViewModel.geometryCache] failure sentinel
     * (`PageGeometry(0, 0)`, written whenever [FixedReader.pageGeometry][com.d4guilar.shelfos.core.reader.FixedReader.pageGeometry]
     * itself returns `null`) and for any other non-positive dimension, never just for the exact `(0, 0)` pair.
     * Deliberately a named property rather than a `width <= 0 || height <= 0` check repeated at each call site
     * (there were previously two independent copies of that check: [isLandscape] below and
     * [FixedReaderScreen][com.d4guilar.shelfos.feature.reader.FixedReaderScreen]'s private `isUnknown()`) -- one
     * semantic source of truth that both now derive from.
     */
    val isUsable: Boolean get() = width > 0 && height > 0

    /**
     * A page clearly wider than it is tall is treated as solo-only for spread presentation, even in explicit
     * [SpreadMode.SPREAD] -- SPREAD means "pair eligible adjacent pages," not "force a wide page beside
     * another." This is a deterministic aspect-ratio rule, not panel/content detection: it never hides, skips,
     * duplicates or reorders either page in the pair it would otherwise have joined -- see [resolvePageGroups].
     * Always `false` for unusable geometry ([isUsable]) -- this is the PRESENTATION answer only; navigation
     * callers must never treat that `false` as "confirmed not landscape" (see
     * [FixedReaderViewModel.isLandscapeAtForNavigation][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]'s
     * doc for why presentation and navigation deliberately read unusable geometry differently).
     */
    val isLandscape: Boolean get() = isUsable &&
        width.toDouble() / height.toDouble() > LANDSCAPE_ASPECT_THRESHOLD

    companion object {
        /**
         * Named, documented conservative threshold (width/height) above which a page is classified landscape/wide
         * for spread-pairing purposes. Set comfortably above `1.0` (square) so an ordinary portrait page -- or a
         * near-square page from minor scan-edge cropping -- is never misclassified as landscape; a page would need
         * to be genuinely wider than tall by a clear margin to trip this. This is the one deliberately conservative
         * magic-number-avoidance constant called for when evidence suggests plain `width > height` alone could
         * misclassify near-square pages.
         */
        const val LANDSCAPE_ASPECT_THRESHOLD = 1.05
    }
}

/**
 * One visible reading unit: one or two zero-based LOGICAL source-page indices, always listed low-to-high
 * ([pages]\[0\] is always the lower logical index regardless of reading direction -- see [physicalOrder] for
 * LTR/RTL placement). Never itself persisted; always derived fresh from [resolvePageGroups]/[canonicalPageGroups].
 */
data class PageGroup(val pages: List<Int>) {
    init { require(pages.isNotEmpty() && pages.size <= 2) { "A page group holds 1 or 2 logical pages, got ${pages.size}" } }
    val isSpread: Boolean get() = pages.size == 2
    operator fun contains(page: Int): Boolean = page in pages

    /**
     * Physical left-to-right placement for this group. LTR: logical order unchanged (lower index on the left).
     * RTL Manga: placement mirrors (higher logical index on the left) -- but the LOGICAL pair membership and the
     * values themselves never change; this function only reorders how [pages] are laid out on screen, exactly
     * the same presentation-only distinction [readingDirection] already draws for ordinary page turning.
     */
    fun physicalOrder(rightToLeft: Boolean): List<Int> = if (rightToLeft) pages.asReversed() else pages
}

/**
 * The canonical pairing rule, independent of spread mode, landscape pages, or window size: page `0` (cover/first
 * page) is always solo; interior pages pair `[1,2]`, `[3,4]`, `[5,6]`...; an unmatched final page is solo. Pure
 * zero-based-index math -- callers ([resolvePageGroups]) decide whether/how to actually use spread presentation
 * around this grouping. A non-positive [pageCount] yields an empty list.
 */
fun canonicalPageGroups(pageCount: Int): List<PageGroup> {
    if (pageCount <= 0) return emptyList()
    val groups = mutableListOf(PageGroup(listOf(0)))
    var i = 1
    while (i < pageCount) {
        if (i + 1 < pageCount) { groups += PageGroup(listOf(i, i + 1)); i += 2 } else { groups += PageGroup(listOf(i)); i += 1 }
    }
    return groups
}

/**
 * The groups actually used for presentation. When [spreadActive] is `false` (explicit [SpreadMode.SINGLE], or
 * [SpreadMode.AUTO] resolving to single on a narrow window -- see [resolveSpreadActive]), every page is solo,
 * one group per logical index, in order -- ordinary single-page reading, unchanged from pre-3C behavior.
 *
 * When [spreadActive] is `true`, [canonicalPageGroups] applies, except a canonical pair containing a landscape
 * page ([PageGeometry.isLandscape], via [geometryOf]) is split into two solo groups instead of being forced
 * together -- neither page is hidden, skipped, duplicated, or reordered; they simply each become their own
 * group, and every later canonical pair is unaffected (grouping never "re-flows" around a split pair, keeping
 * the canonical rule stable regardless of which earlier pages were landscape). [geometryOf] returning `null`
 * (geometry not yet known, e.g. not decoded) is treated as "not landscape" -- pairing proceeds optimistically and
 * is naturally re-evaluated the next time this function is called with fresher geometry; this never crashes and
 * never produces an invalid grouping, it only means a landscape page whose geometry isn't known yet may briefly
 * still be offered paired before geometry resolves.
 */
fun resolvePageGroups(pageCount: Int, spreadActive: Boolean, geometryOf: (Int) -> PageGeometry?): List<PageGroup> =
    resolveGroups(canonicalPageGroups(pageCount), spreadActive, geometryOf)

/**
 * [resolvePageGroups]'s per-group resolution, factored out to accept an already-computed [canonical] list (or a
 * bounded slice of one) -- [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel] never
 * calls this over an entire large publication (that would mean a landscape-geometry lookup for every canonical
 * pair in the book just to turn one page); see [nextPage]/[resolveCurrentGroup] for the bounded-cost real
 * navigation/rendering path this function still backs conceptually and which the tests prove equivalent to.
 */
fun resolveGroups(canonical: List<PageGroup>, spreadActive: Boolean, geometryOf: (Int) -> PageGeometry?): List<PageGroup> {
    if (!spreadActive) return canonical.flatMap { it.pages }.map { PageGroup(listOf(it)) }
    return canonical.flatMap { group ->
        if (!group.isSpread) listOf(group)
        else if (group.pages.any { geometryOf(it)?.isLandscape == true }) group.pages.map { PageGroup(listOf(it)) }
        else listOf(group)
    }
}

/** The group from [groups] that contains logical page [page], or `null` if [page] is out of range / [groups] is
 * empty. Never normalizes [page] to the group's lower index -- callers keep the caller-supplied logical page as
 * the authoritative current page (see `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s "current logical page" invariant). */
fun groupContaining(groups: List<PageGroup>, page: Int): PageGroup? = groups.find { page in it }

/**
 * AUTO's window-size decision: whether the current viewport is wide enough to show a two-page spread legibly.
 * Width-only (dp, density-independent), never device-model/posture-aware -- hinge/fold intelligence is explicit
 * 3D scope, not this function's job (see `docs/design/FOLDABLES.md`, AGENTS.md rule 18).
 */
fun resolveSpreadActive(mode: SpreadMode, viewportWidthDp: Int): Boolean = when (mode) {
    SpreadMode.SINGLE -> false
    SpreadMode.SPREAD -> true
    SpreadMode.AUTO -> viewportWidthDp >= AUTO_SPREAD_MIN_WIDTH_DP
}

/**
 * Named, documented AUTO width threshold (dp): the minimum viewport width AUTO requires before it shows a
 * two-page spread instead of a single page. No canonical window-size-class primitive exists yet elsewhere in
 * ShelfOS (verified by inspection before adding this), so this is a new, narrowly-scoped, independently-testable
 * constant rather than a new dependency. Chosen comfortably above a typical single-pane phone-portrait width
 * (~400dp) and below Android's own conventional "two-pane-capable" breakpoint (600dp expanded / 840dp large, per
 * Android's published window-size-class guidance) is deliberately NOT reused wholesale here, because two legible
 * comic/manga pages side by side (not just "two panes of arbitrary UI") need real width -- 600dp is chosen as the
 * conservative point above which two pages at a readable width are plausible on an ordinary unfolded tablet/
 * large-phone-landscape window, while staying well below any foldable-hinge-specific reasoning (explicit 3D
 * scope; this function only ever sees ordinary window width, never posture).
 */
const val AUTO_SPREAD_MIN_WIDTH_DP = 600

/**
 * Semantic forward/backward navigation between visible reading units (not literal page ±1), reused by
 * [ShelfCommand][com.d4guilar.shelfos.core.input.ShelfCommand]'s existing `NEXT_PAGE`/`PREVIOUS_PAGE` -- no new
 * command is introduced for spreads. From any page within a group, moving forward lands on the first page of the
 * next group; moving backward lands on the first page of the previous group. At either boundary, the current
 * page is returned unchanged (same "disabled at the edge" semantics ordinary single-page turning already has).
 * In [SpreadMode.SINGLE] (or AUTO resolved to single), every group is already solo, so this reduces to ordinary
 * ±1 paging automatically -- no special-casing needed by callers.
 */
fun nextLogicalPage(groups: List<PageGroup>, currentPage: Int): Int {
    val index = groups.indexOfFirst { currentPage in it }
    if (index < 0 || index + 1 >= groups.size) return currentPage
    return groups[index + 1].pages.first()
}

/** See [nextLogicalPage]; symmetric backward semantic navigation. */
fun previousLogicalPage(groups: List<PageGroup>, currentPage: Int): Int {
    val index = groups.indexOfFirst { currentPage in it }
    if (index <= 0) return currentPage
    return groups[index - 1].pages.first()
}

/**
 * Bounded-cost equivalent of [nextLogicalPage], for callers (the real [FixedReaderViewModel]
 * [com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]) that cannot afford to resolve landscape-split
 * status for an entire publication's canonical pairs on every navigation (a large CBZ/PDF could have hundreds of
 * pairs, each requiring a geometry lookup). [canonicalGroups] is the cheap, geometry-free
 * [canonicalPageGroups] result (pure index structure, computed once per session); [isLandscapeAt] is consulted
 * *only* for the at-most-two pages in the single canonical pair containing [page] -- never for any other pair --
 * because which page starts the NEXT canonical group never depends on whether that next pair itself eventually
 * splits (splitting only changes how many separate visible groups a pair becomes, never which page is lowest in
 * it). Does not clamp into `0 until pageCount`; callers apply the same coercion they already apply to ordinary
 * `page + 1` (e.g. [FixedReaderViewModel.showPage][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel.showPage]).
 */
fun nextPage(canonicalGroups: List<PageGroup>, page: Int, spreadActive: Boolean, isLandscapeAt: (Int) -> Boolean): Int {
    if (!spreadActive) return page + 1
    val index = canonicalGroups.indexOfFirst { page in it }
    if (index < 0) return page + 1
    val group = canonicalGroups[index]
    if (group.isSpread && page == group.pages[0] && group.pages.any(isLandscapeAt)) return group.pages[1]
    return if (index + 1 < canonicalGroups.size) canonicalGroups[index + 1].pages.first() else page
}

/** See [nextPage]; symmetric bounded-cost backward semantic navigation. */
fun previousPage(canonicalGroups: List<PageGroup>, page: Int, spreadActive: Boolean, isLandscapeAt: (Int) -> Boolean): Int {
    if (!spreadActive) return page - 1
    val index = canonicalGroups.indexOfFirst { page in it }
    if (index < 0) return page - 1
    val group = canonicalGroups[index]
    if (group.isSpread && page == group.pages[1] && group.pages.any(isLandscapeAt)) return group.pages[0]
    return if (index - 1 >= 0) canonicalGroups[index - 1].pages.first() else page
}

/**
 * The canonical pair containing [page] (empty-geometry-aware resolution of just that one pair, never the whole
 * book), used by [FixedReaderViewModel][com.d4guilar.shelfos.feature.reader.FixedReaderViewModel] to decide how
 * many slots to render for the page currently being shown. See [nextPage]'s doc for why this stays bounded to a
 * single pair's geometry regardless of publication size.
 */
fun resolveCurrentGroup(canonicalGroups: List<PageGroup>, page: Int, spreadActive: Boolean, isLandscapeAt: (Int) -> Boolean): PageGroup {
    if (!spreadActive) return PageGroup(listOf(page))
    val group = canonicalGroups.find { page in it } ?: return PageGroup(listOf(page))
    if (!group.isSpread) return group
    return if (group.pages.any(isLandscapeAt)) PageGroup(listOf(page)) else group
}
