// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Proves the real AppCompatDelegate-backed mechanism end to end: this is the half of the localization
 * foundation `LocalizationPolicyTest` (JVM) cannot reach, since AppCompatDelegate's per-app language store is
 * a real Android framework API with no observable behavior outside a running process (no Robolectric in this
 * project — see every other JSON/Android-boundary split here). Exercises the real Settings language row, not
 * `AppLanguageRepository` directly, so a UI/wiring regression there would actually fail this test.
 */
class AppLanguageInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /**
     * QA M1: resetting after the Activity scenario has already closed can silently no-op on API 33+ (no live
     * AppCompatDelegate left to resolve the system LocaleManager through), which is exactly how a prior focused
     * run leaked pt-BR into AppearanceRestorationTest. Resetting inside `scenario.onActivity` here runs while
     * the rule's Activity is still alive — `@After` methods run before the `@get:Rule` itself tears down — and
     * the wait afterward makes the postcondition (English/system-default UI) deterministic for whatever test
     * runs next, regardless of JUnit method order.
     */
    @After fun resetLocale() {
        compose.activityRule.scenario.onActivity {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        // The rendered UI (not the raw flag — see awaitNavSettingsText's doc) is this harness's reliable
        // completion signal; by the time it reflects English, the flag is also reliably readable as empty,
        // which is what the next test's fresh Activity actually resolves its own locale from.
        awaitNavSettingsText("Settings")
        assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty)
    }

    /** On API 33+, AppCompatDelegate.setApplicationLocales() delegates to the system LocaleManager, and its own
     * readback through getApplicationLocales() was observed (via logcat) to lag well behind the Activity
     * relaunch that same locale change causes, sometimes by several seconds, in this slow, JIT-compiling debug
     * test APK — polling it directly as the primary completion signal is unreliable here. The rendered UI
     * ([awaitNavSettingsText]) is used as the real completion signal instead; this is only a final sanity check
     * once that UI has already confirmed the change landed. */
    private fun awaitApplicationLocales(matches: (LocaleListCompat) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (matches(AppCompatDelegate.getApplicationLocales())) return
            SystemClock.sleep(50)
        }
        assertTrue("Application locales did not reach the expected state in time: ${AppCompatDelegate.getApplicationLocales()}",
            matches(AppCompatDelegate.getApplicationLocales()))
    }

    /** The Settings navigation tab's own rendered label — present regardless of rail/bottom layout and
     * without navigating into the Settings screen body, and (unlike reading the locale flag alone) proof that
     * the actual Compose UI, not just AppCompatDelegate's internal state, reflects the chosen language. A
     * locale change (explicit selection or reset) triggers AppCompat's own Activity recreation; waiting for
     * this text is what lets that recreation settle before the test's next step touches the Activity, instead
     * of racing it with an immediate manual recreate() (QA M1's second finding). The generous timeout matches
     * this harness's observed worst case: a relaunch starting 15+ seconds after the click that caused it. */
    private fun awaitNavSettingsText(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("nav_settings") and hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun openSettings() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("language_spanish").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Clicks a language row and waits for the actual rendered UI to settle on the new language — the
     * reliable completion signal in this harness (see awaitNavSettingsText's doc) — rather than polling
     * AppCompatDelegate.getApplicationLocales() directly beforehand, which was observed to lag well behind the
     * recreation it itself triggers. Only once the UI confirms the change is the flag also checked, as a final
     * sanity assertion, never as the gate itself (QA M1's second finding: never race an uncertain automatic
     * recreation with a manual one — waiting for the UI first sidesteps that race entirely). */
    private fun selectLanguage(tag: String, localeMatches: (LocaleListCompat) -> Boolean, expectedNavSettingsText: String) {
        // The Language section sits below Appearance's theme list in Settings' scrollable column, so the row
        // is very likely off-screen; performClick() does not auto-scroll a node into view first.
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        awaitNavSettingsText(expectedNavSettingsText)
        awaitApplicationLocales(localeMatches)
    }

    private fun stringsFor(locale: Locale): Resources {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration).resources
    }

    /** One real ShelfOS-owned label, resolved in each of the three supported languages via a scoped
     * configuration context — proving the resource set itself is wired up correctly. This never touches the
     * app-wide AppCompatDelegate locale, so unlike the tests below it carries no leak risk at all. */
    @Test fun navigationLabelResolvesToTheExpectedTextInEachSupportedLanguage() {
        assertEquals("Settings", stringsFor(Locale.ENGLISH).getString(R.string.nav_settings))
        assertEquals("Ajustes", stringsFor(Locale.forLanguageTag("es")).getString(R.string.nav_settings))
        assertEquals("Configurações", stringsFor(Locale.forLanguageTag("pt-BR")).getString(R.string.nav_settings))
    }

    /** Drives the real Settings language row (not AppLanguageRepository directly) and reads back the actual
     * rendered UI in each language, including across a deliberate recreation issued only once the locale flag
     * itself has settled — see selectLanguage's doc. */
    @Test fun selectingLanguageThroughTheSettingsRowRendersLocalizedUiAndSurvivesRecreation() {
        openSettings()
        selectLanguage("language_spanish", { !it.isEmpty }, "Ajustes")

        // A second recreation proves the choice and its rendering both survive repeated recreation.
        compose.activityRule.scenario.recreate()
        awaitNavSettingsText("Ajustes")

        // Recreation resets the nav graph to its start destination, so Settings is re-entered explicitly.
        openSettings()
        selectLanguage("language_portuguese_brazil", { it.toLanguageTags() == "pt-BR" }, "Configurações")
    }

    @Test fun systemDefaultIsNotPinnedToTheCurrentSystemLocale() {
        openSettings()
        selectLanguage("language_spanish", { !it.isEmpty }, "Ajustes")

        compose.onNodeWithTag("nav_settings").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("language_system_default").fetchSemanticsNodes().isNotEmpty() }
        // An empty override list means "follow the system", not a pinned snapshot of the explicit choice just
        // made — the rendered label reverting to English (this emulator's own system locale) alongside the
        // empty override list is the actual-UI half of that proof (QA M1's third finding).
        selectLanguage("language_system_default", { it.isEmpty }, "Settings")
    }
}
