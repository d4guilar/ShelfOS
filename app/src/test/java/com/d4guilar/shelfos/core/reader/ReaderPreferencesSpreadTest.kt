// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3C: pure JVM coverage for [SpreadMode] persistence through [ReaderPreferences] -- the "never global"
 * title-only handling [appearanceUpdate]/[appearanceReset] give it (exactly like
 * [ReadingDirection][com.d4guilar.shelfos.domain.library.ReadingDirection]) and [resolveReaderPreferences]'s AUTO
 * default. This file deliberately never calls [ReaderPreferences.json]/[ReaderPreferences.parse]: this project's
 * local JVM unit tests cannot exercise `org.json.JSONObject` (the Android-shadowed stub is unmocked outside
 * Robolectric/instrumented tests -- see [EpubBookmarkPresentationTest]'s class doc for the same documented
 * limitation, and [LibraryPersistenceTest] in `androidTest` for where `ReaderPreferences.json()/.parse()` are
 * actually exercised against real JSON). The additive-JSON-field requirements that genuinely need real JSON
 * parsing (old JSON without `spreadMode` still parsing safely, each mode round-tripping through `json()`, a
 * malformed value falling back safely) are covered instead by `ReaderPreferencesSpreadInstrumentedTest`.
 */
class ReaderPreferencesSpreadTest {

    // ---- resolveReaderPreferences: AUTO is the resolved default when no title override is set ----

    @Test fun defaultReaderPreferencesResolveToAutoSpreadMode() {
        assertEquals(SpreadMode.AUTO, ReaderPreferences.DEFAULT.spreadMode)
    }

    @Test fun noTitleOrGlobalOverrideResolvesToAuto() {
        assertEquals(SpreadMode.AUTO, resolveReaderPreferences(ReaderPreferences(), ReaderPreferences()).spreadMode)
    }

    @Test fun aTitleOverrideWinsOverTheAutoDefault() {
        val title = ReaderPreferences(spreadMode = SpreadMode.SINGLE)
        assertEquals(SpreadMode.SINGLE, resolveReaderPreferences(title, ReaderPreferences()).spreadMode)
    }

    // ---- resolveReaderPreferences: the global layer can never leak a spreadMode, even defensively ----

    @Test fun aGlobalLayerThatSomehowCarriesSpreadModeIsIgnoredByResolution() {
        // Should never happen via normal appearanceUpdate (see below), but resolveReaderPreferences defensively
        // strips any stray spreadMode from the global layer before applying it, the same way it already strips
        // direction.
        val globalWithStraySpreadMode = ReaderPreferences(spreadMode = SpreadMode.SPREAD)
        val resolved = resolveReaderPreferences(ReaderPreferences(), globalWithStraySpreadMode)
        assertEquals(SpreadMode.AUTO, resolved.spreadMode) // falls through to the DEFAULT layer, not the global one
    }

    @Test fun aTitleOverrideStillWinsOverAnyStrayGlobalValue() {
        val title = ReaderPreferences(spreadMode = SpreadMode.SINGLE)
        val global = ReaderPreferences(spreadMode = SpreadMode.SPREAD) // hypothetical stray value
        assertEquals(SpreadMode.SINGLE, resolveReaderPreferences(title, global).spreadMode)
    }

    // ---- appearanceUpdate: spread mode is title-specific, never promoted to the global layer ----

    @Test fun savingForThisTitleRecordsSpreadModeOnTheTitleLayerOnly() {
        val before = ReaderPreferences()
        val after = before.copy(spreadMode = SpreadMode.SPREAD)
        val update = appearanceUpdate(title = ReaderPreferences(), global = ReaderPreferences(), before = before, after = after, globally = false)
        assertEquals(SpreadMode.SPREAD, update.title.spreadMode)
        assertNull(update.global)
    }

