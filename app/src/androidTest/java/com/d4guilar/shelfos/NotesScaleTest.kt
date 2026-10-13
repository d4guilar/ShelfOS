// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.data.annotations.NOTES_PAGE_SIZE
import com.d4guilar.shelfos.data.annotations.RoomAnnotationRepository
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.feature.notes.NotesScreen
import com.d4guilar.shelfos.feature.notes.NotesViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Random

/**
 * Phase 4D scale evidence: 10,000 annotations over 250 publications (25 of them removed, with snapshots), in a file-backed
 * Room v5 database. Measures the repository surface (first page, kind filter, search, publication filter, deep keyset
 * walk) and the real hub UI (open, scroll to the end, search, filter, publication filter) and logs EXPLAIN QUERY PLAN.
 * Numbers go to logcat under NOTES_SCALE; the assertions are generous ceilings that only fail on a real regression.
 */
class NotesScaleTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "notes-scale.db"
    private lateinit var db: ShelfDatabase
    private lateinit var repo: RoomAnnotationRepository
    private val total = 10_000
    private val rareTerm = "zephyrquartz"
    private var rareHits = 0

    @Before fun build() {
        context.deleteDatabase(name)
        db = Room.databaseBuilder(context, ShelfDatabase::class.java, name).build()
        repo = RoomAnnotationRepository(db.annotations(), DefaultAnnotationLocatorValidator { true })
        seed()
    }
    @After fun drop() { db.close(); context.deleteDatabase(name) }

    private fun seed() {
        val sql = db.openHelper.writableDatabase
        val random = Random(42)
        val words = "harbor lighthouse quiet reader margin chapter story winter engine garden river paper window shadow memory orchard signal thread lantern".split(" ")
        fun sentence(n: Int) = (1..n).joinToString(" ") { words[random.nextInt(words.size)] }
        sql.beginTransaction()
        try {
            for (p in 0 until 250) {
                val format = listOf("EPUB", "PDF", "CBZ")[p % 3]
                if (p < 225) sql.execSQL("INSERT INTO library_item VALUES('pub-$p', 'test:pub-$p', ?, ?, 'BOOK', '$format', 'f$p', 1000, 0, ${1000 + p}, 1, NULL, 'filename', 'unknown')",
                    arrayOf("Publication ${"%03d".format(p)} ${sentence(2)}", "Author ${p % 17}"))
                else sql.execSQL("INSERT INTO annotation_source_snapshot VALUES('pub-$p', ?, ?, 'BOOK', '$format')", arrayOf("Removed ${"%03d".format(p)} ${sentence(2)}", "Author ${p % 17}"))
            }
            for (i in 0 until total) {
                val p = i % 250
                val epub = p % 3 == 0
                val kind = when (i % 10) { 0, 1, 2, 3 -> "NOTE"; 4, 5, 6 -> "HIGHLIGHT"; else -> "BOOKMARK" }.let { if (!epub && it == "HIGHLIGHT") "NOTE" else it }
                val rare = i % 800 == 3 && kind != "BOOKMARK"
                if (rare) rareHits++
                val body = if (kind == "BOOKMARK") null else sentence(12 + random.nextInt(25)) + if (rare) " $rareTerm" else ""
                val selected = if (kind == "HIGHLIGHT") sentence(8) else null
                val locator = if (epub) """{"href":"c${i % 12}.xhtml","type":"application/xhtml+xml","locations":{"progression":0.${i % 10},"totalProgression":0.${i % 9}}}""" else """{"version":1,"page":${i % 300}}"""
                sql.execSQL("INSERT INTO annotation VALUES('a-$i', 'pub-$p', '$kind', '${if (epub) "READIUM_LOCATOR_1" else "FIXED_PAGE_1"}', ?, ${i % 101}, ${if (i % 4 == 0) "NULL" else "${(i % 997) / 997.0}"}, NULL, ?, ?, ${if (kind == "HIGHLIGHT") "'amber'" else "NULL"}, ${1_000_000L + i * 7L}, ${1_000_000L + i * 7L})",
                    arrayOf(locator, selected, body))
            }
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
    }

    private inline fun <T> timed(label: String, runs: Int = 5, block: () -> T): Pair<T, Long> {
        var last: T? = null; val samples = ArrayList<Long>()
        repeat(runs) { val t = SystemClock.elapsedRealtime(); last = block(); samples += SystemClock.elapsedRealtime() - t }
        samples.sort()
        Log.i("NOTES_SCALE", "$label: median=${samples[samples.size / 2]} ms min=${samples.first()} ms max=${samples.last()} ms (n=$runs)")
        @Suppress("UNCHECKED_CAST") return (last as T) to samples[samples.size / 2]
    }

    @Test fun repositorySurfaceStaysFastAtTenThousandAnnotations() = runBlocking<Unit> {
        assertEquals(total, repo.notes(NotesQuery(), null, total + 1).rows.size)
        val (firstPub, firstPubMs) = timed("first page, publication sort") { runBlocking { repo.notes(NotesQuery(sort = NotesSort.PUBLICATION), null, NOTES_PAGE_SIZE) } }
        val (firstDate, firstDateMs) = timed("first page, date sort") { runBlocking { repo.notes(NotesQuery(sort = NotesSort.DATE), null, NOTES_PAGE_SIZE) } }
        assertEquals(NOTES_PAGE_SIZE, firstPub.rows.size); assertNotNull(firstPub.next); assertEquals(NOTES_PAGE_SIZE, firstDate.rows.size)
        val (kinds, kindMs) = timed("kind filter NOTES, first page") { runBlocking { repo.notes(NotesQuery(kinds = setOf(AnnotationKind.NOTE)), null, NOTES_PAGE_SIZE) } }
        assertTrue(kinds.rows.all { it.annotation.kind == AnnotationKind.NOTE })
        val (rare, rareMs) = timed("search rare term (~$rareHits hits)") { runBlocking { repo.notes(NotesQuery(text = rareTerm), null, 500) } }
        assertTrue("the seed plants rare-term rows", rareHits > 0)
        assertEquals(rareHits, rare.rows.size)
        val (common, commonMs) = timed("search common word, first page") { runBlocking { repo.notes(NotesQuery(text = "harbor"), null, NOTES_PAGE_SIZE) } }
        assertEquals(NOTES_PAGE_SIZE, common.rows.size)
        val (_, publicationMs) = timed("publication filter (40 rows)") { runBlocking { repo.notes(NotesQuery(publicationId = "pub-10"), null, NOTES_PAGE_SIZE) } }
        val (_, orphanMs) = timed("orphan publication filter") { runBlocking { repo.notes(NotesQuery(publicationId = "pub-240"), null, NOTES_PAGE_SIZE) } }
        val (_, groupedMs) = timed("publications grouped read (250 groups)") { runBlocking { repo.observePublications().first() } }
        // Deep walk: every row exactly once, with per-page cost not growing with depth (keyset, not OFFSET).
        for (sort in NotesSort.entries) {
            val seen = HashSet<String>(); var cursor: NotesCursor? = null; var worst = 0L; var pages = 0; val started = SystemClock.elapsedRealtime()
            do {
                val t = SystemClock.elapsedRealtime()
                val page = repo.notes(NotesQuery(sort = sort), cursor, NOTES_PAGE_SIZE)
                worst = maxOf(worst, SystemClock.elapsedRealtime() - t); pages++
                page.rows.forEach { assertTrue("no duplicate across pages", seen.add(it.annotation.id)) }
                cursor = page.next
            } while (cursor != null)
            Log.i("NOTES_SCALE", "deep keyset walk $sort: $pages pages, ${SystemClock.elapsedRealtime() - started} ms total, worst page $worst ms")
            assertEquals(total, seen.size)
            assertTrue("a late page stays cheap ($worst ms)", worst < 1_500)
        }
        // The window reload the view model does after a change (as many rows as shown).
        timed("reload window of 2,000 rows") { runBlocking { repo.notes(NotesQuery(sort = NotesSort.PUBLICATION), null, 2_000) } }
        explain("date sort", "SELECT a.id FROM annotation a LEFT JOIN library_item li ON li.id = a.libraryItemId LEFT JOIN annotation_source_snapshot s ON s.libraryItemId = a.libraryItemId WHERE a.kind IN ('NOTE','HIGHLIGHT','BOOKMARK') ORDER BY a.createdAt DESC, a.id DESC LIMIT 101")
        explain("search", "SELECT a.id FROM annotation a LEFT JOIN library_item li ON li.id = a.libraryItemId LEFT JOIN annotation_source_snapshot s ON s.libraryItemId = a.libraryItemId WHERE a.body LIKE '%harbor%' ESCAPE '\\' OR li.title LIKE '%harbor%' ESCAPE '\\' ORDER BY a.createdAt DESC, a.id DESC LIMIT 101")
        explain("publication sort", "SELECT a.id FROM annotation a LEFT JOIN library_item li ON li.id = a.libraryItemId LEFT JOIN annotation_source_snapshot s ON s.libraryItemId = a.libraryItemId WHERE a.kind IN ('NOTE','HIGHLIGHT','BOOKMARK') ORDER BY lower(COALESCE(li.title, s.title, '')) ASC, a.libraryItemId ASC, COALESCE(a.orderKey, a.progressSnapshot / 100.0) ASC, a.createdAt ASC, a.id ASC LIMIT 101")
        explain("publication filter", "SELECT a.id FROM annotation a WHERE a.libraryItemId = 'pub-10' ORDER BY a.createdAt DESC LIMIT 101")
        // Ceilings: far above what a mid-range phone needs, low enough to catch an accidental full-table sort per keystroke.
        listOf(firstPubMs, firstDateMs, kindMs, rareMs, commonMs, publicationMs, orphanMs, groupedMs).forEach { assertTrue("a hub query took $it ms", it < 1_500) }
    }

    private fun explain(label: String, query: String) {
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $query").use { c ->
            val lines = ArrayList<String>(); while (c.moveToNext()) lines += c.getString(c.getColumnCount() - 1)
            Log.i("NOTES_SCALE", "EXPLAIN [$label]: ${lines.joinToString(" | ")}")
        }
    }

    private class FrameGapSampler {
        @Volatile var maxGapMs = 0L; @Volatile var gapsOver100 = 0
        @Volatile private var running = false
        private var thread: Thread? = null
        fun start() {
            running = true
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            thread = Thread { while (running) { val posted = SystemClock.uptimeMillis()
                handler.post { val late = SystemClock.uptimeMillis() - posted; if (late > maxGapMs) maxGapMs = late; if (late > 100) gapsOver100++ }
                SystemClock.sleep(20) } }.also { it.isDaemon = true; it.start() }
        }
        fun stop() { running = false; thread?.join(500) }
    }

    @Test fun hubUiOpensScrollsSearchesAndFiltersTenThousandAnnotations() {
        val vm = NotesViewModel(repo, SavedStateHandle())
        val sampler = FrameGapSampler()
        val opened = SystemClock.elapsedRealtime()
        compose.setContent { ShelfTheme(ThemeId.CLASSIC) { NotesScreen(vm, onOpenSource = { _, _, _ -> }) } }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("notes_row").fetchSemanticsNodes().isNotEmpty() }
        Log.i("NOTES_SCALE", "UI open to first row: ${SystemClock.elapsedRealtime() - opened} ms")
        sampler.start()
        // Scroll through all pages: keep jumping to the last loaded item until everything is loaded.
        val scrollStart = SystemClock.elapsedRealtime(); var guard = 0
        while (vm.state.value.hasMore && guard++ < 400) {
            compose.onNodeWithTag("notes_list").performScrollToIndex(vm.state.value.items.size - 1)
            compose.waitForIdle()
        }
        val rows = vm.state.value.items.count { it is com.d4guilar.shelfos.feature.notes.NotesItem.Row }
        val usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)
        Log.i("NOTES_SCALE", "UI scrolled through $rows rows in ${SystemClock.elapsedRealtime() - scrollStart} ms; main max gap ${sampler.maxGapMs} ms; gaps>100ms=${sampler.gapsOver100}; java heap used ~$usedMb MB")
        assertEquals(total, rows)
        // Search, kind filter and publication filter stay interactive with every row loaded.
        compose.onNodeWithTag("notes_list").performScrollToIndex(0)
        var t = SystemClock.elapsedRealtime()
        assertTrue(rareHits > 0)
        compose.onNodeWithTag("notes_search").performTextInput(rareTerm)
        compose.waitUntil(30_000) { vm.state.value.items.count { it is com.d4guilar.shelfos.feature.notes.NotesItem.Row } == rareHits }
        Log.i("NOTES_SCALE", "UI search rare term -> $rareHits rows in ${SystemClock.elapsedRealtime() - t} ms (includes the 200 ms debounce)")
        t = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("notes_search_clear").performClick()
        compose.waitUntil(30_000) { vm.state.value.items.size > 100 }
        Log.i("NOTES_SCALE", "UI clear search -> list back in ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        compose.onNodeWithTag("notes_filter_bookmarks").performClick()
        compose.waitUntil(30_000) { vm.state.value.items.filterIsInstance<com.d4guilar.shelfos.feature.notes.NotesItem.Row>().let { r -> r.isNotEmpty() && r.all { it.item.annotation.kind == AnnotationKind.BOOKMARK } } }
        Log.i("NOTES_SCALE", "UI kind filter Bookmarks in ${SystemClock.elapsedRealtime() - t} ms")
        t = SystemClock.elapsedRealtime()
        vm.selectFilter(com.d4guilar.shelfos.feature.reader.AnnotationFilter.ALL); vm.selectPublication("pub-240")
        compose.waitUntil(30_000) { vm.state.value.items.filterIsInstance<com.d4guilar.shelfos.feature.notes.NotesItem.Row>().let { r -> r.isNotEmpty() && r.all { !it.item.source.live } } }
        Log.i("NOTES_SCALE", "UI orphan publication filter in ${SystemClock.elapsedRealtime() - t} ms")
        sampler.stop()
        compose.onNodeWithTag("notes_group_delete").assertExists()
        assertTrue("the main thread stayed responsive (max gap ${sampler.maxGapMs} ms)", sampler.maxGapMs < 2_000)
    }
}
