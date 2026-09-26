# Phase 2 plan: the Reading phase

Status: **2A is accepted** (2026-09-24, merged to `main`; Codex verdict: PASS
WITH NON-BLOCKING FINDINGS). **2A.1 is accepted and merged** (2026-09-25/26,
squash-merged to `main` via PR #5 at commit `44c8f10600f93f875a3cfb685c27f47c25929c29`,
after a first Codex review returned CHANGES REQUIRED for a modality-tracking
defect, remediation, a second Codex review that returned PASS WITH
NON-BLOCKING FINDINGS, an R3 cleanup pass closing those findings, and final
Codex confirmation). The post-merge maintenance pass
(`maintenance/epub-recreation-back-test`, see its note under 2A.1 below)
corrected stale Phase-2A test debt in `EpubRecreationTest.kt` and was itself
**accepted and merged to `main` via PR #6**. See `VALIDATION.md`'s "Phase 2A.1"
sections for evidence and explicitly-unclaimed items. **2B (EPUB everyday-reading
improvements) is in discovery/implementation-planning** as of this pass
(`phase-2/epub-everyday-reading`, base `8430566396e98196752d9ec9f33910a2e248b039`)
— no 2B production code has been implemented yet; see §3's 2B section for the
grounded capability findings and the proposed internal slice sequence. 2C/2D
remain planned only; none of their work has started. This document is the
canonical Phase 2 planning location referenced by
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
reimplementing it. It is broken into five increments (2A, 2A.1, 2B, 2C, 2D),
each independently mergeable and gated by its own acceptance criteria. 2A and
2A.1 are both accepted and merged (see their sections below and
`VALIDATION.md` for the full history). 2B is now further broken into four
internal slices (2B.1–2B.4, see below) after a discovery/implementation-
planning pass; none of 2B's own implementation exists yet. 2C/2D remain
planned only; none of
their work has started.

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

### 2A.1 — Input Discovery & Controller/Keyboard Hint Polish (accepted 2026-09-26)

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
      note below). Closed in the same pass, then **merged to `main` via PR #5**
      (squash commit `44c8f10600f93f875a3cfb685c27f47c25929c29`) after final
      Codex confirmation.

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

**Post-merge maintenance (2026-09-26), on `maintenance/epub-recreation-back-test`:**
2A.1's merge surfaced stale test debt that actually predates 2A.1 itself:
`EpubRecreationTest.kt`'s `readerUiStateSurvivesRecreationAndAppliedAppearanceReloads`
test was last touched in Phase 1 and never updated when Phase 2A's
[ADR-0023](adr/0023-reader-chrome-back-semantics.md) changed Back's contract —
it pressed `KEYCODE_BACK` while chrome was visible expecting chrome to hide
and the reader to stay open, but under the accepted ADR-0023 behavior,
visible-chrome Back now exits the reader, which broke the test's later
rotation/recreation assertions. This is Phase-2A-caused test debt, not a
Phase 2A.1 production defect, and not a regression introduced by 2A.1's own
work. Fixed by hiding chrome via `KEYCODE_MENU` (the `OPEN_MENU` semantic
command already used for this exact purpose in `NavigationSmokeTest.kt`)
instead of `KEYCODE_BACK`; Back's reveal-then-exit contract is unchanged and
already covered separately by `NavigationSmokeTest`. No production code
changed. **Accepted and merged to `main` via PR #6.**

### 2B — EPUB everyday-reading improvements (discovery/implementation planning, 2026-09-26)

**Scope (unchanged from the roadmap, not silently expanded):** chapter-navigation
polish, in-publication EPUB search where Readium supports it, bookmarks,
pagination vs. scrolling where supported, typography refinement, reading-surface
dark/sepia/light behavior, minimal custom-font architecture. This pass is
discovery and implementation planning only, on branch
`phase-2/epub-everyday-reading` (base `main` at
`8430566396e98196752d9ec9f33910a2e248b039`). No 2B production code exists yet.

#### 2B.0 Current EPUB baseline (as implemented, verified by reading the source)

- **Chapters:** `EpubSession.chapters` (`core/reader/EpubReader.kt`) already
  builds a flat, depth-indented `List<Pair<title, href>>` from
  `publication.tableOfContents.ifEmpty { publication.readingOrder }`.
  `EpubController.chapter(session, href)` already resolves the `Link` and calls
  `navigator.go(link, animated = false)`. `EpubActivity`'s Chapters `AlertDialog`
  already lists and navigates chapters. **Missing:** no current-chapter
  highlight, no title filter.
