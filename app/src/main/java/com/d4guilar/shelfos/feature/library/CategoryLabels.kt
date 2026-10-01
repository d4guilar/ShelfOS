// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.annotation.StringRes
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.domain.library.LibraryFilter
import com.d4guilar.shelfos.domain.library.MediaCategory

/** Display text for [MediaCategory]/[LibraryFilter] lives here, not on the domain enums themselves
 * (see AGENTS.md's localization rules), resolved through Android string resources via `stringResource()`. */
@StringRes
fun MediaCategory.labelRes(): Int = when (this) {
    MediaCategory.BOOK -> R.string.category_books
    MediaCategory.COMIC -> R.string.category_comics
    MediaCategory.MANGA -> R.string.category_manga
    MediaCategory.DOCUMENT -> R.string.category_documents
}

@StringRes
fun MediaCategory.singularRes(): Int = when (this) {
    MediaCategory.BOOK -> R.string.category_book_singular
    MediaCategory.COMIC -> R.string.category_comic_singular
    MediaCategory.MANGA -> R.string.category_manga_singular
    MediaCategory.DOCUMENT -> R.string.category_document_singular
}

@StringRes
fun LibraryFilter.labelRes(): Int = when (this) {
    LibraryFilter.FAVORITES -> R.string.filter_favorites
    LibraryFilter.BOOKS -> R.string.category_books
    LibraryFilter.COMICS -> R.string.category_comics
    LibraryFilter.MANGA -> R.string.category_manga
    LibraryFilter.DOCUMENTS -> R.string.category_documents
}
