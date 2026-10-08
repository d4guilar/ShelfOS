// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.core.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import com.d4guilar.shelfos.core.files.*
import com.d4guilar.shelfos.domain.library.*
import java.io.Closeable
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A page render request: the actual presentation need (viewport size, the active fit mode, a safety ceiling)
 * rather than a single format-specific decode parameter. [viewportWidth]/[viewportHeight] describe the space the
 * page will actually be displayed in (the reader page surface's measured box, pre-fit), and [fit] says how that
 * box maps onto the page: [FitMode.PAGE] fits both axes inside it; [FitMode.WIDTH] always fills the box's width
 * and lets height follow the page's own aspect ratio past the box's bottom edge (see
 * [fixedReaderFittedContentSize], which [resolveRenderTarget] mirrors). Asking for a larger viewport than the
 * publication's current on-screen size is how a future caller requests more detail (a higher-resolution zoom
 * re-render); [maxDimension] is a per-request ceiling the caller may tighten below the absolute safety limit (e.g.
 * a future thumbnail strip asking for at most 200px regardless of layout) -- it can never raise the result above
 * [SAFE_MAX_DIMENSION], and a tightened [maxDimension] is also how a future smaller request opts out of the
 * reading-quality floor described below.
 *
 * Deliberately format- and Compose-free: [com.d4guilar.shelfos.feature.reader.FixedReaderScreen] translates its
 * own measured pixel size into this before calling [FixedReader.render]; no core reader type here depends on
 * `androidx.compose`. This slice (Phase 3A + Codex R1 remediation) only ever constructs a request from the
 * reader's own display viewport and fit preference at page-open/page-turn time -- it does not wire a live
 * pinch-zoom-triggered re-render or a thumbnail UI; see `docs/PHASE_3_IMPLEMENTATION_PLAN.md` for what remains for
 * later slices.
 */
