// SPDX-License-Identifier: MPL-2.0
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.d4guilar.shelfos

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.reader.ReaderPreferences
import com.d4guilar.shelfos.core.reader.EpubManagedFontResource
import com.d4guilar.shelfos.core.reader.EpubReaderFactory
import com.d4guilar.shelfos.feature.reader.EpubManagedFontProofActivity
import com.d4guilar.shelfos.feature.reader.resetAppearance
import com.d4guilar.shelfos.feature.reader.saveAppearance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import java.io.File
import java.io.FileInputStream

/** Phase 2B.4 hard-gate proof: a private runtime file reaches a real Readium WebView without EPUB mutation. */
@RunWith(AndroidJUnit4::class)
class EpubManagedFontProofTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container
    private val managedFont get() = File(context.filesDir, EpubManagedFontProofActivity.PROOF_FONT_PATH)

    @Test fun runtimeManagedFontLoadsFallsBackSafelyAndSurvivesRecreation() = runBlocking {
        val item = OriginalFixtures.epubWithChapters(context).copy(
            preferences = ReaderPreferences(fontFamilyId = EpubManagedFontProofActivity.PROOF_FONT_ID).json()
        )
        container.library.add(item)
        container.library.resetAppearance(item.id, "{}", globally = false)
        container.library.saveAppearance(item.id, "{}", null, ReaderPreferences.DEFAULT,
            ReaderPreferences.DEFAULT.copy(fontFamilyId = EpubManagedFontProofActivity.PROOF_FONT_ID), globally = false)
        val epub = requireNotNull(item.managedPath).let(::File)
        val epubBytes = epub.readBytes()
        val epubModified = epub.lastModified()

        managedFont.parentFile!!.mkdirs()
        FileInputStream("/system/fonts/DancingScript-Regular.ttf").use { input ->
            managedFont.outputStream().use(input::copyTo)
        }
        assertTrue(managedFont.isFile)
        assertFalse(managedFont.path.contains("src/main/assets"))
        val stored = requireNotNull(container.library.publication(item.id).first())
        assertEquals(EpubManagedFontProofActivity.PROOF_FONT_ID, ReaderPreferences.parse(stored.preferences).fontFamilyId)
        EpubReaderFactory(context, container.files) {
            listOf(EpubManagedFontResource(EpubManagedFontProofActivity.PROOF_FONT_ID, "Managed proof", managedFont))
        }.open(item).use { session ->
            assertEquals("ShelfOS-test-managed-proof", session.managedFontCssFamily(EpubManagedFontProofActivity.PROOF_FONT_ID))
            assertEquals("ShelfOS-test-managed-proof", session.preferences(ReaderPreferences.parse(item.preferences), false, item.category).fontFamily?.name)
            val absolute = session.hasResource("https://readium_package/__shelfos/fonts/test-managed-proof/regular.ttf")
            val relative = session.hasResource("__shelfos/fonts/test-managed-proof/regular.ttf")
            assertTrue("absolute=$absolute relative=$relative", absolute || relative)
        }

        launch(item.id).use { scenario ->
            val first = renderedFont(awaitNavigator(scenario))
            assertTrue("Expected managed family in computed style: $first", first.getString("family").contains("ShelfOS-test-managed-proof"))
            assertTrue("Expected the managed @font-face to be loaded: $first", first.getBoolean("loaded"))
            assertEquals(200, first.getJSONObject("fetch").getInt("status"))
            assertEquals(managedFont.length(), first.getJSONObject("fetch").getLong("length"))
            assertEquals(listOf(0, 1, 0, 0), first.getJSONObject("fetch").getJSONArray("head").let { head ->
                (0 until 4).map(head::getInt)
            })
            scenario.recreate()
            val recreated = renderedFont(awaitNavigator(scenario))
            assertTrue(recreated.getString("family").contains("ShelfOS-test-managed-proof"))
            assertTrue(recreated.getBoolean("loaded"))
        }

        assertArrayEquals(epubBytes, epub.readBytes())
        assertEquals(epubModified, epub.lastModified())

        assertTrue(managedFont.delete())
        launch(item.id).use { scenario ->
            val missing = renderedFont(awaitNavigator(scenario))
            assertFalse(missing.getString("family").contains("ShelfOS-test-managed-proof"))
            assertFalse(missing.getBoolean("loaded"))
        }

        managedFont.writeText("not a font")
        launch(item.id).use { scenario ->
            val corrupt = renderedFont(awaitNavigator(scenario))
            assertTrue("Reader did not render fallback text", corrupt.getDouble("width") > 0.0)
            assertFalse(corrupt.getBoolean("loaded"))
        }
        assertArrayEquals(epubBytes, epub.readBytes())
        assertEquals(epubModified, epub.lastModified())
    }

    private fun launch(itemId: String) = ActivityScenario.launch<EpubManagedFontProofActivity>(
        Intent(context, EpubManagedFontProofActivity::class.java).putExtra("itemId", itemId)
    )

    private fun awaitNavigator(scenario: ActivityScenario<EpubManagedFontProofActivity>): EpubNavigatorFragment {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var navigator: EpubNavigatorFragment? = null
            scenario.onActivity { activity ->
                navigator = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
            }
            if (navigator?.view != null) return requireNotNull(navigator)
            SystemClock.sleep(100)
        }
        error("Readium navigator did not attach")
    }

    private suspend fun renderedFont(navigator: EpubNavigatorFragment): JSONObject {
        val script = """
            (() => {
              const family = getComputedStyle(document.body).fontFamily;
              const allFaces = Array.from(document.fonts);
              const face = allFaces.find(f => f.family.includes('ShelfOS-test-managed-proof'));
              const styles = Array.from(document.styleSheets).flatMap(s => {
                try { return Array.from(s.cssRules).map(r => r.cssText).filter(t => t.includes('ShelfOS-test-managed-proof')); }
                catch (_) { return []; }
              });
              if (!window.__shelfFontFetch) {
                window.__shelfFontFetch = {status: 'pending'};
                fetch('https://readium_package/__shelfos/fonts/test-managed-proof/regular.ttf')
                  .then(async r => {
                    const bytes = new Uint8Array(await r.arrayBuffer());
                    window.__shelfFontFetch = {status: r.status, ok: r.ok, type: r.headers.get('content-type'),
                      length: bytes.length, head: Array.from(bytes.slice(0, 16))};
                  })
                  .catch(e => { window.__shelfFontFetch = {status: 'error', message: String(e)}; });
              }
              const marker = document.createElement('span');
              marker.textContent = 'ShelfOS managed font proof WWWiii';
              document.body.appendChild(marker);
              const result = {family, loaded: !!face && face.status === 'loaded', width: marker.getBoundingClientRect().width,
                faces: allFaces.map(f => ({family: f.family, status: f.status})), styles, fetch: window.__shelfFontFetch};
              marker.remove();
              return JSON.stringify(result);
            })()
        """.trimIndent()
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var last: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            val raw = withContext(Dispatchers.Main) { navigator.evaluateJavascript(script) }
            if (raw != null && raw != "null") {
                val decoded = JSONTokener(raw).nextValue() as? String
                if (decoded != null) {
                    last = JSONObject(decoded)
                    val fetched = last.optJSONObject("fetch")?.optInt("status") == 200
                    if ((last.getBoolean("loaded") && fetched) || !last.getString("family").contains("ShelfOS-test-managed-proof")) return last
                }
            }
            SystemClock.sleep(100)
        }
        return requireNotNull(last) { "Navigator JavaScript returned no font evidence" }
    }
}
