// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.designsystem.readerMessage
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 3C: one visible slot in the current reading unit -- a single logical source page. [page] is always the
 * true logical source-page index (never a synthetic "spread index"); [bitmap] and [error] are independent per
 * slot so a corrupt page within a pair ("Corrupt page within a spread" in `docs/PHASE_3_IMPLEMENTATION_PLAN.md`)
 * leaves its healthy sibling visible rather than blanking the whole pair.
 */
/** Codex R1 finding 3 (3C remediation): [geometry] is the page's own undistorted [PageGeometry] -- always
 * populated from [FixedReader.pageGeometry] regardless of whether [bitmap] decoded successfully, so a corrupt
 * slot's placeholder can still be laid out at the SOURCE page's own aspect ratio rather than borrowing its
 * sibling's (see [com.d4guilar.shelfos.feature.reader.combinedContentDimensions]). */
data class PageSlot(val page: Int, val bitmap: Bitmap? = null, val error: UiMessage? = null, val geometry: PageGeometry? = null)

data class FixedReaderState(val item: LibraryItem? = null, val page: Int = 0, val count: Int = 0,
    // Phase 3C: 1 slot for ordinary single-page reading (including SINGLE mode and any AUTO-resolved-single
    // case), 2 for an active spread. Never persisted -- always re-derived from `page` + spread preference +
    // window + page geometry (see FixedReaderViewModel.render). `page` remains the sole authoritative logical
    // position regardless of how many slots are currently shown.
    val slots: List<PageSlot> = emptyList(), val loading: Boolean = true, val error: UiMessage? = null,
    val preferences: ReaderPreferences = ReaderPreferences.DEFAULT, val globalPreferences: String? = null) {
    /** Backward-compatible single-bitmap view for callers/tests that only ever cared about one page (ordinary
     * single-page reading is still the common case): the slot matching [page] if present, else the first slot. */
    val bitmap: Bitmap? get() = slots.firstOrNull { it.page == page }?.bitmap ?: slots.firstOrNull()?.bitmap
}

