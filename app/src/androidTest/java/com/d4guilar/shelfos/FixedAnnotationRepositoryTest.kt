// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.reader.pageLocator
import com.d4guilar.shelfos.core.reader.pageProgress
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Phase 4C.1: fixed-page bookmarks and notes through the canonical [RoomAnnotationRepository] over a real in-memory
 * Room database, for PDF, CBZ and CBR items (the annotation layer is format-agnostic: only the item's format differs).
 */
class FixedAnnotationRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase
    private lateinit var repo: RoomAnnotationRepository
    private val items = mapOf("pdf-item" to PublicationFormat.PDF, "cbz-item" to PublicationFormat.CBZ, "cbr-item" to PublicationFormat.CBR)

    @Before fun open() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        repo = RoomAnnotationRepository(db.annotations(), DefaultAnnotationLocatorValidator { false })
        val library = RoomLibraryRepository(db.library())
        items.forEach { (id, format) -> library.add((if (format == PublicationFormat.PDF) OriginalFixtures.pdf(context) else OriginalFixtures.cbz(context))
            .copy(id = id, sourceUri = "test:$id", format = format)) }
    }
    @After fun close() { db.close() }

    private suspend fun list(item: String) = repo.observeForPublication(item).first().annotations

    /** 4C added no schema change after v4; 4D's snapshot table made it v5 and 4.5A.1's additive `publication_cover` v6; 4.5A.2's `cover_presentation_preference` v7. */
    @Test fun roomIsAtTheCurrentSchemaVersionEight() {
        assertEquals(8, db.openHelper.readableDatabase.version)
    }

    @Test fun canonicalLocatorMatchesTheReadersOwnPageLocatorOnDevice() {
        for (page in listOf(0, 1, 9, 123, Int.MAX_VALUE)) assertEquals(pageLocator(page), FixedPageLocator.format(page))
    }

    @Test fun bookmarkCreateIsExactAndDuplicateFreeForEveryFixedFormat() = runBlocking<Unit> {
        for (item in items.keys) {
            val first = repo.create(fixedPageDraft(item, AnnotationKind.BOOKMARK, 4, 10, pageProgress(4, 10)))
            val again = repo.create(fixedPageDraft(item, AnnotationKind.BOOKMARK, 4, 10, pageProgress(4, 10)))
            assertEquals("$item: exact duplicate returns the existing row", first.id, again.id)
            assertEquals(1, list(item).size)
            assertEquals(LocatorFormat.FIXED_PAGE_1, first.locatorFormat); assertEquals(pageLocator(4), first.locatorJson)
            assertEquals(AnnotationKind.BOOKMARK, first.kind)
            assertNull(first.body); assertNull(first.selectedText); assertNull(first.styleKey); assertNull(first.title)
            assertEquals(50, first.progressSnapshot); assertEquals(0.4, first.orderKey!!, 1e-12)
            val other = repo.create(fixedPageDraft(item, AnnotationKind.BOOKMARK, 5, 10, pageProgress(5, 10)))
            assertNotEquals(first.id, other.id)
            assertTrue(repo.delete(first.id, AnnotationKind.BOOKMARK))
            assertEquals(listOf(other.id), list(item).map { it.id })
        }
    }

    @Test fun toggleRemovesEveryBookmarkAtThePageAndLeavesOthers() = runBlocking<Unit> {
        val item = "pdf-item"
        repo.create(fixedPageDraft(item, AnnotationKind.BOOKMARK, 2, 3, 100))
        repo.create(fixedPageDraft(item, AnnotationKind.NOTE, 2, 3, 100, "note on the same page"))
        val ids = fixedBookmarkIdsAt(list(item), 2)
        assertEquals(1, ids.size)
        ids.forEach { repo.delete(it, AnnotationKind.BOOKMARK) }
        assertEquals(listOf(AnnotationKind.NOTE), list(item).map { it.kind })
        assertTrue(fixedBookmarkIdsAt(list(item), 2).isEmpty())
    }

    @Test fun noteCreateEditKeepsAnchorAndDeleteWorksForEveryFixedFormat() = runBlocking<Unit> {
        for (item in items.keys) {
            val note = repo.create(fixedPageDraft(item, AnnotationKind.NOTE, 3, 10, pageProgress(3, 10), "First"))
            assertEquals(AnnotationKind.NOTE, note.kind); assertEquals(pageLocator(3), note.locatorJson)
            assertNull(note.selectedText); assertNull(note.styleKey); assertNull(note.title)
            val edited = requireNotNull(repo.update(note.id, AnnotationEdit(note.title, null, "Changed", null)))
            assertEquals("Changed", edited.body); assertEquals(note.locatorJson, edited.locatorJson)
            assertEquals(note.createdAt, edited.createdAt); assertEquals(note.libraryItemId, edited.libraryItemId)
            assertEquals(AnnotationKind.NOTE, edited.kind)
            assertTrue(repo.delete(note.id)); assertNull(repo.get(note.id))
        }
    }

    @Test fun fixedNotesRejectSelectedTextBlankAndOversizedBodiesAndHighlights() = runBlocking<Unit> {
        fun rejected(d: AnnotationDraft) = runBlocking { runCatching { repo.create(d) }.exceptionOrNull() is InvalidAnnotationException }
        val base = fixedPageDraft("pdf-item", AnnotationKind.NOTE, 1, 3, 70, "ok")
        assertTrue(rejected(base.copy(selectedText = "quoted")))
        assertTrue(rejected(base.copy(body = "   ")))
        assertTrue(rejected(base.copy(body = "x".repeat(AnnotationLimits.MAX_BODY + 1))))
        assertTrue(rejected(base.copy(kind = AnnotationKind.HIGHLIGHT, styleKey = "amber", selectedText = "t")))
        assertTrue(rejected(base.copy(locatorJson = "{\"version\":1,\"page\":-1}")))
        assertEquals(0, list("pdf-item").size)
        assertNotNull(repo.create(base.copy(body = "x".repeat(AnnotationLimits.MAX_BODY))))
    }

    @Test fun readingOrderInterleavesBookmarksAndNotesByPageAndIsPerPublication() = runBlocking<Unit> {
        val item = "cbz-item"
        val note9 = repo.create(fixedPageDraft(item, AnnotationKind.NOTE, 9, 10, 100, "late"))
        val bm0 = repo.create(fixedPageDraft(item, AnnotationKind.BOOKMARK, 0, 10, 10))
        val note3 = repo.create(fixedPageDraft(item, AnnotationKind.NOTE, 3, 10, 40, "middle"))
        val bm3 = repo.create(fixedPageDraft(item, AnnotationKind.BOOKMARK, 3, 10, 40))
        repo.create(fixedPageDraft("cbr-item", AnnotationKind.BOOKMARK, 1, 10, 20))
        assertEquals(listOf(bm0.id, note3.id, bm3.id, note9.id), list(item).map { it.id })
        assertEquals(1, list("cbr-item").size)
        // A one-page publication has a valid deterministic key.
        val single = repo.create(fixedPageDraft("pdf-item", AnnotationKind.BOOKMARK, 0, 1, pageProgress(0, 1)))
        assertEquals(0.0, single.orderKey!!, 0.0); assertEquals(100, single.progressSnapshot)
    }

    @Test fun persistedMalformedAndOutOfRangeAnchorsStayStoredListedAndDeletable() = runBlocking<Unit> {
        val malformed = repo.create(fixedPageDraft("pdf-item", AnnotationKind.NOTE, 0, 3, 30, "orphan").copy(locatorJson = "{\"version\":1}"), validateLocator = false)
        val far = repo.create(fixedPageDraft("pdf-item", AnnotationKind.BOOKMARK, 9999, 3, 100), validateLocator = false)
        assertEquals(setOf(malformed.id, far.id), list("pdf-item").map { it.id }.toSet())
        assertEquals(FixedPageNavigation.Malformed, fixedPageNavigation(malformed.locatorJson, 3))
        assertEquals(FixedPageNavigation.OutOfRange(9999, 3), fixedPageNavigation(far.locatorJson, 3))
        assertEquals("{\"version\":1}", repo.get(malformed.id)!!.locatorJson)
        assertTrue(repo.delete(malformed.id)); assertTrue(repo.delete(far.id))
    }
}