data class PageRenderRequest(val viewportWidth: Int = 0, val viewportHeight: Int = 0, val fit: FitMode = FitMode.PAGE,
    val maxDimension: Int = SAFE_MAX_DIMENSION,
    // Codex R1 finding 2 (3C spread remediation): declares this request as one slot of an ACTIVE multi-slot
    // spread, so resolveRenderTarget() applies RenderMemoryPolicy's stricter spread-transition per-slot byte
    // budget instead of the single-page one -- see RenderMemoryPolicy's doc for why a spread needs a tighter
    // ceiling (worst-case 4 live reading-resolution bitmaps during a spread-to-spread transition, not 2-3).
    // Deliberately a boolean flag rather than a raw byte number: every call site declares *what kind of request
    // this is*, and the actual budget number lives in exactly one place (RenderMemoryPolicy), never duplicated.
    val spreadSlot: Boolean = false) {
    companion object {
        /**
         * Two distinct roles, deliberately given one value (Codex R1 finding 1):
         * 1. **Pre-layout fallback**: the longest-edge target used when no real viewport is known yet (e.g.
         *    before Compose has measured the page surface). Equal to Phase 1/2's flat `MAX_PAGE_PIXELS`.
         * 2. **Reading-quality floor**: once a real viewport *is* known, [resolveRenderTarget] still never lets a
         *    normal reading render's longest edge fall below this value merely because a small/narrow viewport
         *    (or a viewport/fit combination whose aspect mismatches the page's own) implies a smaller scale --
         *    the regression an independent review found in the first viewport-aware cut of this contract. This
         *    is a *desired-quality floor*, not a safety bound: [SAFE_MAX_DIMENSION] and [RenderMemoryPolicy]
         *    below are the hard allocation ceilings, and either can still reduce the result below this floor when
         *    a page's own size can't support it (no-upscale) or memory safety requires it. A future, deliberately
         *    small request (e.g. a thumbnail) opts out of this floor simply by tightening [maxDimension] below
         *    it -- [resolveRenderTarget] applies the per-request ceiling *after* the floor, so an explicit
         *    smaller ceiling always wins.
         */
        const val DEFAULT_MAX_DIMENSION = 2048

        /** Absolute per-axis longest-edge ceiling. No request, however large its viewport or [maxDimension], can
         * cause a decode/rasterization past this on either axis -- a secondary, independent bound from
         * [RenderMemoryPolicy]'s byte budget below, kept for modest-hardware raster/texture compatibility. */
        const val SAFE_MAX_DIMENSION = 4096

        /** The pre-layout fallback request: an unknown (zero) viewport resolves to [DEFAULT_MAX_DIMENSION] below. */
        val DEFAULT = PageRenderRequest()

        /**
         * Phase 3B: the thumbnail-sized ceiling this slice's thumbnail strip actually uses, replacing the ad hoc
         * `maxDimension=200` example [resolveRenderTarget]'s doc and the Phase 3A/Codex R1 tests used only to prove
         * the contract *could* support a small request. Sized for a thumbnail grid cell (not reading fidelity): a
         * ~96dp-wide cell at up to 3x density is comfortably covered by a 320px longest edge, and even a worst-case
         * *square* decode at this ceiling (320x320 ARGB_8888, ~410KB) is a small fraction of [RenderMemoryPolicy]'s
         * ~32MiB-per-render reading budget -- see `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3B section for the full
         * cache-budget reasoning this feeds into ([com.d4guilar.shelfos.core.reader.ThumbnailLoader]).
         */
        const val THUMBNAIL_MAX_DIMENSION = 320

        /**
         * A thumbnail-shaped request: no viewport (a thumbnail grid cell's size, not the reader's own page-surface
         * viewport, is what should bound it -- the grid never asks [FixedReader.render] to know about Compose cell
         * layout), bounded by [THUMBNAIL_MAX_DIMENSION] rather than the [DEFAULT_MAX_DIMENSION] reading-quality
         * floor. This reuses the exact "a tightened [maxDimension] opts out of the floor" mechanism Codex R1's
         * remediation built [resolveRenderTarget] around for exactly this future caller -- no separate thumbnail
         * code path exists in [resolveRenderTarget] or [ImagePageRenderer]; CBZ and PDF both resolve through the
         * same policy function PDF/CBZ full-page reads already use.
         */
        fun thumbnail(): PageRenderRequest = PageRenderRequest(maxDimension = THUMBNAIL_MAX_DIMENSION)
    }
}

/** One axis-pair render target, in actual decode/rasterize pixels. */
data class RenderTargetSize(val width: Int, val height: Int) {
    /** Estimated ARGB_8888 byte cost of a bitmap at this size (`width * height * 4`); `Long` arithmetic, so this
     * can never integer-overflow even for a pathological pre-policy size. */
    val estimatedBytes: Long get() = width.toLong() * height.toLong() * 4L
}

/**
 * Explicit peak-memory accounting for fixed-reader page bitmaps (Codex R1 finding 3). A single bitmap bounded by
 * [PageRenderRequest.SAFE_MAX_DIMENSION]'s *longest edge* does not bound peak memory: at `ARGB_8888`, a
 * `4096x4096` square bitmap is ~64MiB, and [com.d4guilar.shelfos.feature.reader.FixedReaderViewModel]'s render/
 * cancellation lifecycle can briefly hold more than one decoded bitmap at once --
 * [MAX_CONCURRENT_BITMAPS] enumerates the worst case actually possible there:
 * 1. the bitmap currently published to reader state (owned by Compose/GC -- never force-recycled while it might
 *    still be on screen, so its disposal is not under this policy's direct control);
 * 2. a bitmap actively being decoded for a newer page/request (the one currently holding the render mutex); and
 * 3. -- only for the narrow window between an *older*, already-superseded render's decode finishing and that
 *    same critical section either publishing or discarding it (both now happen before the render mutex is
 *    released; see [com.d4guilar.shelfos.feature.reader.FixedReaderViewModel.render]'s doc) -- that older result,
 *    still briefly alive.
 *
 * [MAX_BITMAP_BYTES] -- not [PageRenderRequest.SAFE_MAX_DIMENSION] alone -- is therefore the real per-render
 * safety bound: an explicit `ARGB_8888` byte budget derived from a conservative total same-session peak budget
 * divided across the worst case above, so a single square bitmap can no longer reach ~64MiB merely because its
 * longest edge alone was in bounds ("no square 64MiB bitmap merely because its edge is ≤4096 unless there's
 * concrete justification" -- there is none here).
 */
