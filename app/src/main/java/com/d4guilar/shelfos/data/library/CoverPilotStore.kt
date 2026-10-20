// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Properties

/** A validated, stored pilot image: [extension]/[mime] are what the bytes really are (never the server's claim). */
data class PilotImage(val mime: String, val extension: String, val width: Int, val height: Int, val sourceWidth: Int, val sourceHeight: Int, val bytes: Long)

/** The minimal durable reference for one Book's Cover Lab pick. It is a debug-pilot record only; it never becomes a production cover row. */
data class PilotSelection(
    val itemId: String, val fileName: String, val candidateKey: String, val providerId: String, val editionRef: String,
    val refKind: String, val refValue: String, val size: String, val mime: String, val width: Int, val height: Int, val bytes: Long,
    val downloadedAtMs: Long, val match: String,
)

/**
 * Private storage for the debug-only online-cover pilot (Phase 4.5C.2B, D1-C-DEV).
 *
 * - Selections: `filesDir/cover_lab/<libraryItemId>/` holding `selection.properties` and exactly one `img-<16 hex>.<ext>`. This is NOT
 *   under `filesDir/covers`, so [com.d4guilar.shelfos.core.files.CoverFiles.sweep] neither sees nor deletes it, and nothing here writes
 *   `publication_cover` (no USER or ONLINE row). At most [MAX_SELECTIONS] items keep a pick; the oldest is evicted first.
 * - Previews: `cacheDir/cover_lab/<libraryItemId>/pv-<candidate key>.<ext>`, bounded per item; the system may evict them.
 * File names are drawn here, never taken from remote text; candidate keys are validated before they reach a path. Writers are serialized by
 * the service's single transfer slot.
 */
