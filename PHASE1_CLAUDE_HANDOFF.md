# Phase 1 Claude Handoff

Temporary review handoff for Codex. Delete after the review is complete.

## Baseline

- Starting commit: `f252e8bfbc7ef128eb08d4f117fa82cdc757f258` ("chore: checkpoint before Phase 1 completition")
- Current branch: `feat/phase-0-foundation` (name predates Phase 1; the work is Phase 1)
- Nothing was committed or pushed. All changes are in the working tree.

## Scope implemented

Completion work for the expanded Phase 1 (increments 1A–1D) only:

- **Import safety.** Reference imports now work for EPUB and CBZ from shared storage. The baseline reopened provider descriptors through `/proc/self/fd`, which Android denies for shared storage. The new `SeekableZip` reads archives through the granted descriptor using positional reads. Errors are typed. PDFs are checked with PdfRenderer during import. EPUB OPF and ComicInfo metadata are read. Startup maintenance cleans up after interrupted imports.
- **Non-destructive removal.** Removing an entry no longer deletes its private copy. Settings → Storage deletes unreferenced copies only after the user confirms.
- **Reader lifecycle.** Fixed the EPUB crash (preferences submitted before the Readium fragment was attached). Reading positions persist through a conflated application-scope writer. Readers observe a single item. Sources that disappear are marked unavailable. Both readers share one rule: Back, Escape and B hide visible controls, then leave. Keyboard and D-pad work as soon as a reader opens. The page controls are mirrored in RTL.
- **Preferences.** Appearance changes record only the fields the user changed. Direction is always per-title. The dialog offers only the controls the publication supports.
- **UI.** Collections → Shelves rename. Compact details keep Read above the fold, which caused both baseline reader test failures. Unavailable states are shown. Keyboard focus is restored when returning to the Library. Stale Settings text ("sample publications… coming later") was replaced. Selected chips now show a check mark. Fixed the RTL "193 / 3" counter.
- **Tests and docs.** New tests and a status-only documentation update.

Not started: multi-file or folder import, ImportSession or LibrarySource, Series, Shelves functionality, CBR, Adapted PDF, OCR, metadata providers, billing, premium themes.

## Files changed

Paths are relative to `app/src/main/java/com/d4guilar/shelfos/` unless noted. (new) = untracked file.

- **Domain/data:** `domain/library/PublicationProblem.kt` (new), `domain/importing/ImportPolicy.kt` (new), `domain/library/LibraryItem.kt`, `data/library/RoomLibraryRepository.kt`
- **UI:** `feature/home/ShelfApp.kt`, `feature/library/LibraryScreen.kt`, `feature/library/PublicationDetails.kt`, `feature/settings/SettingsScreen.kt`, `feature/settings/SettingsViewModel.kt`, `core/designsystem/ShelfComponents.kt`, `MainActivity.kt`
- **Reader:** `core/reader/{FixedReader,EpubReader,EpubSurface,ReaderPreferences}.kt`, `feature/reader/{EpubActivity,EpubReaderViewModel,FixedReaderScreen,FixedReaderViewModel,ReaderAppearance}.kt`, `feature/reader/ReaderPersistence.kt` (new), `core/input/{ShelfCommand,AndroidInput}.kt`
- **Import:** `core/files/PublicationFiles.kt`, `core/files/ArchivePolicy.kt`, `core/files/SeekableZip.kt` (new), `core/files/EmbeddedMetadata.kt` (new), `feature/importing/{ImportViewModel,ImportDialogs}.kt`, `ShelfApplication.kt` (startup maintenance)
- **Persistence:** `core/database/LibraryDao.kt`. Queries only. Entities are unchanged, so there is no schema version bump or migration, and the schema JSON is identical.
- **Tests:** `app/src/test/.../{ImportPolicyTest,ImportViewModelTest,SeekableZipTest}.kt` (new), `ReadingPolicyTest.kt`, `TestLibrary.kt`; `app/src/androidTest/.../ReaderStateTest.kt` (new), `LibraryPersistenceTest.kt`, `NavigationSmokeTest.kt`
- **Docs:** `README.md`, `docs/{ROADMAP,PHASE_1_PLAN,ARCHITECTURE,VALIDATION,DEPENDENCIES,CHANGELOG_DOCS}.md`, `docs/features/SHELVES.md`, `docs/design/INPUT_SYSTEM.md`
- **Build/dependencies:** none. Gradle files, the version catalog, the manifest and `DEPENDENCY_LICENSES.csv` are unchanged.

## Important implementation decisions

1. **Own ZIP reader instead of `java.util.zip.ZipFile`.** Shared-storage descriptors cannot be reopened by path, so `ZipFile` cannot read them. I rejected two alternatives:
   - Readium's container API would couple the CBZ engine to Readium internals and to its async/`Try` API.
   - commons-compress needs `java.nio.file` (API 26+) and adds new dependencies.

   `SeekableZip` supports STORED and DEFLATE entries and ZIP64 directories. It refuses encrypted entries. `readBytes` bounds the actual inflated output. Archives are never extracted.
2. **Removal keeps private copies.** "Removing an entry must not delete … managed publications. Cache cleanup is separate" is implemented as removal releasing the grant only, plus an explicit, confirmed "Delete unused private copies" in Settings. Copies are referenced by file name. Legacy absolute paths resolve by their last segment, so no migration is needed.
3. **Startup maintenance before imports.** `AppContainer.sourceMaintenance` does three things:
   - deletes `*.part` files;
   - releases read grants that no item references;
   - marks items without a grant as unavailable.

   `ImportViewModel` awaits it, so it can never release an in-flight import's grant. The planning step is a pure function (`planSourceMaintenance`) so device tests never touch real grants.
4. **Error model.** `PublicationProblem` covers permission loss, unavailable source, unsupported format/layout, protected, corrupt, empty archive, too large, storage and copy failure. Only the first two mark an item unavailable. A `SecurityException` while ShelfOS still holds a grant maps to SOURCE_UNAVAILABLE. Providers may revoke the grant when a document is deleted, so the permission message does not over-claim a cause.
5. **Preference layers.** `appearanceUpdate`:
   - a title save writes only the changed fields;
   - a global save writes the changed fields as defaults and clears this title's conflicting overrides, so the change is visible;
   - direction is never global. Any global direction is ignored when resolving.
6. **Back rule.** Readers open with controls visible, per the documented "Back dismisses controls, then exits". System Back, Escape and B are consistent. Before this change only gamepad B hid controls.
7. **EPUB keys.** The EPUB reader keeps key interception in `Activity.dispatchKeyEvent`, because Readium's `KeyEvent` has no gamepad buttons. The mapping goes through `InputMapper.readerCommand` with a "controls focused" guard. Edge taps use Readium's `DirectionalNavigationAdapter`, which honors reading progression. Center taps toggle controls.
8. **Direct EPUB launch.** EPUB now opens `EpubActivity` directly. The baseline pushed and popped a `reader/{id}` route, which flickered the chrome. Fixed-layout readers stay in the NavHost.

## Phase 1 acceptance checklist

