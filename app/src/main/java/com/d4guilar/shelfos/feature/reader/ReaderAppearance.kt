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
    onReset: (globally: Boolean) -> Unit,
    fontFamilies: List<ManagedFontFamily> = emptyList(),
    fontImportError: String? = null,
    onImportFont: (() -> Unit)? = null,
    onRemoveFont: ((String) -> Unit)? = null,
) {
    val t = LocalShelfTokens.current
    val initial = rememberSaveable(saver = ReaderPreferencesSaver) { preferences }
    var draft by rememberSaveable(stateSaver = ReaderPreferencesSaver) { mutableStateOf(initial) }
    var globally by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Reading appearance") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (capabilities.typography) {
                Text("Presentation")
                PresentationMode.entries.forEach { mode -> ShelfChoiceChip(draft.presentationMode == mode,
                    { draft = draft.copy(presentationMode = mode) }, if (mode == PresentationMode.SHELFOS) "ShelfOS" else "Publisher") }
                if (draft.presentationMode == PresentationMode.PUBLISHER) {
                    Text("Publisher styles from the original EPUB are honored. The source file is unchanged.",
                        color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
                } else {
                Text("Book style")
                Row { TextButton({ draft = draft.copy(font = BookFont.SERIF, fontFamilyId = BUILTIN_SERIF_FONT_ID, lineHeight = 1.5, margins = 1.0) }) { Text("Editorial") }
                    TextButton({ draft = draft.copy(font = BookFont.SANS, fontFamilyId = BUILTIN_SANS_FONT_ID, lineHeight = 1.5, margins = 1.0) }) { Text("Clean") } }
                TextButton({ draft = draft.copy(font = BookFont.SERIF, fontFamilyId = BUILTIN_SERIF_FONT_ID, lineHeight = 1.9, margins = 1.5) }) { Text("Spacious") }
                Text("Font")
                fontFamilies.forEach { family ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShelfChoiceChip(draft.effectiveFontFamilyId() == family.id, {
                            draft = draft.copy(fontFamilyId = family.id, font = when (family.id) {
                                BUILTIN_SANS_FONT_ID -> BookFont.SANS
                                BUILTIN_SERIF_FONT_ID -> BookFont.SERIF
                                else -> draft.font ?: BookFont.SERIF
                            })
                        }, family.displayName)
                        if (family.source == ManagedFontSource.USER && onRemoveFont != null)
                            TextButton({ onRemoveFont(family.id) }) { Text("Remove") }
                    }
                }
                onImportFont?.let { TextButton(it) { Text("Import font…") } }
                fontImportError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
                }
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
        it.direction?.name, it.fit?.name, it.fontFamilyId, it.presentationMode?.name) },
    restore = { saved ->
        ReaderPreferences(BookFont.entries.find { it.name == saved.getOrNull(0) }, saved.getOrNull(1) as Double?, saved.getOrNull(2) as Double?,
            saved.getOrNull(3) as Double?, saved.getOrNull(4) as Boolean?, saved.getOrNull(5) as Boolean?,
            PagePalette.entries.find { it.name == saved.getOrNull(6) }, ReadingDirection.entries.find { it.name == saved.getOrNull(7) },
            FitMode.entries.find { it.name == saved.getOrNull(8) }, saved.getOrNull(9) as String?,
            PresentationMode.entries.find { it.name == saved.getOrNull(10) })
    },
)
