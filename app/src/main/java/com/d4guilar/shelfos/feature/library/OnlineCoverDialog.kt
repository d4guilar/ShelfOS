// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.LocalCoverArtwork
import com.d4guilar.shelfos.core.designsystem.PublicationCover
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.data.library.OnlineCoverAccess
import com.d4guilar.shelfos.data.library.OnlineCoverFailure
import com.d4guilar.shelfos.data.metadata.openlibrary.CoverWarning
import com.d4guilar.shelfos.data.metadata.openlibrary.OnlineCoverCandidate
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.feature.reader.activateOnGamepadConfirm

internal fun onlineCoverFailureRes(f: OnlineCoverFailure) = when (f) {
    OnlineCoverFailure.NOT_AUTHORIZED -> R.string.cover_online_err_not_authorized
    OnlineCoverFailure.NO_ISBN -> R.string.cover_online_no_isbn
    OnlineCoverFailure.NOT_FOUND -> R.string.cover_online_err_not_found
    OnlineCoverFailure.OFFLINE -> R.string.cover_online_err_offline
    OnlineCoverFailure.TIMEOUT -> R.string.cover_online_err_timeout
    OnlineCoverFailure.RATE_LIMITED -> R.string.cover_online_err_rate_limited
    OnlineCoverFailure.ACCESS_BLOCKED -> R.string.cover_online_err_blocked
    OnlineCoverFailure.SERVER_FAILURE -> R.string.cover_online_err_server
    OnlineCoverFailure.MALFORMED -> R.string.cover_online_err_malformed
    OnlineCoverFailure.REDIRECT_BLOCKED -> R.string.cover_online_err_redirect
    OnlineCoverFailure.TOO_LARGE -> R.string.cover_online_err_too_large
    OnlineCoverFailure.NOT_AN_IMAGE -> R.string.cover_online_err_not_image
    OnlineCoverFailure.MIME_MISMATCH -> R.string.cover_online_err_mime
    OnlineCoverFailure.UNSUPPORTED_IMAGE -> R.string.cover_online_err_unsupported
    OnlineCoverFailure.INVALID_IMAGE -> R.string.cover_online_err_invalid
    OnlineCoverFailure.STORAGE_FULL -> R.string.cover_online_err_storage
    OnlineCoverFailure.BUDGET_EXHAUSTED -> R.string.cover_online_err_budget
    OnlineCoverFailure.BUSY -> R.string.cover_online_err_busy
    OnlineCoverFailure.ITEM_GONE -> R.string.cover_online_err_gone
    OnlineCoverFailure.STALE -> R.string.cover_online_err_stale
    OnlineCoverFailure.SUPERSEDED -> R.string.cover_online_err_superseded
    OnlineCoverFailure.FAILED -> R.string.cover_online_err_failed
}

@Composable
private fun PickerButton(text: String, tag: String, enabled: Boolean = true, modifier: Modifier = Modifier, description: String? = null, onClick: () -> Unit) {
    TextButton(onClick, modifier.testTag(tag).activateOnGamepadConfirm(enabled, onClick).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier), enabled = enabled) { Text(text) }
}

/**
 * Find cover online (Phase 4.5C.3A). Opening it sends nothing: first the disclosure (when a consent is missing), then an explicit Find covers, then a
 * candidate preview, then an explicit Use this cover. Cancel, Back and closing never apply a cover.
 */
