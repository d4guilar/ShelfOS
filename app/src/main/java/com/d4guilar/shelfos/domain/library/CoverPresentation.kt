// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

/**
 * Cover presentation (Phase 4.5A.2, plan §7 rules 2-6 and §8, D9/D10). Presentation only: changing it never touches
 * stored artwork. Persisted as enum NAMES in `cover_presentation_preference`; an unreadable row is ignored (defaults).
 */
enum class CoverShape(val width: Int, val height: Int) {
    ORIGINAL(0, 0), RATIO_2_3(2, 3), RATIO_3_4(3, 4), RATIO_4_5(4, 5), RATIO_1_1(1, 1), CUSTOM(0, 0),
}
enum class CoverFit { CROP, FIT }
enum class CoverScope { GLOBAL, BOOK, COMIC, MANGA, DOCUMENT }

fun MediaCategory.coverScope(): CoverScope = CoverScope.valueOf(name)

/** One shape/fit choice. [customWidth]/[customHeight] are set only for [CoverShape.CUSTOM] (see [coverPresentation]). */
data class CoverPresentation(val shape: CoverShape, val fit: CoverFit, val customWidth: Int? = null, val customHeight: Int? = null) {
    /** Width / height of the shaped frame; null for [CoverShape.ORIGINAL], whose frame follows the artwork. */
    val aspect: Float? get() = when (shape) {
        CoverShape.ORIGINAL -> null
        CoverShape.CUSTOM -> customWidth!!.toFloat() / customHeight!!
        else -> shape.width.toFloat() / shape.height
    }
}

const val CUSTOM_SIDE_MIN = 1
const val CUSTOM_SIDE_MAX = 20
/** The one ratio bound (§7 rules 2 and 4): Custom shapes and the Original/details layout clamp, 1:2 … 2:1. */
const val MIN_COVER_ASPECT = 0.5f
const val MAX_COVER_ASPECT = 2f
/** Original's fixed grid slot and the generated cover's shape when no other ratio applies. */
const val ORIGINAL_SLOT_ASPECT = 2f / 3f

fun customRatioValid(width: Int, height: Int): Boolean = width in CUSTOM_SIDE_MIN..CUSTOM_SIDE_MAX &&
    height in CUSTOM_SIDE_MIN..CUSTOM_SIDE_MAX && width * 2 >= height && height * 2 >= width

/** The validated constructor: null for an invalid Custom ratio; custom sides are dropped for non-Custom shapes. */
fun coverPresentation(shape: CoverShape, fit: CoverFit, customWidth: Int? = null, customHeight: Int? = null): CoverPresentation? = when {
    shape != CoverShape.CUSTOM -> CoverPresentation(shape, fit)
    customWidth != null && customHeight != null && customRatioValid(customWidth, customHeight) ->
        CoverPresentation(shape, fit, customWidth, customHeight)
    else -> null
}

/** Clamps a native artwork ratio for layout only (the artwork itself is never stretched); invalid → the 2:3 slot. */
fun clampCoverAspect(aspect: Float?): Float =
    if (aspect == null || !aspect.isFinite() || aspect <= 0f) ORIGINAL_SLOT_ASPECT else aspect.coerceIn(MIN_COVER_ASPECT, MAX_COVER_ASPECT)

/**
 * The resolved preferences (§8): `effective(category) = override[category] ?: categoryDefault`. Books, Comics and Manga
 * default to [global]; Documents default to Original (D9), so "Use default" on Documents returns to Original.
 */
data class CoverPresentations(val global: CoverPresentation = GLOBAL_DEFAULT, val overrides: Map<MediaCategory, CoverPresentation> = emptyMap()) {
    fun inherited(category: MediaCategory?): CoverPresentation = if (category == MediaCategory.DOCUMENT) DOCUMENT_DEFAULT else global
    fun effective(category: MediaCategory?): CoverPresentation = category?.let(overrides::get) ?: inherited(category)

    companion object {
        val GLOBAL_DEFAULT = CoverPresentation(CoverShape.RATIO_2_3, CoverFit.CROP)
        val DOCUMENT_DEFAULT = CoverPresentation(CoverShape.ORIGINAL, CoverFit.CROP)
    }
}

/** A stored row as read back; any unknown or invalid value drops the row so its scope falls back to the default. */
data class StoredCoverPresentation(val scope: String, val shape: String, val customWidth: Int?, val customHeight: Int?, val fit: String)

fun resolveCoverPresentations(rows: List<StoredCoverPresentation>): CoverPresentations {
    val valid = rows.mapNotNull { row ->
        val scope = enumOrNull<CoverScope>(row.scope) ?: return@mapNotNull null
        val shape = enumOrNull<CoverShape>(row.shape) ?: return@mapNotNull null
        val fit = enumOrNull<CoverFit>(row.fit) ?: return@mapNotNull null
        coverPresentation(shape, fit, row.customWidth, row.customHeight)?.let { scope to it }
    }.toMap()
    return CoverPresentations(valid[CoverScope.GLOBAL] ?: CoverPresentations.GLOBAL_DEFAULT,
        MediaCategory.entries.mapNotNull { c -> valid[c.coverScope()]?.let { c to it } }.toMap())
}
