// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

/** The details cover's height ceiling (4.5C.2B R4): hero in a tall portrait pane, the old conservative rule everywhere else. */
class DetailsCoverPolicyTest {
    private fun h(w: Int, h: Int) = detailsCoverMaxHeight(w.dp, h.dp).value

    @Test fun `tall portrait panes get the larger hero, never above the ceiling`() {
        assertEquals(0.46f * 800, h(360, 800), 0.01f)
        assertTrue(h(360, 800) > 0.4f * 800)
        assertEquals(460f, h(393, 1200), 0f)   // the ceiling
        assertTrue(h(320, 700) > minOf(352f, 0.4f * 700))
    }

    @Test fun `landscape, compact and wide panes keep the conservative rule`() {
        assertEquals(0.4f * 420, h(360, 420), 0.01f)      // compact portrait window: Read must stay in view
        assertEquals(0.4f * 599, h(360, 599), 0.01f)      // just under the hero's minimum height
        assertEquals(minOf(352f, 0.4f * 360), h(840, 360), 0.01f)   // landscape / RP5-like pane
        assertEquals(352f, h(840, 1200), 0f)              // a wide pane is never "hero"
        assertEquals(352f, h(600, 1200), 0f)              // 600 dp is the expanded boundary
        assertEquals(352f, h(1000, 900), 0f)
    }

    @Test fun `the portrait hero is never smaller than the old size`() {
        for (w in listOf(320, 360, 393, 480, 599)) for (hh in 600..1400 step 100)
            assertTrue("$w x $hh", h(w, hh) >= minOf(352f, 0.4f * hh) - 0.01f)
    }
}
