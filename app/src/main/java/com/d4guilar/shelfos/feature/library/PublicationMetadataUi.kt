// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.data.metadata.StoredMetadata
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.metadata.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

/** How screens read an item's stored metadata (4.5B); provided once by the app, like the cover artwork. */
class MetadataAccess(val observe: (String) -> Flow<StoredMetadata?>)

val LocalMetadataAccess = staticCompositionLocalOf<MetadataAccess?> { null }

/** [loaded] is false until the first read arrives, so an editor never opens on (and saves) blank placeholders. */
data class MetadataSnapshot(val loaded: Boolean, val stored: StoredMetadata?)

@Composable
fun rememberStoredMetadata(id: String): MetadataSnapshot {
    val access = LocalMetadataAccess.current
    val flow = remember(access, id) { (access?.observe?.invoke(id) ?: flowOf(null)).map { MetadataSnapshot(true, it) } }
    return flow.collectAsState(MetadataSnapshot(access == null, null)).value
}

/** The fields each category edits and shows; Documents stay conservative (no series/volume/issue). */
fun metadataFields(category: MediaCategory): List<MetadataField> = when (category) {
    MediaCategory.BOOK -> listOf(MetadataField.SUBTITLE, MetadataField.SERIES, MetadataField.VOLUME, MetadataField.PUBLISHER,
        MetadataField.PUBLISHED_DATE, MetadataField.LANGUAGE, MetadataField.DESCRIPTION)
    MediaCategory.COMIC -> listOf(MetadataField.SERIES, MetadataField.ISSUE, MetadataField.VOLUME, MetadataField.PUBLISHER,
        MetadataField.PUBLISHED_DATE, MetadataField.LANGUAGE, MetadataField.DESCRIPTION)
    MediaCategory.MANGA -> listOf(MetadataField.SERIES, MetadataField.VOLUME, MetadataField.PUBLISHER,
        MetadataField.PUBLISHED_DATE, MetadataField.LANGUAGE, MetadataField.DESCRIPTION)
    MediaCategory.DOCUMENT -> listOf(MetadataField.SUBTITLE, MetadataField.PUBLISHER, MetadataField.PUBLISHED_DATE,
        MetadataField.LANGUAGE, MetadataField.DESCRIPTION)
}

private val textFields = listOf(MetadataField.SUBTITLE, MetadataField.DESCRIPTION, MetadataField.PUBLISHER, MetadataField.PUBLISHED_DATE,
    MetadataField.LANGUAGE, MetadataField.SERIES, MetadataField.VOLUME, MetadataField.ISSUE)

fun MetadataField.labelRes(category: MediaCategory? = null): Int = when (this) {
    MetadataField.SUBTITLE -> R.string.metadata_subtitle; MetadataField.DESCRIPTION -> R.string.metadata_description
    MetadataField.PUBLISHER -> R.string.metadata_publisher; MetadataField.PUBLISHED_DATE -> R.string.metadata_published
    MetadataField.LANGUAGE -> R.string.metadata_language; MetadataField.SERIES -> R.string.metadata_series
    MetadataField.VOLUME -> R.string.metadata_volume
    MetadataField.ISSUE -> if (category == MediaCategory.COMIC) R.string.metadata_issue else R.string.metadata_number
    MetadataField.TITLE -> R.string.import_title_field; MetadataField.CREATOR -> R.string.import_creator_field
    MetadataField.IDENTIFIERS -> R.string.metadata_identifiers; MetadataField.CREDITS -> R.string.metadata_credits
}

private fun MetadataField.maxLength() = if (this == MetadataField.DESCRIPTION) 4000 else 300

/** Editable text for the new fields, kept across recreation; [result] is null while a date or language is invalid. */
class MetadataEditState(private val original: MetadataValues, private val texts: Map<MetadataField, MutableState<String>>) {
    fun text(field: MetadataField) = texts.getValue(field)
    fun invalid(field: MetadataField): Boolean {
        val t = texts.getValue(field).value.trim()
        return t.isNotEmpty() && when (field) {
            MetadataField.PUBLISHED_DATE -> normalizedDate(t) != t
            MetadataField.LANGUAGE -> normalizedLanguage(t) != t
            else -> false
        }
    }
    val result: MetadataValues? get() = if (textFields.any(::invalid)) null
        else textFields.fold(original) { v, f -> v.with(f, texts.getValue(f).value.trim().ifEmpty { null }) }
}

/**
 * The editor's original values, captured ONCE when editing starts and kept (saveable) for the whole session, so a value
 * analysis writes while the editor is open is never mistaken for a user change. Only the editable text fields are kept;
 * identifiers and credits are not edited here and stay untouched by the save.
 */
// An ArrayList instance (Bundle-saveable) behind a read-only type.
fun captureEditSnapshot(values: MetadataValues?): List<String> = ArrayList(textFields.map { (values?.get(it) as String?).orEmpty() })

