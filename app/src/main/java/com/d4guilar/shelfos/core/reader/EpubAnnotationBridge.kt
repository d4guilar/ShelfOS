// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/*
 * Phase 4B: the ShelfOS-owned adapter that keeps every Readium selection/decoration type inside core.reader
 * (ADR-0003). It exposes only ShelfOS value types. It injects NO JavaScript: selection and decorations are Readium's
 * own APIs backed by Readium's own bundled scripts, and the Decoration.id is always the Annotation.id.
 */

/** Actions offered in the text-selection ActionMode. */
enum class EpubSelectionAction { HIGHLIGHT, ADD_NOTE, COPY }

/** Localized titles for the selection menu, resolved by the UI layer (the bridge owns no UI strings). */
data class SelectionMenuLabels(val highlight: String, val addNote: String, val copy: String)

class EpubAnnotationBridge {
    /**
     * Whether this WebView supports inline annotations (see [InlineAnnotationCapability]). Must be set before the
     * navigator is created. When false no custom selection menu or decoration listener is installed and the platform's
     * ordinary selection behavior is left untouched.
     */
    var inlineSupported: Boolean = false
    var menuLabels: SelectionMenuLabels? = null
    var onSelectionAction: ((EpubSelectionAction) -> Unit)? = null
    /** Id of the tapped annotation. Return true when handled (the tap then does not toggle reader chrome). */
    var onMarkActivated: ((String) -> Boolean)? = null

    private var navigator: EpubNavigatorFragment? = null
    private var actionMode: ActionMode? = null

    /** True between the selection ActionMode being created and destroyed. Main thread only. */
    val selectionActive: Boolean get() = actionMode != null

    private val decorationListener = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean =
            onMarkActivated?.invoke(event.decoration.id) ?: false
    }

    /**
     * The ActionMode callback handed to Readium's `Configuration.selectionActionModeCallback`, or null when inline
     * annotations are unsupported. Readium uses this callback INSTEAD of the platform's, so ShelfOS also offers Copy;
     * Select all and platform share/search actions are not available through Readium's callback.
     */
    internal fun selectionCallback(): ActionMode.Callback? {
        if (!inlineSupported) return null
        return object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val labels = menuLabels ?: return false
                menu.add(Menu.NONE, ID_HIGHLIGHT, 0, labels.highlight)
                menu.add(Menu.NONE, ID_NOTE, 1, labels.addNote)
                menu.add(Menu.NONE, ID_COPY, 2, labels.copy)
                actionMode = mode
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val action = when (item.itemId) {
                    ID_HIGHLIGHT -> EpubSelectionAction.HIGHLIGHT
                    ID_NOTE -> EpubSelectionAction.ADD_NOTE
                    ID_COPY -> EpubSelectionAction.COPY
                    else -> return false
                }
                onSelectionAction?.invoke(action)
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) { if (actionMode === mode) actionMode = null }
        }
    }

    internal fun attach(target: EpubNavigatorFragment) { navigator = target }

    internal fun detach(target: EpubNavigatorFragment) {
        if (navigator === target) { navigator = null; actionMode = null }
    }

    /** The current selection in ShelfOS terms, or null when there is none (or the navigator is not attached). */
    suspend fun readSelection(): EpubSelectionRead? {
        val selection = navigator?.currentSelection() ?: return null
        val locator = selection.locator
        return EpubSelectionRead(locator.toJSON().toString(), locator.text.highlight, locator.locations.totalProgression)
    }

    /** Clears the native selection and ends its ActionMode. Harmless when nothing is selected. */
    fun clearSelection() {
        navigator?.clearSelection()
        actionMode?.finish()
        actionMode = null
    }

    /**
     * Back precedence step 1: if a text selection is active, dismisses it and returns true; the caller must then
     * stay in the reader and not touch chrome. Returns false when there is nothing to dismiss.
     */
    fun dismissSelection(): Boolean {
        if (!selectionActive) return false
        clearSelection()
        return true
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    /**
     * Applies the whole annotation group to [target] (Readium diffs it against what is already drawn, so repeating
     * this is idempotent and never duplicates). Must only run while the navigator is STARTED.
     */
    internal suspend fun apply(target: EpubNavigatorFragment, marks: List<EpubMark>, tone: ReaderTone) {
        val decorations = marks.mapNotNull { mark ->
            val locator = runCatching { Locator.fromJSON(JSONObject(mark.locatorJson)) }.getOrNull() ?: return@mapNotNull null
            val style = when (mark.shape) {
                EpubMarkShape.HIGHLIGHT -> Decoration.Style.Highlight(tint = AnnotationStyles.tint(mark.styleKey, tone))
                EpubMarkShape.UNDERLINE -> Decoration.Style.Underline(tint = AnnotationStyles.noteUnderlineTint(tone))
            }
            Decoration(mark.id, locator, style)
        }
        try { target.applyDecorations(decorations, GROUP) } catch (e: CancellationException) { throw e }
        // A stale or detached fragment must never crash the reader; the next STARTED pass reapplies the group.
        catch (_: IllegalStateException) { }
    }

    /** Registers the tap listener for as long as the caller stays suspended (cancelled with the STARTED block). */
    internal suspend fun listenForMarkTaps(target: EpubNavigatorFragment): Nothing {
        target.addDecorationListener(GROUP, decorationListener)
        try { awaitCancellation() } finally { runCatching { target.removeDecorationListener(decorationListener) } }
    }

    companion object {
        /** The single decoration group ShelfOS owns. */
        const val GROUP = "shelfos.annotations"
        private const val ID_HIGHLIGHT = 0x5301
        private const val ID_NOTE = 0x5302
        private const val ID_COPY = 0x5303
    }
}
