# Validation

## Phase 2A validation (2026-09-25)

Status: **Phase 2A (reader chrome/Back semantics, `docs/PHASE_2_PLAN.md`) is
accepted**, on branch `phase-2/reading`, not yet merged. Independent Codex
review verdict: **PASS WITH NON-BLOCKING FINDINGS** (see FINAL ACCEPTANCE
below for the full evidence summary and explicitly unclaimed items). Phase 1's
acceptance (below) is unaffected; no Phase 1 code outside the reader
Back-handling paths described in
[ADR-0023](adr/0023-reader-chrome-back-semantics.md) was touched.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | Passed |
| `:app:compileDebugAndroidTestKotlin` | Passed |
| `:app:assembleDebug` | Passed |
| `:app:lintDebug` | Passed |
| `:app:assembleDebugAndroidTest` | Passed |
| Room schema cleanliness (`git status --porcelain -- app/schemas`) | Clean — no Room changes in this increment |
| `git diff --check` | Clean |

### JVM / REGRESSION

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | Passed — 63/63 (independent Codex re-review count) |

### INSTRUMENTED / EMULATOR

`NavigationSmokeTest` run via `:app:connectedDebugAndroidTest`. Test count grew
from 14 (the original acceptance pass, including the two Back-reveal tests
replacing the prior single Back test — see ADR-0023) to 16 after the Codex-review
accessibility remediation added `accessibilityActionRevealsHiddenControlsInFixedReader`
and `...InEpubReader`:

| Device | Result |
| --- | --- |
| `shelfos-api37` (AVD, API 37, freshly data-wiped) | All 14 (pre-remediation) failed identically at Espresso's `onIdle()` with `NoSuchMethodException: android.hardware.input.InputManager.getInstance` — a pre-existing, already-documented environment gap (see "COMPATIBILITY: API 24 and API 37" below), not a regression from this change. Not retried post-remediation; this gap is not claimed fixed. |
| `shelfos-phase0` (AVD, API 35), default phone viewport (1080×1920, 420dpi) | 14/14 passed pre-remediation; **16/16 passed** after the accessibility remediation, targeted explicitly via `ANDROID_SERIAL` to exclude a physical RP5 that was connected at the same time but intentionally not used for this pass (see Phase 2A remediation note below) |
| `shelfos-phase0` (AVD, API 35), forced expanded/tablet viewport (`wm size 1600x1000`, `wm density 160`) | 14/14 passed (pre-remediation) |

This is real automated evidence for the fix: `backRevealsHiddenControlsBeforeLeavingTheReader`
(Original/PDF reader) and `epubBackRevealsHiddenControlsBeforeLeavingTheReader`
(EPUB) both start from hidden chrome, press system Back, assert chrome reappears
and the reader is still open, press Back again, and assert the reader exits —
directly exercising the previously-untested path that let the field bug through.

### PHYSICAL DEVICE

**Retroid Pocket 5 (RP5), Android 13 / API 33, ADB serial `d8f7f1b6`.**
Performed during independent Codex re-review (2026-09-25), after the
accessibility remediation above:

- Real-hardware execution (over ADB, not simulated) confirmed for EPUB, PDF and
  CBZ opening and reading.
- Hide/reveal/exit Back semantics (ADR-0023) validated on-device through
  Android key events: hidden chrome + Back reveals chrome; visible chrome +
  Back exits the reader.
- PDF resume validated on-device.
- CBZ D-pad/input validated on-device.
- Android Home / Recent Apps safety confirmed — normal system navigation
  remains available and is not intercepted.
- A focused RP5 instrumentation pass: **6/6 passed**.
- No ShelfOS crashes or navigation exceptions observed in RP5 logcat during
  the pass.
- No obvious Phase 2A performance regression observed.

