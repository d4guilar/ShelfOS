// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

/**
 * Field-level metadata provenance (Phase 4.5B, plan §9, §16). Title and creator stay on `library_item` with their
 * existing `titleOrigin`/`creatorOrigin` strings; every other field lives in `publication_metadata`. One precedence
 * function, [resolveMetadata], decides every automatic and manual write.
 */
enum class MetadataField { TITLE, CREATOR, SUBTITLE, DESCRIPTION, PUBLISHER, PUBLISHED_DATE, LANGUAGE, SERIES, VOLUME, ISSUE, IDENTIFIERS, CREDITS }

/** Strongest first (§9). [storageKey] keeps the legacy `library_item` origin strings readable. */
enum class MetadataOrigin(val storageKey: String, val external: Boolean = false) {
    USER("user"), EMBEDDED("embedded"),
    EXACT_IDENTIFIER_MATCH("exact_identifier_match", external = true),
    HIGH_CONFIDENCE_EXTERNAL_MATCH("high_confidence_external_match", external = true),
    INFERRED_EXTERNAL_MATCH("inferred_external_match", external = true),
    FILENAME_INFERENCE("filename"), UNKNOWN("unknown");

    companion object {
        /** Legacy and new stored strings; anything unexpected is UNKNOWN (fails safe: replaceable, never USER). */
        fun fromStorage(key: String?): MetadataOrigin = entries.firstOrNull { it.storageKey == key } ?: UNKNOWN
    }
}

enum class MatchConfidence { VERY_HIGH, MEDIUM, LOW }

data class FieldProvenance(
    val origin: MetadataOrigin, val providerId: String? = null, val providerRef: String? = null,
    val confidence: MatchConfidence? = null, val resolvedAt: Long? = null,
)

enum class CreditRole { WRITER, PENCILLER, INKER, COLORIST, LETTERER, COVER_ARTIST, EDITOR, TRANSLATOR }
data class Credit(val role: CreditRole, val name: String)

