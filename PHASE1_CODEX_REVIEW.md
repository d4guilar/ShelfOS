# Phase 1 Codex review

Date: 2026-09-24

## Result

**CHANGES REQUIRED — not PASS.** Scope is no longer blocked.

Five actionable findings: **1 HIGH, 2 MEDIUM, 2 LOW**. No CRITICAL finding.
The existing suite passes; targeted regression checks expose failures it does not
cover. Phase 1 acceptance also remains incomplete for the unexecuted gates below.

## Scope and method

Baseline and HEAD: `f252e8bfbc7ef128eb08d4f117fa82cdc757f258`.
Per the owner's clarification, Claude's uncommitted working tree is the implementation.
Reviewed `git diff HEAD`, `git diff --cached`, `git status --short` and
`git ls-files --others --exclude-standard`: 39 modified tracked files (+1518/-549),
no staged changes, and all nine new implementation/test files:

- `app/src/main/java/com/d4guilar/shelfos/core/files/EmbeddedMetadata.kt`
- `app/src/main/java/com/d4guilar/shelfos/core/files/SeekableZip.kt`
- `app/src/main/java/com/d4guilar/shelfos/domain/importing/ImportPolicy.kt`
- `app/src/main/java/com/d4guilar/shelfos/domain/library/PublicationProblem.kt`
- `app/src/main/java/com/d4guilar/shelfos/feature/reader/ReaderPersistence.kt`
- `app/src/test/java/com/d4guilar/shelfos/ImportPolicyTest.kt`
- `app/src/test/java/com/d4guilar/shelfos/ImportViewModelTest.kt`
- `app/src/test/java/com/d4guilar/shelfos/SeekableZipTest.kt`
- `app/src/androidTest/java/com/d4guilar/shelfos/ReaderStateTest.kt`

Read AGENTS, the handoff, current Phase 1 plan/roadmap and directly relevant
architecture, import, library, reader, input, Shelves and licensing contracts.
Expanded into unchanged code only to understand changed paths and their contracts.
This was not a fresh repository-wide audit. Coordination documents were context/output,
not implementation. Private strategy, example media and concept images were excluded.
Retained acceptance defects in revised paths are identified explicitly below;
not every finding is attributed to a newly introduced regression.

## Findings

### R1 — HIGH — Clearing the import ViewModel can discard a source being committed

**Location:** `app/src/main/java/com/d4guilar/shelfos/feature/importing/ImportViewModel.kt:65-71,87-91`;
`app/src/main/java/com/d4guilar/shelfos/core/files/PublicationFiles.kt:186-188`.

**Issue:** `confirm()` retains `pending` while `repository.add()` suspends inside
`NonCancellable`. Clearing its ViewModel launches cleanup for that same preparation.
Insertion can finish after cleanup has deleted its private copy or released its grant.
Even cleanup after insertion deletes the managed file: `sourceStillUsed` protects
only the grant in `discard()`, not the copy.

**Impact:** A committed LibraryItem can point to a deleted offline copy or inaccessible
source. External originals are not deleted, but ShelfOS's retained publication and
successful-import result become unusable. This violates the Phase 1 ownership contract.

**Evidence:** A coroutine regression test suspends `add()`, calls
`ViewModelStore.clear()`, then completes insertion. The item is committed and its
preparation is also discarded. This hole exists in the baseline too; it remains an
acceptance defect in the revised import lifecycle, not a new regression claim.

**Recommended fix:** Transfer preparation ownership to the committing operation
before suspension. ViewModel cleanup must clean abandoned preparations only.
Resolve ownership on success, duplicate conflict and failure. Add lifecycle tests
for clearing during a suspended insert, covering copies and referenced sources.

### R2 — MEDIUM — Abandoned-import cleanup can revoke the next import's grant

**Location:** `app/src/main/java/com/d4guilar/shelfos/feature/importing/ImportViewModel.kt:31,78-88`;
`app/src/main/java/com/d4guilar/shelfos/core/files/PublicationFiles.kt:83-90,186-188`.

