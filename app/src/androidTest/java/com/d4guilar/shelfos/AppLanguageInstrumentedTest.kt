// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.localization.AppLanguage
import com.d4guilar.shelfos.data.preferences.AppCompatLanguageRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Proves the real AppCompatDelegate-backed mechanism end to end: this is the half of the localization
 * foundation [LocalizationPolicyTest] (JVM) cannot reach, since AppCompatDelegate's per-app language store is a
 * real Android framework API with no observable behavior outside a running process (no Robolectric in this
 * project — see every other JSON/Android-boundary split here).
 */
@RunWith(AndroidJUnit4::class)
class AppLanguageInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** AppCompatDelegate's locale is process-global; every test restores it so later tests see the default. */
    @After fun resetLocale() { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }

    private fun stringsFor(locale: Locale): Resources {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        return context.createConfigurationContext(configuration).resources
    }

    /** On API 33+, AppCompatDelegate.setApplicationLocales() delegates to the system LocaleManager, whose
     * readback through getApplicationLocales() is not guaranteed synchronous with the call that set it. Every
     * assertion against it here polls briefly rather than reading it back immediately. */
    private fun awaitApplicationLocales(matches: (androidx.core.os.LocaleListCompat) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (matches(AppCompatDelegate.getApplicationLocales())) return
            SystemClock.sleep(50)
        }
        assertTrue("Application locales did not reach the expected state in time: ${AppCompatDelegate.getApplicationLocales()}",
            matches(AppCompatDelegate.getApplicationLocales()))
    }

    /** One real ShelfOS-owned label, resolved in each of the three supported languages — proving the resource
     * set is actually wired up and produces the intended, distinct, natural-language text per locale. */
    @Test fun navigationLabelResolvesToTheExpectedTextInEachSupportedLanguage() {
        assertEquals("Settings", stringsFor(Locale.ENGLISH).getString(R.string.nav_settings))
        assertEquals("Ajustes", stringsFor(Locale.forLanguageTag("es")).getString(R.string.nav_settings))
        assertEquals("Configurações", stringsFor(Locale.forLanguageTag("pt-BR")).getString(R.string.nav_settings))
    }

    @Test fun systemDefaultIsNotPinnedToTheCurrentSystemLocale() {
        // The system per-app language API (API 33+) expects a foregrounded Activity of this package, the way a
        // real Settings screen naturally provides one; a bare call with no Activity alive does not reliably
        // propagate to getApplicationLocales(), matching explicitLanguageChoiceSurvivesActivityRecreation below.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var repository: AppCompatLanguageRepository
            scenario.onActivity { repository = AppCompatLanguageRepository(); repository.select(AppLanguage.ENGLISH) }
            awaitApplicationLocales { !it.isEmpty }
            scenario.onActivity { repository.select(AppLanguage.SYSTEM_DEFAULT) }
            // An empty override list means "follow the system", not a pinned snapshot of today's system locale.
            awaitApplicationLocales { it.isEmpty }
        }
    }

    @Test fun explicitLanguageChoiceSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("pt-BR")) }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            instrumentation.waitForIdleSync()
            awaitApplicationLocales { it.toLanguageTags() == "pt-BR" }
        }
    }
}
