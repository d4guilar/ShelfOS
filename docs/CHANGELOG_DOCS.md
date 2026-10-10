# Documentation Changelog

## Phase 3 reader documentation reconciled (2026-10-09)

- Reconciled documentation with the completed, owner-UAT-accepted Phase 3 reader work (merged to `main` as
  `6be8187`, `#29`, "fix: complete Phase 3 reader acceptance").
- `README.md`, `docs/PRODUCT.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md` and `AGENTS.md`: Phase 3 is now
  represented as complete/accepted (2026-10-09); current local reading support is stated conservatively as
  EPUB, PDF, CBZ and CBR; Phase 4 (Notes and Knowledge Layer) is pointed to as the next, not-started area.
- `docs/features/READER.md`, `docs/features/COMICS_MANGA.md`, `docs/features/IMPORT.md`,
  `docs/features/SERIES.md`, `docs/features/DATA_INGESTION.md`, `docs/features/PDF_INGESTION.md`,
  `docs/COMPETITIVE_FEATURES.md` and `docs/DEPENDENCIES.md`: removed stale "CBR is future work" and
  "Phase 1 in progress" wording; documented the accepted final AUTO semantics (one source page at a time;
  SPREAD is the explicit pairing mode) and the native libarchive CBR path.
- `docs/adr/0024-native-cbr-libarchive.md`: status updated from "3E-B through 3E-E NOT STARTED" to
  implemented/accepted, keeping the 3E-A checkpoint text as history.
- `docs/PHASE_3_IMPLEMENTATION_PLAN.md` (header, §1, §34, §35) and `docs/VALIDATION.md` (new
  "PHASE 3 — FINAL ACCEPTANCE AND OWNER UAT" entry): pending-review/UAT statements replaced with the
  accepted outcome; per-checkpoint "Phase 3 is NOT complete" lines retained as historical status records
  under an explicit supersession note; validation caveats (API 25 source-contract-only, no rerun of
  `FoldRenderGeometryUI`, known test-environment issues) preserved.
- Documentation-only pass: no production code, tests, Gradle/build configuration or dependencies changed.

## Post-Phase-2 EPUB XHTML regression recorded (2026-10-02)

