// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.debug

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Debug/test-only generator for the synthetic, license-clean EPUB used by the Phase 4B instrumentation and the manual
 * UAT harness: three reflowable chapters (distinct resources) of generated text. Every paragraph holds a UNIQUE token
 * (`quillfeatherCxP`) and the REPEATED phrase "the quiet harbor" (twice). Chapter 1 additionally has an inline `<em>`
 * span, text inside elements the sanitizer removes (`script`, `form`), a non-ASCII paragraph and boundary sentences.
 * [variant] simulates changed content under an identical item id: `SHIFTED` inserts two paragraphs before everything,
 * `FORMKEPT` keeps the text of the `form` element as a plain `span` (what a weaker sanitizer would leave in the rendered
 * DOM), `MISSING` rewrites paragraph 7 of chapter 1, `LARGE` is four chapters of 170 paragraphs with three unique tokens
 * each (2,040 tokens). Nothing here is shipped: this file lives in the debug source set only.
 */
object SyntheticAnnotationEpub {
    fun write(file: File, variant: String = "BASE") {
        file.parentFile?.mkdirs()
        val large = variant == "LARGE"
        fun paragraph(c: Int, p: Int): String = when {
            c == 1 && p == 2 -> "<p>Before script.<script>var shelfosTrap = 1;</script> After script, marker quillfeather1x2 stands alone.</p>"
            c == 1 && p == 3 && variant == "FORMKEPT" -> "<p>Form lead <span>formtext inside the form</span> form tail, marker quillfeather1x3 stands alone.</p>"
            c == 1 && p == 3 -> "<p>Form lead <form><span>formtext inside the form</span></form> form tail, marker quillfeather1x3 stands alone.</p>"
            c == 1 && p == 4 -> "<p>A <em>hidden emphasis phrase</em> spans inline markup, marker quillfeather1x4 stands alone.</p>"
            c == 1 && p == 5 -> "<p>Café naïve “quoted words” — dash, marker quillfeather1x5 stands alone.</p>"
            c == 1 && p == 7 && variant == "MISSING" -> "<p>This paragraph was rewritten completely and no longer holds the old marker.</p>"
            else -> "<p>Paragraph $p of chapter $c. Marker quillfeather${c}x$p stands alone in this line. The quiet harbor lies beyond the old lighthouse, and the quiet harbor waits for the tide.</p>"
        }
        fun chapter(c: Int): String {
            if (large) return """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $c</title></head><body><h1>Chapter $c</h1>${largeParagraphs(c).joinToString("")}</body></html>"""
            val lead = if (c == 1 && variant == "SHIFTED") "<p>Inserted opening paragraph one for the shifted variant.</p><p>Inserted opening paragraph two for the shifted variant.</p>" else ""
            val first = "<p>First words of chapter $c: the opening tide.</p>"
            val last = "<p>Last words of chapter $c: the closing tide.</p>"
            return """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $c</title></head><body><h1>Chapter $c</h1>$lead$first${(1..30).joinToString("") { paragraph(c, it) }}$last</body></html>"""
        }
        val chapters = if (large) 4 else 3
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:shelfos-annotation-fixture</dc:identifier><dc:title>Annotation Fixture Book</dc:title><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-10T00:00:00Z</meta></metadata><manifest>${(1..chapters).joinToString("") { """<item id="chapter$it" href="chapter$it.xhtml" media-type="application/xhtml+xml"/>""" }}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine>${(1..chapters).joinToString("") { """<itemref idref="chapter$it"/>""" }}</spine></package>""",
            "nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>${(1..chapters).joinToString("") { """<li><a href="chapter$it.xhtml">Chapter $it</a></li>""" }}</ol></nav></body></html>""",
        )
        (1..chapters).forEach { entries["chapter$it.xhtml"] = chapter(it) }
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }

    private fun largeParagraphs(c: Int): List<String> = (1..170).map { p ->
        "<p>Para $p of chapter $c: tokena${c}x${p}a rests, tokenb${c}x${p}b waits and tokenc${c}x${p}c ends. The quiet harbor lies beyond the old lighthouse.</p>"
    }

    /** The comparable `body.textContent` of a LARGE chapter (what Readium slices before/after context from). */
    private fun largeBodyText(c: Int): String = "Chapter $c" + (1..170).joinToString("") { p ->
        "Para $p of chapter $c: tokena${c}x${p}a rests, tokenb${c}x${p}b waits and tokenc${c}x${p}c ends. The quiet harbor lies beyond the old lighthouse."
    }

    /**
     * Valid Readium locator JSON strings (exact text triplets taken from the generated chapter, real `href`, plausible
     * progression) for [count] distinct tokens of LARGE chapter [chapter], in reading order. Used to seed realistic
     * decoration load without any hand-made selection.
     */
    fun largeChapterLocators(chapter: Int, count: Int): List<Pair<String, String>> {
        val text = largeBodyText(chapter)
        val tokens = (1..170).flatMap { p -> listOf("tokena${chapter}x${p}a", "tokenb${chapter}x${p}b", "tokenc${chapter}x${p}c") }.take(count)
        return tokens.map { token ->
            val at = text.indexOf(token)
            val before = text.substring(maxOf(0, at - 60), at)
            val after = text.substring(at + token.length, minOf(text.length, at + token.length + 60))
            val progression = at.toDouble() / text.length
            token to JSONObject().put("href", "chapter$chapter.xhtml").put("type", "application/xhtml+xml").put("title", "Chapter $chapter")
                .put("locations", JSONObject().put("progression", progression).put("position", chapter).put("totalProgression", (chapter - 1 + progression) / 4.0))
                .put("text", JSONObject().put("before", before).put("highlight", token).put("after", after)).toString()
        }
    }
}
