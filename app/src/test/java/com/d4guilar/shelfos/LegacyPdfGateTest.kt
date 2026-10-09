// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.files.usesLegacyPdfiumProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android 7.0/7.1 PDF guard must apply to API 24/25 only and never to API 26 and newer. */
class LegacyPdfGateTest {
    @Test fun legacyProbeAppliesOnlyBeforeApi26() {
        assertTrue(usesLegacyPdfiumProbe(24))
        assertTrue(usesLegacyPdfiumProbe(25))
        assertFalse(usesLegacyPdfiumProbe(26))
        assertFalse(usesLegacyPdfiumProbe(33))
        assertFalse(usesLegacyPdfiumProbe(36))
    }
}
