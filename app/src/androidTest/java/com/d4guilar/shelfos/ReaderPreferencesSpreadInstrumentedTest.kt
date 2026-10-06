// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.core.reader.SpreadMode
import com.d4guilar.shelfos.core.reader.resolveReaderPreferences
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 3C: the real `org.json.JSONObject`-backed half of [SpreadMode] persistence that
 * `ReaderPreferencesSpreadTest` (plain JVM) cannot exercise -- this project's local unit tests use the
 * Android-shadowed `org.json` stub (unmocked outside Robolectric/instrumented tests), matching every other
 * `ReaderPreferences.json()`/`.parse()` round trip in this codebase (see `LibraryPersistenceTest`). No Room/
 * repository/file I/O is needed here -- just [ReaderPreferences.json]/[ReaderPreferences.parse] against real
 * `JSONObject`.
 */
class ReaderPreferencesSpreadInstrumentedTest {

    @Test fun oldJsonWithoutSpreadModeStillParsesSafelyAndResolvesToAuto() {
        // A pre-3C JSON blob -- no "spreadMode" key at all.
        val old = """{"version":2,"font":"SERIF","fit":"PAGE"}"""
        val parsed = ReaderPreferences.parse(old)
        assertNull(parsed.spreadMode)
        assertEquals(SpreadMode.AUTO, resolveReaderPreferences(parsed, ReaderPreferences()).spreadMode)
        // The rest of an old blob's fields are unaffected by the additive field.
        assertEquals(com.d4guilar.shelfos.core.reader.BookFont.SERIF, parsed.font)
    }

    @Test fun eachSpreadModeRoundTripsThroughARealJsonWriteAndRead() {
        for (mode in SpreadMode.entries) {
            val json = ReaderPreferences(spreadMode = mode).json()
            assertEquals("mode $mode", mode, ReaderPreferences.parse(json).spreadMode)
        }
    }

    @Test fun nullSpreadModeRoundTripsAsNullThroughARealJsonWriteAndRead() {
        val json = ReaderPreferences().json()
        assertNull(ReaderPreferences.parse(json).spreadMode)
    }

    @Test fun unrecognizedSpreadModeValueFallsBackToNullRatherThanThrowing() {
        val parsed = ReaderPreferences.parse("""{"version":2,"spreadMode":"NOT_A_REAL_MODE"}""")
        assertNull(parsed.spreadMode)
    }

    @Test fun wrongTypeSpreadModeValueFallsBackToNullRatherThanThrowing() {
        val parsed = ReaderPreferences.parse("""{"version":2,"spreadMode":42}""")
        assertNull(parsed.spreadMode)
    }

    @Test fun malformedWholeJsonFallsBackToDefaultRatherThanThrowing() {
        assertNull(ReaderPreferences.parse("not json").spreadMode)
        assertNull(ReaderPreferences.parse("{").spreadMode)
        assertNull(ReaderPreferences.parse(null).spreadMode)
    }

    @Test fun otherExistingFieldsRoundTripUnaffectedByTheAdditiveSpreadModeField() {
        val prefs = ReaderPreferences(fit = com.d4guilar.shelfos.core.reader.FitMode.WIDTH,
            direction = com.d4guilar.shelfos.domain.library.ReadingDirection.RTL, spreadMode = SpreadMode.SPREAD)
        val roundTripped = ReaderPreferences.parse(prefs.json())
        assertEquals(com.d4guilar.shelfos.core.reader.FitMode.WIDTH, roundTripped.fit)
        assertEquals(com.d4guilar.shelfos.domain.library.ReadingDirection.RTL, roundTripped.direction)
        assertEquals(SpreadMode.SPREAD, roundTripped.spreadMode)
    }
}