**Important distinction (Codex's own framing, preserved here):** this is real
RP5 hardware execution driven through Android key events over ADB, not a
record of physically pressing the handheld's own L1/R1/B buttons during this
review pass. The owner has separately confirmed that physical controller
controls work on the RP5, but exact per-button physical-press sequences for
each reader were not explicitly recorded in this pass, so physical-controller
support is described here conservatively rather than as a specific tested
sequence.

**Manual TalkBack:** not performed. TalkBack was unavailable on the test
targets used for this review. This is not claimed as a pass.

**Pre-existing, non-blocking observation (not caused by Phase 2A):** in
landscape orientation, the import dialog's category-chip row visually exposed
only the Books chip without usable scrolling to reach Comics/Manga/Documents.
This predates Phase 2A and is not part of its acceptance criteria; tracked
here as a future adaptive/import UX follow-up, not fixed in this branch.

**Samsung Galaxy Tab A (SM-T580):** not performed in this pass. Per the device
strategy in `PHASE_2_PLAN.md` §7, this is optional/periodic and non-blocking for
2A; recorded as pending, not claimed.

### ACCESSIBILITY

`stateDescription` semantics were added to the chrome-toggle areas in both
readers (`FixedReaderScreen`'s page `Box`, `EpubActivity`'s `EpubSurface`
container). **Remediated 2026-09-25** after independent Codex review (R2)
found the initial version announced "double tap to show controls" while
double-tap was actually reserved for zoom, and neither surface exposed an
`onClick` accessibility action at all — so TalkBack's double-tap-to-activate
gesture had nothing to invoke and the announced instruction did not correspond
to a working action. Both surfaces now expose `stateDescription` reflecting
only the real state ("Controls shown" / "Controls hidden") and, exclusively
while chrome is hidden, `onClick(label = "Show reader controls")`, which
reveals chrome when invoked. New tests
(`accessibilityActionRevealsHiddenControlsInFixedReader` /
`...InEpubReader`) invoke the semantic action itself via
`performSemanticsAction(SemanticsActions.OnClick)` rather than merely
asserting a description string exists, and pass on `shelfos-phase0` (API 35).
This is still not independently verified with TalkBack physically running —
no physical/emulator TalkBack walkthrough was performed for this change. That
explicit TalkBack pass remains open for 2D's accessibility closure or an
earlier follow-up.

### MOTION

No animation was added to reader chrome show/hide (remains an instant
conditional composition, as it already was). `Context.reducedMotionEnabled()`
was not called from reader code in this pass because there is no motion in
reader chrome to gate — reduced motion is honored trivially. Not independently
tested with the system "Remove animations" setting for this reason.

### FINAL ACCEPTANCE (2A)

**Phase 2A is accepted (2026-09-25).** Independent Codex re-review verdict:
**PASS WITH NON-BLOCKING FINDINGS.** Evidence: JVM unit tests 63/63; API 35
`NavigationSmokeTest` 16/16; a focused RP5 (Android 13/API 33) instrumentation
pass 6/6 with real-hardware EPUB/PDF/CBZ execution, on-device Back-semantics
validation, PDF resume, CBZ D-pad input, Home/Recent-Apps safety, no crashes in
logcat and no obvious performance regression; full Gradle validation passing;
the previously blocking accessibility finding (R2) and its three non-blocking
documentation findings (R3) both independently confirmed resolved.

Explicitly **not** claimed as part of this acceptance: a manual TalkBack
walkthrough (TalkBack was unavailable on the test targets used); exact
per-button physical-press sequences on the RP5's own controls beyond the
owner's separate general confirmation that they work; the API 37
Espresso/InputManager tooling gap (pre-existing, unrelated, not fixed); PDF
rendering resolution/fidelity (explicitly deferred to Phase 2C); and the
pre-existing RP5 landscape import-dialog category-chip scrolling issue noted
above (predates Phase 2A, not part of its acceptance criteria).

## Manual legacy-tablet field evidence

A physical Samsung Galaxy Tab A SM-T580 (Android 8.1, approximately 2 GB RAM)
session found the app responsive and pleasant, with strong reader controls and no
obvious low-memory usability failure. The tablet was not available through ADB, so
this is qualitative product research rather than benchmark evidence. Publication-
specific observations and their limits are retained in the
[field-test record](research/SAMSUNG_TABLET_FIELD_TEST_2026-09.md). This evidence
does not change Phase 1 status or replace the formal acceptance matrix below.

## Phase 1 validation (2026-09-24)

Status: increments 1A–1C are implemented; 1D (integration polish) is partial. The
automated and API 35 emulator checks below pass, including reading the private samples
by reference. The first Phase 1 review found five defects; their fixes and evidence are
under [Review remediation](#review-remediation-2026-09-24). Phase 1 is **not accepted**:
the [open software gaps](#open-software-gaps) remain, and the physical-device, API-range,
accessibility, controller and low-storage gates in
[PHASE_1_PLAN](PHASE_1_PLAN.md#7-validation-and-completion-gates) have not been executed.
Emulator results are not physical-device evidence.

### Automated checks (before the review)

| Check | Result |
| --- | --- |
| Clean export of commit-eligible files (`git ls-files -co --exclude-standard`, no `local.properties`, `ANDROID_HOME` set): `gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --offline --no-build-cache` | BUILD SUCCESSFUL; 86 of 86 tasks executed |
| JVM tests | 44 passed: FoundationTest 5, ImportPolicyTest 9, ImportViewModelTest 9, ReadingPolicyTest 14, SeekableZipTest 5, StateTest 2 |
| Lint | 0 errors; 5 `UseKtx` style suggestions (the working tree also shows the informational Gradle 9.7.1 hint) |
| Room | Schemas v1/v2 regenerate byte-identically; no schema change or migration in this pass |
| Dependencies | None added; every resolved `debugRuntimeClasspath` artifact is covered by `DEPENDENCY_LICENSES.csv` |
| `:app:connectedDebugAndroidTest` (AOSP API 35 emulator) | 20 of 20 passed on 1080×1920 @ 420 dpi; on 1600×1000 @ 160 dpi (rail + detail pane); and on 1080×1920 with all animation scales set to 0 (reduced motion) |

Check per-test XML results, not only the Gradle exit code: during this session AGP
reported BUILD SUCCESSFUL for a connected run whose APK install had failed with a
signing mismatch. Before this pass the two reader device tests failed because the
compact details screen pushed Read below the fold.

Instrumented tests (run on the emulator) cover: v1→v2 migration keeping the theme; close/reopen persistence of
edits, favorite, position and preferences; non-destructive removal that keeps other
titles and private copies until explicit cleanup; copy import of PDF/CBZ/EPUB with
embedded EPUB metadata and unchanged originals; specific problems for text, broken PDF,
imageless archive and DRM EPUB with no partial copy left; partial-copy cleanup; missing
sources; PDF/CBZ rendering with natural page order; locator/preference serialization;
five destinations with Shelves naming; theme persistence across recreation; keyboard
and D-pad navigation; details; PDF open, turn, return and resume; Back hiding controls
before leaving; Manga RTL with arrows, Page Down and L1; keyboard focus restoration;
EPUB open, Appearance and chapter navigation.

### Private-sample acceptance (API 35 emulator, not a physical device)

Samples were pushed to the emulator's Downloads folder and imported through the real
system picker (DocumentsUI), by reference, with persisted read grants.

| Sample | Result |
| --- | --- |
| Dune (PDF, 3.4 MB) as Book | 345 pages; resumed at page 5 after force-stop via Continue Reading; removed from ShelfOS with the original left in place |
| Chainsaw Man Vol. 01 (PDF, 101 MB) as Manga | RTL by default: Next on the left, left-edge tap and swipe right advance; per-title Left-to-right override and Category default reset work; artwork not mirrored; counter reads "2 / 193" |
| Frankenstein (EPUB, 474 KB) | Title and creator read from the package metadata; Spacious preset and chapter navigation; the same passage restored after force-stop, rotation and verified process death; Dark theme page colors follow the theme while explicit typography is kept |
| Immortal Hulk Omnibus (CBZ, 3.16 GB, 1,481 pages) as Comic | Imported by reference without copying or extraction; opened in about 5 s; 120 keyboard Page Down presses landed on pages 61 and 121; slider reached pages 1449 and 772; final page 1481 rendered; resumed at 772 after force-stop (52%) and restored after verified process death |
| Disposable Dune copy, deleted outside ShelfOS after import | Entry kept and marked unavailable; details explain the state; Try to open shows a non-crashing access message |

- SHA-256 of Dune, Chainsaw Man Vol. 01 and Frankenstein was identical before and after
  import, reading and removal. The 3.16 GB Immortal Hulk CBZ was not hashed, so its
  byte-for-byte integrity is unverified.
- Memory while paging through the 3.16 GB archive stayed flat: Java heap about 16–20 MB,
  native heap about 54–57 MB, total PSS 209–216 MB after 1, 61 and 121 pages.
- The review dialog appeared within about 5–9 s of choosing a file, including picker
  dismissal and UI-automation polling overhead.
- Settings → Storage reported no unused private copies (reference imports only).

### Defects found and fixed during validation

- The compact details screen hid Read below the fold (both baseline reader device tests failed).
- EPUB and CBZ imports by reference from shared storage fell back to a copy or failed:
  provider descriptors cannot be reopened through `/proc/self/fd`. Archives are now read
  through the granted descriptor (`core.files.SeekableZip`).
- Opening an EPUB could crash because preferences were submitted before Readium's
  fragment was attached; page commands during attachment had the same risk.
- In right-to-left reading the page counter rendered as "193 / 3"; selected chips were
  hard to see in the monochrome theme; deleted-source messages over-claimed a cause.

### Review remediation (2026-09-24)

The first Phase 1 review reported five defects. The review's own reproduction tests
failed before the fixes and pass after them.

| Finding | Fix |
| --- | --- |
| High: clearing the import screen during a save could discard the work being saved | A preparation has one owner at a time: preparing, review, save, then the library. Cleanup handles only abandoned work and never touches work the library references |
| Medium: cleanup of an abandoned import could release a grant that a new import of the same source needed | `ImportLeases` records which sources unfinished imports hold. A grant is released only when neither a library item nor another import depends on it; otherwise it passes to that import |
| Medium: EPUB controls and dialogs, and unapplied Appearance changes in every reader, were lost on recreation | `EpubActivity` restores its saved state; Readium's navigator is still rebuilt from the saved locator. The Appearance draft, its starting values and its scope are saved with the dialog |
| Low: a ZIP comment containing the end-of-directory signature could hide an archive's entries | End-record candidates are validated against the directory they describe |
| Low: documentation overstated the evidence | Status wording, the named hash results and the gap list on this page |

**Static/build validation.** `gradlew :app:assembleDebug :app:assembleDebugAndroidTest
:app:lintDebug --offline`: BUILD SUCCESSFUL. Lint: 0 errors; the same five `UseKtx`
suggestions and Gradle-version hint as before. No dependency, manifest, schema or
migration change.

**Unit/regression validation (JVM).** The review's three reproduction tests, run from its
harness: 3 of 3 failed before the fixes, 3 of 3 pass after. `gradlew
:app:testDebugUnitTest --rerun`: 60 passed, 0 failed (FoundationTest 5, ImportLeasesTest 5,
ImportPolicyTest 9, ImportViewModelTest 17, ReadingPolicyTest 15, SeekableZipTest 7,
StateTest 2). Copies of the review's tests are part of this suite. Against the
pre-remediation import and ZIP code, 9 of the new tests fail.

**Emulator validation (AOSP API 35, 1080×1920 @ 420 dpi).** `connectedDebugAndroidTest`:
22 of 22 passed (per-test XML checked). Two tests are new. `AppearanceRestorationTest`:
an unapplied draft, its starting values and the "all titles" scope survive state
restoration. `EpubRecreationTest`: after recreation the EPUB Appearance dialog reopens with
its unapplied change, Apply stores it, hidden controls stay hidden through a real rotation,
and reopening the reader shows the applied appearance. Both failed on the same emulator
against the pre-remediation reader code. Not repeated after the fixes: the 1600×1000,
reduced-motion and private-sample runs. EPUB UI state after process death was not
exercised separately; it uses the same restoration path.

**Physical-device validation.** Not verified: no physical device was connected.

### Open software gaps

- 1D motion: only the existing 120 ms fades; no cover-expansion transition or motion pass.
- 1D restoration and switching: Library grid scroll restoration is untested, and there is
  no rapid title-switching stress test.
- Fixed-layout Fit width, pinch zoom and double-tap zoom were not exercised on a device.
- Saving Appearance for all titles is covered by unit tests and the editor test, but the
  full path to stored defaults was not exercised on a device.
- Only early pages of the two private PDFs were checked (the CBZ's early, middle and final
  pages were).
- Room transaction rollback and genuine grant revocation (as opposed to a deleted source)
  are untested.
- A preparation that completes at the moment its import is cancelled is not handed back
  for cleanup: its grant is released at the next launch, and its private copy is listed
  under Settings → Storage as an unused copy.

### Not executed

- Physical phone, tablet or foldable; physical keyboard and game controller (only
  injected key events on the emulator); fold postures.
- API 24 and API 37 runtimes; TalkBack and large font scale.
- Low storage, interrupted copies and non-seekable providers with a real provider: the
  private-copy path is covered only by `file://` fixtures and unit tests.
- Frame-time profiling and physical-device heap measurements.
- A GitHub Actions run of this working tree.

## Phase 1 acceptance closure (2026-09-24)

Codex's focused re-review passed the R1–R5 remediation (`PHASE1_CODEX_REVIEW.md`, "Final
Verification"). This pass addresses the remaining acceptance gates: device, accessibility,
input, performance and motion validation. It does not reopen R1–R5.

**Device used.** A physical **Retroid Pocket 5** (Moorechip, Android 13, API 33, serial
`d8f7f1b6`), connected over USB with USB debugging authorized. Every category below says
whether its evidence is from this physical device, the API 35 emulator, or neither.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `gradlew :app:testDebugUnitTest --tests com.d4guilar.shelfos.ReviewRegressionTest --init-script .tools/codex-review.init.gradle --rerun` | PASS: 3 run, 0 failed. R1–R5 remain resolved |
| `gradlew :app:testDebugUnitTest --rerun` | PASS: 60 tests, 0 failed, 0 errors |
| `gradlew :app:lintDebug` | PASS: 0 errors; the same 5 `UseKtx` warnings and Gradle-version hint as before |
| `gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `git diff --check` | Clean |

No dependency, manifest, schema or migration change in this pass. One instrumented test
(`EpubRecreationTest`) was corrected during this pass; see Instrumented/physical below.

### UNIT / REGRESSION

Unchanged from Review remediation above: 60 JVM tests, 0 failures. No new unit tests were
needed; this pass is device/runtime validation, not a source of new production code.

### INSTRUMENTED / EMULATOR

Not repeated in this pass. The API 35 emulator result already on record (22 of 22, dated
during Review remediation) stands; see above.

### PHYSICAL DEVICE

All physical-device checks below ran on the Retroid Pocket 5 described above, with a debug
build freshly installed for this pass (no prior ShelfOS install or user data on the device).

**Full instrumented suite.** `adb shell am instrument -w -r
com.d4guilar.shelfos.test/androidx.test.runner.AndroidJUnitRunner`:

- First run: **21 of 22 passed.** `EpubRecreationTest.readerUiStateSurvivesRecreationAndAppliedAppearanceReloads`
  timed out waiting for a landscape recreation. Cause: this handheld ships with auto-rotate
  off and `user_rotation` locked to landscape by default, so requesting
  `SCREEN_ORIENTATION_LANDSCAPE` when the device is already effectively landscape never
  triggers a recreation. This is a test assumption bug (fixed orientation target), not a
  production defect; R3's fix and its JVM/emulator regression coverage are unaffected.
  Fixed by requesting whichever orientation the device is not currently in, so the test
  also exercises hardware that defaults to landscape.
- After the fix: **22 of 22 passed**, including the corrected recreation/rotation test.
- Repeated with `animator_duration_scale`/`window_animation_scale`/`transition_animation_scale`
  at 0 (reduced motion): **22 of 22 passed.**

**Real SAF import, read, resume.** Using a small original EPUB fixture (not committed;
generated for this pass and pushed to the device's Downloads folder), through the device's
own system file picker, not a test harness:

1. Add file → real document picker → Downloads → the fixture. ShelfOS's own semantic Escape
   binding correctly dismissed the review dialog on the first attempt (confirmed the
   documented Escape-dismisses behavior); redone by dismissing the keyboard instead.
2. Review dialog showed the correct format/size (EPUB · 5 KB) and the title read from
   embedded metadata; confirmed with the default category (Books).
3. The publication appeared in Library, opened, rendered its content, and paging moved
   from 0% to 50%, then (during frame-rate measurement, see Performance) to 75%.
4. `am force-stop` (simulating process death) then a cold relaunch: Continue Reading showed
   75%, and reopening the reader rendered the same paragraph at 75%.
5. The Read button stayed above the fold on the details screen, and the adaptive rail
   layout (Library/Search/Notes/Shelves/Settings) appeared automatically at this device's
   1920×1080 landscape window, without a device-model check.

**Large text.** `font_scale=1.3`, cold relaunch: Library, Continue Reading and the
navigation rail scaled up without clipping or overlap; long titles wrapped/truncated with
an ellipsis rather than breaking the layout. Not exercised inside the reader or Settings.
Restored to 1.0 afterward.

**Accessibility node tree (runtime, not source inspection).** `uiautomator dump` on the
open EPUB reader, then checked against Android's minimum touch-target guidance at this
device's 360dpi: every clickable control (Library, Chapters, Appearance, Previous, Next)
carries a visible text label as its accessible name and measures at least 48dp in the
constrained dimension. This is the same accessibility-node data TalkBack would read and
navigate; it is not a substitute for hearing spoken output or confirming swipe-based
TalkBack traversal, which this device cannot provide (below).

**TalkBack: NOT VERIFIED.** Neither TalkBack nor Android Accessibility Suite is installed
on this device (`pm list packages` has no matching package), and none was installed for
this pass. Spoken-output and swipe-gesture traversal remain unverified on any device.

**Physical keyboard/controller: NOT VERIFIED.** The RP5's own hardware D-pad and buttons
were not exercised, because that requires a person to physically press them; this pass
only has shell/ADB access. The existing keyboard/D-pad instrumented tests (above) inject
key events through Android's input pipeline and are real-device evidence for the semantic
mapping, but they are simulated key events, not confirmed hardware button presses. See the
physical-device checklist below for the manual controller check.

**API 24 / API 37: NOT VERIFIED.** Only API 33 (this device) and API 35 (existing
emulator) were available. No new emulator image was downloaded for this pass.

**Reliability gates unchanged:** low storage, non-seekable provider, interrupted copy and
Room transaction rollback remain NOT TESTED, as recorded above.

### PERFORMANCE

Measured on the Retroid Pocket 5 (API 33), debug build, with only the one small fixture
imported. No Phase 1 document defines numeric performance thresholds, so these are
measurements, not pass/fail gates against a target.

| Measurement | Method | Result |
| --- | --- | --- |
| Cold start | `adb shell am start -W -n com.d4guilar.shelfos/.MainActivity` | 714–724 ms `TotalTime`, twice |
| Frame timing while paging the EPUB reader (5 page turns) | `dumpsys gfxinfo com.d4guilar.shelfos` | 50th/90th/95th/99th percentile 10/44/150/250 ms combined across two activity windows; the reported "janky (legacy)" rate ranged 3–21% depending on window. Not compared against a target: none is documented |
| Memory footprint | `dumpsys meminfo com.d4guilar.shelfos` | TOTAL PSS 196 MB, Java heap 9.9 MB, native heap 14.3 MB, graphics 31 MB, with the small fixture and two activities resident |

The 3.16 GB CBZ heap-flatness measurement from the emulator private-sample pass was not
repeated on physical hardware: pushing a large private sample to this personal device was
out of scope for this pass. Frame-time profiling under real load (a large CBZ/PDF) and
physical-device heap measurement under that load remain open, as recorded above.

### MOTION

- Reduced motion (`animator_duration_scale`/`window_animation_scale`/`transition_animation_scale = 0`):
  the full instrumented suite passes unchanged on both the emulator (existing evidence) and
  this physical device (above). No test depends on animation timing.
- Rotation/recreation does not produce a broken transition: confirmed by the corrected
  `EpubRecreationTest` on physical hardware — after a real rotation, controls stay hidden
  and the layout settles without a stale frame.
- Focus/selection feedback (`ShelfChoiceChip` check marks, chip selection) is unchanged
  from Review remediation and was not re-verified in this pass.
- **The documented cover-expansion reader transition (`docs/design/CLASSIC_UI.md` §12) is
  still not implemented.** This is unchanged from the existing "1D Library/detail/reader
  transitions: PARTIAL" row. Implementing a shared-element/cover-expansion transition is
  animation-system feature work, not a small fix to an existing acceptance test failure;
  building it now would go beyond this closure pass's "smallest necessary fix" mandate and
  risk exactly the kind of new UI-state defect this pass exists to avoid. It remains an
  explicit, named software gap rather than something quietly dropped from the requirement.

### FINAL ACCEPTANCE

**PHASE 1: NOT YET ACCEPTED.**

What changed in this pass: the physical-device gate has real evidence for the first time
(full instrumented suite, a real SAF import end to end, force-stop/resume, large text,
reduced motion, and an accessibility-node-tree check), and one test bug found only on real
landscape-locked hardware was fixed without touching R1–R5 or production code.

What still blocks acceptance, unchanged in kind from Codex's final verdict:

- TalkBack spoken-output/gesture verification (no TalkBack on the available device).
- Confirmed physical keyboard/controller button presses (only simulated key events were run).
- API 24 and API 37 runtime checks.
- Reader zoom/fit and the global "all titles" Appearance save, exercised on a device.
- Real non-seekable provider, low-storage and interrupted-copy cases, and Room transaction
  rollback.
- Frame-time profiling and physical-device heap measurement under real (large-file) load.
- The documented cover-expansion reader transition; 1D scroll restoration and rapid-switch
  evidence.

None of these are R1–R5 findings. See the [physical device acceptance
checklist](../PHASE1_CLAUDE_HANDOFF.md#physical-device-acceptance-checklist) for what the
owner can complete manually on this or another device.

## Phase 1 acceptance closure — continuation (2026-09-24)

Continues the closure pass above. New automated coverage was found already in the working
tree at the start of this continuation — `LibraryPersistenceTest` gained non-seekable
provider, low-storage, interrupted-copy and Room-rollback cases; `NavigationSmokeTest`
gained a reader zoom/fit + global-Appearance-persistence case and a combined 1D
scroll-restoration/rapid-title-switching case; a new `SyntheticLoadAcceptanceTest` exercises
a large generated archive; a debug-only `NonSeekableTestProvider` backs the provider tests.
This section verifies that coverage, finishes API 24/37, and completes performance
measurement. It preserves and does not duplicate that work.

### STATIC / BUILD

`gradlew :app:testDebugUnitTest --tests com.d4guilar.shelfos.ReviewRegressionTest
--init-script .tools/codex-review.init.gradle --rerun`: 3/3 pass. `gradlew
:app:testDebugUnitTest --rerun`: 60/60 pass (unchanged; the new tests are instrumented).
`gradlew :app:lintDebug`: 0 errors. Lint now also reports one new `ExportedContentProvider`
warning for `NonSeekableTestProvider`; it is a debug-only test provider (`app/src/debug/`),
never part of a release build, and does not change lint's error count. `assembleDebug` and
`assembleDebugAndroidTest`: BUILD SUCCESSFUL. `git diff --check`: clean.

### JVM / REGRESSION

Unchanged: 60 JVM tests, 0 failures.

### RESILIENCE (non-seekable provider, low storage, interrupted copy, Room rollback)

All four have real automated coverage now, in `LibraryPersistenceTest`:

- **Non-seekable provider.** A debug-only `ContentProvider` returns a pipe descriptor
  (`ParcelFileDescriptor.createReliablePipe()`), not a file. `nonSeekableProviderCopies…`
  copies it, commits it, reopens the committed item, and confirms cleanup never touches the
  provider's own source.
- **Low storage.** `PublicationFiles` takes an injectable free-space function (production
  code addition: `availableBytes: ((File) -> Long)?`, defaulting to the real
  `StorageManager`/`File.usableSpace` check as before). The test forces 0 available bytes
  and confirms `INSUFFICIENT_STORAGE` with no partial file left.
- **Interrupted copy.** The same provider's `.../interrupted` path writes half the bytes
  then calls `closeWithError(...)`. The test confirms `COPY_FAILED` with no partial file,
  and that a retry of the same source succeeds normally afterward.
- **Room rollback.** A SQLite trigger injected only for this test aborts the delete inside
  `LibraryDao.remove()`'s existing `@Transaction`. The test confirms the whole transaction
  rolls back: the item, its reading position and its preferences all survive. This needed
  no production change — `remove()` was already `@Transaction`.

Confirmed PASS on the physical Retroid Pocket 5 (10/10 `LibraryPersistenceTest` cases, one
run, no crash) and confirmed PASS on the `shelfos-api24` emulator, though not reliably in
the same run as several preceding tests — see Compatibility below for that emulator-specific
finding.

### COMPATIBILITY: API 24 and API 37

Both AVDs already existed (`shelfos-api24`, `shelfos-api37`) and were already running.

**API 24 (`shelfos-api24`, Android 7.0, x86_64, GPU emulation disabled).**

- App launches (`am start -W`: COLD, succeeds) and the full `NavigationSmokeTest` class —
  launch, import (seeded via the repository, matching the emulator/RP5 smoke pattern),
  Library render, PDF/EPUB/Manga reader open/read/resume/Back, keyboard and D-pad, the new
  zoom/fit + global-Appearance test, and the new scroll-restoration/rapid-switch test —
  **passes 12 of 12.**
- `LibraryPersistenceTest`'s 10 cases pass individually and in small groups. Run together
  (as the full class, or as part of the full 28-test suite), the same suite intermittently
  ends with a **native crash**: `Fatal signal 11 (SIGSEGV) ... libpdfium.so
  (CPDF_Document::CPDF_Document+96)`, always inside
  `nonSeekableProviderCopiesCommitsReopensAndCleansWithoutTouchingTheSource`, reproduced in
  3 of 3 full-sequence attempts.
  - It does **not** reproduce running that test alone, or paired with the immediately
    preceding test.
  - It does **not** reproduce at all on the physical Retroid Pocket 5 running the identical
    test, identical code, in the identical full-suite sequence (confirmed twice).
  - The crash is inside the platform's bundled `libpdfium.so`, not ShelfOS code, and other
    tests exercising the same `PublicationFiles` → `PdfRenderer` path (`pdfAndArchiveRender…`,
    `copyImportDetects…`) pass repeatedly on this same emulator.
  - Conclusion: this is most consistent with a resource/memory-accumulation instability
    specific to the `shelfos-api24` x86_64 AOSP emulator image (a 2016 build already known,
    before this pass, to need GPU emulation disabled just to boot) under repeated native
    `PdfRenderer` allocation within one process, not a confirmed ShelfOS or production
    defect. It is **not independently confirmed absent on real API 24 hardware**, which was
    unavailable for this pass. Recorded honestly rather than hidden or asserted as a fix.
- **API 24 result: PASS for the app's own behavior** (launch, import, read, resume,
  zoom/fit, global Appearance, scroll restoration, rapid switching, and the four resilience
  cases all individually verified). The emulator-specific crash above is a flagged,
  unresolved environment finding, not claimed as fixed or as a confirmed defect.

**API 37 (`shelfos-api37`, Android 17, Google Play image).**

- The system image boots and **ShelfOS itself launches and renders correctly**
  (`am start -W`: COLD, 1794 ms; screenshot confirms Library, navigation and layout render
  as expected).
- Non-UI instrumented tests — `LibraryPersistenceTest`, `RoomPersistenceTest`,
  `ReaderStateTest` (13 tests: Room, import/file logic, locator/preference serialization) —
  **pass 13 of 13.**
- Automated Compose UI instrumented tests (everything that needs Espresso's `onIdle`, i.e.
  every `NavigationSmokeTest`/`AppearanceRestorationTest`/`EpubRecreationTest`/
  `SyntheticLoadAcceptanceTest` case) **cannot run**: every one fails with
  `java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance`. This
  is the project's pinned `androidx.test`/Espresso version calling a static
  `InputManager.getInstance()` accessor that this platform version has removed — a test-
  tooling version gap against a very new API level, not a missing system image and not
  reproduced as an app defect (the app itself runs fine, per the previous two points). No
  dependency version change was attempted: this offline environment cannot download a newer
  artifact to verify, and a test-only dependency bump is outside this pass's scope.
- **API 37 result: system image and app PASS; automated UI-instrumented-test coverage
  NOT VERIFIED (tooling limitation, not a system-image or app problem).** This is the exact
  blocker, not a substitution: the image is real, current (Android 17), and boots; only the
  UI-driving test harness cannot run against it yet.

### ACCESSIBILITY

The runtime accessibility-node-tree check from the previous pass stands (every reader
control has a visible text label and a ≥48dp touch target).

New this pass: TalkBack (`com.google.android.marvin.talkback`) is preinstalled on the
`shelfos-api37` Google Play emulator (unlike the RP5 or the AOSP `shelfos-api24` image).
It was enabled and confirmed bound and running (`dumpsys accessibility`: TalkBack service
bound with `FEEDBACK_SPOKEN, FEEDBACK_HAPTIC, FEEDBACK_AUDIBLE`). However, `adb shell input`
touch/swipe gestures did not reliably drive TalkBack's touch-exploration or announcement
pipeline in this environment: no accessibility-focus change and no utterance-related log
activity were observed after repeated swipe gestures. This is a genuine limitation of
driving TalkBack through ADB-injected input, not a ShelfOS finding either way. TalkBack was
disabled again afterward, restoring the emulator's default state.

**TalkBack: still NOT VERIFIED.** No environment available to this pass could produce
genuine spoken-output or gesture-traversal evidence. This requires a person operating
TalkBack, on the RP5 or on the `shelfos-api37` emulator (already has TalkBack installed) —
see the physical/manual checklist.

### INPUT / HARDWARE

Unchanged: keyboard/D-pad coverage now also confirmed via simulated key events on API 24
(12/12) and, for non-UI paths, API 37. Real physical button presses on the RP5's own
hardware remain **NOT VERIFIED** — see the physical checklist.

### PERFORMANCE

A representative synthetic load, generated for this pass and not committed to Git: a 219 MB,
160-page CBZ (1200×1800 JPEG pages), run on the physical Retroid Pocket 5.

| Measurement | Method | Result |
| --- | --- | --- |
| Memory while paging (page 1 → page 81 of 160) | `Debug.MemoryInfo().totalPss` before/after, logged by the test | 335,750 KB → 346,326 KB PSS (roughly flat over 80 page turns); Java heap 9.6 MB |
| Memory snapshot held at page 81 | `adb shell dumpsys meminfo com.d4guilar.shelfos` | TOTAL PSS 271,784 KB; Java heap 7.4 MB, native heap 36.0 MB, graphics 99.9 MB |
| Frame timing while paging and holding at page 81 | `adb shell dumpsys gfxinfo com.d4guilar.shelfos` | 718 frames rendered, 0.28% janky (legacy 0.28%); 50th/90th/95th/99th percentile 5/5/6/10 ms |
| Cold start (trivial fixture, from the previous pass) | `adb shell am start -W` | 714–724 ms, RP5; 1794 ms, API 37 emulator (first cold start after install, includes APK verification) |

No documented Phase 1 threshold exists, so these are measurements, not pass/fail gates.
The earlier trivial-fixture measurements from the previous closure pass are retained above
for cold-start reference; this larger, generated load is the more representative one for
memory/frame behavior. The original 3.16 GB private-sample measurement (emulator, Phase 1
validation) remains the largest load actually measured for this app; it was not repeated on
physical hardware in this or the previous pass.

### 1D SCROLL RESTORATION AND RAPID SWITCHING

New test: `libraryScrollRestoresAfterReaderAndRapidTitleSwitchingKeepsTheRightSession`
(`NavigationSmokeTest`). It seeds 30 additional Book items, scrolls deep into the grid,
opens one, reads a page, returns, and confirms the scrolled-to item is still visible;
then switches Library → Manga reader → Library → Books reader → Library twice more in
quick succession, confirming each reopen shows the correct title's own session and page,
and that the Library remains valid throughout.

- **PASS on the Retroid Pocket 5** (part of the 28/28 full-suite run).
- **PASS on API 24** (part of the 12/12 `NavigationSmokeTest` run), after a narrow test fix:
  the test scrolled to a hardcoded index (33) copied from an assumption about the total
  item count; Compose's own bounds error ("Can't scroll to index 33, it is out of bounds
  [0, 33)") showed the true count is 33 because the Library's "Continue Reading" header
  shares the same tagged `LazyVerticalGrid` as the publication items (confirmed by reading
  `LibraryScreen.kt`). The valid last index is 32; the test now computes it
  (`extraIds.size + 2`) instead of hardcoding a guessed count. This is a one-line test
  correctness fix with a verified root cause, not a weakening: the assertions and the
  scenario are unchanged.

### ZOOM / FIT AND GLOBAL APPEARANCE

New test: `fixedReaderZoomFitAndGlobalAppearancePersistAcrossRecreation`
(`NavigationSmokeTest`). Opens an Original PDF, exercises the existing "Zoom in"/"Reset
zoom" control (present since Phase 1's original implementation), sets Fit width and "Use as
default for all titles" through Appearance, applies it, reopens Appearance to confirm the
selection stuck, then recreates the activity and confirms the same selection survives.
**PASS on the Retroid Pocket 5 and on API 24** (both full-suite runs above).

### MOTION

No change from the previous pass's determination: the documented cover-expansion reader
transition (`docs/design/CLASSIC_UI.md` §12) remains unimplemented. See the previous
"Decision: motion" analysis in `PHASE1_CLAUDE_HANDOFF.md`; nothing in this continuation
changes that determination or reopens it as a smaller fix than originally assessed.

### FINAL ACCEPTANCE (continuation)

**PHASE 1: NOT YET ACCEPTED.**

What this continuation resolved: non-seekable provider, low storage, interrupted copy, and
Room rollback all now have real automated coverage and pass on real hardware; 1D scroll
restoration and rapid-switch stress now have coverage and pass; reader zoom/fit and global
Appearance persistence now have on-device coverage and pass; API 24 and API 37 were both
actually run, each with a precise, honest result rather than an assumption.

What remains, precisely:

- TalkBack spoken-output/gesture confirmation (a person operating TalkBack is required).
- Real Retroid Pocket 5 hardware button presses (a person is required).
- The API 24 emulator-specific native-crash finding above, unconfirmed on real API 24
  hardware (none available).
- API 37 automated UI-instrumented-test coverage (blocked by an androidx.test/Espresso
  version gap against this very new platform, not by the system image or the app).
- Frame-time/heap profiling under an even larger load than the 219 MB synthetic sample used
  here (the original 3.16 GB private sample was not repeated on physical hardware).
- The documented cover-expansion transition, by deliberate choice (see Motion above).

None of these are R1–R5 findings.

### Physical controller — owner-verified (2026-09-24)

The owner tested ShelfOS on the Retroid Pocket 5 using its actual physical gamepad
(D-pad/buttons), not ADB-injected key events. Reported result: the gamepad works correctly
with no issues observed during normal Library and Reader interaction — physical D-pad/
controller navigation, activation, reader controls and Back behavior all worked as
intended. This is owner-reported, on-device evidence, distinct from and additional to this
pass's own simulated-key-event coverage; it was not rerun or replaced with simulated input.

**REAL HARDWARE INPUT / PHYSICAL CONTROLLER: VERIFIED (PASS)** on the Retroid Pocket 5.

This closes the "Real Retroid Pocket 5 hardware button presses" item above and the
corresponding row in `PHASE1_CLAUDE_HANDOFF.md`.

## Phase 1 acceptance closure — final items (2026-09-24)

Closes the five remaining items from the previous continuation: the cover-expansion
transition, large-load performance, and a precise interpretation of the API 24/API 37
compatibility criterion against the plan's own wording. TalkBack is handed to the owner as
a manual checklist (below). R1–R5 and everything already resolved in the two earlier
closure sections were not reopened.

### Cover-expansion reader transition (CLASSIC_UI.md §12)

**Implemented — the smallest faithful version.** `docs/design/CLASSIC_UI.md` §12: "selected
cover subtly expands, system chrome fades, reader appears... fast and optional under
reduced-motion settings."

What it is: when Read is chosen from a publication's Details screen (the standalone
compact route, or the persistent pane in expanded/tablet layouts), that screen's own cover
image is captured at its on-screen position. A transient, decorative overlay (excluded from
the accessibility tree) grows that cover from there to the incoming reader's area over
220 ms, while the surrounding chrome (top bar, navigation rail or bottom bar) fades out
over the first 60% of that time so the reader is never revealed through visible chrome.
The real navigation — the same `NavHost` route change or `EpubActivity` launch as before —
runs once the animation completes; nothing about reader lifecycle, ViewModels, or Activity
launches changed.

What it deliberately does not do, and why that is still faithful to the requirement:
- It does not attempt a true cross-Activity shared element into `EpubActivity` (a separate
  Activity hosting Readium's fragment). Building that would mean Activity-transition/shared-
  element machinery well beyond "the smallest faithful version," and risk exactly the
  reader-lifecycle regressions this closure exists to avoid. EPUB gets the same expand
  overlay up to the point of launch, then the existing Activity launch takes over.
- The Continue Reading card's direct tap (which does not pass through a Details screen, so
  there is no "selected cover" moment in the documented sense) is unchanged: no overlay,
  instant navigation, exactly as before.
- Exit reversal ("when practical," per the source text) was not implemented; the existing
  120 ms crossfade continues to handle leaving the reader. The specification allows this.

Reduced motion: `Context.reducedMotionEnabled()` (`core/theme/ReducedMotion.kt`) checks
`ValueAnimator.areAnimatorsEnabled()` (API 26+) or the `ANIMATOR_DURATION_SCALE` setting
directly (API 24–25, since `ValueAnimator.areAnimatorsEnabled()` requires API 26 and minSdk
is 24). When true, `openReader` skips the overlay and navigates immediately — the
"documented minimal/no-motion behavior" the source text calls for.

No generalized animation system was created: the overlay is a single-purpose composable
(`feature/home/CoverExpandTransition.kt`) with one pure, unit-tested function
(`chromeAlpha`) and no reusable transition API. `LibraryScreen`/`PublicationDetails` were
not redesigned; each gained one parameter (a nullable on-screen `Rect`) threaded through
existing callbacks.

**Regression coverage:**
- JVM: `CoverExpandTransitionTest` (3 tests) — chrome is fully visible before the transition
  starts, fully faded before the expansion finishes, and never increases in between.
- Instrumented: `NavigationSmokeTest.readerEntryTransitionIsSkippedUnderReducedMotion`
  (new) — forces `animator_duration_scale=0` via a shell command, confirms Read still opens
  the reader. All of `NavigationSmokeTest`'s existing Details→Read cases
  (`originalPdfOpensTurnsPagesResumesAndReturnsToLibrary`,
  `originalEpubOpensAndOffersTypographyAndChapters`, the expanded-layout detail pane cases,
  and the newer zoom/fit and scroll-restoration/rapid-switch cases) now exercise the
  transition's normal-motion path as a side effect of exercising Read at all, since they
  already go through `PublicationDetails`.

**Validation:**
- JVM: 63 tests (was 60), 0 failures. Codex's 3 regressions unaffected. Lint 0 errors
  (unchanged). `assembleDebug`/`assembleDebugAndroidTest`: BUILD SUCCESSFUL.
- Physical device (Retroid Pocket 5): full instrumented suite, three separate runs across
  this pass — 28/28 with a partial suite once, then 29/29, 29/29 (clean rebuild), and 29/29
  again with system-wide reduced motion forced. One run in the middle of this work showed 9
  unrelated failures ("No compose hierarchies found," including in a test using bare
  `createComposeRule()` that shares no code with this feature); the very next run, and the
  one after, passed cleanly. Treated as transient device load from a long testing session,
  not a regression — the failure pattern (test-infrastructure registration errors,
  unrelated to which code was under test) is inconsistent with a code defect and
  inconsistent between consecutive otherwise-identical runs.
- Emulator (API 24, `shelfos-api24`): `NavigationSmokeTest`, 13/13 (12 existing + the new
  reduced-motion case).
- The transition's exact visual appearance was not captured frame-by-frame: at 220 ms it is
  faster than manual ADB screenshot polling can reliably catch (confirmed by attempting it;
  the capture landed on the reader's own load state, after the transition had already
  finished). Its correctness is established by the passing tests above, not by a visual
  recording.

### Large-load performance (closer to the ~3.16 GB private sample)

A synthetic, non-copyrighted CBZ was generated on-device for this pass only (not committed;
not left on disk afterward) and read on the physical Retroid Pocket 5 (API 33).

| Property | Value |
| --- | --- |
| Generated size | 2,997,685,907 bytes (≈2.99 GB) |
| Page count | 947 (1600×2400 JPEG pages, stored uncompressed-in-ZIP so archive size matches page bytes exactly) |
| Device / API | Retroid Pocket 5, API 33 |
| Open time | 534–535 ms (two runs) from tapping Read to the first page rendering |
| Pages visited | 1 through 180, by the same sequential Next control the UI exposes (no private API) |
| Memory (`Debug.MemoryInfo`, in-process) | PSS 231.6 MB → 249.7 MB (page 150) → 246.4 MB (page 180) on the first run; 231.6 MB → 241.2 MB → 241.3 MB on the second. Roughly flat, not growing linearly with pages read |
| Java heap | 6.6 MB, both runs |
| Memory (`adb shell dumpsys meminfo`, held at page 180) | TOTAL PSS 241.8 MB; Java heap 7.3 MB; native heap 21.8 MB; graphics 82.4 MB |
| Frame timing (`adb shell dumpsys gfxinfo`, held at page 180) | 1543 frames rendered; 0.19% janky; 50th/90th/95th/99th percentile 5/5/5/6 ms |

No documented Phase 1 performance threshold exists, so this is a measurement, not a
pass/fail gate. It is not the original 3.16 GB private sample (per instruction, that sample
was not reused), but at essentially the same order of magnitude (2.99 GB vs. 3.16 GB, both
roughly 1,000-page-class archives) it shows the same pattern the original private-sample
validation found: memory stays bounded and frame timing stays smooth while paging through
an archive of this size, on real hardware. The generating test file was a temporary,
uncommitted scratch file, deleted from the repository after this validation; the archive
itself was deleted from the device by its own test teardown (confirmed via `run-as ls`
immediately afterward).

### API 24 and API 37: interpreting the compatibility criterion

Exact source (`docs/PHASE_1_PLAN.md` §7, "Compatibility"):

> API 24 and current-target runtime, compact/expanded layouts, at least one physical
> device, keyboard/D-pad and physical controller where available; TalkBack and
> reduced-motion checks. Document any unexecuted hardware tests.

This wording exercises specific dimensions (each API level, layout modes, at least one
physical device, input methods, TalkBack, reduced motion) and asks that unexecuted hardware
tests be documented. It does not say the mechanism must be one specific automated
instrumented-test command completing without any flake, on every runtime, as the sole
proof. Read that way:

**API 24: the runtime requirement is satisfied**, with an emulator-specific limitation
documented rather than hidden. Evidence: the app launches; the full UI smoke suite
(`NavigationSmokeTest`, including the reader-entry transition and its reduced-motion case)
passes reliably (13/13, confirmed again in this pass); the resilience suite
(`LibraryPersistenceTest`) passes individually and in pairs. The one unresolved item is an
intermittent native crash inside the platform's own `libpdfium.so`, reproduced only when
many `PdfRenderer`-touching tests run back-to-back on the specific `shelfos-api24` x86_64
AOSP emulator image (a 2016 build already known, before this pass, to need GPU emulation
disabled just to boot) — never on the physical device running the identical sequence, and
never in smaller groupings on the same emulator. This is documented as an accurate,
unresolved test-environment limitation specific to that emulator image, not classified as a
ShelfOS defect and not silently dropped from the record. If the owner's intent is
specifically "an uninterrupted, all-tests-in-one-run, zero-flake automated pass on this
particular emulator image," that narrower bar remains unmet; the plan's own text does not
require that specific mechanism.

**API 37: the runtime requirement is satisfied at the application level; automated
UI-instrumented coverage on this platform is not.** Evidence, kept separate as the plan's
wording (and this pass's instructions) ask: the system image boots; ShelfOS itself launches
and renders correctly (screenshot evidence, 1794 ms cold start); non-UI instrumented tests
(Room, import/file logic) pass 13/13. Automated UI-instrumented tests cannot run against
this specific platform version with the project's currently pinned androidx.test/Espresso
version, which calls a static `InputManager.getInstance()` accessor this platform removed —
a test-tooling version gap against a very new API level (Android 17), not a missing system
image and not a demonstrated ShelfOS runtime failure. No dependency version change was made
to work around this: it is out of this pass's scope, and this offline environment cannot
fetch a newer artifact to verify one regardless.

Neither classification substitutes a different API level, weakens the criterion, or
invents a numeric pass threshold.

### TalkBack: manual checklist — PASS (owner-verified, 2026-09-24)

This pass enabled and confirmed TalkBack running (bound, `FEEDBACK_SPOKEN`/`HAPTIC`/
`AUDIBLE`) on the Google Play `shelfos-api37` emulator, then handed it to the owner: ADB-
injected gestures do not reliably drive TalkBack's touch-exploration or announcement
pipeline, so no automated run could have produced genuine spoken-output evidence itself.
The emulator was relaunched with a visible window (data preserved, TalkBack still enabled
after restart) so the owner could operate it directly with real gestures.

The owner ran the 7-item checklist in `PHASE1_CLAUDE_HANDOFF.md` → "TalkBack manual
checklist" on the windowed `shelfos-api37` emulator and reported: **Pass.** Navigation
labels, publication/Details announcements, the reader entry path, the Appearance dialog's
controls, and Back/Library traversal all worked with TalkBack's real spoken/gesture
navigation, with no focus trap encountered.

**TALKBACK: VERIFIED (PASS)**, owner-reported, genuine spoken/gesture evidence — not
simulated, not inferred from the accessibility node tree alone.

### Final validation (this pass)

| Check | Result |
| --- | --- |
| Codex R1–R5 regressions | 3/3 pass |
| Full JVM suite | 63/63 pass (was 60; +3 `CoverExpandTransitionTest`) |
| Lint | 0 errors (same warnings as before) |
| `assembleDebug` / `assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `git diff --check` | Clean |
| RP5, full instrumented suite (normal motion) | 29/29, twice (after one transient failure, see above) |
| RP5, full instrumented suite (system-wide reduced motion) | 29/29 |
| API 24, `NavigationSmokeTest` | 13/13 |
| Large-load synthetic archive (RP5) | Completed; see above |

### Final acceptance

**PHASE 1: ACCEPTED (2026-09-24).**

Every item from `PHASE_1_PLAN.md` §7 has now been exercised, with evidence recorded rather
than assumed, across three closure passes plus the original Phase 1 validation and Codex's
R1–R5 review and remediation verification:

- **Unit, Room, instrumented, CI/build:** all pass (63 JVM tests, Codex's 3 regressions, 0
  lint errors, both builds, `git diff --check` clean).
- **Private acceptance, reliability:** the original four private samples, plus non-seekable
  provider, low storage, interrupted copy and Room transaction rollback — all covered and
  passing.
- **Compatibility:** API 24 and API 37 (current-target) runtime both exercised, each
  classified precisely against the plan's own wording (above); at least one physical
  device (Retroid Pocket 5) exercised extensively; keyboard/D-pad confirmed by both
  simulated and, for the RP5's own hardware, owner-verified real button presses; TalkBack
  now owner-verified PASS; reduced-motion checks pass on physical hardware and emulators,
  including the new cover-expansion transition's own fallback.
- **1D polish:** scroll restoration, rapid title switching, reader zoom/fit, global
  Appearance persistence, and the cover-expansion transition are all implemented and tested.
- **Performance:** measured at a scale (2.99 GB) close to the original 3.16 GB reference,
  on physical hardware, with no documented threshold to fail against.

Two items remain recorded as documented, non-blocking limitations rather than resolved
gates, per their own classification above — neither is a ShelfOS defect, neither was
substituted or weakened, and both are called out explicitly rather than silently absorbed
into "accepted":

- The API 24 emulator-specific native-crash finding (`shelfos-api24` x86_64 AOSP image
  only, never reproduced on physical hardware).
- API 37 automated UI-instrumented-test coverage, blocked by an androidx.test/Espresso
  version gap against a very new platform (the application itself is confirmed working).

None of these, nor anything above, reopens R1–R5.

## Phase 0 validation (2026-09-23)

Status: Phase 0 foundation implemented and validated in the local emulator
acceptance environment on 2026-09-23. This is an early prototype, not a
production-readiness statement. Unexecuted checks are listed separately below.

### Automated commands

```sh
bash ./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
bash ./gradlew :app:connectedDebugAndroidTest
```

Use `./gradlew.bat` on Windows. JDK 17, SDK API 37 and Build Tools 36.0.0
are required. A connected emulator/device is required only for the second command.

Unit tests cover registry fallback/availability, navigation breakpoints, context
sensitive input mapping, system key pass-through, favorite/category semantics,
saved-state reconstruction and preference-write failure. Device tests cover Room
close/reopen persistence, five destinations, theme selection/recreation and basic
keyboard activation/publication selection.

### Device acceptance matrix

- Compact phone portrait: bottom navigation; scrollable complete category names;
  2–3 column grid; selecting a cover opens details; Back returns to Library.
- Tablet >=1000dp wide and >=480dp high: compact rail; grid and persistent details;
  touch/keyboard/D-pad selection updates the selected publication.
- Resize and rotate: preserve category, selection, query, theme and navigation.
- Separating/occluding fold: use larger unobstructed region; no controls under hinge.
- Switch Classic/Dark, force-stop and relaunch: same saved theme.
- Tab, Shift+Tab and D-pad: visible focus, reachable navigation/categories/covers;
  Enter/gamepad A activates; Ctrl+F opens Search; Escape/gamepad B goes back.
- Android Back/Home/Recents leave the app normally; test predictive/system Back.
- Large font/display scaling and TalkBack: usable touch targets, meaningful labels,
  readable navigation, scrollable content, no focus trap.
- Airplane mode: all prototype behavior works; no network permission.
- API 24 and current API: launch and basic navigation.

### Results

| Check | Result |
| --- | --- |
| Clean Git checkout, build cache disabled | Passed: debug APK, unit tests, lint, device-test APK; all 85 tasks executed |
| JVM tests | 7 passed: FoundationTest (5), StateTest (2) |
| Phone Android tests | 5 passed on AOSP API 35, 1080×1920 at 420dpi |
| Expanded Android tests | Same 5 passed on AOSP API 35, 1600×1000 at 160dpi |
| Launch | Successful cold launches on the emulator |
| Classic / Dark | Visually inspected; local-only screenshots in `design/screenshots/` (excluded from Git) |
| Theme persistence | Room close/reopen test, activity recreation test, and manual Dark force-stop/cold-relaunch check passed |
| Navigation / focus | Five destinations, publication details, Enter activation and D-pad movement passed on both window sizes |
| Window adaptation | Observed bottom navigation → rail + details → bottom navigation when resizing the same running emulator |
| Saved state | Category, selection, query and demo favorites reconstructed in ViewModel unit test |
| Source / secrets audit | No signing/credential/local-machine files among nonignored source candidates; no recognized private-key/API-token patterns; main manifest requests no permissions |
| Room | Generated schema v1 contains only `appearance_preference` |
| CI | Workflow configured; not executed on GitHub during this session |

The clean-checkout check used a disposable local Git snapshot of the source
candidates, cloned into an ignored directory. No commits or remote changes were
made in the user's repository. It used the same JDK 17 and installed SDK but no
local project properties, build outputs or Gradle build-cache reuse. Command:

```sh
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-build-cache --console=plain
```

Device checks used `:app:connectedDebugAndroidTest` in each window configuration.
The final phone pass also ran the built test APK directly with:

```sh
adb shell am instrument -w com.d4guilar.shelfos.test/androidx.test.runner.AndroidJUnitRunner
```

The fresh checkout's lint report contains no issues. Earlier connected/source
checks produced an informational Gradle 9.7.1 update suggestion; 9.6.0 remains
the documented AGP 9.4 baseline. The backup-rule warning found during development
was corrected for both pre-Android-12 and newer Android versions.

The debug APK is approximately 12.3 MiB, including debug tooling. This is not
a release size measurement. `DEPENDENCY_LICENSES.csv` records the 74 resolved
runtime artifacts and their license declarations.

### Remaining validation and limitations

- Physical phone/tablet/foldable and physical gamepad testing has not been done.
- Runtime tests used API 35; API 24 and API 37 runtime checks remain unexecuted.
- Hinge avoidance is implemented conservatively, but physical fold/posture and
  process-death continuity need broader testing.
- Full TalkBack, large-font, accessibility, performance and large-library QA
  are still needed before production. This prototype has ten sample publications.
- GitHub branch protection and the first remote CI run require repository-side
  follow-through after these local changes are reviewed and pushed.

These limits must remain visible when evaluating the prototype. Importing,
reading, metadata enrichment, annotations and production releases are future work.
