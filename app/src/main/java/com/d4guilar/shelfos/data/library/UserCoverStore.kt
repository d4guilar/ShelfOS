// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.database.CoverRejectedException
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.core.reader.CoverImageWriter
import com.d4guilar.shelfos.domain.library.CoverFailureReason
import com.d4guilar.shelfos.domain.library.CoverOrigin
import com.d4guilar.shelfos.domain.library.CoverOriginKind
import com.d4guilar.shelfos.domain.library.CoverSlot
import com.d4guilar.shelfos.domain.library.CoverStoragePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** [SUPERSEDED]: a newer choice for the same Book arrived first. [NOT_PERMITTED]: the consent guard of an online selection no longer held. */
enum class UserCoverOutcome { SAVED, ITEM_GONE, UNREADABLE, INVALID_IMAGE, UNSUPPORTED_IMAGE, STORAGE_FULL, FAILED, SUPERSEDED, NOT_PERMITTED }

/**
 * Durable user-chosen cover artwork (Phase 4.5F.0): the USER slot of the existing `publication_cover` table. The picked
 * image is copied once into a bounded temporary file (so a revoked or vanished source cannot break later steps),
 * validated and reduced by the same [CoverImageWriter] ladder as local covers, finalized under a NEW immutable name
 * `user-<16 hex>.<ext>`, and only then does the guarded Room commit make it active; the previous USER file is deleted only
 * after that commit. Any failure leaves the current artwork untouched. USER artwork is durable user data: nothing here
 * or in the startup sweep evicts it (it is a referenced file), and the source URI is never retained.
 * All writes hold [lock], the scheduler's directory lock, so they never race the startup sweep or an extraction.
 */
