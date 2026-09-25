// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Readium's navigator is rebuilt on recreation; the reader's own UI state must be restored around it. */
class EpubRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    @Before fun seedOriginalEpub() = runBlocking<Unit> { container.library.add(OriginalFixtures.epub(context)) }
    @After fun removeOriginalEpub() = runBlocking<Unit> { container.library.remove("test-epub") }

    private fun awaitReader() = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    /** Reads the current reader activity on the main thread. */
    private fun <T> ActivityScenario<EpubActivity>.read(value: (EpubActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = Result.success(value(it)) }
        return result!!.getOrThrow()
    }

    @Test fun readerUiStateSurvivesRecreationAndAppliedAppearanceReloads() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub")).use { scenario ->
            awaitReader()
            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("Paper").performScrollTo().performClick()
            scenario.recreate()
            // The dialog is still open with its unapplied change.
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Paper").assertIsSelected()
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(10_000) {
                runBlocking { container.library.publication("test-epub").first()?.preferences.orEmpty().contains("PAPER") }
            }
            // Keys go to the focused window: the reader's window must have focus again now the dialog is gone.
            compose.waitUntil(5_000) { scenario.read { it.hasWindowFocus() } }
            // Hidden reader controls stay hidden through a real rotation, which the system performs by relaunching.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("epub_library").fetchSemanticsNodes().isEmpty() }
            // Request whichever orientation the device is not already in, so this also rotates hardware that is
            // locked to landscape by default (for example handhelds with auto-rotate off).
            val before = scenario.read { it }
            val startOrientation = before.resources.configuration.orientation
            val target = if (startOrientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            scenario.onActivity { it.requestedOrientation = target }
            compose.waitUntil(30_000) {
                scenario.read { it !== before && it.resources.configuration.orientation != startOrientation }
            }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("epub_reader").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("epub_library").assertCountEquals(0)
        }
        // Reopened, the reader shows the applied appearance from the library.
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub")).use {
            awaitReader()
            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("Paper").assertIsSelected()
        }
    }
}
