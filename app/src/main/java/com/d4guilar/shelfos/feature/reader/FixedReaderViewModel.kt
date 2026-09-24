// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.core.files.PublicationException
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.LibraryItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FixedReaderState(val item: LibraryItem? = null, val page: Int = 0, val count: Int = 0,
    val bitmap: Bitmap? = null, val loading: Boolean = true, val error: String? = null,
    val preferences: ReaderPreferences = ReaderPreferences.DEFAULT)

class FixedReaderViewModel(private val id: String, private val repository: LibraryRepository,
    private val factory: FixedReaderFactory, private val cleanupScope: CoroutineScope) : ViewModel() {
    private val _state = MutableStateFlow(FixedReaderState())
    val state = _state.asStateFlow()
    private val mutex = Mutex()
    private var session: FixedReader? = null
    private var rendering: Job? = null
    private var opened = false
    @Volatile private var closed = false
    init { viewModelScope.launch {
        combine(repository.publications, repository.globalPreferences) { items, global -> items.find { it.id == id } to global }
            .collect { (item, global) ->
                if (item == null) { _state.update { it.copy(loading = false, error = "Publication unavailable.") }; return@collect }
                val preferences = ReaderPreferences.parse(item.preferences).over(ReaderPreferences.parse(global)).over(ReaderPreferences.DEFAULT)
                _state.update { it.copy(item = item, preferences = preferences) }
                if (!opened) { opened = true; open(item) }
            }
    } }
    private fun open(item: LibraryItem) {
        rendering = viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { mutex.withLock {
                    val engine = factory.open(item)
                    if (closed) { engine.close(); throw CancellationException() }
                    session = engine
                    engine.pageCount.also { if (it <= 0) throw PublicationException("This publication has no readable pages.") }
                } }
                repository.available(id, true)
                _state.update { it.copy(count = count, page = restorePage(item.locator, count)) }
                showPage(_state.value.page)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is SecurityException || e is java.io.FileNotFoundException) repository.available(id, false)
                _state.update { it.copy(loading = false, error = (e as? PublicationException)?.message ?: "The publication could not be opened. It may be unavailable, protected or damaged.") }
            }
        }
    }
    fun turn(delta: Int) { val current = _state.value; if (current.count > 0) showPage((current.page + delta).coerceIn(0, current.count - 1)) }
    fun showPage(index: Int) {
        rendering?.cancel()
        _state.update { it.copy(page = index, loading = true, error = null) }
        rendering = viewModelScope.launch {
            var result: Bitmap? = null
            try {
                withContext(Dispatchers.IO) { mutex.withLock { result = requireNotNull(session).render(index) } }
                ensureActive()
                _state.update { it.copy(bitmap = result, loading = false) }
                result = null // Published bitmaps are owned by Compose/GC, not manually recycled while displayed.
                repository.reading(id, pageLocator(index), ((index + 1L) * 100 / _state.value.count).toInt())
            } catch (e: CancellationException) { result?.recycle(); throw e }
            catch (e: Exception) { result?.recycle(); _state.update { it.copy(loading = false,
                error = (e as? PublicationException)?.message ?: "This page could not be displayed. Try another page.") } }
        }
    }
    fun preferences(preferences: ReaderPreferences, globally: Boolean) { viewModelScope.launch {
        try { repository.preferences(if (globally) "" else id, preferences.json()) }
        catch (_: Exception) { _state.update { it.copy(error = "Reading preferences could not be saved.") } }
    } }
    override fun onCleared() {
        closed = true
        cleanupScope.launch { mutex.withLock { session?.close(); session = null } }
    }
}