**Issue:** Dismissal/replacement launches cleanup asynchronously. A new preparation
for the same URI can start first, see the old persisted grant and avoid acquiring
one. Cleanup checks only committed items, sees none, and releases the grant used by
the active replacement. The startup-maintenance gate does not serialize these operations.

**Impact:** Cancel/reimport can commit a publication without durable access; temporary
picker permission may mask the failure until later. The plan explicitly protects
grants required by active operations, not just committed items.

**Evidence:** A coroutine test pauses cleanup's repository read, dismisses the first
review, selects the same URI again and lets it reach review. Resuming cleanup then
committing leaves the new item without a grant. The new replacement branch shares
this issue with the retained dismissal path.

**Recommended fix:** Await previous cleanup before preparing replacements, or serialize
grant ownership with active-operation tracking. A database snapshot alone is insufficient.
Test cancel/reselect and review replacement with delayed cleanup; retain idempotent insertion.

### R3 — MEDIUM — Reader UI state and Appearance drafts are lost on recreation

**Location:** `app/src/main/java/com/d4guilar/shelfos/feature/reader/EpubActivity.kt:47-48,67-69`;
`app/src/main/java/com/d4guilar/shelfos/feature/reader/ReaderAppearance.kt:26-28`.

**Issue:** `super.onCreate(null)` drops the saved-state registry as well as fragment
state, preventing EPUB controls/Appearance/Chapters flags from restoring. The shared
Appearance editor also stores its draft, initial comparison snapshot and global-default
selection in plain `remember`; recreation loses un-applied edits in fixed readers too.

**Impact:** Rotation/resizing closes the EPUB dialog or resets the fixed-reader draft,
and hidden EPUB controls return. Saving the reading locator alone does not satisfy
AGENTS rule 17 and Phase 1 UI-state continuity.

**Evidence:** Static inspection; the handoff acknowledges the EPUB dialog loss. This
retained behavior was not independently re-tested on a device and is not presented
as a newly introduced EPUB regression.

**Recommended fix:** Preserve application/Compose saved state while selectively
reconstructing Readium's asynchronous navigator. Save draft, initial snapshot and
scope with an appropriate Saver or SavedStateHandle. Test recreation with unsaved
Appearance changes and open dialogs, alongside locator restoration.

### R4 — LOW — ZIP comments can be mistaken for the directory terminator

**Location:** `app/src/main/java/com/d4guilar/shelfos/core/files/SeekableZip.kt:96-117`.

**Issue:** The new parser accepts the last EOCD signature in its search window
without validating that it identifies the actual directory. A legal ZIP comment
containing `PK\u0005\u0006` followed by 18 zero bytes is interpreted as an empty
directory even though the archive contains entries.

**Impact:** An otherwise valid CBZ/EPUB can be rejected or misclassified. This is an
uncommon compatibility defect, not a demonstrated source-modification/security exploit.

**Evidence:** A JDK `ZipOutputStream` fixture with one `page.jpg` entry and that comment
returns an empty `SeekableZip.entries` list; the targeted assertion fails.

**Recommended fix:** Validate EOCD candidates against comment length and directory
location/size/count/record consistency, continuing backward for inconsistent candidates.
Comment-length validation alone is insufficient for this zero-filled false record.
Add commented-archive regression cases alongside ZIP64 coverage.

### R5 — LOW — Permanent validation docs overstate the recorded evidence

**Location:** `docs/VALIDATION.md:5-6,51`; corresponding completion wording near the
top of `docs/PHASE_1_PLAN.md`, the Phase 1 section of `docs/ROADMAP.md`, and README status.

**Issue:** The docs say 1A-1D are implemented while the handoff marks the 1D motion
pass, focus/scroll checks and rapid-navigation evidence partial. VALIDATION claims
the originals' hashes matched without limiting that claim to the three files
actually hashed; the handoff explicitly says the 3.16 GB CBZ was not hashed. The
acknowledged EPUB dialog-state defect is absent from the permanent limitations list.

