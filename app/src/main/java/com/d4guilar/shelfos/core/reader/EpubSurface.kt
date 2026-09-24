// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.domain.library.MediaCategory
import org.readium.r2.navigator.epub.EpubNavigatorFragment

class EpubController {
    internal var navigator: EpubNavigatorFragment? = null
    fun next() { navigator?.goForward(animated = false) }
    fun previous() { navigator?.goBackward(animated = false) }
    fun chapter(session: EpubSession, href: String) { session.chapter(href)?.let { navigator?.go(it, animated = false) } }
}

@Composable
fun EpubSurface(activity: FragmentActivity, session: EpubSession, locator: String?, preferences: ReaderPreferences,
    dark: Boolean, category: MediaCategory, controller: EpubController, modifier: Modifier,
    onLocation: (String, Int) -> Unit) {
    val manager = activity.supportFragmentManager
    val navigator = remember(session) {
        session.fragmentFactory(locator, preferences, dark, category)
            .instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
    }
    val locationCallback by rememberUpdatedState(onLocation)
    DisposableEffect(navigator) {
        controller.navigator = navigator
        onDispose {
            controller.navigator = null
            if (!manager.isDestroyed && !manager.isStateSaved) manager.beginTransaction().remove(navigator).commitNow()
        }
    }
    AndroidView(factory = { context -> FragmentContainerView(context).apply {
        id = R.id.epub_container
        // AndroidView attaches the container after its factory returns.
        manager.beginTransaction().replace(id, navigator).commit()
    } }, modifier = modifier)
    LaunchedEffect(navigator, preferences, dark) { navigator.submitPreferences(epubPreferences(preferences, dark, category)) }
    LaunchedEffect(navigator) {
        navigator.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            navigator.currentLocator.collect { current ->
                locationCallback(current.toJSON().toString(), ((current.locations.totalProgression ?: 0.0) * 100).toInt())
            }
        }
    }
}
