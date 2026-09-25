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
        val layer = ReaderPreferences(font = BookFont.SANS, fontSize = 1.3, direction = ReadingDirection.RTL, fit = FitMode.WIDTH)
        assertEquals(layer, ReaderPreferences.parse(layer.json()))
        assertEquals(ReaderPreferences(), ReaderPreferences.parse(ReaderPreferences().json()))
        assertEquals(ReaderPreferences(), ReaderPreferences.parse("{broken"))
        assertEquals(2.5, ReaderPreferences.parse("""{"fontSize": 99}""").fontSize!!, .001)
        assertNull(ReaderPreferences.parse("""{"font": "COMIC_SANS", "direction": "UP"}""").font)
    }
}
