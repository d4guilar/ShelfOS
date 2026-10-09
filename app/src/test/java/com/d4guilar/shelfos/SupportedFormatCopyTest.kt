// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.domain.library.PublicationFormat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3F regression: user-facing copy that lists the formats ShelfOS reads must name every [PublicationFormat].
 * After CBR shipped (3E), the empty-library hint, the About text and the fixed-layout message still said only
 * "PDF, EPUB or CBZ" in all three locales. Format abbreviations are not translated, so the check is the same in
 * EN/ES/PT-BR.
 */
class SupportedFormatCopyTest {
    private val locales = listOf("values", "values-es", "values-pt-rBR")

    private fun strings(locale: String): Map<String, String> {
        val file = listOf(File("src/main/res/$locale/strings.xml"), File("app/src/main/res/$locale/strings.xml")).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test fun formatListsNameEveryReadableFormatInEveryLocale() {
        val all = PublicationFormat.entries.map { it.name }
        for (locale in locales) {
            val s = strings(locale)
            for (key in listOf("library_empty_start", "settings_about_body", "problem_unsupported_format_message")) {
                val text = requireNotNull(s[key]) { "$locale/$key missing" }
                all.forEach { format -> assertTrue("$locale/$key must mention $format: $text", format in text) }
            }
            val fixed = requireNotNull(s["problem_unsupported_layout_message"])
            (all - PublicationFormat.EPUB.name).forEach { format ->
                assertTrue("$locale/problem_unsupported_layout_message must mention $format: $fixed", format in fixed)
            }
        }
    }
}