- **Locator/progress:** `EpubSurface` already collects `navigator.currentLocator`
  every STARTED lifecycle tick and forwards `(locatorJson, progressPercent)` to
  `EpubReaderViewModel.location()`, which persists through a conflated
  `PositionWriter` into `ReadingEntity(itemId, locator, progress, lastRead)`.
  This is resume-position, not a discrete bookmark list — no bookmark storage
  exists.
- **Typography/reading mode/page colors:** `ReaderPreferences` (`core/reader/
  ReaderPreferences.kt`) already has `font` (SERIF/SANS), `fontSize`,
  `lineHeight`, `margins`, `justified`, `scroll` (pagination vs. continuous
  scrolling), and `palette` (THEME/LIGHT/DARK/PAPER). `ReaderAppearance.kt`
  already exposes all of these: three style presets (Editorial/Clean/Spacious),
  size/line-height/margin sliders, a "Justified text" checkbox, a **"Continuous
  scrolling" checkbox already wired to `scroll`**, and a **"Page colors"**
  chip row for Theme/Light/Dark/Paper. `epubPreferences()` (`core/reader/
  EpubReader.kt`) already maps all of this into Readium's `EpubPreferences`
  (`fontFamily`, `fontSize`, `lineHeight`, `pageMargins`, `textAlign`, `scroll`,
  `theme`, `backgroundColor`, `textColor`, `readingProgression`), forces
  `publisherStyles = false` and `columnCount = ColumnCount.ONE`.
- **Fonts:** only the two built-in generic families (`sans-serif`/`serif`); no
  user-supplied font path exists.
- **Search:** no search of any kind exists for EPUB (no UI, no Readium service
  attached to the opened `Publication`).
- **Persistence:** Room v2 (`ShelfDatabase`, `app/schemas/.../2.json`):
  `library_item`, `reading_state` (one row per item — resume position only),
  `reader_preference` (one row per item, plus an empty-key global row),
  `appearance_preference`. No bookmark table exists. `androidx.room:room-testing`
  is not a current dependency; the one existing migration (1→2) has no
  `MigrationTestHelper` coverage.
- **Readium:** `org.readium.kotlin-toolkit` **3.4.0** pinned
  (`gradle/libs.versions.toml`), `readium-navigator` + `readium-streamer`
  (`app/build.gradle.kts`). `EpubReaderFactory.open()` already uses the
  `PublicationOpener(EpubParser(...), onCreatePublication = { ... })` callback
  shape (currently only to wrap `container` for HTML sanitization via
  `TransformingContainer`); the callback receives a `Publication.Builder` with
  mutable `manifest`/`container`/`servicesBuilder`, which is the same
  extension point 2B.3 (search) needs.

#### 2B.1 Readium capability findings (verified against the actual 3.4.0 `.aar`/API jars in this environment, not documentation)

No sources jars ship with the pinned artifacts; every finding below was
confirmed by decompiling method signatures (`javap`) from the cached
`readium-shared-3.4.0-api.jar`, `readium-navigator-3.4.0-api.jar` and
`readium-streamer-3.4.0-api.jar` in `~/.gradle/caches`, not inferred from
upstream Readium documentation, which can drift from a pinned older release.

**Chapters / TOC / current locator**
- `Publication.tableOfContents: List<Link>` and `.readingOrder` — already used.
- `Navigator.currentLocator: StateFlow<Locator>` and `Navigator.go(Locator/Link,
  animated): Boolean` — already used for page turns/chapter jumps.
- No dedicated "current chapter" API: current chapter must be derived
  ShelfOS-side by matching `currentLocator.href` against the flattened TOC/href
  list already built in `EpubSession.chapters`. This is a small, local
  computation, not a missing Readium capability.
- TOC title filtering is plain in-memory string filtering over
  `EpubSession.chapters`; no Readium API involved either way.
- **Verdict: ALREADY IMPLEMENTED** (navigation) **+ REQUIRES SHELFOS UI** (highlight/filter are pure ShelfOS-side, using APIs already in use).

**Publication-wide search**
- `org.readium.r2.shared.publication.services.search.SearchService` (a
  `Publication.Service`): `suspend fun search(query, Options):
  SearchIterator`. Suspend-based, not `Flow`.
- `SearchIterator.next(): Try<LocatorCollection, SearchError>` (suspend, one
  page of results at a time) and a `forEach` convenience; `getResultCount()`
  when known.
- `SearchService.Options`: `caseSensitive`, `diacriticSensitive`, `wholeWord`,
  `exact`, `language`, `regularExpression`, `otherOptions` — real, usable knobs.
- `Locator` (the result type, inside `LocatorCollection.locators`) already
  carries `href`, `title`, `locations` (`progression`, `position`,
  `totalProgression`), **and `text: Locator.Text(before, highlight, after)`
  — the match snippet.** Search results have snippets, hrefs and progression
  out of the box.
