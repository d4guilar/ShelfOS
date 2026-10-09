# Phase 3 Implementation Plan — Comics and Manga

Status: **3A (rendering/fidelity foundation) COMPLETE and MERGED to `main`** (`#24`,
"feat: establish Phase 3A fixed-page rendering foundation"). **3B (page thumbnails/
navigation) COMPLETE and MERGED to `main`** (`#25`, "feat: add Phase 3B page thumbnail
navigation"). **3C (spreads + Manga pairing) COMPLETE and MERGED to `main`** (`#26`,
"feat: add Phase 3C comic and manga spread reading"). **3D (adaptive/foldable comic
spreads) COMPLETE and MERGED to `main`** (`#27`, "feat: add Phase 3D adaptive foldable
comic spreads"). **3E-A (native CBR dependency foundation — NDK/CMake plumbing,
vendored libarchive, JNI smoke test; ZERO archive-reading logic) is COMPLETE and
ACCEPTED on `phase-3/3e-native-cbr`.** **3E-B (generic internal native RAR4/RAR5 engine —
real open/enumerate/extract session on top of 3E-A, no product/reader integration) is
COMPLETE and ACCEPTED on the same branch.** **3E-C (CBR container adapter —
`RarPageSource` + a bounded on-disk extraction cache on top of 3E-B, no product/reader
  integration) is COMPLETE and ACCEPTED on the same branch (final Codex R2 PASS).** **3E-D (CBR product
integration — `PublicationFormat.CBR` wired end to end through import, magic detection, metadata,
category/Manga-RTL, `FixedReaderFactory` routing, spread/fold inheritance, progress/resume, and error
mapping, on top of 3E-C, with no change to the accepted native/cache architecture) is IMPLEMENTED on
the same branch; pending review.** 3E-E is **NOT STARTED**. 3F
remains PLANNING ONLY. Phase 3 overall is **NOT complete**. See §22/§23 for what 3A
landed, §24 for what 3B actually landed, §25 for what 3C actually landed (including the
R1 and R2 remediation records), §26/§26a for what 3D and its R1 remediation landed,
§26b for the R2 remediation (render-geometry state-independence + hysteresis,
hinge-safe modal focus/accessibility) recorded below, §27 for what 3E-B actually landed,
  §28-§30 for what 3E-C and its remediation actually landed, §31 for what 3E-D actually landed, and
`docs/adr/0024-native-cbr-libarchive.md` plus `docs/VALIDATION.md` for what 3E-A/3E-B/3E-C/3E-D actually landed.

## 1. Status / base

- Base: `main` @ `178c27f` ("docs: define metadata and cover enrichment defaults (#22)")
  for this plan's original discovery pass; 3A's implementation itself branched from
  `main` @ `c8fff36` ("docs: define Phase 3 comics and manga plan (#23)"), one commit
  later (docs-only in between), per the owner's implementation-authorization instruction.
- Phase 0, Phase 1, and Phase 2 (including 2A–2D closure) are **COMPLETE** per
  `docs/VALIDATION.md` and `docs/PHASE_2D_IMPLEMENTATION_PLAN.md`. This plan does not
  change that status.
- This document is the canonical Phase 3 planning/acceptance reference, written to the
  same discipline level as the Phase 2 planning docs. It supersedes ad hoc CBR framing
  scattered across `docs/ROADMAP.md`, `docs/PRODUCT.md`, `docs/features/COMICS_MANGA.md`,
  and `docs/features/READER.md` (see §5).
- §12/§13/§21 below authorized exactly one slice at a time. 3A (§22/§23), 3B (§24), 3C
  (§25, including its Codex R1/R2 remediation records), and 3D (§26/§26a/§26b) are
  complete and merged to `main` (`#24`, `#25`, `#26`, `#27`). 3E-A (native CBR
  dependency foundation) is complete and accepted on `phase-3/3e-native-cbr`. 3E-B
  (generic native RAR engine) is complete and accepted on the same branch. 3E-C (CBR
  container adapter + bounded cache) is complete and accepted on that branch (final Codex R2
  PASS). 3E-D (CBR product integration) is implemented on that branch; pending review.
  3E-E has not started. 3F remains planning only, and Phase 3 overall is not
  complete.

## 22. 3A implementation record (landed)

**Scope delivered**: exactly §21's narrowed first slice — (a) the render-request
contract and (b) the container/page-source abstraction — with no thumbnail UI, no spread
UI, and no CBR/RAR code of any kind added or stubbed.

**Files changed**: `app/src/main/java/com/d4guilar/shelfos/core/reader/FixedReader.kt`
(rewritten), `feature/reader/FixedReaderViewModel.kt` and `feature/reader/
FixedReaderScreen.kt` (minimal viewport plumbing), plus new tests
(`core/reader/PageRenderRequestTest.kt`, `androidTest/FixedReaderRenderRequestTest.kt`).
No other production file changed. No dependency, Room schema, or migration was touched.

**Render-request contract**: `FixedReader.render(index, request: PageRenderRequest =
PageRenderRequest.DEFAULT)` replaces the old `render(index): Bitmap`. `PageRenderRequest`
carries `viewportWidth`/`viewportHeight` (the actual display viewport, Compose/UI-free)
and `maxDimension` (a per-request ceiling a future caller — e.g. a thumbnail strip — may
tighten). `resolveRenderTargetLongestEdge(request)` is the pure function that turns a
request into a longest-edge decode/rasterize target: it uses the real viewport when
known, falls back to the old flat `2048` (`PageRenderRequest.DEFAULT_MAX_DIMENSION`) when
the viewport isn't known yet (pre-layout), and is always bounded by both the request's own
`maxDimension` and the absolute `PageRenderRequest.SAFE_MAX_DIMENSION = 4096`, which no
request can exceed regardless of what it asks for. `PageRenderRequest.DEFAULT` reproduces
the old behavior exactly (target `2048`), so every existing one-argument `render(index)`
call site (`LibraryPersistenceTest`, `MalformedFixedReaderResilienceTest`) is unchanged.

**Why this is viewport-aware rather than "raise the ceiling"**: Phase 2C's live RP5 A/B
session (`docs/VALIDATION.md`, "Phase 2C live visual fidelity validation") found that
raising the flat render ceiling to 3072/4096 looked **visibly worse** at ~2x zoom on a
real source-limited PDF, with no visible benefit elsewhere, and explicitly recommended a
"viewport/fit-mode-aware render-target direction... reframed around Fit Width's measured
width requirement rather than 'raise the ceiling'". 3A implements exactly that: the
default/pre-layout path is byte-for-byte unchanged at `2048`; resolution only exceeds
`2048` when a real, larger viewport genuinely needs it (e.g. a wide landscape/tablet
viewport), never as a blanket increase. No zoom-triggered re-render was wired (see below)
— the Phase 2C finding is a specific argument against that, not just an unexplored idea.

**Container/page-source boundary**: a private `PageSource` interface
(`openPage(index): InputStream`, indexed by logical page position) separates "get this
page's bytes" from "decode/sample this page at a target resolution"
(`ImagePageRenderer`, shared decode-bounds-then-sample policy). `ArchivePageSource` is the
only implementation today, backed by `SeekableZip`'s true random access. `PageSource`
itself makes no random-access promise — a future CBR adapter over a "solid" RAR archive
(see §6) could implement `openPage` via a one-time sequential index or a bounded
extract-to-cache instead, and would reuse `ImagePageRenderer` unchanged, rather than
requiring `FixedReader.kt` to be reshaped when 3E eventually lands. `PdfPages` is
unaffected by this boundary (PDF has no page-source/container split to make — it reads
directly via `PdfRenderer`) but shares the same `PageRenderRequest`/
`resolveRenderTargetLongestEdge` contract.

**What this slice deliberately does NOT do** (next-slice or explicitly out of scope,
consistent with §19): no thumbnail UI or thumbnail decode path is wired to any screen; no
spread/two-page rendering; no `PublicationFormat.CBR`, no RAR/libarchive/NDK/JNI, no
`.cbr` magic-byte detection; no live pinch-zoom-triggered re-render (`FixedReaderScreen`'s
pinch/zoom gesture still only scales the already-decoded bitmap via `graphicsLayer`,
exactly as before) — the request contract is now *capable* of a higher-resolution
re-render request, proven by unit/instrumented tests, but nothing in the UI constructs
one in response to a zoom gesture, both because that UI work is out of this slice's scope
and because of the Phase 2C finding above. `FixedReaderViewModel.updateViewport(width,
height)` records the reader page surface's last measured size but never itself triggers a
render — only the next naturally-occurring render (open, page turn, retry) picks up the
new viewport — so a continuous resize/rotation/fold never causes a render storm.

**Evidence**: `PageRenderRequestTest` (pure JVM) proves the resolution policy
(unknown-viewport fallback, small/large/thumbnail-sized/oversized requests, no-upscale
determinism, overflow/malformed-input safety) without needing Android's `BitmapFactory`/
`PdfRenderer` (unavailable in this project's local unit tests). `FixedReaderRenderRequestTest`
(instrumented) proves the same policy against real decodes: a 6000x4000 synthetic CBZ page
decoded at the default request stays ≤2048 (unchanged), at a 3000x3000 viewport request
exceeds 2048 while staying ≤3000, at a 200x200/maxDimension=200 request stays ≤200, and at
a 20,000x20,000 "oversized" request is bounded at the 4096 safety ceiling rather than the
requested viewport; a 100x150 CBZ source is never upscaled by a 3000x3000 request; a
malformed CBZ page still fails gracefully through the new request-shaped call. The
equivalent PDF cases are covered the same way, including natural page ordering across a
3-page PDF at a non-default request. Full JVM unit suite, lint, `assembleDebug` and
`assembleDebugAndroidTest` all pass. A broader instrumented regression pass
(`FixedReaderTransformBoundsTest`, `FixedReaderRecreationTest`,
`MalformedFixedReaderResilienceTest`, `LibraryPersistenceTest`, `NavigationSmokeTest`,
synthetic large-fixture tests) was attempted but produced inconclusive results from
environment instability (an API 37 AVD image's Espresso/`InputManager` incompatibility,
then repeated external Gradle-daemon interruptions on a fallback API 24 AVD) rather than
from any test actually failing against the new contract — see `docs/VALIDATION.md`'s 3A
entry for the exact evidence and the honest limitation. None of those classes exercises
code this slice did not already cover more directly through `FixedReaderRenderRequestTest`
and reasoned-through review of the (intentionally minimal, additive) `FixedReaderViewModel`/
`FixedReaderScreen` changes; closing that broader regression gap with a clean environment
is deferred to 3F's full acceptance matrix rather than re-attempted here.

## 23. Codex R1 remediation (post-3A)

Independent review (Codex) of `c48dd33` (§22's commit) returned **CHANGES REQUIRED**: the
`PageSource`/container direction was judged fundamentally sound (not restarted), but six
findings required a remediation pass before acceptance. This section is a factual record of
each finding and exactly what changed in response, all on one remediation commit on top of
`c48dd33` (not an amend). No new feature, format, or UI surface was added; scope stayed
strictly inside §22's original boundaries (no thumbnails, no spreads, no CBR, no live
zoom-triggered re-render).

**Finding 1 (HIGH) — fidelity floor regression.** A small/narrow known viewport (e.g.
720x1280) could resolve to a target *below* Phase 2's flat `2048` fidelity for ordinary
reading (a real regression, not an improvement, since live zoom re-render does not exist to
compensate). **Remediation**: `PageRenderRequest.DEFAULT_MAX_DIMENSION` (`2048`) is now
explicitly documented and used as a **reading-quality floor** — `resolveRenderTarget`
(replacing `resolveRenderTargetLongestEdge`) takes `max(viewportScale, readingFloorScale)`,
so a normal reading render's longest edge never falls below `2048` merely because the
viewport/fit combination implied a smaller scale, while a larger viewport can still exceed it
(3A's original point, preserved). The per-request `maxDimension` ceiling is applied *after*
the floor, so a future, deliberately smaller request (e.g. a thumbnail) still opts out of the
floor simply by tightening `maxDimension` — no speculative "render purpose" enum was added;
the floor/ceiling distinction alone was enough.

**Finding 2 (HIGH) — Fit Width is aspect-blind.** The original `resolveRenderTargetLongestEdge`
took only the request (viewport + ceiling), never the page's own dimensions, so it could not
tell `FitMode.WIDTH`'s actual rendered-content box (viewport width, page-aspect-derived
height, which may greatly exceed viewport height) apart from a plain viewport-longest-edge
target — a tall page in a landscape viewport resolved to a tiny, severely-on-screen-upscaled
bitmap. **Remediation**: `resolveRenderTarget(sourceWidth, sourceHeight, request)` now takes
the page's own bounds (CBZ's existing bounds-only decode pass; PDF's `page.width`/
`page.height`) alongside `PageRenderRequest.fit: FitMode`, and computes `FitMode.PAGE`/
`FitMode.WIDTH` exactly as `fixedReaderFittedContentSize` does for display, before any ceiling
applies. `FixedReaderViewModel.render` now passes the title's actual fit preference
(`_state.value.preferences.fit`) into the request. No Compose type leaks into `core`; no tiled
rendering or live zoom re-render was added.

**Finding 3 (HIGH) — 4096 bounds one bitmap, not peak memory.** A single `SAFE_MAX_DIMENSION`
(4096) longest-edge bound does not bound peak memory: at `ARGB_8888`, a `4096x4096` bitmap is
~64MiB, and more than one same-session bitmap can be live at once during a page transition.
**Remediation**: two changes.
1. **Explicit byte-budget policy** — `RenderMemoryPolicy` (new, in `FixedReader.kt`) derives a
   conservative `SESSION_BUDGET_BYTES = 96MiB` total, divided by `MAX_CONCURRENT_BITMAPS = 3`
   (the displayed bitmap, a newly-rendering bitmap, and — only for a brief window — a
   stale/cancelled result; see point 2) into `MAX_BITMAP_BYTES ≈ 32MiB` per render.
   `resolveRenderTarget` applies this as an aspect-preserving pixel-budget reduction *after*
   the floor/viewport/ceiling steps, with overflow-safe `Double` arithmetic throughout and a
   final defensive per-axis `coerceIn(1, SAFE_MAX_DIMENSION)`. `SAFE_MAX_DIMENSION` (4096)
   remains as a secondary, independent per-axis ceiling (modest-hardware raster/texture
   compatibility), not the primary memory-safety argument anymore. A square request that
   previously would have reached the full 4096x4096 (~64MiB) now resolves to roughly a
   2896x2896 square (~32MiB) instead — "no square 64MiB bitmap merely because its edge is
   ≤4096," as required.
2. **Tightened render/cancellation lifecycle** — `FixedReaderViewModel.render` previously
   released its `Mutex` as soon as `session.render()` returned, *before* checking
   cancellation and recycling a stale result; a newer render (already launched by a fast
   page-turn that had already called `rendering?.cancel()` on the older job) could start its
   own decode while that older job's just-decoded bitmap was still alive, unpublished and
   unrecycled — three same-session bitmaps live at once (displayed + stale + newly-decoding).
   The decode, the `ensureActive()` cancellation check, and the publish-or-recycle outcome now
   all happen *inside* the same `mutex.withLock` critical section, so a newer render can never
   begin decoding until the previous one has resolved its bitmap's fate. See the updated doc
   comment on `FixedReaderViewModel.render` for the full reasoning. A currently-displayed
   bitmap already shown by Compose is still never force-recycled (unchanged, to avoid a
   visual-corruption/crash risk) — that bitmap's disposal stays GC-governed, bounded to at
   most one prior reference, not something this lifecycle change claims to control directly.

**Finding 4 (MEDIUM) — insufficient memory evidence.** **Remediation**: a new instrumented
test, `FixedReaderViewModelLifecycleTest.rapidPageTurnsDoNotAccumulateStaleBitmapsOrGrowMemoryUnboundedly`,
drives a real `FixedReaderViewModel` (not just `FixedReaderFactory`) through a representative
high-resolution (6000x4000) 6-page CBZ: displayed bitmap (page 0) → 5 rapid un-awaited
page-turns (exercising cancellation mid-decode) → settle → 6 more rapid back-and-forth
turns → settle, recording each observed bitmap's dimensions/estimated bytes and process PSS
(`android.os.Debug.getPss()`) before and after the sequence. See `docs/VALIDATION.md` for the
captured evidence and pass result. A second new test in the same file,
`viewportReportedByScreenReachesTheActualRenderRequest`, is the Finding-4-adjacent "viewport
plumbing end-to-end" proof: it calls `updateViewport` + `retry()` exactly as
`FixedReaderScreen`'s `onSizeChanged` and the ViewModel's own render path do, and asserts the
*next real decode's* dimensions change accordingly — proving the wiring, not just the pure
`resolveRenderTarget` math.

**Finding 5 (LOW) — misleading naming.** `ArchivePageSource` was actually ZIP-specific
(backed by `SeekableZip`), not generically archive-format-agnostic. **Remediation**: renamed
to `ZipPageSource`. The generic `PageSource` interface itself is unchanged.

**Finding 6 (LOW) — stale doc wording.** §3 ("Current implementation inventory") was written
before 3A landed and no longer described the fixed-reader render path accurately.
**Remediation**: §3 now opens with an explicit "PRE-3A BASELINE" notice pointing to this
section and §22 for the current render-path contract, rather than being silently left to look
current.

**What stayed exactly as judged sound**: the `PageSource` boundary (stream-based, no
ZIP-entry types leaking through the generic interface, no whole-publication-extraction
assumption, no promise of cheap random access — a future CBR adapter over a "solid" RAR
archive can still implement `openPage` via sequential decode or a bounded app-private cache
without reshaping `FixedReader.kt` or `ImagePageRenderer`). No CBR, libarchive, NDK/JNI,
thumbnails UI, thumbnail cache, spreads, or live zoom re-render were implemented or stubbed in
this remediation either.

