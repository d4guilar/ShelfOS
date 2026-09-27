// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBookmarkLocationTest {
    @Test fun minimalAndEnrichedSnapshotsAtSameLocationMatch() {
        val stored = EpubBookmarkLocation("chapter1.xhtml", progression = 0.0)
        val enriched = EpubBookmarkLocation("chapter1.xhtml", progression = 0.0, position = 1)

        assertTrue(sameEpubBookmarkLocation(stored, enriched))
    }

    @Test fun differentResourcesDoNotMatch() {
        assertFalse(sameEpubBookmarkLocation(
            EpubBookmarkLocation("chapter1.xhtml", progression = 0.0),
            EpubBookmarkLocation("chapter2.xhtml", progression = 0.0),
        ))
    }

    @Test fun positionsTakePrecedenceWhenBothAreAvailable() {
        assertFalse(sameEpubBookmarkLocation(
            EpubBookmarkLocation("chapter.xhtml", progression = 0.0, position = 1),
            EpubBookmarkLocation("chapter.xhtml", progression = 0.0, position = 2),
        ))
    }

    @Test fun sharedFragmentIdentifiesTheSameLocation() {
        assertTrue(sameEpubBookmarkLocation(
            EpubBookmarkLocation("chapter.xhtml", fragments = listOf("section"), progression = 0.1),
            EpubBookmarkLocation("chapter.xhtml", fragments = listOf("other", "section"), progression = 0.9),
        ))
    }

    @Test fun differentFragmentsDoNotFallBackToProgression() {
        assertFalse(sameEpubBookmarkLocation(
            EpubBookmarkLocation("chapter.xhtml", fragments = listOf("one"), progression = 0.5),
            EpubBookmarkLocation("chapter.xhtml", fragments = listOf("two"), progression = 0.5),
        ))
    }

    @Test fun insufficientLocationEvidenceDoesNotMatch() {
        assertFalse(sameEpubBookmarkLocation(
            EpubBookmarkLocation("chapter.xhtml"),
            EpubBookmarkLocation("chapter.xhtml", progression = 0.0),
        ))
    }
}
