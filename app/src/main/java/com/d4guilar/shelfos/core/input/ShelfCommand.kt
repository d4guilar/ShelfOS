// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.input

enum class ShelfCommand { NEXT_PAGE, PREVIOUS_PAGE, CONFIRM, BACK, OPEN_MENU, SEARCH, TOGGLE_BOOKMARK }
enum class InputContext { LIBRARY, READER }
enum class InputKey { ENTER, CENTER, GAMEPAD_A, GAMEPAD_B, ESCAPE, HOME, BACK, LEFT, RIGHT, UP, DOWN, TAB, PAGE_UP, PAGE_DOWN, SPACE, F, B, MENU, START, L1, R1, OTHER }
data class KeyStroke(val key: InputKey, val control: Boolean = false, val shift: Boolean = false, val alt: Boolean = false)

object InputMapper {
    /** Keys that move or activate Compose focus. */
    private val focusKeys = setOf(InputKey.LEFT, InputKey.RIGHT, InputKey.UP, InputKey.DOWN, InputKey.TAB,
        InputKey.ENTER, InputKey.CENTER, InputKey.SPACE, InputKey.GAMEPAD_A)

    fun command(stroke: KeyStroke, context: InputContext, rightToLeft: Boolean = false): ShelfCommand? {
        if (stroke.alt) return null
        if (stroke.control) return if (stroke.key == InputKey.F) ShelfCommand.SEARCH else null
        return when (stroke.key) {
            InputKey.HOME, InputKey.BACK -> null // System-owned; never consume.
            InputKey.ENTER, InputKey.CENTER, InputKey.GAMEPAD_A -> ShelfCommand.CONFIRM
            InputKey.ESCAPE, InputKey.GAMEPAD_B -> ShelfCommand.BACK
            InputKey.MENU, InputKey.START -> ShelfCommand.OPEN_MENU
            else -> if (context == InputContext.READER) when (stroke.key) {
                InputKey.RIGHT -> if (rightToLeft) ShelfCommand.PREVIOUS_PAGE else ShelfCommand.NEXT_PAGE
                InputKey.LEFT -> if (rightToLeft) ShelfCommand.NEXT_PAGE else ShelfCommand.PREVIOUS_PAGE
                InputKey.PAGE_DOWN, InputKey.R1 -> ShelfCommand.NEXT_PAGE
                InputKey.PAGE_UP, InputKey.L1 -> ShelfCommand.PREVIOUS_PAGE
                InputKey.SPACE -> if (stroke.shift) ShelfCommand.PREVIOUS_PAGE else ShelfCommand.NEXT_PAGE
                InputKey.B -> ShelfCommand.TOGGLE_BOOKMARK
                else -> null
            } else null // Arrows / D-pad belong to Compose focus in the library.
        }
    }

    /**
     * Reader input: while a reader control has focus, keys that move or activate focus keep their normal
     * Compose behavior; page, menu and back commands still apply.
     */
    fun readerCommand(stroke: KeyStroke, rightToLeft: Boolean, controlsFocused: Boolean): ShelfCommand? =
        if (controlsFocused && !stroke.control && !stroke.alt && stroke.key in focusKeys) null
        else command(stroke, InputContext.READER, rightToLeft)
}
