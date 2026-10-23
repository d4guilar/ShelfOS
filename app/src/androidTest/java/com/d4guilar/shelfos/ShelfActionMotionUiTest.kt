// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Quiet Motion v1A: the shared `shelfAction` keeps one immediate activation per intended input, keeps its layout box and
 * focus ring stable while it animates, and drops the scale (never the ring or the callback) under Remove animations.
 * Scale is observed through pixels: the 100 dp control is filled edge to edge, so a pressed (scaled-down) control shows
 * the white parent at its own corner.
 */
@OptIn(ExperimentalTestApi::class)
class ShelfActionMotionUiTest {
    @get:Rule val rule = createComposeRule()

    private val clicks = mutableIntStateOf(0)
    private val enabled = mutableStateOf(true)
    private val selected = mutableStateOf(true)

    private var count: Int
        get() = clicks.intValue
        set(v) { clicks.intValue = v }

    /** `fill` controls draw their content edge to edge (scale test); the others inset it so the ring stays visible. */
    private fun show(fill: Boolean = true, indicator: Boolean = true) {
        rule.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                Column(Modifier.testTag("col").background(Color.White).padding(16.dp)) {
                    for (tag in listOf("a", "b", "c")) {
                        val base = Modifier.size(100.dp).testTag(tag)
                            .shelfAction(selected = selected.value, enabled = enabled.value, role = Role.Tab,
                                focusIndicator = indicator, onClick = { if (tag == "a") count++ })
                        Box(if (fill) base else base.padding(6.dp)) { Box(Modifier.fillMaxSize().background(Color.Black)) }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun focus(tag: String) { rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus); if (rule.mainClock.autoAdvance) rule.waitForIdle() }

    private fun Bitmap.lum(x: Int, y: Int): Int = getPixel(x, y).let { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)) / 3 }
    /** Top-left pixel of control "a" at rest, read from the parent: a scaled node is captured by its shrunken bounds. */
    private fun corner(): Int = shot("col").lum((16 * rule.density.density).toInt(), (16 * rule.density.density).toInt())
    private fun shot(tag: String): Bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()

    @Test fun touchAndAccessibilityActivateOnceAndKeepSemantics() {
        show()
        rule.onNodeWithTag("a").performClick(); rule.waitForIdle()
        assertEquals(1, count)
        rule.onNodeWithTag("a").performSemanticsAction(SemanticsActions.OnClick); rule.waitForIdle()
        assertEquals(2, count)
        rule.onNodeWithTag("a").assertIsSelected().assert(SemanticsMatcher("role Tab") {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Tab })
        enabled.value = false; rule.waitForIdle()
        rule.onNodeWithTag("a").performTouchInput { click() }; rule.waitForIdle()
        assertEquals("disabled control must not activate", 2, count)
        rule.onNodeWithTag("a").assertIsNotEnabled()
        // A disabled control never leaves a pressed look behind.
        assertTrue(shot("a").lum(1, 50) < 40)
    }

