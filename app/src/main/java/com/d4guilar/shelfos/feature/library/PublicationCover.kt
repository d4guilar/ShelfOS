// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.d4guilar.shelfos.domain.library.LibraryItem

/** Original geometric artwork rendered locally. Never extracts reference-image artwork. */
@Composable
fun PublicationCover(item: LibraryItem, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.aspectRatio(0.68f).clipToBounds().background(Color(item.coverColor)).clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize()) {
            val cream = Color(0xFFF5E8CC)
            val w = size.width; val h = size.height
            drawRect(Color.Black.copy(alpha = .1f), size = Size(w * .06f, h))
            when (item.coverMotif) {
                0 -> {
                    drawCircle(cream.copy(alpha = .8f), w * .22f, Offset(w * .65f, h * .55f))
                    val path = Path().apply { moveTo(0f, h); lineTo(0f, h * .82f); lineTo(w * .45f, h * .54f); lineTo(w, h * .84f); lineTo(w, h); close() }
                    drawPath(path, Color.Black.copy(alpha = .24f))
                    drawLine(cream, Offset(w * .15f, h * .91f), Offset(w * .85f, h * .91f), 1.dp.toPx())
                }
                1 -> {
                    repeat(4) { i -> drawCircle(cream.copy(alpha = .5f), w * (.18f + i * .14f), Offset(w * .65f, h * .76f), style = Stroke(1.dp.toPx())) }
                    drawCircle(cream, w * .1f, Offset(w * .37f, h * .64f))
                }
                else -> {
                    repeat(5) { i ->
                        val x = w * (.12f + i * .19f)
                        drawLine(cream.copy(alpha = .6f), Offset(x, h), Offset(x, h * (.43f + (i % 2) * .1f)), w * .04f)
                        drawCircle(cream.copy(alpha = .18f), w * .2f, Offset(x, h * .64f))
                    }
                }
            }
        }
        val coverTypeSize = (maxWidth.value / 7f).coerceIn(8f, 28f)
        Column(Modifier.padding((maxWidth.value / 12f).coerceIn(4f, 16f).dp)) {
            Text(item.title, color = Color(0xFFFFF5E3), fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium, fontSize = coverTypeSize.sp, lineHeight = (coverTypeSize * 1.1f).sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
