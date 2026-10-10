# ShelfOS Roadmap

This roadmap is directional, not a promise of dates.

The goal is to keep every major phase independently usable.

## Phase 0 — Foundation

### Current status

Phase 0 foundation is implemented and validated in the local emulator acceptance
environment. ShelfOS remains an **early development prototype**. See `VALIDATION.md`
for exact evidence and outstanding physical-device, API-range and accessibility QA.

- [x] Single Android module, Kotlin/Compose, stable version catalog and Gradle wrapper.
- [x] Application/package identity `com.d4guilar.shelfos`, minSdk 24.
- [x] Five Navigation Compose destinations, Library selected initially.
- [x] Adaptive bottom navigation / compact rail and expanded details.
- [x] Mock Library hierarchy, generated original covers, sample search and demo favorites.
- [x] Centralized Classic/Dark tokens and three unavailable free-theme registrations.
- [x] Room preference repository, generated schema support and saved theme selection.
- [x] Manual dependency injection and screen ViewModels with saved UI state.
- [x] Semantic input model and centralized focus/activation controls.
- [x] Unit/device test sources and GitHub Actions build/test/lint workflow.
- [x] Canonical documentation, MPL-2.0, contribution/security/PR guidance and ignores.
- [x] Successful clean Git checkout build, unit tests, lint and device-test compilation.
- [x] Launch, phone/tablet navigation and both themes verified on an Android API 35 emulator.
- [x] Relaunch persistence, basic keyboard/D-pad behavior and saved UI state verified within the documented test scope.

Mock presentation in Phase 0 does not complete the persisted Library features in
Phase 1. This is historical Phase 0 evidence, not validation of the unfinished
Phase 1 working tree. Scope and reconciled decisions are in ADR-0016.

### Prototype review follow-up (2026-09-23)

The initial hands-on review was positive overall. Opening/closing publication
details and switching tabs felt insufficiently smooth; motion polish remains
pending for a later build. The reviewed Phase 0 build did not open publications for reading.

- [ ] Review ES-DE or similar frontends as interaction references for transition
  timing, interruption behavior and focus continuity, within ShelfOS's existing
  reading-focused design and shared theme motion tokens.
- [ ] Refine forward/back detail transitions and tab/category changes; preserve
  selection, scroll position and keyboard/D-pad focus on return.
- [ ] Check rapid repeated input, Classic/Dark parity and reduced-motion behavior.
- [ ] Profile on a physical Android device to distinguish animation design issues
  from dropped frames or emulator overhead before choosing performance fixes.

This follow-up does not start Phase 1 or change the shared information architecture.

### Goal
ShelfOS boots, navigates, and has a stable engineering foundation.

### Deliverables

- Android Studio / Gradle project
- Kotlin
- Jetpack Compose
- application navigation
- Room setup
- basic dependency injection strategy
- initial design system
- theme infrastructure
- theme infrastructure plus placeholder Classic / Dark / Pear Platinum / Pear Platinum Dark / Deckle themes
- package architecture
- logging strategy
- basic test setup
- GitHub repository
- CI skeleton
- documentation imported into repository

### Done when

- app launches reliably
- navigation works
- theme can be switched
- selected theme persists
- sample screens exist
- no core architectural warnings remain undocumented

---

## Phase 1 — Library and first reading experience

### Implementation and decision update (2026-09-24)

