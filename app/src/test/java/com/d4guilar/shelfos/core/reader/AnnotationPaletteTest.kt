// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import com.d4guilar.shelfos.domain.annotations.Annotation
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class AnnotationPaletteTest {
    private fun ann(kind: AnnotationKind, selected: String? = "t", body: String? = null, style: String? = null) =
        Annotation("id-$kind", "item", kind, LocatorFormat.READIUM_LOCATOR_1, """{"href":"c.xhtml"}""", 10, null, null, selected, body, style, 1, 1)

    @Test fun persistedStyleKeysAreTheStableFourAndNeverRawColors() {
        assertEquals(listOf("amber", "green", "blue", "rose"), AnnotationStyles.keys)
        assertEquals("amber", AnnotationStyles.DEFAULT)
        AnnotationStyles.keys.forEach { assertTrue(AnnotationStyles.isKnown(it)) }
        assertFalse(AnnotationStyles.isKnown(null)); assertFalse(AnnotationStyles.isKnown("#FFAA00")); assertFalse(AnnotationStyles.isKnown("Amber"))
    }

    @Test fun everyKnownStyleHasADistinctOpaqueTintPerTone() {
        ReaderTone.entries.forEach { tone ->
            val tints = AnnotationStyles.keys.map { AnnotationStyles.tint(it, tone) }
            assertEquals(4, tints.toSet().size)
            tints.forEach { assertEquals("opaque ARGB", 0xFF, (it ushr 24) and 0xFF) }
        }
    }

    @Test fun unknownOrMissingStyleFallsBackToTheDefaultWithoutCrashing() {
        ReaderTone.entries.forEach { tone ->
            val fallback = AnnotationStyles.tint("amber", tone)
            assertEquals(fallback, AnnotationStyles.tint("chartreuse", tone))
            assertEquals(fallback, AnnotationStyles.tint(null, tone))
            assertEquals(fallback, AnnotationStyles.tint("", tone))
        }
    }

    private fun luminance(argb: Int): Double {
        fun channel(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }
    private fun blend(tint: Int, background: Int, alpha: Float): Int {
        fun mix(shift: Int) = (((tint shr shift) and 0xFF) * alpha + ((background shr shift) and 0xFF) * (1 - alpha)).toInt()
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
    private fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun readingTextStaysReadableOnEveryHighlightInEveryReaderPalette() {
        // The page backgrounds and text colors epubPreferences applies for Light, Paper and Dark.
        val pages = mapOf(ReaderTone.LIGHT to (0xFFF7F7F5.toInt() to 0xFF111111.toInt()), ReaderTone.PAPER to (0xFFF2E8D0.toInt() to 0xFF111111.toInt()),
            ReaderTone.DARK to (0xFF0B0B0B.toInt() to 0xFFF3F3EF.toInt()))
        pages.forEach { (tone, colors) ->
            AnnotationStyles.keys.forEach { key ->
                val filled = blend(AnnotationStyles.tint(key, tone), colors.first, AnnotationStyles.HIGHLIGHT_ALPHA)
                assertTrue("$key on $tone contrast ${contrast(filled, colors.second)}", contrast(filled, colors.second) >= 4.5)
            }
            // The note underline must stand out from the page, but is not a fill.
            assertTrue(contrast(AnnotationStyles.noteUnderlineTint(tone), colors.first) >= 3.0)
        }
    }

    @Test fun noteUnderlineIsNeutralSoKindNeverReliesOnHue() {
        ReaderTone.entries.forEach { tone ->
            val c = AnnotationStyles.noteUnderlineTint(tone)
            assertEquals((c shr 16) and 0xFF, (c shr 8) and 0xFF) // grey: r == g
            AnnotationStyles.keys.forEach { assertNotEquals(c, AnnotationStyles.tint(it, tone)) }
        }
    }

    @Test fun readerToneFollowsThePaletteEpubPreferencesActuallyApplies() {
        val follow = ReaderPreferences(palette = PagePalette.THEME)
        assertEquals(ReaderTone.DARK, readerTone(follow, systemDark = true))
        assertEquals(ReaderTone.LIGHT, readerTone(follow, systemDark = false))
        assertEquals(ReaderTone.PAPER, readerTone(ReaderPreferences(palette = PagePalette.PAPER), systemDark = true))
        assertEquals(ReaderTone.DARK, readerTone(ReaderPreferences(palette = PagePalette.DARK), systemDark = false))
    }

    @Test fun inlineShapesFollowTheKindAndABodyNeverChangesTheKind() {
        assertEquals(EpubMarkShape.HIGHLIGHT, ann(AnnotationKind.HIGHLIGHT, style = "blue").toMarkOrNull()!!.shape)
        assertEquals("blue", ann(AnnotationKind.HIGHLIGHT, style = "blue").toMarkOrNull()!!.styleKey)
        assertEquals(EpubMarkShape.HIGHLIGHT, ann(AnnotationKind.HIGHLIGHT, body = "my thought", style = "amber").toMarkOrNull()!!.shape)
        assertEquals(EpubMarkShape.UNDERLINE, ann(AnnotationKind.NOTE, selected = "quote", body = "note").toMarkOrNull()!!.shape)
        assertNull("position note has no inline mark", ann(AnnotationKind.NOTE, selected = null, body = "note").toMarkOrNull())
        assertNull(ann(AnnotationKind.BOOKMARK, selected = null).toMarkOrNull())
        assertEquals("id-HIGHLIGHT", ann(AnnotationKind.HIGHLIGHT, style = "rose").toMarkOrNull()!!.id)
    }
}
