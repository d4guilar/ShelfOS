// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Test-only helpers for Phase 3E-B's real-RAR test matrix: a tiny,
 * dependency-free uudecode implementation (no external `uudecode`
 * executable, no production packaging - this lives entirely under
 * `androidTest`) plus small conveniences for decoding the vendored
 * upstream `.uu` fixtures under
 * `app/src/androidTest/assets/libarchive_fixtures/` into temporary binary
 * `.rar` files for a single test run.
 *
 * Decoded files are written under the instrumentation target context's
 * [Context.getCacheDir] and are temp-file-only: nothing here is ever
 * committed or packaged into app/src/main.
 */
object RarFixtures {

    // The fixture assets live in this test package's OWN APK (under
    // app/src/androidTest/assets/), not the target app-under-test's APK -
    // so they must be read via the instrumentation's own context, not
    // targetContext. (A prior revision of this helper used targetContext
    // and failed with FileNotFoundException on-device for exactly this
    // reason.)
    private val assetContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    // Decoded temp files are written to the TARGET app's cache dir. The
    // instrumentation (test-package) context's own cache dir was observed
    // on-device to fail with a permission error for this separately-
    // installed test package/process, while the target app's cache dir -
    // the same one every other instrumented test in this project already
    // writes to - works reliably.
    private val fileContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Decodes the classic Unix `uuencode` text format (the exact format
     * the vendored upstream `.uu` fixtures use) into raw bytes. Supports
     * the standard begin/data-lines/`\`/end structure; stops at the
     * zero-length (`` ` ``) line exactly as the format specifies.
     */
    fun uudecode(encoded: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (rawLine in encoded.lineSequence()) {
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isEmpty()) continue
            if (line.startsWith("begin ") || line == "end") continue

            val n = (line[0].code - 0x20) and 0x3F
            if (n == 0) break

            var produced = 0
            var i = 1
            while (produced < n && i + 3 <= line.length) {
                val c1 = (line[i].code - 0x20) and 0x3F
                val c2 = (line[i + 1].code - 0x20) and 0x3F
                val c3 = (line[i + 2].code - 0x20) and 0x3F
                val c4 = (line[i + 3].code - 0x20) and 0x3F

                val b0 = ((c1 shl 2) or (c2 shr 4)) and 0xFF
                val b1 = (((c2 and 0xF) shl 4) or (c3 shr 2)) and 0xFF
                val b2 = (((c3 and 0x3) shl 6) or c4) and 0xFF

                if (produced < n) {
                    out.write(b0); produced++
                }
                if (produced < n) {
                    out.write(b1); produced++
                }
                if (produced < n) {
                    out.write(b2); produced++
                }
                i += 4
            }
        }
        return out.toByteArray()
    }

    /**
     * Decodes [assetName] (a `.uu` file under `libarchive_fixtures/`) into
     * a fresh temp file under the cache dir, named [tempFileName]. The
     * caller owns the returned [File] and should delete it when done
     * (tests do this in `@After`/at the end of each test body).
     */
    fun decodeFixtureToTempFile(assetName: String, tempFileName: String): File {
        val encoded = assetContext.assets.open("libarchive_fixtures/$assetName").use { input ->
            input.readBytes().toString(Charsets.US_ASCII)
        }
        val bytes = uudecode(encoded)
        val file = File(fileContext.cacheDir, tempFileName)
        file.writeBytes(bytes)
        return file
    }

    /** Opens [file] read-only and detaches its fd, transferring ownership to the caller. */
    fun detachedReadFd(file: File): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return pfd.detachFd()
    }
}
