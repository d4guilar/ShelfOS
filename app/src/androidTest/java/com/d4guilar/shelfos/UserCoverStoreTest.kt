// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.reader.CoverExtractor
import com.d4guilar.shelfos.core.reader.ExtractedCover
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
import java.io.IOException
import java.util.Random

/**
 * Phase 4.5F.0 durable USER cover artwork on a real Room database, real files and real bitmap decoding: validated and
 * bounded storage, immutable naming, commit-before-delete replacement, failure leaving the current cover intact,
 * removed-item rejection, storage guard, revoked access, cancellation, precedence over LOCAL/ONLINE, sweep safety and a
 * database reopen. The picked image is a plain file; the store never keeps its path.
 */
class UserCoverStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var files: CoverFiles
    private lateinit var library: RoomLibraryRepository
    private var free = Long.MAX_VALUE
    private var onOpen: (String) -> Unit = {}

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        root = File(context.cacheDir, "user-cover-${System.nanoTime()}")
        source = File(context.cacheDir, "user-cover-src-${System.nanoTime()}").apply { mkdirs() }
        files = CoverFiles(root, { free })
        library = RoomLibraryRepository(db.library(), covers = files)
    }
    @After fun tearDown() { db.close(); root.deleteRecursively(); source.deleteRecursively() }

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private fun repo() = RoomCoverRepository(db.covers(), throttleMillis = 0)
    private fun store(covers: RoomCoverRepository = repo()) = UserCoverStore(covers, files, mutex, { path -> onOpen(path); FileInputStream(path) })
    private suspend fun add(id: String) = library.add(LibraryItem(id, "T $id", category = MediaCategory.BOOK, sourceUri = "test:$id",
        format = PublicationFormat.EPUB, fileName = "$id.epub", byteSize = 1))
    private fun dir(id: String) = File(root, id).list()?.sorted().orEmpty()
    private fun user(id: String) = db.openHelper.readableDatabase.query("SELECT fileName FROM publication_cover WHERE libraryItemId = '$id' AND slot = 'USER'")
        .use { if (it.moveToFirst()) it.getString(0) else null }
    private fun rows(id: String, slot: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM publication_cover WHERE libraryItemId = '$id' AND slot = '$slot'")
        .use { it.moveToFirst(); it.getInt(0) }

    private fun image(name: String, w: Int, h: Int, jpeg: Boolean = true, noise: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (noise) { val r = Random(1); val px = IntArray(w * h) { Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)) }; bitmap.setPixels(px, 0, w, 0, 0, w, h) }
        else bitmap.eraseColor(Color.rgb(200, 30, 30))
        return File(source, name).also { f -> f.outputStream().use { bitmap.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, it) }; bitmap.recycle() }
    }
    private fun localRow(id: String, file: String = "local-00000000000000aa.jpg") = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO publication_cover (libraryItemId, slot, status, fileName, mimeType, representation, storedWidthPx, storedHeightPx, storedBytes, extractorVersion, attempts, interruptions, parked, updatedAt) " +
            "VALUES('$id','LOCAL','READY','$file','image/jpeg','DERIVED',800,1200,100,1,0,0,0,1)")

    @Test fun aChosenImageIsStoredBoundedImmutableAndSurvivesTheSourceDisappearing() = runBlocking<Unit> {
        add("a"); localRow("a")
        val pick = image("pick.jpg", 600, 900)
        val originalBytes = pick.readBytes()
        assertEquals(UserCoverOutcome.SAVED, store().set("a", pick.path))
        val name = user("a")!!
        assertTrue(name.matches(Regex("user-[0-9a-f]{16}\\.jpg")))
        assertEquals(listOf(name), dir("a"))
        assertArrayEquals("the source is never modified", originalBytes, pick.readBytes())
        pick.delete()
        val stored = File(File(root, "a"), name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(stored.path, it) }
        assertEquals(listOf(600, 900), listOf(bounds.outWidth, bounds.outHeight))
        assertEquals("USER outranks LOCAL", CoverRef("a", name, 600, 900), repo().covers.first()["a"])
    }

    @Test fun largeAndHeavyImagesAreReducedWithinTheStorageBounds() = runBlocking<Unit> {
        add("a")
        assertEquals(UserCoverOutcome.SAVED, store().set("a", image("big.png", 3000, 4000, jpeg = false).path))
        val big = File(File(root, "a"), user("a")!!)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(big.path, it) }
        assertTrue(maxOf(o.outWidth, o.outHeight) <= CoverStoragePolicy.MAX_EDGE_PX)
        assertTrue(big.length() <= CoverStoragePolicy.MAX_COVER_BYTES)
        assertEquals(UserCoverOutcome.SAVED, store().set("a", image("noise.png", 1500, 1500, jpeg = false, noise = true).path))
        val noisy = File(File(root, "a"), user("a")!!)
        assertTrue("incompressible art still fits the byte bound: ${noisy.length()}", noisy.length() <= CoverStoragePolicy.MAX_COVER_BYTES)
        assertEquals("one file per item", 1, dir("a").size)
    }

    @Test fun invalidOversizedAndUnsupportedInputsAreRejectedAndLeaveTheCurrentCoverAlone() = runBlocking<Unit> {
        add("a")
        val s = store()
        assertEquals(UserCoverOutcome.SAVED, s.set("a", image("ok.jpg", 400, 600).path))
        val good = user("a")
        val garbage = File(source, "g.jpg").apply { writeBytes(ByteArray(5000) { 3 }) }
        val empty = File(source, "e.jpg").apply { writeBytes(ByteArray(0)) }
        val text = File(source, "t.jpg").apply { writeText("not an image at all") }
        for (bad in listOf(garbage, empty, text)) assertEquals(bad.name, UserCoverOutcome.INVALID_IMAGE, s.set("a", bad.path))
        val tooManyPixels = image("huge.png", 7000, 7000, jpeg = false)       // 49 Mpx: above the 40 Mpx input cap
        assertEquals(UserCoverOutcome.UNSUPPORTED_IMAGE, s.set("a", tooManyPixels.path))
        val tooManyBytes = File(source, "bytes.jpg").apply { outputStream().use { o -> val b = ByteArray(1 shl 20); repeat(33) { o.write(b) } } }
        assertEquals(UserCoverOutcome.UNSUPPORTED_IMAGE, s.set("a", tooManyBytes.path))
        assertEquals("every failure kept the previous artwork", good, user("a"))
        assertEquals("and left no temporary or half-written file", listOf(good), dir("a"))
    }

    @Test fun replacementKeepsExactlyOneUserFileAndDeletesTheOldOnlyAfterTheCommit() = runBlocking<Unit> {
        add("a"); val s = store()
        s.set("a", image("1.jpg", 300, 500).path); val first = user("a")!!
        onOpen = { assertTrue("the old cover is intact while the new one is prepared", File(File(root, "a"), first).isFile && user("a") == first) }
        assertEquals(UserCoverOutcome.SAVED, s.set("a", image("2.png", 500, 300, jpeg = false).path))
        val second = user("a")!!
        onOpen = {}
        assertNotEquals(first, second)
        assertEquals(listOf(second), dir("a")); assertEquals(1, rows("a", "USER"))
        repeat(5) { s.set("a", image("r$it.jpg", 200 + it, 300).path) }
        assertEquals("repeated replacements leave no orphans", 1, dir("a").size)
    }

    @Test fun aMissingItemRejectsTheWriteAndCreatesNothing() = runBlocking<Unit> {
        assertEquals(UserCoverOutcome.ITEM_GONE, store().set("nope", image("x.jpg", 100, 100).path))
        assertFalse(File(root, "nope").exists())
        assertEquals(0, rows("nope", "USER"))
    }

    @Test fun anItemRemovedWhileTheImageIsBeingReadIsNeverRevived() = runBlocking<Unit> {
        add("a")
        onOpen = { runBlocking { library.remove("a") } }
        assertEquals(UserCoverOutcome.ITEM_GONE, store().set("a", image("x.jpg", 100, 100).path))
        assertEquals(0, rows("a", "USER"))
        assertTrue("no artwork or directory is left behind", File(root, "a").list().isNullOrEmpty())
    }

    @Test fun lowStorageFailsBeforeAnyWorkAndKeepsTheCurrentCover() = runBlocking<Unit> {
        add("a"); val s = store()
        s.set("a", image("1.jpg", 300, 500).path); val good = user("a")
        free = 100L * 1024 * 1024
        assertEquals(UserCoverOutcome.STORAGE_FULL, s.set("a", image("2.jpg", 300, 500).path))
        assertEquals(good, user("a")); assertEquals(listOf(good), dir("a"))
        free = Long.MAX_VALUE
        assertEquals(UserCoverOutcome.SAVED, s.set("a", image("3.jpg", 300, 500).path))
    }

    @Test fun revokedOrVanishedSourcesAreReportedWithoutChangingAnything() = runBlocking<Unit> {
        add("a")
        for (failure in listOf<() -> Nothing>({ throw SecurityException("grant revoked") }, { throw IOException("gone") }, { throw java.io.FileNotFoundException() })) {
            val s = UserCoverStore(repo(), files, mutex, { failure() })
            assertEquals(UserCoverOutcome.UNREADABLE, s.set("a", "content://x"))
        }
        assertEquals(UserCoverOutcome.UNREADABLE, UserCoverStore(repo(), files, mutex, { null }).set("a", "content://x"))
        assertEquals(0, rows("a", "USER")); assertTrue(dir("a").isEmpty())
    }

    @Test fun cancellationDuringTheReadCommitsNothingAndLeavesNoFiles() = runBlocking<Unit> {
        add("a"); val pick = image("x.jpg", 300, 300)
        val started = CompletableDeferred<Unit>()
        onOpen = { started.complete(Unit); Thread.sleep(400) }
        val job = launch(Dispatchers.Default) { store().set("a", pick.path) }
        started.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, rows("a", "USER")); assertTrue(dir("a").isEmpty())
        onOpen = {}
        assertEquals("the store is usable afterwards", UserCoverOutcome.SAVED, store().set("a", pick.path))
    }

    @Test fun removingTheCustomCoverRestoresLocalThenGeneratedAndDeletesOnlyTheUserFile() = runBlocking<Unit> {
        add("a"); localRow("a"); val s = store()
        s.set("a", image("1.jpg", 300, 500).path); val name = user("a")!!
        assertTrue(s.remove("a"))
        assertNull(user("a")); assertFalse(File(File(root, "a"), name).exists())
        assertEquals("the file cover is back", "local-00000000000000aa.jpg", repo().covers.first()["a"]!!.fileName)
        assertEquals(1, rows("a", "LOCAL"))
        assertFalse("nothing to remove twice", s.remove("a"))
        add("b"); s.set("b", image("2.jpg", 300, 500).path); s.remove("b")
        assertNull("no LOCAL cover: the generated cover shows", repo().covers.first()["b"])
    }

    @Test fun userArtworkOutranksLocalAndOnlineAndALaterLocalExtractionNeverReplacesIt() = runBlocking<Unit> {
        add("a"); val s = store(); s.set("a", image("1.jpg", 300, 500).path); val name = user("a")!!
        db.openHelper.writableDatabase.execSQL("INSERT INTO publication_cover (libraryItemId, slot, status, fileName, storedWidthPx, storedHeightPx, extractorVersion, attempts, interruptions, parked, updatedAt) VALUES('a','ONLINE','READY','online-00000000000000bb.jpg',900,1400,0,0,0,0,1)")
        assertEquals(name, repo().covers.first()["a"]!!.fileName)
        val extractor = CoverExtractor { _, out, _ -> out.writeBytes(ByteArray(900) { 7 }).let {
            ExtractedCover("image/jpeg", "jpg", CoverRepresentation.DERIVED, 2000, 3000, 800, 1200, 900, CoverLocalSource.ARCHIVE_FIRST_PAGE, "page:0", CoverSourceChoice.INFERRED, null) } }
        val scheduler = CoverScheduler(repo(), files, { id -> library.publication(id).first() }, extractor, idleDelayMillis = 0, cbrPauseMillis = 0, pollMillis = 50)
        assertNotNull(scheduler.attempt("a"))
        assertEquals(1, rows("a", "LOCAL"))
        assertEquals("a background extraction cannot replace the user's cover", name, repo().covers.first()["a"]!!.fileName)
    }

    @Test fun theStartupSweepNeverDeletesReferencedUserArtworkButStillCleansStrays() = runBlocking<Unit> {
        add("a"); val s = store(); s.set("a", image("1.jpg", 300, 500).path); val name = user("a")!!
        File(File(root, "a"), "user-ffffffffffffffff.jpg").writeBytes(ByteArray(10))     // finalized but never committed
        File(File(root, "a"), "0123456789abcdef.tmp").writeBytes(ByteArray(10))
        val scheduler = CoverScheduler(repo(), files, { id -> library.publication(id).first() }, CoverExtractor { _, _, _ -> throw IOException() },
            idleDelayMillis = 0, cbrPauseMillis = 0, pollMillis = 50)
        scheduler.reconcileAndSweep()
        assertEquals(listOf(name), dir("a"))
    }

    @Test fun concurrentChoicesSerializeAndLeaveOneConsistentCover() = runBlocking<Unit> {
        add("a"); val s = store()
        val picks = (1..4).map { image("c$it.jpg", 200 + it * 10, 300).path }
        picks.map { p -> async(Dispatchers.Default) { s.set("a", p) } }.awaitAll().forEach { assertEquals(UserCoverOutcome.SAVED, it) }
        assertEquals(1, rows("a", "USER")); assertEquals(listOf(user("a")), dir("a"))
    }

    @Test fun theChosenCoverIsStillThereAfterTheDatabaseIsReopened() = runBlocking<Unit> {
        val dbFile = File(context.cacheDir, "reopen-${System.nanoTime()}.db")
        var disk = Room.databaseBuilder(context, ShelfDatabase::class.java, dbFile.path).build()
        try {
            RoomLibraryRepository(disk.library(), covers = files).add(LibraryItem("a", "T", category = MediaCategory.BOOK, sourceUri = "test:a",
                format = PublicationFormat.EPUB, fileName = "a.epub", byteSize = 1))
            UserCoverStore(RoomCoverRepository(disk.covers(), throttleMillis = 0), files, mutex, { FileInputStream(it) }).set("a", image("1.jpg", 300, 500).path)
            val name = disk.openHelper.readableDatabase.query("SELECT fileName FROM publication_cover WHERE slot='USER'").use { it.moveToFirst(); it.getString(0) }
            disk.close()
            disk = Room.databaseBuilder(context, ShelfDatabase::class.java, dbFile.path).build()
            val ref = RoomCoverRepository(disk.covers(), throttleMillis = 0).covers.first()["a"]!!
            assertEquals(name, ref.fileName)
            assertTrue(files.file("a", ref.fileName).isFile)
        } finally { disk.close(); dbFile.delete() }
    }
}
