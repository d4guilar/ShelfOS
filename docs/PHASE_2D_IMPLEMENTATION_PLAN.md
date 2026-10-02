# Phase 2D implementation plan: reader continuity, adaptive/accessibility/performance closure

Status: **2D.1 (fixed-reader transform/bounds correctness) IMPLEMENTED,
pending independent review and owner RP5 physical acceptance** (2026-10-02),
on `phase-2/reader-closure` (base `main` at `0d8a6a0`, the commit that merged
Phase 2C's closed investigation via PR #16). 2D.2-2D.4 remain planning-only;
no other 2D production code exists yet. See §21 for the 2D.1 implementation
record. This document is the canonical Phase 2D planning location referenced
by [`PHASE_2_PLAN.md`](PHASE_2_PLAN.md)'s §3 2D section.

This pass read, in order: `AGENTS.md`, `docs/PHASE_2_PLAN.md`,
`docs/ROADMAP.md`, `docs/ARCHITECTURE.md`, `docs/features/READER.md`,
`docs/features/PDF_INGESTION.md`, `docs/features/COMICS_MANGA.md`,
`docs/design/READER_UX.md`, `docs/design/INPUT_SYSTEM.md`,
`docs/VALIDATION.md` (for prior-phase evidence format),
`docs/PHASE_2C_IMPLEMENTATION_PLAN.md` (the just-closed investigation), and
ADR-0010 (adaptive foldables), ADR-0017 (Phase 1 reading scope, which also
sets the AUTO-spread interim policy), and ADR-0023 (reader chrome/Back
semantics). This pass does not reopen 2C's closed PDF-rendering conclusion
(§22c of that document) — it is treated as settled evidence throughout.

## 1. Current reader architecture (as verified by reading the source)

### 1.1 Shared across EPUB and fixed-page (PDF/CBZ)

- `core.input` (`ShelfCommand`, `InputMapper`, `InputModality`, `InputHints`):
  both readers resolve keyboard/gamepad key events to the same semantic
  commands (`NEXT_PAGE`/`PREVIOUS_PAGE`/`OPEN_MENU`/`BACK`) and track/display
  input-modality hints identically. Touch itself remains screen-specific
  `pointerInput`/Readium-gesture handling in both readers — not yet routed
  through `ShelfCommand` (a standing, previously-deferred gap, §6 of
  `INPUT_SYSTEM.md`).
- Chrome/Back semantics (ADR-0023): both readers implement "Back reveals
  hidden chrome first, a second Back exits" via the same pattern — a local
  `controls` `rememberSaveable` boolean, a `backPress()`/equivalent function
  checked by both `BackHandler` and the `ShelfCommand.BACK` key path.
  `FixedReaderScreen.kt` and `EpubActivity.kt` still duplicate this logic
  (identified as a refactor target in 2A, still not extracted — unchanged by
  this pass).
- `ReaderAppearance` composable: the same typography/palette/fit-mode sheet
  is shared by both readers via `capabilities(item.format)`, with per-format
  controls gated by what that format actually supports.
- `ReaderPreferences`/`resolveReaderPreferences`: per-title preference over
  global preference precedence, shared persistence shape (JSON blob columns
  on `LibraryEntity`/global preference row), shared `applyAppearance`/
  `resetAppearance` plumbing pattern in both ViewModels.
- Position persistence: both use a `PositionWriter<T>`-shaped
  conflated/debounced writer into `ReadingEntity` — `FixedReaderViewModel`
  keyed on page index, `EpubReaderViewModel`/`EpubSurface` keyed on
  `Locator.toJSON()` + computed progress.
- Error surfacing: both route engine exceptions through a shared
  `PublicationException`/`PublicationProblem`/`UiMessage.readerMessage()`
  pipeline (`core.designsystem`), so localized, typed error messages reach
  both readers' UI without ad hoc strings.
- Accessibility: both expose a `stateDescription` ("Controls shown"/"Controls
  hidden") and an `onClick` reveal action on the page surface, only while
  chrome is hidden (2A).

### 1.2 EPUB-only

- `EpubActivity` (a real `Activity`, not a Compose-only screen) hosts a
  Readium `EpubNavigatorFragment` via `FragmentManager`; the navigator's own
  fragment state survives rotation/process recreation (`FragmentManager`
  restores it), which is how EPUB's current locator/page survives recreation
  without ShelfOS re-deriving it from scratch.
  `EpubActivity.kt`'s own comment (lines 68–69) documents this explicitly.
- Extensive `rememberSaveable` usage for Compose-level UI state: `controls`,
  `appearance`, `chapters`, `bookmarks`, `search`, `searchQuery`,
  `fontImportError` (with a custom `Saver`), `modality` — all survive
  configuration change and process recreation (verified by reading
  `EpubActivity.kt` directly, not inferred).
- Chapter navigation, search (`SearchService`/`SearchIterator` lifecycle,
  2B.3), bookmarks (Room `bookmark` table, 2B.2), managed fonts (2B.4) are
  all EPUB-only; none of this exists for PDF/CBZ.
- `EpubPreferences.columnCount` is hard-forced to `ColumnCount.ONE`
  (single-column, phone-first) — Readium supports `TWO`/`AUTO`, but exposing
  this was explicitly deferred to 2D by the 2B.1 discovery pass (see §8
  below; this is a *different* feature from comic page-spreads).

### 1.3 Fixed-page (PDF/CBZ)-only

- `FixedReaderScreen.kt` + `FixedReaderViewModel.kt` + `core/reader/
  FixedReader.kt` (`PdfPages`, `ArchivePages`): a single Compose screen (no
  separate `Activity`), one `Bitmap` held in state at a time, rendered via
  `PdfPages.render()` (`android.graphics.pdf.PdfRenderer`,
  `MAX_PAGE_PIXELS = 2048` fixed longest-edge, confirmed unchanged by 2C) or
  `ArchivePages` (CBZ image decode). `FixedReaderViewModel` serializes all
  open/render work through one `Mutex`, cancels the in-flight `rendering`
  `Job` before starting a new one, and explicitly recycles a bitmap that
  loses a race (`CancellationException` / later error path) — this is the
  concurrency design 2C already investigated and found adequate (§9/§22a of
  `PHASE_2C_IMPLEMENTATION_PLAN.md`); this pass re-read the same code and
  confirms it is unchanged and still structurally sound.
- Zoom/pan/fit state (`scale`, `panX`, `panY`) lives entirely in
  `FixedReaderScreen`'s Compose state, keyed `remember(state.page)` — **not**
  `rememberSaveable`. This is the single most load-bearing fact for this
  pass's two main findings (§2 and §5 below): it resets on every page change
  by construction, and it does **not** survive configuration change/process
  recreation at all, because `remember` (unlike `rememberSaveable`) is not
  persisted across recreation.
- RTL/LTR: `readingDirection(category, preference)` resolves per-title
  override over category default (Manga → RTL, Comic/Book → LTR), used both
  for touch-zone semantics (which edge turns forward) and for which control
  (Previous/Next) is visually on which side.
- Fit mode: `FitMode.WIDTH` applies `Modifier.fillMaxWidth().aspectRatio(...)`
  inside a `verticalScroll` `Box`; any other fit mode uses
  `Modifier.fillMaxSize()` with `ContentScale.Fit` (letterboxed). Both paths
  then apply the same `graphicsLayer { scaleX; scaleY; translationX;
  translationY }` on top.

## 2. PAN/ZOOM BOUNDS — root cause found by code inspection

**Reproduced: by code/math trace, not by a live on-device gesture session in
this pass** — see the honesty note at the end of this section. The math is
unambiguous enough that a live confirmation would only corroborate, not
change, the root-cause finding below.

### 2.1 Exact location

`FixedReaderScreen.kt`, the second `pointerInput(state.page, rtl)` block
(lines ~152–178), inside the pinch/pan gesture loop:

```kotlin
if (event.changes.count { it.pressed } > 1 || scale > 1f) {
    transformed = true
    scale = (scale * zoom).coerceIn(1f, 5f)
    panX = (panX + pan.x).coerceIn(-size.width * scale, size.width * scale)
    panY = (panY + pan.y).coerceIn(-size.height * scale, size.height * scale)
    event.changes.forEach { it.consume() }
}
```

`size` here is the `pointerInput` scope's `size: IntSize` — the **Box's own
layout size** (the full reader-page viewport, `Modifier.weight(1f)
.fillMaxWidth().clipToBounds()`), not the rendered bitmap's displayed
footprint after `ContentScale.Fit`/letterboxing, and not the viewport minus
that footprint.

### 2.2 Why this produces the reported defect

1. **Wrong magnitude.** The correct bound for translation, per standard
   "content scaled inside a viewport" math, is
   `maxPan = max(0, (scaledContentSize - viewportSize) / 2)` — proportional
   to how much *larger than the viewport* the scaled content is. The current
   code instead bounds pan to `viewportSize * scale` — proportional to the
   *viewport's own size times scale*, with no subtraction of the viewport
   itself and no reference to the actual rendered content size at all. For
   any `scale > 1`, `viewportSize * scale` is always larger — often far
   larger — than any correct `maxPan`, so panning is permitted well past the
   content's real edge into empty space.
2. **Ignores letterboxing entirely.** When `ContentScale.Fit` letterboxes a
   page inside the Box (any page whose aspect ratio doesn't match the
   viewport — e.g. a portrait PDF page in a landscape viewport, or vice
   versa, confirmed as the Fit Page path, line 187's `else` branch), the
   *visible* page content is smaller than the Box the `graphicsLayer`
   transform is actually applied to. The transform scales/translates the
   whole Box uniformly, including its letterbox margins, but the pan bound
   never accounts for where the real content edge sits inside that Box — it
   only knows the Box's own full size. This is exactly the "excessive gray
   letterbox/margin space" the owner observed in 2C's §22b log.
3. **No re-clamp on zoom decrease.** The `coerceIn` call does re-run on every
   pointer-event frame, and does use the *current* `scale` as the bound's
   scale factor, so there is a re-clamp attempt on zoom-out — but because the
   bound itself is wrong (too generous per point 1), the re-clamp doesn't
   produce a correct result; it just produces a different, still-too-loose
   bound. The double-tap zoom-toggle and the chrome "Zoom in"/"Reset zoom"
   button (lines 125, 144) both reset `panX`/`panY` to `0f` directly rather
   than going through this clamp, so those two paths don't exhibit the bug by
   themselves — only continuous pinch/pan does.
4. **Fit Width's `verticalScroll` interaction (a second, smaller
   finding).** In `FitMode.WIDTH`, the `Image` sits inside a `Box` with
   `Modifier.fillMaxWidth().verticalScroll(rememberScrollState())`
   *and* the same `graphicsLayer` translation. Vertical scroll position and
   `panY` are two independent, uncoordinated mechanisms for moving the page
   vertically; this pass did not fully characterize their interaction (no
   live repro), but flags it as a related area the eventual fix must examine
   rather than assume away, since clamping `panY` alone without considering
   the scroll container's own offset could still produce inconsistent
   behavior in Fit Width specifically.

### 2.3 Affected formats/modes

By code inspection, the gesture-handling block is identical for **PDF and
CBZ** (`FixedReaderScreen` is the single shared fixed-page screen for both;
no per-format branch exists in the pinch/pan code) and applies in **both Fit
Page and Fit Width** (the bound calculation doesn't look at `fit` at all — it
is wrong the same way regardless of fit mode, though Fit Page's letterboxing
makes the visible symptom more obvious since Fit Width's content more often
fills the viewport on at least one axis). It applies in both RTL and LTR
reading (direction only affects which edge turns the page and control
placement, not the gesture/transform code). **EPUB does not share this code
path at all** — EPUB has no zoom/pan gesture handling of its own; Readium's
navigator handles its own rendering, and `EpubPreferences` has no pan/zoom
concept. Per this task's principle 3, this finding is **not** generalized
into EPUB — there is no EPUB-side pan/zoom to clamp.

### 2.4 Trigger conditions (from code reading, not live confirmation)

- Requires `scale > 1f` to be reachable at all — pinch zoom (coerced to
  `[1f, 5f]`) or the "Zoom in" button (toggles to `2f`).
- The incorrect bound is present at every scale `> 1`, not only "while
  zooming back out" — but zooming back out is the most *visible* trigger
  because it's when a user notices the page no longer fills the frame while
  translation hasn't actually been pulled back toward center, exactly
  matching the 2C log's description ("zooming back in particular").
- Reachable at minimum zoom (`scale == 1f`) only in the degenerate sense that
  `coerceIn(-size.width * 1f, size.width * 1f)` is still far too generous a
  bound relative to a page that may already be centered with real margin on
  both sides — i.e. even un-zoomed Fit Page content has "valid" pan range
  that should be `0` on at least one axis, but the current bound still
  technically allows large excursions. The gesture code's own
  `scale > 1f` guard on the outer `if` (line 162) does prevent *this specific
  pointerInput block* from running pan math while `scale == 1f` and
  single-finger — so the practical floor for a regular drag is `scale > 1f`.

### 2.5 Correct clamping model (to implement later, not now)

Confirmed against the actual code (not assumed): the content drawn inside
the transformed `Box` has a real rendered size derivable from
`bitmap.width`/`bitmap.height` and the Box's layout size, combined with
`ContentScale.Fit`'s standard aspect-preserving-fit formula (the same
formula `ContentScale.Fit` itself uses internally) — this is exactly the
conceptual model given in this task's brief:

```
scaledWidth  = fittedContentWidth  * scale
scaledHeight = fittedContentHeight * scale
maxPanX = max(0, (scaledWidth  - viewportWidth)  / 2)
maxPanY = max(0, (scaledHeight - viewportHeight) / 2)
translationX ∈ [-maxPanX, +maxPanX]
translationY ∈ [-maxPanY, +maxPanY]
```

where `fittedContentWidth`/`fittedContentHeight` are the *already-letterbox-
fitted* dimensions (i.e., `min(viewportWidth, viewportHeight * aspect)` style
math), not the raw bitmap pixel dimensions and not the viewport dimensions.
Fit Width's case is simpler on the horizontal axis (content width already
equals viewport width by construction via `fillMaxWidth()`), so `maxPanX`
is `0` at `scale == 1` and grows only with `scale`; its height is driven by
`aspectRatio()` plus the `verticalScroll` container, which (per §2.2 point 4)
needs its own look before committing to a single unified formula across both
fit modes.

