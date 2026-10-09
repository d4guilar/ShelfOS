// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import com.d4guilar.shelfos.core.files.LockResolution
import com.d4guilar.shelfos.core.files.PlatformLockLookup
import com.d4guilar.shelfos.core.files.resolveLegacyPdfiumLock
import com.d4guilar.shelfos.core.files.usesLegacyPdfiumProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** API 25 must use the real framework lock or fail closed; only API 24 may use a private monitor. */
class LegacyPdfiumLockResolutionTest {
    private val failure = LockResolution.SetupFailure

    @Test fun api24WithoutFrameworkLockUsesPrivateMonitor() {
        val r = resolveLegacyPdfiumLock(24, PlatformLockLookup.Absent) as LockResolution.Monitor
        assertFalse(r.isFrameworkLock)
    }

    @Test fun api24WithFrameworkLockUsesIt() {
        val lock = Any()
        val r = resolveLegacyPdfiumLock(24, PlatformLockLookup.Found(lock)) as LockResolution.Monitor
        assertSame(lock, r.monitor)
    }

    @Test fun api25UsesTheFrameworkLockObject() {
        val lock = Any()
        val r = resolveLegacyPdfiumLock(25, PlatformLockLookup.Found(lock)) as LockResolution.Monitor
        assertTrue(r.isFrameworkLock)
        assertSame(lock, r.monitor)
    }

    @Test fun api25MissingLockFailsClosed() {
        assertTrue(resolveLegacyPdfiumLock(25, PlatformLockLookup.Absent) == failure)
    }

    @Test fun api25InaccessibleLockFailsClosed() {
        assertTrue(resolveLegacyPdfiumLock(25, PlatformLockLookup.Inaccessible) == failure)
    }

    @Test fun api25NullLockFailsClosed() {
        assertTrue(resolveLegacyPdfiumLock(25, PlatformLockLookup.Found(null)) == failure)
    }

    @Test fun api25UnusableLockTypeFailsClosed() {
        assertTrue(resolveLegacyPdfiumLock(25, PlatformLockLookup.Found(42)) == failure)
        assertTrue(resolveLegacyPdfiumLock(25, PlatformLockLookup.Found("lock")) == failure)
    }

    @Test fun api24InaccessibleOrNullDoesNotSilentlyFallBack() {
        assertTrue(resolveLegacyPdfiumLock(24, PlatformLockLookup.Inaccessible) == failure)
        assertTrue(resolveLegacyPdfiumLock(24, PlatformLockLookup.Found(null)) == failure)
    }

    @Test fun api26AndNewerDoNotUseTheLegacyHelper() {
        assertFalse(usesLegacyPdfiumProbe(26))
        assertFalse(usesLegacyPdfiumProbe(33))
    }
}