object RenderMemoryPolicy {
    /** Conservative total peak-memory budget for same-session fixed-reader bitmaps, sized for modest hardware. */
    const val SESSION_BUDGET_BYTES: Long = 96L * 1024 * 1024

    /**
     * Codex R1 finding 2 (3C spread remediation): [ThumbnailLoader]'s own session-local thumbnail cache
     * ([ThumbnailLoader.DEFAULT_BUDGET_BYTES], ~16MiB) shares this same [SESSION_BUDGET_BYTES] envelope rather
     * than sitting entirely outside it -- both are created for, and live only as long as, the one fixed-reader
     * session. Referencing the constant directly (rather than re-declaring "16MiB" here) is deliberate: one
     * number, one owner, no duplicated magic constant between the two policies.
     */
    const val THUMBNAIL_RESERVATION_BYTES: Long = ThumbnailLoader.DEFAULT_BUDGET_BYTES

    /** What actually remains for reading-resolution page bitmaps once [THUMBNAIL_RESERVATION_BYTES] is set aside
     * from [SESSION_BUDGET_BYTES] -- the real envelope [MAX_BITMAP_BYTES]/[MAX_SPREAD_BITMAP_BYTES] below divide. */
    const val READING_BUDGET_BYTES: Long = SESSION_BUDGET_BYTES - THUMBNAIL_RESERVATION_BYTES

    /** Worst case same-session concurrently-live decoded bitmaps for ordinary single-page reading; see the class
     * doc (the currently-displayed bitmap, a newly-decoding one, and a narrow-window just-superseded one). */
    const val MAX_CONCURRENT_BITMAPS: Long = 3

    /** Per-bitmap `ARGB_8888` byte budget for a single-page render, derived from [READING_BUDGET_BYTES] (not the
     * full [SESSION_BUDGET_BYTES] -- see [THUMBNAIL_RESERVATION_BYTES]) divided across [MAX_CONCURRENT_BITMAPS]. */
    const val MAX_BITMAP_BYTES: Long = READING_BUDGET_BYTES / MAX_CONCURRENT_BITMAPS

    /** [MAX_BITMAP_BYTES] expressed in pixels (`ARGB_8888`: 4 bytes/pixel) -- what [resolveRenderTarget] bounds
     * width*height against for an ordinary (non-spread-slot) request. */
    const val MAX_BITMAP_PIXELS: Double = MAX_BITMAP_BYTES / 4.0

    /**
     * Codex R1 finding 2: during a spread-to-spread replacement (old pair A+B still strongly referenced by
     * StateFlow/Compose while new pair C+D is decoded sequentially and accumulated before publication), the real
     * worst-case ownership graph is FOUR live reading-resolution bitmaps at once, not two -- see
     * [com.d4guilar.shelfos.feature.reader.FixedReaderViewModel.render]'s doc for the full ownership argument
     * (the shared render mutex means only one render's decode section is ever actually running, so there is no
     * fifth concurrently-decoding bitmap beyond "2 old (published/Compose-held) + 2 new (locally accumulated)").
     */
    const val MAX_CONCURRENT_SPREAD_BITMAPS: Long = 4

