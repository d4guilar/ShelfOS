// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.annotations

/**
 * Strict parser for annotation fixed-page anchors: exactly `{"version":1,"page":N}` with N a non-negative integer
 * (0-based source-page index, displayed 1-based). Key order and insignificant whitespace are tolerated; any other
 * member, duplicate key, wrong version, negative/fractional/exponent/string/overflowing page or non-JSON is rejected.
 *
 * This is intentionally separate from, and stricter than, the reader's resume restoration (`restorePage`), which
 * silently clamps and is not changed by annotations. Pure Kotlin so it is JVM-testable without `org.json`.
 */
object FixedPageLocator {
    /** Returns the 0-based page, or null when [json] is not a valid fixed-page locator. */
    fun parse(json: String): Int? {
        var i = 0
        fun skip() { while (i < json.length && json[i].let { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) i++ }
        fun eat(c: Char): Boolean { skip(); return if (i < json.length && json[i] == c) { i++; true } else false }
        fun key(): String? {
            if (!eat('"')) return null
            val start = i
            while (i < json.length && json[i] != '"') { if (json[i] == '\\') return null; i++ }
            if (i >= json.length) return null
            return json.substring(start, i++)
        }
        fun integer(): Long? {
            skip()
            val start = i
            while (i < json.length && json[i] in '0'..'9') i++
            val digits = json.substring(start, i)
            if (digits.isEmpty() || digits.length > 10 || (digits.length > 1 && digits[0] == '0')) return null
            return digits.toLong()
        }
        if (!eat('{')) return null
        var version: Long? = null
        var page: Long? = null
        var first = true
        while (true) {
            skip()
            if (i < json.length && json[i] == '}') { i++; break }
            if (!first && !eat(',')) return null
            first = false
            val k = key() ?: return null
            if (!eat(':')) return null
            val v = integer() ?: return null
            when (k) {
                "version" -> { if (version != null) return null; version = v }
                "page" -> { if (page != null) return null; page = v }
                else -> return null
            }
            // A '.', 'e' or 'E' after the digits (fractional/exponent) fails here: only ',' or '}' may follow.
            skip()
            if (i < json.length && json[i] != ',' && json[i] != '}') return null
        }
        skip()
        if (i != json.length) return null
        if (version != 1L || page == null || page > Int.MAX_VALUE) return null
        return page.toInt()
    }

    fun isValid(json: String) = parse(json) != null
}
