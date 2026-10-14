// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations.portable

import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PortableKnowledgeTest {
    private val locator = " { malformed but opaque 🚀 } "
    private fun annotation(id: String = "a", item: String = "p", kind: AnnotationKind = AnnotationKind.NOTE) =
        PortableAnnotation(id, item, kind,
            if (kind == AnnotationKind.HIGHLIGHT) LocatorFormat.READIUM_LOCATOR_1 else LocatorFormat.FIXED_PAGE_1,
            locator, 42, 1.25, "# title", if (kind == AnnotationKind.HIGHLIGHT) "مرحبا 👋" else null,
            if (kind == AnnotationKind.NOTE) "line\n> hostile `*_[]\n- item\n1. item" else null,
            if (kind == AnnotationKind.HIGHLIGHT) "future-custom-style" else null, 1_700_000_000_123, 1_700_000_100_456)
            .let { if (kind == AnnotationKind.BOOKMARK) it.copy(title = "bookmark") else it }

    private fun document(publications: List<PortablePublication> = listOf(
        PortablePublication("p", "Book /private/nope", "كاتب", MediaCategory.BOOK, PublicationFormat.EPUB, false,
            listOf(annotation("n"), annotation("h", kind = AnnotationKind.HIGHLIGHT), annotation("b", kind = AnnotationKind.BOOKMARK)))
    )) = PortableKnowledgeDocument(1_700_000_200_789, "test", publications)

    @Test fun canonicalJsonRoundTripsEveryFieldAndOrdersByIds() {
        val second = PortablePublication("a", "A", "", MediaCategory.DOCUMENT, PublicationFormat.PDF, true,
            listOf(annotation("z", "a"), annotation("a", "a")))
        val expected = document(document().publications + second)
        val bytes = ByteArrayOutputStream().also { PortableKnowledgeJson.write(expected, it) }.toByteArray()
        val text = bytes.toString(Charsets.UTF_8)
        val restored = PortableKnowledgeJson.read(ByteArrayInputStream(bytes))
        assertEquals(expected.copy(publications = expected.publications.sortedBy { it.libraryItemId }.map {
            it.copy(annotations = it.annotations.sortedBy { row -> row.id })
        }), restored)
        assertTrue(text.indexOf("\"libraryItemId\":\"a\"") < text.indexOf("\"libraryItemId\":\"p\""))
        assertTrue(text.indexOf("\"id\":\"a\"") < text.indexOf("\"id\":\"z\""))
        assertTrue(text.contains("future-custom-style"))
        assertTrue(text.contains(" { malformed but opaque"))
        listOf("sourceUri", "managedPath", "fileName", "byteSize", "contentFingerprint", "coverBytes", "publicationBytes")
            .forEach { assertFalse(text.contains("\"$it\"")) }
        val reexported = ByteArrayOutputStream().also { PortableKnowledgeJson.write(restored, it) }.toByteArray()
        assertEquals(restored, PortableKnowledgeJson.read(ByteArrayInputStream(reexported)))
    }

    @Test fun unknownMembersAreIgnoredButUnknownEnumsAndStructuralDuplicatesFail() {
        val bytes = ByteArrayOutputStream().also { PortableKnowledgeJson.write(document(), it) }.toString("UTF-8")
        val withUnknown = bytes.replaceFirst("{", "{\"future\":{\"nested\":[1,true,null]},")
            .replaceFirst("\"libraryItemId\"", "\"futurePublication\":false,\"libraryItemId\"")
            .replaceFirst("\"id\"", "\"futureAnnotation\":\"ok\",\"id\"")
        assertEquals(3, parse(withUnknown).publications.single().annotations.size)
        assertInvalid(bytes.replace("\"kind\":\"NOTE\"", "\"kind\":\"FUTURE\""))
        assertInvalid(bytes.replace("\"locatorFormat\":\"FIXED_PAGE_1\"", "\"locatorFormat\":\"FUTURE\""))
        assertInvalid(bytes.replace("\"category\":\"BOOK\"", "\"category\":\"FUTURE\""))
        assertInvalid(bytes.replace("\"format\":\"EPUB\"", "\"format\":\"FUTURE\""))
        val group = document().publications.single()
        val repeatedGroup = document(listOf(group, group))
        assertInvalid(ByteArrayOutputStream().also { PortableKnowledgeJson.write(repeatedGroup, it) }.toString("UTF-8"))
        assertInvalid(bytes.replace("\"id\":\"h\"", "\"id\":\"n\""))
        assertInvalid("{")
    }

    @Test fun schemaTimestampDomainAndByteLimitFailuresRejectTheWholeDocument() {
        val json = ByteArrayOutputStream().also { PortableKnowledgeJson.write(document(), it) }.toString("UTF-8")
        assertInvalid(json.replace("shelfos.knowledge", "other"))
        assertTrue(runCatching { parse(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }.exceptionOrNull()
            is UnsupportedPortableKnowledgeVersionException)
        assertInvalid(json.replace("\"appVersion\":\"test\",", ""))
        assertInvalid(json.replace("2023-11-14T22:16:40.789Z", "2023-11-14T22:16:40Z"))
        assertInvalid(json.replace("\"progressSnapshot\":42", "\"progressSnapshot\":101"))
        assertInvalid(json.replace("\"body\":\"line", "\"body\":null,\"ignored\":\"line"))
        assertTrue(runCatching { PortableKnowledgeJson.read(ByteArrayInputStream(json.toByteArray()), 64) }.exceptionOrNull()
            is InvalidPortableKnowledgeException)
    }

    @Test fun classificationIsExactAdditiveAndSnapshotLocalWins() {
        val incoming = document(listOf(
            PortablePublication("live", "Imported", "I", MediaCategory.BOOK, PublicationFormat.EPUB, true,
                listOf(annotation("duplicate", "live"), annotation("conflict", "live"), annotation("new-live", "live"))),
            PortablePublication("orphan", "Imported orphan", "O", MediaCategory.DOCUMENT, PublicationFormat.PDF, false,
                listOf(annotation("new-orphan", "orphan"))),
        ))
        val duplicate = annotation("duplicate", "live").toAnnotation()
        val conflicting = annotation("conflict", "elsewhere").toAnnotation()
        val local = PortableLocalState(mapOf(duplicate.id to duplicate, conflicting.id to conflicting), setOf("live"), mapOf(
            "orphan" to PortableSourceContext("orphan", "Local wins", "L", MediaCategory.DOCUMENT, PublicationFormat.PDF)))
        val preview = classifyPortableKnowledge(incoming, local)
        assertEquals(2, preview.newCount); assertEquals(1, preview.duplicateCount); assertEquals(1, preview.conflictCount)
        assertEquals(0, preview.orphanGroupsToCreate); assertEquals(1, preview.sourceContextWarnings)
        assertTrue(preview.publications.first { it.libraryItemId == "live" }.live)
        assertEquals(PortableSnapshotStatus.NONE, preview.publications.first { it.libraryItemId == "live" }.snapshotStatus)
        assertEquals(PortableSnapshotStatus.LOCAL_WINS, preview.publications.first { it.libraryItemId == "orphan" }.snapshotStatus)
    }

    @Test fun actualPublicationGuardRejectsTenThousandAndOneGroups() {
        val groups = (0..PortableKnowledgeSchema.MAX_PUBLICATIONS).map { n ->
            PortablePublication("p$n", "", "", MediaCategory.DOCUMENT, PublicationFormat.PDF, false, emptyList())
        }
        val bytes = ByteArrayOutputStream().also { PortableKnowledgeJson.write(document(groups), it) }.toByteArray()
        assertTrue(runCatching { PortableKnowledgeJson.read(ByteArrayInputStream(bytes)) }.exceptionOrNull() is InvalidPortableKnowledgeException)
        assertEquals(64L * 1024 * 1024, PortableKnowledgeSchema.MAX_INPUT_BYTES)
        assertEquals(100_000, PortableKnowledgeSchema.MAX_ANNOTATIONS)
    }

    @Test fun actualAnnotationGuardRejectsOneHundredThousandAndOneRows() {
        val rows = (0..PortableKnowledgeSchema.MAX_ANNOTATIONS).map { n ->
            PortableAnnotation("a$n", "p", AnnotationKind.BOOKMARK, LocatorFormat.FIXED_PAGE_1,
                "x", 0, null, null, null, null, null, 0, 0)
        }
        val large = document(listOf(PortablePublication("p", "", "", MediaCategory.DOCUMENT,
            PublicationFormat.PDF, false, rows)))
        val bytes = ByteArrayOutputStream().also { PortableKnowledgeJson.write(large, it) }.toByteArray()
        assertTrue(runCatching { PortableKnowledgeJson.read(ByteArrayInputStream(bytes)) }.exceptionOrNull() is InvalidPortableKnowledgeException)
    }

    @Test fun markdownIsReadableEscapedDeterministicAndContainsNoRestorePayload() {
        val labels = PortableMarkdownLabels("Export", "Removed", "Bookmark", "Highlight", "Note",
            { "Page $it" }, { "$it percent" }, { "Created $it" }, { "Updated $it" })
        val output = ByteArrayOutputStream().also { PortableKnowledgeMarkdown.write(document(), it, labels) }.toString("UTF-8")
        assertTrue(output.contains("# Export")); assertTrue(output.contains("## Book /private/nope"))
        assertTrue(output.contains("> مرحبا 👋")); assertTrue(output.contains("\\> hostile \\`\\*\\_\\[\\]"))
        assertTrue(output.contains("\\- item")); assertTrue(output.contains("1\\. item"))
        assertFalse(output.contains("shelfos.knowledge")); assertFalse(output.contains("locatorJson"))
    }

    @Test fun largeIgnoredStringIsSkippedAndKnownFieldsRejectAtTheirBounds() {
        val json = ByteArrayOutputStream().also { PortableKnowledgeJson.write(document(), it) }.toString("UTF-8")
        val prefix = "{\"future\":\"".toByteArray()
        val suffix = ("\"," + json.drop(1)).toByteArray()
        val unknown = object : java.io.InputStream() {
            var index = 0
            override fun read(): Int = when {
                index < prefix.size -> prefix[index++].toInt() and 0xff
                index < prefix.size + 16 * 1024 * 1024 -> { index++; 'x'.code }
                index < prefix.size + 16 * 1024 * 1024 + suffix.size -> suffix[index++ - prefix.size - 16 * 1024 * 1024].toInt() and 0xff
                else -> -1
            }
        }
        assertEquals(3, PortableKnowledgeJson.read(unknown).publications.single().annotations.size)
        val longBody = json.replace("\"body\":\"line", "\"body\":\"${"x".repeat(20_001)}\" ,\"ignored\":\"line")
        assertEquals("string exceeds field limit", runCatching { parse(longBody) }.exceptionOrNull()?.message)
        val longLocator = json.replace("\"locatorJson\":\" { malformed but opaque", "\"locatorJson\":\"${"x".repeat(65_537)}\",\"ignored\":\" { malformed but opaque")
        assertEquals("string exceeds field limit", runCatching { parse(longLocator) }.exceptionOrNull()?.message)
    }

    @Test fun byteCounterAcceptsExactLimitAndRejectsOneExtraByte() {
        val bytes = ByteArrayOutputStream().also { PortableKnowledgeJson.write(document(), it) }.toByteArray()
        val padded = bytes + byteArrayOf(' '.code.toByte())
        assertEquals(3, PortableKnowledgeJson.read(ByteArrayInputStream(padded), padded.size.toLong()).publications.single().annotations.size)
        assertTrue(runCatching { PortableKnowledgeJson.read(ByteArrayInputStream(padded), bytes.size.toLong()) }.exceptionOrNull()
            is InvalidPortableKnowledgeException)
    }

    @Test fun domainValidStyleHasNoInventedPerFieldCap() {
        val style = "custom-" + "x".repeat(70_000)
        val original = document().let { doc -> doc.copy(publications = doc.publications.map { publication ->
            publication.copy(annotations = publication.annotations.map { annotation ->
                if (annotation.kind == AnnotationKind.HIGHLIGHT) annotation.copy(styleKey = style) else annotation
            })
        }) }
        val bytes = ByteArrayOutputStream().also { PortableKnowledgeJson.write(original, it) }.toByteArray()
        assertEquals(style, PortableKnowledgeJson.read(ByteArrayInputStream(bytes)).publications.single()
            .annotations.first { it.kind == AnnotationKind.HIGHLIGHT }.styleKey)
    }

    private fun parse(json: String) = PortableKnowledgeJson.read(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
    private fun assertInvalid(json: String) = assertTrue(runCatching { parse(json) }.exceptionOrNull() is InvalidPortableKnowledgeException)
}
