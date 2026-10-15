// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * App-private local cover artwork files (plan §7 rule 1, §18, §23.4, §23.5): `filesDir/covers/<libraryItemId>/`.
 * Every successful extraction is finalized under a NEW name `<slot>-<16 hex>.<ext>` (no source-derived text) and is
 * never overwritten or renamed over; temporary output is `<16 hex>.tmp`. `filesDir`, not `cacheDir`: the system must not
 * evict the Library's identity, and nothing here evicts covers automatically (D20).
 */
class CoverFiles(private val root: File, private val freeBytesOverride: (() -> Long)? = null, private val context: Context? = null) {
    constructor(context: Context) : this(File(context.filesDir, "covers"), null, context.applicationContext)

    private val random = SecureRandom()
    private fun token() = "%016x".format(random.nextLong())

    fun directory(libraryItemId: String): File {
        require(ITEM_ID.matches(libraryItemId)) { "unsafe library item id" }
        return File(root, libraryItemId)
    }

    /** Resolves a stored file name; anything that is not a name this class could have written is rejected. */
    fun file(libraryItemId: String, fileName: String): File {
        require(FILE_NAME.matches(fileName)) { "unsafe cover file name" }
        return File(directory(libraryItemId), fileName)
    }

    fun newTemporary(libraryItemId: String): File = File(directory(libraryItemId).also { it.mkdirs() }, "${token()}$TEMP")

    /**
     * Gives the validated [temporary] file a new unique final name (a same-directory rename) and returns it. The target
     * name is freshly drawn and checked absent first; the scheduler's single worker is the only writer of a cover
     * directory, so no other file can appear under that name in between. (Hard links, which would refuse an existing
     * name atomically, are denied for app data by SELinux on Android.)
     */
    fun finalize(libraryItemId: String, temporary: File, slot: String, extension: String): String {
        require(EXTENSION.matches(extension))
        val directory = directory(libraryItemId)
        repeat(8) {
            val name = "${slot.lowercase()}-${token()}.$extension"
            val target = File(directory, name)
            if (target.exists()) return@repeat
            if (!temporary.renameTo(target)) throw IOException("cannot finalize cover")
            return name
        }
        throw IOException("no unique cover name")
    }

    fun delete(libraryItemId: String, fileName: String) { runCatching { file(libraryItemId, fileName).delete() } }

    fun deleteItem(libraryItemId: String) { runCatching { directory(libraryItemId).deleteRecursively() } }

    /**
     * Startup sweep (§23.4 step 5), only after attempt reconciliation and before scheduling: deletes every file that is
     * not referenced by a row of its item (replaced covers, finalized-but-uncommitted files, `.tmp`), and directories
     * that end up empty (removed items). No age heuristic. Returns the number of files deleted.
     */
    fun sweep(referenced: Map<String, Set<String>>): Int {
        var deleted = 0
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) { if (dir.delete()) deleted++; return@forEach }
            val keep = referenced[dir.name].orEmpty()
            dir.listFiles()?.forEach { if (it.name !in keep && it.deleteRecursively()) deleted++ }
            if (dir.list().isNullOrEmpty()) dir.delete()
        }
        return deleted
    }

    /**
     * Free bytes for the low-storage guard (§18.1). API 26+: the minimum of `getAllocatableBytes` (which applies the
     * platform's own low-storage threshold) and `StatFs`; API 24/25 have neither method, so `StatFs` alone.
     */
    fun freeBytes(): Long {
        freeBytesOverride?.let { return it() }
        root.mkdirs()
        val statFs = StatFs(root.path).availableBytes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context != null) try {
            val storage = context.getSystemService(StorageManager::class.java)
            return minOf(statFs, storage.getAllocatableBytes(storage.getUuidForPath(root)))
        } catch (_: IOException) { /* StatFs alone is still a never-loosening figure. */ }
        return statFs
    }

    companion object {
        const val TEMP = ".tmp"
        private val ITEM_ID = Regex("[A-Za-z0-9-]{1,64}")
        private val EXTENSION = Regex("[a-z]{3,4}")
        private val FILE_NAME = Regex("[a-z]+-[0-9a-f]{16}\\.[a-z]{3,4}")
    }
}
