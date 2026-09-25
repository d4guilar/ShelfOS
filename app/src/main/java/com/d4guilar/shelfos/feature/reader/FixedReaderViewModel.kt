// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FixedReaderState(val item: LibraryItem? = null, val page: Int = 0, val count: Int = 0,
    val bitmap: Bitmap? = null, val loading: Boolean = true, val error: String? = null,
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
    private val positions = PositionWriter<Int>(appScope, write = { page ->
        repository.reading(id, pageLocator(page), pageProgress(page, _state.value.count))
    }, onFailure = { _state.update { it.copy(error = "Your reading position could not be saved.") } })

    init {
        viewModelScope.launch {
            var opening = false
            combine(repository.publication(id), repository.globalPreferences) { item, global -> item to global }.collect { (item, global) ->
                if (item == null) {
                    _state.update { it.copy(loading = false, error = "This publication is no longer in your library.") }
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
                if (engine.pageCount <= 0) { engine.close(); throw PublicationException(PublicationProblem.CORRUPT, "This publication has no readable pages.") }
                session = engine
                engine.pageCount
            } }
            markAvailable(true)
            val page = restorePage(item.locator, count)
            _state.update { it.copy(count = count, page = page) }
            positions.save(page)
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

    private fun render(page: Int) {
        rendering?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        rendering = viewModelScope.launch {
            var result: Bitmap? = null
            try {
                withContext(Dispatchers.IO) { mutex.withLock { result = requireNotNull(session).render(page) } }
                ensureActive()
                _state.update { it.copy(bitmap = result, loading = false) }
                result = null // Published bitmaps are owned by Compose/GC, not manually recycled while displayed.
            } catch (e: CancellationException) { result?.recycle(); throw e }
            catch (e: Exception) {
                result?.recycle()
                _state.update { it.copy(bitmap = null, loading = false, error = e.readerMessage()) }
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
        catch (_: Exception) { _state.update { it.copy(error = "Reading preferences could not be saved.") } }
    } }

    private suspend fun markAvailable(available: Boolean) {
        try { repository.available(id, available) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Display state only. */ }
    }

    override fun onCleared() {
        closed = true
        positions.close()
        appScope.launch { mutex.withLock { session?.close(); session = null } }
    }
}
