// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.database.*
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.d4guilar.shelfos.core.files.PublicationFiles

interface LibraryRepository {
    val publications: Flow<List<LibraryItem>>
    val globalPreferences: Flow<String?>
    suspend fun add(item: LibraryItem): String
    suspend fun favorite(id: String)
    suspend fun edit(id: String, title: String, creator: String, category: MediaCategory)
    suspend fun remove(id: String)
    suspend fun available(id: String, available: Boolean)
    suspend fun reading(id: String, locator: String, progress: Int)
    suspend fun preferences(id: String, json: String)
}

class RoomLibraryRepository(private val dao: LibraryDao, private val files: PublicationFiles? = null) : LibraryRepository {
    override val publications = dao.observe().map { rows -> rows.map { row ->
        val e = row.item
        LibraryItem(e.id, e.title, e.creator, MediaCategory.valueOf(e.category), e.sourceUri,
            PublicationFormat.valueOf(e.format), e.fileName, e.byteSize, e.favorite, e.addedAt,
            e.available, e.managedPath, e.titleOrigin, e.creatorOrigin,
            row.reading?.progress ?: 0, row.reading?.lastRead ?: 0, row.reading?.locator,
            row.preferences?.json ?: "{}")
    } }
    override val globalPreferences = dao.globalPreferences()
    override suspend fun add(item: LibraryItem): String {
        dao.insert(LibraryEntity(item.id, item.sourceUri, item.title, item.creator, item.category.name,
            item.format.name, item.fileName, item.byteSize, item.favorite, item.addedAt, item.available,
            item.managedPath, item.titleOrigin, item.creatorOrigin))
        return requireNotNull(dao.bySource(item.sourceUri)).id
    }
    override suspend fun favorite(id: String) = dao.favorite(id)
    override suspend fun edit(id: String, title: String, creator: String, category: MediaCategory) = dao.edit(id, title.trim(), creator.trim(), category.name)
    override suspend fun remove(id: String) {
        val item = publications.first().find { it.id == id } ?: return
        dao.remove(id)
        withContext(Dispatchers.IO) { files?.removeOwnedSource(item, publications.first().any { it.sourceUri == item.sourceUri }) }
    }
    override suspend fun available(id: String, available: Boolean) = dao.available(id, available)
    override suspend fun reading(id: String, locator: String, progress: Int) = dao.saveReading(ReadingEntity(id, locator, progress.coerceIn(0, 100), System.currentTimeMillis()))
    override suspend fun preferences(id: String, json: String) = dao.savePreferences(ReaderPreferenceEntity(id, json))
}
