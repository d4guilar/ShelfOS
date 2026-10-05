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

data class FixedReaderState(val item: LibraryItem? = null, val page: Int = 0, val count: Int = 0,
    val bitmap: Bitmap? = null, val loading: Boolean = true, val error: UiMessage? = null,
    val preferences: ReaderPreferences = ReaderPreferences.DEFAULT, val globalPreferences: String? = null)

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

    fun turn(delta: Int) = showPage(_state.value.page + delta)

    fun showPage(index: Int) {
        val count = _state.value.count
        if (count <= 0) return
        val page = index.coerceIn(0, count - 1)
        if (page == _state.value.page && _state.value.bitmap != null && _state.value.error == null) return
        _state.update { it.copy(page = page) }
        positions.save(page) // The requested position persists even if leaving before the page renders.
        render(page)
    }

    fun retry() = render(_state.value.page)

    /** Records the reader page surface's current measured size (Phase 3A). Does not itself trigger a render --
     * see the field doc above -- so this is safe to call on every `onSizeChanged`, including during a continuous
     * resize/rotation/fold-in-progress, without risking a render storm. */
    fun updateViewport(width: Int, height: Int) {
        if (width > 0 && height > 0) { viewportWidth = width; viewportHeight = height }
    }

    /**
     * Renders [page] and either publishes or disposes of the result. Codex R1 finding 3 (peak memory): the
     * decode, the cancellation check, and the publish-or-recycle outcome all now happen *inside* the same
     * [mutex] critical section, on the IO dispatcher. Previously the lock was released as soon as `session.render`
     * returned, before this job checked whether it had been cancelled and before it recycled a stale result --
     * which let a newer render (already launched by a fast page-turn/retry that had already called
     * `rendering?.cancel()` on this job) start its own decode while this job's just-decoded bitmap was still
     * alive, unpublished and unrecycled. That allowed three same-session bitmaps to be live at once: the
     * displayed one, this stale one, and the newer render's. Holding the lock across resolution-of-fate closes
     * that window: a newer render can never begin decoding until this one has either published its bitmap to
     * state or recycled it, so at most one "currently decoding or just-finished" bitmap exists at a time, plus
     * whatever the UI still displays -- the two-bitmap case [RenderMemoryPolicy] budgets for as the normal case,
     * with its third slot kept as a documented margin rather than something this lifecycle encourages.
     */
    private fun render(page: Int) {
        rendering?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        val request = PageRenderRequest(viewportWidth, viewportHeight, fit = _state.value.preferences.fit ?: FitMode.PAGE)
        rendering = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    var result: Bitmap? = null
                    try {
                        result = requireNotNull(session).render(page, request)
                        ensureActive() // Checked while still holding the lock -- see the method doc above.
                        _state.update { it.copy(bitmap = result, loading = false) }
                        result = null // Published bitmaps are owned by Compose/GC, not manually recycled while displayed.
                    } catch (e: CancellationException) { result?.recycle(); throw e }
                    catch (e: Exception) {
                        result?.recycle()
                        _state.update { it.copy(bitmap = null, loading = false, error = e.readerMessage()) }
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