| Requirement | Status | Evidence |
| --- | --- | --- |
| 1A Room migration from v1 retaining theme | PASS | Device test `migrationPreservesAppearanceAndLibrarySurvivesReopen` |
| 1A SAF import with persisted grants | PASS | Real DocumentsUI imports of 5 files on the emulator; reopened by grant after force-stop and process death |
| 1A Preview and title/creator/category correction | PASS | Review dialog on device; `ImportViewModelTest` provenance and category cases |
| 1A Real Library, Search, Favorites | PASS | Device navigation/search tests; favorites persisted in the migration test; `FoundationTest` filters |
| 1A Recently-added ordering | PASS | DAO `ORDER BY addedAt DESC`; observed Frankenstein listed before the older Dune on device (manual only) |
| 1A Remove (non-destructive) | PASS | Device test `removalKeepsPrivateCopies…`; SHA-256 of originals unchanged after removal |
| 1A Unavailable-source state | PASS | A source deleted outside ShelfOS kept its entry and showed the unavailable notice; device test `missingSources…` |
| 1A Failed or cancelled import leaves no committed row | PASS | Unit cancellation and error tests (nothing added); device test for rejected imports leaves no partial copy |
| 1B Reader boundary and PDF/CBZ adapters | PASS | `FixedReader` behind the ShelfOS interface; private PDFs and the 3.16 GB CBZ read on the emulator |
| 1B Fit, zoom and page controls | PARTIAL | Buttons, slider and keys verified; Fit width, pinch and double-tap zoom were not exercised this pass |
| 1B RTL/LTR default and override | PASS | Manga PDF: mirrored controls, left-edge tap and swipe right advance, override and reset work; device RTL key test |
| 1B Position persistence and Continue Reading | PASS | Resume after force-stop and process death for PDF (page 5), CBZ (772 and 1481) and EPUB (same passage) |
| 1B Direction across touch, keyboard and D-pad | PARTIAL | Touch and injected keys (arrows, Page Down, L1) verified; no physical keyboard or controller |
| 1C EPUB reflow, chapters, presets, font/spacing | PASS | Device EPUB test (Clean preset, chapter jump); manual Spacious preset and chapter navigation |
| 1C Per-title and global preference precedence | PARTIAL | Layer math unit-tested; per-title UI verified on device; the global-save UI path was not exercised on device |
| 1C Theme or size change, restart, same passage | PASS | Dark theme kept the explicit Spacious typography; same passage after force-stop, rotation and process death |
| 1D Library/detail/reader transitions | PARTIAL | Existing 120 ms token fades only; no cover-expansion transition or ES-DE-informed motion pass |
| 1D Focus and scroll restoration | PARTIAL | Keyboard focus restoration device test passes; grid scroll restoration relies on saveable state and was not tested |
| 1D Reduced motion | PASS | Full device suite passes with all animation scales at 0 |
| 1D Adaptive QA | PARTIAL | Suite passes at 1080×1920 and 1600×1000; rotation checked; no fold postures or physical devices |
| 1D Rapid navigation without stale state | PARTIAL | Per-title ViewModels own sessions; multi-title flows pass; no dedicated rapid-switch stress test |
| 1D Physical-device frame and heap measurements | NOT TESTED | Emulator heap only (flat, about 209–216 MB PSS over 121 CBZ pages) |
| §7 Unit coverage (detection, direction, overrides, ordering, duplicates, capabilities, precedence, cancellation) | PASS | 44 JVM tests; locator serialization runs on device (`ReaderStateTest`) because of the org.json stub |
| §7 Room (upgrade, persistence, deletion preserving others) | PASS | Migration and removal device tests; the insert conflict is idempotent |
| §7 Room transaction rollback | NOT TESTED | No test forces a failure mid-transaction |
| §7 Instrumented real picker and revoked access | PARTIAL | Real picker exercised manually only; true revocation (as opposed to deletion) not tested |
| §7 Large text, TalkBack | NOT TESTED | Not run |
| §7 Private acceptance: Dune Book/PDF, Chainsaw Man Manga/PDF RTL, large CBZ LTR | PASS | See VALIDATION.md; CBZ early, middle and final pages checked |
| §7 Early/middle/final pages for both PDFs | PARTIAL | Only early pages were checked for Dune and Chainsaw Man |
| §7 Source bytes unchanged | PARTIAL | SHA-256 identical for Dune, Chainsaw Man and Frankenstein; the 3.16 GB CBZ was not hashed |
| §7 Reliability: malformed archives, rotation, process death, bounded heap | PASS | Unit and device tests; manual rotation and PID-verified process death; flat heap |
| §7 Reliability: low storage, non-seekable provider, real interrupted copy | NOT TESTED | The copy path is covered only by `file://` fixtures and unit cancellation tests |
| §7 Compatibility: API 24, API 37, physical device, controller | NOT TESTED | Only the API 35 emulator was available |
| §7 CI/build tasks from a clean checkout | PASS | Clean export: 86 of 86 tasks executed, lint 0 errors |
| §7 Schemas, licenses, ignored private data, permissions | PASS | Schemas identical; inventory covered; no private media exported; APK has no network or storage permissions |
| Docs describe shipped behavior | PASS | Status-only updates listed above; links checked |

## Validation performed

Environment: Windows 11, JDK 17, project-local SDK (`.tools/android-sdk`), `GRADLE_USER_HOME=.tools/gradle-home`, `ANDROID_USER_HOME=.tools/android-user`. Use the `.tools` keystore; the emulator's installed APK was signed with it.

- Clean export (`git ls-files -co --exclude-standard`, no `local.properties`, `ANDROID_HOME` set): `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --offline --no-build-cache` → **PASS** (86 of 86 tasks executed)
- `./gradlew :app:testDebugUnitTest` → **PASS** (44 tests)
- `./gradlew :app:lintDebug` → **PASS** (0 errors; 5 `UseKtx` suggestions, plus the Gradle 9.7.1 hint in the working tree)
- `./gradlew :app:connectedDebugAndroidTest` → **PASS**, 20 of 20 in each run: phone, expanded (`wm size 1600x1000`, `wm density 160`), and phone with animation scales at 0. Final phone run after the last code change: 20 of 20.
- `./gradlew :app:dependencies --configuration debugRuntimeClasspath`, compared with the CSV → no uncovered artifacts
- `aapt dump permissions app-debug.apk` → only `WAKE_LOCK` (from `media3-exoplayer`) and the dynamic-receiver permission
- `git diff --check` → clean

Baseline before any change: build, unit tests and lint passed; device tests were 8 of 10 (both reader tests failed).

## Device/emulator validation

This was actually tested on the AOSP API 35 x86_64 emulator (`shelfos-phase0` AVD) only. See `docs/VALIDATION.md` for the private-sample matrix, covering real-picker import, reading, resume, RTL, the Dark theme, rotation, process death, the unavailable source, removal and memory.

No physical phone, tablet or foldable, physical keyboard or controller, API 24 or 37 runtime, TalkBack or low-storage testing was performed.

## Known issues / deferred work

### Phase 1 defects and gaps

- Physical-device, API 24/37, TalkBack, controller, low-storage and frame-profiling gates are open, so Phase 1 is not accepted.
- Private copies live in app storage. The accepted contract prefers a user-visible managed Source, which is Source-foundation work.
- One page bitmap at a time and no prefetch. CBZ decode uses power-of-two sampling to 2048 px or less, which can soften very large pages on dense screens.
- The EPUB reader's Appearance and Chapters dialog state is lost on rotation. `super.onCreate(null)` intentionally discards fragment state; the reading position survives.
- The private-copy flow was not validated against a real provider that refuses persistable grants.
- Some providers revoke grants for deleted documents, so a deletion may be reported as lost access.
- `connectedDebugAndroidTest` can exit 0 when APK installation fails. Check the XML results.
- In Git Bash, `MSYS_NO_PATHCONV=1` breaks the Gradle wrapper. This is local tooling only.
- The five `UseKtx` lint suggestions would need a direct `core-ktx` declaration and were left as they are.
- In-app third-party notices are missing. This is a release prerequisite.

### Intentionally later-roadmap work

Multi-file and folder import, ImportSession, LibrarySource and rescans, Series and omnibus, Manual and Smart Shelves, CBR, Adapted PDF and SourceMap, OCR, metadata providers, bookmarks, in-reader search, thumbnails, spreads and foldable pairing, billing, and premium themes.

## Risk areas for Codex

1. `core/files/SeekableZip.kt`, a security-sensitive parser:
   - EOCD and ZIP64 locator/record handling;
   - ZIP64 extra-field order;
   - the `EntryInflaterStream` dummy byte and `inf.end()`;
   - the `readBytes` limit;
   - negative and oversized counts.
2. Startup maintenance: `planSourceMaintenance` releases every read grant that no item references. Confirm the "imports await `sourceMaintenance`" gate is sufficient, and that future grant owners (folder Sources) must update this function.
3. `PositionWriter`: a conflated channel consumed in the application scope. Check close and drain semantics, and the `onFailure` state update from an IO thread.
4. `EpubActivity`:
   - `dispatchKeyEvent` interception;
   - the `controls && (topFocused || bottomFocused)` guard against stale focus flags;
   - Readium guards (`view != null`, `lifecycle.withStarted`).
5. `appearanceUpdate` semantics. A global save clears the current title's conflicting overrides; confirm this matches the intended precedence.
6. Removal and cleanup: `deleteUnusedPrivateCopies` treats any unreferenced file in `filesDir/publications` as unused. A completed but unconfirmed import copy is safe only because the UI makes import and Settings mutually exclusive.
7. `PublicationFiles.managedFile` accepts legacy absolute paths by trusting only the last segment.
8. `LibraryScreen` focus restoration uses a flag in the Library back-stack entry's `SavedStateHandle`.
9. The RTL `LayoutDirection` scope for the fixed-reader bottom bar (slider semantics) and the LTR text direction override.
10. `WAKE_LOCK` from `media3-exoplayer` is merged into the manifest. Decide whether to remove it with `tools:node="remove"`.

