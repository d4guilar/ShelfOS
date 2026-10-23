// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.data.metadata.openlibrary.OpenLibrary
import com.d4guilar.shelfos.feature.library.LocalOnlineCovers
import com.d4guilar.shelfos.feature.library.LocalOnlineMetadata

/**
 * Settings → Online metadata (Phase 4.5C.2A). Both switches are OFF on a fresh install; this screen is where consent is given or
 * withdrawn, and the registry re-reads the stored state on every request, so turning a switch off here stops new requests at once.
 */
@Composable
fun OnlineMetadataSection() {
    val access = LocalOnlineMetadata.current ?: return
    val t = LocalShelfTokens.current
    val online by access.settings.onlineFlow.collectAsStateWithLifecycle()
    val providers by access.settings.providersFlow.collectAsStateWithLifecycle()
    val providerOn = OpenLibrary.ID in providers
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(t.spacing.small)) {
        Text(stringResource(R.string.settings_online_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_online_description), color = t.colors.secondary)
        SwitchRow(stringResource(R.string.settings_online_master), online, "online_master_switch") { access.settings.setOnlineEnabled(it) }
        SwitchRow(stringResource(R.string.settings_online_openlibrary), providerOn, "online_openlibrary_switch", enabled = online) { access.settings.setProviderEnabled(OpenLibrary.ID, it) }
        Text(stringResource(R.string.settings_online_openlibrary_summary), color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.provider_openlibrary_disclosure), color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (online && providerOn) R.string.settings_online_status_on else R.string.settings_online_status_off), Modifier.testTag("online_status"),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.provider_openlibrary_attribution), color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
        CoverDownloadsSettings(online && providerOn)
        TextButton({ uri.openUri("https://openlibrary.org") }, Modifier.testTag("online_openlibrary_link")) { Text(stringResource(R.string.settings_online_link)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, null, enabled = enabled)
    }
}

/** The separate cover-download consent (Phase 4.5C.3A, D1-C-PROD): OFF by default, never implied by the metadata switches above. */
@Composable
private fun CoverDownloadsSettings(metadataOn: Boolean) {
    val covers = LocalOnlineCovers.current ?: return
    val t = LocalShelfTokens.current
    val on by covers.coverConsent.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(t.spacing.small)) {
        Text(stringResource(R.string.settings_cover_online_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.settings_cover_online_summary), color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
        SwitchRow(stringResource(R.string.settings_cover_online_switch), on, "online_covers_switch") { covers.setCoverConsent(it) }
        if (!metadataOn) Text(stringResource(R.string.settings_cover_online_needs_metadata), color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (on && metadataOn) R.string.settings_cover_online_status_on else R.string.settings_cover_online_status_off), Modifier.testTag("online_covers_status"),
            style = MaterialTheme.typography.bodySmall)
    }
}
