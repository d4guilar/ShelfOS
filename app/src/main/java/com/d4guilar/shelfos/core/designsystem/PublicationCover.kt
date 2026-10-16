// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.designsystem

import android.content.ComponentCallbacks2
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.d4guilar.shelfos.core.reader.ByteBudgetedLruCache
import com.d4guilar.shelfos.core.theme.LocalShelfTokens
import com.d4guilar.shelfos.domain.library.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    /** Settings → Library appearance (4.5A.2); presentation only, never a reason to decode or extract again. */
    val presentations: StateFlow<CoverPresentations> = MutableStateFlow(CoverPresentations()),
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
 * Where one cover sits (plan §7 rules 2-6): [slot] (width / height) reserves layout space; [frame] is the visible,
 * bordered box, bottom-aligned in the slot; [crop] fills the frame, otherwise the whole artwork is shown; [neutralFill]
 * paints Fit's unused space with the theme's neutral surface. Original and `whole` (details, cover expansion) frame the
 * complete artwork at its own clamped ratio; a generated cover (no [artworkAspect]) is drawn natively at the frame.
 */
data class CoverLayout(val slot: Float, val frame: Float, val crop: Boolean, val neutralFill: Boolean)

fun coverLayout(presentation: CoverPresentation, artworkAspect: Float?, whole: Boolean = false): CoverLayout {
    val native = artworkAspect?.takeIf { it.isFinite() && it > 0f }
    val shape = presentation.aspect
    return when {
        whole -> (if (native != null) clampCoverAspect(native) else shape ?: ORIGINAL_SLOT_ASPECT).let { CoverLayout(it, it, crop = false, neutralFill = false) }
        shape == null -> CoverLayout(ORIGINAL_SLOT_ASPECT, clampCoverAspect(native), crop = false, neutralFill = false)
        else -> CoverLayout(shape, shape, crop = presentation.fit == CoverFit.CROP, neutralFill = presentation.fit == CoverFit.FIT && native != null)
    }
}

/**
 * The artwork's drawn rectangle inside [frame]: ONE uniform scale for both axes (never stretched), centered, cropping
 * the overflow when [crop], else entirely inside; [bottom] stands a fitted artwork on the frame's bottom edge.
 */
fun artworkRect(frame: Size, artworkAspect: Float, crop: Boolean, bottom: Boolean = false): Rect {
    val aspect = artworkAspect.takeIf { it.isFinite() && it > 0f } ?: (frame.width / frame.height)
    val scale = if (crop) max(frame.width / aspect, frame.height) else min(frame.width / aspect, frame.height)
    val size = Size(aspect * scale, scale)
    val top = if (bottom && !crop) frame.height - size.height else (frame.height - size.height) / 2
    return Rect(Offset((frame.width - size.width) / 2, top), size)
}

/**
 * THE shared cover renderer (plan §6 inventory, §7): real local artwork in the effective shape/fit for [category]
 * (`CoverPresentations.effective`, so cross-category views resolve each item's own category), the generated
 * [PublicationCoverVisual] when there is none or it cannot be read, and a neutral frame while a known cover is decoding.
 * [frame] decorates the visible artwork box (the focus/selection border follows it, not Original's empty slot space);
 * [whole] shows the complete artwork at its real ratio whatever the preference (details pane, cover expansion).
 * Decorative: titles carry meaning. A visible item without artwork asks for its cover; a shape change never does.
 */
@Composable
fun PublicationCover(libraryItemId: String, title: String, modifier: Modifier = Modifier, category: MediaCategory? = null,
    frame: Modifier = Modifier, whole: Boolean = false) {
    val artwork = LocalCoverArtwork.current
    val presentations = (artwork?.presentations ?: DEFAULT_PRESENTATIONS).collectAsState()
    val presentation by remember(presentations, category) { derivedStateOf { presentations.value.effective(category) } }
    val map = (artwork?.covers ?: NO_ARTWORK).collectAsState()
    // Only this item's entry is read, so a cover arriving for another item does not recompose this one.
    val known by remember(map) { derivedStateOf { map.value != null } }
    val ref by remember(map, libraryItemId) { derivedStateOf { map.value?.get(libraryItemId) } }
    val current = ref
    val layout = coverLayout(presentation, current?.let { it.widthPx.toFloat() / it.heightPx }, whole)
    val muted = LocalShelfTokens.current.colors.muted
    Box(modifier.aspectRatio(layout.slot), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.aspectRatio(layout.frame).then(frame).clipToBounds().clearAndSetSemantics { }) {
            when {
                !known -> Box(Modifier.fillMaxSize().background(muted))
                current == null -> {
                    if (artwork != null) LaunchedEffect(libraryItemId) { artwork.request(libraryItemId) }
                    PublicationCoverVisual(libraryItemId, title, Modifier.fillMaxSize())
                }
                else -> CoverArtworkImage(artwork!!, current, title, layout)
            }
        }
    }
}

private val DEFAULT_PRESENTATIONS = MutableStateFlow(CoverPresentations())
// No artwork provider (previews, isolated tests): the map is known and empty, so the generated cover shows.
private val NO_ARTWORK = MutableStateFlow<Map<String, CoverRef>?>(emptyMap())

@Composable
private fun CoverArtworkImage(artwork: CoverArtwork, ref: CoverRef, title: String, layout: CoverLayout) {
    val muted = LocalShelfTokens.current.colors.muted
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else ref.widthPx
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else ref.heightPx
        val sample = coverSampleSize(ref.widthPx, ref.heightPx, width, height)
        val key = "${ref.libraryItemId}/${ref.fileName}/$sample"
        // Keyed by the file, not the sample: a resize (shape change, cover expansion) keeps showing the last decode meanwhile.
        var shown by remember(ref.libraryItemId, ref.fileName) { mutableStateOf(artwork.cached(key)) }
        LaunchedEffect(key) { shown = artwork.load(ref, sample, key) }
        when (val image = shown) {
            null -> Box(Modifier.fillMaxSize().background(muted))
            CoverImage.Failed -> PublicationCoverVisual(ref.libraryItemId, title, Modifier.fillMaxSize())
            // No content description (decorative); the tag is for tests only. The stored bitmap is only drawn, never changed.
            is CoverImage.Loaded -> Canvas(Modifier.fillMaxSize().testTag("cover_artwork")) {
                if (layout.neutralFill) drawRect(muted)
                val bitmap = image.bitmap
                val r = artworkRect(size, bitmap.width.toFloat() / bitmap.height, layout.crop, bottom = !layout.crop && !layout.neutralFill)
                drawImage(bitmap, dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)))
            }
        }
    }
}
