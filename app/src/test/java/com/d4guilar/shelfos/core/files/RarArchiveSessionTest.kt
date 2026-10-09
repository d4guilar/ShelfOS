// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import com.d4guilar.shelfos.domain.library.PublicationProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RarArchiveSessionTest {
    @Test
    fun nativeOpenFailuresPreserveProblemMappingAndTypedCause() {
        val cases = mapOf(
            NativeRarError.PROTECTED to PublicationProblem.PROTECTED,
            NativeRarError.CORRUPT to PublicationProblem.CORRUPT,
            NativeRarError.UNSUPPORTED to PublicationProblem.UNSUPPORTED_FORMAT,
            NativeRarError.TOO_LARGE to PublicationProblem.TOO_LARGE,
            NativeRarError.NOT_SEEKABLE to PublicationProblem.NEEDS_COPY,
            NativeRarError.IO to PublicationProblem.UNREADABLE,
            NativeRarError.NATIVE_INTERNAL to PublicationProblem.UNREADABLE,
            NativeRarError.INVALID_ARGUMENT to PublicationProblem.CORRUPT,
        )

        for ((nativeError, expectedProblem) in cases) {
            val exception = NativeRarResult.Failure(nativeError).toRarOpenPublicationException()
            assertEquals(expectedProblem, exception.problem)
            assertTrue(exception.cause is RarOpenException)
            assertEquals(nativeError, (exception.cause as RarOpenException).error)
        }
    }
}
