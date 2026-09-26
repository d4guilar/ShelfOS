# Documentation Changelog

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
  legitimate Escape/gamepad-B modality updates without addressing the actual
  bug (raw system Back, which `InputMapper` maps to no command at all,
  falling through to an unguarded `KEYBOARD` default).
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
