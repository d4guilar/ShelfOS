// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 3F physical-ARM regression. On the Retroid Pocket 5 (arm64, Android 13) every valid CBR, including the
 * vendored RAR4/RAR5 fixtures, failed to open with NATIVE_INTERNAL because the native session pointer carried an
 * arm64 heap tag in its top byte and was therefore negative, and `nativeOpen`'s result was decoded as
 * "positive == handle". The emulator (x86_64, untagged) never showed this.
 */
class NativeOpenHandleDecodingTest {
    @Test fun taggedNegativeAndPlainPositivePointersAreSessionHandles() {
        // Typical Scudo-tagged arm64 heap pointer (tag 0xB4) and an untagged x86_64-style pointer.
        assertNull(nativeOpenFailure(0xB400_007A_1234_5678uL.toLong()))
        assertNull(nativeOpenFailure(0x0000_7A12_3456_7890L))
        assertNull(nativeOpenFailure(-NATIVE_OPEN_MAX_ENCODED_ERROR - 1))
        assertNull(nativeOpenFailure(Long.MIN_VALUE))
    }

    @Test fun onlyTheSmallNegativeRangeIsAFailureAndMapsToTheNativeErrorOrdinal() {
        assertEquals(NativeRarError.INVALID_ARGUMENT, nativeOpenFailure(-1))
        assertEquals(NativeRarError.IO, nativeOpenFailure(-2))
        assertEquals(NativeRarError.NOT_SEEKABLE, nativeOpenFailure(-3))
        assertEquals(NativeRarError.CORRUPT, nativeOpenFailure(-4))
        assertEquals(NativeRarError.PROTECTED, nativeOpenFailure(-5))
        assertEquals(NativeRarError.UNSUPPORTED, nativeOpenFailure(-6))
        assertEquals(NativeRarError.NATIVE_INTERNAL, nativeOpenFailure(-7))
        assertEquals(NativeRarError.TOO_LARGE, nativeOpenFailure(-8))
        assertEquals(NativeRarError.NATIVE_INTERNAL, nativeOpenFailure(-NATIVE_OPEN_MAX_ENCODED_ERROR))
        assertEquals(NativeRarError.NATIVE_INTERNAL, nativeOpenFailure(0))
    }
}
