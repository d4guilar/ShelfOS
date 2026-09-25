// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.database.*
import com.d4guilar.shelfos.core.files.PrivateCopyUsage
import com.d4guilar.shelfos.core.files.PublicationFiles
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface LibraryRepository {
    val publications: Flow<List<LibraryItem>>
    val globalPreferences: Flow<String?>
    fun publication(id: String): Flow<LibraryItem?>
    suspend fun add(item: LibraryItem): String
    suspend fun favorite(id: String)
    suspend fun edit(id: String, title: String, creator: String, category: MediaCategory)
    /** Removes ShelfOS's entry and state. Never deletes the source file or a private copy. */
    suspend fun remove(id: String)
    suspend fun available(id: String, available: Boolean)
    suspend fun reading(id: String, locator: String, progress: Int)
    suspend fun preferences(id: String, json: String)
}

/** Explicit cleanup of private copies no library item references any more. */
interface PrivateCopyStore {
    suspend fun unusedPrivateCopies(): PrivateCopyUsage
    suspend fun deleteUnusedPrivateCopies(): PrivateCopyUsage
}

class RoomLibraryRepository(private val dao: LibraryDao, private val files: PublicationFiles? = null) : LibraryRepository, PrivateCopyStore {
    override val publications = dao.observe().map { rows -> rows.map(::item) }
    override val globalPreferences = dao.globalPreferences()
    override fun publication(id: String) = dao.observeRecord(id).map { row -> row?.let(::item) }

    override suspend fun add(item: LibraryItem): String {
        dao.insert(LibraryEntity(item.id, item.sourceUri, item.title, item.creator, item.category.name,
            item.format.name, item.fileName, item.byteSize, item.favorite, item.addedAt, item.available,
            item.managedPath, item.titleOrigin, item.creatorOrigin))
        return requireNotNull(dao.bySource(item.sourceUri)).id
    }
    override suspend fun favorite(id: String) = dao.favorite(id)
    override suspend fun edit(id: String, title: String, creator: String, category: MediaCategory) = dao.edit(id, title.trim(), creator.trim(), category.name)
    override suspend fun remove(id: String) {
        val removed = dao.byId(id) ?: return
        dao.remove(id)
        // Release platform access only when no remaining item needs it; files are never deleted here.
        withContext(Dispatchers.IO) { files?.releaseAccess(removed.sourceUri, dao.bySource(removed.sourceUri) != null) }
    }
    override suspend fun available(id: String, available: Boolean) = dao.available(id, available)
    override suspend fun reading(id: String, locator: String, progress: Int) = dao.saveReading(ReadingEntity(id, locator, progress.coerceIn(0, 100), System.currentTimeMillis()))
    override suspend fun preferences(id: String, json: String) = dao.savePreferences(ReaderPreferenceEntity(id, json))

    /** Startup maintenance; see [PublicationFiles.reconcile]. Missing access marks items unavailable, never deletes them. */
    suspend fun reconcileSources() {
        val source = files ?: return
        val missing = source.reconcile(snapshot())
        if (missing.isNotEmpty()) dao.markUnavailable(missing.toList())
    }

    override suspend fun unusedPrivateCopies() = files?.unusedCopies(snapshot()) ?: PrivateCopyUsage(0, 0)
    override suspend fun deleteUnusedPrivateCopies() = files?.deleteUnusedCopies(snapshot()) ?: PrivateCopyUsage(0, 0)

    private suspend fun snapshot() = dao.records().map(::item)

    private fun item(row: LibraryRecord): LibraryItem {
        val e = row.item
        return LibraryItem(e.id, e.title, e.creator, MediaCategory.valueOf(e.category), e.sourceUri,
            PublicationFormat.valueOf(e.format), e.fileName, e.byteSize, e.favorite, e.addedAt,
            e.available, e.managedPath, e.titleOrigin, e.creatorOrigin,
            row.reading?.progress ?: 0, row.reading?.lastRead ?: 0, row.reading?.locator,
            row.preferences?.json ?: "{}")
    }
}
