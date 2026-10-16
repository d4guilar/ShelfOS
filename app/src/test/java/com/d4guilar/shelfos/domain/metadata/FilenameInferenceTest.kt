// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.metadata

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.MediaCategory.*
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 4.5B multilingual file-name corpus: explicit tokens only, ambiguity left alone, titles never rewritten. */
class FilenameInferenceTest {
    private fun check(name: String, category: MediaCategory, title: String, series: String? = null, volume: String? = null,
        issue: String? = null, year: Int? = null) =
        assertEquals(name, FilenameEvidence(title, year, series, volume, issue), FilenameInference.infer(name, category))

    @Test fun comicIssueConventions() {
        check("Saga #001 (2012).cbz", COMIC, "Saga", series = "Saga", issue = "1", year = 2012)
        check("Batman (2016) #12.cbr", COMIC, "Batman", series = "Batman", issue = "12", year = 2016)
        check("Batman 001 (2016) (Digital).cbz", COMIC, "Batman", series = "Batman", issue = "1", year = 2016)
        check("Hellboy Issue 3.cbz", COMIC, "Hellboy", series = "Hellboy", issue = "3")
        check("Spawn No. 7.cbz", COMIC, "Spawn", series = "Spawn", issue = "7")
        check("The Walking Dead #0.cbz", COMIC, "The Walking Dead", series = "The Walking Dead", issue = "0")
        check("Daredevil #1.5.cbz", COMIC, "Daredevil", series = "Daredevil", issue = "1.5")
        check("Watchmen 03 (of 12).cbz", COMIC, "Watchmen", series = "Watchmen", issue = "3")
        check("Saga_v01.cbz", COMIC, "Saga", series = "Saga", volume = "1")
    }

    @Test fun mangaVolumeConventionsAcrossLanguages() {
        check("One Piece v03.cbz", MANGA, "One Piece", series = "One Piece", volume = "3")
        check("One Piece Vol. 3.cbz", MANGA, "One Piece", series = "One Piece", volume = "3")
        check("Berserk Volume 10.cbz", MANGA, "Berserk", series = "Berserk", volume = "10")
        check("ワンピース 第3巻.cbz", MANGA, "ワンピース", series = "ワンピース", volume = "3")
        check("ワンピース ３巻.cbz", MANGA, "ワンピース", series = "ワンピース", volume = "3")
        check("Naruto Tome 12.cbz", MANGA, "Naruto", series = "Naruto", volume = "12")
        check("Dragon Ball Tomo 5.cbz", MANGA, "Dragon Ball", series = "Dragon Ball", volume = "5")
        check("Akira Band 2.cbz", MANGA, "Akira", series = "Akira", volume = "2")
        check("[Group] Yotsuba&! 007.cbz", MANGA, "Yotsuba&!", series = "Yotsuba&!", volume = "7")
        check("진격의 거인 v01.cbz", MANGA, "진격의 거인", series = "진격의 거인", volume = "1")
    }

    @Test fun misleadingNumbersAreNotVolumesOrIssues() {
        check("Catch-22.epub", BOOK, "Catch-22")
        check("1984.epub", BOOK, "1984")
        check("Fahrenheit 451.epub", BOOK, "Fahrenheit 451")
        check("2001 A Space Odyssey (1968).epub", BOOK, "2001 A Space Odyssey", year = 1968)
        check("Ocean's 11.cbz", COMIC, "Ocean's 11")
        check("Report 2023.pdf", DOCUMENT, "Report 2023")
        check("Invoice #12 (2024).pdf", DOCUMENT, "Invoice #12")
        check("Les Misérables Tome 2.epub", BOOK, "Les Misérables", volume = "2")
    }

    /** L-1: comic/manga conventions never rewrite a Book title; each category keeps its own explicit tokens. */
    @Test fun bookTitlesAreNotParsedWithComicOrMangaConventions() {
        check("The No. 1 Ladies' Detective Agency.epub", BOOK, "The No. 1 Ladies' Detective Agency")
        check("Python v3.epub", BOOK, "Python v3")
        check("Catch #22 Notes.epub", BOOK, "Catch #22 Notes")
        check("The Lord of the Rings Volume 2.epub", BOOK, "The Lord of the Rings", volume = "2")
        check("Spawn No. 7.cbz", COMIC, "Spawn", series = "Spawn", issue = "7")
        check("Python v3.cbz", COMIC, "Python", series = "Python", volume = "3")
        check("Blame! v03.cbz", MANGA, "Blame!", series = "Blame!", volume = "3")
        check("Blame! #12.cbz", MANGA, "Blame! #12")
        check("Annual Report No. 3.pdf", DOCUMENT, "Annual Report No. 3")
    }
}
