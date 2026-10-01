// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.ShelfApplication
import com.d4guilar.shelfos.AppContainer
import com.d4guilar.shelfos.core.designsystem.InputKeycap
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.designsystem.UiMessageSaver
import com.d4guilar.shelfos.core.designsystem.formatPercent
import com.d4guilar.shelfos.core.designsystem.resolve
import com.d4guilar.shelfos.core.designsystem.toUiMessage
import com.d4guilar.shelfos.core.input.*
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.core.theme.*
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.launch

/** Below this TOC size, a filter field adds a control without saving meaningful scanning effort. */
private const val CHAPTER_FILTER_THRESHOLD = 8

/**
 * Hosts Readium's fragment-based navigator. The navigator is rebuilt from the persisted locator rather than
 * restored from FragmentManager state, so rotation and process recreation resume the saved position. The rest of
 * the saved state restores normally: controls, open dialogs and unapplied Appearance changes survive recreation.
 */
open class EpubActivity : AppCompatActivity() {
    private var readerKeys: ((KeyEvent) -> Boolean)? = null

    /** Variant/test hosts may replace only the existing ShelfOS cursor boundary; release behavior uses Readium. */
    protected open fun createSearchCursorOpener(): EpubSearchCursorOpener =
        { session, query -> session.search(query) }

    /** Debug/test hosts can replace only the reader factory; release uses the application container. */
    protected open fun createEpubReaderFactory(container: com.d4guilar.shelfos.AppContainer): EpubReaderFactory = container.epubs