**Impact:** Permanent evidence requires the temporary handoff to interpret correctly.
The accurate "not accepted" caveat does not repair these specific overstatements.

**Recommended fix:** Name the three hash-verified samples, leave CBZ byte verification
outstanding, describe 1D as partial and preserve concrete software limitations until
fixed. Do not imply that only physical-device validation remains.

## Verdicts

**Phase 1 acceptance: NOT ACCEPTED / CHANGES REQUIRED.** The principal local-library
and reader slice exists, but R1-R3 concern required behavior. Fix the findings and
retain outstanding plan gates; passing compilation/tests do not establish acceptance.

**Architecture: ALIGNED IN STRUCTURE, QUALIFIED BY OWNERSHIP/STATE DEFECTS.** The
single module, repositories/ViewModels, Room, reader adapters, semantic input and
central theme boundaries remain intact. Entry removal preserves external originals
and private copies; explicit unused-copy deletion is separate. R1/R2 require resource
ownership corrections, not a broad rewrite. No new schema/migration was introduced.

**Scope leakage: NONE FOUND IN THE REVIEWED DELTA.** No Series/Omnibus, CBR, Smart
Shelves, full Library Sources/scanner, Adapted PDF, SourceMap, OCR, Calibre/OPDS,
billing or online-reading dependency was added. ComicInfo title/direction evidence
is local metadata, not Series implementation. Shelves is a renamed placeholder;
startup grant maintenance remains a limited first-file concern.

**Dependencies/licensing:** No dependency/version changes in this delta; the new ZIP
reader uses existing platform/JDK facilities. New implementation/test files have
MPL-2.0 headers. No new license regression found. Coordinate coverage does not certify
finished license review: the existing CSV still has three Guava-family `REVIEW`
entries (`docs/DEPENDENCY_LICENSES.csv:112-114`), and bundled notices remain a documented
release prerequisite. This review does not certify the pre-existing graph for distribution.

## Validation performed by Codex

Windows/JDK 17 with `GRADLE_USER_HOME=.tools/gradle-home`,
`ANDROID_HOME=.tools/android-sdk` and `ANDROID_USER_HOME=.tools/android-user`.

| Command/check | Result |
| --- | --- |
| `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --offline --console=plain` | PASS: 86 tasks, 12 executed/74 up-to-date. Working-tree build; not an independently repeated clean export. |
| Existing JVM suite | 44 tests, no failures; test report checked. |
| `gradlew.bat :app:testDebugUnitTest --rerun --offline --console=plain` | PASS: final explicit rerun, 44 tests/0 failures/0 errors, XML verified; excludes review tests. |
| Lint XML | 0 errors; 5 UseKtx warnings and 1 Gradle-version suggestion. |
| Instrumentation APK | Assembled successfully; not executed in this review. |
| `gradlew.bat :app:testDebugUnitTest --tests com.d4guilar.shelfos.ReviewRegressionTest --init-script .tools/codex-review.init.gradle --offline --console=plain` | 3 executed/3 assertion failures reproducing R1, R2 and R4. Isolated review checks, not changes to Claude's suite. |
| `adb.exe devices` | No emulator/device connected. No independent runtime/private-media verification claimed. |
| `git diff --check` | PASS. |

Reproduction sources, init script, logs and failure XML remain in ignored
`.tools/codex-review*` paths; normal builds do not include these tests. Two initial
harness-configuration attempts executed no tests; the final three assertion failures
were verified. No application code was changed to produce them.

Claude's clean-export, 20-test emulator/layout/reduced-motion runs and private-sample
results remain author-reported evidence. Outstanding gates include API 24/37,
physical devices/controller, TalkBack/large text, real provider-copy/non-seekable and
interrupted/low-storage cases, transaction rollback, rapid switching, full 1D
focus/scroll/motion evidence and frame-time profiling.

