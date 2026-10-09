// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.files

import android.annotation.SuppressLint
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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

/** Unreferenced private copies that only an explicit cleanup may delete. */
data class PrivateCopyUsage(val count: Int, val bytes: Long)

/** Takes ownership of [descriptor]; protected, damaged and non-seekable documents map to explicit problems. */
fun openPdf(descriptor: ParcelFileDescriptor): PdfRenderer = try {
    if (usesLegacyPdfiumProbe(Build.VERSION.SDK_INT)) LegacyPdfiumProbe.requireOpenable(descriptor)
    PdfRenderer(descriptor)
} catch (e: PublicationException) {
    // Setup failure from the legacy probe (it is an IOException, so it must be matched before CORRUPT below).
    descriptor.close(); throw e
} catch (_: SecurityException) {
    descriptor.close(); throw PublicationException(PublicationProblem.PROTECTED)
} catch (_: IllegalArgumentException) {
    descriptor.close(); throw PublicationException(PublicationProblem.NEEDS_COPY)
} catch (_: IOException) {
    descriptor.close(); throw PublicationException(PublicationProblem.CORRUPT)
}

/** Android 7.0/7.1 (API 24/25) only; API 26+ keeps the plain `PdfRenderer` path. */
internal fun usesLegacyPdfiumProbe(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.O

/** Outcome of reflecting the framework's `PdfRenderer.sPdfiumLock`. */
internal sealed interface PlatformLockLookup {
    /** The field exists; [value] may still be null or unusable. */
    data class Found(val value: Any?) : PlatformLockLookup
    data object Absent : PlatformLockLookup
    data object Inaccessible : PlatformLockLookup
}

internal sealed interface LockResolution {
    data class Monitor(val monitor: Any, val isFrameworkLock: Boolean) : LockResolution
    data object SetupFailure : LockResolution
}

/**
 * Chooses the monitor the legacy probe synchronizes on. API 24 (AOSP 7.0) has no Java `sPdfiumLock`, so a missing
 * field there is expected and a private monitor is used. API 25 (AOSP 7.1) has it and its constructor and natives
 * synchronize on it, so on API 25 the real framework lock is mandatory: any absent, inaccessible, null or unusable
 * value is a setup failure (fail closed, no unrelated fallback monitor). Source contract only; API 25 has not been
 * executed on a device.
 */
internal fun resolveLegacyPdfiumLock(sdkInt: Int, lookup: PlatformLockLookup): LockResolution {
    val framework = (lookup as? PlatformLockLookup.Found)?.value?.takeIf { isUsableMonitor(it) }
    return when {
        framework != null -> LockResolution.Monitor(framework, isFrameworkLock = true)
        sdkInt < Build.VERSION_CODES.N_MR1 && lookup == PlatformLockLookup.Absent -> LockResolution.Monitor(Any(), isFrameworkLock = false)
        else -> LockResolution.SetupFailure
    }
}

private fun isUsableMonitor(value: Any): Boolean =
    value !is Number && value !is Boolean && value !is Char && value !is CharSequence

/**
 * Android 7.0/7.1 platform bug guard. pdfium's process-wide init count is bumped by `PdfRenderer.nativeCreate`
 * and dropped by `nativeClose`. A `PdfRenderer` whose `nativeCreate` fails (corrupt or protected PDF) has already
 * stored its descriptor, so the failed, half-built object is still finalized later; its finalizer calls
 * `nativeClose(0)`, decrementing the count a second time. The count goes negative, the next open skips
 * `FPDF_InitLibrary`, and the process dies with SIGSEGV inside `libpdfium.so`.
 *
 * The fix is structural: a `PdfRenderer` is never constructed unless pdfium has just accepted the document. The
 * probe calls the same private static natives the constructor uses, under the same lock, and closes the
 * document straight away (net count change zero). A document pdfium rejects therefore never produces a
 * finalizable `PdfRenderer`, so nothing can run later and correctness does not depend on finalizer timing. The
 * probe keeps no state beyond the cached reflected method handles (three objects, process-wide, constant), opens
 * no extra file and writes nothing; the user's publication is only read. If the platform natives cannot be
 * resolved the open fails closed with an explicit [PublicationProblem.UNREADABLE] instead of risking the crash.
 * Residual: a file that changes between the probe and the constructor (microseconds apart) could still reach the
 * platform failure path.
 */
// The reflection below is reachable only on API 24/25 (see usesLegacyPdfiumProbe), where no private-API restriction
// exists; lint's API 37 warning concerns platforms this code never runs on.
@SuppressLint("SoonBlockedPrivateApi")
internal object LegacyPdfiumProbe {
    private class Natives(val create: Method, val close: Method, val lock: Any)

    private val natives: Natives? by lazy {
        try {
            val create = PdfRenderer::class.java.getDeclaredMethod("nativeCreate", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val close = PdfRenderer::class.java.getDeclaredMethod("nativeClose", Long::class.javaPrimitiveType)
            create.isAccessible = true; close.isAccessible = true
            when (val lock = resolveLegacyPdfiumLock(Build.VERSION.SDK_INT, lookUpPlatformLock())) {
                is LockResolution.Monitor -> Natives(create, close, lock.monitor)
                LockResolution.SetupFailure -> null
            }
        } catch (_: ReflectiveOperationException) { null } catch (_: SecurityException) { null }
    }

    /**
     * Android 7.1 serializes every native PDF call with the static `PdfRenderer.sPdfiumLock`; the probe must use
     * the same monitor (see [resolveLegacyPdfiumLock]). Android 7.0 has no Java-level lock (only a native mutex
     * around the init count). Every lookup problem is reported as an outcome, never swallowed into a fallback.
     */
    private fun lookUpPlatformLock(): PlatformLockLookup = try {
        PlatformLockLookup.Found(PdfRenderer::class.java.getDeclaredField("sPdfiumLock").apply { isAccessible = true }.get(null))
    } catch (_: NoSuchFieldException) { PlatformLockLookup.Absent
    } catch (_: ReflectiveOperationException) { PlatformLockLookup.Inaccessible
    } catch (_: SecurityException) { PlatformLockLookup.Inaccessible
    } catch (_: RuntimeException) { PlatformLockLookup.Inaccessible }

    /** Throws the same [SecurityException], [IOException] or [IllegalArgumentException] the constructor would. */
    fun requireOpenable(descriptor: ParcelFileDescriptor) {
        val natives = natives ?: throw PublicationException(PublicationProblem.UNREADABLE)
        val size = try {
            Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            Os.fstat(descriptor.fileDescriptor).st_size
        } catch (_: ErrnoException) { throw IllegalArgumentException("file descriptor not seekable") }
        synchronized(natives.lock) {
            val document = try {
                natives.create.invoke(null, descriptor.fd, size) as Long
            } catch (e: InvocationTargetException) { throw e.targetException } catch (_: IllegalAccessException) {
                throw PublicationException(PublicationProblem.UNREADABLE)
            }
            try { natives.close.invoke(null, document) } catch (_: ReflectiveOperationException) {
                throw PublicationException(PublicationProblem.UNREADABLE)
            }
        }
    }
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

    override suspend fun prepare(sourceUri: String, copy: Boolean, stage: (ImportProgressStage) -> Unit): PreparedImport = withContext(Dispatchers.IO) {
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
            stage(ImportProgressStage.Inspecting)
            val descriptor = owned?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
                ?: open(LibraryItem(id, name, category = MediaCategory.BOOK, sourceUri = sourceUri, format = PublicationFormat.PDF, fileName = name, byteSize = size))
            val (format, metadata) = descriptor.use { inspect(it) }
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

    private suspend fun copyToPrivateStorage(uri: Uri, id: String, size: Long?, stage: (ImportProgressStage) -> Unit): File {
        stage(ImportProgressStage.CopyingOffline)
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
                        stage(if (size != null && size > 0) ImportProgressStage.CopyingPercent(progress.toInt())
                            else ImportProgressStage.CopyingMegabytes(progress))
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

    /** Bounded detection: headers and container directories only, never whole-publication loading. A RAR/CBR
     * source is always inspected under an ephemeral cache namespace (see [inspectRar]'s doc) -- import-time
     * inspection never needs cross-reopen cache reuse, so it never needs (and never computes) a persistent
     * [com.d4guilar.shelfos.domain.library.rarCacheSourceKey]. */
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
            isRarMagic(head, count) -> inspectRar(descriptor)
            else -> throw PublicationException(PublicationProblem.UNSUPPORTED_FORMAT)
        }
    }

    /**
     * Phase 3E-D, re-layered in 3E-D R1A (HIGH-1/HIGH-2): validates a RAR/CBR container the same way [ArchivePolicy
     * .pages] validates a CBZ ZIP entry -- opened once through [openRarArchiveSession] (never a second
     * detector/parser), indexed and page-filtered by [RarContainer.open] (throwing [PublicationProblem.
     * EMPTY_ARCHIVE]/[PublicationProblem.PROTECTED]/etc exactly as that shared policy already does for the real
     * reading-session route), then closed again immediately -- import-time inspection never keeps a session or
     * cache lease alive past this call. [RarContainer.comicInfo] reuses the exact same `ComicInfo.xml` mapping
     * CBZ's own [EmbeddedMetadataReader.comicInfo] already produces (best-effort; a missing/unparsable
     * `ComicInfo.xml` still imports successfully via filename/fallback evidence, same as CBZ).
     *
     * Opens [RarContainer] directly -- NEVER `core.reader.RarPageSource` -- so import-time inspection has no
     * dependency on the reader layer at all (Phase 3E-D R1A HIGH-1): validating an archive and best-effort reading
     * its `ComicInfo.xml` needs none of [RarContainer]'s page-extraction/[PageSource] surface.
     *
     * Always opened with a `null` [RarContainer.open] `sourceKey` -- i.e. an ephemeral, per-call cache namespace
     * (Phase 3E-D R1A HIGH-2). Its random namespace prevents reuse across reopen; closing the container releases
     * the session but does not delete materialized files. Retained payload stays within [RarCacheCoordinator]'s
     * global 256 MiB/64-entry bounds and is reclaimed by ordinary deterministic LRU eviction. Persistence decisions
     * remain entirely with the reader-time route (see
     * [com.d4guilar.shelfos.domain.library.rarCacheSourceKey]), after [LibraryItem.managedPath] is durably decided.
     */
    private fun inspectRar(descriptor: ParcelFileDescriptor): Pair<PublicationFormat, EmbeddedMetadata> {
        val session = openRarArchiveSession(descriptor)
        val container = RarContainer.open(session, rarCacheRoot, sourceKey = null)
        return try {
            PublicationFormat.CBR to (container.comicInfo() ?: EmbeddedMetadata())
        } finally {
            container.close()
        }
    }

    /** Shared, process-wide RAR extraction-cache root (see [RarCacheCoordinator]/[RarContainer.open]'s "cache
     * namespace" doc): the SAME path is used here (import-time inspection) and by `core.reader.FixedReader`'s
     * `RarPages` (the real reading session), so [RarCacheCoordinator.getInstance] resolves to the same singleton
     * coordinator both times. Import-time inspection always uses an ephemeral namespace (see [inspectRar]), so in
     * practice this sharing only ever matters for reuse ACROSS reader-time reopens of a managed (ShelfOS-owned,
     * immutable) copy -- never between import-time inspection and the first read. */
    internal val rarCacheRoot: File get() = context.cacheDir

    /** Opening the document reads only its cross-reference data; protected and damaged PDFs fail early. */
    private fun checkPdf(descriptor: ParcelFileDescriptor) {
        val pages = openPdf(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { it.pageCount }
        if (pages <= 0) throw PublicationException(PublicationProblem.CORRUPT, PublicationExceptionDetail.PDF_HAS_NO_PAGES)
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
