// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.content.Context
import android.content.Intent
import com.d4guilar.shelfos.core.reader.WebViewInfo
import com.d4guilar.shelfos.core.reader.WebViewInfoProvider

/**
 * Debug-only host that reports a legacy WebView (Chromium 52, the version observed on the API 24 emulator) to the
 * capability gate, so instrumentation can prove the fallback (no inline Highlight, no decorations, position notes and
 * bookmarks still work) on any device. The real reader is otherwise unchanged. Non-exported; absent from release builds.
 */
class EpubLegacyWebViewTestActivity : EpubActivity() {
    override fun createWebViewInfoProvider() = WebViewInfoProvider {
        WebViewInfo("com.android.webview", "52.0.2743.100",
            "Mozilla/5.0 (Linux; Android 7.0; Android SDK built for x86_64 Build/NYC) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/52.0.2743.100 Mobile Safari/537.36")
    }

    companion object {
        fun intent(context: Context, itemId: String): Intent =
            Intent(context, EpubLegacyWebViewTestActivity::class.java).putExtra("itemId", itemId)
    }
}
