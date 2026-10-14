// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations.portable

import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.AnnotationLimits
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/** Dependency-free streaming JSON codec: it never creates a source-sized String or generic JSON object tree. */
object PortableKnowledgeJson {
    private val timestampFormat = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 3, 3, true)
        .appendLiteral('Z')
        .toFormatter()
        .withZone(ZoneOffset.UTC)
    private val timestampPattern = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")

    fun write(document: PortableKnowledgeDocument, output: OutputStream) {
        val writer = JsonSink(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.obj {
            name("schema"); string(PortableKnowledgeSchema.NAME)
            name("schemaVersion"); number(PortableKnowledgeSchema.VERSION)
            name("exportedAt"); string(formatTimestamp(document.exportedAt))
            name("appVersion"); string(document.appVersion)
            name("publications"); array {
                document.publications.sortedBy { it.libraryItemId }.forEach { publication ->
                    valueSeparator(); obj {
                        name("libraryItemId"); string(publication.libraryItemId)
                        name("title"); string(publication.title)
                        name("creator"); string(publication.creator)
                        name("category"); string(publication.category.name)
                        name("format"); string(publication.format.name)
                        name("sourceRemoved"); bool(publication.sourceRemoved)
                        name("annotations"); array {
                            publication.annotations.sortedBy { it.id }.forEach { annotation ->
                                valueSeparator(); writeAnnotation(annotation)
                            }
                        }
                    }
                }
            }
        }
        writer.finish()
    }

    /** Rows arrive in (publication id, annotation id) order; only one row and its header stay live here. */
    suspend fun writeRows(exportedAt: Long, appVersion: String, rows: Flow<PortableExportRow>, output: OutputStream) {
        val writer = JsonSink(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.beginObject()
        writer.name("schema"); writer.string(PortableKnowledgeSchema.NAME)
        writer.name("schemaVersion"); writer.number(PortableKnowledgeSchema.VERSION)
        writer.name("exportedAt"); writer.string(formatTimestamp(exportedAt))
        writer.name("appVersion"); writer.string(appVersion)
        writer.name("publications"); writer.beginArray()
        var groupId: String? = null
        rows.collect { row ->
            val publication = row.publication
            if (publication.libraryItemId != groupId) {
                if (groupId != null) { writer.endArray(); writer.endObject() }
                writer.valueSeparator(); writer.beginObject()
                writer.name("libraryItemId"); writer.string(publication.libraryItemId)
                writer.name("title"); writer.string(publication.title)
                writer.name("creator"); writer.string(publication.creator)
                writer.name("category"); writer.string(publication.category.name)
                writer.name("format"); writer.string(publication.format.name)
                writer.name("sourceRemoved"); writer.bool(publication.sourceRemoved)
                writer.name("annotations"); writer.beginArray()
                groupId = publication.libraryItemId
            }
            writer.valueSeparator(); writer.writeAnnotation(row.annotation)
        }
        if (groupId != null) { writer.endArray(); writer.endObject() }
        writer.endArray(); writer.endObject(); writer.finish()
    }

    private fun JsonSink.writeAnnotation(annotation: PortableAnnotation) = obj {
        name("id"); string(annotation.id)
        name("kind"); string(annotation.kind.name)
        name("locatorFormat"); string(annotation.locatorFormat.name)
        name("locatorJson"); string(annotation.locatorJson)
        name("progressSnapshot"); number(annotation.progressSnapshot)
        name("orderKey"); nullableNumber(annotation.orderKey)
        name("title"); nullableString(annotation.title)
        name("selectedText"); nullableString(annotation.selectedText)
        name("body"); nullableString(annotation.body)
        name("styleKey"); nullableString(annotation.styleKey)
        name("createdAt"); string(formatTimestamp(annotation.createdAt))
        name("updatedAt"); string(formatTimestamp(annotation.updatedAt))
    }

    fun read(input: InputStream, maxBytes: Long = PortableKnowledgeSchema.MAX_INPUT_BYTES): PortableKnowledgeDocument {
        val counted = LimitedInputStream(input, maxBytes)
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = Channels.newReader(Channels.newChannel(counted), decoder, -1)
        try {
            return Parser(reader).document()
        } catch (e: UnsupportedPortableKnowledgeVersionException) {
            throw e
        } catch (e: InvalidPortableKnowledgeException) {
            throw e
        } catch (e: Exception) {
            throw InvalidPortableKnowledgeException("invalid ShelfOS knowledge JSON", e)
        }
    }

    fun formatTimestamp(epochMillis: Long): String = timestampFormat.format(Instant.ofEpochMilli(epochMillis))

    fun parseTimestamp(value: String): Long {
        if (!timestampPattern.matches(value)) throw InvalidPortableKnowledgeException("timestamp must be UTC with millisecond precision")
        return try {
            val instant = Instant.parse(value)
            val millis = instant.toEpochMilli()
            if (formatTimestamp(millis) != value) throw InvalidPortableKnowledgeException("invalid timestamp")
            millis
        } catch (e: InvalidPortableKnowledgeException) {
            throw e
        } catch (e: Exception) {
            throw InvalidPortableKnowledgeException("invalid timestamp", e)
        }
    }

    private class Parser(private val source: Reader) {
        private var lookahead = Int.MIN_VALUE

        fun document(): PortableKnowledgeDocument {
            var schema: String? = null
            var version: Int? = null
            var exportedAt: Long? = null
            var appVersion: String? = null
            var publications: List<PortablePublication>? = null
            val seen = HashSet<String>()
            readObject { name ->
                when (name) {
                    "schema" -> {
                        unique(seen, name); schema = readString(PortableKnowledgeSchema.NAME.length)
                        if (schema != PortableKnowledgeSchema.NAME) throw InvalidPortableKnowledgeException("wrong schema")
                    }
                    "schemaVersion" -> {
                        unique(seen, name); val parsed = readInt(); version = parsed
                        if (parsed != PortableKnowledgeSchema.VERSION) throw UnsupportedPortableKnowledgeVersionException(parsed)
                    }
                    "exportedAt" -> { unique(seen, name); exportedAt = parseTimestamp(readString(24)) }
                    "appVersion" -> { unique(seen, name); appVersion = readString(PortableKnowledgeSchema.MAX_INPUT_BYTES.toInt()) }
                    "publications" -> { unique(seen, name); publications = readPublications() }
                    else -> skipValue()
                }
            }
            endDocument()
            required(seen, "schema", "schemaVersion", "exportedAt", "appVersion", "publications")
            if (schema != PortableKnowledgeSchema.NAME) throw InvalidPortableKnowledgeException("wrong schema")
            if (version != PortableKnowledgeSchema.VERSION) throw UnsupportedPortableKnowledgeVersionException(version ?: -1)
            return PortableKnowledgeDocument(requireNotNull(exportedAt), requireNotNull(appVersion), requireNotNull(publications))
        }

        private fun readPublications(): List<PortablePublication> {
            val publications = ArrayList<PortablePublication>()
            val ids = HashSet<String>()
            val annotationIds = HashSet<String>()
            readArray {
                if (publications.size >= PortableKnowledgeSchema.MAX_PUBLICATIONS)
                    throw InvalidPortableKnowledgeException("too many publication groups")
                val publication = readPublication()
                if (!ids.add(publication.libraryItemId)) throw InvalidPortableKnowledgeException("repeated publication group")
                publication.annotations.forEach {
                    if (!annotationIds.add(it.id)) throw InvalidPortableKnowledgeException("repeated annotation id")
                    if (annotationIds.size > PortableKnowledgeSchema.MAX_ANNOTATIONS)
                        throw InvalidPortableKnowledgeException("too many annotations")
                }
                publications += publication
            }
            return publications
        }

        private fun readPublication(): PortablePublication {
            var id: String? = null; var title: String? = null; var creator: String? = null
            var category: MediaCategory? = null; var format: PublicationFormat? = null
            var sourceRemoved: Boolean? = null; var annotations: List<PortableAnnotation>? = null
            val seen = HashSet<String>()
            readObject { name -> when (name) {
                "libraryItemId" -> { unique(seen, name); id = readString(PortableKnowledgeSchema.MAX_INPUT_BYTES.toInt()) }
                "title" -> { unique(seen, name); title = readString(PortableKnowledgeSchema.MAX_INPUT_BYTES.toInt()) }
                "creator" -> { unique(seen, name); creator = readString(PortableKnowledgeSchema.MAX_INPUT_BYTES.toInt()) }
                "category" -> { unique(seen, name); category = enumValue<MediaCategory>(readString(64), "category") }
                "format" -> { unique(seen, name); format = enumValue<PublicationFormat>(readString(64), "publication format") }
                "sourceRemoved" -> { unique(seen, name); sourceRemoved = readBoolean() }
                "annotations" -> { unique(seen, name); annotations = readAnnotations() }
                else -> skipValue()
            } }
            required(seen, "libraryItemId", "title", "creator", "category", "format", "sourceRemoved", "annotations")
            val publicationId = requireNotNull(id)
            if (publicationId.isBlank()) throw InvalidPortableKnowledgeException("libraryItemId must not be blank")
            val records = requireNotNull(annotations).map { it.copy(libraryItemId = publicationId).also(PortableAnnotation::validate) }
            return PortablePublication(publicationId, requireNotNull(title), requireNotNull(creator), requireNotNull(category),
                requireNotNull(format), requireNotNull(sourceRemoved), records)
        }

        private fun readAnnotations(): List<PortableAnnotation> {
            val records = ArrayList<PortableAnnotation>()
            readArray {
                if (records.size >= PortableKnowledgeSchema.MAX_ANNOTATIONS)
                    throw InvalidPortableKnowledgeException("too many annotations")
                records += readAnnotation()
            }
            return records
        }

        private fun readAnnotation(): PortableAnnotation {
            var id: String? = null; var kind: AnnotationKind? = null; var locatorFormat: LocatorFormat? = null
            var locatorJson: String? = null; var progress: Int? = null; var orderKey: Double? = null
            var title: String? = null; var selected: String? = null; var body: String? = null; var style: String? = null
            var created: Long? = null; var updated: Long? = null
            val seen = HashSet<String>()
            readObject { name -> when (name) {
                "id" -> { unique(seen, name); id = readString(PortableKnowledgeSchema.MAX_INPUT_BYTES.toInt()) }
                "kind" -> { unique(seen, name); kind = enumValue<AnnotationKind>(readString(64), "annotation kind") }
                "locatorFormat" -> { unique(seen, name); locatorFormat = enumValue<LocatorFormat>(readString(64), "locator format") }
                "locatorJson" -> { unique(seen, name); locatorJson = readString(AnnotationLimits.MAX_LOCATOR_BYTES) }
                "progressSnapshot" -> { unique(seen, name); progress = readInt() }
                "orderKey" -> { unique(seen, name); orderKey = readNullableDouble() }
                "title" -> { unique(seen, name); title = readNullableString(PortableKnowledgeSchema.MAX_INPUT_BYTES.toInt()) }
                "selectedText" -> { unique(seen, name); selected = readNullableString(AnnotationLimits.MAX_SELECTED_TEXT) }
                "body" -> { unique(seen, name); body = readNullableString(AnnotationLimits.MAX_BODY) }
                "styleKey" -> { unique(seen, name); style = readNullableString(PortableKnowledgeSchema.MAX_INPUT_BYTES.toInt()) }
                "createdAt" -> { unique(seen, name); created = parseTimestamp(readString(24)) }
                "updatedAt" -> { unique(seen, name); updated = parseTimestamp(readString(24)) }
                else -> skipValue()
            } }
            required(seen, "id", "kind", "locatorFormat", "locatorJson", "progressSnapshot", "orderKey", "title",
                "selectedText", "body", "styleKey", "createdAt", "updatedAt")
            return PortableAnnotation(requireNotNull(id), "", requireNotNull(kind), requireNotNull(locatorFormat),
                requireNotNull(locatorJson), requireNotNull(progress), orderKey, title, selected, body, style,
                requireNotNull(created), requireNotNull(updated))
        }

        private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
            enumValues<T>().firstOrNull { it.name == value }
                ?: throw InvalidPortableKnowledgeException("unknown $label: $value")

        private fun readObject(member: (String) -> Unit) {
            expect('{'); skipWhitespace()
            if (take('}')) return
            while (true) {
                // progressSnapshot is the longest known key; longer keys remain unknown.
                val name = readString(16, discardOverflow = true); skipWhitespace(); expect(':'); member(name); skipWhitespace()
                if (take('}')) return
                expect(',')
            }
        }

        private fun readArray(value: () -> Unit) {
            expect('['); skipWhitespace()
            if (take(']')) return
            while (true) {
                value(); skipWhitespace()
                if (take(']')) return
                expect(',')
            }
        }

        private fun readString(maxChars: Int, discardOverflow: Boolean = false): String {
            skipWhitespace(); expectRaw('"')
            val out = StringBuilder()
            var overflow = false
            fun append(c: Char) {
                if (out.length >= maxChars) {
                    if (!discardOverflow) fail("string exceeds field limit")
                    overflow = true
                } else out.append(c)
            }
            while (true) {
                val c = next()
                when {
                    c < 0 -> fail("unterminated string")
                    c == '"'.code -> return if (overflow) "" else out.toString()
                    c == '\\'.code -> {
                        when (val escaped = next()) {
                            '"'.code, '\\'.code, '/'.code -> append(escaped.toChar())
                            'b'.code -> append('\b'); 'f'.code -> append('\u000c'); 'n'.code -> append('\n')
                            'r'.code -> append('\r'); 't'.code -> append('\t')
                            'u'.code -> append(readHex().toChar())
                            else -> fail("invalid string escape")
                        }
                    }
                    c < 0x20 -> fail("control character in string")
                    else -> append(c.toChar())
                }
            }
        }

        private fun skipString() {
            skipWhitespace(); expectRaw('"')
            while (true) {
                when (val c = next()) {
                    -1 -> fail("unterminated string")
                    '"'.code -> return
                    '\\'.code -> when (next()) {
                        '"'.code, '\\'.code, '/'.code, 'b'.code, 'f'.code, 'n'.code, 'r'.code, 't'.code -> Unit
                        'u'.code -> readHex()
                        else -> fail("invalid string escape")
                    }
                    else -> if (c < 0x20) fail("control character in string")
                }
            }
        }

        private fun readHex(): Int {
            var value = 0
            repeat(4) {
                val digit = next().toChar().digitToIntOrNull(16) ?: fail("invalid unicode escape")
                value = value * 16 + digit
            }
            return value
        }

        private fun readBoolean(): Boolean {
            skipWhitespace()
            return when (peek()) { 't'.code -> { literal("true"); true }; 'f'.code -> { literal("false"); false }; else -> fail("expected boolean") }
        }

        private fun readNullableString(maxChars: Int): String? { skipWhitespace(); return if (peek() == 'n'.code) { literal("null"); null } else readString(maxChars) }
        private fun readNullableDouble(): Double? {
            skipWhitespace(); if (peek() == 'n'.code) { literal("null"); return null }
            val value = readNumber().toDoubleOrNull() ?: fail("invalid number")
            if (!value.isFinite()) fail("number must be finite")
            return value
        }
        private fun readInt(): Int {
            val raw = readNumber()
            if (raw.contains('.') || raw.contains('e', true)) fail("expected integer")
            return raw.toIntOrNull() ?: fail("integer out of range")
        }

        private fun readNumber(keep: Boolean = true): String {
            skipWhitespace(); val out = if (keep) StringBuilder() else null
            fun append(c: Char) { if (out != null) { if (out.length >= 128) fail("number too long"); out.append(c) } }
            if (take('-')) append('-')
            val first = peek()
            if (first == '0'.code) { append(next().toChar()); if (peek() in '0'.code..'9'.code) fail("leading zero") }
            else {
                if (first !in '1'.code..'9'.code) fail("expected number")
                while (peek() in '0'.code..'9'.code) append(next().toChar())
            }
            if (take('.')) { append('.'); if (peek() !in '0'.code..'9'.code) fail("invalid fraction"); while (peek() in '0'.code..'9'.code) append(next().toChar()) }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                append(next().toChar()); if (peek() == '+'.code || peek() == '-'.code) append(next().toChar())
                if (peek() !in '0'.code..'9'.code) fail("invalid exponent")
                while (peek() in '0'.code..'9'.code) append(next().toChar())
            }
            return out?.toString().orEmpty()
        }

        private fun skipValue(depth: Int = 0) {
            if (depth > 256) fail("JSON nesting too deep")
            skipWhitespace()
            when (peek()) {
                '"'.code -> skipString()
                '{'.code -> readObject { skipValue(depth + 1) }
                '['.code -> readArray { skipValue(depth + 1) }
                't'.code -> literal("true"); 'f'.code -> literal("false"); 'n'.code -> literal("null")
                else -> readNumber(false)
            }
        }

        private fun required(seen: Set<String>, vararg names: String) {
            names.firstOrNull { it !in seen }?.let { fail("missing required member: $it") }
        }
        private fun unique(seen: MutableSet<String>, name: String) { if (!seen.add(name)) fail("repeated member: $name") }
        private fun literal(value: String) { value.forEach { expectRaw(it) } }
        private fun endDocument() { skipWhitespace(); if (peek() != -1) fail("trailing JSON") }
        private fun expect(char: Char) { skipWhitespace(); expectRaw(char) }
        private fun expectRaw(char: Char) { if (next() != char.code) fail("expected '$char'") }
        private fun take(char: Char): Boolean { skipWhitespace(); if (peek() != char.code) return false; next(); return true }
        private fun skipWhitespace() { while (peek() == ' '.code || peek() == '\n'.code || peek() == '\r'.code || peek() == '\t'.code) next() }
        private fun peek(): Int { if (lookahead == Int.MIN_VALUE) lookahead = source.read(); return lookahead }
        private fun next(): Int { val value = peek(); lookahead = Int.MIN_VALUE; return value }
        private fun fail(message: String): Nothing = throw InvalidPortableKnowledgeException(message)
    }

    private class LimitedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) add(it) }
        private fun add(amount: Int) { count += amount; if (count > maximum) throw InvalidPortableKnowledgeException("knowledge file exceeds 64 MiB") }
    }

    private class JsonSink(private val output: OutputStreamWriter) {
        private val first = ArrayDeque<Boolean>()
        fun beginObject() { output.write("{"); first.addLast(true) }
        fun endObject() { first.removeLast(); output.write("}") }
        fun beginArray() { output.write("["); first.addLast(true) }
        fun endArray() { first.removeLast(); output.write("]") }
        fun obj(block: JsonSink.() -> Unit) { beginObject(); block(); endObject() }
        fun array(block: JsonSink.() -> Unit) { beginArray(); block(); endArray() }
        fun name(value: String) { valueSeparator(); string(value); output.write(":") }
        fun valueSeparator() { if (first.isEmpty()) return; if (first.last()) first[first.lastIndex] = false else output.write(",") }
        fun string(value: String) { output.write("\""); value.forEach { c -> when (c) {
            '"' -> output.write("\\\""); '\\' -> output.write("\\\\"); '\b' -> output.write("\\b"); '\u000c' -> output.write("\\f")
            '\n' -> output.write("\\n"); '\r' -> output.write("\\r"); '\t' -> output.write("\\t")
            else -> if (c.code < 0x20 || Character.isSurrogate(c))
                output.write("\\u" + c.code.toString(16).padStart(4, '0')) else output.write(c.code)
        } }; output.write("\"") }
        fun nullableString(value: String?) { if (value == null) output.write("null") else string(value) }
        fun number(value: Int) { output.write(value.toString()) }
        fun nullableNumber(value: Double?) { output.write(value?.toString() ?: "null") }
        fun bool(value: Boolean) { output.write(value.toString()) }
        fun finish() { output.flush() }
    }
}
