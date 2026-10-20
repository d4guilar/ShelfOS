// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.network

/**
 * A small strict JSON reader (RFC 8259) for provider responses. It exists instead of `org.json` (plan D14) because the platform
 * `org.json` is a stub in JVM unit tests, so parsing could not be tested, and it gives hard bounds: nesting depth, node count and
 * string length are capped, and anything malformed or trailing raises [JsonException] (a provider failure, never a crash).
 * Values are `Map<String, Any?>`, `List<Any?>`, `String`, `Double`/`Long`, `Boolean` or null.
 */
class JsonException(message: String) : Exception(message)

object Json {
    const val MAX_DEPTH = 24
    const val MAX_NODES = 20_000
    const val MAX_STRING = 64 * 1024

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value(0)
        p.skip()
        if (p.i != text.length) throw JsonException("trailing data")
        return v
    }

    private class Parser(val s: String) {
        var i = 0
        var nodes = 0

        fun skip() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun fail(m: String): Nothing = throw JsonException("$m at $i")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("too deep")
            if (++nodes > MAX_NODES) fail("too many values")
            skip()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            skip()
            if (peek() == '}') { i++; return m }
            while (true) {
                skip()
                if (peek() != '"') fail("expected a key")
                val k = str()
                skip()
                if (peek() != ':') fail("expected ':'")
                i++
                m[k] = value(depth + 1)
                skip()
                when (peek()) { ',' -> i++; '}' -> { i++; return m }; else -> fail("expected ',' or '}'") }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            skip()
            if (peek() == ']') { i++; return l }
            while (true) {
                l += value(depth + 1)
                skip()
                when (peek()) { ',' -> i++; ']' -> { i++; return l }; else -> fail("expected ',' or ']'") }
            }
        }

        fun peek(): Char = if (i < s.length) s[i] else fail("unexpected end")

        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c < ' ' -> fail("control character in string")
                    c == '\\' -> {
                        if (i >= s.length) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u escape")
                                b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape")); i += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    else -> b.append(c)
                }
                if (b.length > MAX_STRING) fail("string too long")
            }
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length || s[i] !in '0'..'9') fail("bad number")
            if (s[i] == '0') i++ else while (i < s.length && s[i] in '0'..'9') i++
            var integral = true
            if (i < s.length && s[i] == '.') { integral = false; i++; digits() }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                digits()
            }
            val t = s.substring(start, i)
            return if (integral) t.toLongOrNull() ?: t.toDouble() else t.toDouble()
        }

        fun digits() {
            val d = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == d) fail("bad number")
        }
    }
}

/** Typed, null-tolerant access: a wrong type reads as absent rather than throwing, so one odd field never breaks a record. */
@Suppress("UNCHECKED_CAST")
fun Any?.asObject(): Map<String, Any?>? = this as? Map<String, Any?>
fun Any?.asList(): List<Any?>? = this as? List<Any?>
fun Any?.asText(): String? = (this as? String)?.trim()?.takeIf { it.isNotEmpty() }
fun Any?.asInt(): Int? = (this as? Number)?.toLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
