// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import android.content.ComponentCallbacks2
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.d4guilar.shelfos.core.reader.ByteBudgetedLruCache
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.CoverProvenance
import com.d4guilar.shelfos.domain.library.CoverRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** The existing fixed cover frame (≈2:3). Configurable shapes arrive with 4.5A.2. */
const val COVER_ASPECT = 0.68f

/** A decoded cover, or a remembered failure so a broken file is not decoded on every frame. */
sealed interface CoverImage {
    data class Loaded(val bitmap: ImageBitmap) : CoverImage
    data object Failed : CoverImage
}

/**
 * Everything the shared renderer needs, provided once by the app (`LocalCoverArtwork`). [covers] is null until the
 * first cover map arrives (the renderer shows a neutral frame, not generated art, so tiles do not flash). Decoding
 * is off the main thread, sampled to the rendered size, and cached by `id/fileName/sample` in a byte-budgeted LRU:
 * a replaced cover has a new file name and therefore a new key, so a stale bitmap is never served.
 */
class CoverArtwork(
    val covers: StateFlow<Map<String, CoverRef>?>,
    private val file: (CoverRef) -> File,
    val request: (String) -> Unit,
    private val reportMissing: (String) -> Unit,
    val provenance: (String) -> Flow<CoverProvenance?>,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
) {
    private val cache = ByteBudgetedLruCache<String, CoverImage>(budgetBytes, MAX_ENTRIES) {
        (it as? CoverImage.Loaded)?.bitmap?.let { b -> b.width.toLong() * b.height * 4 } ?: 0L
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val decoder = Dispatchers.IO.limitedParallelism(2)

    fun cached(key: String): CoverImage? = cache.get(key)

    suspend fun load(ref: CoverRef, sample: Int, key: String): CoverImage = cache.get(key) ?: withContext(decoder) {
        val result = try {
            val f = file(ref)
            if (!f.isFile) { reportMissing(ref.libraryItemId); CoverImage.Failed }
            else BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { CoverImage.Loaded(it.asImageBitmap()) } ?: CoverImage.Failed
        } catch (_: OutOfMemoryError) { null } catch (_: Exception) { CoverImage.Failed }
        // An out-of-memory decode is not remembered as a broken file; the generated cover shows meanwhile.
        result?.also { cache.put(key, it) } ?: CoverImage.Failed
    }

    /** Applies [coverCacheTrimTarget] for a `ComponentCallbacks2.onTrimMemory` [level]; evicted covers re-decode on demand. */
    fun trimMemory(level: Int) { coverCacheTrimTarget(level, budgetBytes)?.let(cache::trimTo) }

    companion object {
        /** §7 rule 7: ≤ ~24 MiB of decoded covers (≈ 60 grid tiles at 3x density). */
        const val DEFAULT_BUDGET_BYTES = 24L * 1024 * 1024
        private const val MAX_ENTRIES = 256
    }
}

/**
 * Bounded memory-pressure policy for the decoded-cover cache: null keeps it; RUNNING_LOW halves it; RUNNING_CRITICAL and
 * every more severe level (UI_HIDDEN, BACKGROUND, MODERATE, COMPLETE) empty it. The levels are ordered by severity.
 */
@Suppress("DEPRECATION") // the RUNNING_* levels are deprecated on API 35+ but still delivered on the API 24+ devices ShelfOS targets
fun coverCacheTrimTarget(level: Int, budgetBytes: Long): Long? = when {
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 0L
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> budgetBytes / 2
    else -> null
}

val LocalCoverArtwork = staticCompositionLocalOf<CoverArtwork?> { null }

/** Power-of-two subsampling that keeps the decoded bitmap at least the frame's size on both axes (crop never upscales). */
fun coverSampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= targetWidth.coerceAtLeast(1) && height / (sample * 2) >= targetHeight.coerceAtLeast(1)) sample *= 2
    return sample
}

/**
 * THE shared cover renderer (plan §6 inventory, §7 rule 11): real local artwork cropped to fill the existing fixed frame
 * (never stretched), the generated [PublicationCoverVisual] when there is none or it cannot be read, and a neutral frame
 * while a known cover is decoding. Decorative: titles carry meaning. A visible item without artwork asks for its cover.
 */
@Composable
fun PublicationCover(libraryItemId: String, title: String, modifier: Modifier = Modifier) {
    val artwork = LocalCoverArtwork.current
    if (artwork == null) { PublicationCoverVisual(libraryItemId, title, modifier); return }
    val map = artwork.covers.collectAsState()
    // Only this item's entry is read, so a cover arriving for another item does not recompose this one.
    val known by remember(map) { derivedStateOf { map.value != null } }
    val ref by remember(map, libraryItemId) { derivedStateOf { map.value?.get(libraryItemId) } }
    val current = ref
    when {
        !known -> Box(modifier.aspectRatio(COVER_ASPECT).background(LocalShelfTokens.current.colors.muted).clearAndSetSemantics { })
        current == null -> {
            LaunchedEffect(libraryItemId) { artwork.request(libraryItemId) }
            PublicationCoverVisual(libraryItemId, title, modifier)
        }
        else -> CoverArtworkImage(artwork, current, title, modifier)
    }
}

@Composable
private fun CoverArtworkImage(artwork: CoverArtwork, ref: CoverRef, title: String, modifier: Modifier) {
    // No semantics of its own: the image has no content description (decorative); the tag is for tests only.
    BoxWithConstraints(modifier.aspectRatio(COVER_ASPECT).clipToBounds()) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else ref.widthPx
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else ref.heightPx
        val sample = coverSampleSize(ref.widthPx, ref.heightPx, width, height)
        val key = "${ref.libraryItemId}/${ref.fileName}/$sample"
        // Keyed by the file, not the sample: a resize (cover-expand transition) keeps showing the last decode meanwhile.
        var shown by remember(ref.libraryItemId, ref.fileName) { mutableStateOf(artwork.cached(key)) }
        LaunchedEffect(key) { shown = artwork.load(ref, sample, key) }
        when (val image = shown) {
            null -> Box(Modifier.fillMaxSize().background(LocalShelfTokens.current.colors.muted))
            CoverImage.Failed -> PublicationCoverVisual(ref.libraryItemId, title, Modifier.fillMaxSize())
            is CoverImage.Loaded -> Image(image.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize().testTag("cover_artwork"),
                contentScale = ContentScale.Crop)
        }
    }
}
