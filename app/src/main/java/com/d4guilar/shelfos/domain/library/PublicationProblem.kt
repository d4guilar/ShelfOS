// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

/**
 * Distinct, explainable reasons a publication cannot be imported or opened. [unavailable] problems concern
 * access to the source rather than its content. This is identity only, never text: UI-layer code maps each
 * case to a localized string (see core.designsystem's PublicationErrorMessages) so ShelfOS's interface
 * language controls how these surface, without the domain layer depending on Android resources at all — see
 * AGENTS.md's localization rules.
 */
enum class PublicationProblem(val unavailable: Boolean = false) {
    // Providers may revoke access when a document is deleted, so the message does not over-claim a cause.
    PERMISSION_LOST(unavailable = true),
    SOURCE_UNAVAILABLE(unavailable = true),
    NEEDS_COPY,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_LAYOUT,
    PROTECTED,
    CORRUPT,
    EMPTY_ARCHIVE,
    TOO_LARGE,
    INSUFFICIENT_STORAGE,
    COPY_FAILED,
    UNREADABLE,
}

/**
 * A more specific reason than [PublicationProblem] alone can express, raised by pure file/archive/EPUB-parsing
 * code that has no Android Context to resolve a localized string with. Like [PublicationProblem], this is a
 * locale-neutral identifier, not text; UI-layer code maps each case to a localized string. [name] still serves
 * as [PublicationException]'s own technical `.message` for logs and stack traces, never shown to the user
 * directly — see [PublicationException].
 */
enum class PublicationExceptionDetail {
    UNSAFE_ENTRY_PATH, UNSUPPORTED_ENTRY_SIZE, EXPANDS_BEYOND_SAFE_LIMITS, IMAGE_PAGE_TOO_LARGE, PDF_HAS_NO_PAGES,
    UNSUPPORTED_COMPRESSION_METHOD, TOO_MANY_ENTRIES, ARCHIVE_DAMAGED_OR_INCOMPLETE, EPUB_CONTENT_TOO_LARGE,
    EPUB_UNSUPPORTED_ENTITY_DECLARATIONS, EPUB_INVALID_OR_UNSUPPORTED, USE_EPUB_READER_INSTEAD,
    PAGE_IMAGE_DAMAGED_OR_TOO_LARGE, PAGE_IMAGE_DECODE_FAILED, EPUB_MISSING_CONTAINER, PUBLICATION_HAS_NO_READABLE_PAGES,
}

/** [detail], when present, is a more specific reason than [problem] alone — see [PublicationExceptionDetail]. */
class PublicationException(val problem: PublicationProblem, val detail: PublicationExceptionDetail? = null) : IOException(detail?.name)

/** Maps platform failures onto the ShelfOS error model without collapsing them into one generic message. */
fun Throwable.publicationProblem(): PublicationProblem = when (this) {
    is PublicationException -> problem
    is SecurityException -> PublicationProblem.PERMISSION_LOST
    is FileNotFoundException -> PublicationProblem.SOURCE_UNAVAILABLE
    is ZipException -> PublicationProblem.CORRUPT
    else -> PublicationProblem.UNREADABLE
}

/** The specific detail a [PublicationException] carries, when present, otherwise null. Pure and localization-
 * free, like the rest of this file; UI-layer code resolves either this or [publicationProblem] to a message. */
fun Throwable.publicationExceptionDetail(): PublicationExceptionDetail? = (this as? PublicationException)?.detail