- `StringSearchService` (`readium-shared`) is a **ready-made, concrete
  implementation**: constructed from `(Manifest, Container<Resource>,
  PublicationServicesHolder, language, snippetLength, Algorithm,
  ResourceContentExtractor.Factory)`, with `StringSearchService.Algorithm`
  offering `NaiveAlgorithm`/`IcuAlgorithm`. `StringSearchService.Companion
  .createDefaultFactory(snippetLength, algorithm, extractorFactory)` returns a
  ready `(Publication.Service.Context) -> StringSearchService` factory.
  `DefaultResourceContentExtractorFactory` (plus `HtmlResourceContentExtractor`)
  already exists to extract plain text from EPUB XHTML resources — no custom
  HTML/text extractor needs to be written.
- **Not auto-registered:** `EpubParser` (readium-streamer) does not attach any
  `SearchService` by default — only `EpubPositionsService`. Attaching search
  is a deliberate `servicesBuilder.set(SearchService::class, factory)` call
  inside `PublicationOpener`'s `onCreatePublication` callback — the exact
  callback shape `EpubReaderFactory.open()` already uses for HTML
  sanitization, extended with one more `servicesBuilder.set(...)` call.
- **Verdict: SUPPORTED BY CURRENT READIUM BUT NOT WIRED.** No custom EPUB
  parser or text index is needed; this is wiring a real, current-version
  Readium service, not building one. Search is in-memory/on-demand per
  session (Readium extracts and searches resource text at query time); this
  is expected to be fine for typical EPUB sizes given the existing per-EPUB
  size caps already enforced in `EpubReaderFactory.open()` (8 MiB per markup
  resource / 128 MiB total markup), and should be measured against the
  largest private fixture during implementation rather than assumed.

**Reading mode (pagination/scroll)**
- `EpubPreferences.scroll: Boolean?` — **already wired end-to-end**
  (`ReaderPreferences.scroll` → `ReaderAppearance`'s "Continuous scrolling"
  checkbox → `epubPreferences()` → Readium). Nothing to build.
- `EpubPreferences.columnCount: ColumnCount` exists (Readium supports
  ONE/TWO/AUTO) but ShelfOS hard-codes `ColumnCount.ONE` in `epubPreferences()`
  — a deliberate single-column, phone-first choice, not a gap. Multi-column/
  two-page EPUB layout is foldable/tablet territory and belongs to 2D's
  adaptive-layout closure (§4/§10 scope guard), not 2B.
- **Verdict: ALREADY IMPLEMENTED** (pagination vs. scroll toggle). Column
  count: **SUPPORTED BY CURRENT READIUM, DELIBERATELY NOT EXPOSED** (out of
  2B scope; belongs to 2D).

**Typography**
- `EpubPreferences` (decompiled field list) supports: `fontFamily`,
  `fontSize`, `fontWeight`, `lineHeight`, `pageMargins`, `paragraphIndent`,
  `paragraphSpacing`, `wordSpacing`, `letterSpacing`, `hyphens`, `ligatures`,
  `textAlign`, `textNormalization`, `typeScale`, `publisherStyles`,
  `columnCount`, `imageFilter`, `verticalText`, `theme`, `backgroundColor`,
  `textColor`, `readingProgression`, `spread`, `language`.
- **Already wired:** `fontFamily`, `fontSize`, `lineHeight`, `pageMargins`,
  `textAlign` (justified/start only), `scroll`, `theme`/`backgroundColor`/
  `textColor`, `readingProgression`. `publisherStyles` is hard-forced `false`.
- **SUPPORTED BY CURRENT READIUM BUT NOT WIRED (desired, in-scope for 2B):**
  none identified as clearly desired beyond what's already wired — the
  roadmap's "typography refinement beyond the three presets" is satisfied by
  the existing size/line-height/margin/justify controls; no field gap was
  found that users have asked for. Paragraph spacing/hyphens/ligatures/word
  spacing/letter spacing exist in Readium but are **DESIRED BUT UNSUPPORTED
  BY ANY EXISTING SHELFOS REQUIREMENT** — do not add UI for these
  speculatively (AGENTS.md: no features beyond what the task requires).
- **Verdict: ALREADY IMPLEMENTED** for everything currently asked for.
  2B.1 (below) does not add new typography controls; it only confirms this
  finding in the plan so nobody re-builds what already exists.

