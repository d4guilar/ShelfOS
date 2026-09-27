# Documentation Changelog

## Phase 2B.2 — durable EPUB bookmarks + Room v2→v3 migration (2026-09-26)

- Implemented Phase 2B.2 on new branch `phase-2/epub-bookmarks`, based on
  `main` at `ac9476d56f332c067aa39dc2b1e5533288103c12` (2B.1 accepted and
  merged via PR #8). **2B.2 is IMPLEMENTED, PENDING INDEPENDENT REVIEW — not
  accepted, not merged.**
- Added a new `domain.library.Bookmark` model (`id`, `itemId`, `locator`,
  `progress`, optional `label`, `createdAt`): a discrete saved location,
  separate from resume position. The serialized Readium `Locator` JSON is the
  sole source of truth for navigation; `progress` is a display/sort snapshot
  only and is never read back for navigation; `label` is reserved, unused
  this slice.
- Added the one schema-changing migration of Phase 2B: `ShelfDatabase` v2→v3,
  adding a `bookmark` table (FK to `library_item.id` with
  `ON DELETE CASCADE`, indexed on `itemId`) via a new, additive-only
  `MIGRATION_2_3`. No existing table, column, or index was altered or
  dropped, and no `fallbackToDestructiveMigration` was used anywhere.
- Added `BookmarkRepository` (observe/add/delete) implemented by the existing
  `RoomLibraryRepository`/`LibraryDao`, following the project's established
  one-interface-per-concern, shared-DAO pattern — no new DAO class, no Room
  details leak into Compose.
- Duplicate-add is handled at the DAO/repository layer (exact
  `(itemId, locator)` string match inside a `@Transaction`), not via a
  database `UNIQUE` constraint — the opaque locator JSON was not judged
  stable enough to key a constraint on. Default ordering is
  `progress ASC, createdAt ASC`.
- "Add bookmark" reuses the single live-locator collector `EpubSurface`
  already exposes (from 2B.1) via `EpubActivity`'s existing
  `currentLocatorJson` — no second long-lived collector was added. If no live
  locator is available yet, Add is disabled rather than creating an
  empty/fake bookmark.
- Added one reader-chrome entry point, "Bookmarks," opening a transient
  dialog (matching the existing Chapters/Appearance dialog pattern) with: add
  at current position (disabled once the current position is already
  bookmarked), a list of existing bookmarks for the title, tap-to-jump,
  delete, and an empty state. No separate top-level Add/List buttons, no
  Notes screen, no folders/tags/colors/rename editor were added.
- Bookmark rows display a derived label reusing 2B.1's `matchChapter`
  ("Chapter title · progress%", or just "progress%" when the chapter is
  ambiguous per 2B.1's own honesty rule) — no chapter title is persisted;
  the stored locator remains authoritative.
- Jump-to-bookmark parses the stored locator via `Locator.fromJSON` and
  navigates through the existing navigator/controller ownership boundary
  (`EpubController.goTo`) — no second navigator is created, and there is no
  fallback to progress-percentage navigation. A malformed/unparseable
  locator shows a readable in-dialog failure instead of crashing or
  corrupting other data; the bad bookmark can still be deleted.
- Delete removes only the selected bookmark; deleting a `LibraryItem` cascades
  to delete its bookmarks purely through the Room FK's `ON DELETE CASCADE` —
  `LibraryDao.remove(itemId)` itself was intentionally left unchanged.
- Bookmarks are Room-backed only; no bookmark list or Readium object is ever
  placed in `rememberSaveable`/`SavedState`. Verified surviving dialog
  close/reopen, activity recreation, and publication close/reopen.
- Accessibility: each row has one meaningful `contentDescription`
  ("Bookmark, <label>"); the Add control's description states whether adding
  is currently possible; the Delete control's description names its own
  bookmark. No new `ShelfCommand` mapping was added; all controls are
  ordinary focusable Compose buttons reachable by keyboard/D-pad exactly like
  existing Chapters/Appearance controls, and Back/Escape/gamepad B closes the
  dialog first via the existing dismiss handling. `INPUT_SYSTEM.md`'s
  conceptual `TOGGLE_BOOKMARK` command was **not** wired, since no accepted
  mapping contract for it exists yet.
- No `androidx.room:room-testing`/`MigrationTestHelper` dependency was added.
  The project's existing raw-SQL-schema-bootstrap migration-testing
  convention (already used by `LibraryPersistenceTest`) covered the v2→v3
  migration test's needs without a new dependency, per `AGENTS.md`'s
  dependency-review rule.
- Added `BookmarkPersistenceTest` (6 tests: migration+preservation+cascade,
  duplicate no-op, deterministic ordering, FK rejection, per-item isolation,
  single delete) and `EpubBookmarkTest` (4 tests: full add/list/jump/delete
  flow across dialog reopens, activity recreation, publication reopen,
  malformed-locator handling) — all instrumented against the real database
  and the real `EpubReaderViewModel`/`EpubActivity` wiring.
- **Fixed a real regression, caused directly by the intentional schema
  version bump, in the pre-existing `LibraryPersistenceTest`:** its own
  `open()` migration-test helper hardcoded only `MIGRATION_1_2` and needed
  `MIGRATION_2_3` added once the database moved to version 3; production's
  own `ShelfDatabase.create()` already had both and was unaffected. Confirmed
  fixed: 10/10 `LibraryPersistenceTest` cases pass.
- Recorded, then confirmed non-reproducible in isolation, one
  `EpubBookmarkTest` timing flake seen on a single full-suite run under
  emulator load (a 10s Compose wait timeout) — consistent with device-load
  flakiness, not a defect; see `VALIDATION.md` for the full evidence trail,
  including a separate emulator-infrastructure disconnection encountered and
  resolved during this validation pass.
- Because this slice changes the persistent schema, the full
  `connectedDebugAndroidTest` suite was run to completion: **65/65 passed, 0
  failures, 0 errors.**
- Updated `PHASE_2_PLAN.md`'s status and 2B.2 sections, `VALIDATION.md` with
  a full Phase 2B.2 validation record, and `ROADMAP.md`'s Phase 2 status line
  (2B.1 accepted/merged via PR #8, 2B.2 implemented pending review).
- No 2B.3 (`SearchService` UI), 2B.4 (custom fonts), highlights, textual/
  handwritten notes, note export, third-party integrations, PDF/CBZ changes,
  Series, CBR, OCR, Adapted PDF, ShelfOS Home, cloud sync, or control
  remapping work was added. 2B.2 remains implemented, not yet reviewed by
  Codex, not merged, not pushed.

## Phase 2B.1 R2 second remediation round (2026-09-26)

- Fixed two remaining findings from a second independent Codex review of the
  R2/R3 remediation below, on the same branch (`phase-2/epub-chapters`),
  without resetting/dropping either prior commit (`aa0a5d7`, `9ed363a`):
  (R2) `matchChapter`'s fallback — corrected in the first remediation round
  to prefer a lone no-fragment entry, else "the first same-resource entry" —
  could still present a specific, named chapter as current in a genuinely
  ambiguous case (several equally-plausible same-resource candidates, no
  locator fragment to prefer one). Corrected again: the fallback now returns
  the sole no-fragment entry, or the sole same-resource candidate by
  elimination, and otherwise returns **null** — deliberately admitting the
  position cannot be determined rather than guessing. `EpubActivity`'s
  existing `chapter.id == currentChapterId` comparison already treats null
  correctly with no UI code changes needed: no row shows a checkmark, bold
  weight, or "current chapter" description when the result is ambiguous:
  every row simply stays a normal, navigable chapter button. No "current
  chapter unknown" text or warning was added.
- Updated `EpubChapterMatchTest` (16 JVM tests, all pass) with explicit
  ambiguous-case coverage (`twoFragmentOnlyCandidatesWithNoUsableLocatorFragmentAreAmbiguous`,
  `twoFragmentOnlyCandidatesWithAnUnrelatedLocatorFragmentAreStillAmbiguous`,
  `duplicateHrefRowsAreAmbiguousAndResolveToNoCurrentRowRatherThanAnArbitraryPick`)
  and explicit unambiguous-case coverage
  (`locatorWithNoUsableFragmentFallsBackToTheResourceLevelEntryWhenExactlyOneExists`,
  `aSingleFragmentOnlyCandidateWinsByEliminationWhenNoOtherSameResourceEntryExists`).
  The already-fixed multi-fragment-order test is untouched — this round only
  changes what happens once no locator fragment produces an exact match.
- Renamed and re-asserted the real fragmented-EPUB instrumented test
  (`EpubChapterHighlightTest`) to
  `ambiguousSameResourceFragmentsShowNoCurrentRowButNavigationAndTheDialogStillWork`:
  against the real fixture (two fragment-only same-resource entries, no
  resource-level sibling), it now asserts **zero** current rows both at
  initial open and after navigating to the other entry and reopening the
  dialog — while confirming navigation and the dialog itself remain fully
  functional. The unambiguous-case instrumented test is unchanged and still
  proves exactly one current row when a chapter is the sole entry for its
  own resource.
- (R3) Softened `VALIDATION.md`'s wording around the one full-connected-suite
  teardown failure from earlier this pass: it previously stated the timeout
  was "confirming device-load flakiness," which overstated the evidence.
  Corrected to state only what was actually observed — the timeout followed
  a long `SyntheticLoadAcceptanceTest` run and is consistent with load-
  related/environmental flakiness, not directly proven to be caused by it;
  the affected test passed 26/26 in isolation, and no production regression
  was reproduced.
- No bookmarks, Room migration, `SearchService` UI, custom fonts, new
  dependencies, XHTML parsing, or other 2B.2–2B.4 work was added. 2B.1
  remains implemented, not yet re-reviewed by Codex, not merged.

## Phase 2B.1 R2/R3 remediation (2026-09-26)

- Fixed two independent-Codex-review findings on the same branch
  (`phase-2/epub-chapters`), without resetting/dropping the prior commit:
  (R2.1) `matchChapter` now checks every locator fragment in order, not just
  the first, since `Locator.Locations.fragments` is not guaranteed to list
  the corresponding fragment first; (R2.2) current-chapter identity now uses
  a new `EpubChapter.id` (a stable, in-memory-only, flattened-TOC-position
  ordinal) instead of `href`, so two rows that happen to share a raw href
  (a redundant/duplicate TOC entry) can no longer both read as "current" —
  chapter navigation itself is unaffected, still keyed on the raw href
  exactly as before.
- Added a real same-resource fragmented EPUB fixture
  (`OriginalFixtures.epubWithFragmentedChapter`, two TOC entries into one
  XHTML resource at different real anchors) and an instrumented test
  exercising it, per the R3 finding that fragment behavior lacked real
  integration coverage.
- **Discovered and recorded a real, empirically-verified Readium navigator
  limitation while building that fixture's test:** the pinned Readium 3.4.0
  EPUB navigator's own `currentLocator`, in its default paginated mode, never
  reports `Locations.fragments` after navigating to a fragment (verified
  directly via logged real `Locator` JSON, for both the `Link`- and
  `Locator`-based `Navigator.go(...)` overloads) — only
  `Publication.locatorFromLink` resolves a TOC entry's own fragment
  correctly. This means the (correctly implemented and unit-tested)
  multi-fragment matching fix is not yet observably exercised by real
  in-app navigation; documented in `matchChapter`'s doc comment
  (`core.reader.EpubReader.kt`), the new instrumented test's own doc comment,
  and `docs/PHASE_2_PLAN.md`'s 2B.1 R2/R3 remediation record, rather than
  silently assumed away or hidden behind a test asserting the
  originally-hoped-for (but not actually true) behavior. No HTML-position
  heuristic was built to work around it, per this remediation's explicit
  scope guard.
- Directly recorded the completed full Gradle gate result in
  `VALIDATION.md` (R3 finding: the prior entry deferred to "see final
  report" instead of stating the result there).
- Re-ran the full instrumented package; one flaky failure
  (`NavigationSmokeTest.mangaReadsRightToLeftWithKeyboardAndPageKeysStaySemantic`,
  an `ActivityScenario` teardown timeout after a 34-minute
  `SyntheticLoadAcceptanceTest` in the same run exhausted emulator
  resources) was confirmed as device-load flakiness, not a regression, by
  re-running `NavigationSmokeTest` alone (26/26 passed).
- 2B.1 remains implemented, not yet re-reviewed by Codex, not merged. 2B.2
  (bookmarks)/2B.3 (search)/2B.4 (custom fonts) are untouched.

## Phase 2B.1: chapter-navigation polish + scroll/typography/page-color closure (2026-09-26)

- Recorded 2B's discovery/implementation-planning pass as **accepted and
  merged to `main`** (PR #7, `410ce4d6c4daa5d6fa65fb2787027d50d722822f`) in
  `PHASE_2_PLAN.md`.
- Implemented 2B.1 on `phase-2/epub-chapters`: current-chapter highlighting in
  the Chapters dialog, an optional above-threshold chapter-title filter, and
  verification (not re-implementation) that pagination/scroll, typography and
  page-color reading-surface behavior are already complete. Recorded as
  **IMPLEMENTED, PENDING INDEPENDENT REVIEW** — not accepted — in
  `PHASE_2_PLAN.md` and `VALIDATION.md`.
- Corrected the plan's own current-chapter matching design during
  implementation: `EpubChapter` now carries a `resource`/`fragment` pair
  resolved via `Publication.locatorFromLink(link)` (the same resolution
  Readium's navigator uses for `currentLocator`) rather than assuming a raw
  TOC href string and a live locator's href share one format; the actual
  comparison (`matchChapter`) stays a small, pure, plain-`String`-based
  function so it remains unit-testable in a plain JVM test, mirroring
  `core.input`'s `resolveInputSources`/`isGamepadSource` precedent.
- Assessed and reported the plan's own stated fallback ("last same-resource
  TOC entry at or before the current position") before implementing it, per
  that task's explicit instruction: no available Readium/Link data can
  establish a within-resource position to compare against without parsing
  the resource's own HTML (out of scope). Implemented the smallest honest
  fallback instead — prefer the same-resource entry with no fragment of its
  own, else the first same-resource entry in TOC order — documented in
  `PHASE_2_PLAN.md`'s 2B.1 implementation record and in `matchChapter`'s own
  doc comment (`core.reader.EpubReader.kt`).
- Found and fixed a real regression during this same pass (not merely
  documented as a risk): the first current-chapter indicator design
  concatenated a checkmark into the title string, which broke
  `NavigationSmokeTest.originalEpubOpensAndOffersTypographyAndChapters`'s
  exact-text chapter lookup. Fixed by rendering the checkmark as a sibling
  `Text` node instead, restoring exact-text matchability everywhere;
  confirmed by re-running the full instrumented package (54/54).
- Recorded new automated evidence: `EpubChapterMatchTest` (11 JVM tests) and
  `EpubChapterHighlightTest` (2 instrumented tests, API 35), plus a clean
  full `connectedDebugAndroidTest` run and full local Gradle gate.
- 2B.1 remains implemented, not yet reviewed by Codex, not merged. 2B.2
  (bookmarks)/2B.3 (search)/2B.4 (custom fonts) are untouched.

## Post-merge maintenance: EpubRecreationTest Back-semantics test debt (2026-09-26)

- Recorded Phase 2A.1 as **accepted and merged to `main`** (PR #5, squash
  commit `44c8f10600f93f875a3cfb685c27f47c25929c29`), following final Codex
  confirmation after the R3 cleanup below, in `PHASE_2_PLAN.md`, `ROADMAP.md`
  and `VALIDATION.md`.
- Fixed the stale Back-semantics assumption in `EpubRecreationTest.kt`'s
  `readerUiStateSurvivesRecreationAndAppliedAppearanceReloads` test on branch
  `maintenance/epub-recreation-back-test`: it pressed `KEYCODE_BACK` while
  chrome was visible, expecting chrome to hide and the reader to stay open — a
  pre-[ADR-0023](adr/0023-reader-chrome-back-semantics.md) assumption that
  Phase 2A's accepted Back contract (visible chrome + Back → exit) had already
  made stale, previously recorded as out-of-scope test debt in the R3 cleanup
  below. Replaced with a `KEYCODE_MENU` press (`ShelfCommand.OPEN_MENU`),
  matching the existing chrome-toggle pattern in `NavigationSmokeTest.kt`. No
  production code changed; Back's reveal-then-exit contract is unchanged.
- Reclassified this issue in `VALIDATION.md`/`PHASE_2_PLAN.md` as test debt
  from Phase 2A's Back-contract change, now corrected — not an open item, and
  never a Phase 2A.1 production defect.

## Phase 2A.1 R3 cleanup (2026-09-25)

- Closed all four non-blocking findings from a second independent Codex
  review (PASS WITH NON-BLOCKING FINDINGS): extracted a pure, unit-testable
  `resolveInputSources`/`isGamepadSource` helper pair so source-precedence has
  a deterministic JVM regression test (the prior instrumented test used a
  nonexistent device ID and never actually exercised a real hybrid device's
  aggregate); added an explicit `CONTROLLER → Escape → KEYBOARD` transition
  step to the fixed-reader modality test, replacing a redundant no-op step;
  corrected `PHASE_2_PLAN.md`'s stale status line; and corrected an inaccurate
  historical claim, repeated in `PHASE_2_PLAN.md`, `VALIDATION.md` and
  `docs/design/INPUT_SYSTEM.md` §10, that raw system Back "fell through to an
  unguarded KEYBOARD default" — re-verified against the actual pre-remediation
  commit, `InputMapper` mapped raw Back to no command in both the original
  implementation and the first fix, so it never entered the affected branch
  in either version; the real defect was Escape/gamepad B being wrongly
  excluded from the modality update.
- `EpubRecreationTest.kt` remains untouched, as instructed — separate,
  pre-existing Phase 2A test debt, deferred to a follow-up after 2A.1 merges.
- The only production change (the helper extraction) is a pure refactor with
  identical logic; no RP5 re-certification was required or performed.
- 2A.1 remains implemented, not yet re-confirmed by Codex, not merged.

## Phase 2A.1 modality-tracking remediation (2026-09-25)

- Corrected `PHASE_2_PLAN.md`, `VALIDATION.md` and `docs/design/
  INPUT_SYSTEM.md` §10 after an independent Codex review found the initial
  2A.1 implementation's Back/modality fix itself incorrect: it filtered on
  the *resolved semantic command* (`ShelfCommand.BACK`), which suppressed
  legitimate Escape/gamepad-B modality updates. Raw system Back maps to no
  `ShelfCommand` at all (`InputMapper` returns `null` for `InputKey.BACK`), so
  it never entered that semantic-command branch in the first place, in either
  the original implementation or this fix — the defect was Escape and
  gamepad B, which do produce `ShelfCommand.BACK`, being wrongly excluded from
  their legitimate `KEYBOARD`/`CONTROLLER` modality updates by that guard.
- Narrowed the previously overstated "hints can never drift" wording to
  "validated against InputMapper for candidate bindings the catalog covers" —
  a future rebinding is not automatically discoverable unless also added to
  the candidate catalog.
- Also corrected the ambiguous-source classification to prefer the specific
  event's own reported source over a hybrid device's aggregate sources.
- Recorded new automated evidence: `InputModalityClassificationTest`
  (instrumented, 11 tests, real `KeyEvent`/`InputDevice` raw-classification
  coverage) and five new `NavigationSmokeTest` transition/RTL-hint cases
  (26 total), plus a real-hardware RP5 sequence confirming the fix in both
  directions (Escape/gamepad B now establish modality; raw Back does not).
- Recorded a separate, pre-existing, out-of-scope gap discovered while
  running the full test suite: `EpubRecreationTest.kt` still assumes the
  pre-ADR-0023 "Back hides chrome, stays open" contract and was never updated
  when Phase 2A's merge changed that behavior. Documented, not fixed, per
  this remediation's scope guard.
- 2A.1 remains implemented, not yet re-reviewed by Codex, not merged.

## Phase 2A.1: input-discovery/controller-hint implementation (2026-09-25)

- Recorded Phase 2A.1 as implemented in `PHASE_2_PLAN.md` and `VALIDATION.md`:
  `core.input.InputModality`/`InputHints` (self-verifying against the real
  `InputMapper` bindings) and `core.designsystem.InputKeycap`, integrated into
  both readers' chrome, with keyboard hints as first-class (not deferred).
- Recorded a real bug found via RP5 hardware testing and its fix: the system
  Back key was unconditionally classified as keyboard modality, flipping an
  active controller hint set on every Back press; Back is now excluded from
  modality updates in both readers.
- Recorded automated evidence (21/21 on the known-good API 35 emulator and on
  real RP5 hardware) and the explicit limits: no manual TalkBack walkthrough,
  no physical tablet keyboard available (keyboard validation used ADB-injected
  keys), and a known gap where EPUB edge-tap-only reading doesn't clear a
  stale controller/keyboard hint (only center-tap does).
- Added `docs/design/INPUT_SYSTEM.md` §10 documenting the hint-resolution
  architecture and the Back/modality interaction.
- Phase 2A.1 is implemented but not yet reviewed by Codex or merged.

## Phase 2A acceptance and Phase 2A.1 planning (2026-09-25)

- Recorded Phase 2A as accepted in `VALIDATION.md` and `PHASE_2_PLAN.md`,
  following independent Codex re-review (PASS WITH NON-BLOCKING FINDINGS):
  JVM tests 63/63, API 35 `NavigationSmokeTest` 16/16, a focused RP5
  (Android 13/API 33) instrumentation pass 6/6 with real-hardware EPUB/PDF/CBZ
  execution and on-device Back-semantics validation. Preserved the explicit
  limits: no manual TalkBack walkthrough, no overstated physical-button
  evidence beyond the owner's separate confirmation, the pre-existing API 37
  tooling gap, and PDF fidelity deferred to 2C.
- Recorded a pre-existing, non-blocking RP5 landscape import-dialog
  category-chip scrolling observation in `VALIDATION.md`; it predates Phase 2A
  and is not part of its acceptance criteria.
- Added Phase 2A.1 ("Input Discovery & Controller Hint Polish") to
  `PHASE_2_PLAN.md` as a planning-only increment between 2A and 2B, and a
  minimal corresponding reference in `ROADMAP.md`. No implementation code for
  2A.1 was added.

## Phase 2A: reader chrome/Back semantics (2026-09-25)

- Added `PHASE_2_PLAN.md` as the canonical Phase 2 planning location: reconciles
  Phase 1's acceptance against stale roadmap/feature wording, defines increments
  2A–2D with acceptance criteria, records the emulator/RP5/Galaxy-Tab-A device
  strategy, and records PDF-fidelity pipeline observations without acting on them.
- Fixed stale "work in progress" / "unfinished... pending" wording in
  `ROADMAP.md` (Phase 2 and Phase 3 sections) and `docs/features/READER.md`
  that contradicted the Phase 1 acceptance already recorded elsewhere in the
  same documents.
- Added ADR-0023 recording the reader chrome-visibility/Back-semantics decision
  and resolved the two matching "remains open" spots in
  `docs/design/READER_UX.md` and `docs/design/INPUT_SYSTEM.md`.
- Recorded Phase 2A validation evidence in `VALIDATION.md`, including an
  emulator tooling-gap result (not a regression) and explicitly-pending RP5/
  Galaxy Tab A physical validation.

## Tablet field-test and product-direction reconciliation (2026-09-25)

- Preserved qualitative Samsung Galaxy Tab A findings separately from formal metrics.
- Strengthened offline-complete architecture, source fidelity, shared CBR/CBZ reader
  semantics, Adapted PDF/local OCR, metadata/BYOK security and cover presentation.
- Recorded immersive-control rediscoverability, launcher fidelity and future local
  Completion Cards without changing Phase 1 status or implementing future work.
- Reconciled all 67 source sections, retained a cleaned historical research record,
  and removed the temporary v3 integration source after the audit passed.

## Phase 1 review remediation (2026-09-24)

- Corrected Phase 1 status (README, roadmap, Phase 1 plan, validation): 1A–1C implemented,
  1D partial; open software gaps listed alongside the physical-device gates.
- Named the three samples whose hashes were verified; the large CBZ was not hashed.
- Recorded the review fixes and their evidence by category (static/build, unit/regression,
  emulator, physical device), and the import ownership rule in the architecture.

## Generic ZIP library import decision (2026-09-24)

- Added future `ZipArchiveSource` to the shared staged-ingestion architecture.
- Distinguished a multi-publication generic ZIP from CBZ and ShelfOS backup semantics.
- Specified managed extraction, one-shot Source lifetime, hierarchy evidence,
  partial failure, duplicate reuse, nested-archive policy and archive safety.
- Sequenced the capability after bulk/folder and LibrarySource foundations and
  before richer migration adapters. No application code was implemented.

## Phase 1 implementation status (2026-09-24)

- Recorded Phase 1 as implemented and emulator-validated but not accepted (README,
  roadmap, Phase 1 plan, architecture, validation); physical-device gates stay open.
- Marked the Shelves rename and non-destructive private-copy removal as done.
- Documented descriptor-based archive access (shared storage cannot be reopened by path),
  startup maintenance, the error model and reader input/focus behavior.
- Recorded the measured debug APK size and a dependency-inventory check; no dependency changed.

## Accepted ingestion and organization decisions (2026-09-24)

- Added dedicated Data Ingestion, PDF Ingestion, Series, Shelves and Library Sources specifications.
- Added ADRs 0018–0022 and a requirement-by-requirement integration audit.
- Reconciled Collections → Shelves, pre-commit metadata → post-commit enrichment,
  Source/session identity, PDF mode terminology and safe managed-source behavior.
- Updated product/architecture/roadmap, Phase 1 status, feature/design and contributor guidance.
- Preserved Phase 0 validation as historical evidence and unfinished Phase 1 code unchanged.
- This reconciliation implements no application functionality; accepted targets remain phased.

## Phase 1 implementation plan (2026-09-23)

- Added PHASE_1_PLAN and proposed ADR-0017 for an expanded library/first-reader build.
- Recorded the change from original phase sequencing instead of silently moving scope.
- Distinguished reflowable typography from PDF reconstruction and image-page limits.
- Planned Manga PDF/CBZ direction, stable page identity and user preference precedence.
- Specified staged schemas, private fixtures, large-file handling and acceptance gates.
- Preserved the Phase 0 implementation status; no reader code or dependencies added.

## Phase 0 implementation revision

- Imported the supplied documentation bundle into canonical root/docs paths.
- Added ADR-0016 covering scope, identity, persistence, input and adaptive behavior.
- Reconciled the obsolete four-theme architecture list against accepted ADR-0012.
- Finalized MPL-2.0 adoption against the existing LICENSE and owner direction.
- Clarified that suggested publication schemas and metadata work are future phases.
- Added dependency review, validation instructions and actual prototype status.

## v4 — Library Foundation + Metadata Enrichment

This revision incorporates conclusions from the approved ShelfOS Classic Library mock.

Added:

- approved UI reference image
- detailed Classic Library implementation reference
- Metadata Enrichment specification
- metadata provider/provenance architecture
- expanded tablet/foldable detail-pane behavior
- compact phone details behavior
- explicit theme structural boundaries
- ADR-0013 metadata enrichment with provenance
- ADR-0014 themes preserve information architecture

Updated:

- Library
- Import
- Classic UI
- Themes
- Architecture
- Product
- Roadmap
- AGENTS.md
- README

Core decision:

> Themes are visual/interaction layers over one ShelfOS structure. Metadata enrichment is part of the library model, not a theme feature.


## v5 — Open Source, Licensing, and Release Governance

Added:

- `OPEN_SOURCE_MODEL.md`
- `LICENSING.md`
- `RELEASE_GOVERNANCE.md`
- root `CONTRIBUTING.md`
- root `SECURITY.md`
- draft brand policy
- pull-request template
- ADR-0015

Decisions:

- recommend MPL-2.0
- full source remains public
- Plus implementation may remain public
- no DRM arms race
- debug builds may simulate Plus
- official releases are controlled by signing/store credentials
- `main` should be protected and changed through reviewed pull requests
