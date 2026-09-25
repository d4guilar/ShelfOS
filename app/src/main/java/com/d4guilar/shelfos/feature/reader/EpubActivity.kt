// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.d4guilar.shelfos.ShelfApplication
import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.core.theme.*
import com.d4guilar.shelfos.domain.library.*

/**
 * Hosts Readium's fragment-based navigator. The navigator is rebuilt from the persisted locator rather than
 * restored from FragmentManager state, so rotation and process recreation resume the saved position. The rest of
 * the saved state restores normally: controls, open dialogs and unapplied Appearance changes survive recreation.
 */
class EpubActivity : AppCompatActivity() {
    private var readerKeys: ((KeyEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        restoreEpubNavigatorAsPlaceholder()
        super.onCreate(savedInstanceState)
        removeRestoredEpubNavigator()
        enableEdgeToEdge()
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        val container = (application as ShelfApplication).container
        setContent {
            val vm: EpubReaderViewModel = viewModel(factory = viewModelFactory { initializer {
                EpubReaderViewModel(itemId, container.library, container.epubs, container.backgroundScope)
            } })
            val theme by container.themes.theme.collectAsStateWithLifecycle(initialValue = null as ThemeId?)
            // Wait for the saved theme instead of flashing Classic first.
            theme?.let { ShelfTheme(it) { EpubReaderContent(vm) } }
        }
    }

    @Composable
    private fun EpubReaderContent(vm: EpubReaderViewModel) {
        val state by vm.state.collectAsStateWithLifecycle()
        val tokens = LocalShelfTokens.current
        val controller = remember { EpubController() }
        var controls by rememberSaveable { mutableStateOf(true) }
        var appearance by rememberSaveable { mutableStateOf(false) }
        var chapters by rememberSaveable { mutableStateOf(false) }
        var topFocused by remember { mutableStateOf(false) }
        var bottomFocused by remember { mutableStateOf(false) }
        var controlFocusRequests by remember { mutableIntStateOf(0) }
        val firstControl = remember { FocusRequester() }
        val item = state.item
        val session = state.session
        val rtl = readingDirection(item?.category ?: MediaCategory.BOOK, state.preferences.direction) == ReadingDirection.RTL
        // Back never leaves the reader from hidden chrome: it reveals controls first, then a second Back exits.
        fun backPress() { if (controls) finish() else { controls = true; controlFocusRequests++ } }

        SideEffect {
            val style = if (tokens.dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            // Reader commands apply unless a reader control holds focus, so keys work before the WebView is focused.
            readerKeys = { event ->
                when (val command = event.readerCommand(rtl, controls && (topFocused || bottomFocused))) {
                    ShelfCommand.NEXT_PAGE, ShelfCommand.PREVIOUS_PAGE, ShelfCommand.OPEN_MENU, ShelfCommand.BACK -> {
                        if (event.action == KeyEvent.ACTION_UP) when (command) {
                            ShelfCommand.NEXT_PAGE -> controller.next()
                            ShelfCommand.PREVIOUS_PAGE -> controller.previous()
                            ShelfCommand.OPEN_MENU -> if (controls) controls = false else { controls = true; controlFocusRequests++ }
                            else -> backPress()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        BackHandler { backPress() }
        LaunchedEffect(controlFocusRequests) { if (controlFocusRequests > 0) runCatching { firstControl.requestFocus() } }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("epub_reader")) {
                if (controls) Row(Modifier.fillMaxWidth().onFocusChanged { topFocused = it.hasFocus }, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ finish() }, Modifier.focusRequester(firstControl).testTag("epub_library")) { Text("Library") }
                    TextButton({ chapters = true }, enabled = session != null) { Text("Chapters") }
                    TextButton({ appearance = true }, enabled = session != null) { Text("Appearance") }
                }
                if (session != null && item != null) {
                    EpubSurface(this@EpubActivity, session, item.locator, state.preferences, tokens.dark, item.category, controller,
                        Modifier.weight(1f).fillMaxWidth()
                            .semantics { stateDescription = if (controls) "Controls shown" else "Controls hidden. Tap the page center to show controls." },
                        onCenterTap = { controls = !controls }, onLocation = vm::location)
                } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val error = state.error
                    if (error == null) CircularProgressIndicator()
                    else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error)
                        TextButton({ finish() }) { Text("Back to Library") }
                    }
                }
                if (session != null) {
                    state.error?.let { Text(it, Modifier.padding(8.dp)) }
                    // Page controls follow the reading direction: in right-to-left reading, Next sits on the left.
                    if (controls) CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxWidth().onFocusChanged { bottomFocused = it.hasFocus }, horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(controller::previous) { Text("Previous") }
                            Text("${item?.progress ?: 0}%", Modifier.align(Alignment.CenterVertically),
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                            TextButton(controller::next) { Text("Next") }
                        }
                    }
                }
            }
        }
        if (appearance && item != null) ReaderAppearance(state.preferences, capabilities(item.format), { appearance = false },
            vm::applyAppearance, vm::resetAppearance)
        if (chapters && session != null) AlertDialog(onDismissRequest = { chapters = false }, title = { Text("Chapters") }, text = {
            LazyColumn { items(session.chapters) { (title, href) -> TextButton({ controller.chapter(session, href); chapters = false }) { Text(title) } } }
        }, confirmButton = { TextButton({ chapters = false }) { Text("Close") } })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = readerKeys?.invoke(event) == true || super.dispatchKeyEvent(event)

    companion object {
        private const val EXTRA_ITEM_ID = "itemId"
        fun intent(context: Context, itemId: String): Intent = Intent(context, EpubActivity::class.java).putExtra(EXTRA_ITEM_ID, itemId)
    }
}