/** Owns one fixed-layout session for one title; the session is closed when this ViewModel is cleared. */
class FixedReaderViewModel(private val id: String, private val repository: LibraryRepository,
    private val factory: FixedReaderFactory, private val appScope: CoroutineScope) : ViewModel() {
    private val _state = MutableStateFlow(FixedReaderState())
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var session: FixedReader? = null
    private var rendering: Job? = null
    @Volatile private var closed = false
    // Phase 3B: lazily decodes small page thumbnails for the on-demand thumbnail strip, scoped to this one session
    // (never app-global, never persisted -- see ThumbnailLoader's doc). Null until open() knows the real page
    // count; FixedReaderScreen treats a null loader as "thumbnails unavailable yet" the same way it already treats
    // state.count == 0 that way for the page slider.
    private var thumbnailLoader: ThumbnailLoader<Bitmap>? = null
    val thumbnails: ThumbnailLoader<Bitmap>? get() = thumbnailLoader
    // Last viewport size reported by FixedReaderScreen (Phase 3A). Read only when a *new* render is already about
    // to happen (open/page-turn/retry); updating it on its own never triggers a render, so a resize/rotation/fold
    // stream of onSizeChanged calls can never itself cause a render storm -- it only changes what resolution the
    // next naturally-occurring render asks for.
    @Volatile private var viewportWidth: Int = 0
    @Volatile private var viewportHeight: Int = 0
    // Phase 3C: dp (density-independent) viewport width, the unit AUTO's window-size decision is specified in
    // (resolveSpreadActive) -- tracked separately from the px viewportWidth/Height above, which stay the actual
    // decode-target inputs. Updated the same "never itself triggers a render" way as viewportWidth/Height.
    @Volatile private var viewportWidthDp: Int = 0
    // Phase 3D: non-null only while FixedReaderScreen's measured fold layout is a VERTICAL_SPLIT with two
    // positive-area panes; null for FLAT/HORIZONTAL_SPLIT (whose AUTO policy and slot sizing stay exactly the
    // flat 3C behavior, driven entirely by viewportWidth/viewportWidthDp above) or before the first fold layout
    // is measured. Same "updating never itself triggers a render" discipline as viewportWidth/viewportWidthDp --
    // see updateViewport's doc for the one narrow exception (an actual spreadActive() flip).
    @Volatile private var foldPaneWidths: FoldPaneWidths? = null
    // Pure index structure only (no geometry/IO) -- cheap to build once per session regardless of page count; see
    // SpreadModel.kt's nextPage/previousPage/resolveCurrentGroup docs for why this stays bounded-cost even for a
    // very large publication (geometry is only ever looked up for the single pair actually being navigated/shown,
    // never eagerly for the whole book).
    private var canonicalGroups: List<PageGroup> = emptyList()
    // Session-scoped, in-memory only (never persisted/disk-cached) landscape-geometry cache, keyed by logical
    // page index -- a page's own undistorted dimensions don't change within one open session. A failed/unknown
    // lookup is cached as PageGeometry(0, 0) (PageGeometry.isUsable == false for that sentinel), so a
    // corrupt/undecodable page's geometry is never re-attempted every navigation. Codex R2 finding 1 (3C
    // remediation): this cached sentinel is read very differently by the two consumers below -- resolveCurrentGroup's
    // PRESENTATION lambda (inside render()) keeps the original optimistic "unusable == not landscape" reading
    // (pairs it, reconciles once/if real geometry ever becomes known), while isLandscapeAtForNavigation below
    // must NEVER make that same optimistic read -- see its doc.
    //
    // Codex R1 finding 1 (3C remediation): this cache is WRITTEN from render()'s Dispatchers.IO critical section
    // (mutex-serialized against other writers, but not against concurrent UI-thread readers) and READ
    // synchronously from the UI thread by turn()/isLandscapeAtForNavigation -- a plain MutableMap offers no
    // visibility/safety guarantee across that reader/writer split. ConcurrentHashMap makes every get/getOrPut
    // safe to call from either thread without introducing a second lock or blocking either side on the other.
    private val geometryCache = java.util.concurrent.ConcurrentHashMap<Int, PageGeometry>()
    // The spreadActive decision actually used by the most recently started render -- compared against a freshly
    // computed decision on every updateViewport call so a continuous resize/rotation stream only triggers a new
    // render on the rare call that actually flips single<->spread, never on every pixel of movement (see
    // updateViewport's doc).
    @Volatile private var lastSpreadActiveRendered: Boolean? = null
    // Codex R1 finding 5 (3C remediation): the effective title SpreadMode this ViewModel last actually rendered
    // against, so the init{} collector below can detect a genuine mode change (title override set/cleared, or a
    // reset restoring AUTO) and reconcile the currently-visible page immediately -- see the collector's doc.
    // Null only before the very first preference emission is processed.
    @Volatile private var lastAppliedSpreadMode: SpreadMode? = null
    private val positions = PositionWriter<Int>(appScope, write = { page ->
        repository.reading(id, pageLocator(page), pageProgress(page, _state.value.count))
    }, onFailure = { _state.update { it.copy(error = UiMessage.Resource(R.string.reader_position_save_failed)) } })

    init {
        viewModelScope.launch {
            var opening = false
            combine(repository.publication(id), repository.globalPreferences) { item, global -> item to global }.collect { (item, global) ->
                if (item == null) {
                    _state.update { it.copy(loading = false, error = UiMessage.Resource(R.string.reader_no_longer_in_library)) }
                    return@collect
                }
                val resolved = resolveReaderPreferences(ReaderPreferences.parse(item.preferences), ReaderPreferences.parse(global))
                _state.update { it.copy(item = item, globalPreferences = global, preferences = resolved) }
                val effectiveSpreadMode = resolved.spreadMode ?: SpreadMode.AUTO
                if (!opening) {
                    opening = true
                    lastAppliedSpreadMode = effectiveSpreadMode
                    launch { open(item) }
                } else if (effectiveSpreadMode != lastAppliedSpreadMode && _state.value.count > 0) {
                    // Codex R1 finding 5: the EFFECTIVE title SpreadMode (title override set/cleared, or a reset
                    // restoring AUTO) just changed for an already-open session -- reconcile the currently visible
                    // logical page's presentation immediately (narrowly scoped to this one field: an unrelated
                    // preference change, or a global-only change that never affects the per-title spreadMode
                    // resolution, never re-triggers this). render() never changes state.page, the locator, or
                    // progress, and never writes a new position -- it only re-derives the visible slot group for
                    // the page that was already current.
                    lastAppliedSpreadMode = effectiveSpreadMode
                    render(_state.value.page)
                } else {
                    lastAppliedSpreadMode = effectiveSpreadMode
                }
            }
        }
    }

    private suspend fun open(item: LibraryItem) {
        try {
            val count = withContext(Dispatchers.IO) { mutex.withLock {
                val engine = factory.open(item)
                if (closed) { engine.close(); throw CancellationException("Reader closed while opening") }
                if (engine.pageCount <= 0) { engine.close(); throw PublicationException(PublicationProblem.CORRUPT, PublicationExceptionDetail.PUBLICATION_HAS_NO_READABLE_PAGES) }
                session = engine
                engine.pageCount
            } }
            markAvailable(true)
            canonicalGroups = canonicalPageGroups(count)
            val page = restorePage(item.locator, count)
            _state.update { it.copy(count = count, page = page) }
            positions.save(page)
            // The thumbnail loader shares this ViewModel's own render mutex/session for every decode (see
            // ThumbnailLoader's class doc for why: neither PdfRenderer nor the CBZ PageSource path documents
            // concurrent-access safety), so it is only ever created once a session/count genuinely exists.
            thumbnailLoader = ThumbnailLoader(scope = viewModelScope, pageCount = count, sizeOf = { it.byteCount.toLong() },
                decode = { index -> withContext(Dispatchers.IO) { mutex.withLock {
                    requireNotNull(session).render(index, PageRenderRequest.thumbnail())
                } } })
            render(page)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e.publicationProblem().unavailable) markAvailable(false)
            _state.update { it.copy(loading = false, error = e.readerMessage()) }
        }
    }

    /** The spread preference currently in effect for this title (AUTO if unset -- see [ReaderPreferences.DEFAULT]). */
    private fun spreadMode() = _state.value.preferences.spreadMode ?: SpreadMode.AUTO

    /** Phase 3D: the same RTL resolution [FixedReaderScreen] already computes for physical page placement
     * (`readingDirection(item.category, preferences.direction) == ReadingDirection.RTL`), reused here ONLY to
     * map a fold-aware spread's logical pages onto the correct physical pane for render-request sizing -- never
     * a second RTL system, never affecting [FixedReaderState.page]/the locator/progress. */
    private fun itemCategoryIsRtl(): Boolean {
        val item = _state.value.item ?: return false
        return readingDirection(item.category, _state.value.preferences.direction) == ReadingDirection.RTL
    }

    /** Whether a spread is currently active: the window/preference decision ([resolveSpreadActive]), defensively
     * ANDed with [spreadCapable] for this item's actual format/category -- never Books/Documents, regardless of
     * what a stray [SpreadMode] value might say (e.g. a title re-categorized from Comic to Book after a SPREAD
     * preference was already saved). The UI-level gate ([ReaderAppearance]'s `capabilities.spread`, which hides
     * the control entirely for non-Comic/Manga) is the primary enforcement; this is defense-in-depth so the
     * render pipeline itself never shows a Book/Document as a spread even if a stray preference value exists.
     * Pure/cheap -- safe to call synchronously from the UI thread (turn/showPage). */
    private fun spreadActive(): Boolean {
        val item = _state.value.item ?: return false
        if (!spreadCapable(item.format, item.category)) return false
        // Phase 3D: under a vertical fold split, AUTO/SPREAD consult the fold-aware two-pane policy
        // (verticalFoldSpreadEligibleForAuto / FoldPaneWidths.bothPanesUsable) instead of the flat 3C
        // total-width rule -- "don't decide from total window width alone if one pane is a tiny sliver." SINGLE
        // is unaffected either way: it is always false. FLAT/HORIZONTAL_SPLIT (foldPaneWidths == null) keep the
        // exact flat 3C behavior unchanged.
        val fold = foldPaneWidths
        return when (val mode = spreadMode()) {
            SpreadMode.SINGLE -> false
            SpreadMode.SPREAD -> if (fold != null) fold.bothPanesUsable else resolveSpreadActive(mode, viewportWidthDp)
            SpreadMode.AUTO -> if (fold != null) verticalFoldSpreadEligibleForAuto(fold.leftDp, fold.rightDp)
                else resolveSpreadActive(mode, viewportWidthDp)
        }
    }

    /**
     * Codex R1 finding 1 (3C remediation): cached/bounded landscape lookup used ONLY by semantic navigation
     * ([turn], [hasNext], [hasPrevious]). Never performs IO itself -- it must stay safe to call synchronously
     * from the UI thread. Unlike [resolvePageGroups]'s documented *presentation* default ("unknown geometry is
     * treated as not landscape, optimistic pairing reconciles naturally once geometry resolves"), navigation
     * must NEVER let unresolved geometry enable a skip: a page whose geometry has not yet resolved in
     * [geometryCache] is treated as landscape=true here -- conservative, not optimistic -- so [nextPage]/
     * [previousPage] fall back to single-step (no-skip) movement into the unresolved pair's first member rather
     * than assuming it is safe to jump straight past the whole pair. Once [render] actually resolves that page's
     * real geometry (which it always does before deciding how many slots to show it, inside the IO-dispatched
     * critical section -- see [render]'s doc), subsequent navigation naturally sees the true cached value and
     * behaves exactly like the always-resolved case. This asymmetry (conservative for navigation, optimistic for
     * presentation) is intentional: a wrong presentation guess self-corrects the moment it renders, but a wrong
     * navigation guess silently skips an unshown page and can persist the wrong progress.
     *
     * Codex R2 finding 1 (3C remediation): a cached entry is not automatically "known good" just because it's
     * present -- [PageGeometry.isUsable] must be checked FIRST. The previous `geometryCache[page]?.isLandscape
     * ?: true` only ever fell back to the conservative `true` when the page had no cache entry at all; once
     * [render] cached the `PageGeometry(0, 0)` failure sentinel for a page whose geometry lookup itself failed
     * (e.g. a corrupt/undecodable page), that sentinel IS present in the cache, so the `?:` fallback never ran --
     * `PageGeometry(0, 0).isLandscape` evaluates to `false` (not landscape), which [nextPage]/[previousPage] then
     * read as "confirmed pair-eligible," authorizing exactly the multi-page skip this function exists to prevent.
     * An unusable cached value must behave exactly like a missing one here: both mean "unknown, might be
     * landscape, do not skip."
     */
    private fun isLandscapeAtForNavigation(page: Int): Boolean {
        val geometry = geometryCache[page] ?: return true
        return !geometry.isUsable || geometry.isLandscape
    }

    /**
     * Phase 3C: semantic forward/backward navigation between visible reading units, reusing the existing
     * [ShelfCommand.NEXT_PAGE][com.d4guilar.shelfos.core.input.ShelfCommand.NEXT_PAGE]/
     * [PREVIOUS_PAGE][com.d4guilar.shelfos.core.input.ShelfCommand.PREVIOUS_PAGE] semantic commands unchanged --
     * no `NEXT_SPREAD` exists. In [SpreadMode.SINGLE] (or AUTO resolved to single), [nextPage]/[previousPage]
     * reduce to ordinary `page +/- 1`; when a spread is active, [delta] of `+1`/`-1` moves to the next/previous
     * visible GROUP rather than literally one page, so pressing Next from either half of a displayed pair never
     * shows the same pair again. A [delta] other than `+1`/`-1` (not used by [ShelfCommand]-driven navigation
     * today) falls back to plain arithmetic.
     */
    fun turn(delta: Int) {
        val page = _state.value.page
        val target = when (delta) {
            1 -> nextPage(canonicalGroups, page, spreadActive(), ::isLandscapeAtForNavigation)
            -1 -> previousPage(canonicalGroups, page, spreadActive(), ::isLandscapeAtForNavigation)
            else -> page + delta
        }
        showPage(target)
    }

    /**
     * Codex R1 finding 6 (3C remediation): whether Next/Previous actually has a different semantic reading-unit
     * target to move to, reusing the EXACT SAME canonical navigation resolver [turn] itself uses -- never
     * duplicated arithmetic in Compose. This is deliberately not `state.page + 1 < state.count`/`state.page > 0`:
     * that raw arithmetic is wrong exactly when [state]'s current page is the first member of the FINAL complete
     * spread (e.g. count=5, final group [3,4], page=3 -- a page 4 exists, but there is no next READING UNIT to
     * advance to), and symmetrically wrong at the opposite boundary for Previous. A control is enabled only when
     * its real navigation target actually differs from the current page.
     */
    fun hasNext(): Boolean {
        val page = _state.value.page
        return nextPage(canonicalGroups, page, spreadActive(), ::isLandscapeAtForNavigation) != page
    }

    /** See [hasNext]; symmetric backward semantic enablement. */
    fun hasPrevious(): Boolean {
        val page = _state.value.page
        return previousPage(canonicalGroups, page, spreadActive(), ::isLandscapeAtForNavigation) != page
    }

    fun showPage(index: Int) {
        val count = _state.value.count
        if (count <= 0) return
        val page = index.coerceIn(0, count - 1)
        if (page == _state.value.page && _state.value.bitmap != null && _state.value.error == null) return
        // The requested LOGICAL page is the authoritative position, even when its containing group is a spread
        // whose other member has a lower index (e.g. selecting a thumbnail's second pair member keeps `page`
        // exactly as requested -- see `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s "current logical page" invariant).
        _state.update { it.copy(page = page) }
        positions.save(page) // The requested position persists even if leaving before the page renders.
        render(page)
    }

    fun retry() = render(_state.value.page)

    /** Records the reader page surface's current measured size (Phase 3A) plus its density-independent width in
     * dp (Phase 3C -- the unit AUTO's window threshold, [AUTO_SPREAD_MIN_WIDTH_DP], is specified in). Updating the
     * stored size never itself triggers a render -- see the field docs above -- EXCEPT for the one narrowly-scoped
     * Phase 3C case where the width crossing [AUTO_SPREAD_MIN_WIDTH_DP] actually changes AUTO's single/spread
     * decision: that is the only resize-driven re-render, it is coalesced to at most once per actual flip (not
     * once per pixel of a continuous resize/rotation/fold gesture, since most size changes don't cross the
     * threshold), it never changes `state.page`/the locator/progress, and it is a no-op before a session/page
     * exists (count == 0), so this stays safe to call on every `onSizeChanged`. */
    /**
     * [foldPaneWidths]: Phase 3D's fold-aware pane widths (`null` when flat/horizontal-fold/not-yet-measured --
     * see the field's own doc). Stored unconditionally on every call (even when `width`/`height`/`widthDp` are
     * themselves not updated this call), exactly like the existing px/dp fields, so it is always current the
     * next time [render] actually runs -- but, per the render-storm guard this method has always enforced,
     * storing a new value here never itself triggers a render. The ONE existing narrow exception (an actual
     * [spreadActive] single<->spread flip) is reused unchanged rather than duplicated: [spreadActive] itself now
     * consults [foldPaneWidths] when present, so a continuous resize/posture-transition stream that keeps
     * [spreadActive]'s boolean result the same (the overwhelming majority of fold-layout recomputation, which
     * happens every frame in Compose for pure UI placement/clipping) never forces a new decode -- only the rare
     * call that actually changes the single/spread decision does, exactly mirroring 3C's AUTO-width coalescing.
     */
    fun updateViewport(width: Int, height: Int, widthDp: Int, foldPaneWidths: FoldPaneWidths? = null) {
        if (width > 0 && height > 0) { viewportWidth = width; viewportHeight = height }
        this.foldPaneWidths = foldPaneWidths
        if (widthDp > 0) {
            viewportWidthDp = widthDp
            val count = _state.value.count
            if (count > 0) {
                val active = spreadActive()
                if (active != lastSpreadActiveRendered) render(_state.value.page)
            }
        }
    }

    /**
     * Renders the current logical [page]'s visible reading unit (one page, or an active spread's pair -- see
     * [resolveCurrentGroup]) and either publishes or disposes of each slot's result. Codex R1 finding 3 (peak
     * memory) still governs this fully in Phase 3C: every slot in the group is decoded SEQUENTIALLY (never in
     * parallel) inside the same single [mutex] critical section used for ordinary single-page reads and for
     * thumbnails, so at most one full-resolution decode is ever in flight at a time regardless of spread mode --
     * a spread's two slots are two sequential decodes, not two concurrent ones. The cancellation check, each
     * decode, and the publish-or-recycle outcome for the WHOLE group all happen before the lock is released, so a
     * newer render (already launched by a fast page-turn/retry that already called `rendering?.cancel()` on this
     * job) can never begin decoding until this one has resolved every slot's fate -- preserving the exact
     * invariant Codex R1 remediation established for the single-page case.
     *
     * Codex R1 finding 2 (3C remediation) -- peak ownership during a spread transition: this method never
     * manually recycles the OLD slots still published in [_state] (Compose/the StateFlow collector may still
     * hold them), and decodes every NEW slot sequentially into the local [decoded] list before publishing, so a
     * spread-to-spread replacement's real worst case is FOUR concurrently-live reading-resolution bitmaps (two
     * old, strongly referenced by whatever last observed [_state]; two new, strongly referenced by [decoded]) --
     * never more, because the shared [mutex] guarantees only one render's decode section is ever actually
     * running. [PageRenderRequest.spreadSlot] is set whenever [group] has more than one page, which routes each
     * such decode through [RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES] instead of [RenderMemoryPolicy.MAX_BITMAP_BYTES],
     * so that real four-bitmap worst case is mechanically bounded at exactly [RenderMemoryPolicy.READING_BUDGET_BYTES]
     * rather than merely reasoned about in documentation.
     *
     * Per-slot sizing: each slot's [PageRenderRequest.viewportWidth] is the measured viewport width divided by
     * the number of slots in the group (full [viewportHeight] either way) -- an approximation that ignores the
     * few-dp inter-page gutter (a pure design-token/layout concern, not a decode-resolution one); harmless, since
     * [RenderMemoryPolicy]'s byte-budget ceiling already bounds the result regardless of a slightly-generous
     * width estimate.
     *
     * Corrupt-page handling: a decode failure for one slot never blanks its sibling -- that slot's [PageSlot]
     * carries [PageSlot.error] instead of a bitmap, the other slot (if any) keeps its own successfully-decoded
     * bitmap, and the top-level [FixedReaderState.error] (the Retry/Back-to-library affordance) is only set when
     * EVERY slot in the group failed, exactly matching ordinary single-page behavior when there is only one slot.
     */
    private fun render(page: Int) {
        rendering?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        val spreadActive = spreadActive()
        lastSpreadActiveRendered = spreadActive
        val fit = _state.value.preferences.fit ?: FitMode.PAGE
        rendering = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val decoded = mutableListOf<PageSlot>()
                    try {
                        val reader = requireNotNull(session)
                        ensureActive()
                        val group = resolveCurrentGroup(canonicalGroups, page, spreadActive) { idx ->
                            geometryCache.getOrPut(idx) { reader.pageGeometry(idx) ?: PageGeometry(0, 0) }.isLandscape
                        }
                        val flatSlotWidth = if (viewportWidth > 0 && group.pages.isNotEmpty()) (viewportWidth / group.pages.size) else viewportWidth
                        // Codex R1 finding 2 (3C remediation): an ACTIVE spread's slots (group.pages.size > 1)
                        // decode under RenderMemoryPolicy's stricter spread-transition per-slot budget -- see
                        // PageRenderRequest.spreadSlot / RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES.
                        val spreadSlot = group.pages.size > 1
                        // Phase 3D render-request sizing: when a real vertical fold split is active AND this is
                        // a 2-slot spread, each LOGICAL page in the group is decoded at the PIXEL WIDTH of the
                        // physical pane it will actually occupy, rather than the flat viewportWidth/2
                        // approximation -- "don't request each slot at whole-window width" for an asymmetric
                        // hinge. Mapping from logical (ascending) order to physical left/right pane reuses the
                        // EXACT SAME PageGroup.physicalOrder(rtl) the Compose layer uses for drawing (no second
                        // RTL system). Solo-page (group.pages.size == 1) and FLAT/HORIZONTAL_SPLIT (foldPaneWidths
                        // == null) both keep the flat sizing unchanged -- this is scoped narrowly to the one case
                        // the 3D contract calls out ("where practical"), never weakening RenderMemoryPolicy's
                        // byte-budget ceiling, which still applies identically regardless of the width hint.
                        val fold = foldPaneWidths
                        val widthForIndex: (Int) -> Int = if (spreadSlot && fold != null && group.pages.size == 2) {
                            val rtl = itemCategoryIsRtl()
                            val physical = PageGroup(group.pages).physicalOrder(rtl) // [0]=left pane, [1]=right pane
                            val widthByPage = mapOf(physical[0] to fold.leftPx, physical[1] to fold.rightPx)
                            fun(idx: Int): Int = widthByPage[idx] ?: flatSlotWidth
                        } else fun(_: Int): Int = flatSlotWidth
                        for (idx in group.pages) {
                            ensureActive() // Re-checked before each sequential decode, not just once for the whole group.
                            // Codex R1 finding 3 (3C remediation): always resolve this page's own geometry BEFORE
                            // deciding its fate, regardless of whether the decode below succeeds -- a failed
                            // decode must still carry its own source geometry so combinedContentDimensions()/the
                            // Compose placeholder never have to borrow a sibling's aspect ratio for it.
                            val geometry = geometryCache.getOrPut(idx) { reader.pageGeometry(idx) ?: PageGeometry(0, 0) }
                            val request = PageRenderRequest(widthForIndex(idx), viewportHeight, fit = fit, spreadSlot = spreadSlot)
                            decoded += try { PageSlot(idx, bitmap = reader.render(idx, request), geometry = geometry) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { PageSlot(idx, error = e.readerMessage(), geometry = geometry) }
                        }
                        ensureActive() // Checked once more, still holding the lock, before publishing -- see the method doc above.
                        val allFailed = decoded.all { it.bitmap == null }
                        _state.update { it.copy(slots = decoded, loading = false,
                            error = if (allFailed) decoded.firstOrNull()?.error else null) }
                    } catch (e: CancellationException) {
                        decoded.forEach { it.bitmap?.recycle() } // Published bitmaps are owned by Compose/GC, never touched here.
                        throw e
                    } catch (e: Exception) {
                        decoded.forEach { it.bitmap?.recycle() }
                        _state.update { it.copy(slots = emptyList(), loading = false, error = e.readerMessage()) }
                    }
                }
            }
        }
    }

    fun applyAppearance(before: ReaderPreferences, after: ReaderPreferences, globally: Boolean) = persistPreferences {
        val current = _state.value
        repository.saveAppearance(id, current.item?.preferences ?: "{}", current.globalPreferences, before, after, globally)
    }

    fun resetAppearance(globally: Boolean) = persistPreferences {
        repository.resetAppearance(id, _state.value.item?.preferences ?: "{}", globally)
    }

    private fun persistPreferences(block: suspend () -> Unit) { viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = UiMessage.Resource(R.string.reader_preferences_save_failed)) } }
    } }

    private suspend fun markAvailable(available: Boolean) {
        try { repository.available(id, available) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Display state only. */ }
    }

    override fun onCleared() {
        closed = true
        positions.close()
        thumbnailLoader?.close()
        appScope.launch { mutex.withLock { session?.close(); session = null } }
    }
}