    @Test fun confirmKeysActivateOnceOnKeyUpAndRepeatsDoNotMultiply() {
        show()
        for ((n, key) in listOf(Key.Enter, Key.DirectionCenter, Key.ButtonA).withIndex()) {
            focus("a")
            rule.onNodeWithTag("a").performKeyInput {
                keyDown(key)
            }
            // Held: the system sends further ACTION_DOWN events with a repeat count.
            for (repeat in 1..3) {
                val code = (key.keyCode and 0xFFFFFFFFL).toInt()
                rule.onNodeWithTag("a").performKeyPress(androidx.compose.ui.input.key.KeyEvent(
                    android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, code, repeat)))
            }
            rule.waitForIdle()
            assertEquals("$key fires on key up, not on down/repeat", n, count)
            rule.onNodeWithTag("a").performKeyInput { keyUp(key) }
            rule.waitForIdle()
            assertEquals("$key exactly once", n + 1, count)
        }
        val before = count
        repeat(5) { rule.onNodeWithTag("a").performKeyInput { keyDown(Key.ButtonA); keyUp(Key.ButtonA) } }
        rule.waitForIdle()
        assertEquals("five rapid presses are five activations, none lost, none doubled", before + 5, count)
    }

    @Test fun focusRingStaysAcrossFocusChangeAndSelectionIsUnchanged() {
        show(fill = false)
        focus("a")
        rule.mainClock.autoAdvance = false
        focus("b")
        rule.mainClock.advanceTimeBy(50) // mid focus animation: ring must already be fully drawn on the new target
        assertTrue("ring on focused control mid-animation lum=${shot("b").lum(1, 50)}", shot("b").lum(1, 50) < 90)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("old control's ring is gone lum=${shot("a").lum(1, 50)}", shot("a").lum(1, 50) > 200)
        focus("a")
        assertTrue("re-focus draws the ring again lum=${shot("a").lum(1, 50)}", shot("a").lum(1, 50) < 90)
        rule.onNodeWithTag("a").assertIsSelected().assertIsFocused()
    }

    @Test fun publicationStyleControlDrawsNoRingOfItsOwn() {
        show(fill = false, indicator = false)
        focus("a")
        assertTrue("focusIndicator=false: no ring (only the Material focus state layer, ~10%) lum=${shot("a").lum(1, 50)}", shot("a").lum(1, 50) > 200)
        rule.onNodeWithTag("a").assertIsFocused()
    }

    @Test fun layoutBoxAndNeighboursNeverMoveWhilePressedOrFocused() {
        show()
        fun node(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val rest = listOf("a", "b", "c").map { node(it).let { n -> n.size to n.boundsInRoot } }
        rule.mainClock.autoAdvance = false
        focus("a")
        rule.onNodeWithTag("a").performTouchInput { down(center) }
        for (t in 0 until 6) {
            rule.mainClock.advanceTimeBy(25)
            val now = listOf("a", "b", "c").map { node(it).let { n -> n.size to n.boundsInRoot } }
            for (i in 0..2) assertEquals("frame $t layout size $i", rest[i].first, now[i].first)
            // The pressed node only ever draws inside its resting box (shrink-only); neighbours are untouched.
            val r = rest[0].second; val p = now[0].second
            assertTrue("frame $t stays inside rest box: $p vs $r", p.left >= r.left && p.top >= r.top && p.right <= r.right && p.bottom <= r.bottom)
            assertEquals("frame $t b", rest[1].second, now[1].second); assertEquals("frame $t c", rest[2].second, now[2].second)
        }
        rule.onNodeWithTag("a").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(rest[0].second, node("a").boundsInRoot)
    }

    @Test fun pressedScaleAppliesThenSettlesBackToFullSize() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("a").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(300)
        assertTrue("pressed control is drawn smaller lum=${corner()}", corner() > 200)
        rule.onNodeWithTag("a").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(400)
        assertTrue("released control is back to full size lum=${corner()}", corner() < 40)
        assertEquals(1, count)
    }

    @Test fun cancelledAndFocusLostPressNeverLeavesControlPressed() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("a").performTouchInput { down(center); cancel() }
        rule.mainClock.advanceTimeBy(400)
        assertTrue("touch cancel restores size lum=${corner()}", corner() < 40)
        assertEquals("a cancelled touch is not an activation", 0, count)
        focus("a")
        rule.onNodeWithTag("a").performKeyInput { keyDown(Key.ButtonA) }
        rule.mainClock.advanceTimeBy(300)
        assertTrue("key held: pressed lum=${corner()}", corner() > 200)
        focus("b") // focus leaves mid-press: key-up will go elsewhere
        rule.mainClock.advanceTimeBy(400)
        assertTrue("focus loss restores size lum=${corner()}", corner() < 40)
        assertEquals(0, count)
    }

    @Test fun fastFocusTraversalAndRapidTapsSettleWithoutDelayedActivation() {
        show()
        rule.mainClock.autoAdvance = false
        repeat(6) { i -> focus(listOf("a", "b", "c")[i % 3]); rule.mainClock.advanceTimeBy(10) }
        repeat(4) { rule.onNodeWithTag("a").performTouchInput { click() } ; rule.mainClock.advanceTimeBy(10) }
        // Activation already happened with no animation time elapsed beyond a few frames.
        assertEquals(4, count)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(4, count)
        assertTrue(corner() < 40)
        rule.onNodeWithTag("c").assertIsFocused()
    }

    @Test fun removeAnimationsDropsScaleButKeepsRingAndActivation() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(c: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).use { it.readBytes().decodeToString() }
        val previous = shell("settings get global animator_duration_scale").trim()
        try {
            shell("settings put global animator_duration_scale 0")
            show()
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("a").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(300)
            assertTrue("no decorative scale under Remove animations lum=${corner()}", corner() < 40)
            rule.onNodeWithTag("a").performTouchInput { up() }
            rule.mainClock.advanceTimeBy(50)
            assertEquals(1, count)
        } finally {
            shell("settings put global animator_duration_scale ${previous.takeIf { it.isNotEmpty() && it != "null" } ?: "1.0"}")
        }
    }

    @Test fun removeAnimationsKeepsFocusRingVisibleImmediately() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(c: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).use { it.readBytes().decodeToString() }
        val previous = shell("settings get global animator_duration_scale").trim()
        try {
            shell("settings put global animator_duration_scale 0")
            show(fill = false)
            rule.mainClock.autoAdvance = false
            focus("a"); rule.mainClock.advanceTimeBy(16)
            assertTrue("ring visible with no animation time elapsed lum=${shot("a").lum(1, 50)}", shot("a").lum(1, 50) < 90)
        } finally {
            shell("settings put global animator_duration_scale ${previous.takeIf { it.isNotEmpty() && it != "null" } ?: "1.0"}")
        }
    }
}