## Handoff disposition

Keep `PHASE1_CLAUDE_HANDOFF.md` and this review for fixes and focused re-review.
No application fixes, dependency changes, commits or pushes were made. Only this
review and ignored reproduction artifacts were written. Phase 2 was not started.

## Claude remediation notes

Appended by Claude on 2026-09-24. These are claimed resolutions for Codex to verify, not
a verdict. Codex's findings, severities and verdicts above are unchanged. Details, files
and evidence are in `PHASE1_CLAUDE_HANDOFF.md` under "Codex Review Remediation".

- **R1 (claimed resolved).** Ownership moves one way: preparing job, review, save, library.
  The save takes the preparation before any suspension point, so clearing the ViewModel
  cleans only abandoned work. The save settles success, duplicate conflict and failure;
  a failure returns the work to the review, or abandons it if the review is gone.
  Cleanup never discards work the library references.
- **R2 (claimed resolved).** A process-wide `ImportLeases` records the sources that
  unfinished imports hold. A grant is released only when no library item or other import
  depends on it; otherwise it passes to the remaining import, and the last holder releases
  it. New imports wait for any release already in progress, and a replacement waits for a
  cancelled predecessor that is still running.
- **R3 (claimed resolved).** `EpubActivity` restores saved state. Readium's navigator comes
  back as its documented placeholder, which is removed immediately, and is still rebuilt
  from the locator. The Appearance draft, snapshot and scope use `rememberSaveable`.
- **R4 (claimed resolved).** EOCD candidates are validated against their comment extent,
  their ZIP64 locator/record, the directory's end position and the entry count, and the
  search continues backwards.
- **R5 (claimed resolved).** VALIDATION, the plan, the roadmap and the README now say that
  1D is partial and name the three hashed samples; the CBZ is unhashed. Evidence is split
  into static/build, unit/regression, emulator and physical-device categories, and open
  software gaps are listed.
- **Regression tests.** Codex's three tests are unmodified (same SHA-256). They failed 3 of 3
  before the fixes and pass 3 of 3 after. Verbatim copies run in the normal suite.
- **Validation.**
  - JVM: 60 passed, 0 failed.
  - Lint: 0 errors, with the same 5 `UseKtx` warnings and Gradle hint.
  - `assembleDebug` and `assembleDebugAndroidTest` pass.
  - API 35 emulator `connectedDebugAndroidTest`: 22 of 22, including two new recreation
    tests, one with a real rotation.
  - Physical device: not verified.

# Final Verification

Focused verification performed by Codex on 2026-09-24. This pass rechecked only R1-R5
against the remediation delta and the remediation claims in
`PHASE1_CLAUDE_HANDOFF.md`. It did not perform a new repository audit or implement fixes.

## Finding status

### R1 — RESOLVED

`ImportViewModel` now transfers a prepared import out of review ownership before the
save coroutine can suspend. Save completion handles success, duplicate conflict and
failure; failure restores the review while its ViewModel remains active and abandons it
after the review is gone. Cleanup checks the committed library state before discarding a
managed copy, including the case where the database row was written before an exception.
Focused tests cover clear-during-commit, retry, cleared failure and failure-after-row.

### R2 — RESOLVED

`ImportLeases` is process-wide through `AppContainer` and tracks overlapping imports by
source URI. It transfers or releases a persisted grant only after both library references
and active holders are considered, waits for an in-progress release, and releases after
the last abandoned holder. The focused lease and ViewModel tests cover overlapping use,
replacement of the same URI, deferred release and eventual release; the fix does not
replace the original leak with an unconditional grant leak.

### R3 — RESOLVED

