// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.AnnotationEntity
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.PortableKnowledgeFiles
import com.d4guilar.shelfos.data.annotations.PortableExportFormat
import com.d4guilar.shelfos.data.annotations.AnnotationRepository
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.annotations.RoomPortableKnowledgeRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.DefaultAnnotationLocatorValidator
import com.d4guilar.shelfos.domain.annotations.NotesQuery
import com.d4guilar.shelfos.domain.annotations.portable.PortableKnowledgeJson
import com.d4guilar.shelfos.domain.annotations.AnnotationWithSource
import com.d4guilar.shelfos.domain.annotations.portable.PortableMarkdownLabels
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import android.net.Uri

class PortableKnowledgeExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build() }
    @After fun close() = db.close()

    @Test fun filteredExportWalksEveryKeysetPageForJsonAndMarkdown() = runBlocking<Unit> {
        RoomLibraryRepository(db.library()).add(LibraryItem("p", "Publication", "Creator", MediaCategory.DOCUMENT,
            "test:p", PublicationFormat.PDF, "private.pdf", 10))
        val rows = (0 until 225).reversed().map { n -> AnnotationEntity("id-%03d".format(n), "p", "NOTE", "FIXED_PAGE_1",
            "{\"version\":1,\"page\":$n}", n % 101, n.toDouble(), null, null,
            if (n < 205) "match $n" else "other $n", null, 1_000L + n, 1_000L + n) }
        db.annotations().insertPortableAnnotations(rows)
        db.annotations().insertPortableAnnotations(listOf(
            AnnotationEntity("z", "a-orphan", "NOTE", "READIUM_LOCATOR_1", "opaque", 0, null,
                null, null, "other orphan", null, 1, 1),
            AnnotationEntity("a", "a-orphan", "NOTE", "FIXED_PAGE_1", "opaque", 0, null,
                null, null, "other orphan", null, 1, 1),
        ))
        val base = RoomAnnotationRepository(db.annotations(), DefaultAnnotationLocatorValidator { true })
        var pages = 0
        var largestPage = 0
        var contextLookups = 0
        var largestLookup = 0
        val annotations = object : AnnotationRepository by base {
            override suspend fun portableExportPage(query: NotesQuery, after: Pair<String, String>?, limit: Int): List<AnnotationWithSource> {
                val page = base.portableExportPage(query, after, limit)
                pages++
                largestPage = maxOf(largestPage, page.size)
                return page
            }
            override suspend fun portableGroupsWithNonReadium(query: NotesQuery, ids: List<String>): Set<String> {
                contextLookups++
                largestLookup = maxOf(largestLookup, ids.size)
                return base.portableGroupsWithNonReadium(query, ids)
            }
        }
        val files = PortableKnowledgeFiles(context.contentResolver, annotations, RoomPortableKnowledgeRepository(db), "test")
        val destination = Uri.parse("content://com.d4guilar.shelfos.test.documents/knowledge-output")
        val labels = PortableMarkdownLabels("Export", "Removed", "Bookmark", "Highlight", "Note",
            { "Page $it" }, { "$it%" }, { "Created $it" }, { "Updated $it" })
        files.export(destination, PortableExportFormat.JSON, NotesQuery(text = "match"), labels)
        val json = awaitOutput().toString(Charsets.UTF_8)
        assertEquals(205, Regex("\\\"id\\\":").findAll(json).count())
        assertEquals((0 until 205).map { "id-%03d".format(it) }, PortableKnowledgeJson.read(json.byteInputStream())
            .publications.single().annotations.map { it.id })
        assertTrue(pages >= 3); assertTrue(largestPage <= 100)
        NonSeekableTestProvider.knowledgeOutput = null
        files.export(destination, PortableExportFormat.JSON, NotesQuery(), labels)
        val all = PortableKnowledgeJson.read(awaitOutput().inputStream())
        assertEquals(listOf("a-orphan", "p"), all.publications.map { it.libraryItemId })
        assertEquals(PublicationFormat.PDF, all.publications.first().format)
        assertEquals(listOf("a", "z"), all.publications.first().annotations.map { it.id })
        assertEquals(1, contextLookups); assertTrue(largestLookup <= 100)
        NonSeekableTestProvider.knowledgeOutput = null
        files.export(destination, PortableExportFormat.MARKDOWN, NotesQuery(text = "match"), labels)
        val markdown = awaitOutput().toString(Charsets.UTF_8)
        assertEquals(205, Regex("(?m)^### Note").findAll(markdown).count())
        assertFalse(json.contains("private.pdf")); assertFalse(markdown.contains("private.pdf"))
    }

    private fun awaitOutput(): ByteArray {
        repeat(100) { NonSeekableTestProvider.knowledgeOutput?.let { return it }; Thread.sleep(10) }
        fail("provider output did not complete")
        error("unreachable")
    }
}
