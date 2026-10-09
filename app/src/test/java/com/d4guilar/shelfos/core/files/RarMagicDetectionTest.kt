// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3E-D: pure-JVM proof of [isRarMagic] -- the import-time detector [PublicationFiles.inspect] uses to
 * decide whether a source's header bytes are evidence of a RAR/CBR container, independent of filename. A full,
 * exact RAR4 or RAR5 magic prefix is evidence regardless of extension; a `.cbr`-named file whose bytes are NOT
 * RAR must never be accepted; and a short/partial prefix (a truncated download, 1-6 bytes) must never be treated
 * as valid evidence -- that distinction is deliberate (see [isRarMagic]'s doc): a damaged-but-genuinely-RAR
 * archive is a later, 3E-B-level classification concern, never an import-detection one.
 */
class RarMagicDetectionTest {
    private val rar4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val rar5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    @Test fun exactRar4MagicIsRecognizedRegardlessOfTrailingBytes() {
        val head = rar4 + byteArrayOf(0x11, 0x22, 0x33)
        assertTrue(isRarMagic(head, head.size))
    }

    @Test fun exactRar5MagicIsRecognizedAndNeverConfusedWithRar4() {
        val head = rar5 + byteArrayOf(0x11, 0x22)
        assertTrue(isRarMagic(head, head.size))
    }

    @Test fun neutralFilenameEvidenceIsMagicOnlyNeverFilenameDriven() {
        // isRarMagic itself takes no filename at all -- a RAR-magic header is evidence on its own merit, the same
        // way a "neutral"/wrong-extension file with real RAR bytes must still classify as CBR at the
        // PublicationFiles.inspect() layer (filename is never consulted there either).
        val head = rar4 + byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertTrue(isRarMagic(head, head.size))
    }

    @Test fun cbrExtensionWithNonRarBytesIsNeverFalselyAccepted() {
        val pdfLikeHead = "%PDF-1.7".toByteArray(Charsets.ISO_8859_1)
        assertFalse(isRarMagic(pdfLikeHead, pdfLikeHead.size))
        val zipLikeHead = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0, 0, 0)
        assertFalse(isRarMagic(zipLikeHead, zipLikeHead.size))
    }

    @Test fun shortOrPartialPrefixIsNeverValidImportEvidence() {
        for (length in 1..6) {
            val truncated = rar4.copyOf(length)
            assertFalse("a $length-byte RAR4 prefix must not be valid import evidence", isRarMagic(truncated, length))
        }
        for (length in 1..7) {
            val truncated = rar5.copyOf(length)
            assertFalse("a $length-byte RAR5 prefix must not be valid import evidence", isRarMagic(truncated, length))
        }
        // count reported lower than the buffer's own real byte content (e.g. a short read) must honor count, not
        // the buffer's physical size.
        val buffer = rar4 + ByteArray(10)
        assertFalse(isRarMagic(buffer, 3))
    }

    @Test fun emptyOrZeroCountNeverMatches() {
        assertFalse(isRarMagic(ByteArray(0), 0))
        assertFalse(isRarMagic(rar4, 0))
    }
}
