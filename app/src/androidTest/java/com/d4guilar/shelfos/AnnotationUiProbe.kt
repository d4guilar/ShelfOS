// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Test-only helper that drives and observes the REAL EpubActivity through its real WebView: injected touch (long press,
 * tap) and read-only DOM probes through `evaluateJavascript`. The probes only measure (decoration element counts and
 * geometry, text positions); they never create, alter or style anything, and production code contains none of this.
 */
class AnnotationUiProbe(private val inst: Instrumentation, private val scenario: ActivityScenario<out Activity>,
    /** Lets Compose make progress while the test waits (the compose test rule only advances its frame clock when pumped). */
    private val idle: () -> Unit = {}) {
    /** Waits [ms] of real time while pumping Compose. */
    fun settle(ms: Long) { val end = SystemClock.uptimeMillis() + ms; do { idle(); SystemClock.sleep(40) } while (SystemClock.uptimeMillis() < end) }

    private fun decor(): View? { var view: View? = null; scenario.onActivity { view = it.window.decorView }; return view }

    /** The WebView currently showing the page: the attached WebView with the largest on-screen area (Readium keeps neighbours attached). */
    fun webView(): WebView? {
        var best: WebView? = null
        inst.runOnMainSync {
            var area = 0
            fun walk(view: View?) {
                when (view) {
                    is WebView -> { val r = Rect(); if (view.isShown && view.getGlobalVisibleRect(r) && r.width() * r.height() > area) { area = r.width() * r.height(); best = view } }
                    is ViewGroup -> for (i in 0 until view.childCount) walk(view.getChildAt(i))
                }
            }
            walk(decor())
        }
        return best
    }

    /** Evaluates [script] in the page; returns the raw JSON-encoded result or null on failure/timeout. */
    fun js(script: String, timeoutMs: Long = 5_000): String? {
        val latch = CountDownLatch(1); var out: String? = null
        val wv = webView() ?: return null
        inst.runOnMainSync { wv.evaluateJavascript(script) { out = it; latch.countDown() } }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) out else null
    }
    fun jsString(script: String): String? = js(script)?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull()?.let { v -> if (v == JSONObject.NULL) null else v.toString() } }

    /** Waits until a page document is complete, Readium's script is present and (optionally) [needText] is in it. */
    fun awaitReady(timeoutMs: Long = 40_000, needText: String? = null): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val ready = jsString("(function(){return document.readyState+'|'+(window.readium?'r':'-')+'|'+document.body.textContent.length})()")
            if (ready != null && ready.startsWith("complete|r|") && ready.substringAfterLast('|').toInt() > 100 &&
                (needText == null || jsString("document.body.textContent.indexOf(${JSONObject.quote(needText)})>=0") == "true")) { settle(400); return true }
            settle(250)
        }
        return false
    }

    /** Screen-pixel rect (left, top, right, bottom) of the first visible occurrence of [token], or null when it is not on screen. */
    fun tokenScreenRect(token: String): List<Float>? {
        val raw = jsString("""(function(tok){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n;
            while(n=w.nextNode()){var i=n.nodeValue.indexOf(tok);if(i>=0){var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+tok.length);
            var b=r.getBoundingClientRect();if(b.width>0&&b.left>=0&&b.left<window.innerWidth&&b.top>=0&&b.top<window.innerHeight)return JSON.stringify({l:b.left,t:b.top,r:b.right,b:b.bottom,iw:window.innerWidth});}}return null;})(${JSONObject.quote(token)})""")
            ?: return null
        val o = JSONObject(raw)
        val wv = webView() ?: return null
        val loc = IntArray(2); inst.runOnMainSync { wv.getLocationOnScreen(loc) }
        val scale = wv.width.toFloat() / o.getDouble("iw").toFloat()
        return listOf(loc[0] + o.getDouble("l").toFloat() * scale, loc[1] + o.getDouble("t").toFloat() * scale,
            loc[0] + o.getDouble("r").toFloat() * scale, loc[1] + o.getDouble("b").toFloat() * scale)
    }
    fun centerOf(rect: List<Float>) = ((rect[0] + rect[2]) / 2f) to ((rect[1] + rect[3]) / 2f)

    private fun pointer(action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        inst.sendPointerSync(event); event.recycle()
    }
    fun tap(x: Float, y: Float) { val t = SystemClock.uptimeMillis(); pointer(MotionEvent.ACTION_DOWN, x, y, t); SystemClock.sleep(60); pointer(MotionEvent.ACTION_UP, x, y, t); settle(450) }
    fun longPress(x: Float, y: Float, holdMs: Long = 1400) { val t = SystemClock.uptimeMillis(); pointer(MotionEvent.ACTION_DOWN, x, y, t); SystemClock.sleep(holdMs); pointer(MotionEvent.ACTION_UP, x, y, t); settle(700) }

    /** Real long-press on [token]; returns true when the token was on screen to press. */
    fun longPressToken(token: String): Boolean {
        val rect = tokenScreenRect(token) ?: return false
        val (x, y) = centerOf(rect); longPress(x, y); return true
    }

    private fun automation(): UiAutomation {
        val ua = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ua.serviceInfo = ua.serviceInfo.also { it.flags = it.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        return ua
    }

    /** Bounds of a visible UI element with exactly [text] in any window (including the floating selection toolbar). */
    fun findByText(text: String, timeoutMs: Long = 4_000): Rect? {
        val ua = automation(); val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val roots = ua.windows.mapNotNull { it.root } + listOfNotNull(ua.rootInActiveWindow)
            for (root in roots) {
                val hit = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
                if (hit != null) return Rect().also { hit.getBoundsInScreen(it) }
            }
            settle(200)
        }
        return null
    }
    fun tapText(text: String): Boolean { val r = findByText(text) ?: return false; tap(r.exactCenterX(), r.exactCenterY()); return true }

    // ---- read-only decoration probes ----

    /** Number of ShelfOS decoration elements Readium has drawn (one per applied decoration that resolved to text). */
    fun decorationCount(): Int = jsString("""document.querySelectorAll('[data-group="shelfos.annotations"] > *').length""")?.toIntOrNull() ?: -1

    /** Distinct `data-style` values currently drawn in the ShelfOS group (Readium's own marker for Highlight/Underline). */
    fun decorationStyles(): List<String> = jsString("""JSON.stringify(Array.prototype.map.call(document.querySelectorAll('[data-group="shelfos.annotations"] > *'),function(e){return e.getAttribute('data-style')||'?'}))""")
        ?.let { org.json.JSONArray(it).let { a -> (0 until a.length()).map(a::getString) } } ?: emptyList()

    /** Whether the on-screen center of [token]'s first occurrence lies inside a drawn ShelfOS decoration box. */
    fun tokenDecorated(token: String): Boolean = jsString("""(function(tok){
        var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n,rect=null;
        function inDec(node){var p=node;while(p){if(p.getAttribute&&p.getAttribute('data-group'))return true;p=p.parentNode;}return false;}
        while(n=w.nextNode()){if(inDec(n))continue;var i=n.nodeValue.indexOf(tok);if(i>=0){var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+tok.length);rect=r.getBoundingClientRect();break;}}
        if(!rect||rect.width<=0)return 'absent';var cx=(rect.left+rect.right)/2,cy=(rect.top+rect.bottom)/2;
        var els=document.querySelectorAll('[data-group="shelfos.annotations"] *');
        for(var k=0;k<els.length;k++){var b=els[k].getBoundingClientRect();if(b.width>1&&b.height>1&&cx>=b.left&&cx<=b.right&&cy>=b.top-2&&cy<=b.bottom+2)return 'covered';}
        return 'uncovered';})(${JSONObject.quote(token)})""") == "covered"

    /** Whether [token] is rendered inside the visible viewport (used by jump regression). */
    fun tokenVisible(token: String) = tokenScreenRect(token) != null

    fun pageTextLength(): Int = jsString("document.body.textContent.length")?.toIntOrNull() ?: -1

    /**
     * For [phrase]: how many occurrences exist in the page, and the paragraph token (`quillfeatherCxP`) of every occurrence whose
     * center lies inside a drawn ShelfOS decoration box. Used to prove a repeated phrase is decorated only where it was selected.
     */
    fun coveredOccurrences(phrase: String): Pair<Int, List<String>> {
        val raw = jsString("""(function(tok){
            function inDec(node){var p=node;while(p){if(p.getAttribute&&p.getAttribute('data-group'))return true;p=p.parentNode;}return false;}
            function para(node){var p=node.parentElement;while(p&&p.tagName.toUpperCase()!='P')p=p.parentElement;if(!p)return '?';var m=p.textContent.match(/quillfeather\d+x\d+/);return m?m[0]:'?';}
            var boxes=[];var els=document.querySelectorAll('[data-group="shelfos.annotations"] *');
            for(var k=0;k<els.length;k++){var b=els[k].getBoundingClientRect();if(b.width>1&&b.height>1)boxes.push(b);}
            var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n,occ=0,cov=[];
            while(n=w.nextNode()){if(inDec(n))continue;var from=0,i;
              while((i=n.nodeValue.indexOf(tok,from))>=0){from=i+tok.length;occ++;var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+tok.length);var b2=r.getBoundingClientRect();
                var cx=(b2.left+b2.right)/2,cy=(b2.top+b2.bottom)/2;
                for(var m=0;m<boxes.length;m++){var d=boxes[m];if(cx>=d.left&&cx<=d.right&&cy>=d.top-2&&cy<=d.bottom+2){cov.push(para(n));break;}}}}
            return JSON.stringify({occ:occ,cov:cov});})(${JSONObject.quote(phrase)})""") ?: return -1 to emptyList()
        val o = JSONObject(raw); val a = o.getJSONArray("cov")
        return o.getInt("occ") to (0 until a.length()).map { a.getString(it) }
    }
}
