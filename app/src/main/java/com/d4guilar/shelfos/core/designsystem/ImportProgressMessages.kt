// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.domain.importing.ImportProgressStage

/** [ImportProgressStage] is a pure, locale-neutral identifier (see its own doc comment); this is the UI-layer
 * mapping to a localized, resource-backed message, following the same boundary as feature.library's
 * CategoryLabels. Resolving it here — rather than inside the importer, which only has an Application Context —
 * is also what keeps this correct on API 24-32, where AppCompat applies a locale override to Activity
 * configuration but not to the Application context (QA M2). */
fun ImportProgressStage.toUiMessage(): UiMessage = when (this) {
    ImportProgressStage.Inspecting -> UiMessage.Resource(R.string.import_stage_inspecting)
    ImportProgressStage.CopyingOffline -> UiMessage.Resource(R.string.import_stage_copying_offline)
    is ImportProgressStage.CopyingPercent -> UiMessage.Resource(R.string.import_stage_copying_percent, listOf(percent))
    is ImportProgressStage.CopyingMegabytes -> UiMessage.Resource(R.string.import_stage_copying_megabytes, listOf(megabytes))
}