### 2.6 Severity

**MEDIUM**, not BLOCKER/HIGH: it is a real, reproducible-by-math interaction
defect (unpolished/unprofessional-feeling zoom/pan, directly observed by the
owner on real content) but it does not crash, does not corrupt state, does
not lose data/position, does not block reading, and has a trivial user
recovery (double-tap or "Reset zoom" immediately re-centers). It affects two
of three formats (PDF, CBZ) in specific interaction modes (zoomed pinch/pan),
not baseline reading.

### 2.7 Pure clamp helper: recommended — YES

A pure function `clampPan(scale: Float, viewportW: Float, viewportH: Float,
contentW: Float, contentH: Float, panX: Float, panY: Float): Pair<Float,
Float>` (or a small value-holding result type) has no Compose/Context/Android
dependency, is fully deterministic, and is exactly the kind of math this
task's brief describes extracting. Reasons to do this rather than inline the
corrected formula directly in the gesture `pointerInput` block:

- It is independently JVM-unit-testable (the ten acceptance cases in §8 of
  this document) without Espresso/instrumentation, which is strictly
  cheaper and more reliable than the existing `connectedDebugAndroidTest`-only
  coverage this area currently has (none, today — see §2.8).
  This mirrors the repository's own precedent of extracting pure helpers out
  of Compose gesture code specifically to make precedence/ordering rules
  unit-testable without a real `InputDevice`/`KeyEvent`
  (`resolveInputSources`/`isGamepadSource` in 2A.1 — see
  `docs/design/INPUT_SYSTEM.md` §10).
- Viewport-resize and page-change invalidation both need to re-run the same
  formula from a slightly different call site (a `LaunchedEffect`/derived
  state recompute rather than inside the gesture loop) — a shared pure
  function avoids duplicating the math twice.
- It must defensively handle zero/invalid dimensions (a not-yet-measured Box,
  `contentW`/`contentH == 0` before the first bitmap arrives) — exactly the
  kind of edge case a pure function with no Compose timing dependency is
  easiest to make correct and test.

### 2.8 Honesty note on reproduction method

This pass did not drive a live pinch/pan gesture sequence on the API 35
emulator or the RP5 and capture a screenshot of the resulting letterbox gap.
The root cause is established with high confidence from direct code/math
inspection (the `coerceIn` bound is unambiguously wrong relative to any
correct "content vs. viewport" model, independent of runtime observation),
and this matches the owner's own 2C field observation closely enough
(letterbox margin visible while zooming back out, tracked as a known,
logged, not-yet-investigated item) that no contradicting evidence is
expected. A live gesture-injection reproduction (`adb shell input swipe`
sequences simulating pinch are notoriously unreliable for true multi-touch
pinch gestures) is recorded as a **gap**, not fabricated, and should be the
first manual check performed before/alongside implementing 2D.1 (§9).

## 3. Zoom semantics (current, as implemented)

- **Minimum scale:** `1f` (pinch coerced to `[1f, 5f]`; no way to zoom below
  1x).
- **Maximum scale:** `5f` (pinch only; the toolbar button only offers `1f`/
  `2f`).
- **Initial scale:** always `1f` on open and on every page change (`remember(
  state.page)` re-initializes it).
- **Double-tap:** toggles between `1f` and `2f`, always resetting
  `panX`/`panY` to `0f` — a deliberate, already-correct "reset to centered"
  behavior for this one path.
- **Pinch:** continuous, multiplicative (`scale * zoom`), coerced to
  `[1f, 5f]`; pan accumulates additively from gesture deltas, clamped by the
  defective bound in §2.
