// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.input

import android.view.InputDevice
import android.view.KeyEvent

/** The user's most recent meaningful input modality, tracked per reader screen. */
enum class InputModality { TOUCH, KEYBOARD, CONTROLLER }

/** Keycodes a keyboard cannot generate; any of these is unambiguously a gamepad button. */
private val gamepadOnlyKeys = setOf(InputKey.L1, InputKey.R1, InputKey.GAMEPAD_A, InputKey.GAMEPAD_B, InputKey.START)

/** D-pad/enter keycodes a physical keyboard's arrow/Enter keys and a gamepad's D-pad both report identically;
 *  only the reporting event/device's sources tell them apart. */
private val ambiguousDirectionalKeys = setOf(InputKey.LEFT, InputKey.RIGHT, InputKey.UP, InputKey.DOWN, InputKey.CENTER, InputKey.ENTER)

/** Keys with no meaningful, attributable input modality: system-owned (Back/Home) or unclassified. */
private val noModalityKeys = setOf(InputKey.BACK, InputKey.HOME, InputKey.OTHER)

/**
 * Classifies a real key event's raw input modality (KEYBOARD/CONTROLLER), independently of whether it becomes a
 * ShelfCommand. Returns null for keys with no meaningful, attributable modality — system-owned Back/Home, and
 * unclassified keys — so a caller can distinguish "no signal" from an actual modality and never mistake, say, the
 * system Back key for keyboard input. Never inspects device model/name: gamepad-exclusive keycodes are always
 * CONTROLLER, and the ambiguous D-pad/Enter keycodes are resolved from the reporting sources (SOURCE_GAMEPAD/
 * SOURCE_JOYSTICK/SOURCE_DPAD vs. a plain keyboard) — the specific event's own source takes precedence, since a
 * hybrid device's aggregate InputDevice sources can otherwise misclassify one of its plain keyboard events as a
 * gamepad's D-pad; the aggregate is only a fallback when the event itself reports no source.
 */
fun KeyEvent.inputModalityOrNull(): InputModality? {
    val key = keyStroke().key
    if (key in noModalityKeys) return null
    if (key in gamepadOnlyKeys) return InputModality.CONTROLLER
    if (key in ambiguousDirectionalKeys) {
        val sources = source.takeIf { it != InputDevice.SOURCE_UNKNOWN } ?: device?.sources ?: InputDevice.SOURCE_UNKNOWN
        val fromGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            sources and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
        return if (fromGamepad) InputModality.CONTROLLER else InputModality.KEYBOARD
    }
    return InputModality.KEYBOARD
}

/**
 * Resolves the on-screen hint label for a reader command under a given modality. Hint labels are resolved from
 * this centralized candidate catalog and validated against the real [InputMapper.command] before being shown, so a
 * displayed hint can never disagree with [InputMapper]'s current behavior for a candidate binding it does cover.
 * This does not make arbitrary future remapping automatically discoverable: a new or reassigned binding is only
 * ever hinted if it is also added to [keyboardCandidates]/[controllerCandidates] here. Returns null for TOUCH (no
 * hint shown) or when no candidate key under this modality currently maps to the command.
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
