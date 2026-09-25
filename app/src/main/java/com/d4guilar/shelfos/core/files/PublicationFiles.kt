// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.d4guilar.shelfos.domain.importing.*
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Unreferenced private copies that only an explicit cleanup may delete. */
data class PrivateCopyUsage(val count: Int, val bytes: Long)

/** Takes ownership of [descriptor]; protected, damaged and non-seekable documents map to explicit problems. */
fun openPdf(descriptor: ParcelFileDescriptor): PdfRenderer = try {
    PdfRenderer(descriptor)
} catch (_: SecurityException) {
    descriptor.close(); throw PublicationException(PublicationProblem.PROTECTED)
} catch (_: IllegalArgumentException) {
    descriptor.close(); throw PublicationException(PublicationProblem.NEEDS_COPY)
} catch (_: IOException) {
    descriptor.close(); throw PublicationException(PublicationProblem.CORRUPT)
}

/** [root] holds private offline copies; tests pass an isolated directory. */
class PublicationFiles(
    private val context: Context,
    private val root: File = File(context.filesDir, "publications"),
    private val availableBytes: ((File) -> Long)? = null,
) : PublicationImporter {
    private val resolver get() = context.contentResolver
    private val directory get() = root.also { it.mkdirs() }

    /** Opens the publication read-only; sources are never opened for writing. */
    fun open(item: LibraryItem): ParcelFileDescriptor = try {
        val managed = item.managedPath
        if (managed != null) ParcelFileDescriptor.open(managedFile(managed), ParcelFileDescriptor.MODE_READ_ONLY)
        else resolver.openFileDescriptor(Uri.parse(item.sourceUri), "r") ?: throw PublicationException(PublicationProblem.SOURCE_UNAVAILABLE)
    } catch (_: SecurityException) {
        // Some providers also refuse deleted documents with SecurityException; a grant ShelfOS still holds
        // means access was not revoked, so the source itself is what is missing.
        val granted = resolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == item.sourceUri }
        throw PublicationException(if (granted) PublicationProblem.SOURCE_UNAVAILABLE else PublicationProblem.PERMISSION_LOST)
    } catch (_: FileNotFoundException) {
        throw PublicationException(PublicationProblem.SOURCE_UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        throw PublicationException(PublicationProblem.SOURCE_UNAVAILABLE)
    }

    /**
     * Private copies are referenced by file name inside ShelfOS storage. Early prototype rows stored an
     * absolute path; only its final segment is trusted, so a relocated data directory still resolves.
     */
    fun managedFile(reference: String): File {
        val name = reference.substringAfterLast('/')
        if (name.isEmpty() || name.startsWith('.') || name.endsWith(PARTIAL) || !ArchivePolicy.safeName(name))
            throw PublicationException(PublicationProblem.SOURCE_UNAVAILABLE)
        return File(directory, name)
    }

    override suspend fun prepare(sourceUri: String, copy: Boolean, stage: (String) -> Unit): PreparedImport = withContext(Dispatchers.IO) {
        val uri = Uri.parse(sourceUri)
        var name = "Publication"
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0) name = cursor.getString(n) ?: name
                    if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s).takeIf { it >= 0 }
                }
            }
        } catch (_: SecurityException) { throw PublicationException(PublicationProblem.PERMISSION_LOST) }
        val existingGrant = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        var acquired = false
        var owned: File? = null
        try {
            if (!copy && !existingGrant) {
                try { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); acquired = true }
                catch (_: SecurityException) { throw PublicationException(PublicationProblem.NEEDS_COPY) }
            }
            val id = UUID.randomUUID().toString()
            if (copy) owned = copyToPrivateStorage(uri, id, size, stage).also { size = it.length() }
            stage("Inspecting publication…")
            val descriptor = owned?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
                ?: open(LibraryItem(id, name, category = MediaCategory.BOOK, sourceUri = sourceUri, format = PublicationFormat.PDF, fileName = name, byteSize = size))
            val (format, metadata) = descriptor.use(::inspect)
            currentCoroutineContext().ensureActive()
            owned?.let { partial ->
                val completed = File(directory, "$id.${format.name.lowercase()}")
                if (!partial.renameTo(completed)) throw PublicationException(PublicationProblem.COPY_FAILED)
                owned = completed
            }
            val title = metadata.title
            val creator = metadata.creator
            PreparedImport(LibraryItem(id, title ?: name.substringBeforeLast('.', name).ifBlank { name }, creator.orEmpty(),
                suggestedCategory(format, metadata.rightToLeftManga), sourceUri, format, name, size, managedPath = owned?.name,
                titleOrigin = if (title != null) "embedded" else "filename", creatorOrigin = if (creator != null) "embedded" else "unknown"), acquired)
        } catch (error: Throwable) {
            owned?.delete()
            if (acquired) release(sourceUri)
            throw error
        }
    }

    private suspend fun copyToPrivateStorage(uri: Uri, id: String, size: Long?, stage: (String) -> Unit): File {
        stage("Copying for offline access…")
        val root = directory
        if (size != null && freeBytes(root) < size + RESERVED_BYTES) throw PublicationException(PublicationProblem.INSUFFICIENT_STORAGE)
        val partial = File(root, "$id$PARTIAL")
        try {
            val input = try { resolver.openInputStream(uri) } catch (_: SecurityException) { throw PublicationException(PublicationProblem.PERMISSION_LOST) }
            input?.use { stream -> partial.outputStream().use { output ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                var checkedAt = 0L
                var reported = -1L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    total += count
                    if (total - checkedAt >= SPACE_CHECK_BYTES) {
                        checkedAt = total
                        if (freeBytes(root) < MINIMUM_FREE_BYTES) throw PublicationException(PublicationProblem.INSUFFICIENT_STORAGE)
                    }
                    val progress = if (size != null && size > 0) total * 100 / size else total / (1024 * 1024)
                    if (progress != reported) {
                        reported = progress
                        stage(if (size != null && size > 0) "Copying… $progress%" else "Copying… $progress MB")
                    }
                }
            } } ?: throw PublicationException(PublicationProblem.SOURCE_UNAVAILABLE)
            return partial
        } catch (error: Throwable) {
            partial.delete()
            if (error is IOException && error !is PublicationException && error !is FileNotFoundException) {
                if (error.message.orEmpty().contains("ENOSPC"))
                    throw PublicationException(PublicationProblem.INSUFFICIENT_STORAGE)
                throw PublicationException(PublicationProblem.COPY_FAILED)
            }
            throw error
        }
    }

    /** Bounded detection: headers and container directories only, never whole-publication loading. */
    private fun inspect(descriptor: ParcelFileDescriptor): Pair<PublicationFormat, EmbeddedMetadata> {
        try { Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET) }
        catch (_: ErrnoException) { throw PublicationException(PublicationProblem.NEEDS_COPY) }
        val head = ByteArray(1024)
        var count = 0
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
            while (count < head.size) { val read = input.read(head, count, head.size - count); if (read < 0) break; count += read }
        }
        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
        return when {
            count > 0 && head.copyOf(count).toString(Charsets.ISO_8859_1).contains("%PDF-") -> {
                checkPdf(descriptor)
                PublicationFormat.PDF to EmbeddedMetadata()
            }
            count >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte() -> ArchivePolicy.open(descriptor).use { zip ->
                val names = ArchivePolicy.entries(zip).map { it.name }
                val mimetype = zip.entry("mimetype")?.let { zip.readBytes(it, 128) }?.toString(Charsets.UTF_8)
                when (classifyArchive(names, mimetype)) {
                    PublicationFormat.EPUB -> PublicationFormat.EPUB to EmbeddedMetadataReader.epub(zip)
                    else -> { ArchivePolicy.pages(zip); PublicationFormat.CBZ to EmbeddedMetadataReader.comicInfo(zip) }
                }
            }
            else -> throw PublicationException(PublicationProblem.UNSUPPORTED_FORMAT)
        }
    }

    /** Opening the document reads only its cross-reference data; protected and damaged PDFs fail early. */
    private fun checkPdf(descriptor: ParcelFileDescriptor) {
        val pages = openPdf(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { it.pageCount }
        if (pages <= 0) throw PublicationException(PublicationProblem.CORRUPT, "This PDF has no pages.")
    }

    override fun discard(prepared: PreparedImport, sourceStillUsed: Boolean) {
        prepared.item.managedPath?.let { managedFile(it).delete() }
        if (prepared.acquiredGrant && !sourceStillUsed) release(prepared.item.sourceUri)
    }

    /** Removing a library entry releases access only; the source and any private copy stay on disk. */
    fun releaseAccess(sourceUri: String, sourceStillUsed: Boolean) {
        if (!sourceStillUsed) release(sourceUri)
    }

    /**
     * Startup maintenance after possible process death: removes interrupted partial copies, releases read
     * grants no library item needs, and returns referenced items whose grant is gone. Runs before imports.
     */
    suspend fun reconcile(items: List<LibraryItem>): Set<String> = withContext(Dispatchers.IO) {
        deletePartialCopies()
        val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() }
        val plan = planSourceMaintenance(items, grants)
        plan.releaseGrants.forEach(::release)
        plan.unavailableItems
    }

    /** Copies interrupted by process death are incomplete by definition; originals are unaffected. */
    fun deletePartialCopies() { directory.listFiles()?.filter { it.name.endsWith(PARTIAL) }?.forEach { it.delete() } }

    suspend fun unusedCopies(items: List<LibraryItem>): PrivateCopyUsage = withContext(Dispatchers.IO) {
        unusedFiles(items).let { files -> PrivateCopyUsage(files.size, files.sumOf { it.length() }) }
    }

    /** Explicit, user-confirmed cleanup; copies still referenced by a library item are never touched. */
    suspend fun deleteUnusedCopies(items: List<LibraryItem>): PrivateCopyUsage = withContext(Dispatchers.IO) {
        unusedFiles(items).forEach { it.delete() }
        unusedCopies(items)
    }

    private fun unusedFiles(items: List<LibraryItem>): List<File> {
        val used = items.mapNotNullTo(HashSet()) { item -> item.managedPath?.substringAfterLast('/') }
        return directory.listFiles()?.filter { it.isFile && !it.name.endsWith(PARTIAL) && it.name !in used }.orEmpty()
    }

    private fun release(uri: String) {
        try { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { /* Already revoked. */ }
    }

    private fun freeBytes(directory: File): Long {
        availableBytes?.let { return it(directory) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) try {
            val storage = context.getSystemService(StorageManager::class.java)
            return storage.getAllocatableBytes(storage.getUuidForPath(directory))
        } catch (_: IOException) { /* Fall back to the filesystem estimate below. */ }
        return directory.usableSpace
    }

    private companion object {
        const val PARTIAL = ".part"
        const val RESERVED_BYTES = 64L * 1024 * 1024
        const val MINIMUM_FREE_BYTES = 16L * 1024 * 1024
        const val SPACE_CHECK_BYTES = 32L * 1024 * 1024
    }
}
