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

    // ---- 4.5D.0 Manga hardening (matrix I) ----

    private fun infer(name: String, category: MediaCategory = MANGA) = FilenameInference.infer(name, category)

    @Test fun mangaVolumeWordsAcrossLanguagesAndNumberForms() {
        check("Dragon Ball Volumen 5.cbz", MANGA, "Dragon Ball", series = "Dragon Ball", volume = "5")
        check("Dragon Ball Vol.5.cbz", MANGA, "Dragon Ball", series = "Dragon Ball", volume = "5")
        check("Naruto Tome 07.cbz", MANGA, "Naruto", series = "Naruto", volume = "7")
        check("Akira Band 02.cbz", MANGA, "Akira", series = "Akira", volume = "2")
        check("Gantz Vol. 1.5.cbz", MANGA, "Gantz", series = "Gantz", volume = "1.5")
        check("ワンピース 第３巻.cbz", MANGA, "ワンピース", series = "ワンピース", volume = "3")
        check("20th Century Boys Vol. 2 (2001).cbz", MANGA, "20th Century Boys", series = "20th Century Boys", volume = "2", year = 2001)
        check("Dr. Stone Vol. 3 - 2019.cbz", MANGA, "Dr. Stone - 2019", series = "Dr. Stone - 2019", volume = "3")
    }

    @Test fun rangesTotalsAndOmnibusWordsAreCollectionsNotVolumes() {
        for (name in listOf("Berserk Vols. 1-3.cbz", "Berserk Vol. 1–3.cbz", "Berserk Tomos 1 a 3.cbz", "Berserk Volumes 1 to 3.cbz"))
            assertEquals(name, FilenameEvidence("Berserk", series = "Berserk", collection = true), infer(name))
        assertEquals(FilenameEvidence("ワンピース", series = "ワンピース", collection = true), infer("ワンピース 1~3巻.cbz"))
        assertEquals("全12巻 is a total, not volume 12", FilenameEvidence("ワンピース", series = "ワンピース", collection = true), infer("ワンピース 全12巻.cbz"))
        assertEquals(FilenameEvidence("Berserk", series = "Berserk", collection = true), infer("Berserk Omnibus.cbz"))
        assertEquals(FilenameEvidence("Berserk", series = "Berserk", volume = "2", collection = true), infer("Berserk Omnibus Vol. 2.cbz"))
        assertEquals(FilenameEvidence("Naruto", series = "Naruto", collection = true), infer("Naruto 3-in-1.cbz"))
        assertEquals(FilenameEvidence("Naruto", series = "Naruto", collection = true), infer("Naruto Box Set.cbz"))
    }

    @Test fun yearsChaptersAndNonVolumeLabelsAreNeverVolumes() {
        assertEquals(FilenameEvidence("Berserk Vol. 2019"), infer("Berserk Vol. 2019.cbz"))
        assertEquals(FilenameEvidence("Berserk 2019"), infer("Berserk 2019.cbz"))
        assertEquals("a chapter number is not a volume", FilenameEvidence("Berserk"), infer("Berserk Ch. 099.cbz"))
        assertEquals(FilenameEvidence("Berserk"), infer("Berserk Chapter 12.cbz"))
        assertEquals(FilenameEvidence("ベルセルク"), infer("ベルセルク 第99話.cbz"))
        assertEquals(FilenameEvidence("Berserk", series = "Berserk", volume = "3"), infer("Berserk Chapter 12 Vol. 3.cbz"))
        for (title in listOf("Steins;Gate 0", "Fahrenheit 451", "Book 2", "Part 3", "Season 2", "Vol. abc", "Vol. 1x", "Vol. 1..5", "100 Girlfriends"))
            assertEquals(title, FilenameEvidence(title), infer("$title.cbz"))
        assertEquals("a Book keeps its title", FilenameEvidence("Berserk Vols. 1-3"), infer("Berserk Vols. 1-3.epub", BOOK).let { FilenameEvidence(it.title) })
        assertEquals("Documents stay conservative", FilenameEvidence("Berserk Vol. 1-3"), infer("Berserk Vol. 1-3.pdf", DOCUMENT))
    }

    @Test fun aTitleContainingBoxIsNotACollection() {
        assertEquals(FilenameEvidence("Blue Box", series = "Blue Box", volume = "1"), infer("Blue Box Vol. 1.cbz"))
        assertEquals(FilenameEvidence("Naruto", series = "Naruto", collection = true), infer("Naruto Box Set.cbz"))
    }
}
