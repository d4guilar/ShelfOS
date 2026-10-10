// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

/** The reading surface an annotation is drawn on. Mirrors the page palettes [epubPreferences] actually applies. */
enum class ReaderTone { LIGHT, PAPER, DARK }

/** The tone the EPUB page really renders in. Publisher presentation keeps Readium's light default. */
fun readerTone(preferences: ReaderPreferences, systemDark: Boolean): ReaderTone {
    if (preferences.presentationMode == PresentationMode.PUBLISHER) return ReaderTone.LIGHT
    val palette = preferences.palette ?: PagePalette.THEME
    return when {
        palette == PagePalette.DARK || (palette == PagePalette.THEME && systemDark) -> ReaderTone.DARK
        palette == PagePalette.PAPER -> ReaderTone.PAPER
        else -> ReaderTone.LIGHT
    }
}

/**
 * ShelfOS-owned highlight palette. A persisted `styleKey` is a stable token (never a color value); the color it
 * resolves to depends on the reader tone, so it stays readable on Light, Paper and Dark pages. Color is never the
 * only cue: a highlight is a filled shape, a selection note an underline, and the UI always shows the style name.
 */
object AnnotationStyles {
    const val DEFAULT = "amber"
    /** Stable keys in display order. Never rename or reuse one: they are persisted. */
    val keys: List<String> = listOf("amber", "green", "blue", "rose")

    fun isKnown(styleKey: String?) = styleKey in keys

    /** Highlight tint (ARGB); Readium draws it translucent. An unknown key falls back to the default style. */
    fun tint(styleKey: String?, tone: ReaderTone): Int {
        val key = if (isKnown(styleKey)) styleKey else DEFAULT
        return when (tone) {
            ReaderTone.LIGHT -> when (key) { "green" -> 0xFF43A047; "blue" -> 0xFF1E88E5; "rose" -> 0xFFE91E63; else -> 0xFFFFB300 }
            ReaderTone.PAPER -> when (key) { "green" -> 0xFF2E7D32; "blue" -> 0xFF1565C0; "rose" -> 0xFFC2185B; else -> 0xFFE69500 }
            ReaderTone.DARK -> when (key) { "green" -> 0xFF66BB6A; "blue" -> 0xFF64B5F6; "rose" -> 0xFFF06292; else -> 0xFFFFCA28 }
        }.toInt()
    }

    /** Underline tint for selection-anchored notes: deliberately neutral, so note vs highlight never relies on hue. */
    fun noteUnderlineTint(tone: ReaderTone): Int = when (tone) {
        ReaderTone.DARK -> 0xFFBDBDBD
        else -> 0xFF606060
    }.toInt()

    /** Opacity Readium applies to a highlight tint when it fills the text rectangle. */
    const val HIGHLIGHT_ALPHA = 0.3f
}
