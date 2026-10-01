// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.PresentationMode
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class EpubManagedFontAppearanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container
    private var familyId: String? = null

    @Before fun seed() = runBlocking<Unit> {
        container.library.add(OriginalFixtures.epub(context))
        familyId = FileInputStream("/system/fonts/DancingScript-Regular.ttf").use {
            container.fonts.import(it, "Dancing Script.ttf").id
        }
    }

    @After fun clean() = runBlocking<Unit> {
        container.library.remove("test-epub")
        familyId?.let { container.fonts.remove(it) }
    }

    @Test fun managedFamilyAndPresentationPersistThenMissingFamilyFallsBack() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub")).use {
            awaitReader()
            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("Dancing Script").performScrollTo().performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(10_000) {
                runBlocking { ReaderPreferences.parse(container.library.publication("test-epub").first()?.preferences).fontFamilyId == familyId }
            }

            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("Dancing Script").assertIsSelected()
            compose.onNodeWithText("Publisher").performScrollTo().performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(10_000) {
                runBlocking { ReaderPreferences.parse(container.library.publication("test-epub").first()?.preferences).presentationMode == PresentationMode.PUBLISHER }
            }
            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("ShelfOS").performScrollTo().performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.waitUntil(10_000) {
                runBlocking { ReaderPreferences.parse(container.library.publication("test-epub").first()?.preferences).presentationMode == PresentationMode.SHELFOS }
            }
        }

        runBlocking { container.fonts.remove(requireNotNull(familyId)) }
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub")).use { scenario ->
            awaitReader()
            val removedFamilyId = requireNotNull(familyId)
            val storedPreferences = runBlocking {
                ReaderPreferences.parse(container.library.publication("test-epub").first()?.preferences)
            }
            // The stored preference still points at the removed font — it is not silently rewritten — but
            // rendering must fall back to a safe built-in family rather than failing to resolve a CSS family that
            // no longer exists. "Serif" always being a listed chip proves nothing about this; check the actual
            // resolved rendering family instead.
            assertEquals(removedFamilyId, storedPreferences.fontFamilyId)
            val item = requireNotNull(runBlocking { container.library.publication("test-epub").first() })
            runBlocking { container.epubs.open(item) }.use { session ->
                val resolved = session.preferences(storedPreferences, false, item.category)
                assertEquals("serif", resolved.fontFamily?.name)
            }
            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("Serif").assertExists()
        }
        familyId = null
    }

    private fun awaitReader() = compose.waitUntil(30_000) {
        compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
}
