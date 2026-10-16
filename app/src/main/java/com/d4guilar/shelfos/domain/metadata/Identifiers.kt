// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

/**
 * Publication identifiers (Phase 4.5B, plan §10/§15). [value] is canonical: digits only, ISBN-10 check digit `X`
 * upper-case, a UPC/EAN supplement appended after `-`. Only checksum-valid values exist; nothing is looked up online.
 */
enum class IdentifierType { ISBN_10, ISBN_13, UPC_A, EAN_13 }

data class PublicationIdentifier(val type: IdentifierType, val value: String) {
    /** The ISBN-13 form of an ISBN (an ISBN-10 maps to 978…); null for UPC/EAN. Equal ISBNs compare by this. */
    val isbn13: String? get() = when (type) {
        IdentifierType.ISBN_13 -> value
        IdentifierType.ISBN_10 -> "978${value.take(9)}".let { it + ean13CheckDigit(it) }
        else -> null
    }

    /** Same publication identity: ISBN-10 ≡ ISBN-13; UPC/EAN must match including any supplement. */
    fun sameAs(other: PublicationIdentifier): Boolean =
        isbn13?.let { it == other.isbn13 } ?: (type == other.type && value == other.value)
}

/**
 * Pure, offline identifier detection. Rejects anything without a valid checksum, a wrong length or (for a bare
 * ISBN-13) a non-Bookland prefix, so years, page counts, issue numbers and file-name digits are never identifiers.
 */
object IdentifierDetector {
    private val separators = Regex("[\\s\\-‐-―]")

    /**
     * An ISBN from free text: `urn:isbn:`, an `ISBN` label, hyphens and spaces are accepted. [schemeSaysIsbn] (EPUB
     * `opf:scheme="ISBN"` / `identifier-type`) or a label/URN is required for ISBN-10, the form most prone to false
     * positives; a bare 13-digit value must be a valid 978/979 ISBN-13.
     */
    fun isbn(raw: String, schemeSaysIsbn: Boolean = false): PublicationIdentifier? {
        var text = raw.trim()
        var labelled = schemeSaysIsbn
        for (prefix in listOf("urn:isbn:", "isbn:", "isbn-13:", "isbn-10:", "isbn13:", "isbn10:", "isbn")) {
            if (text.startsWith(prefix, ignoreCase = true)) { text = text.substring(prefix.length).trim(); labelled = true; break }
        }
        val compact = text.replace(separators, "").uppercase()
        return when {
            compact.length == 13 && compact.all { it in '0'..'9' } && (compact.startsWith("978") || compact.startsWith("979")) &&
                ean13CheckDigit(compact.take(12)) == compact[12] -> PublicationIdentifier(IdentifierType.ISBN_13, compact)
            labelled && compact.length == 10 && compact.take(9).all { it in '0'..'9' } && (compact[9] in '0'..'9' || compact[9] == 'X') &&
                isbn10CheckDigit(compact.take(9)) == compact[9] -> PublicationIdentifier(IdentifierType.ISBN_10, compact)
            else -> null
        }
    }

    /** A UPC-A (12 digits) with an optional 2- or 5-digit supplement, e.g. `7 59606 09091 9 00111`. */
    fun upc(raw: String): PublicationIdentifier? = gtin(raw)?.takeIf { it.type == IdentifierType.UPC_A }

    /**
     * ComicInfo `GTIN` (v2.1): a 12-digit UPC-A or a 13-digit EAN, optionally followed by a 2/5-digit supplement. A
     * 978/979 EAN is reported as the ISBN-13 it is.
     */
    fun gtin(raw: String): PublicationIdentifier? {
        val digits = raw.trim().replace(separators, "")
        if (!digits.all { it in '0'..'9' }) return null
        for (base in listOf(13, 12)) {
            val supplement = digits.length - base
            if (supplement != 0 && supplement != 2 && supplement != 5) continue
            val main = digits.take(base)
            val valid = if (base == 13) ean13CheckDigit(main.take(12)) == main[12] else ean13CheckDigit("0" + main.take(11)) == main[11]
            if (!valid) continue
            val suffix = if (supplement > 0) "-" + digits.takeLast(supplement) else ""
            return when {
                base == 13 && supplement == 0 && (main.startsWith("978") || main.startsWith("979")) -> PublicationIdentifier(IdentifierType.ISBN_13, main)
                base == 13 -> PublicationIdentifier(IdentifierType.EAN_13, main + suffix)
                else -> PublicationIdentifier(IdentifierType.UPC_A, main + suffix)
            }
        }
        return null
    }

}

/** EAN-13 / ISBN-13 check digit for 12 digits (UPC-A uses it with a leading 0). */
internal fun ean13CheckDigit(first12: String): Char {
    val sum = first12.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
    return '0' + (10 - sum % 10) % 10
}

internal fun isbn10CheckDigit(first9: String): Char {
    val sum = first9.mapIndexed { i, c -> (c - '0') * (10 - i) }.sum()
    return when (val check = (11 - sum % 11) % 11) { 10 -> 'X'; else -> '0' + check }
}
