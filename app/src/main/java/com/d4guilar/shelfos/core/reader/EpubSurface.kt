// SPDX-License-Identifier: MPL-2.0
@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.d4guilar.shelfos.core.reader

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.domain.library.MediaCategory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter

class EpubController {
    internal var navigator: EpubNavigatorFragment? = null
    /** Readium needs the fragment's view (and activity); commands arriving while it attaches are ignored, not crashes. */
    private val ready get() = navigator?.takeIf { it.view != null }
    fun next() { ready?.goForward(animated = false) }
    fun previous() { ready?.goBackward(animated = false) }
    fun chapter(session: EpubSession, href: String) { session.chapter(href)?.let { ready?.go(it, animated = false) } }
}

/**
 * Call before `super.onCreate`. Readium's navigator needs the open publication, so it is rebuilt from the persisted
 * locator instead of being restored from FragmentManager state: a placeholder stands in for it during restoration,
 * which lets the rest of the activity's saved state (Compose UI state) restore normally.
 */
fun FragmentActivity.restoreEpubNavigatorAsPlaceholder() {
    supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
}

/** Call right after `super.onCreate`: removes the restored placeholder before it needs its container view. */
fun FragmentActivity.removeRestoredEpubNavigator() {
    supportFragmentManager.findFragmentById(R.id.epub_container)?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
}

@Composable
fun EpubSurface(activity: FragmentActivity, session: EpubSession, locator: String?, preferences: ReaderPreferences,
    dark: Boolean, category: MediaCategory, controller: EpubController, modifier: Modifier,
    onCenterTap: () -> Unit, onLocation: (String, Int) -> Unit) {
    val manager = activity.supportFragmentManager
    val navigator = remember(session) {
        session.fragmentFactory(locator, preferences, dark, category)
            .instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
    }
    val locationCallback by rememberUpdatedState(onLocation)
    val centerTap by rememberUpdatedState(onCenterTap)
    DisposableEffect(navigator) {
        controller.navigator = navigator
        // Edge taps follow the publication's reading progression; remaining taps toggle reader controls.
        val edges = DirectionalNavigationAdapter(navigator, animatedTransition = false)
        val center = object : InputListener {
            override fun onTap(event: TapEvent): Boolean { centerTap(); return true }
        }
        navigator.addInputListener(edges)
        navigator.addInputListener(center)
        onDispose {
            navigator.removeInputListener(center)
            navigator.removeInputListener(edges)
            controller.navigator = null
            if (!manager.isDestroyed && !manager.isStateSaved) manager.beginTransaction().remove(navigator).commitNow()
        }
    }
    AndroidView(factory = { context -> FragmentContainerView(context).apply {
        id = R.id.epub_container
        // AndroidView attaches the container after its factory returns.
        manager.beginTransaction().replace(id, navigator).commit()
    } }, modifier = modifier)
    // The factory applies the initial preferences; later changes wait until the fragment is attached and started.
    LaunchedEffect(navigator, preferences, dark) {
        navigator.lifecycle.withStarted { navigator.submitPreferences(epubPreferences(preferences, dark, category)) }
    }
    LaunchedEffect(navigator) {
        navigator.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            navigator.currentLocator.collect { current ->
                locationCallback(current.toJSON().toString(), ((current.locations.totalProgression ?: 0.0) * 100).toInt())
            }
        }
    }
}
