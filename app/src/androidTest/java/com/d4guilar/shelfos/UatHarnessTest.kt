// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.d4guilar.shelfos.debug.UatLauncherActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Debug-only manual-UAT harness (used on a tablet without ADB) must itself work: it reports the device and WebView on
 * screen, opens the synthetic reader, and the 500-decoration button seeds valid highlights and shows an on-screen verdict.
 * Expected on this suite's WebView 109 device: capability OFFERED and a PASS banner. On a legacy WebView the banner reports
 * SKIPPED (capability gate) instead, which this test also accepts, because that is the documented expected outcome there.
 */
class UatHarnessTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val container get() = (inst.targetContext.applicationContext as ShelfApplication).container

    @After fun reset() = runBlocking<Unit> { container.library.remove(UatLauncherActivity.BOOK_ID); container.library.remove(UatLauncherActivity.STRESS_ID) }

    private fun bannerText(): String? {
        var text: String? = null
        inst.runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList()
            fun walk(view: View?, out: MutableList<TextView>) { when (view) { is TextView -> out += view; is ViewGroup -> for (i in 0 until view.childCount) walk(view.getChildAt(i), out) } }
            resumed.forEach { activity: Activity ->
                val views = mutableListOf<TextView>(); walk(activity.window.decorView, views)
                views.firstOrNull { it.text.startsWith("UAT 500-decoration stress") }?.let { text = it.text.toString() }
            }
        }
        return text
    }

    @Test fun launcherShowsDeviceAndWebViewAndTheStressButtonReportsAnOnScreenVerdict() {
        ActivityScenario.launch(UatLauncherActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("WebView version:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Device:", substring = true).assertExists()
            compose.onNodeWithText("ShelfOS inline annotation capability:", substring = true).assertExists()
            compose.onNodeWithText("Run 500-decoration stress", substring = true).performClick()
            val deadline = SystemClock.uptimeMillis() + 120_000
            var verdict: String? = null
            while (SystemClock.uptimeMillis() < deadline) {
                compose.waitForIdle()
                verdict = bannerText()
                if (verdict != null && (verdict.contains("PASS") || verdict.contains("FAIL") || verdict.contains("SKIPPED"))) break
                SystemClock.sleep(500)
            }
            android.util.Log.i("UAT_HARNESS", "banner=$verdict")
            assertNotNull("an on-screen verdict appeared", verdict)
            assertTrue("PASS, or SKIPPED on a legacy WebView; was: $verdict", verdict!!.contains("PASS") || verdict.contains("SKIPPED"))
        }
    }
}
