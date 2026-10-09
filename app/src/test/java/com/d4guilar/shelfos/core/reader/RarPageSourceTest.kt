// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.core.files.FakeRarArchiveSession
import com.d4guilar.shelfos.core.files.NativeRarEntryType
import com.d4guilar.shelfos.core.files.NativeRarError
import com.d4guilar.shelfos.core.files.RarExtractionException
import com.d4guilar.shelfos.core.files.RarExtractionCache
import com.d4guilar.shelfos.core.files.toPublicationProblem
import com.d4guilar.shelfos.domain.library.PublicationException
import com.d4guilar.shelfos.domain.library.PublicationProblem
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 3E-C: pure-JVM proof of [RarPageSource]'s ordering/filtering/caching/error-mapping policy against
 * [FakeRarArchiveSession] -- no real archive, no Android runtime, no `BitmapFactory` (see that fake's doc for
 * the honesty boundary: this proves [RarPageSource]'s own logic, never real libarchive/RAR parsing).
 */
class RarPageSourceTest {
    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        cacheRoot = File.createTempFile("rar-page-source-test", "").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        cacheRoot.deleteRecursively()
    }

    private fun open(
        entries: List<com.d4guilar.shelfos.core.files.NativeRarEntry>,
        content: Map<Int, ByteArray> = emptyMap(),
        sourceKey: String? = "test-source",
    ): Pair<RarPageSource, FakeRarArchiveSession> {
        val fake = FakeRarArchiveSession(entries, content)
        val source = RarPageSource.open(fake, cacheRoot, sourceKey)
        return source to fake
    }

    @Test
    fun naturalOrderingMatchesCbzBehavior() {
        val entries = listOf(
            FakeRarArchiveSession.entry(0, "page10.jpg"),
            FakeRarArchiveSession.entry(1, "page2.jpg"),
            FakeRarArchiveSession.entry(2, "page1.jpg"),
        )
        val content = mapOf(0 to byteArrayOf(10), 1 to byteArrayOf(2), 2 to byteArrayOf(1))
        val (source, _) = open(entries, content)
        // natural order: page1, page2, page10 -- never lexicographic ("page1","page10","page2")
        assertEquals(1, source.openPage(0).use { it.readBytes() }[0].toInt())
        assertEquals(2, source.openPage(1).use { it.readBytes() }[0].toInt())
        assertEquals(10, source.openPage(2).use { it.readBytes() }[0].toInt())
        source.close()
    }

    @Test
    fun duplicateFilenamesAtDifferentPhysicalOrdinalsNeverCollide() {
        val entries = listOf(
            FakeRarArchiveSession.entry(0, "page.jpg"),
            FakeRarArchiveSession.entry(1, "page.jpg"),
        )
        val content = mapOf(0 to byteArrayOf(0xA.toByte()), 1 to byteArrayOf(0xB.toByte()))
        val (source, fake) = open(entries, content)
        assertEquals(2, source.pageCount)
        val first = source.openPage(0).use { it.readBytes() }
        val second = source.openPage(1).use { it.readBytes() }
        assertTrue(first[0] != second[0]) // distinct bytes -- never confused despite identical names
        assertEquals(2, fake.extractionCount)
        source.close()
    }

    @Test
    fun unsafeNamesNeverBecomeLogicalPages() {
        val entries = listOf(
            FakeRarArchiveSession.entry(0, "../escape.jpg"),
        )
        try {
            open(entries, mapOf(0 to byteArrayOf(1)))
            throw AssertionError("expected a PublicationException for an unsafe entry name")
        } catch (e: PublicationException) {
            assertEquals(PublicationProblem.CORRUPT, e.problem)
        }
    }

    @Test
    fun nonImageEntriesAreExcludedFromPages() {
        val entries = listOf(
            FakeRarArchiveSession.entry(0, "readme.txt"),
            FakeRarArchiveSession.entry(1, "page1.jpg"),
            FakeRarArchiveSession.entry(2, "folder", type = NativeRarEntryType.DIRECTORY),
        )
        val (source, _) = open(entries, mapOf(1 to byteArrayOf(5)))
        assertEquals(1, source.pageCount)
        source.close()
    }

    @Test
    fun emptyArchiveWithNoImagePagesFails() {
        val entries = listOf(FakeRarArchiveSession.entry(0, "readme.txt"))
        try {
            open(entries)
            throw AssertionError("expected EMPTY_ARCHIVE")
        } catch (e: PublicationException) {
            assertEquals(PublicationProblem.EMPTY_ARCHIVE, e.problem)
        }
    }

    @Test
    fun firstMaterializationExtractsOnceAndCacheHitIsZeroAdditionalExtractions() {
        val entries = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val (source, fake) = open(entries, mapOf(0 to byteArrayOf(9)))
        source.openPage(0).use { it.readBytes() } // first read ("bounds" stand-in)
        source.openPage(0).use { it.readBytes() } // second read ("full decode" stand-in) -- same logical page
        assertEquals("bounds+full reuse must cost exactly one extraction", 1, fake.extractionCount)
        source.close()
    }

    @Test
    fun solidStyleNonSequentialAccessReExtractsOnlyOnCacheMiss() {
        val entries = listOf(
            FakeRarArchiveSession.entry(0, "page1.jpg"),
            FakeRarArchiveSession.entry(1, "page2.jpg"),
            FakeRarArchiveSession.entry(2, "page3.jpg"),
        )
        val content = mapOf(0 to byteArrayOf(1), 1 to byteArrayOf(2), 2 to byteArrayOf(3))
        val (source, fake) = open(entries, content)

        source.openPage(2).use { it.readBytes() } // later page: +1 extraction
        assertEquals(1, fake.extractionCount)
        source.openPage(2).use { it.readBytes() } // same page again: +0
        assertEquals(1, fake.extractionCount)
        source.openPage(0).use { it.readBytes() } // different page: +1
        assertEquals(2, fake.extractionCount)
        source.openPage(2).use { it.readBytes() } // first page again, still cached: +0
        assertEquals(2, fake.extractionCount)
        source.close()
    }

    @Test
    fun comicInfoIsNeverExposedAsALogicalPageAndIsParsedViaTheExistingReader() {
        val xml = "<ComicInfo><Title>Test Issue</Title><Writer>Ada</Writer></ComicInfo>"
        val entries = listOf(
            FakeRarArchiveSession.entry(0, "ComicInfo.xml"),
            FakeRarArchiveSession.entry(1, "page1.jpg"),
        )
        val content = mapOf(0 to xml.toByteArray(Charsets.UTF_8), 1 to byteArrayOf(1))
        val (source, _) = open(entries, content)
        assertEquals(1, source.pageCount) // ComicInfo.xml never counted as a page
        val metadata = source.comicInfo()
        assertEquals("Test Issue", metadata?.title)
        assertEquals("Ada", metadata?.creator)
        source.close()
    }

    @Test
    fun noComicInfoEntryReturnsNull() {
        val entries = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val (source, _) = open(entries, mapOf(0 to byteArrayOf(1)))
        assertNull(source.comicInfo())
        source.close()
    }

    @Test
    fun errorMappingPreservesEachNativeRarErrorCategory() {
        val cases = mapOf(
            NativeRarError.PROTECTED to PublicationProblem.PROTECTED,
            NativeRarError.UNSUPPORTED to PublicationProblem.UNSUPPORTED_FORMAT,
            NativeRarError.CORRUPT to PublicationProblem.CORRUPT,
            NativeRarError.INVALID_ARGUMENT to PublicationProblem.CORRUPT,
            NativeRarError.NOT_SEEKABLE to PublicationProblem.NEEDS_COPY,
            NativeRarError.IO to PublicationProblem.UNREADABLE,
            NativeRarError.NATIVE_INTERNAL to PublicationProblem.UNREADABLE,
            NativeRarError.TOO_LARGE to PublicationProblem.TOO_LARGE,
        )
        for ((native, expected) in cases) {
            assertEquals(expected, native.toPublicationProblem())
        }
    }

    @Test
    fun extractionErrorsPreserveProblemAndExactTypedNativeCause() {
        val expectedProblems = mapOf(
            NativeRarError.PROTECTED to PublicationProblem.PROTECTED,
            NativeRarError.UNSUPPORTED to PublicationProblem.UNSUPPORTED_FORMAT,
            NativeRarError.CORRUPT to PublicationProblem.CORRUPT,
            NativeRarError.INVALID_ARGUMENT to PublicationProblem.CORRUPT,
            NativeRarError.NOT_SEEKABLE to PublicationProblem.NEEDS_COPY,
            NativeRarError.IO to PublicationProblem.UNREADABLE,
            NativeRarError.NATIVE_INTERNAL to PublicationProblem.UNREADABLE,
            NativeRarError.TOO_LARGE to PublicationProblem.TOO_LARGE,
        )
        val entries = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))

        for ((nativeError, expectedProblem) in expectedProblems) {
            val source = RarPageSource.open(
                FakeRarArchiveSession(entries, failures = mapOf(0 to nativeError)),
                cacheRoot,
                "typed-error-$nativeError",
            )
            try {
                source.openPage(0)
                throw AssertionError("expected PublicationException for $nativeError")
            } catch (e: PublicationException) {
                assertEquals(expectedProblem, e.problem)
                assertEquals(nativeError, (e.cause as RarExtractionException).error)
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun filesystemIOExceptionUsesTypedUnreadableContractAndRetainsCause() {
        val failure = IOException("synthetic PFD/filesystem failure")
        val entries = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val source = RarPageSource.open(
            FakeRarArchiveSession(entries, throwables = mapOf(0 to failure)),
            cacheRoot,
            "filesystem-io",
        )
        try {
            source.openPage(0)
            throw AssertionError("expected PublicationException")
        } catch (e: PublicationException) {
            assertEquals(PublicationProblem.UNREADABLE, e.problem)
            assertSame(failure, e.cause)
        } finally {
            source.close()
        }
    }

    @Test
    fun extractionCancellationIsNeverMappedAsOrdinaryFailure() {
        val cancellation = CancellationException("synthetic cancellation")
        val entries = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val source = RarPageSource.open(
            FakeRarArchiveSession(entries, throwables = mapOf(0 to cancellation)),
            cacheRoot,
            "cancelled-extraction",
        )
        try {
            source.openPage(0)
            throw AssertionError("expected CancellationException")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        } finally {
            source.close()
        }
    }

    @Test
    fun extractionFailureDuringRenderThrowsAMappedPublicationExceptionAndRetrySucceeds() {
        val entries = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val fake = FakeRarArchiveSession(entries, failures = mapOf(0 to NativeRarError.IO))
        val source = RarPageSource.open(fake, cacheRoot, "retry-source")
        try {
            source.openPage(0)
            throw AssertionError("expected a PublicationException")
        } catch (e: PublicationException) {
            assertEquals(PublicationProblem.UNREADABLE, e.problem)
        }
        source.close()

        // A fresh session (fake no longer configured to fail) over the SAME source key must succeed -- the
        // failed extraction must never have left a poisoned/partial cache entry behind.
        val fakeRetry = FakeRarArchiveSession(entries, content = mapOf(0 to byteArrayOf(7)))
        val retrySource = RarPageSource.open(fakeRetry, cacheRoot, "retry-source")
        val bytes = retrySource.openPage(0).use { it.readBytes() }
        assertEquals(7, bytes[0].toInt())
        retrySource.close()
    }

    @Test
    fun openPageAfterCloseThrows() {
        val entries = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val (source, _) = open(entries, mapOf(0 to byteArrayOf(1)))
        source.close()
        try {
            source.openPage(0)
            throw AssertionError("expected IllegalStateException after close")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun differentSourceKeysNeverShareCachedBytesEvenWithTheSamePhysicalOrdinal() {
        val entriesA = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val entriesB = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val sourceA = RarPageSource.open(
            FakeRarArchiveSession(entriesA, mapOf(0 to byteArrayOf(1))), cacheRoot, "key-a",
        )
        val sourceB = RarPageSource.open(
            FakeRarArchiveSession(entriesB, mapOf(0 to byteArrayOf(2))), cacheRoot, "key-b",
        )
        assertEquals(1, sourceA.openPage(0).use { it.readBytes() }[0].toInt())
        assertEquals(2, sourceB.openPage(0).use { it.readBytes() }[0].toInt())
        sourceA.close(); sourceB.close()
    }

    @Test
    fun declaredSizeOverTheLimitIsRejectedBeforeAnyExtractionIsAttempted() {
        // Phase 3E-C R1A (HIGH-2): the cheap declared-size precheck in index() must still reject an entry whose
        // KNOWN declared size already exceeds the policy limit, before any extraction (and therefore before the
        // new streamed hard ceiling) is ever reached.
        val entries = listOf(
            FakeRarArchiveSession.entry(
                0,
                "page1.jpg",
                size = com.d4guilar.shelfos.core.files.ArchivePolicy.MAX_IMAGE_BYTES + 1,
            ),
        )
        val fake = FakeRarArchiveSession(entries)
        try {
            RarPageSource.open(fake, cacheRoot, "declared-too-large")
            throw AssertionError("expected TOO_LARGE")
        } catch (e: PublicationException) {
            assertEquals(PublicationProblem.TOO_LARGE, e.problem)
        }
        assertEquals(
            "the declared-size precheck must reject before any extraction is ever attempted",
            0,
            fake.extractionCount,
        )
    }

    @Test
    fun entryCountOverPolicyLimitFails() {
        val entries = (0 until (com.d4guilar.shelfos.core.files.ArchivePolicy.MAX_ENTRIES + 1)).map {
            FakeRarArchiveSession.entry(it, "page$it.jpg")
        }
        try {
            open(entries)
            throw AssertionError("expected TOO_LARGE")
        } catch (e: PublicationException) {
            assertEquals(PublicationProblem.TOO_LARGE, e.problem)
        }
    }

    @Test
    fun nullSourceKeyNeverReusesCachedBytesAcrossReopensEvenWithIdenticalPhysicalOrdinals() {
        // Phase 3E-D R1A (HIGH-2) integration-level proof: a `null` sourceKey (the production policy for a
        // referenced external source without a trustworthy refreshed revision token, or any other source that
        // cannot safely offer a persistent key) must fall back to a fresh ephemeral/random namespace on EVERY
        // open -- simulating the underlying source's bytes changing between two "reopens" of the same logical
        // publication (same physical ordinal 0) must therefore never expose a stale cache hit, with or without a
        // byteSize/content-size change.
        val entriesBefore = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val before = RarPageSource.open(FakeRarArchiveSession(entriesBefore, mapOf(0 to byteArrayOf(1))), cacheRoot, sourceKey = null)
        assertEquals(1, before.openPage(0).use { it.readBytes() }[0].toInt())
        before.close()

        // Same-size replacement: the most dangerous case for the old "$id:$byteSize" key (identical declared
        // size would have reused the same key/namespace and served the OLD, now-stale materialized bytes).
        val entriesSameSize = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val sameSize = RarPageSource.open(FakeRarArchiveSession(entriesSameSize, mapOf(0 to byteArrayOf(2))), cacheRoot, sourceKey = null)
        assertEquals("a null sourceKey must never reuse a prior open's cached bytes", 2, sameSize.openPage(0).use { it.readBytes() }[0].toInt())
        sameSize.close()

        // Changed-size replacement: same requirement, for completeness.
        val entriesChangedSize = listOf(FakeRarArchiveSession.entry(0, "page1.jpg"))
        val changedSize = RarPageSource.open(
            FakeRarArchiveSession(entriesChangedSize, mapOf(0 to byteArrayOf(3, 3, 3))), cacheRoot, sourceKey = null,
        )
        assertEquals(3, changedSize.openPage(0).use { it.readBytes() }[0].toInt())
        changedSize.close()
    }

    @Test
    fun tinyInjectableCacheLimitsTriggerDeterministicEviction() {
        val entries = listOf(
            FakeRarArchiveSession.entry(0, "page1.jpg"),
            FakeRarArchiveSession.entry(1, "page2.jpg"),
            FakeRarArchiveSession.entry(2, "page3.jpg"),
        )
        val content = mapOf(0 to byteArrayOf(1), 1 to byteArrayOf(2), 2 to byteArrayOf(3))
        val fake = FakeRarArchiveSession(entries, content)
        val source = RarPageSource.open(fake, cacheRoot, "tiny-cache", maxCacheEntries = 1)
        source.openPage(0).use { it.readBytes() }
        source.openPage(1).use { it.readBytes() }
        // Re-reading page 0 after the 1-entry cache evicted it must re-extract rather than crash/corrupt.
        source.openPage(0).use { it.readBytes() }
        assertTrue(fake.extractionCount >= 3)
        source.close()
    }
}
