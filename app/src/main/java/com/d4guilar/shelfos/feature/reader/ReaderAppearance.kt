// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.core.reader.*
import com.d4guilar.shelfos.domain.library.ReadingDirection

@Composable
fun ReaderAppearance(preferences: ReaderPreferences, typography: Boolean, onDismiss: () -> Unit,
    onSave: (ReaderPreferences, Boolean) -> Unit) {
    var draft by remember { mutableStateOf(preferences) }
    var globally by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Reading appearance") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (typography) {
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
                Row { Checkbox(draft.justified ?: false, { draft = draft.copy(justified = it) }); Text("Justified text") }
                Row { Checkbox(draft.scroll ?: false, { draft = draft.copy(scroll = it) }); Text("Continuous scrolling") }
                PagePalette.entries.forEach { palette -> FilterChip(draft.palette == palette,
                    { draft = draft.copy(palette = palette) }, label = { Text(palette.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
            } else {
                Text("This publication uses fixed pages. Fonts and artwork retain their original appearance.")
                FitMode.entries.forEach { fit -> FilterChip(draft.fit == fit, { draft = draft.copy(fit = fit) },
                    label = { Text(if (fit == FitMode.PAGE) "Fit page" else "Fit width") }) }
            }
            Text("Reading direction")
            FilterChip(draft.direction == null, { draft = draft.copy(direction = null) }, label = { Text("Category default") })
            ReadingDirection.entries.forEach { direction -> FilterChip(draft.direction == direction,
                { draft = draft.copy(direction = direction) }, label = { Text(if (direction == ReadingDirection.RTL) "Right to left" else "Left to right") }) }
            Row { Checkbox(globally, { globally = it }); Text("Save as global reading defaults") }
            TextButton({ onSave(ReaderPreferences(), globally); onDismiss() }) { Text("Reset saved overrides") }
        }
    }, confirmButton = { TextButton({ onSave(draft, globally); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
