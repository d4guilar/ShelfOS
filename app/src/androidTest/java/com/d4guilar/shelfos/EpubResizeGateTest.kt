// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/** Mandatory Phase 4C.2 gate: real Readium reflow while the one mounted EPUB surface changes width. */
class EpubResizeGateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container
    private val itemId = OriginalFixtures.ANNOTATION_ITEM_ID

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    @Before fun seed() = runBlocking<Unit> {
        shell("wm size 1600x1000")
        shell("wm density 160")
        container.library.remove(itemId)
        container.library.add(OriginalFixtures.epubAnnotations(context))
    }

    @After fun cleanup() = runBlocking<Unit> {
        container.library.remove(itemId)
        shell("wm size reset")
        shell("wm density 360")
    }

    private fun locator(fragment: EpubNavigatorFragment): JSONObject = fragment.currentLocator.value.toJSON()

    @Test fun mountedNavigatorSurvivesRepeatedPaneEquivalentWidthChanges() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, itemId)).use { scenario ->
            val probe = AnnotationUiProbe(instrumentation, scenario) { compose.waitForIdle() }
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("epub_notes").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("initial EPUB resource ready", probe.awaitReady())

            lateinit var navigator: EpubNavigatorFragment
            lateinit var baseline: JSONObject
            var activityIdentity = 0
            scenario.onActivity { activity ->
                activityIdentity = System.identityHashCode(activity)
                navigator = activity.supportFragmentManager.findFragmentById(R.id.epub_container) as EpubNavigatorFragment
                baseline = locator(navigator)
            }
            val baselineWidth = probe.jsString("window.innerWidth")!!.toInt()
            val baselineHref = baseline.getString("href")
            val baselineProgress = baseline.getJSONObject("locations").optDouble("totalProgression", 0.0)

            repeat(4) { cycle ->
                compose.onNodeWithTag("epub_notes").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("notebook_pane").fetchSemanticsNodes().isNotEmpty() }
                if (cycle == 0) compose.onNodeWithTag("notebook_filter_highlights").performClick().assertIsSelected()
                else compose.onNodeWithTag("notebook_filter_highlights").assertIsSelected()
                val deadline = SystemClock.uptimeMillis() + 15_000
                var width = baselineWidth
                while (SystemClock.uptimeMillis() < deadline) {
                    compose.waitForIdle()
                    width = probe.jsString("window.innerWidth")?.toIntOrNull() ?: width
                    if (width < baselineWidth) break
                    SystemClock.sleep(100)
                }
                assertTrue("cycle $cycle reflowed narrower", width < baselineWidth)
                assertTrue("cycle $cycle resource remains usable", probe.awaitReady())

                scenario.onActivity { activity ->
                    assertEquals("activity was not recreated", activityIdentity, System.identityHashCode(activity))
                    val current = activity.supportFragmentManager.findFragmentById(R.id.epub_container) as EpubNavigatorFragment
                    assertSame("navigator was not replaced", navigator, current)
                    assertEquals("one navigator fragment", 1,
                        activity.supportFragmentManager.fragments.count { it is EpubNavigatorFragment })
                    val after = locator(current)
                    assertEquals("resource href changed", baselineHref, after.getString("href"))
                    val progress = after.getJSONObject("locations").optDouble("totalProgression", 0.0)
                    assertTrue("progress moved by more than one percent: $baselineProgress -> $progress",
                        kotlin.math.abs(progress - baselineProgress) <= 0.01)
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("notebook_pane").fetchSemanticsNodes().isEmpty() }
                compose.waitUntil(15_000) { probe.jsString("window.innerWidth")?.toIntOrNull() == baselineWidth }
            }
        }
    }
}