    override fun onCreate(savedInstanceState: Bundle?) {
        restoreEpubNavigatorAsPlaceholder()
        super.onCreate(savedInstanceState)
        removeRestoredEpubNavigator()
        enableEdgeToEdge()
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: run { finish(); return }
        val container = (application as ShelfApplication).container
        // Resolve once without retaining this Activity in the configuration-surviving ViewModel.
        val searchCursorOpener = createSearchCursorOpener()
        setContent {
            val vm: EpubReaderViewModel = viewModel(factory = viewModelFactory { initializer {
                EpubReaderViewModel(itemId, container.library, container.library, createEpubReaderFactory(container),
                    container.backgroundScope, searchCursorOpener)
            } })
            val theme by container.themes.theme.collectAsStateWithLifecycle(initialValue = null as ThemeId?)
            // Wait for the saved theme instead of flashing Classic first.
            theme?.let { ShelfTheme(it) { EpubReaderContent(vm, container) } }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun EpubReaderContent(vm: EpubReaderViewModel, container: AppContainer) {
        val state by vm.state.collectAsStateWithLifecycle()
        val searchState by vm.searchState.collectAsStateWithLifecycle()
        val tokens = LocalShelfTokens.current
        val controller = remember { EpubController() }
        // Semantics lambdas below run outside composition, so these localized strings are resolved here.
        val controlsShownDescription = stringResource(R.string.content_desc_controls_shown)
        val controlsHiddenDescription = stringResource(R.string.content_desc_controls_hidden)
        val showControlsActionLabel = stringResource(R.string.action_show_controls)
        val previousLabel = stringResource(R.string.content_desc_previous_hint)
        val nextLabel = stringResource(R.string.content_desc_next_hint)
        fun previousHintDescription(hint: String) = String.format(previousLabel, hint)
        fun nextHintDescription(hint: String) = String.format(nextLabel, hint)
        val fontRemoveFailedMessage = UiMessage.Resource(R.string.font_remove_failed)
        val filterChaptersDescription = stringResource(R.string.content_desc_filter_chapters)
        val searchThisPublicationLabel = stringResource(R.string.search_this_publication)
        val searchingDescription = stringResource(R.string.content_desc_searching)
        val noSearchResultsDescription = stringResource(R.string.content_desc_no_search_results)
        val searchResultOpenFailedMessage = stringResource(R.string.search_result_open_failed)
        val searchMatchFallback = stringResource(R.string.search_match_fallback)
        val progressVisualTemplate = stringResource(R.string.reading_progress_visual)
        val progressSpokenTemplate = stringResource(R.string.reading_progress_spoken)
        val bookmarkLocationTemplate = stringResource(R.string.bookmark_location)
        val searchResultDescriptionTemplate = stringResource(R.string.content_desc_search_result)
        fun progressVisual(percent: Int) = String.format(progressVisualTemplate, percent)
        fun progressSpoken(percent: Int) = String.format(progressSpokenTemplate, percent)
        fun locationPhrase(location: Int) = String.format(bookmarkLocationTemplate, location)
        var controls by rememberSaveable { mutableStateOf(true) }
        var appearance by rememberSaveable { mutableStateOf(false) }
        var chapters by rememberSaveable { mutableStateOf(false) }
        var bookmarks by rememberSaveable { mutableStateOf(false) }
        var search by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }
        var fontImportError by rememberSaveable(stateSaver = UiMessageSaver) { mutableStateOf<UiMessage?>(null) }
        val fontFamilies by container.fonts.families.collectAsStateWithLifecycle()
        val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    try {
                        container.fonts.import(it)
                        fontImportError = null
                        // The navigator configuration declares the catalog available when its session opens.
                        // Recreate once so a newly imported family can be selected immediately in this dialog.
                        recreate()
                    } catch (error: FontImportException) {
                        fontImportError = error.toUiMessage()
                    }
                }
            }
        }
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
        // Phase 2B.1: the current chapter is derived from the live locator, never stored on its own (AGENTS.md's
        // "not independently stored" contract) — it recomputes on every position update and on recreation once
        // the navigator reports its restored position again, exactly like `item.progress` already does via Room.
        // Compared by EpubChapter's stable row id, not href: two TOC rows can share one href (a redundant TOC
        // entry, or two entries with the same fragment), and href-equality would then mark both "current".
        var currentLocatorJson by remember { mutableStateOf<String?>(null) }
        val currentChapterId = remember(session, currentLocatorJson) { session?.currentChapterId(currentLocatorJson) }
        // Readium can enrich the live locator after a bookmark is stored without moving the reader. Compare the
        // stable location fields instead of the entire serialized snapshot. The DAO's exact duplicate guard is
        // deliberately separate from this live UI state. Phase 2B.2.2: keeping the matched Bookmark itself (not
        // just whether one exists) lets the current-position control remove exactly that row, not merely disable.
        val currentBookmark = remember(currentLocatorJson, state.bookmarks) {
            currentLocatorJson?.let { live -> state.bookmarks.find { sameEpubBookmarkLocation(it.locator, live) } }
        }
        val currentlyBookmarked = currentBookmark != null
        val rtl = readingDirection(item?.category ?: MediaCategory.BOOK, state.preferences.direction) == ReadingDirection.RTL
        val previousHint = InputHints.hint(ShelfCommand.PREVIOUS_PAGE, modality, rtl)
        val nextHint = InputHints.hint(ShelfCommand.NEXT_PAGE, modality, rtl)
        val backHint = InputHints.hint(ShelfCommand.BACK, modality, rtl)
        // Back never leaves the reader from hidden chrome: it reveals controls first, then a second Back exits.
        fun backPress() { if (controls) finish() else { controls = true; controlFocusRequests++ } }
        fun closeSearch() { search = false; searchQuery = ""; vm.clearSearch() }

        // A configuration change disposes this Activity composition while retaining its ViewModel. Closing here
        // ensures the old session-bound iterator cannot continue across recreation; rememberSaveable restores the
        // plain query and the new composition deliberately reruns it against the same freshly attached session.
        DisposableEffect(vm) { onDispose { vm.clearSearch() } }

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
                when (val command = event.readerCommand(rtl, search || (controls && (topFocused || bottomFocused)))) {
                    ShelfCommand.NEXT_PAGE, ShelfCommand.PREVIOUS_PAGE, ShelfCommand.OPEN_MENU, ShelfCommand.BACK,
                    ShelfCommand.SEARCH -> {
                        if (event.action == KeyEvent.ACTION_UP) when (command) {
                            ShelfCommand.NEXT_PAGE -> controller.next()
                            ShelfCommand.PREVIOUS_PAGE -> controller.previous()
                            ShelfCommand.OPEN_MENU -> if (controls) controls = false else { controls = true; controlFocusRequests++ }
                            ShelfCommand.SEARCH -> { controls = true; search = true }
                            else -> if (search) closeSearch() else backPress()
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
                if (controls) FlowRow(Modifier.fillMaxWidth().onFocusChanged { topFocused = it.hasFocus },
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ finish() }, Modifier.focusRequester(firstControl).testTag("epub_library")) { Text(stringResource(R.string.nav_library)) }
                        // Input-discovery hint (Phase 2A.1): decorative only, since no single existing control is
                        // exactly "Back" to merge this into; the system Back gesture/button remains self-describing.
                        backHint?.let { Row(Modifier.padding(start = 4.dp).testTag("back_hint").clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) { InputKeycap(it); Text(stringResource(R.string.action_back), color = tokens.colors.secondary, style = tokens.typography.labelSmall) } }
                    }
                    TextButton({ chapters = true }, enabled = session != null) { Text(stringResource(R.string.action_chapters)) }
                    TextButton({ bookmarks = true }, enabled = session != null) { Text(stringResource(R.string.action_bookmarks)) }
                    TextButton({ search = true }, enabled = session != null) { Text(stringResource(R.string.nav_search)) }
                    TextButton({ appearance = true }, enabled = session != null) { Text(stringResource(R.string.action_appearance)) }
                }
                if (session != null && item != null) {
                    EpubSurface(this@EpubActivity, session, item.locator, state.preferences, tokens.dark, item.category, controller,
                        Modifier.weight(1f).fillMaxWidth().testTag("epub_page")
                            // Center-tap-to-toggle is unchanged; this only adds an accessibility action, exposed
                            // exclusively while chrome is hidden, so TalkBack's instruction matches a real action.
                            .semantics {
                                if (controls) stateDescription = controlsShownDescription
                                else {
                                    stateDescription = controlsHiddenDescription
                                    onClick(label = showControlsActionLabel) { controls = true; controlFocusRequests++; true }
                                }
                            },
                        onCenterTap = { modality = InputModality.TOUCH; controls = !controls },
                        onLocation = { locator, progress -> vm.location(locator, progress); currentLocatorJson = locator })
                } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val error = state.error
                    if (error == null) CircularProgressIndicator()
                    else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error.resolve())
                        TextButton({ finish() }) { Text(stringResource(R.string.action_back_to_library)) }
                    }
                }
                if (session != null) {
                    state.error?.let { Text(it.resolve(), Modifier.padding(8.dp)) }
                    // Page controls follow the reading direction: in right-to-left reading, Next sits on the left.
                    if (controls) CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxWidth().onFocusChanged { bottomFocused = it.hasFocus }, horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(controller::previous, Modifier.let { m -> previousHint?.let { m.semantics { contentDescription = previousHintDescription(it) } } ?: m }) {
                                previousHint?.let { InputKeycap(it, Modifier.padding(end = 4.dp)) }; Text(stringResource(R.string.action_previous))
                            }
                            Text(formatPercent(item?.progress ?: 0), Modifier.align(Alignment.CenterVertically),
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                            TextButton(controller::next, Modifier.let { m -> nextHint?.let { m.semantics { contentDescription = nextHintDescription(it) } } ?: m }) {
                                Text(stringResource(R.string.action_next)); nextHint?.let { InputKeycap(it, Modifier.padding(start = 4.dp)) }
                            }
                        }
                    }
                }
            }
        }
        if (appearance && item != null) ReaderAppearance(state.preferences, capabilities(item.format), { appearance = false },
            vm::applyAppearance, vm::resetAppearance, fontFamilies, fontImportError,
            onImportFont = {
                fontImportError = null
                importFont.launch(arrayOf("font/ttf", "font/otf", "application/vnd.ms-opentype", "application/octet-stream"))
            },
            onRemoveFont = { familyId ->
                lifecycleScope.launch {
                    if (container.fonts.remove(familyId)) { fontImportError = null; recreate() }
                    else fontImportError = fontRemoveFailedMessage
                }
            })
        if (chapters && session != null) {
            // Reset on each open (this state lives inside the dialog's own composition, discarded when the
            // dialog closes) but preserved by rememberSaveable while the dialog stays open across recreation.
            var filter by rememberSaveable { mutableStateOf("") }
            val query = filter.trim()
            val visible = if (query.isEmpty()) session.chapters
                else session.chapters.filter { it.title.contains(query, ignoreCase = true) }
            val currentChapterDescription = stringResource(R.string.content_desc_current_chapter)
            val noChaptersMatchTemplate = stringResource(R.string.chapters_no_match)
            AlertDialog(onDismissRequest = { chapters = false }, title = { Text(stringResource(R.string.action_chapters)) }, text = {
                Column {
                    // Only worth the extra control on a TOC long enough that scanning it visually is a chore.
                    if (session.chapters.size > CHAPTER_FILTER_THRESHOLD) OutlinedTextField(filter, { filter = it },
                        Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics { contentDescription = filterChaptersDescription },
                        placeholder = { Text(stringResource(R.string.filter_chapters_placeholder)) }, singleLine = true)
                    if (visible.isEmpty()) Text(String.format(noChaptersMatchTemplate, query), Modifier.padding(vertical = 16.dp))
                    else LazyColumn(Modifier.testTag("chapters_list")) { items(visible) { chapter ->
                        val current = chapter.id == currentChapterId
                        // The checkmark is its own Text node (not interpolated into the title string) so the
                        // chapter's own title remains exact-matchable by anything that looks it up by title,
                        // current or not — matching how the existing Previous/Next hint keycaps sit beside their
                        // own Text rather than being folded into it.
                        TextButton({ controller.chapter(session, chapter.href); chapters = false },
                            Modifier.padding(start = (chapter.depth * 16).dp)
                                .semantics { if (current) contentDescription = String.format(currentChapterDescription, chapter.title) }) {
                            if (current) Text("✓ ")
                            Text(chapter.title, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                        }
                    } }
                }
            }, confirmButton = { TextButton({ chapters = false }) { Text(stringResource(R.string.action_close)) } })
        }
        if (search && session != null) {
            val searchField = remember { FocusRequester() }
            var jumpError by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(searchQuery, session) { vm.search(session, searchQuery) }
            val searchPromptEmpty = stringResource(R.string.search_prompt_empty)
            val noResultsTemplate = stringResource(R.string.search_no_results)
            AlertDialog(onDismissRequest = ::closeSearch, title = { Text(searchThisPublicationLabel) }, text = {
                // A Dialog owns a separate window. Request focus only after that window reports focus; requesting
                // during its first composition can be dropped on a cold launch before the window is attached.
                val searchWindow = LocalWindowInfo.current
                LaunchedEffect(searchWindow.isWindowFocused) {
                    if (searchWindow.isWindowFocused) searchField.requestFocus()
                }
                val normalizedQuery = normalizeSearchQuery(searchQuery)
                // LaunchedEffect submits a replacement after composition. Until its state arrives, suppress the
                // prior query's rows so they cannot flash under the newly typed query even for a single frame.
                val visibleSearchState = searchState.takeIf { it.query == normalizedQuery }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(searchQuery, { searchQuery = it; jumpError = null },
                        Modifier.fillMaxWidth().focusRequester(searchField)
                            .semantics { contentDescription = searchThisPublicationLabel },
                        label = { Text(searchThisPublicationLabel) }, singleLine = true,
                        trailingIcon = { if (searchQuery.isNotEmpty()) TextButton({ searchQuery = "" }) { Text(stringResource(R.string.action_clear)) } })
                    jumpError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    when {
                        searchQuery.isBlank() -> Text(searchPromptEmpty, Modifier.padding(vertical = 16.dp))
                        visibleSearchState == null -> CircularProgressIndicator(
                            Modifier.align(Alignment.CenterHorizontally)
                                .semantics { contentDescription = searchingDescription })
                        visibleSearchState.error != null -> Text(visibleSearchState.error.resolve(), Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.error)
                        visibleSearchState.complete && visibleSearchState.results.isEmpty() -> Text(String.format(noResultsTemplate, normalizedQuery),
                            Modifier.padding(vertical = 16.dp).semantics { contentDescription = noSearchResultsDescription })
                        else -> {
                            if (visibleSearchState.loading && visibleSearchState.results.isEmpty()) CircularProgressIndicator(
                                Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = searchingDescription })
                            if (visibleSearchState.results.isNotEmpty()) LazyColumn(
                                Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("search_results")) {
                                items(visibleSearchState.results) { result ->
                                    val accessible = searchResultAccessibilityText(result, searchMatchFallback, ::progressSpoken)
                                    TextButton(onClick = {
                                        jumpError = null
                                        if (controller.goTo(result.locator)) closeSearch()
                                        else jumpError = searchResultOpenFailedMessage
                                    }, modifier = Modifier.fillMaxWidth().testTag("search_result")
                                        .semantics { contentDescription = String.format(searchResultDescriptionTemplate, accessible) }) {
                                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                            result.title?.let { Text(it, fontWeight = FontWeight.Bold) }
                                            Text(buildAnnotatedString {
                                                val before = normalizeSearchText(result.before)
                                                val highlight = normalizeSearchText(result.highlight)
                                                val after = normalizeSearchText(result.after)
                                                append(before)
                                                if (before.isNotEmpty() && highlight.isNotEmpty() && result.before.lastOrNull()?.isWhitespace() == true) append(' ')
                                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(highlight) }
                                                if (highlight.isNotEmpty() && after.isNotEmpty() && result.after.firstOrNull()?.isWhitespace() == true) append(' ')
                                                append(after)
                                            })
                                            result.progression?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let {
                                                Text(progressVisual((it * 100).toInt()), color = tokens.colors.secondary,
                                                    style = tokens.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton(::closeSearch) { Text(stringResource(R.string.action_close)) } })
        }
        if (bookmarks && session != null) {
            // Reset on each open, like the Chapters dialog's filter — a stale failure message from a previous
            // open should not linger silently into the next one.
            var jumpError by rememberSaveable { mutableStateOf<String?>(null) }
            val addBookmarkUnavailableDescription = stringResource(R.string.content_desc_bookmark_add_unavailable)
            val removeBookmarkCurrentDescription = stringResource(R.string.content_desc_bookmark_remove_current)
            val addBookmarkCurrentDescription = stringResource(R.string.content_desc_bookmark_add_current)
            val bookmarkOpenFailedMessage = stringResource(R.string.bookmark_open_failed)
            val bookmarkRowTemplate = stringResource(R.string.content_desc_bookmark_row)
            val bookmarkDeleteTemplate = stringResource(R.string.content_desc_bookmark_delete)
            AlertDialog(onDismissRequest = { bookmarks = false }, title = { Text(stringResource(R.string.action_bookmarks)) }, text = {
                Column {
                    // Phase 2B.2.2: a real toggle, not a dead-end disabled state once bookmarked — removing the
                    // bookmark at the reader's current position no longer requires finding its row in the list.
                    TextButton(onClick = {
                        val bookmark = currentBookmark
                        if (bookmark != null) vm.deleteBookmark(bookmark.id)
                        else currentLocatorJson?.let { vm.addBookmark(it, locatorProgress(it)) }
                    }, enabled = currentLocatorJson != null,
                        modifier = Modifier.semantics { contentDescription = when {
                            currentLocatorJson == null -> addBookmarkUnavailableDescription
                            currentlyBookmarked -> removeBookmarkCurrentDescription
                            else -> addBookmarkCurrentDescription
                        } }) { Text(stringResource(if (currentlyBookmarked) R.string.bookmark_remove else R.string.bookmark_add)) }
                    jumpError?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                    if (state.bookmarks.isEmpty()) Text(stringResource(R.string.bookmarks_empty), Modifier.padding(vertical = 16.dp))
                    else LazyColumn(Modifier.testTag("bookmarks_list").padding(top = 8.dp)) {
                        items(state.bookmarks, key = { it.id }) { bookmark ->
                            val presentation = session.presentBookmark(bookmark.locator, bookmark.progress, state.epubPositions)
                            val display = bookmarkDisplayText(presentation, ::locationPhrase, ::progressVisual)
                            val accessible = bookmarkAccessibilityText(presentation, ::locationPhrase, ::progressSpoken)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                TextButton({
                                    jumpError = null
                                    if (controller.goTo(bookmark.locator)) bookmarks = false
                                    else jumpError = bookmarkOpenFailedMessage
                                }, Modifier.weight(1f).semantics { contentDescription = String.format(bookmarkRowTemplate, accessible) }) { Text(display) }
                                TextButton({ vm.deleteBookmark(bookmark.id) },
                                    Modifier.semantics { contentDescription = String.format(bookmarkDeleteTemplate, accessible) }) { Text(stringResource(R.string.action_delete)) }
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton({ bookmarks = false }) { Text(stringResource(R.string.action_close)) } })
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = readerKeys?.invoke(event) == true || super.dispatchKeyEvent(event)

    companion object {
        private const val EXTRA_ITEM_ID = "itemId"
        fun intent(context: Context, itemId: String): Intent = Intent(context, EpubActivity::class.java).putExtra(EXTRA_ITEM_ID, itemId)
    }
}
