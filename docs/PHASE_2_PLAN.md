# Phase 2 plan: the Reading phase

Status: increment 2A implemented 2026-09-25 on branch `phase-2/reading`, not yet
merged or accepted. 2B/2C/2D are planned only; none of their work has started.
This document is the canonical Phase 2 planning location referenced by
[`ROADMAP.md`](ROADMAP.md#phase-2--reading).

## 1. Reconciling Phase 1 acceptance with the roadmap

Phase 1 (accepted 2026-09-24, [ADR-0017](adr/0017-phase-one-reading-scope.md))
already implemented and validated: persisted `LibraryItem`s, EPUB opening via
Readium, Original PDF opening (`android.graphics.pdf.PdfRenderer`), CBZ
image-sequence reading, resume/progress persistence, basic EPUB typography,
basic zoom/fit behavior, appearance persistence, touch/keyboard/gamepad
semantic-command foundations for page turning and menu access, reader state
restoration across configuration changes and process recreation, immersive
comic reading, physical-controller validation, and the accessibility/
performance closure recorded for that scope.

`ROADMAP.md`'s Phase 2 and Phase 3 sections previously said this work was
"in the Phase 1 work in progress" and `docs/features/READER.md` said reader
adapters were "in the unfinished Phase 1 working tree" — both stale relative
to the acceptance recorded in `ARCHITECTURE.md`, `PHASE_1_PLAN.md` and
`VALIDATION.md`. That wording is corrected as part of this plan (see the diffs
to those three files landed alongside this document). Phase 2 is **not** a
green-field reading phase; it is refinement and extension of an already-shipped
reader.

## 2. Phase 2 goal

> ShelfOS becomes a useful everyday reader.

Phase 2 builds on the accepted Phase 1 reader foundation rather than
reimplementing it. It is broken into four increments, each independently
mergeable and gated by its own acceptance criteria. Only 2A is implemented by
this document's authoring pass.

## 3. Increments

### 2A — Reader interaction, chrome and navigation foundation (this pass)

**Motivation.** A Samsung Galaxy Tab A field test found that a real user
pressed Android Back while reader chrome was hidden and nearly exited the
reader before understanding how to bring controls back. `docs/design/
READER_UX.md` and `docs/design/INPUT_SYSTEM.md` both left the exact Back
behavior as an open design question pending this increment.

**Scope implemented:**
- Reader chrome visibility and Back semantics ([ADR-0023](adr/0023-reader-chrome-back-semantics.md)):
  Back reveals hidden chrome instead of leaving the reader; only once chrome is
  visible does Back exit. Applied identically to the system Back
  gesture/button (`BackHandler`) and the `ShelfCommand.BACK` key path
  (Escape/gamepad B), in both the Original (PDF/CBZ) reader and the EPUB
  reader — these were two independently broken call sites with the same bug.
- Accessibility: reader chrome-toggle areas (`FixedReaderScreen`'s page `Box`,
  `EpubActivity`'s `EpubSurface` container) now carry a `stateDescription`
  ("Controls shown" / "Controls hidden. Double tap to show controls.") so
  TalkBack users get feedback on the current chrome state and how to change it.
- Reduced motion: chrome show/hide remains an instant, unanimated state change
  (no `AnimatedVisibility` was introduced). This trivially honors reduced
  motion, since there is no motion. `Context.reducedMotionEnabled()`
  (`core.theme.ReducedMotion`) remains the hook for the library cover-expansion
  transition and must be reused if a future increment adds an animated chrome
  transition.
- Tests: replaced the one existing Back-related test (which only exercised
  starting from *visible* chrome, never the actual field-reported bug) with
  tests that start from *hidden* chrome and assert the reveal-then-exit
  sequence, for both the Original reader and EPUB.

**Explicitly not done in 2A** (see 2B/2C/2D and the out-of-scope list below):
- No shared `ReaderChrome` composable extraction. `FixedReaderScreen.kt` and
  `EpubActivity.kt` still duplicate the `controls`/`appearance`/focus-tracking
  state and top/bottom-row layout logic. This duplication was identified
  during investigation as a natural refactor target, but extracting it is a
  larger, riskier change than the scoped bug fix and is deferred rather than
  bundled in opportunistically.
- Touch gestures (tap-to-page, pinch-zoom, center-tap-to-toggle) remain
  screen-specific `pointerInput` handling, not routed through
  `ShelfCommand`/`InputMapper`. Unifying touch into the semantic command layer
  (as `ADR-0006` and `ARCHITECTURE.md` §12 describe conceptually) is deferred.
- No PDF/CBZ rendering-fidelity changes. See §5 for the investigation findings
  recorded (not acted on) in this pass.

**Acceptance criteria:**
- [x] Compiles (`:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`).
- [x] `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`,
      `:app:assembleDebugAndroidTest` pass.
- [ ] `:app:connectedDebugAndroidTest` (`NavigationSmokeTest`) passes on an
      emulator that supports Espresso/Compose UI test instrumentation. See §6 —
      blocked in this run by a pre-existing, documented environment gap
      (`ROADMAP.md`'s "API 37 automated-UI-test tooling gap"), not a
      regression from this change; retry is pending an emulator/tooling
      combination that supports it.
- [ ] RP5 physical validation of the fixed Back behavior. Not performed in this
      run — no RP5 device was available in this environment. See §6.
- [ ] Galaxy Tab A physical validation. Optional/periodic per the device
      strategy in §7; not performed in this run, documented as pending.

### 2B — EPUB everyday-reading improvements (planned, not started)

- Chapter navigation polish (the existing Chapters dialog already lists
  chapters; evaluate current-chapter highlighting and search-within-list).
- In-publication search where the Readium adapter supports it.
- Bookmarks (a discrete saved-locations list; distinct from resume position).
- Pagination vs. scrolling preference where the adapter supports it.
- EPUB typography refinement beyond the four presets already implemented
  (Editorial/Clean/Spacious plus size/line-height/margin sliders).
- Dark-reading behavior (a reading-surface dark mode independent of the
  Classic/Dark app theme, e.g. sepia/night page colors — `ReaderAppearance.kt`
  already has a `Theme`/`Light`/`Dark`/`Paper` `Page colors` group; 2B should
  evaluate whether this already satisfies the roadmap's "dark reading mode"
  deliverable or whether more is needed).
- Custom-font architecture without unnecessarily bundling many fonts (the
  Phase 1 comment in `app/build.gradle.kts` already excludes bundled
  accessibility fonts from assets; 2B should evaluate a minimal
  user-supplied-font path rather than adding a large bundled font set).

### 2C — Original PDF hardening and fidelity (planned, not started)

Motivated by an unresolved field observation (§5): a New X-Men PDF looked
blurry on the Galaxy Tab A, especially text, and it is not yet known whether
the source file, the conversion that produced it, or ShelfOS's own rendering
caused that. 2C is where this gets an actual controlled investigation and, if
warranted, a fix — not 2A.

- Resolution-aware rendering: `FixedReader.kt`'s `PdfPages.render()` currently
  renders every page at a single fixed `MAX_PAGE_PIXELS = 2048`-longest-edge
  bitmap regardless of zoom level or display density. Evaluate re-rendering at
  a higher resolution when the user zooms in, bounded by a memory-safe cap.
- Cache behavior: there is currently no page cache or prefetch (one bitmap
  held at a time, matches `PHASE_1_PLAN.md`'s documented limitation). Evaluate
  a small LRU/prefetch window against modest-device memory limits.
- Explicit evidence distinguishing bad source from ShelfOS degradation before
  changing rendering behavior — do not add upscaling/sharpening to compensate
  for a possibly-already-degraded source; document if the source itself is
  low quality instead.
- CBZ context: the same `MAX_PAGE_PIXELS`/no-cache/no-zoom-rerender pipeline
  is shared with CBZ (`ArchivePages`) through the same `FixedReaderScreen`.
  Native CBZ was field-reported as sharp even on the Galaxy Tab A, so "do
  less" is valid there — 2C should not add processing to CBZ paths that are
  already working well, and should scope any change to what PDF fidelity
  actually needs.
- AI upscaling is explicitly not part of Phase 2.

### 2D — Reader continuity, adaptive/accessibility/performance closure (planned, not started)

- Configuration/process restoration hardening beyond what 2A's chrome-state
  fix touches (rotation, fold/unfold, multi-window resize).
- Adaptive reading layouts (e.g. two-page spreads on wide/tablet/foldable
  viewports) — `COMICS_MANGA.md`'s AUTO-spreads-resolve-to-one-page policy
  from ADR-0017 remains the interim behavior until this lands.
- Keyboard/controller consistency closure across any gaps found while
  building 2B/2C.
- Accessibility closure: TalkBack coverage beyond 2A's chrome-state
  descriptions, focus order review across the reader screens.
- Performance profiling and malformed/large-document resilience passes.
- Final physical-device acceptance gate for the whole Phase 2 scope.

## 4. Out of scope for Phase 2 (all increments)

CBR, Series, virtual Omnibus, Library Sources, folder/bulk import, generic ZIP
library import, Shelves implementation beyond existing accepted naming/state,
Adapted PDF, OCR, AI enhancement, metadata provider expansion, Completion
Cards, Plus/billing, stylus/ink, ShelfOS Home/launcher mode, iOS,
social/cloud functionality, major theme work, guided comic panels, speculative
plugin architecture. CBR remains a high-priority future Comics/Manga item
(see the standing product-feedback record) but is not Phase 2A, and public
attention does not change this scope without the owner's explicit sign-off.

TTS remains research/implementation-if-practical and must not block any Phase
2 increment's acceptance.

## 5. PDF fidelity — pipeline observations recorded during 2A (no changes made)

Per the field-test framing, 2A investigated but did not change PDF rendering.
Findings, as evidence for 2C, not conclusions about fault:

- **Renderer:** `android.graphics.pdf.PdfRenderer` via a `ParcelFileDescriptor`
  (`core/files`), wrapped by `PdfPages` in
  `app/src/main/java/com/d4guilar/shelfos/core/reader/FixedReader.kt`.
- **Requested render size:** `scale = MAX_PAGE_PIXELS / max(page.width,
  page.height)`, `MAX_PAGE_PIXELS = 2048` — every page is rendered once at a
  fixed longest-edge pixel budget, into an `ARGB_8888` bitmap, via
  `RENDER_MODE_FOR_DISPLAY`.
- **Scaling path:** display uses `Modifier.graphicsLayer { scaleX = scale;
  scaleY = scale }` in `FixedReaderScreen.kt` — zooming in past 1x is bitmap
  up-scaling of the same fixed-resolution render, not a PDF re-render at
  higher resolution.
- **Cache behavior:** none. `FixedReaderViewModel.render()` holds exactly one
  page `Bitmap` in state at a time; no prefetch of adjacent pages.
- **Zoom re-render behavior:** zoom never triggers `PdfPages.render()` again at
  a different resolution; it only changes the Compose transform applied to the
  existing bitmap.
- **A plausible, unconfirmed source of visible softness at zoom:** because
  zoom is pure bitmap upscaling of a fixed 2048px-longest-edge render, a page
  viewed at >1x zoom will look softer than a re-render at that zoom level
  would, independent of whatever the source PDF's own quality is. This is a
  pipeline characteristic that could contribute to the field-reported
  blurriness, but it is not established that it is the (sole, or even primary)
  cause — the source file's own resolution/compression was not independently
  measured in this pass. 2C should measure the actual source PDF's embedded
  image resolution before concluding ShelfOS under-renders it.
- **No obvious severe rendering bug was found** (e.g., no evidence of
  accidental double-downscaling, wrong DPI assumption, or corrupt bitmap
  handling) — the pipeline does exactly what its fixed-resolution,
  no-rerender design implies, nothing more, nothing less.

## 6. Validation performed for 2A

**Automated (local, this run):**
- `:app:assembleDebug` — BUILD SUCCESSFUL.
- `:app:testDebugUnitTest` — BUILD SUCCESSFUL.
- `:app:lintDebug` — BUILD SUCCESSFUL.
- `:app:assembleDebugAndroidTest` — BUILD SUCCESSFUL.
- `:app:connectedDebugAndroidTest` (`NavigationSmokeTest`, 14 tests including
  the two new Back-reveal tests) — **all 14 failed with the same
  environment-level error**, `NoSuchMethodException:
  android.hardware.input.InputManager.getInstance`, on `shelfos-api37`
  (a fresh, data-wiped AVD). This is not a test-logic failure; every test in
  the class failed identically at Espresso's `onIdle()` step regardless of
  what it exercises, matching `ROADMAP.md`'s already-documented "API 37
  automated-UI-test tooling gap, neither a ShelfOS defect." Retried against
  `shelfos-phase0`; see the accompanying final report for that run's actual
  result.

**Emulator (manual):** tablet-viewport (`shelfos-api37`, forced
`1600x1000`/160dpi) and default-phone-viewport smoke behavior; see the final
report for what was actually exercised this run.

**RP5 (Retroid Pocket 5, physical, primary debugging target for Phase 2):**
not performed in this authoring pass — no RP5 device was connected to this
environment. This is recorded as **pending field validation**, not claimed as
passed. It should be the first physical check before Codex review or merge.

**Galaxy Tab A (SM-T580, secondary/periodic):** not performed in this run, per
the device strategy in §7 — optional and non-blocking for 2A. Recorded as
pending, not claimed as passed.

## 7. Device / debugging strategy

Documented here because `VALIDATION.md`/`PHASE_1_PLAN.md` did not yet draw
this distinction explicitly:

- **Primary active development targets — the build/debug loop:**
  1. **Android emulator** — used continuously during development for
     repeatable automated and manual debugging; phone/tablet configurations
     and API-range coverage as justified by the active change. Automated
     (`connectedDebugAndroidTest`) checks must remain emulator/CI-friendly.
  2. **Retroid Pocket 5 (RP5)** — the primary *physical* debugging device for
     Phase 2. Used for real-device behavior, keyboard/D-pad/controller
     semantics, performance sanity, and immersive-reader/input validation.
     Real controller testing matters specifically here.
- **Secondary, periodic validation device:**
  3. **Samsung Galaxy Tab A 10.1 (2016), SM-T580, Android 8.1, ~2 GB RAM** —
     *not* an iterative per-commit debug target. Used occasionally for tablet
     layout validation, visual scale/layout review, performance sanity on
     modest 2016-class hardware, and general usability sanity. No
     architecture or UI decision is made specifically to accommodate this
     device, and no device-model checks are introduced. Its USB data
     connection is unreliable; deployment may require transferring the APK
     manually over local Wi-Fi. Development is never blocked waiting on it —
     if a Galaxy Tab A pass cannot be performed immediately, it is documented
     as pending, not fabricated.

In short: **emulator + RP5 = the build/debug loop; Galaxy Tab A = periodic
field validation.**

## 8. Manual test matrix (for RP5/emulator physical validation)

| Area | Steps | Devices |
| --- | --- | --- |
| Back reveals hidden chrome (PDF/CBZ) | Open a PDF or CBZ, tap "Hide controls" (or center-tap), press Back, confirm chrome reappears and reader stays open; press Back again, confirm reader exits | Emulator, RP5 |
| Back reveals hidden chrome (EPUB) | Open an EPUB, hide chrome (center-tap or menu key), press Back, confirm chrome reappears; press Back again, confirm reader exits | Emulator, RP5 |
| Controller/D-pad Back-equivalent | Same as above using gamepad B / Escape on a keyboard | RP5 (physical controller), emulator (keyboard) |
| Rapid repeated input | Rapidly toggle chrome and press Back/turn pages repeatedly; confirm no crash, no stuck state | Emulator, RP5 |
| Resume/reopen | Open a publication, turn pages, exit via the new Back contract, reopen from Library, confirm position resumed | Emulator, RP5 |
| Compact vs. expanded layout | Repeat chrome/Back checks in both phone (compact) and tablet (expanded) window sizes | Emulator |
| Tablet reader layout sanity (optional/periodic) | Open each format, check chrome scale, interaction visibility, obvious performance regression | Galaxy Tab A |

## 9. Explicit out-of-scope confirmation for this run

Only 2A was implemented. 2B, 2C and 2D are planning-only in this document; no
code for them exists. No item from §4's out-of-scope list was touched.
