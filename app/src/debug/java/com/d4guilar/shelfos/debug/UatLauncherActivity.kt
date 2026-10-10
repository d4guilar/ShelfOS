// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.debug

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.d4guilar.shelfos.AppContainer
import com.d4guilar.shelfos.R
import com.d4guilar.shelfos.ShelfApplication
import com.d4guilar.shelfos.core.reader.AnnotationStyles
import com.d4guilar.shelfos.core.reader.InlineAnnotationCapability
import com.d4guilar.shelfos.core.reader.SystemWebViewInfoProvider
import com.d4guilar.shelfos.domain.annotations.AnnotationDraft
import com.d4guilar.shelfos.domain.annotations.AnnotationKind
import com.d4guilar.shelfos.domain.annotations.LocatorFormat
import com.d4guilar.shelfos.domain.library.LibraryItem
import com.d4guilar.shelfos.domain.library.MediaCategory
import com.d4guilar.shelfos.domain.library.PublicationFormat
import com.d4guilar.shelfos.feature.reader.EpubActivity
import com.d4guilar.shelfos.feature.reader.UatStressActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only manual UAT launcher for Phase 4B, used on a tablet without ADB. It never exists in a release build (debug
 * source set). It shows the device, Android version, WebView provider and exact version and ShelfOS's capability state,
 * and opens ONLY a generated synthetic EPUB (no user files). The tester then exercises the real reader: long-press
 * selection, Highlight, Add note, decorations, tapping one, rotation, jumping back to an annotation, Back with a selection.
 * A button seeds 500 valid highlights and opens a reader that shows apply time and PASS/FAIL on screen.
 */
class UatLauncherActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as ShelfApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = SystemWebViewInfoProvider.current(this)
        val support = InlineAnnotationCapability.evaluate(info)
        setContent {
            var status by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            MaterialTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.uat_title), style = MaterialTheme.typography.titleLarge)
                    Text("Device: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                        "WebView package: ${info?.packageName ?: "unknown"}\nWebView version: ${info?.versionName ?: "unknown"}\n" +
                        "Chromium major: ${info?.chromiumMajor ?: "unknown"}\nUser agent: ${info?.userAgent ?: "unknown"}")
                    Text("ShelfOS inline annotation capability: $support\n" +
                        (if (support.offersInlineAnnotations) "Inline Highlight and selection notes are OFFERED."
                        else "Inline Highlight and selection notes are NOT offered (position notes and bookmarks still work)") +
                        "\nRule: Chromium major >= ${InlineAnnotationCapability.MIN_CHROMIUM_MAJOR}.")
                    Button(enabled = !busy, onClick = { launchTask(setBusy = { busy = it }, setStatus = { status = it }) { openReader() } }) { Text(stringResource(R.string.uat_open_reader)) }
                    Button(enabled = !busy, onClick = { launchTask(setBusy = { busy = it }, setStatus = { status = it }) { runStress(it) } }) { Text(stringResource(R.string.uat_run_stress)) }
                    Button(enabled = !busy, onClick = { launchTask(setBusy = { busy = it }, setStatus = { status = it }) { reset() } }) { Text(stringResource(R.string.uat_reset)) }
                    if (status.isNotEmpty()) Text(status)
                    Text(stringResource(R.string.uat_checklist))
                }
            }
        }
    }

    private fun launchTask(setBusy: (Boolean) -> Unit, setStatus: (String) -> Unit, block: suspend ((String) -> Unit) -> String) {
        lifecycleScope.launch {
            setBusy(true)
            try { setStatus(block(setStatus)) } catch (e: Exception) { setStatus("Failed: ${e::class.java.simpleName}: ${e.message}") } finally { setBusy(false) }
        }
    }

    private suspend fun ensureBook(id: String, variant: String): LibraryItem {
        val file = File(filesDir, "publications/uat-$variant.epub")
        withContext(Dispatchers.IO) { SyntheticAnnotationEpub.write(file, variant) }
        val item = LibraryItem(id, "ShelfOS UAT synthetic book", "ShelfOS test", MediaCategory.BOOK, "test:$id", PublicationFormat.EPUB,
            file.name, file.length(), managedPath = file.path)
        if (container.library.publication(id).first() == null) container.library.add(item)
        return item
    }

    private suspend fun openReader(): String {
        ensureBook(BOOK_ID, "BASE")
        startActivity(EpubActivity.intent(this, BOOK_ID))
        return "Opened the synthetic book. Annotations you create persist until you press Reset."
    }

    private suspend fun runStress(setStatus: (String) -> Unit): String {
        container.library.remove(STRESS_ID)
        ensureBook(STRESS_ID, "LARGE")
        val locators = SyntheticAnnotationEpub.largeChapterLocators(chapter = 1, count = STRESS_COUNT)
        locators.forEachIndexed { index, (token, locator) ->
            if (index % 25 == 0) setStatus("Seeding highlights: $index / ${locators.size}")
            container.annotations.create(AnnotationDraft(STRESS_ID, AnnotationKind.HIGHLIGHT, LocatorFormat.READIUM_LOCATOR_1, locator,
                progressSnapshot = (index * 100 / locators.size / 4), orderKey = index / (locators.size * 4.0),
                selectedText = token, styleKey = AnnotationStyles.keys[index % AnnotationStyles.keys.size]))
        }
        startActivity(UatStressActivity.intent(this, STRESS_ID, locators.size))
        return "Seeded ${locators.size} highlights and opened the stress reader. The result banner shows on screen."
    }

    private suspend fun reset(): String {
        container.library.remove(BOOK_ID); container.library.remove(STRESS_ID)
        return "UAT books and all of their annotations were removed."
    }

    companion object {
        const val BOOK_ID = "uat-epub-annotations"
        const val STRESS_ID = "uat-epub-annotations-stress"
        const val STRESS_COUNT = 500
    }
}
