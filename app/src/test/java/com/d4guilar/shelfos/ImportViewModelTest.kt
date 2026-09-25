// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.d4guilar.shelfos.data.library.LibraryRepository
import com.d4guilar.shelfos.domain.importing.PreparedImport
import com.d4guilar.shelfos.domain.importing.PublicationImporter
import com.d4guilar.shelfos.domain.library.*
import com.d4guilar.shelfos.feature.importing.ImportViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    @Before fun installMainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }

    private class FakeImporter : PublicationImporter {
        val requests = mutableListOf<Pair<String, Boolean>>()
        val discarded = mutableListOf<Pair<PreparedImport, Boolean>>()
        var prepare: suspend (String, Boolean) -> PreparedImport = { uri, copy -> PreparedImport(candidate(uri), acquiredGrant = !copy) }
        override suspend fun prepare(sourceUri: String, copy: Boolean, stage: (String) -> Unit): PreparedImport {
            requests += sourceUri to copy
            stage("Inspecting publication…")
            return prepare(sourceUri, copy)
        }
        override fun discard(prepared: PreparedImport, sourceStillUsed: Boolean) { discarded += prepared to sourceStillUsed }
    }

    private companion object {
        fun candidate(uri: String, title: String = "Candidate", origin: String = "filename") = LibraryItem("id-$uri", title, "", MediaCategory.BOOK,
            uri, PublicationFormat.EPUB, "candidate.epub", 42L, titleOrigin = origin)
    }

    private fun TestScope.importing(importer: FakeImporter, library: TestLibrary = TestLibrary(), gate: suspend () -> Unit = {}) =
        ImportViewModel(importer, library, this, gate)

    @Test fun newSourcesAreReviewedBeforeAnythingIsCommitted() = runTest {
        val library = TestLibrary()
        val vm = importing(FakeImporter(), library)
        vm.choose("content://docs/new")
        advanceUntilIdle()
        assertEquals("content://docs/new", vm.state.value.preview?.sourceUri)
        assertFalse(vm.state.value.busy)
        assertTrue(library.added.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun exactRepeatedSourceReturnsTheExistingItemWithoutPreparing() = runTest {
        val importer = FakeImporter()
        val vm = importing(importer)
        vm.choose("test:book")
        advanceUntilIdle()
        assertEquals("book", vm.state.value.imported?.id)
        assertTrue(importer.requests.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun providersWithoutDurableAccessOfferAnExplicitPrivateCopy() = runTest {
        val importer = FakeImporter()
        importer.prepare = { uri, copy ->
            if (!copy) throw PublicationException(PublicationProblem.NEEDS_COPY)
            PreparedImport(candidate(uri).copy(managedPath = "copy.epub"), acquiredGrant = false)
        }
        val vm = importing(importer)
        vm.choose("content://remote/doc")
        advanceUntilIdle()
        assertTrue(vm.state.value.needsCopy)
        vm.copySource()
        advanceUntilIdle()
        assertEquals(listOf("content://remote/doc" to false, "content://remote/doc" to true), importer.requests)
        assertEquals("copy.epub", vm.state.value.preview?.managedPath)
        vm.viewModelScope.cancel()
    }

    @Test fun failuresReportSpecificProblemsInImportLanguage() = runTest {
        val importer = FakeImporter()
        val vm = importing(importer)
        importer.prepare = { _, _ -> throw PublicationException(PublicationProblem.CORRUPT, "This EPUB has no publication container.") }
        vm.choose("content://docs/damaged"); advanceUntilIdle()
        assertEquals("This EPUB has no publication container.", vm.state.value.error)
        vm.dismiss()
        importer.prepare = { _, _ -> throw SecurityException("revoked") }
        vm.choose("content://docs/revoked"); advanceUntilIdle()
        assertEquals(PublicationProblem.PERMISSION_LOST.importMessage, vm.state.value.error)
        assertNull(vm.state.value.preview)
        vm.viewModelScope.cancel()
    }

    @Test fun cancellingDuringPreparationReturnsToIdleWithoutCommitting() = runTest {
        val importer = FakeImporter()
        val cancelled = CompletableDeferred<Unit>()
        importer.prepare = { _, _ -> try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        val library = TestLibrary()
        val vm = importing(importer, library)
        vm.choose("content://docs/slow"); advanceUntilIdle()
        assertTrue(vm.state.value.busy && vm.state.value.cancellable)
        vm.dismiss(); advanceUntilIdle()
        assertTrue(cancelled.isCompleted)
        assertFalse(vm.state.value.busy)
        assertTrue(library.added.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun dismissingOrReplacingAReviewReleasesWhatWasAcquired() = runTest {
        val importer = FakeImporter()
        val vm = importing(importer)
        vm.choose("content://docs/first"); advanceUntilIdle()
        vm.choose("content://docs/second"); advanceUntilIdle()
        assertEquals(listOf("content://docs/first"), importer.discarded.map { it.first.item.sourceUri })
        vm.dismiss(); advanceUntilIdle()
        assertEquals(listOf("content://docs/first" to false, "content://docs/second" to false),
            importer.discarded.map { it.first.item.sourceUri to it.second })
        vm.viewModelScope.cancel()
    }

    @Test fun confirmingCommitsOnceAndRecordsUserProvenance() = runTest {
        val library = TestLibrary()
        val importer = FakeImporter()
        importer.prepare = { uri, _ -> PreparedImport(candidate(uri, "Embedded Title", "embedded").copy(creator = "Author", creatorOrigin = "embedded"), true) }
        val vm = importing(importer, library)
        vm.choose("content://docs/one"); advanceUntilIdle()
        vm.confirm("Embedded Title", "Author", MediaCategory.MANGA)
        vm.confirm("Embedded Title", "Author", MediaCategory.MANGA)
        advanceUntilIdle()
        val saved = library.added.single()
        assertEquals(MediaCategory.MANGA, saved.category)
        assertEquals("embedded", saved.titleOrigin); assertEquals("embedded", saved.creatorOrigin)
        assertEquals(saved.id, vm.state.value.imported?.id)
        vm.consumed()
        vm.choose("content://docs/two"); advanceUntilIdle()
        vm.confirm("  Edited  ", "", MediaCategory.BOOK); advanceUntilIdle()
        assertEquals("Edited", library.added.last().title)
        assertEquals("user", library.added.last().titleOrigin); assertEquals("unknown", library.added.last().creatorOrigin)
        assertTrue(importer.discarded.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun similarFilesAreFlaggedButNeverMerged() = runTest {
        val library = TestLibrary()
        val importer = FakeImporter()
        importer.prepare = { uri, _ -> PreparedImport(candidate(uri).copy(fileName = "book.epub", byteSize = 100L), true) }
        val vm = importing(importer, library)
        vm.choose("content://elsewhere/book.epub"); advanceUntilIdle()
        assertTrue(vm.state.value.possibleDuplicate)
        vm.confirm("An Original Book", "", MediaCategory.BOOK); advanceUntilIdle()
        assertEquals(4, library.publications.value.size)
        vm.viewModelScope.cancel()
    }

    @Test fun importWaitsForStartupMaintenance() = runTest {
        val importer = FakeImporter()
        val gate = CompletableDeferred<Unit>()
        val vm = importing(importer, gate = { gate.await() })
        vm.choose("content://docs/waiting"); advanceUntilIdle()
        assertTrue(importer.requests.isEmpty())
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, importer.requests.size)
        vm.viewModelScope.cancel()
    }

    // Ownership: a save owns its preparation until the library does.

    /** Codex review R1 regression, kept verbatim. */
    @Test fun clearingViewModelDuringCommitMustNotDiscardCommittedSource() = runTest {
        val base = TestLibrary(emptyList())
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repository = object : LibraryRepository by base {
            override suspend fun add(item: LibraryItem): String {
                entered.complete(Unit)
                finish.await()
                return base.add(item)
            }
        }
        val discarded = mutableListOf<PreparedImport>()
        val item = testItems().first().copy(sourceUri = "content://review/book", managedPath = "review.epub")
        val importer = object : PublicationImporter {
            override suspend fun prepare(sourceUri: String, copy: Boolean, stage: (String) -> Unit) = PreparedImport(item, true)
            override fun discard(prepared: PreparedImport, sourceStillUsed: Boolean) { discarded += prepared }
        }
        val vm = ImportViewModel(importer, repository, this)
        val store = ViewModelStore().apply { put("import", vm) }
        vm.choose(item.sourceUri); advanceUntilIdle()
        vm.confirm(item.title, item.creator, item.category); runCurrent()
        assertTrue(entered.isCompleted)
        store.clear(); runCurrent()
        finish.complete(Unit); advanceUntilIdle()
        assertEquals(item.id, base.added.single().id)
        assertTrue("Committed source was discarded while its non-cancellable insert was pending", discarded.isEmpty())
    }

    @Test fun aFailedSaveReturnsThePreparationToTheReviewForRetry() = runTest {
        val base = TestLibrary(emptyList())
        var failures = 1
        val library = object : LibraryRepository by base {
            override suspend fun add(item: LibraryItem): String {
                if (failures-- > 0) throw IllegalStateException("database unavailable")
                return base.add(item)
            }
        }
        val importer = FakeImporter()
        val vm = ImportViewModel(importer, library, this)
        vm.choose("content://docs/retry"); advanceUntilIdle()
        vm.confirm("Retry", "", MediaCategory.BOOK); advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertEquals("content://docs/retry", vm.state.value.preview?.sourceUri)
        vm.confirm("Retry", "", MediaCategory.BOOK); advanceUntilIdle()
        assertEquals("id-content://docs/retry", vm.state.value.imported?.id)
        assertTrue(importer.discarded.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun aSaveThatFailsAfterItsReviewIsGoneDiscardsThePreparation() = runTest {
        val base = TestLibrary(emptyList())
        val entered = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()
        val library = object : LibraryRepository by base {
            override suspend fun add(item: LibraryItem): String {
                entered.complete(Unit); fail.await(); throw IllegalStateException("database unavailable")
            }
        }
        val importer = FakeImporter()
        val vm = ImportViewModel(importer, library, this)
        val store = ViewModelStore().apply { put("import", vm) }
        vm.choose("content://docs/lost"); advanceUntilIdle()
        vm.confirm("Lost", "", MediaCategory.BOOK); runCurrent()
        assertTrue(entered.isCompleted)
        store.clear(); runCurrent()
        assertTrue("The save still owns its preparation", importer.discarded.isEmpty())
        fail.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("content://docs/lost" to false), importer.discarded.map { it.first.item.sourceUri to it.second })
        assertTrue(base.added.isEmpty())
    }

    @Test fun workTheLibraryAlreadyHoldsIsNeverDiscarded() = runTest {
        // The row is written, then the save still reports a failure; dismissing the review afterwards must not touch it.
        val base = TestLibrary(emptyList())
        val library = object : LibraryRepository by base {
            override suspend fun add(item: LibraryItem): String { base.add(item); throw IllegalStateException("reply lost") }
        }
        val importer = FakeImporter()
        importer.prepare = { uri, _ -> PreparedImport(candidate(uri).copy(managedPath = "kept.epub"), acquiredGrant = true) }
        val vm = ImportViewModel(importer, library, this)
        vm.choose("content://docs/kept"); advanceUntilIdle()
        vm.confirm("Kept", "", MediaCategory.BOOK); advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        vm.dismiss(); advanceUntilIdle()
        assertEquals("kept.epub", base.added.single().managedPath)
        assertTrue(importer.discarded.isEmpty())
        vm.viewModelScope.cancel()
    }

    // Access: abandoned work never releases a grant another import or a library item still needs.

    /** Codex review R2 regression, kept verbatim. */
    @Test fun cancellingThenReimportingSameSourceMustRetainItsGrant() = runTest {
        val base = TestLibrary(emptyList())
        var grant = false
        val firstDiscard = CompletableDeferred<Unit>()
        val allowDiscard = CompletableDeferred<Unit>()
        var reads = 0
        val repository = object : LibraryRepository by base {
            override val publications get() = kotlinx.coroutines.flow.flow {
                reads++
                if (reads == 3) { firstDiscard.complete(Unit); allowDiscard.await() }
                emit(base.publications.value)
            }
        }
        val item = testItems().first().copy(sourceUri = "content://review/book")
        val importer = object : PublicationImporter {
            override suspend fun prepare(sourceUri: String, copy: Boolean, stage: (String) -> Unit): PreparedImport {
                val acquired = !grant; grant = true
                return PreparedImport(item, acquired)
            }
            override fun discard(prepared: PreparedImport, sourceStillUsed: Boolean) {
                if (prepared.acquiredGrant && !sourceStillUsed) grant = false
            }
        }
        val vm = ImportViewModel(importer, repository, this)
        val store = ViewModelStore().apply { put("import", vm) }
        vm.choose(item.sourceUri); advanceUntilIdle()
        vm.dismiss(); runCurrent()
        assertTrue(firstDiscard.isCompleted)
        vm.choose(item.sourceUri); runCurrent()
        assertNotNull(vm.state.value.preview)
        allowDiscard.complete(Unit); advanceUntilIdle()
        vm.confirm(item.title, item.creator, item.category); advanceUntilIdle()
        store.clear(); advanceUntilIdle()
        assertEquals(1, base.added.size)
        assertTrue("Superseded review released the grant needed by the replacement import", grant)
    }

    /** One document's persisted grant, taken and released the way [com.d4guilar.shelfos.core.files.PublicationFiles] does. */
    private class GrantImporter(private val item: LibraryItem) : PublicationImporter {
        var grant = false
        val discarded = mutableListOf<Pair<PreparedImport, Boolean>>()
        override suspend fun prepare(sourceUri: String, copy: Boolean, stage: (String) -> Unit): PreparedImport {
            val acquired = !grant; grant = true
            return PreparedImport(item, acquired)
        }
        override fun discard(prepared: PreparedImport, sourceStillUsed: Boolean) {
            discarded += prepared to sourceStillUsed
            if (prepared.acquiredGrant && !sourceStillUsed) grant = false
        }
    }

    /** Holds the abandoned import's cleanup at its library check (the third read) until [allow] completes. */
    private fun delayedCleanup(base: TestLibrary, reached: CompletableDeferred<Unit>, allow: CompletableDeferred<Unit>): LibraryRepository {
        var reads = 0
        return object : LibraryRepository by base {
            override val publications get() = flow {
                if (++reads == 3) { reached.complete(Unit); allow.await() }
                emit(base.publications.value)
            }
        }
    }

    @Test fun replacingAReviewWithTheSameSourceKeepsTheGrantTheReplacementNeeds() = runTest {
        val base = TestLibrary(emptyList())
        val item = candidate("content://docs/same")
        val importer = GrantImporter(item)
        val reached = CompletableDeferred<Unit>()
        val allow = CompletableDeferred<Unit>()
        val vm = ImportViewModel(importer, delayedCleanup(base, reached, allow), this)
        vm.choose(item.sourceUri); advanceUntilIdle()
        vm.choose(item.sourceUri); runCurrent()
        assertTrue(reached.isCompleted)
        assertNotNull(vm.state.value.preview)
        allow.complete(Unit); advanceUntilIdle()
        // Only the replaced review's own work goes; its grant stays for the replacement.
        assertEquals(listOf(true), importer.discarded.map { it.second })
        vm.confirm(item.title, item.creator, item.category); advanceUntilIdle()
        assertEquals(1, base.added.size)
        assertTrue(importer.grant)
        vm.viewModelScope.cancel()
    }

    @Test fun aGrantKeptForAReplacementIsReleasedWhenTheReplacementIsAbandonedToo() = runTest {
        val base = TestLibrary(emptyList())
        val item = candidate("content://docs/same")
        val importer = GrantImporter(item)
        val reached = CompletableDeferred<Unit>()
        val allow = CompletableDeferred<Unit>()
        val vm = ImportViewModel(importer, delayedCleanup(base, reached, allow), this)
        vm.choose(item.sourceUri); advanceUntilIdle()
        vm.dismiss(); runCurrent()
        vm.choose(item.sourceUri); runCurrent()
        assertTrue(reached.isCompleted)
        assertNotNull(vm.state.value.preview)
        allow.complete(Unit); advanceUntilIdle()
        assertTrue(importer.grant)
        vm.dismiss(); advanceUntilIdle()
        assertFalse("Nothing depends on the source any more, so its grant must not leak", importer.grant)
        assertTrue(base.added.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun aCancelledPreparationFinishesReleasingBeforeTheSameSourceIsPreparedAgain() = runTest {
        var grant = false
        val released = CompletableDeferred<Unit>()
        val item = candidate("content://docs/slow")
        var calls = 0
        val importer = object : PublicationImporter {
            override suspend fun prepare(sourceUri: String, copy: Boolean, stage: (String) -> Unit): PreparedImport {
                val acquired = !grant; grant = true
                if (++calls == 1) try { awaitCancellation() } finally {
                    // Like PublicationFiles, a failed preparation releases what it acquired; here it takes a while.
                    withContext(NonCancellable) { released.await() }
                    if (acquired) grant = false
                }
                return PreparedImport(item, acquired)
            }
            override fun discard(prepared: PreparedImport, sourceStillUsed: Boolean) {
                if (prepared.acquiredGrant && !sourceStillUsed) grant = false
            }
        }
        val library = TestLibrary(emptyList())
        val vm = ImportViewModel(importer, library, this)
        vm.choose(item.sourceUri); advanceUntilIdle()
        vm.dismiss(); vm.choose(item.sourceUri); runCurrent()
        assertNull(vm.state.value.preview)
        released.complete(Unit); advanceUntilIdle()
        vm.confirm(item.title, item.creator, item.category); advanceUntilIdle()
        assertEquals(1, library.added.size)
        assertTrue("The replacement relied on a grant its cancelled predecessor then released", grant)
        vm.viewModelScope.cancel()
    }
}
