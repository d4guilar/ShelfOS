// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

import com.d4guilar.shelfos.core.database.CoverPresentationDao
import com.d4guilar.shelfos.core.database.CoverPresentationPreferenceEntity
import com.d4guilar.shelfos.data.preferences.RoomCoverPresentationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Phase 4.5A.2 preference model (plan §8, D9): defaults, category resolution, Custom bounds, stored-value fallback. */
class CoverPresentationTest {
    private val crop23 = CoverPresentation(CoverShape.RATIO_2_3, CoverFit.CROP)

    @Test fun shippedDefaultsAreGlobal23CropAndDocumentsOriginal() {
        val p = CoverPresentations()
        assertEquals(crop23, p.global)
        for (c in listOf(MediaCategory.BOOK, MediaCategory.COMIC, MediaCategory.MANGA)) assertEquals(crop23, p.effective(c))
        assertEquals(CoverShape.ORIGINAL, p.effective(MediaCategory.DOCUMENT).shape)
        assertEquals("an unknown category (orphan Notes row) uses the global default", crop23, p.effective(null))
    }

    @Test fun changingTheGlobalDefaultMovesBooksComicsAndMangaButNotDocuments() {
        val fit11 = CoverPresentation(CoverShape.RATIO_1_1, CoverFit.FIT)
        val p = CoverPresentations(global = fit11)
        for (c in listOf(MediaCategory.BOOK, MediaCategory.COMIC, MediaCategory.MANGA)) assertEquals(fit11, p.effective(c))
        assertEquals(CoverShape.ORIGINAL, p.effective(MediaCategory.DOCUMENT).shape)
    }

    @Test fun categoryOverrideWinsAndClearingItRestoresTheCategoryDefault() {
        val manga34 = CoverPresentation(CoverShape.RATIO_3_4, CoverFit.FIT)
        val docs23 = CoverPresentation(CoverShape.RATIO_2_3, CoverFit.FIT)
        val p = CoverPresentations(overrides = mapOf(MediaCategory.MANGA to manga34, MediaCategory.DOCUMENT to docs23))
        assertEquals(manga34, p.effective(MediaCategory.MANGA))
        assertEquals(crop23, p.effective(MediaCategory.COMIC))
        assertEquals("an explicit Documents override is honoured", docs23, p.effective(MediaCategory.DOCUMENT))
        val cleared = p.copy(overrides = emptyMap())
        assertEquals(crop23, cleared.effective(MediaCategory.MANGA))
        assertEquals(CoverShape.ORIGINAL, cleared.effective(MediaCategory.DOCUMENT).shape)
    }