/** The non-title fields. Text is kept as the source wrote it (trimmed, whitespace-collapsed), never translated. */
data class MetadataValues(
    val subtitle: String? = null, val description: String? = null, val publisher: String? = null,
    /** `YYYY`, `YYYY-MM` or `YYYY-MM-DD` ([normalizedDate]). */
    val publishedDate: String? = null,
    /** A BCP 47 language tag ([normalizedLanguage]). */
    val language: String? = null,
    val series: String? = null, val volume: String? = null, val issue: String? = null,
    val identifiers: List<PublicationIdentifier> = emptyList(), val credits: List<Credit> = emptyList(),
) {
    operator fun get(field: MetadataField): Any? = when (field) {
        MetadataField.SUBTITLE -> subtitle; MetadataField.DESCRIPTION -> description; MetadataField.PUBLISHER -> publisher
        MetadataField.PUBLISHED_DATE -> publishedDate; MetadataField.LANGUAGE -> language; MetadataField.SERIES -> series
        MetadataField.VOLUME -> volume; MetadataField.ISSUE -> issue; MetadataField.IDENTIFIERS -> identifiers
        MetadataField.CREDITS -> credits; MetadataField.TITLE, MetadataField.CREATOR -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun with(field: MetadataField, value: Any?): MetadataValues = when (field) {
        MetadataField.SUBTITLE -> copy(subtitle = value as String?); MetadataField.DESCRIPTION -> copy(description = value as String?)
        MetadataField.PUBLISHER -> copy(publisher = value as String?); MetadataField.PUBLISHED_DATE -> copy(publishedDate = value as String?)
        MetadataField.LANGUAGE -> copy(language = value as String?); MetadataField.SERIES -> copy(series = value as String?)
        MetadataField.VOLUME -> copy(volume = value as String?); MetadataField.ISSUE -> copy(issue = value as String?)
        MetadataField.IDENTIFIERS -> copy(identifiers = (value as List<PublicationIdentifier>?).orEmpty())
        MetadataField.CREDITS -> copy(credits = (value as List<Credit>?).orEmpty())
        MetadataField.TITLE, MetadataField.CREATOR -> this
    }

    /** Every non-blank field as a proposal with one provenance (embedded or filename evidence). */
    fun proposals(provenance: FieldProvenance): List<Proposed> =
        MetadataField.entries.mapNotNull { f -> this[f]?.takeUnless(::isBlankValue)?.let { Proposed(f, it, provenance) } }
}

/** A publication's resolved metadata: title/creator (from `library_item`) plus [values], each with provenance. */
data class ResolvedMetadata(
    val title: String, val creator: String, val values: MetadataValues = MetadataValues(),
    val provenance: Map<MetadataField, FieldProvenance> = emptyMap(),
) {
    fun value(field: MetadataField): Any? = when (field) { MetadataField.TITLE -> title; MetadataField.CREATOR -> creator; else -> values[field] }
    fun origin(field: MetadataField): MetadataOrigin? = provenance[field]?.origin
}

data class Proposed(val field: MetadataField, val value: Any, val provenance: FieldProvenance)

fun isBlankValue(value: Any?): Boolean = value == null || (value is String && value.isBlank()) || (value is List<*> && value.isEmpty())

/**
 * The one precedence rule (§9, §16):
 * - `USER` replaces anything, and is never replaced automatically (a user-cleared field stays cleared).
 * - A missing/blank proposal never deletes existing metadata.
 * - External origins fill only unresolved fields (empty, filename-inferred, unknown) or refresh a weaker-or-equal
 *   external value; they never replace `EMBEDDED`. A title needs a VERY_HIGH external match (§9 "title is special").
 * - Otherwise a stronger-or-equal origin replaces (re-analysis may refresh `EMBEDDED`; embedded beats filename).
 */
fun canReplace(field: MetadataField, current: FieldProvenance?, currentBlank: Boolean, proposed: FieldProvenance): Boolean = when {
    proposed.origin == MetadataOrigin.USER -> true
    current?.origin == MetadataOrigin.USER -> false
    proposed.origin.external -> (field != MetadataField.TITLE || proposed.confidence == MatchConfidence.VERY_HIGH) &&
        (currentBlank || current == null || current.origin == MetadataOrigin.FILENAME_INFERENCE || current.origin == MetadataOrigin.UNKNOWN ||
            (current.origin.external && proposed.origin.ordinal <= current.origin.ordinal))
    currentBlank || current == null -> true
    else -> proposed.origin.ordinal <= current.origin.ordinal
}

/** Applies [proposals] to [current] under [canReplace]; the result equals [current] when nothing may change. */
fun resolveMetadata(current: ResolvedMetadata, proposals: List<Proposed>): ResolvedMetadata = proposals.fold(current) { acc, p ->
    if (isBlankValue(p.value) && p.provenance.origin != MetadataOrigin.USER) return@fold acc
    if (!canReplace(p.field, acc.provenance[p.field], isBlankValue(acc.value(p.field)), p.provenance)) return@fold acc
    val provenance = acc.provenance + (p.field to p.provenance)
    when (p.field) {
        MetadataField.TITLE -> acc.copy(title = (p.value as String).trim(), provenance = provenance)
        MetadataField.CREATOR -> acc.copy(creator = (p.value as String).trim(), provenance = provenance)
        else -> acc.copy(values = acc.values.with(p.field, p.value.takeUnless(::isBlankValue)), provenance = provenance)
    }
}

/**
 * Manual edits (§16): only fields the user changed relative to what the editor showed ([original]) become `USER`, so a
 * value analysis wrote while the editor was open is never overwritten with the stale one the editor still displays.
 */
fun userEdits(original: MetadataValues, edited: MetadataValues, at: Long): List<Proposed> = MetadataField.entries
    .filter { it != MetadataField.TITLE && it != MetadataField.CREATOR && normalizeEdit(edited[it]) != normalizeEdit(original[it]) }
    .map { Proposed(it, normalizeEdit(edited[it]) ?: "", FieldProvenance(MetadataOrigin.USER, resolvedAt = at)) }

private fun normalizeEdit(value: Any?): Any? = when (value) {
    is String -> value.trim().ifEmpty { null }
    is List<*> -> value.ifEmpty { null }
    else -> value
}

private val DATE = Regex("^(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?")
private val LANGUAGE = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*$")

/** The leading `YYYY[-MM[-DD]]` of a date string, with month/day range checks; null when there is none. */
fun normalizedDate(raw: String?): String? {
    val m = DATE.find(raw?.trim() ?: return null) ?: return null
    val (y, mo, d) = m.destructured
    if (y.toInt() !in 1000..2999) return null
    if (mo.isNotEmpty() && mo.toInt() !in 1..12) return y
    if (d.isNotEmpty() && d.toInt() !in 1..31) return "$y-$mo"
    return m.value
}

/** A syntactically valid BCP 47-style tag (`en`, `pt-BR`, `ja`), else null. Never guessed from text. */
fun normalizedLanguage(raw: String?): String? = raw?.trim()?.replace('_', '-')?.takeIf(LANGUAGE::matches)
