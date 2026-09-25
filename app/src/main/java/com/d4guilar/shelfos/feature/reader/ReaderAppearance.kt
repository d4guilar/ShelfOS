// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.core.designsystem.ShelfChoiceChip
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.domain.library.ReadingDirection
import com.d4guilar.shelfos.core.theme.LocalShelfTokens

/**
 * Offers only controls the publication's renderer supports. Apply records the fields changed here;
 * untouched settings keep following global defaults and the theme.
 *
 * Until Apply, changes are an editing draft: the draft, the values it started from and the chosen scope survive
 * recreation with the dialog, while committed preferences stay in the library.
 */
@Composable
fun ReaderAppearance(preferences: ReaderPreferences, capabilities: ReaderCapabilities, onDismiss: () -> Unit,
    onApply: (before: ReaderPreferences, after: ReaderPreferences, globally: Boolean) -> Unit,
    onReset: (globally: Boolean) -> Unit) {
    val t = LocalShelfTokens.current
    val initial = rememberSaveable(saver = ReaderPreferencesSaver) { preferences }
    var draft by rememberSaveable(stateSaver = ReaderPreferencesSaver) { mutableStateOf(initial) }
    var globally by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Reading appearance") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (capabilities.typography) {
                Text("Book style")
                Row { TextButton({ draft = draft.copy(font = BookFont.SERIF, lineHeight = 1.5, margins = 1.0) }) { Text("Editorial") }
                    TextButton({ draft = draft.copy(font = BookFont.SANS, lineHeight = 1.5, margins = 1.0) }) { Text("Clean") } }
                TextButton({ draft = draft.copy(font = BookFont.SERIF, lineHeight = 1.9, margins = 1.5) }) { Text("Spacious") }
                Text("Text size: ${((draft.fontSize ?: 1.0) * 100).toInt()}%")
                Slider((draft.fontSize ?: 1.0).toFloat(), { draft = draft.copy(fontSize = it.toDouble()) }, valueRange = .7f..2.5f)
                Text("Line spacing")
                Slider((draft.lineHeight ?: 1.5).toFloat(), { draft = draft.copy(lineHeight = it.toDouble()) }, valueRange = 1f..2.5f)
                Text("Page margins")
                Slider((draft.margins ?: 1.0).toFloat(), { draft = draft.copy(margins = it.toDouble()) }, valueRange = 0f..3f)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.justified ?: false, { draft = draft.copy(justified = it) }); Text("Justified text") }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.scroll ?: false, { draft = draft.copy(scroll = it) }); Text("Continuous scrolling") }
                Text("Page colors")
                PagePalette.entries.forEach { palette -> ShelfChoiceChip(draft.palette == palette,
                    { draft = draft.copy(palette = palette) }, palette.name.lowercase().replaceFirstChar { it.uppercase() }) }
            } else {
                Text("This publication uses fixed pages. Fonts, layout and artwork keep their original appearance.")
            }
            if (capabilities.fit) FitMode.entries.forEach { fit -> ShelfChoiceChip(draft.fit == fit, { draft = draft.copy(fit = fit) },
                if (fit == FitMode.PAGE) "Fit page" else "Fit width") }
            if (capabilities.direction) {
                Text("Reading direction")
                ShelfChoiceChip(draft.direction == null, { draft = draft.copy(direction = null) }, "Category default")
                ReadingDirection.entries.forEach { direction -> ShelfChoiceChip(draft.direction == direction,
                    { draft = draft.copy(direction = direction) }, if (direction == ReadingDirection.RTL) "Right to left" else "Left to right") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(globally, { globally = it }, Modifier.testTag("appearance_scope")); Text("Use as default for all titles")
            }
            if (globally && capabilities.direction)
                Text("Reading direction always applies to this title only.", color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
            TextButton({ onReset(globally); onDismiss() }) { Text(if (globally) "Reset defaults for all titles" else "Reset this title") }
        }
    }, confirmButton = { TextButton({ onApply(initial, draft, globally); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** Saves a preference layer as plain values; unknown names from an older build restore as unset. */
internal val ReaderPreferencesSaver = listSaver<ReaderPreferences, Any?>(
    save = { listOf(it.font?.name, it.fontSize, it.lineHeight, it.margins, it.justified, it.scroll, it.palette?.name,
        it.direction?.name, it.fit?.name) },
    restore = { saved ->
        ReaderPreferences(BookFont.entries.find { it.name == saved[0] }, saved[1] as Double?, saved[2] as Double?,
            saved[3] as Double?, saved[4] as Boolean?, saved[5] as Boolean?, PagePalette.entries.find { it.name == saved[6] },
            ReadingDirection.entries.find { it.name == saved[7] }, FitMode.entries.find { it.name == saved[8] })
    },
)