- **Page change:** always resets zoom and pan to `1f`/`0f`/`0f` — confirmed
  intentional, pre-existing behavior (also documented by 2C §/structural
  analysis). This pass finds no reason to change it; it is the expected,
  unsurprising default (arriving at a new page zoomed-in from the previous
  page's zoom level would be confusing, not helpful).
- **Fit-mode change:** switching `FitMode` (via `ReaderAppearance`) does not
  go through the zoom/pan state at all — it changes which `Modifier` branch
  renders the `Image` (line 187), independent of `scale`/`panX`/`panY`. A
  fit-mode change **while already zoomed** does not reset zoom/pan (no code
  path connects `ReaderAppearance`'s fit selection to these three `remember`
  blocks) — this is a real, newly-identified small gap: a user could change
  Fit Page → Fit Width while zoomed/panned and keep a transform computed for
  the old fit's layout, which the §2 clamp fix should also re-validate
  against (the pan-bound model is already sensitive to "viewport/content
  size changed," and a fit-mode change is exactly that kind of change, not
  only a literal window resize).
- **Recreation:** zoom/pan do **not** survive configuration change or process
  recreation at all (§1.3, §5) — they are not `rememberSaveable`. Per
  existing product semantics (page-change already always resets them, and no
  requirement anywhere asks for zoom continuity across recreation), this is
  judged **acceptable transient behavior**, not a defect — but it was not
  previously documented as a verified fact, only assumed; this pass confirms
  it by reading the code.

## 4. Viewport-resize implications

No code currently listens for Box-size changes to re-derive zoom/pan bounds
(there is no `onSizeChanged`/`BoxWithConstraints` driving this state) — the
gesture block only reads `size` reactively *during* a gesture, from the
`pointerInput` scope's live size. A resize that happens while **not**
gesturing (rotation, fold, multi-window drag) leaves stale `panX`/`panY`
values in place against the new viewport until the next gesture frame
re-clamps them (with the still-wrong bound). Once the clamp formula in §2.5
is implemented, it must be driven by both the gesture loop and a
viewport-size-change observer, not only the former — this is `Phase 2D
acceptance case 9` (§8).

## 5. CONTINUITY

### 5.1 EPUB recreation

**Strong, by code inspection.** Readium's `EpubNavigatorFragment` is retained
by `FragmentManager` across configuration change, restoring locator/scroll
position natively. `rememberSaveable` covers all Compose-level transient UI
(`controls`, `appearance`, `chapters`, `bookmarks`, `search`, `searchQuery`,
font-import error). Search specifically has documented, binding lifecycle
rules from 2B.1/2B.3 (not retaining `SearchService`/`SearchIterator` across
recreation; only serializable query/result data may survive) — already
implemented per 2B.3's acceptance, not re-litigated here. An existing
instrumented test, `EpubRecreationTest.kt`, already covers
"reader UI state survives recreation and applied-appearance reloads" (fixed
during the 2A.1 post-merge maintenance pass to use `OPEN_MENU` instead of a
stale `KEYCODE_BACK` assumption — see `PHASE_2_PLAN.md`'s 2A.1 section).

### 5.2 PDF recreation

**Page/position: strong** (persisted via `PositionWriter`/`ReadingEntity`,
restored via `restorePage()` in `FixedReaderViewModel.open()`, independent of
Activity/Compose lifecycle since the ViewModel itself and the Room-backed
repository own this state). **Chrome/appearance/modality: strong**
(`rememberSaveable`, same pattern as EPUB). **Zoom/pan: does not
survive**, confirmed in §1.3/§3 — judged acceptable transient state, not a
defect, but newly confirmed rather than assumed.

### 5.3 CBZ recreation

Identical code path to PDF (`FixedReaderScreen`/`FixedReaderViewModel` are
format-agnostic; `ArchivePages` vs. `PdfPages` only differs inside
`FixedReader.open()`/`render()`). Same continuity profile as §5.2.

### 5.4 Process-death expectations (not simple Activity recreation)

Ownership, stated explicitly per the brief's request:

| State | Survives process death? | Owner |
| --- | --- | --- |
| Publication identity (`LibraryItem.id`) | Yes | ViewModel args / SavedStateHandle-backed navigation, re-resolved from Room on recreation |
| Current page (fixed-page) / locator (EPUB) | Yes | `ReadingEntity` (Room), durable |
| Reader preferences (per-title + global) | Yes | `reader_preference`/`appearance_preference` (Room), durable |
| Bookmark state | Yes | `bookmark` table (Room), durable |
| Chrome visibility, dialog-open state (appearance/chapters/bookmarks/search panel open) | Should NOT need to (acceptable to reset to default-visible chrome) but currently DOES survive simple Activity recreation via `rememberSaveable`; true process death depends on whether `rememberSaveable`'s backing `Bundle` round-trips through a real process kill, which this pass did not instrument/verify empirically | Compose `rememberSaveable` |
| Zoom/pan (fixed-page) | Should NOT survive (transient, already reset on page change) | None — intentionally ephemeral |
| In-flight EPUB search query/iterator | Must NOT survive (2B.3 binding rule) | Cancelled/closed at teardown |

**Gap, honestly recorded:** this pass did not force a true `adb shell am
kill` process-death test (distinct from configuration-change recreation) on
either device during this run. `rememberSaveable`'s `Bundle`-based state
*should* survive real process death the same way it survives configuration
change (that is the documented Android contract Compose relies on), but this
pass did not empirically re-confirm it for the reader screens specifically.
Recommended as an explicit 2D.2 acceptance check (§9), not assumed passed
here.

### 5.5 Resize/multi-window

Not empirically exercised with live window-drag tooling in this pass (see
honesty notes throughout). By code inspection: EPUB's navigator fragment and
Compose appearance/chrome layouts use `fillMaxSize()`/`fillMaxWidth()`-style
sizing throughout — no hard-coded dp width/height assumptions were found in
`EpubActivity.kt` or `FixedReaderScreen.kt`. The one resize-sensitive gap
already identified is §4 (stale pan/zoom bounds after a resize that happens
outside an active gesture).

### 5.6 What should persist vs. remain transient (synthesized)

**Must persist:** publication identity, page/locator, reader preferences
(typography/fit/direction/presentation), bookmarks, EPUB font selection.
**May/should remain transient:** chrome visibility (acceptable either way;
currently persists, which is harmless), zoom/pan (should reset, already
does on page change, already doesn't survive recreation — consistent), any
in-flight search/iterator state (must not persist, already enforced).

## 6. ADAPTIVE LAYOUT

### 6.1 Current compact behavior

Both readers already use `fillMaxSize()`-based Compose layouts with no
`BoxWithConstraints`/window-size-class branching found anywhere in
`FixedReaderScreen.kt` or `EpubActivity.kt` — i.e., there is currently no
adaptive *branch* at all, compact and "expanded" currently render through
the exact same layout code, just at different measured sizes. This is
consistent with ADR-0010's framing ("use current window size/capabilities")
in the narrow sense that nothing hard-codes a phone-only assumption, but it
has not yet been *exercised or validated* at genuinely different size
classes in this pass (see the honesty note in §6.3).

### 6.2 Current expanded behavior

Same code path as §6.1 — no distinct expanded/tablet layout exists yet for
either reader. The chrome rows (`Row`/`Column` with `horizontalScroll`) will
naturally get more breathing room on a wider viewport, but nothing
*reflows* content differently (e.g. no side panel, no two-pane appearance +
page layout).

### 6.3 Two-page/spread decision: DEFER to Phase 3 for comics; EPUB multi-column stays a separate, still-deferred 2D candidate — not built now either

This finding required reconciling a real but narrow documentation tension,
per AGENTS.md's "do not silently choose" rule:

- `PHASE_2_PLAN.md`'s existing §3 2D bullet list already says "Adaptive
  reading layouts (e.g. two-page spreads on wide/tablet/foldable viewports)"
  is 2D-scoped, citing `COMICS_MANGA.md`'s AUTO-spread interim policy.
- `ROADMAP.md`'s Phase 3 — Comics and Manga section explicitly lists
  "spreads where appropriate," "foldable two-page/spread behavior," grouped
  with CBR, thumbnails and guided panels as Phase-3-owned work.
- `docs/features/COMICS_MANGA.md` itself lists "guided panels" and implicitly
  non-single-page spread refinement under "Later," consistent with Phase 3
  ownership, not Phase 2.
- Separately, `PHASE_2_PLAN.md`'s 2B.1 discovery section (§2B.1, "Reading
  mode") already made a **distinct, already-accepted** finding: EPUB's
  `ColumnCount` (Readium's own two-column/auto layout for reflowable text) is
  "SUPPORTED BY CURRENT READIUM, DELIBERATELY NOT EXPOSED... belongs to 2D."

These are two different features that share the words "two-page"/"spread":
**comic/manga fixed-page pairing** (pairing two page bitmaps side by side in
`FixedReaderScreen`) is Phase-3-owned per `ROADMAP.md`'s explicit section;
**EPUB reflowable multi-column** (Readium's `ColumnCount.TWO`/`AUTO`) was
separately, already flagged as a 2D candidate by the 2B.1 pass. Treating
`PHASE_2_PLAN.md`'s 2D bullet as referring to the comic case would duplicate
Phase 3's explicit ownership; treating it as referring to the EPUB case is
consistent with 2B.1's own finding. This document resolves the ambiguity by
recommending this document's own wording (§10) and a small clarifying edit
to `PHASE_2_PLAN.md`'s 2D bullet (§12.2) rather than silently picking one
reading and leaving the other document's language unclear for the next
person.

**Decision:**
- **Comic/manga fixed-page spreads: DEFER to Phase 3.** `ROADMAP.md` already
  and explicitly owns this; building it in 2D would duplicate/pre-empt that
  section's scope, contradicting this task's own "avoid duplicating future
  Comics/Manga work" instruction. No foldable-posture-aware pairing logic,
  cover-offset rule, or AUTO-resolution change is proposed here. The interim
  "AUTO resolves to one page" policy (ADR-0017) remains correct and
  unchanged.
- **EPUB multi-column: NOT implemented in 2D either, but for a different
  reason** — no demonstrated user-facing need surfaced during this pass (no
  field report, no roadmap urgency beyond the original "deliberately not
  exposed" discovery note), and 2D's own stated purpose is *closure*, not
  adding a new reflow mode. Recommend it remain an explicitly open, low-risk,
  well-understood candidate for a future slice (2D or later) if real demand
  or a foldable/tablet-readability push materializes — the Readium API
  already supports it cleanly (confirmed in 2B.1's bytecode-level
  investigation), so there is no re-investigation cost if it's picked up
  later.
- This is choice **(B)** from the brief's framing (better left to Phase 3)
  for the comic case, and effectively **(D)** (too broad/no current need) for
  the EPUB case — neither is built now.

### 6.4 Foldable readiness (no foldable-specific code proposed)

No hard-coded width/height assumption was found in either reader's layout
code. Reader state (position, preferences, bookmarks) already persists
durably (§5), independent of posture. No current architecture change is
needed merely to *allow* a future posture-aware layout to be added — the
gap is entirely "no adaptive layout branch exists yet," not "the
architecture actively blocks one." Per ADR-0010 and this task's principle 7
("no speculative abstractions ahead of demonstrated need"), this pass
recommends building no foldable-specific infrastructure now. `docs/design/
FOLDABLES.md` was not deeply re-audited in this pass beyond this
architectural read — if it specifies a stronger near-term foldable
commitment for Phase 2D specifically, that would need reconciling in a
follow-up, but nothing found during this pass's reading of `PHASE_2_PLAN.md`
§3's 2D scope treats foldable-specific work as mandatory for 2D (it treats
"fold/unfold" restoration — i.e., the resize/continuity case in §5.5 — as
in-scope, which is distinct from building two-page spread *support*).

## 7. INPUT

### 7.1 Keyboard findings

By code inspection, `InputMapper.readerCommand` resolution is identical for
both readers (same `core.input` call sites). No divergence found in how
Escape/Page Up/Page Down/arrows resolve to commands between EPUB and
fixed-page. The one already-documented asymmetry (2A.1) is **EPUB edge-tap
page turns bypass the modality-tracking layer entirely** (Readium's own
`DirectionalNavigationAdapter` never reaches `EpubActivity`'s Compose tree),
while fixed-page's tap-to-turn is ShelfOS's own `pointerInput` code and
always updates modality. This is a pre-existing, already-documented,
already-accepted gap (not new to this pass) — restated here because it is
directly relevant to "cross-reader input consistency," not reopened as a
new defect.

### 7.2 Controller findings

Same semantic command set (`NEXT_PAGE`/`PREVIOUS_PAGE`/`OPEN_MENU`/`BACK`),
same hint-rendering mechanism (`InputHints.hint`), both readers. No
divergence found by code inspection beyond the known EPUB edge-tap gap
above.

### 7.3 RP5 physical findings

**Not performed as ADB-injected verification in this pass** — the RP5
(`d8f7f1b6`) and the API 35 emulator (`emulator-5554`) were both confirmed
online and authorized (`adb devices -l`) at the start of this session, but
given this pass's scope (documentation/planning only, no production or test
code changes, and the pan/zoom root cause already resolved conclusively by
code inspection in §2), no `adb shell input keyevent`/`swipe` driving
session against a running ShelfOS build was performed. This is recorded
honestly as a **gap**, not fabricated evidence, and is explicitly listed as
the first physical-validation task for 2D.1/2D.4 (§9, §11). Prior phases'
RP5 evidence (2A, 2A.1, 2B.3) already established that the shared
`ShelfCommand`/chrome/Back mechanisms work correctly on real RP5 hardware —
this pass found no code change since then that would plausibly regress
that, so it is treated as still-valid standing evidence, not re-claimed as
freshly re-verified.

### 7.4 Cross-reader inconsistencies

Only the already-documented EPUB-edge-tap-modality gap (§7.1). No other
input inconsistency was found between EPUB and fixed-page command
resolution, Back semantics, or hint rendering.

## 8. ACCESSIBILITY

### 8.1 Semantics findings

- Chrome-toggle surfaces (`FixedReaderScreen`'s page `Box`, `EpubActivity`'s
  `epub_page`-tagged surface) carry `stateDescription` + a conditional
  `onClick` reveal action exactly matching real behavior (2A, re-confirmed
  present by this pass's code read, unchanged).
  the chrome `onClick` is intentionally **only** exposed while chrome is
  hidden, meaning TalkBack never announces a stale/misleading action.
- Input hints (`backHint`/`previousHint`/`nextHint`) are either merged into
  an existing control's `contentDescription` (Previous/Next) or made
  `clearAndSetSemantics { }` decorative-only (the Back keycap row, line 129)
  — this avoids a duplicate/decorative node being independently announced,
  a known pattern this task's brief specifically asks to check for. No
  duplicate-announcement issue found in the reader chrome.
  by this pass's reading.
- Icon-only/ambiguous actions: this pass found none introduced since 2A —
  reader controls use `Text(stringResource(...))` labels (Previous/Next/
  Appearance/Library/Hide controls), not bare icons, so no icon-only
  accessible-name gap was found in the fixed-page reader's own chrome.
  (`EpubActivity`'s dialogs were not exhaustively re-audited line-by-line in
  this pass beyond the architecture read in §1.2 — recorded as a partial
  gap, not a clean bill of health, for the chapter/search/bookmark dialog
  interiors specifically.)
- Error state (`state.error`): surfaced as plain `Text(message.resolve())`
  inside a `Surface`, with a `Retry`/`Back to library` `TextButton` — both
  are labeled, accessible actions, not icon-only, not silently swallowed.

### 8.2 Focus-order findings

By code inspection: `pageFocus`/`firstControl` `FocusRequester`s drive an
explicit, intentional focus flow (page surface takes initial focus; opening
chrome via `OPEN_MENU`/double-tap-equivalent moves focus to the first
control via `controlFocusRequests`). No live D-pad/keyboard traversal
session was run in this pass to confirm no focus trap exists across the
chapters/search/bookmark dialogs' own internal focus order — this is a
**gap**, recorded honestly, not claimed as audited.

### 8.3 TalkBack: unavailable/not run

TalkBack availability was not checked against the live emulator/RP5
accessibility-service list in this pass (would require `adb shell settings
get secure enabled_accessibility_services` plus enabling it, which this pass
did not perform given its documentation-only scope). Per the brief's own
guidance ("If not available, do not install arbitrary accessibility
software... record semantic-code audit complete, manual TalkBack validation
pending"), this is recorded as: **semantic-code audit complete (§8.1),
manual TalkBack validation pending.**

### 8.4 Reduced motion

No animation was found introduced by any 2B/2C work in either reader —
chrome show/hide remains the same instant, unanimated state change
established in 2A (`Context.reducedMotionEnabled()`/`core.theme.
ReducedMotion` remains unused by the readers, which is correct, since there
is no reader motion to gate). This pass's code read found no new
`AnimatedVisibility`/`animate*AsState` call introduced in
`FixedReaderScreen.kt` or `EpubActivity.kt` since 2A. No regression found.

## 9. PERFORMANCE/RESILIENCE

### 9.1 Large PDF / Large CBZ

**Gap, honestly recorded.** No large PDF or CBZ fixture exists in this
repository's authorized test locations (`app/src/androidTest/java/com/
d4guilar/shelfos/OriginalFixtures.kt` only builds small, synthetic,
originally-authored fixtures — a 3-page PDF, a 3-image CBZ, small EPUBs).
Per this task's explicit constraint (do not browse broadly for personal
files, do not fabricate a synthetic huge file solely to stress storage
unless justified), this pass performed no large-document resilience test.
Recommended as an explicit 2D.4 task using a deliberately-generated large
*synthetic* fixture (e.g. a many-page `PdfDocument`-generated PDF, following
exactly the same already-accepted pattern 2C used for its dense vector
control PDF — see `PHASE_2C_IMPLEMENTATION_PLAN.md` §22c — generated as a
temporary test artifact, not committed) if and when that slice is
implemented, rather than skipped indefinitely.

### 9.2 Malformed-file behavior

**Code-level review only, no destructive fuzzing performed (per the brief's
own constraint).** Confirmed existing, typed, localized error paths:
`FixedReader.kt` throws `PublicationException(PublicationProblem.CORRUPT,
PAGE_IMAGE_DAMAGED_OR_TOO_LARGE)` and `...PAGE_IMAGE_DECODE_FAILED` for
page-level image failures; `EpubReader.kt` throws
`...EPUB_INVALID_OR_UNSUPPORTED` for an unopenable EPUB
(`PublicationOpener.OpenError.FormatNotSupported`). Both flow through
`UiMessage.readerMessage()` (the localization-foundation typed-error
pipeline) into the same error-surface UI described in §8.1, with a Retry/
Back action, never a crash path, by this pass's reading of the exception
handling in both `FixedReaderViewModel.render()`/`open()` and the EPUB
equivalent. This is judged **already adequate** for the currently-known
failure modes; no new crash path was identified in this pass's code reading.

### 9.3 Fast navigation/cancellation

**Reused as accepted evidence, not re-derived.** `FixedReaderViewModel.
render()`'s `rendering?.cancel()` + single-`Mutex` + bitmap-recycle-on-loss
pattern is unchanged since 2C's §9/§22a investigation of this exact code,
and this pass's own reading of the current `FixedReaderViewModel.kt`
confirms it is still present and structurally identical. No new gap found;
not re-tested empirically in this pass (not required — 2C already performed
that verification and nothing has since touched this code).

### 9.4 EPUB lifecycle/performance

No new lifecycle risk was found in this pass's reading of `EpubActivity.kt`
beyond what 2B.3 already documented and bound (search iterator
cancellation/closure, `recreate()` calls on font catalog changes). This pass
did not independently re-measure Readium open/close/repeated-session
performance — treated as a gap, low-priority given no field complaint exists
about EPUB performance specifically.

### 9.5 Fixed-reader performance

Per this task's explicit instruction, 2C's measurements are accepted
evidence and were not reopened. This pass looked only for *new* user-visible
jank risk introduced since 2C and found none — the only relevant open items
are the §2 pan/zoom-bounds defect (an interaction-correctness issue, not a
performance one) and the already-known lack of bitmap
caching/prefetch (deliberately deferred, unchanged).

## 10. LOCALIZATION/LAYOUT

Not independently re-audited string-by-string in this pass beyond
confirming (§9.2) that reader error strings already flow through the
accepted localization-foundation `UiMessage`/string-resource pipeline. No
live EN/ES/pt-BR layout comparison at compact/expanded viewports was
performed (gap, honestly recorded) — recommended as part of 2D.3's
accessibility/focus closure slice, since it's the same kind of
manual-walkthrough validation as the TalkBack check in §8.3 and is cheapest
to do together.

## 11. FINDINGS BY SEVERITY

- **BLOCKER:** none found.
- **HIGH:** none found.
- **MEDIUM:**
  1. Fixed-reader pan/zoom bounds defect (§2) — PDF + CBZ, both fit modes,
     both RTL/LTR, reproduced by code/math inspection, not yet by live
     gesture session. Ownership: `FixedReaderScreen.kt` gesture/transform
     code.
  2. Fit-mode change while zoomed doesn't reset/re-validate the transform
     (§3) — newly identified, narrow, same ownership/fix surface as #1.
- **LOW:**
  3. Viewport-size changes outside an active gesture don't re-clamp
     pan/zoom until the next gesture frame (§4) — same fix surface as #1.
  4. EPUB edge-tap page turns don't update tracked input modality (§7.1,
     pre-existing/already-documented, not new).
- **OBSERVATIONS (no defect, recorded for completeness):**
  5. `FixedReaderScreen`/`EpubActivity` still duplicate chrome/Back-state
     plumbing (pre-existing, previously deferred refactor target).
  6. Touch is still not routed through `ShelfCommand` (pre-existing,
     previously deferred).
  7. No adaptive (compact vs. expanded) layout branch exists yet in either
     reader — not itself a defect, see §6.
  8. `docs/PHASE_2_PLAN.md`'s 2D-scope bullet and `ROADMAP.md`'s Phase 3
     section both use "two-page/spread" language in a way that could be
     misread as overlapping (§6.3) — a documentation clarity gap, not a code
     defect, addressed by §12.2's edit.

## 12. Recommended implementation slices

### 12.1 Slice sequence

**2D.1 — Fixed-reader transform/bounds correctness**
- Motivation: §2/§3's MEDIUM findings; the only user-visible interaction
  defect found this pass.
- Scope: extract the pure pan-clamp helper (§2.7); correct the pinch/pan
  gesture's bound calculation to the content-vs-viewport model (§2.5); make
  a fit-mode change re-validate/reset the transform consistently with
  page-change behavior (§3); re-clamp on viewport-size change (§4), not only
  during active gestures.
- Production files likely affected: `FixedReaderScreen.kt` (gesture block),
  a new small pure-math file (e.g. `core/reader/PanClamp.kt` or similar,
  exact location/name an implementation-time decision).
- Tests: JVM unit tests for the pure clamp function covering the ten cases
  in §13; a focused instrumentation/Compose test reproducing the original
  letterbox-overpan symptom before the fix and asserting it's gone after.
- Physical validation: RP5 pinch/pan session (ADB-injected at minimum, real
  physical pinch if practical) on both PDF and CBZ, both fit modes.
- Non-goals: no zoom range change, no new gesture (e.g. no double-finger-tap
  reset), no EPUB change, no caching/prefetch change.
- Rollback boundary: purely additive/corrective to one screen's gesture
  code + one new pure file; revertable independently of any other 2D slice.
- Acceptance: all ten §13 cases pass as JVM tests; live RP5/emulator
  confirmation that zoom-out no longer exposes excess letterbox space;
  `:app:assembleDebug`/`:app:testDebugUnitTest`/`:app:lintDebug`/
  `:app:connectedDebugAndroidTest` green.

**2D.2 — Recreation/resize continuity closure**
- Motivation: §5's process-death gap (not empirically confirmed) and §5.5's
  unvalidated resize behavior.
- Scope: a true `adb shell am kill`-based process-death validation pass for
  all three formats (not assumed passed); live window-resize validation at
  compact and forced-expanded viewports; confirm §4's resize-triggered
  re-clamp (if 2D.1 landed first) actually fires on a real resize, not only
  gesture-time.
- Production files likely affected: possibly none (validation-only) unless
  the process-death check surfaces a real gap, in which case the fix would
  be scoped narrowly at that point, not speculatively now.
- Tests: an instrumented process-death-style test if the existing test
  infrastructure supports simulating it reliably (to be confirmed at
  implementation time); otherwise manual ADB-driven validation, documented
  as such.
- Physical validation: emulator forced-expanded + compact viewport resize
  while each reader is open; RP5 (rotation only — RP5 has no fold/multi-
  window posture).
- Non-goals: no new persistence, no schema change, no foldable-specific
  code.
- Rollback boundary: validation-only unless a real gap is found; any fix
  would be scoped at that time.
- Acceptance: documented pass/fail for every row in §5's "what should
  persist" table, against real process death, not just configuration
  change.

**2D.3 — Input/accessibility/focus closure**
- Motivation: §7's RP5-physical gap, §8's focus-order/TalkBack gaps, §10's
  localization-layout gap.
- Scope: RP5 ADB-injected (and, if the owner is available, owner-physical)
  input walkthrough across all three formats; keyboard/D-pad focus-order
  walkthrough through chrome → appearance → chapters/search/bookmarks →
  back to chrome; TalkBack walkthrough if/when available on a test target;
  EN/ES/pt-BR layout check at compact + expanded viewport for clipped/
  overlapping reader chrome.
- Production files likely affected: none predicted ahead of findings; any
  fix is scoped once a real gap is found, not pre-built speculatively.
- Tests: extend `NavigationSmokeTest`/`InputModalityClassificationTest` only
  if a real gap is found requiring regression coverage.
- Physical validation: RP5 (primary), emulator, TalkBack if available.
- Non-goals: no input-system redesign, no remapping UI, no new accessibility
  framework.
- Rollback boundary: validation-only unless findings require small, scoped
  fixes.
- Acceptance: documented walkthrough results for every item in §7/§8/§10;
  any found defect classified and either fixed in this slice (if small) or
  explicitly deferred with a reason.

**2D.4 — Performance/resilience + final physical acceptance**
- Motivation: §9's large-file/malformed-file gaps; this is also the natural
  place for the Phase-2-wide final acceptance gate (§15).
- Scope: generate and exercise a synthetic large PDF/CBZ fixture (temporary,
  not committed, per 2C's own precedent); confirm no regression in
  fast-navigation/cancellation behavior under load; final device-acceptance
  matrix (§16) executed end-to-end across EPUB/PDF/CBZ.
- Production files likely affected: none predicted ahead of findings.
- Tests: a temporary large-fixture-based instrumentation test used for this
  pass's own validation (not necessarily retained, per 2C's precedent,
  unless it proves durably valuable as regression coverage — an
  implementation-time decision).
- Physical validation: full matrix, §16.
- Non-goals: no PDF-rendering change (2C is closed), no new caching
  architecture unless a real, measured gap is found.
- Rollback boundary: validation-heavy; any production fix scoped narrowly
  per finding.
- Acceptance: §15's Phase 2 completion definition fully satisfied with
  evidence.

### 12.2 Recommended documentation clarification (small, docs-only)

`PHASE_2_PLAN.md`'s §3 2D bullet list currently says "Adaptive reading
layouts (e.g. two-page spreads on wide/tablet/foldable viewports)" without
distinguishing the EPUB-multi-column case (2B.1's already-accepted 2D
candidate) from the comic/manga spread case (`ROADMAP.md`'s explicit Phase 3
ownership). This document's §6.3 recommends a minimal wording clarification
there — not a scope change, not a rewrite of accepted history — splitting
that one bullet into its two actual referents and stating the Phase 3
boundary explicitly, so a future reader of `PHASE_2_PLAN.md` alone doesn't
re-litigate the Phase 3 boundary. Applied as part of this pass's docs-only
commit (see `PHASE_2_PLAN.md`'s diff).

## 13. Pan-bound acceptance test cases (future tests, not implemented now)

Preferred strategy: pure JVM tests for 1–9 against the extracted clamp
function (§2.7), plus one focused Compose/instrumentation test for 10 (and
as a live end-to-end confirmation of 1–3).

1. Page smaller than viewport (both axes) at `scale == 1` → clamped
   translation is `(0, 0)` on both axes.
2. Page wider than viewport (scaled) → horizontal pan bounded to
   `±maxPanX`; vertical stays `0` if the page fits vertically.
3. Page taller than viewport (scaled) → vertical pan bounded to
   `±maxPanY`; horizontal stays `0` if the page fits horizontally.
4. Page larger than viewport on both axes → both axes clamp independently
   to their own `maxPanX`/`maxPanY`.
5. Zoom in (`scale` increases) → `maxPanX`/`maxPanY` increase
   monotonically; a previously-valid translation remains valid or is
   unaffected.
6. Zoom out (`scale` decreases) → any existing translation exceeding the
   new, smaller `maxPanX`/`maxPanY` is immediately re-clamped, not left
   stale until the next pan delta.
7. Return to minimum/default zoom (`scale == 1`) → translation recenters to
   `(0, 0)` when the content no longer exceeds the viewport on that axis
   (matches the existing double-tap/"Reset zoom" behavior, which this pass
   confirms is already correct — the fix must not regress it).
8. Page change → a translation computed for the previous page's content
   dimensions must not leak into the new page's clamp (already true today
   only because `remember(state.page)` resets `scale`/`panX`/`panY` to
   defaults on every page change — the new clamp function must preserve
   this invariant, not merely coincidentally satisfy it).
9. Viewport resize (rotation, fold, multi-window) → translation is
   recalculated against the new viewport dimensions, not left clamped to
   the old viewport's bound (§4's gap).
10. PDF and CBZ exhibit identical clamp behavior for the same input
    dimensions/scale/pan (shared code path, §2.3) — a focused instrumented
    test opening both a PDF and a CBZ fixture and asserting the same
    resulting bound/behavior for equivalent inputs.

## 14. Device acceptance matrix (final Phase 2 gate)

A high-value, not exhaustive, matrix:

| Axis | Values |
| --- | --- |
| Devices | API 35 emulator (compact default + forced-expanded viewport); RP5 physical (primary); Galaxy Tab A (optional/periodic, not available this pass) |
| Formats | EPUB, PDF, CBZ |
| Input | Touch; keyboard (emulator physical/RP5 ADB-injected); gamepad (RP5, ADB-injected + owner-physical where available) |
| Lifecycle | Fresh open; configuration-change recreation; forced process death (`adb shell am kill`); leave-and-return; close-and-reopen from Library |
| Viewport | Compact; forced-expanded/tablet-like |
| Accessibility | Semantic-code audit (always); TalkBack walkthrough (if available on the target) |

High-value combinations to prioritize (not every cell): PDF+CBZ pinch/pan
at compact and expanded viewport on both emulator and RP5 (directly
validates 2D.1); all three formats × process-death on the emulator (2D.2);
RP5 gamepad/keyboard walkthrough for all three formats (2D.3); EPUB search/
bookmark/font surfaces only need one representative device each, since
their lifecycle rules were already validated in 2B.2/2B.3.

## 15. Phase 2 final acceptance definition (proposed)

A user on a supported device can, with evidence behind every clause below
(not claimed until the relevant 2D slice closes it):

1. Open an EPUB, PDF or CBZ from the Library and read/navigate it reliably.
2. Resume at the correct position after leaving and returning, and after a
   full app close/reopen.
3. Use touch, keyboard, and gamepad interchangeably for the same semantic
   actions (page turn, menu, back) in every reader.
4. Reveal and leave the reader predictably (ADR-0023's two-Back-press
   contract), from any chrome state.
5. Use the supported appearance controls for that format (typography/fit/
   direction/presentation) and have changes persist correctly.
6. Use EPUB's chapter navigation, search, bookmarks and managed-font
   features without a crash or data loss.
7. Survive Activity recreation (rotation, etc.) without losing position,
   preferences, bookmarks, or chrome/appearance UI state.
8. Survive practical viewport changes (resize, fold-equivalent on emulator)
   without broken layout, cut-off controls, or an un-recoverable pan/zoom
   state.
9. Not be able to pan/zoom a fixed-page reader's content visibly past its
   real edges into empty space (2D.1's defect fixed and verified).
10. Encounter a graceful, localized error message — never a crash — for a
    malformed/corrupt publication or page.
11. Operate the core reader controls (page turn, menu, back, appearance)
    accessibly: correct semantics, no misleading TalkBack announcements,
    reachable keyboard/D-pad focus order.
12. Show no obvious memory/performance regression versus the Phase
    2C-accepted baseline (2C's own measurements remain the reference; this
    pass introduces no new rendering work to regress them).

This explicitly does **not** claim: comic/manga page-spread layouts, EPUB
multi-column reflow, CBR, OCR, annotations/highlights/notes, Adapted PDF,
cloud sync, or any item from `PHASE_2_PLAN.md` §4's standing out-of-scope
list.

## 16. Risks

- The pan-clamp fix touches shared PDF/CBZ gesture code; an incorrect
  formula could make zoom/pan feel *more* broken (e.g. over-clamping to
  zero on content that legitimately needs pan) rather than less — the ten
  cases in §13 exist specifically to guard against this.
- Process-death validation (2D.2) could surface a real gap that requires
  unplanned schema/persistence work — if that happens, it should be scoped
  as its own narrow follow-up, not absorbed silently into 2D.2's budget.
- Accessibility/TalkBack validation depends on tooling availability this
  pass could not confirm; if TalkBack remains unavailable through 2D.3, the
  manual-walkthrough gap would need to be explicitly carried forward rather
  than silently dropped.
- Large-fixture performance testing (2D.4) risks scope creep into a
  rendering-architecture discussion that Phase 2C already closed — 2D.4 must
  stay focused on resilience/crash-avoidance under load, not reopen
  `MAX_PAGE_PIXELS`/caching decisions without new evidence.

## 17. Explicit non-goals for Phase 2D (restated, not newly invented)

Per the brief's §29 and this document's own findings: no Adapted PDF, no PDF
rerender architecture change, no OCR, no AI enhancement, no annotations, no
stylus/ink, no native CBR implementation, no Series/Omnibus, no Library
Sources, no bulk import, no cloud/sync, no theme implementation, no general
input rebinding UI, no plugin architecture, no broad Readium rewrite, no
image-processing pipeline, no speculative device-tier framework, no
comic/manga page-spread implementation (Phase 3), no EPUB multi-column
implementation (left open, not built). CBR remains a reinforced future
Comics/Manga priority per `PHASE_2C_IMPLEMENTATION_PLAN.md`'s §22b
cross-cutting finding, not implemented here.

## 18. GitHub issue vs. plan-doc tracking for the pan/zoom defect

This environment's `gh` CLI is not installed/available in the current shell
(`gh --version`/`gh issue list` both failed with "command not found"), so
this pass could not directly query whether the repository has any open
GitHub Issues or an established issue-based workflow. No `CONTRIBUTING.md`
or issue-template convention was found during this pass's documentation
reading (none of `AGENTS.md`/`PHASE_2_PLAN.md`/`ROADMAP.md`/
`RELEASE_GOVERNANCE.md` references a GitHub Issues workflow as the way
defects are tracked — this repository's tracking convention, as observed
throughout Phase 2A–2C, is entirely plan-document/`VALIDATION.md`-based).
**Recommendation: keep the pan/zoom defect tracked only in this plan
document (§2, §9's 2D.1 slice), not as a GitHub Issue**, consistent with
the repository's existing convention and this task's own default guidance
not to create one absent an established workflow or explicit instruction.

## 19. ADR decision

**Not needed.** The pan-clamp fix is a bug fix to existing, already-accepted
gesture-handling code — it does not change an architectural boundary,
introduce a new abstraction, or alter a product-level decision recorded by
an existing ADR. The two-page/spread question (§6.3) that could plausibly
have warranted a new ADR was resolved by reconciling existing documents
(ADR-0010, ADR-0017, `ROADMAP.md`'s Phase 3 section already cover the
relevant ground) rather than by making a new durable architectural
commitment — no new adaptive-reader-architecture decision is being made by
this pass, only a scope/sequencing clarification. If a future pass actually
commits to building EPUB multi-column or comic spreads, an ADR may become
warranted at that time, but not from this discovery pass's findings alone.

## 20. Summary table: what 2D should actually build

| Area | Build in 2D? | Slice |
| --- | --- | --- |
| Pan/zoom bounds clamp fix | Yes | 2D.1 |
| Fit-mode-change transform reset | Yes | 2D.1 |
| Viewport-resize re-clamp | Yes | 2D.1 |
| Process-death validation (+ narrow fix if found) | Validation yes; fix only if needed | 2D.2 |
| Resize/multi-window validation | Yes (validation) | 2D.2 |
| RP5/keyboard/TalkBack/focus-order walkthrough | Yes (validation + scoped fixes) | 2D.3 |
| EN/ES/pt-BR layout check | Yes (validation) | 2D.3 |
| Large PDF/CBZ resilience check | Yes | 2D.4 |
| Final Phase 2 device acceptance matrix | Yes | 2D.4 |
| Comic/manga page-spread layout | No — Phase 3 | — |
| EPUB multi-column reflow | No — left open, no current need | — |
| Any PDF render-resolution change | No — Phase 2C closed this | — |
| CBR | No — future Comics/Manga priority, not 2D | — |

## 21. 2D.1 implementation record (2026-10-02)

**IMPLEMENTED, pending independent review and owner RP5 physical acceptance.**
Scope held exactly to §9/§12's 2D.1 slice (fixed-reader transform/bounds
correctness) — no PDF resolution, CBZ sampling, EPUB, persistence, or input-
remapping changes.

### Live reproduction (gate cleared before implementation)

Per §2.8's honesty note, the owner personally reproduced the defect live on
the physical RP5 before any code was written: *"If I zoom out pulling to the
gray side, it just overrides the actual pdf page and I can continue moving
until even the page is completely gone, having to change page for it to even
reset. This is the same on both sides or up and down."* This confirmed §2's
code-inspection diagnosis was not merely theoretical.

### Root cause and fix

Confirmed exactly as §2.1/§2.2 predicted: `FixedReaderScreen`'s pinch/pan
gesture handler clamped `panX`/`panY` to the viewport's own width/height
multiplied by `scale` — the full viewport dimension times scale, unrelated to
how far the actual fitted, scaled content extends past the viewport. This
permitted dragging a page's content completely off-screen, matching the
reproduction above exactly.

Fix: a new pure, Compose-free, Context-free file,
`app/src/main/java/com/d4guilar/shelfos/core/reader/FixedReaderTransform.kt`,
with three functions:

- `fixedReaderFittedContentSize(bitmapWidth, bitmapHeight, viewportWidth,
  viewportHeight, fitWidth)` — the unscaled (1x) fitted content size, mirroring
  `ContentScale.Fit`'s letterbox math for Fit Page, and
  `fillMaxWidth().aspectRatio(...)`'s width-bound sizing for Fit Width (whose
  height may exceed the viewport, handled by the screen's own `verticalScroll`).

  > **Correction (2026-10-02, 2D.1 remediation, §21.1 below):** this entry
  > originally claimed Fit Width's `verticalScroll` was "independent of this
  > pan model" and that this was "confirmed, not redesigned." That was false.
  > Nothing stopped the screen from also feeding this tall content height into
  > `fixedReaderMaxPan`/`translationY` the same way Fit Page does, and it did —
  > letting the pan transform and `verticalScroll` both move the same gesture
  > at once, with a Y bound measured against the viewport alone rather than
  > the already-tall content. Independent QA caught this; see §21.1.
- `fixedReaderMaxPan(contentSize, viewportSize, scale)` — the correct geometric
  model from §2.5: zero if the scaled content already fits, otherwise half the
  excess of the scaled content over the viewport.
- `fixedReaderClampPan(value, max)` — defensive coercion.

All three are defensive against non-finite/non-positive/zero inputs (return a
safe `0` bound rather than propagating `NaN`/`Infinity`), and deliberately
take no reading-direction (`rtl`) input — pan bounds are purely geometric.

`FixedReaderScreen.kt` changes (43 insertions / 7 deletions, one file):

- The pinch/pan gesture block now computes the fitted content size from
  `state.bitmap` and the live gesture `size`, then clamps `panX`/`panY` using
  the **new** `scale` and **new** candidate translation together in the same
  update (not against stale pre-zoom geometry).
- **Fit-mode-change reset (§2's adjacent LOW finding)**: `scale`/`panX`/`panY`'s
  `remember` keys now include `fitWidth` alongside `state.page`
  (`remember(state.page, fitWidth) { ... }`) — switching Fit Page ↔ Fit Width
  now resets the transform exactly like a page change already did. Chosen per
  §6's "smallest predictable behavior" guidance; no evidence found justifying
  "preserve + re-clamp" complexity instead.
- **Viewport-resize re-clamp (§4)**: a new `viewportSize` state, updated via
  `Modifier.onSizeChanged` on the `reader_page` Box, drives a
  `LaunchedEffect(viewportSize, scale, fitWidth, state.bitmap)` that re-clamps
  `panX`/`panY` against the live viewport whenever any of these change —
  guaranteeing an idle (non-gesturing) transform cannot remain invalid after a
  resize/rotation/fold.

### Test coverage

- **JVM** (`app/src/test/java/com/d4guilar/shelfos/core/reader/FixedReaderTransformTest.kt`):
  **29/29 passed.** Covers every acceptance case in §13 (content smaller/
  larger than viewport on one/both axes, in-range/out-of-range translation,
  zoom increase/decrease re-clamping, default-zoom recentering, content/
  viewport size changes, invalid/zero/negative/non-finite dimensions, extreme
  finite values — no NaN/Infinity output).
- **Instrumented** (new `app/src/androidTest/java/com/d4guilar/shelfos/FixedReaderTransformBoundsTest.kt`,
  5 tests: PDF pinch-zoom-drag stays within bounds, CBZ pinch-zoom-drag stays
  within bounds + navigation still works, default zoom reports zero
  transform, fit-mode change resets transform, page change resets transform):
  **5/5 passed on both the physical RP5 and the API 35 emulator**, in two
  independent full-suite runs.
- **Regression** (`NavigationSmokeTest`, pre-existing, unchanged, 26 tests
  covering Back/Escape/gamepad-B chrome semantics, keyboard/controller hint
  derivation, RTL manga key mapping, focus order, accessibility actions):
  **26/26 passed on both RP5 and emulator**, both runs — no input,
  accessibility, Back-semantics, or RTL regression from this change.
- **Full JVM suite**: **186/186 passed, 0 failed, 0 skipped**
  (`:app:testDebugUnitTest --rerun-tasks --offline`).
- **Final Gradle gate** (`compileDebugKotlin compileDebugAndroidTestKotlin
  assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
  --rerun-tasks --offline`): **BUILD SUCCESSFUL, 86/86 tasks.**
- **Full connected suite, both devices**: run twice. First run hit a
  genuinely degraded emulator (composer/allocator processes consuming
  69%/49% CPU, load average 7.64 from earlier concurrent build activity),
  confirmed by a clean 7-second pass of the same test immediately after an
  emulator restart. On a fresh emulator: **98/98 passed, 0 failed** — every
  test, including this slice's own new coverage. On the RP5 in that same
  run: 98 tests, 16 failures, every one sharing the identical
  `IllegalStateException: No compose hierarchies found... Activity did not
  launch` signature (a device-state symptom — confirmed the test APK had
  been uninstalled by Gradle between runs and the RP5 screen state was
  stale), **not a single failure in this slice's own new tests or in
  `NavigationSmokeTest`, both of which passed 0 failures on RP5 in the same
  run.** Reinstalling and re-running each originally-failed class
  individually on RP5 resolved all but two: `EpubBookmarkTest.bookmarksDialogIsReachableAndOperableThroughKeyboardFocus`
  and two `EpubSearchTest` keyboard-focus cases — both touch-mode/keyboard-
  focus RP5 quirks already documented as device-specific in this project's
  own prior validation history (Phase 2B.2.1/2B.2.2 sections of
  `VALIDATION.md`), on code this slice never touches (confirmed via
  `git diff --stat`: only `FixedReaderScreen.kt` plus the three new files
  changed — no EPUB, bookmark, or search code).
- `git diff --check`: **PASS.**

### RP5 physical acceptance — OWNER VISUAL OBSERVATION: PASS (2026-10-02)

The owner installed the committed fix build on the physical RP5 and performed
the real pinch-zoom-in/pan-to-edge/zoom-back-out sequence by hand — the exact
gesture that originally exposed the defect. Reported result: *"pass! Zoom in
and zoom out dont go out of bounds or slides of screen, fit width too. All
good."* This closes the one evidence gap this implementation pass's own
RP5 instrumented testing (above) could not substitute for.

### Scope discipline confirmed

`git diff --stat` against the pre-implementation commit shows exactly one
modified production file (`FixedReaderScreen.kt`) and three new files (the
pure helper, the JVM test, the instrumented test) — no `PdfPages`,
`ArchivePages`, PDF resolution, CBZ sampling, EPUB, persistence, or input-
remapping code touched. Dependencies and Room schema: unchanged.

### Remaining 2D work (unchanged by this slice)

2D.2 (recreation/resize continuity closure), 2D.3 (input/accessibility/focus
closure), and 2D.4 (performance/resilience + final physical acceptance)
remain exactly as scoped in §12 — this slice closes only the fixed-reader
transform/bounds defect and its two adjacent findings (fit-mode-change reset,
viewport-resize re-clamp).

## 21.1. 2D.1 remediation — Fit Width tall-content blocker (2026-10-02)

**The first 2D.1 pass above was incomplete.** It fixed Fit Page correctly, but
independent QA returned the slice **BLOCKED**: Fit Width with content taller
than the viewport could still be dragged out of bounds into gray, the exact
defect class §21 was supposed to close. The owner's own RP5 "fit width too.
All good" acceptance quoted above did not happen to exercise a page tall
enough, in a landscape viewport, to expose this — the defect requires fitted
content height to exceed the viewport height, which §21's own JVM/instrumented
fixtures never constructed. This section documents the remediation
honestly as incomplete-then-fixed, not as a clean first pass.

### Root cause

`fixedReaderMaxPan` has no fit-mode concept, so `FixedReaderScreen` fed it
Fit Width's fitted content *height* (which routinely exceeds the viewport —
by design, that's what `verticalScroll` is for) the same way it feeds Fit
Page's always-viewport-sized content. Two concrete problems resulted:

1. **Wrong bound.** The formula `max(0, (scaledContent - viewport) / 2)`
   measures excess against the *viewport*, not against content that is
   already taller than the viewport before any zoom. At `scale == 1` with a
   tall page, this yielded a *large positive* Y bound instead of the correct
   `0` — the page could be dragged via `graphicsLayer.translationY` even
   completely unzoomed.
2. **Double vertical movement.** Fit Width's `Image` sits inside
   `Modifier.verticalScroll`, which already owns vertical movement for
   content taller than the viewport. The pinch/pan gesture handler's
   `awaitEachGesture` loop sits on an *ancestor* of that scrollable; in
   Compose's `Main` pointer pass, the descendant `verticalScroll` detector
   sees — and can already consume/scroll from — a one-finger vertical drag
   before the ancestor handler runs, which then *also* read the same raw
   `pan.y` and added it to `translationY`. The same physical drag moved the
   content twice, through two independent systems, neither aware of the
   other.

### Chosen model: verticalScroll-only (Option B)

Rather than deriving a fit-mode-aware Y formula for the shared pan clamp
(Option A — keep `translationY`, just bound it correctly against
`max(contentHeight, viewportHeight)`), the simpler and more predictable model
was chosen: **Fit Width's vertical movement belongs entirely to
`verticalScroll`; the pan transform never touches the Y axis in that mode.**
Horizontal `graphicsLayer` pan is retained for Fit Width (needed once zoomed,
since the fitted width equals the viewport at `scale == 1`). Fit Page is
completely unaffected — it has no scroll container, so it keeps its original
full pinch-zoom + clamped pan X/Y behavior.

Implementation (`FixedReaderTransform.kt` / `FixedReaderScreen.kt`):

- A new pure function, `fixedReaderMaxPanY(contentSize, viewportHeight,
  scale, fitWidth)`, returns `0` unconditionally for Fit Width (at every
  scale) and otherwise delegates to the existing `fixedReaderMaxPan`. This
  keeps the "Fit Width Y is always 0" invariant in one directly JVM-testable
  place instead of duplicated inline checks.
- The gesture handler now only treats a Fit Width gesture as a *transform*
  (pinch-zoom/pan, consuming the touch events) when it is a real multi-finger
  pinch, or a one-finger drag that is horizontal-dominant while already
  zoomed. A one-finger vertical-dominant drag is left unconsumed so
  `verticalScroll`'s own detector handles it natively — this is what
  eliminates the double-movement defect, not merely bounding it.
- `graphicsLayer.translationY` is forced to `0` for Fit Width as a second,
  structural safeguard, even though `panY` itself can no longer become
  nonzero in that mode.
- The viewport-resize re-clamp `LaunchedEffect` uses the same
  `fixedReaderMaxPanY` function, so a resize/rotation/fold cannot resurrect a
  stale nonzero Fit Width `panY` either.
- `pointerInput(state.page, rtl)` → `pointerInput(state.page, rtl, fitWidth)`:
  the gesture coroutine reads `fitWidth` from its enclosing closure for the
  branching above, so it must restart (not silently keep running the old
  closure's logic) when fit mode changes mid-session.

### Test evidence

- **JVM** (`FixedReaderTransformTest.kt`): **35/35 passed** (29 existing + 6
  new). New coverage: `fixedReaderMaxPanY` at scale 1/2/5 for H > V (all
  exactly `0`), the H ≤ V case, a full 5→2→1 zoom-down sequence, confirmation
  that Fit Page's `fixedReaderMaxPanY` still equals the plain
  `fixedReaderMaxPan` result exactly, and confirmation that Fit Width's
  horizontal bound still grows normally with zoom while Y stays locked.
  Two previously weak tests were strengthened rather than left as loose sanity
  checks: `case4` now asserts exact computed values instead of only
  `maxX != maxY`, and `case10` now exercises two genuinely different
  width/height-limited fit shapes (rather than two identical calls) to prove
  there is no format-shaped branch, not merely that equal inputs produce
  equal outputs.
- **Instrumented** (`FixedReaderTransformBoundsTest.kt`, +3 new tests, 8
  total): a new tall-page PDF fixture (`OriginalFixtures.tallPdf`, 400×2400,
  1:6 aspect) in a rotated landscape viewport reliably reproduces fitted
  content height far exceeding viewport height. New tests, driven through the
  real production gesture code (not synthetic adb swipes): zoomed-in drag
  beyond the TOP edge stays bounded (`panY == 0`, scroll floor holds at `0`);
  zoomed-in drag beyond the BOTTOM edge stays bounded (`panY == 0`, scroll
  ceiling holds at its real `maxValue`); at `scale == 1`, vertical drags move
  the real `verticalScroll` state (not `panY`). **8/8 passed on both the
  physical RP5 and the API 35 emulator.**
- **Regression**: `NavigationSmokeTest`, pre-existing and unchanged — **26/26
  passed on both RP5 and emulator.**
- **Full JVM suite**: **192/192 passed, 0 failed, 0 skipped.**
- **Full connected suite, both devices**: see `VALIDATION.md`'s corresponding
  entry for the exact run-level counts from this remediation pass.
- `git diff --check` against `7964a98`: **PASS.**

### Scope discipline confirmed

`git diff --stat` against `7964a98` (the pre-remediation commit) touches
exactly the same four files as the original 2D.1 slice (`FixedReaderScreen.kt`,
`FixedReaderTransform.kt`, the JVM test, the instrumented test) plus one
fixture addition (`OriginalFixtures.tallPdf`) — no PDF rasterization, CBZ
decoding, EPUB, persistence, Room schema, or dependency changes.

### RP5 physical acceptance for this remediation

**OWNER VISUAL OBSERVATION: PASS (2026-10-02).** The owner installed the
committed remediation build (`06bcc14`) on the physical RP5 and performed the
real pinch-zoom/drag-to-top/drag-to-bottom sequence on a tall Fit Width page
by hand, plus a Fit Page sanity check: *"ALL GOOD!"* This closes the evidence
gap the remediation's own instrumented-test evidence (above) could not
substitute for.

## 21.2. 2D.1 remediation, round two — zoomed Fit Width top/bottom reachability (2026-10-02)

**The `06bcc14` remediation above was itself incomplete.** It correctly
removed the gray-escape and double-vertical-movement defects, but a second,
independent QA pass found a third defect in the same area: with a tall Fit
Width page zoomed in, the outer top/bottom fraction of the page became
permanently unreachable by scrolling. This section documents that finding and
its fix honestly, as a second incomplete-then-fixed iteration on the same
slice, not as a clean pass.

### Root cause

`graphicsLayer`'s `scaleX`/`scaleY` visually scale the `Image` around its own
layout center without changing its *layout* size. `06bcc14` correctly made
`verticalScroll` the sole owner of Fit Width's vertical movement, but
`verticalScroll` only ever measured the **unscaled** fitted height `H` of the
`Image`'s own layout box — never the visually-scaled `scale * H` the
`graphicsLayer` actually painted. At `scale == 2`, a center-origin scale
bulges the visual content by `(scale-1)*H/2` above and below the Image's own
layout bounds; that bulge was invisible to `verticalScroll`'s measurement, so
its scroll range stayed `max(0, H - viewport)` instead of the
visually-correct `max(0, scale*H - viewport)`. Concretely: at `scale == 2`
roughly the outer 25% of the page at each end was stuck outside any reachable
scroll position; at `scale == 5`, roughly the outer 40% at each end.

### Chosen architecture: scroll-range compensation (the plan's preferred option)

The investigation confirmed the preferred architecture was structurally
achievable as built: `FixedReaderScreen`'s Fit Width render path is
`Box(reader_page, pointerInput, onSizeChanged) -> Box(verticalScroll) ->
Image(graphicsLayer)` with no intervening layout between the scrollable
container and the Image. This meant blank spacer space could be inserted
*outside* the `Image`'s own `graphicsLayer` modifier (which is exactly where
it must live — spacers inside the scaled layer would themselves be scaled,
defeating the compensation) while staying inside the scrollable container, by
replacing the single-child `Box` with a `Column`:
`Spacer(overflow) -> Image(graphicsLayer) -> Spacer(overflow)`.

A new pure function, `core.reader.FixedReaderTransform.kt`'s
`fixedReaderVerticalScaleOverflow(contentHeight, scale)`, returns
`max(0, (scale-1) * contentHeight / 2)` — exactly the per-side visual bulge.
`FixedReaderScreen` reserves that much blank layout space (converted to `Dp`
via `LocalDensity`) above and below the `Image`, so the scrollable column's
total measured height becomes `H + 2*overflow == scale*H`, matching the
visual extent exactly and handing `verticalScroll` the correct range for
free. No second vertical-movement mechanism was introduced:
`graphicsLayer.translationY` stays forced to `0` in Fit Width exactly as
`06bcc14` established, and the gesture-classification logic from that pass
(vertical-dominant one-finger drags left unconsumed for `verticalScroll`) is
untouched. Fallback A (resize the `Image`'s own layout to `scale*H` directly)
and fallback B (bounded post-scroll-edge `translationY`) were not needed —
the spacer-based compensation worked structurally on the first attempt.

### Test evidence and a self-correction during this pass

**JVM** (`FixedReaderTransformTest.kt`): **43/43 passed** (35 existing + 8
new covering `fixedReaderVerticalScaleOverflow` at scale 1/2/5, linear growth,
the `H + 2*overflow == scale*H` identity at scale 1/2/5, and the same
defensive non-finite/non-positive/extreme-value cases as the rest of the
file).

**Instrumented** (`FixedReaderTransformBoundsTest.kt`, 15 tests total): this
pass's first attempt at the new reachability tests **initially failed on
both devices**, and the failures were genuinely instructive rather than
calibration noise to wave away:

- Two tests asserted the fixture's existing top/bottom text markers
  (`y=80`/`y=2340` of the 400x2400 bitmap) were visible at the scroll
  floor/ceiling. Both failed on both devices at `scale == 2`, and worse at
  `scale == 5` — not because the fix was wrong, but because this fixture's
  extreme 1:6 aspect ratio in a wide landscape viewport means a single
  screenful at `scale == 2` shows only the nearest ~3-6% of the page, and at
  `scale == 5` only ~1-2% (confirmed with real on-device numbers: visible
  window sizes of 59-67 bitmap px at 2x and 24-27 bitmap px at 5x, out of a
  2400px-tall page, varying slightly between the RP5 and the emulator's own
  exact aspect ratio). The original markers, placed at 80px and 2340px in
  from each edge, simply sat just outside that thin field of view. Fix:
  moved the markers to `y=30`/`y=2380` (closer to the true edges) for the
  `scale == 2` tests, and replaced the `scale == 5` stress case's proof with
  a direct, device-independent check that the real `verticalScroll.maxValue`
  equals the expected `scale*H - viewport` — which is both the actual
  regression-sensitive fact being tested and immune to any fixed marker
  position's field-of-view problem at extreme zoom (see
  `assertScrollRangeMatchesScaledContent`'s doc in the test file).
- One new test, plus one pre-existing `06bcc14` test
  (`fitWidthTallPageBottomDragBeyondEdgeStaysWithinBounds`), initially
  flaked on reaching the scroll ceiling before asserting against it: a fixed
  swipe-repeat count calibrated for `06bcc14`'s flat `H - viewport` range
  fell short once this pass correctly made the range scale with zoom (a much
  larger ceiling at `scale == 5` than a fixed repeat count assumed). Fixed by
  replacing the fixed-repeat swipe helper with `swipeVerticalToExtreme`,
  which swipes until the real scroll state actually reports it has reached
  its floor/ceiling (capped, so a genuine stuck-scroll bug still fails
  loudly instead of looping forever).

After both fixes, **15/15 instrumented tests passed on both the physical RP5
and the API 35 emulator**, confirmed via direct `adb shell am instrument`
runs (see the coordinator-directed scope note below on why the full
connected suite was not re-run for this iteration).

**Scope note on this pass's validation depth**: at the owner's explicit
mid-task instruction, this iteration's validation was deliberately narrowed
to the focused JVM test class, focused instrumentation via direct `adb shell
am instrument` on both devices, and `git diff --check`/`git diff --stat` —
the full `:app:testDebugUnitTest` suite, `NavigationSmokeTest`, the full
`:app:connectedDebugAndroidTest` suite, and the final combined Gradle gate
were **not** re-run for this specific iteration, to avoid a repeated ~40
minute wait on a narrow fix layered on already-validated shared code. The
owner asked for this tradeoff to be surfaced here for a standing policy
decision rather than re-litigated per iteration. `git diff --check`: PASS.
`git diff --stat` against `b2c8761` touches exactly the same files as the
first 2D.1 remediation plus the fixture's marker-position adjustment
(`OriginalFixtures.tallPdf`) — no PDF rasterization, CBZ decoding, EPUB,
persistence, Room schema, or dependency changes.

### Scope discipline confirmed

`git diff --stat` against `b2c8761` (the pre-round-two commit): five files —
`FixedReaderScreen.kt`, `FixedReaderTransform.kt`, the JVM test, the
instrumented test, and `OriginalFixtures.kt` (marker position only, same
fixture, same page dimensions). No renderer, decoder, persistence, or
dependency changes.

### RP5 owner physical acceptance for this remediation

**NOT YET OBTAINED — pending a separate live session with the owner.** This
pass's own validation is limited to automated JVM and instrumented evidence
on both devices (above); a subagent has no live channel to the owner. The
specific live check still needed: zoom a tall Fit Width page to ~2x on the
physical RP5, scroll fully to the top and confirm the top of the page is
actually visible (not merely that the app does not crash), scroll fully to
the bottom and confirm the bottom is visible, confirm no gray escape dragging
past either edge, confirm horizontal pan still works while zoomed, confirm
double-tap zoom reset still works and leaves the page reachable, confirm Fit
Page is still unaffected, and ideally repeat with a CBZ.