    /** Per-slot `ARGB_8888` byte budget for an ACTIVE spread's slots ([PageRenderRequest.spreadSlot]), stricter
     * than [MAX_BITMAP_BYTES] because [MAX_CONCURRENT_SPREAD_BITMAPS] (4) is worse than
     * [MAX_CONCURRENT_BITMAPS] (3). Every transition class derived from this and [MAX_BITMAP_BYTES] together stays
     * within [READING_BUDGET_BYTES]: spread->spread is `4 * MAX_SPREAD_BITMAP_BYTES == READING_BUDGET_BYTES`
     * exactly (both old slots were themselves rendered under this same stricter budget); single->spread and
     * spread->single are each `MAX_BITMAP_BYTES + 2 * MAX_SPREAD_BITMAP_BYTES`, comfortably under
     * [READING_BUDGET_BYTES]; single->single remains `MAX_CONCURRENT_BITMAPS * MAX_BITMAP_BYTES ==
     * READING_BUDGET_BYTES` exactly, preserving the original 3A single-page guarantee (tightened only by the new
     * [THUMBNAIL_RESERVATION_BYTES] carve-out, which was always implicitly true -- the thumbnail cache always
     * coexisted in the same process -- just not previously reflected in this constant). */
    const val MAX_SPREAD_BITMAP_BYTES: Long = READING_BUDGET_BYTES / MAX_CONCURRENT_SPREAD_BITMAPS

    /** [MAX_SPREAD_BITMAP_BYTES] expressed in pixels -- what [resolveRenderTarget] bounds width*height against
     * for an active spread's slot ([PageRenderRequest.spreadSlot] `== true`). */
    const val MAX_SPREAD_BITMAP_PIXELS: Double = MAX_SPREAD_BITMAP_BYTES / 4.0
}

/**
 * The actual width/height a [PageRenderRequest] should decode/rasterize at for a page whose own undistorted
 * dimensions are [sourceWidth]x[sourceHeight]. Replaces the old purely-viewport-longest-edge
 * `resolveRenderTargetLongestEdge` (Codex R1 finding 2): that function could not tell a [FitMode.WIDTH] caller's
 * raw viewport box apart from the actual rendered-content box a tall page occupies in that fit mode (viewport
 * width, but page-aspect-derived height, which may far exceed viewport height) -- so a landscape viewport on a
 * tall page silently under-rendered it. This function needs the page's own aspect ratio to size correctly, which
 * is why it is only ever called once a page's bounds are already known (CBZ's bounds-only decode pass in
 * [ImagePageRenderer]; PDF's `page.width`/`page.height` in `PdfPages`), never before.
 *
 * The result is always aspect-preserving relative to [sourceWidth]x[sourceHeight] -- every reduction below is a
 * single uniform scale factor applied to both axes together, matching how `BitmapFactory`'s `inSampleSize`
 * downsamples both axes equally -- and is bounded, in order, by:
 * 1. **The caller's actual need**: [PageRenderRequest.fit]-aware scale against [PageRenderRequest.viewportWidth]/
 *    [PageRenderRequest.viewportHeight] when the viewport is known ([FitMode.PAGE] fits both axes inside the
 *    viewport; [FitMode.WIDTH]'s width always matches the viewport, height follows the page's own aspect ratio
 *    and may exceed viewport height -- mirroring [fixedReaderFittedContentSize]), or
 *    [PageRenderRequest.DEFAULT_MAX_DIMENSION] against the longest source edge before any real viewport is known
 *    (pre-layout fallback, byte-for-byte unchanged from Phase 1/2/3A).
 * 2. **The reading-quality floor** ([PageRenderRequest.DEFAULT_MAX_DIMENSION], see its doc): the result's
 *    longest edge never falls below it merely because step 1 implied a smaller scale.
 * 3. **The request's own ceiling** ([PageRenderRequest.maxDimension], itself bounded by
 *    [PageRenderRequest.SAFE_MAX_DIMENSION]) -- applied *after* the floor, so an explicit smaller ceiling (a
 *    future thumbnail request) always wins over it.
 * 4. **[RenderMemoryPolicy.MAX_BITMAP_PIXELS]**, an explicit byte-budget-based ceiling rather than a
 *    longest-edge-only one (Codex R1 finding 3) -- this is what actually degrades an ideal-but-oversized
 *    [FitMode.WIDTH] tall-page target or an oversized square request down to something safe, truthfully (a
 *    smaller bitmap, not a silently huge allocation), rather than pretending the ideal size is free.
 *
 * None of steps 2-4 can increase the scale step 1 already computed -- each is a `coerceAtMost`-style reduction --
 * and separately, neither of this function's two callers ever synthesizes pixels beyond a page's own native
 * resolution for CBZ either ([ImagePageRenderer]'s `inSampleSize` only ever increases from `1`, so a target above
 * native resolution simply decodes at native resolution, unchanged from Phase 1/2/3A).
 *
 * Defensive/overflow-safe: non-positive or malformed [sourceWidth]/[sourceHeight]/viewport/
 * [PageRenderRequest.maxDimension] values are treated as unspecified rather than propagated; all scale math is
 * `Double`, so a structurally valid but huge `Int` (e.g. a malformed `Int.MAX_VALUE` viewport) can never
 * integer-overflow -- by the time the final `Int` rounding step runs, every bound above has already pulled the
 * scale well under a safe range. Final rounding uses [floor] on each axis independently rather than nearest/round:
 * since `floor(a) <= a` and `floor(b) <= b`, `floor(a) * floor(b) <= a * b`, so flooring both axes can only ever
 * *tighten* the byte budget in step 4, never push the actual allocated size back over it by rounding up. The
 * result is always at least `1x1` and each axis is always in `1..SAFE_MAX_DIMENSION` (a final defensive
 * `coerceIn`, redundant with step 3's math in the normal case but independent of any floating-point edge case).
 */
