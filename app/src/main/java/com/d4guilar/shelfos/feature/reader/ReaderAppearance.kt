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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.ShelfChoiceChip
import com.d4guilar.shelfos.core.designsystem.UiMessage
import com.d4guilar.shelfos.core.designsystem.formatPercent
import com.d4guilar.shelfos.core.designsystem.resolve
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
    fontImportError: UiMessage? = null,
    onImportFont: (() -> Unit)? = null,
    onRemoveFont: ((String) -> Unit)? = null,
) {
    val t = LocalShelfTokens.current
    val initial = rememberSaveable(saver = ReaderPreferencesSaver) { preferences }
    var draft by rememberSaveable(stateSaver = ReaderPreferencesSaver) { mutableStateOf(initial) }
    var globally by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.appearance_dialog_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (capabilities.typography) {
                Text(stringResource(R.string.appearance_presentation))
                PresentationMode.entries.forEach { mode -> ShelfChoiceChip(draft.presentationMode == mode,
                    { draft = draft.copy(presentationMode = mode) },
                    if (mode == PresentationMode.SHELFOS) "ShelfOS" else stringResource(R.string.appearance_presentation_publisher)) }
                if (draft.presentationMode == PresentationMode.PUBLISHER) {
                    Text(stringResource(R.string.appearance_publisher_notice),
                        color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
                } else {
                Text(stringResource(R.string.appearance_book_style))
                Row { TextButton({ draft = draft.copy(font = BookFont.SERIF, fontFamilyId = BUILTIN_SERIF_FONT_ID, lineHeight = 1.5, margins = 1.0) }) { Text(stringResource(R.string.appearance_style_editorial)) }
                    TextButton({ draft = draft.copy(font = BookFont.SANS, fontFamilyId = BUILTIN_SANS_FONT_ID, lineHeight = 1.5, margins = 1.0) }) { Text(stringResource(R.string.appearance_style_clean)) } }
                TextButton({ draft = draft.copy(font = BookFont.SERIF, fontFamilyId = BUILTIN_SERIF_FONT_ID, lineHeight = 1.9, margins = 1.5) }) { Text(stringResource(R.string.appearance_style_spacious)) }
                Text(stringResource(R.string.appearance_font))
                fontFamilies.forEach { family ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShelfChoiceChip(draft.effectiveFontFamilyId() == family.id, {
                            draft = draft.copy(fontFamilyId = family.id, font = when (family.id) {
                                BUILTIN_SANS_FONT_ID -> BookFont.SANS
                                BUILTIN_SERIF_FONT_ID -> BookFont.SERIF
                                else -> draft.font ?: BookFont.SERIF
                            })
                        }, builtinFontLabelRes(family.id)?.let { stringResource(it) } ?: family.displayName)
                        if (family.source == ManagedFontSource.USER && onRemoveFont != null)
                            TextButton({ onRemoveFont(family.id) }) { Text(stringResource(R.string.action_remove)) }
                    }
                }
                onImportFont?.let { TextButton(it) { Text(stringResource(R.string.appearance_import_font)) } }
                fontImportError?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.appearance_text_size, formatPercent(((draft.fontSize ?: 1.0) * 100).toInt())))
                Slider((draft.fontSize ?: 1.0).toFloat(), { draft = draft.copy(fontSize = it.toDouble()) }, valueRange = .7f..2.5f)
                Text(stringResource(R.string.appearance_line_spacing))
                Slider((draft.lineHeight ?: 1.5).toFloat(), { draft = draft.copy(lineHeight = it.toDouble()) }, valueRange = 1f..2.5f)
                Text(stringResource(R.string.appearance_page_margins))
                Slider((draft.margins ?: 1.0).toFloat(), { draft = draft.copy(margins = it.toDouble()) }, valueRange = 0f..3f)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.justified ?: false, { draft = draft.copy(justified = it) }); Text(stringResource(R.string.appearance_justified_text)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(draft.scroll ?: false, { draft = draft.copy(scroll = it) }); Text(stringResource(R.string.appearance_continuous_scrolling)) }
                Text(stringResource(R.string.appearance_page_colors))
                PagePalette.entries.forEach { palette -> ShelfChoiceChip(draft.palette == palette,
                    { draft = draft.copy(palette = palette) }, stringResource(palette.labelRes())) }
                }
            } else {
                Text(stringResource(R.string.appearance_fixed_pages_notice))
            }
            if (capabilities.fit) FitMode.entries.forEach { fit -> ShelfChoiceChip(draft.fit == fit, { draft = draft.copy(fit = fit) },
                stringResource(if (fit == FitMode.PAGE) R.string.appearance_fit_page else R.string.appearance_fit_width)) }
            if (capabilities.direction) {
                Text(stringResource(R.string.appearance_reading_direction))
                ShelfChoiceChip(draft.direction == null, { draft = draft.copy(direction = null) }, stringResource(R.string.appearance_direction_category_default))
                ReadingDirection.entries.forEach { direction -> ShelfChoiceChip(draft.direction == direction,
                    { draft = draft.copy(direction = direction) },
                    stringResource(if (direction == ReadingDirection.RTL) R.string.appearance_direction_rtl else R.string.appearance_direction_ltr)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(globally, { globally = it }, Modifier.testTag("appearance_scope")); Text(stringResource(R.string.appearance_use_as_default))
            }
            if (globally && capabilities.direction)
                Text(stringResource(R.string.appearance_direction_title_only_notice), color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
            TextButton({ onReset(globally); onDismiss() }) { Text(stringResource(if (globally) R.string.appearance_reset_all_titles else R.string.appearance_reset_this_title)) }
        }
    }, confirmButton = { TextButton({ onApply(initial, draft, globally); onDismiss() }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

/** The two builtin font families' display names are ShelfOS-owned UI copy, not font metadata (unlike a USER
 * family's [ManagedFontFamily.displayName], which is the imported file's own name and stays as-is) — localized
 * here rather than in [ManagedFontFamily] itself, whose stable [BUILTIN_SERIF_FONT_ID]/[BUILTIN_SANS_FONT_ID]
 * identity is unchanged. */
private fun builtinFontLabelRes(familyId: String): Int? = when (familyId) {
    BUILTIN_SERIF_FONT_ID -> R.string.font_builtin_serif
    BUILTIN_SANS_FONT_ID -> R.string.font_builtin_sans
    else -> null
}

private fun PagePalette.labelRes(): Int = when (this) {
    PagePalette.THEME -> R.string.palette_theme
    PagePalette.LIGHT -> R.string.palette_light
    PagePalette.DARK -> R.string.palette_dark
    PagePalette.PAPER -> R.string.palette_paper
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