**Page colors / dark reading mode**
- `PagePalette.THEME/LIGHT/DARK/PAPER` already maps to Readium's
  `Theme.LIGHT/DARK/SEPIA` plus explicit `backgroundColor`/`textColor`, fully
  independent of the ShelfOS app theme (Classic/Dark/etc.) — a real reading
  surface color system, not merely following the app theme.
- **Verdict: ALREADY IMPLEMENTED.** This already satisfies the roadmap's
  "dark reading mode" deliverable. **2B must not add a second, redundant dark
  mode.** The only 2B action here is documenting this closure (this section).

**Custom fonts**
- `org.readium.r2.navigator.epub.css.FontFamilyDeclaration` /
  `MutableFontFamilyDeclaration` / `FontFaceSource` /
  `EpubNavigatorFragment.Configuration.addFontFamilyDeclaration(name,
  alternates) { addFontFace { addSource(url, preload) } }` — a real,
  concrete API for registering a custom `@font-face` with the navigator,
  confirmed present in the pinned 3.4.0 navigator artifact.
- `FontFaceSource.href` is a `org.readium.r2.shared.util.Url` — the source
  must be reachable by Readium's own resource-serving path (the same
  mechanism that serves the publication's own resources into its WebView),
  not an arbitrary external URI. `EpubNavigatorFragment.Configuration
  .servedAssets: List<String>` further suggests only specific, declared
  asset paths are servable this way.
- **Findings against the prompt's specific questions:**
  - *Can the navigator accept custom font-family declarations?* Yes —
    `addFontFamilyDeclaration`/`FontFamilyDeclaration` is real and present.
  - *Can ShelfOS inject a local font resource/URL?* Only if that resource is
    reachable through Readium's own resource-serving path — an arbitrary
    external `content://` URI is **not confirmed** to be directly usable as
    a `FontFaceSource.href` from this API surface alone.
  - *Would a user-selected font need to be copied into ShelfOS-managed
    storage?* **Yes, almost certainly** — this matches ShelfOS's existing
    "private managed copy" pattern already used for large/otherwise-
    unreachable source files (`core.files`, Phase 1), and is the safest,
    most consistent way to guarantee the font byte-for-byte survives SAF
    grant loss, is available offline, and lands on a stable local path
    Readium's resource path can serve.
  - *Can SAF-persisted content be referenced safely by the navigator
    directly?* **Not confirmed safe or supported** by this API surface; the
    managed-copy path avoids relying on that.
  - *Lifetime/permission concerns?* Same class of concern already solved for
    managed publication copies: SAF grants can be revoked, files can move;
    a copied font in ShelfOS-managed storage avoids depending on a live SAF
    grant at read time.
  - *What happens if the font disappears* (managed copy deleted/corrupted)?
    Reader must fall back to the existing built-in `sans-serif`/`serif`
    choice, never fail to open the publication.
  - *Reset/fallback?* `BookFont` would need a third case (e.g. `CUSTOM`) with
    a stored reference to the managed copy; resetting typography (existing
    "Reset" action in `ReaderAppearance`) must cleanly fall back to SERIF/
    SANS, not leave a dangling reference.
- **Verdict: FEASIBLE, NOT CONFIRMED SIMPLE.** The Readium-side API exists
  and is real; the ShelfOS-side work (SAF font picker, managed-copy storage,
  lifecycle, fallback) is genuinely new architecture, not a small wiring
  task like search. Do not bundle a font library; a single user-supplied
  font per title/globally is the minimal viable shape, consistent with the
  roadmap's own "without unnecessarily bundling many fonts" instruction.

#### 2B.2 Supported/not-supported summary table

| Capability | Status |
| --- | --- |
| Chapter navigation (jump to TOC entry) | ALREADY IMPLEMENTED |
| Current-chapter highlight | REQUIRES SHELFOS UI (Readium API already used) |
| TOC title filter | REQUIRES SHELFOS UI (no Readium API involved) |
| Publication-wide text search | SUPPORTED BY CURRENT READIUM BUT NOT WIRED |
| Pagination vs. scrolling | ALREADY IMPLEMENTED |
| Multi-column / two-page EPUB | SUPPORTED BY CURRENT READIUM, out of 2B scope (2D) |
| Typography (font/size/line-height/margins/justify) | ALREADY IMPLEMENTED |
| Extended typography (paragraph/word/letter spacing, hyphens, ligatures) | DESIRED BY NO CURRENT REQUIREMENT — not built |
| Reading-surface dark/sepia/light ("Page colors") | ALREADY IMPLEMENTED — satisfies roadmap's dark-reading-mode item |
| Bookmarks (discrete saved locations) | NOT IMPLEMENTED — REQUIRES SHELFOS STORAGE/UI |
| Custom user-supplied font | NOT IMPLEMENTED — REQUIRES SHELFOS STORAGE/UI, Readium font-face API confirmed available |