fun resolveRenderTarget(sourceWidth: Int, sourceHeight: Int, request: PageRenderRequest): RenderTargetSize {
    val srcW = if (sourceWidth > 0) sourceWidth else 1
    val srcH = if (sourceHeight > 0) sourceHeight else 1
    val longestSource = maxOf(srcW, srcH).toDouble()

    val viewportW = request.viewportWidth
    val viewportH = request.viewportHeight
    val viewportScale = if (viewportW <= 0 || viewportH <= 0) PageRenderRequest.DEFAULT_MAX_DIMENSION / longestSource
        else when (request.fit) {
            FitMode.WIDTH -> viewportW.toDouble() / srcW
            FitMode.PAGE -> minOf(viewportW.toDouble() / srcW, viewportH.toDouble() / srcH)
        }
    val readingFloorScale = PageRenderRequest.DEFAULT_MAX_DIMENSION / longestSource
    var scale = maxOf(viewportScale, readingFloorScale)
    if (!scale.isFinite() || scale <= 0.0) scale = 1.0

    // Per-request/absolute longest-edge ceiling, applied after the floor so a tightened maxDimension always wins.
    val ceilingDimension = (if (request.maxDimension > 0) request.maxDimension else PageRenderRequest.DEFAULT_MAX_DIMENSION)
        .coerceAtMost(PageRenderRequest.SAFE_MAX_DIMENSION).toDouble()
    val longestAtScale = longestSource * scale
    if (longestAtScale > ceilingDimension && longestAtScale > 0.0) scale *= ceilingDimension / longestAtScale

    // Explicit byte/pixel memory budget, aspect-preserving (Codex R1 finding 3 / 3C remediation finding 2).
    // An active spread's slot uses the stricter spread-transition ceiling; an ordinary single-page request
    // (including the pre-layout fallback and thumbnails) uses the original per-render ceiling.
    val pixelCeiling = if (request.spreadSlot) RenderMemoryPolicy.MAX_SPREAD_BITMAP_PIXELS else RenderMemoryPolicy.MAX_BITMAP_PIXELS
    val pixelsAtScale = (srcW * scale) * (srcH * scale)
    if (pixelsAtScale > pixelCeiling && pixelsAtScale > 0.0)
        scale *= sqrt(pixelCeiling / pixelsAtScale)

    if (!scale.isFinite() || scale <= 0.0) scale = 1.0 / longestSource
    val width = floor(srcW * scale).toInt().coerceIn(1, PageRenderRequest.SAFE_MAX_DIMENSION)
    val height = floor(srcH * scale).toInt().coerceIn(1, PageRenderRequest.SAFE_MAX_DIMENSION)
    return RenderTargetSize(width, height)
}

