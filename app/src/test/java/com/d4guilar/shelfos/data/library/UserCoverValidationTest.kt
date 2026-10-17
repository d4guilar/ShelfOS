// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.reader.StoredImage
import com.d4guilar.shelfos.domain.library.CoverRepresentation
import com.d4guilar.shelfos.domain.library.CoverStoragePolicy
import com.d4guilar.shelfos.domain.library.CoverSlot
import com.d4guilar.shelfos.domain.library.ReadyCover
import com.d4guilar.shelfos.domain.library.resolveCovers
import org.junit.Assert.*
import org.junit.Test

/** The persistence-boundary rules for a USER write (4.5F.0) and slot precedence, with no Android or database. */
class UserCoverValidationTest {
    private val name = "user-0123456789abcdef.jpg"
    private fun image(rep: CoverRepresentation = CoverRepresentation.DERIVED, sw: Int = 2000, sh: Int = 3000, w: Int = 800, h: Int = 1200,
        bytes: Long = 1000, ext: String = "jpg", mime: String = "image/jpeg") = StoredImage(mime, ext, rep, sw, sh, w, h, bytes)

    @Test fun aWellFormedWriteIsAccepted() {
        requireValidUser(name, image())
        requireValidUser("user-0123456789abcdef.png", image(CoverRepresentation.ORIGINAL, 600, 900, 600, 900, ext = "png", mime = "image/png"))
    }

    @Test fun badNamesAndBoundsAreRejectedBeforeAnySql() {
        val bad = listOf(
            "local-0123456789abcdef.jpg" to image(), "user-xyz.jpg" to image(), "../user-0123456789abcdef.jpg" to image(), name to image(ext = "png"),
            name to image(mime = "text/html"), name to image(bytes = 0), name to image(bytes = CoverStoragePolicy.MAX_COVER_BYTES + 1),
            name to image(w = CoverStoragePolicy.MAX_EDGE_PX + 1), name to image(w = 0), name to image(CoverRepresentation.ORIGINAL, 2000, 3000, 800, 1200))
        for ((n, i) in bad) assertThrows("$n $i", IllegalArgumentException::class.java) { requireValidUser(n, i) }
    }

    @Test fun userOutranksOnlineOutranksLocalAndOnlyReadyRowsCount() {
        fun row(slot: CoverSlot, file: String) = ReadyCover("a", slot, file, 100, 150)
        val all = resolveCovers(listOf(row(CoverSlot.LOCAL, "l"), row(CoverSlot.ONLINE, "o"), row(CoverSlot.USER, "u")))
        assertEquals("u", all.getValue("a").fileName)
        assertEquals("o", resolveCovers(listOf(row(CoverSlot.LOCAL, "l"), row(CoverSlot.ONLINE, "o"))).getValue("a").fileName)
        assertEquals("l", resolveCovers(listOf(row(CoverSlot.LOCAL, "l"))).getValue("a").fileName)
        assertTrue(resolveCovers(emptyList()).isEmpty())
    }
}
