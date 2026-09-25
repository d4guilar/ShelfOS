// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.data.library.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Persists the newest reading position off the UI path. Rapid page turns collapse to the latest value,
 * and a pending write still completes after the reader closes because it runs in the application scope.
 */
internal class PositionWriter<T>(scope: CoroutineScope, private val write: suspend (T) -> Unit, private val onFailure: () -> Unit) {
    private val pending = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            for (value in pending) try { write(value) } catch (e: CancellationException) { throw e } catch (_: Exception) { onFailure() }
        }
    }

    fun save(value: T) { pending.trySend(value) }
    fun close() { pending.close() }
}

/** Writes an Appearance change into the explicit per-title and global layers. */
internal suspend fun LibraryRepository.saveAppearance(id: String, titleJson: String, globalJson: String?,
    before: ReaderPreferences, after: ReaderPreferences, globally: Boolean) =
    save(id, appearanceUpdate(ReaderPreferences.parse(titleJson), ReaderPreferences.parse(globalJson), before, after, globally))

internal suspend fun LibraryRepository.resetAppearance(id: String, titleJson: String, globally: Boolean) =
    save(id, appearanceReset(ReaderPreferences.parse(titleJson), globally))

private suspend fun LibraryRepository.save(id: String, update: AppearanceUpdate) {
    update.global?.let { preferences("", it.json()) }
    preferences(id, update.title.json())
}
