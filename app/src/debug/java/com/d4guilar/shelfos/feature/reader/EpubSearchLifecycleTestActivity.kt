// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

/**
 * Debug-only host for instrumentation that must control the session-bound cursor lifecycle. It is non-exported
 * and fails closed unless a test installs hooks; the normal [EpubActivity] never consults this boundary.
 */
class EpubSearchLifecycleTestActivity : EpubActivity() {
    override fun createSearchCursorOpener(): EpubSearchCursorOpener =
        EpubSearchLifecycleTestBoundary.requireOpener()
}

/** Test-process state used only by [EpubSearchLifecycleTestActivity]. */
object EpubSearchLifecycleTestBoundary {
    @Volatile private var opener: EpubSearchCursorOpener? = null

    fun install(value: EpubSearchCursorOpener) { opener = value }
    fun clear() { opener = null }
    internal fun requireOpener(): EpubSearchCursorOpener =
        checkNotNull(opener) { "Install an EPUB search cursor opener before launching the debug test Activity." }
}
