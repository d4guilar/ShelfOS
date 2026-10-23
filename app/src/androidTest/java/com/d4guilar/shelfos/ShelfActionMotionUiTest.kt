// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
        enterKeyboardMode()
    }

    /** Real key injection leaves Android touch mode; without it RequestFocus is refused on API 28+ (as in NavigationSmokeTest). */
    private fun enterKeyboardMode() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
        rule.waitForIdle()
    }

    private val wideClicks = mutableIntStateOf(0)
    private val neighbourClicks = mutableIntStateOf(0)

    /** Full-width 100 dp controls stacked with no gap: at the old 0.98 layer the dead strip is several dp per side. */
    private fun showWide() {
        rule.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                Column(Modifier.testTag("col").background(Color.White).padding(16.dp)) {
                    for (tag in listOf("wtop", "w", "wbot")) {
                        Box(Modifier.fillMaxWidth().height(100.dp).testTag(tag)
                            .shelfAction(role = Role.Tab, onClick = { if (tag == "w") wideClicks.intValue++ else neighbourClicks.intValue++ })) {
                            Box(Modifier.fillMaxSize().background(Color.Black))
                        }
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
    /** One capture per assertion, so the message reports the value that was actually judged. */
    private fun assertPressed(msg: String) {
        val series = mutableListOf(corner())
        // Allow the 90 ms press to settle frame by frame; the series is the failure message.
        while (series.last() <= 200 && series.size < 40) { rule.mainClock.advanceTimeBy(16); series += corner() }
        assertTrue("$msg lum series=$series", series.last() > 200)
    }
    private fun assertResting(msg: String) { val v = corner(); assertTrue("$msg lum=$v", v < 40) }
    private fun shot(tag: String): Bitmap {
        // captureToImage uses PixelCopy (API 26+); the pixel-observed scale checks are skipped, not faked, on API 24-25.
        assumeTrue("captureToImage needs API 26", android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        return rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    }

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

    /** A real Android KeyEvent, checked to be the intended key (not KEYCODE_UNKNOWN) before it is sent. */
    private fun send(tag: String, action: Int, androidCode: Int, expected: Key, repeat: Int = 0) {
        val native = android.view.KeyEvent(0L, 0L, action, androidCode, repeat)
        val event = androidx.compose.ui.input.key.KeyEvent(native)
        assertEquals("generated event keyCode", androidCode, native.keyCode)
        assertEquals("generated event is the intended key", expected, event.key)
        assertEquals(repeat, native.repeatCount)
        rule.onNodeWithTag(tag).performKeyPress(event)
    }

    private val confirmKeys = listOf(
        Triple(Key.Enter, android.view.KeyEvent.KEYCODE_ENTER, "Enter"),
        Triple(Key.DirectionCenter, android.view.KeyEvent.KEYCODE_DPAD_CENTER, "DPadCenter"),
        Triple(Key.NumPadEnter, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, "NumPadEnter"),
        Triple(Key.ButtonA, android.view.KeyEvent.KEYCODE_BUTTON_A, "ButtonA"),
    )

    @Test fun confirmKeysHeldWithRepeatActivateOnceAndReturnToFullSize() {
        show()
        rule.mainClock.autoAdvance = false
        for ((key, code, name) in confirmKeys) {
            focus("a")
            val before = count
            send("a", android.view.KeyEvent.ACTION_DOWN, code, key)
            rule.mainClock.advanceTimeBy(300)
            assertPressed("$name held: pressed")
            // Held past the repeat delay: the system sends further ACTION_DOWN events with repeatCount > 0.
            for (repeat in 1..3) {
                send("a", android.view.KeyEvent.ACTION_DOWN, code, key, repeat)
                rule.mainClock.advanceTimeBy(60)
            }
            assertPressed("$name still pressed while repeating")
            assertEquals("$name fires on key up, not on down/repeat", before, count)
            send("a", android.view.KeyEvent.ACTION_UP, code, key)
            rule.mainClock.advanceTimeBy(300)
            assertEquals("$name exactly once", before + 1, count)
            assertResting("$name released after a repeated hold")
            // A following ordinary tap neither sticks nor doubles.
            send("a", android.view.KeyEvent.ACTION_DOWN, code, key); rule.mainClock.advanceTimeBy(200)
            assertPressed("$name second tap pressed")
            send("a", android.view.KeyEvent.ACTION_UP, code, key); rule.mainClock.advanceTimeBy(300)
            assertEquals("$name second tap once", before + 2, count)
            assertResting("$name second tap released")
        }
    }

    @Test fun controllerAHeldAndReleasedSeparatelyFromClickableKeys() {
        show()
        rule.mainClock.autoAdvance = false
        focus("a")
        val code = android.view.KeyEvent.KEYCODE_BUTTON_A
        send("a", android.view.KeyEvent.ACTION_DOWN, code, Key.ButtonA)
        rule.mainClock.advanceTimeBy(1000)
        assertPressed("A held 1 s: pressed")
        assertEquals(0, count)
        send("a", android.view.KeyEvent.ACTION_UP, code, Key.ButtonA)
        rule.mainClock.advanceTimeBy(300)
        assertEquals(1, count)
        assertResting("A released")
        repeat(5) {
            send("a", android.view.KeyEvent.ACTION_DOWN, code, Key.ButtonA); send("a", android.view.KeyEvent.ACTION_UP, code, Key.ButtonA)
        }
        rule.mainClock.advanceTimeBy(300)
        assertEquals("five rapid presses are five activations, none lost, none doubled", 6, count)
        assertTrue(corner() < 40)
    }

    @Test fun pointerEdgeGesturesOnPressedFullWidthControlStillActivateOnceEach() {
        showWide()
        rule.mainClock.autoAdvance = false
        fun node(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val rest = listOf("wtop", "w", "wbot").map { node(it).let { n -> n.size to n.boundsInRoot } }
        val d = rule.density.density
        val x0 = (16 * d).toInt() + 2; val yMid = ((16 + 100 + 50) * d).toInt()
        // 0.3 dp / 0.8 dp inside each edge, 1 px of movement toward the inside, as a real thumb would produce.
        val gestures: List<Triple<String, (Float, Float, Float) -> Offset, Offset>> = listOf(
            Triple("left 0.3dp", { _, h, dp -> Offset(0.3f * dp, h / 2) }, Offset(1f, 0f)),
            Triple("left 0.8dp", { _, h, dp -> Offset(0.8f * dp, h / 2) }, Offset(1f, 0f)),
            Triple("right 0.3dp", { w, h, dp -> Offset(w - 0.3f * dp - 1f, h / 2) }, Offset(-1f, 0f)),
            Triple("right 0.8dp", { w, h, dp -> Offset(w - 0.8f * dp - 1f, h / 2) }, Offset(-1f, 0f)),
            Triple("top 0.3dp", { w, _, dp -> Offset(w / 2, 0.3f * dp) }, Offset(0f, 1f)),
            Triple("bottom 0.3dp", { w, h, dp -> Offset(w / 2, h - 0.3f * dp - 1f) }, Offset(0f, -1f)),
        )
        for ((n, g) in gestures.withIndex()) {
            val (name, start, move) = g
            val before = wideClicks.intValue
            rule.onNodeWithTag("w").performTouchInput { down(start(width.toFloat(), height.toFloat(), density)) }
            rule.mainClock.advanceTimeBy(300)
            // Pressed: content visibly smaller (white strip at the left of the control), box and neighbours unmoved.
            assertTrue("$name pressed shows subtle feedback lum=${shot("col").lum(x0, yMid)}", shot("col").lum(x0, yMid) > 200)
            rule.onNodeWithTag("w").performTouchInput { moveBy(move) }
            rule.mainClock.advanceTimeBy(50)
            val now = listOf("wtop", "w", "wbot").map { node(it).let { nd -> nd.size to nd.boundsInRoot } }
            assertEquals("$name pressed layout size/bounds unchanged", rest, now)
            rule.onNodeWithTag("w").performTouchInput { up() }
            rule.mainClock.advanceTimeBy(300)
            assertEquals("$name activates exactly once", before + 1, wideClicks.intValue)
            assertEquals("$name must not activate a neighbour", 0, neighbourClicks.intValue)
            assertTrue("$name released back to full size", shot("col").lum(x0, yMid) < 40)
            assertEquals(n + 1, wideClicks.intValue)
        }
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
        assertPressed("pressed control is drawn smaller")
        rule.onNodeWithTag("a").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(400)
        assertResting("released control is back to full size")
        assertEquals(1, count)
    }

    @Test fun cancelledAndFocusLostPressNeverLeavesControlPressed() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("a").performTouchInput { down(center); cancel() }
        rule.mainClock.advanceTimeBy(400)
        assertResting("touch cancel restores size")
        assertEquals("a cancelled touch is not an activation", 0, count)
        focus("a")
        rule.onNodeWithTag("a").performKeyInput { keyDown(Key.ButtonA) }
        rule.mainClock.advanceTimeBy(300)
        assertPressed("key held: pressed")
        focus("b") // focus leaves mid-press: key-up will go elsewhere
        rule.mainClock.advanceTimeBy(400)
        assertResting("focus loss restores size")
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

    private fun shell(c: String): String {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).use { it.readBytes().decodeToString() }
    }

    /**
     * Sets the global animator scale and restores EXACTLY what was there: the prior string, or the key deleted when it was
     * unset (never a hard-coded 1.0). Emulator only; if the test process dies abruptly the setting stays altered, so this
     * must never be automated on a physical device.
     */
    private fun withAnimatorScale(value: String, block: () -> Unit) {
        val fp = android.os.Build.FINGERPRINT + android.os.Build.HARDWARE + android.os.Build.PRODUCT
        assumeTrue("global-setting mutation runs only on a disposable emulator",
            fp.contains("generic") || fp.contains("ranchu") || fp.contains("goldfish") || fp.contains("sdk"))
        val previous = shell("settings get global animator_duration_scale").trim()
        val wasUnset = previous.isEmpty() || previous == "null"
        try {
            shell("settings put global animator_duration_scale $value")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // The platform propagates the setting asynchronously (observed up to ~2.5 s on API 35); wait for it, bounded.
                val deadline = System.currentTimeMillis() + 6000
                while (android.animation.ValueAnimator.areAnimatorsEnabled() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            }
            block()
        } finally {
            if (wasUnset) shell("settings delete global animator_duration_scale") else shell("settings put global animator_duration_scale $previous")
        }
    }

    @Test fun removeAnimationsDropsScaleButKeepsRingAndActivation() = withAnimatorScale("0") {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("a").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(300)
        assertResting("no decorative scale under Remove animations")
        rule.onNodeWithTag("a").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(1, count)
    }

    @Test fun removeAnimationsKeepsFocusRingVisibleImmediately() = withAnimatorScale("0") {
        show(fill = false)
        rule.mainClock.autoAdvance = false
        focus("a"); rule.mainClock.advanceTimeBy(16)
        assertTrue("ring visible with no animation time elapsed lum=${shot("a").lum(1, 50)}", shot("a").lum(1, 50) < 90)
    }
}