class CoverPilotStore(private val root: File, private val previewRoot: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()
    private fun token() = "%016x".format(random.nextLong())

    private fun dir(base: File, itemId: String): File { require(ITEM_ID.matches(itemId)) { "unsafe library item id" }; return File(base, itemId) }

    fun newTemporary(itemId: String): File = File(dir(root, itemId).also { it.mkdirs() }, "${token()}.tmp")

    fun file(selection: PilotSelection): File {
        require(IMAGE_NAME.matches(selection.fileName)) { "unsafe pilot file name" }
        return File(dir(root, selection.itemId), selection.fileName)
    }

    /** The stored pick, or null if none, the manifest is unreadable, or its image file is gone (a half-finished state is never shown). */
    fun selection(itemId: String): PilotSelection? = runCatching {
        val manifest = File(dir(root, itemId), MANIFEST)
        if (!manifest.isFile) return null
        val p = Properties().apply { manifest.inputStream().use(::load) }
        val s = PilotSelection(itemId, p.getProperty("file") ?: return null, p.getProperty("candidate") ?: return null, p.getProperty("provider") ?: return null,
            p.getProperty("edition") ?: return null, p.getProperty("refKind") ?: return null, p.getProperty("refValue") ?: return null, p.getProperty("size") ?: return null,
            p.getProperty("mime") ?: return null, p.getProperty("width")?.toInt() ?: return null, p.getProperty("height")?.toInt() ?: return null,
            p.getProperty("bytes")?.toLong() ?: return null, p.getProperty("at")?.toLong() ?: return null, p.getProperty("match") ?: return null)
        if (s.width <= 0 || s.height <= 0 || !IMAGE_NAME.matches(s.fileName) || !file(s).isFile) null else s
    }.getOrNull()

    /**
     * Finalizes [temporary] (already validated, described by [image]) as the item's new pick and writes its manifest; only then is the previous
     * image deleted, so a failure at any step leaves the last valid pick intact. Returns the new selection.
     */
    fun commit(itemId: String, temporary: File, image: PilotImage, candidateKey: String, providerId: String, editionRef: String,
        refKind: String, refValue: String, size: String, match: String): PilotSelection {
        require(KEY.matches(candidateKey) && KEY.matches(editionRef) && EXT.matches(image.extension))
        val directory = dir(root, itemId).also { it.mkdirs() }
        val previous = selection(itemId)
        val name = "img-${token()}.${image.extension}"
        val target = File(directory, name)
        if (!temporary.renameTo(target)) throw IOException("cannot finalize pilot image")
        val selection = PilotSelection(itemId, name, candidateKey, providerId, editionRef, refKind, refValue, size, image.mime, image.width, image.height, image.bytes, clock(), match)
        try { writeManifest(directory, selection) } catch (e: Exception) { target.delete(); throw e }
        if (previous != null && previous.fileName != name) file(previous).delete()
        sweep(itemId)
        evictOldest()
        return selection
    }

    private fun writeManifest(directory: File, s: PilotSelection) {
        val p = Properties().apply {
            setProperty("file", s.fileName); setProperty("candidate", s.candidateKey); setProperty("provider", s.providerId); setProperty("edition", s.editionRef)
            setProperty("refKind", s.refKind); setProperty("refValue", s.refValue); setProperty("size", s.size); setProperty("mime", s.mime)
            setProperty("width", s.width.toString()); setProperty("height", s.height.toString()); setProperty("bytes", s.bytes.toString())
            setProperty("at", s.downloadedAtMs.toString()); setProperty("match", s.match)
        }
        val tmp = File(directory, "${token()}.tmp")
        tmp.outputStream().use { p.store(it, null); it.flush() }
        val target = File(directory, MANIFEST)
        // Android replaces atomically; the delete-then-rename fallback only exists for platforms whose renameTo refuses an existing target.
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("cannot write pilot manifest") } }
    }

    /** Removes the item's pick, its previews and everything else in its pilot directories. */
    fun remove(itemId: String) { runCatching { dir(root, itemId).deleteRecursively(); dir(previewRoot, itemId).deleteRecursively() } }

    /** Deletes every file in the item's pilot directory that the manifest does not reference (partial `.tmp`, replaced images). */
    fun sweep(itemId: String) {
        val directory = dir(root, itemId)
        val keep = selection(itemId)?.fileName
        directory.listFiles()?.forEach { if (it.name != MANIFEST && it.name != keep) it.deleteRecursively() }
        if (keep == null && directory.list().isNullOrEmpty()) directory.delete()
    }

    private fun evictOldest() {
        val picks = root.listFiles()?.mapNotNull { d -> if (d.isDirectory && ITEM_ID.matches(d.name)) selection(d.name) else null }.orEmpty()
        picks.sortedByDescending { it.downloadedAtMs }.drop(MAX_SELECTIONS).forEach { remove(it.itemId) }
    }

    fun previewFile(itemId: String, candidateKey: String): File? {
        require(KEY.matches(candidateKey))
        return dir(previewRoot, itemId).listFiles()?.firstOrNull { it.name.startsWith("pv-$candidateKey.") && it.length() > 0 }
    }

    fun newPreviewTemporary(itemId: String): File = File(dir(previewRoot, itemId).also { it.mkdirs() }, "${token()}.tmp")

    fun commitPreview(itemId: String, temporary: File, candidateKey: String, extension: String): File {
        require(KEY.matches(candidateKey) && EXT.matches(extension))
        val directory = dir(previewRoot, itemId).also { it.mkdirs() }
        directory.listFiles()?.filter { it.name.startsWith("pv-$candidateKey.") }?.forEach { it.delete() }
        val target = File(directory, "pv-$candidateKey.$extension")
        if (!temporary.renameTo(target)) throw IOException("cannot finalize preview")
        directory.listFiles()?.filter { it.name.startsWith("pv-") }?.sortedByDescending { it.lastModified() }?.drop(MAX_PREVIEWS)?.forEach { it.delete() }
        return target
    }

    fun clearPreviews(itemId: String) { runCatching { dir(previewRoot, itemId).deleteRecursively() } }

    companion object {
        const val MANIFEST = "selection.properties"
        const val MAX_SELECTIONS = 8
        const val MAX_PREVIEWS = 8
        private val ITEM_ID = Regex("[A-Za-z0-9-]{1,64}")
        private val KEY = Regex("[A-Za-z0-9-]{1,64}")
        private val EXT = Regex("[a-z]{3,4}")
        private val IMAGE_NAME = Regex("img-[0-9a-f]{16}\\.[a-z]{3,4}")
    }
}
