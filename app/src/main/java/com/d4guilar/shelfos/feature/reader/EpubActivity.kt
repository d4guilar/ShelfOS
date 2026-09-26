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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
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
import com.d4guilar.shelfos.core.designsystem.InputKeycap
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
        // Recent input modality (Phase 2A.1): only the real center-tap gesture and real key events update this,
        // never a button click, since a click may itself have been keyboard/gamepad-activated. Edge taps that
        // turn EPUB pages are handled entirely inside Readium's navigator and do not reach this callback.
        var modality by rememberSaveable { mutableStateOf(InputModality.TOUCH) }
        var topFocused by remember { mutableStateOf(false) }
        var bottomFocused by remember { mutableStateOf(false) }
        var controlFocusRequests by remember { mutableIntStateOf(0) }
        val firstControl = remember { FocusRequester() }
        val item = state.item
        val session = state.session
        val rtl = readingDirection(item?.category ?: MediaCategory.BOOK, state.preferences.direction) == ReadingDirection.RTL
        val previousHint = InputHints.hint(ShelfCommand.PREVIOUS_PAGE, modality, rtl)
        val nextHint = InputHints.hint(ShelfCommand.NEXT_PAGE, modality, rtl)
        val backHint = InputHints.hint(ShelfCommand.BACK, modality, rtl)
        // Back never leaves the reader from hidden chrome: it reveals controls first, then a second Back exits.
        fun backPress() { if (controls) finish() else { controls = true; controlFocusRequests++ } }

        SideEffect {
            val style = if (tokens.dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            // Reader commands apply unless a reader control holds focus, so keys work before the WebView is focused.
            readerKeys = { event ->
                // Raw modality classification is independent of which (if any) ShelfCommand the event becomes: it
                // must also apply to focus-navigation keys, CONFIRM and anything else that never reaches the
                // branch below. inputModalityOrNull() itself excludes the raw system Back/Home keys (never a
                // modality signal) rather than filtering by the resolved *semantic* command here, since Escape/
                // gamepad B legitimately produce ShelfCommand.BACK while still being real, attributable input.
                event.inputModalityOrNull()?.let { modality = it }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ finish() }, Modifier.focusRequester(firstControl).testTag("epub_library")) { Text("Library") }
                        // Input-discovery hint (Phase 2A.1): decorative only, since no single existing control is
                        // exactly "Back" to merge this into; the system Back gesture/button remains self-describing.
                        backHint?.let { Row(Modifier.padding(start = 4.dp).testTag("back_hint").clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) { InputKeycap(it); Text("Back", color = tokens.colors.secondary, style = tokens.typography.labelSmall) } }
                    }
                    TextButton({ chapters = true }, enabled = session != null) { Text("Chapters") }
                    TextButton({ appearance = true }, enabled = session != null) { Text("Appearance") }
                }
                if (session != null && item != null) {
                    EpubSurface(this@EpubActivity, session, item.locator, state.preferences, tokens.dark, item.category, controller,
                        Modifier.weight(1f).fillMaxWidth().testTag("epub_page")
                            // Center-tap-to-toggle is unchanged; this only adds an accessibility action, exposed
                            // exclusively while chrome is hidden, so TalkBack's instruction matches a real action.
                            .semantics {
                                if (controls) stateDescription = "Controls shown"
                                else {
                                    stateDescription = "Controls hidden"
                                    onClick(label = "Show reader controls") { controls = true; controlFocusRequests++; true }
                                }
                            },
                        onCenterTap = { modality = InputModality.TOUCH; controls = !controls }, onLocation = vm::location)
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
                            TextButton(controller::previous, Modifier.let { m -> previousHint?.let { m.semantics { contentDescription = "Previous, $it" } } ?: m }) {
                                previousHint?.let { InputKeycap(it, Modifier.padding(end = 4.dp)) }; Text("Previous")
                            }
                            Text("${item?.progress ?: 0}%", Modifier.align(Alignment.CenterVertically),
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                            TextButton(controller::next, Modifier.let { m -> nextHint?.let { m.semantics { contentDescription = "Next, $it" } } ?: m }) {
                                Text("Next"); nextHint?.let { InputKeycap(it, Modifier.padding(start = 4.dp)) }
                            }
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
