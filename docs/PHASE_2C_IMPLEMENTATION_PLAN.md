# Phase 2C implementation plan: Original PDF hardening and fidelity

Status: **investigation/planning pass, 2026-10-01**, on branch
`phase-2/pdf-fidelity` (base `main` at `7dcfd38`). This document is the
canonical Phase 2C planning location referenced by
[`PHASE_2_PLAN.md`](PHASE_2_PLAN.md#2c--original-pdf-hardening-and-fidelity-investigationplanning-active).
**No production code changes were made during this pass.** This is discovery
and architecture planning only, per AGENTS.md's "do not build yet unless
explicitly requested" guidance and the owner's explicit instruction for this
pass. Adapted PDF, OCR, AI enhancement, annotations and a new PDF engine
remain out of scope here, as they are everywhere else in the repository.

## 1. Motivation and question being investigated

A field observation during Phase 2A work: a New X-Men PDF looked blurry on a
Samsung Galaxy Tab A (SM-T580, Android 8.1), particularly around text. It was
**not** established whether this was caused by the source file's own quality
(a scan, a low-resolution rip, lossy re-compression), ShelfOS's fixed
2048px-longest-edge render budget, display scaling/density on that specific
tablet, or some combination. `PHASE_2_PLAN.md` §5 recorded pipeline
observations during 2A without changing behavior. 2C is where this gets an
actual controlled investigation and, if warranted, a scoped fix.

This document does not assume ShelfOS is at fault. Where evidence could not
be gathered (no instrumented device in this environment, no scanned/raster
PDF fixture in the repository), that gap is stated explicitly rather than
papered over with invented numbers.

## 2. Current PDF pipeline (verified against actual code, 2026-10-01)

All of the following was confirmed by reading the actual source in this
environment, not assumed from historical docs. File paths are exact.

**Entry point / file access**
- `FixedReaderFactory.open(item)` (`core/reader/FixedReader.kt`) calls
  `PublicationFiles.open(item)` (`core/files/PublicationFiles.kt`), which
  returns a read-only `ParcelFileDescriptor` — either from an app-managed
  private copy (`ParcelFileDescriptor.open(managedFile(...), MODE_READ_ONLY)`)
  or from the original SAF `Uri` via `resolver.openFileDescriptor(uri, "r")`.
  Sources are never opened for writing (AGENTS.md rule 4 is respected
  structurally, not just by convention).
- For `PublicationFormat.PDF`, `FixedReaderFactory` constructs `PdfPages(descriptor)`.
- `PdfPages` calls `openPdf(descriptor)` (`core/files/PublicationFiles.kt`),
  which wraps `android.graphics.pdf.PdfRenderer(descriptor)` and maps
  `SecurityException`/`IllegalArgumentException`/`IOException` to explicit
  `PublicationProblem`s (`PROTECTED`, `NEEDS_COPY`, `CORRUPT`) — protected,
  damaged and non-seekable documents already fail with a typed, user-facing
  problem rather than a crash.

**Page render**
```kotlin
// core/reader/FixedReader.kt, PdfPages.render(index)
renderer.openPage(index).use { page ->
    val scale = MAX_PAGE_PIXELS.toFloat() / maxOf(page.width, page.height)
    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    bitmap
}
```
with `MAX_PAGE_PIXELS = 2048` (a private constant in `FixedReader.kt`). This
is an exact match for `PHASE_2_PLAN.md` §5's historical description — nothing
had drifted. Every page, regardless of its own aspect ratio, content type, or
the viewport it will actually be displayed in, is rendered once at a fixed
2048px-longest-edge budget into a full `ARGB_8888` bitmap via
`RENDER_MODE_FOR_DISPLAY` (anti-aliased, intended for on-screen display rather
than print).

**ViewModel / state**
- `FixedReaderViewModel.render(page)` (`feature/reader/FixedReaderViewModel.kt`)
  cancels any in-flight render `Job` (`rendering?.cancel()`), then launches a
  new coroutine that acquires a `Mutex` and calls `session.render(page)` on
  `Dispatchers.IO`, checks `ensureActive()` after the mutex-guarded render
  completes, and only then publishes the bitmap into `FixedReaderState`.
  **This already provides real stale-render prevention** for the page-change
  case: a superseded render job is cancelled before publishing, and the
  `Mutex` also serializes all `PdfRenderer`/archive access (open, page open,
  render) through one critical section — see §9 below.
- Exactly one `Bitmap` is held in `FixedReaderState.bitmap` at a time. No
  prefetch, no cache, no retained previous/next page.

**Screen / zoom**
- `FixedReaderScreen.kt` displays the bitmap via Compose `Image` with
  `Modifier.graphicsLayer { scaleX = scale; scaleY = scale; translationX = ...;
  translationY = ... }`, where `scale` is local Compose state (`1f..5f`)
  updated by a two-finger `calculateZoom()` pinch gesture or a center
  double-tap (`1f`/`2f` toggle). **Zoom never triggers a re-render.** It is
  pure GPU/Compose transform scaling of the one existing bitmap. This
  confirms the historical claim exactly.
- `FitMode.PAGE` lays the image out with `Modifier.fillMaxSize()` +
  `ContentScale.Fit` (letterboxed, aspect-ratio-preserving, bounded by the
  shorter of viewport width/height scale factors). `FitMode.WIDTH` lays it out
  with `Modifier.fillMaxWidth().aspectRatio(bitmap.width / bitmap.height)`
  inside a `verticalScroll` container (width-bound, can overflow vertically).
  Neither mode currently reads the actual rendered bitmap's pixel dimensions
  back into the *render* decision — they only affect Compose layout of an
  already-fixed-resolution bitmap.
- Page change resets zoom: `scale`/`panX`/`panY` are `remember(state.page) {
  mutableFloatStateOf(...) }` — keyed on the page index, so changing pages
  always resets zoom/pan to `1f`/`0f`/`0f`. This is intentional existing
  behavior (confirmed in code, not assumed) and is preserved as-is in every
  recommendation below (Step 14 of the brief: do not redesign unless 2C
  requires it — it does not).

**Lifecycle / close**
- `FixedReaderViewModel.onCleared()` sets `closed = true`, closes the
  `PositionWriter`, and launches `session?.close()` on the app-level
  `CoroutineScope` (survives the ViewModel's own cancelled scope) under the
  same `Mutex` used by `open()`/`render()` — so a render in flight cannot race
  a close that recycles/invalidates the underlying `PdfRenderer`.
- `PdfPages.close()` calls `renderer.close()`. `ArchivePages.close()` closes
  the zip then the descriptor.

## 3. Current CBZ pipeline and the shared boundary

- `FixedReaderFactory` constructs `ArchivePages(descriptor)` for
  `PublicationFormat.CBZ`, implementing the same `FixedReader` interface
  (`pageCount`, `render(index): Bitmap`, `Closeable`).
- `ArchivePages.render(index)` decodes the entry's bounds first
  (`inJustDecodeBounds = true`), rejects corrupt/oversized images
  (`outWidth*outHeight > 100_000_000`), then computes `sample` by doubling
  until `max(outWidth, outHeight) / sample <= MAX_PAGE_PIXELS` (the **same**
  2048 constant), and decodes once with that `inSampleSize`.
- **Critically, this is downsample-only and power-of-two.** If a comic page
  image's longest edge is already ≤2048px (the common case for typical CBZ
  scan/rip resolutions), `sample` stays `1` and the page decodes at its own
  native resolution — no upscaling, no quality loss ShelfOS introduces. PDF's
  `PdfPages.render()`, by contrast, *always* scales to exactly 2048px longest
  edge regardless of the page's own content resolution, because PDF page
  dimensions are expressed in points (see §4), not source pixels, so there is
  no equivalent "already native, skip the no-op" case for PDF the way there
  is for a raster CBZ page.
- **This is the actual, evidence-based explanation for the field report that
  native CBZ looked sharp on the same older tablet while PDF did not**: CBZ's
  sampling policy is "never do more work than the source needs, cap only
  oversized sources," while PDF's policy is "always rasterize to exactly this
  fixed budget," which can be wrong in either direction (under-serving a
  high-zoom view, or needlessly over-rendering a small viewport) and, more
  relevantly to the field report, provides no guarantee of matching whatever
  resolution would look sharp at the viewer's actual zoom level.
