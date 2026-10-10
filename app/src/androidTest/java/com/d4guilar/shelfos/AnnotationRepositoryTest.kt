// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.annotations.RoomBookmarkRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Phase 4A: [RoomAnnotationRepository] over a real (in-memory) Room v4 database, plus library removal under KnowledgePolicy.DELETE. */
class AnnotationRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase
    private lateinit var library: RoomLibraryRepository
    private var time = 1_000L
    private var ids = 0
    private lateinit var repo: RoomAnnotationRepository
    private val epub get() = "epub-item"
    private val pdf get() = "pdf-item"
    private val readium = """{"href":"c1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.2}}"""

    @Before fun open() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        library = RoomLibraryRepository(db.library())
        repo = RoomAnnotationRepository(db.annotations(),
            DefaultAnnotationLocatorValidator { it.contains("\"href\"") }, clock = { time++ }, newId = { "id-${ids++}" })
        library.add(OriginalFixtures.epub(context).copy(id = epub, sourceUri = "test:epub"))
        library.add(OriginalFixtures.pdf(context).copy(id = pdf, sourceUri = "test:pdf"))
    }
    @After fun close() { db.close() }

    private fun draft(item: String = epub, kind: AnnotationKind = AnnotationKind.BOOKMARK, locator: String = readium, progress: Int = 20,
        format: LocatorFormat = LocatorFormat.READIUM_LOCATOR_1, orderKey: Double? = null, title: String? = null,
        selected: String? = null, body: String? = null, style: String? = null) =
        AnnotationDraft(item, kind, format, locator, progress, orderKey, title, selected, body, style)

    private suspend fun list(item: String, kinds: Set<AnnotationKind>? = null, order: AnnotationOrder = AnnotationOrder.READING) =
        repo.observeForPublication(item, kinds, order).first()

    @Test fun createReadUpdateDeleteRoundTrip() = runBlocking<Unit> {
        val note = repo.create(draft(kind = AnnotationKind.NOTE, body = "My note", title = "T", progress = 40))
        assertEquals(note, repo.get(note.id))
        assertEquals(note.createdAt, note.updatedAt)
        val edited = requireNotNull(repo.update(note.id, AnnotationEdit("T2", null, "Changed", null)))
        assertEquals(AnnotationKind.NOTE, edited.kind); assertEquals(LocatorFormat.READIUM_LOCATOR_1, edited.locatorFormat)
        assertEquals(readium, edited.locatorJson); assertEquals(note.createdAt, edited.createdAt)
        assertTrue(edited.updatedAt > note.updatedAt)
        assertEquals("Changed", repo.get(note.id)!!.body)
        assertNull(repo.update("missing", AnnotationEdit(null, null, "x", null)))
        assertTrue(repo.delete(note.id)); assertNull(repo.get(note.id)); assertFalse(repo.delete(note.id))
    }

    @Test fun editRejectsInvariantViolationsAndNeverChangesKind() = runBlocking<Unit> {
        val note = repo.create(draft(kind = AnnotationKind.NOTE, body = "keep"))
        assertTrue(runCatching { repo.update(note.id, AnnotationEdit(null, null, "  ", null)) }.exceptionOrNull() is InvalidAnnotationException)
        assertEquals("keep", repo.get(note.id)!!.body)
        val bookmark = repo.create(draft(locator = """{"href":"other.xhtml"}"""))
        assertTrue(runCatching { repo.update(bookmark.id, AnnotationEdit(null, null, "body on a bookmark", null)) }.exceptionOrNull() is InvalidAnnotationException)
        assertEquals(AnnotationKind.BOOKMARK, repo.get(bookmark.id)!!.kind)
        val highlight = repo.create(draft(kind = AnnotationKind.HIGHLIGHT, style = "amber", selected = "text"))
        val annotated = requireNotNull(repo.update(highlight.id, AnnotationEdit(null, "text", "added body", "amber")))
        assertEquals("a body never turns a highlight into a note", AnnotationKind.HIGHLIGHT, annotated.kind)
    }

    @Test fun createEnforcesKindInvariantsAndLocatorValidation() = runBlocking<Unit> {
        fun rejected(d: AnnotationDraft) = runBlocking { runCatching { repo.create(d) }.exceptionOrNull() is InvalidAnnotationException }
        assertTrue(rejected(draft(body = "x")))                                   // bookmark with body
        assertTrue(rejected(draft(kind = AnnotationKind.NOTE, body = " ")))      // blank note
        assertTrue(rejected(draft(kind = AnnotationKind.HIGHLIGHT)))             // highlight without style
        assertTrue(rejected(draft(locator = "not json")))                        // malformed readium locator
        assertTrue(rejected(draft(item = pdf, format = LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":-1}""")))
        assertTrue(rejected(draft(progress = 101)))
        assertEquals(0, list(epub).annotations.size + list(pdf).annotations.size)
        assertNotNull(repo.create(draft(item = pdf, format = LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":7}""", progress = 50)))
        // Unknown library item: no FK exists, so the repository itself refuses.
        assertTrue(runCatching { repo.create(draft(item = "no-such-item")) }.isFailure)
        assertEquals(0, repo.countOrphans())
    }

    @Test fun itemsAreIsolatedAndKindsFilter() = runBlocking<Unit> {
        repo.create(draft(item = epub)); repo.create(draft(item = epub, kind = AnnotationKind.NOTE, body = "n", locator = """{"href":"c2.xhtml"}"""))
        repo.create(draft(item = pdf, format = LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":1}"""))
        assertEquals(2, list(epub).annotations.size); assertEquals(1, list(pdf).annotations.size)
        assertTrue(list(epub).annotations.all { it.libraryItemId == epub })
        assertEquals(listOf(AnnotationKind.NOTE), list(epub, setOf(AnnotationKind.NOTE)).annotations.map { it.kind })
        assertTrue(list(epub, emptySet()).annotations.isEmpty())
        assertEquals(3, repo.observeRecent().first().annotations.size)
    }

    @Test fun orderingIsDeterministic() = runBlocking<Unit> {
        // READING: COALESCE(orderKey, progress/100), createdAt, id.
        val a = repo.create(draft(locator = """{"href":"a"}""", progress = 99, orderKey = 0.30))   // key .30
        val b = repo.create(draft(locator = """{"href":"b"}""", progress = 20))                      // key .20
        val c = repo.create(draft(locator = """{"href":"c"}""", progress = 30))                      // key .30, created after a
        assertEquals(listOf(b.id, a.id, c.id), list(epub).annotations.map { it.id })
        // BOOKMARK_COMPAT: progress, createdAt, id. Identical progress + createdAt tie-break on id, inserted out of order.
        val tieRepo = RoomAnnotationRepository(db.annotations(), DefaultAnnotationLocatorValidator { true }, clock = { 5_000L }, newId = { error("unused") })
        val dao = db.annotations()
        for (id in listOf("t-c", "t-a", "t-b")) dao.create(com.d4guilar.shelfos.core.database.AnnotationEntity(id, pdf, "BOOKMARK", "FIXED_PAGE_1", """{"version":1,"page":${id.last().code}}""", 50, null, null, null, null, null, 5_000, 5_000))
        assertEquals(listOf("t-a", "t-b", "t-c"), tieRepo.observeForPublication(pdf, order = AnnotationOrder.BOOKMARK_COMPAT).first().annotations.map { it.id })
        assertEquals(listOf("t-a", "t-b", "t-c"), tieRepo.observeForPublication(pdf, order = AnnotationOrder.READING).first().annotations.map { it.id })
        // Recent: newest first.
        val recent = repo.observeRecent().first().annotations
        assertEquals(recent.sortedWith(compareByDescending<Annotation> { it.createdAt }.thenByDescending { it.id }), recent)
    }

    @Test fun bookmarkDedupeKeepsTheLegacyExactLocatorRule() = runBlocking<Unit> {
        val first = repo.create(draft())
        val again = repo.create(draft(progress = 99))
        assertEquals(first.id, again.id)
        assertEquals(1, list(epub).annotations.size)
        assertEquals(2, run { repo.create(draft(locator = """{"href":"c1.xhtml","extra":1}""")); list(epub).annotations.size })
        // Same locator on another item, and a NOTE at the same locator, are not duplicates.
        repo.create(draft(item = pdf, format = LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":2}"""))
        repo.create(draft(kind = AnnotationKind.NOTE, body = "n"))
        assertEquals(3, list(epub).annotations.size)
    }

    @Test fun unknownPersistedValuesAreSkippedAndCountedNeverReinterpreted() = runBlocking<Unit> {
        val known = repo.create(draft())
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO annotation VALUES('u-kind', '$epub', 'STICKER', 'READIUM_LOCATOR_1', '{\"href\":\"u\"}', 10, NULL, NULL, NULL, 'b', NULL, 1, 1)")
        sql.execSQL("INSERT INTO annotation VALUES('u-fmt', '$epub', 'BOOKMARK', 'FUTURE_LOCATOR_9', '{}', 10, NULL, NULL, NULL, NULL, NULL, 1, 1)")
        val listing = list(epub)
        assertEquals(listOf(known.id), listing.annotations.map { it.id })
        assertEquals(2, listing.skippedUnknown)
        assertNull(repo.get("u-kind")); assertNull(repo.get("u-fmt"))
        assertEquals(AnnotationCounts(bookmarks = 2, other = 1), repo.counts(epub).first())   // u-fmt is a BOOKMARK row by kind
        assertTrue(runCatching { repo.update("u-kind", AnnotationEdit(null, null, "x", null)) }.isFailure)
        assertEquals("unknown row left untouched", "b", sql.query("SELECT body FROM annotation WHERE id='u-kind'").use { it.moveToFirst(); it.getString(0) })
        // The legacy compat adapter lists only known bookmarks and does not crash.
        assertEquals(listOf(known.id), RoomBookmarkRepository(repo).bookmarks(epub).first().map { it.id })
    }

    @Test fun countsReflectCanonicalRowsPerKind() = runBlocking<Unit> {
        assertEquals(AnnotationCounts.None, repo.counts(epub).first())
        repo.create(draft()); repo.create(draft(locator = """{"href":"c9"}"""))
        repo.create(draft(kind = AnnotationKind.HIGHLIGHT, style = "amber", locator = """{"href":"h"}"""))
        repo.create(draft(kind = AnnotationKind.NOTE, body = "n", locator = """{"href":"n"}"""))
        val counts = repo.counts(epub).first()
        assertEquals(AnnotationCounts(2, 1, 1, 0), counts); assertEquals(4, counts.total)
        assertEquals(AnnotationCounts.None, repo.counts(pdf).first())
    }

    @Test fun removingAnItemDeletesItsAnnotationsAtomicallyAndOnlyItsOwn() = runBlocking<Unit> {
        repo.create(draft()); repo.create(draft(kind = AnnotationKind.NOTE, body = "n", locator = """{"href":"n"}"""))
        repo.create(draft(item = pdf, format = LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":1}"""))
        db.openHelper.writableDatabase.execSQL("INSERT INTO bookmark VALUES('legacy-1', '$epub', '{}', 1, NULL, 1)")
        library.reading(epub, readium, 20); library.preferences(epub, "{}")
        library.remove(epub)
        assertEquals("zero canonical rows remain for the removed item", 0, list(epub).annotations.size)
        assertEquals(0, db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM annotation WHERE libraryItemId = '$epub'").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, list(pdf).annotations.size)
        assertEquals(0, repo.countOrphans())
        // The legacy table's own cascade FK still clears its rows for the removed item.
        assertEquals(0, db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM bookmark").use { it.moveToFirst(); it.getInt(0) })
        // Explicit policy entry point behaves identically; removing twice is a harmless no-op.
        library.remove(pdf, KnowledgePolicy.DELETE); library.remove(pdf, KnowledgePolicy.DELETE)
        assertEquals(0, repo.observeRecent().first().annotations.size)
        assertEquals(0, repo.countOrphans())
    }

    @Test fun deleteForPublicationUnderDeletePolicy() = runBlocking<Unit> {
        repo.create(draft()); repo.create(draft(locator = """{"href":"c9"}""")); repo.create(draft(item = pdf, format = LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":1}"""))
        assertEquals(2, repo.deleteForPublication(epub, KnowledgePolicy.DELETE))
        assertEquals(0, list(epub).annotations.size); assertEquals(1, list(pdf).annotations.size)
    }

    @Test fun orphanIntegrityQueryDetectsRowsWithoutAnItem() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("INSERT INTO annotation VALUES('orphan', 'gone', 'BOOKMARK', 'READIUM_LOCATOR_1', '{\"href\":\"x\"}', 1, NULL, NULL, NULL, NULL, NULL, 1, 1)")
        assertEquals(1, repo.countOrphans())
    }

    @Test fun compatibilityAdapterPreservesLegacyContract() = runBlocking<Unit> {
        val compat = RoomBookmarkRepository(repo)
        compat.addBookmark(epub, "not a valid locator", 0)                  // legacy contract: opaque strings are stored
        compat.addBookmark(epub, readium, 150)                              // progress clamps like before
        compat.addBookmark(epub, readium, 10)                               // exact duplicate: no second row
        val bookmarks = compat.bookmarks(epub).first()
        assertEquals(listOf(0, 100), bookmarks.map { it.progress })
        assertEquals(listOf("not a valid locator", readium), bookmarks.map { it.locator })
        assertTrue(runCatching { compat.addBookmark("missing-item", readium, 1) }.isFailure)
        // A non-bookmark annotation with the same id space is untouched by deleteBookmark.
        val note = repo.create(draft(kind = AnnotationKind.NOTE, body = "n", locator = """{"href":"n"}"""))
        compat.deleteBookmark(note.id)
        assertNotNull(repo.get(note.id))
        compat.deleteBookmark(bookmarks.first().id)
        assertEquals(1, compat.bookmarks(epub).first().size)
        // Bookmarks written by the adapter are READIUM_LOCATOR_1 BOOKMARK annotations with updatedAt set.
        val saved: Annotation = repo.get(bookmarks.last().id)!!
        assertEquals(LocatorFormat.READIUM_LOCATOR_1, saved.locatorFormat); assertEquals(AnnotationKind.BOOKMARK, saved.kind)
        assertEquals(saved.createdAt, saved.updatedAt)
    }

    // ---- Phase 4B: EPUB highlight and note behavior on the repository ----

    @Test fun highlightWithAnOptionalBodyStaysAHighlightAndStyleAndBodyAreEditable() = runBlocking<Unit> {
        val h = repo.create(draft(kind = AnnotationKind.HIGHLIGHT, selected = "a passage", style = "amber", progress = 30))
        assertNull(h.body)
        val withBody = repo.update(h.id, AnnotationEdit(h.title, h.selectedText, "why I highlighted it", "blue"))!!
        assertEquals(AnnotationKind.HIGHLIGHT, withBody.kind)
        assertEquals("blue", withBody.styleKey); assertEquals("why I highlighted it", withBody.body); assertEquals("a passage", withBody.selectedText)
        val cleared = repo.update(h.id, AnnotationEdit(h.title, h.selectedText, null, "blue"))!!
        assertEquals(AnnotationKind.HIGHLIGHT, cleared.kind); assertNull(cleared.body)
        assertTrue("a highlight needs a style", runCatching { repo.update(h.id, AnnotationEdit(null, "a passage", null, null)) }.exceptionOrNull() is InvalidAnnotationException)
        assertEquals("blue", repo.get(h.id)!!.styleKey)
    }

    @Test fun selectionNoteAndPositionNoteRulesAndCaps() = runBlocking<Unit> {
        val selection = repo.create(draft(kind = AnnotationKind.NOTE, selected = "quoted", body = "about it"))
        val position = repo.create(draft(kind = AnnotationKind.NOTE, selected = null, body = "here", locator = """{"href":"p.xhtml"}"""))
        assertEquals("quoted", selection.selectedText); assertNull(position.selectedText)
        assertTrue(runCatching { repo.create(draft(kind = AnnotationKind.NOTE, body = "  ")) }.exceptionOrNull() is InvalidAnnotationException)
        assertTrue(runCatching { repo.update(selection.id, AnnotationEdit(null, "quoted", "", null)) }.exceptionOrNull() is InvalidAnnotationException)
        // Caps: 20,000-character body accepted exactly, one more rejected (never truncated); 64 KiB locator; 2,000-unit excerpt.
        assertEquals(AnnotationLimits.MAX_BODY, repo.create(draft(kind = AnnotationKind.NOTE, body = "n".repeat(AnnotationLimits.MAX_BODY), locator = """{"href":"cap.xhtml"}""")).body!!.length)
        assertTrue(runCatching { repo.create(draft(kind = AnnotationKind.NOTE, body = "n".repeat(AnnotationLimits.MAX_BODY + 1))) }.exceptionOrNull() is InvalidAnnotationException)
        assertTrue(runCatching { repo.create(draft(locator = """{"href":"${"x".repeat(AnnotationLimits.MAX_LOCATOR_BYTES)}"}""")) }.exceptionOrNull() is InvalidAnnotationException)
        assertTrue(runCatching { repo.create(draft(kind = AnnotationKind.HIGHLIGHT, style = "amber", selected = "s".repeat(AnnotationLimits.MAX_SELECTED_TEXT + 1))) }.exceptionOrNull() is InvalidAnnotationException)
    }

    @Test fun perPublicationListMixesKindsInReadingOrderByOrderKeyAndNeverLeaksAcrossItems() = runBlocking<Unit> {
        val late = repo.create(draft(kind = AnnotationKind.HIGHLIGHT, selected = "late", style = "green", orderKey = 0.50, progress = 50, locator = """{"href":"l.xhtml"}"""))
        val early = repo.create(draft(kind = AnnotationKind.NOTE, selected = "early", body = "n", orderKey = 0.10, progress = 10, locator = """{"href":"e.xhtml"}"""))
        val mark = repo.create(draft(kind = AnnotationKind.BOOKMARK, progress = 30, locator = """{"href":"b.xhtml"}"""))   // no orderKey: falls back to progress / 100
        repo.create(draft(item = pdf, kind = AnnotationKind.BOOKMARK, format = LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":2}"""))
        assertEquals(listOf(early.id, mark.id, late.id), list(epub).annotations.map { it.id })
        assertEquals(listOf(late.id), list(epub, setOf(AnnotationKind.HIGHLIGHT)).annotations.map { it.id })
        assertEquals(3, list(epub).annotations.size)
        // Deleting through the same repository removes exactly that row, whatever its kind.
        assertTrue(repo.delete(late.id)); assertEquals(listOf(early.id, mark.id), list(epub).annotations.map { it.id })
    }
}
