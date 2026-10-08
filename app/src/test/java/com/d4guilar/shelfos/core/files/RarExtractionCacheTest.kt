// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 3E-C: pure-JVM proof of [RarExtractionCache]'s atomicity, concurrency-dedup, eviction, and stale-cleanup
 * contract -- entirely independent of any real archive or Android runtime (see [FakeRarArchiveSession]'s doc).
 */
class RarExtractionCacheTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("rar-cache-test", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun bytesOf(length: Int, seed: Int): ByteArray = ByteArray(length) { ((it + seed) % 256).toByte() }

    @Test
    fun firstAcquireExtractsExactlyOnceAndCacheHitExtractsZeroMore() {
        val cache = RarExtractionCache(root)
        var calls = 0
        val extract: (File) -> NativeRarError? = { dest -> calls++; dest.writeBytes(bytesOf(16, 1)); null }

        val first = cache.acquire(0, extract)
        assertEquals(1, calls)
        assertArrayEquals(bytesOf(16, 1), first.file.readBytes())
        first.release()

        val second = cache.acquire(0, extract) // cache hit
        assertEquals("a cache hit must never re-extract", 1, calls)
        assertEquals(first.file, second.file)
        second.release()
    }

    @Test
    fun boundsThenFullDecodeOfTheSamePageReusesOneMaterialization() {
        // Mirrors ImagePageRenderer's bounds-pass-then-full-decode call pattern: two openPage-equivalent calls
        // for the SAME logical page must cost exactly one native extraction.
        val cache = RarExtractionCache(root)
        var calls = 0
        val extract: (File) -> NativeRarError? = { dest -> calls++; dest.writeBytes(bytesOf(8, 7)); null }

        val bounds = cache.acquire(3, extract)
        val full = cache.acquire(3, extract)
        assertEquals(1, calls)
        bounds.release()
        full.release()
    }

    @Test
    fun distinctPhysicalEntriesProduceDistinctNonCollidingCacheEntries() {
        val cache = RarExtractionCache(root)
        val a = cache.acquire(0) { it.writeBytes(bytesOf(4, 1)); null }
        val b = cache.acquire(1) { it.writeBytes(bytesOf(4, 2)); null }
        assertFalse(a.file == b.file)
        assertArrayEquals(bytesOf(4, 1), a.file.readBytes())
        assertArrayEquals(bytesOf(4, 2), b.file.readBytes())
        a.release(); b.release()
    }

    @Test
    fun differentSourceNamespacesDoNotCollideEvenWithIdenticalOrdinals() {
        val rootA = File(root, "a").apply { mkdirs() }
        val rootB = File(root, "b").apply { mkdirs() }
        val cacheA = RarExtractionCache(rootA)
        val cacheB = RarExtractionCache(rootB)

        val a = cacheA.acquire(0) { it.writeBytes(bytesOf(4, 11)); null }
        val b = cacheB.acquire(0) { it.writeBytes(bytesOf(4, 22)); null }
        assertArrayEquals(bytesOf(4, 11), a.file.readBytes())
        assertArrayEquals(bytesOf(4, 22), b.file.readBytes())
        a.release(); b.release()
    }

    @Test
    fun extractionFailureNeverLeavesAPartialFileMasqueradingAsACacheHitAndRetrySucceeds() {
        val cache = RarExtractionCache(root)
        var attempt = 0
        val error: Throwable = assertThrowsRarExtraction {
            cache.acquire(5) { dest ->
                attempt++
                dest.writeBytes(bytesOf(100, 1)) // partial write before the simulated failure
                NativeRarError.IO
            }
        }
        assertTrue(error is RarExtractionException)
        assertEquals(NativeRarError.IO, (error as RarExtractionException).error)
        assertFalse("no .bin/.tmp- leftovers after a failed extraction", root.listFiles()!!.any { it.isFile })
        assertFalse(cache.containsForTest(5))

        val retried = cache.acquire(5) { dest -> attempt++; dest.writeBytes(bytesOf(10, 2)); null }
        assertEquals(2, attempt)
        assertArrayEquals(bytesOf(10, 2), retried.file.readBytes())
        retried.release()
    }

    @Test
    fun staleTempFilesFromAnAbandonedProcessAreCleanedUpOnConstruction() {
        File(root, "7.tmp-123-1").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "8.bin").writeBytes(byteArrayOf(9)) // a legitimate final file must survive
        RarExtractionCache(root) // construction triggers lazy stale-temp cleanup
        assertFalse(File(root, "7.tmp-123-1").exists())
        assertTrue(File(root, "8.bin").exists())
    }

    @Test
    fun evictsDeterministicallyByEntryCount() {
        val cache = RarExtractionCache(root, maxBytes = Long.MAX_VALUE, maxEntries = 2)
        val a = cache.acquire(1) { it.writeBytes(bytesOf(4, 1)); null }.also { it.release() }
        val b = cache.acquire(2) { it.writeBytes(bytesOf(4, 2)); null }.also { it.release() }
        assertEquals(2, cache.entryCountForTest)
        val c = cache.acquire(3) { it.writeBytes(bytesOf(4, 3)); null }.also { it.release() }
        assertEquals(2, cache.entryCountForTest) // key 1 (least-recently-accessed) evicted
        assertFalse(cache.containsForTest(1))
        assertTrue(cache.containsForTest(2))
        assertTrue(cache.containsForTest(3))
    }

    @Test
    fun evictsDeterministicallyByByteBudget() {
        val cache = RarExtractionCache(root, maxBytes = 20, maxEntries = 100)
        cache.acquire(1) { it.writeBytes(bytesOf(10, 1)); null }.release()
        cache.acquire(2) { it.writeBytes(bytesOf(10, 2)); null }.release()
        assertEquals(20, cache.usedBytesForTest)
        cache.acquire(3) { it.writeBytes(bytesOf(10, 3)); null }.release()
        // Adding a third 10-byte entry must evict the least-recently-accessed one to stay within the 20-byte budget.
        assertTrue(cache.usedBytesForTest <= 20)
        assertFalse(cache.containsForTest(1))
    }

    @Test
    fun anActivelyReferencedEntryIsNeverEvicted() {
        val cache = RarExtractionCache(root, maxBytes = Long.MAX_VALUE, maxEntries = 1)
        val held = cache.acquire(1) { it.writeBytes(bytesOf(4, 1)); null } // never released -- stays "active"
        cache.acquire(2) { it.writeBytes(bytesOf(4, 2)); null }.release()
        // maxEntries=1 would normally evict key 1, but it is still actively referenced.
        assertTrue(cache.containsForTest(1))
        held.release()
    }

    @Test
    fun entryExceedingTheMaxSizeFailsAndLeavesNoCacheEntry() {
        val cache = RarExtractionCache(root, maxBytes = Long.MAX_VALUE, maxEntries = 10)
        val oversized = RarExtractionCache.MAX_ENTRY_BYTES + 1
        val error = assertThrowsRarExtraction {
            cache.acquire(1) { dest ->
                dest.outputStream().use { out ->
                    val chunk = ByteArray(1024 * 1024)
                    var written = 0L
                    while (written < oversized) {
                        val toWrite = minOf(chunk.size.toLong(), oversized - written).toInt()
                        out.write(chunk, 0, toWrite)
                        written += toWrite
                    }
                }
                null
            }
        }
        assertEquals(NativeRarError.INVALID_ARGUMENT, (error as RarExtractionException).error)
        assertFalse(cache.containsForTest(1))
    }

    private fun assertThrowsRarExtraction(block: () -> Unit): Throwable {
        return try {
            block()
            throw AssertionError("expected RarExtractionException")
        } catch (e: RarExtractionException) {
            e
        }
    }
}
