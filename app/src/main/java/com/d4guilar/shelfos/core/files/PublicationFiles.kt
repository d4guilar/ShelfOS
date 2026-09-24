// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

data class PreparedImport(val item: LibraryItem, val acquiredGrant: Boolean)

class PublicationFiles(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val directory get() = File(context.filesDir, "publications").also { it.mkdirs() }

    fun open(item: LibraryItem): ParcelFileDescriptor = if (item.managedPath != null)
        ParcelFileDescriptor.open(ownedFile(item.managedPath), ParcelFileDescriptor.MODE_READ_ONLY)
    else resolver.openFileDescriptor(Uri.parse(item.sourceUri), "r") ?: throw PublicationException("The source is unavailable.")

    private fun ownedFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.parentFile == directory.canonicalFile) { "Invalid managed source" }
        return file
    }

    suspend fun prepare(uri: Uri, copy: Boolean, stage: (String) -> Unit): PreparedImport = withContext(Dispatchers.IO) {
        var name = "Publication"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = cursor.getString(n) ?: name
                if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s).takeIf { it >= 0 }
            }
        }
        val existingGrant = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        var acquired = false
        var owned: File? = null
        try {
            if (!copy && !existingGrant) {
                try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); acquired = true }
                catch (_: SecurityException) { throw CopyRequired() }
            }
            val id = UUID.randomUUID().toString()
            if (copy) {
                stage("Copying for offline access…")
                val root = directory
                if (size != null && root.usableSpace < size + 64L * 1024 * 1024)
                    throw PublicationException("There is not enough storage for a private copy.")
                owned = File(root, "$id.part")
                resolver.openInputStream(uri)?.use { input -> requireNotNull(owned).outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (root.usableSpace < 16L * 1024 * 1024) throw PublicationException("Storage is full. The incomplete copy will be removed.")
                        output.write(buffer, 0, count); total += count
                        stage(if (size != null && size!! > 0) "Copying… ${total * 100 / size!!}%" else "Copying… ${total / (1024 * 1024)} MB")
                    }
                    size = total
                } } ?: throw PublicationException("The source is unavailable.")
            }
            stage("Inspecting publication…")
            val provisional = LibraryItem(id, name.substringBeforeLast('.', name), category = MediaCategory.BOOK,
                sourceUri = uri.toString(), format = PublicationFormat.PDF, fileName = name, byteSize = size, managedPath = owned?.path)
            val format = open(provisional).use { descriptor ->
                try { Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET) }
                catch (_: android.system.ErrnoException) { throw CopyRequired() }
                val head = ByteArray(1024)
                val count = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { it.read(head) }
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                if (count > 0 && head.copyOf(count).toString(Charsets.ISO_8859_1).contains("%PDF-")) PublicationFormat.PDF
                else if (count >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) {
                    ZipFile("/proc/self/fd/${descriptor.fd}").use { zip ->
                        ArchivePolicy.entries(zip)
                        val mime = zip.getEntry("mimetype")
                        if (mime != null && mime.size in 1..128 && zip.getInputStream(mime).use { it.readBytes().toString(Charsets.UTF_8).trim() } == "application/epub+zip") {
                            if (zip.getEntry("META-INF/container.xml") == null) throw PublicationException("This EPUB has no publication container.")
                            PublicationFormat.EPUB
                        } else {
                            ArchivePolicy.pages(zip)
                            PublicationFormat.CBZ
                        }
                    }
                } else throw PublicationException("Choose a PDF, EPUB or CBZ publication.")
            }
            currentCoroutineContext().ensureActive()
            if (owned != null) {
                val completed = File(directory, "$id.${format.name.lowercase()}")
                if (!owned.renameTo(completed)) throw PublicationException("The local copy could not be finalized.")
                owned = completed
            }
            PreparedImport(provisional.copy(format = format,
                category = if (format == PublicationFormat.CBZ) MediaCategory.COMIC else MediaCategory.BOOK,
                managedPath = owned?.path), acquired)
        } catch (error: Throwable) {
            owned?.delete()
            if (acquired) release(uri.toString())
            throw error
        }
    }

    fun discard(prepared: PreparedImport, sourceStillUsed: Boolean) {
        prepared.item.managedPath?.let { ownedFile(it).delete() }
        if (prepared.acquiredGrant && !sourceStillUsed) release(prepared.item.sourceUri)
    }

    fun removeOwnedSource(item: LibraryItem, sourceStillUsed: Boolean) {
        item.managedPath?.let { ownedFile(it).delete() }
        if (!sourceStillUsed) release(item.sourceUri)
    }

    private fun release(uri: String) {
        try { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Already revoked. */ }
    }

    suspend fun available(item: LibraryItem): Boolean = withContext(Dispatchers.IO) {
        try { open(item).use { true } } catch (_: Exception) { false }
    }
}
