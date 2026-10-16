// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.data.metadata.LocalMetadataAnalyzer
import com.d4guilar.shelfos.data.metadata.LocalMetadataEvidence
import com.d4guilar.shelfos.data.metadata.RoomMetadataRepository
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.MetadataValues
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Phase 4.5B L-3 on real SQLite: 1,100 items whose source cannot be read come first, then one readable item. The keyset
 * traversal reaches the readable item without a bind-variable error (the previous `NOT IN (:failed)` list would exceed
 * SQLite's 999-variable limit on API 24), visits every item exactly once, and then stops instead of retrying.
 */
class MetadataBackfillLimitTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShelfDatabase::class.java).build()
    @After fun close() { db.close() }

    @Test fun moreThanAThousandFailedItemsNeverBlockALaterEligibleItem() = runBlocking<Unit> {
        val sql = db.openHelper.writableDatabase
        sql.beginTransaction()
        try {
            for (i in 0 until 1_100) sql.execSQL("INSERT INTO library_item VALUES('bad-$i', 'test:bad-$i', 'Bad $i', '', 'BOOK', 'EPUB', 'bad-$i.epub', 1, 0, ${10_000 - i}, 1, NULL, 'filename', 'unknown')")
            sql.execSQL("INSERT INTO library_item VALUES('good', 'test:good', 'Good', '', 'COMIC', 'CBZ', 'Saga #002.cbz', 1, 0, 1, 1, NULL, 'filename', 'unknown')")
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        val repo = RoomMetadataRepository(db)
        val reads = HashMap<String, Int>()
        val analyzer = LocalMetadataAnalyzer(repo, { id ->
            val good = id == "good"
            LibraryItem(id, id, category = if (good) MediaCategory.COMIC else MediaCategory.BOOK, sourceUri = "test:$id",
                format = if (good) PublicationFormat.CBZ else PublicationFormat.EPUB, fileName = if (good) "Saga #002.cbz" else "$id.epub", byteSize = 1)
        }) { item ->
            reads.merge(item.id, 1, Int::plus)
            if (item.id.startsWith("bad-")) throw IOException("unreadable")
            LocalMetadataEvidence(null, null, MetadataValues(publisher = "Image"))
        }
        var turns = 0
        while (analyzer.analyzeNext() != null) { turns++; check(turns <= 1_200) { "no termination" } }
        assertEquals("every item visited exactly once", 1_101, turns)
        assertTrue(reads.values.all { it == 1 })
        assertEquals(1_101, reads.size)
        val good = repo.resolved("good")!!
        assertEquals("the eligible item after the failures was reached", "Image", good.values.publisher)
        assertEquals("2", good.values.issue)
        assertNull("no endless retry in the same launch", analyzer.analyzeNext())
        assertEquals("failed items stay pending for the next launch", 1_100, repo.pendingLocal(LocalMetadataAnalyzer.VERSION, null, 2_000).size)
    }
}
