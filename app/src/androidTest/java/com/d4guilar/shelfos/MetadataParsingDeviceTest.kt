// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.FilenameEvidence
import com.d4guilar.shelfos.domain.metadata.FilenameInference
import com.d4guilar.shelfos.domain.metadata.normalizeSequence
import com.d4guilar.shelfos.domain.metadata.normalizeText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 4.5B on Android's ICU regex engine, which differs from the JVM's (it rejects Java's `(?U)` flag, a crash the JVM
 * suite cannot see). A representative slice of the JVM corpus must give the same answers on device.
 */
class MetadataParsingDeviceTest {
    @Test fun filenameInferenceAndNormalizationMatchTheJvmCorpusOnDevice() {
        assertEquals(FilenameEvidence("Saga", 2012, "Saga", issue = "1"), FilenameInference.infer("Saga #001 (2012).cbz", MediaCategory.COMIC))
        assertEquals(FilenameEvidence("ワンピース", series = "ワンピース", volume = "3"), FilenameInference.infer("ワンピース ３巻.cbz", MediaCategory.MANGA))
        assertEquals(FilenameEvidence("One Piece", series = "One Piece", volume = "3"), FilenameInference.infer("One Piece Vol. 3.cbz", MediaCategory.MANGA))
        assertEquals(FilenameEvidence("Watchmen", series = "Watchmen", issue = "3"), FilenameInference.infer("Watchmen 03 (of 12).cbz", MediaCategory.COMIC))
        assertEquals(FilenameEvidence("Catch-22"), FilenameInference.infer("Catch-22.epub", MediaCategory.BOOK))
        assertEquals(FilenameEvidence("Report 2023"), FilenameInference.infer("Report 2023.pdf", MediaCategory.DOCUMENT))
        // 4.5D.0 Manga ranges, totals, chapters, Spanish volumes and years on the ICU engine.
        assertEquals(FilenameEvidence("Berserk", series = "Berserk", collection = true), FilenameInference.infer("Berserk Vols. 1-3.cbz", MediaCategory.MANGA))
        assertEquals(FilenameEvidence("Berserk", series = "Berserk", collection = true), FilenameInference.infer("Berserk Tomos 1 a 3.cbz", MediaCategory.MANGA))
        assertEquals(FilenameEvidence("ワンピース", series = "ワンピース", collection = true), FilenameInference.infer("ワンピース 全12巻.cbz", MediaCategory.MANGA))
        assertEquals(FilenameEvidence("ワンピース", series = "ワンピース", collection = true), FilenameInference.infer("ワンピース 1~3巻.cbz", MediaCategory.MANGA))
        assertEquals(FilenameEvidence("Berserk"), FilenameInference.infer("Berserk Ch. 099.cbz", MediaCategory.MANGA))
        assertEquals(FilenameEvidence("Dragon Ball", series = "Dragon Ball", volume = "5"), FilenameInference.infer("Dragon Ball Volumen 5.cbz", MediaCategory.MANGA))
        assertEquals(FilenameEvidence("Berserk Vol. 2019"), FilenameInference.infer("Berserk Vol. 2019.cbz", MediaCategory.MANGA))
        assertEquals("one piece", normalizeText("ＯＮＥ　ＰＩＥＣＥ!"))
        assertEquals("12", normalizeSequence("#０１２"))
        assertEquals("9780306406157", com.d4guilar.shelfos.domain.metadata.IdentifierDetector.isbn("ISBN 978‐0‐306‐40615‐7")?.value)
        assertEquals("036000291452-00111", com.d4guilar.shelfos.domain.metadata.IdentifierDetector.gtin("0 36000 29145 2 00111")?.value)
        assertEquals("2012-03", com.d4guilar.shelfos.domain.metadata.normalizedDate("2012-03"))
        assertEquals("pt-BR", com.d4guilar.shelfos.domain.metadata.normalizedLanguage("pt_BR"))
    }
}
