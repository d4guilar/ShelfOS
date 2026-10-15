// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesControlsLayoutTest {
    @Test fun shortHeightScrollsControlsWithList() { assertTrue(notesControlsScroll(445f)); assertTrue(notesControlsScroll(479.9f)) }
    @Test fun tallHeightKeepsControlsPinned() { assertFalse(notesControlsScroll(480f)); assertFalse(notesControlsScroll(800f)) }
}
