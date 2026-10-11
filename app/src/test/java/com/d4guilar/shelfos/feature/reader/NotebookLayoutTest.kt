// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class NotebookLayoutTest {
    @Test fun exactBoundaries() {
        assertEquals(NotebookPresentation.DIALOG, notebookPresentation(839.99f, 480f).presentation)
        assertEquals(NotebookPresentation.DIALOG, notebookPresentation(840f, 479.99f).presentation)
        assertEquals(NotebookLayout(NotebookPresentation.SIDE_BY_SIDE, 320f), notebookPresentation(840f, 480f))
    }

    @Test fun representativeWidthsClampPane() {
        assertEquals(320f, notebookPresentation(880f, 600f).paneWidthDp, 0f)
        assertEquals(360f, notebookPresentation(1000f, 700f).paneWidthDp, 0f)
        assertEquals(400f, notebookPresentation(1280f, 800f).paneWidthDp, 0f)
        assertEquals(400f, notebookPresentation(1600f, 1000f).paneWidthDp, 0f)
        assertEquals(NotebookPresentation.DIALOG, notebookPresentation(800f, 1280f).presentation)
        assertEquals(NotebookPresentation.DIALOG, notebookPresentation(640f, 360f).presentation)
    }

    @Test fun integerBoundariesAndRepresentativeHosts() {
        assertEquals(NotebookPresentation.DIALOG, notebookPresentation(839f, 480f).presentation)
        assertEquals(NotebookPresentation.DIALOG, notebookPresentation(840f, 479f).presentation)
        assertEquals(NotebookPresentation.SIDE_BY_SIDE, notebookPresentation(840f, 480f).presentation)
        listOf(360f to 640f, 411f to 731f, 640f to 360f, 731f to 411f, 800f to 1280f).forEach { (w, h) ->
            assertEquals("$w x $h", NotebookPresentation.DIALOG, notebookPresentation(w, h).presentation)
        }
        listOf(840f to 480f, 1280f to 800f, 1600f to 1000f).forEach { (w, h) ->
            assertEquals("$w x $h", NotebookPresentation.SIDE_BY_SIDE, notebookPresentation(w, h).presentation)
        }
    }
}
