// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import androidx.annotation.StringRes
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.reader.FontImportException
import com.d4guilar.shelfos.core.reader.FontImportFailureReason

/** [FontImportFailureReason] is a pure, locale-neutral identifier (see its own doc comment); this is the
 * UI-layer mapping to a localized string, following the same boundary as feature.library's CategoryLabels. */
@StringRes
fun FontImportFailureReason.messageRes(): Int = when (this) {
    FontImportFailureReason.READ_FAILED -> R.string.font_error_read_failed
    FontImportFailureReason.UNSUPPORTED_EXTENSION -> R.string.font_error_unsupported_extension
    FontImportFailureReason.TOO_LARGE -> R.string.font_error_too_large
    FontImportFailureReason.TRUNCATED_OR_DAMAGED -> R.string.font_error_truncated_or_damaged
    FontImportFailureReason.COLLECTION_UNSUPPORTED -> R.string.font_error_collection_unsupported
    FontImportFailureReason.NOT_SFNT -> R.string.font_error_not_sfnt
    FontImportFailureReason.MISSING_TABLES -> R.string.font_error_missing_tables
    FontImportFailureReason.DAMAGED_OR_UNSUPPORTED -> R.string.font_error_damaged_or_unsupported
    FontImportFailureReason.FAMILY_NOT_FOUND -> R.string.font_error_family_not_found
    FontImportFailureReason.FINISH_IMPORT_FAILED -> R.string.font_error_finish_import_failed
    FontImportFailureReason.FINISH_REPLACE_FAILED -> R.string.font_error_finish_replace_failed
}

fun FontImportException.toUiMessage(): UiMessage = UiMessage.Resource(reason.messageRes())
