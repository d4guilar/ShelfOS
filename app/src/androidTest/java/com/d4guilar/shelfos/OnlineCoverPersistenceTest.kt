// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.data.library.*
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * Phase 4.5C.3A: a downloaded, app-private image becomes the durable USER cover through [UserCoverStore.setFromPrivateFile] on a real Room database
 * and real files: USER slot (never ONLINE), provenance committed atomically with the file, atomic replacement, an untouched old cover on every
 * failure, supersession by a newer choice, the revoked-consent guard, source-path confinement and persistence across a repository reopen.
 */
class OnlineCoverPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase
    private lateinit var root: File
    private lateinit var staging: File
    private lateinit var outside: File
    private lateinit var files: CoverFiles
    private lateinit var library: RoomLibraryRepository
    private var free = Long.MAX_VALUE
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val origin = CoverOrigin(CoverOriginKind.PROVIDER_SELECTED, "openlibrary", "OL1M", "id", "101", 5_000)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        val base = File(context.cacheDir, "online-cover-${System.nanoTime()}")
        root = File(base, "covers"); staging = File(base, "staging").apply { mkdirs() }; outside = File(base, "outside").apply { mkdirs() }
        files = CoverFiles(root, { free })
        library = RoomLibraryRepository(db.library(), covers = files)
    }
    @After fun tearDown() { db.close(); root.parentFile!!.deleteRecursively() }

    private fun repo() = RoomCoverRepository(db.covers(), throttleMillis = 0)
    private fun store(covers: RoomCoverRepository = repo()) = UserCoverStore(covers, files, mutex, { FileInputStream(it) }, stagingRoot = staging)
    private suspend fun add(id: String) = library.add(LibraryItem(id, "T $id", category = MediaCategory.BOOK, sourceUri = "test:$id", format = PublicationFormat.EPUB, fileName = "$id.epub", byteSize = 1))
    private fun jpeg(dir: File, name: String, w: Int = 400, h: Int = 600, color: Int = Color.rgb(200, 30, 30)): File {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }
        return File(dir, name).also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }; b.recycle() }
    }
    private fun user(id: String) = db.openHelper.readableDatabase.query("SELECT fileName FROM publication_cover WHERE libraryItemId = '$id' AND slot = 'USER'").use { if (it.moveToFirst()) it.getString(0) else null }
    private fun count(slot: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM publication_cover WHERE slot = '$slot'").use { it.moveToFirst(); it.getInt(0) }
    private fun dir(id: String) = File(root, id).list()?.sorted().orEmpty()

    @Test fun anOnlineChoiceIsStoredInTheUserSlotWithProvenanceAndSurvivesAReopen() = runBlocking<Unit> {
        add("a")
        val downloaded = jpeg(staging, "dl.tmp")
        val s = store()
        assertEquals(UserCoverOutcome.SAVED, s.setFromPrivateFile("a", downloaded, origin, s.epoch("a")) { true })
        val name = user("a")!!
        assertTrue(name.matches(Regex("user-[0-9a-f]{16}\\.[a-z]{3,4}")))
        assertEquals(0, count("ONLINE")); assertEquals(0, count("LOCAL")); assertEquals(1, count("USER"))
        assertEquals(listOf(name), dir("a"))
        assertTrue("the staged file belongs to its owner, not the store", downloaded.isFile)
        // A new repository over the same database (a restart) reads the same origin, and the file reopens offline.
        val reopened = repo().userOrigin("a")!!
        assertEquals(CoverOriginKind.PROVIDER_SELECTED, reopened.kind)
        assertEquals(listOf("openlibrary", "OL1M", "id", "101"), listOf(reopened.providerId, reopened.editionRef, reopened.refKind, reopened.refValue))
        assertEquals(5_000L, reopened.selectedAtMs)
        assertNotNull(android.graphics.BitmapFactory.decodeFile(files.file("a", name).path))
        assertTrue(repo().hasUser("a").first())
    }

    @Test fun aDeviceChoiceIsMarkedUserSuppliedAndAReplacementIsAtomic() = runBlocking<Unit> {
        add("a")
        val pick = jpeg(outside, "pick.jpg", color = Color.BLUE)
        val s = store()
        assertEquals(UserCoverOutcome.SAVED, s.set("a", pick.path))
        val first = user("a")!!
        assertEquals(CoverOriginKind.USER_SUPPLIED, repo().userOrigin("a")!!.kind)
        assertEquals(UserCoverOutcome.SAVED, s.setFromPrivateFile("a", jpeg(staging, "dl.tmp"), origin, s.epoch("a")) { true })
        val second = user("a")!!
        assertNotEquals(first, second)
        assertEquals("the previous file is deleted only after the new one committed", listOf(second), dir("a"))
        assertEquals(CoverOriginKind.PROVIDER_SELECTED, repo().userOrigin("a")!!.kind)
    }

    @Test fun aFailedReplacementLeavesTheOldCoverAndItsOriginIntact() = runBlocking<Unit> {
        add("a")
        val s = store()
        assertEquals(UserCoverOutcome.SAVED, s.set("a", jpeg(outside, "pick.jpg").path))
        val old = user("a")!!
        val garbage = File(staging, "bad.tmp").apply { writeBytes(ByteArray(64) { 7 }) }
        assertEquals(UserCoverOutcome.INVALID_IMAGE, s.setFromPrivateFile("a", garbage, origin, s.epoch("a")) { true })
        free = 1024
        assertEquals(UserCoverOutcome.STORAGE_FULL, s.setFromPrivateFile("a", jpeg(staging, "dl.tmp"), origin, s.epoch("a")) { true })
        free = Long.MAX_VALUE
        assertEquals(old, user("a")); assertEquals(listOf(old), dir("a"))
        assertEquals("the old cover keeps its own origin", CoverOriginKind.USER_SUPPLIED, repo().userOrigin("a")!!.kind)
    }

    @Test fun anOlderOnlineSelectionCannotOverwriteANewerChoice() = runBlocking<Unit> {
        add("a")
        val s = store()
        val started = s.epoch("a")                                   // the online selection began here
        assertEquals(UserCoverOutcome.SAVED, s.set("a", jpeg(outside, "pick.jpg").path))   // the person then chose from the device
        val chosen = user("a")!!
        assertEquals(UserCoverOutcome.SUPERSEDED, s.setFromPrivateFile("a", jpeg(staging, "dl.tmp"), origin, started) { true })
        assertEquals(chosen, user("a"))
        // Two online selections that started together: only the first lands.
        val e = s.epoch("a")
        assertEquals(UserCoverOutcome.SAVED, s.setFromPrivateFile("a", jpeg(staging, "one.tmp"), origin, e) { true })
        assertEquals(UserCoverOutcome.SUPERSEDED, s.setFromPrivateFile("a", jpeg(staging, "two.tmp"), origin.copy(refValue = "202"), e) { true })
        assertEquals("101", repo().userOrigin("a")!!.refValue)
        // Removing the cover also supersedes an in-flight selection.
        val f = s.epoch("a"); s.remove("a")
        assertEquals(UserCoverOutcome.SUPERSEDED, s.setFromPrivateFile("a", jpeg(staging, "three.tmp"), origin, f) { true })
        assertNull(user("a"))
    }

    @Test fun revokedConsentCommitsNothing() = runBlocking<Unit> {
        add("a")
        val s = store()
        assertEquals(UserCoverOutcome.NOT_PERMITTED, s.setFromPrivateFile("a", jpeg(staging, "dl.tmp"), origin, s.epoch("a")) { false })
        var calls = 0
        // Allowed when the work starts, withdrawn before the commit.
        assertEquals(UserCoverOutcome.NOT_PERMITTED, s.setFromPrivateFile("a", jpeg(staging, "dl2.tmp"), origin, s.epoch("a")) { ++calls < 2 })
        assertNull(user("a")); assertEquals(0, count("USER")); assertTrue("no orphan file remains", dir("a").isEmpty())
    }

    @Test fun aRemovedBookCannotReceiveACoverAndNothingIsRevived() = runBlocking<Unit> {
        add("a"); library.remove("a")
        val s = store()
        assertEquals(UserCoverOutcome.ITEM_GONE, s.setFromPrivateFile("a", jpeg(staging, "dl.tmp"), origin, s.epoch("a")) { true })
        assertEquals(0, count("USER")); assertTrue(dir("a").isEmpty())
    }

    @Test fun onlyFilesInTheStagingAreaAreAccepted() = runBlocking<Unit> {
        add("a")
        val s = store()
        val elsewhere = jpeg(outside, "pick.jpg")
        assertEquals(UserCoverOutcome.UNREADABLE, s.setFromPrivateFile("a", elsewhere, origin, s.epoch("a")) { true })
        assertEquals(UserCoverOutcome.UNREADABLE, s.setFromPrivateFile("a", File(staging, "../outside/pick.jpg"), origin, s.epoch("a")) { true })
        assertEquals(UserCoverOutcome.UNREADABLE, UserCoverStore(repo(), files, mutex, { FileInputStream(it) }).setFromPrivateFile("a", jpeg(staging, "x.tmp"), origin, 0) { true })
        assertNull(user("a")); assertTrue(elsewhere.isFile)
    }

    @Test fun removingTheOverrideRevealsTheNextEligibleCover() = runBlocking<Unit> {
        add("a")
        db.openHelper.writableDatabase.execSQL("INSERT INTO publication_cover (libraryItemId, slot, status, fileName, mimeType, representation, storedWidthPx, storedHeightPx, storedBytes, extractorVersion, attempts, interruptions, parked, updatedAt) " +
            "VALUES('a','LOCAL','READY','local-00000000000000aa.jpg','image/jpeg','DERIVED',800,1200,100,1,0,0,0,1)")
        val r = repo(); val s = store(r)
        assertEquals(UserCoverOutcome.SAVED, s.setFromPrivateFile("a", jpeg(staging, "dl.tmp"), origin, s.epoch("a")) { true })
        assertEquals(user("a"), r.covers.first().getValue("a").fileName)
        assertTrue(s.remove("a"))
        assertEquals("the LOCAL cover shows again", "local-00000000000000aa.jpg", r.covers.first().getValue("a").fileName)
        assertNull(r.userOrigin("a"))
    }
}