#### 2B.3 Bookmark data-model proposal

Distinct from `reading_state` (one row per item, auto-updated resume
position). A bookmark is a **user-created, durable, multi-row-per-item**
record, following the existing `ReadingEntity`/repository pattern rather than
inventing a new shape:

```kotlin
@Entity(tableName = "bookmark", foreignKeys = [ForeignKey(entity = LibraryEntity::class,
    parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("itemId")])
data class BookmarkEntity(
    @PrimaryKey val id: String,       // ShelfOS UUID, not the item id — multiple per item
    val itemId: String,               // LibraryItem.id
    val locator: String,              // Locator.toJSON() — same serialization already used by reading_state
    val progress: Int,                // 0..100, same convention as ReadingEntity, for display only
    val label: String?,               // optional user note/context; null shows chapter title + progress instead
    val createdAt: Long,
)
```

- **Requires a Room migration**, v2 → v3, adding one table (`CREATE TABLE
  bookmark (...)` plus `CREATE INDEX index_bookmark_itemId ON
  bookmark(itemId)`), following the same manual-SQL `Migration` pattern as
  the existing 1→2 migration. No changes to any existing table.
- **Multiple bookmarks per publication:** yes — `itemId` is not unique/PK;
  `id` (a fresh UUID) is the primary key.
- **Duplicate bookmark behavior:** not deduplicated by locator — a user may
  bookmark the same passage twice if they choose to; this matches how a
  physical bookmark works and avoids surprising silent no-ops. (Revisit only
  if real usage shows this is confusing — do not pre-solve.)
- **Delete bookmark:** `DELETE FROM bookmark WHERE id = :id`.
- **Bookmark current location:** insert a new row from `EpubActivity`'s
  already-available current locator (the same JSON `EpubSurface` already
  forwards to `onLocation`) — no new Readium capability needed.
- **List bookmarks:** `SELECT * FROM bookmark WHERE itemId = :itemId ORDER BY
  locator position / createdAt` (exact ordering — by document position via
  the stored locator, or by creation time — is a UI-detail decision for
  implementation, not a discovery blocker).
- **Jump to bookmark:** `Locator.fromJSON(stored locator)` then
  `navigator.go(locator, animated = false)` — identical mechanism to the
  existing chapter-jump code path.
- **Source file remains untouched:** bookmarks are pure ShelfOS-owned rows
  referencing a `Locator`, never written into the EPUB — consistent with
  AGENTS.md rule 4 and the existing reading-state/preferences pattern.
- Repository surface follows the existing `LibraryRepository` shape:
  `fun bookmarks(itemId: String): Flow<List<Bookmark>>`, `suspend fun
  addBookmark(itemId: String, locator: String, progress: Int, label: String?):
  String`, `suspend fun removeBookmark(id: String)`.
- **Testing implication:** implementing this migration should add
  `androidx.room:room-testing` (official AndroidX artifact, Apache-2.0,
  actively maintained, test-only/no release APK size impact) and a
  `MigrationTestHelper`-based 2→3 test — the existing 1→2 migration has no
  such coverage today, so this would also be the first. This is a dependency
  decision for the implementation slice, not made in this planning pass.

#### 2B.4 Chapter UX clarification

"Search within chapter list," per the roadmap wording, means **(A) filter TOC
chapter titles**, a small local string filter over `EpubSession.chapters` —
**not (B) search publication text**, which is the separate, larger
publication-search capability (2B's own "in-publication search" item, §2B.1
above). These are kept as two separate features in the slices below and must
not be conflated in implementation either. (A) is only worth adding if a
publication's TOC is long enough to need it — evaluate against real fixture
TOC sizes during implementation rather than always showing a filter field.

#### 2B.5 Proposed internal slices and order

