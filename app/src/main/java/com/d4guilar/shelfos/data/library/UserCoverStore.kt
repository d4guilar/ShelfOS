// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.data.library

import com.d4guilar.shelfos.core.database.CoverRejectedException
import com.d4guilar.shelfos.core.files.CoverFiles
import com.d4guilar.shelfos.core.reader.CoverExtractionFailure
import com.d4guilar.shelfos.core.reader.CoverImageWriter
import com.d4guilar.shelfos.domain.library.CoverFailureReason
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

enum class UserCoverOutcome { SAVED, ITEM_GONE, UNREADABLE, INVALID_IMAGE, UNSUPPORTED_IMAGE, STORAGE_FULL, FAILED }

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
) {
    fun hasCustom(id: String): Flow<Boolean> = covers.hasUser(id)

    suspend fun set(id: String, uri: String): UserCoverOutcome = lock.withLock {
        if (!covers.itemExists(id)) return UserCoverOutcome.ITEM_GONE
        if (!CoverStoragePolicy.admits(withContext(io) { files.freeBytes() }, MAX_INPUT_BYTES + CoverStoragePolicy.MAX_COVER_BYTES)) return UserCoverOutcome.STORAGE_FULL
        var input: File? = null
        var output: File? = null
        var finalName: String? = null
        var committed = false
        try {
            val stream = try { withContext(io) { open(uri) } } catch (_: SecurityException) { null } catch (_: IOException) { null }
                ?: return UserCoverOutcome.UNREADABLE
            val tmpIn = files.newTemporary(id).also { input = it }
            val tmpOut = files.newTemporary(id).also { output = it }
            val stored = withContext(io) {
                stream.use { copyBounded(it, tmpIn) }
                CoverImageWriter.store({ FileInputStream(tmpIn) }, tmpOut, orientationOf(tmpIn))
            }
            val name = withContext(io) { files.finalize(id, tmpOut, CoverSlot.USER.name, stored.extension) }.also { finalName = it }
            // Never cancelled half-way: a commit that landed must not have its referenced file deleted below.
            val previous = withContext(NonCancellable) { covers.commitUser(id, name, stored).also { committed = true } }
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
    suspend fun remove(id: String): Boolean = lock.withLock {
        withContext(NonCancellable) {
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
