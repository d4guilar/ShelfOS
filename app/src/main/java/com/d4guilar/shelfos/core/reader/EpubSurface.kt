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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.domain.library.MediaCategory
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Locator

/** Outcome of [EpubController.jumpTo]. */
enum class JumpResult {
    /** The reader landed on the persisted locator's resource and the exact locator was issued a second time. */
    ARRIVED,
    /** The persisted locator is not a parseable Readium locator; nothing was attempted. */
    MALFORMED,
    /** The navigator was not ready, was destroyed, or did not reach the target resource within the timeout. */
    UNAVAILABLE,
}

class EpubController {
    internal var navigator: EpubNavigatorFragment? = null
    /** Phase 4B: ShelfOS-owned selection/decoration adapter (see [EpubAnnotationBridge]). */
    val annotations = EpubAnnotationBridge()
    /** Readium needs the fragment's view (and activity); commands arriving while it attaches are ignored, not crashes. */
    private val ready get() = navigator?.takeIf { it.view != null }
    fun next() { ready?.goForward(animated = false) }
    fun previous() { ready?.goBackward(animated = false) }
    fun chapter(session: EpubSession, href: String) { session.chapter(href)?.let { ready?.go(it, animated = false) } }
    /** Navigates to a bookmark's stored locator JSON through the same navigator this controller already owns —
     * no second navigator is created. Returns false, without crashing or touching any other bookmark, when the
     * locator is malformed/unparseable or the navigator isn't attached yet; the caller decides how to surface that. */
    fun goTo(locatorJson: String): Boolean {
        val locator = runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull() ?: return false
        return ready?.go(locator, animated = false) ?: false
    }

    /**
     * Deterministic jump to a persisted annotation locator (Phase 4B). A single `go` in paginated mode is unreliable:
     * spike evidence landed on the target text in only 5-6 of 10 trials, while repeating the same locator once the
     * navigator reports the target resource as current hit 30 of 30. So: parse the exact persisted locator, `go`,
     * await the navigator's own `currentLocator` reaching the target resource (never a sleep), then issue the exact
     * same locator once more. Bounded by [timeoutMs], cancellation-safe (a cancelled caller cancels the wait), and
     * progressSnapshot is never consulted. Must be called on the main thread.
     */
    suspend fun jumpTo(locatorJson: String, timeoutMs: Long = JUMP_TIMEOUT_MS): JumpResult {
        val target = runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull() ?: return JumpResult.MALFORMED
        val nav = navigator ?: return JumpResult.UNAVAILABLE
        return try {
            val arrived = withTimeoutOrNull(timeoutMs) {
                nav.lifecycle.withStarted { }
                if (nav.view == null || !nav.go(target, animated = false)) return@withTimeoutOrNull false
                nav.currentLocator.first { it.href == target.href }
                nav.go(target, animated = false)
            }
            if (arrived == true) JumpResult.ARRIVED else JumpResult.UNAVAILABLE
        } catch (e: CancellationException) {
            // The navigator's lifecycle can be destroyed under us; that is not the caller being cancelled.
            currentCoroutineContext().ensureActive()
            JumpResult.UNAVAILABLE
        }
    }

    companion object { const val JUMP_TIMEOUT_MS = 4_000L }
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
    onCenterTap: () -> Unit, onLocation: (String, Int) -> Unit, marks: List<EpubMark> = emptyList()) {
    val manager = activity.supportFragmentManager
    val navigator = remember(session) {
        session.fragmentFactory(locator, preferences, dark, category, controller.annotations.selectionCallback())
            .instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
    }
    val locationCallback by rememberUpdatedState(onLocation)
    val centerTap by rememberUpdatedState(onCenterTap)
    DisposableEffect(navigator) {
        controller.navigator = navigator
        controller.annotations.attach(navigator)
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
            controller.annotations.detach(navigator)
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
        navigator.lifecycle.withStarted { navigator.submitPreferences(session.preferences(preferences, dark, category)) }
    }
    // Phase 4B: decorations and the tap listener touch the fragment's ViewModel, which throws until the fragment is
    // attached, so both run only inside STARTED (cancelled and re-run on every stop/start of the navigator) and only
    // when the WebView can render them. Readium diffs the group, so re-applying is idempotent.
    val tone = readerTone(preferences, dark)
    LaunchedEffect(navigator) {
        if (!controller.annotations.inlineSupported) return@LaunchedEffect
        navigator.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.annotations.listenForMarkTaps(navigator) }
    }
    LaunchedEffect(navigator, marks, tone) {
        if (!controller.annotations.inlineSupported) return@LaunchedEffect
        navigator.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.annotations.apply(navigator, marks, tone) }
    }
    LaunchedEffect(navigator) {
        navigator.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            navigator.currentLocator.collect { current ->
                locationCallback(current.toJSON().toString(), ((current.locations.totalProgression ?: 0.0) * 100).toInt())
            }
        }
    }
}
