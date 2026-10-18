// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory

/**
 * Candidates read from a file name (plan §15, METADATA_ENRICHMENT §5): never truth, always `FILENAME_INFERENCE`, and
 * outranked by any embedded value. [title] is the name with recognised tokens and bracketed tags removed, used for
 * matching queries; the Library's displayed title keeps the import behaviour (the file name without its extension).
 */
data class FilenameEvidence(val title: String, val year: Int? = null, val series: String? = null, val volume: String? = null, val issue: String? = null,
    /** An explicit omnibus / box set / multi-volume range ("Vols. 1-3", "全3巻") rather than one volume. A hint, never truth. */
    val collection: Boolean = false) {
    fun proposals(): List<Proposed> {
        val p = FieldProvenance(MetadataOrigin.FILENAME_INFERENCE)
        return listOfNotNull(
            series?.let { Proposed(MetadataField.SERIES, it, p) }, volume?.let { Proposed(MetadataField.VOLUME, it, p) },
            issue?.let { Proposed(MetadataField.ISSUE, it, p) }, year?.let { Proposed(MetadataField.PUBLISHED_DATE, it.toString(), p) },
        )
    }
}

object FilenameInference {
    // Portable patterns only: Android's ICU regex rejects Java's (?U) flag, so Unicode digits and spaces are explicit
    // (\p{Nd}, \p{Z}); full-width "第３巻" still parses. The original text is never rewritten.
    private const val S = """[\s\p{Z}]"""
    private const val NUM = """(\p{Nd}{1,4}(?:[.,]\p{Nd}{1,2})?)"""
    private const val END = """(?=${'$'}|[\s\p{Z}_)\]\-])"""
    private val volumePatterns = listOf(
        Regex("""(?i)(?:^|[\s\p{Z}_.\-(\[])(?:vol(?:umen|ume)?\.?|tome|tomo|band|bd\.)$S*$NUM$END"""),
        Regex("""(?i)(?:^|[\s\p{Z}_\-(\[])v$NUM$END"""),
        Regex("""第?$S*$NUM$S*巻"""),
    )
    private const val BARE_V = 1  // index of the bare "v03" pattern above
    private val issuePatterns = listOf(
        Regex("""#$S*$NUM$END"""),
        Regex("""(?i)(?:^|[\s\p{Z}_\-(\[])(?:issue|no\.?|nº|n°)$S*$NUM$END"""),
    )
    // Multi-volume ranges ("Vols. 1-3", "Tomos 1 a 3", "1~3巻"), a total ("全12巻") and omnibus words describe a collection, not a volume.
    private const val RNUM = """(\p{Nd}{1,3}(?:[.,]\p{Nd}{1,2})?)"""
    private const val SEP = """(?:[\s\p{Z}]*[-–—~～〜][\s\p{Z}]*|[\s\p{Z}]+(?:to|a|à|et|y|und|bis)[\s\p{Z}]+)"""
    private val volumeRange = Regex("""(?i)(?:^|[\s\p{Z}_.\-(\[])(?:vols?\.?|volumes?|volumen(?:es)?|tomes?|tomos?|bands?|bände|bd\.)$S*$RNUM$SEP$RNUM$END""")
    private val kanjiRange = Regex("""第?$S*$RNUM$S*[-–—~～〜]$S*$RNUM$S*巻""")
    private val kanjiTotal = Regex("""全$S*$NUM$S*巻""")
    private val collectionWord = Regex("""(?i)(?:^|[\s\p{Z}_\-(\[])(?:omnibus|box[\s\p{Z}]?set|\p{Nd}+[\s\p{Z}]?-?[\s\p{Z}]?in[\s\p{Z}]?-?[\s\p{Z}]?\p{Nd}+)$END""")
    // A chapter number is never a volume ("Berserk Ch. 099").
    private val chapter = Regex("""(?i)(?:^|[\s\p{Z}_\-(\[])(?:ch(?:apter)?|chap|cap(?:[ií]tulo)?|kapitel)\.?$S*$NUM$END|第$S*$NUM$S*話""")
    private val year = Regex("""[(\[]((?:19|20)\p{Nd}{2})[)\]]""")
    // A zero-padded trailing number ("Saga 001") is an issue only for Comics, a volume only for Manga; never a plain "22".
    private val paddedTrailing = Regex("""[\s\p{Z}_\-]+(0\p{Nd}{1,3})$S*${'$'}""")
    // ICU (Android) needs closing `]`/`}` escaped outside a character class; the JVM does not.
    private val bracketed = Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}""")
    private val ofCount = Regex("""(?i)\($S*of$S+\p{Nd}+$S*\)""")

    fun infer(fileName: String, category: MediaCategory): FilenameEvidence {
        val stem = fileName.substringBeforeLast('.', fileName).ifBlank { fileName }
        // Documents stay conservative: only the cleaned name, no sequence numbers or years.
        if (category == MediaCategory.DOCUMENT) return FilenameEvidence(clean(stem).ifBlank { stem })
        var rest = stem
        val y = year.find(rest)?.groupValues?.get(1)?.toInt()
        rest = ofCount.replace(rest, " ")
        var volume: String? = null
        var issue: String? = null
        var collection = false
        // Collections and chapters are Comics/Manga conventions; Books and Documents keep their names untouched.
        val sequentialMedia = category == MediaCategory.COMIC || category == MediaCategory.MANGA
        var chapterSeen = false
        if (sequentialMedia) {
            kanjiTotal.find(rest)?.let { collection = true; rest = rest.removeRange(it.range) }
            listOf(volumeRange, kanjiRange).firstNotNullOfOrNull { it.find(rest) }?.let { collection = true; rest = rest.removeRange(it.range) }
            collectionWord.find(rest)?.let { collection = true; rest = rest.removeRange(it.range) }
            chapterSeen = chapter.find(rest)?.also { rest = rest.removeRange(it.range) } != null
        }
        // Category-aware (L-1): Books keep only explicit volume words ("Python v3" is a version, "No. 1 Ladies'" a title);
        // the bare v03 form is Comics/Manga, and issue tokens are Comics only.
        val volumes = if (category == MediaCategory.BOOK) volumePatterns.filterIndexed { i, _ -> i != BARE_V } else volumePatterns
        volumes.firstNotNullOfOrNull { it.find(rest) }?.let { m ->
            // A four-digit "volume" is a year, not a volume.
            number(m.groupValues[1]).takeIf { it.substringBefore('.').length < 4 }?.let { volume = it; rest = rest.removeRange(m.range) }
        }
        if (category == MediaCategory.COMIC)
            issuePatterns.firstNotNullOfOrNull { it.find(rest) }?.let { m -> issue = number(m.groupValues[1]); rest = rest.removeRange(m.range) }
        if (volume == null && issue == null && !collection && !chapterSeen) paddedTrailing.find(bracketed.replace(rest, " ").trimEnd())?.let { m ->
            val n = number(m.groupValues[1])
            if (category == MediaCategory.COMIC) issue = n else if (category == MediaCategory.MANGA) volume = n
            rest = bracketed.replace(rest, " ").trimEnd().removeRange(m.range)
        }
        val title = clean(rest).ifBlank { clean(stem).ifBlank { stem } }
        val sequential = category == MediaCategory.COMIC || category == MediaCategory.MANGA
        return FilenameEvidence(title, y, series = if (sequential && (volume != null || issue != null || collection)) title else null, volume = volume, issue = issue,
            collection = collection)
    }

    private fun clean(s: String) = bracketed.replace(s, " ").replace('_', ' ').replace(Regex("$S+"), " ")
        .trim().trim('-', '–', '—', '.', ',', ':', ' ')

    /** Unicode digits to ASCII; leading zeros dropped ("003" → "3", "0" stays, "1.5" stays). */
    private fun number(raw: String): String {
        val ascii = raw.map { c -> Character.digit(c, 10).takeIf { it >= 0 }?.let { '0' + it } ?: if (c == ',') '.' else c }.joinToString("")
        val (whole, fraction) = ascii.split('.').let { it[0] to it.getOrNull(1) }
        val trimmed = whole.trimStart('0').ifEmpty { "0" }
        return if (fraction != null) "$trimmed.$fraction" else trimmed
    }
}