fun snapshotValues(snapshot: List<String>): MetadataValues =
    textFields.foldIndexed(MetadataValues()) { i, v, f -> v.with(f, snapshot.getOrNull(i)?.ifEmpty { null }) }

@Composable
fun rememberMetadataEditState(id: String, original: MetadataValues): MetadataEditState {
    // Fixed field order, so the saveable keys are stable across recompositions.
    val texts = textFields.associateWith { f -> rememberSaveable(id, f.name) { mutableStateOf((original[f] as String?).orEmpty()) } }
    return remember(original, texts) { MetadataEditState(original, texts) }
}

/** The category's fields as text inputs; user text is only trimmed on save, never rewritten. */
@Composable
fun MetadataEditorFields(state: MetadataEditState, category: MediaCategory) {
    for (field in metadataFields(category)) {
        val text = state.text(field)
        val error = when {
            !state.invalid(field) -> null
            field == MetadataField.PUBLISHED_DATE -> stringResource(R.string.metadata_error_date)
            else -> stringResource(R.string.metadata_error_language)
        }
        OutlinedTextField(text.value, { text.value = it.take(field.maxLength()) }, label = { Text(stringResource(field.labelRes(category))) },
            isError = error != null, supportingText = error?.let { { Text(it) } },
            singleLine = field != MetadataField.DESCRIPTION, maxLines = if (field == MetadataField.DESCRIPTION) 6 else 1,
            modifier = Modifier.fillMaxWidth().testTag("edit_${field.name.lowercase()}"))
    }
}

/** Category-appropriate local metadata for the details pane; nothing is shown for fields the file did not declare. */
@Composable
fun MetadataSummary(category: MediaCategory, metadata: StoredMetadata?) {
    val values = metadata?.values ?: return
    val t = LocalShelfTokens.current
    val locale = LocalLocale.current.platformLocale
    Column(Modifier.testTag("metadata_summary"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.subtitle?.takeIf { MetadataField.SUBTITLE in metadataFields(category) }?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("metadata_subtitle"))
        }
        val lines = metadataFields(category).filter { it != MetadataField.SUBTITLE && it != MetadataField.DESCRIPTION }.mapNotNull { f ->
            val raw = values[f] as String? ?: return@mapNotNull null
            val shown = when (f) {
                MetadataField.PUBLISHED_DATE -> formatPublishedDate(raw, locale)
                MetadataField.LANGUAGE -> Locale.forLanguageTag(raw).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { raw }
                else -> raw
            }
            f to stringResource(R.string.detail_label_value, stringResource(f.labelRes(category)), shown)
        }
        val credits = if (category == MediaCategory.DOCUMENT) emptyList() else values.credits.groupBy { it.role }.map { (role, list) ->
            stringResource(R.string.detail_label_value, stringResource(role.labelRes()), list.joinToString(", ") { it.name })
        }
        for ((f, line) in lines) Text(line, color = t.colors.secondary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("metadata_${f.name.lowercase()}"))
        for (line in credits) Text(line, color = t.colors.secondary, style = MaterialTheme.typography.bodySmall)
        // The description is prose, not another field: it is set apart (2 dp column gap + this 10 dp) only when something precedes it.
        val hasFields = lines.isNotEmpty() || credits.isNotEmpty() || (values.subtitle != null && MetadataField.SUBTITLE in metadataFields(category))
        values.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (hasFields) 10.dp else 0.dp).testTag("metadata_description")) }
    }
}

/** Identifier lines for technical details: type codes are standard notation, not translated. */
fun identifierLines(metadata: StoredMetadata?): List<String> = metadata?.values?.identifiers.orEmpty().map { id ->
    when (id.type) { IdentifierType.ISBN_10, IdentifierType.ISBN_13 -> "ISBN ${id.value}"; IdentifierType.UPC_A -> "UPC ${id.value}"; IdentifierType.EAN_13 -> "EAN ${id.value}" }
}

fun CreditRole.labelRes(): Int = when (this) {
    CreditRole.WRITER -> R.string.credit_writer; CreditRole.PENCILLER -> R.string.credit_penciller; CreditRole.INKER -> R.string.credit_inker
    CreditRole.COLORIST -> R.string.credit_colorist; CreditRole.LETTERER -> R.string.credit_letterer
    CreditRole.COVER_ARTIST -> R.string.credit_cover_artist; CreditRole.EDITOR -> R.string.credit_editor; CreditRole.TRANSLATOR -> R.string.credit_translator
}

/** `YYYY-MM-DD` in the interface locale's medium date format; `YYYY-MM` and `YYYY` as stored (numeric, unambiguous). */
private fun formatPublishedDate(raw: String, locale: Locale): String {
    val parts = raw.split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return raw
    val calendar = Calendar.getInstance(locale).apply { clear(); set(parts[0], parts[1] - 1, parts[2]) }
    return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(calendar.time)
}