    @Test fun savingGloballyNeverGlobalizesSpreadModeEvenWhenOtherFieldsChange() {
        val before = ReaderPreferences(fit = FitMode.PAGE, spreadMode = SpreadMode.AUTO)
        // The user changed BOTH fit (an ordinary global-eligible field) and spreadMode in the same Appearance edit.
        val after = before.copy(fit = FitMode.WIDTH, spreadMode = SpreadMode.SPREAD)
        val update = appearanceUpdate(title = ReaderPreferences(), global = ReaderPreferences(), before = before, after = after, globally = true)
        assertEquals(FitMode.WIDTH, update.global?.fit) // fit legitimately globalizes...
        assertNull(update.global?.spreadMode) // ...but spreadMode never does, regardless of "Use as default for all titles"
        assertEquals(SpreadMode.SPREAD, update.title.spreadMode) // the title layer keeps the explicit choice the user made
    }

    @Test fun savingGloballyWithOnlyASpreadModeChangeStillKeepsItOnTheTitleLayer() {
        val before = ReaderPreferences(spreadMode = SpreadMode.AUTO)
        val after = before.copy(spreadMode = SpreadMode.SINGLE)
        val update = appearanceUpdate(title = ReaderPreferences(), global = ReaderPreferences(), before = before, after = after, globally = true)
        assertEquals(SpreadMode.SINGLE, update.title.spreadMode)
        assertNull(update.global?.spreadMode)
    }

    @Test fun anExistingTitleSpreadModeOverrideSurvivesAnUnrelatedGlobalSave() {
        val existingTitle = ReaderPreferences(spreadMode = SpreadMode.SPREAD)
        val before = ReaderPreferences(fit = FitMode.PAGE)
        val after = before.copy(fit = FitMode.WIDTH) // unrelated global-eligible change; spreadMode untouched by the user this time
        val update = appearanceUpdate(title = existingTitle, global = ReaderPreferences(), before = before, after = after, globally = true)
        assertEquals(SpreadMode.SPREAD, update.title.spreadMode) // preserved, not clobbered by the global save
        assertNull(update.global?.spreadMode)
    }

    // ---- appearanceReset: resetting this title returns spreadMode to AUTO (via the DEFAULT layer) ----

    @Test fun resettingThisTitleClearsSpreadModeBackToTheAutoDefault() {
        val title = ReaderPreferences(spreadMode = SpreadMode.SPREAD)
        val reset = appearanceReset(title, globally = false)
        assertNull(reset.title.spreadMode) // cleared title layer...
        assertEquals(SpreadMode.AUTO, resolveReaderPreferences(reset.title, ReaderPreferences()).spreadMode) // ...resolves to AUTO
    }

    @Test fun resettingGloballyLeavesThisTitlesSpreadModeOverrideIntact() {
        val title = ReaderPreferences(spreadMode = SpreadMode.SPREAD)
        val reset = appearanceReset(title, globally = true)
        assertEquals(SpreadMode.SPREAD, reset.title.spreadMode) // title layer is untouched by a global-scope reset
    }

    // ---- Structural sanity: adding spreadMode did not disturb existing field behavior ----

    @Test fun existingDirectionAndFitBehaviorIsStructurallyUnaffectedBySpreadModeAddition() {
        val before = ReaderPreferences(fit = FitMode.PAGE, direction = null)
        val after = before.copy(fit = FitMode.WIDTH, direction = com.d4guilar.shelfos.domain.library.ReadingDirection.RTL)
        val update = appearanceUpdate(title = ReaderPreferences(), global = ReaderPreferences(), before = before, after = after, globally = true)
        assertEquals(FitMode.WIDTH, update.global?.fit)
        assertNull(update.global?.direction) // direction still never globalizes
        assertEquals(com.d4guilar.shelfos.domain.library.ReadingDirection.RTL, update.title.direction)
    }

    @Test fun withChangesAndWithoutChangesHandleSpreadModeLikeAnyOtherField() {
        val before = ReaderPreferences(spreadMode = SpreadMode.AUTO)
        val after = before.copy(spreadMode = SpreadMode.SPREAD)
        val layer = ReaderPreferences()
        assertEquals(SpreadMode.SPREAD, layer.withChanges(before, after).spreadMode)
        assertNull(ReaderPreferences(spreadMode = SpreadMode.SPREAD).withoutChanges(before, after).spreadMode)
    }
}
