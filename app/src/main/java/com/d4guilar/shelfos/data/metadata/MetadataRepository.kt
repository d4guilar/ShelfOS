// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata

import androidx.room.withTransaction
import com.d4guilar.shelfos.core.database.LocalCursor
import com.d4guilar.shelfos.core.database.MetadataDao
import com.d4guilar.shelfos.core.database.MetadataHead
import com.d4guilar.shelfos.core.database.PublicationMetadataEntity
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The only writer of `publication_metadata` and of title/creator origins after import (plan §9, §16). */
interface MetadataStore {
    /** Title/creator plus stored fields with provenance; null when the item does not exist. */
    suspend fun resolved(id: String): ResolvedMetadata?
    /**
     * Applies [proposals] through [resolveMetadata] in one transaction. A removed item is never recreated (returns
     * false). [analysisVersion], when given, records that local analysis at that version is done.
     */
    suspend fun apply(id: String, proposals: List<Proposed>, analysisVersion: Int? = null): Boolean
    fun observe(id: String): Flow<StoredMetadata?>
    /** Pending items after [after] (null = from the newest) in `addedAt DESC, id` order. */
    suspend fun pendingLocal(version: Int, after: LocalCursor?, limit: Int): List<LocalCursor>

    /** A manual edit: every field changed from [original] (what the editor showed) becomes `USER` (§16); others keep their provenance. */
    suspend fun editByUser(id: String, original: MetadataValues, edited: MetadataValues, at: Long = System.currentTimeMillis()): Boolean =
        apply(id, userEdits(original, edited, at))
}

/** What the details pane shows: the stored fields and their provenance (title/creator come from the Library item). */
data class StoredMetadata(val values: MetadataValues, val provenance: Map<MetadataField, FieldProvenance>)

class RoomMetadataRepository(private val database: ShelfDatabase, private val clock: () -> Long = System::currentTimeMillis) : MetadataStore {
    private val dao: MetadataDao = database.metadata()

    override suspend fun resolved(id: String): ResolvedMetadata? = dao.head(id)?.let { resolve(it, dao.get(id)) }

    override suspend fun apply(id: String, proposals: List<Proposed>, analysisVersion: Int?): Boolean = database.withTransaction {
        // Read and write in one transaction: a removal either happened before (nothing to write) or waits for this.
        val head = dao.head(id) ?: return@withTransaction false
        val row = dao.get(id)
        val current = resolve(head, row)
        val next = resolveMetadata(current, proposals)
        val version = maxOf(row?.localAnalysisVersion ?: 0, analysisVersion ?: 0)
        if (next == current && row != null && version == row.localAnalysisVersion) return@withTransaction true
        val title = next.provenance[MetadataField.TITLE]?.origin?.storageKey ?: head.titleOrigin
        val creator = next.provenance[MetadataField.CREATOR]?.origin?.storageKey ?: head.creatorOrigin
        dao.updateHead(id, next.title, title, next.creator, creator)
        val v = next.values
        // Title/creator provenance is stored here only for its external detail; the origin column stays authoritative.
        val provenance = next.provenance.filterKeys { it != MetadataField.TITLE && it != MetadataField.CREATOR } +
            next.provenance.filter { (k, p) -> (k == MetadataField.TITLE || k == MetadataField.CREATOR) && p.origin.external }
        dao.upsert(PublicationMetadataEntity(id, v.subtitle, v.description, v.publisher, v.publishedDate, v.language, v.series, v.volume,
            v.issue, MetadataCodec.identifiers(v.identifiers), MetadataCodec.credits(v.credits), MetadataCodec.provenance(provenance), version, clock()))
        true
    }

    override fun observe(id: String): Flow<StoredMetadata?> = dao.observe(id).map { row ->
        row?.let { StoredMetadata(values(it), MetadataCodec.provenance(it.provenanceJson)) }
    }.distinctUntilChanged()

    override suspend fun pendingLocal(version: Int, after: LocalCursor?, limit: Int) =
        dao.pendingLocal(version, after?.addedAt ?: Long.MAX_VALUE, after?.id ?: "", limit)

    private fun resolve(head: MetadataHead, row: PublicationMetadataEntity?): ResolvedMetadata {
        val stored = MetadataCodec.provenance(row?.provenanceJson)
        fun headProvenance(field: MetadataField, key: String) =
            stored[field]?.takeIf { it.origin.storageKey == key } ?: FieldProvenance(MetadataOrigin.fromStorage(key))
        return ResolvedMetadata(head.title, head.creator, row?.let(::values) ?: MetadataValues(),
            stored + (MetadataField.TITLE to headProvenance(MetadataField.TITLE, head.titleOrigin)) +
                (MetadataField.CREATOR to headProvenance(MetadataField.CREATOR, head.creatorOrigin)))
    }

    private fun values(r: PublicationMetadataEntity) = MetadataValues(r.subtitle, r.description, r.publisher, normalizedDate(r.publishedDate),
        normalizedLanguage(r.language), r.seriesName, r.volume, r.issue, MetadataCodec.identifiers(r.identifiersJson), MetadataCodec.credits(r.creditsJson))
}
