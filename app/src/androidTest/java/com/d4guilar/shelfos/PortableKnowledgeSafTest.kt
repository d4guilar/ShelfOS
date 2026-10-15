// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.*
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.portable.*
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class PortableKnowledgeSafTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase
    private lateinit var files: PortableKnowledgeFiles
    private lateinit var annotations: RoomAnnotationRepository
    private val labels = PortableMarkdownLabels("Export", "Removed", "Bookmark", "Highlight", "Note",
        { "Page $it" }, { "$it%" }, { "Created $it" }, { "Updated $it" })

    @Before fun open() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        annotations = RoomAnnotationRepository(db.annotations(), DefaultAnnotationLocatorValidator { true })
        files = PortableKnowledgeFiles(context.contentResolver, annotations, RoomPortableKnowledgeRepository(db), "test")
        RoomLibraryRepository(db.library()).add(LibraryItem("live", "Live", "Creator", MediaCategory.BOOK,
            "test:live", PublicationFormat.EPUB, "source.epub", 10))
        annotations.create(AnnotationDraft("live", AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1,
            "opaque", 10, body = "saved"), validateLocator = false)
        NonSeekableTestProvider.knowledgeInput = byteArrayOf()
        NonSeekableTestProvider.knowledgeOutput = null
        NonSeekableTestProvider.deleteAttempts = 0
    }
    @After fun close() = db.close()

    @Test fun providerExportStreamsAndFailureAttemptsCleanupWithoutSuccess() = runBlocking<Unit> {
        val source = File(context.cacheDir, "portable-source-immutable.pdf").apply { writeBytes(ByteArray(2048) { (it * 17).toByte() }) }
        val hash = { MessageDigest.getInstance("SHA-256").digest(source.readBytes()).toList() }
        val before = hash()
        RoomLibraryRepository(db.library()).add(LibraryItem("file-backed", "File backed", "Creator", MediaCategory.DOCUMENT,
            source.toURI().toString(), PublicationFormat.PDF, source.name, source.length()))
        annotations.create(AnnotationDraft("file-backed", AnnotationKind.NOTE, LocatorFormat.FIXED_PAGE_1,
            "malformed", 0, body = "file note"), validateLocator = false)
        files.export(uri("knowledge-output"), PortableExportFormat.JSON, NotesQuery(), labels)
        val output = waitForOutput()
        val parsed = PortableKnowledgeJson.read(output.inputStream())
        assertEquals(2, parsed.publications.sumOf { it.annotations.size })
        assertFalse(output.toString(Charsets.UTF_8).contains("source.epub"))
        val failure = runCatching { files.export(uri("knowledge-write-failure"), PortableExportFormat.JSON, NotesQuery(), labels) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, NonSeekableTestProvider.deleteAttempts)
        assertNull(NonSeekableTestProvider.knowledgeOutput)
        assertEquals(before, hash())
        source.delete()
    }

    @Test fun selectedProviderInputPreviewsReopensImportsAndInvalidInputMutatesNothing() = runBlocking<Unit> {
        val annotation = PortableAnnotation("restored", "missing", AnnotationKind.NOTE, LocatorFormat.FIXED_PAGE_1,
            "malformed preserved", 20, null, null, null, "restored body", null, 100, 200)
        val document = PortableKnowledgeDocument(300, "test", listOf(PortablePublication("missing", "Removed", "Author",
            MediaCategory.DOCUMENT, PublicationFormat.PDF, true, listOf(annotation))))
        NonSeekableTestProvider.knowledgeInput = ByteArrayOutputStream().also { PortableKnowledgeJson.write(document, it) }.toByteArray()
        val (previewDocument, preview) = files.preview(uri("knowledge-input"))
        assertEquals(1, preview.newCount); assertEquals(1, preview.orphanGroupsToCreate)
        val result = files.import(uri("knowledge-input"), previewDocument, preview)
        assertEquals(1, result.imported)
        assertEquals("malformed preserved", db.annotations().byId("restored")!!.locatorJson)
        val changed = document.copy(publications = listOf(document.publications.single().copy(annotations =
            listOf(annotation.copy(id = "changed-after-preview", body = "first")))))
        NonSeekableTestProvider.knowledgeInput = ByteArrayOutputStream().also { PortableKnowledgeJson.write(changed, it) }.toByteArray()
        val (changedDocument, changedPreview) = files.preview(uri("knowledge-input"))
        val replaced = changed.copy(publications = listOf(changed.publications.single().copy(annotations =
            listOf(changed.publications.single().annotations.single().copy(body = "different after preview")))))
        NonSeekableTestProvider.knowledgeInput = ByteArrayOutputStream().also { PortableKnowledgeJson.write(replaced, it) }.toByteArray()
        assertTrue(runCatching { files.import(uri("knowledge-input"), changedDocument, changedPreview) }.exceptionOrNull()
            is StalePortableKnowledgePreviewException)
        assertNull(db.annotations().byId("changed-after-preview"))
        NonSeekableTestProvider.knowledgeInput = "{}".toByteArray()
        assertTrue(runCatching { files.preview(uri("knowledge-invalid")) }.exceptionOrNull() is InvalidPortableKnowledgeException)
        assertEquals(2, db.annotations().portableAnnotations().size) // Existing live + restored rows remain; no partial mutation.
    }

    private fun uri(path: String) = Uri.parse("content://com.d4guilar.shelfos.test.documents/$path")
    private fun waitForOutput(): ByteArray {
        repeat(100) { NonSeekableTestProvider.knowledgeOutput?.let { return it }; Thread.sleep(10) }
        fail("provider output did not complete")
        error("unreachable")
    }
}
