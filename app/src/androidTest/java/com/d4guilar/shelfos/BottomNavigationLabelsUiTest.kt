// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.feature.home.BottomNavigation
import com.d4guilar.shelfos.feature.home.Destination
import com.d4guilar.shelfos.feature.home.NavLabelMode
import com.d4guilar.shelfos.feature.home.navLabelLayout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

/**
 * NAV-1: the real bottom bar at phone widths, font scales, densities and the three interface languages. A label is drawn whole on
 * one line or not at all (never broken mid-word or clipped); all five labels show whenever their real measured widths together
 * fit the bar; every destination stays reachable, at least 48 dp wide, with its complete localized name for accessibility; the bar
 * height does not change between routes and, with all labels shown, no item moves when the selection changes.
 */
class BottomNavigationLabelsUiTest {
    @get:Rule val rule = createComposeRule()

    private var width by mutableStateOf(360.dp)
    private var fontScale by mutableStateOf(1f)
    private var density by mutableStateOf(0f) // 0 = the device's own density
    // Not named `locale`: inside `Configuration().apply { }` that name resolves to Configuration's own field, leaving the bar English.
    private var language by mutableStateOf(Locale.ENGLISH)
    private var route by mutableStateOf("library")
    private val navigated = ArrayList<String>()
    private var started = false

    /** Each label's single-line width in pixels (wider of both weights), measured by real Compose text in the bar's own context. */
    private var measured: List<Int> = emptyList()

