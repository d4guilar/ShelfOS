// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import androidx.annotation.StringRes
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.domain.library.PublicationExceptionDetail
import com.d4guilar.shelfos.domain.library.PublicationProblem
import com.d4guilar.shelfos.domain.library.publicationExceptionDetail
import com.d4guilar.shelfos.domain.library.publicationProblem

/** [PublicationProblem]/[PublicationExceptionDetail] are pure domain identifiers with no text of their own
 * (see their own doc comments); this is the UI-layer mapping from each case to a localized string resource,
 * following the same boundary as feature.library's CategoryLabels. */
@StringRes
fun PublicationProblem.messageRes(): Int = when (this) {
    PublicationProblem.PERMISSION_LOST -> R.string.problem_permission_lost_message
    PublicationProblem.SOURCE_UNAVAILABLE -> R.string.problem_source_unavailable_message
    PublicationProblem.NEEDS_COPY -> R.string.problem_needs_copy_message
    PublicationProblem.UNSUPPORTED_FORMAT -> R.string.problem_unsupported_format_message
    PublicationProblem.UNSUPPORTED_LAYOUT -> R.string.problem_unsupported_layout_message
    PublicationProblem.PROTECTED -> R.string.problem_protected_message
    PublicationProblem.CORRUPT -> R.string.problem_corrupt_message
    PublicationProblem.EMPTY_ARCHIVE -> R.string.problem_empty_archive_message
    PublicationProblem.TOO_LARGE -> R.string.problem_too_large_message
    PublicationProblem.INSUFFICIENT_STORAGE -> R.string.problem_insufficient_storage_message
    PublicationProblem.COPY_FAILED -> R.string.problem_copy_failed_message
    PublicationProblem.UNREADABLE -> R.string.problem_unreadable_message
}

@StringRes
fun PublicationProblem.importMessageRes(): Int = when (this) {
    PublicationProblem.PERMISSION_LOST -> R.string.problem_permission_lost_import_message
    PublicationProblem.SOURCE_UNAVAILABLE -> R.string.problem_source_unavailable_import_message
    PublicationProblem.UNREADABLE -> R.string.problem_unreadable_import_message
    else -> messageRes()
}

@StringRes
fun PublicationExceptionDetail.messageRes(): Int = when (this) {
    PublicationExceptionDetail.UNSAFE_ENTRY_PATH -> R.string.publication_error_unsafe_entry_path
    PublicationExceptionDetail.UNSUPPORTED_ENTRY_SIZE -> R.string.publication_error_unsupported_entry_size
    PublicationExceptionDetail.EXPANDS_BEYOND_SAFE_LIMITS -> R.string.publication_error_expands_beyond_safe_limits
    PublicationExceptionDetail.IMAGE_PAGE_TOO_LARGE -> R.string.publication_error_image_page_too_large
    PublicationExceptionDetail.PDF_HAS_NO_PAGES -> R.string.publication_error_pdf_has_no_pages
    PublicationExceptionDetail.UNSUPPORTED_COMPRESSION_METHOD -> R.string.publication_error_unsupported_compression_method
    PublicationExceptionDetail.TOO_MANY_ENTRIES -> R.string.publication_error_too_many_entries
    PublicationExceptionDetail.ARCHIVE_DAMAGED_OR_INCOMPLETE -> R.string.publication_error_archive_damaged_or_incomplete
    PublicationExceptionDetail.EPUB_CONTENT_TOO_LARGE -> R.string.publication_error_epub_content_too_large
    PublicationExceptionDetail.EPUB_UNSUPPORTED_ENTITY_DECLARATIONS -> R.string.publication_error_epub_unsupported_entity_declarations
    PublicationExceptionDetail.EPUB_INVALID_OR_UNSUPPORTED -> R.string.publication_error_epub_invalid_or_unsupported
    PublicationExceptionDetail.USE_EPUB_READER_INSTEAD -> R.string.publication_error_use_epub_reader_instead
    PublicationExceptionDetail.PAGE_IMAGE_DAMAGED_OR_TOO_LARGE -> R.string.publication_error_page_image_damaged_or_too_large
    PublicationExceptionDetail.PAGE_IMAGE_DECODE_FAILED -> R.string.publication_error_page_image_decode_failed
    PublicationExceptionDetail.EPUB_MISSING_CONTAINER -> R.string.publication_error_epub_missing_container
    PublicationExceptionDetail.PUBLICATION_HAS_NO_READABLE_PAGES -> R.string.publication_error_publication_has_no_readable_pages
}

/** The specific detail a failure carries, when present, otherwise the problem's own localized reader message. */
fun Throwable.readerMessage(): UiMessage =
    UiMessage.Resource(publicationExceptionDetail()?.messageRes() ?: publicationProblem().messageRes())

/** Same as [readerMessage], but falls back to the problem's import-flow wording rather than its reader wording. */
fun Throwable.importMessage(): UiMessage =
    UiMessage.Resource(publicationExceptionDetail()?.messageRes() ?: publicationProblem().importMessageRes())
