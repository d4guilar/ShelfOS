// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.feature.home.BottomNavigation
import com.d4guilar.shelfos.feature.home.Destination
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * NAV-1: the real bottom bar at phone widths, font scales and the three interface languages. A label is drawn whole on one line or
 * not at all (never broken mid-word); every destination stays reachable, at least 48 dp wide, with its complete localized name for
 * accessibility; the bar height does not change between routes.
 */
class BottomNavigationLabelsUiTest {
    @get:Rule val rule = createComposeRule()

    private var width by mutableStateOf(360.dp)
    private var fontScale by mutableStateOf(1f)
    private var locale by mutableStateOf(Locale.ENGLISH)
    private var route by mutableStateOf("library")
    private val navigated = ArrayList<String>()
    private var started = false

    private fun show(w: Dp, scale: Float, l: Locale, r: String = "library") {
        width = w; fontScale = scale; locale = l; route = r
        if (started) { rule.waitForIdle(); return }
        started = true
        rule.setContent {
            val base = LocalContext.current
            val ctx = base.createConfigurationContext(Configuration(LocalConfiguration.current).apply { setLocale(locale) })
            CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides ctx.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ShelfTheme(ThemeId.CLASSIC) {
                    Box(Modifier.width(width)) { BottomNavigation(route, Modifier) { navigated += it.route } }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun label(d: Destination) = InstrumentationRegistry.getInstrumentation().targetContext
        .createConfigurationContext(Configuration().apply { setLocale(locale) }).resources.getString(d.labelRes)

    private fun assertBar(tag: String) {
        val density = rule.density.density
        val bar = rule.onNodeWithTag("bottom_navigation").fetchSemanticsNode()
        for (d in Destination.entries) {
            val node = rule.onNodeWithTag("nav_${d.route}").assertExists().fetchSemanticsNode()
            assertTrue("$tag: ${d.route} at least 48 dp wide (${node.size.width / density})", node.size.width >= 48f * density - 1f)
            assertTrue("$tag: ${d.route} inside the bar", node.positionInRoot.x >= -1f && node.positionInRoot.x + node.size.width <= bar.size.width + 1f)
            // The complete localized name is always available to accessibility: as visible text or as the content description.
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertTrue("$tag: ${d.route} names itself '${label(d)}' (desc=$desc text=$text)", label(d) in desc || label(d) in text)
        }
        // Whatever text is drawn is whole: one line, and no wider than its own item (softWrap is off, so a too-long label would be cut).
        for (d in Destination.entries) {
            val item = rule.onNodeWithTag("nav_${d.route}").fetchSemanticsNode()
            rule.onNodeWithTag("nav_${d.route}", useUnmergedTree = true).onChildren().fetchSemanticsNodes().forEach { n ->
                val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@forEach
                val results = ArrayList<TextLayoutResult>().also { action(it) }
                results.forEach {
                    assertEquals("$tag: ${d.route} label stays on one line", 1, it.lineCount)
                    assertTrue("$tag: ${d.route} label '${it.layoutInput.text.text}' (${it.getLineRight(0)}px) fits its item (${item.size.width}px)", it.getLineRight(0) <= item.size.width + 1f)
                }
            }
        }
        assertEquals("$tag: exactly one selected destination", 1, rule.onAllNodes(isSelected()).fetchSemanticsNodes().size)
    }

    private val locales = listOf(Locale.ENGLISH, Locale("es"), Locale.forLanguageTag("pt-BR"))

    @Test fun noLabelBreaksAtPhoneWidthsFontScalesAndLanguages() {
        for (l in locales) for (w in listOf(320, 360, 393)) for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            show(w.dp, scale, l); assertBar("$l ${w}dp x$scale")
        }
    }

    @Test fun everyDestinationCanBeSelectedAndTheBarHeightNeverJumps() {
        show(320.dp, 1.5f, Locale.forLanguageTag("pt-BR"))
        val heights = HashSet<Int>()
        for (d in Destination.entries) {
            route = d.route; rule.waitForIdle()
            heights += rule.onNodeWithTag("bottom_navigation").fetchSemanticsNode().size.height
            rule.onNodeWithTag("nav_${d.route}").assertIsSelected()
            assertBar("select ${d.route}")
        }
        assertEquals("one height for every route: $heights", 1, heights.size)
    }

    @Test fun tappingEachDestinationReportsIt() {
        show(320.dp, 1.5f, Locale("es")); navigated.clear()
        for (d in Destination.entries) rule.onNodeWithTag("nav_${d.route}").performClick()
        assertEquals(Destination.entries.map { it.route }, navigated)
    }
}
