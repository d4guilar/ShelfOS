// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations.portable

import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationDraft
import com.d4guilar.shelfos.domain.annotations.AnnotationRules
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat

object PortableKnowledgeSchema {
    const val NAME = "shelfos.knowledge"
    const val VERSION = 1
    const val MAX_INPUT_BYTES = 64L * 1024 * 1024
    const val MAX_PUBLICATIONS = 10_000
    const val MAX_ANNOTATIONS = 100_000
}

data class PortableKnowledgeDocument(
    val exportedAt: Long,
    val appVersion: String,
    val publications: List<PortablePublication>,
)

data class PortablePublication(
    val libraryItemId: String,
    val title: String,
    val creator: String,
    val category: MediaCategory,
    val format: PublicationFormat,
    val sourceRemoved: Boolean,
    val annotations: List<PortableAnnotation>,
)

data class PortableAnnotation(
    val id: String,
    val libraryItemId: String,
    val kind: AnnotationKind,
    val locatorFormat: LocatorFormat,
    val locatorJson: String,
    val progressSnapshot: Int,
    val orderKey: Double?,
    val title: String?,
    val selectedText: String?,
    val body: String?,
    val styleKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun validate() {
        if (id.isBlank()) throw InvalidPortableKnowledgeException("annotation id must not be blank")
        AnnotationRules.validate(
            AnnotationDraft(libraryItemId, kind, locatorFormat, locatorJson, progressSnapshot, orderKey,
                title, selectedText, body, styleKey),
            validator = null, // Imported known-format locators are deliberately opaque, including malformed persisted locators.
        )
    }

    fun toAnnotation() = Annotation(id, libraryItemId, kind, locatorFormat, locatorJson, progressSnapshot,
        orderKey, title, selectedText, body, styleKey, createdAt, updatedAt)
}

fun Annotation.toPortable() = PortableAnnotation(id, libraryItemId, kind, locatorFormat, locatorJson,
    progressSnapshot, orderKey, title, selectedText, body, styleKey, createdAt, updatedAt)

class InvalidPortableKnowledgeException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
class UnsupportedPortableKnowledgeVersionException(val version: Int) : IllegalArgumentException("unsupported schema version: $version")
class StalePortableKnowledgePreviewException : IllegalStateException("portable knowledge preview is stale")

enum class PortableAnnotationStatus { NEW, DUPLICATE, CONFLICT }
enum class PortableSnapshotStatus { NONE, CREATE, REUSE, LOCAL_WINS }

data class PortablePublicationPlan(
    val libraryItemId: String,
    val live: Boolean,
    val snapshotStatus: PortableSnapshotStatus,
)

data class PortableAnnotationPlan(val id: String, val status: PortableAnnotationStatus)

data class PortableImportPreview(
    val publicationCount: Int,
    val annotationCount: Int,
    val newCount: Int,
    val duplicateCount: Int,
    val conflictCount: Int,
    val orphanGroupsToCreate: Int,
    val sourceContextWarnings: Int,
    val publications: List<PortablePublicationPlan>,
    val annotations: List<PortableAnnotationPlan>,
)

data class PortableImportResult(
    val imported: Int,
    val duplicates: Int,
    val conflicts: Int,
    val orphanGroups: Int,
    val sourceContextWarnings: Int,
)

data class PortableSourceContext(
    val libraryItemId: String,
    val title: String,
    val creator: String,
    val category: MediaCategory,
    val format: PublicationFormat,
)

data class PortableLocalState(
    val annotations: Map<String, Annotation>,
    val livePublicationIds: Set<String>,
    val snapshots: Map<String, PortableSourceContext>,
    val unknownAnnotationIds: Set<String> = emptySet(),
    val unknownSnapshotIds: Set<String> = emptySet(),
)

/** Pure classification used for preview and repeated inside the committing Room transaction. */
fun classifyPortableKnowledge(document: PortableKnowledgeDocument, local: PortableLocalState): PortableImportPreview {
    val publicationPlans = ArrayList<PortablePublicationPlan>(document.publications.size)
    val annotationPlans = ArrayList<PortableAnnotationPlan>()
    var newCount = 0
    var duplicateCount = 0
    var conflictCount = 0
    var snapshotCreates = 0
    var warnings = 0

    for (publication in document.publications.sortedBy { it.libraryItemId }) {
        val live = publication.libraryItemId in local.livePublicationIds
        val hasNew = publication.annotations.any { local.annotations[it.id] == null }
        val importedContext = publication.toContext()
        val localSnapshot = local.snapshots[publication.libraryItemId]
        val snapshot = when {
            live -> PortableSnapshotStatus.NONE
            publication.libraryItemId in local.unknownSnapshotIds -> PortableSnapshotStatus.LOCAL_WINS
            localSnapshot == null && hasNew -> PortableSnapshotStatus.CREATE
            localSnapshot == null -> PortableSnapshotStatus.NONE
            localSnapshot == importedContext -> PortableSnapshotStatus.REUSE
            else -> PortableSnapshotStatus.LOCAL_WINS
        }
        if (snapshot == PortableSnapshotStatus.CREATE) snapshotCreates++
        if (snapshot == PortableSnapshotStatus.LOCAL_WINS) warnings++
        publicationPlans += PortablePublicationPlan(publication.libraryItemId, live, snapshot)
        for (incoming in publication.annotations.sortedBy { it.id }) {
            val current = local.annotations[incoming.id]
            val status = when {
                incoming.id in local.unknownAnnotationIds -> PortableAnnotationStatus.CONFLICT
                current == null -> PortableAnnotationStatus.NEW
                current == incoming.toAnnotation() -> PortableAnnotationStatus.DUPLICATE
                else -> PortableAnnotationStatus.CONFLICT
            }
            when (status) {
                PortableAnnotationStatus.NEW -> newCount++
                PortableAnnotationStatus.DUPLICATE -> duplicateCount++
                PortableAnnotationStatus.CONFLICT -> conflictCount++
            }
            annotationPlans += PortableAnnotationPlan(incoming.id, status)
        }
    }
    return PortableImportPreview(document.publications.size, annotationPlans.size, newCount, duplicateCount,
        conflictCount, snapshotCreates, warnings, publicationPlans, annotationPlans)
}

fun PortablePublication.toContext() = PortableSourceContext(libraryItemId, title, creator, category, format)