- Added `docs/VALIDATION.md`'s "POST-PHASE-2 EPUB XHTML REGRESSION" entry for a maintenance fix on
  `fix/epub-xhtml-head-injection`. A real EPUB (Project Gutenberg's *Frankenstein*) rendered every chapter as
  an XML parse error, also reproduced independently with two minimal synthetic EPUBs. The rendition sanitizer
  round-tripped well-formed XHTML through an HTML parser/serializer. XML content documents are now parsed and
  serialized as XML, selected by declared media type.
- The Phase 2 acceptance record is unchanged. It passed against its then-current fixtures, none of which
  contained a void or self-closed XHTML element. The defect was not known during the final gate.
- No other document changed: no product, architecture or roadmap behavior changed.

## Phase 2D.4 / Phase 2 documented as complete (2026-10-02)

- Reconciled documentation with the completed, final Phase 2 acceptance pass on
  `phase-2/final-reader-acceptance` (candidate `2f2116a`). Full JVM 200/200, full
  connected 121/122 (one confirmed-transient infra flake under emulator CPU load,
  passed clean on isolated retry), full Gradle gate green, zero production code
  changed.
- Added `docs/PHASE_2D_IMPLEMENTATION_PLAN.md` §22: the full 2D.4 implementation
  record — owner-physical RP5 acceptance (all three outstanding 2D.3 questions,
  "All 3: Pass"), large synthetic PDF/CBZ fixture evidence, the
  `FixedReader`-level malformed-publication resilience tests, the twelve-clause
  final Phase 2 acceptance matrix (all PASS), the Part-Z device-targeting finding
  (`ANDROID_SERIAL` reliably restricts `connectedDebugAndroidTest` to one device
  even with a second attached, so no physical disconnect was needed), and the
  final decision: **PHASE 2 COMPLETE**.
- Added `docs/VALIDATION.md`'s "Phase 2D.4" entry with the validation-log summary
  of the same evidence.
- Updated `docs/PHASE_2_PLAN.md`'s 2D section and `docs/ROADMAP.md`'s Phase 2
  section to record 2D.1–2D.4 and Phase 2 as COMPLETE, without renumbering any
  phase or rewriting prior (2D.1–2D.3) historical records.
- Two new test files only (`SyntheticLargePdfAcceptanceTest.kt`,
  `MalformedFixedReaderResilienceTest.kt`, both `app/src/androidTest`); no
  production code, Room/schema, or dependency change in this slice.

## Localization foundation documented as complete (2026-09-30)

- Reconciled documentation with the completed, independently validated localization
  foundation on `feat/localization-foundation` (`ce32d5f` + `e6cc519`). QA verdict:
  **PASS WITH NON-BLOCKING FOLLOW-UPS**; no remaining HIGH or MEDIUM findings.
- Updated `docs/PRODUCT.md` §20: the four initial official app locales (System default,
  English, Español, Português (Brasil) as `pt-BR`) are implemented and shipped, English
  is the canonical/fallback language, System default is an empty override rather than a
  snapshot, the control lives in the existing Settings UI, and the covered
  production-UI surfaces are listed.
- Updated `docs/ARCHITECTURE.md` localization architecture with the actual implementation:
  AppCompat per-app locales (`setApplicationLocales`/`getApplicationLocales` +
  `LocaleListCompat`, AppCompat activity participation and AppCompat-compatible NoActionBar
  theme parent), locale-neutral language identity/persistence with no Room change, the
  domain/UI boundary (typed publication, font-import and import-progress reasons/states
  mapped to resources at the presentation boundary, including the API 24–32 rationale),
  locale-aware formatting on touched paths, and localized accessibility copy.
- Recorded explicitly, in both product and architecture docs, that ShelfOS does **not**
  declare `android:localeConfig` and is therefore not exposed in Android Settings → Apps →
  App language, so no system-level App Languages integration may be implied.
- Marked the localization foundation **COMPLETE** in `docs/ROADMAP.md` without creating or
  renumbering a phase; Phase 2C remains the next engineering phase.
- Recorded final validation evidence, the NavigationSmokeTest teardown flake triage
  (emulator/infra flake, not merge-blocking, not a localization defect) and the three
  remaining non-blocking follow-ups (API 24–32 device verification, external
  locale-change observation, optional translation polish) in `docs/VALIDATION.md`.
- Documentation only: no production code, test, resource, Gradle, Room/schema or
  dependency change.

## Interface localization direction + merge of latest main (2026-09-30)

- Merged `origin/main` (`6eea505`, Phase 2B.4 managed fonts and reading presentation)
  into `docs/theme-lineup` as merge commit `a85a9cd`, preserving the completed Phase
  2B.4 reader documentation (plan §12/§13, validation record, roadmap sequencing,
  managed-font architecture, source immutability, fallback) **and** the existing
  product/theme/supporter/community/cloud decisions. The only merge conflict was this
  changelog; it was resolved by keeping all three entries (2B.4 first, then the
  supporter-model and theme-lineup entries). No "ours"/"theirs" shortcut was used and no
  shared history was rewritten.
- Recorded the ShelfOS UI localization decision as a core, free requirement in
  `docs/PRODUCT.md` §20: the interface language is independent from publication
  language, and changing it never translates EPUB/PDF text, comic pages, publication
  content, user notes, titles, creators or imported metadata.
- Documented the intended initial official app locales — System default, English,
  Español — with an explicit choice honored independently of the Android system
  language, and the conceptual `Settings → General → Language` location without
  redesigning Settings.
- Added the localization resource boundary in `docs/ARCHITECTURE.md`: UI strings in
  Android string resources (never hard-coded, never in domain/data models),
  locale-aware formatting, localizable accessibility labels, no translation or
  language-detection ingestion/analysis, and Reading Presentation kept independent of
  app language, with right-to-left/future-locale compatibility framed as design
  discipline rather than a launch commitment.
- Recorded the theme-side requirement in `docs/design/THEMES.md`: theme-owned ShelfOS
  copy resolves through the app's localization resources, themes may style localized
  text but must not ship their own translation mechanism, and reference-theme
  terminology is not translated content.
- Added interface localization to the free/core baseline in `docs/features/PREMIUM.md`
  (explicitly never a Plus feature) and added localization guardrails for agents in
  `AGENTS.md`.
- Placed the near-term localization foundation in `docs/ROADMAP.md` as an explicitly
  labeled cross-cutting pre-launch requirement, without inventing a new numbered phase
  and without moving Phase 2C or any other phase.
- Reconciled `docs/COMPETITIVE_FEATURES.md`, which previously listed "translation" under
  adopted ideas and could have implied a planned publication-translation feature; it now
  states that publication-content translation is not planned and points to the interface
  localization decision.
- Documentation only: no production code, test, dependency, Room/schema or Gradle
  change, and no localization implementation.

## Phase 2B.4 documentation reconciliation (2026-09-30)

- Reconciled documentation with the completed Phase 2B.4 implementation on
  `phase-2/managed-fonts` (`93d0aaf`). Final independent QA was **PASS**; no
  additional production changes were required.
- Marked Phase 2B.4 complete in `PHASE_2_PLAN.md` (status paragraphs, capability
  table, and the 2B.4 proof-gate entry), `ROADMAP.md` (Phase 2 sequencing and the
  custom-font-architecture deliverable), and `VALIDATION.md` (new final entry).
- Added the canonical 2B.4 implementation record in `PHASE_2_PLAN.md` §12 covering
  managed font import/validation/app-private storage, the logical family id model
  (`builtin:serif`, `builtin:sans`, `user:<uuid>`), namespace-confined resource
  lookup, the Readium 3.4.0 managed-font → `@font-face` serving path, live catalog
  resolution and live font switching, the Publisher/ShelfOS presentation boundary,
  legacy preference compatibility and title/global precedence, source immutability,
  and built-in serif fallback — plus §13's out-of-scope confirmation.
- Recorded the managed-font repository boundary in `ARCHITECTURE.md` §10 and the
  EPUB reader capabilities in `features/READER.md`.
- Recorded final validation evidence and the non-blocking follow-up list in
  `VALIDATION.md`, explicitly stating that the 90-test connected suite ran after
  `ed38761` and that the narrow `93d0aaf` `ReaderPreferences` fix was validated
  independently with the 143-test JVM suite rather than a connected re-run.
- Documentation only: no production code, test, dependency, Room/schema or Gradle
  change was made, and no theme/monetization/cloud content was touched.

## Phase 2B.3 final review and RP5 acceptance (2026-09-28)

- Recorded the final targeted independent verdict for `e395ce7`: **PASS WITH
  NON-BLOCKING FINDINGS**, with no R1/R2/R3 findings.
- Recorded owner physical RP5 acceptance of Search reachability/activation,
  known-query entry, result navigation, correct-passage jump, zero-results,
  Search-dialog Back dismissal, hidden-chrome Back reveal-before-exit, and
  compact-layout/focus sanity on the exact latest installed build.
- Retained the two non-blocking R4 items: unbounded result accumulation is
  deferred to later performance closure, and the debug-only opener is not
  parallel-test-safe.
- Phase 2B.3 is now accepted and ready for PR/merge, but is not yet merged or
  pushed. No production code/tests, 2B.4, or 2C work was included.

## Phase 2B.3 R3 lifecycle-evidence remediation (2026-09-28)

- Recorded the first independent verdict accurately: CHANGES REQUIRED, no
  R1/R2 findings, and one R3 gap in active-search recreation/teardown evidence;
  production search architecture was confirmed correct.
- Documented the deterministic controlled-cursor tests: acquisition and
  suspension before recreation/exit, old-cursor closure, fresh acquisition and
  valid result after recreation, Activity destruction, and coordinator-close
  return only after cursor cleanup. Lifecycle instrumentation no longer claims
  that a closed cursor can later return a stale page. Release search behavior
  remains unchanged; direct session-close instrumentation is not claimed.
- Added the actual generation-guard race: A returns a page and pauses before
  publication, B becomes latest while waiting for A's cursor cleanup, A resumes
  and its page is rejected, then B acquires its cursor and publishes normally.
  Removing `id == requestId` made the test fail, confirming guard dependence.
  Documented why mutex serialization and cancellable `withContext(worker)` make
  the previously requested post-close stale-return sequence impossible.
- Recorded final focused evidence (search 7/7, recreation 1/1, navigation
  26/26, JVM search/presentation 11/11) and the 86-task offline Gradle gate,
  while retaining the preceding lifecycle pass's complete API 35 connected-
  suite result of 84/84; the full suite was not repeated for this behavior-
  preserving guard-only correction.
- Phase 2B.3 remains implemented/R3-remediated, pending targeted independent
  re-review and owner RP5 acceptance, not accepted/merged/pushed. Unbounded
  result accumulation remains a non-blocking R4 performance carry-forward with
  no current failure evidence; the debug-only process-global opener remains
  sequential-only and not parallel-test-safe. No 2B.4 or 2C scope was added.

## Phase 2B.3 EPUB publication search implementation (2026-09-28)

- Recorded 2B.2.2 as accepted and squash-merged via PR #11 at `ab612a46`, and
  moved 2B.3 from active implementation to implemented/pending independent
  review and owner acceptance. It is not described as accepted or merged.
- Documented the parser-attached Readium 3.4.0 `SearchService` boundary,
  ShelfOS value model, serialized iterator ownership/cleanup, rapid-query and
  recreation policy, local/read-only/non-persistent behavior, locator-based
  navigation, accessibility/focus behavior, and explicit exclusions.
- Recorded actual evidence: 10/10 focused JVM tests, 7/7 search instrumentation
  checks, required regressions 39/39, full connected suite 84/84, offline
  airplane-mode pass, compact/expanded viewports, 909 ms largest-fixture
  timing, unchanged source file, and the 86-task offline Gradle gate.
- Recorded that RP5 was unavailable, so no real-device or owner physical-input
  claim is made. No schema/dependency change and no 2B.4/2C implementation.

## Phase 2B.2.2 R3 remediation (2026-09-28)

- Closed all three R3 findings from an independent Codex review of
  `e1c92f0` (verdict CHANGES REQUIRED, **no R1/R2 findings — production
  implementation confirmed correct**), on the same branch
  (`phase-2/epub-reading-flow`), without resetting or dropping `e1c92f0`
  and **without touching any production source file**.
- **R3.1 (rapid-activation coverage claimed but not tested):** removed the
  misleading "rapid repeat tap" claim. Compose test input synchronizes around
  idleness, so two back-to-back `performClick()` calls could not faithfully
  represent two UI activations before the first state update; suspending the
  Compose test clock instead corrupted shared idling state. The final
  regression therefore exercises the underlying concurrency/persistence
  invariant through
  `concurrentAddBookmarkActivationsForTheSameLocationPersistOnlyOneBookmark`:
  a real settled UI Add captures the actual live locator, then two concurrent
  `addBookmark` requests for that exact locator result in exactly one
  persisted bookmark. Separate UI-state tests verify the persisted bookmark
  is reflected by the "Remove bookmark" control. No rapid UI double-click
  coverage is claimed.
- **R3.2 (missing persistence/state-transition regression coverage):**
  added `removingTheCurrentBookmarkPersistsThroughPublicationReopen`
  (a quick-toggle removal survives publication reopen, confirmed both via
  UI and directly against the repository) and
  `returningToAPreviouslyBookmarkedLocationShowsRemoveBookmarkAgain`
  (bookmark state is recomputed from the live locator on return to a
  previously bookmarked location, using the existing bookmark-jump
  mechanism, not chapter title/Location N as identity).
- **R3.3 (stale status wording):** corrected `PHASE_2_PLAN.md` (top status
  block, §2 goal section, 2B.2.2 section header — no longer "discovery/
  implementation-planning" now that implementation and review exist),
  `ROADMAP.md`, and `VALIDATION.md` to state the true current status: 2B.2.1
  accepted/merged via PR #10; 2B.2.2 implementation complete, CHANGES
  REQUIRED review with no R1/R2 findings, R3 remediation complete, not
  accepted, not merged, pending independent re-review and owner acceptance.
  Historical dated sections describing earlier stages were left untouched.
- Test names and comments now distinguish the UI toggle coverage from the
  repository-level concurrent-add persistence regression; no rapid UI tap
  coverage is claimed.
- Validated: `EpubBookmarkTest` 9/9 (twice, for stability),
  `EpubRecreationTest` 1/1, `NavigationSmokeTest` 26/26, full Gradle gate
  BUILD SUCCESSFUL. No schema/dependency change. Full connected-suite rerun
  and RP5 re-certification were not performed — not required since
  production code remained untouched (confirmed: the only file changed is
  `EpubBookmarkTest.kt`).
- 2B.2.2 remains implemented, R3 closed, pending independent re-review and
  owner acceptance — not accepted, not merged, not pushed. No 2B.3/2B.4
  work was added.

## Phase 2B.2.2 — EPUB reading flow discovery + bookmark toggle (2026-09-28)

- Reconciled stale documentation: `PHASE_2_PLAN.md`, `ROADMAP.md`, and
  `VALIDATION.md` all still described Phase 2B.2.1 as pending independent
  review/not merged, even though PR #10 (commit `af5410c`) had already
  merged it to `main`. Updated the top status blocks in all three files to
  state 2B.2.1 is accepted and merged, while leaving the dated, in-process
  review-history sections themselves untouched (they were accurate
  snapshots of the state at the time they were written).
- Turned the previously undefined "2B.2.2 — Reading Flow" placeholder into
  a concrete, bounded scope. Traced the real post-2B.2.1 reading/bookmark
  flow through the actual call paths (not filenames) and investigated,
  empirically, two friction candidates: whether keyboard/D-pad focus is
  lost after a Chapters/Bookmarks dialog dismiss (tested directly — no
  defect found) and whether the reader chrome has room for a new
  always-visible bookmark-state affordance (screenshotted on a portrait
  phone emulator and the connected RP5 — confirmed a portrait phone's four
  existing chrome buttons already leave no room for a fifth).
- Identified the one concrete, safely-fixable friction point: the
  current-position bookmark control became a permanently disabled dead end
  ("Bookmarked") once a bookmark existed, forcing users to locate that
  bookmark's row in the management list just to remove it.
- Implemented the REQUIRED fix: the control is now a real two-way toggle
  ("Add bookmark" ⟷ "Remove bookmark"), reusing the existing
  `sameEpubBookmarkLocation` match and `deleteBookmark` repository call —
  no schema change, no new `ShelfCommand`, no new UI surface, no new
  physical input binding.
- Explicitly deferred, with reasoning, three items that would need an owner
  product/visual decision rather than an engineering guess: an ambient
  "this page is bookmarked" indicator outside the dialog, a single-tap
  toggle without opening any dialog, and wiring
  `ShelfCommand.TOGGLE_BOOKMARK`/`InputKey.B` (already present in the
  mapper but never consumed, and only ever a *suggested* keyboard default
  in `INPUT_SYSTEM.md`) to any reader action. Also deferred: unifying touch
  gestures into the `ShelfCommand`/`InputMapper` layer, an open item
  ADR-0023 itself already flags as needing a future increment, not this
  small slice.
- Added `EpubBookmarkTest.addBookmarkControlTogglesToRemoveOnceBookmarkedAndBackAgain`
  and extended the recreation/reopen tests to assert the toggle's own state
  survives, not just the bookmark count. Updated existing assertions for
  the "Bookmarked" → "Remove bookmark" label change.
- Validated: `EpubBookmarkTest` 6/6, `BookmarkPersistenceTest` 7/7,
  `EpubBookmarkLocationInstrumentedTest` 6/6, `EpubChapterHighlightTest`
  3/3, `NavigationSmokeTest` 26/26, `EpubRecreationTest` 1/1, full
  `connectedDebugAndroidTest` 74/74. Full Gradle gate BUILD SUCCESSFUL. RP5:
  6/6 `EpubBookmarkTest` on the real device plus a direct screenshot
  confirming the new control renders legibly. No schema/dependency change.
- Updated `PHASE_2_PLAN.md` with the full discovery record (§9's 22-point
  structure) and `VALIDATION.md` with the implementation/validation record.
  2B.2.2 remains implemented, pending independent review, not merged, not
  pushed. No 2B.3/2B.4 work was added.

## Phase 2B.2.1 final R3 closure (2026-09-27)

- Closed the last open finding from the "PASS WITH NON-BLOCKING FINDINGS"
  review of `7f8eef9`: `epubPositions()` defaulted a missing
  `locations.progression` to `0.0`, indistinguishable from a genuine
  first-segment start. Extracted the catalog conversion into a pure
  `toEpubPositions(List<RawEpubPosition>)` that now drops any entry missing
  `position` or `progression` instead of defaulting it. Floor/segment-start
  semantics, valid-range handling, global one-based numbering, and the
  chapter/progress fallback are unchanged.
- Added 6 focused JVM tests (`EpubBookmarkPresentationTest`, 31/31 total):
  missing progression dropped, missing position dropped, a dropped entry
  cannot become Location 1 by default, another valid candidate still
  resolves when a malformed entry is present, no Location when every
  candidate is unusable, no renumbering of remaining global positions.
- Validated: `EpubBookmarkPresentationTest` 31/31,
  `EpubBookmarkLocationInstrumentedTest` 6/6, `EpubBookmarkTest` 5/5, full
  Gradle gate BUILD SUCCESSFUL. No schema/dependency change; no bookmark
  persistence/navigation/equivalence code touched.
- Updated `PHASE_2_PLAN.md` and `VALIDATION.md` with a concise closure
  record. 2B.2.2/2B.3/2B.4/Notes work: none added.

## Phase 2B.2.1 R2/R3 remediation (2026-09-27)

- Fixed two independent-Codex-review findings on `f43e70d` (2B.2.1's
  original implementation, below), on the same branch
  (`phase-2/bookmark-location-polish`), without resetting or dropping it.
  Verdict was CHANGES REQUIRED; 2B.2.1 remains implemented/remediated,
  pending final independent review.
- **R2 (location-resolution defect):** `resolveEpubLocation` picked the
  numerically *nearest* Readium position instead of the correct
  floor/segment-start match (Readium's catalog lists segment starts, not
  midpoints) — a bookmark at progression 0.7 between segment starts 0.4 and
  0.8 was wrongly resolved to 0.8. Fixed to select the greatest-progression
  segment start `<=` the bookmark's own progression. Progression is now
  validated conservatively (missing/negative/`>1`/NaN/infinite all yield no
  Location, never a clamp or a guess); invalid candidate positions are
  ignored. Global one-based numbering is unchanged.
- Replaced `EpubBookmarkPresentationTest`'s old "nearest"/"tied distance"
  tests (13 tests) with the full boundary matrix (25 tests): exact/mid
  boundaries, the critical 0.7 regression, end-of-resource behavior,
  invalid-progression rejection, unmatched href, cross-resource global
  numbering, invalid-candidate filtering, deterministic floor tie-breaking.
- Strengthened `EpubBookmarkLocationInstrumentedTest` (4 -> 6 tests) with
  two real-fixture proofs: a later-resource bookmark resolves with Location
  > 1 and no numbering reset; and, using a new fixture
  (`OriginalFixtures.epubWithLongChapter`, seeded high-entropy content that
  does not compress into a single position), the floor/segment-start
  boundary behavior holds against real Readium-computed segment starts, not
  only a synthetic list.
- **R3 (off-Main dispatch):** `EpubReaderViewModel`'s position-catalog fetch
  ran on `viewModelScope`'s own Main dispatcher. Fixed with
  `withContext(Dispatchers.IO) { session.epubPositions() }`, matching this
  codebase's existing dispatch convention; no new dispatcher abstraction.
- **R3 (cancellation):** replaced a bare `runCatching {...}.getOrDefault(...)`
  (which silently swallowed `CancellationException`) with explicit
  `catch (e: CancellationException) { throw e } catch (e: Exception) {
  emptyList() }` — cancellation now propagates; only a genuine failure
  degrades to no Location N.
- **R3 (documentation corrections):** archive-length wording now states the
  real fallback (`Resource.properties().archive?.entryLength` when present,
  else `Resource.length()`), re-verified via `javap` decompilation, rather
  than universally "compressed length"; Compose-blocking language no longer
  claims jank is "mathematically impossible," only that catalog computation
  is dispatched off Main; the one observed `EpubBookmarkTest` timeout is now
  recorded as "observed timing instability" with the full evidence trail
  preserved, not a settled "confirmed flake" conclusion; the RP5
  keyboard-focus finding is now recorded as the best-available evidence
  without overstating a confirmed launcher-specific cause.
- Validation: `EpubBookmarkPresentationTest` 25/25,
  `EpubBookmarkLocationInstrumentedTest` 6/6, `EpubBookmarkTest` 5/5,
  `BookmarkPersistenceTest` 7/7, `EpubChapterHighlightTest` 3/3,
  `NavigationSmokeTest` 26/26, `EpubRecreationTest` 1/1. A first full
  `connectedDebugAndroidTest` run stopped at 32/65 with two failures
  factually diagnosed as the same ADB transport-disconnection emulator
  infrastructure failure documented earlier in 2B.2's own history, not a
  code defect (neither failing test touches this remediation's changes);
  after the established recovery procedure, a complete re-run passed
  73/73, 0 failures. Full local Gradle gate: BUILD SUCCESSFUL, 86/86 tasks.
- Confirmed unchanged: bookmark Room schema, `Bookmark` model, stored
  locator format, navigation authority, DAO duplicate guard,
  `sameEpubBookmarkLocation`, bookmark ordering, `matchChapter`, source
  EPUB, resume model, and the accepted 2B.2 keyboard-focus behavior (not
  modified in this remediation, per its own scope).
- Updated `PHASE_2_PLAN.md` (status block, §2, and a new R2/R3 remediation
  record superseding the original 2B.2.1 record for current status) and
  `VALIDATION.md` (new remediation section, with the original section
  marked superseded rather than deleted).
- No 2B.2.2 (reading flow), 2B.3 (search), 2B.4 (custom fonts), Notes/
  highlights/export work was added. 2B.2.1 remains implemented/remediated,
  not yet re-reviewed by Codex, not merged, not pushed.

## Phase 2B.2.1 — bookmark location polish (2026-09-27)

- Implemented Phase 2B.2.1 on new branch `phase-2/bookmark-location-polish`,
  based on `main` at `b1ad0290fa6a4e7b5148a9cd068a4913ac616704` (2B.2
  accepted/merged via PR #9). **2B.2.1 is IMPLEMENTED, PENDING INDEPENDENT
  REVIEW — not accepted, not merged.** Presentation-only: 2B.2's persistence/
  migration architecture was not reopened, and no schema or dependency
  changed.
- Investigated the pinned Readium 3.4.0 publication-position API via `javap`
  decompilation (no source jar exists for this release): `EpubParser` wires
  a `PositionsService` (`EpubPositionsService`) onto every EPUB `Publication`
  by default, entirely offline (`Container`/`Resource`-based, never
  `HttpClient`), memoized after first computation, and structurally stable
  (the default `ArchiveEntryLength(1024)` strategy segments by each
  resource's archive-*stored* byte length — independent of font, line
  height, margins, orientation, and screen size). This directly addresses
  2B.2's own documented finding that a bookmark's *stored* locator does not
  reliably carry `position` at Add time: this slice never reads that field,
  resolving instead against the independent position catalog by
  resource+progression.
- Added `EpubPosition`, `resolveEpubLocation` (pure position-resolution,
  mirroring `matchChapter`'s honesty contract — never guesses when several
  candidates share a resource with no progression to disambiguate),
  `EpubBookmarkPresentation`, `bookmarkDisplayText`, and
  `bookmarkAccessibilityText` in `core.reader.EpubReader.kt`, plus
  `EpubSession.epubPositions()` (lazy, memoized, offline). Removed the old
  single-line `bookmarkLabel` function it replaces.
- New bookmark row wording: "Chapter 7 / Location 184 · 56% through book",
  degrading through "Location 184 · 56% through book" (no chapter), "Chapter
  7 / 56% through book" (no Location), down to "56% through book" (neither)
  — never a fabricated chapter or Location, and never "Page N" for a
  reflowable EPUB. Accessibility uses a separate single spoken sentence
  ("Chapter Seven, Location 184, 56 percent through book").
- `EpubReaderState` gained `epubPositions: List<EpubPosition>`, populated by
  `EpubReaderViewModel` in its own coroutine after the session is already
  open — never during reader startup, never a second navigator or a second
  `currentLocator` collector.
- Bookmark identity/equivalence (`sameEpubBookmarkLocation`, the DAO's
  exact-locator duplicate guard) is byte-for-byte unchanged; no fake
  position is ever persisted.
- Added `EpubBookmarkPresentationTest` (13 pure JVM tests: resolution
  disambiguation including a real floating-point tie, all four display/
  accessibility format branches, enriched-target equivalence, no-"Page"
  assertion, format consistency) and `EpubBookmarkLocationInstrumentedTest`
  (4 tests against the real `epubWithChapters` fixture: offline/stable/
  contiguous position catalog, first-chapter resolution, enriched-locator
  equivalence against real data, malformed-locator fallback) — the latter
  also empirically discovered and documented that this fixture's repetitive
  text compresses under 1024 bytes/chapter, yielding one position per
  chapter rather than several (the multi-position path is instead proven via
  the pure JVM test's synthetic data).
- Regression: `EpubBookmarkTest` (5/5), `BookmarkPersistenceTest` (7/7),
  `EpubChapterHighlightTest` (3/3), `NavigationSmokeTest` (26/26),
  `EpubRecreationTest` (1/1) all green; one
  `addListJumpAndDeleteBookmarksAcrossDialogReopens` timeout under emulator
  load was confirmed non-reproducible in isolation, consistent with prior
  documented flakiness, not a regression from this slice.
- **RP5**: production reader UI changed, so the Retroid Pocket 5 (`d8f7f1b6`)
  was exercised, not skipped. After waking/unlocking the device,
  `EpubBookmarkTest` scored 4/5 on real hardware (the one failure is an
  unrelated 2B.2 R3 keyboard-focus test, flagged as a device-specific
  finding rather than dropped silently). A direct `adb exec-out screencap`
  captured the Bookmarks dialog showing the new wording rendering legibly on
  the real device. ShelfOS was tested on a Retroid Pocket 5 — real hardware
  execution with a directly observed visual result, not a claim of manual
  physical controller button-pressing.
- Updated `PHASE_2_PLAN.md` (status block, §2, and a new 2B.2.1 record) and
  `VALIDATION.md` (full 2B.2.1 validation section, including the Readium
  investigation findings table).
- No 2B.2.2 (reading flow), 2B.3 (search), 2B.4 (custom fonts), Notes/
  highlights/export, or PDF/CBZ work was added. No new `ShelfCommand`/
  `TOGGLE_BOOKMARK` wiring. 2B.2.1 remains implemented, not yet reviewed by
  Codex, not merged, not pushed.

## Phase 2B.2 bookmark-state blocker remediation (2026-09-27)

- Corrected the earlier characterization of the post-Add "Bookmarked"
  timeout. Final-gate runs reproduced it in the full suite, isolation, and
  after emulator restart; it was a production live-state comparison defect,
  not a non-reproducible emulator flake.
- Recorded diagnostic evidence that Add fired once, the exact duplicate check
  found no row, Room inserted successfully, and the bookmark Flow reached the
  ViewModel and Compose. Readium then enriched the same location with title,
  position, and total progression, invalidating the UI's exact JSON equality.
- Documented the narrow remediation: live bookmark state now compares parsed
  stable locator evidence while the DAO's exact duplicate-storage guard and
  authoritative stored locator remain unchanged. Added six pure JVM
  equivalence cases; no schema or dependency changed.
- Recorded that stored bookmark locators do not reliably include Readium
  publication position at Add time. Phase 2B.2 therefore retains chapter plus
  percentage display and does not invent an EPUB page number or "Location N."
- Recorded post-fix evidence: the failing method passed twice,
  `EpubBookmarkTest` 5/5, persistence 7/7, navigation 26/26, recreation 1/1,
  chapter highlighting 3/3, full connected suite 67/67, and the 86-task
  offline Gradle gate with 93/93 JVM tests and zero lint issues.
- Installed the exact fixed `264d6f4` APK (SHA-256
  `cfa17e5d469452af44ac8644340f1ba183c40d29b81b473b0eaeaab27361b9df`)
  successfully on Retroid Pocket 5 `d8f7f1b6` using `adb install -r`, without
  uninstalling ShelfOS or clearing app data, and launched `.MainActivity`.
- Recorded the owner's **RP5 FIXED BUILD PASS**: Add remained recognized as
  "Bookmarked" after the reader settled; another location was not falsely
  matched; bookmark jump returned to and recognized the saved location;
  Delete restored Add; and physical B passed dialog-dismiss, hidden-chrome
  reveal, and visible-chrome exit behavior. The earlier `25612d5` physical
  pass is historical evidence only and is superseded for final readiness.
- Phase 2B.2 is now **IMPLEMENTED**, **INDEPENDENT REVIEW PASSED**,
  **AUTOMATED ACCEPTANCE PASSED**, **RP5 PHYSICAL ACCEPTANCE PASSED**, and
  **READY FOR PR**. It is not merged; nothing was pushed and no PR was opened.

## Phase 2B.2 pre-remediation final-gate and RP5 validation (2026-09-27)

- Confirmed the clean PR branch `phase-2/epub-bookmarks-ready` contains only
  `9d0d965` (Phase 2B.2) and `25612d5` (ordering remediation) above `main` at
  `ac9476d5`; unrelated future Notes commit `90622a6` is absent.
- Closed both review R3 findings in code/evidence: bookmark ordering now has
  the final `id ASC` tie-breaker with an equal-time regression test, and the
  Bookmarks UI has explicit-focus + real-key automated coverage for Add,
  jump, Delete, and Back.
- Built the exact `25612d5` debug APK and installed it on RP5 `d8f7f1b6`
  using `adb install -r`. Installation succeeded without uninstalling ShelfOS
  or clearing app data; the package's original `firstInstallTime` remained
  unchanged. The app launched successfully. The library was empty before the
  acceptance publication was added, so no pre-existing publication/progress
  row was available for visual migration verification.
- Recorded the owner's **RP5 PASS** using the handheld's physical controls:
  Bookmarks was reachable; Add created a bookmark; jump returned to the saved
  location and closed the dialog; Delete removed the correct bookmark and
  restored the empty state; physical B dismissed the dialog first, revealed
  hidden chrome, and exited only with chrome visible. This is owner physical
  evidence, not Codex-injected input.
- The static gate passed (86/86 tasks, 87/87 JVM tests, lint 0 errors/7
  warnings), and the ordering, navigation, recreation, and chapter-highlight
  focused classes passed. The final connected suite was **66/67**: the
  existing add/list bookmark flow timed out waiting for "Bookmarked" after
  Add. It failed again in isolation and after a fresh emulator restart, so
  the earlier "confirmed non-reproducible" characterization no longer holds.
- At that pre-remediation point, Phase 2B.2 was **not ready for PR** despite
  independent review and the owner's physical RP5 acceptance. The blocker is
  resolved in the current remediation record above. No Phase 2B.3, Phase
  2B.4, Notes, push, or PR action was included in that documentation pass.

## Phase 2B.2 R3 remediation (2026-09-26)

- Fixed two R3 findings from an independent Codex review of 2B.2 (below,
  verdict PASS WITH NON-BLOCKING FINDINGS, no R1/R2 findings), on the same
  branch (`phase-2/epub-bookmarks`), without resetting or dropping `e1ab272`.
- (R3.1) `LibraryDao.observeBookmarks`'s ordering gained a final deterministic
  tie-breaker: `progress ASC, createdAt ASC, id ASC` (previously stopped at
  `createdAt`). `progress`, `createdAt`, and locator authority are otherwise
  unchanged — this is ordering only. Added
  `BookmarkPersistenceTest.bookmarksWithIdenticalProgressAndCreatedAtStillSortDeterministicallyById`,
  which inserts three bookmarks sharing one progress and one createdAt, with
  explicit deterministic ids inserted out of id order (bypassing
  `RoomLibraryRepository.addBookmark`'s random UUID generation), and asserts
  the returned order matches the id tie-break exactly. All prior migration/
  FK/duplicate/isolation/delete tests are preserved unchanged.
- (R3.2) Added `EpubBookmarkTest.bookmarksDialogIsReachableAndOperableThroughKeyboardFocus`,
  proving the Bookmarks chrome entry and dialog controls are reachable and
  operable through real Compose focus (`RequestFocus`) plus real key events
  (`Key.Enter`, `KEYCODE_BACK`) rather than semantic clicks alone — following
  `NavigationSmokeTest`'s own established explicit-focus-then-key-press
  convention instead of counting an arbitrary, focus-order-dependent number
  of DPAD_RIGHT presses. Covers: opening Bookmarks, activating Add bookmark,
  jumping via a bookmark row, dismissing the dialog with the real hardware
  Back key without disturbing Phase 2A/ADR-0023 Back semantics, and Delete.
  No new `ShelfCommand` mapping was added.
- **RP5 physical controller acceptance remains pending before merge.** A
  physical RP5 was connected to the environment during this remediation
  pass, but no physical button-press interaction was performed; only
  emulator key-event injection was used, which per this task's own
  instruction is not a substitute for physical validation and is not claimed
  as one.
- Validation: `BookmarkPersistenceTest` 7/7, `EpubBookmarkTest` 5/5,
  `NavigationSmokeTest` 26/26, `EpubRecreationTest` 1/1, all on a freshly
  restarted `shelfos-phase0` emulator after a recurrence of the same ADB
  transport-disconnection infrastructure issue seen during the original
  2B.2 pass (resolved the same way: confirm no stale processes, restart ADB,
  relaunch the emulator, poll for boot completion). Because DAO ordering
  changed but the schema did not, a second full connected suite was not
  required and was not re-run. Full local Gradle gate: BUILD SUCCESSFUL,
  86/86 tasks. `git diff --check` clean; `git status --porcelain --
  app/schemas` clean (no new schema revision).
- Updated `PHASE_2_PLAN.md` and `VALIDATION.md` with the R3 remediation
  record. 2B.2 remains **IMPLEMENTED, PENDING INDEPENDENT REVIEW** — not
  accepted, not merged. No 2B.3/2B.4/Notes work was added.

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
- Recorded one `EpubBookmarkTest` timeout in this historical run, followed by
  passing isolated and full-suite reruns. Later final-gate validation
  reproduced it consistently and diagnosed a production live-locator
  equality defect; the current remediation record above supersedes the old
  flake interpretation. See `VALIDATION.md` for the full evidence trail.
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

## Supporter model, premium theme packs and post-launch sync direction (2026-09-28)

- Recorded the monetization philosophy — "Free users should not feel limited. Paid users
  should feel rewarded." — and the complete free core in `docs/features/PREMIUM.md`,
  including the explicit list of artificial limits ShelfOS will not introduce.
- Replaced the separate "Theme Pass" with **ShelfOS Plus** ($14.99 USD launch/founding
  lifetime direction) and recorded the **$4.99 theme packs** (Retro Systems 5, Pop & Print
  4, Dream Internet 4 = 13 themes), pack → Plus upgrade fairness, the lifetime promise
  boundary (which excludes future hosted/cloud services with recurring costs), Labs/early
  access, the no-marketplace/no-Store-tab rule and the Free-vs-Plus decision test.
- Reconciled premium theme names and directions in `docs/design/THEMES.md` (including
  Terminal, Pagestation, and the *PS2 WebXperience* / *OPL-Theme-PS2pops* references,
  which stay inspiration only), plus the semantic accent model, optional theme effects
  with the effects toggle and Reduced Motion rule, and community/custom themes as an
  explicitly post-launch, demand-gated, free-if-shipped, "themes are data" feature.
- Recorded Gallery / Grid / List as free organization views with per-category remembered
  views in `docs/features/LIBRARY.md` and `docs/design/VISUAL_IDENTITY.md`.
- Added the local-first synchronization guardrails to `docs/ARCHITECTURE.md`: no current
  cloud commitment, the sustained post-launch demand gate, reading/library/source-file
  separation, publication identity ≠ device-local source location, no raw Room database
  sync, deletion/conflict principles, BYOC-before-managed-cloud ordering, no ShelfOS
  account required for BYOC, accountless-vault research caveats, P2P as optional future
  research, backup ≠ sync, cloud separate from Lifetime Plus, and uncommitted cloud
  pricing.
- Reconciled `docs/PRODUCT.md`, `docs/ROADMAP.md`, `docs/design/DESIGN_SYSTEM.md`,
  `AGENTS.md` and ADR-0012 (a reconciliation note preserving the earlier premium backlog),
  then deleted the temporary product-decision handoff once every unique decision had a
  permanent home. No other accepted ADR contradicted these decisions.
- Documentation only: no sync, cloud, billing, account, migration, UI, test, dependency or
  theme implementation was added.

## Theme lineup finalized: five free themes (2026-09-28)

- Recorded the canonical free theme lineup in `docs/design/THEMES.md`: Classic,
  Dark, Pear Platinum, Pear Platinum Dark and Deckle, with each theme's designed
  default accent (ShelfOS Blue, ShelfOS Blue, restrained Classic Blue, cool
  restrained blue, Oxblood).
- Recorded the accent-personalization direction: each theme ships one designed
  default accent and may later offer a curated per-theme set that recolors
  semantic roles (accent, selection, focus, progress) instead of hard-coded
  values; an unrestricted RGB picker is explicitly not the initial design.
- Recorded the separation between app theme/accent and Reading Profile/page
  palette, including that Comics and Original PDFs stay source-faithful. Both
  this and the accent direction are future direction only — no theme, accent,
  reader or persistence code was changed.
- Recorded that all five themes are free and may not be assigned to Plus, and
  referenced the existing Classic concept image as visual reference only.
- Reconciled the previous working names in `AGENTS.md`, `docs/PRODUCT.md`,
  `docs/ARCHITECTURE.md`, `docs/design/VISUAL_IDENTITY.md`,
  `docs/features/PREMIUM.md`, `docs/ROADMAP.md`,
  `docs/SHELFOS_PRIVATE_LAUNCH_AND_AUDIENCE_STRATEGY.md`, ADR-0007 and ADR-0012.

## Phase 2B.3 final review and RP5 acceptance (2026-09-28)

- Recorded the final targeted independent verdict for `e395ce7`: **PASS WITH
  NON-BLOCKING FINDINGS**, with no R1/R2/R3 findings.
- Recorded owner physical RP5 acceptance of Search reachability/activation,
  known-query entry, result navigation, correct-passage jump, zero-results,
  Search-dialog Back dismissal, hidden-chrome Back reveal-before-exit, and
  compact-layout/focus sanity on the exact latest installed build.
- Retained the two non-blocking R4 items: unbounded result accumulation is
  deferred to later performance closure, and the debug-only opener is not
  parallel-test-safe.
- Phase 2B.3 is now accepted and ready for PR/merge, but is not yet merged or
  pushed. No production code/tests, 2B.4, or 2C work was included.

## Phase 2B.3 R3 lifecycle-evidence remediation (2026-09-28)

- Recorded the first independent verdict accurately: CHANGES REQUIRED, no
  R1/R2 findings, and one R3 gap in active-search recreation/teardown evidence;
  production search architecture was confirmed correct.
- Documented the deterministic controlled-cursor tests: acquisition and
  suspension before recreation/exit, old-cursor closure, fresh acquisition and
  valid result after recreation, Activity destruction, and coordinator-close
  return only after cursor cleanup. Lifecycle instrumentation no longer claims
  that a closed cursor can later return a stale page. Release search behavior
  remains unchanged; direct session-close instrumentation is not claimed.
- Added the actual generation-guard race: A returns a page and pauses before
  publication, B becomes latest while waiting for A's cursor cleanup, A resumes
  and its page is rejected, then B acquires its cursor and publishes normally.
  Removing `id == requestId` made the test fail, confirming guard dependence.
  Documented why mutex serialization and cancellable `withContext(worker)` make
  the previously requested post-close stale-return sequence impossible.
- Recorded final focused evidence (search 7/7, recreation 1/1, navigation
  26/26, JVM search/presentation 11/11) and the 86-task offline Gradle gate,
  while retaining the preceding lifecycle pass's complete API 35 connected-
  suite result of 84/84; the full suite was not repeated for this behavior-
  preserving guard-only correction.
- Phase 2B.3 remains implemented/R3-remediated, pending targeted independent
  re-review and owner RP5 acceptance, not accepted/merged/pushed. Unbounded
  result accumulation remains a non-blocking R4 performance carry-forward with
  no current failure evidence; the debug-only process-global opener remains
  sequential-only and not parallel-test-safe. No 2B.4 or 2C scope was added.

## Phase 2B.3 EPUB publication search implementation (2026-09-28)

- Recorded 2B.2.2 as accepted and squash-merged via PR #11 at `ab612a46`, and
  moved 2B.3 from active implementation to implemented/pending independent
  review and owner acceptance. It is not described as accepted or merged.
- Documented the parser-attached Readium 3.4.0 `SearchService` boundary,
  ShelfOS value model, serialized iterator ownership/cleanup, rapid-query and
  recreation policy, local/read-only/non-persistent behavior, locator-based
  navigation, accessibility/focus behavior, and explicit exclusions.
- Recorded actual evidence: 10/10 focused JVM tests, 7/7 search instrumentation
  checks, required regressions 39/39, full connected suite 84/84, offline
  airplane-mode pass, compact/expanded viewports, 909 ms largest-fixture
  timing, unchanged source file, and the 86-task offline Gradle gate.
- Recorded that RP5 was unavailable, so no real-device or owner physical-input
  claim is made. No schema/dependency change and no 2B.4/2C implementation.

## Phase 2B.2.2 R3 remediation (2026-09-28)

- Closed all three R3 findings from an independent Codex review of
  `e1c92f0` (verdict CHANGES REQUIRED, **no R1/R2 findings — production
  implementation confirmed correct**), on the same branch
  (`phase-2/epub-reading-flow`), without resetting or dropping `e1c92f0`
  and **without touching any production source file**.
- **R3.1 (rapid-activation coverage claimed but not tested):** removed the
  misleading "rapid repeat tap" claim. Compose test input synchronizes around
  idleness, so two back-to-back `performClick()` calls could not faithfully
  represent two UI activations before the first state update; suspending the
  Compose test clock instead corrupted shared idling state. The final
  regression therefore exercises the underlying concurrency/persistence
  invariant through
  `concurrentAddBookmarkActivationsForTheSameLocationPersistOnlyOneBookmark`:
  a real settled UI Add captures the actual live locator, then two concurrent
  `addBookmark` requests for that exact locator result in exactly one
  persisted bookmark. Separate UI-state tests verify the persisted bookmark
  is reflected by the "Remove bookmark" control. No rapid UI double-click
  coverage is claimed.
- **R3.2 (missing persistence/state-transition regression coverage):**
  added `removingTheCurrentBookmarkPersistsThroughPublicationReopen`
  (a quick-toggle removal survives publication reopen, confirmed both via
  UI and directly against the repository) and
  `returningToAPreviouslyBookmarkedLocationShowsRemoveBookmarkAgain`
  (bookmark state is recomputed from the live locator on return to a
  previously bookmarked location, using the existing bookmark-jump
  mechanism, not chapter title/Location N as identity).
- **R3.3 (stale status wording):** corrected `PHASE_2_PLAN.md` (top status
  block, §2 goal section, 2B.2.2 section header — no longer "discovery/
  implementation-planning" now that implementation and review exist),
  `ROADMAP.md`, and `VALIDATION.md` to state the true current status: 2B.2.1
  accepted/merged via PR #10; 2B.2.2 implementation complete, CHANGES
  REQUIRED review with no R1/R2 findings, R3 remediation complete, not
  accepted, not merged, pending independent re-review and owner acceptance.
  Historical dated sections describing earlier stages were left untouched.
- Test names and comments now distinguish the UI toggle coverage from the
  repository-level concurrent-add persistence regression; no rapid UI tap
  coverage is claimed.
- Validated: `EpubBookmarkTest` 9/9 (twice, for stability),
  `EpubRecreationTest` 1/1, `NavigationSmokeTest` 26/26, full Gradle gate
  BUILD SUCCESSFUL. No schema/dependency change. Full connected-suite rerun
  and RP5 re-certification were not performed — not required since
  production code remained untouched (confirmed: the only file changed is
  `EpubBookmarkTest.kt`).
- 2B.2.2 remains implemented, R3 closed, pending independent re-review and
  owner acceptance — not accepted, not merged, not pushed. No 2B.3/2B.4
  work was added.

## Phase 2B.2.2 — EPUB reading flow discovery + bookmark toggle (2026-09-28)

- Reconciled stale documentation: `PHASE_2_PLAN.md`, `ROADMAP.md`, and
  `VALIDATION.md` all still described Phase 2B.2.1 as pending independent
  review/not merged, even though PR #10 (commit `af5410c`) had already
  merged it to `main`. Updated the top status blocks in all three files to
  state 2B.2.1 is accepted and merged, while leaving the dated, in-process
  review-history sections themselves untouched (they were accurate
  snapshots of the state at the time they were written).
- Turned the previously undefined "2B.2.2 — Reading Flow" placeholder into
  a concrete, bounded scope. Traced the real post-2B.2.1 reading/bookmark
  flow through the actual call paths (not filenames) and investigated,
  empirically, two friction candidates: whether keyboard/D-pad focus is
  lost after a Chapters/Bookmarks dialog dismiss (tested directly — no
  defect found) and whether the reader chrome has room for a new
  always-visible bookmark-state affordance (screenshotted on a portrait
  phone emulator and the connected RP5 — confirmed a portrait phone's four
  existing chrome buttons already leave no room for a fifth).
- Identified the one concrete, safely-fixable friction point: the
  current-position bookmark control became a permanently disabled dead end
  ("Bookmarked") once a bookmark existed, forcing users to locate that
  bookmark's row in the management list just to remove it.
- Implemented the REQUIRED fix: the control is now a real two-way toggle
  ("Add bookmark" ⟷ "Remove bookmark"), reusing the existing
  `sameEpubBookmarkLocation` match and `deleteBookmark` repository call —
  no schema change, no new `ShelfCommand`, no new UI surface, no new
  physical input binding.
- Explicitly deferred, with reasoning, three items that would need an owner
  product/visual decision rather than an engineering guess: an ambient
  "this page is bookmarked" indicator outside the dialog, a single-tap
  toggle without opening any dialog, and wiring
  `ShelfCommand.TOGGLE_BOOKMARK`/`InputKey.B` (already present in the
  mapper but never consumed, and only ever a *suggested* keyboard default
  in `INPUT_SYSTEM.md`) to any reader action. Also deferred: unifying touch
  gestures into the `ShelfCommand`/`InputMapper` layer, an open item
  ADR-0023 itself already flags as needing a future increment, not this
  small slice.
- Added `EpubBookmarkTest.addBookmarkControlTogglesToRemoveOnceBookmarkedAndBackAgain`
  and extended the recreation/reopen tests to assert the toggle's own state
  survives, not just the bookmark count. Updated existing assertions for
  the "Bookmarked" → "Remove bookmark" label change.
- Validated: `EpubBookmarkTest` 6/6, `BookmarkPersistenceTest` 7/7,
  `EpubBookmarkLocationInstrumentedTest` 6/6, `EpubChapterHighlightTest`
  3/3, `NavigationSmokeTest` 26/26, `EpubRecreationTest` 1/1, full
  `connectedDebugAndroidTest` 74/74. Full Gradle gate BUILD SUCCESSFUL. RP5:
  6/6 `EpubBookmarkTest` on the real device plus a direct screenshot
  confirming the new control renders legibly. No schema/dependency change.
- Updated `PHASE_2_PLAN.md` with the full discovery record (§9's 22-point
  structure) and `VALIDATION.md` with the implementation/validation record.
  2B.2.2 remains implemented, pending independent review, not merged, not
  pushed. No 2B.3/2B.4 work was added.

## Phase 2B.2.1 final R3 closure (2026-09-27)

- Closed the last open finding from the "PASS WITH NON-BLOCKING FINDINGS"
  review of `7f8eef9`: `epubPositions()` defaulted a missing
  `locations.progression` to `0.0`, indistinguishable from a genuine
  first-segment start. Extracted the catalog conversion into a pure
  `toEpubPositions(List<RawEpubPosition>)` that now drops any entry missing
  `position` or `progression` instead of defaulting it. Floor/segment-start
  semantics, valid-range handling, global one-based numbering, and the
  chapter/progress fallback are unchanged.
- Added 6 focused JVM tests (`EpubBookmarkPresentationTest`, 31/31 total):
  missing progression dropped, missing position dropped, a dropped entry
  cannot become Location 1 by default, another valid candidate still
  resolves when a malformed entry is present, no Location when every
  candidate is unusable, no renumbering of remaining global positions.
- Validated: `EpubBookmarkPresentationTest` 31/31,
  `EpubBookmarkLocationInstrumentedTest` 6/6, `EpubBookmarkTest` 5/5, full
  Gradle gate BUILD SUCCESSFUL. No schema/dependency change; no bookmark
  persistence/navigation/equivalence code touched.
- Updated `PHASE_2_PLAN.md` and `VALIDATION.md` with a concise closure
  record. 2B.2.2/2B.3/2B.4/Notes work: none added.

## Phase 2B.2.1 R2/R3 remediation (2026-09-27)

- Fixed two independent-Codex-review findings on `f43e70d` (2B.2.1's
  original implementation, below), on the same branch
  (`phase-2/bookmark-location-polish`), without resetting or dropping it.
  Verdict was CHANGES REQUIRED; 2B.2.1 remains implemented/remediated,
  pending final independent review.
- **R2 (location-resolution defect):** `resolveEpubLocation` picked the
  numerically *nearest* Readium position instead of the correct
  floor/segment-start match (Readium's catalog lists segment starts, not
  midpoints) — a bookmark at progression 0.7 between segment starts 0.4 and
  0.8 was wrongly resolved to 0.8. Fixed to select the greatest-progression
  segment start `<=` the bookmark's own progression. Progression is now
  validated conservatively (missing/negative/`>1`/NaN/infinite all yield no
  Location, never a clamp or a guess); invalid candidate positions are
  ignored. Global one-based numbering is unchanged.
- Replaced `EpubBookmarkPresentationTest`'s old "nearest"/"tied distance"
  tests (13 tests) with the full boundary matrix (25 tests): exact/mid
  boundaries, the critical 0.7 regression, end-of-resource behavior,
  invalid-progression rejection, unmatched href, cross-resource global
  numbering, invalid-candidate filtering, deterministic floor tie-breaking.
- Strengthened `EpubBookmarkLocationInstrumentedTest` (4 -> 6 tests) with
  two real-fixture proofs: a later-resource bookmark resolves with Location
  > 1 and no numbering reset; and, using a new fixture
  (`OriginalFixtures.epubWithLongChapter`, seeded high-entropy content that
  does not compress into a single position), the floor/segment-start
  boundary behavior holds against real Readium-computed segment starts, not
  only a synthetic list.
- **R3 (off-Main dispatch):** `EpubReaderViewModel`'s position-catalog fetch
  ran on `viewModelScope`'s own Main dispatcher. Fixed with
  `withContext(Dispatchers.IO) { session.epubPositions() }`, matching this
  codebase's existing dispatch convention; no new dispatcher abstraction.
- **R3 (cancellation):** replaced a bare `runCatching {...}.getOrDefault(...)`
  (which silently swallowed `CancellationException`) with explicit
  `catch (e: CancellationException) { throw e } catch (e: Exception) {
  emptyList() }` — cancellation now propagates; only a genuine failure
  degrades to no Location N.
- **R3 (documentation corrections):** archive-length wording now states the
  real fallback (`Resource.properties().archive?.entryLength` when present,
  else `Resource.length()`), re-verified via `javap` decompilation, rather
  than universally "compressed length"; Compose-blocking language no longer
  claims jank is "mathematically impossible," only that catalog computation
  is dispatched off Main; the one observed `EpubBookmarkTest` timeout is now
  recorded as "observed timing instability" with the full evidence trail
  preserved, not a settled "confirmed flake" conclusion; the RP5
  keyboard-focus finding is now recorded as the best-available evidence
  without overstating a confirmed launcher-specific cause.
- Validation: `EpubBookmarkPresentationTest` 25/25,
  `EpubBookmarkLocationInstrumentedTest` 6/6, `EpubBookmarkTest` 5/5,
  `BookmarkPersistenceTest` 7/7, `EpubChapterHighlightTest` 3/3,
  `NavigationSmokeTest` 26/26, `EpubRecreationTest` 1/1. A first full
  `connectedDebugAndroidTest` run stopped at 32/65 with two failures
  factually diagnosed as the same ADB transport-disconnection emulator
  infrastructure failure documented earlier in 2B.2's own history, not a
  code defect (neither failing test touches this remediation's changes);
  after the established recovery procedure, a complete re-run passed
  73/73, 0 failures. Full local Gradle gate: BUILD SUCCESSFUL, 86/86 tasks.
- Confirmed unchanged: bookmark Room schema, `Bookmark` model, stored
  locator format, navigation authority, DAO duplicate guard,
  `sameEpubBookmarkLocation`, bookmark ordering, `matchChapter`, source
  EPUB, resume model, and the accepted 2B.2 keyboard-focus behavior (not
  modified in this remediation, per its own scope).
- Updated `PHASE_2_PLAN.md` (status block, §2, and a new R2/R3 remediation
  record superseding the original 2B.2.1 record for current status) and
  `VALIDATION.md` (new remediation section, with the original section
  marked superseded rather than deleted).
- No 2B.2.2 (reading flow), 2B.3 (search), 2B.4 (custom fonts), Notes/
  highlights/export work was added. 2B.2.1 remains implemented/remediated,
  not yet re-reviewed by Codex, not merged, not pushed.

## Phase 2B.2.1 — bookmark location polish (2026-09-27)

- Implemented Phase 2B.2.1 on new branch `phase-2/bookmark-location-polish`,
  based on `main` at `b1ad0290fa6a4e7b5148a9cd068a4913ac616704` (2B.2
  accepted/merged via PR #9). **2B.2.1 is IMPLEMENTED, PENDING INDEPENDENT
  REVIEW — not accepted, not merged.** Presentation-only: 2B.2's persistence/
  migration architecture was not reopened, and no schema or dependency
  changed.
- Investigated the pinned Readium 3.4.0 publication-position API via `javap`
  decompilation (no source jar exists for this release): `EpubParser` wires
  a `PositionsService` (`EpubPositionsService`) onto every EPUB `Publication`
  by default, entirely offline (`Container`/`Resource`-based, never
  `HttpClient`), memoized after first computation, and structurally stable
  (the default `ArchiveEntryLength(1024)` strategy segments by each
  resource's archive-*stored* byte length — independent of font, line
  height, margins, orientation, and screen size). This directly addresses
  2B.2's own documented finding that a bookmark's *stored* locator does not
  reliably carry `position` at Add time: this slice never reads that field,
  resolving instead against the independent position catalog by
  resource+progression.
- Added `EpubPosition`, `resolveEpubLocation` (pure position-resolution,
  mirroring `matchChapter`'s honesty contract — never guesses when several
  candidates share a resource with no progression to disambiguate),
  `EpubBookmarkPresentation`, `bookmarkDisplayText`, and
  `bookmarkAccessibilityText` in `core.reader.EpubReader.kt`, plus
  `EpubSession.epubPositions()` (lazy, memoized, offline). Removed the old
  single-line `bookmarkLabel` function it replaces.
- New bookmark row wording: "Chapter 7 / Location 184 · 56% through book",
  degrading through "Location 184 · 56% through book" (no chapter), "Chapter
  7 / 56% through book" (no Location), down to "56% through book" (neither)
  — never a fabricated chapter or Location, and never "Page N" for a
  reflowable EPUB. Accessibility uses a separate single spoken sentence
  ("Chapter Seven, Location 184, 56 percent through book").
- `EpubReaderState` gained `epubPositions: List<EpubPosition>`, populated by
  `EpubReaderViewModel` in its own coroutine after the session is already
  open — never during reader startup, never a second navigator or a second
  `currentLocator` collector.
- Bookmark identity/equivalence (`sameEpubBookmarkLocation`, the DAO's
  exact-locator duplicate guard) is byte-for-byte unchanged; no fake
  position is ever persisted.
- Added `EpubBookmarkPresentationTest` (13 pure JVM tests: resolution
  disambiguation including a real floating-point tie, all four display/
  accessibility format branches, enriched-target equivalence, no-"Page"
  assertion, format consistency) and `EpubBookmarkLocationInstrumentedTest`
  (4 tests against the real `epubWithChapters` fixture: offline/stable/
  contiguous position catalog, first-chapter resolution, enriched-locator
  equivalence against real data, malformed-locator fallback) — the latter
  also empirically discovered and documented that this fixture's repetitive
  text compresses under 1024 bytes/chapter, yielding one position per
  chapter rather than several (the multi-position path is instead proven via
  the pure JVM test's synthetic data).
- Regression: `EpubBookmarkTest` (5/5), `BookmarkPersistenceTest` (7/7),
  `EpubChapterHighlightTest` (3/3), `NavigationSmokeTest` (26/26),
  `EpubRecreationTest` (1/1) all green; one
  `addListJumpAndDeleteBookmarksAcrossDialogReopens` timeout under emulator
  load was confirmed non-reproducible in isolation, consistent with prior
  documented flakiness, not a regression from this slice.
- **RP5**: production reader UI changed, so the Retroid Pocket 5 (`d8f7f1b6`)
  was exercised, not skipped. After waking/unlocking the device,
  `EpubBookmarkTest` scored 4/5 on real hardware (the one failure is an
  unrelated 2B.2 R3 keyboard-focus test, flagged as a device-specific
  finding rather than dropped silently). A direct `adb exec-out screencap`
  captured the Bookmarks dialog showing the new wording rendering legibly on
  the real device. ShelfOS was tested on a Retroid Pocket 5 — real hardware
  execution with a directly observed visual result, not a claim of manual
  physical controller button-pressing.
- Updated `PHASE_2_PLAN.md` (status block, §2, and a new 2B.2.1 record) and
  `VALIDATION.md` (full 2B.2.1 validation section, including the Readium
  investigation findings table).
- No 2B.2.2 (reading flow), 2B.3 (search), 2B.4 (custom fonts), Notes/
  highlights/export, or PDF/CBZ work was added. No new `ShelfCommand`/
  `TOGGLE_BOOKMARK` wiring. 2B.2.1 remains implemented, not yet reviewed by
  Codex, not merged, not pushed.

## Phase 2B.2 bookmark-state blocker remediation (2026-09-27)

- Corrected the earlier characterization of the post-Add "Bookmarked"
  timeout. Final-gate runs reproduced it in the full suite, isolation, and
  after emulator restart; it was a production live-state comparison defect,
  not a non-reproducible emulator flake.
- Recorded diagnostic evidence that Add fired once, the exact duplicate check
  found no row, Room inserted successfully, and the bookmark Flow reached the
  ViewModel and Compose. Readium then enriched the same location with title,
  position, and total progression, invalidating the UI's exact JSON equality.
- Documented the narrow remediation: live bookmark state now compares parsed
  stable locator evidence while the DAO's exact duplicate-storage guard and
  authoritative stored locator remain unchanged. Added six pure JVM
  equivalence cases; no schema or dependency changed.
- Recorded that stored bookmark locators do not reliably include Readium
  publication position at Add time. Phase 2B.2 therefore retains chapter plus
  percentage display and does not invent an EPUB page number or "Location N."
- Recorded post-fix evidence: the failing method passed twice,
  `EpubBookmarkTest` 5/5, persistence 7/7, navigation 26/26, recreation 1/1,
  chapter highlighting 3/3, full connected suite 67/67, and the 86-task
  offline Gradle gate with 93/93 JVM tests and zero lint issues.
- Installed the exact fixed `264d6f4` APK (SHA-256
  `cfa17e5d469452af44ac8644340f1ba183c40d29b81b473b0eaeaab27361b9df`)
  successfully on Retroid Pocket 5 `d8f7f1b6` using `adb install -r`, without
  uninstalling ShelfOS or clearing app data, and launched `.MainActivity`.
- Recorded the owner's **RP5 FIXED BUILD PASS**: Add remained recognized as
  "Bookmarked" after the reader settled; another location was not falsely
  matched; bookmark jump returned to and recognized the saved location;
  Delete restored Add; and physical B passed dialog-dismiss, hidden-chrome
  reveal, and visible-chrome exit behavior. The earlier `25612d5` physical
  pass is historical evidence only and is superseded for final readiness.
- Phase 2B.2 is now **IMPLEMENTED**, **INDEPENDENT REVIEW PASSED**,
  **AUTOMATED ACCEPTANCE PASSED**, **RP5 PHYSICAL ACCEPTANCE PASSED**, and
  **READY FOR PR**. It is not merged; nothing was pushed and no PR was opened.

## Phase 2B.2 pre-remediation final-gate and RP5 validation (2026-09-27)

- Confirmed the clean PR branch `phase-2/epub-bookmarks-ready` contains only
  `9d0d965` (Phase 2B.2) and `25612d5` (ordering remediation) above `main` at
  `ac9476d5`; unrelated future Notes commit `90622a6` is absent.
- Closed both review R3 findings in code/evidence: bookmark ordering now has
  the final `id ASC` tie-breaker with an equal-time regression test, and the
  Bookmarks UI has explicit-focus + real-key automated coverage for Add,
  jump, Delete, and Back.
- Built the exact `25612d5` debug APK and installed it on RP5 `d8f7f1b6`
  using `adb install -r`. Installation succeeded without uninstalling ShelfOS
  or clearing app data; the package's original `firstInstallTime` remained
  unchanged. The app launched successfully. The library was empty before the
  acceptance publication was added, so no pre-existing publication/progress
  row was available for visual migration verification.
- Recorded the owner's **RP5 PASS** using the handheld's physical controls:
  Bookmarks was reachable; Add created a bookmark; jump returned to the saved
  location and closed the dialog; Delete removed the correct bookmark and
  restored the empty state; physical B dismissed the dialog first, revealed
  hidden chrome, and exited only with chrome visible. This is owner physical
  evidence, not Codex-injected input.
- The static gate passed (86/86 tasks, 87/87 JVM tests, lint 0 errors/7
  warnings), and the ordering, navigation, recreation, and chapter-highlight
  focused classes passed. The final connected suite was **66/67**: the
  existing add/list bookmark flow timed out waiting for "Bookmarked" after
  Add. It failed again in isolation and after a fresh emulator restart, so
  the earlier "confirmed non-reproducible" characterization no longer holds.
- At that pre-remediation point, Phase 2B.2 was **not ready for PR** despite
  independent review and the owner's physical RP5 acceptance. The blocker is
  resolved in the current remediation record above. No Phase 2B.3, Phase
  2B.4, Notes, push, or PR action was included in that documentation pass.

## Phase 2B.2 R3 remediation (2026-09-26)

- Fixed two R3 findings from an independent Codex review of 2B.2 (below,
  verdict PASS WITH NON-BLOCKING FINDINGS, no R1/R2 findings), on the same
  branch (`phase-2/epub-bookmarks`), without resetting or dropping `e1ab272`.
- (R3.1) `LibraryDao.observeBookmarks`'s ordering gained a final deterministic
  tie-breaker: `progress ASC, createdAt ASC, id ASC` (previously stopped at
  `createdAt`). `progress`, `createdAt`, and locator authority are otherwise
  unchanged — this is ordering only. Added
  `BookmarkPersistenceTest.bookmarksWithIdenticalProgressAndCreatedAtStillSortDeterministicallyById`,
  which inserts three bookmarks sharing one progress and one createdAt, with
  explicit deterministic ids inserted out of id order (bypassing
  `RoomLibraryRepository.addBookmark`'s random UUID generation), and asserts
  the returned order matches the id tie-break exactly. All prior migration/
  FK/duplicate/isolation/delete tests are preserved unchanged.
- (R3.2) Added `EpubBookmarkTest.bookmarksDialogIsReachableAndOperableThroughKeyboardFocus`,
  proving the Bookmarks chrome entry and dialog controls are reachable and
  operable through real Compose focus (`RequestFocus`) plus real key events
  (`Key.Enter`, `KEYCODE_BACK`) rather than semantic clicks alone — following
  `NavigationSmokeTest`'s own established explicit-focus-then-key-press
  convention instead of counting an arbitrary, focus-order-dependent number
  of DPAD_RIGHT presses. Covers: opening Bookmarks, activating Add bookmark,
  jumping via a bookmark row, dismissing the dialog with the real hardware
  Back key without disturbing Phase 2A/ADR-0023 Back semantics, and Delete.
  No new `ShelfCommand` mapping was added.
- **RP5 physical controller acceptance remains pending before merge.** A
  physical RP5 was connected to the environment during this remediation
  pass, but no physical button-press interaction was performed; only
  emulator key-event injection was used, which per this task's own
  instruction is not a substitute for physical validation and is not claimed
  as one.
- Validation: `BookmarkPersistenceTest` 7/7, `EpubBookmarkTest` 5/5,
  `NavigationSmokeTest` 26/26, `EpubRecreationTest` 1/1, all on a freshly
  restarted `shelfos-phase0` emulator after a recurrence of the same ADB
  transport-disconnection infrastructure issue seen during the original
  2B.2 pass (resolved the same way: confirm no stale processes, restart ADB,
  relaunch the emulator, poll for boot completion). Because DAO ordering
  changed but the schema did not, a second full connected suite was not
  required and was not re-run. Full local Gradle gate: BUILD SUCCESSFUL,
  86/86 tasks. `git diff --check` clean; `git status --porcelain --
  app/schemas` clean (no new schema revision).
- Updated `PHASE_2_PLAN.md` and `VALIDATION.md` with the R3 remediation
  record. 2B.2 remains **IMPLEMENTED, PENDING INDEPENDENT REVIEW** — not
  accepted, not merged. No 2B.3/2B.4/Notes work was added.

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
- Recorded one `EpubBookmarkTest` timeout in this historical run, followed by
  passing isolated and full-suite reruns. Later final-gate validation
  reproduced it consistently and diagnosed a production live-locator
  equality defect; the current remediation record above supersedes the old
  flake interpretation. See `VALIDATION.md` for the full evidence trail.
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
