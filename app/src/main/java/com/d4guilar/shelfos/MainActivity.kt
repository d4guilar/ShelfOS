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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import com.d4guilar.shelfos.core.reader.legacySafePaneInset
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
 */
private fun FoldingFeature.toReaderFoldDescriptor(): ReaderFoldDescriptor = ReaderFoldDescriptor(
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
            val foldingFlow = remember { WindowInfoTracker.getOrCreate(this).windowLayoutInfo(this)
                .map { info -> info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL } } }
            val fold by foldingFlow.collectAsStateWithLifecycle(initialValue = null)
            ShelfTheme(theme ?: ThemeId.CLASSIC) {
                val dark = LocalShelfTokens.current.dark
                SideEffect {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                Surface(Modifier.fillMaxSize()) {
                    // Phase 0 keeps all controls inside the larger unobstructed fold region. Dedicated book/
                    // tabletop arrangements belong to later reader work. Phase 3D: this Phase-0 behavior is
                    // PRESERVED BYTE-FOR-BYTE for every non-reader screen (now via the extracted, independently
                    // pure-tested legacySafePaneInset -- see FoldLayoutTest's "non-reader regression guard" cases)
                    // -- but while the reader route is active, the fixed reader needs the FULL safe-drawing
                    // window plus the raw fold descriptor to do its OWN fold-aware layout, rather than being
                    // pre-collapsed into a single pane before it ever sees the hinge. `reading` is reported by
                    // ShelfApp itself (the only place that knows whether the current nav-graph route is the
                    // reader), never guessed here -- MainActivity still never learns anything about comic
                    // pairing/spreads; it only toggles whether its OWN legacy padding applies.
                    var bounds by remember { mutableStateOf(Rect.Zero) }
                    var reading by remember { mutableStateOf(false) }
                    val density = LocalDensity.current
                    val hinge = fold?.bounds
                    val padding = with(density) {
                        if (reading || hinge == null || bounds == Rect.Zero) PaddingValues(0.dp)
                        else {
                            val vertical = fold?.orientation == FoldingFeature.Orientation.VERTICAL
                            val windowBounds = FoldRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                            val windowHinge = FoldRect(hinge.left.toFloat(), hinge.top.toFloat(), hinge.right.toFloat(), hinge.bottom.toFloat())
                            val inset = legacySafePaneInset(windowBounds, windowHinge, vertical)
                            PaddingValues.Absolute(inset.left.toDp(), inset.top.toDp(), inset.right.toDp(), inset.bottom.toDp())
                        }
                    }
                    // The one app-owned fold descriptor, re-derived every time the platform reports a new
                    // FoldingFeature -- never persisted, never a second WindowInfoTracker. Harmless to compute
                    // even when not reading; FixedReaderScreen is the only consumer.
                    val readerFold = remember(fold) { fold?.toReaderFoldDescriptor() }
                    Box(Modifier.fillMaxSize().safeDrawingPadding().onGloballyPositioned { bounds = it.boundsInWindow() }.padding(padding)) {
                        if (theme != null) ShelfApp(library, settings, container, onSystemBack = { onBackPressedDispatcher.onBackPressed() },
                            readerFold = readerFold, onReadingChanged = { reading = it })
                    }
                }
            }
        }
    }
}