Four slices (not the example's three-to-four "chapter+bookmark combined"
grouping — schema-affecting work is kept in its own isolated slice per this
plan's own risk analysis, not bundled with a zero-schema UI change):

**2B.1 — Chapter navigation polish + typography/reading-mode/page-color closure.**
Current-chapter highlight in the Chapters dialog (derive from
`navigator.currentLocator.href` against `EpubSession.chapters`, both already
in use); TOC title filter if warranted by real fixture TOC length; formally
document (this plan + `docs/design/READER_UX.md`/`docs/features/READER.md` as
needed) that pagination/scroll and page-color/dark-reading-mode are already
complete, closing those two roadmap bullets without new code. Zero schema
change, zero new Readium capability — pure reuse of APIs already wired.

**2B.2 — Bookmark foundation.**
Room migration 2→3 (`bookmark` table), repository CRUD, "Add bookmark"/list/
jump/delete UI in the EPUB reader chrome and/or a bookmarks sheet. The only
slice touching the schema; isolating it means a migration problem is caught
and reviewed on its own, not entangled with unrelated UI changes.

**2B.3 — Publication search.**
Wire `StringSearchService` via `servicesBuilder.set(SearchService::class,
StringSearchService.createDefaultFactory(...))` inside `EpubReaderFactory`'s
existing `onCreatePublication` callback; add a search UI surface (query
input, snippet results list reusing `Locator.Text.before/highlight/after`,
jump-to-result via the same `navigator.go(locator)` path as chapters/
bookmarks); no schema change. Ordered after bookmarks because it is 2B's
largest net-new UI surface and can reuse the list/jump-to-locator UI pattern
2B.2 establishes, and because it depends on nothing bookmarks doesn't also
depend on, so de-risking the smaller schema change first is preferable.

**2B.4 — Custom-font architecture.**
SAF font picker → managed-copy storage (reusing the existing private-copy
pattern) → `EpubNavigatorFragment.Configuration.addFontFamilyDeclaration`
wiring → fallback-to-built-in-font behavior on failure/deletion. Ordered
last: it is the most architecturally novel piece (new user-supplied-asset
lifecycle through a third-party rendering engine), has the least-specific
existing user pull among the four, and benefits from the reader chrome
being otherwise stable (chapters/bookmarks/search settled) before adding a
new asset-lifecycle concern. **Recommend a short ADR when this slice is
actually implemented** (not now — this is a discovery pass) if it in fact
requires a new "user-supplied external content becomes managed, engine-
servable storage" pattern beyond what Phase 1's private-copy precedent
already covers; that determination is better made with real implementation
detail in hand than speculatively here.

#### 2B.6 Data-model / dependency impact summary

- **Schema:** exactly one migration, 2B.2 only (`bookmark` table, v2→v3).
  2B.1/2B.3/2B.4 make no schema changes.
- **Dependencies:** none required to be added in this planning pass. 2B.2's
  implementation will likely want `androidx.room:room-testing` (test-only).
  2B.3/2B.1 need no new dependency — `StringSearchService`,
  `DefaultResourceContentExtractorFactory` and the font-face API all already
  ship inside the pinned `readium-shared`/`readium-navigator` 3.4.0 artifacts.
  2B.4 needs no new dependency either — SAF picking and managed-copy storage
  reuse `core.files` patterns already in the codebase.
- **UI impact:** 2B.1 changes only the existing Chapters dialog (highlight +
  optional filter) and adds no new screen. 2B.2 adds a bookmarks
  list/add/jump surface. 2B.3 adds a search surface. 2B.4 adds a font picker
  entry point inside `ReaderAppearance`.

#### 2B.7 Testing strategy (per slice, see acceptance criteria below for detail)

- Unit/JVM: TOC-highlight matching logic, TOC filter logic, bookmark
  entity/repository mapping, search-result-to-UI mapping — all plain Kotlin,
  testable without instrumentation, following the existing `InputHintsTest`-
  style pattern of extracting pure logic where possible.
- Instrumented (`shelfos-phase0`, API 35, the established known-good target
  for this class of Compose/Espresso test): new `EpubBookmarkTest`,
  `EpubSearchTest`, and additions to the existing Chapters coverage area,
  following the existing `NavigationSmokeTest`/`EpubRecreationTest` house
  style (real `ActivityScenario`, real key events where input-modality-
  relevant, `compose.waitUntil`).
- Room migration test (2B.2 only): `MigrationTestHelper` 2→3, the first such
  test in this codebase — validates the new table/index/foreign key without
  relying only on manual inspection.
- RP5 relevance is per-slice, not blanket: warranted where a slice adds new
  reachable chrome (bookmark add/list button, search entry point) that must
  stay reachable via the existing semantic-command/input-hint layer
  (2A/2A.1); **not** warranted for typography confirmation (2B.1's typography
  finding is "already implemented," nothing new to certify) or for search
  result *content* correctness (an emulator-only concern).

#### 2B.8 Risks

- **Search performance/size:** `StringSearchService` extracts and scans
  resource text at query time; large EPUBs could make search feel slow. Not
  yet measured — 2B.3's acceptance criteria must include a real-fixture
  timing check before calling it done, not an assumption either way.
- **Migration correctness:** the existing 1→2 migration has no automated
  regression test; 2B.2 is the first schema change since acceptance and
  should not repeat that gap — add `MigrationTestHelper` coverage as part of
  2B.2, not defer it.
- **Font lifecycle edge cases:** a managed font copy could be deleted by the
  same Settings → Storage cleanup flow that already reclaims unreferenced
  private copies (Phase 1); 2B.4 must ensure a font copy in active use is
  never treated as "unreferenced," and that losing it degrades to a built-in
  font rather than breaking the reader.
- **Scope bleed:** chapter/typography/search/bookmark work all touch
  `EpubActivity`/`EpubReaderViewModel`, which are already fairly dense; each
  slice should resist expanding these files' existing responsibilities
  (dialog, key handling, chrome) beyond what its own capability needs.

#### 2B.9 Explicitly deferred, not part of any 2B slice

Two-page/multi-column EPUB layout (2D, adaptive/foldable closure); highlights/
notes/annotations (post-2B, per `docs/features/READER.md`'s "Later" list);
in-page search-result highlighting via `HtmlDecorationTemplates` (a possible
future refinement of 2B.3, not required for a useful first search feature);
bundling any font library; remapping/controls UI; anything in §4/§10's
out-of-scope lists (PDF/CBZ rendering, Series, CBR, OCR, Adapted PDF, TTS
beyond existing research, ShelfOS Home, billing, cloud sync, metadata-
provider work, theme redesign).

#### 2B.10 Regression gates (must not break, verified per slice before merge)

- Phase 2A Back reveal/exit semantics (ADR-0023) — new dialogs (bookmarks,
  search) must not introduce a new trap; Back must still close a transient
  dialog first, then behave per ADR-0023.
- Phase 2A's chrome accessibility action (`onClick(label = "Show reader
  controls")` while chrome is hidden).
- Phase 2A.1 contextual input hints (Previous/Next/Back keycaps) and touch-
  modality clearing on a real touch gesture.
- Keyboard/controller navigation of existing chrome (Chapters/Appearance
  buttons) must remain reachable, and any new buttons (bookmark, search)
  must join the same semantic-command-reachable chrome rather than becoming
  touch-only additions.
- Resume/progress persistence (`reading_state`) — unaffected by a new,
  separate `bookmark` table.
- Appearance persistence (per-title/global layering) — unaffected by 2B.1's
  documentation-only typography closure.
- Recreation/rotation behavior (`EpubRecreationTest`'s now-corrected
  contract) — new dialogs must survive recreation the same way the existing
  Chapters/Appearance dialogs already do.
- Offline reading — search/bookmarks/fonts must all work fully offline;
  none of this pass's findings introduce a network dependency.
- Source preservation (AGENTS.md rule 4) — nothing in 2B writes into the
  EPUB file itself.

#### 2B.11 Acceptance criteria (per slice, to be met at implementation time — none met yet)

**2B.1 (chapter/typography/page-color closure):**
user-visible: current chapter visibly indicated in the Chapters dialog;
optional TOC filter if implemented. persistence: none new. accessibility:
highlighted chapter row must not rely on color alone (e.g. also a check mark
or bold label). touch/keyboard/controller: dialog remains fully reachable
exactly as today (TextButton-based list, already focusable). compact/
expanded layout: dialog already scrolls via `LazyColumn`, no new layout
concern. recreation/app restart: highlight recomputes from the restored
locator, not stored separately. source preservation: n/a (read-only).
offline: n/a (local-only). failure/recovery: empty/short TOC (already
handled — dialog just lists what exists). tests: unit test for the
highlight-matching function; instrumented test extending the existing
Chapters coverage. API 35: yes. RP5: not required (no new chrome reachability
surface, per §2B.7).

**2B.2 (bookmarks):**
user-visible: add/list/jump/delete bookmarks for the open EPUB. persistence:
new `bookmark` table survives app restart and recreation; deleting the
`LibraryItem` cascades bookmark deletion (matches `reading_state`'s existing
`ForeignKey.CASCADE`). accessibility: list items expose readable content
descriptions (label or chapter+progress), delete action clearly labeled.
touch/keyboard/controller: add/list/jump/delete all reachable via existing
semantic-command chrome. compact/expanded: bookmark list scrolls like the
Chapters dialog. recreation: dialog-open state (if any) survives recreation
like Appearance/Chapters already do. app restart: bookmarks persist (Room).
source preservation: untouched (ShelfOS-owned table only). offline: fully
local. failure/recovery: deleting the last bookmark leaves an empty-state
list, not an error. unit tests: entity mapping, repository CRUD. instrumented
tests: add/jump/delete flow, migration test (`MigrationTestHelper` 2→3).
API 35: yes. RP5: relevant — confirm the new add/list chrome entry point is
reachable via existing input hints/semantic commands, consistent with 2A.1's
own RP5 evidence bar.

**2B.3 (search):**
user-visible: query input, results with snippets, jump to a result.
persistence: none (search is not persisted; recent searches are explicitly
not part of this scope). accessibility: result rows expose their snippet
text as content description; empty-results state announced. touch/keyboard/
controller: query field and result list reachable via existing focus/
semantic-command handling. compact/expanded: result list scrolls; no
tablet-specific layout required beyond what Compose gives for free.
recreation: in-progress query/results survive recreation the way the
Appearance dialog's draft already does (`rememberSaveable`). app restart: no
persistence needed, so nothing to restore. source preservation: read-only.
offline: fully local (Readium extracts text from the already-open
publication, no network). failure/recovery: `SearchError` (from
`SearchIterator.next()`) surfaces a readable message rather than crashing.
unit tests: result-to-UI mapping. instrumented tests: query → results → jump
flow against a real fixture EPUB with known text; a timing check against the
largest available fixture (§2B.8). API 35: yes. RP5: relevant for the new
search entry point's reachability, same bar as 2B.2.

**2B.4 (custom fonts):**
user-visible: pick a font file, apply it, reset falls back to built-in.
persistence: a reference to the managed font copy, likely as a new `BookFont`
case or a separate preference field (exact shape decided at implementation
time). accessibility: font picker uses standard SAF file-picker accessibility
(system-provided). touch/keyboard/controller: picker entry point reachable
like other Appearance controls. compact/expanded: no new layout surface
beyond the existing Appearance dialog. recreation: draft/applied font
selection follows the existing Appearance draft/apply pattern. app restart:
managed copy and preference persist. source preservation: n/a (a new asset,
not the publication). offline: fully local once copied. failure/recovery:
missing/corrupt managed copy falls back to built-in font, never fails to
open the publication. unit tests: fallback logic. instrumented tests: pick →
apply → recreate → still-applied; delete managed copy → falls back cleanly.
API 35: yes. RP5: not required specifically for font rendering (a rendering-
fidelity concern more than an input-reachability one), but the picker entry
point should join the same reachability bar as other Appearance controls.

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
| Controller hint discovery | Press a gamepad button (R1/L1/B); confirm hints appear next to Previous/Next/Back, matching the actual binding | Emulator (injected/instrumented), RP5 (real-device execution over ADB), RP5 (owner-verified physical controller buttons) |
| Keyboard hint discovery | Press a keyboard key (Page Down/Up, Escape); confirm hints appear as arrows/Esc, matching the actual binding, including under RTL | Emulator (injected/simulated), RP5 (injected/simulated over ADB) — no physical keyboard tested yet, on RP5 or otherwise |
| Modality switching | Alternate controller input, keyboard input and a real touch tap; confirm hints switch/clear correctly each time, including across a Back press | Emulator, RP5 |
| Tablet reader layout sanity (optional/periodic) | Open each format, check chrome scale, interaction visibility, obvious performance regression | Galaxy Tab A |

## 9. Explicit out-of-scope confirmation for this run

2A and 2A.1 were each implemented and remediated against independent Codex
review, then accepted and merged to `main` (2A.1 via PR #5, commit
`44c8f10600f93f875a3cfb685c27f47c25929c29`). The subsequent
`maintenance/epub-recreation-back-test` pass corrected only the stale
`EpubRecreationTest.kt` assumption described above and this document's/
`VALIDATION.md`'s/`ROADMAP.md`'s/`CHANGELOG_DOCS.md`'s acceptance wording, and
was itself accepted and merged via PR #6. No code for user-editable bindings,
a complete remapping UI, controller profiles, console-brand-specific glyph
packs, controller detection by product/model database, platform-specific
visual modes, or any 2B/2C/2D work existed on that branch. No item from §4's
out-of-scope list was touched.

## 10. Explicit out-of-scope confirmation for the 2B discovery/planning pass

This pass, on `phase-2/epub-everyday-reading` (base `main` at
`8430566396e98196752d9ec9f33910a2e248b039`), changed documentation only: this
document's 2B section (§3) plus, if updated, `ROADMAP.md` sequencing notes. No
file under `app/src/main/...` was changed. No Room schema, migration or
`app/schemas/` file was changed. No `gradle/libs.versions.toml`/
`app/build.gradle.kts` dependency was added, removed or upgraded. No PDF/CBZ
rendering change, no Series/CBR/OCR/Adapted PDF/notes/annotations/TTS-beyond-
research/remappable-controls/ShelfOS-Home/billing/cloud-sync/metadata-
provider/theme-redesign work exists on this branch — see §4's standing
Phase-2-wide out-of-scope list and §3's 2B "explicitly deferred" subsection
above, both of which this pass's findings respect without exception.