## Git diff scope

Compare against `f252e8bfbc7ef128eb08d4f117fa82cdc757f258`:

- `git diff f252e8b`: 39 tracked files, +1518/−549
- plus the untracked, non-ignored files from `git ls-files --others --exclude-standard`: 9 source/test files (802 lines) and this handoff

Private samples (`Example Book files/`), `.tools/`, `local.properties` and build outputs remain ignored.

# Codex Review Remediation

Date: 2026-09-24. Scope: the five findings in `PHASE1_CODEX_REVIEW.md` only. No Phase 2, Public Demo Readiness, Home Mode or other roadmap work. Nothing was committed. Everything above this section is the original Phase 1 handoff, unchanged.

**Regression-test policy.** Codex's three reproduction tests were correct and were not modified.
- `.tools/codex-review-tests/ReviewRegressionTest.kt` is byte-identical to Codex's version (SHA-256 `6e70fe1f…54a6281`), as is `.tools/codex-review.init.gradle`.
- Verbatim copies of the three tests now run in the normal suite: two in `ImportViewModelTest`, one in `SeekableZipTest`. They keep their method names in different classes, so Codex's init-script run still compiles.
- Sequence: red (3 of 3 failed, reproduced here before any change) → fix → green (3 of 3 pass).

## Finding
R1 — Clearing the import ViewModel can discard a source being committed

Severity: HIGH

Status: RESOLVED

Root cause:
- `confirm()` kept the preparation in `pending` while `repository.add()` ran inside `NonCancellable`. `onCleared()` treated anything in `pending` as abandoned, so clearing the ViewModel mid-save cleaned up work being committed.
- Cleanup also deleted the private copy unconditionally, so even cleanup after a successful insert removed a copy the library referenced.
- Nothing marked the transition from temporary import artifact to committed library artifact.

Fix: a preparation has exactly one owner at a time, and each owner settles it when it ends.
- **Preparing job.** Owns its hold and anything it prepares until it hands the preparation to the review. It abandons both on failure or cancellation.
- **Review (`pending`).** Abandoned by dismissing, replacing or clearing.
- **Save.** Its first statement, before any suspension point, takes the preparation out of `pending`. From then on only the save settles it, inside `NonCancellable`:
  - success hands ownership to the library item;
  - a duplicate conflict discards only this preparation's own private copy (the winning item owns the grant);
  - failure returns the preparation to the review for retry, or abandons it if the ViewModel has been cleared.
  If the review is dismissed or cleared before the save starts, the save never runs and the review abandons the preparation as before.
- **Cleanup guard.** Cleanup checks the library first. Work the library references is committed (`isCommitted`: same id or same private-copy name) and is never discarded, even when a save reports failure after writing its row.
- No delays or ordering assumptions. The only flag, `reviewing`, records whether the preparing job has handed its ownership to the review.

Files: `feature/importing/ImportViewModel.kt`, `domain/importing/ImportPolicy.kt` (`isCommitted`, importer contract KDoc).

Regression coverage (`ImportViewModelTest`):
- `clearingViewModelDuringCommitMustNotDiscardCommittedSource`: Codex's test, verbatim.
- `aFailedSaveReturnsThePreparationToTheReviewForRetry`: retry works and nothing is discarded.
- `aSaveThatFailsAfterItsReviewIsGoneDiscardsThePreparation`: the ViewModel is cleared during the save, then the save fails. Nothing is discarded while the save owns the work; afterwards it is discarded exactly once and its grant released.
- `workTheLibraryAlreadyHoldsIsNeverDiscarded`: the row is written, the save reports failure, the review is dismissed. The referenced private copy is untouched.

Validation:
- Codex's test: red → green.
- Against the pre-remediation `ImportViewModel`, the new tests fail, except the retry test, which guards the new design's retry path.
- Full JVM suite green (see Validation below).

Future follow-up — NOT PART OF PHASE 1: a preparation that completes at the moment its import is cancelled is lost when `withContext` resumes. Its grant is released at the next launch by startup maintenance, and its private copy is listed under Settings → Storage as an unused copy for explicit cleanup. This pre-dates the review and is recorded in VALIDATION's open software gaps. A durable ImportSession/staging record should hand results over explicitly.

## Finding
R2 — Abandoned-import cleanup can revoke the next import's grant

Severity: MEDIUM

Status: RESOLVED

Root cause: cleanup released a grant after checking only committed items. A replacement import of the same URI could start in the meantime, find the old persisted grant, acquire none of its own, and then lose the grant it depended on. Nothing tracked grants needed by active operations.

Fix: active-operation tracking with explicit grant hand-over. Awaiting cleanup was not an option, because Codex's test requires the replacement to reach review while cleanup is pending.
- **One process-wide tracker.** `domain/importing/ImportLeases`, held by `AppContainer.importLeases`. Grants are process-wide, and cleanup outlives the screen that started it.
- **Holds.** An import holds its source from its start until it is committed or abandoned.
- **Release rule.** Abandoned work releases its grant only when no library item references the source and no other import holds it.
- **Hand-over.** Otherwise the grant passes to the imports still holding the source, and the last to end without committing releases it. Nothing leaks, and nothing is released while something depends on it.
- **Release in flight.** While a decided release is in progress, a new import of that source waits (`awaitRelease`) before looking for a grant. This closes the check-then-release window.
- **Cancelled predecessor.** A replacement preparation first joins a cancelled predecessor that is still running. `PublicationFiles.prepare` releases what it acquired on failure, so the replacement can no longer see a grant about to disappear.
- **Durable owners.** Release still depends on a Library-level dependency: library items remain the durable owners. Future durable owners (Library Sources) join the `referenced` check instead of releasing grants themselves; `ARCHITECTURE.md` records this.
- **Other sources are unaffected.** The existing replace-a-review test still releases the first source's grant.

Files: `domain/importing/ImportLeases.kt` (new), `feature/importing/ImportViewModel.kt`, `ShelfApplication.kt`, `feature/home/ShelfApp.kt`.

Regression coverage:
- `ImportViewModelTest`:
  - `cancellingThenReimportingSameSourceMustRetainItsGrant`: Codex's test, verbatim.
  - `replacingAReviewWithTheSameSourceKeepsTheGrantTheReplacementNeeds`: Codex's other named path, review replacement with delayed cleanup.
  - `aGrantKeptForAReplacementIsReleasedWhenTheReplacementIsAbandonedToo`: no leak.
  - `aCancelledPreparationFinishesReleasingBeforeTheSameSourceIsPreparedAgain`.
- `ImportLeasesTest` (5): release only when nothing depends on the source; hand-over to the last holder; an import that ends without work releases the grant it inherited; commit hands the grant to the library; imports started during a release wait for it.

Validation:
- Codex's test: red → green.
- The three new ViewModel tests fail against the pre-remediation `ImportViewModel`.
- Idempotent insertion is unchanged.

Future follow-up — NOT PART OF PHASE 1:
- `PublicationFiles.prepare` still releases on failure without consulting `ImportLeases`. Within one import screen the join serializes this; two screen instances preparing one source at the same instant is theoretical. Library Source / ImportSession work should move all import grant acquisition and release behind one owner.
- Leases are in memory. After process death, startup maintenance remains the recovery path, and `planSourceMaintenance` must learn about Source grants.

## Finding
R3 — Reader UI state and Appearance drafts are lost on recreation

Severity: MEDIUM

Status: RESOLVED

Root cause:
- `EpubActivity` called `super.onCreate(null)` to avoid restoring Readium's navigator fragment, which cannot be recreated by class name. That also dropped the saved-state registry, so every `rememberSaveable` in the EPUB reader (controls, Appearance, Chapters) reset.
- `ReaderAppearance` kept its draft, its initial comparison snapshot and its "all titles" scope in plain `remember`. Recreation lost unapplied edits in the fixed-layout readers too.

