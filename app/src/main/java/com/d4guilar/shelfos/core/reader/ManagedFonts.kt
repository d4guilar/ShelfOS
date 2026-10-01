// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

const val BUILTIN_SERIF_FONT_ID = "builtin:serif"
const val BUILTIN_SANS_FONT_ID = "builtin:sans"

enum class ManagedFontSource { BUILTIN, USER }
enum class ManagedFontStyle { NORMAL, ITALIC }
enum class ManagedFontFormat(val extension: String) { TTF("ttf"), OTF("otf") }

data class ManagedFontFace(
    val style: ManagedFontStyle,
    val weight: Int,
    val storageId: String?,
    val format: ManagedFontFormat?,
    val checksum: String?,
)

data class ManagedFontFamily(
    val id: String,
    val displayName: String,
    val source: ManagedFontSource,
    val faces: List<ManagedFontFace>,
)

/** A locale-neutral identifier, not text — see [FontImportException]. UI-layer code (not this file) maps each
 * case to a localized string, keeping this validation layer free of Android resources/Context, as some of it
 * (e.g. [validateSfnt]) is also exercised directly by plain JVM tests. */
enum class FontImportFailureReason {
    READ_FAILED, UNSUPPORTED_EXTENSION, TOO_LARGE, TRUNCATED_OR_DAMAGED, COLLECTION_UNSUPPORTED, NOT_SFNT,
    MISSING_TABLES, DAMAGED_OR_UNSUPPORTED, FAMILY_NOT_FOUND, FINISH_IMPORT_FAILED, FINISH_REPLACE_FAILED,
}

class FontImportException(val reason: FontImportFailureReason, cause: Throwable? = null) : Exception(reason.name, cause)

/**
 * Owns copies of user-selected fonts. The catalog is reconstructed from per-family metadata in private storage;
 * preferences keep only [ManagedFontFamily.id], never an external URI or absolute path.
 */
