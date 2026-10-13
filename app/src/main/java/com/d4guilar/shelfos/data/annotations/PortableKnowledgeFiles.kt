// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.annotations

import android.content.ContentResolver
import android.net.Uri
import com.d4guilar.shelfos.domain.annotations.AnnotationWithSource
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.domain.annotations.NotesCursor
import com.d4guilar.shelfos.domain.annotations.NotesQuery
import com.d4guilar.shelfos.domain.annotations.portable.*
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                val document = buildDocument(query)
                when (format) {
                    PortableExportFormat.JSON -> PortableKnowledgeJson.write(document, output)
                    PortableExportFormat.MARKDOWN -> PortableKnowledgeMarkdown.write(document, output, labels)
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

    internal suspend fun buildDocument(query: NotesQuery): PortableKnowledgeDocument {
        val rows = ArrayList<AnnotationWithSource>()
        var cursor: NotesCursor? = null
        do {
            val page = annotations.notes(query, cursor, NOTES_PAGE_SIZE)
            rows += page.rows
            cursor = page.next
        } while (cursor != null)
        val publications = rows.groupBy { it.annotation.libraryItemId }.map { (id, group) ->
            val source = group.first().source
            val inferredFormat = source.format ?: if (group.all { it.annotation.locatorFormat == LocatorFormat.READIUM_LOCATOR_1 })
                PublicationFormat.EPUB else PublicationFormat.PDF
            val inferredCategory = source.category ?: when (inferredFormat) {
                PublicationFormat.EPUB -> MediaCategory.BOOK
                PublicationFormat.CBZ, PublicationFormat.CBR -> MediaCategory.COMIC
                PublicationFormat.PDF -> MediaCategory.DOCUMENT
            }
            PortablePublication(id, source.title.orEmpty(), source.creator.orEmpty(), inferredCategory, inferredFormat,
                sourceRemoved = !source.live, annotations = group.map { it.annotation.toPortable() })
        }
        return PortableKnowledgeDocument(clock(), appVersion, publications)
    }
}