`EpubActivity` now passes the real saved state to `super.onCreate`. Readium restoration
uses its documented dummy navigator factory, removes that placeholder, and rebuilds the
real navigator from ShelfOS reader state. The Appearance draft, baseline and scope use
saveable state. JVM saver coverage passes, and the checked-in instrumentation tests cover
dialog recreation and a real activity rotation. The existing API 35 result artifact
records 22 tests with 0 failures and 0 errors; this focused pass could not independently
rerun it because no emulator or device was connected.

### R4 — RESOLVED

`SeekableZip` now treats each EOCD signature as a candidate and validates its comment
extent, ZIP64 records, central-directory position/bounds and entry consistency before
accepting it. Invalid candidates are skipped while the backward search continues. The
original false-EOCD regression and the additional ZIP64-comment case pass in the normal
unit suite.

### R5 — RESOLVED

Permanent status documentation now distinguishes static/build, JVM, emulator and
physical-device evidence; describes 1D as partial; names the three hash-verified samples;
leaves the large CBZ unverified; and retains the open software and device gates. README,
the Phase 1 plan, roadmap, architecture and validation record no longer present Phase 1
as accepted. The unrelated Generic ZIP documentation does not interfere with these
Phase 1 implementation or status claims.

## Validation

Environment: Windows/JDK 17 using the repository-local Gradle and Android SDK state.

| Command/check | Result |
| --- | --- |
| `gradlew.bat :app:testDebugUnitTest --tests com.d4guilar.shelfos.ReviewRegressionTest --init-script .tools/codex-review.init.gradle --rerun --offline --console=plain` | PASS: the same 3 focused reproductions that previously failed now pass. The reproduction source SHA-256 is `6E70FE1FCC28647BD5B9F7665FA50C0EE90F1FB46A3872786D152179854A6281`. |
| `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun --offline --console=plain` | PASS. JVM XML: 60 tests, 0 failures, 0 errors. Lint: 0 errors, 5 `UseKtx` warnings and 1 Android Gradle Plugin version suggestion. Both debug APK assemblies pass. |
| Existing connected-test result artifact | API 35 emulator artifact dated 2026-09-24: 22 tests, 0 failures, 0 errors, including `AppearanceRestorationTest` and `EpubRecreationTest`. Inspected as existing evidence; not generated by this focused pass. |
| `adb.exe devices` | No connected emulator or device. Instrumented tests were not independently rerun. |

## Final verdicts

**REMEDIATION: PASS.** R1-R5 are resolved and no regression was introduced by their
fixes.

**Architecture: PASS FOR THE REMEDIATION.** Explicit import ownership, process-wide
source leases, saved-state restoration, bounded ZIP parsing and accurate status records
fit the existing repository/ViewModel/reader boundaries. Source files remain distinct
from LibraryItems and are not deleted by ordinary library removal.

**Scope leakage: NONE FOUND IN THE REMEDIATION.** The fixes do not implement Series or
Omnibus, CBR, Smart Shelves, full Library Sources, Adapted PDF, SourceMap, OCR,
Calibre/OPDS, billing or an online reading dependency. Generic ZIP documentation is
unrelated and caused no interference.

**Physical device: NOT VERIFIED.** No physical device was connected for this pass.

**PHASE 1: NOT YET ACCEPTED.** Remaining acceptance blockers are the partial 1D motion
and focus/scroll/rapid-navigation work and evidence; reader zoom/fit and global
Appearance device exercise; API 24/37, TalkBack/large-text, physical-controller and
physical-device coverage; real provider/non-seekable, interrupted/low-storage and Room
rollback/grant-revocation cases; and frame-time profiling. These are acceptance gates,
not reopened R1-R5 findings.

## Phase 1 Acceptance Closure Note (Claude, 2026-09-24)

Appended after Codex's Final Verification above, which is unchanged. This records what the
subsequent acceptance-closure pass resolved against the blocker list in Codex's verdict
immediately above. Full detail is in `docs/VALIDATION.md` ("Phase 1 acceptance closure" and
its "— continuation") and `PHASE1_CLAUDE_HANDOFF.md`. Claimed resolutions only; Codex's
verdict and severities above are unchanged, and Codex still owns Phase 1's final acceptance
call.

