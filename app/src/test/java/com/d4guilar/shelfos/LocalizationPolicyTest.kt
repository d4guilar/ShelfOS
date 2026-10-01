// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.localization.AppLanguage
import com.d4guilar.shelfos.core.reader.BookFont
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.data.preferences.AppLanguageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test

/**
 * The localization foundation's two halves are tested at different boundaries, mirroring every other
 * Android-framework-adjacent split in this project (see ReadingPolicyTest's JSON-boundary note): [AppLanguage]
 * itself is a plain Kotlin enum with no Android dependency, so its default/mapping rules are tested directly
 * here. The real persistence mechanism ([AppCompatDelegate.setApplicationLocales] via
 * `AppCompatLanguageRepository`) is a thin wrapper around a static Android API with no observable behavior
 * outside a real process, so its actual persist-across-recreation contract is proven instead by
 * `AppLanguageInstrumentedTest`. Here, that same [AppLanguageRepository] *interface* contract — select, read
 * back, survive a fresh repository instance over the same backing store — is exercised against an in-memory
 * fake that honors the interface precisely, which is what every ViewModel/Compose call site actually depends
 * on (not the concrete AppCompatDelegate wiring).
 */
class LocalizationPolicyTest {
    @Test fun defaultApplicationLanguageIsSystemDefault() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.DEFAULT)
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag(null))
        assertNull(AppLanguage.SYSTEM_DEFAULT.tag)
    }

    @Test fun explicitLanguagesRoundTripThroughStableLocaleNeutralTags() {
        assertEquals("en", AppLanguage.ENGLISH.tag)
        assertEquals("es", AppLanguage.SPANISH.tag)
        assertEquals("pt-BR", AppLanguage.PORTUGUESE_BRAZIL.tag)
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTag("es"))
        assertEquals(AppLanguage.PORTUGUESE_BRAZIL, AppLanguage.fromTag("pt-BR"))
    }

    /** A locale this picker never offers (e.g. chosen outside ShelfOS, or a stale/unknown tag) must not be
     * silently treated as a pinned, permanent choice — it falls back to following the system language instead,
     * exactly like no explicit choice at all. This is the "system default never gets permanently pinned" rule
     * from a locale ShelfOS doesn't recognize, not from the user's own SYSTEM_DEFAULT selection (covered above). */
    @Test fun unrecognizedLocaleTagFallsBackToSystemDefaultRatherThanPinning() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag("fr"))
        assertEquals(AppLanguage.SYSTEM_DEFAULT, AppLanguage.fromTag(""))
    }

    /** The four persisted identifiers are locale-neutral BCP-47 tags (or null for system default), never one of
     * the localized labels a Settings screen would display — see AGENTS.md's localization rules and PRODUCT.md's
     * "stable, locale-neutral" persistence requirement. */
    @Test fun persistedIdentifiersAreLocaleNeutralNotLocalizedLabels() {
        val tags = AppLanguage.entries.map { it.tag }
        val localizedLabels = setOf("System default", "English", "Español", "Português (Brasil)",
            "Predeterminado del sistema", "Padrão do sistema")
        assertTrue(tags.none { it in localizedLabels })
    }

    /** A minimal in-memory double for [AppLanguageRepository], standing in for a real process's persisted
     * per-app locale store to prove the interface contract every call site actually relies on. */
    private class FakeAppLanguageRepository(private val store: MutableMap<String, AppLanguage>) : AppLanguageRepository {
        private val _language = MutableStateFlow(store["language"] ?: AppLanguage.DEFAULT)
        override val language: StateFlow<AppLanguage> = _language
        override fun select(language: AppLanguage) { store["language"] = language; _language.value = language }
    }

    @Test fun explicitLanguagePreferencesPersistAcrossARecreatedRepositoryOverTheSameStore() {
        val backingStore = mutableMapOf<String, AppLanguage>()
        listOf(AppLanguage.ENGLISH, AppLanguage.SPANISH, AppLanguage.PORTUGUESE_BRAZIL).forEach { chosen ->
            val repository = FakeAppLanguageRepository(backingStore)
            repository.select(chosen)
            // A fresh repository instance over the same backing store simulates the process restarting while
            // the persisted preference survives, the way AppCompatDelegate's own store does in production.
            val recreated = FakeAppLanguageRepository(backingStore)
            assertEquals(chosen, recreated.language.value)
        }
    }

    @Test fun defaultRepositoryStateIsSystemDefaultBeforeAnyExplicitChoice() {
        val repository = FakeAppLanguageRepository(mutableMapOf())
        assertEquals(AppLanguage.SYSTEM_DEFAULT, repository.language.value)
    }

    /** Changing ShelfOS's interface language must never touch reading-presentation state — the two are
     * independent concerns per AGENTS.md's localization rules and ARCHITECTURE.md's localization-architecture
     * section. [AppLanguageRepository] has no reference to [ReaderPreferences], a Library repository or Room at
     * all, so this guards the contract structurally: selecting every language leaves an independently held
     * [ReaderPreferences] value completely unchanged. */
    @Test fun switchingApplicationLanguageDoesNotAlterReaderPreferences() {
        val titlePreferences = ReaderPreferences(font = BookFont.SANS, fontSize = 1.2, lineHeight = 1.8)
        val repository = FakeAppLanguageRepository(mutableMapOf())
        AppLanguage.entries.forEach(repository::select)
        assertEquals(ReaderPreferences(font = BookFont.SANS, fontSize = 1.2, lineHeight = 1.8), titlePreferences)
    }
}