Fix, by state lifetime:
- **Durable preferences:** unchanged, stored per title and globally in the library.
- **In-progress draft:** `rememberSaveable` with a Saver of plain values (`ReaderPreferencesSaver`) for the draft, snapshot and scope. This is the smallest mechanism: it lives and dies with the dialog, and adds no second source of truth.
- **Ephemeral state:** focus flags stay in `remember`.
- **EPUB activity:** calls `super.onCreate(savedInstanceState)`, with Readium's navigator handled around it:
  - before it, `restoreEpubNavigatorAsPlaceholder()` sets Readium's documented `EpubNavigatorFragment.createDummyFactory()`;
  - after it, `removeRestoredEpubNavigator()` removes the restored placeholder before it needs its container view;
  - the navigator is still rebuilt from the persisted locator, as before.
  Both helpers live in `core.reader.EpubSurface`, so the feature layer does not touch Readium (AGENTS rule 13).
- **Test tag:** the scope checkbox gained `testTag("appearance_scope")` for tests.

Files: `feature/reader/EpubActivity.kt`, `feature/reader/ReaderAppearance.kt`, `core/reader/EpubSurface.kt`.

Regression coverage:
- JVM: `ReadingPolicyTest.unappliedAppearanceDraftsSaveAsPlainValues` (Saver round-trip; unknown names restore as unset).
- Instrumented `AppearanceRestorationTest` (fixed-layout capabilities, `StateRestorationTester`): the draft, the opening baseline and the scope survive restoration even when committed values change meanwhile, and Apply receives exactly the right before, after and scope.
- Instrumented `EpubRecreationTest`:
  - after activity recreation, the Appearance dialog reopens with its unapplied "Paper" change, and Apply stores it;
  - hidden controls stay hidden through a real system rotation (`requestedOrientation = LANDSCAPE`; a new activity instance is asserted);
  - reopening the reader shows the applied appearance reloaded from the library.

Validation (emulator):
- Both instrumented tests pass on the API 35 emulator.
- Against the pre-remediation `EpubActivity`/`ReaderAppearance`, both fail on the same emulator: the draft is lost (`AppearanceRestorationTest.kt:40`), and the dialog is not restored (`EpubRecreationTest.kt:45`).
- Test fix during this pass: the first `EpubRecreationTest` run failed because the test injected Back while the just-dismissed dialog still held window focus. A temporary probe (since deleted) showed that Back hides EPUB controls on a fresh reader, after a dialog closes and after recreation. The test now waits for the reader window's focus. Production code was not changed for this.

Future follow-up — NOT PART OF PHASE 1: EPUB UI state after process death was not exercised separately. It uses the same FragmentManager placeholder and saved-state path as recreation and rotation, and Phase 1 verified position restore after process death.

## Finding
R4 — ZIP comments can be mistaken for the directory terminator

Severity: LOW

Status: RESOLVED

Root cause: the parser accepted the last end-of-central-directory signature in the tail without validating it. A legal comment containing a zero-filled end record read as an empty directory, and comment-length checks alone cannot reject that record.

Fix: candidates are tried from the end backwards, and one is used only when it is consistent with the archive:
- its comment ends within the file;
- any ZIP64 locator before it points to a ZIP64 record before the locator;
- the directory ends exactly where the end records begin (the EOCD, or the ZIP64 record when present);
- the directory is large enough for its entry count.

All existing safety checks are unchanged:
- the entry limit (`TOO_LARGE`) and the directory size cap;
- per-entry bounds and central-header signatures;
- ZIP64 extra-field order;
- encryption refusal;
- bounded reads, and channel closing on failure.

No CBR work.

Files: `core/files/SeekableZip.kt`.

Regression coverage (`SeekableZipTest`):
- `validZipCommentMustNotReplaceTheDirectory`: Codex's test, verbatim.
- `commentsCannotReplaceAZip64Directory`: the same false record in the comment of an archive whose totals live in ZIP64 end records. The same 22 bytes on their own still open as a valid empty archive, so validation is by position, not content.

Validation:
- Both tests fail against the pre-remediation parser and pass now.
- The existing ZIP64 (70,000 entries), corrupt/truncated, encrypted and bounded-read tests stay green.

Future follow-up — NOT PART OF PHASE 1: the parser now requires the spec layout, with the directory immediately before the end records. Java's `ZipFile` effectively requires the same for mismatched comments. Non-conforming archives with bytes between the directory and the end record, which the old parser tolerated, are now reported as damaged. None are known among the samples; revisit only if real files show up.

## Finding
R5 — Permanent validation docs overstate the recorded evidence

Severity: LOW

Status: RESOLVED

Root cause: the status-level doc update said "1A–1D implemented" and stated SHA-256 verification without naming samples. This handoff, by contrast, recorded 1D as partial and the CBZ as unhashed, and the EPUB dialog-state defect appeared only here.

Fix:
- `docs/VALIDATION.md`:
  - status now reads 1A–1C implemented, 1D partial;
  - hashes are named for Dune, Chainsaw Man Vol. 01 and Frankenstein, and the 3.16 GB CBZ is "not hashed … unverified";
  - the pre-review table is labelled as such, and "Device tests" became "Instrumented tests (run on the emulator)";
  - a new "Review remediation" section gives evidence in four categories: Static/build, Unit/regression, Emulator, and Physical-device (not verified);
  - a new "Open software gaps" list covers: 1D motion; scroll restoration and rapid switching; fit width and zoom; saving Appearance for all titles, not exercised on a device; early PDF pages only; rollback and revocation; the cancellation window from R1. The EPUB dialog defect is recorded as fixed, not dropped silently.
- `docs/PHASE_1_PLAN.md`, `docs/ROADMAP.md` (status paragraph and track table) and `README.md`: 1D partial, with open software gaps named alongside the device gates. Nothing implies that only physical-device validation remains.
- `docs/ARCHITECTURE.md`: an "Import ownership" row for R1/R2, and the same correction to its Phase 1 status line, which Codex did not cite but which had the same overstatement.
- `docs/CHANGELOG_DOCS.md`: an entry for these changes.

Files: `docs/VALIDATION.md`, `docs/PHASE_1_PLAN.md`, `docs/ROADMAP.md`, `README.md`, `docs/ARCHITECTURE.md`, `docs/CHANGELOG_DOCS.md`.

Regression coverage: not applicable (documentation). The wording was checked against the evidence below, and no "device verified" or "runtime verified" claim was added.

Validation: in-page anchors were checked. The only inbound anchor, `#phase-1-validation-2026-09-24`, is unchanged.

## Updated acceptance checklist (after remediation)

The original checklist above is kept as history. Changed or added rows:

| Requirement | Status | Evidence |
| --- | --- | --- |
| Import ownership: committed work never discarded; abandoned work always released | PASS | R1 tests plus Codex's regression (JVM) |
| Grants kept while a library item or another import needs them; no leak | PASS | R2 tests, `ImportLeasesTest` and Codex's regression (JVM) |
| Reader UI state (controls, dialogs, unapplied Appearance) across recreation and rotation | PASS (emulator) | `AppearanceRestorationTest` and `EpubRecreationTest` on API 35. Replaces the known issue "EPUB Appearance and Chapters dialog state is lost on rotation" |
| Committed Appearance reloads after reopening | PASS (emulator) | `EpubRecreationTest` |
| Commented ZIP/CBZ archives | PASS | `SeekableZipTest` (both comment cases) |
| §7 Unit coverage | PASS | 60 JVM tests (was 44) |
| Docs describe shipped behavior | PASS | Corrected for R5; see VALIDATION |
| §7 CI/build tasks from a clean checkout | PASS (Phase 1 run) | Not repeated after the remediation; working-tree build, lint and unit tests pass |
| 1D rows, §7 source bytes, rollback, API 24/37, physical device, controller, TalkBack, low storage | Unchanged | Still PARTIAL or NOT TESTED as above |

## Validation (after remediation)

Environment as above: Windows 11, JDK 17, the `.tools` SDK, `GRADLE_USER_HOME=.tools/gradle-home`, `ANDROID_USER_HOME=.tools/android-user`, `--offline`.

