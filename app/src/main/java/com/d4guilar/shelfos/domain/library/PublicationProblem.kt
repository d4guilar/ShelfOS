// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

import androidx.annotation.StringRes
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.UiMessage
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

/**
 * Distinct, explainable reasons a publication cannot be imported or opened.
 * [unavailable] problems concern access to the source rather than its content.
 * [messageRes]/[importMessageRes] are string resources, not text, so ShelfOS's interface language controls how
 * these surface — see AGENTS.md's localization rules.
 */
enum class PublicationProblem(@StringRes val messageRes: Int, @StringRes val importMessageRes: Int = messageRes, val unavailable: Boolean = false) {
    // Providers may revoke access when a document is deleted, so the message does not over-claim a cause.
    PERMISSION_LOST(R.string.problem_permission_lost_message, R.string.problem_permission_lost_import_message, unavailable = true),
    SOURCE_UNAVAILABLE(R.string.problem_source_unavailable_message, R.string.problem_source_unavailable_import_message, unavailable = true),
    NEEDS_COPY(R.string.problem_needs_copy_message),
    UNSUPPORTED_FORMAT(R.string.problem_unsupported_format_message),
    UNSUPPORTED_LAYOUT(R.string.problem_unsupported_layout_message),
    PROTECTED(R.string.problem_protected_message),
    CORRUPT(R.string.problem_corrupt_message),
    EMPTY_ARCHIVE(R.string.problem_empty_archive_message),
    TOO_LARGE(R.string.problem_too_large_message),
    INSUFFICIENT_STORAGE(R.string.problem_insufficient_storage_message),
    COPY_FAILED(R.string.problem_copy_failed_message),
    UNREADABLE(R.string.problem_unreadable_message, R.string.problem_unreadable_import_message),
}

/**
 * [message] stays a plain, non-localized `IOException` message (for logs and Java interop), optionally a
 * specific technical detail that wins over [problem]'s own localized wording when the user sees it — see
 * [readerMessage]/[importMessage]. Threading Android resource access into the low-level file/archive-parsing
 * code that raises these specific details is out of scope for this localization pass (see the localization
 * foundation handoff's intentional-exceptions list); only [problem]'s own message is guaranteed localized.
 */
class PublicationException(val problem: PublicationProblem, message: String? = null) : IOException(message)

/** Maps platform failures onto the ShelfOS error model without collapsing them into one generic message. */
fun Throwable.publicationProblem(): PublicationProblem = when (this) {
    is PublicationException -> problem
    is SecurityException -> PublicationProblem.PERMISSION_LOST
    is FileNotFoundException -> PublicationProblem.SOURCE_UNAVAILABLE
    is ZipException -> PublicationProblem.CORRUPT
    else -> PublicationProblem.UNREADABLE
}

/** Specific detail when a [PublicationException] carries one (not localized, see [PublicationException]'s doc),
 * otherwise the problem's own localized reader message. */
fun Throwable.readerMessage(): UiMessage = (this as? PublicationException)?.message?.let(UiMessage::Literal)
    ?: UiMessage.Resource(publicationProblem().messageRes)
