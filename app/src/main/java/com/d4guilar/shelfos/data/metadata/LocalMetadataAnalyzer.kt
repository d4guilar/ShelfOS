// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.CancellationException

/** What a source declares locally: embedded title/creator (null when absent) and every other embedded field. */
data class LocalMetadataEvidence(val title: String?, val creator: String?, val values: MetadataValues)

/**
 * Offline local metadata analysis (plan §19 step 2): embedded fields and file-name candidates, merged through the one
 * precedence rule, so `USER` values are never touched and embedded values outrank file-name inference. New imports are
 * recorded from the evidence import already read (no second pass); existing items are backfilled one at a time on the
 * cover scheduler's worker (same foreground/no-reader gates, never concurrent with a native RAR extraction).
 * Bump [VERSION] when the parsers learn something new: items are re-analysed and user choices survive.
 */
class LocalMetadataAnalyzer(
    private val store: MetadataStore,
    private val publication: suspend (String) -> LibraryItem?,
    private val read: suspend (LibraryItem) -> LocalMetadataEvidence,
) {
    // Items whose source could not be read this launch: skipped until the next launch (no retry loop, no permanent mark).
    private val failed = HashSet<String>()

    fun proposals(item: LibraryItem, evidence: LocalMetadataEvidence?): List<Proposed> {
        val embedded = FieldProvenance(MetadataOrigin.EMBEDDED)
        // File-name candidates first so embedded evidence for the same field wins.
        return FilenameInference.infer(item.fileName, item.category).proposals() + listOfNotNull(
            evidence?.title?.let { Proposed(MetadataField.TITLE, it, embedded) },
            evidence?.creator?.let { Proposed(MetadataField.CREATOR, it, embedded) },
        ) + (evidence?.values?.proposals(embedded) ?: emptyList())
    }

    /** A just-committed import, from the evidence its inspection read. Runs after the commit, off the import path. */
    suspend fun recordImport(item: LibraryItem, values: MetadataValues) {
        store.apply(item.id, proposals(item, LocalMetadataEvidence(null, null, values)), VERSION)
    }

    /** Analyses the next pending available item; returns its format when one was processed, null when none is pending. */
    suspend fun analyzeNext(): PublicationFormat? {
        val id = store.pendingLocal(VERSION, failed, 1).firstOrNull() ?: return null
        val item = publication(id) ?: run { failed += id; return PublicationFormat.PDF }
        // Per-item isolation: any failure skips this item until the next launch and never stops the worker.
        try {
            val evidence = read(item)
            // A late result for an item removed meanwhile is dropped by the store, never recreated.
            if (!store.apply(id, proposals(item, evidence), VERSION)) failed += id
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed += id }
        return item.format
    }

    companion object { const val VERSION = 1 }
}
