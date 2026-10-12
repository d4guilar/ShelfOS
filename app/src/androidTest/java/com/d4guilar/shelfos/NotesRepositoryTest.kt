// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.LegacyDatabases.q
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.data.library.RoomLibraryRepository
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Phase 4D: the global Notes read surface and the KEEP/DELETE removal matrix over a real in-memory Room v5 database:
 * kind filters, escaped LIKE search, publication filter, both sorts, keyset pages and their boundaries, orphan rows with
 * and without a snapshot, snapshot lifecycle, explicit orphan-group deletion and editing of retained notes.
 */
class NotesRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase
    private lateinit var library: RoomLibraryRepository
    private lateinit var repo: RoomAnnotationRepository
    private val readium = """{"href":"c1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.2}}"""

    @Before fun open() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        library = RoomLibraryRepository(db.library())
        repo = RoomAnnotationRepository(db.annotations(), DefaultAnnotationLocatorValidator { true })
    }
    @After fun close() { db.close() }

    private suspend fun item(id: String, title: String, creator: String = "Writer", format: PublicationFormat = PublicationFormat.EPUB,
        category: MediaCategory = MediaCategory.BOOK, uri: String = "test:$id", managedPath: String? = null) =
        library.add(LibraryItem(id, title, creator, category, uri, format, "$id.${format.name.lowercase()}", 100L, managedPath = managedPath))

    /** Exact-row insert (timestamps and order keys chosen by the test, ties included). */
    private fun insert(id: String, item: String, kind: String = "NOTE", format: String = "READIUM_LOCATOR_1", locator: String = readium, progress: Int = 10,
        orderKey: Double? = null, title: String? = null, selected: String? = null, body: String? = "body $id", style: String? = null, created: Long = 1_000, updated: Long = created) {
        db.openHelper.writableDatabase.execSQL("INSERT INTO annotation VALUES(${q(id)}, ${q(item)}, ${q(kind)}, ${q(format)}, ${q(locator)}, $progress, " +
            "${orderKey ?: "NULL"}, ${q(title)}, ${q(selected)}, ${q(body)}, ${q(style)}, $created, $updated)")
    }
    private suspend fun page(query: NotesQuery = NotesQuery(), after: NotesCursor? = null, limit: Int = 500) = repo.notes(query, after, limit)
    private suspend fun ids(query: NotesQuery = NotesQuery()) = page(query).rows.map { it.annotation.id }
    private fun count(sql: String) = db.openHelper.writableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

    // ---------------------------------------------------------------- removal: KEEP and DELETE

    private suspend fun seedTwoItems() {
        item("a", "Alpha Book", "Ann Author", PublicationFormat.EPUB, MediaCategory.BOOK, managedPath = "/private/a.epub")
        item("b", "Beta Comic", "Bo Artist", PublicationFormat.CBZ, MediaCategory.COMIC)
        insert("a-hl", "a", "HIGHLIGHT", selected = "quoted text", body = "why", style = "amber", created = 2_000, progress = 20)
        insert("a-note", "a", "NOTE", body = "my note", created = 3_000, progress = 30)
        insert("a-bm", "a", "BOOKMARK", body = null, title = "label", created = 1_000, progress = 5)
        insert("b-note", "b", "NOTE", "FIXED_PAGE_1", """{"version":1,"page":4}""", 40, body = "page note", created = 4_000)
        library.reading("a", readium, 20); library.preferences("a", """{"scroll":true}""")
    }

    @Test fun keepRemovalLeavesOrphansIntactAndStoresOnlyADisplaySnapshot() = runBlocking<Unit> {
        seedTwoItems()
        val before = repo.observeForPublication("a").first().annotations
        library.remove("a", KnowledgePolicy.KEEP)
        assertTrue(library.publications.first().none { it.id == "a" })
        assertEquals("kept annotations are byte-identical, locators included", before, repo.observeForPublication("a").first().annotations)
        assertEquals("ordinary reader state is removed", 0, count("SELECT COUNT(*) FROM reading_state WHERE itemId = 'a'") + count("SELECT COUNT(*) FROM reader_preference WHERE itemId = 'a'"))
        db.openHelper.writableDatabase.query("SELECT libraryItemId, title, creator, category, format FROM annotation_source_snapshot").use { c ->
            assertEquals(listOf(listOf("a", "Alpha Book", "Ann Author", "BOOK", "EPUB")), LegacyDatabases.rows(c))
        }
        // Not one stored value is a source URI or a private path.
        db.openHelper.writableDatabase.query("SELECT * FROM annotation_source_snapshot").use { c ->
            val values = LegacyDatabases.rows(c).flatten()
            assertTrue(values.none { it!!.contains("test:a") || it.contains("/private") })
        }
        assertEquals(3, repo.countOrphans())
        val rows = page(NotesQuery(publicationId = "a")).rows
        assertEquals(3, rows.size)
        assertTrue(rows.all { !it.source.live && it.source.title == "Alpha Book" && it.source.creator == "Ann Author" &&
            it.source.category == MediaCategory.BOOK && it.source.format == PublicationFormat.EPUB })
        // The other publication is untouched.
        assertEquals(1, page(NotesQuery(publicationId = "b")).rows.size)
        assertTrue(page(NotesQuery(publicationId = "b")).rows.single().source.live)
    }

    @Test fun deleteRemovalDeletesAnnotationsAndAnyStaleSnapshot() = runBlocking<Unit> {
        seedTwoItems()
        // A stale snapshot row for the same id (from an earlier keep) must not outlive a delete removal.
        db.openHelper.writableDatabase.execSQL("INSERT INTO annotation_source_snapshot VALUES('a', 'Stale', 'Stale', 'BOOK', 'EPUB')")
        library.remove("a", KnowledgePolicy.DELETE)
        assertEquals(0, count("SELECT COUNT(*) FROM annotation WHERE libraryItemId = 'a'"))
        assertEquals(0, count("SELECT COUNT(*) FROM annotation_source_snapshot"))
        assertEquals(1, count("SELECT COUNT(*) FROM annotation"))
        assertEquals(0, repo.countOrphans())
    }

    @Test fun keepWithNoAnnotationsLeavesNoSnapshot() = runBlocking<Unit> {
        item("empty", "Nothing Here")
        library.remove("empty", KnowledgePolicy.KEEP)
        assertEquals(0, count("SELECT COUNT(*) FROM annotation_source_snapshot"))
        assertEquals(0, count("SELECT COUNT(*) FROM library_item"))
    }

    @Test fun readdingTheSameFileNeverReattachesKeptNotes() = runBlocking<Unit> {
        seedTwoItems()
        library.remove("a", KnowledgePolicy.KEEP)
        // The very same file (same source URI, same title) comes back as a NEW library item with a new id.
        item("a-new", "Alpha Book", "Ann Author", uri = "test:a")
        assertTrue(page(NotesQuery(publicationId = "a-new")).rows.isEmpty())
        assertEquals("kept notes stay on the removed publication", 3, page(NotesQuery(publicationId = "a")).rows.size)
        assertTrue(page(NotesQuery(publicationId = "a")).rows.none { it.source.live })
        assertEquals(3, repo.countOrphans())
        assertEquals(0, count("SELECT COUNT(*) FROM annotation WHERE libraryItemId = 'a-new'"))
    }

    @Test fun removalNeverMutatesTheSourceFile() = runBlocking<Unit> {
        val file = File(context.cacheDir, "source-immutable.epub").apply { writeBytes(ByteArray(2048) { (it * 7).toByte() }) }
        val sha = { MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) } }
        val stamp = file.lastModified(); val hash = sha()
        item("f", "File Backed", uri = file.toURI().toString(), managedPath = file.path)
        insert("f-1", "f", body = "n")
        library.remove("f", KnowledgePolicy.KEEP)
        assertTrue(file.exists()); assertEquals(hash, sha()); assertEquals(stamp, file.lastModified())
        item("g", "File Backed Two", uri = file.toURI().toString() + "#2", managedPath = file.path)
        library.remove("g", KnowledgePolicy.DELETE)
        assertTrue(file.exists()); assertEquals(hash, sha())
        file.delete()
    }

    @Test fun lastOrphanDeletionRemovesTheSnapshotAndEarlierOnesKeepIt() = runBlocking<Unit> {
        seedTwoItems()
        library.remove("a", KnowledgePolicy.KEEP)
        assertTrue(repo.delete("a-hl"))
        assertEquals(1, count("SELECT COUNT(*) FROM annotation_source_snapshot"))
        assertTrue(repo.delete("a-note")); assertTrue(repo.delete("a-bm"))
        assertEquals("no snapshot outlives its last annotation", 0, count("SELECT COUNT(*) FROM annotation_source_snapshot"))
        assertEquals(0, repo.countOrphans())
        assertEquals(0, db.annotations().countUnusedSnapshots())
        // Deleting a live item's annotation never touches another publication's snapshot.
        item("c", "Gamma"); insert("c-1", "c"); library.remove("c", KnowledgePolicy.KEEP)
        assertTrue(repo.delete("b-note"))
        assertEquals(1, count("SELECT COUNT(*) FROM annotation_source_snapshot"))
    }

    @Test fun orphanGroupDeletionIsExplicitRefusesLiveItemsAndRemovesTheSnapshot() = runBlocking<Unit> {
        seedTwoItems()
        library.remove("a", KnowledgePolicy.KEEP)
        assertEquals("a live publication's notes can never be deleted through the orphan path", 0, repo.deleteOrphanedPublication("b"))
        assertEquals(1, count("SELECT COUNT(*) FROM annotation WHERE libraryItemId = 'b'"))
        assertEquals(3, repo.deleteOrphanedPublication("a"))
        assertEquals(0, count("SELECT COUNT(*) FROM annotation WHERE libraryItemId = 'a'"))
        assertEquals(0, count("SELECT COUNT(*) FROM annotation_source_snapshot"))
        assertEquals("idempotent", 0, repo.deleteOrphanedPublication("a"))
        assertEquals(1, count("SELECT COUNT(*) FROM annotation"))
    }

    @Test fun keepPolicyOnTheRepositoryDeletesNothing() = runBlocking<Unit> {
        seedTwoItems()
        assertEquals(0, repo.deleteForPublication("a", KnowledgePolicy.KEEP))
        assertEquals(3, count("SELECT COUNT(*) FROM annotation WHERE libraryItemId = 'a'"))
    }

    @Test fun retainedOrphanNotesAndHighlightsAreEditableWithKindAndLocatorUnchanged() = runBlocking<Unit> {
        seedTwoItems()
        library.remove("a", KnowledgePolicy.KEEP)
        val note = requireNotNull(repo.update("a-note", AnnotationEdit(null, null, "edited later", null)))
        assertEquals("edited later", note.body); assertEquals(AnnotationKind.NOTE, note.kind); assertEquals(readium, note.locatorJson)
        val hl = requireNotNull(repo.update("a-hl", AnnotationEdit(null, "quoted text", "", "green")))
        assertEquals(AnnotationKind.HIGHLIGHT, hl.kind); assertEquals("green", hl.styleKey); assertEquals(readium, hl.locatorJson)
        assertTrue(runCatching { repo.update("a-note", AnnotationEdit(null, null, "  ", null)) }.exceptionOrNull() is InvalidAnnotationException)
        assertEquals("edited later", repo.get("a-note")!!.body)
        assertTrue(page(NotesQuery(publicationId = "a")).rows.all { !it.source.live })
    }

    @Test fun anOrphanWithoutASnapshotIsStillListedWithoutATitle() = runBlocking<Unit> {
        insert("lost", "missing-item", body = "survivor")
        val row = page().rows.single()
        assertFalse(row.source.live); assertNull(row.source.title); assertNull(row.source.format)
        assertEquals("survivor", row.annotation.body)
        assertEquals(1, repo.observePublications().first().single().total)
        assertEquals(1, repo.deleteOrphanedPublication("missing-item"))
    }

    // ---------------------------------------------------------------- queries

    private suspend fun seedQueries() {
        item("a", "Alpha Book", "Ann Author"); item("b", "beta comic", "Bo Artist", PublicationFormat.CBZ, MediaCategory.COMIC); item("c", "Gamma Doc", "Cy", PublicationFormat.PDF, MediaCategory.DOCUMENT)
        insert("a1", "a", "NOTE", body = "100% sure", created = 100, progress = 50)
        insert("a2", "a", "NOTE", body = "1000 sure", created = 200, progress = 10)
        insert("a3", "a", "HIGHLIGHT", selected = "The Quick Fox", body = null, style = "amber", created = 300, progress = 30, orderKey = 0.3)
        insert("a4", "a", "BOOKMARK", body = null, title = "Chapter Two", created = 400, progress = 20)
        insert("b1", "b", "NOTE", "FIXED_PAGE_1", """{"version":1,"page":2}""", 20, body = "under_score", created = 500)
        insert("b2", "b", "BOOKMARK", "FIXED_PAGE_1", """{"version":1,"page":9}""", 90, body = null, created = 600)
        insert("c1", "c", "NOTE", "FIXED_PAGE_1", """{"version":1,"page":1}""", 5, body = "back\\slash", created = 700)
        // An orphan with a snapshot, and a row of a kind this build does not know.
        item("gone", "Zeta Removed", "Zed"); insert("g1", "gone", "NOTE", body = "kept words", created = 800)
        library.remove("gone", KnowledgePolicy.KEEP)
        insert("future", "a", "FUTURE_KIND", body = "hidden", created = 900)
    }

    @Test fun kindFiltersAreExactAndAnnotatedHighlightsStayHighlights() = runBlocking<Unit> {
        seedQueries()
        insert("a5", "a", "HIGHLIGHT", selected = "annotated", body = "has a body", style = "blue", created = 450)
        assertEquals(setOf("a1", "a2", "b1", "c1", "g1"), ids(NotesQuery(kinds = setOf(AnnotationKind.NOTE))).toSet())
        assertEquals(setOf("a3", "a5"), ids(NotesQuery(kinds = setOf(AnnotationKind.HIGHLIGHT))).toSet())
        assertEquals(setOf("a4", "b2"), ids(NotesQuery(kinds = setOf(AnnotationKind.BOOKMARK))).toSet())
        assertEquals("unknown kinds are left out of every view", 9, ids().size)
        assertFalse("future" in ids())
    }

    @Test fun searchCoversKnowledgeFieldsAndPublicationDetailsAndEscapesLikeCharacters() = runBlocking<Unit> {
        seedQueries()
        suspend fun hits(text: String, kinds: Set<AnnotationKind>? = null, publication: String? = null) = ids(NotesQuery(kinds, publication, text)).toSet()
        assertEquals(setOf("a1"), hits("100%"))                       // % is literal
        assertEquals(setOf("b1"), hits("_"))                          // _ is literal, not "any character"
        assertEquals(setOf("c1"), hits("\\"))                         // backslash is literal
        assertEquals(setOf("a3"), hits("quick FOX"))                  // selected text, case-insensitive
        assertEquals(setOf("a4"), hits("chapter two"))                // a bookmark's own title
        assertEquals(setOf("a1", "a2", "a3", "a4"), hits("alpha"))    // live publication title
        assertEquals(setOf("a1", "a2", "a3", "a4"), hits("ann AUTHOR")) // live creator
        assertEquals(setOf("g1"), hits("zeta"))                       // orphan snapshot title
        assertEquals(setOf("g1"), hits("zed"))                        // orphan snapshot creator
        assertEquals(setOf("a1", "a2"), hits("sure", setOf(AnnotationKind.NOTE), "a")) // search composes with kind and publication
        assertEquals(emptySet<String>(), hits("sure", setOf(AnnotationKind.BOOKMARK)))
        assertEquals(emptySet<String>(), hits("no such words anywhere"))
        assertEquals(ids().toSet(), hits("   "))                      // blank text is no filter
        assertTrue(page(NotesQuery(text = "zzz")).let { it.rows.isEmpty() && it.next == null })
        // The result count agrees with the rows for the same filters (all pages, not just one).
        assertEquals(4, repo.countNotes(NotesQuery(text = "alpha")))
        assertEquals(ids().size, repo.countNotes(NotesQuery()))
        assertEquals(2, repo.countNotes(NotesQuery(kinds = setOf(AnnotationKind.NOTE), publicationId = "a", text = "sure")))
        assertEquals(0, repo.countNotes(NotesQuery(text = "zzz")))
    }

    @Test fun publicationFilterIncludesRemovedPublications() = runBlocking<Unit> {
        seedQueries()
        assertEquals(setOf("b1", "b2"), ids(NotesQuery(publicationId = "b")).toSet())
        assertEquals(setOf("g1"), ids(NotesQuery(publicationId = "gone")).toSet())
        assertTrue(ids(NotesQuery(publicationId = "nope")).isEmpty())
        val pubs = repo.observePublications().first()
        assertEquals(listOf("a", "b", "c", "gone"), pubs.map { it.source.libraryItemId })      // by lower-cased title
        assertEquals(listOf(4, 2, 1, 1), pubs.map { it.total })
        assertEquals(listOf(true, true, true, false), pubs.map { it.source.live })
        assertEquals("Zeta Removed", pubs.last().source.title)
    }

    @Test fun orderingByDateAndByPublicationUsesTheDocumentedKeys() = runBlocking<Unit> {
        seedQueries()
        assertEquals(listOf("g1", "c1", "b2", "b1", "a4", "a3", "a2", "a1"), ids(NotesQuery(sort = NotesSort.DATE)))
        // Publications by lower-cased title ("beta comic" sorts as b), each in reading order (orderKey, else progress/100).
        assertEquals(listOf("a2", "a4", "a3", "a1", "b1", "b2", "c1", "g1"), ids(NotesQuery(sort = NotesSort.PUBLICATION)))
    }

    private suspend fun walk(query: NotesQuery, size: Int): List<String> {
        val out = ArrayList<String>(); var cursor: NotesCursor? = null; var guard = 0
        do {
            val p = page(query, cursor, size)
            assertTrue("a page never exceeds its limit", p.rows.size <= size)
            out += p.rows.map { it.annotation.id }; cursor = p.next
            assertTrue("pagination must terminate", ++guard < 1000)
        } while (cursor != null)
        return out
    }

    @Test fun keysetPagesConcatenateToTheFullListWithoutGapsOrDuplicatesForBothSorts() = runBlocking<Unit> {
        seedQueries()
        // Ties on createdAt, orderKey and progress: only the id breaks them.
        for (n in 1..5) insert("t$n", "a", "NOTE", body = "tie $n", created = 300, progress = 30, orderKey = 0.3)
        for (sort in NotesSort.entries) {
            val full = ids(NotesQuery(sort = sort))
            assertEquals(full.size, full.toSet().size)
            for (size in listOf(1, 2, 3, 5, 7, full.size - 1, full.size, full.size + 1, 100))
                assertEquals("sort=$sort size=$size", full, walk(NotesQuery(sort = sort), size))
            // A filtered walk pages the filtered list.
            val notes = NotesQuery(kinds = setOf(AnnotationKind.NOTE), sort = sort)
            assertEquals(ids(notes), walk(notes, 2))
        }
    }

    @Test fun nextCursorIsNullExactlyWhenNothingFollows() = runBlocking<Unit> {
        seedQueries()
        val total = ids().size
        assertNull(page(limit = total).next)                 // exactly one full page
        assertNotNull(page(limit = total - 1).next)          // one row left over
        val second = page(after = page(limit = total - 1).next, limit = total - 1)
        assertEquals(1, second.rows.size); assertNull(second.next)
        assertTrue(runCatching { page(limit = 0) }.isFailure)
        // A cursor past the end yields an empty last page.
        assertTrue(page(NotesQuery(sort = NotesSort.DATE), NotesCursor("", "", 0.0, 0L, ""), 5).rows.isEmpty())
    }

    @Test fun observeChangesFiresOnAnnotationSnapshotAndLibraryWrites() = runBlocking<Unit> {
        seedQueries()
        val seen = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { repo.observeChanges().collect { seen.send(it) } }
        try {
            kotlinx.coroutines.withTimeout(5_000) { seen.receive() }
            repo.create(AnnotationDraft("a", AnnotationKind.NOTE, LocatorFormat.READIUM_LOCATOR_1, readium, 10, body = "fresh"))
            kotlinx.coroutines.withTimeout(5_000) { seen.receive() }
            repo.deleteOrphanedPublication("gone")
            kotlinx.coroutines.withTimeout(5_000) { seen.receive() }
        } finally { job.cancel() }
    }
}
