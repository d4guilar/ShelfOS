// SPDX-License-Identifier: MPL-2.0
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.d4guilar.shelfos

import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.FileInputStream

/**
 * Codex QA H1/H2 remediation. Both findings shared one root cause: `EpubNavigatorFragment.Configuration`'s
 * `@font-face` declarations and `ManagedFontContainer`'s resource lookup were fixed at the moment the navigator/
 * publication were first built, so (H1) switching live to a *different*, already-catalogued managed font had no
 * declaration to resolve, and (H2) a font imported after the reader was already open was invisible to the
 * surviving session/container even across `Activity.recreate()` (a ViewModel — and the `EpubSession` it owns —
 * is retained across recreation by design). Both are fixed by resolving the managed-font catalog live
 * (`EpubSession.liveManagedFonts()`, `ManagedFontContainer`'s live resource map) instead of a frozen snapshot, and
 * by declaring `@font-face` for every managed font available to the session, not only the one selected at
 * navigator-creation time.
 */
class EpubManagedFontLiveSwitchTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container
    private val importedFamilies = mutableListOf<String>()

    @Before fun seed() = runBlocking<Unit> { container.library.add(OriginalFixtures.epub(context)) }

    @After fun clean() = runBlocking<Unit> {
        container.library.remove("test-epub")
        importedFamilies.forEach { container.fonts.remove(it) }
    }

    private fun awaitReader() = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun currentNavigator(scenario: ActivityScenario<EpubActivity>): EpubNavigatorFragment? {
        var navigator: EpubNavigatorFragment? = null
        scenario.onActivity { activity -> navigator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull() }
        return navigator
    }

    private fun awaitNavigator(scenario: ActivityScenario<EpubActivity>): EpubNavigatorFragment {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            currentNavigator(scenario)?.takeIf { it.view != null }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Readium navigator did not attach")
    }

    private suspend fun computedFontFamily(navigator: EpubNavigatorFragment, cssFamilySubstring: String): JSONObject {
        val script = """
            (() => {
              const family = getComputedStyle(document.body).fontFamily;
              const face = Array.from(document.fonts).find(f => f.family.includes('$cssFamilySubstring'));
              return JSON.stringify({family, loaded: !!face && face.status === 'loaded'});
            })()
        """.trimIndent()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var last: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = withContext(Dispatchers.Main) { navigator.evaluateJavascript(script) }
            if (raw != null && raw != "null") {
                val decoded = JSONTokener(raw).nextValue() as? String
                if (decoded != null) {
                    last = JSONObject(decoded)
                    if (last.getString("family").contains(cssFamilySubstring) && last.getBoolean("loaded")) return last
                }
            }
            SystemClock.sleep(100)
        }
        return requireNotNull(last) { "Navigator JavaScript returned no font evidence for '$cssFamilySubstring'" }
    }

    private fun cssFamilyFor(familyId: String) = "ShelfOS-${familyId.replace(Regex("[^A-Za-z0-9_-]"), "-")}"

    private fun importDancingScript(): String = runBlocking {
        FileInputStream("/system/fonts/DancingScript-Regular.ttf").use { container.fonts.import(it, "Dancing Script.ttf") }.id
    }.also { importedFamilies += it }

    /** H1: the catalog already has a managed font the reader did NOT open with (builtin Serif, the fixture's
     * default); switching to it live via Appearance — with no Activity/navigator recreation — must actually
     * render, not silently keep the old font because its `@font-face` was never declared on this navigator. */
    @Test fun liveSwitchToAnAlreadyExistingManagedFontRendersWithoutRecreation() {
        val familyId = importDancingScript()
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub")).use { scenario ->
            awaitReader()
            val navigatorBeforeSwitch = awaitNavigator(scenario)
            val before = runBlocking { computedFontFamily(navigatorBeforeSwitch, "serif") }
            assertFalse("Expected to start without the managed family: $before", before.getString("family").contains("ShelfOS-"))

            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("Dancing Script").performScrollTo().performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty() }

            // The navigator/fragment instance itself must be unchanged — this is a live preference update, not a
            // rebuild, which is exactly the scenario H1's fix (declaring every managed font up front) addresses.
            assertSame("The navigator was recreated; this test no longer proves a live switch", navigatorBeforeSwitch, currentNavigator(scenario))

            val after = runBlocking { computedFontFamily(navigatorBeforeSwitch, cssFamilyFor(familyId)) }
            assertTrue("Expected the managed family in computed style: $after", after.getString("family").contains(cssFamilyFor(familyId)))
            assertTrue("Expected the managed @font-face to be loaded: $after", after.getBoolean("loaded"))
        }
    }

    /**
     * H2: a font imported while the reader is already open must become selectable and renderable without leaving
     * or reopening the publication. The production flow (`EpubActivity`'s SAF `importFont` callback) imports via
     * `ManagedFontRepository.import` and then calls `recreate()`; this test exercises that same production shape
     * directly — real import, real `recreate()`, real Appearance selection, real WebView render check — except for
     * the SAF document-picker UI itself, which cannot be driven from an instrumented test (no production code
     * change the picker triggers is skipped; only its own system UI is bypassed by calling the same repository
     * method the picker's callback would have called).
     */
    @Test fun fontImportedWhileReaderIsOpenRendersAfterSelectionWithoutLeavingThePublication() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub")).use { scenario ->
            awaitReader()
            val familyId = importDancingScript()
            scenario.onActivity { it.recreate() }
            awaitReader()

            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("Dancing Script").performScrollTo().performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Reading appearance").fetchSemanticsNodes().isEmpty() }

            val rendered = runBlocking { computedFontFamily(awaitNavigator(scenario), cssFamilyFor(familyId)) }
            assertTrue("Expected the newly imported family in computed style: $rendered", rendered.getString("family").contains(cssFamilyFor(familyId)))
            assertTrue("Expected the newly imported @font-face to be loaded: $rendered", rendered.getBoolean("loaded"))
        }
        // Still the same publication throughout this test: never removed from the library, never navigated back
        // to Library and reopened — only the activity (not the publication) was recreated, matching the
        // production import flow exactly.
        assertNotNull(runBlocking { container.library.publication("test-epub").first() })
    }
}
