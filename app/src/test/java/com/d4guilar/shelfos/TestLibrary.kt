// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

fun testItems() = listOf(
    LibraryItem("book", "An Original Book", "A. Writer", MediaCategory.BOOK, "test:book", PublicationFormat.EPUB, "book.epub", 100L, favorite = true),
    LibraryItem("forest", "Forest", "Sora", MediaCategory.MANGA, "test:forest", PublicationFormat.PDF, "forest.pdf", 200L, favorite = true),
    LibraryItem("field", "Field Notes", "ShelfOS", MediaCategory.DOCUMENT, "test:field", PublicationFormat.PDF, "notes.pdf", 300L, favorite = true),
)

class TestLibrary : LibraryRepository {
    override val publications = MutableStateFlow(testItems())
    override val globalPreferences = MutableStateFlow<String?>(null)
    override suspend fun add(item: LibraryItem): String { publications.update { it + item }; return item.id }
    override suspend fun favorite(id: String) { publications.update { list -> list.map { if (it.id == id) it.copy(favorite = !it.favorite) else it } } }
    override suspend fun edit(id: String, title: String, creator: String, category: MediaCategory) { publications.update { list -> list.map { if (it.id == id) it.copy(title = title, creator = creator, category = category) else it } } }
    override suspend fun remove(id: String) { publications.update { list -> list.filterNot { it.id == id } } }
    override suspend fun available(id: String, available: Boolean) {}
    override suspend fun reading(id: String, locator: String, progress: Int) {}
    override suspend fun preferences(id: String, json: String) {}
}
