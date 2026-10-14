// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.annotations

import android.content.ContentResolver
import android.net.Uri
import com.d4guilar.shelfos.domain.annotations.NotesQuery
import com.d4guilar.shelfos.domain.annotations.portable.*
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flow

enum class PortableExportFormat { JSON, MARKDOWN }

/** SAF edge for Phase 4E. It stores no URI and performs all parsing/writing away from the main thread. */
class PortableKnowledgeFiles(
    private val resolver: ContentResolver,
    private val annotations: AnnotationRepository,
    private val restore: PortableKnowledgeRepository,
    private val appVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun export(destination: Uri, format: PortableExportFormat, query: NotesQuery, labels: PortableMarkdownLabels) = withContext(io) {
        try {
            resolver.openOutputStream(destination, "wt")?.use { output ->
                val rows = exportRows(query)
                when (format) {
                    PortableExportFormat.JSON -> PortableKnowledgeJson.writeRows(clock(), appVersion, rows, output)
                    PortableExportFormat.MARKDOWN -> PortableKnowledgeMarkdown.writeRows(rows, output, labels)
                }
            } ?: throw IllegalStateException("destination cannot be opened")
        } catch (failure: Exception) {
            runCatching { resolver.delete(destination, null, null) }
            throw failure
        }
    }

    suspend fun preview(uri: Uri): Pair<PortableKnowledgeDocument, PortableImportPreview> = withContext(io) {
        val document = read(uri)
        document to restore.classify(document)
    }

    /** Reopens and fully revalidates before the repository starts its single atomic transaction. */
    suspend fun import(uri: Uri, expectedDocument: PortableKnowledgeDocument, expected: PortableImportPreview): PortableImportResult = withContext(io) {
        val reopened = read(uri)
        if (reopened != expectedDocument) throw StalePortableKnowledgePreviewException()
        restore.import(reopened, expected)
    }

    private fun read(uri: Uri): PortableKnowledgeDocument =
        resolver.openInputStream(uri)?.use(PortableKnowledgeJson::read)
            ?: throw IllegalStateException("source cannot be opened")

    private fun exportRows(query: NotesQuery) = flow {
        var after: Pair<String, String>? = null
        var groupId: String? = null
        var groupHeader: PortablePublication? = null
        while (true) {
            val page = annotations.portableExportPage(query, after, NOTES_PAGE_SIZE)
            if (page.isEmpty()) break
            val contextless = page.mapNotNull { row -> row.source.libraryItemId.takeIf {
                row.source.format == null && it != groupId
            } }.distinct()
            val nonReadium = if (contextless.isEmpty()) emptySet() else annotations.portableGroupsWithNonReadium(query, contextless)
            for (row in page) {
                val source = row.source
                if (source.libraryItemId != groupId) {
                    val inferredFormat = source.format ?: if (source.libraryItemId in nonReadium)
                        PublicationFormat.PDF else PublicationFormat.EPUB
                    val inferredCategory = source.category ?: when (inferredFormat) {
                        PublicationFormat.EPUB -> MediaCategory.BOOK
                        PublicationFormat.CBZ, PublicationFormat.CBR -> MediaCategory.COMIC
                        PublicationFormat.PDF -> MediaCategory.DOCUMENT
                    }
                    groupHeader = PortablePublication(source.libraryItemId, source.title.orEmpty(), source.creator.orEmpty(),
                        inferredCategory, inferredFormat, sourceRemoved = !source.live, annotations = emptyList())
                    groupId = source.libraryItemId
                }
                emit(PortableExportRow(requireNotNull(groupHeader), row.annotation.toPortable()))
                after = source.libraryItemId to row.annotation.id
            }
            if (page.size < NOTES_PAGE_SIZE) break
        }
    }
}