class UserCoverStore(
    private val covers: RoomCoverRepository,
    private val files: CoverFiles,
    private val lock: Mutex,
    /** Opens the picked image; may throw [SecurityException] or [IOException] (revoked or vanished source). */
    private val open: (String) -> InputStream?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Test seam: the EXIF orientation of the staged copy (never of the external URI). */
    private val orientationOf: (File) -> Int = CoverImageWriter::exifOrientation,
    /**
     * The only directory [setFromPrivateFile] accepts a source from (an app-private staging area the online-cover service owns). Null
     * refuses every file source, so no caller can name an arbitrary path.
     */
    private val stagingRoot: File? = null,
) {
    /**
     * One counter per Book, bumped by EVERY choice or removal as it begins. An online selection remembers the value it started from and is
     * [UserCoverOutcome.SUPERSEDED] if it changed, so an older in-flight selection can never overwrite a newer one (a device pick, a removal,
     * or another candidate), whichever finishes first.
     */
    private val epochs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun epoch(id: String): Long = epochs[id] ?: 0L
    private fun bump(id: String): Long = epochs.merge(id, 1L, Long::plus)!!

    fun hasCustom(id: String): Flow<Boolean> = covers.hasUser(id)

    suspend fun set(id: String, uri: String): UserCoverOutcome {
        bump(id)
        return commit(id, CoverOrigin.DEVICE, null, null) { own ->
            val stream = try { withContext(io) { open(uri) } } catch (_: SecurityException) { null } catch (_: IOException) { null } ?: return@commit null
            val tmpIn = files.newTemporary(id).also(own)   // after open, as before: a source that removes the item while opening cannot strand the directory
            withContext(io) { stream.use { copyBounded(it, tmpIn) } }
            tmpIn
        }
    }

    /**
     * Commits an already downloaded, app-private [file] as the USER cover with a provider [origin] (Phase 4.5C.3A). The file must lie under
     * [stagingRoot]; it goes through the same validation, reduction, naming and guarded commit as a picked image and is NOT deleted here (its
     * owner removes it). [expectedEpoch] is [epoch] as read when the selection began, and [guard] is the consent/permission check, evaluated
     * under the lock before any work and again immediately before the commit; a false guard commits nothing.
     */
    suspend fun setFromPrivateFile(id: String, file: File, origin: CoverOrigin, expectedEpoch: Long, guard: () -> Boolean): UserCoverOutcome {
        val root = stagingRoot?.canonicalFile ?: return UserCoverOutcome.UNREADABLE
        if (!file.canonicalFile.startsWith(root) || !file.isFile) return UserCoverOutcome.UNREADABLE
        require(origin.kind == CoverOriginKind.PROVIDER_SELECTED)
        return commit(id, origin, expectedEpoch, guard) { file }
    }

    /** [source] yields the image to validate, or null when unreadable; a temporary it creates is reported through `own` BEFORE it is written, so it is always removed. */
    private suspend fun commit(id: String, origin: CoverOrigin, expectedEpoch: Long?, guard: (() -> Boolean)?, source: suspend (own: (File) -> Unit) -> File?): UserCoverOutcome = lock.withLock {
        if (expectedEpoch != null) {
            if (epoch(id) != expectedEpoch) return UserCoverOutcome.SUPERSEDED
            if (guard?.invoke() != true) return UserCoverOutcome.NOT_PERMITTED
            bump(id)   // this selection is now the newest; a second one that started from the same epoch is superseded
        }
        if (!covers.itemExists(id)) return UserCoverOutcome.ITEM_GONE
        if (!CoverStoragePolicy.admits(withContext(io) { files.freeBytes() }, MAX_INPUT_BYTES + CoverStoragePolicy.MAX_COVER_BYTES)) return UserCoverOutcome.STORAGE_FULL
        var input: File? = null
        var output: File? = null
        var finalName: String? = null
        var committed = false
        try {
            val tmpIn = source { input = it } ?: return UserCoverOutcome.UNREADABLE
            val tmpOut = files.newTemporary(id).also { output = it }
            val stored = withContext(io) { CoverImageWriter.store({ FileInputStream(tmpIn) }, tmpOut, orientationOf(tmpIn)) }
            val name = withContext(io) { files.finalize(id, tmpOut, CoverSlot.USER.name, stored.extension) }.also { finalName = it }
            if (guard != null && !guard()) return UserCoverOutcome.NOT_PERMITTED   // revoked during the work: nothing is committed (finally removes the file)
            // Never cancelled half-way: a commit that landed must not have its referenced file deleted below.
            val previous = withContext(NonCancellable) { covers.commitUser(id, name, stored, origin).also { committed = true } }
            if (previous != null && previous != name) withContext(io + NonCancellable) { files.delete(id, previous) }
            UserCoverOutcome.SAVED
        } catch (e: CancellationException) {
            throw e
        } catch (_: CoverRejectedException) {
            withContext(NonCancellable + io) { files.deleteItem(id) }   // the item was removed meanwhile: nothing is revived
            UserCoverOutcome.ITEM_GONE
        } catch (e: CoverExtractionFailure) {
            when (e.reason) {
                CoverFailureReason.UNDECODABLE_CONTENT, CoverFailureReason.NO_SUITABLE_ARTWORK -> UserCoverOutcome.INVALID_IMAGE
                CoverFailureReason.UNSUPPORTED_REPRESENTATION -> UserCoverOutcome.UNSUPPORTED_IMAGE
                CoverFailureReason.STORAGE_FULL -> UserCoverOutcome.STORAGE_FULL
                else -> UserCoverOutcome.FAILED
            }
        } catch (_: OutOfMemoryError) {
            UserCoverOutcome.UNSUPPORTED_IMAGE
        } catch (_: SecurityException) {
            UserCoverOutcome.UNREADABLE
        } catch (_: FileNotFoundException) {
            UserCoverOutcome.UNREADABLE
        } catch (e: IOException) {
            if (e.message.orEmpty().contains("ENOSPC")) UserCoverOutcome.STORAGE_FULL else UserCoverOutcome.FAILED
        } catch (_: Exception) {
            UserCoverOutcome.FAILED
        } finally {
            withContext(NonCancellable + io) {
                input?.delete(); output?.delete()
                if (!committed) finalName?.let { files.delete(id, it) }
            }
        }
    }

    /** Removes the custom cover: the file cover (LOCAL, else generated) shows again; the publication is never touched. */
    suspend fun remove(id: String): Boolean {
        bump(id)
        return lock.withLock { removeLocked(id) }
    }

    private suspend fun removeLocked(id: String): Boolean {
        return withContext(NonCancellable) {
            val previous = covers.clearUser(id)
            if (previous != null) withContext(io) { files.delete(id, previous) }
            previous != null
        }
    }

    /** Copies at most [MAX_INPUT_BYTES]; a larger source is not artwork ShelfOS will accept. */
    private fun copyBounded(from: InputStream, to: File) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        java.io.FileOutputStream(to).use { out ->
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_INPUT_BYTES) throw CoverExtractionFailure(CoverFailureReason.UNSUPPORTED_REPRESENTATION)
                out.write(buffer, 0, n)
            }
            out.fd.sync()
        }
    }

    companion object { const val MAX_INPUT_BYTES = CoverStoragePolicy.UNKNOWN_TEMP_RESERVE_BYTES }
}
