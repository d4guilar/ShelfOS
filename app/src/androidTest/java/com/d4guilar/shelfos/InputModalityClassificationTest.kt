// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.view.InputDevice
import android.view.KeyEvent
import com.d4guilar.shelfos.core.input.InputModality
import com.d4guilar.shelfos.core.input.inputModalityOrNull
import org.junit.Assert.*
import org.junit.Test

/**
 * Codex review (Phase 2A.1 remediation): raw modality classification must operate on the key event itself,
 * independently of whether it becomes a ShelfCommand, and must prefer the specific event's own reported source
 * over a hybrid device's aggregate sources. Real android.view.KeyEvent/InputDevice behavior isn't mockable in a
 * plain JVM unit test, so this coverage is instrumented.
 */
class InputModalityClassificationTest {
    /** Builds a real KeyEvent with an explicit source, bypassing any real InputDevice lookup for that source. */
    private fun keyEvent(code: Int, source: Int, deviceId: Int = -1) =
        KeyEvent(0, 0, KeyEvent.ACTION_UP, code, 0, 0, deviceId, 0, 0, source)

    @Test fun escapeIsKeyboardRegardlessOfSource() {
        assertEquals(InputModality.KEYBOARD, keyEvent(KeyEvent.KEYCODE_ESCAPE, InputDevice.SOURCE_KEYBOARD).inputModalityOrNull())
    }

    @Test fun gamepadButtonBIsAlwaysController() {
        assertEquals(InputModality.CONTROLLER, keyEvent(KeyEvent.KEYCODE_BUTTON_B, InputDevice.SOURCE_GAMEPAD).inputModalityOrNull())
    }

    @Test fun gamepadButtonAIsAlwaysController() {
        assertEquals(InputModality.CONTROLLER, keyEvent(KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD).inputModalityOrNull())
    }

    @Test fun l1AndR1AreAlwaysController() {
        assertEquals(InputModality.CONTROLLER, keyEvent(KeyEvent.KEYCODE_BUTTON_L1, InputDevice.SOURCE_GAMEPAD).inputModalityOrNull())
        assertEquals(InputModality.CONTROLLER, keyEvent(KeyEvent.KEYCODE_BUTTON_R1, InputDevice.SOURCE_GAMEPAD).inputModalityOrNull())
    }

    @Test fun rawSystemBackHasNoModality() {
        assertNull(keyEvent(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_KEYBOARD).inputModalityOrNull())
        assertNull(keyEvent(KeyEvent.KEYCODE_BACK, InputDevice.SOURCE_GAMEPAD).inputModalityOrNull())
    }

    @Test fun homeHasNoModality() {
        assertNull(keyEvent(KeyEvent.KEYCODE_HOME, InputDevice.SOURCE_KEYBOARD).inputModalityOrNull())
    }

    @Test fun dpadLeftFromAKeyboardSourceIsKeyboard() {
        assertEquals(InputModality.KEYBOARD, keyEvent(KeyEvent.KEYCODE_DPAD_LEFT, InputDevice.SOURCE_KEYBOARD).inputModalityOrNull())
    }

    @Test fun dpadLeftFromAGamepadOrJoystickOrDpadSourceIsController() {
        assertEquals(InputModality.CONTROLLER, keyEvent(KeyEvent.KEYCODE_DPAD_LEFT, InputDevice.SOURCE_GAMEPAD).inputModalityOrNull())
        assertEquals(InputModality.CONTROLLER, keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, InputDevice.SOURCE_JOYSTICK).inputModalityOrNull())
        assertEquals(InputModality.CONTROLLER, keyEvent(KeyEvent.KEYCODE_DPAD_UP, InputDevice.SOURCE_DPAD).inputModalityOrNull())
    }

    @Test fun enterFromAKeyboardSourceIsKeyboardAndFromAGamepadSourceIsController() {
        assertEquals(InputModality.KEYBOARD, keyEvent(KeyEvent.KEYCODE_ENTER, InputDevice.SOURCE_KEYBOARD).inputModalityOrNull())
        assertEquals(InputModality.CONTROLLER, keyEvent(KeyEvent.KEYCODE_ENTER, InputDevice.SOURCE_GAMEPAD).inputModalityOrNull())
    }

    /**
     * The event's own source must win even when it conflicts with what a hybrid device's aggregate sources would
     * suggest. A gamepad-classified deviceId here (if it resolved to a real, currently-connected InputDevice) would
     * push this toward CONTROLLER through the device-aggregate fallback; supplying a concrete SOURCE_KEYBOARD event
     * source must still resolve to KEYBOARD, proving event-source precedence rather than device-aggregate lookup.
     */
    @Test fun specificEventSourceTakesPrecedenceOverDeviceAggregate() {
        val event = keyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, InputDevice.SOURCE_KEYBOARD, deviceId = 12345)
        assertEquals(InputModality.KEYBOARD, event.inputModalityOrNull())
    }

    /** When the event itself reports no source, classification falls back to the (possibly absent) device's
     *  aggregate sources rather than crashing; with no real device registered at this id, it defaults sensibly. */
    @Test fun unknownEventSourceFallsBackWithoutCrashing() {
        val event = keyEvent(KeyEvent.KEYCODE_DPAD_LEFT, InputDevice.SOURCE_UNKNOWN, deviceId = 999_999)
        assertEquals(InputModality.KEYBOARD, event.inputModalityOrNull())
    }
}
