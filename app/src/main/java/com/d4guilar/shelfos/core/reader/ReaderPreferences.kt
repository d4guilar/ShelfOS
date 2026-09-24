// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.domain.library.*
import org.json.JSONObject

enum class BookFont { SERIF, SANS }
enum class PagePalette { THEME, LIGHT, DARK, PAPER }
enum class FitMode { PAGE, WIDTH }
data class ReaderPreferences(
    val font: BookFont? = null, val fontSize: Double? = null, val lineHeight: Double? = null,
    val margins: Double? = null, val justified: Boolean? = null, val scroll: Boolean? = null,
    val palette: PagePalette? = null, val direction: ReadingDirection? = null, val fit: FitMode? = null,
) {
    fun over(defaults: ReaderPreferences) = ReaderPreferences(font ?: defaults.font, fontSize ?: defaults.fontSize,
        lineHeight ?: defaults.lineHeight, margins ?: defaults.margins, justified ?: defaults.justified,
        scroll ?: defaults.scroll, palette ?: defaults.palette, direction ?: defaults.direction, fit ?: defaults.fit)
    fun json(): String = JSONObject().apply {
        put("version", 1); put("font", font?.name); put("fontSize", fontSize); put("lineHeight", lineHeight)
        put("margins", margins); put("justified", justified); put("scroll", scroll); put("palette", palette?.name)
        put("direction", direction?.name); put("fit", fit?.name)
    }.toString()
    companion object {
        val DEFAULT = ReaderPreferences(BookFont.SERIF, 1.0, 1.5, 1.0, false, false, PagePalette.THEME, null, FitMode.PAGE)
        fun parse(json: String?): ReaderPreferences = try {
            val obj = JSONObject(json ?: "{}")
            ReaderPreferences(
                BookFont.entries.find { it.name == obj.optString("font") },
                obj.number("fontSize")?.coerceIn(.7, 2.5), obj.number("lineHeight")?.coerceIn(1.0, 2.5),
                obj.number("margins")?.coerceIn(0.0, 3.0), obj.boolean("justified"), obj.boolean("scroll"),
                PagePalette.entries.find { it.name == obj.optString("palette") },
                ReadingDirection.entries.find { it.name == obj.optString("direction") },
                FitMode.entries.find { it.name == obj.optString("fit") })
        } catch (_: Exception) { ReaderPreferences() }
        private fun JSONObject.number(key: String) = if (has(key)) optDouble(key).takeIf { it.isFinite() } else null
        private fun JSONObject.boolean(key: String) = if (has(key) && !isNull(key)) optBoolean(key) else null
    }
}

data class ReaderCapabilities(val typography: Boolean, val direction: Boolean = true, val zoom: Boolean = false)
fun capabilities(format: PublicationFormat) = ReaderCapabilities(format == PublicationFormat.EPUB, zoom = format != PublicationFormat.EPUB)

fun pageLocator(index: Int): String = JSONObject().put("version", 1).put("page", index).toString()
fun restorePage(locator: String?, pageCount: Int): Int = try {
    JSONObject(locator ?: "{}").optInt("page", 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
} catch (_: Exception) { 0 }