/**
 * Original-page reading session. Page identity is the stored sequence, independent of reading direction. [render]
 * accepts a [PageRenderRequest] describing the actual presentation need (defaulting to the pre-layout fallback
 * resolution) rather than a single fixed resolution -- see [PageRenderRequest] and [resolveRenderTarget].
 */
interface FixedReader : Closeable {
    val pageCount: Int
    fun render(index: Int, request: PageRenderRequest = PageRenderRequest.DEFAULT): Bitmap

    /**
     * Phase 3C: a page's own undistorted dimensions, needed for spread-pairing eligibility ([PageGeometry.isLandscape])
     * -- deliberately NOT a reading-resolution render. CBZ ([ArchivePages]) reuses the exact bounds-only
     * `BitmapFactory` decode pass [ImagePageRenderer] already performs before every full decode (no new decode
     * path); PDF ([PdfPages]) reads `PdfRenderer.Page.width`/`height`, which `PdfRenderer` already exposes without
     * rasterizing. A future CBR [PageSource] adapter satisfies this the same way CBZ does today, through the same
     * [ImagePageRenderer]-shared bounds pass -- no CBR-specific geometry method is needed. Returns `null` for an
     * out-of-range index or an undecodable page, rather than throwing -- callers (spread pairing) already treat
     * unknown geometry as "not landscape" (see [resolvePageGroups]), so a geometry failure degrades gracefully
     * instead of blocking presentation.
     */
    fun pageGeometry(index: Int): PageGeometry?
}

/** `open` only for Codex R2's 3C remediation test seam (see `FixedReaderSpreadViewModelTest`'s
 * `GatedGeometryFixedReaderFactory`): a deterministic rapid-navigation test needs to wrap the real, factory-opened
 * [FixedReader] with a decorator that can deliberately hold a specific page's [FixedReader.pageGeometry] lookup
 * open, rather than relying on an async `StateFlow` collector's racy timing (Codex R2 found that approach
 * nondeterministic). Behavior is otherwise byte-for-byte unchanged -- no new production code path exists. */
open class FixedReaderFactory(private val files: PublicationFiles) {
    open fun open(item: LibraryItem): FixedReader {
        val descriptor = files.open(item)
        try {
            return when (item.format) {
                PublicationFormat.PDF -> PdfPages(descriptor)
                PublicationFormat.CBZ -> ArchivePages(descriptor)
                PublicationFormat.EPUB -> throw PublicationException(PublicationProblem.UNSUPPORTED_FORMAT, PublicationExceptionDetail.USE_EPUB_READER_INSTEAD)
            }
        } catch (error: Throwable) { descriptor.close(); throw error }
    }
}

