// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.input.InputHints
import com.d4guilar.shelfos.core.input.InputModality
import com.d4guilar.shelfos.core.input.ShelfCommand
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 2A.1: hints must derive from the real InputMapper bindings, not a separately hand-maintained table
 * that could drift out of sync with what a key actually does.
 */
class InputHintsTest {
    @Test fun touchModalityNeverShowsAHint() {
        assertNull(InputHints.hint(ShelfCommand.NEXT_PAGE, InputModality.TOUCH, rightToLeft = false))
        assertNull(InputHints.hint(ShelfCommand.PREVIOUS_PAGE, InputModality.TOUCH, rightToLeft = false))
        assertNull(InputHints.hint(ShelfCommand.BACK, InputModality.TOUCH, rightToLeft = false))
    }

    @Test fun controllerHintsMatchTheActualGamepadBindings() {
        assertEquals("R1", InputHints.hint(ShelfCommand.NEXT_PAGE, InputModality.CONTROLLER, rightToLeft = false))
        assertEquals("L1", InputHints.hint(ShelfCommand.PREVIOUS_PAGE, InputModality.CONTROLLER, rightToLeft = false))
        assertEquals("B", InputHints.hint(ShelfCommand.BACK, InputModality.CONTROLLER, rightToLeft = false))
    }

    @Test fun controllerHintsAreNotRtlSwapped() {
        // L1/R1 always mean Previous/Next in InputMapper, independent of reading direction.
        assertEquals("R1", InputHints.hint(ShelfCommand.NEXT_PAGE, InputModality.CONTROLLER, rightToLeft = true))
        assertEquals("L1", InputHints.hint(ShelfCommand.PREVIOUS_PAGE, InputModality.CONTROLLER, rightToLeft = true))
    }

    @Test fun keyboardHintsMatchTheActualArrowAndEscapeBindingsInLtr() {
        assertEquals("→", InputHints.hint(ShelfCommand.NEXT_PAGE, InputModality.KEYBOARD, rightToLeft = false))
        assertEquals("←", InputHints.hint(ShelfCommand.PREVIOUS_PAGE, InputModality.KEYBOARD, rightToLeft = false))
        assertEquals("Esc", InputHints.hint(ShelfCommand.BACK, InputModality.KEYBOARD, rightToLeft = false))
    }

    @Test fun keyboardHintsFollowTheSameRtlSwapAsInputMapperArrowKeys() {
        // InputMapper.command() swaps RIGHT/LEFT to PREVIOUS/NEXT under RTL; the displayed hint must follow.
        assertEquals("←", InputHints.hint(ShelfCommand.NEXT_PAGE, InputModality.KEYBOARD, rightToLeft = true))
        assertEquals("→", InputHints.hint(ShelfCommand.PREVIOUS_PAGE, InputModality.KEYBOARD, rightToLeft = true))
    }

    @Test fun noHintForACommandWithoutACandidateBindingUnderThatModality() {
        // OPEN_MENU has no candidate key registered in either hint table.
        assertNull(InputHints.hint(ShelfCommand.OPEN_MENU, InputModality.KEYBOARD, rightToLeft = false))
        assertNull(InputHints.hint(ShelfCommand.OPEN_MENU, InputModality.CONTROLLER, rightToLeft = false))
    }
}
