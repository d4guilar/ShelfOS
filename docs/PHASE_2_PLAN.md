# Phase 2 plan: the Reading phase

Status: **2A is accepted** (2026-09-24, merged to `main`; Codex verdict: PASS
WITH NON-BLOCKING FINDINGS). **2A.1 implementation is complete; a first Codex
review (2026-09-25) returned CHANGES REQUIRED for a modality-tracking defect,
which was remediated; a second Codex review then returned PASS WITH
NON-BLOCKING FINDINGS.** A small R3 cleanup pass closes those findings (see
below) — final cleanup complete, **not yet merged through the normal PR
process, and not yet re-confirmed by Codex.** Do not mark 2A.1 accepted until
that confirmation lands. See `VALIDATION.md`'s "Phase 2A.1" sections for
evidence and explicitly-unclaimed items. 2B/2C/2D remain planned only; none of
their work has started. This document is the canonical Phase 2 planning
location referenced by [`ROADMAP.md`](ROADMAP.md#phase-2--reading).

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
reimplementing it. It is broken into five increments (2A, 2A.1, 2B, 2C, 2D),
each independently mergeable and gated by its own acceptance criteria. Only 2A
is implemented as of this document's latest revision; 2A.1 is documented as an
accepted future direction but not implemented (see its section below).

## 3. Increments

### 2A — Reader interaction, chrome and navigation foundation (accepted 2026-09-25)

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
  `EpubActivity`'s `EpubSurface` container, the latter now also tagged
  `epub_page`) carry a `stateDescription` ("Controls shown" / "Controls
  hidden") reflecting the real state, and — only while chrome is hidden — an
  `onClick(label = "Show reader controls")` accessibility action that actually
  reveals chrome. **Remediated 2026-09-25** after Codex review (R2) found the
  first version announced "double tap to show controls" while double-tap was
  actually reserved for zoom and no accessibility action existed at all, so
  TalkBack could not reliably perform the announced action; when chrome is
  visible, no such action is exposed (nothing misleading is announced). Normal
  touch behavior (single center tap toggles chrome, double tap zooms) is
  unchanged in both readers.
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
- [x] `:app:connectedDebugAndroidTest` (`NavigationSmokeTest`) passes on an
      emulator that supports Espresso/Compose UI test instrumentation:
      **passed on `shelfos-phase0` (API 35), both default phone and forced
      expanded/tablet viewports.** Still blocked on `shelfos-api37` by a
      pre-existing, documented environment gap (`ROADMAP.md`'s "API 37
      automated-UI-test tooling gap"), not a regression from this change and
      not claimed fixed here. See §6.
- [x] RP5 physical validation of the fixed Back behavior and the accessibility
      remediation. Performed during independent Codex re-review (2026-09-25):
      real-hardware EPUB/PDF/CBZ execution, on-device Back-semantics
      validation, PDF resume, CBZ D-pad input, Home/Recent-Apps safety, a
      focused 6/6-passing instrumentation pass, no crashes in logcat. See
      `VALIDATION.md` for the full breakdown and its explicit distinction
      between hardware execution over ADB and physically pressing the RP5's
      own buttons.
- [ ] Galaxy Tab A physical validation. Optional/periodic per the device
      strategy in §7; not performed as of this pass, documented as pending.
- [x] **Accessibility remediation** (Codex review R2, 2026-09-25): the reader
      chrome-toggle surfaces now expose a real `onClick` accessibility action
      while chrome is hidden, instead of only descriptive text that didn't
      correspond to an actionable TalkBack gesture. See the "Scope
      implemented" accessibility bullet above and new tests
      `accessibilityActionRevealsHiddenControlsInFixedReader`/
      `...InEpubReader` in `NavigationSmokeTest.kt`. Independently confirmed
      resolved by Codex re-review. A manual TalkBack walkthrough was still not
      performed — TalkBack was unavailable on the test targets used.

### 2A.1 — Input Discovery & Controller/Keyboard Hint Polish (implemented 2026-09-25)

**Purpose.** ShelfOS already supports controller/keyboard reader navigation
(Phase 1's `ShelfCommand`/`InputMapper`, hardened in 2A), but nothing in the UI
let a user discover that capability without experimenting. Media frontends
such as Beacon and ES-DE demonstrate the value of subtle contextual input
hints — they were used as interaction references only; no proprietary assets,
controller artwork, layouts, branding or sounds were copied. ShelfOS's own
visual system (`core.designsystem`/`core.theme` tokens) is used throughout.

**Implemented scope:**
- `core.input.InputModality` (`TOUCH`/`KEYBOARD`/`CONTROLLER`) and
  `KeyEvent.inputModalityOrNull()` (`core/input/InputHints.kt`): classifies a
  real key event's *raw* input modality — independently of whether it becomes
  a `ShelfCommand` — by keycode where unambiguous (`L1`/`R1`/`GAMEPAD_A`/
  `GAMEPAD_B`/`START` are gamepad-exclusive keycodes a keyboard cannot
  generate) and, for the keys a keyboard's arrows/Enter and a gamepad's D-pad
  report identically, from the reporting sources — the specific event's own
  `source` takes precedence over a device's aggregate `sources`, which is only
  a fallback when the event itself reports none. Returns `null` (no modality
  signal) for the raw system Back/Home keys and unclassified keys. Never
  inspects device model/name.
- `core.input.InputHints.hint(command, modality, rightToLeft)`: resolves the
  on-screen label from a small candidate catalog, each candidate validated
  against the real `InputMapper.command()` before being shown. A displayed
  hint can never disagree with `InputMapper`'s current behavior for a
  candidate the catalog covers — this does **not** mean an arbitrary future
  rebinding is automatically discoverable; a new binding must also be added to
  the catalog. Returns `null` for `TOUCH` (no hint shown).
- `core.designsystem.InputKeycap`: the reusable monochrome keycap badge —
  small, rounded, theme-aware (`t.colors.muted`/`t.colors.divider`/
  `t.shapes.extraSmall`), visually secondary, no bright console branding.
- Reader-chrome integration in both `FixedReaderScreen.kt` (PDF/CBZ, shared) and
  `EpubActivity.kt` (EPUB): Previous/Next hints are merged into the existing
  Previous/Next buttons' `contentDescription` (e.g. `"Next, R1"`) rather than
  a separate node; the Back hint is a decorative, `clearAndSetSemantics`-only
  keycap next to the Library button (no exact existing "Back" control to merge
  into). All hints live inside the existing `if (controls) { ... }` chrome
  blocks, so hiding chrome removes them from the tree automatically — no
  separate visibility mechanism was needed.
- Recent-modality tracking is local per-reader-screen state
  (`rememberSaveable`), updated for every key event reaching the reader's key
  handler — before, and independently of, dispatching any semantic command —
  and by real touch gestures (tap/double-tap/swipe-turn in `FixedReaderScreen`,
  center-tap in `EpubSurface`); never by a button `onClick`, since a click may
  itself have been keyboard/gamepad-activated. Applying the update
  unconditionally (gated only on `inputModalityOrNull()` returning non-null)
  means it also covers keys that never produce a reader `ShelfCommand` at all,
  such as pure Compose focus navigation.
- Keyboard hints are first-class, not deferred: `←`/`→`/`Esc` render exactly
  like controller hints, including the RTL swap (`InputHints.hint` reuses
  `InputMapper.command()`'s own RTL branch, so it can never disagree with it).

**Remediation (2026-09-25), after independent Codex review returned CHANGES
REQUIRED:** the first version excluded modality updates whenever the
*resolved semantic command* equaled `ShelfCommand.BACK`
(`if (command != ShelfCommand.BACK) modality = ...`), on the reasoning that
this would stop the raw system Back key from claiming a modality. That
reasoning was already moot: `InputMapper` maps `InputKey.BACK`/`InputKey.HOME`
to no command at all (`null`), so raw Back never entered that branch in either
version — this was true before the fix as well as after, and the comment
claiming RP5 testing showed raw Back "defaulting to KEYBOARD" through this
code path was inaccurate and has been corrected. The actual defect was
simpler: `InputKey.ESCAPE` and `InputKey.GAMEPAD_B` both map to
`ShelfCommand.BACK`, and excluding that resolved command from the modality
update meant this real, attributable keyboard/controller input silently
failed to update the displayed hint style, even though it correctly still
revealed/exited the reader. The fix separates "what command does this
produce" from "what raw modality does this represent": `inputModalityOrNull()`
excludes only the raw system Back/Home keys directly, at the raw-key-
classification layer, and both readers apply it unconditionally before
dispatching any command — so Escape now correctly establishes `KEYBOARD` and
gamepad B correctly establishes `CONTROLLER` (each still also reveals hidden
chrome per ADR-0023), while the raw system Back key/gesture still never
claims a modality of its own. The ambiguous-source precedence was also
corrected to prefer the specific event's own source over a hybrid device's
aggregate sources, via a small extracted `resolveInputSources`/
`isGamepadSource` helper pair that is pure int-constant logic and directly
unit-testable without any real or fake `InputDevice`. See `docs/design/
INPUT_SYSTEM.md` §10 for the full explanation, `InputHintsTest` (JVM) for the
deterministic source-precedence proof, `InputModalityClassificationTest.kt`
(instrumented — real `KeyEvent` classification by keycode isn't mockable in a
plain JVM test) for raw-key coverage, and the `NavigationSmokeTest` transition
cases (including an explicit `CONTROLLER → Escape → KEYBOARD` sequence) for
end-to-end regression coverage.

**Known gap, honestly recorded, not fixed in this pass:** touch-modality
detection for the EPUB reader is only fully reliable via the center tap.
Edge taps that turn EPUB pages are handled entirely inside Readium's
`DirectionalNavigationAdapter` and never reach `EpubActivity`'s Compose tree,
so a user who turns EPUB pages purely by edge-tapping (without ever
center-tapping) after a keyboard/controller session will keep seeing the
stale hint set until they do center-tap or press a key. Fixing this would
require hooking Readium's own gesture pipeline, which is out of scope for this
increment's size.

**Deferred, not part of this slice:** a shared `ReaderChrome` component (the
Previous/Next/Back duplication between `FixedReaderScreen`/`EpubActivity`
identified in 2A remains); unifying touch itself into the `ShelfCommand`
semantic layer (touch still drives readers via raw `pointerInput`/Readium
listeners, only the *hint* layer is command-derived); remapped-control
reflection; alternate controller layouts. None of these were built
speculatively ahead of need.

**Input-modality-aware presentation (as implemented):**
- **Touch:** the active modality starts as `TOUCH`; no hints render, and a
  real touch gesture always clears any previously-shown controller/keyboard
  hints back to none.
- **Controller:** a gamepad-exclusive key (`L1`/`R1`/`GAMEPAD_A`/`GAMEPAD_B`/
  `START`) always switches to `CONTROLLER`; an ambiguous D-pad/Enter key
  switches to `CONTROLLER` only if its `InputDevice` reports gamepad sources.
- **Keyboard:** any other real key event (arrows/Escape/Page Up/Page Down/etc.
  not from a gamepad-sourced device) switches to `KEYBOARD`.
- **Immersive mode:** hidden chrome hides hints with it, confirmed both by
  instrumented test and RP5 screenshot evidence.

**UX principles:**
1. Discoverability without clutter.
2. The publication remains the hero.
3. Hints describe actual behavior.
4. Input hints come from semantic commands/bindings, not individual-screen
   strings.
5. Controller support should feel intentional rather than accidental.
6. Touch-only users should not be presented with irrelevant controller chrome.
7. Hints must remain readable on compact handheld devices such as RP5.
8. Hints must also scale appropriately on tablets.
9. Accessibility names should describe actions, not merely announce raw
   button names.
10. ShelfOS should use its own neutral visual language rather than imitate a
    specific console platform.

**Sequencing.** 2A.1 sits between 2A and 2B because input hints directly
extend the reader-chrome and semantic-input work 2A just closed, and they
benefit EPUB/PDF/CBZ together rather than being comics-specific — so it is not
postponed to Phase 3 merely because controller navigation is especially useful
for comics:

```
2A   — Reader interaction/chrome/navigation
2A.1 — Input Discovery & Controller Hint Polish
2B   — EPUB everyday-reading improvements
2C   — Original PDF hardening/fidelity
2D   — continuity/accessibility/performance closure
```

**Acceptance criteria:**
- [x] Compiles (`:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`).
- [x] `:app:assembleDebug`, `:app:testDebugUnitTest` (including `InputHintsTest`),
      `:app:lintDebug`, `:app:assembleDebugAndroidTest` pass.
- [x] `:app:connectedDebugAndroidTest`: `NavigationSmokeTest` (26 tests, after
      remediation added 5 modality-transition/RTL-hint cases) and the
      instrumented `InputModalityClassificationTest` (10 tests, real-`KeyEvent`
      raw-classification coverage a plain JVM test cannot exercise) both pass
      on `shelfos-phase0` (API 35) — the known-good emulator for this class of
      test, per the API 37 Espresso/InputManager tooling gap recorded in 2A.
- [x] RP5 (Retroid Pocket 5, Android 13/API 33) physical validation: both test
      classes pass on-device (26/26, 10/10), plus manual real-hardware
      confirmation (screenshots) of the full requested sequence — TOUCH+B
      reveals chrome and establishes CONTROLLER hints without exiting, R1/L1
      match Next/Previous, a real touch tap clears hints, a controller press
      restores them, Escape establishes KEYBOARD hints, and raw Back reveals
      chrome while leaving the established modality untouched. See
      `VALIDATION.md`. Separately, the owner has manually pressed the RP5's
      actual physical controller controls and confirmed they activate the
      corresponding controller-hint UI — owner-verified physical evidence,
      distinct from the ADB-injected evidence above; see `VALIDATION.md`'s
      "Phase 2A.1 owner physical-controller verification" entry.
- [x] Keyboard hint validation: automated (emulator + RP5) via `PAGE_DOWN`/
      `PAGE_UP`/`ESCAPE` injection — `PAGE_DOWN`/`PAGE_UP` chosen over
      `DPAD_LEFT/RIGHT` for the page-turn cases specifically because the
      emulator's/RP5's synthetic D-pad injection was ambiguous for modality
      classification; a real physical tablet keyboard was not available in
      this environment (see "Known limitations" in `VALIDATION.md`).
- [ ] Galaxy Tab A physical validation. Optional/periodic per the device
      strategy in §7; not performed in this pass, documented as pending.
- [x] First independent Codex review: **CHANGES REQUIRED** (the modality-
      tracking defect described in the remediation note above). Remediated and
      re-validated.
- [x] Second independent Codex review: **PASS WITH NON-BLOCKING FINDINGS**
      (test-quality and documentation-accuracy items — see the R3 cleanup
      note below). Closed in this same pass; **not yet re-confirmed by
      Codex, not yet merged.**

**R3 cleanup (2026-09-25), after the second Codex review:** four non-blocking
findings, none touching production behavior beyond a pure refactor:
1. The source-precedence regression test used a nonexistent device ID, so
   `device == null` and it never actually exercised a real hybrid device's
   aggregate sources. Fixed by extracting `resolveInputSources`/
   `isGamepadSource` — pure int-constant logic, directly unit-testable in a
   plain JVM test (`InputHintsTest`) without any real or fake `InputDevice`,
   including a test that would fail if precedence reverted to
   aggregate-device-first.
2. The `escapeAndGamepadBEstablishModalityWhileRevealingChromeInFixedReader`
   test claimed `CONTROLLER → Escape` coverage but never actually established
   `CONTROLLER` immediately before pressing Escape. Fixed by replacing the
   redundant `KEYBOARD → Escape → KEYBOARD` no-op step with an explicit
   `TOUCH → GAMEPAD_B → CONTROLLER` step first.
3. This document's own status line understated 2A.1's actual state after the
   first remediation. Corrected above.
4. The remediation history (here, `VALIDATION.md`, and `docs/design/
   INPUT_SYSTEM.md` §10) claimed raw system Back "fell through to an
   unguarded KEYBOARD default" as the observed defect. Re-verified against
   the actual pre-remediation commit: `InputMapper` mapped `InputKey.BACK` to
   no command in both the first implementation and the fix, so raw Back never
   entered the affected branch in either version. The real defect was that
   Escape/gamepad B — which do produce `ShelfCommand.BACK` — were wrongly
   excluded from the modality update. Corrected in all three locations.

### 2B — EPUB everyday-reading improvements (planned, not started)

- Chapter navigation polish (the existing Chapters dialog already lists
  chapters; evaluate current-chapter highlighting and search-within-list).
- In-publication search where the Readium adapter supports it.
- Bookmarks (a discrete saved-locations list; distinct from resume position).
- Pagination vs. scrolling preference where the adapter supports it.
- EPUB typography refinement beyond the three presets already implemented
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
| Controller hint discovery | Press a gamepad button (R1/L1/B); confirm hints appear next to Previous/Next/Back, matching the actual binding | Emulator (injected), RP5 (real hardware) |
| Keyboard hint discovery | Press a keyboard key (Page Down/Up, Escape); confirm hints appear as arrows/Esc, matching the actual binding, including under RTL | Emulator (injected), RP5 (real hardware) |
| Modality switching | Alternate controller input, keyboard input and a real touch tap; confirm hints switch/clear correctly each time, including across a Back press | Emulator, RP5 |
| Tablet reader layout sanity (optional/periodic) | Open each format, check chrome scale, interaction visibility, obvious performance regression | Galaxy Tab A |

## 9. Explicit out-of-scope confirmation for this run

2A was implemented and remediated against independent Codex review, then
accepted and merged to `main`. This pass implemented 2A.1 only, on top of that
merge, on branch `phase-2/input-hints`. No code for user-editable bindings,
a complete remapping UI, controller profiles, console-brand-specific glyph
packs, controller detection by product/model database, platform-specific
visual modes, or any 2B/2C/2D work exists on this branch. No item from §4's
out-of-scope list was touched.
