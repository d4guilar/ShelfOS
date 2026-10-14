// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations.portable

import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.FixedPageLocator
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

data class PortableMarkdownLabels(
    val documentTitle: String,
    val removedSource: String,
    val bookmark: String,
    val highlight: String,
    val note: String,
    val page: (Int) -> String,
    val progress: (Int) -> String,
    val created: (Long) -> String,
    val updated: (Long) -> String,
)

/** Human-facing only. User content is escaped as text; no machine restore payload is embedded. */
object PortableKnowledgeMarkdown {
    fun write(document: PortableKnowledgeDocument, output: OutputStream, labels: PortableMarkdownLabels) {
        val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
        writer.append("# ").append(escape(labels.documentTitle)).append("\n\n")
        document.publications.sortedBy { it.libraryItemId }.forEach { publication ->
            writeHeader(writer, publication, labels)
            publication.annotations.sortedBy { it.id }.forEach { annotation ->
                writeAnnotation(writer, annotation, labels)
            }
        }
        writer.flush()
    }

    suspend fun writeRows(rows: Flow<PortableExportRow>, output: OutputStream, labels: PortableMarkdownLabels) {
        val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
        writer.append("# ").append(escape(labels.documentTitle)).append("\n\n")
        var groupId: String? = null
        rows.collect { row ->
            if (row.publication.libraryItemId != groupId) {
                writeHeader(writer, row.publication, labels)
                groupId = row.publication.libraryItemId
            }
            writeAnnotation(writer, row.annotation, labels)
        }
        writer.flush()
    }

    private fun writeHeader(writer: OutputStreamWriter, publication: PortablePublication, labels: PortableMarkdownLabels) {
        writer.append("## ").append(escape(publication.title)).append("\n\n")
        if (publication.creator.isNotBlank()) writer.append(escape(publication.creator)).append("\n\n")
        if (publication.sourceRemoved) writer.append("_").append(escape(labels.removedSource)).append("_\n\n")
    }

    private fun writeAnnotation(writer: OutputStreamWriter, annotation: PortableAnnotation, labels: PortableMarkdownLabels) {
        val kind = when (annotation.kind) {
            AnnotationKind.BOOKMARK -> labels.bookmark
            AnnotationKind.HIGHLIGHT -> labels.highlight
            AnnotationKind.NOTE -> labels.note
        }
        val location = if (annotation.locatorFormat == LocatorFormat.FIXED_PAGE_1)
            FixedPageLocator.parse(annotation.locatorJson)?.let { labels.page(it + 1) }
                ?: labels.progress(annotation.progressSnapshot)
        else labels.progress(annotation.progressSnapshot)
        writer.append("### ").append(escape(kind)).append(" — ").append(escape(location)).append("\n\n")
        annotation.title?.takeIf(String::isNotBlank)?.let { writer.append("**").append(escape(it)).append("**\n\n") }
        annotation.selectedText?.takeIf(String::isNotBlank)?.let { excerpt ->
            excerpt.lines().forEach { writer.append("> ").append(escape(it)).append("\n") }
            writer.append("\n")
        }
        annotation.body?.takeIf(String::isNotBlank)?.let { body -> writer.append(escape(body)).append("\n\n") }
        writer.append(escape(labels.created(annotation.createdAt)))
        if (annotation.updatedAt > annotation.createdAt) writer.append(" · ").append(escape(labels.updated(annotation.updatedAt)))
        writer.append("\n\n")
    }

    internal fun escape(value: String): String = buildString(value.length) {
        var lineStart = 0
        value.forEachIndexed { index, c ->
            val nextIsSpace = value.getOrNull(index + 1)?.isWhitespace() == true
            val unorderedPrefix = index == lineStart && (c == '-' || c == '+') && nextIsSpace
            val orderedPrefix = (c == '.' || c == ')') && nextIsSpace && index > lineStart &&
                value.substring(lineStart, index).all(Char::isDigit)
            if (unorderedPrefix || orderedPrefix) append('\\')
            if (c in "\\`*_{}[]<>#>|") append('\\')
            append(c)
            if (c == '\n') lineStart = index + 1
        }
    }
}
