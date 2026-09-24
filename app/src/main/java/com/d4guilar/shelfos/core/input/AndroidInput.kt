// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.input

import android.view.KeyEvent

fun KeyEvent.shelfCommand(context: InputContext): ShelfCommand? {
    val key = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> InputKey.ENTER
        KeyEvent.KEYCODE_DPAD_CENTER -> InputKey.CENTER
        KeyEvent.KEYCODE_BUTTON_A -> InputKey.GAMEPAD_A
        KeyEvent.KEYCODE_BUTTON_B -> InputKey.GAMEPAD_B
        KeyEvent.KEYCODE_ESCAPE -> InputKey.ESCAPE
        KeyEvent.KEYCODE_HOME -> InputKey.HOME
        KeyEvent.KEYCODE_BACK -> InputKey.BACK
        KeyEvent.KEYCODE_DPAD_LEFT -> InputKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> InputKey.RIGHT
        KeyEvent.KEYCODE_PAGE_UP -> InputKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> InputKey.PAGE_DOWN
        KeyEvent.KEYCODE_SPACE -> InputKey.SPACE
        KeyEvent.KEYCODE_F -> InputKey.F
        KeyEvent.KEYCODE_B -> InputKey.B
        KeyEvent.KEYCODE_MENU -> InputKey.MENU
        KeyEvent.KEYCODE_BUTTON_START -> InputKey.START
        KeyEvent.KEYCODE_BUTTON_L1 -> InputKey.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> InputKey.R1
        else -> InputKey.OTHER
    }
    return InputMapper.command(KeyStroke(key, isCtrlPressed, isShiftPressed, isAltPressed), context)
}
