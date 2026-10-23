// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.data.library.UserCoverOutcome
import com.d4guilar.shelfos.data.library.UserCoverStore
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.feature.reader.activateOnGamepadConfirm
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Provided once by the app; null (previews, isolated tests) hides the Cover action. */
val LocalUserCovers = staticCompositionLocalOf<UserCoverStore?> { null }

/**
 * The Cover dialog (Phase 4.5F.0): current artwork, whether a custom cover is active, choose an image from the device,
 * or go back to the file's cover. The picker result is copied and stored by [UserCoverStore] off the main thread; the
 * dialog stays open with progress, can be cancelled, and reports a specific error without changing the current cover.
 */
@Composable
fun CoverDialog(item: LibraryItem, store: UserCoverStore, onDismiss: () -> Unit) {
    val t = LocalShelfTokens.current
    val scope = rememberCoroutineScope()
    val custom by remember(store, item.id) { store.hasCustom(item.id) }.collectAsState(false)
    var working by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun run(block: suspend () -> UserCoverOutcome?) {
        working = true; error = null
        job = scope.launch { try { error = block()?.takeIf { it != UserCoverOutcome.SAVED }?.name } finally { working = false } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run { store.set(item.id, uri.toString()) }
    }
    val choose = { picker.launch(arrayOf("image/*")) }
    val close = { onDismiss() }
    val cancel = { job?.cancel(); working = false }
    val onlineCovers = LocalOnlineCovers.current.takeIf { item.category == MediaCategory.BOOK }
    var onlineOpen by rememberSaveable(item.id) { mutableStateOf(false) }
    if (onlineOpen && onlineCovers != null) OnlineCoverDialog(item, onlineCovers) { onlineOpen = false }
    AlertDialog(onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.cover_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PublicationCover(item, Modifier.heightIn(max = 180.dp).testTag("cover_preview"), whole = true)
                Text(stringResource(if (custom) R.string.cover_custom_active else R.string.cover_file_active), Modifier.testTag("cover_status")
                    .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                if (working) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp).testTag("cover_progress"), strokeWidth = 2.dp)
                    Text(stringResource(R.string.cover_saving), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                error?.let { e ->
                    Text(stringResource(when (enumValues<UserCoverOutcome>().firstOrNull { it.name == e }) {
                        UserCoverOutcome.ITEM_GONE -> R.string.cover_error_gone
                        UserCoverOutcome.UNREADABLE -> R.string.cover_error_unreadable
                        UserCoverOutcome.INVALID_IMAGE -> R.string.cover_error_invalid
                        UserCoverOutcome.UNSUPPORTED_IMAGE -> R.string.cover_error_unsupported
                        UserCoverOutcome.STORAGE_FULL -> R.string.cover_error_storage
                        else -> R.string.cover_error_failed
                    }), Modifier.testTag("cover_error").semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error)
                }
                if (onlineCovers != null) {
                    val findOnline = { onlineOpen = true }
                    TextButton(findOnline, Modifier.testTag("cover_find_online").activateOnGamepadConfirm(!working, findOnline), enabled = !working) {
                        Text(stringResource(R.string.cover_online_action))
                    }
                }
                if (custom) {
                    Text(stringResource(R.string.cover_restore_explain), style = MaterialTheme.typography.bodySmall, color = t.colors.secondary)
                    val restore = { run { store.remove(item.id); null } }
                    TextButton(restore, Modifier.testTag("cover_restore").activateOnGamepadConfirm(!working, restore), enabled = !working) {
                        Text(stringResource(R.string.cover_restore))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(choose, Modifier.testTag("cover_choose").activateOnGamepadConfirm(!working, choose), enabled = !working) {
                Text(stringResource(R.string.cover_choose))
            }
        },
        dismissButton = {
            if (working) TextButton(cancel, Modifier.testTag("cover_cancel").activateOnGamepadConfirm(onActivate = cancel)) { Text(stringResource(R.string.action_cancel)) }
            else TextButton(close, Modifier.testTag("cover_close").activateOnGamepadConfirm(onActivate = close)) { Text(stringResource(R.string.action_close)) }
        })
}