**Targeted validation**: see `docs/VALIDATION.md`'s "PHASE 3A — Codex R1 remediation" entry
for the exact tests run and their results. Per the owner's explicit scope instruction, the
full JVM suite and the full connected/instrumented regression matrix were **not** re-run here
(the original candidate already has a full-suite PASS on record; both are reserved for
Phase 3F's acceptance matrix) — only the tests this remediation's changes actually required.

## 24. 3B implementation record (landed)

**Scope delivered**: a session-scoped, lazily-decoded page-thumbnail navigator for the fixed
reader (PDF/CBZ), built entirely on 3A's `PageRenderRequest`/`resolveRenderTarget`/
`RenderMemoryPolicy`/`PageSource` foundation — no second archive-reading path, no Room
schema change, no new dependency, no spreads, no CBR, no foldable-specific layout.

**Thumbnail render path**: `PageRenderRequest.THUMBNAIL_MAX_DIMENSION = 320` and
`PageRenderRequest.thumbnail()` (both in `core/reader/FixedReader.kt`) are the only new
production surface in the render-request contract itself. `thumbnail()` is a request with no
viewport and `maxDimension = 320`; it resolves through the exact same `resolveRenderTarget`
every full-page read already uses, opting out of the Codex R1 reading-quality floor purely by
tightening `maxDimension` below it — the mechanism that remediation was explicitly built to
support for a future caller like this one (see §23 finding 1). CBZ and PDF both get this for
free through `FixedReader.render(index, PageRenderRequest.thumbnail())`; no format-specific
thumbnail branch exists anywhere.

**Thumbnail loader/cache** (`core/reader/ThumbnailLoader.kt`, new file, Compose-free):
- `ByteBudgetedLruCache<K, V>` — a generic, byte- and entry-count-bounded LRU,
  backed by an access-order `LinkedHashMap`. Generic so it is independently JVM-testable with
  a trivial fake value (this project's local unit tests cannot construct a real
  `android.graphics.Bitmap`, same limitation `PageRenderRequestTest` already documents).
- `nextThumbnailToLoad(lo, hi, center, cached, inFlight)` — the pure, stateless scheduling
  policy: nearest-to-center first, skipping already-cached or already-in-flight pages.
- `ThumbnailResult<T>` — `Loaded<T>` or `Failed` (object, no payload — a decode failure is
  never retained as a large object).
- `ThumbnailLoader<T : Any>` — one single-worker coroutine (`collectLatest` over a
  `setVisibleRange(center)`-driven `MutableStateFlow`) that decodes only a
  `[center-prefetch, center+prefetch]` window (`DEFAULT_PREFETCH = 6`, so a 13-page working
  set), nearest-first, strictly one decode at a time. Generic over the decoded type purely for
  testability — the only real caller (`FixedReaderViewModel`) instantiates it with
  `T = android.graphics.Bitmap`.
- `ThumbnailLoader.DEFAULT_BUDGET_BYTES = 16 MiB`, **and** `ByteBudgetedLruCache.DEFAULT_MAX_ENTRIES = 64`
  as a second, independent bound (corrected — a prior revision of this paragraph implied
  byte-only bounding, which no longer matches `ByteBudgetedLruCache`). Eviction runs whenever
  *either* bound is exceeded, not only the byte budget, so a run of failed (`ThumbnailResult.Failed`,
  zero-payload) or otherwise tiny-byte entries is still bounded by the 64-entry cap even though
  such entries barely move `usedBytes`. Byte-budget reasoning, mirroring `RenderMemoryPolicy`'s
  own budget philosophy but proportionally much smaller: a worst-case *square* decode at the
  320px ceiling is `320*320*4 ≈ 410KB` ARGB_8888; `16 MiB / 410KB ≈ 40` worst-case-square
  thumbnails resident at once — comfortably more than the 13-page prefetch window even on a
  wide foldable/tablet strip, while staying a small fraction of `RenderMemoryPolicy`'s 96 MiB
  full-reading session budget. The 64-entry cap sits above that ~40-entry worst case, so it never
  fights the byte budget under normal decodes — it exists specifically to bound the failed/tiny-entry
  case the byte budget alone cannot.

**Concurrency/safety decision**: neither `android.graphics.pdf.PdfRenderer` nor the CBZ
`PageSource`/`SeekableZip` path documents safety for concurrent access from multiple threads,
so `FixedReaderViewModel` wires the thumbnail loader's `decode` lambda through the *exact same*
`mutex`/`session` it already uses for full-page reads, rather than building a second concurrent
reader. A thumbnail decode at 320px is small/fast relative to a full-page decode, so this
briefly serializes with (never indefinitely blocks) page-turn rendering. `ThumbnailLoader`
itself never fans out to multiple concurrent decodes regardless of caller.

**Cancellation**: each `setVisibleRange` call supersedes the previous one via `collectLatest`,
so a fast scroll cancels the "keep decoding this range" loop at its next suspension point
rather than queuing superseded ranges. The one piece that cannot be interrupted mid-call is the
synchronous decode itself (`BitmapFactory`/`PdfRenderer` are not cooperatively cancellable once
started) — at most one already-started decode finishes before a newer range takes over, and its
result is still stored at its own true page key, never "the wrong slot," so a late-arriving
result from an abandoned range is at worst a harmless extra cache entry.

**Dismissal/prefetch (`ThumbnailLoader.deactivate()`)**: dismissing Pages does not close the
loader's whole session — `ThumbnailNavigator`'s `DisposableEffect(loader) { onDispose { loader?.deactivate() } }`
calls `deactivate()`, which resets the loader's requested center back to its "nothing requested"
sentinel. This clears the *active requested range* only: already-cached thumbnails remain in
`ByteBudgetedLruCache` untouched (they are not evicted just because Pages closed), and — same
one-decode-cannot-be-interrupted-mid-call constraint as ordinary cancellation above — at most one
already-in-flight synchronous decode may still finish and land in the cache as a harmless extra
entry. Because the loop that keeps requesting pages for the old window is itself cancelled at
`deactivate()`, no stale prefetch window continues decoding after Pages closes; a later reopen's
fresh `setVisibleRange` call establishes a genuinely new window normally, not a resumed one.

**UI** (`feature/reader/ThumbnailNavigator.kt`, new file): a "Pages" button
(`action_thumbnails`, next to Appearance in the existing control row,
`testTag="reader_thumbnails"`) opens a dialog containing a `LazyRow` thumbnail strip
(`testTag="thumbnail_strip"`) visually matching the same `AlertDialog` look every other reader
overlay already uses (Appearance/Chapters/Search/Bookmarks) — title, content, trailing Close
button — with zero new key-handling code in `FixedReaderScreen`. **Gamepad B is corrected here
post-Codex-R2** (a prior revision of this paragraph claimed it "dismisses exactly the way"
Escape/system Back already do through ordinary dialog behavior — that was never accurate for
gamepad B specifically): system Back and Escape dismiss Pages through ordinary
`DialogProperties`-driven dialog behavior (`dismissOnBackPress`), same as every other reader
dialog. Gamepad B is **not** handled by that default behavior at all — Android's default dialog
handling does not translate `KEYCODE_BUTTON_B` into dismissal — so ShelfOS's own semantic
`ShelfCommand.BACK` handling (`InputMapper` already maps both Escape and gamepad B to
`ShelfCommand.BACK`) explicitly covers it, attached at **dialog-wide scope**: one `onKeyEvent` on
a single `Surface` that is an ancestor of the thumbnail content, the title, and the Close button
together (built on `BasicAlertDialog` precisely so such a shared ancestor exists — the public
`AlertDialog` composable's separate `text`/`confirmButton` slots do not share one), not merely a
sub-slot. This means gamepad B dismisses Pages regardless of which dialog child currently owns
focus, Close button included — redundant with (never conflicting with) Escape's already-correct
system handling. Opening the
strip scrolls to roughly center the current page (`scrollToItem(max(0, currentPage-2))`, an
approximate centering, not pixel-exact). The strip is driven by the *actual* visible items
(`snapshotFlow` over `LazyListState.layoutInfo.visibleItemsInfo`), so lazy decoding follows
real scroll position, not a guess. Selecting a thumbnail calls `FixedReaderViewModel.showPage`
— the exact same jump path an existing page slider drag already uses — then dismisses the
dialog; there is no second page-numbering model, no new locator format, no separate thumbnail
progress concept. RTL/Manga: only the strip's visual layout direction mirrors, via
`CompositionLocalProvider(LocalLayoutDirection ...)` — the identical technique the bottom
control row already uses for Previous/Next placement — while the underlying
`items(pageCount) { page -> ... }` loop always iterates true logical indices in natural order
regardless of direction, so the page-identity invariant holds by construction, not by a runtime
check. The current thumbnail is marked by both a checkmark glyph and bold weight (not color
alone), the same two-signal idiom `EpubActivity`'s Chapters dialog already uses for its current
chapter. Each cell exposes a `contentDescription` ("Page N" / "Page N, current page" /
localized), is keyboard/D-pad focusable and activatable via the plain `clickable` modifier
(the same default Compose keyboard-activation behavior every other control in this screen
already relies on — no new input architecture, no new `ShelfCommand`). A corrupt page renders
a localized "Unavailable" placeholder in its cell only; the strip and every other cell keep
working.

**Process death/rotation/recreation** (corrected post-Codex-R1; the previous wording here was
inaccurate): `thumbnails` open/closed state is a plain `rememberSaveable Boolean`, the same
mechanism `controls` and `appearance` already use in `FixedReaderScreen` — and `rememberSaveable`
*does* survive supported recreation/saved-state restoration by design, so if Pages was open when
recreation happened, it genuinely can reopen automatically afterward (this is accepted, working
behavior, not a defect; it is not being changed to force-close on recreation). What never
survives is the thumbnail *bitmap cache* itself: `ThumbnailLoader` is session-scoped and ephemeral
(never written to disk or Room — see its class doc), so it does not exist yet immediately after
recreation. The restored current logical page (`state.page`, from the normal locator-restore
path) remains authoritative regardless of whether Pages reopens; if it does reopen, its
thumbnails are simply re-decoded lazily on demand against that restored page and viewport, exactly
like opening Pages fresh for the first time — no thumbnail bitmap, scroll position, or decode
state is itself part of saved/restored UI state. No Room schema, no migration, no new persisted
field.

**What this slice deliberately does NOT do** (next-slice or explicitly out of scope, consistent
with §12/§13): no spreads, no foldable-specific thumbnail layout or pairing, no on-disk
thumbnail cache, no CBR, no new third-party dependency. The thumbnail UI/loader contract never
exposes `SeekableZip.Entry`, ZIP paths, or ZIP CRCs — cache identity is plain
session-local/logical-page-index, so a future CBR `PageSource` can feed this same thumbnail
system unchanged, per §12's refinement.

**Evidence**: see `docs/VALIDATION.md`'s "PHASE 3B" entry for the exact tests run and their
results (pure JVM coverage of `ByteBudgetedLruCache`/`nextThumbnailToLoad`/`ThumbnailLoader`
under `kotlinx-coroutines-test` virtual time; instrumented `FixedReaderViewModel`-level
evidence against real CBZ decodes including a 300+ page fixture; a Compose UI pass for
jump-to-page, the Manga/RTL page-identity invariant, accessibility content descriptions, and
corrupt-thumbnail resilience). Per standing policy, the full JVM suite and the full connected/
instrumented regression matrix were **not** re-run here — reserved for Phase 3F.

## 25. 3C implementation record (landed)

**Scope delivered**: AUTO/SINGLE/SPREAD comic/manga spread presentation (§9/§13's 3C contract)
for CBZ and PDF titles categorized `MediaCategory.COMIC`/`MANGA`, built entirely on 3A's
render-request contract and 3B's render/mutex discipline — no new reader architecture, no
bitmap stitching, no Room schema change, no new dependency, no foldable/CBR code.

**SpreadMode and canonical pairing** (new `core/reader/SpreadModel.kt`, pure/Compose-free):
`enum class SpreadMode { AUTO, SINGLE, SPREAD }`. `canonicalPageGroups(pageCount)` is the
zero-based rule — page 0 (cover) always solo; interior pages pair `[1,2]`, `[3,4]`...; an
unmatched final page solo — independent of mode/geometry/window. `resolvePageGroups`/
`resolveGroups` apply a landscape split on top: a canonical pair containing a page whose
`PageGeometry.isLandscape` (width/height ratio above the named `LANDSCAPE_ASPECT_THRESHOLD =
1.05`, chosen conservatively above square so near-square scan-cropped pages are never
misclassified) is true splits into two solo groups, never skipping, duplicating, or reordering
either page, and never affecting any other canonical pair. `PageGroup.physicalOrder(rtl)` is
the only place LTR/RTL placement is decided — it reorders which side a pair's members are drawn
on, never the underlying `pages` list or any stored value.

**Persistence**: `ReaderPreferences.spreadMode: SpreadMode?`, a purely additive JSON field
(`ReaderPreferences.json()`/`.parse()`) — old JSON without the key parses with `spreadMode =
null`, which `ReaderPreferences.DEFAULT.spreadMode = SpreadMode.AUTO` then resolves to AUTO; no
Room schema/migration exists or is needed. `resolveReaderPreferences`/`appearanceUpdate` treat
`spreadMode` exactly like the pre-existing `direction` override: title-specific, never
globalized by "Use as default for all titles" even when other fields in the same edit
legitimately globalize, and `appearanceReset`'s title-scope reset clears it back to AUTO while a
global-scope reset leaves an existing title override untouched. The Appearance control itself
(`ReaderAppearance.kt`) is gated by `capabilities(format, category).spread`
(`spreadCapable(format, category)` in `ReaderPreferences.kt`) — PDF/CBZ **and**
`COMIC`/`MANGA` only, so an ordinary Book/Document PDF never shows the control.

**AUTO window policy**: `resolveSpreadActive(mode, viewportWidthDp)` — SINGLE/SPREAD are
unconditional overrides; AUTO compares the reader page surface's measured width, converted to
dp by `FixedReaderScreen` (the Compose-measured `onSizeChanged` width via `LocalDensity`, kept
separate from the existing px `viewportWidth`/`Height` that still drive decode resolution),
against the named `AUTO_SPREAD_MIN_WIDTH_DP = 600` threshold. No window-size-class primitive
existed anywhere in ShelfOS prior to this (verified by inspection), so this is a new, narrowly-
scoped, independently-tested constant rather than a new dependency — and deliberately *not*
foldable/posture-aware (ordinary window width only; `FoldingFeature`/hinge intelligence is
explicit 3D scope per §11/§12's refinement).

**Logical page invariant**: `FixedReaderState.page` remains the single authoritative logical
position throughout — `FixedReaderViewModel.showPage`/`turn` never replace it with a group's
lower index, `canonicalGroups`/`resolveCurrentGroup`/`nextPage`/`previousPage` only ever *derive*
presentation from it, and nothing new is persisted beyond the existing `pageLocator`/progress
(no spread index, no left/right-half flag). Proven by `SpreadModelTest`'s
`selectingTheSecondPairMemberDoesNotNormalizeToTheLowerIndex` (pure) and
`FixedReaderSpreadViewModelTest#jumpingDirectlyToTheSecondPairMemberKeepsItCurrentRatherThanNormalizingToTheFirst`
(real `FixedReaderViewModel`/CBZ).

**Navigation**: no new `ShelfCommand` — `FixedReaderViewModel.turn(±1)` now routes through
`nextPage`/`previousPage` (a bounded-cost specialization of the pure whole-list
`nextLogicalPage`/`previousLogicalPage`: it only ever needs the single canonical pair containing
the current page to decide where "next"/"previous" lands, never the whole book's geometry, which
is what keeps a large publication's semantic navigation cheap). In SINGLE (or AUTO-resolved-
single), this reduces to ordinary `page ± 1` by construction. `showPage(index)` (the thumbnail
jump path, unchanged) still sets `state.page` directly; spread presentation is derived afterward,
never the other way around.

**Page metadata**: `FixedReader.pageGeometry(index): PageGeometry?` — `PdfPages` reads
`PdfRenderer.Page.width`/`height` (no rasterization); `ArchivePages` reuses
`ImagePageRenderer`'s existing bounds-only `inJustDecodeBounds` pass via a new `bounds()`
helper (no new decode path, no full bitmap allocation). `FixedReaderViewModel.geometryCache`
memoizes per-page results for the session (a failed/unknown lookup caches as `PageGeometry(0,
0)`, inert for `isLandscape`, so a corrupt page's geometry is never re-attempted every
navigation). The contract never exposes `SeekableZip.Entry`/ZIP-specific types; a future CBR
`PageSource` satisfies it the same way CBZ does today.

**Render/state model**: `FixedReaderState.slots: List<PageSlot>` (`PageSlot(page, bitmap?,
error?)`) replaces the single `bitmap` field (a `bitmap` compat getter remains for existing
single-page call sites/tests). `FixedReaderViewModel.render(page)` resolves the active group
(1 or 2 pages) and decodes every slot **sequentially inside the same single render mutex** 3A/3B
already use — never in parallel — with the full cancellation/publish-or-recycle discipline
Codex R1 established now covering the *whole* group's fate before the lock releases, so a newer
render still can never begin decoding until every slot of the previous one has resolved. A
decode failure for one slot never blanks its sibling: `PageSlot.error` carries that page's own
error, the top-level `FixedReaderState.error` (Retry/Back-to-library) only activates when every
slot in the group failed.

**Rendering (Compose)**: `FixedReaderScreen.kt` renders 1 slot exactly as before (unchanged code
path/testTags) or, for 2 slots, a `Row` of two independent `Image`s (never a stitched bitmap)
with a small gutter (`t.spacing.small`, an existing design token — no new gutter preference),
physically reordered for RTL via `PageGroup.physicalOrder`. Fit Page/Fit Width/zoom/pan reuse
the *existing* `fixedReaderFittedContentSize`/`fixedReaderMaxPan`/`fixedReaderMaxPanY`/
`fixedReaderVerticalScaleOverflow` functions unchanged, fed a synthesized "combined content
size" (`combinedContentDimensions`: each bitmap notionally scaled to a shared reference height
and summed with the gutter) standing in for a single bitmap's width/height — no new geometry
primitive, one shared `graphicsLayer`/transform state for the whole visible unit (never
independent per-slot zoom/pan). A failed slot renders a neutral placeholder box (reusing
`label_unavailable`) sized to **its own source `PageGeometry`'s aspect ratio — never its healthy
sibling's** (original 3C claim here was "sized to its healthy sibling's aspect ratio"; that was
corrected by Codex R1 finding 3 — see the remediation record below for the real fix and its
evidence), with its own localized content description.

**Accessibility**: each slot's `Image` carries a distinct content description
(`content_desc_spread_current_page` for the slot matching `state.page`,
`content_desc_page_of_count` — the existing single-page string — for its sibling), and a failed
slot's placeholder carries `content_desc_spread_page_unavailable`; page-turn controls/semantics
are otherwise unchanged from the single-page case.

**Memory (original 3C claim, corrected by Codex R1 — see the remediation record below)**: the
original landed claim ("accounted for fully by `RenderMemoryPolicy` unchanged") was **incorrect**
and has been corrected. Sequential-never-parallel decode prevents concurrent *decoders*, but a
spread REPLACEMENT (old pair A+B still held by `_state`/Compose while new pair C+D decodes) can
reach four live reading-resolution bitmaps at once, not two. §25's remediation record documents
the real derived budget and the deterministic tests that now enforce it in code.

**Tests**: `core/reader/SpreadModelTest.kt` (pure JVM — pairing, landscape, RTL
placement, current-page invariant, AUTO threshold, navigation boundaries, bounded-cost
equivalence; the RTL navigation test was corrected by the R1 remediation to compare real LTR/RTL
group lists instead of a tautological self-comparison), `core/reader/ReaderPreferencesSpreadTest.kt`
(pure JVM — persistence logic, deliberately JSON-free), `core/reader/PageRenderRequestTest.kt`
(R1 remediation added a spread-memory-budget group — thumbnail reservation, all four transition
classes' worst-case arithmetic, a near-ceiling spread-slot request, 3A single-page preservation),
`ReaderPreferencesSpreadInstrumentedTest.kt` (instrumented — the real JSON round trip/
malformed-value handling), `FixedReaderSpreadViewModelTest.kt` (instrumented — real-CBZ pairing/
landscape/AUTO-resize/corrupt-pair/thumbnail-jump/category-gate/memory evidence against the real
`FixedReaderViewModel`; R1 remediation added the rapid-Next unresolved-geometry adversarial tests
for both split-page variants), `FixedReaderSpreadUiTest.kt` (new, R1 remediation — real Compose/
bounds-based coverage: corrupt-first/corrupt-second spread layout, LTR/RTL physical placement,
mixed-aspect Fit Page, final-complete-spread Next-enablement, immediate SpreadMode apply in both
directions, stale-gesture-geometry regression, Fit Width + zoom spread geometry). See
`docs/VALIDATION.md`'s "PHASE 3C" entry for exact commands/results, including the honest
regression spot-check and the one documented environment flake (unrelated to this slice).

**What this slice deliberately does NOT do** (explicitly 3D/3E/future scope, consistent with
§12/§13/§19): no `FoldingFeature`/hinge/posture detection or hinge gutter (3D); no
`PublicationFormat.CBR`/RAR support (3E); no panel detection, guided view, or source-page
splitting; no persistent/on-disk spread state of any kind; no live high-resolution
zoom-triggered re-render beyond what 3A already established.

**Codex R1 remediation record (this branch, one commit on top of the original 3C commit).**
Codex R1 reviewed the full 3C candidate above and returned CHANGES REQUIRED while accepting the
underlying architecture (pure `SpreadModel`, cover-offset canonical pairing, title-specific JSON
persistence, logical/physical RTL separation, format-neutral `PageGeometry`, no Room migration,
future-CBR compatibility unchanged). Every finding below was remediated on this same branch; no
dependency, Room schema, or migration was touched; 3D/3E/CBR/foldable/hinge scope was not
started.

- **Finding 1 (unresolved geometry could enable a skip).** `FixedReaderViewModel.geometryCache`
  is now a `ConcurrentHashMap` (previously a plain `MutableMap` read on the UI thread while
  written from `Dispatchers.IO`). Semantic navigation (`turn`/`hasNext`/`hasPrevious`) now reads
  it through `isLandscapeAtForNavigation`, which treats UNRESOLVED geometry as `true`
  (conservative — forces single-step, no-skip movement) rather than the presentation layer's
  documented optimistic `false` default; once `render()` actually resolves the real geometry
  (always before it decides a group's slot count), later navigation sees the true value and
  behaves exactly as before. Initial evidence used asynchronous `StateFlow` observation
  (`FixedReaderSpreadViewModelTest`'s original
  `rapidNextNeverSkipsAnUnresolvedLandscapePair_firstMemberLandscape`/`_secondMemberLandscape`,
  firing three unawaited `turn(1)` calls and recording every `state.page` value via a background
  collector) and was later superseded because conflation made it insufficient — see Codex R2
  Finding 2 below. The final deterministic evidence is: `CountDownLatch`-gated geometry
  resolution via `GatedGeometryFixedReaderFactory`, direct synchronous `state.value.page`
  inspection immediately after each `turn()` call (no waiting, no collector), the companion
  cached-unusable-`0×0`-geometry regression test
  (`cachedUnusableGeometryForACorruptPairMemberNeverAuthorizesANavigationSkip`), and — after the
  Codex R3 micro-remediation — a persisted locator/progress assertion proving the actual saved
  reading position matches page 2, never page 3, across the same rapid sequence.
- **Finding 2 (spread-aware memory budget).** `RenderMemoryPolicy` now derives
  `READING_BUDGET_BYTES = SESSION_BUDGET_BYTES (96MiB) - THUMBNAIL_RESERVATION_BYTES`
  (`ThumbnailLoader.DEFAULT_BUDGET_BYTES`, 16MiB — the thumbnail cache always shared this
  envelope; the constant now actually reflects that). Single-page: `MAX_BITMAP_BYTES =
  READING_BUDGET_BYTES / 3` (unchanged shape, tighter real number). Spread slot:
  `MAX_SPREAD_BITMAP_BYTES = READING_BUDGET_BYTES / 4` (the real worst case — two old spread
  slots still held by `_state`/Compose plus two newly-decoding replacement slots). Every
  transition class stays within `READING_BUDGET_BYTES`: spread→spread is `4 *
  MAX_SPREAD_BITMAP_BYTES` exactly at the limit; single↔spread is
  `MAX_BITMAP_BYTES + 2*MAX_SPREAD_BITMAP_BYTES`, comfortably under it; single→single remains
  `3 * MAX_BITMAP_BYTES` exactly at the limit (the original 3A invariant, preserved). Enforced via
  a new `PageRenderRequest.spreadSlot` flag that `resolveRenderTarget` and
  `FixedReaderViewModel.render()` route through the stricter ceiling. Deterministic coverage:
  `PageRenderRequestTest`'s spread-budget test group (budget derivation, all four transition
  classes' worst-case arithmetic, a near-ceiling spread-slot request, 3A single-page preservation)
  — this deterministic JVM suite, not the instrumented test, is the actual proof of the spread-
  slot ceiling/transition arithmetic. `FixedReaderSpreadViewModelTest`'s existing memory test
  still asserts each settled slot bitmap against `RenderMemoryPolicy.MAX_BITMAP_BYTES` (the
  looser, ordinary single-page ceiling), not the tighter `MAX_SPREAD_BITMAP_BYTES` spread ceiling
  — a real test fixtures could pass under without ever approaching the stricter bound, so this is
  supplemental PSS/runtime evidence only ("no observed runaway growth"), never independent proof
  the spread-slot ceiling itself held (corrected here by the Codex R2 remediation record, which
  found the previous wording here overstated what that instrumented test actually asserts).
- **Finding 3 (corrupt slot corrupted the whole spread layout).** `PageSlot` now carries its own
  `geometry: PageGeometry?`, always resolved by `render()` regardless of decode outcome.
  `FixedReaderScreen.combinedContentDimensions()` and the failed-slot placeholder
  (`placeholderAspect()`) now use each slot's OWN geometry — never a healthy sibling's — so a
  corrupt page occupies its own correct geometric footprint. Proven by
  `FixedReaderSpreadUiTest`'s `corruptFirstSlotLeavesHealthySiblingVisibleAndCorrectlyPositioned`/
  `corruptSecondSlotLeavesHealthySiblingVisibleAndCorrectlyPositioned` (real bounds assertions via
  new `spread_slot_<page>` test tags).
- **Finding 4 (stale pointerInput/slot capture).** The zoom/pan gesture now reads spread content
  geometry through `rememberUpdatedState(combinedContentDimensions(state.slots, gutterPx))`
  rather than closing over `state.slots` directly inside the long-lived gesture coroutine (still
  keyed only by page/rtl/fitWidth, so an in-progress gesture is never restarted merely because
  AUTO/SpreadMode flipped single↔spread). Initial evidence
  (`FixedReaderSpreadUiTest`'s `gestureGeometryStaysCurrentAcrossASpreadToSingleFlipWithoutAPageChange`,
  using `doubleClick`) was insufficient and was later superseded: `doubleClick` never entered the
  real pointer-transform path, only a separate `detectTapGestures(onDoubleTap = ...)` handler — see
  Codex R2 Finding 3 below. The final regression test,
  `pointerTransformGestureUsesCurrentGeometryAcrossASpreadToSingleAndBackFlipWithoutAPageChange`,
  actually zooms, performs a real one-finger drag/pan, enters `awaitEachGesture`, executes the
  `calculatePan`/transform logic, reads `currentContentDimensions`, distinguishes the single-mode
  vs. spread-mode pan clamps, and asserts `state.page` never changes across the flip. R1's original
  double-tap test is kept as narrower regression coverage for that specific (still valid) path, not
  as proof of the pointer-transform fix.
- **Finding 5 (SpreadMode change must apply immediately).** The ViewModel's preference collector
  now tracks `lastAppliedSpreadMode` and re-renders the current page the moment the EFFECTIVE
  title SpreadMode changes for an already-open session (title override set/cleared, or a reset
  restoring AUTO) — narrowly scoped to that one field; `state.page`/locator/progress are never
  touched and no extra persistence write occurs. Proven by `FixedReaderSpreadUiTest`'s
  `switchingFromSpreadToSingleAppliesImmediatelyWithoutChangingTheLogicalPage`/
  `switchingFromSingleToSpreadAppliesImmediatelyAndDerivesTheEligiblePair`.
- **Finding 6 (final-complete-spread Next-enablement).** `FixedReaderScreen`'s Next/Previous
  `enabled` now calls the ViewModel's `hasNext()`/`hasPrevious()`, which reuse the exact same
  `nextPage`/`previousPage` resolver `turn()` itself uses, instead of raw `page+1 < count`/
  `page > 0` arithmetic. Proven by `FixedReaderSpreadUiTest`'s
  `nextIsDisabledOnTheFirstPageOfTheFinalCompleteSpread`.
- **RTL tautology.** `SpreadModelTest.rtlNavigationUsesTheSameLogicalGroupsAsLtr` no longer
  compares one call to itself; it compares independently-built LTR/RTL group lists across every
  page and separately proves `physicalOrder` is the only thing direction changes.
- **RTL/Fit Page/Fit Width UI-evidence gap (flagged honestly above, now closed).**
  `FixedReaderSpreadUiTest` adds real bounds-based Compose coverage: LTR/RTL physical placement
  (`ltrPlacesTheLowerLogicalPageOnThePhysicalLeft`/
  `rtlMirrorsPhysicalPlacementWithoutReversingLogicalIdentity`), a mixed-aspect healthy Fit Page
  pair (`fitPageMixedAspectPairStaysWithinViewportWithGutterAndNoOverlap`), and a Fit Width +
  zoom spread case reusing the existing `reader_scroll_probe`/`reader_transform_probe` seams
  (`fitWidthSpreadZoomStaysClampedAndStatePageNeverChanges`). AUTO-driven single↔spread
  reconciliation itself remains proven at the ViewModel level
  (`FixedReaderSpreadViewModelTest.autoResolvesToSingleOnANarrowViewportAndToSpreadOnAWideOne`);
  the new Compose-level stale-gesture/SpreadMode-apply tests above exercise the equivalent
  explicit-SpreadMode-change path instead of a real device-rotation-driven AUTO flip, which this
  remediation pass did not attempt given emulator window-size reliability — an honest, flagged
  scope note, not a silent gap.

**Codex R2 micro-remediation record (this branch, one commit on top of the R1 remediation
commit `bc96099`).** Codex R2 reviewed the R1 remediation above and returned CHANGES REQUIRED with
four findings; every R1 finding was explicitly accepted and left unmodified (memory policy,
corrupt-slot layout, `SpreadMode` reconciliation, final-nav resolver, RTL physical ordering, and
AUTO's width-driven behavior with physical device rotation still deferred to 3F).

- **Finding 1 (cached unusable geometry was still navigation-pair-eligible).** R1's navigation
  conservative-default (`geometryCache[page]?.isLandscape ?: true`) only engaged when a page had
  NO cache entry; once the `PageGeometry(0, 0)` failure sentinel WAS cached (a corrupt/undecodable
  page), reading `.isLandscape` directly on it evaluated `false`, bypassing the `?:` fallback and
  letting navigation treat it as "confirmed pair-eligible" — able to reintroduce the exact skip R1
  closed. Fixed with one centralized `PageGeometry.isUsable` (`width > 0 && height > 0`);
  `isLandscapeAtForNavigation` now checks it before ever trusting `isLandscape`, and
  `FixedReaderScreen`'s separate `isUnknown()` helper (presentation-side, intentionally left
  optimistic) now delegates to the same property instead of its own duplicated
  `width <= 0 || height <= 0` check. Proven by `SpreadModelTest`'s new `isUsable`/`isLandscape`
  sentinel coverage and a direct buggy-vs-fixed `nextPage`/`previousPage` contract test, plus
  `FixedReaderSpreadViewModelTest`'s new `cachedUnusableGeometryForACorruptPairMemberNeverAuthorizesANavigationSkip`.
  One accepted, mechanical downstream consequence: a final-complete-spread whose FIRST member is
  corrupt can no longer have its true aspect confirmed, so `FixedReaderSpreadUiTest`'s
  `corruptFirstSlotLeavesHealthySiblingVisibleAndCorrectlyPositioned` now correctly asserts Next
  stays enabled (navigating single-steps within the same visible pair, no skip, no crash) rather
  than the pre-fix assertion that it was disabled — Finding 6's resolver logic itself is
  unchanged; only this one input (an unusable geometry read) now flows through it correctly.
- **Finding 2 (rapid-navigation race test was non-deterministic).** R1's test fired three
  `vm.turn(1)` calls and recorded every `state.page` value via a background `StateFlow` collector,
  then asserted the split page appeared somewhere in that list — Codex R2 found collecting in the
  background can conflate/miss intermediate values (`visited=[1, 1, 3, 3]` observed on one run).
  Replaced with a deterministic adversarial test: a `GatedGeometryFixedReaderFactory` test seam
  (`FixedReaderFactory`/`FixedReaderFactory.open` made `open` for exactly this purpose) wraps the
  real `FixedReader` so `pageGeometry` for the candidate pair blocks on a `CountDownLatch` the test
  controls explicitly; since `showPage()` updates `state.value.page` synchronously before the
  async render is even launched, the authoritative navigation result is asserted immediately after
  each `turn()` call with no waiting and no collector.
- **Finding 3 (gesture regression test never reached the real transform loop).** R1's
  `gestureGeometryStaysCurrentAcrossASpreadToSingleFlipWithoutAPageChange` used `doubleClick`,
  which only flips `scale` through a separate `detectTapGestures(onDoubleTap = ...)` handler —
  never the `awaitEachGesture`/`calculateZoom`/`calculatePan` loop that actually reads
  `currentContentDimensions` (R1 finding 4's fix). A new test,
  `pointerTransformGestureUsesCurrentGeometryAcrossASpreadToSingleAndBackFlipWithoutAPageChange`,
  uses the existing "Zoom in" control plus a one-finger drag (`scale > 1f` alone routes a single-
  pointer drag through the real transform branch), against a fixture built so the single-mode and
  spread-mode pan clamps are measurably different — an assertion capable of failing if stale
  geometry were ever captured again across the flip. R1's original double-tap test is kept as
  regression coverage for that specific (still valid, just narrower) path.
- **Finding 4 (stale/inaccurate documentation).** This document's "healthy sibling's aspect
  ratio" placeholder description (superseded by R1 finding 3, never corrected in prose) and its
  claim that the instrumented memory test "asserts against the new per-slot budgets" (it actually
  still asserts the looser `MAX_BITMAP_BYTES`, not `MAX_SPREAD_BITMAP_BYTES`) are corrected in
  place above. `docs/VALIDATION.md`'s PHASE 3C entry's stale `8/8` test-count claim, "~32MiB"
  memory wording (predates the `THUMBNAIL_RESERVATION_BYTES` carve-out), and "RTL/UI evidence
  remains outstanding" claim (closed by R1's `FixedReaderSpreadUiTest`) are corrected there, with
  new R1/R2 remediation entries recording the real final counts/values.

See `docs/VALIDATION.md`'s "PHASE 3C — Codex R2 micro-remediation" entry for exact commands/
results.

## 26. 3D implementation record (landed, pending review)

**Scope delivered**: hinge-aware spread placement, gutter, no-artwork-under-hinge, and
fold/unfold continuity for CBZ/PDF comics and manga (§11's 3D contract), reusing 3C's
`SpreadModel`/`PageGroup`/`PageGeometry`/render-request/memory architecture entirely — no new
reader engine, no bitmap stitching, no device-model checks, no Room schema change, no new
dependency, no CBR/RAR code.

**WindowManager integration**: the existing `androidx.window:window` dependency (already present
pre-3D) remains the ONLY fold data source — `MainActivity` is still the one and only
`WindowInfoTracker`/`FoldingFeature` consumer in the app (no second tracker added). A new private
`FoldingFeature.toReaderFoldDescriptor()` mapping function (in `MainActivity.kt`, never in
`core.reader`) converts the platform type into the small, app-owned, WindowManager-free
`ReaderFoldDescriptor` (`core/reader/FoldLayout.kt`) — orientation, `isSeparating`,
`occlusionType == FULL`, and `bounds` widened from `android.graphics.Rect` (int) to `FoldRect`
(float). No raw `FoldingFeature`/`androidx.window` type ever reaches `core.reader` or
`FixedReaderViewModel`; `FixedReaderScreen` receives only the descriptor.

**Pre-3D fold behavior preserved for non-reader screens**: `MainActivity`'s Phase-0 "stay inside
the larger unobstructed region" padding is now implemented via the extracted, independently pure-
tested `legacySafePaneInset` (byte-for-byte the same formula as the original inline code), applied
exactly as before for every non-reader screen (Library/Search/Shelves/Settings/Details/import
dialogs). `ShelfApp` reports whether the current nav-graph route is the reader
(`onReadingChanged`) purely as a boolean callback — `MainActivity` never inspects navigation state
or learns anything about comic pairing/spreads itself. While reading, `MainActivity` applies ZERO
padding (`PaddingValues(0.dp)`) and hands the fixed reader the full safe-drawing window plus the
raw fold descriptor, so the reader is never pre-collapsed into one pane before it can do its own
fold-aware layout.

**Fold descriptor / feature filtering (the single-feature assumption SUPERSEDED by §26a's finding
3 — kept here only as the historical starting point)**: `ReaderFoldDescriptor.isRelevant` is
`isSeparating || occludesFully` — a merely-visible, non-separating, non-occluding crease is
filtered out before it ever reaches `resolveReaderFoldLayout`. A feature whose bounds don't
intersect the reader's own measured bounds at all degrades to `FoldPresentation.FLAT` (see
`resolveReaderFoldLayout`'s doc). Never persisted (no Room, no SavedState, no device-specific
info) — re-derived fresh from the platform on every composition/recreation. As originally landed,
only one `FoldingFeature` was realistically expected and `MainActivity` picked the first relevant
one BEFORE reader bounds were known; §26a's finding 3 replaced that with bounds-aware selection
across every reported feature.

**Coordinate mapping (the slice's flagged highest-risk area)**: `resolveReaderFoldLayout` takes
the reader surface's own bounds AND the fold's bounds both in WINDOW coordinates (exactly what
`LayoutCoordinates.boundsInWindow()`/`FoldingFeature.bounds` report), intersects them, and
translates the result into the reader's own LOCAL coordinate space (origin at the reader
surface's own top-left) — this is the one and only place that translation happens; nothing
elsewhere compares window-space fold bounds directly against local Compose coordinates.
`FixedReaderScreen` captures its own window bounds via `onGloballyPositioned` on the
`reader_page` `Box` (superseding the pre-3D plain `onSizeChanged`). Proven by `FoldLayoutTest`'s
dedicated coordinate-space cases (center/asymmetric/zero-width/full-occlusion hinges, an
offset-from-origin reader surface, a feature entirely outside reader bounds).

**Vertical fold**: `resolveReaderFoldLayout` produces `FoldPresentation.VERTICAL_SPLIT` with
`leftPane`/`rightPane` (reader-local `FoldRect`s) and `hingeGapPx` (the real hinge width, widened
to at least the existing `t.spacing.small` visual gutter for a zero-width separating crease,
symmetric around the hinge's own center line). **SPREAD**: `PageGroup.physicalOrder(rtl)` (3C's
existing, UNCHANGED RTL mapping) decides which logical page occupies which physical pane —
LTR: lower index left, higher index right; RTL Manga: mirrored. **AUTO**:
`verticalFoldSpreadEligibleForAuto(leftPaneWidthDp, rightPaneWidthDp)` — both panes must each be
at least half of the existing `AUTO_SPREAD_MIN_WIDTH_DP` AND their combined usable width must
still meet the full threshold, so a narrow sliver pane never gets "spread" treatment merely
because the total window happens to be wide. Explicit **SPREAD** uses the more permissive
`verticalFoldHasTwoUsablePanes` (both panes genuinely positive-area). **SINGLE**/solo-page pane
selection (`selectSoloPane`): the pane with the greater usable area; on an exact tie, the
reading-direction start pane (LTR → left, RTL → right) — proven by `FoldLayoutTest`.

**Horizontal fold**: `FoldPresentation.HORIZONTAL_SPLIT` resolves exactly ONE safe pane (the
larger region; "top" wins an exact tie) and the EXISTING flat 3C SINGLE/AUTO/SPREAD code runs
entirely inside it (reused byte-for-byte, just fed the pane's own size instead of the whole
window) — no tabletop controls pane, no notes-below-fold, no TTS deck, no new comic "top/bottom
page" model.

**Hinge/gutter**: the real intersected hinge bounds are honored; `FixedReaderScreen`'s existing
`t.spacing.small` token is reused as the minimum visual gutter for a zero-width separating
crease (no new preference). The hinge gap is defined by the two panes' FIXED positions, computed
once per fold-layout resolution — it is never recomputed from a transform state, so it cannot
scale with zoom or translate with pan. Artwork safety is enforced in TWO independent layers: (1)
`foldSpreadSharedMaxPan` clamps the one shared `panX`/`panY` to the MORE restrictive of both
panes' own fitted-content bounds; (2) each pane's own `Modifier.clipToBounds()` is an
unconditional hard safety net regardless of (1)'s correctness — artwork can never visually render
past its own pane's edge into the hinge, at any scale/pan value, by construction. The legacy
`combinedContentDimensions` Row (which relies on an internal Spacer, never pinned to the real
physical hinge once the whole Row can be panned) is used ONLY for FLAT/HORIZONTAL_SPLIT, never for
a vertical-split spread — exactly the case the 3D contract's "never rely only on a Spacer" warning
targets.

**RTL**: LTR → lower logical index physically left; RTL Manga → higher logical index physically
left — both via the UNCHANGED `PageGroup.physicalOrder(rtl)`. `state.page`/locator/progress are
never reordered; only physical placement (and, for render-request sizing, which pane's pixel
width a logical page is decoded at) depends on direction.

**Fit Page / Fit Width / zoom-pan (Fit-Width-under-fold-spread portion SUPERSEDED by §26a's
finding 1 — kept here only as the historical starting point)**: FLAT/HORIZONTAL_SPLIT reuse ALL existing 3C transform code
unchanged, confined to the active pane's own size. VERTICAL_SPLIT's SOLO page also reuses the
existing single-page code, confined to the chosen pane. VERTICAL_SPLIT's SPREAD uses a new, small,
pure fold-aware renderer: each physical slot independently fits its OWN pane
(`fixedReaderFittedContentSize(bitmap, pane.width, pane.height, fitWidth)`), with ONE shared
`scale`/`panX`/`panY` (no independent per-pane transform state) read through
`rememberUpdatedState` inside the long-lived gesture coroutine (the same Codex-R1-established
discipline 3C already uses for `combinedContentDimensions`, extended to `FoldSpreadGeometry`).
Fit Width under a vertical-split spread intentionally does NOT reuse flat Fit Width's
`verticalScroll` container (a shared scroll across two independently-clipped, hinge-separated
panes has no single well-defined scroll position) — vertical overflow beyond a pane's own height
is instead reached through the same shared pan, exactly like Fit Page's reachability model. This
is an honest, documented narrowing from flat Fit Width's auto-scroll convenience (flagged as a 3F
follow-up candidate), never a regression into unreachable/hidden content, since the pan clamp
still accounts for the real overflow. Fold/unfold re-clamp: the existing idle re-clamp
`LaunchedEffect` (keyed by, among others, `foldLayoutState`) re-derives and re-applies the correct
clamp (fold-spread-aware or flat, as appropriate) immediately after any fold-layout change, so a
stale flat-window pan offset can never survive into a newly-folded geometry.

**Render requests**: flat/solo-pane sizing is completely unchanged from 3C. For an ACTIVE
vertical-split spread, each logical page's `PageRenderRequest.viewportWidth` is its own assigned
physical pane's pixel width (mapped via `PageGroup.physicalOrder(rtl)`, never a second RTL
system) instead of the flat `viewportWidth / slotCount` approximation — proven directly by
`FixedReaderFoldRenderRequestTest`. `PageRenderRequest.spreadSlot` (and therefore
`RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES`) is unchanged and still applied to every spread slot
regardless of fold state.

**Memory**: `RenderMemoryPolicy` is completely unmodified — same `SESSION_BUDGET_BYTES`,
`THUMBNAIL_RESERVATION_BYTES`, `MAX_BITMAP_BYTES`, `MAX_SPREAD_BITMAP_BYTES`. No fold-specific
bitmap cache was added anywhere. Sequential (never parallel) decode inside the same render mutex
is unchanged. `FixedReaderFoldRenderRequestTest` directly asserts both fold-spread slots' decoded
bitmaps stay within `MAX_SPREAD_BITMAP_BYTES` despite the new asymmetric width hint.

**Render-storm safety (SUPERSEDED by §26a's finding 2 — kept here only as the historical starting
point)**: as originally landed in `3550484`, the effective layout/decode-triggering key was
`spreadActive()`'s boolean result alone (now itself fold-aware). `updateViewport` stored every new
`foldPaneWidths` value unconditionally but only actually triggered a new render on the rare call
where that one boolean changed. Codex R1 found this too coarse (see §26a, finding 2) — it is now
the richer `EffectiveRenderKey` (presentation shape + bucketed per-slot width/height +
`spreadSlot`), not a bare boolean. The render-storm coalescing GOAL (continuous fold-layout
recomputation never forces a new decode; only a materially different decision/geometry does) is
unchanged and re-proven against the new key by
`FixedReaderFoldRenderRequestTest.continuousFoldPaneWidthChangesThatNeverFlipSpreadActiveNeverTriggerANewDecode`.

**Fold/unfold continuity**: `state.page`, the locator, progress, `SpreadMode`, and reading
direction are untouched by any fold-layout change — `FixedReaderViewModel` never writes a fold-
specific field anywhere. Zoom (`scale`) is preserved across a fold/unfold exactly as it already
was across any other resize (it's keyed only by `(state.page, fitWidth)`, never by fold state);
`panX`/`panY` are immediately re-clamped against the new fold-aware geometry by the existing idle
re-clamp effect. Proven by `FixedReaderFoldableUiTest.foldFlatFoldPreservesTheSameLogicalPageThroughout`
(flat → vertical fold → flat → vertical fold again, same `state.page` asserted at every step, no
extra position advancement from posture change alone, presentation correctly cycling single/
fold-spread/flat-spread as appropriate).

**Reader chrome / dialogs (dialogs portion SUPERSEDED by §26a's finding 4 — kept here only as the
historical starting point)**: under a `VERTICAL_SPLIT`, the top controls row and the bottom
Previous/Next/slider row are both confined to ONE safe pane (`chromePane`, the same
`selectSoloPane` choice a solo page would use) via offset+width, computed OUTSIDE the existing
RTL `CompositionLocalProvider` so the pane's real physical position is never itself mirrored —
only the internal Previous/Next arrangement still mirrors for RTL, unchanged from 3C. FLAT/
HORIZONTAL_SPLIT chrome is unaffected (spans the full width exactly as before; chrome already
sits at the screen's true top/bottom edges, clear of a horizontal fold in the ordinary case).
Appearance/Pages dialogs were not changed in this slice — both are ordinary `AlertDialog`/
`BasicAlertDialog`-based overlays that Android already centers within the window's safe area;
no evidence of a hinge-specific problem was found, and no redesign was attempted (an honest,
lighter-touch verification than a dedicated dialog-positioning test, flagged rather than silently
assumed).

**Corrupt pair under a fold**: unchanged from 3C's per-slot error handling — a failed slot still
carries its own `PageSlot.error`/`PageGeometry`, its healthy sibling still renders independently.
For a vertical-split spread specifically, each slot (healthy or placeholder) is confined to its
OWN fixed pane exactly like a healthy slot — proven by
`FixedReaderFoldableUiTest.corruptSpreadMemberPlaceholderStaysInItsOwnHingeSafePane`.

**Thumbnails**: completely unchanged — no fold-specific thumbnail cache, no paired thumbnails, no
reversed logical ordering; `ThumbnailNavigator`/`ThumbnailLoader` were not touched by this slice.

**CBR compatibility preserved**: every new/changed surface in this slice operates on
`PageGroup`/`PageSlot`/`PageGeometry`/`FixedReader`/reader-viewport-or-fold-geometry — never on
ZIP entries, CBZ filenames, or ZIP CRCs. A future CBR `PageSource`/`FixedReader` implementation
satisfying the existing contract inherits fold-aware spread support automatically, with zero
changes required to `FoldLayout.kt` or the fold-aware portions of `FixedReaderViewModel`/
`FixedReaderScreen`.

**Dependencies**: NONE ADDED. The existing `androidx.window:window` dependency (already present
pre-3D) was reused as-is — no version change, no Accompanist, no device-vendor SDK.

**Room schema**: unchanged. **ReaderPreferences**: unchanged (no new fold/gutter field; `SpreadMode`
remains the only user-facing control, exactly as the 3D contract requires).

**Tests (as landed in `3550484`)**: `core/reader/FoldLayoutTest.kt` (new, pure JVM, 22 tests — the
full required matrix: no-feature/flat parity, non-separating/non-occluding crease ignored,
feature outside reader bounds ignored, vertical center/asymmetric/full-occlusion/zero-width
hinges, horizontal fold safe-pane selection + exact tie-break, malformed/empty-bounds safe
fallback, solo-pane selection including the LTR/RTL tie-break, AUTO's fold-aware policy (rejects a
tiny-sliver pane despite ample total width; rejects below-threshold combined width; accepts two
individually-useful panes), explicit SPREAD's two-usable-panes policy, and the extracted
`legacySafePaneInset` non-reader-regression-guard cases). `app/src/androidTest/.../FixedReaderFoldableUiTest.kt`
(new, 6 tests, cases A–F from the required instrumented matrix). `FixedReaderFoldRenderRequestTest.kt`
(new, 2 tests — render-request pane-aware sizing + the render-storm coalescing guard). Regression:
`FixedReaderSpreadUiTest`, `FixedReaderSpreadViewModelTest`, `ReaderPreferencesSpreadInstrumentedTest`,
`FixedReaderViewModelLifecycleTest` all re-run and PASS unchanged. **This test list grew
substantially in the Codex R1 remediation commit (§26a) — see `docs/VALIDATION.md`'s "PHASE 3D
CODEX R1 REMEDIATION" entry for the current exact counts/commands/results; the numbers above are
historical, as originally landed, not the current total.**

**What this slice deliberately does NOT do** (explicitly 3E/3F/future scope): no
`PublicationFormat.CBR`/RAR support; no tabletop controls pane/notes-below-fold/TTS deck/
annotation palette; no new persisted fold/gutter preference; no dedicated foldable AVD or physical
foldable posture-transition check (honest gap, flagged for 3F — the deterministic injected-fold-
descriptor tests above are the primary gate per §15); no redesign of Appearance/Pages dialogs
beyond the verification noted above; no Fit-Width verticalScroll equivalent for the vertical-
split spread case (documented narrowing, not a regression).

### 26a. 3D Codex R1 remediation (this commit, on top of 3550484)

Codex R1 reviewed the §26 candidate and returned CHANGES REQUIRED on 5 findings, while explicitly
accepting the core architecture (coordinate mapping, AUTO/SPREAD pane policy, hinge clipping,
memory model, continuity, non-reader legacy behavior — none of that was touched). This records the
actual final behavior and corrects several §26 claims Codex found overstated or wrong. This is a
single remediation commit; it does not start 3E.

**Finding 1 (Fit Width fold-spread reachability — §26's claim corrected).** §26 claimed Fit Width's
vertical-fold-spread reachability model ("vertical overflow... reached through the same shared
pan, exactly like Fit Page's reachability model") was fully reachable; it was not. The actual
model was one shared literal `panY` pixel value, clamped to `min(leftPane's own max pan,
rightPane's own max pan)` — wrong whenever the two panes' fitted heights differ materially (a
pane height 1000 with fitted heights 1600/2600 clamped the shared value to the SHORTER page's
±300 range, permanently hiding ~500px at both ends of the taller page), and additionally never
consumed an ordinary one-finger vertical drag at base (`scale == 1f`) Fit Width scale at all (no
`verticalScroll` container exists for a fold-spread pane, and the old `isTransformGesture` gate
required a pinch or an existing zoom). **Final model**: one shared, normalized vertical reading
`progress` (`0f` = top/reading-start, `1f` = bottom/reading-end), mapped INDEPENDENTLY into each
pane's own overflow range via `foldPaneVerticalOverflow`/`foldPaneReadingTranslationY`
(`FixedReaderTransform.kt`) — never a shared pixel bound. Folded Fit Width now begins at reading
start (top), never centered. An ordinary one-finger vertical-dominant drag moves `progress`
directly at any scale (including base scale); a pinch or a zoomed horizontal-dominant drag
continues to drive shared `panX`/`scale` exactly as before, combined with the same `progress`
drag via `foldSpreadDragToProgress`. Applies uniformly to Fit Page's own zoomed fold-spread case
too (the underlying `min()` defect was fit-mode-agnostic). Proven by
`FixedReaderTransformTest`'s mixed-height-pane cases (pane 1000 / fitted 1600 / fitted 2600,
matching this exact example) and `FixedReaderFoldableUiTest
.oneFingerVerticalDragAtBaseFitWidthScaleMovesTheSharedReadingPositionForMismatchedHeightPages`
(real production gesture, real mismatched-height pages, asserts `foldSpreadProgress` via the
extended `reader_transform_probe`). `clipToBounds` pane safety is unchanged.

**Finding 2 (effective render key — §26's claim corrected).** §26 claimed the render-storm guard
was "the effective layout/decode-triggering key... `spreadActive()`'s boolean result" — too
coarse: that boolean cannot distinguish flat↔asymmetric-vertical-fold, vertical↔horizontal, a
safe-pane-selection change, or a material same-spread pane resize from each other when none of
them happens to flip it, risking a stale wrong-resolution bitmap. **Final model**: a stable
`EffectiveRenderKey` (`core/reader/RenderKey.kt`) built from the presentation SHAPE
(`ReaderRenderGeometry.Single` vs `.Spread`, so a shape change always differs by list length
alone) plus each relevant slot's own BUCKETED width/height and `spreadSlot` classification.
Quantization policy: round (not floor) to the nearest `RENDER_KEY_BUCKET_PX` (32px) bucket — a
small, named, decode-size-irrelevant coalescing policy (never `RenderMemoryPolicy`'s own
byte-budget constants, which are untouched). `FixedReaderViewModel.updateViewport` now takes a
`ReaderRenderGeometry` (the ACTUAL per-presentation decode-target box(es): the active pane's own
size for FLAT/HORIZONTAL_SPLIT-safe-pane/VERTICAL_SPLIT-solo, each physical pane's own independent
size for an active vertical-fold spread) instead of the whole reader surface's raw
`width/height/widthDp` — §26's claim that flat/solo-pane sizing was "completely unchanged from
3C" undersold a real gap Codex found: a solo page confined to a fold-safe pane (horizontal fold,
or a vertical-fold solo page) was previously still requested at the WHOLE reader surface's
dimensions, never the pane's own. Proven by `RenderKeyTest` (pure JVM bucket/key policy: harmless
same-bucket drift vs. a material bucket change, `Single`/`Spread` never colliding) and the
extended `FixedReaderFoldRenderRequestTest` (flat→fold/fold→flat/vertical→horizontal-shaped
transitions via the key; a real asymmetric-pane render-request assertion matching the production
two-pass Single-then-Spread layout sequence; the render-storm coalescing guard re-proven against
the new key).

**Route-entry race (part of finding 2).** §26's "`ShelfApp` reports whether the current nav-graph
route is the reader (`onReadingChanged`) purely as a boolean callback" was itself the defect: that
callback fired from an async `LaunchedEffect`, so `MainActivity`'s own non-reader legacy fold
padding could still reflect a stale `reading` value for one frame around a reader-route
transition. **Fix**: `MainActivity` no longer computes that padding at all — it only measures its
own window bounds and hands RAW inputs (`folds: List<ReaderFoldDescriptor>`, `legacyWindowBounds`)
down to `ShelfApp`, which computes `reading` AND the legacy padding SYNCHRONOUSLY, in the same
composition pass, at the exact point `reading` is already known (`ShelfApp.kt`). No callback, no
`LaunchedEffect`, no cross-composable round trip remains in this path.

**Finding 3 (multiple `FoldingFeature`s — §26's claim corrected).** §26 claimed "Only one
`FoldingFeature` is realistically expected (the existing `MainActivity` selection already picks
the first relevant one)" and relied on that `firstOrNull`, chosen BEFORE any reader bounds were
known — if a second, reader-intersecting feature existed, it could never be considered. **Fix**:
`MainActivity` maps EVERY relevant-or-not platform feature into `ReaderFoldDescriptor`s (its
`toReaderFoldDescriptor()` mapper is now `internal`, not `private`, specifically so it has a real
test) and passes the full `List<ReaderFoldDescriptor>` down; `resolveReaderFoldLayout` gained a
list-taking overload that calls the new `selectRelevantFoldDescriptor(readerBoundsWindow,
descriptors)` — filters to relevant AND actually-intersecting-the-reader descriptors, then picks
the LARGEST intersection area, tie-broken deterministically by intersection top then left (never
platform list order). The single-descriptor overload is unchanged/still used for `MainActivity`'s
own non-reader legacy padding (byte-for-byte `firstOrNull { isRelevant }`, as before — that path
was never bounds-aware and isn't being made so here). No tri-fold multi-pane reading was added;
still resolves AT MOST one constraining feature. Proven by `FoldLayoutTest`'s new selection cases
(outside-reader vs. intersecting-reader; irrelevant-crease vs. relevant; two intersecting features
resolved deterministically; an exact-area tie; three relevant features still resolving to exactly
one). The previously-untested REAL production mapper now has dedicated coverage:
`FoldDescriptorMapperTest` (instrumented — `androidx.window:window-testing` is not a project
dependency, so this test implements the plain public `FoldingFeature` interface directly rather
than adding one) covers bounds mapping, `VERTICAL`/`HORIZONTAL` orientation, `isSeparating`, FULL
occlusion, irrelevant-crease preservation, and multiple-feature list mapping.

**Finding 4 (Appearance/Pages hinge safety — §26's claim corrected).** §26 said "no evidence of a
hinge-specific problem was found, and no redesign was attempted" for the ordinary
`AlertDialog`/`BasicAlertDialog`-based dialogs — true that no redesign happened, but also no
EVIDENCE that the platform's own window-centering is fold-aware, because it is not: a real
`Dialog` window centers on the WHOLE window regardless of any hinge. **Fix**: both dialogs gained
an optional `safePane: FoldRect?` parameter; when non-null (a relevant vertical fold constrains
the reader), the SAME content renders through a new `HingeSafeDialogOverlay`
(`feature/reader/HingeSafeDialog.kt`) — an in-composition-tree overlay (not a platform `Dialog`)
confined by plain `Modifier.offset`/`width` to the given pane, reusing the EXACT SAME pane
`FixedReaderScreen`'s own chrome (`chromePane`) already uses, for policy coherence. `safePane ==
null` (the ordinary unfolded case) keeps the exact pre-existing `AlertDialog`/`BasicAlertDialog`
path, completely untouched. Proven by `ReaderDialogFoldSafetyTest` (instrumented): both dialogs'
surfaces stay entirely inside the given pane and never intersect the simulated hinge, their
primary action stays reachable, an RTL-selected (opposite-side) pane is also honored, and the
unfolded case still uses the ordinary platform dialog.

**Transformed-artwork clipping claim corrected (finding 5).** Earlier phrasing implied the
existing zoom/pan UI test (`FixedReaderFoldableUiTest.zoomAndPanGestureNeverMovesArtworkUnderTheHinge`)
proves transformed artwork is clipped at the hinge. It does not, by itself: that test only asserts
the FIXED PANE BOX's own bounds, which stay safe regardless of where a transformed child
graphicsLayer attempts to draw past them — it is production's `Modifier.clipToBounds()` at the
correct pane ancestor (verified by direct modifier-chain inspection, not a pixel screenshot) that
actually clips. Both remain true and are kept: the pane-bounds test (proves panes themselves never
straddle the hinge) and the unconditional `clipToBounds()` safety net (proves transformed content
is clipped at each pane's own edge), documented as two separate, narrower claims rather than one
overclaiming test.

**Missing horizontal-fold UI / corrupt-second-member coverage.** §26's test list had no dedicated
horizontal-fold UI case and no corrupt-SECOND-pair-member case. Added:
`FixedReaderFoldableUiTest.horizontalFoldChoosesCorrectSafePaneWithNoFakeVerticalSpread` (a
horizontal separating fold resolves to exactly one safe pane, content stays inside it, no
`spread_slot_*` tag is ever created) and
`.corruptSecondSpreadMemberStaysInItsOwnPaneWithNoSubstitutionOrHingeIntersection` (pair `[3, 4]`,
page 4 corrupt: page 3 stays visible in its own pane, page 4's placeholder stays in its own pane,
neither intersects the hinge, no substitution with a nonexistent page 5, LTR physical identity
unchanged).

**Dependencies/Room/ReaderPreferences**: still unchanged by this remediation — no new dependency
(confirmed `androidx.window:window-testing` was deliberately NOT added; the production-mapper test
uses a minimal in-test `FoldingFeature` implementation instead), no schema change, no new
persisted field. See `docs/VALIDATION.md`'s "PHASE 3D CODEX R1 REMEDIATION" entry for exact
commands/results.

### 26b. 3D Codex R2 remediation (this commit, on top of `ba391c9`)

Codex R2 reviewed §26a's candidate and returned CHANGES REQUIRED on 2 findings, while explicitly
accepting everything else §26a fixed (folded Fit Width, per-pane overflow mapping, one-finger Fit
Width vertical movement, the Fit Page fold-spread generalization, synchronous route handling,
multiple-`FoldingFeature` mapping/selection, fold geometry/coordinate conversion, horizontal fold
behavior, corrupt-second-member layout, the memory model, continuity, `WindowManager` lifecycle,
CBR compatibility — **none of that was touched by this remediation**; Fit Width, the Fit Page
generalization, multiple-feature handling, route entry, and the memory model are byte-for-byte
unchanged from `ba391c9`). This is a single remediation commit on top of `ba391c9`; it does not
start 3E, implement CBR, or redesign anything already accepted.

**Finding A (circular render-geometry delivery — the real correctness bug §26a left in place).**
§26a's own `ReaderRenderGeometry` was a SEALED type (`Single` vs `Spread`) — the CALLER
(`FixedReaderScreen`'s `onGloballyPositioned`) had to pick exactly one shape before calling
`FixedReaderViewModel.updateViewport`, and it picked using the ALREADY-PUBLISHED `state.slots.size`.
This is circular: the whole point of reporting geometry is to let the ViewModel decide how to
render the NEXT group, which may have a different size than the one currently published. Navigating
from a solo page to a newly-eligible vertical-fold spread meant `onGloballyPositioned` still saw
`state.slots.size == 1` at the moment it ran, so it reported `Single`-shaped geometry (sized to the
one solo pane) with no spread target at all; the FIRST decode of the new pair then used that stale
solo-pane box (split evenly across 2 slots) instead of each pane's own real, asymmetric size.
Production correctness depended on an ACCIDENTAL second `onGloballyPositioned` call, triggered
once two slots were actually published, to self-correct — never guaranteed.

**Fix**: `ReaderRenderGeometry` (`core/reader/RenderKey.kt`) is no longer sealed. It is one data
class carrying BOTH `single` (the correct solo-page target for the CURRENT fold presentation —
FLAT's whole pane, `HORIZONTAL_SPLIT`'s safe pane, or a `VERTICAL_SPLIT`'s selected solo pane) and
`spread` (each physical vertical-fold pane's own independent size, non-null exactly when
`ReaderFoldLayout.hasTwoPanes` is true) — BOTH computed unconditionally from the current
`ReaderFoldLayout` alone, in `FixedReaderScreen`'s `onGloballyPositioned`, with **no
`state.slots.size` check anywhere in that computation**. `FixedReaderViewModel.render`/
`updateViewport` resolve the real `PageGroup` for the page about to be shown FIRST (via
`resolveCurrentGroup`, using a new synchronous, cache-only, optimistic landscape read,
`isLandscapeAtForPresentationSync` — never doing IO on the UI thread) and pick the matching shape
(`resolveRenderTargets`) — `spread` when the resolved group has 2 pages and a spread target
exists, `single` split evenly otherwise — strictly BEFORE decoding. Because `spread`/`single` are
always both current, selecting the right one never depends on a second layout pass, a later
recomposition, a retry, or a page turn.

Proven by three NEW instrumented tests, each driving the REAL `FixedReaderScreen`/
`FixedReaderViewModel` (never manually constructing a corrective second `updateViewport` call) —
`FixedReaderFoldRenderGeometryUiTest.kt`:

- `soloToSpreadFirstDecodeAlreadyUsesCorrectPerPaneTargetsWithNoIncidentalSecondLayout`: an
  OFF-CENTER vertical fold (so the two panes are meaningfully asymmetric, not coincidentally close)
  while a solo page is showing, then `vm.turn(1)` into a newly-eligible spread. Records every
  decode per page (not just the last) and asserts each of the two new slots was decoded EXACTLY
  ONCE with meaningfully asymmetric widths — proving no wrong-then-corrected pair of decodes ever
  happened. **Verified to actually catch the regression**: temporarily reintroducing the old
  `state.slots.size >= 2` gate made this exact test fail with "page 1... expected:&lt;1&gt; but
  was:&lt;2&gt;" (an incidental second decode), then pass again once the gate was removed.
- `spreadToSoloFirstDecodeAlreadyUsesTheSoloPaneTarget`: the reverse transition (spread → the
  cover, always solo) — the first decode already uses the solo pane's own width, never a stale
  spread-shaped half-pane value, and is never flagged `spreadSlot`.
- `horizontalFoldRequestUsesTheSafePaneDimensionsNeverTheWholeReaderSurface`: closes a remaining
  evidence gap Codex flagged — a horizontal fold's decode request height matches the selected safe
  pane, never the whole reader surface's full height.

**Finding A, part 2 (render-key quantization boundary jitter).** §26a's `EffectiveRenderKey`
bucketed to the NEAREST 32px (`RENDER_KEY_BUCKET_PX`) boundary with plain rounding — a raw
dimension oscillating by a single px across an exact half-bucket boundary (783↔784, since
784.0 == 24.5×32) flipped the bucket, and therefore the render-storm guard's decode decision, on
every oscillation. **Fix**: `acceptedRenderKeyBucket(px, lastAccepted)` — hysteresis around the
LAST ACCEPTED bucket. Once a bucket is accepted, it is kept as long as the raw px stays within
that bucket's own nearest-rounding span widened by `RENDER_KEY_HYSTERESIS_PX` (12px) on each
side; only a move past that widened span computes a fresh nearest bucket. `effectiveRenderKey`
now takes the resolved `groupSize` (not merely a `spreadActive` boolean) and the previous accepted
key, applying hysteresis per slot, falling back to plain nearest-rounding whenever the previous
key had a different structural shape (so a `Single`↔`Spread` transition never inherits a stale
bucket from an unrelated slot). The actual `PageRenderRequest` dimensions `render()` uses
(`resolveRenderTargets`) always stay the CURRENT raw pane size — hysteresis governs only whether a
re-render is triggered, never a systematically under-sampled decode target.

Proven by `RenderKeyTest` (pure JVM, 25 tests): `acceptedBucketNeverThrashesAcrossTheOldPlainBoundary`
(783↔784 oscillation, zero accepted-bucket changes after the first settle),
`acceptedBucketStillMovesOnceDriftGenuinelyExitsTheHysteresisBand`,
`boundaryJitterAroundTheOldBucketBoundaryProducesZeroKeyChangesAfterStabilizing`/
`...OnASpreadPaneAlso...` (the same proof through the real `effectiveRenderKey` entry point, for
both Single and Spread shapes), and `materialResizeProducesExactlyOneRenderCorrection` (a genuine
~500px→~780px resize still corrects exactly once, and a 1px drift around the NEW value afterward
does not re-thrash). The existing `FixedReaderFoldRenderRequestTest` test Codex flagged as
assertion-by-comment (its comment claimed "must trigger exactly one new render" but its only
assertion checked `slots.size == 1`, never a render COUNT) now additionally asserts the actual
recorded decode-count delta: the page that flips to solo is decoded exactly once more, and its
former sibling is never decoded again.

**Finding B (`HingeSafeDialogOverlay` isn't truly modal).** §26a's overlay (R1) was visually
dialog-like but not modal for keyboard/D-pad/controller focus or accessibility traversal: nothing
prevented focus from moving OUT of it into the reader's own background chrome/page content (a
SIBLING in the composition tree, never a descendant of the overlay), at which point the overlay's
own `Surface`-local `onKeyEvent`/`BackHandler` (both scoped to the overlay's own focus subtree)
could no longer see a subsequent key event, and an arrow key landing on a background control would
be read by the background `Column`'s own `onPreviewKeyEvent` as a page-turn command.

**Fix** (`FixedReaderScreen.kt`, `HingeSafeDialog.kt`): `hingeSafeModalOpen` is `true` exactly
while Appearance or Pages renders through `HingeSafeDialogOverlay` (never for the ordinary
unconstrained `AlertDialog`/`BasicAlertDialog` path, which keeps real platform window modality and
is untouched). Two complementary mechanisms, because the overlay is deliberately a SIBLING (not a
wrapper) of the reader's own background content and cannot reach into that subtree itself:

- **Root-level Back interception**: the screen's outermost `Box` (a genuine ancestor of BOTH the
  background `Column` and the dialogs) gets an `onPreviewKeyEvent` that, while a modal is open,
  intercepts the existing semantic `ShelfCommand.BACK` (reusing `InputMapper`/`shelfCommand` — no
  second key-mapping system) and dismisses the open modal, BEFORE the tunneling phase can ever
  reach the background `Column`'s own handler — regardless of which node actually holds focus.
  Deliberately NOT a blanket swallow of `NEXT_PAGE`/`PREVIOUS_PAGE`/`OPEN_MENU` (those commands are
  also the plain arrow keys the dialog's own content needs for internal D-pad navigation between
  its controls); "no page-turn while the modal is open" is instead guaranteed structurally by:
- **Background focus/accessibility suppression**: while `hingeSafeModalOpen`, the background
  `Column` gets `Modifier.focusProperties { canFocus = false }` (Compose's own focus search then
  skips every descendant focus target reachable through it — the standard "a scrim blocks focus"
  idiom) and `Modifier.clearAndSetSemantics {}` (excludes the whole subtree from the default/merged
  accessibility tree). Since the background `Column` is never an ancestor of a node focused inside
  the overlay, and can no longer receive focus itself, its own `onPreviewKeyEvent` simply never
  runs for a key event while a modal is open — the actual mechanism closing "D-pad/page commands
  never turn the page while open." `HingeSafeDialogOverlay`'s own `Surface` additionally gained
  `Modifier.semantics { paneTitle = ... }` (a localized, spoken-only string — never visible,
  satisfying the localization rules) communicating "this is a modal surface" to TalkBack.
- **Focus restoration**: `dismissAppearance()`/`dismissThumbnails()` (the one dismissal
  implementation per dialog, reused for Cancel/Apply/Close, scrim-tap, and the root-level Back
  interception above) restore focus to the reader's own STABLE control surface (`pageFocus`, the
  same target the reader's initial-open focus already uses) — the 3D contract's own explicitly-
  sanctioned fallback when exact-original-trigger restoration is impractical. This is not a
  shortcut: when the overlay's own focused `Surface` is disposed, Compose's focus owner itself
  needs to reassign focus somewhere and was empirically observed to land on `pageFocus` regardless
  (the first focusable candidate in the background once unblocked); confining the explicit request
  to that SAME stable target, rather than racing a specific original trigger button through several
  frames of a disposal-driven reassignment that could not reliably be outlasted in testing, is
  simpler and still strictly satisfies "focus must never simply disappear."

Proven by `FixedReaderHingeSafeModalTest.kt` (new, instrumented, 7 tests), all driving the REAL
`FixedReaderScreen` under an injected vertical fold: Pages/Appearance dismissal via Escape and
gamepad B with the reader staying open and `state.page` unchanged; an explicit page-command-block
proof (`PAGE_DOWN`, never a focus-navigation key, does nothing while Pages is open); D-pad
page-turning resumes normally after dismissal; a background touch over the reader's own
tap-to-turn zone never turns the page while the modal is open (no click-through); the overlay
exposes `PaneTitle` accessibility semantics and a known background control is absent from the
accessible tree while open and returns once dismissed; a background control cannot become focused
at all while a modal is open (`canFocus = false` proven directly, not merely "happened not to be
reached"); and dismissing either dialog restores focus to the reader's stable page surface, never
leaving it stranded. The pre-existing, NON-fold `ThumbnailNavigationUiTest` (7 tests, real
`MainActivity` flow, ordinary `BasicAlertDialog` path) was re-run and PASSES unchanged — the new
modal machinery is fold-overlay-specific and does not touch the ordinary dialog path.

**Honest limitation, documented rather than silently worked around**: this remediation's own
testing could not get Compose's test-side `requestFocus()`/Tab-key navigation to reliably move
KEYBOARD focus from the overlay's own initial-focus `Surface` onto a SPECIFIC deeper descendant
(e.g. the Close button) in the bare `createComposeRule()` harness used by these tests (no real
hosting `Activity` window) — this appears to be a harness characteristic, not a production claim;
`performKeyInput` dispatches to whichever node ACTUALLY holds focus regardless of which node
reference it is called on (the same mechanism the pre-existing `ThumbnailNavigationUiTest` already
relies on), so the dismissal/page-command-block tests above still genuinely exercise the real
key-dispatch path through the overlay's own focused subtree; they just cannot additionally assert
the EXACT descendant holding focus in this harness. Flagged as a Phase 3F follow-up if a future
change ever needs to assert precise intra-dialog focus position.

Regression (unchanged): `RenderKeyTest` (JVM), `FixedReaderFoldRenderRequestTest`,
`ReaderDialogFoldSafetyTest`, `FixedReaderFoldableUiTest` (9 tests), `FixedReaderSpreadViewModelTest`
(11 tests), `FixedReaderViewModelLifecycleTest` (2 tests), `ThumbnailNavigationUiTest` (7 tests)
all re-run and PASS. Fit Width, the Fit Page generalization, multiple-`FoldingFeature` handling,
route-entry, and the memory/ownership model are unchanged from `ba391c9` (confirmed by inspection —
none of their own files were touched by this remediation beyond the mechanical
`ReaderRenderGeometry.Single(...)` → `.flat(...)` rename in two unrelated test files that only ever
needed the flat/no-fold-spread shape).

**Dependencies/Room/ReaderPreferences/manifest**: unchanged — no new dependency, no schema change,
no new persisted field, no manifest change. One localized content-description resource was added
across EN, ES, and PT-BR (`content_desc_hinge_safe_dialog` — spoken-only accessibility copy,
never visible UI text). See `docs/VALIDATION.md`'s "PHASE 3D CODEX R2 REMEDIATION" entry for exact
commands/results.

## 27. 3E-B implementation record (generic internal native RAR engine)

**Scope delivered**: real RAR4/RAR5 open/enumerate/extract through a new, generic,
product-free native session (`NativeRarSession`, `app/src/main/java/com/d4guilar/shelfos/
core/files/NativeRarSession.kt` + `app/src/main/cpp/shelfos_rar_session_jni.cpp`), built on
top of 3E-A's accepted foundation (vendored libarchive 3.8.9, the `shelfos_cbr` JNI library,
NDK/CMake plumbing) without reopening or modifying any of it. This checkpoint still does NOT
add `PublicationFormat.CBR`, a `RarPageSource`, any page/image filtering, a cache, or any
reader/product integration — see `docs/adr/0024-native-cbr-libarchive.md` and this section for
exactly what is and is not in scope.

**Session/handle model**: one native C++ `Session` struct per `NativeRarSession.open(fd)` call,
addressed by an opaque `jlong` (a `reinterpret_cast` of the heap pointer — never meaningful
outside the native file, never logged/persisted). There is no process-global "current archive"
and no native handle-validity registry; `NativeRarSession` itself is the sole owner of the
handle, enforcing atomic set/clear-on-close, idempotent `close()`, and rejecting post-close
operations entirely in Kotlin before any native call — the native side's own defense is limited
to a cheap `handle <= 0` sanity check, documented as not a substitute for that contract.

**FD ownership (source)**: the caller transfers ownership of `fd` to
`NativeRarSession.open(fd: Int)` and must first relinquish Java-level ownership via
`ParcelFileDescriptor.detachFd()`. If the native backend is unavailable before JNI,
`NativeRarSession.open()` closes the fd itself. Once native session initialization begins,
ownership transitions into native `Session` handling; every success/failure path closes exactly
once. No `dup()` is used anywhere — there is exactly one owned fd, restarted in place via
`lseek(fd, 0, SEEK_SET)` whenever a fresh sequential pass is needed, so there is no shared-offset
subtlety. The vendored `archive_read_open_fd.c` `file_close()` only frees its own internal buffer
and never closes the fd, so ShelfOS remains responsible for that exactly-once cleanup.

**Seek/restart (no cache)**: every operation that needs a specific entry's data — the metadata
pass in `open()`, and each `extractEntry()` call — restarts a brand-new `archive_read` from
`lseek(fd, 0, SEEK_SET)` and sequentially skips (`archive_read_data_skip`) to the target physical
index. This applies equally to solid RAR5 (proven by `rar5SolidEnumeratesAllEntriesAndRestartBasedAccessSequenceWorks`'s
non-sequential last→earlier→last-again access sequence). If the fd cannot be seeked at all
(verified via a direct `lseek` probe: `ESPIPE` → `NOT_SEEKABLE`; `EBADF` and every other errno
→ `IO`), `open()` fails with `NOT_SEEKABLE` rather than falling
back to any managed-copy behavior — that policy mapping (e.g. to ShelfOS's existing NEEDS_COPY
semantics) is explicitly deferred to a later slice.

**Destination FD (extraction)**: `extractEntry(index, destinationFd)`'s destination is a
BORROWED fd — the engine never closes it, on any path; the caller retains ownership.

**Threading**: one archive_read object alive at a time per session, scoped to a single call.
`NativeRarSession` synchronizes every public operation on its own per-instance lock (never a
global/cross-archive lock); the native engine performs no internal synchronization and relies
entirely on that contract.

**Minimal native API** (8 JNI functions, all on `NativeRarSession`): `nativeOpen`, `nativeClose`,
`nativeEntryCount`, `nativeEntryType`, `nativeEntryName`, `nativeEntryIsNameUtf8`,
`nativeEntrySize`, `nativeExtractEntry`. No writing, no delete/rename/repack, no generic
execute-command, no extract-all-to-directory, no shell/bsdtar invocation, and no
arbitrary-destination-path extraction exist anywhere in this surface. `grep -c archive_write`
against the new file returns 0.

**Streaming/memory**: extraction streams through one fixed 64 KiB (`kStreamBufferSize`) native
buffer into the destination fd — never a whole-entry or whole-archive allocation, and never
sized from an archive-claimed entry size. `EINTR` on `write()` retries the same write; a
zero-byte/no-progress write and every other `write()` failure (e.g. a closed/unwritable
destination fd) are reported immediately as `IO`; a real partial write is handled by
accumulating the written offset until the chunk is fully flushed. Session metadata allocation
scales with enumerated entry count/name bytes and remains subject to 3E-C archive-policy limits.

**Entry metadata/encoding**: `NativeRarEntry` exposes physical index, name, a confirmed-UTF-8
flag, type (`REGULAR_FILE`/`DIRECTORY`/`OTHER`), and a nullable size (`null` = declared size
unknown or distrusted, e.g. negative — distinct from a real 0-byte entry; a real entry size can
never be negative, so native uses `-1` unambiguously as that sentinel across the JNI boundary).
Names cross the JNI boundary as raw UTF-8 bytes (`jbyteArray`, not `NewStringUTF`/Modified-UTF-8)
— `archive_entry_pathname_utf8()` is used natively whenever available (a correct conversion
libarchive already performs); when it is not, the raw `archive_entry_pathname()` bytes are
returned instead and Kotlin decodes them as ISO-8859-1 (every byte value is a valid code point —
this path can never throw) as a documented, deliberately non-claiming-correctness fallback.
Directory/symlink/other-special entries are never extractable (`extractEntry` on a non-regular
entry returns `INVALID_ARGUMENT`); no link creation, no materializing special filesystem objects.

**Error contract**: one `NativeRarError` enum (`INVALID_ARGUMENT`, `IO`, `NOT_SEEKABLE`,
`CORRUPT`, `PROTECTED`, `UNSUPPORTED`, `NATIVE_INTERNAL`) — no raw libarchive numeric code and no
`archive_error_string()` text ever crosses into Kotlin or any test assertion as product text
(internal diagnostic/test-assertion use of libarchive's own codes stays entirely inside the C++
file). `ARCHIVE_OK`/`ARCHIVE_WARN`/`ARCHIVE_EOF` are the only "proceed" results during header
reads; `ARCHIVE_RETRY`/`ARCHIVE_FAILED`/`ARCHIVE_FATAL` all abort the current pass deterministically
(no retry loop is implemented for any of them) after first checking
`archive_read_has_encrypted_entries()` — this ordering is what lets a fully header-encrypted
archive (which never yields a single successful header) still map to `PROTECTED` rather than
`CORRUPT`/`UNSUPPORTED`, without ever requiring a successful entry list first. **No
partial-success interpretation**: if ANY entry in the archive is encrypted (data and/or
metadata), the WHOLE `open()` fails with `PROTECTED` — the real vendored encryption fixtures
(see below) have some entries unencrypted and some encrypted, and this engine never exposes a
session covering only the unencrypted subset.

**Wrong-format detection**: with only RAR4/RAR5 registered, the engine combines libarchive's
format-bid result with a position-preserving `pread()` of the pinned RAR signatures. Unrelated
bytes and matching prefixes shorter than six bytes map to `UNSUPPORTED`. The six-byte common RAR
marker is the minimum recognizable family prefix; once it matches, an early open/first-header
failure maps to `CORRUPT`, including incomplete/complete RAR4/RAR5 signatures. This avoids
treating meaningfully recognizable truncated RAR input as unrelated merely because libarchive
reported `EILSEQ`/`EINVAL` before selecting a reader.
Extension/magic-byte classification (`.cbr`/`.rar` filenames) is explicitly not this engine's
job — it operates on fd bytes only.

**Test matrix** (two instrumented classes, each run via its own separate Gradle invocation per
this checkpoint's standing constraint — never a comma-separated class list):

- `LibarchiveRarNativeTest` (format/extraction/encryption): RAR4 plain (`test_read_format_rar.rar.uu`,
  5 entries verified by name/type against direct inspection of upstream
  `libarchive/test/test_read_format_rar.c` — `test.txt`/`testlink`(symlink, `OTHER`, non-extractable)/
  `testdir/test.txt`/`testdir`/`testemptydir` — with `test.txt` and `testdir/test.txt` both extracted
  and compared byte-for-byte against the exact expected `"test text document\r\n"`); RAR5 compressed
  (`test_read_format_rar5_compressed.rar.uu`, `test.bin`, 1200 bytes, extracted and verified against
  the exact upstream `verify_data()` generator formula from `test_read_format_rar5.c`
  — `val = max(0, k*k - 3*k + 1)` per little-endian int32 word, not just "bytes > 0"); RAR5 solid
  (`test_read_format_rar5_solid.rar.uu`, 7 entries `test.bin`/`test1..6.bin` enumerated in order,
  plus a last→earlier→last-again extraction sequence verified via CRC32 against the exact upstream
  values from `test_read_format_rar5.c`'s `test_read_format_rar5_solid_skip_all_but_second`
  (`test1.bin` = `0x7E13B2C6`) and `..._skip_all_but_last` (`test6.bin` = `0x36A448FF`), proving
  restart-based non-sequential access); RAR4 and RAR5 encrypted fixtures both map to `PROTECTED`
  (never `CORRUPT`, no crash); a synthetic non-RAR byte buffer maps to `UNSUPPORTED`; a RAR5 solid
  fixture truncated to a 100-byte prefix (empirically chosen — see the test's own comment for the
  bisection evidence across 16 cut points, since this fixture's full payload is only ~1050 bytes
  and several naive cut points happened to still land on a valid entry boundary) maps to `CORRUPT`.
- `LibarchiveRarNativeLifecycleTest` (FD/handle/lifecycle/failure): an unopened fd number maps to
  `IO` with no open-fd-count growth (`/proc/self/fd` before/after); a source fd closed out from
  under the contract (via `ParcelFileDescriptor.adoptFd(fd).close()`) maps to `IO`; normal close;
  double close (harmless no-op); use-after-close (`entryCount` → 0, `entryAt` → null, `extractEntry`
  → `INVALID_ARGUMENT`, all without touching native code again); a failed open due to wrong format
  does not leak the source fd (`/proc/self/fd` count check); a destination fd closed before
  extraction maps to `IO` while the session itself remains fully usable/closable afterward.

Upstream ground truth (entry names/sizes/content/CRC32 values) for every fixture above was taken
by directly fetching and reading the real upstream test sources at the exact pinned commit
(`libarchive/test/test_read_format_rar.c`, `test_read_format_rar5.c`,
`test_read_format_rar_encryption.c`) rather than assumed from memory or "bytes > 0" placeholder
assertions. See `docs/VALIDATION.md`'s "PHASE 3E-B" entry for exact commands, counts, and results,
including the truncation bisection evidence, the solid-RAR5 diagnostic timing note, ABI compile
results, and native `.so` sizes.

**What this slice deliberately does NOT do** (explicit 3E-C/D/E or later scope, consistent with
AGENTS.md's "do not build yet" list and this checkpoint's own boundary): no `PublicationFormat.CBR`,
no `RarPageSource`, no page/image filtering, `isPageImage`, `naturalCompare`, or `ComicInfo.xml`
parsing, no Manga/RTL handling at this layer, no cache/LRU/cacheDir/page-cache/stale-session-cleanup
logic of any kind, no reader UI or `LibraryItem`/import-routing/persistence change, no extension/
magic-byte format classification, no passphrase/password support, no partial-archive recovery.

## 28. 3E-C implementation record (CBR container adapter / bounded extraction cache)

**Scope delivered**: the `RarPageSource` container adapter and a bounded on-disk extraction
cache (`RarExtractionCache`) on top of 3E-B's accepted `NativeRarSession`, completing exactly
the architecture goal stated for this checkpoint: **RAR native engine -> `RarPageSource` ->
the existing `PageSource` contract -> the existing `ImagePageRenderer`** — the identical shape
`ZipPageSource` already has for CBZ. This checkpoint still does NOT add
`PublicationFormat.CBR`, any import routing, magic-byte classification, `FixedReaderFactory`
routing, library category integration, Room/`LibraryEntity` changes, reader UI/Compose
changes, localized user-facing error strings, or encrypted-archive password UI. No native
C++ was touched or needed — 3E-B's JNI surface (`shelfos_rar_session_jni.cpp`,
`NativeRarSession`) is consumed exactly as accepted.

**Files added**: `core/files/RarArchiveSession.kt` (the `RarArchiveSession` interface +
production `NativeRarArchiveSession` adapter + `RarExtractionException`),
`core/files/RarExtractionCache.kt` (the bounded on-disk cache), `core/reader/RarPageSource.kt`
(the container adapter + `NativeRarError.toPublicationProblem()` mapping). Two small,
visibility-only edits to `core/reader/FixedReader.kt`: `PageSource` and `ImagePageRenderer`
widened from file-private to `internal` so `RarPageSource` (a different file in the same
package) can implement/use them — no behavior change to either.

**`RarArchiveSession` seam (testability boundary)**: a narrow interface
(`entryCount`/`entryAt`/`extractEntry(index, destination: File)`/`close`) expressed over a
destination `File` rather than a raw fd, specifically so `RarPageSource`/`RarExtractionCache`'s
own ordering/caching/safety-policy logic can be unit-tested in a plain JVM test
(`FakeRarArchiveSession`, `app/src/test`) without a real multi-image RAR fixture — upstream's
vendored `.uu` fixtures contain no comic-image sequence, and ShelfOS has (deliberately) no
RAR-writing capability to construct one (AGENTS.md: no RAR-writer, no external unrar/rar CLI
dependency). Production code satisfies the same interface via `NativeRarArchiveSession`, a
thin adapter that opens a `ParcelFileDescriptor` over the destination file and delegates to
the real `NativeRarSession.extractEntry(index, fd)` — adding zero new JNI operations. Honesty
boundary, stated explicitly in both the interface's doc and every fake-based test: a test
built on `FakeRarArchiveSession` proves this checkpoint's own logic, never real libarchive/RAR
parsing correctness — that remains proven only by 3E-B's real instrumented tests
(`LibarchiveRarNativeTest`/`LibarchiveRarNativeLifecycleTest`) plus this checkpoint's own
`RarPageSourceRealSessionInstrumentedTest`, which drives the real native session end to end
(open/enumerate/extract/close) against a real vendored fixture.

**Entry model / physical ordinal vs. logical page**: `RarPageEntry(physicalIndex, name)` is
the unit `RarPageSource.pages` holds; a logical page's cache/extraction identity is always its
`physicalIndex`, never its display name. Filenames are decoded exactly as 3E-B's
`NativeRarSession.entryAt` already does (UTF-8 primary via libarchive's
`archive_entry_pathname_utf8()`, ISO-8859-1 best-effort fallback otherwise) — no new decoding
logic was added. Image filtering reuses `isPageImage` unchanged; natural ordering reuses
`naturalCompare` unchanged, tie-broken deterministically by physical ordinal when two names
compare equal (duplicate filenames, or the same basename in different folders, therefore sort
deterministically and extract to distinct, non-colliding cache entries). The safe-name check
(`ArchivePolicy.safeName`) is applied to every entry (not just regular files, mirroring
`ArchivePolicy.entries` for CBZ) before any entry can become a logical page or a `ComicInfo.xml`
candidate — even though native extraction only ever writes to a caller-supplied destination
file and cannot itself path-traverse.

**Shared archive policy**: nothing needed to change in `ArchivePolicy.kt`. `ArchivePolicy.
safeName`, `naturalCompare` (both already plain, format-independent string functions) and
`isPageImage` (format-independent already) were reusable as-is; `RarPageSource` applies them
to `RarArchiveSession`'s physical-entry model directly, and also reuses
`ArchivePolicy.MAX_ENTRIES` (archive-entry-count ceiling) and `ArchivePolicy.MAX_IMAGE_BYTES`
(per-page-image size ceiling) rather than inventing new policy numbers. `ZipPageSource`/
`SeekableZip`/`ArchivePolicy` are therefore completely unmodified; CBZ's extraction mechanics
(true random access) and RAR's (cache-backed, non-seekable) remain fully separate — no generic
"ArchivePageSource" was introduced.

**ComicInfo.xml**: identified and parsed by reusing `EmbeddedMetadataReader.parse`/
`EmbeddedMetadataReader.comicInfo(document)` completely unchanged — no second XML parser.
Multiple-`ComicInfo.xml` policy mirrors CBZ's own (previously undefined, now made explicit):
the first physical entry whose name equals `"ComicInfo.xml"` case-insensitively, exactly as
`EmbeddedMetadataReader.comicInfo(zip)` already selects for a ZIP container — never a
subdirectory match, never a merge of several. `RarPageSource.comicInfo()` is exposed at the
container level only; it is never counted as a logical page (page filtering already excludes
it, since `isPageImage(".xml")` is false) and is not wired to `LibraryEntity`/import in any way.

**SUPERSEDED BY §29 (3E-C R1A remediation) — read §29 first.** The cache-bound description below
describes 3E-C's ORIGINAL, Codex-rejected architecture: each `RarExtractionCache` instance owned
its own independent 256MiB/64-entry budget (so the real bound was per-instance/per-namespace, not
global), on-disk files from an earlier process were never rediscovered on reopen despite the
"cross-reopen reuse" framing below, and the per-entry ceiling was a true post-hoc check only.
§29 records what Phase 3E-C R1A actually changed (a global `RarCacheCoordinator`, real lazy disk
discovery, and a real mid-stream native extraction ceiling) and is the current, accurate record;
this paragraph and the two below it are kept for historical/diff context only.

**Cache (`RarExtractionCache`)**: root is caller-supplied (`RarPageSource.open`'s `cacheRoot`
parameter); the actual cache directory is `cacheRoot/cbr/<UUID.nameUUIDFromBytes(sourceKeyOrRandom)>`
— the caller's `sourceKey` string is hashed into a deterministic, filesystem-safe directory
name rather than ever used as a raw path segment. When no `sourceKey` is supplied (3E-C has no
product/import integration to derive a stable one from yet), a fresh per-call random namespace
is used instead — correctness over cache persistence, exactly as required: a caller unable to
supply a reliable stable identity sacrifices cross-reopen reuse rather than risk serving stale
bytes under a reused key for a different archive. Entry key = physical ordinal; final filename
= `"$physicalIndex.bin"` (never a raw entry name). Max bytes: 256MiB (deliberately far larger
than `RenderMemoryPolicy.SESSION_BUDGET_BYTES`'s ~96MiB in-memory budget or
`ThumbnailLoader.DEFAULT_BUDGET_BYTES`'s 16MiB, since a `cacheDir`-backed, OS-reclaimable disk
artifact is not competing for the same scarce resource those two budgets protect; comfortably
holds several full-resolution comic pages at the 128MiB-per-entry ceiling below). Max entries:
64 (mirrors `ByteBudgetedLruCache.DEFAULT_MAX_ENTRIES`'s identical reasoning from 3B). Both are
constructor-injectable (`RarPageSource.open`'s `maxCacheBytes`/`maxCacheEntries` parameters) so
tests can force deterministic eviction with tiny limits. Per-entry byte ceiling reuses
`ArchivePolicy.MAX_IMAGE_BYTES` (128MiB), enforced by measuring the real extracted file size
*after* extraction completes — an honest, documented post-hoc check, not a true mid-stream
abort (`RarArchiveSession.extractEntry`/`NativeRarSession.extractEntry` is one blocking native
call with no interruption point; adding one would mean changing 3E-B's native C++, which this
checkpoint does not do). Eviction: deterministic LRU, bounded by both byte and entry budgets,
tie-broken by an injectable monotonic access counter (never wall-clock time); a slot with an
active, unreleased reference is never evicted even if that leaves the cache temporarily over
budget. Atomicity: extraction writes to a ShelfOS-generated temp sibling file, is verified
(native success, file present, within the per-entry ceiling), then atomically renamed
(`File.renameTo`, same-directory) to the final filename; any failure deletes the temp file and
never produces/promotes a final file. Concurrency: one lock object per cache key (never a
process-global archive lock) deduplicates concurrent `acquire` calls for the same key; 3E-C
does not yet wire `RarPageSource` into any shared `FixedReaderViewModel`-style render mutex
since it has no reader-UI caller yet — that discipline (mirroring how `ThumbnailLoader`'s
decode already routes through the same mutex/session CBZ full-page reads use) is deferred to
whichever later slice (3E-D) first calls this from the reader. Stale cleanup: a
`RarExtractionCache` constructor lazily deletes any leftover `*.tmp-*` file under its own
`root` only (never a wider scan), handling a process death mid-extraction from a previous run.
Namespace cleanup (deleting an entire old source-key directory once it is known stale) is
NOT implemented in this checkpoint — an honest limitation, not an oversight: nothing in 3E-C
yet has the product context to know when a source key is genuinely retired; a caller may
delete `cacheRoot/cbr/<namespace>` itself if it wishes.

**Error mapping (superseded by §30)**: the original checkpoint mapped `PROTECTED`,
`UNSUPPORTED_FORMAT`, and `CORRUPT` directly but collapsed `NOT_SEEKABLE`/`IO`/`NATIVE_INTERNAL`
to `UNREADABLE`. R1B corrects that boundary without UI strings or new public enums:
`NOT_SEEKABLE` -> `NEEDS_COPY`, while typed causes preserve the `IO`/`NATIVE_INTERNAL`
distinction through their canonical `UNREADABLE` problem.

**Tests** (all pass; see `docs/VALIDATION.md`'s "PHASE 3E-C" entry for exact commands/counts):
`RarExtractionCacheTest` (pure JVM, 10 tests) — first-extraction-once/cache-hit-zero,
bounds-then-full reuse, distinct/non-colliding keys, distinct namespaces, extraction-failure
leaves no partial file + retry succeeds, stale-temp cleanup on construction, deterministic
eviction by entry count and by byte budget, an actively-referenced entry is never evicted,
an oversized entry fails cleanly. `RarPageSourceTest` (pure JVM, 15 tests, against
`FakeRarArchiveSession`) — natural ordering matches CBZ, duplicate filenames at different
ordinals never collide, unsafe names never become pages, non-image entries excluded, empty
archive fails, first-materialization-once/cache-hit evidence, solid-style non-sequential access
pattern (later page -> same page again [0 more] -> different page [+1] -> first page again
[0 more]), `ComicInfo.xml` identified/parsed/excluded-from-pages, no-ComicInfo returns null,
every `NativeRarError` category's mapping, extraction-failure-then-retry-succeeds, post-close
throws, distinct source keys never collide even with identical ordinals, entry-count-over-limit
fails, tiny injectable cache limits trigger deterministic eviction. `RarPageSourceRenderInstrumentedTest`
(instrumented, 3 tests, against a local fake carrying real PNG bytes) — proves the actual
`ImagePageRenderer.bounds`/`ImagePageRenderer.render` pipeline (real `BitmapFactory` decode)
sees exactly one extraction across a bounds-then-full-decode pair, exactly two extractions
across two distinct pages (with a third render of page 0 staying a cache hit), and a corrupt
page never poisons its healthy sibling. `RarPageSourceRealSessionInstrumentedTest`
(instrumented, 1 test) — drives `NativeRarArchiveSession` over the REAL `NativeRarSession`
against a real vendored `.uu` fixture through a full open/enumerate/extract/close cycle and
asserts the source `.rar` file's SHA-256 hash is unchanged before and after.

**Build/validation**: `assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` all pass
with zero findings in the new files. No native rebuild was needed or triggered (confirmed:
`assembleDebug`'s CMake tasks ran only because of toolchain/ABI configuration, not because any
`.cpp`/`.h` source changed). APK fixture hygiene: a quick `unzip -l` check of the production
debug APK confirms no `.uu`, `.rar`, fixture, or owner-CBR content is present. Physical ARM
hardware validation was NOT performed (not required for this checkpoint, no native change).
Full JVM suite and full connected/instrumented regression were NOT run (reserved for Phase
3F), consistent with standing policy; only this checkpoint's own targeted tests were run.

**What this slice deliberately does NOT do** (explicit 3E-D/E or later scope): no
`PublicationFormat.CBR`, no Room/`LibraryEntity` change, no import routing/magic-byte
classification/`FixedReaderFactory` routing/library category integration, no reader UI/Compose
change, no localized user-facing error strings, no encrypted-archive password UI, no shared
render-mutex wiring to any reader ViewModel (deferred to whichever slice first calls
`RarPageSource` from a reader), no namespace/cross-session cache garbage collection, no CBR
acceptance-gate work from §17.

## 29. 3E-C R1A remediation record (global cache coordinator + hard extraction ceiling)

Codex High reviewed §28's 3E-C checkpoint and returned CHANGES REQUIRED on two HIGH findings; the
rest of §28's architecture (RarPageSource/PageSource contract, physical-ordinal identity, natural
sort, ComicInfo reuse, safe-name policy, native-engine reuse) was accepted as-is and is NOT
redescribed here.

**HIGH-1 (cache not globally bounded)**: the defect was real — each `RarExtractionCache` instance
owned its own slots/bytes/locks, so the 256MiB/64-entry budget applied per instance/per-namespace,
not to the whole `cacheDir/cbr` tree, and on-disk files from an earlier process were never
rediscovered on reopen. Fix: `core/files/RarCacheCoordinator.kt` is now the ONE process-wide
accounting/coordination owner for a given cache root (`context.cacheDir/cbr` in production),
returned by `RarCacheCoordinator.getInstance(root, maxBytes, maxEntries)` — a canonical-path-keyed
registry, never a single "current book" global, never one coarse lock held across I/O. Source
namespace (the caller's hashed `sourceKey`, or a random fallback — unchanged contract) is still
part of every cache entry's identity (`RarCacheCoordinator.Key(namespace, physicalIndex)`), it is
simply no longer a separate accounting domain: byte/entry budgets and LRU eviction now span every
namespace sharing a coordinator. On first `acquire()` (never in a constructor, never an always-on
startup scan), the coordinator lazily walks exactly one level of subdirectories under its root,
recognizes only the ShelfOS-generated `<namespace>/<index>.bin` layout it itself writes (deleting
anything unrecognized, any non-directory entry directly under root, and any symlink resolving
outside the root), and folds discovered finals into live accounting — this is what makes a reopen
of the same `sourceKey` genuinely reuse an earlier materialization (proven by
`RarCacheCoordinatorTest.aDiscoveredPriorFinalIsActuallyReusedOnRepeatRequestExtractionCountUnchanged`,
not merely documented as before). `RarExtractionCache` is now a thin per-namespace facade over the
shared coordinator (`RarExtractionCache(coordinator, namespace)`); `RarPageSource.open` obtains the
coordinator via `getInstance` and wraps it in a facade for its own namespace. Ephemeral/random
namespaces (no stable `sourceKey`) are accounted and evicted by the SAME global LRU as any other
namespace — chosen policy (b) from the remediation brief, not namespace-close cleanup — so they can
never sit on disk unaccounted, even though a long-lived ephemeral working set shares the same
budget as stable sources (an accepted, documented tradeoff, not an oversight). Locking: unchanged
in spirit from §28 — one lock per cache key (never one coarse global lock), a short `stateLock`
for accounting only, never held across extraction/decode/copy I/O; the coordinator always acquires
its own locks before calling into `extract()` (which may transitively acquire a `NativeRarSession`
instance's own per-session lock), never the reverse, so a coordinator/session deadlock is
structurally impossible, not merely untested. Tests: `RarCacheCoordinatorTest` (pure JVM, 8 tests)
— global byte limit across two namespaces, global entry limit across two namespaces (both: a third
namespace's insert evicts across namespace boundaries to stay within ONE global budget), a
reopened coordinator lazily discovers a prior final and folds it into accounting, that discovered
final is genuinely reused (extraction count unchanged), ephemeral-random-namespace payload stays
bounded by the global budget (both in-memory accounting and actual on-disk footprint), `getInstance`
returns the same shared instance for the same root, two independently constructed clients sharing
one root/namespace/ordinal coordinate into exactly one materialization under real concurrency (a
`CountDownLatch`-driven race, never a sleep), and an active lease in one client protects its entry
from eviction pressure triggered by a different client (proven against a newer-but-inactive
competing entry, so it is lease-aware, not just recency-LRU). `RarExtractionCacheTest`'s original
10 per-namespace tests were adapted to the new constructor shape and still pass unchanged in
substance.

**HIGH-2 (no hard extraction-time byte ceiling)**: the defect was real — a known declared size was
(and still is) prechecked before extraction, but an unknown/dishonestly-declared size let native
extraction write unboundedly before a post-hoc length check rejected it. Fix: a narrow, surgical
extension to the accepted 3E-B native API (never touching session ownership, FD ownership,
restart architecture, the handle model, synchronization, or RAR/RAR5 registration) —
`NativeRarSession.extractEntry(index, destinationFd, maxOutputBytes = Long.MAX_VALUE)` now threads
a `jlong maxOutputBytes` into `shelfos_rar_session_jni.cpp`'s native write loop, which tracks a
running `cumulative` byte count and checks `cumulative > maxOutputBytes - n` (subtraction, never
`cumulative + n`, so there is no addition-overflow case) BEFORE writing each 64KiB-buffered chunk,
aborting with a new `ErrorCode::TOO_LARGE`/`NativeRarError.TOO_LARGE` (ordinal 8) the moment a
chunk would exceed the ceiling — never after. The fixed 64KiB transfer buffer, EINTR/partial-write/
zero-byte-write handling are all unchanged; `maxOutputBytes <= 0` is rejected as `INVALID_ARGUMENT`
(a caller/input error, distinct from a legitimate size violation). `RarArchiveSession.extractEntry`
and `FakeRarArchiveSession` gained the same `maxBytes` parameter (default `Long.MAX_VALUE`, so every
pre-existing call site compiles unchanged); `RarPageSource.extract()` is the one production caller
that passes a real ceiling (`RarExtractionCache.MAX_ENTRY_BYTES`, i.e. `ArchivePolicy.MAX_IMAGE_BYTES`,
128MiB). `NativeRarError.TOO_LARGE` maps onto the EXISTING `PublicationProblem.TOO_LARGE` (the same
value CBZ's own oversized-page-image policy already reports) rather than collapsing into
`UNREADABLE` or inventing a new enum value — no new `PublicationExceptionDetail`, no localized
text. The old post-hoc temp-file-length check in `RarExtractionCache`/`RarCacheCoordinator` remains
as defense in depth; it should now be structurally unreachable in practice since the native layer
already enforces the same ceiling during streaming. Tests: `RarExtractionCacheCeilingTest` (pure
JVM, 3 tests, against `FakeRarArchiveSession`'s simulated streamed abort) — exactly-at-the-limit
succeeds, limit+1 is rejected with no leftover temp/final file and no accounting change, a
subsequent normal-sized entry in the same namespace still works after a rejection.
`RarPageSourceTest.declaredSizeOverTheLimitIsRejectedBeforeAnyExtractionIsAttempted` confirms the
cheap declared-size precheck still runs before any extraction is attempted (both checks coexist).
`LibarchiveRarNativeTest.extractEntryEnforcesHardByteCeilingDuringExtractionOnRealDevice` (real
on-device/JNI evidence, not a fake) drives the REAL native engine against the real rar4-plain
fixture's 21-byte `test.txt` entry with a tiny (not 128MiB) injectable limit: exactly-at-size
succeeds, size-minus-one aborts with `TOO_LARGE` and never writes more than the ceiling, and the
session remains usable for a subsequent legitimately-sized entry afterward. Administrator-retained
evidence now records `LibarchiveRarNativeTest` **13/13 PASS**, including this case;
`LibarchiveRarNativeLifecycleTest` also PASS, with no ShelfOS native crash signals. R1B does not
rerun either native class because it changes no native production code.

**Scope at R1A completion**: this pass intentionally deferred lease-release/pinning/deletion-
accounting mechanics, user-actionable error-semantic preservation, internal-visibility tightening,
and broader evidence reconciliation to Codex R1B. Those four findings are now closed by §30 without
reopening either HIGH fix. No `PublicationFormat.CBR`, product/import integration, or Phase 3E-D
work was added.

**Build/validation for this remediation**: `assembleDebug` (all three ABIs' native CMake
configure+build succeeded; stripped `libshelfos_cbr.so`: arm64-v8a ~678KB, armeabi-v7a ~406KB,
x86_64 ~665KB — no x86, no standalone `libarchive.so`), `assembleDebugAndroidTest`, and `lintDebug`
all pass with zero findings in the touched files. Targeted JVM unit tests (`RarExtractionCacheTest`
10, `RarCacheCoordinatorTest` 8, `RarExtractionCacheCeilingTest` 3, `RarPageSourceTest` 16 — all
pass, 0 failures) were run via `testDebugUnitTest` with explicit `--tests` filters; the full JVM
suite and full connected/instrumented suite were NOT run, consistent with standing policy. Physical
ARM hardware validation was NOT performed in R1A. Later administrator-retained on-device evidence,
recorded above and in `docs/VALIDATION.md`, closes the native-ceiling execution gap without changing
R1A's original environment history.

## 30. 3E-C R1B remediation record (cache lifecycle, typed errors, visibility, evidence)

**Cache lifecycle/accounting**: `cacheDir/cbr` remains one process-local, root-scoped
`RarCacheCoordinator` domain. Its production limits remain 256 MiB and 64 final entries across all
namespaces, with first-use lazy discovery/accounting of existing finals. Stable source namespaces
reuse finals across reopen; callers must change the source/revision key when source bytes change.
Missing stable keys still receive random per-open namespaces whose payload remains globally
accounted and eligible for the same deterministic LRU. Active leases may temporarily defer
eviction, but releasing the last lease now immediately reruns global eviction. A failed filesystem
deletion leaves its final in slot/byte accounting; eviction tries other eligible inactive
candidates once and stops deterministically if no deletion can restore the bound. Stream creation
after lease acquisition is exception-safe: failure releases the lease before propagating.

**Hard extraction ceiling retained**: the known declared size still receives the cheap early
precheck. `RarPageSource` passes `maxOutputBytes` to native extraction, where overflow-safe
cumulative tracking rejects a chunk before writing it if the 128 MiB ceiling would be exceeded.
The fixed 64 KiB transfer buffer is unchanged. `TOO_LARGE` remains typed, the partial temp is
removed, and no final cache payload is published. No native production code changed in R1B.

**Typed error boundary**: `PROTECTED`, `CORRUPT`, `UNSUPPORTED`, and `TOO_LARGE` keep their existing
`PublicationProblem` meanings. `NOT_SEEKABLE` now maps to existing `NEEDS_COPY`. `IO` and
`NATIVE_INTERNAL` use `UNREADABLE`, but the `PublicationException` retains the exact typed
`RarExtractionException` cause, preserving the distinction for 3E-D without raw codes, string
parsing, new public enums, or UI copy. Expected filesystem/PFD `IOException` maps into the same
typed `UNREADABLE` contract with its cause retained; `SecurityException` maps to
`PERMISSION_LOST`; `CancellationException` is rethrown unchanged.

**Visibility**: `RarArchiveSession`, `NativeRarArchiveSession`, `RarExtractionException`,
`RarExtractionCache`, `CachedExtraction`, and `RarCacheCoordinator` are `internal`; the
coordinator's `Key` is private. Existing `PageSource` architecture remains accessible as before.

**Evidence**: focused JVM tests pass: `RarCacheCoordinatorTest` 10/10,
`RarExtractionCacheTest` 11/11, `RarExtractionCacheCeilingTest` 3/3, and `RarPageSourceTest`
19/19. `RarPageSourceRenderInstrumentedTest` passes 3/3 on the API 24 emulator, followed by a
zero-match native/JNI crash scan. Administrator-retained on-device evidence records
`RarPageSourceRealSessionInstrumentedTest` 1/1, `LibarchiveRarNativeTest` 13/13 including the hard
ceiling case, and `LibarchiveRarNativeLifecycleTest` PASS, also with no ShelfOS native crash signal.
`assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` pass. Full JVM, full connected, and
physical ARM were not run for R1B.

**Status/scope**: 3E-A, 3E-B and 3E-C are complete/accepted (3E-C's final Codex R2 PASS). 3E-D is
implemented (see §31); 3E-E is not started; Phase 3 is not complete.

## 31. 3E-D implementation record (CBR product integration)

**Scope delivered**: `PublicationFormat.CBR` is added and wired end to end through the product, with
the accepted 3E-A/B/C native/cache architecture left byte-for-byte unchanged (no native file touched; no
change to `NativeRarSession`, `shelfos_rar_session_jni.cpp`, `RarCacheCoordinator`, or
`RarExtractionCache`).

- **Format/persistence**: `PublicationFormat.CBR` added to the domain enum
  (`domain/library/LibraryItem.kt`). Every exhaustive `when (format)`/`when (item.format)` over
  `PublicationFormat` was audited and updated: `core/reader/FixedReader.kt`'s `FixedReaderFactory.open`
  (new `RarPages` route), `core/reader/ReaderPreferences.kt`'s `capabilities()`/`spreadCapable()`, and
  `domain/importing/ImportPolicy.kt`'s `suggestedCategory()`. `format` is persisted as a plain Room
  `String` column (`LibraryEntity.format`, read back via `PublicationFormat.valueOf`); adding the enum
  value required no schema version bump and no migration, confirmed by a round-trip unit test
  (`ImportPolicyTest.publicationFormatRoundTripsThroughItsStringPersistenceWithoutASchemaChange`).
- **Magic detection**: `isRarMagic` (new, `core/files/RarArchiveSession.kt`) recognizes an exact
  RAR4 (`52 61 72 21 1A 07 00`) or RAR5 (`52 61 72 21 1A 07 01 00`) prefix, independent of filename —
  wired into `PublicationFiles.inspect`'s existing header-sniff `when` (alongside the existing `%PDF-`/
  `PK` checks), never a second/duplicate detector. A short/partial prefix (1-6 bytes) is deliberately
  rejected as import evidence (`RarMagicDetectionTest`, 6/6 JVM).
- **Import routing**: `PublicationFiles.inspectRar` opens the source through the new
  `openRarArchiveSession` helper (duplicates the descriptor's fd, exactly like `ArchivePolicy.open` does
  for ZIP — the original descriptor is never consumed) and reuses `RarPageSource.open`'s existing
  index/page-filter/safety policy unchanged (same `EMPTY_ARCHIVE`/`PROTECTED`/`TOO_LARGE`/etc as the real
  reading route) to validate the container and read `ComicInfo.xml` via the existing
  `EmbeddedMetadataReader`. No RAR-specific import subsystem, no source mutation, no repack; a rejected
  import leaves no partial private copy (same transaction discipline CBZ/EPUB/PDF already have).
  **Superseded by §32**: this originally imported `core.reader.RarPageSource` directly, a
  `core.files → core.reader → core.files` layering inversion — §32's HIGH-1 fix moved the container
  logic to `core.files`'s `RarContainer` so import-time inspection no longer depends on the reader layer.
- **Source key**: `rarCacheSourceKey(id, byteSize)` (new, `domain/library/LibraryItem.kt`) is
  `"$id:${byteSize ?: -1}"` — the item's own stable UUID plus its persisted byte size (the one
  revision-sensitive signal already available; ShelfOS has no finer per-provider last-modified signal
  today). Never derived from title/fileName. The SAME formula is used at import-time inspection
  (`PublicationFiles.inspectRar`) and at the real reading route (`FixedReaderFactory`'s `RarPages`), and
  both share the SAME cache root (`PublicationFiles.rarCacheRoot = context.cacheDir`), so
  `RarCacheCoordinator.getInstance` resolves to the same singleton coordinator in both places — a page
  materialized while inspecting a just-imported CBR can be reused the moment that title is first opened
  to read.
  **Superseded by §32**: Codex proved `byteSize` is NOT a trustworthy revision signal for a referenced
  external SAF source (it is stale, import-time-persisted metadata, never refreshed on reopen) — §32's
  HIGH-2 fix replaces this formula with a managed-vs-external-aware factory that returns `null`
  (ephemeral cache namespace) for any source without a `LibraryItem.managedPath`.
- **Metadata/ComicInfo**: `RarPageSource.comicInfo()` (unchanged, 3E-C) is reused as-is — the same
  `EmbeddedMetadataReader.comicInfo(Document)` mapping CBZ already uses (title/series/number, writer,
  `<Manga>YesAndRightToLeft</Manga>`). No second parser. A missing/unparsable `ComicInfo.xml` still
  imports successfully via filename fallback, same as CBZ. A RAR container with zero safe
  page-image-named entries fails `EMPTY_ARCHIVE`, proven with a real native-engine instrumented test
  (`CbrProductIntegrationTest`) against the same non-image vendored upstream RAR fixture 3E-B/C already
  use — never a silently "imported" zero-page comic.
- **Category/Manga/RTL, reader capabilities, spreads**: `suggestedCategory`, `capabilities`, and
  `spreadCapable` all treat `CBZ`/`CBR` identically (`format == PublicationFormat.CBZ || format ==
  PublicationFormat.CBR`) — no CBR-only branch anywhere. RTL/spread/fold behavior is entirely inherited
  through the shared `PageSource`/`ImagePageRenderer`/`SpreadModel`/`FixedReader` machinery; no
  CBR-specific reader, spread, or fold code exists.
- **Reader routing**: `core/reader/FixedReader.kt`'s new `RarPages` (private class, structurally
  identical to the existing `ArchivePages`) is the only place `PublicationFormat.CBR` reaches a real
  `FixedReader`: `openRarArchiveSession(descriptor)` → `RarPageSource.open(...)` → the same
  `ImagePageRenderer`/`PageSource` contract CBZ and PDF already share. Created once per
  `FixedReaderFactory.open` call (never recreated per page), closed exactly once by `RarPages.close()`;
  `FixedReaderViewModel` already closes its one session only when the ViewModel is cleared, so no change
  was needed there for correct lifecycle.
- **Error mapping**: unchanged from 3E-C's `NativeRarError.toPublicationProblem()` (relocated,
  unmodified in behavior, from `core.reader`'s `RarPageSource.kt` to `core.files`'s
  `RarArchiveSession.kt` — purely a layering fix so `core.files`'s new `openRarArchiveSession` can use it
  without a `core.files` → `core.reader` reverse dependency through the old location).
  `PublicationProblem.messageRes()`/`importMessageRes()` already map every `PublicationProblem`
  case-generically (not per-format), so CBR's `PROTECTED`/`CORRUPT`/`TOO_LARGE`/`NEEDS_COPY`/
  `PERMISSION_LOST`/`UNREADABLE` all resolve to the SAME existing localized strings CBZ/EPUB/PDF already
  use — no raw libarchive text, no new CBR-specific string, no password prompt. Only
  `problem_unsupported_format_message` (EN/ES/PT-BR) was updated to also mention CBR, since CBR is now a
  genuinely supported format.
- **Import picker/cover**: the existing picker already launches with `arrayOf("*/*")` (no MIME
  filtering) and format detection is magic-byte-driven — no picker change needed. ShelfOS Classic covers
  are locally generated artwork keyed off the item's own id (`LibraryItem.coverColor`/`coverMotif`,
  rendered by `PublicationCover`), never derived from a page image for ANY format — so "cover
  generation" required zero CBR-specific work; this is recorded here because the brief for this
  checkpoint asked for it explicitly, not because any change was made.

**What this slice deliberately does NOT do**: no CBR-specific reader/zoom/spread/fold/thumbnail code; no
native/C++ change; no Room migration; no RAR-writing/authoring tooling added to the repository; no
broad Phase 3 regression re-run; no push/PR/Codex invocation; 3E-E not started.

**Tests** (see `docs/VALIDATION.md`'s "PHASE 3E-D" entry for exact commands/counts): `ImportPolicyTest`
12/12 (4 new CBR cases), `RarMagicDetectionTest` 6/6 (new), `RarPageSourceTest` 19/19 (unchanged
behavior after the `toPublicationProblem()` relocation). `CbrProductIntegrationTest` (new,
instrumented, real native engine, real vendored RAR fixtures, API 24 emulator) 3/3:
zero-image-page CBR import fails `EMPTY_ARCHIVE` with source-hash equality preserved, `FixedReaderFactory`
routes CBR through the real native engine (distinguishable from the ZIP/PDF paths by the specific
problem reached), and both RAR4/RAR5 encrypted fixtures map to `PROTECTED` through the real import path.
`assembleDebug`, `assembleDebugAndroidTest`, and `lintDebug` all pass. The owner's real local CBR fixture
(not committed, not packaged) was used for a manual acceptance pass via a throwaway, never-committed
instrumented test that was deleted immediately after one local run: import succeeded (format CBR,
category Comics, ComicInfo-derived title), 151 pages, first/later-page render and a thumbnail render all
succeeded, page geometry resolved, a progress-locator round trip to the last page succeeded, and the
source file's SHA-256 was byte-identical before and after the full cycle. Full JVM, full connected
regression, and physical ARM hardware were not run for 3E-D (none were in scope).

## 32. 3E-D R1A implementation record (HIGH architecture remediation)

Codex High reviewed §31's broad CBR product integration and returned CHANGES REQUIRED on exactly two
HIGH findings; everything else in §31 was accepted and is unchanged. This record covers only those two
fixes. The accepted 3E-A/B/C native/cache architecture (`NativeRarSession`,
`shelfos_rar_session_jni.cpp`, `RarCacheCoordinator`, `RarExtractionCache`) remains byte-for-byte
unchanged — no native file touched.

**HIGH-1 (layering inversion, `core.files → core.reader`)**: §31's `PublicationFiles.inspectRar`
imported and constructed `core.reader.RarPageSource` directly for import-time validation/ComicInfo
inspection — but `RarPageSource` implements the reader-owned `PageSource` contract and depends on
`core.files`'s RAR session/cache machinery, so this was a real `core.files → core.reader → core.files`
cycle. Fix: a new lower-level abstraction, `RarContainer` (`core/files/RarContainer.kt`, `internal
class`), now owns every format-independent, reusable piece of RAR container knowledge — session
opening via `RarArchiveSession`, safe entry enumeration, `ArchivePolicy.MAX_ENTRIES` enforcement,
`ArchivePolicy.safeName` validation, image-only filtering (`isPageImage`), natural logical ordering
(`naturalCompare`) tie-broken by physical ordinal, `ComicInfo.xml` entry identification/extraction/
parsing (reusing `EmbeddedMetadataReader` unchanged), empty-archive validation, and cached page
materialization through `RarExtractionCache`/`RarCacheCoordinator`. `PublicationFiles.inspectRar` now
opens a `RarContainer` directly and calls only `RarContainer.comicInfo()` — it no longer imports
`core.reader` at all, and it never needs the `PageSource`/`ImagePageRenderer` surface merely to
validate an import. `core.reader.RarPageSource` became a thin thirty-line `PageSource` adapter over
`RarContainer`: `pageCount`/`openPage`/`close` all delegate straight through, and `comicInfo()` is kept
as a pass-through method only so existing reader-side call sites/tests reading a CBR's embedded
metadata through a `RarPageSource` instance keep working unchanged. Neither `PublicationFiles` nor
`RarPageSource` independently enumerates/sorts/filters the archive any more — `RarContainer.index()` is
the one place that logic lives. Verified: no `core.files` production file imports `core.reader` for the
CBR path (`grep -rn "import com.d4guilar.shelfos.core.reader" core/files/` returns nothing). One
unrelated pre-existing exception was found and deliberately left untouched (out of this fix's scope):
`core/designsystem/FontImportMessages.kt` imports `core.reader` — unconnected to CBR/RAR and not part of
this finding.

**HIGH-2 (unsafe external-source cache identity)**: §31's `rarCacheSourceKey(id, byteSize) =
"$id:${byteSize ?: -1}"` was proven unsafe for a referenced external SAF source: `LibraryItem.byteSize`
is import-time-persisted metadata that is never refreshed on reopen, so if the underlying provider
content changes behind a durable URI (same size OR different size), the persisted `byteSize` stays
stale and the old key could collide with an earlier materialization of different bytes — most
dangerously when the replacement happens to be the exact same size. Fix: `rarCacheSourceKey` now reads
`LibraryItem.managedPath` — the only managed-vs-external distinction ShelfOS's data model actually has
today (the same split `PublicationDetails` already surfaces to the user as "linked" vs "private copy").
A non-null `managedPath` means a ShelfOS-owned private copy, written once at import
(`PublicationFiles.copyToPrivateStorage`) and never mutated afterward — its bytes genuinely cannot
change under the same `id`, so a stable key derived from `id` alone (`"managed:$id"`) is safe and
preserves cross-reopen cache reuse. A null `managedPath` (a referenced external source) has no
revision signal that is actually re-queried at open time — only the stale persisted `byteSize`, exactly
the unsafe signal above — so the factory returns `null`, which `RarContainer.open` treats as "fresh
ephemeral/random namespace for this open, never reused." `byteSize`/`title`/`fileName` are never
consulted by the new factory. Import-time inspection (`PublicationFiles.inspectRar`) now always opens
with `sourceKey = null` regardless of managed/external status — it never needs cross-reopen reuse. The
random namespace prevents materialized import payload from being reused across reopen; closing that
validation's `RarContainer` releases its session but does not delete cache files. Retained payload stays
globally accounted within `RarCacheCoordinator`'s 256 MiB/64-entry bounds and ages out through ordinary
deterministic LRU eviction. Import therefore never needs to decide persistence for a not-yet-committed
item; only the reader-time route (`FixedReaderFactory`'s `RarPages`, via
`LibraryItem.rarCacheSourceKey()`) ever uses a persistent key, and only for a managed copy. No Room
schema change; no revision fingerprint added to persistence. Universal ephemeral use for external
sources remains architecturally safe, not a regression.

**Tests**: `ImportPolicyTest` gained
`managedSourcesGetAStablePersistentKeyDerivedOnlyFromIdNeverFromByteSizeOrDisplayName` and
`externalReferencedSourcesWithoutATrustworthyRefreshedRevisionAlwaysGetAnEphemeralNullKey` (replacing
the now-obsolete byteSize-sensitivity test), proving: a managed source's key depends only on `id`
(never byteSize/title/fileName); an external source's key is always `null`, including a simulated
same-size-after-replacement case (the old key's most dangerous blind spot) and a changed-size case.
`RarPageSourceTest` gained
`nullSourceKeyNeverReusesCachedBytesAcrossReopensEvenWithIdenticalPhysicalOrdinals`, an
integration-level proof against `FakeRarArchiveSession` that a `null` sourceKey never serves a stale
cached page across simulated reopens of the same physical ordinal, with same-size and changed-size
replacement content. `RarPageSourceTest` (20/20, including the new test), `ImportPolicyTest` (13/13),
and `RarMagicDetectionTest` (6/6) all pass unchanged otherwise.
`CbrProductIntegrationTest` (instrumented, real native engine, API 24 emulator) reran 3/3 PASS after
the refactor, with no native crash signal observed in logcat.

**Subsequent R1B closure**: archive-open failures now retain their typed `NativeRarError` through an
internal `RarOpenException` cause without changing `PublicationProblem` mapping, and stale source KDoc
now describes CBR's active shared-reader route truthfully.

## 2. Why Phase 3 is not green-field

CBZ import/opening, image-sequence (fixed-layout) reading, LTR/RTL defaults with
per-title override, PDF Manga RTL, Fit Page/Fit Width, pinch-zoom/pan, progress/resume,
process-recreation and resize continuity, keyboard/controller paging, immersive chrome,
Back/chrome semantics (ADR-0023), malformed-file resilience, and large-fixture stress
validation were already implemented and validated in Phase 1 and Phase 2 (2A–2D),
confirmed in `docs/VALIDATION.md`. Phase 3 is an **extension** of this working
foundation — it does not rebuild the reader, the archive layer, or the input system. The
job of Phase 3 is: (a) make Comics/Manga visually and navigationally first-class
(fidelity, thumbnails, spreads, foldables), and (b) close the one real format gap — CBR
— that the architecture has reserved a slot for since `docs/ARCHITECTURE.md` was
written, but never implemented.

## 3. Current implementation inventory (verified by code inspection)

**PRE-3A BASELINE — superseded by §22/§23 for the fixed-reader render path.** This section was
written before 3A's implementation landed and describes the codebase as it stood at that time
(a single flat `MAX_PAGE_PIXELS` ceiling, no `PageRenderRequest`/`PageSource` split). It is kept
as-is below for its still-accurate CBR/RAR gap analysis (archive layer, format enum, dependency
inventory), which 3A and its Codex R1 remediation did not touch. For the current
`core/reader/FixedReader.kt` contract (`PageRenderRequest`, `resolveRenderTarget`,
`RenderMemoryPolicy`, the `PageSource`/`ZipPageSource`/`ImagePageRenderer` split), see §22 (what
3A landed) and §23 (Codex R1's review and this remediation) instead of the "Fixed reader core"
bullet immediately below, which describes the pre-3A state only.

All paths under `app/src/`.

**Fixed reader core** — `core/reader/FixedReader.kt`:
- `FixedReaderFactory.open(item)` dispatches on `PublicationFormat`: `PDF -> PdfPages`,
  `CBZ -> ArchivePages`, `EPUB -> throws` (unsupported here). No CBR branch exists; there
  is no third case to add it to — a new format value and a new container adapter are
  both required.
- `PdfPages` rasterizes via `android.graphics.pdf.PdfRenderer` at
  `scale = MAX_PAGE_PIXELS / max(w,h)`, re-rendering on every `render()` call (no bitmap
  cache across zoom levels).
- `ArchivePages` (CBZ) decodes bounds-only first, rejects pages over 100MP, computes
  `inSampleSize` to keep the longest edge at or under `MAX_PAGE_PIXELS`, then fully
  decodes. Page ordering/selection is entirely delegated to `ArchivePolicy`.
- `MAX_PAGE_PIXELS = 2048` is the single shared sampling/rasterization ceiling for both
  PDF and CBZ, defined once in this file.
- No spread/two-page logic exists anywhere in the reader stack (confirmed by source
  search — zero hits for "spread"/"twoPage"). No thumbnail-generation code exists
  anywhere in `app/src` (zero hits for "thumbnail" outside docs).

**Geometry** — `core/reader/FixedReaderTransform.kt`: pure, format-agnostic functions
(`fixedReaderFittedContentSize`, `fixedReaderMaxPan`, `fixedReaderClampPan`,
`fixedReaderMaxPanY`, `fixedReaderVerticalScaleOverflow`). Explicitly documented as
taking no format input — any future CBR page reuses this file unchanged. Zoom today is a
**view-transform only**: pinch/double-tap scale a `graphicsLayer` on the *already
decoded* bitmap (capped at 2048px longest edge); it is not a re-decode at higher
resolution. 5x zoom magnifies a 2048px-capped bitmap, it does not fetch more source
resolution.

**Preferences/capabilities** — `core/reader/ReaderPreferences.kt`: `FitMode{PAGE,WIDTH}`,
`ReaderCapabilities(typography,fit,zoom,direction)`; PDF and CBZ currently share an
identical capability tuple (`typography=false, fit=true, zoom=true`) — a future CBR entry
plugs into the same `PDF, CBZ ->` branch with no new capability concept needed. Locator
format is versioned JSON (`{"version":1,"page":N}`), tolerant of damage/out-of-range.

**Archive/file layer**:
- `core/files/SeekableZip.kt` — minimal read-only ZIP reader over a seekable channel
  (positional reads), because SAF file descriptors can't be reopened by path for
  `java.util.zip.ZipFile`. Supports STORED/DEFLATE and ZIP64. Rejects encrypted entries.
  Pure ZIP format; **no RAR support of any kind**.
- `core/files/ArchivePolicy.kt` — the sole chokepoint for CBZ page enumeration:
  `MAX_ENTRIES=100_000`, `MAX_IMAGE_BYTES=128MB`, zip-slip guard, zip-bomb ratio check,
  natural sort (`page2` before `page10`) for page ordering. No `.cbr`/RAR awareness
  anywhere in this file or in `isPageImage`/`classifyArchive`.
- `core/files/PublicationFiles.kt` — `inspect()` classifies by magic bytes: `%PDF-` →
  PDF; `PK\x03\x04` → opens as ZIP, classifies EPUB vs CBZ by `mimetype`/
  `container.xml`. **The RAR magic byte (`Rar!\x1a\x07`) is never checked anywhere** — a
  `.cbr` file today falls through to `UNSUPPORTED_FORMAT`.
- `core/files/EmbeddedMetadata.kt` — `comicInfo()` parses `ComicInfo.xml`'s
  `<Manga>YesAndRightToLeft</Manga>` into `rightToLeftManga: Boolean`, which
  `ImportPolicy.suggestedCategory()` uses to default new CBZ imports into
  `MediaCategory.MANGA` vs `COMIC`. This is the one concrete, already-working
  RTL/Manga signal in the codebase.
- No standalone `LibrarySource` entity exists in code yet; persistent source ownership
  (ADR-0022, `docs/features/LIBRARY_SOURCES.md`) is a documentation/ADR concept only
  today. Current model is `LibraryItem.sourceUri` + `managedPath` + persisted URI grants.

**Reader presentation**:
- `feature/reader/FixedReaderViewModel.kt` — opens one `FixedReader` session per item,
  restores page via locator, persists position through a debounced writer, cancels
  in-flight render jobs on page change, recycles orphaned bitmaps.
- `feature/reader/FixedReaderScreen.kt` — single `Image` per page (`key(state.page)`),
  no spread UI. RTL affects only tap-zone mirroring, swipe direction, button layout, and
  `LocalLayoutDirection` — page identity/stored order is explicitly untouched per
  in-code comment. Pinch-zoom clamps `1f..5f` with pan clamped via the pure transform
  helpers. Back/Escape/gamepad-B follows ADR-0023 (reveal chrome, then exit).
- `core/input/ShelfCommand.kt` / `InputMapper` — semantic commands
  (`NEXT_PAGE`/`PREVIOUS_PAGE`/etc.) shared across touch/keyboard/gamepad, already
  RTL-aware (`InputMapper.command(stroke, context, rightToLeft)` swaps LEFT/RIGHT but not
  PAGE_UP/PAGE_DOWN or shoulder buttons). This is reusable unchanged for spreads/CBR.

**Domain/data** — `domain/library/LibraryItem.kt`:
- `enum class PublicationFormat { PDF, EPUB, CBZ }` — **confirmed: no CBR value exists
  anywhere in the codebase.** This is a hard gate: CBR cannot be imported, classified, or
  opened until this enum (and every exhaustive `when` over it) is extended.
- `readingDirection(category, override)`: `override ?: if (MANGA) RTL else LTR` —
  category-level default, per-title override already modeled.
- `domain/importing/ImportPolicy.kt` — `classifyArchive`, `isPageImage`,
  `suggestedCategory`, `existingSource`, `SourceMaintenance`. No RAR path.

**Dependencies** — `gradle/libs.versions.toml` has zero matches for rar/junrar/zip4j/
commons-compress. CBZ reading deliberately uses no third-party archive library (JDK
`Inflater` + ShelfOS's own `SeekableZip`). This is existing precedent that a CBR solution
should be evaluated against the same bar, not assumed to need a large dependency.

**Tests** (ran as factual verification, not part of this pass's deliverable):
`./gradlew.bat :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.FixedReaderTransformTest" --tests "com.d4guilar.shelfos.SeekableZipTest" -q`
— exit 0, no failures. Confirms `FixedReaderTransformTest` (354 lines, format-independence
explicitly asserted) and `SeekableZipTest` (in-memory ZIP fixtures, byte-exact streaming)
both currently pass. Also present and relevant: `FixedReaderTransformBoundsTest`,
`FixedReaderRecreationTest`, `MalformedFixedReaderResilienceTest`,
`NavigationSmokeTest`, `ReaderStateTest`, `SyntheticLargePdfAcceptanceTest`,
`SyntheticLoadAcceptanceTest` — all CBZ/PDF-only; no CBR test exists (expected, since no
CBR code exists); no dedicated Manga/RTL unit test exists (RTL is covered only indirectly
through `FixedReaderTransformBoundsTest`/manual RP5 validation per
`docs/PHASE_2D_IMPLEMENTATION_PLAN.md`).

## 4. Gap analysis

| Area | Status |
|---|---|
| CBZ open/read/decode | Implemented, validated (Phase 1/2) |
| Single-page fit/zoom/pan | Implemented, validated (Phase 2D.1) |
| Progress/resume/locator | Implemented, validated |
| Process recreation/resize continuity | Implemented, validated (Phase 2D.2) |
| Keyboard/controller paging, RTL-aware | Implemented, validated (Phase 2D.3) |
| Malformed CBZ/PDF resilience | Implemented, validated (Phase 2D.4) |
| Manga RTL default from `ComicInfo.xml` | Implemented |
| High-res re-decode on zoom | **Not implemented** — zoom is bitmap scaling only |
| Thumbnails (any kind) | **Not implemented** — no code exists |
| Spreads (AUTO/SINGLE/SPREAD) | **Implemented in 3C** (pending review) — see §25. This row described the pre-3C baseline (spec-only, "AUTO resolves to single page" per ADR-0017); superseded. |
| Foldable-aware two-page layout | **Not implemented** — `FOLDABLES.md` specifies intent, no comic-specific code |
| CBR/RAR format support | **Not implemented** — no enum value, no magic-byte detection, no container adapter, no dependency |
| `PublicationFormat` extensibility | Currently a 3-value enum with exhaustive `when`s in `FixedReaderFactory`, `ReaderCapabilities`, `ImportPolicy` — extending it is mechanical but touches multiple files |
| Persistent `LibrarySource` entity | Documented (ADR-0022) but not implemented — not blocking for Phase 3, noted for completeness |

## 5. CBR mandatory product decision

**Resolution of the documentation contradiction**: `docs/ROADMAP.md` currently states
CBR as a "high-priority" Phase 3 deliverable (line ~308) in the same file that lists bare
"CBR" in a "Future / Exploration... not a committed delivery phase" bucket (line ~576).
`docs/PRODUCT.md` §15 lists CBR among "long-term possibilities... timing not committed."
These are inconsistent with each other and with the owner's direct instruction for this
pass and the user's standing memory note (`cbr-support-priority.md`: "CBR is a must-have,
not a someday item").

**This plan's resolution, per explicit owner decision**: CBR is **mandatory Phase 3
scope** — not optional, not deferred, not exploration. The question this plan answers is
*how* to implement CBR compliantly, not *whether*. §12–13 schedule it as slice 3E, gated
only on the dependency/license decision in §6, not on product priority. §20 flags the
remaining open items (library selection, formal license sign-off) that still require
owner/admin confirmation before implementation begins. Docs updated in this pass (§ "Also
update" below) remove the stale "exploration/no committed phase" framing for CBR
specifically while leaving genuinely-unscheduled items (DOCX, OCR, etc.) untouched.

## 6. RAR/CBR technical investigation

RAR is architecturally harder than ZIP for ShelfOS's model in one specific way that must
be stated plainly: **RAR supports "solid" archives**, where decoding entry N can require
sequentially decoding entries 1..N-1 because they share a compression dictionary. CBZ's
`SeekableZip` exploits ZIP's independent-entry structure for true random-access,
on-demand, out-of-order page decode. RAR does not uniformly offer this. A RAR archive
built without solid compression (common for image collections, since solid compression
gives little benefit on already-compressed JPEGs and real-world comic scanners
frequently disable it) supports near-random access to individual entries. A solid RAR
archive does not, regardless of which library is used — this is a property of the
archive, not of the implementation. This is a factual format constraint, not a library
limitation, and must be documented as a possible user-facing limitation/slow-path
(e.g., "first open may take longer for solid archives") rather than something a library
choice can wholly solve.

**Candidates evaluated** (based on existing technical knowledge of the Java/Android RAR
ecosystem; none of this was verified live — see verification flags below):

1. **junrar** (pure Java/Kotlin RAR4/RAR5 reader, `com.github.junrar:junrar`).
   - Language: pure Java — easiest Android integration, no native/JNI, minimal APK size
     impact, straightforward testability.
   - License: derived from RARLAB's original C "unrar" source, ported to Java. Historical
     junrar distributions ship the "UnRAR License" (a non-OSI, field-of-use-restricted
     license: free for decompression-only use in end-user applications, but it forbids
     using the source to build a RAR-compatible *compressor*, imposes attribution/notice
     obligations, and is not a license ShelfOS's `docs/LICENSING.md` ("avoid GPL/AGPL
     casually," "every dependency reviewed individually") has an existing exception for.
     **This is exactly the licensing tension the owner's brief asked to be flagged
     explicitly** — not GPL, but a restrictive, non-standard field-of-use license that
     needs its own explicit review and sign-off, not a default "it's pure Java so it's
     fine" assumption.
   - Maintenance/current license text: **needs live verification** — I cannot confirm
     today's exact LICENSE file content, release cadence, or RAR5 completeness without
     network access. Flagged as an open question (§20).
   - Random access: junrar's historical API centers on sequential extraction per solid
     archive handling; genuinely random per-entry access for non-solid archives is
     usually fine, but needs to be verified against the current library version, not
     assumed.
   - Encrypted archives: historically unsupported or only partially supported for
     decompression; needs verification.

2. **SevenZipJBinding** (Java bindings to 7-Zip native code, including its RAR codec).
   - Native/JNI, ships prebuilt `.so` per ABI — meaningful APK size impact, native crash
     surface, more complex test strategy.
   - 7-Zip's own RAR-reading codec is itself derived from unrar source under the same
     restrictive UnRAR License family — bundling it does not avoid the licensing tension
     above, it just relocates it into a native library ShelfOS would not control the
     source review of as directly.
   - Maintenance status: needs live verification; this project is historically
     lower-velocity.
   - Rejected as a **worse** candidate than junrar for ShelfOS: same license family, plus
     native/JNI/ABI/size cost with no compensating random-access benefit.

3. **libarchive** (BSD-licensed C library, used by `bsdtar`) built for Android via NDK.
   - License: libarchive core is BSD-2/3-Clause. Critically, libarchive's RAR
     (`archive_read_support_format_rar`) and RAR5
     (`archive_read_support_format_rar5`) readers were implemented independently from
     the published format behavior, **not derived from RARLAB's unrar source** — this
     is the one candidate that does not inherit the UnRAR License's field-of-use
     restrictions. This materially changes the license-compatibility picture relative to
     options 1 and 2, consistent with `docs/LICENSING.md`'s stated preference to avoid
     non-standard restrictive licenses.
   - Cost: requires NDK/CMake build tooling ShelfOS does not currently have (the project
     is pure Kotlin/Gradle today), a JNI boundary ShelfOS would own and test itself, and
     per-ABI `.so` artifacts (`arm64-v8a`, `armeabi-v7a`, `x86_64`, optionally `x86`).
     Android App Bundle (if ShelfOS's release pipeline already uses AAB — needs
     verification, see §20) can deliver only the relevant ABI per device, bounding the
     per-install size impact; APK-only distribution would not.
   - Random access: libarchive's reader is fundamentally **sequential/streaming**, same
     as its ZIP reader — it does not provide ZIP-style central-directory random access
     even for RAR archives that are internally non-solid. Practical consequence: a
     `ComicContainer` RAR adapter built on libarchive would need to either (a) do a
     one-time sequential scan on open to build an in-memory index of entry offsets it can
     re-seek to (works for non-solid archives, cheap relative to full extraction), or (b)
     extract to a bounded app-private cache directory on first open and serve pages from
     that cache thereafter (works for all RAR archives including solid ones, at the cost
     of needing disk space and a cache-eviction policy). This does **not** modify or
     replace the user's original CBR file — the cache is an ephemeral derived artifact,
     consistent with AGENTS.md rule 4 and the "no conversion" rule in
     `docs/features/SERIES.md`.
   - Encrypted/password RAR: libarchive can detect a password-protected archive but
     cannot decrypt without the password; ShelfOS's correct behavior is an explicit,
     truthful error state ("password-protected RAR is not supported") reusing the
     existing malformed-archive error UI pattern (`MalformedFixedReaderResilienceTest`'s
     Retry/Back-to-library pattern), never silent failure or a misleading "corrupt file"
     message.
   - Maintenance: libarchive is a long-lived, widely-used project (used by `bsdtar`,
     macOS, FreeBSD, many package managers); still, exact current release/CVE status
     needs live verification (§20), as does the availability/maintenance of any existing
     "libarchive for Android" wrapper project versus building it in-house.

**Recommended direction**: pursue a **libarchive-based native adapter** as the
compliant path, specifically because it is the only evaluated candidate that avoids the
UnRAR License's field-of-use restrictions. This is a direction, not a final dependency
approval — adding it requires the explicit dependency-review step in §16/§20 and (new)
an ADR, since no existing ADR covers a RAR/archive library decision (next ADR number is
0024). If live verification during that review surfaces a materially better
BSD/MIT/Apache-licensed, independently-implemented RAR reader (pure Java or native) that
this pass could not find from static knowledge, it should be substituted — the
constraint is the license property (no UnRAR-License inheritance, no GPL/AGPL), not
loyalty to libarchive specifically. **CBR remains mandatory Phase 3 scope regardless of
which specific library is finally approved** — this investigation found a viable
compliant path (libarchive), so "no good library exists" is not the conclusion here.

**Decompression-safety**: whichever library is chosen, CBR entries must go through the
same `ArchivePolicy`-equivalent limits already enforced for CBZ (max entries, max
per-image bytes, zip-bomb-style expansion-ratio checks, path/name safety) — these limits
are format-agnostic and should be generalized rather than duplicated.

**SAF compatibility**: libarchive needs a readable byte stream/file descriptor, not
necessarily a real filesystem path — the existing `PublicationFiles.open()` pattern
(returns a `ParcelFileDescriptor`) should be reusable, but libarchive's C I/O layer would
need a custom read callback bound to that FD (or its backing channel), mirroring what
`SeekableZip` already does in pure Kotlin. This is a real integration task, not a trivial
wrapper.

## 7. Rendering / fidelity findings

Current behavior (verified, §3): both PDF and CBZ pages are decoded/rasterized once per
`render()` call at a resolution capped by `MAX_PAGE_PIXELS = 2048` (longest edge), using
`ARGB_8888` throughout. Zoom (pinch/double-tap) is a pure Compose `graphicsLayer` scale
of that already-decoded bitmap — it does not request a higher-resolution decode. For a
4K/6K comic scan, this means 2048px is the ceiling even at 5x pinch-zoom, so fine detail
beyond that ceiling cannot be recovered by zooming; the user sees a magnified but not
sharper image past that point.

**No defect is being claimed here** — `MAX_PAGE_PIXELS=2048` was a deliberate Phase 1/2
memory/performance bound, not a bug, and `docs/features/COMICS_MANGA.md` cites a real
field test (Samsung Tab A, CBZ, "sharp/clear/immersive") as evidence the existing
renderer is not inherently blurry at normal zoom levels. The gap is specifically: Phase 3
wants high-resolution zoom/re-render as declared scope (`docs/ROADMAP.md` Phase 3
deliverables), and today there is no re-decode-at-higher-resolution path for either
format.

**Recommended direction** (discovery only, no implementation this pass): extend the
page-render request from a single `index: Int` to a request carrying target
size/scale/quality (e.g., `render(index, targetLongEdgePx)`), with:
- A capped maximum re-decode resolution well above 2048 but still bounded (to be
  determined empirically against real device memory budgets — not invented here).
- Explicit non-caching of full-resolution zoomed bitmaps beyond the current page (only
  the currently-visible page/spread should ever hold a high-resolution bitmap;
  neighboring pages should hold low-resolution/thumbnail-grade bitmaps only).
- A distinct, deliberately low-resolution decode path for thumbnails (§8) that never
  shares a cache key with the full-resolution reading path, to avoid thumbnail browsing
  evicting or being confused with reading-resolution bitmaps.
- Cancellation-safety consistent with the existing `FixedReaderViewModel` pattern
  (in-flight render jobs already get cancelled on page change — a re-decode-on-zoom path
  must honor the same cancellation discipline, especially during rapid pinch gestures).
- CBZ and PDF should be able to share one render-request API shape even though their
  underlying decode primitives differ (`BitmapFactory` sampling vs. `PdfRenderer` scale)
  — this is already true of the existing `render(index)` contract and should be
  preserved, since a future CBR adapter needs to satisfy the same contract as CBZ.

This plan does **not** choose exact pixel ceilings or write a new renderer — that is
implementation work for the appropriate slice (§12), to be validated against real device
memory evidence (PSS), not assumed from this document.

## 8. Thumbnail architecture

No thumbnail code exists today (§3/§4). Discovery conclusions (plan, not implementation):

- **Generation strategy**: lazy, on-demand per visible-range in a thumbnail strip/grid —
  never eagerly decode hundreds of pages at reader resolution. Use a bounded decode
  (very small target size, e.g. a small fraction of `MAX_PAGE_PIXELS`) through the same
  container abstraction used for full-page decode, not a separate file-reading path.
- **Caching**: hybrid — an in-memory LRU bounded by both byte budget and entry count
  (the byte budget still matters because page dimensions vary wildly) for the
  currently-open book's visible/near-visible thumbnail range, plus an optional on-disk
  cache keyed by a stable fingerprint (e.g.
  `LibraryItem.id` + page index + a content fingerprint such as entry CRC/size, not a
  mutable path) so thumbnails survive process death without needing to be memory-resident
  across app restarts. Disk cache must be invalidated when the underlying source changes
  (new CRC/size, or the item's `available` flag flips) — never trust a stale on-disk
  thumbnail blindly.
- **Jump-to-page / current-page indicator**: thumbnail strip should be fully usable via
  touch, keyboard (arrow + Enter), and D-pad/gamepad using the existing `ShelfCommand`
  semantic layer (§3) — not a new input scheme.
- **RTL Manga**: thumbnail strip ordering follows the same `readingDirection` presentation
  rule as page turns — physical left-to-right layout of the strip mirrors for RTL titles,
  while underlying page index order is untouched (same invariant as page turning).
- **Corrupt-page handling**: a thumbnail decode failure for one page must render a
  placeholder (not crash, not block the strip), reusing the resilience pattern already
  validated for full-page decode (`MalformedFixedReaderResilienceTest`).
- **Cancellation**: scrolling the thumbnail strip quickly must cancel superseded decode
  requests, same discipline as full-page rendering.
- **Process recreation**: thumbnail strip scroll position and selection should be
  `rememberSaveable` or re-derived from the restored page locator, not separately
  persisted state.

## 9. Spread model

Discovery conclusion: adopt **AUTO / SINGLE / SPREAD** as the user-facing spread
preference (names may change during implementation if architecture suggests a cleaner
representation, but the three behaviors must be preserved):

- **AUTO**: on a window wide enough to show two pages at a legible size (a size-class /
  posture decision, never a device-model check — per `docs/design/FOLDABLES.md` and
  AGENTS.md rule 18), show spreads; otherwise single page. Interior pages pair
  (page 2+3, 4+5, ...), a cover/first page displays alone, and a final odd page displays
  alone. A page that already contains scanned double-page art, or a portrait/landscape
  page that doesn't pair cleanly, should not be forced into an artificial pairing — but
  **reliable automatic landscape-page detection is not assumed to exist** for a first
  implementation; a conservative heuristic (e.g., aspect-ratio threshold with manual
  per-page or per-title override) is the honest starting point, with full automatic
  detection deferred as an explicit non-goal if evidence during implementation shows it's
  unreliable.
- **SINGLE** / **SPREAD**: explicit user override, persisted per-title like other
  `ReaderPreferences`, taking priority over AUTO.
- **Manga RTL pairing**: physical left/right placement of a pair mirrors for RTL, but
  **page order, stored page identity, and locator values never change** — this is the
  non-negotiable invariant from the brief, and it is already structurally supported today
  because `readingDirection` is purely a presentation concern (§3) separate from the
  stored page array.
- **Resize/rotation/fold while reading, process recreation**: the current logical
  position (not "which half of a spread was visible") is the thing that must survive —
  i.e., the locator continues to be a single page index, and spread presentation is
  recomputed from window state + that index, not stored as its own persisted state. This
  reuses the Phase 2D.2 recreation-continuity pattern directly.
- **Fit Page / zoom in spread mode**: Fit Page's "page" becomes "the current visible pair"
  for layout-fitting purposes; zoom/pan math is already format- and content-size-agnostic
  (`FixedReaderTransform.kt`) and should extend to a pair's combined content size without
  new geometry primitives, in principle — to be confirmed during implementation.
- **Keyboard/controller Next/Previous**: must move by one logical page (not one spread)
  internally when adjacent to a boundary case (e.g. leaving a solo cover into a pair),
  reusing the existing semantic `ShelfCommand` layer unchanged.

## 10. RTL / Manga semantics

Already correctly modeled as a **presentation-only** concern: `MediaCategory.MANGA`
defaults to RTL, per-title override exists, `ComicInfo.xml`'s `<Manga>` field feeds
category suggestion at import, and `InputMapper` already swaps directional semantic
commands for RTL contexts (validated on physical RP5 hardware in Phase 2D.3). Phase 3
must extend this unchanged pattern to spreads (§9) and thumbnails (§8) rather than
introduce a parallel RTL concept. The one standing gap: there is no dedicated automated
unit test for `readingDirection()`/`ComicInfo.xml` RTL parsing (§3) — Phase 3 test
strategy (§14) should close this regardless of which slice lands first, since every
subsequent Comics/Manga feature depends on this being correct.

## 11. Adaptive / foldable behavior

`docs/design/FOLDABLES.md` already specifies the target behavior for comics specifically
(§6 of that doc): AUTO single/spread decisions on fold state, configurable gutter, no
artwork under an occluding hinge, RTL-aware spread placement, prefetching both visible
pages, fast posture-transition handling, and explicit prohibition of hard-coded
device-model checks (use window size class / `FoldingFeature` posture, per ADR-0010).
Phase 3's foldable scope is **bounded to what spreads need**: hinge-aware layout for an
already-open book, not the general adaptive-platform phase. Logical reading position and
spread preference must survive fold/unfold exactly as they survive rotation/resize/
recreation today (§9) — this is additive to the existing Phase 2D.2 continuity work, not
a new persistence mechanism. No physical foldable device is confirmed available for this
pass (see §15) — this is an honest gap to close with device availability before any
foldable-dependent slice is marked done, not something to claim validated now.

## 12. Proposed slice sequence

The administrator's candidate sequence (3A rendering/fidelity → 3B thumbnails → 3C
spreads/Manga pairing → 3D adaptive/foldable spreads → 3E native CBR → 3F resilience/
performance/physical acceptance) is **largely confirmed by this discovery**, with one
refinement:

**Refinement**: CBR's hardest constraint (§6) is that RAR cannot always offer ZIP-style
random access, and its dependency is native/JNI rather than pure Kotlin like the existing
CBZ path. If the page-render API and `ComicContainer`-style abstraction are designed in
3A without this constraint in mind, adding CBR later in 3E would likely force a rework of
that abstraction. Therefore: **3A must define the container/page-source abstraction
(not just the rendering ceiling) in a way that already accommodates a
sequential-with-index-cache or extract-to-cache access pattern, even though CBR itself
is not implemented until 3E.** This does not move CBR earlier in the delivery sequence
(the native/JNI build infrastructure and license sign-off are substantial, independent
work best kept isolated and last) — it only means 3A's abstraction design must not
quietly assume ZIP-style random access as a universal property of "a comic container."
With that adjustment, the administrator's ordering stands. CBR remains mandatory
regardless of its position (§5).

- **3A — Rendering/fidelity foundation.** Goal: extend page-render requests to carry
  target resolution/quality; define a container/page-source abstraction general enough
  for a future non-random-access (RAR) adapter; establish the thumbnail-vs-reading cache
  separation (§7/§8) without yet building the thumbnail UI. User-visible: comic pages can
  render at higher fidelity on zoom (ceiling to be determined empirically, not assumed).
  Key areas: `FixedReader.kt`, `ArchivePolicy.kt`, `ReaderPreferences.kt`'s capability
  model. No schema change expected. Tests: extend `FixedReaderTransformTest`-style pure
  tests plus a new render-request contract test; memory/PSS evidence for a large CBZ.
  Non-goals: thumbnails, spreads, CBR.

- **3B — Page thumbnails/navigation.** Goal: jump-to-page UI via a bounded, cancellable,
  lazily-decoded thumbnail strip (§8). Key areas: new `feature/reader` thumbnail
  composable, a thumbnail decode path built on 3A's container abstraction, possibly a
  small on-disk cache table (schema impact: likely yes — a thumbnail-cache table or
  directory convention; to be scoped precisely at implementation time, not here).
  Non-goals: spreads, CBR, foldable-specific thumbnail layout.

- **3C — Spreads + Manga pairing. IMPLEMENTED (pending review) — see §25 for what actually
  landed; this bullet is kept as the original planning record.** Goal: AUTO/SINGLE/SPREAD (§9) for CBZ/PDF comics and
  manga on existing (non-foldable) window sizes. Key areas: `FixedReaderScreen.kt`,
  `ReaderPreferences.kt` (new per-title spread preference), `FixedReaderTransform.kt`
  (pair-aware fitting, to be confirmed not require new primitives). Schema impact: one
  new persisted preference field. Non-goals: foldable hinge-specific behavior (3D), CBR
  (3E).

- **3D — Adaptive/foldable comic spreads.** Goal: hinge-aware spread placement, gutter,
  no-artwork-under-hinge, fold/unfold continuity (§11). Key areas: window
  posture/size-class consumption in `FixedReaderScreen.kt`, reuse of 3C's spread model.
  Requires a foldable device or emulator posture simulation for validation (§15).
  Non-goals: the general adaptive-platform phase beyond comics; CBR.

- **3E — Native CBR container support.** Goal: `.cbr` recognized and readable without
  user conversion, source file untouched, through the 3A container abstraction. Key
  areas: new `PublicationFormat.CBR` enum value (and every exhaustive `when` over it —
  `FixedReaderFactory`, `ImportPolicy.classifyArchive`, `ReaderCapabilities`), magic-byte
  detection in `PublicationFiles.inspect()`, a new native/JNI archive adapter (library
  TBD per §6/§20), build-system changes (NDK/CMake — explicitly a dependency-policy
  decision requiring separate authorization, §16). Schema impact: enum value addition
  (Room-safe if stored as a string/ordinal already handled defensively — to confirm at
  implementation time). This is very likely the largest single-slice engineering and
  review cost in Phase 3 and should get dedicated Codex review before merge. Non-goals:
  CB7, any other archive format; RAR-writing/repacking of any kind.

- **3F — Final resilience/performance/physical acceptance.** Goal: run the full
  acceptance matrix (§18) across formats, including CBR, on physical hardware (§15), plus
  stress/perf evidence (§16). This is a closure slice, not new product surface.

No slice except 3D/3F strictly requires a foldable device to validate its own core
behavior (3D's primary gate does — flagged honestly in §15, not glossed over).

## 13. Per-slice implementation contracts

Each slice below reuses the table skeleton; "TBD at implementation time" marks items this
discovery pass intentionally does not pre-decide.

**3A** — Schema: none expected. Dependencies: none. Migration: none. Source-file
ownership: unaffected (read-only rendering change). State/persistence: none new.
Accessibility: no regression; higher-fidelity rendering should not change
`contentDescription`/semantics. Keyboard/controller: unaffected. RTL: unaffected.
Adaptive/foldable: unaffected. Process-death/config: must keep existing recreation
continuity (Phase 2D.2) passing unchanged. Malformed-file: new render-request path must
degrade exactly like today's single-resolution path (Retry/Back). Performance/memory:
primary risk area — must produce PSS evidence before/after for a representative large
CBZ. Tests required: pure unit tests for new render-request math, instrumented
re-render-on-zoom test. Emulator validation: yes. Physical-device validation: recommended
(memory behavior differs from emulator). Codex review: recommended (architecture-shaping
slice). Non-goals: thumbnails, spreads, CBR. Done when: render requests can target a
resolution above 2048px with bounded memory, verified by test + PSS evidence, with no
regression in existing Phase 2 reader tests.

**3B** — Schema: likely a thumbnail-cache table/convention (TBD). Dependencies: none
expected (reuse existing bitmap decode). Migration: additive only if schema changes.
Source-file ownership: unaffected. State/persistence: thumbnail cache is derived/
disposable, never authoritative. Accessibility: thumbnail strip needs labeled,
focus-navigable items (page-number content descriptions). Keyboard/controller: full
jump-to-page via existing semantic commands. RTL: strip visual order mirrors for Manga.
Adaptive/foldable: basic responsiveness only, hinge-specific behavior deferred to 3D.
Process-death/config: strip position re-derivable from locator, not separately
persisted. Malformed-file: per-thumbnail placeholder on decode failure, no crash.
Performance/memory: bounded LRU, cancellation on fast scroll — primary risk area. Tests
required: cancellation test, corrupt-page placeholder test, large-CBZ thumbnail
scroll test. Emulator + physical validation both recommended. Codex review: recommended.
Non-goals: spreads, CBR. Done when: a 300+ page CBZ's thumbnail strip scrolls smoothly
with bounded memory and no crash on a corrupt page.

**3C** — IMPLEMENTED (pending review); see §25 for the actual landed record. Original contract
kept below as the planning record. Schema: one new persisted spread-preference field. Dependencies: none.
Migration: additive preference field, default AUTO. Source-file ownership: unaffected.
State/persistence: spread preference persists like other `ReaderPreferences`. Accessibility:
spread mode must not hide page-turn semantics from screen readers. Keyboard/controller:
Next/Previous must move one logical page at spread boundaries (§9). RTL: pairing mirrors
physically, page order/locator untouched (non-negotiable invariant). Adaptive/foldable:
AUTO threshold uses size class, not device model; hinge-specific refinement is 3D.
Process-death/config: locator-driven recompute, not separately persisted spread state.
Malformed-file: a corrupt page within a pair must not blank the whole pair silently —
needs an explicit per-page error state within the spread. Performance/memory: two pages
decoded/rendered concurrently — must not double the 3A memory ceiling carelessly. Tests
required: pairing-boundary unit tests (cover, odd final page, RTL mirroring,
locator-invariant assertion), instrumented resize single↔spread test. Emulator + physical
validation recommended. Codex review: recommended (RTL invariant is easy to violate
silently). Non-goals: foldable hinge behavior, CBR. Done when: Comics (LTR) and Manga
(RTL) both pair correctly including cover/odd-page cases, with the page-identity
invariant covered by an automated test.

**3D** — Schema: none expected beyond 3C's. Dependencies: none. Migration: none.
State/persistence: fold/unfold must preserve logical position (reuse 3C/Phase 2D.2
patterns, no new mechanism). Accessibility: hinge gutter must not break focus order.
Keyboard/controller: unaffected by fold state. RTL: gutter/pairing placement must stay
RTL-aware through fold transitions. Adaptive/foldable: this slice's entire purpose —
window posture/`FoldingFeature` consumption, no device-model branches. Process-death/
config: fold-triggered recreation must behave like any other recreation (Phase 2D.2).
Malformed-file: unaffected beyond 3C. Performance: prefetch of both visible pages must
respect 3A's memory ceiling. Tests required: posture-simulated instrumented test (emulator
fold simulation) at minimum; physical foldable test if hardware is available (§15) —
explicitly not fabricated if unavailable. Codex review: recommended. Non-goals: the
general adaptive-platform phase; CBR. Done when: spreads adapt correctly to simulated
fold/unfold with no artwork under the hinge and no lost position, with physical
validation performed if and when hardware is available (tracked as an open item
otherwise, not silently skipped).

**3E** — Schema: `PublicationFormat` enum addition (Room storage strategy TBD —
confirm ordinal vs. string storage at implementation time to assess migration risk).
Dependencies: one new native/JNI dependency (§6/§16) — requires explicit separate
authorization before addition, NDK/CMake build-system changes, new ABI artifacts.
Migration: enum addition should be additive/non-breaking if storage is string-based;
needs verification. Source-file ownership: a `.cbr` file must open read-only exactly like
`.cbz` — zero write/convert path, ever. State/persistence: same locator/progress model as
CBZ (no new persistence concept). Accessibility/keyboard/controller/RTL/adaptive: all
inherited unchanged from CBZ once the container adapter satisfies the shared
`ComicContainer` contract (§6, §12 refinement) — this is the entire point of the shared
abstraction. Process-death/config: identical to CBZ. Malformed-file: corrupt/truncated
RAR, non-image entries, and explicitly password-protected archives must all produce
truthful, non-crashing error states (§6) — password-protected is a distinct, explicit
"not supported" message, never misreported as generic corruption. Performance/memory:
solid-archive sequential-scan or extract-to-cache cost (§6) must be measured and bounded;
cache eviction policy required. Tests required: new archive-fixture tests mirroring
`SeekableZipTest`/`MalformedFixedReaderResilienceTest` patterns for RAR, including a
password-protected fixture and a solid-archive fixture. Emulator validation: yes.
Physical-device validation: yes (native/JNI code behaves differently across ABIs/devices
than emulator). Codex review: **required**, not just recommended — new native dependency,
new license surface, largest blast radius in Phase 3. Non-goals: CB7, RAR-writing,
converting CBR to any other format. Done when: the CBR acceptance gate (§17) passes in
full, including a real-world CBR fixture, with dependency license obligations documented
and satisfied.

**3F** — Schema/dependencies/migration: none new (closure slice). Covers: full
acceptance matrix (§18) across all formats including CBR, physical-device pass (§15),
performance/memory evidence consolidation (§16), final accessibility pass. Codex review:
recommended as a final gate. Non-goals: any new product surface. Done when: §18's matrix
is fully exercised and results (including any honest gaps, e.g. missing hardware) are
recorded truthfully.

## 14. Testing strategy

- Extend existing pure-function test patterns (`FixedReaderTransformTest`'s style) for
  every new pure geometry/pairing/render-request function introduced in 3A/3C.
- Extend instrumented patterns (`FixedReaderTransformBoundsTest`,
  `FixedReaderRecreationTest`, `MalformedFixedReaderResilienceTest`) to synthetic CBR
  fixtures once 3E lands — synthetic, non-copyrighted fixtures only, matching current
  practice.
- Add the standing gap noted in §10: a dedicated unit test for `readingDirection()` and
  `ComicInfo.xml` RTL parsing, independent of which slice lands it.
- No full Android validation matrix runs during planning (per this pass's instructions);
  each slice's own test gate is scoped in §13.
- Physical-device regression: every slice that touches input/RTL/foldable must re-run the
  relevant subset of Phase 2D's physical validation (RP5 controller, available
  tablet/phone), not just emulator tests, consistent with existing project practice.

## 15. Physical-device strategy

Per `docs/PHASE_2D_IMPLEMENTATION_PLAN.md`, physical validation previously used real RP5
controller hardware and at least one real Android device for process-death testing
(`adb shell am kill`). For Phase 3:
- RP5 controller: available, reuse for keyboard/controller spread and thumbnail
  navigation acceptance.
- A representative phone and tablet window-size: availability for Phase 3 specifically
  is **not confirmed by this planning pass** — this document does not assume hardware
  that hasn't been verified as on hand. Flagged as an open item (§20).
- Foldable device: **not confirmed available.** 3D's foldable-specific acceptance should
  use Android Studio's foldable emulator posture simulation as the primary gate, with
  physical foldable validation performed opportunistically if/when hardware becomes
  available — this plan does not claim foldable hardware validation will happen on a
  schedule it can't back up.
- TalkBack: Phase 2D already documented TalkBack as unavailable on both test devices at
  that time ("a documented boundary, not a failure") — Phase 3 should re-check this
  before claiming any accessibility acceptance criterion closed.

## 16. Performance / memory strategy

- PSS evidence (as already practiced in `SyntheticLargePdfAcceptanceTest`) should be
  captured before/after 3A's re-decode-on-zoom change, for a large synthetic CBZ fixture.
- Thumbnail caching (3B) must have an explicit byte-budget LRU ceiling, never an
  unbounded cache — consistent with `ArchivePolicy`'s existing posture of explicit
  numeric ceilings everywhere.
- Spread rendering (3C/3D) roughly doubles concurrent decoded-page memory; 3A's ceiling
  work should account for this before 3C lands, not be revisited reactively.
- CBR (3E) introduces a new memory risk class: solid-archive sequential decode or
  extract-to-cache (§6) must have its own bounded cache/eviction policy, measured with
  PSS on a real large CBR fixture, and must not be allowed to extract unboundedly to
  disk without a size cap and cleanup-on-close/LRU policy.
- Rapid-navigation cancellation (already validated for CBZ/PDF in Phase 2D.4) must be
  re-validated for every new decode path (thumbnails, re-decode-on-zoom, CBR) rather than
  assumed to transfer automatically.

## 17. CBR acceptance gate

CBR implementation (3E) is accepted only when all of the following hold, validated with
real (non-synthetic where feasible) CBR fixtures in addition to synthetic ones used for
license-safe automated tests:

- `.cbr` files are recognized by magic byte, not merely by file extension.
- A user can import a real `.cbr` file with zero conversion/renaming/repackaging step.
- The original `.cbr` file is never modified, moved into a converted form, or deleted as
  a side effect of reading.
- Archive entries are enumerated safely (entry-count/size/expansion-ratio limits
  equivalent to `ArchivePolicy`'s existing CBZ limits).
- Natural page ordering matches CBZ's ordering semantics for equivalent filename patterns.
- All image formats already supported for CBZ pages remain usable inside CBR.
- Pages are decoded on demand, not wholesale-extracted eagerly — with the documented,
  honest exception of the solid-archive / extract-to-bounded-cache fallback (§6), which
  must itself be bounded and evicted, not an unbounded silent extraction.
- Progress/resume works identically to CBZ.
- LTR Comic and RTL Manga CBR titles both behave identically to their CBZ equivalents,
  including spreads once 3C/3D have landed.
- Fit Page/Fit Width/zoom work via the same shared reader semantics as CBZ — no
  CBR-specific reader UI.
- Thumbnails work for CBR once 3B has landed, through the same abstraction.
- Malformed/corrupt/truncated CBR archives fail gracefully (Retry/Back pattern), never
  crash.
- Password-protected/encrypted RAR archives produce an explicit, truthful "not
  supported" message — never a misleading generic error, never a silent partial read.
- Process restoration/reopen behaves identically to CBZ (Phase 2D.2 pattern).
- Controller/keyboard navigation works identically to CBZ.
- Dependency license obligations (attribution/notice requirements of whichever library is
  finally approved per §6/§20) are documented in `docs/DEPENDENCIES.md` and satisfied in
  the shipped app (e.g., any required notice screen/file).
- Full build/test/license gate passes — this bullet describes the bar for when
  implementation actually happens; it is not satisfied or claimed satisfied by this
  planning pass.

## 18. Final Phase 3 acceptance matrix

To be exercised at 3F, covering at minimum:

- **Formats**: CBZ, CBR, Comic PDF, Manga CBZ, Manga CBR, Manga PDF.
- **Source preservation**: unchanged original for every format, no conversion required,
  no destructive mutation, verified by hash/byte comparison before/after a read session.
- **Reading**: single-page, AUTO spread, forced single, forced spread, Fit Page, Fit
  Width, zoom, high-res fidelity (post-3A), progress, resume — across all formats above.
- **Navigation**: thumbnails, jump-to-page, touch, keyboard, D-pad, RTL — across all
  formats above.
- **State**: recreation, rotation, resize, real process death (`adb shell am kill`, not
  `am force-stop`, per existing Phase 2D practice), reopen, preference restoration.
- **Spreads**: LTR, RTL, first page, odd final page, portrait pair, wide-page behavior,
  resize single↔spread, foldable case if hardware/emulator posture is available at the
  time.
- **Resilience**: corrupt CBZ, corrupt CBR, corrupt page image, non-image entry, empty
  archive, huge-dimension page, unsupported-encrypted RAR, rapid-navigation
  cancellation, unavailable/disconnected source.
- **Performance**: long CBZ/CBR, rapid page turns, thumbnail browsing, spread rendering,
  zoom re-render, memory/PSS evidence, no unbounded cache for any of the above.
- **Accessibility**: semantics, page-nav labels, thumbnail labels, focus order, chrome
  discoverability — re-verified truthfully against whatever TalkBack availability exists
  at that time (§15).
- **Physical devices**: RP5 controller acceptance; any tablet/phone/foldable hardware
  actually available at validation time, reported truthfully — this matrix is not
  considered satisfied by emulator-only evidence where a physical-device row is listed,
  and any row that could not be physically validated must be recorded as such, not
  silently marked done.

## 19. Explicit non-goals (Phase 3)

Series/Omnibus implementation (DB tables, UI, automatic grouping, multi-volume import,
virtual transitions, volume-to-volume reader coordination) — explicitly out of Phase 3
per the owner's brief; Phase 3 only verifies it doesn't foreclose future Series work
(each CBZ/CBR volume stays an independent `LibraryItem`/source file; both formats share
the `ImageSequenceReaderEngine` contract so a future Series can mix them, per
`docs/features/SERIES.md`'s mixed-format section — already architecturally true today).
Also out of scope, per the brief: separate reader stacks for CBZ vs CBR, extracting
entire archives by default, filesystem-path assumptions that break SAF, static global
bitmap caches, loading entire books into memory, format conversion as an implementation
shortcut, page identity tied to screen/spread position, hard-coded device-model logic,
coupling UI directly to archive implementation, guided panels, automatic panel detection,
AI image enhancement, super-resolution, metadata provider work, OPDS/Calibre, cloud/sync,
Notes/Highlights, Adapted PDF, theme work, billing, CB7, iOS, OCR.

## 20. Open questions requiring owner/admin decision

1. **RAR library final approval**: this pass recommends a libarchive-based native
   adapter (§6) as the compliant direction, but does not approve adding it — that
   requires a dedicated dependency-review pass (§16 policy) and likely a new ADR
   (0024) once a specific library/version is chosen. Live verification of current
   license text, maintenance activity, and RAR5 completeness is required before that
   approval, and could not be performed in this offline planning pass.
2. **Solid-archive behavior**: should a first CBR implementation support solid RAR
   archives via extract-to-bounded-cache (more compatible, more disk/memory cost), or
   explicitly decline solid archives in v1 with a truthful "not supported" message (less
   compatible, simpler/safer first slice)? This plan does not decide this — it is a
   real product trade-off for the owner.
3. **AAB/per-ABI delivery**: does ShelfOS's release pipeline already use Android App
   Bundle (needed to bound CBR's native-library size impact per install)? Not verified in
   this pass.
4. **Physical device inventory for Phase 3**: what tablet/foldable/additional phone
   hardware is actually available for 3D/3F validation? §15 does not assume hardware that
   hasn't been confirmed.
5. **Automatic landscape-page detection** (§9): is a heuristic acceptable for a first
   spread implementation, or should 3C ship manual-override-only until a more reliable
   signal is found? This plan defaults to "heuristic + manual override" but flags it as a
   product call.
6. **Doc reconciliation depth**: this pass updated `docs/ROADMAP.md`, `docs/PRODUCT.md`,
   and `AGENTS.md` minimally to remove the direct self-contradiction (§5); it left
   `docs/ARCHITECTURE.md`, `docs/features/COMICS_MANGA.md`, and
   `docs/features/READER.md` largely as-is because their existing "future CBR" framing
   is still literally true (not yet implemented) and did not directly contradict
   mandatory-scope status the way ROADMAP.md's two sections contradicted each other. If
   the admin wants stronger "mandatory" language in those files too, that's a quick
   follow-up, not a blocker.

## 21. Recommended first implementation slice

**3A (rendering/fidelity foundation)**, specifically narrowed to: (a) define the
render-request contract (target resolution, not just index) and (b) define the
container/page-source abstraction with the RAR-compatibility refinement from §12 — before
writing any new UI (thumbnails, spreads) or touching the dependency graph (CBR). This is
the lowest-risk, most foundational slice: it has no schema/dependency impact, builds
directly on already-validated Phase 2 code, and its abstraction decisions are the ones
most expensive to redo later if skipped or rushed. CBR (3E) should not be started before
3A lands, both because of the shared-abstraction reason above and because its dependency
review (§20.1) will independently take real calendar time regardless of engineering
readiness.