| Category | Command / check | Result |
| --- | --- | --- |
| Unit/regression | `gradlew :app:testDebugUnitTest --tests com.d4guilar.shelfos.ReviewRegressionTest --init-script .tools/codex-review.init.gradle --rerun` | Before the fixes: 3 run, 3 failed. After: 3 run, 0 failed (XML checked) |
| Unit/regression | `gradlew :app:testDebugUnitTest --rerun` | **60 passed, 0 failed.** FoundationTest 5, ImportLeasesTest 5, ImportPolicyTest 9, ImportViewModelTest 17, ReadingPolicyTest 15, SeekableZipTest 7, StateTest 2 |
| Unit/regression | New tests run against the pre-remediation import and ZIP code (temporarily restored, then re-restored and hash-checked) | 9 failed, as intended; all pre-existing tests passed |
| Static/build | `gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` | BUILD SUCCESSFUL |
| Static/build | Lint XML | **0 errors.** 5 `UseKtx` warnings in untouched code and 1 Gradle-version hint, unchanged |
| Static/build | `git diff --check`; new files | Clean; LF endings; MPL-2.0 headers |
| Static/build | Dependencies, manifest, Room schema | Unchanged; no migration |
| Emulator | `gradlew :app:connectedDebugAndroidTest` on AOSP API 35 (`shelfos-phase0`, 1080×1920 @ 420 dpi) | **22 of 22 passed** (20 existing + 2 new; per-test XML checked) |
| Emulator | New recreation tests against the pre-remediation reader code | Both failed, as intended |
| Physical device | — | **PHYSICAL DEVICE: NOT VERIFIED.** No physical device was connected |

Notes on the emulator runs:
- The emulator was booted for this pass from the existing Phase 1 AVD. No new environment was set up.
- It was not re-run at 1600×1000, with reduced motion, or with the private samples.
- `connectedDebugAndroidTest` uninstalls the app afterwards, so emulator app data does not persist between runs.
- Logs and XML for these runs are in the local scratchpad only, not in the repository.

## Files changed in this remediation

Paths are relative to `app/src/main/java/com/d4guilar/shelfos/` unless noted. Most of these files were already modified or untracked by Phase 1, so `git status` alone cannot separate the two passes.

- **Production:** `domain/importing/ImportLeases.kt` (new), `domain/importing/ImportPolicy.kt`, `feature/importing/ImportViewModel.kt`, `ShelfApplication.kt`, `feature/home/ShelfApp.kt`, `core/files/SeekableZip.kt`, `core/reader/EpubSurface.kt`, `feature/reader/EpubActivity.kt`, `feature/reader/ReaderAppearance.kt`
- **Unit tests** (`app/src/test/java/com/d4guilar/shelfos/`): `ImportLeasesTest.kt` (new), `ImportViewModelTest.kt`, `SeekableZipTest.kt`, `ReadingPolicyTest.kt`
- **Instrumented tests** (`app/src/androidTest/java/com/d4guilar/shelfos/`): `AppearanceRestorationTest.kt` (new), `EpubRecreationTest.kt` (new)
- **Docs:** `README.md`, `docs/{VALIDATION,PHASE_1_PLAN,ROADMAP,ARCHITECTURE,CHANGELOG_DOCS}.md`
- **Coordination:** this section, and "Claude remediation notes" appended to `PHASE1_CODEX_REVIEW.md`
- **Not changed:** Gradle files, the version catalog, the manifest, the schema and `DEPENDENCY_LICENSES.csv`.
- **Private strategy:** untouched (SHA-256 `67a58ea0…`, unchanged) and still excluded only through `.git/info/exclude`. No `git clean` was run.

**Concurrent changes that are not part of this remediation.** Between 16:18 and 16:20 during this pass, another actor made documentation-only "Generic ZIP library import" (future `ZipArchiveSource`) changes, which I did not make. I left them untouched.
- Files: `AGENTS.md`, `SECURITY.md`, `docs/adr/0021-staged-library-ingestion.md`, and `docs/features/{DATA_INGESTION,IMPORT,LIBRARY_SOURCES,SERIES}.md`.
- Lines in `docs/ROADMAP.md`: new track "4. Generic ZIP archive source" and the backup-vs-generic-ZIP wording.
- Lines in `docs/ARCHITECTURE.md`: `ZipArchiveSource` and `ImportCoordinator`.
- The "Generic ZIP library import decision" entry in `docs/CHANGELOG_DOCS.md`.

They do not conflict with the R4 parser change. Whether they belong with the Phase 1 work is the owner's decision.

## Unresolved

- None of R1–R5.
- Phase 1 as a whole remains **not accepted**: the open software gaps, and the device, API-range, accessibility, controller and low-storage gates listed in VALIDATION, remain.
- Codex owns the verdict on this remediation.

# Phase 1 Acceptance Closure

Date: 2026-09-24. Scope: device, accessibility, input, performance and motion validation
following Codex's remediation PASS. Full detail and per-category evidence is in
`docs/VALIDATION.md` under "Phase 1 acceptance closure (2026-09-24)"; this section gives
the outstanding-criterion table the closure instructions asked for, plus the manual
checklist. R1–R5 were not reopened; the regression suite that guards them (Codex's three
tests plus the full JVM suite) still passes: 60 tests, 0 failures.

A physical device connected mid-pass: a **Retroid Pocket 5** (Moorechip, Android 13,
API 33), over USB with debugging authorized. It is used below wherever "physical device"
appears; nothing here claims a device it did not use.

## Outstanding criteria

| Outstanding criterion | Evidence | Result |
| --- | --- | --- |
| At least one physical device (plan §7 "Compatibility") | Retroid Pocket 5: full instrumented suite (22/22), a real SAF import end to end, force-stop/cold-relaunch resume, large text, reduced motion | PASS |
| Reader UI-state fixes (R3) hold on real hardware | Same instrumented suite; one test's fixed-orientation assumption was corrected (see below) | PASS |
| TalkBack (plan §7 "Compatibility"; closure §5) | No TalkBack/Accessibility Suite package on the only physical device available; none installed for this pass | NOT VERIFIED |
| Meaningful labels / traversal order (closure §5) | `uiautomator dump` of the reader's accessibility node tree: every control has a visible text label and meets the 48dp touch-target minimum at this device's density | PASS (node-tree evidence; not spoken-output evidence) |
| Physical keyboard/controller (plan §7; closure §6) | RP5 hardware D-pad/buttons were not pressed (requires a person); existing keyboard/D-pad tests remain simulated key events on real hardware | NOT VERIFIED |
| API 24 and API 37 (plan §7) | Only API 33 (this device) and API 35 (existing emulator) were available; no new emulator image was created | NOT VERIFIED |
| Performance measurement (closure §7) | Cold start, frame timing and memory measured on the RP5 with the one available fixture; no documented Phase 1 threshold exists to grade against | MEASURED, not a PASS/FAIL gate |
| Motion: reduced-motion and rotation (closure §8) | Full instrumented suite at animation scale 0 on the RP5 (22/22); corrected rotation test confirms no stale frame after a real rotation | PASS |
| Motion: documented cover-expansion transition (`CLASSIC_UI.md` §12) | Not implemented; determined to be feature work, not a smallest-fix defect correction (see below) | NOT DONE — explicit gap, not weakened |
| Reader zoom/fit, global Appearance save on a device | Unchanged from Review remediation | NOT VERIFIED |
| Real non-seekable provider, low storage, interrupted copy, Room rollback | Unchanged from Review remediation | NOT TESTED |
| Frame-time/heap under real (large-file) load, physical-device heap | Not repeated: pushing a large private sample to this personal device was out of scope for this pass | NOT VERIFIED |
| 1D scroll restoration, rapid-switch stress | Unchanged from Review remediation | NOT TESTED |

## What changed in this pass

- **One instrumented test, fixed; no production code touched by it.**
  `EpubRecreationTest.readerUiStateSurvivesRecreationAndAppliedAppearanceReloads` requested
  a fixed `SCREEN_ORIENTATION_LANDSCAPE` and waited for a new activity instance. On the
  RP5, auto-rotate is off and the device defaults to landscape (`user_rotation=1`), so
  requesting landscape when already effectively landscape never recreated the activity, and
  the test timed out. This is a hardware-dependent test assumption, not a defect in R3's
  fix: the JVM Saver test and the emulator's `StateRestorationTester` coverage are
  untouched. Fixed by requesting whichever orientation the device is not currently in, so
  the test rotates real hardware regardless of its default. After the fix: 22 of 22 on the
  RP5, and unchanged 22 of 22 on the emulator (rerun not required; the emulator test never
  depended on a fixed target orientation).
- **Real SAF import exercised for the first time this phase on physical hardware**, using a
  small original EPUB fixture generated for this pass (not committed to Git, matching
  "clean test fixtures... do not add copyrighted demo media to Git"): system picker →
  ShelfOS review dialog (embedded title, correct format/size) → Library → reader → position
  change → force-stop → cold relaunch → resumed at the same position, both on the Library
  card and inside the reader.
