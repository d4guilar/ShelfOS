// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.metadata.openlibrary

import com.d4guilar.shelfos.core.network.HttpResponse
import com.d4guilar.shelfos.core.network.HttpTransport
import com.d4guilar.shelfos.data.metadata.MetadataStore
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.core.database.LocalCursor
import com.d4guilar.shelfos.data.metadata.StoredMetadata
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Never touches a network: answers from a map of URL → response and records every request. */
class FakeHttp(var handler: suspend (String) -> HttpResponse = { json(404, "{}") }) : HttpTransport {
    val requests = ArrayList<String>()
    override suspend fun get(url: String): HttpResponse { requests += url; return handler(url) }
}

fun json(status: Int, body: String, vararg headers: Pair<String, String>) =
    HttpResponse(status, mapOf("content-type" to "application/json; charset=utf-8") + headers.associate { it.first.lowercase() to it.second }, body.toByteArray(Charsets.UTF_8))

/** A realistic edition record (field names as Open Library publishes them), with cover fields present on purpose: they must be ignored. */
fun editionJson(key: String = "OL7353617M", title: String = "Matilda", isbn13: String? = "9780140328721", isbn10: String? = "0140328726",
    authors: List<String> = listOf("OL34184A"), publishers: List<String> = listOf("Puffin"), date: String? = "1998", language: String? = "eng",
    extra: String = "") = buildString {
    append("""{"key":"/books/$key","title":${q(title)},"covers":[240726,9999],"works":[{"key":"/works/OL45804W"}],"publish_date":${q(date)}""")
    append(""","publishers":[${publishers.joinToString(",") { q(it) }}],"authors":[${authors.joinToString(",") { """{"key":"/authors/$it"}""" }}]""")
    if (isbn13 != null) append(""","isbn_13":["$isbn13"]""")
    if (isbn10 != null) append(""","isbn_10":["$isbn10"]""")
    if (language != null) append(""","languages":[{"key":"/languages/$language"}]""")
    if (extra.isNotEmpty()) append(",").append(extra)
    append("}")
}

private fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun authorJson(name: String) = """{"key":"/authors/OL34184A","name":${q(name)},"photos":[1]}"""

/** An in-memory [MetadataStore] applying the real precedence rule ([resolveMetadata]); counts writes so tests can prove a preview writes nothing. */
class InMemoryStore : MetadataStore {
    val items = LinkedHashMap<String, ResolvedMetadata>()
    var writes = 0
    override suspend fun resolved(id: String) = items[id]
    override suspend fun apply(id: String, proposals: List<Proposed>, analysisVersion: Int?): Boolean {
        val c = items[id] ?: return false
        writes++; items[id] = resolveMetadata(c, proposals); return true
    }
    override fun observe(id: String): Flow<StoredMetadata?> = emptyFlow()
    override suspend fun pendingLocal(version: Int, after: LocalCursor?, limit: Int) = emptyList<LocalCursor>()
}

fun bookItem(id: String = "b1", fileName: String = "Matilda.epub", category: MediaCategory = MediaCategory.BOOK) =
    LibraryItem(id, fileName.substringBeforeLast('.'), category = category, sourceUri = "test:$id", format = PublicationFormat.EPUB, fileName = fileName, byteSize = 1)

private val FILE_NAME = FieldProvenance(MetadataOrigin.FILENAME_INFERENCE)
private val EMBEDDED = FieldProvenance(MetadataOrigin.EMBEDDED)

/** A Book as local analysis leaves it: a file-name title, optionally embedded identifiers and values. */
fun InMemoryStore.addBook(id: String = "b1", title: String = "Matilda", creator: String = "", isbn: String? = null, embedded: MetadataValues = MetadataValues(),
    titleOrigin: MetadataOrigin = MetadataOrigin.FILENAME_INFERENCE) {
    val values = embedded.copy(identifiers = embedded.identifiers + listOfNotNull(isbn?.let { PublicationIdentifier(IdentifierType.ISBN_13, it) }))
    val provenance = HashMap<MetadataField, FieldProvenance>()
    provenance[MetadataField.TITLE] = FieldProvenance(titleOrigin)
    provenance[MetadataField.CREATOR] = FieldProvenance(if (creator.isBlank()) MetadataOrigin.UNKNOWN else MetadataOrigin.EMBEDDED)
    MetadataField.entries.forEach { f -> if (f != MetadataField.TITLE && f != MetadataField.CREATOR && !isBlankValue(values[f])) provenance[f] = EMBEDDED }
    items[id] = ResolvedMetadata(title, creator, values, provenance)
}
