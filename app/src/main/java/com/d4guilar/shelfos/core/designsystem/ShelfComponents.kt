// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.core.input.InputContext
import com.d4guilar.shelfos.core.input.ShelfCommand
import com.d4guilar.shelfos.core.input.shelfCommand
import com.d4guilar.shelfos.core.theme.LocalShelfTokens

/** One focus/activation policy for touch, keyboard, D-pad and gamepad controls. */
fun Modifier.shelfAction(
    selected: Boolean = false,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
): Modifier = composed {
    val tokens = LocalShelfTokens.current
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .border(if (focused) tokens.focus.width else 0.dp,
            if (focused) tokens.colors.ink else Color.Transparent, tokens.shapes.extraSmall)
        .semantics { this.selected = selected }
        .onKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (enabled && native.shelfCommand(InputContext.LIBRARY) == ShelfCommand.CONFIRM) {
                if (native.action == android.view.KeyEvent.ACTION_UP) onClick()
                true
            } else false
        }
        .clickable(enabled = enabled, role = role, onClick = onClick)
}

enum class ShelfIcon { LIBRARY, SEARCH, NOTES, COLLECTIONS, SETTINGS, BACK, STAR }

/** Original stroke icons; no third-party icon pack or font dependency. */
@Composable
fun ShelfIcon(icon: ShelfIcon, modifier: Modifier = Modifier) {
    val tokens = LocalShelfTokens.current
    val ink = tokens.colors.ink
    Canvas(modifier.size(tokens.icons.size)) {
        val scale = size.minDimension / 24f
        fun p(x: Float, y: Float) = Offset(x * scale, y * scale)
        val stroke = Stroke(tokens.icons.stroke * scale)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(ink, p(x, y), p(x2, y2), stroke.width)
        when (icon) {
            ShelfIcon.LIBRARY -> {
                drawRect(ink, p(3f, 4f), Size(5f * scale, 16f * scale), style = stroke)
                drawRect(ink, p(10f, 4f), Size(4f * scale, 16f * scale), style = stroke)
                line(17f, 4f, 21f, 19f); line(19f, 4f, 23f, 19f)
            }
            ShelfIcon.SEARCH -> { drawCircle(ink, 7f * scale, p(10f, 10f), style = stroke); line(15f, 15f, 22f, 22f) }
            ShelfIcon.NOTES -> {
                val path = Path().apply { moveTo(5f * scale, 2f * scale); lineTo(15f * scale, 2f * scale); lineTo(20f * scale, 7f * scale); lineTo(20f * scale, 22f * scale); lineTo(5f * scale, 22f * scale); close() }
                drawPath(path, ink, style = stroke); line(9f, 12f, 16f, 12f); line(9f, 16f, 16f, 16f)
            }
            ShelfIcon.COLLECTIONS -> {
                val path = Path().apply { moveTo(12f * scale, 2f * scale); lineTo(22f * scale, 7f * scale); lineTo(22f * scale, 17f * scale); lineTo(12f * scale, 22f * scale); lineTo(2f * scale, 17f * scale); lineTo(2f * scale, 7f * scale); close() }
                drawPath(path, ink, style = stroke); line(2f, 7f, 12f, 12f); line(12f, 12f, 22f, 7f)
            }
            ShelfIcon.SETTINGS -> {
                drawCircle(ink, 7f * scale, p(12f, 12f), style = stroke)
                drawCircle(ink, 2.5f * scale, p(12f, 12f), style = stroke)
                for (i in 0..7) {
                    val angle = i * Math.PI / 4
                    val x = kotlin.math.cos(angle).toFloat(); val y = kotlin.math.sin(angle).toFloat()
                    line(12f + x * 8, 12f + y * 8, 12f + x * 11, 12f + y * 11)
                }
            }
            ShelfIcon.BACK -> { line(14f, 4f, 6f, 12f); line(6f, 12f, 14f, 20f) }
            ShelfIcon.STAR -> {
                val path = Path()
                repeat(10) { i ->
                    val angle = i * Math.PI / 5 - Math.PI / 2
                    val r = if (i % 2 == 0) 10f else 4.5f
                    val x = (12 + kotlin.math.cos(angle).toFloat() * r) * scale
                    val y = (12 + kotlin.math.sin(angle).toFloat() * r) * scale
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); drawPath(path, ink, style = stroke)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleMedium)
}