- **Accessibility got its first runtime (not source-only) check**: the reader's actual
  accessibility node tree was dumped and checked for labels and touch-target size, rather
  than only reading the Compose source for `testTag`/`contentDescription` calls.
- **Motion's cover-expansion gap was evaluated, not silently dropped.** See "Decision: motion"
  below.

## Decision: motion (cover-expansion transition)

`docs/design/CLASSIC_UI.md` §12 describes a signature transition where the selected cover
subtly expands into the reader, fast and skippable under reduced motion. Neither this pass
nor Phase 1 as shipped implements it; the Library/detail/reader transition today is the
existing 120 ms token fade only ("1D Library/detail/reader transitions: PARTIAL",
unchanged).

This closure pass's instructions allow fixing "the smallest Phase 1 issue necessary" when
a documented motion requirement is missing, but also say not to redesign the animation
system and not to change code just to satisfy a checkbox. A cover-expansion transition is
a shared-element animation across two different screens (Library grid item → reader
surface, one of which hosts a Fragment-based Readium navigator): implementing it now is
new UI work, not a targeted fix to something an acceptance test caught, and building it
under this pass's time and testing budget risks introducing exactly the kind of
UI-state/recreation defect this closure exists to rule out. I did not implement it. It
remains an explicit, named gap in both this handoff and `VALIDATION.md`, not weakened or
removed from the requirement.

## Physical Device Acceptance Checklist

This section exists for the owner (or anyone) to run manually, on the Retroid Pocket 5 or
any other physical Android device. It covers what this pass could not do without a person
physically operating the device: real hardware button presses and audible accessibility
output. It intentionally does not repeat what the automated pass above already verified
(launch, real-picker import, resume, rotation, large text, reduced motion): rerun those
manually only if you want to double-check them yourself.

| # | Action | Expected result | PASS / FAIL |
| - | --- | --- | --- |
| 1 | With the physical D-pad/analog stick, navigate the Library grid and open a publication | Focus moves visibly between covers; the highlighted cover opens on confirm | ☐ |
| 2 | With the hardware D-pad, turn pages in an Original PDF/CBZ | Pages turn the same way the emulator's injected D-pad test does (`mangaReadsRightToLeftWithKeyboardAndPageKeysStaySemantic`) | ☐ |
| 3 | With a hardware shoulder button (L1/R1) if present, turn pages | Behaves like the existing `KEYCODE_BUTTON_L1` test | ☐ |
| 4 | With the hardware B/Back button, hide reader controls, then leave | Same as System Back/Escape: first press hides controls, second leaves | ☐ |
| 5 | Enable TalkBack (Settings → Accessibility) or install Android Accessibility Suite, then navigate Library, a publication's details, and the reader by swipe | Every focused control is announced with a clear, distinct label; nothing is silently skipped or announced only as "button" | ☐ |
| 6 | With TalkBack on, open and use the Appearance dialog | Every option (font presets, sliders, palette, "use as default") is announced and reachable in a sensible order | ☐ |
| 7 | With TalkBack on, confirm no control traps focus (you can always reach the next/previous element and Back) | No focus trap in Library, details, reader or dialogs | ☐ |
| 8 | On an API 24 device if available | App launches and the Phase 1 flow (import, read, resume) works | ☐ |
| 9 | On an API 37 (or newer) device if available | Same as #8 | ☐ |
| 10 | Rotate the device (or fold/unfold, if applicable) while an Appearance dialog is open with unsaved changes | The draft and dialog survive, as verified on the emulator and the RP5 in this pass | ☐ |
| 11 | Read a large PDF or CBZ (hundreds of MB or more) start to end, watching memory (Settings → Developer options → Running services, or a profiler) | Memory stays roughly flat rather than growing with pages read, matching the emulator's private-sample result | ☐ |
| 12 | Force a low-storage condition (or fill the device close to capacity) and attempt an import that needs a private copy | A clear, non-crashing error; no partial file left behind | ☐ |

Record results directly in this table (or a copy of it) and share them; that is
sufficient evidence to close the remaining physical-device sub-items without another full
pass.

## Final validation summary (this pass)

| Category | Result |
| --- | --- |
| Codex R1–R5 regression tests | 3 of 3 pass, unchanged |
| Full JVM unit suite | 60 of 60 pass |
| Lint | 0 errors |
| `assembleDebug` / `assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| Instrumented suite, physical device (Retroid Pocket 5, API 33) | 22 of 22 pass (after the orientation-assumption test fix) |
| Instrumented suite, physical device, reduced motion | 22 of 22 pass |
| Instrumented suite, emulator (API 35) | 22 of 22, existing evidence from Review remediation, not rerun |
| Physical device | Retroid Pocket 5 used for the checks above; PHYSICAL DEVICE (general hardware controller/TalkBack): NOT VERIFIED |

## Unresolved (this pass)

- TalkBack spoken/gesture verification, physical controller button presses, API 24/37,
  reader zoom/fit and global Appearance on-device exercise, non-seekable
  provider/low-storage/interrupted-copy/Room-rollback cases, frame-time/heap under real
  large-file load, 1D scroll/rapid-switch evidence, and the cover-expansion transition —
  all listed above, all explicit, none silently dropped.
- **PHASE 1 ACCEPTANCE STATUS: NOT YET ACCEPTED.**

# Phase 1 Acceptance Closure — Continuation

Date: 2026-09-24. Picked up mid-pass: the working tree already contained new, uncommitted
instrumented test coverage (non-seekable provider, low storage, interrupted copy, Room
rollback, reader zoom/fit + global Appearance, 1D scroll restoration + rapid switching) and
a debug-only content-provider test double, plus two already-running emulators
(`shelfos-api24`, `shelfos-api37`). Nothing was discarded or rewritten. Full detail and
per-category evidence is in `docs/VALIDATION.md` under "Phase 1 acceptance closure —
continuation". R1–R5 were not touched; the guarding suite (Codex's 3 tests, the full 60-test
JVM suite) still passes.

## What was already there, verified and preserved

- `app/src/androidTest/.../LibraryPersistenceTest.kt`: four new cases —
  `nonSeekableProviderCopiesCommitsReopensAndCleansWithoutTouchingTheSource`,
  `lowStorageAndInterruptedCopiesLeaveNoCommittedItemOrPartialAndCanRetry`,
  `roomTransactionRollsBackRelatedStateWhenRemovalFails`, plus supporting changes
  (`@Before`/`@After` isolated storage, a few sourceUri/API updates). All four are correct
  and were kept unmodified.
- `app/src/androidTest/.../NavigationSmokeTest.kt`: two new cases —
  `fixedReaderZoomFitAndGlobalAppearancePersistAcrossRecreation` and
  `libraryScrollRestoresAfterReaderAndRapidTitleSwitchingKeepsTheRightSession`. The first
  was kept unmodified. The second had a one-line off-by-one bug (below), fixed narrowly.
- `app/src/androidTest/.../SyntheticLoadAcceptanceTest.kt` (new file): a generated, non-
  copyrighted 160-page/219 MB CBZ load test with memory logging. Kept unmodified; also used
  for the performance gate below.
- `app/src/debug/AndroidManifest.xml` and `app/src/debug/.../NonSeekableTestProvider.kt`
  (new files): a debug-only `ContentProvider` backing the non-seekable/low-storage/
  interrupted-copy tests with a real pipe descriptor. Debug-only by construction (`src/debug`
  source set); never in a release build. Kept unmodified.
- `app/src/main/.../core/files/PublicationFiles.kt`: gained an injectable
  `availableBytes: ((File) -> Long)?` constructor parameter (defaults to the real check
  unchanged) so the low-storage case can force a deterministic result, plus a small
  exception-mapping addition for the interrupted-copy path. Both are narrow, correct,
  test-enabling additions to already-existing logic. Kept unmodified.
- `RoomLibraryRepository.kt` / `LibraryDao.kt`: no changes were needed for the Room-rollback
  gate. `LibraryDao.remove()` was already `@Transaction`; the new test only had to prove it
  by injecting a SQLite trigger that aborts the delete, and confirming the whole transaction
  rolled back.

## One test fix (not a weakening)

`libraryScrollRestoresAfterReaderAndRapidTitleSwitchingKeepsTheRightSession` called
`performScrollToIndex(33)`. On the `shelfos-api24` emulator this threw
`IllegalArgumentException: Can't scroll to index 33, it is out of bounds [0, 33)` —
Compose's own framework code reporting the true item count as 33, one more than the
30 seeded items plus 2 Book fixtures (32) the original author likely counted. Reading
`LibraryScreen.kt` confirmed the extra item: the "Continue Reading" header shares the same
`testTag("library_grid")` `LazyVerticalGrid` as the publication cards. Fixed to compute the
true last valid index (`extraIds.size + 2` = 32) instead of a guessed literal. The scenario,
assertions and intent are unchanged; this only corrects which index is definitely in bounds.