    @Test fun customSidesAreOneToTwentyAndTheRatioStaysWithinHalfToTwo() {
        assertNotNull(coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 1, 2))
        assertNotNull(coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 20, 10))
        assertNotNull(coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 7, 9))
        for ((w, h) in listOf(0 to 1, 1 to 0, 21 to 20, 20 to 21, -1 to 1, 1 to 3, 3 to 1, 20 to 9, 9 to 20))
            assertNull("$w:$h must be rejected", coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, w, h))
        assertNull("Custom needs both sides", coverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 3, null))
        assertEquals(5f / 7f, coverPresentation(CoverShape.CUSTOM, CoverFit.FIT, 5, 7)!!.aspect!!, 1e-6f)
        assertEquals("non-Custom shapes drop custom sides", crop23, coverPresentation(CoverShape.RATIO_2_3, CoverFit.CROP, 9, 9))
    }

    @Test fun shapeRatiosAndOriginalHasNone() {
        assertNull(CoverPresentation(CoverShape.ORIGINAL, CoverFit.CROP).aspect)
        assertEquals(listOf(2f / 3, 3f / 4, 4f / 5, 1f), listOf(CoverShape.RATIO_2_3, CoverShape.RATIO_3_4, CoverShape.RATIO_4_5, CoverShape.RATIO_1_1)
            .map { CoverPresentation(it, CoverFit.CROP).aspect })
    }

    @Test fun originalSlotClampIsHalfToTwoWithA23FallbackForInvalidRatios() {
        assertEquals(0.5f, clampCoverAspect(0.2f), 0f)
        assertEquals(2f, clampCoverAspect(5f), 0f)
        assertEquals(0.7f, clampCoverAspect(0.7f), 0f)
        for (bad in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(ORIGINAL_SLOT_ASPECT, clampCoverAspect(bad), 0f)
    }

    @Test fun storedRowsResolveAndUnknownOrInvalidValuesFallBackToDefaults() {
        val p = resolveCoverPresentations(listOf(
            StoredCoverPresentation("GLOBAL", "RATIO_4_5", null, null, "FIT"),
            StoredCoverPresentation("COMIC", "CUSTOM", 3, 5, "CROP"),
            StoredCoverPresentation("BOOK", "HEXAGON", null, null, "CROP"),      // unknown shape
            StoredCoverPresentation("MANGA", "RATIO_1_1", null, null, "STRETCH"), // unknown fit
            StoredCoverPresentation("DOCUMENT", "CUSTOM", 1, 9, "CROP"),         // out-of-range custom
            StoredCoverPresentation("SHELF", "RATIO_1_1", null, null, "CROP"),   // unknown scope
        ))
        assertEquals(CoverPresentation(CoverShape.RATIO_4_5, CoverFit.FIT), p.global)
        assertEquals(mapOf(MediaCategory.COMIC to CoverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 3, 5)), p.overrides)
        assertEquals(p.global, p.effective(MediaCategory.BOOK))
        assertEquals(CoverShape.ORIGINAL, p.effective(MediaCategory.DOCUMENT).shape)
        assertEquals(CoverPresentations(), resolveCoverPresentations(listOf(StoredCoverPresentation("GLOBAL", "", null, null, ""))))
    }

    @Test fun repositoryWritesValidatedRowsClearsOverridesAndRejectsInvalidPresentations() = runBlocking {
        val dao = FakeDao()
        val repo = RoomCoverPresentationRepository(dao)
        repo.setGlobal(CoverPresentation(CoverShape.RATIO_1_1, CoverFit.FIT))
        repo.setOverride(MediaCategory.DOCUMENT, CoverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 4, 5))
        assertEquals(CoverPresentation(CoverShape.RATIO_1_1, CoverFit.FIT), repo.presentations.first().global)
        assertEquals(4f / 5f, repo.presentations.first().effective(MediaCategory.DOCUMENT).aspect!!, 1e-6f)
        repo.setOverride(MediaCategory.DOCUMENT, null)
        assertEquals(CoverShape.ORIGINAL, repo.presentations.first().effective(MediaCategory.DOCUMENT).shape)
        assertEquals(setOf("GLOBAL"), dao.rows.value.map { it.scope }.toSet())
        for (bad in listOf(CoverPresentation(CoverShape.CUSTOM, CoverFit.CROP, 1, 9), CoverPresentation(CoverShape.RATIO_2_3, CoverFit.CROP, 2, 2)))
            assertTrue(runCatching { repo.setGlobal(bad) }.isFailure)
        assertEquals("a rejected write changes nothing", CoverPresentation(CoverShape.RATIO_1_1, CoverFit.FIT), repo.presentations.first().global)
    }

    private class FakeDao : CoverPresentationDao {
        val rows = MutableStateFlow<List<StoredCoverPresentation>>(emptyList())
        override fun observeAll() = rows
        override suspend fun save(preference: CoverPresentationPreferenceEntity) {
            rows.value = rows.value.filter { it.scope != preference.scope } +
                StoredCoverPresentation(preference.scope, preference.shape, preference.customWidth, preference.customHeight, preference.fit)
        }
        override suspend fun delete(scope: String) { rows.value = rows.value.filter { it.scope != scope } }
    }
}