private class PdfPages(descriptor: ParcelFileDescriptor) : FixedReader {
    private val renderer = openPdf(descriptor)
    override val pageCount get() = renderer.pageCount
    override fun render(index: Int, request: PageRenderRequest): Bitmap = renderer.openPage(index).use { page ->
        val target = resolveRenderTarget(page.width, page.height, request)
        val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        try { page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap }
        catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    override fun pageGeometry(index: Int): PageGeometry? =
        if (index !in 0 until pageCount) null
        else try { renderer.openPage(index).use { PageGeometry(it.width, it.height) } } catch (_: Throwable) { null }
    override fun close() = renderer.close()
}

/**
 * One page's bytes from a paged image-sequence container, independent of the container's archive format. A
 * container need not support random access to satisfy this contract: [openPage] is indexed by logical page
 * position, but whether (or how efficiently) an implementation serves pages out of order is entirely its own
 * concern. [ZipPageSource] is backed by true random access (`SeekableZip`'s positional reads); a future CBR
 * adapter over a "solid" RAR archive that cannot offer ZIP-style random access could instead serve this from a
 * one-time sequential index or a bounded extract-to-cache, without this interface -- or [ImagePageRenderer], which
 * is written only against it -- changing at all. This is the Phase 3A container/page-source boundary. Phase 3E-C
 * adds exactly that future CBR adapter, `RarPageSource` (`core/reader/RarPageSource.kt`): this interface and
 * [ImagePageRenderer] below were widened from file-private to `internal` for that one reason (a visibility-only
 * change, see `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3E-C record) -- `RarPageSource` is NOT wired into
 * [FixedReaderFactory]/any product format routing yet.
 */
internal interface PageSource : Closeable {
    val pageCount: Int
    fun openPage(index: Int): InputStream
}

/** [PageSource] backed by `SeekableZip`'s true random access; today's only container implementation, and the
 * only one CBZ (a ZIP container) needs. Named for that backing container -- not `ArchivePageSource` -- since
 * "archive" is generic enough to misleadingly suggest it already covers a future non-ZIP (e.g. RAR/CBR) format;
 * it does not (Codex R1 finding 5). */
private class ZipPageSource(private val zip: SeekableZip, private val entries: List<SeekableZip.Entry>) : PageSource {
    override val pageCount get() = entries.size
    override fun openPage(index: Int): InputStream = zip.open(entries[index])
    override fun close() = zip.close()
}

/**
 * Decodes one page image from any [PageSource] at a resolution driven by a [PageRenderRequest], bounded by the
 * same safety ceilings regardless of container format. Used by [ArchivePages] today; a future CBR [PageSource]
 * reuses this unchanged, rather than duplicating the bounds-then-sample decode policy per container format.
 */
internal object ImagePageRenderer {
    /** Bounds-only decode (no full-resolution allocation) -- the same `inJustDecodeBounds` pass [render] already
     * performs before every full decode, exposed standalone for [FixedReader.pageGeometry] (Phase 3C). Returns
     * `null` rather than throwing for an out-of-range index or an undecodable page. */
    fun bounds(source: PageSource, index: Int): PageGeometry? {
        if (index !in 0 until source.pageCount) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            source.openPage(index).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) PageGeometry(bounds.outWidth, bounds.outHeight) else null
        } catch (_: Throwable) { null }
    }

    fun render(source: PageSource, index: Int, request: PageRenderRequest): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.openPage(index).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 100_000_000)
            throw PublicationException(PublicationProblem.CORRUPT, PublicationExceptionDetail.PAGE_IMAGE_DAMAGED_OR_TOO_LARGE)
        val target = resolveRenderTarget(bounds.outWidth, bounds.outHeight, request)
        val targetLongestEdge = maxOf(target.width, target.height)
        var sample = 1
        // sample only ever increases from 1, so a source already at or below the target decodes at its own
        // native resolution -- never upscaled, however large the request's viewport/maxDimension is.
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > targetLongestEdge) sample *= 2
        return source.openPage(index).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw PublicationException(PublicationProblem.CORRUPT, PublicationExceptionDetail.PAGE_IMAGE_DECODE_FAILED)
    }
}

/** Streams one page at a time from the archive; nothing is extracted to storage. */
private class ArchivePages(private val descriptor: ParcelFileDescriptor) : FixedReader {
    private val zip = ArchivePolicy.open(descriptor)
    private val entries = try { ArchivePolicy.pages(zip) } catch (e: Throwable) { zip.close(); throw e }
    private val source: PageSource = ZipPageSource(zip, entries)
    override val pageCount get() = entries.size
    override fun render(index: Int, request: PageRenderRequest): Bitmap = ImagePageRenderer.render(source, index, request)
    override fun pageGeometry(index: Int): PageGeometry? = ImagePageRenderer.bounds(source, index)
    override fun close() { try { zip.close() } finally { descriptor.close() } }
}
