// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.preferences

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.d4guilar.shelfos.core.localization.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface AppLanguageRepository {
    val language: StateFlow<AppLanguage>
    fun select(language: AppLanguage)
}

/**
 * Applies and persists ShelfOS's interface language through AndroidX's per-app language support
 * (AppCompatDelegate), the platform-supported mechanism already in this stack (appcompat is already a
 * dependency) that works down to this app's minSdk: on API 33+ it routes through the system LocaleManager (so
 * the choice survives this app's own process death) and on earlier API levels through AppCompat's own
 * auto-persisted store (see the `autoStoreLocales` service in the manifest), recreating every AppCompatActivity
 * with the new locale without a manual relaunch. ShelfOS does not currently declare `android:localeConfig`, so
 * it has no entry in Android Settings -> Apps -> App language and nothing changes this locale from outside this
 * repository's own [select] today; [language] is a plain snapshot updated only there, not a live observer of
 * AppCompatDelegate, so it would go stale if that ever changed (documented LOW follow-up, not fixed here — it
 * would need Activity-lifecycle-level observation, out of scope for this pass). This never reads or writes
 * Room, ReaderPreferences or any publication/user data — selecting a language only ever touches this process's
 * locale state.
 */
class AppCompatLanguageRepository : AppLanguageRepository {
    private val _language = MutableStateFlow(AppCompatDelegate.getApplicationLocales().toAppLanguage())
    override val language: StateFlow<AppLanguage> = _language

    override fun select(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(language.toLocaleList())
        _language.value = language
    }
}

internal fun AppLanguage.toLocaleList(): LocaleListCompat =
    tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList()

internal fun LocaleListCompat.toAppLanguage(): AppLanguage =
    if (isEmpty) AppLanguage.SYSTEM_DEFAULT else AppLanguage.fromTag(get(0)?.toLanguageTag())