## API 24 and API 37

Both AVDs (`shelfos-api24`, `shelfos-api37`) already existed and were already running, as
described. Full results are in VALIDATION.md; summary:

- **API 24:** app launches; full `NavigationSmokeTest` (12/12, including both new tests)
  and all resilience cases pass. Running many `PublicationFiles`/`PdfRenderer`-touching
  tests together on this specific x86_64 emulator image intermittently ends in a native
  `SIGSEGV` inside the platform's `libpdfium.so`, always at the same test
  (`nonSeekableProviderCopies…`), reproduced 3 of 3 times in full-sequence runs, never in
  isolation or in pairs, and never on the physical RP5 running the identical sequence twice.
  Treated as an emulator-specific instability, not a confirmed ShelfOS defect, and not
  claimed as fixed — real API 24 hardware was unavailable to confirm either way.
- **API 37:** the system image boots and ShelfOS itself launches and renders correctly
  (screenshot evidence, cold start 1794 ms). Non-UI instrumented tests pass 13/13. Every
  UI-driving instrumented test fails with
  `NoSuchMethodException: android.hardware.input.InputManager.getInstance` — the pinned
  androidx.test/Espresso version predates this platform's removal of that accessor. This is
  a test-tooling gap against a very new API level (Android 17), not a missing system image
  and not an app defect; no dependency change was attempted (this offline environment cannot
  fetch a newer artifact, and a test-only version bump is outside this pass's scope).

Neither result substitutes a different API level for what was asked.

## TalkBack: attempted, still not verifiable automatically

TalkBack is preinstalled on the `shelfos-api37` Google Play emulator. It was enabled and
confirmed running via `dumpsys accessibility` (service bound, spoken/haptic/audible
feedback). ADB-injected swipe gestures did not reliably drive its touch-exploration or
announcement behavior in this environment (no accessibility-focus change, no utterance
activity observed), so this did not produce genuine spoken-output evidence. TalkBack was
disabled again afterward. This remains a manual gate; see the checklist below, unchanged in
substance from the previous pass but now noting that TalkBack is available pre-installed on
`shelfos-api37` if the owner prefers to test there instead of the RP5.

## Resilience, performance, 1D and zoom/fit gates: closed

- Non-seekable provider, low storage, interrupted copy, Room rollback: all pass on the RP5
  (10/10 `LibraryPersistenceTest`) and on API 24 (individually/paired; see the emulator
  finding above for the full-sequence flakiness).
- Reader zoom/fit and global Appearance persistence: pass on the RP5 and API 24.
- 1D scroll restoration and rapid title switching: pass on the RP5 and API 24 (after the
  test fix above).
- Performance: a generated, non-copyrighted 219 MB/160-page CBZ was paged on the RP5.
  Memory stayed roughly flat (335.8→346.3 MB PSS over 80 pages); frame timing was smooth
  (718 frames, 0.28% janky, 50th/99th percentile 5/10 ms). No documented threshold exists;
  this is a measurement, not a pass/fail gate.

## Updated outstanding-criteria table

| Outstanding criterion | Evidence | Result |
| --- | --- | --- |
| Non-seekable provider | `LibraryPersistenceTest`, RP5 + API 24 | PASS |
| Low storage | `LibraryPersistenceTest`, RP5 + API 24 | PASS |
| Interrupted copy | `LibraryPersistenceTest`, RP5 + API 24 | PASS |
| Room transaction rollback | `LibraryPersistenceTest`, RP5 + API 24 (existing `@Transaction`, no code change needed) | PASS |
| Reader zoom/fit on a device | `NavigationSmokeTest`, RP5 + API 24 | PASS |
| Global Appearance persistence on a device | `NavigationSmokeTest`, RP5 + API 24 | PASS |
| 1D Library scroll restoration | `NavigationSmokeTest`, RP5 + API 24 | PASS |
| Rapid reader/view switching stress | `NavigationSmokeTest`, RP5 + API 24 | PASS |
| API 24 runtime | `shelfos-api24`, app + full UI smoke suite | PASS (app); emulator-only native-crash finding, unconfirmed on real hardware |
| API 37 runtime | `shelfos-api37`, app launch + non-UI tests | PASS (app + non-UI); UI-instrumented coverage NOT VERIFIED (tooling gap) |
| Performance under representative load | 219 MB synthetic CBZ, RP5, gfxinfo/meminfo | MEASURED |
| TalkBack spoken/gesture | Attempted on `shelfos-api37`; not automatable via ADB | NOT VERIFIED — manual |
| Real RP5 hardware buttons | Owner tested the RP5's physical D-pad/gamepad directly (not simulated); reported no issues across Library and Reader navigation, activation, reader controls and Back | PASS — owner-verified |
| Cover-expansion transition | Unchanged determination | NOT DONE — explicit gap |

## Validation (this continuation)

| Category | Command / check | Result |
| --- | --- | --- |
| Static/build | `gradlew :app:testDebugUnitTest --tests ReviewRegressionTest --init-script ... --rerun` | 3/3 pass |
| Static/build | `gradlew :app:testDebugUnitTest --rerun` | 60/60 pass |
| Static/build | `gradlew :app:lintDebug` | 0 errors (new debug-only `ExportedContentProvider` warning, expected) |
| Static/build | `gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| Static/build | `git diff --check` | Clean |
| Physical device (RP5) | Full instrumented suite | 28 of 28 pass |
| Physical device (RP5) | Synthetic-load performance run | Completed; see Performance |
| Emulator (API 24) | Full `NavigationSmokeTest` | 12 of 12 pass (after the one test fix) |
| Emulator (API 24) | `LibraryPersistenceTest`, individually/paired | 10 of 10 pass |
| Emulator (API 24) | `LibraryPersistenceTest`/full-suite together | Intermittent native crash; see finding above |
| Emulator (API 37) | App launch | PASS (screenshot, 1794 ms cold start) |
| Emulator (API 37) | Non-UI instrumented tests | 13 of 13 pass |
| Emulator (API 37) | UI-instrumented tests | Cannot run (Espresso/platform version gap) |

## Files changed by me in this continuation

- `app/src/androidTest/java/com/d4guilar/shelfos/NavigationSmokeTest.kt`: one-line index fix
  (see above).
- `docs/VALIDATION.md`: new "Phase 1 acceptance closure — continuation" section.
- `PHASE1_CLAUDE_HANDOFF.md`: this section.

No other production or test files were changed by me in this continuation. Everything else
listed under "What was already there" was present before I started and is preserved as-is.

## Physical controller — owner-verified (2026-09-24)

The owner tested ShelfOS on the Retroid Pocket 5 using its actual physical gamepad
(D-pad/buttons), not ADB-injected key events. Reported result: the gamepad works correctly
with no issues observed during normal Library and Reader interaction — physical D-pad/
controller navigation, activation, reader controls and Back behavior all worked as intended.

**REAL HARDWARE INPUT / PHYSICAL CONTROLLER: VERIFIED (PASS)**, owner-reported, on the
Retroid Pocket 5. This is genuine hardware evidence, not simulated input, and was not rerun
or replaced with simulated key events. It closes item 16 (physical RP5 controller) from the
outstanding-criteria table above.

## Unresolved (this continuation)

- TalkBack spoken/gesture confirmation: still needs a person; see the physical/manual
  checklist above (unchanged in substance). Real RP5 hardware button presses are now
  resolved (owner-verified, above).
- The API 24 emulator-specific native-crash finding: unconfirmed on real API 24 hardware.
- API 37 automated UI-test coverage: blocked by a test-tooling version gap, not by anything
  ShelfOS controls; the app itself is confirmed working on API 37.
- The cover-expansion transition: unchanged, deliberate non-implementation.

**PHASE 1 ACCEPTANCE STATUS: NOT YET ACCEPTED.**

# Phase 1 Acceptance Closure — Final Items

Date: 2026-09-24. Closes the five items handed off after the previous continuation: the
cover-expansion transition, large-load performance, and a precise interpretation of API
24/37 against the plan's own compatibility wording. Full detail and evidence is in
`docs/VALIDATION.md` under "Phase 1 acceptance closure — final items"; this section
summarizes the outcome and gives the owner the one remaining manual item.

## Cover-expansion transition: implemented

The smallest faithful version of `docs/design/CLASSIC_UI.md` §12. A publication's Details
screen (compact route or expanded-layout pane) is where "the selected cover" the
specification means actually is; when Read is chosen there, that cover expands into the
incoming reader over 220 ms while chrome fades, then the same navigation as before (NavHost
route or `EpubActivity` launch) runs. Reduced motion skips straight to that navigation
(the documented no-motion fallback). No shared element crosses into `EpubActivity` itself
(a separate Activity) — building that was judged out of scope for "smallest faithful";
EPUB gets the same pre-launch expand, then its existing Activity launch. The Continue
Reading card's direct tap is unchanged (no "selected cover" moment to expand from).

New files: `core/theme/ReducedMotion.kt`, `feature/home/CoverExpandTransition.kt`. Changed:
`ShelfApp.kt` (drives the transition and applies chrome alpha), `PublicationDetails.kt` and
`LibraryScreen.kt` (thread the cover's on-screen position through the existing `onRead`
callback as an added nullable parameter). Neither Library nor Reader was redesigned.

Tests: `CoverExpandTransitionTest` (JVM, 3 tests, the chrome-fade curve) and
`NavigationSmokeTest.readerEntryTransitionIsSkippedUnderReducedMotion` (instrumented, forces
`animator_duration_scale=0` and confirms Read still opens the reader). Every existing
Details→Read test now also exercises the transition's normal-motion path.

**Validation:** JVM 63/63 (was 60). Codex's 3 regressions unaffected. Lint 0 errors,
unchanged. Both builds succeed. RP5: 29/29 three times (including once under system-wide
reduced motion), after one transient run showed 9 unrelated "no compose hierarchy" failures
(including in a test using bare `createComposeRule()`, sharing no code with this feature) —
not reproduced on the next two runs, and not a pattern consistent with a code defect. API 24
(`shelfos-api24`): `NavigationSmokeTest` 13/13.

## Large-load performance: measured at ~3 GB

A generated, non-copyrighted CBZ (**2,997,685,907 bytes, 947 pages**), not the original
private sample, read on the physical Retroid Pocket 5 (API 33):

- Opened in 534–535 ms.
- Memory (`Debug.MemoryInfo`, two runs): PSS 231.6 MB → 249.7 MB → 246.4 MB, and 231.6 MB →
  241.2 MB → 241.3 MB across pages 1→150→180 — roughly flat, not growing with pages read.
  Java heap 6.6 MB both times.
- `adb shell dumpsys meminfo`, held at page 180: TOTAL PSS 241.8 MB.
- `adb shell dumpsys gfxinfo`, held at page 180: 1543 frames, 0.19% janky, 50th/99th
  percentile 5/6 ms.

No documented threshold exists to grade this against; it is a measurement. The generating
test file was a temporary scratch file (`LargeLoadScratch.kt`), removed from the tree after
this validation; the archive itself was deleted from the device by its own test teardown
(confirmed with `run-as ls` immediately after).

## API 24 / API 37: classified against the plan's actual wording

`docs/PHASE_1_PLAN.md` §7's Compatibility line: "API 24 and current-target runtime,
compact/expanded layouts, at least one physical device, keyboard/D-pad and physical
controller where available; TalkBack and reduced-motion checks. Document any unexecuted
hardware tests." This asks that these dimensions be exercised and unexecuted hardware tests
documented — it does not name one specific zero-flake automated command as the sole proof.

- **API 24: runtime requirement satisfied**, with a documented, unresolved emulator-specific
  limitation. The app and its full UI smoke suite work reliably (13/13). An intermittent
  native crash inside the platform's own `libpdfium.so`, reproduced only under a long
  sequence of `PdfRenderer`-touching tests on the specific `shelfos-api24` x86_64 AOSP image
  (already known to need GPU emulation disabled just to boot), never on the physical device
  running the same sequence — recorded honestly as an environment finding, not classified as
  a ShelfOS defect, not claimed fixed.
- **API 37: application-level runtime requirement satisfied; automated UI-instrumented
  coverage is not**, and the two are kept separate as asked. The system image boots; ShelfOS
  launches and renders correctly; non-UI instrumented tests pass 13/13. UI-instrumented
  tests cannot run against this platform with the project's pinned androidx.test/Espresso
  version (a removed `InputManager` accessor) — a tooling gap, not a missing image and not a
  demonstrated app failure. No dependency was upgraded to work around this.

Neither is a different API level substituted for what was asked, and neither classification
weakens the criterion.

## TalkBack manual checklist — PASS (owner-verified, 2026-09-24)

Automated: TalkBack was confirmed installable and running (bound, spoken/haptic/audible
feedback) on the Google Play `shelfos-api37` emulator, but ADB-injected gestures do not
reliably drive its touch-exploration or announcement pipeline — no automated run could
produce genuine spoken-output evidence itself. The emulator was relaunched with a visible
window (data preserved; TalkBack still enabled after restart) so the owner could run the
checklist directly with real gestures.

| # | Action | Expected | Result |
| - | --- | --- | --- |
| 1 | Swipe through the Library's bottom navigation / rail (Library, Search, Notes, Shelves, Settings) | Each is announced by its own name; none is silent or announced only as "button" | PASS |
| 2 | Swipe through the Library grid | Each publication is announced with its title (and progress, if it has any); "Continue Reading" is announced as a heading, distinct from the publications below it | PASS |
| 3 | Double-tap a publication to open Details | Lands on Details; title, category/format, and the Read/Continue button are all announced with distinct, meaningful text (not just "button") | PASS |
| 4 | Double-tap Read/Continue | Opens the reader; you can continue swiping to reach reader controls | PASS |
| 5 | Swipe to and double-tap Appearance | The dialog's controls (style presets, sliders, palette choices, "use as default") are each announced and reachable in order; nothing is skipped | PASS |
| 6 | Swipe to Back/Library from the reader, then from Details | Returns correctly at each level; you are never stuck with no reachable control | PASS |
| 7 | Throughout 1–6 | You can always reach a next and a previous element — no focus trap anywhere | PASS |

Owner's report: **Pass.** (Reported as a single result covering the whole checklist,
recorded here row-by-row against what was asked.) Genuine spoken/gesture TalkBack evidence,
not simulated and not inferred from the accessibility node tree alone.

**TALKBACK: VERIFIED (PASS).**

## Files changed in this closure

- New: `app/src/main/java/com/d4guilar/shelfos/core/theme/ReducedMotion.kt`,
  `app/src/main/java/com/d4guilar/shelfos/feature/home/CoverExpandTransition.kt`,
  `app/src/test/java/com/d4guilar/shelfos/CoverExpandTransitionTest.kt`.
- Changed: `feature/home/ShelfApp.kt`, `feature/library/PublicationDetails.kt`,
  `feature/library/LibraryScreen.kt`, `androidTest/.../NavigationSmokeTest.kt` (added the
  reduced-motion transition test).
- Temporary, already removed from the tree: `LargeLoadScratch.kt` (the one-off ~3 GB
  performance exercise).
- `docs/VALIDATION.md`, `PHASE1_CLAUDE_HANDOFF.md`: this closure's evidence.

## Final status

Every `PHASE_1_PLAN.md` §7 gate has been exercised with recorded evidence: unit/Room/
instrumented/CI all pass; private acceptance and reliability (non-seekable provider, low
storage, interrupted copy, Room rollback) all pass; compatibility (API 24, API 37,
compact/expanded layouts, a physical device, keyboard/D-pad, the RP5's own physical
controller, TalkBack, reduced motion) is exercised and evidenced, with two items recorded as
documented non-blocking limitations rather than left silently unresolved:

- The API 24 emulator-specific native-crash finding (`shelfos-api24` x86_64 AOSP image
  only; never reproduced on physical hardware; not classified as a ShelfOS defect).
- API 37 automated UI-instrumented-test coverage (blocked by an androidx.test/Espresso
  version gap against a very new platform; the application itself is confirmed working).

Neither reopens R1–R5, neither was weakened or substituted, and both remain visible here and
in `docs/VALIDATION.md` rather than being absorbed quietly into an unqualified "accepted."

**PHASE 1 ACCEPTANCE STATUS: ACCEPTED (2026-09-24).**