- **1D scroll/rapid-navigation:** now has automated coverage (`NavigationSmokeTest`) and
  passes on the Retroid Pocket 5 and an API 24 emulator.
- **Reader zoom/fit and global Appearance device exercise:** now has automated coverage and
  passes on the same two targets.
- **Real provider/non-seekable, interrupted/low-storage, Room rollback:** now has automated
  coverage (`LibraryPersistenceTest`, a debug-only test `ContentProvider`) and passes on the
  same two targets.
- **API 24:** run on a physical-SDK emulator (`shelfos-api24`). The app and the full UI
  smoke suite pass. Found and diagnosed an intermittent native crash in the platform's
  bundled `libpdfium.so` specific to that emulator image under a long test sequence; not
  reproduced on the physical device; treated as an environment finding, not a confirmed
  ShelfOS defect, and not asserted as fixed.
- **API 37:** run on a Google Play emulator (`shelfos-api37`, Android 17). The app launches
  and renders correctly; non-UI instrumented tests pass. Automated UI-instrumented tests
  cannot run against this platform version with the project's current pinned
  androidx.test/Espresso version (a removed `InputManager` accessor) — a test-tooling
  version gap, not a missing system image or an app defect.
- **Physical-controller coverage:** the device owner tested the Retroid Pocket 5's actual
  physical gamepad directly (not simulated input) and reported no issues across Library and
  Reader navigation, activation, reader controls and Back. Recorded as owner-verified,
  genuine hardware evidence.
- **TalkBack:** attempted on the `shelfos-api37` emulator, where TalkBack is preinstalled
  and was confirmed enabled and running; ADB-injected gestures did not reliably drive its
  touch-exploration/announcement behavior, so this remains **NOT VERIFIED** — a person
  operating TalkBack is still needed.
- **Frame-time/heap under load:** measured on the Retroid Pocket 5 with a generated,
  non-copyrighted 219 MB/160-page synthetic archive (not the original 3.16 GB private
  sample, which was not repeated on physical hardware). Memory stayed roughly flat; frame
  timing was smooth. No documented Phase 1 threshold exists to grade against.
- **Not touched:** R1–R5, Phase 2, Public Demo Readiness, Home Mode, and the unrelated
  Generic ZIP documentation.

**Remaining, unresolved:** TalkBack spoken/gesture confirmation (needs a person); the API 24
emulator-specific native-crash finding (unconfirmed on real API 24 hardware, none available);
API 37 automated UI-test coverage (tooling gap); the documented cover-expansion transition
(deliberately not implemented, explained in `PHASE1_CLAUDE_HANDOFF.md`); frame-time/heap
under a load closer to the original 3.16 GB sample. None of these are R1–R5 findings.

## Final Phase 1 acceptance closure (Codex, 2026-09-24)

The later acceptance work recorded in `docs/VALIDATION.md` and
`PHASE1_CLAUDE_HANDOFF.md` closes the historical blocker list above:

- R1–R5 remain **RESOLVED** and their focused regressions pass.
- The cover-expansion transition, reduced-motion behavior, scroll/rapid switching,
  reader zoom/fit, global Appearance persistence, resilience cases and representative
  large-load exercise are complete and recorded.
- TalkBack manual spoken/gesture validation is owner-verified **PASS**.
- Retroid Pocket 5 physical controller validation is owner-verified **PASS**.
- API 24 application behavior passes with an accurately documented emulator-specific
  native crash; API 37 application behavior passes with an accurately documented
  AndroidX test-tooling limitation. These are non-blocking environment limitations,
  not unresolved ShelfOS defects.

**FINAL PHASE 1 VERDICT: ACCEPTED.** No mandatory Phase 1 blocker remains. This verdict
does not begin Phase 2 or Public Demo Readiness.
