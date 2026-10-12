// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.annotations

import org.junit.Assert.*
import org.junit.Test

class LikePatternTest {
    @Test fun blankTextMeansNoFilter() { assertNull(likePattern("")); assertNull(likePattern("   \n")) }
    @Test fun plainTextIsWrappedAndTrimmed() = assertEquals("%quick fox%", likePattern("  quick fox "))
    @Test fun likeMetacharactersAreEscapedSoTheyMatchLiterally() {
        assertEquals("%100\\%%", likePattern("100%"))
        assertEquals("%a\\_b%", likePattern("a_b"))
        assertEquals("%back\\\\slash%", likePattern("back\\slash"))
        assertEquals("%\\%\\_\\\\%", likePattern("%_\\"))
    }
    @Test fun unicodeAndQuotesPassThroughUnchanged() = assertEquals("%é中 \"it's\"%", likePattern("é中 \"it's\""))
}