- **Shared boundary, precisely:** `FixedReaderViewModel`, `FixedReaderScreen`,
  `FixedReaderState`, the zoom/pan/fit Compose logic, the `Mutex`/cancellation
  pattern, and the `FixedReader` interface itself are all shared between PDF
  and CBZ. The *only* format-specific code is inside `PdfPages`/`ArchivePages`
  themselves (two private classes in the same file). **A PDF-only fidelity
  change can be introduced entirely inside `PdfPages` (and, if the render
  request needs new input such as a target dimension, by widening the shared
  `FixedReader.render(index)` method signature in a way `ArchivePages` either
  ignores or interprets under its own existing sampling policy) without
  touching `FixedReaderScreen.kt`'s zoom/fit/gesture code or
  `ArchivePages`'s sampling policy.** This matches the brief's Step 2
  instruction to prefer separating rendering *policy* by source capability
  over UI branching — the natural separation point already exists
  structurally; it is not implemented in this pass.

## 4. PdfRenderer dimensions: what they actually mean

`PdfRenderer.Page.width`/`.height` are, per the Android platform contract,
**the page's media-box dimensions in PDF points (1/72 inch)**, not pixels and
not display-relative. They describe the page as authored, independent of any
rendering target. `PdfPages.render()`'s `scale = MAX_PAGE_PIXELS /
maxOf(page.width, page.height)` therefore converts "points" directly into "a
fixed pixel budget" with no reference to:
- the actual device display density (`Resources.getSystem().displayMetrics.density`
  / `densityDpi`),
- the actual viewport size the bitmap will be laid out into (Compose layout
  size in px, available only after measurement — not generally known at the
  time `render()` runs, since render happens in the ViewModel/IO layer, not
  in the composable),
- the current fit mode (`PAGE` vs `WIDTH`),
- or the current zoom level.

This confirms the pipeline **does preserve aspect ratio** (both dimensions
scale by the same factor) but **never adapts to viewport, density, fit mode,
or zoom**. It can be *simultaneously* too low (a high-density tablet at >1x
zoom, where 2048px stretched across more physical pixels looks soft) and
unnecessarily high (a compact low-density phone in `FitMode.PAGE`, where the
on-screen page occupies far fewer than 2048 physical pixels on its longest
edge, so most of the rendered detail is downscaled away by Compose layout and
spent for nothing but memory and render time). Neither direction was
previously measured; §7 below works out the memory cost analytically.

## 5. Display/viewport measurement findings

- Compose layout provides actual viewport pixel dimensions only after
  measurement, inside a composable (`BoxWithConstraints`, `onSizeChanged`, or
  `LocalDensity`/`LocalConfiguration` combined with `Modifier.onGloballyPositioned`).
  `FixedReaderViewModel.render()` runs outside composition, so a
  viewport-aware render request would need the Activity/Compose layer to
  **report** the measured viewport size/density to the ViewModel (e.g. via a
  new `vm.updateViewport(widthPx, heightPx, density)` call from
  `FixedReaderScreen`'s `BoxWithConstraints`/`onSizeChanged`), rather than the
  ViewModel querying it directly — this keeps the existing MVVM/unidirectional
  boundary (AGENTS.md's "Screen-level ViewModels", "Unidirectional data flow")
  intact.
- Five distinct units are in play and must not be conflated in any future
  implementation: **dp** (density-independent, UI layout unit),
  **physical/screen pixels** (`dp * density`), **PDF page units** (points,
  1/72 inch, from `PdfRenderer.Page`), **bitmap pixels** (the `Bitmap`
  ShelfOS allocates and `PdfRenderer` draws into), and **Compose layout
  coordinates** (post-measurement px the `Image` composable occupies, which
  can differ from the raw viewport if letterboxed by `ContentScale.Fit`).
- A future render-target calculation should be expressed as: desired on-screen
  physical pixels for the page's longest edge (viewport px × fit-mode factor ×
  current zoom factor, clamped to a memory cap) → divided by the PDF page's
  own longest edge in points → `scale` passed to `PdfRenderer.Page.render()`'s
  implicit bitmap-size contract (the existing `Bitmap.createBitmap(w*scale,
  h*scale, ...)` call), replacing the current constant-only `MAX_PAGE_PIXELS /
  maxOf(page.width, page.height)` formula with one that also incorporates
  real viewport/zoom/density instead of only the fixed budget.
- No device/emulator was reachable in this environment (see §6) to directly
  observe `Resources.getSystem().displayMetrics` values for the RP5, API 35
  emulator, or Galaxy Tab A; density assumptions above are from platform
  documentation and prior Phase 2 validation records, not fresh measurement
  in this pass.

## 6. Controlled render-size experiment — NOT PERFORMED; explicit coverage gap

Step 5 of the brief calls for measuring actual render behavior (bitmap
dimensions, memory, render duration, visual fidelity) at multiple target
longest-edge resolutions (1024/2048/3072/4096) against representative PDF
fixtures (vector/text-heavy, raster/scanned, mixed).

**This could not be performed in this environment, and no numbers are
fabricated to fill the gap:**

- `adb` is not available in this shell (`adb devices` returns "command not
  found"), and no emulator or physical device is reachable from this
  environment. `PdfRenderer` is an Android framework class with no JVM-
  testable equivalent, so render duration/visual fidelity cannot be measured
  without a device or emulator.
- The **only** PDF fixture in the repository is
  `OriginalFixtures.pdf()` (`app/src/androidTest/java/com/d4guilar/shelfos/OriginalFixtures.kt`):
  a synthetically generated 3-page, 400×600-point, pure-vector/text PDF
  (`android.graphics.pdf.PdfDocument` drawing a single line of black text on
  a white background per page). There is **no raster, scanned, or
  mixed-content PDF fixture anywhere in the repository** — consistent with
  the repo's stated policy of not packaging private samples or artwork. This
  means even with a device available, the repository's own fixtures could
  only exercise the vector/text-heavy case from Step 5's list, not the
  raster/scanned or mixed-content cases.
- **What this gap means for the recommendation in §12**: the quantitative
  render-duration/visual-fidelity comparison this step asks for is deferred
  to actual 2C implementation time, when a connected emulator/device is
  available and a privately-held raster/scanned PDF (the owner's own New
  X-Men file, or an equivalent, never committed to the repo) can be used for
  manual visual comparison. The architectural recommendation in §12 is
  derived from the mathematical/structural analysis in §4, §7 and §9
  (which does not require a device) rather than from empirical timing data,
  and is explicitly flagged as needing device confirmation before being
  treated as final.
- No temporary instrumented test/tool was written for this step, because
  without `adb`/a device it could not have been run or captured real output
  — writing one that was never executed would not satisfy the brief's "do
  not fabricate numbers" instruction, and per Step 25 any such tool must be
  reverted before commit regardless. Nothing of this kind was added to the
  working tree.

## 7. Source quality vs. ShelfOS quality

Without a device or a representative raster/scanned fixture, source-content
classification for the New X-Men PDF specifically cannot be performed in
this pass. What can be established from the pipeline alone:

- **Vector/text content**: `PdfRenderer.Page.render()` rasterizes vector
  paths and text at whatever bitmap resolution ShelfOS requests — there is no
  fixed "native resolution" for vector content to fall below or exceed, so
  for a pure-vector/text PDF, a higher target resolution *can* genuinely
  improve apparent sharpness up to the point where it exceeds what the
  viewport/zoom can actually display, with no ceiling imposed by the source
  itself. This is the case the repository's own fixture represents, and the
  case where "ShelfOS under-renders" is the most plausible, measurable,
  fixable explanation of blurriness.
- **Embedded raster/scanned content**: a PDF page containing an embedded
  raster image (a scan, or a photo) has a real source-resolution ceiling.
  `PdfRenderer` rasterizes the *page* at the requested scale, but an embedded
  image's own pixel data cannot gain detail it does not have — rendering a
  low-resolution scan at a higher target longest-edge only enlarges existing
  pixels (closer to what zoom already does today) rather than recovering
  missing detail. **2C must not attempt to compensate for this with
  sharpening or upscaling** (explicitly out of scope, per AGENTS.md and the
  brief) — the correct, honest behavior for a source-limited page is to
  render at a reasonable resolution and accept that the result reflects the
  source, not to disguise it.
- **The New X-Men PDF itself was not independently inspected in this pass**
  (no device, and the owner's actual file is correctly not part of this
  repository) — whether it is source-limited, ShelfOS-render-limited, or both
  remains genuinely unresolved pending a manual side-by-side comparison on a
  real device once the owner can run one against a build implementing §12's
  recommendation. This is stated plainly as an open question, not resolved
  here.

## 8. Memory model (ARGB_8888, analytical — no device measurement available)

All figures below are calculated directly from `PdfPages.render()`'s own
formula (`scale = target / maxOf(page.width, page.height)`,
`bitmap = w*scale × h*scale`, `ARGB_8888` = 4 bytes/pixel) applied to a
representative US Letter page (612×792 points, portrait, from
`PdfRenderer.Page`'s point-based contract in §4) and are exact arithmetic, not
measured allocations:

| Target longest edge | Bitmap dimensions | Bytes | Approx. MB |
| --- | --- | --- | --- |
| 1024 | 791 × 1024 | 3,239,936 | ~3.1 MB |
| 2048 (current fixed budget) | 1583 × 2048 | 12,967,936 | ~12.4 MB |
| 3072 | 2374 × 3072 | 29,179,392 | ~27.8 MB |
| 4096 | 3165 × 4096 | 51,867,648 | ~49.5 MB |

Retention scenarios, using the current 2048px budget as the baseline and the
candidate 3072/4096 budgets for comparison:

| Scenario | 2048px budget | 3072px budget | 4096px budget |
| --- | --- | --- | --- |
| One active bitmap (current behavior) | ~12.4 MB | ~27.8 MB | ~49.5 MB |
| Current + next (one-ahead prefetch) | ~24.8 MB | ~55.6 MB | ~99.0 MB |
| Current + previous + next (3-page LRU) | ~37.2 MB | ~83.4 MB | ~148.5 MB |
| Transient overlap during re-render (old bitmap retained until new one is ready, to avoid a blank flash) | +1 bitmap at whichever size is in flight, momentarily | | |

These are per-page-pair figures for a single representative portrait page;
an actual implementation must also account for: a higher-resolution zoom
re-render briefly holding both the base-resolution and the new
higher-resolution bitmap simultaneously during swap (an additional transient
spike on top of whatever steady-state cache is chosen); `Bitmap` objects
surviving until Compose/GC releases them (the codebase comment in
`FixedReaderViewModel.render()` — "Published bitmaps are owned by
Compose/GC, not manually recycled while displayed" — means a cancelled/
replaced bitmap is explicitly `recycle()`d only when **not** yet published,
but a published-then-superseded bitmap relies on GC, not immediate
reclamation); and that Activity/ViewModel retention across a configuration
change does **not** currently re-render — the existing `FixedReaderViewModel`
survives process-held configuration changes and keeps its last-rendered
bitmap in `StateFlow`, so a config change alone does not spike memory under
the current architecture, and should not under any 2C change either.

**Implication for §12's memory cap recommendation**: 4096px triple-buffered
(current+previous+next) reaches ~148 MB for a single reader screen's bitmaps
alone, before any other app memory — a real risk on a modest/low-RAM device
whose entire per-app heap limit can be well under that. 3072px triple-buffered
(~83 MB) is materially safer. This arithmetic, not a device measurement, is
the basis for preferring a bounded single-or-double-buffer strategy over an
automatic triple-buffer LRU at an increased resolution (see §12.7–§12.8).

## 9. PdfRenderer lifecycle, threading and concurrency

- `android.graphics.pdf.PdfRenderer` and its `Page` objects are documented by
  the Android platform as **not thread-safe for concurrent use of the same
  renderer instance** — only one `Page` may be open at a time, and rendering
  must not overlap. ShelfOS's current code already respects this, structurally:
  `FixedReaderViewModel` serializes every `session`-touching operation
  (`open()`, `render()`, and `onCleared()`'s close) through one shared
  `Mutex`, all dispatched on `Dispatchers.IO`. There is currently **no**
  concurrent access to the same `PdfRenderer`/`Page` anywhere in the codebase.
- `render(page)`'s `rendering?.cancel()` + fresh `Job` pattern, combined with
  the `Mutex`, already prevents the specific race the brief's Step 10 asks
  about (page 4 render in flight, user switches to page 5, a stale render
  completing late): the old `Job` is cancelled *before* the new one starts,
  and `ensureActive()` is checked immediately after the mutex-guarded render
  call returns, before the result is published to `_state`. A cancelled job's
  result (if it already finished before cancellation was observed) is
  explicitly `recycle()`d, never published. **This is a materially stronger
  starting point than "no stale-render prevention exists" — it already
  exists for the page-change case.** What it does *not* yet handle, because
  nothing currently requests it, is a *second axis* of request identity:
  today a render request is identified by page index alone; introducing
  zoom-triggered re-render means a request must additionally carry a target
  resolution/quality-bucket so that a stale *same-page, old-resolution*
  render in flight can be distinguished from, and superseded by, a newer
  *same-page, higher-resolution* request (§11).
- **Concurrency/serialization rule for 2C**: continue serializing all
  `PdfRenderer`/`Page` access through the existing single `Mutex` per
  `FixedReader` session. Do **not** introduce a second concurrent render path
  (e.g. speculative prefetch rendering on a separate coroutine against the
  same `PdfRenderer`) without first confirming — against the platform
  contract, not assumption — that doing so is safe; the existing single-mutex
  model already satisfies the brief's "do not introduce concurrent
  `PdfRenderer` use unless known-safe" instruction by construction, and 2C's
  default should be to keep it that way rather than add speculative
  parallelism.
- **Ownership/close semantics**: unchanged from today — `FixedReaderViewModel`
  owns the one `FixedReader` session for the screen's lifetime;
  `onCleared()` closes it under the same mutex used for rendering, so a
  render cannot be left executing against a closed/invalid renderer. Any 2C
  cache (if adopted, see §12.7) must close/discard its retained bitmaps at
  the same teardown point, and must not itself retain a second
  `PdfRenderer`/`Page` reference independent of the one `FixedReader` already
  owns.

## 10. Fit Page / Fit Width semantics review

- **Fit Page** (`FitMode.PAGE`): `Modifier.fillMaxSize()` + `ContentScale.Fit`
  letterboxes the bitmap into the available viewport, preserving aspect
  ratio, bound by whichever of width/height is the limiting dimension. A
  "sufficient base render resolution" for Fit Page is: the bitmap's longest
  edge, after Compose's `ContentScale.Fit` scaling, should land at
  approximately 1:1 with physical display pixels at the *initial* (1x) zoom
  level — i.e., render target ≈ viewport's limiting-dimension physical pixel
  count at the page's own aspect ratio, not a fixed constant. Today's fixed
  2048px budget can exceed or fall short of this depending on device density
  and page aspect ratio; neither direction was previously quantified (§4).
- **Fit Width** (`FitMode.WIDTH`): `Modifier.fillMaxWidth()` with
  `aspectRatio(bitmap.width / bitmap.height)` inside a `verticalScroll`
  container — the page can be taller than the viewport and scrolls. A
  "sufficient base render resolution" here is: the bitmap's width, after
  `fillMaxWidth()` scaling, should land at approximately 1:1 with the
  viewport's physical pixel width — i.e., render target's width ≈ viewport
  physical pixel width, independent of viewport height (since height can
  scroll). This means Fit Width's resolution requirement is driven by width
  alone, not longest-edge, which the current single `MAX_PAGE_PIXELS`
  longest-edge formula does not distinguish between modes for at all.
- **A fidelity solution must not change what these modes mean** — only what
  resolution is requested to serve them. Neither mode's layout/scroll/fit
  behavior needs to change for 2C; the render-target *input* changes, not the
  Compose layout logic that consumes the resulting bitmap.

## 11. Page-change + zoom semantics (current, preserved as-is)

Confirmed directly from `FixedReaderScreen.kt`: `scale`, `panX`, `panY` are
`remember(state.page) { mutableFloatStateOf(...) }` — keyed on page index, so
**zoom and pan always reset to `1f`/`0f`/`0f` on every page change**, and fit
mode (`state.preferences.fit`) determines the initial Compose layout
(`ContentScale.Fit` vs. `fillMaxWidth` + scroll) applied to that 1x state.
Persisted appearance (`ReaderPreferences`) contains `fit`, but **not** zoom
level or pan position — those are ephemeral Compose state, never persisted,
and reset on page turn by design. **2C must preserve this exactly**: it is
existing, intentional behavior (not something Step 14 asks to redesign), and
any zoom-aware re-render mechanism must hook into the *existing* zoom gesture
state without changing when or whether zoom resets.

## 12. Resize/rotation/recreation implications (2D's territory, must not block)

- `FixedReaderViewModel` survives configuration changes (it is a
  `ViewModel`), and its `StateFlow`-held bitmap/page/preferences persist
  across rotation/resize without a new render being triggered today — Compose
  simply re-lays-out the existing bitmap at the new viewport size via the
  same `ContentScale.Fit`/`fillMaxWidth` logic. This means a rotation or a
  foldable fold/unfold currently can leave the bitmap under-resolved for the
  new (likely larger, e.g. landscape or unfolded) viewport, with no
  mechanism to request a higher-resolution render for the new viewport size.
- 2C's rendering-policy design must leave room for a **future** (2D-owned)
  "viewport changed significantly enough to warrant a re-render" signal to
  plug into the same render-request mechanism zoom-triggered re-render uses
  (§13) — the same request-identity model (page, target resolution/quality
  bucket) can carry a resize-triggered request without 2C needing to
  implement the trigger itself. 2C should **not** implement
  viewport-change-triggered re-rendering now; it should only avoid an
  architecture that makes it structurally awkward to add in 2D.

## 13. Image quality / filtering review

- Compose's `Image` composable with a `Bitmap`-backed `ImageBitmap` uses the
  platform's default bitmap sampling (bilinear filtering) when
  `graphicsLayer` scale is applied; no explicit filter quality configuration
  exists in `FixedReaderScreen.kt` today, and none should be added — this is
  interpolation quality (how an existing bitmap is stretched), which is a
  separate concern from render *resolution* (how much real detail the bitmap
  contains). **No sharpening filter, custom shader, or interpolation-quality
  override is proposed anywhere in this plan.** The entire fidelity
  recommendation in §14 is about requesting more real pixels from
  `PdfRenderer` when warranted, never about processing existing pixels to
  look sharper.

## 14. Recommended minimum viable architecture for Phase 2C

Every recommendation below is tied to the code inspection (§2–§3, §9–§11) or
the arithmetic (§4, §8) above, not preference. Where device/empirical
confirmation is still needed (per §6's honest gap), that is stated.

**(1) Should the fixed 2048px-longest-edge policy be replaced?**
Yes, but narrowly: replace "always exactly 2048" with "compute the initial
render target from the actual viewport's limiting dimension (per fit mode,
§10) and density, clamped to a memory-safe ceiling" — not a different fixed
constant. The *mechanism* (a single longest-edge target feeding the existing
`scale = target / maxOf(page.width, page.height)` formula) does not need to
change; only how the target number is derived does.

**(2) What should initial PDF render dimensions depend on?**
Viewport physical pixel dimensions (reported from Compose via a new
`vm.updateViewport(...)`-style call, §5), the active fit mode (longest edge
for `PAGE`, width for `WIDTH`, §10), and display density — **not** zoom (zoom
starts at 1x on every page per §11) and **not** device model/API level
(AGENTS.md: no device-model checks; the brief's Step 8: do not key quality
off Android version).

**(3) Should zoom trigger higher-resolution re-render?**
Yes, but only after zoom settles past a threshold and only as an upgrade path
layered on top of the existing instant `graphicsLayer` scale transform — the
existing immediate-visual-feedback zoom behavior must not change; a
higher-detail bitmap swaps in later, once ready, as a quality upgrade the
user may not even notice arriving. This matches the brief's Step 9 model
(initial fitted render → instant transform scaling → debounced high-detail
re-render → swap-in) and is the single most evidence-grounded change here,
since §4 and §9 both show the current architecture has no mechanism for it
at all today.

**(4) At what threshold/quality-bucket strategy?**
A small, fixed set of discrete longest-edge buckets (e.g. "fitted", "2x
fitted", a memory-capped maximum — exact multiplier/count decided at
implementation time against real device measurement, not fixed here),
triggered only after the pinch gesture's `awaitEachGesture` loop in
`FixedReaderScreen.kt` settles (no pointer events pending, i.e. after the
gesture's `do { ... } while (event.changes.any { it.pressed })` loop exits) —
**not** on every pinch delta, which would cause render storms the brief's
Step 9 explicitly warns against. Debounce further with a short settle delay
before issuing the re-render request, consistent with "must stay responsive,
avoid render storms on every pinch delta."

**(5) What memory cap should bound target bitmap dimensions, and why?**
Per §8's arithmetic, a single-higher-resolution-bitmap-at-a-time ceiling
around 3072px longest edge (~28 MB for a representative portrait page) is a
materially safer default than 4096px (~50 MB), especially once a brief
transient overlap (old + new bitmap during swap, §8) is accounted for — that
transient alone can approach ~78 MB at the 3072px cap or ~62 MB at the 2048+
4096 swap boundary. The exact ceiling needs device confirmation (§6's gap)
before being finalized, but the arithmetic alone supports capping below
4096px as the default rather than above it.

**(6) Should low-RAM devices get a lower cap?**
Yes, gated on `ActivityManager.isLowRamDevice()` and/or
`ActivityManager.getMemoryClass()` (both real, already-available Android
signals, per the brief's Step 8 — no new device-tier framework needed) rather
than API level or device model. A low-RAM device should use a lower ceiling
(e.g. stay at or near the current 2048px baseline, skip the higher-resolution
zoom re-render tier entirely, or both) — the exact reduction is an
implementation-time decision justified against `getMemoryClass()`'s reported
heap budget at run time, not invented here.

**(7) Is a cache warranted in 2C?**
**Defer** (brief's option D: "defer cache entirely because fidelity
re-rendering is the actual issue"). Evidence: the field report was about
*blurriness*, not *navigation slowness* — nothing in §2's pipeline audit or
§6's (device-less) investigation surfaced a reported or measured performance
complaint about prev/next page navigation feeling expensive; the brief's own
Step 11 says "measure first, don't assume a cache is needed," and no
measurement was possible in this environment (§6). Introducing a 2–3 page LRU
*and* a higher-resolution re-render tier in the same slice would also compose
their memory costs (§8) in a way that is harder to reason about and test
incrementally. A single current-bitmap-at-a-time model, with the
zoom-re-render upgrade path added on top, is the smaller, more reviewable
first step; a cache/prefetch slice (2C.3 in §15) can be added later **only
if** real on-device measurement during 2C.1/2C.2 shows navigation feels slow
enough to justify the added memory/complexity.

**(8) Is adjacent-page prefetch warranted?**
**Defer**, same reasoning as (7) — it is the same class of decision (option
B vs. D) and the same missing evidence applies equally.

**(9) Should PDF rendering policy be separated from CBZ policy?**
Yes — and it already structurally can be, per §3: confine all changes to
`PdfPages` (and, if the shared `FixedReader.render(index)` signature needs to
grow to carry a target-resolution/quality-bucket parameter, `ArchivePages`
keeps its own existing downsample-only sampling policy and simply does not
need to honor a resolution hint the same way, or can honor it only as an
additional *cap* on top of its own already-correct behavior — never making
CBZ worse). `FixedReaderScreen.kt`'s zoom/fit/gesture/Compose code remains
fully shared and unchanged; only `PdfPages`'s internal render-target
calculation changes.

**(10) What concurrency/serialization rules are required?**
Keep the existing single `Mutex`-per-session serialization (§9) unchanged.
Do not add a second concurrent render path against the same `PdfRenderer`.

**(11) How are stale render results prevented?**
Extend the existing page-index-keyed cancellation (§9) to also key on target
resolution/quality bucket: a render request becomes `(page index, quality
bucket)` rather than just `page index`. The existing `rendering?.cancel()` +
fresh-`Job` + `ensureActive()`-before-publish pattern already generalizes to
this directly — cancel-and-replace on *either* a page change or a
zoom-triggered quality-bucket change, keyed by a small value class/data class
request identity rather than page index alone.

**(12) How does the design behave after rotation/resize?**
No new behavior is required from 2C itself (§12 above) beyond not making
future 2D viewport-change-triggered re-rendering structurally awkward to add
on top of the same request-identity model from (11).

**(13) What happens when the source PDF itself is low quality?**
Render it faithfully at the computed target resolution and stop — no
upscaling, no sharpening, no attempt to "fix" a low-resolution embedded scan
(§7). This is a product/AGENTS.md-mandated boundary, not an engineering
limitation to work around.

**(14) Which behavior belongs in 2D instead?**
Cache/prefetch (if evidence later supports it, could land as 2D or a later
2C sub-slice depending on when evidence appears), viewport-change-triggered
re-render on rotation/fold/resize, any two-page/adaptive layout work, and the
final physical-device acceptance gate for the whole Phase 2 scope (per
`PHASE_2_PLAN.md`'s existing 2D scope, unchanged by this plan).

## 15. Rejected alternatives

- **A device-class/tier framework** (e.g. classifying devices into
  LOW/MEDIUM/HIGH quality tiers): rejected per the brief's explicit Step 8
  instruction — not justified by measurement, and `isLowRamDevice()`/
  `getMemoryClass()` already provide the one binary signal actually needed
  (§14.6).
- **A fixed higher constant** (e.g. just raising `MAX_PAGE_PIXELS` from 2048
  to some other fixed number): rejected because §4 shows the problem is that
  render target never adapts to viewport/density/zoom at all — a different
  fixed number has exactly the same structural flaw as the current one, just
  moved.
- **AI/ML upscaling or sharpening**: rejected per AGENTS.md ("no AI services"
  in this phase) and the brief's explicit "no arbitrary sharpening/upscaling
  to disguise low-quality source" principle.
- **Immediate cache/prefetch alongside the resolution-policy change**:
  rejected for 2C.1/2C.2 per (7)/(8) above — not evidence-supported yet, and
  would make the first reviewable slice larger and harder to isolate from the
  render-target change.
- **Concurrent/parallel PdfRenderer access** (e.g. rendering a prefetched
  adjacent page on a second coroutine while the current page renders):
  rejected per §9 — not confirmed safe, and the existing single-mutex
  serialization already works; no measured problem justifies the added risk.

## 16. Implementation slices

**2C.1 — Render-target policy + measurement foundation**
- Goal: replace the fixed `MAX_PAGE_PIXELS` formula with a viewport/density/
  fit-mode-aware initial render target for PDF only, confined to `PdfPages`
  and a small, pure, unit-testable calculation function (e.g.
  `renderTargetLongestEdge(viewportWidthPx, viewportHeightPx, density,
  fitMode, pageAspectRatio, memoryCapPx): Int`), plus the
  `FixedReaderScreen → vm.updateViewport(...)` plumbing needed to report real
  viewport dimensions.
- Production changes: `FixedReader.kt` (`PdfPages` render-target
  calculation; `FixedReader` interface signature only if a target parameter
  is needed), `FixedReaderViewModel.kt` (accept and store viewport info,
  pass it into `render()`), `FixedReaderScreen.kt` (report measured
  viewport). `ArchivePages`/CBZ behavior unchanged.
- Tests: pure unit tests for the render-target calculation (aspect-ratio
  preservation, Fit Page vs. Fit Width behavior, memory-cap clamping,
  low-RAM-device clamping) — no device needed for these. Instrumented: PDF
  opens and renders at the new computed target instead of a hardcoded 2048;
  no CBZ regression (`ArchivePages` test coverage unchanged, explicitly
  re-run).
- Acceptance: compiles, existing reader test suites pass unchanged for CBZ,
  new unit tests pass, manual device comparison (once available) shows no
  regression in Fit Page/Fit Width layout behavior.
- Explicit non-goals: no zoom re-render yet, no cache, no low-RAM-specific
  cap tuning beyond the calculation accepting the signal.
- Rollback boundary: a revert of 2C.1 alone restores the exact current fixed
  2048px behavior; CBZ is untouched throughout, so no CBZ rollback is ever
  needed.

**2C.2 — Zoom-aware high-detail rerender**
- Goal: after a pinch-zoom gesture settles past a quality-bucket threshold,
  request a higher-resolution render of the current page and swap it in when
  ready, per §14(3)/(4)/(11).
- Production changes: `FixedReaderViewModel` gains a request-identity model
  `(page, qualityBucket)` replacing the current `page`-only cancellation key;
  `FixedReaderScreen.kt`'s existing `awaitEachGesture` zoom-gesture loop
  signals "settled" to the ViewModel; `PdfPages.render()` accepts a target
  resolution parameter from 2C.1's calculation, now also callable at a higher
  bucket.
- Tests: unit tests for request-identity/stale-request superseding logic
  (pure, no Android dependency); instrumented tests for zoom → settle →
  higher-detail swap-in, page-navigation-while-rerender-pending (confirms the
  existing cancel-before-publish pattern still holds with the new two-axis
  key), and zoom-out not force-downgrading (brief's Step 9 requirement).
- Acceptance: visual zoom responsiveness is unchanged (instant transform
  scaling still happens immediately); higher-detail bitmap arrives without
  blocking interaction; no stale bitmap ever replaces a newer one; memory
  stays within the 2C.1 cap at every step, including the transient overlap
  case from §8.
- Explicit non-goals: no cache/prefetch, no CBZ change, no rotation/resize
  re-render trigger (2D).
- Rollback boundary: a revert of 2C.2 alone restores 2C.1's single-render-
  per-page-change behavior; the zoom gesture's existing instant-transform
  behavior is unaffected either way.

**2C.3 — Cache/prefetch (ONLY if evidence supports it)**
- Goal: conditional on 2C.1/2C.2's on-device validation actually showing
  prev/next navigation feels slow enough to justify it (§14.7/.8) — if no
  such evidence appears, this slice is skipped entirely and the plan says so
  explicitly rather than building it speculatively.
- If pursued: a bounded 1-adjacent-page prefetch (brief's option B) is the
  smallest defensible step before a full 2–3-page LRU (option C), given
  §8's memory arithmetic compounds with 2C.2's higher-resolution tier.
- Non-goals if skipped: no code changes at all; this plan document itself is
  the record of why.

**2C.4 — Fidelity/performance validation closure**
- Goal: the physical-device comparison §6 could not perform in this
  environment — RP5 and, where feasible, Galaxy Tab A comparison of the
  owner's actual field-reported PDF before/after 2C.1/2C.2, plus the
  acceptance matrix in §17.
- Non-goals: no new production behavior; this slice is validation/evidence
  only, closing the gap §6 left open.

## 17. Test strategy

- Pure/unit-testable: the render-target calculation (aspect ratio, fit mode,
  memory cap, low-RAM clamping), the quality-bucket/rerender-threshold
  decision, and the stale-request-superseding logic (request-identity
  comparison) should all be plain Kotlin functions/classes with no Android
  framework dependency, directly JVM-testable — following the same pattern
  `InputHintsTest`/`ReadingPolicyTest` already use elsewhere in this
  codebase for comparable pure-logic extraction.
- Instrumented (requires `PdfRenderer`, so Android-dependent): PDF open at
  the new computed target, Fit Page, Fit Width, zoom-triggered rerender,
  page navigation while a rerender is pending (stale-result discard), Activity
  recreation (bitmap/page state survives, no re-render storm on
  recreation), and an explicit CBZ-no-regression pass (`ArchivePages`
  behavior and existing CBZ instrumented tests unchanged).
- No screenshot/pixel-comparison tests — not justified here, consistent with
  the brief's own caution and the repository's existing test style (no such
  tests exist elsewhere in the reader suite).

## 18. Performance instrumentation plan

Debug-only, never shipped as production logging (consistent with the
brief's Step 18 and the repository's general avoidance of noisy production
logging): a build-variant-gated (`BuildConfig.DEBUG`) log/metric point inside
`PdfPages.render()` and the ViewModel's rerender-trigger path, recording
render duration, requested/actual bitmap dimensions, and whether a render was
discarded as stale — scoped narrowly enough to remove or gate out of release
builds the same way other debug-only instrumentation in this codebase
already is (e.g. the debug-only search opener noted in `PHASE_2_PLAN.md`'s
2B.3 record). Exact metric plumbing (a simple in-memory ring buffer exposed
through a debug menu, vs. `Log.d`) is an implementation-time decision, not
fixed here.

## 19. Acceptance matrix

| Dimension | Coverage |
| --- | --- |
| Formats | PDF (fidelity change), CBZ (explicit regression check — no behavior change expected) |
| PDF source types | Vector/text-heavy (repo fixture available); image/scanned (no repo fixture — requires the owner's own file, never committed); mixed (same gap) |
| View modes | Fit Page, Fit Width, zoom > 1x (settled high-detail swap-in) |
| Devices/viewports | API 35 emulator (compact + forced-expanded viewport), RP5 physical (primary debug target per `PHASE_2_PLAN.md` §7), Galaxy Tab A physical (periodic field verification — this is literally the device the original report came from) |
| Lifecycle | page navigation, fast repeated page navigation (cancellation correctness), Activity recreation, source close/reopen |
| Memory | bounded max render dimensions enforced by the memory cap (§14.5), no unbounded bitmap accumulation across navigation, no obvious OOM path under ordinary prev/next/zoom use |

## 20. Explicit non-goals (restated for this plan's own scope discipline)

Phase 2C must not become: Adapted PDF, OCR, PDF text reflow/font replacement,
annotations, AI enhancement, super-resolution/sharpening, a new PDF engine,
a comics/CBZ redesign, Phase 2D's adaptive/two-page layout work, cloud/sync,
theme work, TTS, or a general device-performance framework. None of these
were found necessary during this investigation; if a future slice's
implementation discovers otherwise, that discovery must stop and be reported
rather than silently expanding scope, per the brief's Step 20.

## 21. ADR decision

**Not created in this pass.** This remains an exploratory planning document;
no implementation direction has been accepted or built yet. **An ADR is
required after the architecture direction in §14 is accepted and at least
2C.1 is implemented and validated** — at that point the decision to replace
a fixed-constant render budget with a viewport/density-aware, zoom-tiered,
memory-capped policy (and to keep PDF/CBZ policy separated per §14.9) is
significant enough to warrant one, following the same pattern as
ADR-0018/ADR-0003. Recorded here rather than written speculatively now.

## 22. Validation performed for this planning pass

- `git diff --check`: see final report (run immediately before commit).
- No temporary experimental/profiling code was added or needs removal — none
  was written, per §6's honest explanation of why Step 5's experiment could
  not be executed in this environment.
- The final committed diff is docs-only: this new file plus the minimal
  status reconciliation to `docs/PHASE_2_PLAN.md` described in its own
  change. No file under `app/src/...` was touched. No dependency, Room
  schema, or `app/schemas/` file was changed.

## 23. Deferred 2D work (restated from §16.14 for clarity)

Cache/prefetch (if later evidence supports it and isn't absorbed into a later
2C slice instead), rotation/fold/resize-triggered re-rendering, adaptive
two-page/foldable layout, and the final Phase-2-wide physical-device
acceptance gate remain 2D's responsibility per `PHASE_2_PLAN.md`'s existing
2D scope, unchanged by this document.
