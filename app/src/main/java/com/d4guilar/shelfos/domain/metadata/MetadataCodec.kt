// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

/**
 * The `identifiersJson`, `creditsJson` and `provenanceJson` columns: small JSON arrays of string arrays, written and read
 * here without a JSON dependency (the domain stays JVM-testable). Decoding never throws: malformed JSON, unknown enum
 * names and invalid identifiers are dropped, so an unexpected stored value degrades to "unknown", never a crash.
 */
object MetadataCodec {
    fun identifiers(list: List<PublicationIdentifier>): String? =
        list.takeIf { it.isNotEmpty() }?.let { rows(it.map { id -> listOf(id.type.name, id.value) }) }

    fun identifiers(json: String?): List<PublicationIdentifier> = parse(json).mapNotNull { row ->
        val type = row.getOrNull(0)?.let { n -> IdentifierType.entries.firstOrNull { it.name == n } } ?: return@mapNotNull null
        val value = row.getOrNull(1) ?: return@mapNotNull null
        // Re-validated on read: only a value the detector itself would produce survives.
        when (type) {
            IdentifierType.ISBN_10 -> IdentifierDetector.isbn(value, schemeSaysIsbn = true)
            IdentifierType.ISBN_13 -> IdentifierDetector.isbn(value)
            IdentifierType.UPC_A, IdentifierType.EAN_13 -> IdentifierDetector.gtin(value)
        }?.takeIf { it.type == type && it.value == value }
    }

    fun credits(list: List<Credit>): String? = list.takeIf { it.isNotEmpty() }?.let { rows(it.map { c -> listOf(c.role.name, c.name) }) }

    fun credits(json: String?): List<Credit> = parse(json).mapNotNull { row ->
        val role = row.getOrNull(0)?.let { n -> CreditRole.entries.firstOrNull { it.name == n } } ?: return@mapNotNull null
        row.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { Credit(role, it) }
    }

    /** One row per field: `[field, origin, providerId, providerRef, confidence, resolvedAt]`. */
    fun provenance(map: Map<MetadataField, FieldProvenance>): String? = map.takeIf { it.isNotEmpty() }?.let { m ->
        rows(m.entries.sortedBy { it.key.ordinal }.map { (f, p) ->
            listOf(f.name, p.origin.name, p.providerId, p.providerRef, p.confidence?.name, p.resolvedAt?.toString())
        })
    }

    fun provenance(json: String?): Map<MetadataField, FieldProvenance> = parse(json).mapNotNull { row ->
        val field = row.getOrNull(0)?.let { n -> MetadataField.entries.firstOrNull { it.name == n } } ?: return@mapNotNull null
        val origin = row.getOrNull(1)?.let { n -> MetadataOrigin.entries.firstOrNull { it.name == n } } ?: MetadataOrigin.UNKNOWN
        field to FieldProvenance(origin, row.getOrNull(2), row.getOrNull(3),
            row.getOrNull(4)?.let { n -> MatchConfidence.entries.firstOrNull { it.name == n } }, row.getOrNull(5)?.toLongOrNull())
    }.toMap()

    private fun rows(rows: List<List<String?>>): String = rows.joinToString(",", "[", "]") { row ->
        row.joinToString(",", "[", "]") { v -> if (v == null) "null" else quote(v) }
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\""); c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(java.util.Locale.ROOT, c.code))
            else -> append(c)
        }
        append('"')
    }

    /** Parses exactly `[[string|null, …], …]`; anything else yields an empty list. */
    internal fun parse(json: String?): List<List<String?>> {
        if (json.isNullOrBlank()) return emptyList()
        var i = 0
        fun ws() { while (i < json.length && json[i].isWhitespace()) i++ }
        fun expect(c: Char) { ws(); if (i >= json.length || json[i] != c) throw IllegalArgumentException(); i++ }
        fun peek(): Char? { ws(); return json.getOrNull(i) }
        fun string(): String? {
            ws()
            if (json.startsWith("null", i)) { i += 4; return null }
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = json.getOrNull(i++) ?: throw IllegalArgumentException()
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = json.getOrNull(i++)) {
                        '"', '\\', '/' -> out.append(e)
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                        'u' -> { out.append(json.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> throw IllegalArgumentException()
                    }
                    else -> out.append(c)
                }
            }
        }
        fun <T> list(item: () -> T): List<T> {
            expect('[')
            val out = ArrayList<T>()
            if (peek() == ']') { i++; return out }
            while (true) {
                out += item()
                when (peek()) { ',' -> i++; ']' -> { i++; return out }; else -> throw IllegalArgumentException() }
            }
        }
        return try { list { list(::string) }.also { ws(); if (i != json.length) throw IllegalArgumentException() } }
        catch (_: RuntimeException) { emptyList() }
    }
}
