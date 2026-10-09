// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.domain.library.*
import org.json.JSONObject

enum class BookFont { SERIF, SANS }
enum class PagePalette { THEME, LIGHT, DARK, PAPER }
enum class FitMode { PAGE, WIDTH }
enum class PresentationMode { SHELFOS, PUBLISHER }

/** One preference layer. Null fields are not set in this layer and inherit from the next one. */
data class ReaderPreferences(
    val font: BookFont? = null, val fontSize: Double? = null, val lineHeight: Double? = null,
    val margins: Double? = null, val justified: Boolean? = null, val scroll: Boolean? = null,
    val palette: PagePalette? = null, val direction: ReadingDirection? = null, val fit: FitMode? = null,
    val fontFamilyId: String? = null,
    val presentationMode: PresentationMode? = null,
    // Phase 3C: comic/manga spread presentation. Additive JSON field only -- no Room schema/migration (see
    // json()/parse() below). Title-specific like [direction]; see appearanceUpdate()'s "never global" handling.
    val spreadMode: SpreadMode? = null,
) {
    fun over(defaults: ReaderPreferences) = ReaderPreferences(font ?: defaults.font, fontSize ?: defaults.fontSize,
        lineHeight ?: defaults.lineHeight, margins ?: defaults.margins, justified ?: defaults.justified,
        scroll ?: defaults.scroll, palette ?: defaults.palette, direction ?: defaults.direction, fit ?: defaults.fit,
        fontFamilyId ?: defaults.fontFamilyId, presentationMode ?: defaults.presentationMode,
        spreadMode ?: defaults.spreadMode)

    /** Copies only the fields that differ between [before] and [after] into this layer. */
    fun withChanges(before: ReaderPreferences, after: ReaderPreferences) = ReaderPreferences(
        pick(before.font, after.font, font), pick(before.fontSize, after.fontSize, fontSize),
        pick(before.lineHeight, after.lineHeight, lineHeight), pick(before.margins, after.margins, margins),
        pick(before.justified, after.justified, justified), pick(before.scroll, after.scroll, scroll),
        pick(before.palette, after.palette, palette), pick(before.direction, after.direction, direction),
        pick(before.fit, after.fit, fit), pick(before.fontFamilyId, after.fontFamilyId, fontFamilyId),
        pick(before.presentationMode, after.presentationMode, presentationMode),
        pick(before.spreadMode, after.spreadMode, spreadMode))

    /** Clears this layer's values for fields that differ between [before] and [after]. [font] and [fontFamilyId]
     * are treated as one semantic unit here: a global `fontFamilyId` change must also clear this layer's own
     * [font], even when [font] itself did not change in the edit. Otherwise a leftover legacy-looking `font` value
     * (with no [fontFamilyId] of its own, since that half of the pair was correctly cleared) would be backfilled
     * by [backfillLegacyFontFamilyId] on the very next parse into a *new* [fontFamilyId] override for this layer —
     * resurrecting the stale title override the global application was supposed to clear. */
    fun withoutChanges(before: ReaderPreferences, after: ReaderPreferences) = ReaderPreferences(
        font.unless(before.font != after.font || before.fontFamilyId != after.fontFamilyId), fontSize.unless(before.fontSize != after.fontSize),
        lineHeight.unless(before.lineHeight != after.lineHeight), margins.unless(before.margins != after.margins),
        justified.unless(before.justified != after.justified), scroll.unless(before.scroll != after.scroll),
        palette.unless(before.palette != after.palette), direction.unless(before.direction != after.direction),
        fit.unless(before.fit != after.fit), fontFamilyId.unless(before.fontFamilyId != after.fontFamilyId),
        presentationMode.unless(before.presentationMode != after.presentationMode),
        spreadMode.unless(before.spreadMode != after.spreadMode))

    fun json(): String = JSONObject().apply {
        put("version", 2); put("font", font?.name); put("fontSize", fontSize); put("lineHeight", lineHeight)
        put("margins", margins); put("justified", justified); put("scroll", scroll); put("palette", palette?.name)
        put("direction", direction?.name); put("fit", fit?.name); put("fontFamilyId", fontFamilyId)
        put("presentationMode", presentationMode?.name); put("spreadMode", spreadMode?.name)
    }.toString()

    companion object {
        val DEFAULT = ReaderPreferences(BookFont.SERIF, 1.0, 1.5, 1.0, false, false, PagePalette.THEME, null,
            FitMode.PAGE, presentationMode = PresentationMode.SHELFOS, spreadMode = SpreadMode.AUTO)
        fun parse(json: String?): ReaderPreferences = try {
            val obj = JSONObject(json ?: "{}")
            val font = BookFont.entries.find { it.name == obj.optString("font") }
            val fontFamilyId = backfillLegacyFontFamilyId(font, obj.optString("fontFamilyId").takeIf { it.isNotBlank() })
            ReaderPreferences(
                font,
                obj.number("fontSize")?.coerceIn(.7, 2.5), obj.number("lineHeight")?.coerceIn(1.0, 2.5),
                obj.number("margins")?.coerceIn(0.0, 3.0), obj.boolean("justified"), obj.boolean("scroll"),
                PagePalette.entries.find { it.name == obj.optString("palette") },
                ReadingDirection.entries.find { it.name == obj.optString("direction") },
                FitMode.entries.find { it.name == obj.optString("fit") },
                fontFamilyId,
                PresentationMode.entries.find { it.name == obj.optString("presentationMode") },
                // Additive field (Phase 3C): absent/unrecognized/malformed value from an old JSON blob or a
                // damaged string simply parses as null here, which resolveReaderPreferences()/DEFAULT then
                // resolve to SpreadMode.AUTO -- no Room schema/migration, old JSON keeps parsing safely.
                SpreadMode.entries.find { it.name == obj.optString("spreadMode") })
        } catch (_: Exception) { ReaderPreferences() }
        private fun JSONObject.number(key: String) = if (has(key)) optDouble(key).takeIf { it.isFinite() } else null
        private fun JSONObject.boolean(key: String) = if (has(key) && !isNull(key)) optBoolean(key) else null
    }
}

