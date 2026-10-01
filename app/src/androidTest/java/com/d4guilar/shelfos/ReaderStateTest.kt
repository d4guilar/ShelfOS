// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.domain.library.ReadingDirection
import org.junit.Assert.*
import org.junit.Test

/** Serialization uses the platform org.json implementation, so it is verified on a device. */
class ReaderStateTest {
    @Test fun pageLocatorsAreVersionedStableAndTolerateDamage() {
        assertEquals(7, restorePage(pageLocator(7), 10))
        assertEquals(9, restorePage(pageLocator(40), 10))
        assertEquals(0, restorePage("not json", 10))
        assertEquals(0, restorePage(null, 0))
        assertTrue(pageLocator(3).contains("\"version\":1"))
    }

    @Test fun preferenceLayersRoundTripAndIgnoreDamagedValues() {
        val layer = ReaderPreferences(font = BookFont.SANS, fontSize = 1.3, direction = ReadingDirection.RTL,
            fit = FitMode.WIDTH, fontFamilyId = "user:family", presentationMode = PresentationMode.PUBLISHER)
        assertEquals(layer, ReaderPreferences.parse(layer.json()))
        assertEquals(ReaderPreferences(), ReaderPreferences.parse(ReaderPreferences().json()))
        assertEquals(ReaderPreferences(), ReaderPreferences.parse("{broken"))
        assertEquals(2.5, ReaderPreferences.parse("""{"fontSize": 99}""").fontSize!!, .001)
        assertNull(ReaderPreferences.parse("""{"font": "COMIC_SANS", "direction": "UP"}""").font)
        val legacy = ReaderPreferences.parse("""{"version":1,"font":"SANS","fontSize":1.2}""")
        assertEquals(BUILTIN_SANS_FONT_ID, legacy.effectiveFontFamilyId())
        assertEquals(PresentationMode.SHELFOS, resolveReaderPreferences(legacy, ReaderPreferences()).presentationMode)
    }

    @Test fun publisherPresentationHonorsPublisherStylesWithoutShelfTypographyOverrides() {
        val publisher = epubPreferences(ReaderPreferences.DEFAULT.copy(presentationMode = PresentationMode.PUBLISHER), false,
            com.d4guilar.shelfos.domain.library.MediaCategory.BOOK)
        assertEquals(true, publisher.publisherStyles)
        assertNull(publisher.fontFamily)
        assertNull(publisher.fontSize)
        val shelf = epubPreferences(ReaderPreferences.DEFAULT.copy(presentationMode = PresentationMode.SHELFOS), false,
            com.d4guilar.shelfos.domain.library.MediaCategory.BOOK)
        assertEquals(false, shelf.publisherStyles)
        assertEquals("serif", shelf.fontFamily?.name)
    }
}
