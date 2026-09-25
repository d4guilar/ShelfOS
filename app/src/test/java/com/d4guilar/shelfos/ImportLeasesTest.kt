// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.domain.importing.ImportLeases
import com.d4guilar.shelfos.domain.importing.PreparedImport
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ImportLeasesTest {
    private val leases = ImportLeases()
    private val discarded = mutableListOf<Pair<PreparedImport, Boolean>>()
    private fun discard(prepared: PreparedImport, stillUsed: Boolean) { discarded += prepared to stillUsed }

    private fun work(acquired: Boolean = true, copy: String? = "copy.epub") = PreparedImport(LibraryItem("id-$copy", "Title", "",
        MediaCategory.BOOK, SOURCE, PublicationFormat.EPUB, "title.epub", 1L, managedPath = copy), acquired)

    @Test fun abandonedWorkReleasesItsGrantOnlyWhenNothingDependsOnTheSource() {
        leases.hold(SOURCE)
        val abandoned = leases.end(SOURCE, work())!!
        leases.dispose(abandoned, referenced = true, ::discard)
        leases.dispose(abandoned, referenced = false, ::discard)
        assertEquals("A library item keeps it; then nothing does", listOf(true, false), discarded.map { it.second })
    }

    @Test fun aGrantStillNeededPassesToTheLastImportHoldingTheSource() {
        leases.hold(SOURCE); leases.hold(SOURCE) // An abandoned review and its replacement.
        val first = leases.end(SOURCE, work())!!
        leases.dispose(first, referenced = false, ::discard)
        assertEquals(first to true, discarded.single()) // Its private copy goes; its grant stays.
        // The replacement found the grant, so it acquired none; ending without committing, it releases the inherited one.
        val last = leases.end(SOURCE, work(acquired = false, copy = "second.epub"))!!
        assertTrue(last.acquiredGrant)
        assertEquals("second.epub", last.item.managedPath)
        leases.dispose(last, referenced = false, ::discard)
        assertFalse(discarded.last().second)
    }

    @Test fun anImportEndingWithoutWorkStillReleasesTheGrantItInherited() {
        leases.hold(SOURCE); leases.hold(SOURCE)
        leases.dispose(leases.end(SOURCE, work())!!, referenced = false, ::discard)
        val inherited = leases.end(SOURCE, null)!! // The replacement's preparation failed.
        assertTrue(inherited.acquiredGrant)
        assertNull("Its private copy was already discarded", inherited.item.managedPath)
    }

    @Test fun committingHandsAnInheritedGrantToTheLibrary() {
        leases.hold(SOURCE); leases.hold(SOURCE)
        leases.dispose(leases.end(SOURCE, work())!!, referenced = false, ::discard)
        leases.committed(SOURCE)
        leases.hold(SOURCE)
        assertNull(leases.end(SOURCE, null))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun importsStartedDuringAReleaseWaitForItToFinish() = runTest {
        var waiting: Job? = null
        leases.dispose(work(), referenced = false) { _, stillUsed ->
            assertFalse(stillUsed)
            // A new import of the same source starts while the grant is being released.
            leases.hold(SOURCE)
            waiting = launch { leases.awaitRelease(SOURCE) }
            runCurrent()
            assertTrue(waiting!!.isActive)
        }
        runCurrent()
        assertTrue(waiting!!.isCompleted)
    }

    private companion object { const val SOURCE = "content://docs/source" }
}
