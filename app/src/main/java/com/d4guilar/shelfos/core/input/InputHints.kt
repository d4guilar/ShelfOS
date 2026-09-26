// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.input

import android.view.InputDevice
import android.view.KeyEvent

/** The user's most recent meaningful input modality, tracked per reader screen. */
enum class InputModality { TOUCH, KEYBOARD, CONTROLLER }

/** Keycodes a keyboard cannot generate; any of these is unambiguously a gamepad button. */
private val gamepadOnlyKeys = setOf(InputKey.L1, InputKey.R1, InputKey.GAMEPAD_A, InputKey.GAMEPAD_B, InputKey.START)

/** D-pad/enter keycodes a physical keyboard's arrow/Enter keys and a gamepad's D-pad both report identically;
 *  only the originating [InputDevice]'s reported sources tell them apart. */
private val ambiguousDirectionalKeys = setOf(InputKey.LEFT, InputKey.RIGHT, InputKey.UP, InputKey.DOWN, InputKey.CENTER, InputKey.ENTER)

/**
 * Classifies a real key event as KEYBOARD or CONTROLLER input. Never inspects device model/name: gamepad-exclusive
 * keycodes are always CONTROLLER, and the ambiguous D-pad/Enter keycodes fall back to the event's reported
 * InputDevice sources (SOURCE_GAMEPAD/SOURCE_JOYSTICK/SOURCE_DPAD vs. a plain keyboard).
 */
fun KeyEvent.inputModality(): InputModality {
    val key = keyStroke().key
    if (key in gamepadOnlyKeys) return InputModality.CONTROLLER
    if (key in ambiguousDirectionalKeys) {
        val sources = device?.sources ?: source
        val fromGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        return if (fromGamepad) InputModality.CONTROLLER else InputModality.KEYBOARD
    }
    return InputModality.KEYBOARD
}

/**
 * Resolves the on-screen hint label for a reader command under a given modality, by asking the real
 * [InputMapper.command] which candidate physical key currently produces that command — so a displayed hint can
 * never drift out of sync with the binding it claims to describe. Returns null for TOUCH (no hint shown) or when
 * no candidate key under this modality currently maps to the command.
 */
object InputHints {
    private val keyboardCandidates = listOf(InputKey.LEFT, InputKey.RIGHT, InputKey.ESCAPE)
    private val controllerCandidates = listOf(InputKey.L1, InputKey.R1, InputKey.GAMEPAD_B)

    private fun label(key: InputKey): String = when (key) {
        InputKey.LEFT -> "←"
        InputKey.RIGHT -> "→"
        InputKey.ESCAPE -> "Esc"
        InputKey.L1 -> "L1"
        InputKey.R1 -> "R1"
        InputKey.GAMEPAD_B -> "B"
        else -> key.name
    }

    fun hint(command: ShelfCommand, modality: InputModality, rightToLeft: Boolean): String? {
        val candidates = when (modality) {
            InputModality.TOUCH -> return null
            InputModality.KEYBOARD -> keyboardCandidates
            InputModality.CONTROLLER -> controllerCandidates
        }
        val key = candidates.firstOrNull { InputMapper.command(KeyStroke(it), InputContext.READER, rightToLeft) == command } ?: return null
        return label(key)
    }
}