class ManagedFontRepository(
    private val context: Context,
    private val root: File = File(context.filesDir, "fonts/families"),
) {
    private val mutex = Mutex()
    private val _families = MutableStateFlow(loadFamilies(root.also(::discardInterruptedWrites)))
    val families = _families.asStateFlow()

    fun epubResources(): List<EpubManagedFontResource> = families.value.mapNotNull { family ->
        if (family.source != ManagedFontSource.USER) return@mapNotNull null
        val face = family.faces.firstOrNull { it.style == ManagedFontStyle.NORMAL && it.weight == 400 }
            ?: return@mapNotNull null
        val file = face.storageId?.let(::managedFile) ?: return@mapNotNull null
        file.takeIf { it.isFile && it.canRead() }?.let { EpubManagedFontResource(family.id, family.displayName, it) }
    }

    suspend fun import(uri: Uri): ManagedFontFamily = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val input = try { context.contentResolver.openInputStream(uri) }
        catch (error: Exception) { throw FontImportException(FontImportFailureReason.READ_FAILED, error) }
        input?.use { import(it, name) } ?: throw FontImportException(FontImportFailureReason.READ_FAILED)
    }

    internal suspend fun import(input: InputStream, sourceName: String): ManagedFontFamily = mutex.withLock {
        val uuid = UUID.randomUUID().toString()
        val partial = File(root, ".$uuid.part")
        val complete = File(root, uuid)
        try {
            root.mkdirs()
            val family = stageFamily(input, sourceName, uuid, partial)
            if (!partial.renameTo(complete)) throw FontImportException(FontImportFailureReason.FINISH_IMPORT_FAILED)
            refresh()
            family
        } catch (error: Throwable) {
            partial.deleteRecursively()
            complete.deleteRecursively()
            if (error is FontImportException) throw error
            throw FontImportException(FontImportFailureReason.DAMAGED_OR_UNSUPPORTED, error)
        }
    }

    /** Replaces only ShelfOS's managed Regular face; the external source remains read-only and untouched. */
    internal suspend fun replace(familyId: String, input: InputStream, sourceName: String): ManagedFontFamily = mutex.withLock {
        val uuid = userUuid(familyId) ?: throw FontImportException(FontImportFailureReason.FAMILY_NOT_FOUND)
        val complete = File(root, uuid)
        if (!complete.isDirectory) throw FontImportException(FontImportFailureReason.FAMILY_NOT_FOUND)
        val partial = File(root, ".$uuid.replace.part")
        val old = File(root, ".$uuid.replace.old")
        try {
            partial.deleteRecursively(); old.deleteRecursively()
            val family = stageFamily(input, sourceName, uuid, partial)
            if (!complete.renameTo(old) || !partial.renameTo(complete)) {
                if (!complete.exists()) old.renameTo(complete)
                throw FontImportException(FontImportFailureReason.FINISH_REPLACE_FAILED)
            }
            old.deleteRecursively()
            refresh()
            family
        } catch (error: Throwable) {
            partial.deleteRecursively()
            if (!complete.exists()) old.renameTo(complete)
            old.deleteRecursively()
            refresh()
            if (error is FontImportException) throw error
            throw FontImportException(FontImportFailureReason.DAMAGED_OR_UNSUPPORTED, error)
        }
    }

    suspend fun remove(familyId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val uuid = userUuid(familyId) ?: return@withLock false
            val directory = File(root, uuid)
            val removed = !directory.exists() || directory.deleteRecursively()
            refresh()
            removed
        }
    }

    private fun managedFile(storageId: String): File? {
        val parts = storageId.split('/')
        if (parts.size != 2 || parts.any { !it.matches(SAFE_SEGMENT) }) return null
        val candidate = File(root, storageId.replace('/', File.separatorChar))
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return resolved.takeIf { it.parentFile == File(base, parts[0]) }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0)
            }
        }
        return name ?: uri.lastPathSegment ?: "Imported font.ttf"
    }

    private fun refresh() { _families.value = loadFamilies(root) }

    private fun stageFamily(input: InputStream, sourceName: String, uuid: String, directory: File): ManagedFontFamily {
        checkFontExtension(sourceName)
        directory.mkdirs()
        val staging = File(directory, "regular.part")
        copyBounded(input, staging)
        val format = validateSfnt(staging, sourceName)
        val face = File(directory, "regular.${format.extension}")
        if (!staging.renameTo(face)) throw FontImportException(FontImportFailureReason.FINISH_IMPORT_FAILED)
        val family = ManagedFontFamily(
            id = "user:$uuid",
            displayName = normalizedFontName(sourceName),
            source = ManagedFontSource.USER,
            faces = listOf(ManagedFontFace(ManagedFontStyle.NORMAL, 400, "$uuid/${face.name}", format, sha256(face))),
        )
        writeMetadata(directory, family)
        return family
    }

    companion object {
        const val MAX_FONT_BYTES = 32L * 1024 * 1024
        val SAFE_SEGMENT = Regex("[A-Za-z0-9._-]+")

        val builtins = listOf(
            ManagedFontFamily(BUILTIN_SERIF_FONT_ID, "Serif", ManagedFontSource.BUILTIN,
                listOf(ManagedFontFace(ManagedFontStyle.NORMAL, 400, null, null, null))),
            ManagedFontFamily(BUILTIN_SANS_FONT_ID, "Sans", ManagedFontSource.BUILTIN,
                listOf(ManagedFontFace(ManagedFontStyle.NORMAL, 400, null, null, null))),
        )

        private fun discardInterruptedWrites(root: File) {
            root.listFiles().orEmpty().filter { it.name.startsWith('.') }.forEach { file ->
                if (file.name.endsWith(".old")) {
                    val uuid = file.name.removePrefix(".").removeSuffix(".replace.old")
                    val complete = File(root, uuid)
                    if (!complete.exists() && uuid.matches(Regex("[0-9a-fA-F-]{36}"))) file.renameTo(complete)
                    else file.deleteRecursively()
                } else if (file.name.endsWith(".part")) file.deleteRecursively()
            }
        }

        private fun loadFamilies(root: File): List<ManagedFontFamily> = builtins + root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith('.') && it.name.matches(SAFE_SEGMENT) }
            .mapNotNull(::readMetadata)
            .sortedBy { it.displayName.lowercase() }
            .toList()

        private fun readMetadata(directory: File): ManagedFontFamily? = runCatching {
            val properties = Properties().apply { File(directory, "family.properties").inputStream().use(::load) }
            val uuid = directory.name
            val id = properties.getProperty("id")
            if (id != "user:$uuid") return null
            val format = ManagedFontFormat.valueOf(properties.getProperty("format"))
            val fileName = "regular.${format.extension}"
            val file = File(directory, fileName)
            if (!file.isFile) return null
            ManagedFontFamily(id, properties.getProperty("name").orEmpty().takeIf { it.isNotBlank() } ?: "Imported font",
                ManagedFontSource.USER, listOf(ManagedFontFace(ManagedFontStyle.NORMAL, 400, "$uuid/$fileName", format,
                    properties.getProperty("checksum"))))
        }.getOrNull()

        private fun writeMetadata(directory: File, family: ManagedFontFamily) {
            val face = family.faces.single()
            val properties = Properties().apply {
                setProperty("id", family.id)
                setProperty("name", family.displayName)
                setProperty("format", requireNotNull(face.format).name)
                setProperty("checksum", requireNotNull(face.checksum))
            }
            File(directory, "family.properties").outputStream().use { properties.store(it, "ShelfOS managed font") }
        }

        internal fun userUuid(id: String): String? = id.removePrefix("user:").takeIf {
            id.startsWith("user:") && it.matches(Regex("[0-9a-fA-F-]{36}"))
        }

        internal fun checkFontExtension(name: String) {
            if (name.substringAfterLast('.', "").lowercase() !in setOf("ttf", "otf"))
                throw FontImportException(FontImportFailureReason.UNSUPPORTED_EXTENSION)
        }

        internal fun normalizedFontName(name: String): String = name.substringBeforeLast('.', name)
            .replace(Regex("[_-]+"), " ").replace(Regex("\\s+"), " ").trim().take(80)
            .ifBlank { "Imported font" }

        private fun copyBounded(input: InputStream, target: File) {
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_FONT_BYTES) throw FontImportException(FontImportFailureReason.TOO_LARGE)
                    output.write(buffer, 0, count)
                }
            }
        }

        internal fun validateSfnt(file: File, sourceName: String): ManagedFontFormat {
            if (file.length() < 28) throw FontImportException(FontImportFailureReason.TRUNCATED_OR_DAMAGED)
            RandomAccessFile(file, "r").use { data ->
                val signature = data.readInt()
                // Codex QA M2: the sfnt signature identifies the *outline* format (TrueType glyphs vs. CFF/
                // PostScript glyphs), which is independent of the file's own extension — a real .otf can contain
                // TrueType outlines (0x00010000) and a real .ttf can contain CFF outlines ('OTTO'), both valid in
                // the actual OpenType ecosystem. The file's claimed extension was already restricted to .ttf/.otf
                // by checkFontExtension before this ever runs, so it is not re-validated against the detected
                // outline signature here — only used, as before, to name the stored face file. Every structural
                // check below (table-directory bounds, required tables) still fully applies regardless of which
                // outline format was detected.
                val format = when (signature) {
                    // checkFontExtension already restricted sourceName to .ttf/.otf before this ever runs; the
                    // stored face file simply keeps that same, already-validated extension rather than one
                    // re-derived from (and potentially disagreeing with) the detected outline signature.
                    0x00010000, 0x74727565, 0x4F54544F ->
                        if (sourceName.substringAfterLast('.', "").lowercase() == "otf") ManagedFontFormat.OTF else ManagedFontFormat.TTF
                    0x74746366 -> throw FontImportException(FontImportFailureReason.COLLECTION_UNSUPPORTED)
                    else -> throw FontImportException(FontImportFailureReason.NOT_SFNT)
                }
                val tableCount = data.readUnsignedShort()
                if (tableCount !in 1..256 || 12L + tableCount * 16L > data.length())
                    throw FontImportException(FontImportFailureReason.TRUNCATED_OR_DAMAGED)
                data.skipBytes(6)
                val tags = mutableSetOf<String>()
                repeat(tableCount) {
                    val tagBytes = ByteArray(4).also(data::readFully)
                    val tag = tagBytes.toString(Charsets.ISO_8859_1)
                    data.skipBytes(4)
                    val offset = data.readInt().toLong() and 0xffffffffL
                    val length = data.readInt().toLong() and 0xffffffffL
                    if (offset > data.length() || length > data.length() - offset)
                        throw FontImportException(FontImportFailureReason.TRUNCATED_OR_DAMAGED)
                    tags += tag
                }
                if (!tags.containsAll(setOf("name", "cmap", "head")))
                    throw FontImportException(FontImportFailureReason.MISSING_TABLES)
                return format
            }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Old BookFont-only preferences resolve to the same built-in families without a Room migration. */
fun ReaderPreferences.effectiveFontFamilyId(): String = fontFamilyId ?: when (font) {
    BookFont.SANS -> BUILTIN_SANS_FONT_ID
    else -> BUILTIN_SERIF_FONT_ID
}

/**
 * Codex QA M1: called from [ReaderPreferences.parse], per layer, before title/global merging — not from
 * [effectiveFontFamilyId]. A legacy layer (e.g. a title saved before Phase 2B.4) can have [font] set with no
 * [storedFontFamilyId]; if left null here, [ReaderPreferences.over] would let a *global* `fontFamilyId` (e.g. a
 * later-chosen managed font) silently win over this layer's own legacy choice once the layers are merged, since
 * by then there is no way to tell "this layer never set a font" apart from "this layer explicitly wants whatever
 * font wins elsewhere." Populating the matching built-in logical ID right here keeps this layer self-consistent
 * and title-specific precedence intact — this runs on every parse, not a one-time stored rewrite, so no Room
 * migration is needed. Pure so it is directly unit-testable without `org.json.JSONObject`, mirroring every other
 * JSON-boundary split in this project (`EpubBookmarkLocation`, `toEpubPositions`, etc.).
 */
internal fun backfillLegacyFontFamilyId(font: BookFont?, storedFontFamilyId: String?): String? =
    storedFontFamilyId ?: font?.let { if (it == BookFont.SANS) BUILTIN_SANS_FONT_ID else BUILTIN_SERIF_FONT_ID }
