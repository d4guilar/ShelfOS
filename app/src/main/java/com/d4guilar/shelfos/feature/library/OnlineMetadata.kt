// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.data.metadata.ApplyOutcome
import com.d4guilar.shelfos.data.metadata.LookupMode
import com.d4guilar.shelfos.data.metadata.LookupOutcome
import com.d4guilar.shelfos.data.metadata.ManualMetadataLookup
import com.d4guilar.shelfos.data.metadata.ReviewCandidate
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibrary
import com.d4guilar.shelfos.data.preferences.OnlineMetadataSettings
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.metadata.MatchConfidence
import com.d4guilar.shelfos.domain.metadata.MatchSignal
import com.d4guilar.shelfos.domain.metadata.MetadataField
import com.d4guilar.shelfos.domain.metadata.ProviderFailureKind
import com.d4guilar.shelfos.feature.reader.activateOnGamepadConfirm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the UI needs for the optional online metadata feature (Phase 4.5C.2A): consent state and the manual lookup. Null means the feature is not wired. */
class OnlineMetadataAccess(val settings: OnlineMetadataSettings, val lookup: ManualMetadataLookup)

val LocalOnlineMetadata = staticCompositionLocalOf<OnlineMetadataAccess?> { null }

sealed interface OnlineMetadataUi {
    data object Closed : OnlineMetadataUi
    /** Open Library is off: nothing has been sent, and nothing is until the user enables it here. */
    data object Disclosure : OnlineMetadataUi
    data class Loading(val mode: LookupMode) : OnlineMetadataUi
    data class Results(val candidates: List<ReviewCandidate>, val mode: LookupMode) : OnlineMetadataUi
    data class Empty(val mode: LookupMode) : OnlineMetadataUi
    data class Error(val kind: ProviderFailureKind, val retryAfterMs: Long?, val mode: LookupMode) : OnlineMetadataUi
    data object Applying : OnlineMetadataUi
    data class Done(val changed: List<MetadataField>) : OnlineMetadataUi
    data object NothingChanged : OnlineMetadataUi
    data object Gone : OnlineMetadataUi
}

/**
 * Holds one manual lookup. A request starts only from [open] (when already enabled), [enableAndSearch] (after the disclosure),
 * [searchByTitle] or [retry], each a user action. Switching the provider off cancels the work and discards the results.
 */
class OnlineMetadataViewModel(private val access: OnlineMetadataAccess) : ViewModel() {
    private val _ui = MutableStateFlow<OnlineMetadataUi>(OnlineMetadataUi.Closed)
    val ui: StateFlow<OnlineMetadataUi> = _ui.asStateFlow()
    private var job: Job? = null
    private var item: LibraryItem? = null
    private var lastMode = LookupMode.ISBN

    init {
        viewModelScope.launch {
            combine(access.settings.onlineFlow, access.settings.providersFlow) { online, providers -> online && OpenLibrary.ID in providers }
                .distinctUntilChanged().collect { enabled ->
                    if (!enabled && _ui.value !is OnlineMetadataUi.Disclosure) { job?.cancel(); _ui.value = OnlineMetadataUi.Closed }
                }
        }
    }

    private val enabled get() = access.settings.onlineEnabled && access.settings.providerEnabled(OpenLibrary.ID)

    /** The "Find metadata online" action. Disabled → the disclosure, and no request. */
    fun open(item: LibraryItem) {
        this.item = item
        if (enabled) start(LookupMode.ISBN, autoSearchWithoutIsbn = true) else { job?.cancel(); _ui.value = OnlineMetadataUi.Disclosure }
    }

    /** "Enable and search": the affirmative consent. Only now are the switches turned on and the original lookup run. */
    fun enableAndSearch() {
        if (_ui.value !is OnlineMetadataUi.Disclosure) return
        access.settings.enableProvider(OpenLibrary.ID)
        start(LookupMode.ISBN, autoSearchWithoutIsbn = true)
    }

    fun searchByTitle() = start(LookupMode.SEARCH, autoSearchWithoutIsbn = false)
    fun retry() = start(lastMode, autoSearchWithoutIsbn = lastMode == LookupMode.ISBN)

    fun close() { job?.cancel(); _ui.value = OnlineMetadataUi.Closed }

