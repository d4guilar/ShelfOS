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
import com.d4guilar.shelfos.core.theme.reducedMotionEnabled
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Quiet Motion v1A: the shared `shelfAction` keeps one immediate activation per intended input, keeps its layout box and
 * focus ring stable while it animates, and drops the scale (never the ring or the callback) under Remove animations.
 *
 * Two layers, so API 24-25 is not skipped wholesale:
 *  - `*Functional` tests need no image: activation counts, key repeat, raw near-edge touches, cancel/focus loss, layout,
 *    and the semantics bounds (`boundsInRoot`). Bounds detect graphics-layer geometry changes, which is how the original
 *    `29f2f82` implementation was partly caught on API 24. They cannot detect a latched draw-only pressed scale (the H-1
 *    stuck look), because no bounds move. That regression is guarded only by the `*Visual` pixel test on API 26+; no H-1
 *    visual detection is claimed on API 24-25.
 *  - `*Visual` tests additionally read pixels (the 100 dp control is filled edge to edge, so a pressed control shows the
 *    white parent at its corner). `captureToImage` uses PixelCopy, so they run on API 26+ only.
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

    /** True only inside a `*Visual` test: pixel assertions run; otherwise they are no-ops and the rest still executes. */
    private var visual = false

    /** Marks a test as pixel-observing; skips (only that test) below API 26 where PixelCopy is unavailable. */
    private fun requireVisual() {
        assumeTrue("captureToImage needs API 26 (PixelCopy)", android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        visual = true
    }

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

    private fun node(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode()
    private fun boundsOf(tag: String) = node(tag).let { it.size to it.boundsInRoot }

    private fun Bitmap.lum(x: Int, y: Int): Int = getPixel(x, y).let { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)) / 3 }
    private fun shot(tag: String): Bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    /** Top-left pixel of control "a" at rest, read from the parent: a scaled node is captured by its shrunken bounds. */
    private fun corner(): Int = shot("col").lum((16 * rule.density.density).toInt(), (16 * rule.density.density).toInt())
    /** One capture per assertion, so the message reports the value that was actually judged. No-op unless [visual]. */
    private fun assertPressed(msg: String) {
        if (!visual) return
        val series = mutableListOf(corner())
        // Allow the 90 ms press to settle frame by frame; the series is the failure message.
        while (series.last() <= 200 && series.size < 40) { rule.mainClock.advanceTimeBy(16); series += corner() }
        assertTrue("$msg lum series=$series", series.last() > 200)
    }
    private fun assertResting(msg: String) { if (!visual) return; val v = corner(); assertTrue("$msg lum=$v", v < 40) }
    private fun ringLum(tag: String): Int = shot(tag).lum(1, 50)

    // ---- touch, semantics, disabled ------------------------------------------------------------------------------

    private fun touchAndAccessibilityCore() {
        show()
        val rest = boundsOf("a")
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
        assertEquals(rest, boundsOf("a"))
        // A disabled control never leaves a pressed look behind.
        if (visual) assertTrue(ringLum("a") < 40)
    }
    @Test fun touchAndAccessibilityActivateOnceAndKeepSemanticsFunctional() = touchAndAccessibilityCore()
    @Test fun touchAndAccessibilityActivateOnceAndKeepSemanticsVisual() { requireVisual(); touchAndAccessibilityCore() }

    // ---- key repeat -----------------------------------------------------------------------------------------------

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

    private fun heldRepeatCore() {
        show()
        val rest = boundsOf("a")
        rule.mainClock.autoAdvance = false
        for ((key, code, name) in confirmKeys) {
            focus("a")
            val before = count
            send("a", android.view.KeyEvent.ACTION_DOWN, code, key)
            rule.mainClock.advanceTimeBy(300)
            assertPressed("$name held:")
            // Held past the repeat delay: the system sends further ACTION_DOWN events with repeatCount > 0.
            for (repeat in 1..3) {
                send("a", android.view.KeyEvent.ACTION_DOWN, code, key, repeat)
                rule.mainClock.advanceTimeBy(60)
            }
            assertPressed("$name still pressed while repeating:")
            assertEquals("$name fires on key up, not on down/repeat", before, count)
            send("a", android.view.KeyEvent.ACTION_UP, code, key)
            rule.mainClock.advanceTimeBy(300)
            assertEquals("$name exactly once", before + 1, count)
            assertEquals("$name semantics bounds back at rest after a repeated hold", rest, boundsOf("a"))
            assertResting("$name released after a repeated hold")
            // A following ordinary tap neither sticks nor doubles.
            send("a", android.view.KeyEvent.ACTION_DOWN, code, key); rule.mainClock.advanceTimeBy(200)
            assertPressed("$name second tap pressed")
            send("a", android.view.KeyEvent.ACTION_UP, code, key); rule.mainClock.advanceTimeBy(300)
            assertEquals("$name second tap once", before + 2, count)
            assertEquals("$name second tap bounds at rest", rest, boundsOf("a"))
            assertResting("$name second tap released")
        }
    }
    @Test fun confirmKeysHeldWithRepeatActivateOnceFunctional() = heldRepeatCore()
    @Test fun confirmKeysHeldWithRepeatActivateOnceAndReturnToFullSizeVisual() { requireVisual(); heldRepeatCore() }

    private fun controllerACore() {
        show()
        val rest = boundsOf("a")
        rule.mainClock.autoAdvance = false
        focus("a")
        val code = android.view.KeyEvent.KEYCODE_BUTTON_A
        send("a", android.view.KeyEvent.ACTION_DOWN, code, Key.ButtonA)
        rule.mainClock.advanceTimeBy(1000)
        assertPressed("A held 1 s:")
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
        assertEquals(rest, boundsOf("a"))
        assertResting("after rapid presses")
    }
    @Test fun controllerAHeldAndReleasedSeparatelyFromClickableKeysFunctional() = controllerACore()
    @Test fun controllerAHeldAndReleasedSeparatelyFromClickableKeysVisual() { requireVisual(); controllerACore() }

    // ---- raw edge touches -----------------------------------------------------------------------------------------

    private fun edgeCore() {
        showWide()
        rule.mainClock.autoAdvance = false
        val rest = listOf("wtop", "w", "wbot").map { boundsOf(it) }
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
            if (visual) assertTrue("$name pressed shows subtle feedback lum=${shot("col").lum(x0, yMid)}", shot("col").lum(x0, yMid) > 200)
            rule.onNodeWithTag("w").performTouchInput { moveBy(move) }
            rule.mainClock.advanceTimeBy(50)
            assertEquals("$name pressed layout size/bounds unchanged", rest, listOf("wtop", "w", "wbot").map { boundsOf(it) })
            rule.onNodeWithTag("w").performTouchInput { up() }
            rule.mainClock.advanceTimeBy(300)
            assertEquals("$name activates exactly once", before + 1, wideClicks.intValue)
            assertEquals("$name must not activate a neighbour", 0, neighbourClicks.intValue)
            assertEquals("$name released bounds", rest, listOf("wtop", "w", "wbot").map { boundsOf(it) })
            if (visual) assertTrue("$name released back to full size", shot("col").lum(x0, yMid) < 40)
            assertEquals(n + 1, wideClicks.intValue)
        }
    }
    @Test fun pointerEdgeGesturesOnFullWidthControlStillActivateOnceEachFunctional() = edgeCore()
    @Test fun pointerEdgeGesturesOnPressedFullWidthControlStillActivateOnceEachVisual() { requireVisual(); edgeCore() }

    // ---- focus ring -----------------------------------------------------------------------------------------------

    @Test fun focusMovesAndSelectionIsUnchangedFunctional() {
        show(fill = false)
        focus("a"); rule.onNodeWithTag("a").assertIsFocused()
        focus("b"); rule.onNodeWithTag("b").assertIsFocused()
        focus("a")
        rule.onNodeWithTag("a").assertIsSelected().assertIsFocused()
    }

    @Test fun focusRingStaysAcrossFocusChangeVisual() {
        requireVisual()
        show(fill = false)
        focus("a")
        rule.mainClock.autoAdvance = false
        focus("b")
        rule.mainClock.advanceTimeBy(50) // mid focus animation: ring must already be fully drawn on the new target
        assertTrue("ring on focused control mid-animation lum=${ringLum("b")}", ringLum("b") < 90)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("old control's ring is gone lum=${ringLum("a")}", ringLum("a") > 200)
        focus("a")
        assertTrue("re-focus draws the ring again lum=${ringLum("a")}", ringLum("a") < 90)
        rule.onNodeWithTag("a").assertIsSelected().assertIsFocused()
    }

    @Test fun publicationStyleControlDrawsNoRingOfItsOwnVisual() {
        requireVisual()
        show(fill = false, indicator = false)
        focus("a")
        assertTrue("focusIndicator=false: no ring (only the Material focus state layer, ~10%) lum=${ringLum("a")}", ringLum("a") > 200)
        rule.onNodeWithTag("a").assertIsFocused()
    }

    // ---- layout stability -----------------------------------------------------------------------------------------

    @Test fun layoutBoxAndNeighboursNeverMoveWhilePressedOrFocused() {
        show()
        val rest = listOf("a", "b", "c").map { boundsOf(it) }
        rule.mainClock.autoAdvance = false
        focus("a")
        rule.onNodeWithTag("a").performTouchInput { down(center) }
        for (t in 0 until 6) {
            rule.mainClock.advanceTimeBy(25)
            assertEquals("frame $t layout size/bounds", rest, listOf("a", "b", "c").map { boundsOf(it) })
        }
        rule.onNodeWithTag("a").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(rest[0], boundsOf("a"))
    }

    // ---- press scale and release ----------------------------------------------------------------------------------

    @Test fun pressedScaleAppliesThenSettlesBackToFullSizeVisual() {
        requireVisual()
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

    private fun cancelAndFocusLossCore() {
        show()
        val rest = boundsOf("a")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("a").performTouchInput { down(center); cancel() }
        rule.mainClock.advanceTimeBy(400)
        assertResting("touch cancel restores size")
        assertEquals(rest, boundsOf("a"))
        assertEquals("a cancelled touch is not an activation", 0, count)
        focus("a")
        rule.onNodeWithTag("a").performKeyInput { keyDown(Key.ButtonA) }
        rule.mainClock.advanceTimeBy(300)
        assertPressed("key held:")
        focus("b") // focus leaves mid-press: key-up will go elsewhere
        rule.mainClock.advanceTimeBy(400)
        assertResting("focus loss restores size")
        assertEquals(rest, boundsOf("a"))
        assertEquals(0, count)
    }
    @Test fun cancelledAndFocusLostPressNeverActivatesFunctional() = cancelAndFocusLossCore()
    @Test fun cancelledAndFocusLostPressNeverLeavesControlPressedVisual() { requireVisual(); cancelAndFocusLossCore() }

    private fun fastTraversalCore() {
        show()
        val rest = boundsOf("a")
        rule.mainClock.autoAdvance = false
        repeat(6) { i -> focus(listOf("a", "b", "c")[i % 3]); rule.mainClock.advanceTimeBy(10) }
        repeat(4) { rule.onNodeWithTag("a").performTouchInput { click() } ; rule.mainClock.advanceTimeBy(10) }
        // Activation already happened with no animation time elapsed beyond a few frames.
        assertEquals(4, count)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(4, count)
        assertEquals(rest, boundsOf("a"))
        assertResting("after fast taps")
        rule.onNodeWithTag("c").assertIsFocused()
    }
    @Test fun fastFocusTraversalAndRapidTapsSettleWithoutDelayedActivationFunctional() = fastTraversalCore()
    @Test fun fastFocusTraversalAndRapidTapsSettleVisual() { requireVisual(); fastTraversalCore() }

    // ---- Remove animations (global setting mutation; emulator only) -----------------------------------------------

    private fun shell(c: String): String {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).use { it.readBytes().decodeToString() }
    }

    /**
     * Emulator detection policy (fails closed): the build must report an emulator kernel hardware (`ranchu` or `goldfish`)
     * AND, when `ro.kernel.qemu` is readable, it must be `1`. A physical device (RP5, SM-T580, ...) reports neither, so the
     * global-setting tests skip there; unrelated interaction tests never call this. The shell runs on the device that hosts
     * the instrumentation, so which device that is stays decided by the explicit serial (ANDROID_SERIAL / -s) used to launch.
     */
    private fun isDisposableEmulator(): Boolean {
        val hardware = android.os.Build.HARDWARE.lowercase()
        if (hardware != "ranchu" && hardware != "goldfish") return false
        val qemu = shell("getprop ro.kernel.qemu").trim()
        return qemu.isEmpty() || qemu == "1"
    }

    /**
     * Sets the global animator scale and restores EXACTLY what was there: the prior string, or the key deleted when it was
     * unset (never a hard-coded 1.0). Pre/post values are logged and compared. If the test process dies abruptly the setting
     * stays altered, so this must never be automated on a physical device (see [isDisposableEmulator]).
     */
    private fun withAnimatorScale(value: String, block: () -> Unit) {
        assumeTrue("global-setting mutation runs only on a disposable emulator (ranchu/goldfish)", isDisposableEmulator())
        val previous = shell("settings get global animator_duration_scale").trim()
        val wasUnset = previous.isEmpty() || previous == "null"
        android.util.Log.i(TAG, "animator_duration_scale before: '$previous' (unset=$wasUnset)")
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
            val after = shell("settings get global animator_duration_scale").trim()
            android.util.Log.i(TAG, "animator_duration_scale after restore: '$after'")
            check(after == previous) { "animator_duration_scale not restored: before='$previous' after='$after'" }
        }
    }

    /**
     * Runs on every API level, including the pre-26 `Settings.Global` path of `reducedMotionEnabled()` on API 24. Establishes
     * that the policy reads "reduced" and that activation, focus and layout still work; it cannot show the scale was dropped
     * (that needs pixels, see the Visual tests).
     */
    @Test fun removeAnimationsKeepsActivationFocusAndLayoutFunctional() = withAnimatorScale("0") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("reducedMotionEnabled() sees animator_duration_scale=0", context.reducedMotionEnabled())
        show()
        val rest = boundsOf("a")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("a").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(300)
        assertEquals(rest, boundsOf("a"))
        rule.onNodeWithTag("a").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(50)
        assertEquals(1, count)
        focus("a"); rule.onNodeWithTag("a").assertIsFocused()
        send("a", android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, Key.Enter)
        send("a", android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, Key.Enter)
        rule.mainClock.advanceTimeBy(50)
        assertEquals(2, count)
    }

    @Test fun removeAnimationsDropsScaleButKeepsRingAndActivationVisual() {
        requireVisual()
        withAnimatorScale("0") {
            show()
            rule.mainClock.autoAdvance = false
            rule.onNodeWithTag("a").performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(300)
            assertResting("no decorative scale under Remove animations")
            rule.onNodeWithTag("a").performTouchInput { up() }
            rule.mainClock.advanceTimeBy(50)
            assertEquals(1, count)
        }
    }

    @Test fun removeAnimationsKeepsFocusRingVisibleImmediatelyVisual() {
        requireVisual()
        withAnimatorScale("0") {
            show(fill = false)
            rule.mainClock.autoAdvance = false
            focus("a"); rule.mainClock.advanceTimeBy(16)
            assertTrue("ring visible with no animation time elapsed lum=${ringLum("a")}", ringLum("a") < 90)
        }
    }

    private companion object { const val TAG = "ShelfActionMotionUiTest" }
}
