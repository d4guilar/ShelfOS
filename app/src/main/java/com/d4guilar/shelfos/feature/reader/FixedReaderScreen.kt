// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.reader.FitMode
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.*

@Composable
fun FixedReaderScreen(vm: FixedReaderViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val t = LocalShelfTokens.current
    var controls by rememberSaveable { mutableStateOf(true) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    var scale by remember(state.page) { mutableFloatStateOf(1f) }
    var panX by remember(state.page) { mutableFloatStateOf(0f) }
    var panY by remember(state.page) { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    val rtl = readingDirection(state.item?.category ?: MediaCategory.BOOK, state.preferences.direction) == ReadingDirection.RTL
    BackHandler(appearance) { appearance = false }
    if (appearance) ReaderAppearance(state.preferences, false, { appearance = false }, vm::preferences)
    Column(Modifier.fillMaxSize().background(t.colors.canvas).testTag("reader_screen")) {
        if (controls) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack) { Text("Library") }
            TextButton({ appearance = true }) { Text("Appearance") }
            TextButton({ scale = if (scale == 1f) 2f else 1f; panX = 0f; panY = 0f }) { Text(if (scale == 1f) "Zoom in" else "Reset zoom") }
            TextButton({ controls = false; focus.requestFocus() }) { Text("Hide controls") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().focusRequester(focus).onPreviewKeyEvent { event ->
            when (val command = event.nativeKeyEvent.shelfCommand(InputContext.READER, rtl)) {
                ShelfCommand.NEXT_PAGE, ShelfCommand.PREVIOUS_PAGE, ShelfCommand.OPEN_MENU, ShelfCommand.BACK -> {
                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP) when (command) {
                        ShelfCommand.NEXT_PAGE -> vm.turn(1)
                        ShelfCommand.PREVIOUS_PAGE -> vm.turn(-1)
                        ShelfCommand.OPEN_MENU -> controls = !controls
                        else -> if (controls) controls = false else onBack()
                    }
                    true
                }
                else -> false
            }
        }.focusable().pointerInput(state.page, rtl) {
            detectTapGestures(onDoubleTap = { scale = if (scale == 1f) 2f else 1f; panX = 0f; panY = 0f }, onTap = { point ->
                when {
                    point.x < size.width * .25f -> vm.turn(if (rtl) 1 else -1)
                    point.x > size.width * .75f -> vm.turn(if (rtl) -1 else 1)
                    else -> controls = !controls
                }
            })
        }.pointerInput(state.page, rtl) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var horizontal = 0f
                var vertical = 0f
                var transformed = scale > 1f
                do {
                    val event = awaitPointerEvent()
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (event.changes.count { it.pressed } > 1 || scale > 1f) {
                        transformed = true
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        panX = (panX + pan.x).coerceIn(-size.width * scale, size.width * scale)
                        panY = (panY + pan.y).coerceIn(-size.height * scale, size.height * scale)
                        event.changes.forEach { it.consume() }
                    } else {
                        horizontal += pan.x; vertical += pan.y
                        if (kotlin.math.abs(horizontal) > viewConfiguration.touchSlop && kotlin.math.abs(horizontal) > kotlin.math.abs(vertical))
                            event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (!transformed && kotlin.math.abs(horizontal) > 64.dp.toPx() && kotlin.math.abs(horizontal) > kotlin.math.abs(vertical))
                    vm.turn(if ((horizontal > 0) == rtl) 1 else -1)
            }
        }, contentAlignment = Alignment.Center) {
            state.bitmap?.let { bitmap ->
                val fitWidth = state.preferences.fit == FitMode.WIDTH
                Box(if (fitWidth) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(bitmap.asImageBitmap(), "Page ${state.page + 1}",
                        (if (fitWidth) Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height) else Modifier.fillMaxSize())
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = panX; translationY = panY }, contentScale = ContentScale.Fit)
                }
            }
            if (state.loading) CircularProgressIndicator()
            state.error?.let { message -> Surface(color = t.colors.surface) { Column(Modifier.padding(16.dp)) {
                Text(message); if (state.count > 0) TextButton({ vm.showPage(state.page) }) { Text("Retry page") }
            } } }
        }
        if (controls) Column(Modifier.padding(horizontal = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ vm.turn(-1) }, enabled = state.page > 0) { Text("Previous") }
                Text("${state.page + 1} / ${state.count}", Modifier.testTag("page_number"))
                TextButton({ vm.turn(1) }, enabled = state.page + 1 < state.count) { Text("Next") }
            }
            if (state.count > 1) {
                var slider by remember(state.page) { mutableFloatStateOf(state.page.toFloat()) }
                Slider(slider, { slider = it }, valueRange = 0f..(state.count - 1).toFloat(), onValueChangeFinished = { vm.showPage(slider.toInt()) })
            }
        }
    }
}
