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
 * dependency) that works down to this app's minSdk: it delegates to the system per-app language setting on
 * API 33+ and to AppCompat's own auto-persisted store below that (see the `autoStoreLocales` service in the
 * manifest), recreating every AppCompatActivity with the new locale without a manual relaunch. This never
 * reads or writes Room, ReaderPreferences or any publication/user data — selecting a language only ever
 * touches this process's locale state.
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
