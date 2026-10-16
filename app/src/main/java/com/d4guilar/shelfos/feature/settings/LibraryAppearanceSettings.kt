// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.designsystem.shelfAction
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.*
import com.d4guilar.shelfos.feature.library.labelRes

/**
 * Settings → Library appearance (plan §8, D9/D10): the global cover shape and fit, and an optional override per
 * category. Every control is a radio row or a stepper reachable by touch, keyboard, D-pad and controller; no text entry.
 */
@Composable
fun LibraryAppearanceSection(covers: CoverPresentations, onGlobal: (CoverPresentation) -> Unit,
    onOverride: (MediaCategory, CoverPresentation?) -> Unit) {
    val t = LocalShelfTokens.current
    Text(stringResource(R.string.settings_library_appearance_title), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.settings_library_appearance_description), color = t.colors.secondary)
    PresentationChoices(covers.global, "cover_global", onGlobal)
    Text(stringResource(R.string.settings_cover_per_category), style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() })
    var open by rememberSaveable { mutableStateOf<MediaCategory?>(null) }
    MediaCategory.entries.forEach { category ->
        val override = covers.overrides[category]
        val tag = "cover_category_${category.name.lowercase()}"
        val summary = override?.let { stringResource(R.string.settings_cover_override_summary, presentationLabel(it)) }
            ?: stringResource(R.string.settings_cover_default_summary, presentationLabel(covers.inherited(category)))
        val expanded = open == category
        val toggle = { open = if (expanded) null else category }
        // An expand/collapse control, not a selectable option: its state is announced as expanded/collapsed.
        val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
        Row(Modifier.fillMaxWidth().testTag(tag).shelfAction(onClick = toggle).semantics {
            stateDescription = state
            if (expanded) collapse { toggle(); true } else expand { toggle(); true }
        }.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(category.labelRes()))
                // Polite: announces "Use default" ↔ "Category override" changes once, after the radio row's own announcement.
                Text(summary, color = t.colors.secondary, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("${tag}_summary").semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text(if (expanded) "−" else "+", color = t.colors.secondary, modifier = Modifier.clearAndSetSemantics { })
        }
        if (expanded) Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RadioRow(stringResource(R.string.settings_cover_use_default), override == null, "${tag}_default") { onOverride(category, null) }
            // An explicit override starts from what the category shows now, so enabling it changes nothing visible.
            RadioRow(stringResource(R.string.settings_cover_category_override), override != null, "${tag}_override") {
                if (override == null) onOverride(category, covers.inherited(category))
            }
            if (override != null) PresentationChoices(override, tag) { onOverride(category, it) }
        }
        HorizontalDivider(color = t.colors.divider)
    }
}

@Composable
private fun PresentationChoices(p: CoverPresentation, tag: String, onChange: (CoverPresentation) -> Unit) {
    val t = LocalShelfTokens.current
    Text(stringResource(R.string.settings_cover_shape), style = MaterialTheme.typography.labelLarge)
    CoverShape.entries.forEach { shape ->
        RadioRow(shapeLabel(shape, p), p.shape == shape, "${tag}_shape_${shape.name.lowercase()}") {
            if (p.shape != shape) onChange(if (shape == CoverShape.CUSTOM) p.copy(shape = shape, customWidth = 2, customHeight = 3)
                else CoverPresentation(shape, p.fit))
        }
    }
    if (p.shape == CoverShape.CUSTOM) {
        val w = p.customWidth!!; val h = p.customHeight!!
        Stepper(stringResource(R.string.settings_cover_width), w, "${tag}_width",
            decrease = coverPresentation(p.shape, p.fit, w - 1, h), increase = coverPresentation(p.shape, p.fit, w + 1, h), onChange)
        Stepper(stringResource(R.string.settings_cover_height), h, "${tag}_height",
            decrease = coverPresentation(p.shape, p.fit, w, h - 1), increase = coverPresentation(p.shape, p.fit, w, h + 1), onChange)
    }
    Text(stringResource(R.string.settings_cover_fit), style = MaterialTheme.typography.labelLarge)
    val original = p.shape == CoverShape.ORIGINAL
    CoverFit.entries.forEach { fit ->
        RadioRow(stringResource(fit.labelRes()), p.fit == fit && !original, "${tag}_fit_${fit.name.lowercase()}", enabled = !original) {
            onChange(p.copy(fit = fit))
        }
    }
    if (original) Text(stringResource(R.string.settings_cover_fit_original_note), color = t.colors.secondary,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp).testTag("${tag}_fit_note"))
}

@Composable
private fun RadioRow(label: String, selected: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalShelfTokens.current
    Row(Modifier.fillMaxWidth().testTag(tag).shelfAction(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 12.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (selected) "●" else "○", color = t.colors.secondary)
        Text(label, color = if (enabled) t.colors.ink else t.colors.secondary)
    }
}

/**
 * Two buttons around the value. A step that would leave 1…20 or the 1:2…2:1 bound is unavailable, never clamped silently:
 * the button stays focusable (a D-pad user at the limit keeps focus), activating it does nothing, and it is exposed as
 * disabled. The value is read as "Width: 3" and changes are announced once through a polite live region.
 */
@Composable
private fun Stepper(label: String, value: Int, tag: String, decrease: CoverPresentation?, increase: CoverPresentation?,
    onChange: (CoverPresentation) -> Unit) {
    val t = LocalShelfTokens.current
    val spoken = stringResource(R.string.settings_cover_stepper_value, label, value)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).clearAndSetSemantics { })
        StepButton("−", stringResource(R.string.content_desc_decrease, label), "${tag}_decrease", decrease, onChange)
        Text(stringResource(R.string.settings_cover_stepper_number, value), Modifier.width(40.dp).testTag("${tag}_value")
            .clearAndSetSemantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
        StepButton("+", stringResource(R.string.content_desc_increase, label), "${tag}_increase", increase, onChange)
    }
    HorizontalDivider(color = t.colors.divider)
}

@Composable
private fun StepButton(symbol: String, description: String, tag: String, target: CoverPresentation?, onChange: (CoverPresentation) -> Unit) {
    val t = LocalShelfTokens.current
    Box(Modifier.size(48.dp).testTag(tag)
        .shelfAction(onClick = { target?.let(onChange) })
        .semantics { contentDescription = description; if (target == null) disabled() }, contentAlignment = Alignment.Center) {
        Text(symbol, color = if (target != null) t.colors.ink else t.colors.secondary, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun shapeLabel(shape: CoverShape, current: CoverPresentation): String = when (shape) {
    CoverShape.ORIGINAL -> stringResource(R.string.cover_shape_original)
    CoverShape.CUSTOM -> if (current.shape == CoverShape.CUSTOM)
        stringResource(R.string.cover_shape_custom_ratio, current.customWidth!!, current.customHeight!!) else stringResource(R.string.cover_shape_custom)
    else -> stringResource(R.string.cover_shape_ratio, shape.width, shape.height)
}

/** "2:3 · Crop to fill", "Original" (fit does not apply), "Custom 3:5 · Fit entire artwork". */
@Composable
private fun presentationLabel(p: CoverPresentation): String {
    val shape = shapeLabel(p.shape, p)
    return if (p.shape == CoverShape.ORIGINAL) shape else stringResource(R.string.settings_cover_summary, shape, stringResource(p.fit.labelRes()))
}

private fun CoverFit.labelRes(): Int = when (this) {
    CoverFit.CROP -> R.string.cover_fit_crop
    CoverFit.FIT -> R.string.cover_fit_fit
}
