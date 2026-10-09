// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import java.io.File

/**
 * Phase 3E-C test double for [RarArchiveSession]. Lets `RarPageSource`/[RarExtractionCache]'s own
 * ordering/caching/safety-policy logic be deterministically unit-tested in a plain JVM test -- no real archive,
 * no Android runtime -- per `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3E-C testability note. This proves THIS
 * checkpoint's logic only; real libarchive/RAR parsing correctness remains proven exclusively by the Phase 3E-B
 * instrumented tests (`LibarchiveRarNativeTest`/`LibarchiveRarNativeLifecycleTest`) against the real
 * [NativeRarSession], plus this checkpoint's own real-session instrumented smoke test.
 */
internal class FakeRarArchiveSession(
    private val entryList: List<NativeRarEntry>,
    private val content: Map<Int, ByteArray> = emptyMap(),
    private val failures: Map<Int, NativeRarError> = emptyMap(),
    private val throwables: Map<Int, Throwable> = emptyMap(),
    /** Simulates a partial write before failure (process-death-mid-extraction style): bytes written to the
     * destination before the configured [failures] error (if any) is returned for that index. */
    private val partialBytesBeforeFailure: Map<Int, ByteArray> = emptyMap(),
) : RarArchiveSession {

    var extractionCount: Int = 0
        private set
    val extractedIndices: MutableList<Int> = mutableListOf()
    var closed: Boolean = false
        private set

    override val entryCount: Int get() = entryList.size
    override fun entryAt(index: Int): NativeRarEntry? = entryList.getOrNull(index)

    override fun extractEntry(index: Int, destination: File, maxBytes: Long): NativeRarError? {
        extractionCount++
        extractedIndices += index
        throwables[index]?.let { throw it }
        partialBytesBeforeFailure[index]?.let { destination.writeBytes(it) }
        failures[index]?.let { return it }
        val bytes = content[index] ?: return NativeRarError.INVALID_ARGUMENT
        // Simulates the real native engine's streamed hard ceiling (Phase 3E-C R1A, HIGH-2): writes only up to
        // maxBytes and reports TOO_LARGE rather than writing the full (dishonestly-sized-relative-to-the-cap)
        // payload and rejecting it afterward -- never a post-hoc check.
        if (bytes.size.toLong() > maxBytes) {
            destination.writeBytes(bytes.copyOf(maxBytes.toInt()))
            return NativeRarError.TOO_LARGE
        }
        destination.writeBytes(bytes)
        return null
    }

    override fun close() {
        closed = true
    }

    companion object {
        fun entry(
            index: Int,
            name: String,
            type: NativeRarEntryType = NativeRarEntryType.REGULAR_FILE,
            size: Long? = null,
        ) = NativeRarEntry(index, name, nameEncodingConfirmedUtf8 = true, type = type, size = size)
    }
}
