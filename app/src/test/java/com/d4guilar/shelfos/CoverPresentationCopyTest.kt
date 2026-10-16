// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4.5A.2: every Library appearance string exists in EN/ES/PT-BR with the same format placeholders. */
class CoverPresentationCopyTest {
    private val locales = listOf("values", "values-es", "values-pt-rBR")
    private val keys = listOf("settings_library_appearance_title", "settings_library_appearance_description", "settings_cover_shape",
        "settings_cover_fit", "cover_shape_original", "cover_shape_ratio", "cover_shape_custom", "cover_shape_custom_ratio", "cover_fit_crop",
        "cover_fit_fit", "settings_cover_width", "settings_cover_height", "content_desc_decrease", "content_desc_increase",
        "settings_cover_per_category", "settings_cover_use_default", "settings_cover_category_override", "settings_cover_fit_original_note",
        "settings_cover_summary", "settings_cover_default_summary", "settings_cover_override_summary", "state_expanded", "state_collapsed",
        "settings_cover_stepper_value", "settings_cover_stepper_number")

    private fun strings(locale: String): Map<String, String> {
        val file = listOf(File("src/main/res/$locale/strings.xml"), File("app/src/main/res/$locale/strings.xml")).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private fun placeholders(text: String) = Regex("%\\d\\$[sd]").findAll(text).map { it.value }.sorted().toList()

    @Test fun everyCoverPresentationStringIsLocalizedWithMatchingPlaceholders() {
        val english = strings("values")
        for (locale in locales) {
            val s = strings(locale)
            for (key in keys) {
                val text = requireNotNull(s[key]) { "$locale/$key missing" }
                assertTrue("$locale/$key is blank", text.isNotBlank())
                assertEquals("$locale/$key placeholders", placeholders(english.getValue(key)), placeholders(text))
            }
        }
        // Words, not the ratio/summary patterns, must actually be translated.
        for (key in listOf("settings_library_appearance_title", "cover_fit_crop", "cover_fit_fit", "settings_cover_use_default", "state_expanded"))
            for (locale in locales - "values") assertTrue("$locale/$key untranslated", strings(locale)[key] != english[key])
        // Cover fit and Category override are different controls; they must not share their leading word.
        for (locale in locales) {
            val s = strings(locale)
            assertTrue("$locale: fit and override labels are distinguishable",
                s.getValue("settings_cover_fit").substringBefore(' ') != s.getValue("settings_cover_category_override").substringBefore(' '))
        }
    }
}
