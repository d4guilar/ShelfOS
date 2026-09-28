// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.EpubSearchRead
import com.d4guilar.shelfos.core.reader.search
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Direct evidence that EpubParser's attached SearchService works locally on the largest generated EPUB fixture. */
class EpubSearchServiceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    @Test fun parserProvidedServiceSearchesLargestFixtureLocallyWithoutMutatingIt() = runBlocking {
        val item = OriginalFixtures.epubWithLongChapter(context)
        val source = requireNotNull(item.managedPath).let(::File)
        val sizeBefore = source.length()
        val modifiedBefore = source.lastModified()
        val session = container.epubs.open(item)
        val started = SystemClock.elapsedRealtime()
        var matches = 0
        val cursor = session.search("Chapter")
        try {
            while (true) when (val read = cursor.next()) {
                EpubSearchRead.Complete -> break
                EpubSearchRead.Error -> error("The real parser-provided SearchService returned SearchError")
                is EpubSearchRead.Page -> matches += read.results.size
            }
        } finally {
            cursor.close()
            session.close()
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        println("EPUB_SEARCH_ELAPSED_MS=$elapsed")

        assertTrue("Expected at least one real search match", matches > 0)
        assertTrue("Search took ${elapsed}ms", elapsed < 15_000)
        assertEquals(sizeBefore, source.length())
        assertEquals(modifiedBefore, source.lastModified())
    }
}
