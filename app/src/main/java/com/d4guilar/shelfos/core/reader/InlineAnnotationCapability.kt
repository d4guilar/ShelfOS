// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView

/** The Android System WebView that will actually render the EPUB, as reported by the platform. */
data class WebViewInfo(val packageName: String?, val versionName: String?, val userAgent: String?) {
    /** Chromium major version, or null when it cannot be read. If the package version and the user agent disagree, the lower one wins. */
    val chromiumMajor: Int? get() = listOfNotNull(
        userAgent?.let { Regex("""Chrome/(\d+)\.""").find(it)?.groupValues?.get(1)?.toIntOrNull() },
        versionName?.let { Regex("""^(\d+)\.""").find(it)?.groupValues?.get(1)?.toIntOrNull() },
    ).minOrNull()
}

enum class InlineAnnotationSupport {
    /** The WebView meets the documented requirements of Readium's bundled scripts; inline highlights are offered. */
    SUPPORTED,
    /** A WebView old enough that Readium's decoration script fails (observed: Chromium 52). Inline text annotations are not offered. */
    UNSUPPORTED_LEGACY_WEBVIEW,
    /** The WebView could not be identified. Treated like unsupported: nothing is offered that might silently fail. */
    UNKNOWN;

    val offersInlineAnnotations: Boolean get() = this == SUPPORTED
}

/** Resolves the current WebView. Injectable so the capability rule is JVM-testable and debug hosts can simulate a legacy WebView. */
fun interface WebViewInfoProvider { fun current(context: Context): WebViewInfo? }

/**
 * Capability gate for inline (in-text) highlights and selection-anchored notes. It is keyed on the real WebView
 * version, never on the Android API level: a user can update the WebView on any Android release.
 *
 * Evidence for [MIN_CHROMIUM_MAJOR] (see docs/VALIDATION.md, "PHASE 4B"): Readium 3.4.0's bundled
 * `readium-reflowable.js` (not polyfilled) calls `Object.entries` and `document.body.append` on the decoration path
 * (both Chromium 54), `ResizeObserver` at load (Chromium 64) and `Array.prototype.flatMap` (Chromium 69). Chromium 52
 * was observed to fail all three runtime errors and render 0 of 3 decorations; Chromium 109 renders them correctly.
 * Nothing between 52 and 109 was executed. 69 is the highest platform version the script statically requires, so it is
 * the narrowest conservative threshold the evidence supports: some WebViews in 54..68 might in fact work, and they are
 * deliberately not offered inline annotations rather than risking a silently missing highlight.
 */
object InlineAnnotationCapability {
    const val MIN_CHROMIUM_MAJOR = 69

    fun evaluate(info: WebViewInfo?): InlineAnnotationSupport {
        val major = info?.chromiumMajor ?: return InlineAnnotationSupport.UNKNOWN
        return if (major >= MIN_CHROMIUM_MAJOR) InlineAnnotationSupport.SUPPORTED else InlineAnnotationSupport.UNSUPPORTED_LEGACY_WEBVIEW
    }
}

/** Reads the real WebView through public platform APIs only. Call on the main thread. A failure is reported as null, never thrown. */
object SystemWebViewInfoProvider : WebViewInfoProvider {
    override fun current(context: Context): WebViewInfo? {
        val pkg = runCatching { if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null }.getOrNull()
        val agent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        if (pkg == null && agent == null) return null
        return WebViewInfo(pkg?.packageName, pkg?.versionName, agent)
    }
}
