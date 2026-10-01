// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import com.d4guilar.shelfos.AppContainer
import com.d4guilar.shelfos.core.reader.EpubManagedFontResource
import com.d4guilar.shelfos.core.reader.EpubReaderFactory
import java.io.File

/** Debug-only host for the runtime managed-font instrumentation proof. */
class EpubManagedFontProofActivity : EpubActivity() {
    override fun createEpubReaderFactory(container: AppContainer): EpubReaderFactory =
        EpubReaderFactory(this, container.files) {
            listOf(EpubManagedFontResource(PROOF_FONT_ID, "Managed proof", File(filesDir, PROOF_FONT_PATH)))
        }

    companion object {
        const val PROOF_FONT_ID = "test:managed-proof"
        const val PROOF_FONT_PATH = "fonts/proof/regular.ttf"
    }
}