The owner accepted the expanded [Phase 1 plan](PHASE_1_PLAN.md) after its initial
proposal. [ADR-0017](adr/0017-phase-one-reading-scope.md) records that scope:
durable Library, basic Original PDF/CBZ reading, reflowable EPUB typography and
integration polish. **Phase 1 is accepted (2026-09-24):** increments 1A–1D, including the
cover-expansion transition, 1D scroll-restoration/rapid-switch coverage, reader zoom/fit and
global Appearance persistence, are complete. They pass the automated build, unit, lint,
license-inventory, emulator and physical-device checks, including private-sample reading,
the fixes for the first Phase 1 review, and the acceptance-closure passes that followed
([validation](VALIDATION.md#phase-1-validation-2026-09-24)). Two items remain as documented,
non-blocking environment limitations rather than open gates: an API 24 emulator-specific
test flake and an API 37 automated-UI-test tooling gap, neither a ShelfOS defect. Phase 1 is
not yet a public release; see [Public Demo Readiness](VALIDATION.md) as the separate next
gate.

The newly accepted ingestion/organization architecture in ADRs 0018–0022 extends
that first slice without making advanced PDF reconstruction, Sources, Series or
Smart Shelves immediate implementation requirements. The ordered sequence below
governs this growth. Original PDF does not replace its fonts; advanced Adapted
presentation stays in Phase 5. Online enrichment cannot gate import or reading.

### Accepted ingestion and organization sequence

All rows are incomplete. The names below are incremental work tracks, not new
claims that existing phases shipped. Preserve the already approved first reader
scope; introduce schema and UI only with each corresponding implementation.

| Order / track | Scope | Gate / relationship |
| --- | --- | --- |
| 1. Early import and first readers (Phase 1) | Basic single-file import, LibraryItem persistence, local metadata/category, source provenance, errors; Original PDF/CBZ and EPUB reading/resume | **Accepted.** Emulator- and physical-device-validated, including the cover-expansion transition and 1D polish. Shelves naming and non-destructive private-copy removal are done. Two documented, non-blocking environment limitations remain (API 24 emulator flake, API 37 UI-test tooling gap) |
| 2. Multi-file foundation (next import increment) | One selection of many files, shared candidate/duplicate/error handling | No hundreds-of-single-import migration UX; adopt shared ingestion boundaries |
| 3. Bulk/folder and Source foundation | Recursive discovery, durable ImportSession/staging/review, process-death recovery, connected LibrarySource, health, safe manual rescan | Bounded batches, isolated corrupt files, no deletion on missing/partial scans; references default, explicit managed copies |
| 4. Generic ZIP archive source | One-shot library ZIP discovery, staging/review and safe extraction of accepted publications into managed storage | Follows durable staging and managed-Source ownership; distinct from CBZ and backup restore; original ZIP untouched; no implicit nested-archive recursion |
| 5. Series foundation | Series/Membership, manual creation, bulk/folder/archive Import as Series, reviewable detection, natural/manual order, details, Series-level Continue Reading | Generic Books/Comics/Manga; optional virtual omnibus follows stable per-member resume and boundary recovery |
| 6. Shelf foundation | Manual Shelves, multi-membership, add/remove, pinning, import assignment and folder/archive suggestions | Canonical Shelves terminology applies now; no Smart rule engine required |
| 7. PDF foundation hardening (Phase 2) | Original rendering, progress, bookmarks/search where available, basic PDF analysis | Original starts in Phase 1; basic analysis never waits for advanced reconstruction |
| 8. Advanced PDF ingestion (Phase 5) | Adapted, PublicationDocument, SourceMap, recommendations/per-title mode, cross-mode annotations and complex-layout limits | Original remains available; tested best-effort mapping and source preservation |
| 9. Advanced organization | Smart Shelves, explicit Source → Shelf automation, stronger Series detection, richer issue/annual handling, optional external metadata | Post-commit enrichment only; user decisions win; entitlement remains separately scoped |
| 10. Richer migration adapters | ShelfOS backup import, Calibre, OPDS, supported exported libraries, device Source reconnection | Shared pipeline, portable UUIDs/fingerprints; backup ZIP semantics remain distinct from generic ZIP import; no private sandbox or DRM bypass |
| 11. Later resilience/intelligence | OCR, advanced changed-source/annotation reconciliation, optional scheduled scans, advanced Smart rules, specialized comics/manga metadata | No real-time watching assumption or early OCR dependency |

Detailed contracts: [ingestion](features/DATA_INGESTION.md),
[PDF modes](features/PDF_INGESTION.md), [Series](features/SERIES.md),
[Shelves](features/SHELVES.md), [Sources](features/LIBRARY_SOURCES.md).
This sequencing follows the accepted specification while retaining the previously
authorized Original PDF first-reader increment; it is not a demand to finish all
bulk/organization work before any PDF can be opened.

### Goal
Turn files into ShelfOS library objects.

### Deliverables

- Android file picker
- Storage Access Framework integration
- persistent URI access where possible
- format detection
- `LibraryItem`
- Books / Comics / Manga / Documents classification
- Favorites
- Room schema
- cover grid/list
- Recently Added
- Continue Reading from persisted state once the first reader increment is validated
- manual title/author/category edits
- manual favorite toggle
- remove library item
- source-unavailable state
- adaptive cover grids
- baseline compact/expanded layouts
- preserve library state across resizing/fold changes

### Metadata enrichment milestone

After the local import model is stable:

Local extraction/provenance can grow incrementally. External providers belong to
the later advanced-organization/enrichment track above, after commit; this retained
inventory is not a requirement to add network APIs to the first reader build.

- metadata provenance model
- embedded metadata extraction
- manual metadata editor
- cover override (custom user cover from device)
- cover candidate picker ("Find cover online")
- identifier detection
- Open Library provider
- Google Books fallback/provider
- confidence scoring
- candidate confirmation UI
- background automatic metadata enrichment after commit
- best suitable cover selected by default for confidently matched items
- local cover/metadata cache
- Refresh metadata action

Cover policy defaults live in [Metadata enrichment](features/METADATA_ENRICHMENT.md):
a user-selected cover always wins, a confident online cover may improve a weak
embedded/generated cover, and enrichment never blocks local import or reading.
Specialist Comic/Manga metadata providers remain a later item in the
advanced-organization/enrichment track above.

### Visual implementation milestone

Implement the approved Classic library structure from:

- `docs/design/CLASSIC_LIBRARY_REFERENCE.md`
- `docs/design/references/ShelfOS_Classic_Library_v1.jpeg`

Includes:

- navigation rail / bottom navigation
- Continue Reading
- media categories
- cover grid
- focus state
- expanded detail pane
- compact details screen

### Done when

A user can import local files, classify them, see them persist across app restarts, favorite them, browse all five main library views, inspect publication details, and edit resolved metadata.

That is the Library milestone. Under the accepted expanded scope, Phase 1 additionally
requires offline EPUB/PDF/CBZ reading and resume, capability-appropriate typography,
RTL/LTR preferences, and the validation gates in `PHASE_1_PLAN.md`.

---

## Phase 2 — Reading

Planning note: basic EPUB/PDF opening, resume, supported typography and reader
input were pulled forward and are **implemented and accepted as part of Phase 1**
(ADR-0017, 2026-09-24). This track is the refinement/extension phase that builds on
that accepted foundation: chapter/search refinements, bookmarks, reader chrome and
Back/immersive-mode hardening, controller/keyboard input-discovery hints, broader
reading controls, and closure beyond the first usable reader. See
[`PHASE_2_PLAN.md`](PHASE_2_PLAN.md) for the current increment breakdown
(2A accepted and merged; 2A.1 input-discovery/controller-hint polish accepted
and merged via PR #5; 2B EPUB everyday-reading improvements sequenced as four
internal slices 2B.1–2B.4 — 2B.1 chapter-navigation polish accepted and merged
via PR #8, 2B.2 durable bookmarks accepted and merged via PR #9, 2B.2.1
bookmark-location polish accepted and merged via PR #10, and 2B.2.2 reading
flow accepted and merged via PR #11 at `ab612a46`; 2B.3 EPUB publication
search accepted and merged via PR #12; **2B.4 managed fonts and ShelfOS
reading presentation accepted and merged via PR #13**; **2C's PDF-fidelity
investigation is closed** (no production rendering change justified — see
`PHASE_2C_IMPLEMENTATION_PLAN.md` §22c); **2D (2D.1–2D.4) is COMPLETE and
Phase 2 is COMPLETE** (2026-10-02 — see `PHASE_2D_IMPLEMENTATION_PLAN.md`
§22 and `docs/VALIDATION.md`'s "Phase 2D.4" entry for the full final
acceptance record)) and
acceptance criteria. The
original inventory below is retained for coverage; items already accepted in
Phase 1 are marked accordingly.

### Goal
ShelfOS becomes a useful everyday reader.

### Initial formats

- EPUB
- PDF

### Deliverables

- reader abstraction — accepted in Phase 1
- Readium integration where appropriate — accepted in Phase 1 (EPUB)
- EPUB opening — accepted in Phase 1
- PDF opening — accepted in Phase 1 (Original mode)
- current location persistence — accepted in Phase 1
- resume reading — accepted in Phase 1
- chapter navigation where available
- reader search where available
- pagination / scroll preferences
- typography controls for EPUB
- bookmarks
- reader chrome — hardened in Phase 2A (immersive Back/rediscoverability)
- dark reading mode
- TTS research / initial implementation if practical
- custom font architecture — delivered for EPUB in Phase 2B.4 (user-imported managed
  fonts and the Publisher/ShelfOS reading-presentation boundary)
- adaptive reader layouts
- fold/unfold reading continuity

### Input

- touch
- keyboard basics
- gamepad basics

### Done when

A user can import an EPUB or PDF, read it, close the app, reopen it, and return to the correct location.

---

## Phase 3 — Comics and Manga

### Current status

**Phase 3 is complete and accepted (2026-10-09).** Slices 3A–3F landed as PRs
`#24`–`#28`, and final acceptance (final AUTO semantics, legacy-PDF hardening and
launcher icon, including the Samsung owner-UAT remediation) merged as PR `#29`
(`6be818749ba2ca1389c633847307ccb02d6874e0`, "fix: complete Phase 3 reader
acceptance"). Owner UAT ran on a Samsung Galaxy Tab A (SM-T580, Android 8.1 /
API 27, 2 GB RAM): a large real-world CBR read successfully, comic rendering,
navigation and fullscreen reading were accepted, the final AUTO semantics and
explicit SPREAD were accepted, the launcher icon was accepted, and performance on
this old device was judged very good for the intended reading workflow. Note that
API 27 does not exercise the special API 24/25 `PdfRenderer` compatibility path.
Full evidence and caveats (API 25 runtime unavailable — reviewed from AOSP source
contract only; `FoldRenderGeometryUI` not rerun after the final AUTO remediation;
no dedicated foldable hardware) are in `VALIDATION.md` and
`PHASE_3_IMPLEMENTATION_PLAN.md` §34–§35.

Scope note: basic CBZ reading and Manga RTL (including Manga PDFs) were pulled
forward and are **implemented and accepted as part of Phase 1** (ADR-0017,
2026-09-24). Advanced spreads, thumbnails, foldable-aware pairing and native CBR
support were completed here in Phase 3. The original inventory below is retained
for coverage; delivered items are marked accordingly. ShelfOS as a whole is not
finished: Phase 4 (Notes and Knowledge Layer) is next and has not started.

### Goal
Make image-sequence media first-class.

### Deliverables

- [x] CBZ parser (accepted in Phase 1)
- [x] image sequence reader (3A; shared `PageSource`/`ImagePageRenderer` pipeline)
- [x] Comics defaults
- [x] Manga RTL defaults
- [x] user-overridable reading direction
- [x] fit page
- [x] fit width
- [x] single page (SINGLE = exactly one source page)
- [x] spreads where appropriate (explicit SPREAD pairs normal pages; AUTO reads
  one source page at a time and shows genuine authored wide source pages whole —
  `PHASE_3_IMPLEMENTATION_PLAN.md` §35)
- [x] page thumbnails (3B)
- [x] progress persistence
- [x] keyboard/gamepad paging
- [x] foldable two-page/spread behavior (3D; validated with fold tests and
  emulator geometry — no dedicated foldable hardware yet)
- [x] hinge-aware gutter handling (3D)
- [x] high-priority CBR container support through the same image-sequence engine
  as CBZ (3E; native libarchive RAR4/RAR5 including solid archives, dependency
  and license review completed in ADR-0024; encrypted archives report a truthful
  error; no conversion or repacking)
- [x] source-faithful, resolution-aware rendering and high-resolution zoom/re-render (3A)
- [ ] immersive-chrome rediscoverability across touch, keyboard/gamepad and
  accessibility (reader chrome was hardened in Phase 2A; broader accessibility
  QA remains open)

### Done when

A user can comfortably read a CBZ comic or manga entirely with touch, keyboard, or controller.

**Accepted** via the final owner UAT (2026-10-09); see Current status above.

---

## Phase 4 — Notes and Knowledge Layer

### Current status

Not started (implementation). Phase 4 is the next numbered roadmap area following the
accepted Phase 3 (2026-10-09). Planning is recorded in
[`PHASE_4_IMPLEMENTATION_PLAN.md`](PHASE_4_IMPLEMENTATION_PLAN.md) (slices 4A–4F) and
[ADR-0025](adr/0025-annotation-anchor-and-knowledge-model.md) (Proposed); no slice is
authorized or implemented yet.

### Goal
ShelfOS becomes useful for active reading and study.

### Deliverables

- highlights
- text notes
- bookmark improvements
- annotation persistence
- global Notes screen
- annotations grouped by library item
- jump from annotation to source location
- annotation search
- basic export format
- excerpt-to-note workflow
- dictionary/provider architecture research

### Done when

A student can use ShelfOS to highlight and annotate a supported publication and review those notes from one central screen.

---

## Phase 5 — Advanced PDF ingestion

### Goal
Differentiate ShelfOS from ordinary viewers.

### Deliverables and research

Follow [PDF_INGESTION](features/PDF_INGESTION.md): text/reading-order extraction,
heading/chapter/paragraph reconstruction, repeated header/footer filtering, images,
captions/footnotes, column/layout confidence, structured PublicationDocument and
revision-aware SourceMap. Add Adapted/Original switching, per-title preference,
recommendations, typography and best-effort cross-mode position/annotation mapping.
Future global PDF preference follows usable per-title behavior. OCR is later.
That later OCR path must work locally by default and retain per-block confidence;
engine choice, model/language packaging and optional remote policy remain research.

### Constraint

Preserve the original PDF and always provide Original access. Do not hold bulk
imports behind expensive analysis. EPUB keeps its structured pathway; Comic/Manga
artwork is not replaced with text. DOCX is a separate future structured adapter.

### Done when

Compatible PDFs can be read in Adapted and Original modes without source changes,
with tested mapping limits, truthful confidence and preserved annotation anchors.
This is advanced work, not a prerequisite for basic PDF reading.

---

## Phase 6 — Ink and Study Mode

### Goal
Add serious tablet/stylus functionality.

### Deliverables

- stylus input abstraction
- ink annotations
- highlighter
- eraser
- lasso if practical
- pen presets
- page-relative coordinates
- Study Mode toolbar
- document-first annotation UX

### Done when

Stylus annotations survive orientation changes, reopening, and device layout changes without drifting.

---

## Phase 6.5 — Visual Identity / Free Themes

### Goal
Complete the public free visual system before monetized theme work.

### Deliverables

- ShelfOS Classic production polish
- ShelfOS Dark parity
- Pear Platinum implementation
- Pear Platinum Dark implementation
- Deckle implementation
- Gallery / Grid / List free view modes, with Compact and Showcase as additional later presentations
- shared theme token validation
- keyboard/gamepad focus QA across all free themes
- reduced-motion QA

### Done when

All five free themes feel complete and the library remains recognizable as ShelfOS across each visual personality.

---

## Phase 7 — Premium Infrastructure

### Goal
Introduce sustainable monetization without damaging free ShelfOS.

### Deliverables

- centralized entitlement system
- Google Play Billing
- permanent **ShelfOS Plus** lifetime unlock ($14.99 launch/founding direction)
- premium theme packs ($4.99 each) with upgrade accounting toward Plus where platform
  billing capabilities allow
- restore purchase behavior
- Plus entitlement registry and settings surface
- graceful offline entitlement behavior

### Candidate Plus content

- the 13 launch premium themes in three packs (Retro Systems, Pop & Print, Dream Internet)
- curated accent sets and optional theme effects
- local cosmetic personalization extras and Labs / early access

Non-cosmetic candidates remain unresolved rather than phase scope; see
[Premium](features/PREMIUM.md). Free ShelfOS stays complete in every case.

### Done when

Free ShelfOS remains excellent and Premium reliably restores on supported Google Play installations.

---

## Phase 8 — Signature theme experience (Pagestation)

Naming note: the theme is **Pagestation** (the earlier working name *Memory Orbit* is
retired) and it ships as one theme inside the **Retro Systems** pack rather than as a
standalone product. This phase covers the deeper presentation work a signature theme
needs; theme directions and pack contents live in [Themes](design/THEMES.md).

### Goal
Ship ShelfOS's signature premium theme experience.

### Deliverables

- original boot sequence (short, optional, toggleable and Reduced Motion aware)
- optional original UI sounds
- custom library motion
- controller-friendly horizontal browsing
- custom focus states
- custom system labels
- custom loading interactions

### Legal rule

No PlayStation trademark, logo, button iconography, boot audio, copyrighted visuals, or copied interface assets. *PS2 WebXperience* and *OPL-Theme-PS2pops* are atmosphere references only.

---

## Localization foundation — COMPLETE

Not a numbered phase: this is a cross-cutting foundation, and it is **complete** on
`feat/localization-foundation` (`ce32d5f` + `e6cc519`, QA PASS with non-blocking
follow-ups). It did not move Phase 2C or any other phase, and phase numbering is
unchanged.

### Scope

- ShelfOS-owned UI strings move to Android string resources; no hard-coded user-facing
  copy in UI, domain or data layers
- locale-aware formatting for dates, numbers, percentages, plurals and file sizes
- localizable accessibility labels and content descriptions
- a language preference with System default / English / Español / Português (Brasil)
  that changes only ShelfOS-owned UI text
- theme-owned ShelfOS copy resolving through the same resources

### Done when (met)

The interface runs in English, Español and Português (Brasil) with no hard-coded
English strings in current production UI, and changing the app language changes only
ShelfOS-owned UI text — never publication content or imported metadata.

Validation evidence and the remaining non-blocking follow-ups are recorded in
[`VALIDATION.md`](VALIDATION.md).

See [`PRODUCT.md`](PRODUCT.md#20-interface-language) and [`ARCHITECTURE.md`](ARCHITECTURE.md#localization-architecture).

---

## Phase 9 — Google Play Release

### Deliverables

- signing/release pipeline
- internal testing
- closed testing if required
- accessibility pass
- performance profiling
- large-library testing
- crash reporting decision
- privacy disclosures
- screenshots
- store listing
- app icon
- content rating
- production build

---


## Phase 8.5 — Adaptive / Foldable Differentiation

This work begins earlier at a baseline level; this phase represents the polished differentiated experience.

### Android

- book posture layouts
- tabletop layouts
- two-page book reader
- comic/manga fold-aware spreads
- document + notes side-by-side
- chapter/navigation supporting pane
- advanced continuity testing
- split-screen / resizable-window polish
- outer/cover-screen quick actions where appropriate

### Goal

ShelfOS should feel intentionally designed for foldables rather than merely stretched onto them.

---

## Reader & Library Hardening

Future, post-Phase-4 work recorded from accepted reader feedback and administrator
review. Not a numbered phase, not dated, and explicitly **not part of Phase 4**
(Notes and Knowledge Layer); no slice is authorized. Each area has a canonical
feature-specification home — the bullets below are the roadmap grouping, not the
full requirement text.

### Reader performance / scale

- multi-GB publication acceptance across CBZ, CBR and PDF — time to first readable
  page, memory while opening and during long sessions, random and sequential page
  navigation, thumbnail generation and navigation, zoom/high-resolution re-render,
  archive access behavior, recovery under Android memory pressure, reopening/
  resuming, large page counts, unusually large individual page images, and behavior
  on modest/older hardware — with the architectural expectation that the entire
  publication/archive is never required to be in memory to begin reading.
  Large-file support is a performance/reliability requirement, not a
  format-support checkbox; no maximum supported file size is committed.
  See [Reader](features/READER.md#reader-performance-and-scale-hardening-future)
  and [Comics and Manga](features/COMICS_MANGA.md#large-publication-scale-future).

### Reader interaction

- phone-first zoom gesture refinement: bounded UX/interaction research before any
  gesture is chosen, to reduce repeated pinch gestures on small phone displays
  while coexisting with ordinary pinch-to-zoom, page turning, edge taps,
  center-tap chrome, immersive/fullscreen reader behavior, keyboard/controller
  navigation, future fixed-layout annotation interactions and accessibility.
  Reader UX hardening, not a redesign of the fixed-page reader.
  See [Reader](features/READER.md#phone-first-zoom-and-gesture-refinement-future)
  and [Comics and Manga](features/COMICS_MANGA.md#phone-first-zoom-gestures-future).

### Library identity

- durable publication identity: recognize a previously known publication after
  removal → re-import and offer explicit restoration/relink of local history (read
  state, progress, annotations, bookmarks, metadata overrides, organizational
  state). No silent reattachment on filename, size or metadata resemblance alone;
  ambiguous matches require explicit user confirmation; a wrong match is worse
  than leaving history detached.
  See [Library Sources](features/LIBRARY_SOURCES.md#publication-identity-across-removal-and-re-import-future).

---

## Future Platform Track — iOS / iPadOS

Begins only after Android architecture/product stability.

### Likely work

- Swift / SwiftUI client
- Readium Swift evaluation/integration
- portable ShelfOS backup/library schema
- Files integration
- SwiftData/local persistence
- Apple Pencil
- widgets
- StoreKit
- iPhone Duo adaptive/foldable layouts
- iPad multitasking

---

## Future / Exploration

Exploratory scope or deferred implementation; accepted adapter directions are
sequenced above, not implemented. Community themes and cloud/sync are additionally
demand-gated (see `design/THEMES.md` and the local-first section in `ARCHITECTURE.md`);
nothing here is a committed delivery phase. (CBR is not in this list: it was
mandatory Phase 3 scope and is now implemented, accepted and merged — native
libarchive RAR reading, see `docs/adr/0024-native-cbr-libarchive.md` and the
Phase 3 section above.)

- DOCX
- TXT / Markdown
- OCR
- guided comic panels
- automatic panel detection
- reading statistics
- widgets
- optional scheduled LibrarySource rescans (manual rescan comes first)
- OPDS
- Calibre integration
- optional state sync, then bring-your-own-cloud providers (Google Drive / WebDAV /
  Nextcloud) — post-launch and gated on sustained cross-device demand
- optional managed ShelfOS Cloud — only after state sync and BYOC are validated, and never
  part of Lifetime Plus
- peer-to-peer device-to-device sync — optional future research, never the presumed primary
  architecture
- community/custom theme import — post-launch, gated on a very active community or
  substantial repeated demand, free if it ever ships
- theme SDK
- plugin architecture
- metadata provider extensions
- zero-setup default online enrichment plus optional BYOK specialist providers
- cover normalization/generated fallbacks and provider-term-aware caching
- local Completion Cards, system-share images and local completion history
- launcher icon density/adaptive/legacy fidelity validation
- iOS / iPadOS
- SwiftUI
- iPhone Duo optimization
- Apple Pencil
