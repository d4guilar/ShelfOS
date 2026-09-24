// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.core.theme.ShelfTheme
import com.d4guilar.shelfos.core.theme.ThemeId
import com.d4guilar.shelfos.feature.home.ShelfApp
import com.d4guilar.shelfos.feature.library.LibraryViewModel
import com.d4guilar.shelfos.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ShelfApplication).container
        setContent {
            val settings: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(container.themes) } })
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
                    // Phase 0 keeps all controls inside the larger unobstructed fold region.
                    // Dedicated book/tabletop arrangements belong to later reader work.
                    var bounds by remember { mutableStateOf(Rect.Zero) }
                    val density = LocalDensity.current
                    val hinge = fold?.bounds
                    val padding = with(density) {
                        if (hinge == null || bounds == Rect.Zero) PaddingValues(0.dp)
                        else if (fold?.orientation == FoldingFeature.Orientation.VERTICAL) {
                            val left = (hinge.left - bounds.left).coerceAtLeast(0f)
                            val right = (bounds.right - hinge.right).coerceAtLeast(0f)
                            if (left >= right) PaddingValues.Absolute(right = (bounds.width - left).coerceAtLeast(0f).toDp())
                            else PaddingValues.Absolute(left = (bounds.width - right).coerceAtLeast(0f).toDp())
                        } else {
                            val top = (hinge.top - bounds.top).coerceAtLeast(0f)
                            val bottom = (bounds.bottom - hinge.bottom).coerceAtLeast(0f)
                            if (top >= bottom) PaddingValues(bottom = (bounds.height - top).coerceAtLeast(0f).toDp())
                            else PaddingValues(top = (bounds.height - bottom).coerceAtLeast(0f).toDp())
                        }
                    }
                    Box(Modifier.fillMaxSize().safeDrawingPadding().onGloballyPositioned { bounds = it.boundsInWindow() }.padding(padding)) {
                        if (theme != null) ShelfApp(library, settings, container, onSystemBack = { onBackPressedDispatcher.onBackPressed() })
                    }
                }
            }
        }
    }
}
