// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.notes

import androidx.lifecycle.SavedStateHandle
import com.d4guilar.shelfos.data.annotations.AnnotationRepository
import com.d4guilar.shelfos.domain.annotations.*
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.reader.AnnotationFilter
import com.d4guilar.shelfos.data.annotations.PortableExportFormat
import com.d4guilar.shelfos.domain.annotations.portable.PortableMarkdownLabels
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModelTest {
    @After fun resetMain() { Dispatchers.resetMain() }

    private class FakeRepo(initial: List<AnnotationWithSource>) : AnnotationRepository {
        val rows = MutableStateFlow(initial)
        val queries = mutableListOf<NotesQuery>()
        val updates = mutableListOf<Pair<String, AnnotationEdit>>()
        val deleted = mutableListOf<String>()
        val groupsDeleted = mutableListOf<String>()
        private val changes = MutableStateFlow(0)
        private fun matches(q: NotesQuery, r: AnnotationWithSource) = (q.kinds == null || r.annotation.kind in q.kinds) &&
            (q.publicationId == null || r.source.libraryItemId == q.publicationId) &&
            (q.text.isBlank() || r.annotation.body.orEmpty().contains(q.text, true))
        override suspend fun notes(query: NotesQuery, after: NotesCursor?, limit: Int): NotesPage {
            queries += query
            val all = rows.value.filter { matches(query, it) }
            val start = after?.id?.toInt() ?: 0
            val page = all.drop(start).take(limit)
            return NotesPage(page, if (start + limit < all.size) NotesCursor("", "", 0.0, 0, (start + limit).toString()) else null)
        }
        override fun observeChanges() = changes
        fun touch() { changes.value++ }
        override fun observePublications() = rows.map { list ->
            list.groupBy { it.source.libraryItemId }.map { (_, g) -> NotesPublication(g.first().source, g.size) }
        }
        override suspend fun get(id: String) = rows.value.firstOrNull { it.annotation.id == id }?.annotation
        override suspend fun update(id: String, edit: AnnotationEdit): Annotation? { updates += id to edit; return get(id) }
        override suspend fun delete(id: String, kind: AnnotationKind?): Boolean { deleted += id; return true }
        override suspend fun countNotes(query: NotesQuery) = rows.value.count { matches(query, it) }
        override suspend fun deleteOrphanedPublication(libraryItemId: String): Int { groupsDeleted += libraryItemId; return 1 }
        override suspend fun create(draft: AnnotationDraft, validateLocator: Boolean) = error("unused")
        override suspend fun deleteForPublication(libraryItemId: String, policy: KnowledgePolicy) = error("unused")
        override fun observeForPublication(libraryItemId: String, kinds: Set<AnnotationKind>?, order: AnnotationOrder) = error("unused")
        override fun observeRecent() = error("unused")
        override fun counts(libraryItemId: String) = error("unused")
        override suspend fun countOrphans() = 0
    }

    private fun source(id: String, title: String, live: Boolean = true) =
        AnnotationSource(id, title, "Author", MediaCategory.BOOK, PublicationFormat.EPUB, live, true)
    private fun row(id: String, pub: String, kind: AnnotationKind = AnnotationKind.NOTE, body: String? = "body $id", live: Boolean = true,
        format: LocatorFormat = LocatorFormat.READIUM_LOCATOR_1, locator: String = "{}") =
        AnnotationWithSource(Annotation(id, pub, kind, format, locator, 10, null, null, if (kind == AnnotationKind.HIGHLIGHT) "sel" else null, body,
            if (kind == AnnotationKind.HIGHLIGHT) "amber" else null, 1, 1), source(pub, "Title $pub", live))

    private fun TestScope.viewModel(repo: FakeRepo, saved: SavedStateHandle = SavedStateHandle()): NotesViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return NotesViewModel(repo, saved)
    }

    private val sample = listOf(row("a1", "a"), row("a2", "a", AnnotationKind.HIGHLIGHT), row("b1", "b", AnnotationKind.BOOKMARK, body = null), row("g1", "g", live = false))

    @Test fun loadsTheFirstPageWithAHeaderPerPublicationInTheDefaultSort() = runTest {
        val repo = FakeRepo(sample); val vm = viewModel(repo)
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state.loaded)
        assertEquals(NotesQuery(), repo.queries.first())
        assertEquals(listOf("pub:a", "note:a1", "note:a2", "pub:b", "note:b1", "pub:g", "note:g1"), state.items.map { it.key })
        val orphanHeader = state.items.filterIsInstance<NotesItem.Header>().last()
        assertFalse(orphanHeader.source.live); assertEquals(1, orphanHeader.total)
    }

    @Test fun dateSortHasNoGroupHeadersAndFiltersMapToKinds() = runTest {
        val repo = FakeRepo(sample); val vm = viewModel(repo)
        advanceUntilIdle()
        vm.selectSort(NotesSort.DATE); advanceUntilIdle()
        assertTrue(vm.state.value.items.none { it is NotesItem.Header })
        vm.selectFilter(AnnotationFilter.NOTES); advanceUntilIdle()
        assertEquals(setOf(AnnotationKind.NOTE), repo.queries.last().kinds)
        assertEquals(listOf("a1", "g1"), vm.state.value.items.filterIsInstance<NotesItem.Row>().map { it.item.annotation.id })
        vm.selectFilter(AnnotationFilter.HIGHLIGHTS); advanceUntilIdle()
        assertEquals(setOf(AnnotationKind.HIGHLIGHT), repo.queries.last().kinds)
        vm.selectFilter(AnnotationFilter.BOOKMARKS); advanceUntilIdle()
        assertEquals(setOf(AnnotationKind.BOOKMARK), repo.queries.last().kinds)
        vm.selectFilter(AnnotationFilter.ALL); advanceUntilIdle()
        assertNull(repo.queries.last().kinds)
    }

    @Test fun typingShowsAtOnceButTheDatabaseIsAskedOnlyAfterTheDebounceAndClearingIsImmediate() = runTest {
        val repo = FakeRepo(sample); val vm = viewModel(repo)
        advanceUntilIdle(); val before = repo.queries.size
        vm.search("b"); vm.search("bo"); vm.search("bod"); runCurrent()
        assertEquals("the field reflects typing immediately", "bod", vm.state.value.query)
        assertEquals("no query yet during the pause", before, repo.queries.size)
        advanceTimeBy(250); runCurrent()
        assertEquals("bod", repo.queries.last().text); assertEquals(before + 1, repo.queries.size)
        vm.search(""); runCurrent()
        assertEquals("", repo.queries.last().text)
    }

    @Test fun loadMoreAppendsTheNextKeysetPageAndStopsAtTheEnd() = runTest {
        val many = (1..250).map { row("n$it", "p${it % 3}", body = "body") }
        val repo = FakeRepo(many); val vm = viewModel(repo)
        advanceUntilIdle()
        fun rows() = vm.state.value.items.count { it is NotesItem.Row }
        assertEquals(100, rows()); assertTrue(vm.state.value.hasMore)
        vm.loadMore(); advanceUntilIdle(); assertEquals(200, rows()); assertTrue(vm.state.value.hasMore)
        vm.loadMore(); advanceUntilIdle(); assertEquals(250, rows()); assertFalse(vm.state.value.hasMore)
        val queriesSoFar = repo.queries.size
        vm.loadMore(); advanceUntilIdle(); assertEquals("nothing left to load", queriesSoFar, repo.queries.size)
    }

    @Test fun aChangeReloadsAsManyRowsAsAreAlreadyShown() = runTest {
        val many = (1..250).map { row("n$it", "p", body = "body") }
        val repo = FakeRepo(many); val vm = viewModel(repo)
        advanceUntilIdle(); vm.loadMore(); advanceUntilIdle()
        assertEquals(200, vm.state.value.items.count { it is NotesItem.Row })
        repo.rows.value = many.drop(1); repo.touch(); advanceUntilIdle()
        assertEquals("the list keeps its length after an edit or delete", 200, vm.state.value.items.count { it is NotesItem.Row })
        assertEquals("n2", vm.state.value.items.filterIsInstance<NotesItem.Row>().first().item.annotation.id)
    }

    @Test fun controlsSurviveRecreationBecauseTheyLiveInSavedState() = runTest {
        val repo = FakeRepo(sample); val saved = SavedStateHandle()
        val first = viewModel(repo, saved); advanceUntilIdle()
        first.selectFilter(AnnotationFilter.NOTES); first.selectSort(NotesSort.DATE); first.selectPublication("a"); first.search("body"); advanceTimeBy(300); advanceUntilIdle()
        val second = viewModel(FakeRepo(sample), saved); advanceUntilIdle()
        assertEquals("body", second.state.value.query); assertEquals(AnnotationFilter.NOTES, second.state.value.filter)
        assertEquals("a", second.state.value.publicationId); assertEquals(NotesSort.DATE, second.state.value.sort)
        assertTrue(second.state.value.filtering)
    }

    @Test fun aPublicationFilterWhoseNotesAreGoneClearsItselfInsteadOfShowingAnEmptyList() = runTest {
        val repo = FakeRepo(sample); val vm = viewModel(repo)
        advanceUntilIdle(); vm.selectPublication("g"); advanceUntilIdle()
        assertEquals("g", vm.state.value.publicationId)
        repo.rows.value = sample.filter { it.source.libraryItemId != "g" }; repo.touch(); advanceUntilIdle()
        assertNull(vm.state.value.publicationId)
    }

    @Test fun aSinglePublicationFilterShowsOneHeaderAndKeepsItWhenSearchMatchesNothing() = runTest {
        val repo = FakeRepo(sample); val vm = viewModel(repo)
        advanceUntilIdle(); vm.selectSort(NotesSort.DATE); vm.selectPublication("g"); advanceUntilIdle()
        assertEquals(listOf("pub:g", "note:g1"), vm.state.value.items.map { it.key })
        vm.search("no such words"); advanceTimeBy(300); advanceUntilIdle()
        assertEquals("the removed publication keeps its header, so its group action stays reachable", listOf("pub:g"), vm.state.value.items.map { it.key })
    }

    @Test fun fixedPageRowsCarryTheirPageParsedOnce() = runTest {
        val fixed = row("f1", "f", format = LocatorFormat.FIXED_PAGE_1, locator = """{"version":1,"page":6}""")
        val bad = row("f2", "f", format = LocatorFormat.FIXED_PAGE_1, locator = "nope")
        val vm = viewModel(FakeRepo(listOf(fixed, bad, row("e1", "e")))); advanceUntilIdle()
        val rows = vm.state.value.items.filterIsInstance<NotesItem.Row>().associateBy { it.item.annotation.id }
        assertEquals(7, rows.getValue("f1").page); assertNull(rows.getValue("f2").page); assertNull(rows.getValue("e1").page)
    }

    @Test fun editLoadsTheFullAnnotationValidatesAndNeverChangesKindOrLocator() = runTest {
        val repo = FakeRepo(sample); val vm = viewModel(repo); advanceUntilIdle()
        vm.beginEdit("b1"); advanceUntilIdle()
        assertNull("a bookmark has nothing to edit", vm.state.value.editing)
        vm.beginEdit("a1"); advanceUntilIdle()
        assertEquals("a1", vm.state.value.editing?.id)
        vm.saveEdit("   ", null); advanceUntilIdle()
        assertTrue("a blank note is refused", repo.updates.isEmpty()); assertNotNull(vm.state.value.notice); assertNotNull(vm.state.value.editing)
        vm.dismissNotice()
        vm.saveEdit("x".repeat(AnnotationLimits.MAX_BODY + 1), null); advanceUntilIdle()
        assertTrue("an over-long note is refused, never truncated", repo.updates.isEmpty())
        vm.saveEdit("new body", null); advanceUntilIdle()
        assertEquals(AnnotationEdit(null, null, "new body", null), repo.updates.single().second)
        assertNull(vm.state.value.editing)
        vm.beginEdit("a2"); advanceUntilIdle()
        vm.saveEdit("", "green"); advanceUntilIdle()
        assertEquals("a highlight may clear its body and change style", AnnotationEdit(null, "sel", null, "green"), repo.updates.last().second)
    }

    @Test fun deleteAndGroupDeleteNeedAnExplicitConfirmationAndSurviveRecreationWhilePending() = runTest {
        val repo = FakeRepo(sample); val saved = SavedStateHandle(); val vm = viewModel(repo, saved); advanceUntilIdle()
        vm.requestDelete("a1")
        assertEquals("a1", viewModel(repo, saved).state.value.deleteId)
        vm.cancelDelete(); advanceUntilIdle()
        assertTrue(repo.deleted.isEmpty()); assertNull(vm.state.value.deleteId)
        vm.requestDelete("a1"); vm.confirmDelete(); advanceUntilIdle()
        assertEquals(listOf("a1"), repo.deleted); assertNull(vm.state.value.deleteId)
        vm.requestDeleteOrphanGroup("g"); vm.cancelDeleteOrphanGroup(); advanceUntilIdle()
        assertTrue(repo.groupsDeleted.isEmpty())
        vm.requestDeleteOrphanGroup("g"); vm.confirmDeleteOrphanGroup(); advanceUntilIdle()
        assertEquals(listOf("g"), repo.groupsDeleted)
    }

    @Test fun cancellingSafPickersIsANoOpNotAnError() = runTest {
        val vm = viewModel(FakeRepo(sample)); advanceUntilIdle()
        val labels = PortableMarkdownLabels("", "", "", "", "", { "" }, { "" }, { "" }, { "" })
        vm.showExportOptions(); assertTrue(vm.state.value.exportOptions)
        vm.export(null, PortableExportFormat.JSON, PortableExportScope.ALL, labels)
        vm.previewImport(null)
        assertFalse(vm.state.value.exportOptions)
        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.importPreview)
    }
}
