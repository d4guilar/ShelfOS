// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.AnnotationEntity
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.PortableKnowledgeFiles
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.annotations.RoomPortableKnowledgeRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.DefaultAnnotationLocatorValidator
import com.d4guilar.shelfos.domain.annotations.NotesQuery
import com.d4guilar.shelfos.domain.annotations.portable.PortableKnowledgeJson
import com.d4guilar.shelfos.domain.annotations.portable.PortableKnowledgeMarkdown
import com.d4guilar.shelfos.domain.annotations.portable.PortableMarkdownLabels
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

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
        val annotations = RoomAnnotationRepository(db.annotations(), DefaultAnnotationLocatorValidator { true })
        val files = PortableKnowledgeFiles(context.contentResolver, annotations, RoomPortableKnowledgeRepository(db), "test")
        val document = files.buildDocument(NotesQuery(text = "match"))
        assertEquals(205, document.publications.single().annotations.size)
        assertEquals((0 until 205).map { "id-%03d".format(it) }, document.publications.single().annotations.sortedBy { it.id }.map { it.id })
        val json = ByteArrayOutputStream().also { PortableKnowledgeJson.write(document, it) }.toString("UTF-8")
        assertEquals(205, Regex("\\\"id\\\":").findAll(json).count())
        val labels = PortableMarkdownLabels("Export", "Removed", "Bookmark", "Highlight", "Note",
            { "Page $it" }, { "$it%" }, { "Created $it" }, { "Updated $it" })
        val markdown = ByteArrayOutputStream().also { PortableKnowledgeMarkdown.write(document, it, labels) }.toString("UTF-8")
        assertEquals(205, Regex("(?m)^### Note").findAll(markdown).count())
        assertFalse(json.contains("private.pdf")); assertFalse(markdown.contains("private.pdf"))
    }
}