    fun apply(review: ReviewCandidate) {
        val target = item ?: return
        if (!review.applicable) return
        job?.cancel()
        _ui.value = OnlineMetadataUi.Applying
        job = viewModelScope.launch {
            _ui.value = try {
                when (val o = access.lookup.apply(target, review)) {
                    is ApplyOutcome.Applied -> OnlineMetadataUi.Done(o.changed)
                    ApplyOutcome.NothingChanged, ApplyOutcome.NotApplicable -> OnlineMetadataUi.NothingChanged
                    ApplyOutcome.ItemGone -> OnlineMetadataUi.Gone
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { OnlineMetadataUi.NothingChanged }
        }
    }

    private fun start(mode: LookupMode, autoSearchWithoutIsbn: Boolean) {
        val target = item ?: return
        if (!enabled) { _ui.value = OnlineMetadataUi.Disclosure; return }
        job?.cancel()
        lastMode = mode
        _ui.value = OnlineMetadataUi.Loading(mode)
        job = viewModelScope.launch {
            var m = mode
            var outcome = find(target, m)
            if (outcome is LookupOutcome.NoIdentifier && autoSearchWithoutIsbn) {
                m = LookupMode.SEARCH; lastMode = m; _ui.value = OnlineMetadataUi.Loading(m)
                outcome = find(target, m)
            }
            // Switched off while the request ran: whatever came back is discarded.
            if (!enabled) { _ui.value = OnlineMetadataUi.Closed; return@launch }
            _ui.value = when (outcome) {
                is LookupOutcome.Found -> OnlineMetadataUi.Results(outcome.candidates, outcome.mode)
                is LookupOutcome.NoMatch -> OnlineMetadataUi.Empty(outcome.mode)
                LookupOutcome.NoIdentifier -> OnlineMetadataUi.Empty(m)
                LookupOutcome.Unavailable -> OnlineMetadataUi.Error(ProviderFailureKind.POLICY_DISABLED, null, m)
                is LookupOutcome.Failed -> OnlineMetadataUi.Error(outcome.kind, outcome.retryAfterMs, m)
                LookupOutcome.ItemGone -> OnlineMetadataUi.Gone
            }
        }
    }

    private suspend fun find(item: LibraryItem, mode: LookupMode): LookupOutcome = try {
        access.lookup.find(item, mode)
    } catch (e: CancellationException) { throw e } catch (_: Exception) { LookupOutcome.Failed(ProviderFailureKind.MALFORMED, null) }
}

@Composable
fun rememberOnlineMetadata(access: OnlineMetadataAccess, itemId: String): OnlineMetadataViewModel =
    viewModel(key = "online_metadata_$itemId", factory = viewModelFactory { initializer { OnlineMetadataViewModel(access) } })

@Composable
fun OnlineMetadataDialog(vm: OnlineMetadataViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state = ui
    if (state is OnlineMetadataUi.Closed) return
    val t = LocalShelfTokens.current
    val close = { vm.close() }
    // Keyboard/gamepad users get a visible, safe starting point on Cancel (never on the consent button); touch is unaffected.
    val cancelFocus = remember { FocusRequester() }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(state is OnlineMetadataUi.Disclosure, keyboardMode) {
        if (state is OnlineMetadataUi.Disclosure && keyboardMode) runCatching { cancelFocus.requestFocus() }
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(if (state is OnlineMetadataUi.Disclosure) R.string.online_disclosure_title else R.string.online_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("online_dialog"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    OnlineMetadataUi.Closed -> Unit
                    OnlineMetadataUi.Disclosure -> {
                        Text(stringResource(R.string.provider_openlibrary_disclosure))
                        Text(stringResource(R.string.online_disclosure_extra), color = t.colors.secondary)
                    }
                    is OnlineMetadataUi.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(if (state.mode == LookupMode.ISBN) R.string.online_loading_isbn else R.string.online_loading_search), Modifier.testTag("online_loading"))
                    }
                    is OnlineMetadataUi.Results -> {
                        Text(stringResource(R.string.online_results_intro), color = t.colors.secondary)
                        state.candidates.forEachIndexed { i, c -> CandidateCard(c, i) { vm.apply(c) } }
                        Text(stringResource(R.string.provider_openlibrary_attribution), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
                    }
                    is OnlineMetadataUi.Empty -> Text(stringResource(R.string.online_no_match), Modifier.testTag("online_no_match"))
                    is OnlineMetadataUi.Error -> Text(stringResource(errorRes(state.kind)), Modifier.testTag("online_error"))
                    OnlineMetadataUi.Applying -> CircularProgressIndicator(Modifier.size(24.dp))
                    is OnlineMetadataUi.Done -> Text(stringResource(R.string.online_applied, state.changed.map { stringResource(fieldRes(it)) }.joinToString(", ")), Modifier.testTag("online_done"))
                    OnlineMetadataUi.NothingChanged -> Text(stringResource(R.string.online_nothing_changed), Modifier.testTag("online_unchanged"))
                    OnlineMetadataUi.Gone -> Text(stringResource(R.string.online_gone))
                }
            }
        },
        confirmButton = {
            when (state) {
                OnlineMetadataUi.Disclosure -> TextButton(vm::enableAndSearch, Modifier.testTag("online_enable_search").activateOnGamepadConfirm(onActivate = vm::enableAndSearch)) {
                    Text(stringResource(R.string.online_enable_and_search))
                }
                is OnlineMetadataUi.Empty -> if (state.mode == LookupMode.ISBN) TextButton(vm::searchByTitle, Modifier.testTag("online_search_title").activateOnGamepadConfirm(onActivate = vm::searchByTitle)) {
                    Text(stringResource(R.string.online_search_by_title))
                }
                is OnlineMetadataUi.Error -> if (state.kind != ProviderFailureKind.POLICY_DISABLED && state.kind != ProviderFailureKind.ACCESS_BLOCKED)
                    TextButton(vm::retry, Modifier.testTag("online_retry").activateOnGamepadConfirm(onActivate = vm::retry)) { Text(stringResource(R.string.online_retry)) }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(close, Modifier.focusRequester(cancelFocus).testTag("online_cancel").activateOnGamepadConfirm(onActivate = close)) {
                Text(stringResource(if (state is OnlineMetadataUi.Disclosure || state is OnlineMetadataUi.Loading) R.string.action_cancel else R.string.action_close))
            }
        })
}

@Composable
private fun CandidateCard(c: ReviewCandidate, index: Int, onApply: () -> Unit) {
    val t = LocalShelfTokens.current
    Surface(color = t.colors.muted, shape = t.shapes.small, modifier = Modifier.fillMaxWidth().testTag("online_candidate_$index")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.candidate.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
            c.candidate.creator?.let { Text(it) }
            val v = c.candidate.values
            val isbns = v.identifiers.mapNotNull { it.isbn13 }.distinct()
            if (isbns.isNotEmpty()) Text(stringResource(R.string.online_isbn_line, isbns.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            listOfNotNull(v.publisher, v.publishedDate, v.language).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(when {
                metadataIsbnLabel(c.tier, c.agreements, c.contradictions, c.rejected) == IsbnLabel.ISBN_MATCHES -> R.string.online_confidence_exact
                metadataIsbnLabel(c.tier, c.agreements, c.contradictions, c.rejected) == IsbnLabel.ISBN_MATCHES_VERIFY -> R.string.online_confidence_isbn_verify
                c.applicable -> R.string.online_confidence_likely
                else -> R.string.online_confidence_none
            }), style = MaterialTheme.typography.labelMedium)
            if (c.workLevel) Text(stringResource(R.string.online_candidate_work), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
            val conflicts = c.contradictions.filter { it != MatchSignal.AMBIGUOUS || c.contradictions.size == 1 }
            if (conflicts.isNotEmpty()) Text(stringResource(R.string.online_conflicts, conflicts.map { stringResource(signalRes(it)) }.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            if (c.applicable) {
                if (c.changes.isEmpty()) Text(stringResource(R.string.online_nothing_to_change), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
                else Text(stringResource(R.string.online_will_update, c.changes.map { stringResource(fieldRes(it.field)) }.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onApply, Modifier.testTag("online_apply_$index").activateOnGamepadConfirm(c.applicable && c.changes.isNotEmpty(), onApply), enabled = c.applicable && c.changes.isNotEmpty()) {
                Text(stringResource(R.string.online_apply))
            }
        }
    }
}

private fun errorRes(kind: ProviderFailureKind) = when (kind) {
    ProviderFailureKind.OFFLINE -> R.string.online_error_offline
    ProviderFailureKind.TIMEOUT -> R.string.online_error_timeout
    ProviderFailureKind.RATE_LIMITED, ProviderFailureKind.CIRCUIT_OPEN -> R.string.online_error_rate_limited
    ProviderFailureKind.ACCESS_BLOCKED -> R.string.online_error_blocked
    ProviderFailureKind.SERVER_FAILURE -> R.string.online_error_server
    ProviderFailureKind.MALFORMED -> R.string.online_error_malformed
    ProviderFailureKind.BUSY -> R.string.online_error_busy
    ProviderFailureKind.POLICY_DISABLED -> R.string.online_error_disabled
}

private fun fieldRes(f: MetadataField) = when (f) {
    MetadataField.TITLE -> R.string.online_field_title
    MetadataField.CREATOR -> R.string.online_field_creator
    MetadataField.SUBTITLE -> R.string.metadata_subtitle
    MetadataField.DESCRIPTION -> R.string.metadata_description
    MetadataField.PUBLISHER -> R.string.metadata_publisher
    MetadataField.PUBLISHED_DATE -> R.string.metadata_published
    MetadataField.LANGUAGE -> R.string.metadata_language
    MetadataField.SERIES -> R.string.metadata_series
    MetadataField.VOLUME -> R.string.metadata_volume
    MetadataField.ISSUE -> R.string.metadata_issue
    MetadataField.IDENTIFIERS -> R.string.metadata_identifiers
    MetadataField.CREDITS -> R.string.metadata_credits
}

private fun signalRes(s: MatchSignal) = when (s) {
    MatchSignal.IDENTIFIER -> R.string.online_signal_identifier
    MatchSignal.CATEGORY -> R.string.online_signal_category
    MatchSignal.TITLE -> R.string.online_signal_title
    MatchSignal.CREATOR -> R.string.online_signal_creator
    MatchSignal.SERIES -> R.string.online_signal_series
    MatchSignal.VOLUME -> R.string.online_signal_volume
    MatchSignal.ISSUE -> R.string.online_signal_issue
    MatchSignal.PUBLISHER -> R.string.online_signal_publisher
    MatchSignal.YEAR -> R.string.online_signal_year
    MatchSignal.LANGUAGE -> R.string.online_signal_language
    MatchSignal.EDITION -> R.string.online_signal_edition
    MatchSignal.AMBIGUOUS -> R.string.online_signal_ambiguous
}
