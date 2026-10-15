// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.designsystem.CoverArtwork
import com.d4guilar.shelfos.core.designsystem.LocalCoverArtwork
import com.d4guilar.shelfos.core.designsystem.PublicationCover
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.domain.library.CoverRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Phase 4.5A.1 shared renderer (the one used by Library grid/list, Continue Reading, details, cover expansion, Search and
 * Notes rows): a READY cover is decoded off the main thread and cropped into the existing 0.68 frame; an item without a
 * cover (or an orphan Notes row) keeps the generated visual and asks for extraction once; a missing file is reported and
 * falls back to the generated visual; a replaced cover (new file name) shows the new artwork.
 */
class CoverRendererUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "cover-ui-${System.nanoTime()}").apply { mkdirs() }
    @After fun cleanUp() { dir.deleteRecursively() }

    private fun jpeg(name: String, color: Int) = File(dir, name).also { file ->
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }
    private fun artworkNodes() = compose.onAllNodesWithTag("cover_artwork", useUnmergedTree = true).fetchSemanticsNodes()

    @Test fun realArtworkGeneratedFallbackMissingFilesAndReplacement() {
        jpeg("local-000000000000000a.jpg", Color.RED); jpeg("local-000000000000000b.jpg", Color.BLUE)
        val map = MutableStateFlow<Map<String, CoverRef>?>(null)
        val requested = mutableListOf<String>(); val missing = mutableListOf<String>()
        val artwork = CoverArtwork(map, { File(dir, it.fileName) }, { synchronized(requested) { requested += it } },
            { synchronized(missing) { missing += it } }, { flowOf(null) })
        compose.setContent {
            ShelfTheme(ThemeId.CLASSIC) {
                CompositionLocalProvider(LocalCoverArtwork provides artwork) {
                    Column {
                        PublicationCover("live", "Live", Modifier.width(120.dp))
                        PublicationCover("orphan", "Removed publication", Modifier.width(48.dp))
                        PublicationCover("lost", "Lost file", Modifier.width(52.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("unknown map: neutral frame, no generated flash, no request yet", artworkNodes().isEmpty() && requested.isEmpty())
        map.value = mapOf("live" to CoverRef("live", "local-000000000000000a.jpg", 600, 900),
            "lost" to CoverRef("lost", "local-00000000000000ff.jpg", 600, 900))
        compose.waitUntil(5_000) { artworkNodes().size == 1 && missing.isNotEmpty() }
        val bounds = artworkNodes().single().boundsInRoot
        assertEquals("existing fixed frame, cropped, never stretched", 0.68f, bounds.width / bounds.height, 0.02f)
        assertEquals(listOf("orphan"), synchronized(requested) { requested.toList() })
        assertEquals(listOf("lost"), synchronized(missing) { missing.toList() })
        map.value = map.value!! + ("live" to CoverRef("live", "local-000000000000000b.jpg", 600, 900))
        compose.waitUntil(5_000) { artwork.cached("live/local-000000000000000b.jpg/1") != null || artwork.cached("live/local-000000000000000b.jpg/2") != null }
        compose.waitForIdle()
        assertEquals(1, artworkNodes().size)
    }
}
