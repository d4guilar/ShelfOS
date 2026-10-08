// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.d4guilar.shelfos.core.reader.FoldOrientation
import com.d4guilar.shelfos.core.reader.FoldRect
import com.d4guilar.shelfos.core.reader.ReaderFoldDescriptor
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.feature.home.ShelfApp
import com.d4guilar.shelfos.feature.library.LibraryViewModel
import com.d4guilar.shelfos.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.map

/**
 * Phase 3D: the ONE platform-to-app mapping from a raw `androidx.window.layout.FoldingFeature` into the small,
 * app-owned, WindowManager-free [ReaderFoldDescriptor] that `core.reader` (and ultimately `FixedReaderScreen`)
 * consumes -- see [FoldLayout.kt][com.d4guilar.shelfos.core.reader] for why that boundary exists. Lives beside
 * [MainActivity] (the one and only `WindowInfoTracker`/`FoldingFeature` consumer in the app -- never a second
 * tracker) rather than inside `core.reader`, so no Android WindowManager type ever leaks into that pure package.
 * `FoldingFeature.bounds` is `android.graphics.Rect` (int, window coordinates); this simply widens to `Float` and
 * carries the orientation/separating/occlusion flags through unchanged.
 *
 * Phase 3D Codex R1 remediation, finding 3: deliberately `internal`, not `private` -- this is the REAL production
 * mapper `MainActivityFoldMapperTest` exercises directly (via a minimal in-test [FoldingFeature] implementation;
 * `androidx.window:window-testing` is not a project dependency, so this is the smallest way to test the actual
 * mapping function rather than a reimplementation of its logic inside the test). `internal` visibility is
 * sufficient and intentional: Kotlin's friend-module compiler args already make `internal` members of the `app`
 * module's main source set visible to its own `androidTest` source set, so no broader (`public`) exposure of an
 * app-layer mapping helper is needed just to test it.
 */
internal fun FoldingFeature.toReaderFoldDescriptor(): ReaderFoldDescriptor = ReaderFoldDescriptor(
    bounds = FoldRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
    orientation = if (orientation == FoldingFeature.Orientation.VERTICAL) FoldOrientation.VERTICAL else FoldOrientation.HORIZONTAL,
    isSeparating = isSeparating,
    occludesFully = occlusionType == FoldingFeature.OcclusionType.FULL,
)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ShelfApplication).container
        setContent {
            val settings: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container.themes, container.library, container.languages) } })
            val library: LibraryViewModel = viewModel(factory = viewModelFactory { initializer {
                LibraryViewModel(container.library, createSavedStateHandle())
            } })
            val theme by settings.theme.collectAsStateWithLifecycle()
            // Phase 3D Codex R1 remediation, finding 3: maps EVERY `FoldingFeature` the platform currently
            // reports (never pre-filtered with `firstOrNull` before any reader bounds are known) into the small,
            // app-owned `ReaderFoldDescriptor` list -- `core.reader`'s own `selectRelevantFoldDescriptor`/
            // `resolveReaderFoldLayout(bounds, descriptors, gutter)` is the ONE place a single constraining
            // descriptor is actually chosen, and it only ever does so once the reader's own bounds are known
            // (see that function's own doc for why choosing earlier, by platform list order alone, could pick a
            // feature that doesn't even intersect the reader while discarding one that does). Still the one and
            // only `WindowInfoTracker`/`FoldingFeature` consumer in the app.
            val foldingFlow = remember { WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
                .map { info -> info.displayFeatures.filterIsInstance<FoldingFeature>().map { it.toReaderFoldDescriptor() } } }
            val folds by foldingFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            ShelfTheme(theme ?: ThemeId.CLASSIC) {
                val dark = LocalShelfTokens.current.dark
                SideEffect {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                Surface(Modifier.fillMaxSize()) {
                    // Phase 3D Codex R1 remediation, "route-entry race" (finding 2's sub-section): the
                    // non-reader legacy fold-padding decision used to reach ShelfApp through `onReadingChanged`,
                    // an async `LaunchedEffect(reading)` callback -- meaning a composition pass could still
                    // apply legacy padding (or, symmetrically, skip it) for one frame BEFORE ShelfApp's own
                    // already-synchronously-known `reading` value had a chance to propagate back up here. The
                    // fix: MainActivity no longer computes this padding itself at all -- it only measures
                    // [bounds] and hands RAW window-space inputs (`bounds`, `folds`) down to ShelfApp, which
                    // computes `reading` AND the legacy padding in the SAME composition pass, at the SAME
                    // composition level where the route is already known (see ShelfApp's own doc for where that
                    // decision now lives). A fixed reader's own first-frame geometry therefore never depends on
                    // an async round trip either -- it reads `folds` directly.
                    var bounds by remember { mutableStateOf(Rect.Zero) }
                    Box(Modifier.fillMaxSize().safeDrawingPadding().onGloballyPositioned { bounds = it.boundsInWindow() }) {
                        if (theme != null) ShelfApp(library, settings, container, onSystemBack = { onBackPressedDispatcher.onBackPressed() },
                            folds = folds, legacyWindowBounds = bounds)
                    }
                }
            }
        }
    }
}
