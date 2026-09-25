// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.feature.home.chromeAlpha
import org.junit.Assert.*
import org.junit.Test

/** The reader-entry cover transition's chrome fade (CLASSIC_UI.md §12): finishes before the expansion does. */
class CoverExpandTransitionTest {
    @Test fun chromeIsFullyVisibleBeforeTheTransitionStarts() {
        assertEquals(1f, chromeAlpha(0f), 0.001f)
    }

    @Test fun chromeFinishesFadingBeforeTheCoverFinishesExpanding() {
        assertEquals(0f, chromeAlpha(0.6f), 0.001f)
        assertEquals(0f, chromeAlpha(1f), 0.001f)
    }

    @Test fun chromeFadesMonotonicallyAndStaysInRange() {
        val samples = (0..20).map { it / 20f }
        val alphas = samples.map(::chromeAlpha)
        alphas.forEach { assertTrue("alpha $it out of range", it in 0f..1f) }
        alphas.zipWithNext().forEach { (a, b) -> assertTrue("chrome alpha must never increase", b <= a) }
    }
}
