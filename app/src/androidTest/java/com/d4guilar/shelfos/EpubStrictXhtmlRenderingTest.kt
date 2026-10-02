// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.feature.reader.EpubActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Post-Phase-2 EPUB XHTML regression. A real EPUB (Project Gutenberg's Frankenstein) rendered every chapter as
 * Chromium's "This page contains the following errors … Opening and ending tag mismatch: meta … and head" page:
 * ShelfOS's rendition sanitizer round-tripped well-formed XHTML through an HTML parser and HTML serializer, emitting
 * `<meta …>`/`<br>` without their XML self-closing slash into a resource the WebView parses as XML
 * (`application/xhtml+xml`). Every earlier fixture happened to contain no void or self-closed element, so nothing
 * caught it. [OriginalFixtures.strictXhtmlEpub] reproduces the exact characteristics with original content.
 */
class EpubStrictXhtmlRenderingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (context.applicationContext as ShelfApplication).container

    @Before fun seed() = runBlocking<Unit> { container.library.add(OriginalFixtures.strictXhtmlEpub(context)) }
    @After fun remove() = runBlocking<Unit> { container.library.remove("test-epub-strict-xhtml") }

    /** The exact bytes the navigator serves for each reading-order resource, through the production
     * EpubReaderFactory/TransformingContainer pipeline — not a reimplementation of it. */
    private fun servedChapters(): List<String> = runBlocking {
        container.epubs.open(OriginalFixtures.strictXhtmlEpub(context)).use { session ->
            session.publication.readingOrder.map { link ->
                val resource = assertNotNullAndGet(session.publication.get(link), "resource for ${link.href}")
                try { assertNotNullAndGet(resource.read().getOrNull(), "bytes for ${link.href}").toString(Charsets.UTF_8) }
                finally { resource.close() }
            }
        }
    }

    private fun <T : Any> assertNotNullAndGet(value: T?, what: String): T { assertNotNull("missing $what", value); return value!! }

    @Test fun everyServedXhtmlChapterIsWellFormedXmlWithItsStructureIntact() {
        val chapters = servedChapters()
        assertEquals(2, chapters.size)
        chapters.forEachIndexed { index, xhtml ->
            val n = index + 1
            val document = try {
                DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                    .parse(xhtml.byteInputStream())
            } catch (error: Exception) {
                throw AssertionError("chapter $n served to the WebView is not well-formed XML: ${error.message}\n$xhtml", error)
            }
            val text = document.documentElement.textContent
            assertTrue("chapter $n keeps its reading text", "STRICT-XHTML-MARKER-$n" in text && "second line after a break" in text)
            // The sanitizer still strips active content in XML mode.
            assertEquals("chapter $n script removed", 0, document.getElementsByTagNameNS("*", "script").length)
            assertFalse("chapter $n event handler removed", "onclick" in xhtml)
            // The self-closed empty anchor stays one empty element: an HTML parser instead treats `<a …/>` as an open
            // tag, wraps the heading text in it and duplicates it after the heading (two elements with the same id).
            val anchors = (0 until document.getElementsByTagNameNS("*", "a").length)
                .map { document.getElementsByTagNameNS("*", "a").item(it) as Element }.filter { it.getAttribute("id") == "strict-$n" }
            assertEquals("chapter $n anchor id stays unique", 1, anchors.size)
            assertEquals("chapter $n anchor stays empty", 0, anchors.single().childNodes.length)
            // Publication-authored head content (stylesheet link, metadata) is preserved, not dropped.
            assertEquals("chapter $n stylesheet link kept", 1, document.getElementsByTagNameNS("*", "link").length)
            assertTrue("chapter $n epub:type kept", "epub:type=\"chapter\"" in xhtml)
        }
    }

    /** Text of every accessibility node in the active window, including the WebView's own DOM text nodes. */
    private fun visibleText(): String {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val out = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.let { out.append(it).append('\n') }
            node.contentDescription?.let { out.append(it).append('\n') }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(automation.rootInActiveWindow)
        return out.toString()
    }

    @Test fun strictXhtmlChapterRendersInTheWebViewWithoutAnXmlParseError() {
        ActivityScenario.launch<EpubActivity>(EpubActivity.intent(context, "test-epub-strict-xhtml")).use {
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("Appearance") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var text = visibleText()
            while (SystemClock.elapsedRealtime() < deadline && "STRICT-XHTML-MARKER-1" !in text && "following errors" !in text) {
                SystemClock.sleep(250); text = visibleText()
            }
            assertFalse("WebView showed an XML parse error page:\n$text", "This page contains the following errors" in text)
            assertTrue("chapter text never rendered in the WebView:\n$text", "STRICT-XHTML-MARKER-1" in text)
        }
    }
}
