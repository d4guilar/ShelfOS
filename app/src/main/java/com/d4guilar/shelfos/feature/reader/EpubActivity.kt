// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.d4guilar.shelfos.ShelfApplication
import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.core.theme.*
import com.d4guilar.shelfos.domain.library.*

/** Reconstruct the asynchronous navigator from the persisted locator, not FragmentManager state. */
class EpubActivity : AppCompatActivity() {
    private var handleReaderKey: ((KeyEvent) -> Boolean)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        enableEdgeToEdge()
        val itemId = intent.getStringExtra("itemId") ?: run { finish(); return }
        val container = (application as ShelfApplication).container
        setContent {
            val vm: EpubReaderViewModel = viewModel(factory = viewModelFactory { initializer {
                EpubReaderViewModel(itemId, container.library, container.epubs, container.backgroundScope)
            } })
            val state by vm.state.collectAsStateWithLifecycle()
            val theme by container.themes.theme.collectAsStateWithLifecycle(ThemeId.CLASSIC)
            val controller = remember { EpubController() }
            var appearance by rememberSaveable { mutableStateOf(false) }
            var chapters by rememberSaveable { mutableStateOf(false) }
            ShelfTheme(theme) {
                val tokens = LocalShelfTokens.current
                val item = state.item
                val session = state.session
                SideEffect {
                    handleReaderKey = { event ->
                        val command = event.shelfCommand(InputContext.READER,
                            readingDirection(item?.category ?: MediaCategory.BOOK, state.preferences.direction) == ReadingDirection.RTL)
                        if (command in setOf(ShelfCommand.NEXT_PAGE, ShelfCommand.PREVIOUS_PAGE, ShelfCommand.OPEN_MENU, ShelfCommand.BACK)) {
                            if (event.action == KeyEvent.ACTION_UP) when (command) {
                                ShelfCommand.NEXT_PAGE -> controller.next()
                                ShelfCommand.PREVIOUS_PAGE -> controller.previous()
                                ShelfCommand.OPEN_MENU -> appearance = !appearance
                                else -> finish()
                            }
                            true
                        } else false
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("epub_reader")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ finish() }) { Text("Library") }
                            TextButton({ chapters = true }, enabled = session != null) { Text("Chapters") }
                            TextButton({ appearance = true }, enabled = session != null) { Text("Appearance") }
                        }
                        if (session != null && item != null) {
                            EpubSurface(this@EpubActivity, session, item.locator, state.preferences,
                                tokens.dark, item.category, controller, Modifier.weight(1f).fillMaxWidth(), vm::location)
                        } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (state.error == null) CircularProgressIndicator() else Text(state.error.orEmpty(), Modifier.padding(24.dp))
                        }
                        if (session != null) {
                            state.error?.let { Text(it, Modifier.padding(8.dp)) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(controller::previous) { Text("Previous") }
                                Text("${item?.progress ?: 0}%", Modifier.align(Alignment.CenterVertically))
                                TextButton(controller::next) { Text("Next") }
                            }
                        }
                    }
                }
                if (appearance) ReaderAppearance(state.preferences, true, { appearance = false }, vm::preferences)
                if (chapters && session != null) AlertDialog(onDismissRequest = { chapters = false }, title = { Text("Chapters") }, text = {
                    LazyColumn { items(session.chapters) { (title, href) -> TextButton({ controller.chapter(session, href); chapters = false }) { Text(title) } } }
                }, confirmButton = { TextButton({ chapters = false }) { Text("Close") } })
            }
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus is WebView && handleReaderKey?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}
