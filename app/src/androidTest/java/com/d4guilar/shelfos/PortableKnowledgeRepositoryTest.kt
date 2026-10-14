// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.AnnotationEntity
import com.d4guilar.shelfos.core.database.AnnotationSourceSnapshotEntity
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.RoomPortableKnowledgeRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.domain.annotations.portable.*
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PortableKnowledgeRepositoryTest {
    private lateinit var db: ShelfDatabase
    private lateinit var repository: RoomPortableKnowledgeRepository

    @Before fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        repository = RoomPortableKnowledgeRepository(db)
    }
    @After fun close() = db.close()

    private fun annotation(id: String, item: String, body: String = "body") = PortableAnnotation(id, item,
        AnnotationKind.NOTE, LocatorFormat.FIXED_PAGE_1, "malformed persisted locator", 12, 0.12,
        null, null, body, null, 1_000, 2_000)
    private fun publication(id: String, annotations: List<PortableAnnotation>, title: String = "Imported") =
        PortablePublication(id, title, "Creator", MediaCategory.DOCUMENT, PublicationFormat.PDF, true, annotations)
    private fun document(vararg publications: PortablePublication) = PortableKnowledgeDocument(3_000, "test", publications.toList())

    @Test fun exactIdsImportAdditivelyAndSnapshotRulesArePreserved() = runBlocking<Unit> {
        val library = RoomLibraryRepository(db.library())
        library.add(LibraryItem("live", "Local title", "Local creator", MediaCategory.BOOK, "test:live",
            PublicationFormat.EPUB, "secret.epub", 99))
        db.annotations().insertPortableSnapshots(listOf(
            AnnotationSourceSnapshotEntity("live", "Stale", "Stale", "DOCUMENT", "PDF"),
            AnnotationSourceSnapshotEntity("orphan-local", "Local context", "Local", "DOCUMENT", "PDF"),
        ))
        db.annotations().insertPortableAnnotations(listOf(annotation("duplicate", "live").toEntity(),
            annotation("conflict", "other", "local wins").toEntity(), annotation("untouched", "local-only").toEntity()))
        val input = document(
            publication("live", listOf(annotation("duplicate", "live"), annotation("conflict", "live"), annotation("new-live", "live")), "Imported must not win"),
            publication("orphan-new", listOf(annotation("new-orphan", "orphan-new"))),
            publication("orphan-local", listOf(annotation("new-local", "orphan-local")), "Different imported context"),
        )
        val preview = repository.classify(input)
        assertEquals(3, preview.newCount); assertEquals(1, preview.duplicateCount); assertEquals(1, preview.conflictCount)
        assertEquals(1, preview.orphanGroupsToCreate); assertEquals(1, preview.sourceContextWarnings)
        val result = repository.import(input, preview)
        assertEquals(3, result.imported); assertEquals(1, result.duplicates); assertEquals(1, result.conflicts)
        assertNotNull(db.annotations().byId("untouched"))
        assertEquals("local wins", db.annotations().byId("conflict")!!.body)
        assertEquals("malformed persisted locator", db.annotations().byId("new-live")!!.locatorJson)
        val snapshots = db.annotations().portableSnapshots().associateBy { it.libraryItemId }
        assertEquals("Stale", snapshots.getValue("live").title) // live + stale is neither used nor cleaned.
        assertEquals("Local context", snapshots.getValue("orphan-local").title)
        assertEquals("Imported", snapshots.getValue("orphan-new").title)
        val again = repository.classify(input)
        assertEquals(0, again.newCount); assertEquals(4, again.duplicateCount); assertEquals(1, again.conflictCount)
    }

    @Test fun changedClassificationAbortsTheWholeTransaction() = runBlocking<Unit> {
        val input = document(publication("orphan", listOf(annotation("first", "orphan"), annotation("second", "orphan"))))
        val preview = repository.classify(input)
        db.annotations().insertPortableAnnotations(listOf(annotation("first", "orphan", "changed locally").toEntity()))
        assertTrue(runCatching { repository.import(input, preview) }.exceptionOrNull() is StalePortableKnowledgePreviewException)
        assertNull(db.annotations().byId("second"))
        assertTrue(db.annotations().portableSnapshots().isEmpty())
    }

    @Test fun conflictOnlyUnknownLocalRowDoesNotCreateEmptyOrphanSnapshot() = runBlocking<Unit> {
        val malformed = annotation("unknown", "orphan", "local body").toEntity().copy(kind = "FUTURE_KIND")
        db.annotations().insertPortableAnnotations(listOf(malformed))
        val input = document(publication("orphan", listOf(annotation("unknown", "orphan", "incoming body"))))
        val preview = repository.classify(input)
        assertEquals(0, preview.newCount)
        assertEquals(1, preview.conflictCount)
        assertEquals(0, preview.orphanGroupsToCreate)
        val result = repository.import(input, preview)
        assertEquals(0, result.imported)
        assertEquals(1, result.conflicts)
        assertEquals(0, result.orphanGroups)
        assertTrue(db.annotations().portableSnapshots().isEmpty())
        assertEquals(malformed, db.annotations().byId("unknown"))
    }
}

private fun PortableAnnotation.toEntity() = AnnotationEntity(id, libraryItemId, kind.name, locatorFormat.name,
    locatorJson, progressSnapshot, orderKey, title, selectedText, body, styleKey, createdAt, updatedAt)
