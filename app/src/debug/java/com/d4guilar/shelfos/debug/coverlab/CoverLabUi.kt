// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.debug.coverlab

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.LocalCoverArtwork
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.data.library.PilotFailure
import com.d4guilar.shelfos.data.library.PilotImage
import com.d4guilar.shelfos.data.metadata.openlibrary.CoverMatchKind
import com.d4guilar.shelfos.data.metadata.openlibrary.CoverWarning
import com.d4guilar.shelfos.data.metadata.openlibrary.OnlineCoverCandidate
import com.d4guilar.shelfos.domain.library.*
import com.d4guilar.shelfos.feature.library.CoverLabEntry
import com.d4guilar.shelfos.feature.library.IsbnLabel
import com.d4guilar.shelfos.feature.library.coverIsbnLabel
import com.d4guilar.shelfos.feature.reader.activateOnGamepadConfirm
import java.util.Locale

/** Debug-only entry (D1-C-DEV): a details-pane action that opens the Cover Lab. Opening it never sends a request. */
class DebugCoverLabEntry(private val session: CoverLabSession) : CoverLabEntry {

    @Composable
    override fun Action(item: LibraryItem) {
        var open by rememberSaveable(item.id) { mutableStateOf(false) }
        val show = { open = true }
        TextButton(show, Modifier.testTag("cover_lab_action").activateOnGamepadConfirm(onActivate = show)) { Text(stringResource(R.string.cover_lab_action)) }
        if (open) CoverLabDialog(item, session) { open = false }
    }
}

/** The preset matrix of the pilot: Original, the four fixed ratios and four Custom ratios (5:8, 16:9, 1:2, 2:1). */
private data class ShapeChoice(val shape: CoverShape, val w: Int = 0, val h: Int = 0)
private val SHAPES = listOf(
    ShapeChoice(CoverShape.ORIGINAL), ShapeChoice(CoverShape.RATIO_2_3), ShapeChoice(CoverShape.RATIO_3_4), ShapeChoice(CoverShape.RATIO_4_5),
    ShapeChoice(CoverShape.RATIO_1_1), ShapeChoice(CoverShape.CUSTOM, 5, 8), ShapeChoice(CoverShape.CUSTOM, 16, 9),
    ShapeChoice(CoverShape.CUSTOM, 1, 2), ShapeChoice(CoverShape.CUSTOM, 2, 1),
)

private fun failureRes(f: PilotFailure) = when (f) {
    PilotFailure.NOT_AUTHORIZED -> R.string.cover_lab_err_not_authorized
    PilotFailure.NO_ISBN -> R.string.cover_lab_err_no_isbn
    PilotFailure.NOT_FOUND -> R.string.cover_lab_err_not_found
    PilotFailure.OFFLINE -> R.string.cover_lab_err_offline
    PilotFailure.TIMEOUT -> R.string.cover_lab_err_timeout
    PilotFailure.RATE_LIMITED -> R.string.cover_lab_err_rate_limited
    PilotFailure.ACCESS_BLOCKED -> R.string.cover_lab_err_blocked
    PilotFailure.SERVER_FAILURE -> R.string.cover_lab_err_server
    PilotFailure.MALFORMED -> R.string.cover_lab_err_malformed
    PilotFailure.REDIRECT_BLOCKED -> R.string.cover_lab_err_redirect
    PilotFailure.TOO_LARGE -> R.string.cover_lab_err_too_large
    PilotFailure.NOT_AN_IMAGE -> R.string.cover_lab_err_not_image
    PilotFailure.MIME_MISMATCH -> R.string.cover_lab_err_mime
    PilotFailure.UNSUPPORTED_IMAGE -> R.string.cover_lab_err_unsupported
    PilotFailure.INVALID_IMAGE -> R.string.cover_lab_err_invalid
    PilotFailure.STORAGE_FULL -> R.string.cover_lab_err_storage
    PilotFailure.BUDGET_EXHAUSTED -> R.string.cover_lab_err_budget
    PilotFailure.BUSY -> R.string.cover_lab_err_busy
    PilotFailure.ITEM_GONE -> R.string.cover_lab_err_gone
    PilotFailure.FAILED -> R.string.cover_lab_err_failed
}

private fun ratio(image: PilotImage) = String.format(Locale.getDefault(), "%.2f", image.width.toFloat() / image.height)

@Composable
private fun LabButton(text: String, tag: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier.testTag(tag).activateOnGamepadConfirm(enabled, onClick), enabled = enabled) { Text(text) }
}

