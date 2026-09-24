// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.input

enum class ShelfCommand { NEXT_PAGE, PREVIOUS_PAGE, CONFIRM, BACK, OPEN_MENU, SEARCH, TOGGLE_BOOKMARK }
enum class InputContext { LIBRARY, READER }
enum class InputKey { ENTER, CENTER, GAMEPAD_A, GAMEPAD_B, ESCAPE, HOME, BACK, LEFT, RIGHT, PAGE_UP, PAGE_DOWN, SPACE, F, B, MENU, START, L1, R1, OTHER }
data class KeyStroke(val key: InputKey, val control: Boolean = false, val shift: Boolean = false, val alt: Boolean = false)

object InputMapper {
    fun command(stroke: KeyStroke, context: InputContext): ShelfCommand? {
        if (stroke.alt) return null
        if (stroke.control) return if (stroke.key == InputKey.F) ShelfCommand.SEARCH else null
        return when (stroke.key) {
            InputKey.HOME, InputKey.BACK -> null // System-owned; never consume.
            InputKey.ENTER, InputKey.CENTER, InputKey.GAMEPAD_A -> ShelfCommand.CONFIRM
            InputKey.ESCAPE, InputKey.GAMEPAD_B -> ShelfCommand.BACK
            InputKey.MENU, InputKey.START -> ShelfCommand.OPEN_MENU
            else -> if (context == InputContext.READER) when (stroke.key) {
                InputKey.RIGHT, InputKey.PAGE_DOWN, InputKey.R1 -> ShelfCommand.NEXT_PAGE
                InputKey.LEFT, InputKey.PAGE_UP, InputKey.L1 -> ShelfCommand.PREVIOUS_PAGE
                InputKey.SPACE -> if (stroke.shift) ShelfCommand.PREVIOUS_PAGE else ShelfCommand.NEXT_PAGE
                InputKey.B -> ShelfCommand.TOGGLE_BOOKMARK
                else -> null
            } else null // Arrows / D-pad belong to Compose focus in the library.
        }
    }
}