private fun <T> pick(before: T?, after: T?, current: T?) = if (after != before) after else current
private fun <T> T?.unless(changed: Boolean) = if (changed) null else this

/**
 * Field-by-field precedence: explicit per-title value, explicit global value, then presentation defaults.
 * Reading direction and spread mode are both title-specific; without a title override, direction falls back to
 * the category default and spread mode falls back to [SpreadMode.AUTO] (via [ReaderPreferences.DEFAULT]) --
 * neither is ever taken from the global layer, even defensively, in case a global JSON blob somehow carried one.
 */
fun resolveReaderPreferences(title: ReaderPreferences, global: ReaderPreferences) =
    title.over(global.copy(direction = null, spreadMode = null)).over(ReaderPreferences.DEFAULT)

/** New explicit layers after an Appearance change; unchanged fields keep inheriting. */
data class AppearanceUpdate(val title: ReaderPreferences, val global: ReaderPreferences?)

/**
 * Saving for this title records only changed fields as title overrides. Saving globally records changed
 * fields as defaults and drops this title's conflicting overrides so the change is visible here; other
 * titles keep their explicit choices. Direction is never global.
 */
fun appearanceUpdate(title: ReaderPreferences, global: ReaderPreferences, before: ReaderPreferences,
    after: ReaderPreferences, globally: Boolean): AppearanceUpdate {
    if (!globally) return AppearanceUpdate(title.withChanges(before, after), null)
    // Spread mode is treated exactly like direction: a title-owned presentation choice that "Use as default for
    // all titles" must never globalize (see AGENTS.md rule 9/16, and the plan's "spread mode never globalizes"
    // persistence requirement), even though every other appearance field legitimately can.
    val shared = after.copy(direction = before.direction, spreadMode = before.spreadMode)
    val titleDirection = if (after.direction != before.direction) after.direction else title.direction
    val titleSpreadMode = if (after.spreadMode != before.spreadMode) after.spreadMode else title.spreadMode
    return AppearanceUpdate(title.withoutChanges(before, shared).copy(direction = titleDirection, spreadMode = titleSpreadMode),
        global.withChanges(before, shared).copy(direction = null, spreadMode = null))
}

/** Reset clears the chosen layer; the title's direction override is part of the title layer. */
fun appearanceReset(title: ReaderPreferences, globally: Boolean) =
    if (globally) AppearanceUpdate(title, ReaderPreferences()) else AppearanceUpdate(ReaderPreferences(), null)

/** Controls offered for a publication's actual rendering capabilities; unsupported controls are omitted. */
data class ReaderCapabilities(val typography: Boolean, val fit: Boolean, val zoom: Boolean, val direction: Boolean = true,
    val spread: Boolean = false)

/** Overload kept for EPUB (no fixed-layout/spread concept at all). */
fun capabilities(format: PublicationFormat) = when (format) {
    PublicationFormat.EPUB -> ReaderCapabilities(typography = true, fit = false, zoom = false)
    PublicationFormat.PDF, PublicationFormat.CBZ, PublicationFormat.CBR -> ReaderCapabilities(typography = false, fit = true, zoom = true)
}

/**
 * Phase 3C: the spread control is offered only for a fixed-layout format capable of spreads (PDF/CBZ), AND only
 * for [MediaCategory.COMIC]/[MediaCategory.MANGA] -- an ordinary Book/Document that merely happens to be a PDF
 * must never show spread controls merely because of its file format (per the owner's explicit product brief).
 */
fun capabilities(format: PublicationFormat, category: MediaCategory): ReaderCapabilities =
    capabilities(format).copy(spread = spreadCapable(format, category))

fun spreadCapable(format: PublicationFormat, category: MediaCategory): Boolean =
    (format == PublicationFormat.PDF || format == PublicationFormat.CBZ || format == PublicationFormat.CBR) &&
        (category == MediaCategory.COMIC || category == MediaCategory.MANGA)

/** Versioned fixed-layout locator: a stable index into the stored page sequence. */
fun pageLocator(index: Int): String = JSONObject().put("version", 1).put("page", index).toString()
fun restorePage(locator: String?, pageCount: Int): Int = try {
    JSONObject(locator ?: "{}").optInt("page", 0).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
} catch (_: Exception) { 0 }
fun pageProgress(index: Int, pageCount: Int): Int = if (pageCount <= 0) 0 else ((index + 1L) * 100 / pageCount).toInt().coerceIn(0, 100)