@Composable
fun CoverLabDialog(item: LibraryItem, session: CoverLabSession, onDismiss: () -> Unit) {
    val t = LocalShelfTokens.current
    val enabled by session.authorization.enabled.collectAsState()
    val ui by remember(item.id) { session.ui(item.id) }.collectAsState()
    // Survives rotation and activity recreation: which candidate is open, and the presentation under test.
    var inspecting by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    val start = LocalCoverArtwork.current?.presentations?.value?.effective(MediaCategory.BOOK) ?: CoverPresentations.GLOBAL_DEFAULT
    var shapeIndex by rememberSaveable { mutableStateOf(SHAPES.indexOfFirst { it.shape == start.shape && it.w == (start.customWidth ?: 0) && it.h == (start.customHeight ?: 0) }.coerceAtLeast(1)) }
    var crop by rememberSaveable { mutableStateOf(start.fit == CoverFit.CROP) }
    val choice = SHAPES[shapeIndex]
    val presentation = remember(shapeIndex, crop) { coverPresentation(choice.shape, if (crop) CoverFit.CROP else CoverFit.FIT, choice.w, choice.h) ?: CoverPresentations.GLOBAL_DEFAULT }
    LaunchedEffect(presentation) { session.setPresentation(presentation) }   // presentation only: no request, no re-encode
    LaunchedEffect(item.id) { session.hydrate(item.id) }
    val close = { session.cancel(item.id); onDismiss() }
    // Keyboard/gamepad users start on the safe button (Cancel), never on the consent button; touch is unaffected.
    val safeFocus = remember { FocusRequester() }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    LaunchedEffect(enabled, keyboardMode) { if (!enabled && keyboardMode) runCatching { safeFocus.requestFocus() } }

    AlertDialog(onDismissRequest = close, title = { Text(stringResource(if (enabled) R.string.cover_lab_title else R.string.cover_lab_disclosure_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("cover_lab_dialog"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!enabled) {
                    Text(stringResource(R.string.cover_lab_disclosure), Modifier.testTag("cover_lab_disclosure"))
                } else {
                    Text(stringResource(R.string.cover_lab_note), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
                    ui.selection?.let { s ->
                        Text(stringResource(R.string.cover_lab_selected), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.cover_lab_selected_info, s.width, s.height, s.editionRef), Modifier.testTag("cover_lab_selected_info"), style = MaterialTheme.typography.bodySmall)
                        LabArtwork(session, session.selectionId(item.id), item, "cover_lab_selected", presentation, t.colors.divider)
                        LabButton(stringResource(R.string.cover_lab_remove), "cover_lab_remove") { session.removePick(item.id) }
                    }
                    PresentationControls(shapeIndex, crop, { shapeIndex = it }, { crop = it })
                    val open = ui.candidates?.firstOrNull { it.key == inspecting }
                    if (open != null) Inspector(item, session, ui, open, presentation, { inspecting = null }) else {
                        LabButton(stringResource(R.string.cover_lab_find), "cover_lab_find", !ui.discovering && ui.busyKey == null) { session.find(item) }
                        if (ui.discovering) Progress(R.string.cover_lab_finding, "cover_lab_finding")
                        ui.candidates?.let { list ->
                            if (list.isEmpty()) Text(stringResource(R.string.cover_lab_none), Modifier.testTag("cover_lab_none"))
                            list.forEachIndexed { i, c -> CandidateRow(item, session, ui, c, i, presentation) { inspecting = c.key } }
                        }
                    }
                    ui.failure?.let { f ->
                        Text(stringResource(failureRes(f)), Modifier.testTag("cover_lab_error").semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.provider_openlibrary_attribution), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
                }
            }
        },
        confirmButton = {
            if (!enabled) LabButton(stringResource(R.string.cover_lab_enable), "cover_lab_enable") { session.authorization.enable() }
            else LabButton(stringResource(R.string.cover_lab_disable), "cover_lab_disable") { session.cancel(item.id); session.authorization.disable() }
        },
        dismissButton = { LabButton(stringResource(if (enabled) R.string.cover_lab_close else R.string.cover_lab_cancel), "cover_lab_close", modifier = Modifier.focusRequester(safeFocus), onClick = close) })
}

@Composable
private fun Progress(label: Int, tag: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(stringResource(label), Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** The lab's artwork through the unchanged shared renderer, with the lab's own map/presentation provided for this subtree only. */
@Composable
private fun LabArtwork(session: CoverLabSession, id: String, item: LibraryItem, tag: String, presentation: CoverPresentation, border: androidx.compose.ui.graphics.Color, whole: Boolean = false, maxHeight: Int = 160) {
    CompositionLocalProvider(LocalCoverArtwork provides session.artwork) {
        Box(Modifier.fillMaxWidth().testTag(tag), contentAlignment = Alignment.Center) {
            com.d4guilar.shelfos.core.designsystem.PublicationCover(id, item.title, Modifier.heightIn(max = maxHeight.dp), MediaCategory.BOOK, Modifier.border(1.dp, border), whole)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresentationControls(shapeIndex: Int, crop: Boolean, onShape: (Int) -> Unit, onCrop: (Boolean) -> Unit) {
    Text(stringResource(R.string.cover_lab_shape), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SHAPES.forEachIndexed { i, s ->
            val label = when (s.shape) {
                CoverShape.ORIGINAL -> stringResource(R.string.cover_lab_shape_original)
                CoverShape.CUSTOM -> stringResource(R.string.cover_lab_shape_custom, "${s.w}:${s.h}")
                else -> "${s.shape.width}:${s.shape.height}"
            }
            FilterChip(i == shapeIndex, { onShape(i) }, { Text(label) }, Modifier.testTag("cover_lab_shape_$i").activateOnGamepadConfirm(onActivate = { onShape(i) }))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(crop, { onCrop(true) }, { Text(stringResource(R.string.cover_lab_crop)) }, Modifier.testTag("cover_lab_crop").activateOnGamepadConfirm(onActivate = { onCrop(true) }))
        FilterChip(!crop, { onCrop(false) }, { Text(stringResource(R.string.cover_lab_fit)) }, Modifier.testTag("cover_lab_fit").activateOnGamepadConfirm(onActivate = { onCrop(false) }))
    }
}

@Composable
private fun warningText(c: OnlineCoverCandidate): List<String> = c.warnings.map {
    stringResource(when (it) {
        CoverWarning.DIFFERENT_ISBN -> R.string.cover_lab_warn_isbn
        CoverWarning.DIFFERENT_LANGUAGE -> R.string.cover_lab_warn_language
        CoverWarning.DIFFERENT_PUBLISHER -> R.string.cover_lab_warn_publisher
        CoverWarning.DIFFERENT_YEAR -> R.string.cover_lab_warn_year
        CoverWarning.LOW_CONFIDENCE -> R.string.cover_lab_warn_low
        CoverWarning.NO_COVER_RECORDED -> R.string.cover_lab_warn_nocover
    })
}

@Composable
private fun CandidateSummary(c: OnlineCoverCandidate) {
    // Identifier agreement, stated separately from how far the rest of the record can be trusted (META-1).
    Text(stringResource(when (coverIsbnLabel(c.match, c.tier, c.warnings)) {
        IsbnLabel.ISBN_MATCHES -> R.string.cover_lab_exact
        IsbnLabel.ISBN_MATCHES_VERIFY -> R.string.cover_lab_exact_verify
        IsbnLabel.NO_ISBN_MATCH -> R.string.cover_lab_other
    }), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.cover_lab_source_edition, c.editionRef), style = MaterialTheme.typography.bodySmall)
    Text(listOfNotNull(c.title, c.publisher, c.year?.toString(), c.language).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    warningText(c).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun CandidateRow(item: LibraryItem, session: CoverLabSession, ui: LabUi, c: OnlineCoverCandidate, index: Int, presentation: CoverPresentation, inspect: () -> Unit) {
    val t = LocalShelfTokens.current
    val preview = ui.previews[c.key]
    Row(Modifier.fillMaxWidth().testTag("cover_lab_candidate_$index"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(72.dp), contentAlignment = Alignment.TopCenter) {
            if (preview != null) LabArtwork(session, session.candidateId(item.id, c.key), item, "cover_lab_thumb_$index", presentation, t.colors.divider, maxHeight = 108)
        }
        Column(Modifier.weight(1f)) {
            CandidateSummary(c)
            Row {
                if (preview == null) LabButton(stringResource(R.string.cover_lab_preview), "cover_lab_preview_$index", ui.busyKey == null && !ui.discovering) { session.preview(item, c) }
                LabButton(stringResource(R.string.cover_lab_inspect), "cover_lab_inspect_$index", onClick = inspect)
            }
            if (ui.busyKey == c.key) Progress(R.string.cover_lab_previewing, "cover_lab_busy")
        }
    }
}

@Composable
private fun Inspector(item: LibraryItem, session: CoverLabSession, ui: LabUi, c: OnlineCoverCandidate, presentation: CoverPresentation, back: () -> Unit) {
    val t = LocalShelfTokens.current
    val preview = ui.previews[c.key]
    val busy = ui.busyKey != null
    Column(Modifier.testTag("cover_lab_inspector"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CandidateSummary(c)
        if (preview != null) {
            // The whole artwork at its own ratio, then the same file under the presentation being compared (no request in between).
            LabArtwork(session, session.candidateId(item.id, c.key), item, "cover_lab_whole", presentation, t.colors.divider, whole = true, maxHeight = 220)
            Text(stringResource(R.string.cover_lab_dims, preview.width, preview.height, ratio(preview)), Modifier.testTag("cover_lab_dims"), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.cover_lab_compare), style = MaterialTheme.typography.titleSmall)
            LabArtwork(session, session.candidateId(item.id, c.key), item, "cover_lab_compare", presentation, t.colors.divider, maxHeight = 180)
        } else LabButton(stringResource(R.string.cover_lab_preview), "cover_lab_inspector_preview", !busy) { session.preview(item, c) }
        if (busy) Progress(if (ui.busyDownload) R.string.cover_lab_downloading else R.string.cover_lab_previewing, "cover_lab_busy")
        LabButton(stringResource(R.string.cover_lab_download), "cover_lab_download", !busy) { session.download(item, c) }
        LabButton(stringResource(R.string.cover_lab_back), "cover_lab_back", onClick = back)
    }
}
