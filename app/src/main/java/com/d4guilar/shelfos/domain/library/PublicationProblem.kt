// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.library

import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

/**
 * Distinct, explainable reasons a publication cannot be imported or opened.
 * [unavailable] problems concern access to the source rather than its content.
 */
enum class PublicationProblem(val message: String, val importMessage: String = message, val unavailable: Boolean = false) {
    // Providers may revoke access when a document is deleted, so the message does not over-claim a cause.
    PERMISSION_LOST(
        "ShelfOS can no longer open the original file. Access may have been revoked, or the file was moved or deleted. Your reading position and edits are kept.",
        "Access to this file was denied. Choose it again.", unavailable = true,
    ),
    SOURCE_UNAVAILABLE(
        "The original file can't be reached. It may have been moved, deleted or disconnected. Your reading position and edits are kept.",
        "The selected file is unavailable. Check that it still exists and try again.", unavailable = true,
    ),
    NEEDS_COPY("This provider can't offer durable, seekable access. ShelfOS needs a private offline copy."),
    UNSUPPORTED_FORMAT("Choose a PDF, EPUB or CBZ publication."),
    UNSUPPORTED_LAYOUT("Fixed-layout EPUB reading is not supported in this build. PDF and CBZ fixed pages are supported."),
    PROTECTED("This publication is password-protected or uses DRM. ShelfOS can't open protected publications."),
    CORRUPT("This publication appears to be damaged or incomplete."),
    EMPTY_ARCHIVE("The archive contains no supported image pages."),
    TOO_LARGE("This publication exceeds ShelfOS's safe processing limits."),
    INSUFFICIENT_STORAGE("There isn't enough free storage for a private copy. The incomplete copy was removed."),
    COPY_FAILED("The private copy could not be completed. The original file is untouched."),
    UNREADABLE(
        "This publication could not be opened. It may be unavailable or damaged.",
        "This file could not be imported. Check that it is available and not damaged.",
    ),
}

class PublicationException(val problem: PublicationProblem, message: String = problem.message) : IOException(message)

/** Maps platform failures onto the ShelfOS error model without collapsing them into one generic message. */
fun Throwable.publicationProblem(): PublicationProblem = when (this) {
    is PublicationException -> problem
    is SecurityException -> PublicationProblem.PERMISSION_LOST
    is FileNotFoundException -> PublicationProblem.SOURCE_UNAVAILABLE
    is ZipException -> PublicationProblem.CORRUPT
    else -> PublicationProblem.UNREADABLE
}

/** Specific detail when a [PublicationException] carries one, otherwise the problem's reader message. */
fun Throwable.readerMessage(): String = (this as? PublicationException)?.message ?: publicationProblem().message
