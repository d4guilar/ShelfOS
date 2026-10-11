// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.feature.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import com.d4guilar.shelfos.core.reader.InlineAnnotationCapability
import com.d4guilar.shelfos.core.reader.InlineAnnotationSupport

/**
 * Debug-only manual-UAT host (Phase 4B). It IS the production [EpubActivity] reading a synthetic book whose annotations were
 * seeded into Room by `UatLauncherActivity`; it only adds an on-screen result banner so a tester without ADB can read
 * PASS/FAIL. The banner measures through read-only DOM probes (`evaluateJavascript`, allowed in debug harness code only):
 * how many ShelfOS decorations Readium has drawn, how long that took from launch, and the worst main-thread frame gap.
 * It changes no reader behavior. On a WebView without inline-annotation support, the expected outcome is a clear
 * "SKIPPED (capability gate)" banner and a stable app.
 */
class UatStressActivity : EpubActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var banner: TextView
    private var startedAt = 0L
    private var expected = 0
    private var maxFrameGapMs = 0L
    private var lastFrameNanos = 0L
    private var finishedAtMs = -1L
    private var firstDrawnAtMs = -1L
    private var lastCount = 0
    private var done = false
    private lateinit var support: InlineAnnotationSupport

    private val frames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNanos != 0L) maxFrameGapMs = maxOf(maxFrameGapMs, (frameTimeNanos - lastFrameNanos) / 1_000_000)
            lastFrameNanos = frameTimeNanos
            if (!done) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedAt = SystemClock.elapsedRealtime()
        expected = intent.getIntExtra(EXTRA_EXPECTED, 0)
        support = InlineAnnotationCapability.evaluate(runCatching { createWebViewInfoProvider().current(this) }.getOrNull())
        banner = TextView(this).apply {
            setBackgroundColor(Color.argb(220, 0, 0, 0)); setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; textSize = 13f
            setPadding(24, 16, 24, 16)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        addContentView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        Choreographer.getInstance().postFrameCallback(frames)
        handler.post(poll)
    }

    override fun onDestroy() { done = true; handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun visibleWebView(): WebView? {
        var best: WebView? = null; var area = 0
        fun walk(view: View?) {
            when (view) {
                is WebView -> { val r = Rect(); if (view.isShown && view.getGlobalVisibleRect(r) && r.width() * r.height() > area) { area = r.width() * r.height(); best = view } }
                is ViewGroup -> for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(window.decorView)
        return best
    }

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (!support.offersInlineAnnotations) {
                banner.text = "UAT 500-decoration stress: SKIPPED (capability gate: $support)\n" +
                    "Inline annotations are not offered on this WebView. Expected on old WebViews. Reader stayed stable."
                done = true; return
            }
            val webView = visibleWebView()
            if (webView == null) { render(elapsed, "waiting for the page"); handler.postDelayed(this, 400); return }
            webView.evaluateJavascript("document.querySelectorAll('[data-group=\"shelfos.annotations\"] > *').length") { raw ->
                if (done) return@evaluateJavascript
                val count = raw?.trim('"')?.toIntOrNull() ?: 0
                lastCount = count
                if (count > 0 && firstDrawnAtMs < 0) firstDrawnAtMs = SystemClock.elapsedRealtime() - startedAt
                if (count >= expected && finishedAtMs < 0) finishedAtMs = SystemClock.elapsedRealtime() - startedAt
                val finished = finishedAtMs >= 0
                val timedOut = SystemClock.elapsedRealtime() - startedAt > TIMEOUT_MS
                render(SystemClock.elapsedRealtime() - startedAt, if (finished) "complete" else if (timedOut) "timed out" else "applying")
                if (finished || timedOut) { done = true; return@evaluateJavascript }
                handler.postDelayed(this, 300)
            }
        }
    }

    private fun render(elapsedMs: Long, phase: String) {
        val pass = finishedAtMs >= 0
        val verdict = when { pass -> "PASS"; phase == "timed out" -> "FAIL"; else -> "RUNNING" }
        banner.text = "UAT 500-decoration stress: $verdict ($phase)\n" +
            "decorations drawn: $lastCount / $expected\n" +
            "first drawn: ${if (firstDrawnAtMs >= 0) "$firstDrawnAtMs ms" else "-"} after launch   all drawn: ${if (finishedAtMs >= 0) "$finishedAtMs ms" else "-"}\n" +
            "worst main-thread frame gap: $maxFrameGapMs ms   elapsed: $elapsedMs ms\n" +
            "(includes opening the book, Room read and exact-text verification of every annotation)"
    }

    companion object {
        private const val EXTRA_EXPECTED = "uatExpected"
        private const val TIMEOUT_MS = 120_000L
        fun intent(context: Context, itemId: String, expected: Int): Intent =
            Intent(context, UatStressActivity::class.java).putExtra("itemId", itemId).putExtra(EXTRA_EXPECTED, expected)
    }
}
