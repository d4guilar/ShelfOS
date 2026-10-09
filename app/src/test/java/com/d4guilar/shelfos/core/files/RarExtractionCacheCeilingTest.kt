// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 3E-C R1A (HIGH-2 remediation): pure-JVM proof that the extraction-time hard byte ceiling
 * ([NativeRarError.TOO_LARGE]) is enforced DURING materialization through the real cache plumbing
 * ([RarExtractionCache]/[RarCacheCoordinator]) -- never only after the full payload is already written -- and
 * that a rejected oversized extraction leaves no final file, no leftover temp file, and no cache accounting
 * change, with a subsequent normal-sized extraction in the same namespace still succeeding afterward.
 * [FakeRarArchiveSession.extractEntry] simulates the real native engine's streamed abort (writing only up to
 * the ceiling before reporting [NativeRarError.TOO_LARGE] -- see its own doc); the real device-level proof of
 * the ACTUAL native byte ceiling lives in
 * `LibarchiveRarNativeTest.extractEntryEnforcesHardByteCeilingDuringExtraction`.
 */
class RarExtractionCacheCeilingTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("rar-ceiling-test", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun cacheFor(namespace: String) = RarExtractionCache(RarCacheCoordinator.createForTest(root), namespace)

    @Test
    fun payloadExactlyAtTheLimitSucceeds() {
        val fake = FakeRarArchiveSession(
            listOf(FakeRarArchiveSession.entry(0, "page1.jpg")),
            content = mapOf(0 to ByteArray(10) { it.toByte() }),
        )
        val cache = cacheFor("exact")
        val extraction = cache.acquire(0) { dest -> fake.extractEntry(0, dest, maxBytes = 10) }
        assertEquals(10L, extraction.file.length())
        extraction.release()
    }

    @Test
    fun limitPlusOneIsRejectedDuringExtractionNeverAfter() {
        val fake = FakeRarArchiveSession(
            listOf(FakeRarArchiveSession.entry(0, "page1.jpg")),
            content = mapOf(0 to ByteArray(11) { it.toByte() }),
        )
        val cache = cacheFor("too-big")
        val error = assertThrowsRarExtraction {
            cache.acquire(0) { dest -> fake.extractEntry(0, dest, maxBytes = 10) }
        }
        assertEquals(NativeRarError.TOO_LARGE, (error as RarExtractionException).error)

        // No leftover temp/final file anywhere under this namespace's directory.
        val namespaceDir = File(root, "too-big")
        val leftovers = namespaceDir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertTrue("a rejected oversized extraction must never leave a temp or final file behind", leftovers.isEmpty())

        // No cache accounting change.
        assertFalse(cache.containsForTest(0))
        assertEquals(0L, cache.usedBytesForTest)
        assertEquals(0, cache.entryCountForTest)
    }

    @Test
    fun subsequentNormalSizedEntryStillWorksAfterARejection() {
        val fake = FakeRarArchiveSession(
            listOf(
                FakeRarArchiveSession.entry(0, "page1.jpg"),
                FakeRarArchiveSession.entry(1, "page2.jpg"),
            ),
            content = mapOf(0 to ByteArray(11) { it.toByte() }, 1 to ByteArray(5) { it.toByte() }),
        )
        val cache = cacheFor("retry")
        assertThrowsRarExtraction { cache.acquire(0) { dest -> fake.extractEntry(0, dest, maxBytes = 10) } }

        val ok = cache.acquire(1) { dest -> fake.extractEntry(1, dest, maxBytes = 10) }
        assertEquals(5L, ok.file.length())
        ok.release()
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
