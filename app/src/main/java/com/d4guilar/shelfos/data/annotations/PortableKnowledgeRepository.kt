// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.annotations

import androidx.room.withTransaction
import com.d4guilar.shelfos.core.database.AnnotationDao
import com.d4guilar.shelfos.core.database.AnnotationEntity
import com.d4guilar.shelfos.core.database.AnnotationSourceSnapshotEntity
import com.d4guilar.shelfos.core.database.ShelfDatabase
import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.domain.annotations.portable.*
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat

interface PortableKnowledgeRepository {
    suspend fun classify(document: PortableKnowledgeDocument): PortableImportPreview
    suspend fun import(document: PortableKnowledgeDocument, expected: PortableImportPreview): PortableImportResult
}

class RoomPortableKnowledgeRepository(
    private val database: ShelfDatabase,
    private val dao: AnnotationDao = database.annotations(),
) : PortableKnowledgeRepository {
    override suspend fun classify(document: PortableKnowledgeDocument) = classifyPortableKnowledge(document, state())

    override suspend fun import(document: PortableKnowledgeDocument, expected: PortableImportPreview): PortableImportResult =
        database.withTransaction {
            val current = classifyPortableKnowledge(document, state())
            if (current != expected) throw StalePortableKnowledgePreviewException()
            val statuses = current.annotations.associate { it.id to it.status }
            val snapshots = current.publications.associate { it.libraryItemId to it.snapshotStatus }
            val newAnnotations = document.publications.flatMap { it.annotations }
                .filter { statuses[it.id] == PortableAnnotationStatus.NEW }
                .map(PortableAnnotation::toEntity)
            val newSnapshots = document.publications
                .filter { snapshots[it.libraryItemId] == PortableSnapshotStatus.CREATE }
                .map { AnnotationSourceSnapshotEntity(it.libraryItemId, it.title, it.creator, it.category.name, it.format.name) }
            if (newSnapshots.isNotEmpty()) dao.insertPortableSnapshots(newSnapshots)
            if (newAnnotations.isNotEmpty()) dao.insertPortableAnnotations(newAnnotations)
            PortableImportResult(newAnnotations.size, current.duplicateCount, current.conflictCount,
                newSnapshots.size, current.sourceContextWarnings)
        }

    private suspend fun state(): PortableLocalState {
        val stored = dao.portableAnnotations()
        val annotations = stored.mapNotNull { it.toDomainOrNull() }.associateBy { it.id }
        val unknown = stored.mapTo(HashSet()) { it.id }.apply { removeAll(annotations.keys) }
        val live = dao.portableLibraryContexts().mapTo(HashSet()) { it.id }
        val storedSnapshots = dao.portableSnapshots()
        val snapshots = storedSnapshots.mapNotNull { row ->
            val category = MediaCategory.entries.firstOrNull { it.name == row.category } ?: return@mapNotNull null
            val format = PublicationFormat.entries.firstOrNull { it.name == row.format } ?: return@mapNotNull null
            row.libraryItemId to PortableSourceContext(row.libraryItemId, row.title, row.creator, category, format)
        }.toMap()
        val unknownSnapshots = storedSnapshots.mapTo(HashSet()) { it.libraryItemId }.apply { removeAll(snapshots.keys) }
        return PortableLocalState(annotations, live, snapshots, unknown, unknownSnapshots)
    }
}

private fun AnnotationEntity.toDomainOrNull(): Annotation? {
    val domainKind = AnnotationKind.entries.firstOrNull { it.name == this.kind } ?: return null
    val domainFormat = LocatorFormat.entries.firstOrNull { it.name == locatorFormat } ?: return null
    return Annotation(id, libraryItemId, domainKind, domainFormat, locatorJson, progressSnapshot, orderKey,
        title, selectedText, body, styleKey, createdAt, updatedAt)
}

private fun PortableAnnotation.toEntity() = AnnotationEntity(id, libraryItemId, kind.name, locatorFormat.name,
    locatorJson, progressSnapshot, orderKey, title, selectedText, body, styleKey, createdAt, updatedAt)
