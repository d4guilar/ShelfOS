// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.core.os.LocaleListCompat
import com.d4guilar.shelfos.core.designsystem.messageRes
import com.d4guilar.shelfos.core.designsystem.importMessageRes
import com.d4guilar.shelfos.core.designsystem.toUiMessage
import com.d4guilar.shelfos.core.localization.AppLanguage
import com.d4guilar.shelfos.core.reader.FontImportFailureReason
import com.d4guilar.shelfos.data.preferences.toAppLanguage
import com.d4guilar.shelfos.data.preferences.toLocaleList
import com.d4guilar.shelfos.domain.importing.ImportProgressStage
import com.d4guilar.shelfos.domain.library.PublicationExceptionDetail
import com.d4guilar.shelfos.domain.library.PublicationProblem
import org.junit.Assert.*
import org.junit.Test

/**
 * QA remediation (test quality): exercises the REAL production mapping functions directly — not fakes — for
 * every locale-neutral identifier this localization foundation introduced. [AppLanguage.toLocaleList]/
 * [LocaleListCompat.toAppLanguage] are `internal` (module-visible, same Gradle module as this test) rather than
 * exercised only through `AppLanguageRepository`'s fake in LocalizationPolicyTest, which only proves the
 * interface contract, not this actual conversion logic.
 */
class LocalizationMessageMappingTest {
    @Test fun appLanguageToLocaleListProductionMapping() {
        assertTrue(AppLanguage.SYSTEM_DEFAULT.toLocaleList().isEmpty)
        assertEquals("en", AppLanguage.ENGLISH.toLocaleList().toLanguageTags())
        assertEquals("es", AppLanguage.SPANISH.toLocaleList().toLanguageTags())
        assertEquals("pt-BR", AppLanguage.PORTUGUESE_BRAZIL.toLocaleList().toLanguageTags())
    }

    @Test fun localeListToAppLanguageProductionMapping() {
        assertEquals(AppLanguage.SYSTEM_DEFAULT, LocaleListCompat.getEmptyLocaleList().toAppLanguage())
        assertEquals(AppLanguage.ENGLISH, LocaleListCompat.forLanguageTags("en").toAppLanguage())
        assertEquals(AppLanguage.SPANISH, LocaleListCompat.forLanguageTags("es").toAppLanguage())
        assertEquals(AppLanguage.PORTUGUESE_BRAZIL, LocaleListCompat.forLanguageTags("pt-BR").toAppLanguage())
        // A locale ShelfOS's picker never offers resolves to SYSTEM_DEFAULT rather than crashing or pinning.
        assertEquals(AppLanguage.SYSTEM_DEFAULT, LocaleListCompat.forLanguageTags("fr").toAppLanguage())
    }

    @Test fun appLanguageAndLocaleListRoundTripForEverySupportedLanguage() {
        AppLanguage.entries.forEach { language ->
            assertEquals(language, language.toLocaleList().toAppLanguage())
        }
    }

    @Test fun importProgressStageMapsToTheCorrectResourceIdentity() {
        assertEquals(UiMessageResource(R.string.import_stage_inspecting), ImportProgressStage.Inspecting.toUiMessage())
        assertEquals(UiMessageResource(R.string.import_stage_copying_offline), ImportProgressStage.CopyingOffline.toUiMessage())
        assertEquals(UiMessageResource(R.string.import_stage_copying_percent, listOf(42)),
            ImportProgressStage.CopyingPercent(42).toUiMessage())
        assertEquals(UiMessageResource(R.string.import_stage_copying_megabytes, listOf(7L)),
            ImportProgressStage.CopyingMegabytes(7L).toUiMessage())
    }

    @Test fun publicationProblemMapsToDistinctResourceIdentitiesForEveryCase() {
        assertEquals(PublicationProblem.entries.size, PublicationProblem.entries.map { it.messageRes() }.toSet().size)
        assertEquals(R.string.problem_corrupt_message, PublicationProblem.CORRUPT.messageRes())
        assertEquals(R.string.problem_permission_lost_message, PublicationProblem.PERMISSION_LOST.messageRes())
        // The import-flow wording differs only where the production list explicitly gives it its own string;
        // every other problem's import message falls back to its (shared) reader message.
        assertEquals(R.string.problem_permission_lost_import_message, PublicationProblem.PERMISSION_LOST.importMessageRes())
        assertEquals(R.string.problem_source_unavailable_import_message, PublicationProblem.SOURCE_UNAVAILABLE.importMessageRes())
        assertEquals(R.string.problem_unreadable_import_message, PublicationProblem.UNREADABLE.importMessageRes())
        assertEquals(PublicationProblem.CORRUPT.messageRes(), PublicationProblem.CORRUPT.importMessageRes())
    }

    @Test fun publicationExceptionDetailMapsToDistinctResourceIdentitiesForEveryCase() {
        assertEquals(PublicationExceptionDetail.entries.size, PublicationExceptionDetail.entries.map { it.messageRes() }.toSet().size)
        assertEquals(R.string.publication_error_epub_missing_container, PublicationExceptionDetail.EPUB_MISSING_CONTAINER.messageRes())
        assertEquals(R.string.publication_error_use_epub_reader_instead, PublicationExceptionDetail.USE_EPUB_READER_INSTEAD.messageRes())
    }

    @Test fun fontImportFailureReasonMapsToDistinctResourceIdentitiesForEveryCase() {
        assertEquals(FontImportFailureReason.entries.size, FontImportFailureReason.entries.map { it.messageRes() }.toSet().size)
        assertEquals(R.string.font_error_unsupported_extension, FontImportFailureReason.UNSUPPORTED_EXTENSION.messageRes())
        assertEquals(R.string.font_error_truncated_or_damaged, FontImportFailureReason.TRUNCATED_OR_DAMAGED.messageRes())
    }
}

/** `UiMessage.Resource` itself is a data class in core.designsystem; this local alias just keeps call sites
 * above terse without a wildcard import colliding with this file's other `R`-prefixed usages. */
private fun UiMessageResource(id: Int, args: List<Any> = emptyList()) = com.d4guilar.shelfos.core.designsystem.UiMessage.Resource(id, args)
