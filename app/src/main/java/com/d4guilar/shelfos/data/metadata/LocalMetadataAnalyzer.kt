// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import com.d4guilar.shelfos.core.database.LocalCursor
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
    // Keyset position of this launch's traversal: every item is visited at most once per launch, so an unreadable item is
    // skipped until the next launch (no retry loop, no permanent mark) and any number of them costs no bind parameters.
    private var cursor: LocalCursor? = null

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
        // The query is inside the recoverable boundary too: a database error idles this pass, it never stops the worker.
        val next = try { store.pendingLocal(VERSION, cursor, 1).firstOrNull() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: return null
        cursor = next
        val item = publication(next.id) ?: return PublicationFormat.PDF
        // Per-item isolation: an expected failure (unreadable source, parse error) skips only this item for this launch.
        try {
            // A late result for an item removed meanwhile is dropped by the store, never recreated.
            store.apply(item.id, proposals(item, read(item)), VERSION)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        return item.format
    }

    companion object { const val VERSION = 1 }
}
