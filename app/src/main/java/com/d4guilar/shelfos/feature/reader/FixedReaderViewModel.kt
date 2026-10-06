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
data class PageSlot(val page: Int, val bitmap: Bitmap? = null, val error: UiMessage? = null)

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
    // Pure index structure only (no geometry/IO) -- cheap to build once per session regardless of page count; see
    // SpreadModel.kt's nextPage/previousPage/resolveCurrentGroup docs for why this stays bounded-cost even for a
    // very large publication (geometry is only ever looked up for the single pair actually being navigated/shown,
    // never eagerly for the whole book).
    private var canonicalGroups: List<PageGroup> = emptyList()
    // Session-scoped, in-memory only (never persisted/disk-cached) landscape-geometry cache, keyed by logical
    // page index -- a page's own undistorted dimensions don't change within one open session. A failed/unknown
    // lookup is cached as PageGeometry(0, 0), which PageGeometry.isLandscape treats as "not landscape" (see its
    // doc), so a corrupt/undecodable page's geometry is never re-attempted every navigation.
    private val geometryCache = mutableMapOf<Int, PageGeometry>()
    // The spreadActive decision actually used by the most recently started render -- compared against a freshly
    // computed decision on every updateViewport call so a continuous resize/rotation stream only triggers a new
    // render on the rare call that actually flips single<->spread, never on every pixel of movement (see
    // updateViewport's doc).
    @Volatile private var lastSpreadActiveRendered: Boolean? = null
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
                _state.update { it.copy(item = item, globalPreferences = global,
                    preferences = resolveReaderPreferences(ReaderPreferences.parse(item.preferences), ReaderPreferences.parse(global))) }
                if (!opening) { opening = true; launch { open(item) } }
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
        return resolveSpreadActive(spreadMode(), viewportWidthDp)
    }

    /** Cached/bounded landscape lookup -- see [geometryCache]'s field doc. Never performs IO itself; a page whose
     * geometry has not yet been looked up (not cached) is conservatively treated as "not landscape" here, same as
     * [resolvePageGroups]'s documented default, rather than blocking the calling thread on a decode. The first
     * [render] of a group containing that page populates the cache for next time. */
    private fun isLandscapeAtCached(page: Int) = geometryCache[page]?.isLandscape == true

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
            1 -> nextPage(canonicalGroups, page, spreadActive(), ::isLandscapeAtCached)
            -1 -> previousPage(canonicalGroups, page, spreadActive(), ::isLandscapeAtCached)
            else -> page + delta
        }
        showPage(target)
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
    fun updateViewport(width: Int, height: Int, widthDp: Int) {
        if (width > 0 && height > 0) { viewportWidth = width; viewportHeight = height }
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
                        val slotWidth = if (viewportWidth > 0 && group.pages.isNotEmpty()) (viewportWidth / group.pages.size) else viewportWidth
                        for (idx in group.pages) {
                            ensureActive() // Re-checked before each sequential decode, not just once for the whole group.
                            val request = PageRenderRequest(slotWidth, viewportHeight, fit = fit)
                            decoded += try { PageSlot(idx, bitmap = reader.render(idx, request)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { PageSlot(idx, error = e.readerMessage()) }
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
