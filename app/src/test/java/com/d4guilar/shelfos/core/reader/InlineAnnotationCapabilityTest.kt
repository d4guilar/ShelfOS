// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import org.junit.Assert.*
import org.junit.Test

/** The capability gate keys on the real WebView version, never on the Android API level. */
class InlineAnnotationCapabilityTest {
    private fun ua(major: Int) = "Mozilla/5.0 (Linux; Android 13; RP5) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/$major.0.0.0 Mobile Safari/537.36"
    private fun info(major: Int) = WebViewInfo("com.google.android.webview", "$major.0.0.0", ua(major))

    @Test fun observedLegacyWebViewFiftyTwoIsUnsupported() {
        val webView52 = WebViewInfo(null, null, "Mozilla/5.0 (Linux; Android 7.0; Android SDK built for x86_64 Build/NYC) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/52.0.2743.100 Mobile Safari/537.36")
        assertEquals(52, webView52.chromiumMajor)
        assertEquals(InlineAnnotationSupport.UNSUPPORTED_LEGACY_WEBVIEW, InlineAnnotationCapability.evaluate(webView52))
        assertFalse(InlineAnnotationCapability.evaluate(webView52).offersInlineAnnotations)
    }

    @Test fun observedWebViewOneHundredNineIsSupported() {
        val info = WebViewInfo("com.android.webview", "109.0.5414.123", ua(109))
        assertEquals(109, info.chromiumMajor)
        assertEquals(InlineAnnotationSupport.SUPPORTED, InlineAnnotationCapability.evaluate(info))
        assertTrue(InlineAnnotationCapability.evaluate(info).offersInlineAnnotations)
    }

    @Test fun thresholdIsTheHighestVersionReadiumsScriptStaticallyRequires() {
        assertEquals(69, InlineAnnotationCapability.MIN_CHROMIUM_MAJOR)
        assertEquals(InlineAnnotationSupport.UNSUPPORTED_LEGACY_WEBVIEW, InlineAnnotationCapability.evaluate(info(68)))
        assertEquals(InlineAnnotationSupport.SUPPORTED, InlineAnnotationCapability.evaluate(info(69)))
        assertEquals(InlineAnnotationSupport.UNSUPPORTED_LEGACY_WEBVIEW, InlineAnnotationCapability.evaluate(info(54)))
    }

    @Test fun unidentifiableWebViewIsConservativelyNotOffered() {
        assertEquals(InlineAnnotationSupport.UNKNOWN, InlineAnnotationCapability.evaluate(null))
        assertEquals(InlineAnnotationSupport.UNKNOWN, InlineAnnotationCapability.evaluate(WebViewInfo(null, null, null)))
        assertEquals(InlineAnnotationSupport.UNKNOWN, InlineAnnotationCapability.evaluate(WebViewInfo("x", "dev-build", "no version here")))
        assertFalse(InlineAnnotationSupport.UNKNOWN.offersInlineAnnotations)
    }

    @Test fun whenPackageVersionAndUserAgentDisagreeTheLowerWins() {
        assertEquals(60, WebViewInfo("p", "120.0.0.1", ua(60)).chromiumMajor)
        assertEquals(60, WebViewInfo("p", "60.0.0.1", ua(120)).chromiumMajor)
        assertEquals(InlineAnnotationSupport.UNSUPPORTED_LEGACY_WEBVIEW, InlineAnnotationCapability.evaluate(WebViewInfo("p", "60.0.0.1", ua(120))))
    }

    @Test fun packageVersionAloneIsEnough() {
        assertEquals(InlineAnnotationSupport.SUPPORTED, InlineAnnotationCapability.evaluate(WebViewInfo("p", "130.0.6723.58", null)))
        assertEquals(InlineAnnotationSupport.SUPPORTED, InlineAnnotationCapability.evaluate(WebViewInfo(null, null, ua(120))))
    }
}
