// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.data.library.*
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs

/**
 * Phase 4.5F.0 F-1: a user-chosen JPEG with an EXIF orientation is stored upright. Each fixture is four solid quadrants
 * (stored top-left red, top-right green, bottom-left blue, bottom-right yellow) tagged with an orientation; the stored
 * artwork is decoded WITHOUT reading EXIF and each quadrant is checked at the position the EXIF definition says it
 * must appear, so a wrong rotation direction or a missing mirror fails, not just swapped dimensions.
 */
class UserCoverExifTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: ShelfDatabase
    private lateinit var root: File
    private lateinit var source: File
    private lateinit var files: CoverFiles
    private val mutex = Mutex()
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)   // TL, TR, BL, BR in the stored pixels

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, ShelfDatabase::class.java).build()
        root = File(context.cacheDir, "exif-${System.nanoTime()}")
        source = File(context.cacheDir, "exif-src-${System.nanoTime()}").apply { mkdirs() }
        files = CoverFiles(root, { Long.MAX_VALUE })
    }
    @After fun tearDown() { db.close(); root.deleteRecursively(); source.deleteRecursively() }

    private fun store(orientationOf: ((File) -> Int)? = null) = UserCoverStore(RoomCoverRepository(db.covers(), throttleMillis = 0), files, mutex,
        { FileInputStream(it) }, orientationOf = orientationOf ?: com.d4guilar.shelfos.core.reader.CoverImageWriter::exifOrientation)
    private suspend fun add(id: String) = RoomLibraryRepository(db.library(), covers = files).add(LibraryItem(id, "T $id", category = MediaCategory.BOOK,
        sourceUri = "test:$id", format = PublicationFormat.EPUB, fileName = "$id.epub", byteSize = 1))
    private fun userRow(id: String) = db.openHelper.readableDatabase.query(
        "SELECT fileName, representation, sourceWidthPx, sourceHeightPx, storedWidthPx, storedHeightPx, storedBytes FROM publication_cover WHERE libraryItemId='$id' AND slot='USER'")
        .use { if (it.moveToFirst()) listOf(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3), it.getInt(4), it.getInt(5), it.getLong(6)) else null }

    private fun quadrants(name: String, w: Int, h: Int, orientation: Int?, png: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap); val paint = android.graphics.Paint()
        listOf(Triple(0, 0, 0), Triple(w / 2, 0, 1), Triple(0, h / 2, 2), Triple(w / 2, h / 2, 3)).forEach { (x, y, i) ->
            paint.color = colors[i]; canvas.drawRect(x.toFloat(), y.toFloat(), (x + w / 2).toFloat(), (y + h / 2).toFloat(), paint)
        }
        val f = File(source, name)
        f.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        if (orientation != null) ExifInterface(f.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        return f
    }

    /** Where a stored pixel (x, y) of a w x h image appears once the EXIF orientation is applied (EXIF definition, independent of the Matrix code). */
    private fun visual(o: Int, x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> = when (o) {
        1 -> x to y; 2 -> (w - 1 - x) to y; 3 -> (w - 1 - x) to (h - 1 - y); 4 -> x to (h - 1 - y)
        5 -> y to x; 6 -> (h - 1 - y) to x; 7 -> (h - 1 - y) to (w - 1 - x); else -> y to (w - 1 - x)
    }

    private fun close(expected: Int, actual: Int) = abs(Color.red(expected) - Color.red(actual)) < 70 &&
        abs(Color.green(expected) - Color.green(actual)) < 70 && abs(Color.blue(expected) - Color.blue(actual)) < 70

    /** Decodes the stored file as raw pixels (no EXIF handling) and asserts every quadrant is where [o] says. */
    private fun assertUpright(id: String, o: Int, w: Int, h: Int) {
        val row = userRow(id)!!
        val bitmap = BitmapFactory.decodeFile(File(File(root, id), row[0] as String).path)!!
        try {
            val swapped = o >= 5
            val vw = if (swapped) h else w; val vh = if (swapped) w else h
            assertTrue("aspect of $o: ${bitmap.width}x${bitmap.height} for ${vw}x$vh", abs(bitmap.width.toDouble() / bitmap.height - vw.toDouble() / vh) < 0.02)
            listOf(w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4).forEachIndexed { i, (sx, sy) ->
                val (vx, vy) = visual(o, sx, sy, w, h)
                val px = bitmap.getPixel((vx * bitmap.width / vw).coerceIn(0, bitmap.width - 1), (vy * bitmap.height / vh).coerceIn(0, bitmap.height - 1))
                assertTrue("orientation $o quadrant $i expected ${Integer.toHexString(colors[i])} got ${Integer.toHexString(px)}", close(colors[i], px))
            }
            assertTrue(maxOf(bitmap.width, bitmap.height) <= CoverStoragePolicy.MAX_EDGE_PX)
            assertTrue((row[6] as Long) <= CoverStoragePolicy.MAX_COVER_BYTES)
        } finally { bitmap.recycle() }
    }

    @Test fun orientation6LandscapePixelsBecomeAnUprightPortraitCoverThatSurvivesReopeningTheDatabase() = runBlocking<Unit> {
        val dbFile = File(context.cacheDir, "exif-reopen-${System.nanoTime()}.db")
        var disk = Room.databaseBuilder(context, ShelfDatabase::class.java, dbFile.path).build()
        try {
            RoomLibraryRepository(disk.library(), covers = files).add(LibraryItem("a", "T", category = MediaCategory.BOOK, sourceUri = "test:a",
                format = PublicationFormat.EPUB, fileName = "a.epub", byteSize = 1))
            val pick = quadrants("p.jpg", 3200, 2400, 6)
            val before = pick.readBytes()
            assertEquals(UserCoverOutcome.SAVED, UserCoverStore(RoomCoverRepository(disk.covers(), throttleMillis = 0), files, mutex, { FileInputStream(it) }).set("a", pick.path))
            assertArrayEquals("the source is untouched", before, pick.readBytes())
            disk.close()
            disk = Room.databaseBuilder(context, ShelfDatabase::class.java, dbFile.path).build()
            val ref = RoomCoverRepository(disk.covers(), throttleMillis = 0).covers.first()["a"]!!
            assertTrue(ref.fileName.startsWith("user-"))
            assertEquals(listOf(900, 1200), listOf(ref.widthPx, ref.heightPx))
            val bitmap = BitmapFactory.decodeFile(files.file("a", ref.fileName).path)!!
            assertEquals("portrait, about 900 x 1200", listOf(900, 1200), listOf(bitmap.width, bitmap.height))
            // 6 = rotate 90 clockwise: stored red (TL) is now top-right, green (TR) bottom-right, blue (BL) top-left, yellow (BR) bottom-left.
            fun at(x: Float, y: Float) = bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt())
            assertTrue(close(Color.BLUE, at(0.25f, 0.25f))); assertTrue(close(Color.RED, at(0.75f, 0.25f)))
            assertTrue(close(Color.YELLOW, at(0.25f, 0.75f))); assertTrue(close(Color.GREEN, at(0.75f, 0.75f)))
            val row = disk.openHelper.readableDatabase.query("SELECT representation, sourceWidthPx, sourceHeightPx FROM publication_cover WHERE slot='USER'").use { it.moveToFirst(); listOf(it.getString(0), it.getInt(1), it.getInt(2)) }
            assertEquals(listOf("DERIVED", 2400, 3200), row)
            bitmap.recycle()
        } finally { disk.close(); dbFile.delete() }
    }

    @Test fun allEightOrientationsLandUprightWithCorrectRotationAndMirroring() = runBlocking<Unit> {
        for (o in 1..8) {
            val id = "o$o"; add(id)
            assertEquals("orientation $o", UserCoverOutcome.SAVED, store().set(id, quadrants("o$o.jpg", 1600, 1200, o).path))
            assertEquals("DERIVED", userRow(id)!![1])
            assertUpright(id, o, 1600, 1200)
        }
    }

    @Test fun aSmallTaggedJpegIsNormalizedInsteadOfCopiedWithItsTag() = runBlocking<Unit> {
        add("a")
        val pick = quadrants("small.jpg", 600, 400, 6)
        assertTrue("small enough for the byte-exact path", pick.length() < CoverStoragePolicy.MAX_COVER_BYTES)
        assertEquals(UserCoverOutcome.SAVED, store().set("a", pick.path))
        assertEquals("DERIVED", userRow("a")!![1])
        val stored = File(File(root, "a"), userRow("a")!![0] as String)
        assertTrue("no orientation tag is carried into the stored file", ExifInterface(stored.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 0..1)
        assertUpright("a", 6, 600, 400)
    }

    @Test fun normalAndUntaggedImagesKeepTheOriginalCopyOptimization() = runBlocking<Unit> {
        for ((i, f) in listOf(quadrants("n.jpg", 600, 400, null), quadrants("one.jpg", 600, 400, 1), quadrants("n.png", 600, 400, null, png = true)).withIndex()) {
            val id = "n$i"; add(id)
            assertEquals(UserCoverOutcome.SAVED, store().set(id, f.path))
            val row = userRow(id)!!
            assertEquals(f.name, "ORIGINAL", row[1])
            assertEquals(listOf(600, 400), listOf(row[4], row[5]))
            assertArrayEquals("byte-exact", f.readBytes(), File(File(root, id), row[0] as String).readBytes())
        }
    }

    @Test fun aFailureWhileHandlingOrientationKeepsThePreviousCoverAndLeavesNoArtifacts() = runBlocking<Unit> {
        add("a")
        assertEquals(UserCoverOutcome.SAVED, store().set("a", quadrants("ok.jpg", 600, 400, null).path))
        val good = userRow("a")!!
        val pick = quadrants("bad.jpg", 600, 400, 6); val before = pick.readBytes()
        assertEquals(UserCoverOutcome.FAILED, store { throw IllegalStateException("exif failure") }.set("a", pick.path))
        assertEquals("no new row or file", good, userRow("a")); assertEquals(listOf(good[0]), File(root, "a").list()!!.toList())
        assertArrayEquals(before, pick.readBytes())
    }

    @Test fun malformedExifNeverCrashesAndIsTreatedAsNormal() = runBlocking<Unit> {
        add("a")
        val pick = quadrants("m.jpg", 600, 400, 6)
        val bytes = pick.readBytes()
        val exifAt = (0 until bytes.size - 4).first { bytes[it] == 'E'.code.toByte() && bytes[it + 1] == 'x'.code.toByte() && bytes[it + 2] == 'i'.code.toByte() }
        for (i in exifAt + 6 until exifAt + 40) bytes[i] = 0x7F        // destroy the TIFF header and first IFD entries
        pick.writeBytes(bytes)
        assertEquals(UserCoverOutcome.SAVED, store().set("a", pick.path))
        assertEquals(1, File(root, "a").list()!!.size)
        assertEquals(1, com.d4guilar.shelfos.core.reader.CoverImageWriter.exifOrientation(File(source, "does-not-exist.jpg")))
    }
}