    private fun show(w: Dp, scale: Float, l: Locale, r: String = "library", d: Float = 0f) {
        width = w; fontScale = scale; language = l; route = r; density = d
        if (started) { rule.waitForIdle(); return }
        started = true
        rule.setContent {
            val base = LocalContext.current
            val ctx = base.createConfigurationContext(Configuration(LocalConfiguration.current).apply { setLocale(language) })
            val own = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides ctx, LocalConfiguration provides ctx.resources.configuration,
                LocalDensity provides Density(if (density > 0f) density else own, fontScale)) {
                ShelfTheme(ThemeId.CLASSIC) {
                    val measurer = rememberTextMeasurer()
                    val styles = listOf(FontWeight.SemiBold, FontWeight.Normal).map { MaterialTheme.typography.labelSmall.copy(fontWeight = it) }
                    val labels = Destination.entries.map { stringResource(it.labelRes) }
                    SideEffect { measured = labels.map { label -> styles.maxOf { measurer.measure(label, it, maxLines = 1, softWrap = false).size.width } } }
                    Box(Modifier.width(width)) { BottomNavigation(route, Modifier) { navigated += it.route } }
                }
            }
        }
        rule.waitForIdle()
    }

    private val px get() = if (density > 0f) density else rule.density.density
    private val padPx get() = (4f * px).roundToInt()
    private val targetPx get() = (48f * px).roundToInt()

    private fun label(d: Destination) = InstrumentationRegistry.getInstrumentation().targetContext
        .createConfigurationContext(Configuration().apply { setLocale(language) }).resources.getString(d.labelRes)

    private fun shown(d: Destination) = rule.onNodeWithTag("nav_${d.route}").fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label(d) }

    /** Checks the drawn bar, and returns its mode as read from what is actually displayed. */
    private fun assertBar(tag: String): NavLabelMode {
        val bar = rule.onNodeWithTag("bottom_navigation").fetchSemanticsNode()
        for (d in Destination.entries) {
            val node = rule.onNodeWithTag("nav_${d.route}").assertExists().assertHasClickAction().fetchSemanticsNode()
            assertTrue("$tag: ${d.route} at least 48 dp wide (${node.size.width / px})", node.size.width >= 48f * px - 1f)
            assertTrue("$tag: ${d.route} inside the bar", node.positionInRoot.x >= -1f && node.positionInRoot.x + node.size.width <= bar.size.width + 1f)
            // The complete localized name is always available to accessibility: as visible text or as the content description.
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            assertTrue("$tag: ${d.route} names itself '${label(d)}' (desc=$desc text=$text)", label(d) in desc || label(d) in text)
        }
        // Whatever text is drawn is whole: one line, not ellipsized, and its line ends inside both its own laid-out width and the item's
        // padded content box (softWrap is off, so a too-long label would run past them and be clipped).
        for (d in Destination.entries) {
            val item = rule.onNodeWithTag("nav_${d.route}").fetchSemanticsNode()
            rule.onNodeWithTag("nav_${d.route}", useUnmergedTree = true).onChildren().fetchSemanticsNodes().forEach { n ->
                val action = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return@forEach
                val results = ArrayList<TextLayoutResult>().also { action(it) }
                results.forEach {
                    val name = "$tag: ${d.route} label '${it.layoutInput.text.text}'"
                    assertEquals("$name stays on one line", 1, it.lineCount)
                    assertFalse("$name is not ellipsized", it.isLineEllipsized(0))
                    assertTrue("$name is not clipped (${it.getLineRight(0)}px in ${it.size.width}px)", it.getLineRight(0) <= it.size.width)
                    assertTrue("$name fits its item's content box (${it.getLineRight(0)}px in ${item.size.width}px - 2 x ${padPx}px)",
                        it.getLineRight(0) <= item.size.width - 2 * padPx)
                    assertTrue("$name lies inside its item", n.positionInRoot.x >= item.positionInRoot.x - 0.5f &&
                        n.positionInRoot.x + n.size.width <= item.positionInRoot.x + item.size.width + 0.5f)
                }
            }
        }
        assertEquals("$tag: exactly one selected destination", 1, rule.onAllNodes(isSelected()).fetchSemanticsNodes().size)

        val shownCount = Destination.entries.count(::shown)
        val mode = when (shownCount) { 5 -> NavLabelMode.ALL; 1 -> NavLabelMode.SELECTED_ONLY; 0 -> NavLabelMode.NONE
            else -> error("$tag: $shownCount labels shown") }
        // The displayed labels are what the policy decides from the real measured widths...
        val selected = Destination.entries.indexOfFirst { it.route == route }
        assertEquals("$tag: displayed mode (measured $measured px, bar ${bar.size.width}px)",
            navLabelLayout(bar.size.width, measured, selected, padPx, targetPx).mode, mode)
        // ...and, independently of the policy: whenever the labels with their padding fit together, all of them are shown.
        val need = measured.sumOf { maxOf(targetPx, it + padPx * 2) }
        if (need <= bar.size.width) assertEquals("$tag: all labels fit ($need of ${bar.size.width}px)", NavLabelMode.ALL, mode)
        if (mode == NavLabelMode.SELECTED_ONLY) assertTrue("$tag: the selected label is shown", shown(Destination.entries[selected]))
        return mode
    }

    private val locales = listOf(Locale.ENGLISH, Locale("es"), Locale.forLanguageTag("pt-BR"))
    private val settingsLabel = mapOf(locales[0] to "Settings", locales[1] to "Ajustes", locales[2] to "Configurações")

    @Test fun noLabelBreaksAtPhoneWidthsFontScalesDensitiesAndLanguages() {
        // 0 = this device; 2.625 = 420 dpi, where a 4 dp padding rounds to 10.5 px per side.
        for (d in listOf(0f, 2.625f)) for (l in locales) for (w in listOf(320, 360, 393)) for (scale in listOf(1f, 1.3f, 1.5f, 2f)) {
            show(w.dp, scale, l, d = d)
            assertEquals("$l: the bar really is in this language", settingsLabel.getValue(l), label(Destination.SETTINGS))
            assertBar("$l ${w}dp x$scale @${d}")
        }
    }

    @Test fun brazilianPortugueseShowsEveryLabelAtTypicalPhoneWidth() {
        // QA regression of 7451484: "Configurações" did not fit a fifth of 393 dp, so four labels were hidden although all five fit.
        for (d in listOf(0f, 2.625f)) {
            show(393.dp, 1f, Locale.forLanguageTag("pt-BR"), d = d)
            assertEquals("the bar really is in Brazilian Portuguese", "Configurações", label(Destination.SETTINGS))
            assertEquals("pt-BR 393dp x1 @$d", NavLabelMode.ALL, assertBar("pt-BR 393dp x1 @$d"))
            for (dest in Destination.entries) assertTrue("${dest.route} label shown", shown(dest))
        }
    }

    @Test fun everyDestinationCanBeSelectedAndTheBarHeightNeverJumps() {
        for ((w, scale) in listOf(320 to 1.5f, 393 to 1f, 393 to 2f)) {
            show(w.dp, scale, Locale.forLanguageTag("pt-BR"))
            val heights = HashSet<Int>()
            val geometries = HashSet<List<Pair<Float, Int>>>()
            var allLabels = true
            for (d in Destination.entries) {
                route = d.route; rule.waitForIdle()
                heights += rule.onNodeWithTag("bottom_navigation").fetchSemanticsNode().size.height
                rule.onNodeWithTag("nav_${d.route}").assertIsSelected()
                allLabels = allLabels && assertBar("$w x$scale select ${d.route}") == NavLabelMode.ALL
                geometries += Destination.entries.map { e -> rule.onNodeWithTag("nav_${e.route}").fetchSemanticsNode().let { it.positionInRoot.x to it.size.width } }
            }
            assertEquals("$w x$scale: one height for every route: $heights", 1, heights.size)
            if (allLabels) assertEquals("$w x$scale: with every label shown no item moves between routes: $geometries", 1, geometries.size)
        }
    }

    @Test fun tappingEachDestinationReportsIt() {
        for ((w, scale) in listOf(320 to 1.5f, 393 to 1f)) {
            show(w.dp, scale, Locale("es")); navigated.clear()
            for (d in Destination.entries) rule.onNodeWithTag("nav_${d.route}").performClick()
            assertEquals(Destination.entries.map { it.route }, navigated)
        }
    }
}