@Composable
fun OnlineCoverDialog(item: LibraryItem, controller: OnlineCoverController, onDismiss: () -> Unit) {
    val t = LocalShelfTokens.current
    val access by controller.access.collectAsState()
    val ui by remember(item.id) { controller.ui(item.id) }.collectAsState()
    // Survives rotation and activity recreation: which candidate is open. The consent ticks are only a draft until Enable is pressed.
    var inspecting by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    var allowMetadata by rememberSaveable { mutableStateOf(false) }
    var allowCovers by rememberSaveable { mutableStateOf(false) }
    val close = { controller.cancel(item.id); onDismiss() }
    val available = access == OnlineCoverAccess.AVAILABLE
    val needsMetadata = access == OnlineCoverAccess.METADATA_OFF || access == OnlineCoverAccess.BOTH_OFF
    val needsCovers = access == OnlineCoverAccess.COVERS_OFF || access == OnlineCoverAccess.BOTH_OFF
    // Keyboard/gamepad users start on the safe button (Close), never on the consent button; touch is unaffected.
    val safeFocus = remember { FocusRequester() }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(available, keyboardMode) { if (!available && keyboardMode) runCatching { safeFocus.requestFocus() } }
    val busy = ui.discovering || ui.busyKey != null
    val open = ui.candidates?.firstOrNull { it.key == inspecting }

    AlertDialog(onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(if (available) R.string.cover_online_title else R.string.cover_online_consent_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("cover_online_dialog"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!available) {
                    Text(stringResource(R.string.cover_online_consent_body), Modifier.testTag("cover_online_disclosure"))
                    if (needsMetadata) ConsentRow(stringResource(R.string.cover_online_allow_metadata), allowMetadata, "cover_online_allow_metadata") { allowMetadata = it }
                    if (needsCovers) ConsentRow(stringResource(R.string.cover_online_allow_covers), allowCovers, "cover_online_allow_covers") { allowCovers = it }
                } else if (open != null) {
                    Inspector(item, controller, ui, open, busy, { inspecting = null })
                } else {
                    Text(stringResource(R.string.cover_online_intro), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
                    PickerButton(stringResource(R.string.cover_online_find), "cover_online_find", !busy) { controller.find(item) }
                    if (ui.discovering) Progress(R.string.cover_online_finding, "cover_online_finding")
                    ui.candidates?.let { list ->
                        if (list.isEmpty()) Text(stringResource(R.string.cover_online_none), Modifier.testTag("cover_online_none"))
                        list.forEachIndexed { i, c -> CandidateRow(item, controller, ui, c, i, list.size, busy) { inspecting = c.key; if (c.key !in ui.previews) controller.preview(item, c.key) } }
                    }
                }
                if (ui.saved) Text(stringResource(R.string.cover_online_saved), Modifier.testTag("cover_online_saved").semantics { liveRegion = LiveRegionMode.Polite })
                ui.failure?.let { f ->
                    Text(stringResource(onlineCoverFailureRes(f)), Modifier.testTag("cover_online_error").semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error)
                }
                if (available) Text(stringResource(R.string.provider_openlibrary_attribution), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
            }
        },
        confirmButton = {
            if (!available) PickerButton(stringResource(R.string.cover_online_enable), "cover_online_enable", (!needsMetadata || allowMetadata) && (!needsCovers || allowCovers)) {
                controller.enable(metadataToo = needsMetadata, covers = needsCovers)
            }
        },
        dismissButton = { PickerButton(stringResource(if (busy) R.string.action_cancel else R.string.action_close), "cover_online_close", modifier = Modifier.focusRequester(safeFocus), onClick = close) })
}

@Composable
private fun ConsentRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked, null)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun Progress(label: Int, tag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(label), Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** A candidate's artwork through the unchanged shared renderer, with the picker's own map provided for this subtree only. */
@Composable
private fun PickerArtwork(controller: OnlineCoverController, item: LibraryItem, key: String, tag: String, whole: Boolean = false, maxHeight: Int = 160) {
    val t = LocalShelfTokens.current
    CompositionLocalProvider(LocalCoverArtwork provides controller.artwork) {
        Box(Modifier.fillMaxWidth().testTag(tag), contentAlignment = Alignment.Center) {
            PublicationCover(controller.artworkId(item.id, key), item.title, Modifier.heightIn(max = maxHeight.dp), MediaCategory.BOOK, Modifier.border(1.dp, t.colors.divider), whole)
        }
    }
}

@Composable
private fun warningText(c: OnlineCoverCandidate): List<String> = c.warnings.map {
    stringResource(when (it) {
        CoverWarning.DIFFERENT_ISBN -> R.string.cover_online_warn_isbn
        CoverWarning.DIFFERENT_LANGUAGE -> R.string.cover_online_warn_language
        CoverWarning.DIFFERENT_PUBLISHER -> R.string.cover_online_warn_publisher
        CoverWarning.DIFFERENT_YEAR -> R.string.cover_online_warn_year
        CoverWarning.LOW_CONFIDENCE -> R.string.cover_online_warn_low
        CoverWarning.NO_COVER_RECORDED -> R.string.cover_online_warn_nocover
    })
}

@Composable
private fun CandidateSummary(c: OnlineCoverCandidate) {
    // Identifier agreement, stated separately from how far the rest of the record can be trusted (META-1); never "exact edition".
    Text(stringResource(when (coverIsbnLabel(c.match, c.tier, c.warnings)) {
        IsbnLabel.ISBN_MATCHES -> R.string.cover_online_label_isbn
        IsbnLabel.ISBN_MATCHES_VERIFY -> R.string.cover_online_label_isbn_verify
        IsbnLabel.NO_ISBN_MATCH -> R.string.cover_online_label_other
    }), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.cover_online_edition, c.editionRef), style = MaterialTheme.typography.bodySmall)
    Text(listOfNotNull(c.title, c.publisher, c.year?.toString(), c.language).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    warningText(c).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun CandidateRow(item: LibraryItem, controller: OnlineCoverController, ui: OnlineCoverUi, c: OnlineCoverCandidate, index: Int, count: Int, busy: Boolean, inspect: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("cover_online_candidate_$index"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(72.dp), contentAlignment = Alignment.TopCenter) {
            if (ui.previews.containsKey(c.key)) PickerArtwork(controller, item, c.key, "cover_online_thumb_$index", maxHeight = 108)
        }
        Column(Modifier.weight(1f)) {
            CandidateSummary(c)
            val description = stringResource(R.string.cover_online_candidate_desc, index + 1, count, c.title ?: stringResource(R.string.cover_online_untitled))
            PickerButton(stringResource(R.string.cover_online_preview), "cover_online_preview_$index", !busy, description = description, onClick = inspect)
            if (ui.busyKey == c.key && !ui.busyUse) Progress(R.string.cover_online_previewing, "cover_online_busy")
        }
    }
}

@Composable
private fun Inspector(item: LibraryItem, controller: OnlineCoverController, ui: OnlineCoverUi, c: OnlineCoverCandidate, busy: Boolean, back: () -> Unit) {
    val t = LocalShelfTokens.current
    val preview = ui.previews[c.key]
    Column(Modifier.testTag("cover_online_inspector"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CandidateSummary(c)
        if (preview != null) {
            PickerArtwork(controller, item, c.key, "cover_online_whole", whole = true, maxHeight = 220)
            Text(stringResource(R.string.cover_online_dims, preview.width, preview.height), Modifier.testTag("cover_online_dims"), style = MaterialTheme.typography.bodySmall)
        } else if (ui.busyKey == c.key) Progress(R.string.cover_online_previewing, "cover_online_busy")
        else PickerButton(stringResource(R.string.cover_online_preview), "cover_online_inspector_preview", !busy) { controller.preview(item, c.key) }
        Text(stringResource(R.string.cover_online_rights), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
        if (ui.busyKey == c.key && ui.busyUse) Progress(R.string.cover_online_using, "cover_online_busy")
        // Use this cover needs the artwork on screen first: preview is not confirmation, and nothing is applied by tapping a candidate.
        PickerButton(stringResource(R.string.cover_online_use), "cover_online_use", preview != null && !busy) { controller.use(item, c.key) }
        PickerButton(stringResource(R.string.action_back), "cover_online_back", !(busy && ui.busyUse), onClick = back)
    }
}
