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
- theme infrastructure plus placeholder Classic / Dark / Retro Apple UI / Retro Apple UI Dark / Paper themes
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
integration polish. Unfinished application work exists; full build/device/license
and acceptance gates remain open. Phase 1 is not complete or production-ready.

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
| 1. Early import and first readers (Phase 1) | Basic single-file import, LibraryItem persistence, local metadata/category, source provenance, errors; Original PDF/CBZ and EPUB reading/resume | Current work in progress; validate before completion. Rename legacy navigation to Shelves and review owned-copy removal policy |
| 2. Multi-file foundation (next import increment) | One selection of many files, shared candidate/duplicate/error handling | No hundreds-of-single-import migration UX; adopt shared ingestion boundaries |
| 3. Bulk/folder and Source foundation | Recursive discovery, durable ImportSession/staging/review, process-death recovery, connected LibrarySource, health, safe manual rescan | Bounded batches, isolated corrupt files, no deletion on missing/partial scans; references default, explicit managed copies |
| 4. Series foundation | Series/Membership, manual creation, bulk/folder Import as Series, reviewable detection, natural/manual order, details, Series-level Continue Reading | Generic Books/Comics/Manga; optional virtual omnibus follows stable per-member resume and boundary recovery |
| 5. Shelf foundation | Manual Shelves, multi-membership, add/remove, pinning, import assignment and folder suggestions | Canonical Shelves terminology applies now; no Smart rule engine required |
| 6. PDF foundation hardening (Phase 2) | Original rendering, progress, bookmarks/search where available, basic PDF analysis | Original starts in Phase 1; basic analysis never waits for advanced reconstruction |
| 7. Advanced PDF ingestion (Phase 5) | Adapted, PublicationDocument, SourceMap, recommendations/per-title mode, cross-mode annotations and complex-layout limits | Original remains available; tested best-effort mapping and source preservation |
| 8. Advanced organization | Smart Shelves, explicit Source → Shelf automation, stronger Series detection, richer issue/annual handling, optional external metadata | Post-commit enrichment only; user decisions win; entitlement remains separately scoped |
| 9. Migration adapters | ShelfOS backup import, Calibre, OPDS, supported exported libraries, device Source reconnection | Shared pipeline, portable UUIDs/fingerprints; no private sandbox or DRM bypass |
| 10. Later resilience/intelligence | OCR, advanced changed-source/annotation reconciliation, optional scheduled scans, advanced Smart rules, specialized comics/manga metadata | No real-time watching assumption or early OCR dependency |

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
- cover override
- identifier detection
- Open Library provider
- Google Books fallback/provider
- confidence scoring
- candidate confirmation UI
- local cover/metadata cache
- Refresh metadata action

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
input are in the Phase 1 work in progress. This track retains chapter/search refinements,
bookmarks, broader reading controls and hardening beyond that first usable reader.
The original inventory below is retained for coverage; no reader milestone has completed full acceptance.

### Goal
ShelfOS becomes a useful everyday reader.

### Initial formats

- EPUB
- PDF

### Deliverables

- reader abstraction
- Readium integration where appropriate
- EPUB opening
- PDF opening
- current location persistence
- resume reading
- chapter navigation where available
- reader search where available
- pagination / scroll preferences
- typography controls for EPUB
- bookmarks
- reader chrome
- dark reading mode
- TTS research / initial implementation if practical
- custom font architecture
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

Scope note: basic CBZ reading and Manga RTL (including Manga PDFs) are in the Phase 1
work in progress. Advanced spreads, thumbnails, foldable pairing and broader comic/manga
polish remain here. The original inventory below is not a claim of completion.

### Goal
Make image-sequence media first-class.

### Deliverables

- CBZ parser
- image sequence reader
- Comics defaults
- Manga RTL defaults
- user-overridable reading direction
- fit page
- fit width
- single page
- spreads where appropriate
- page thumbnails
- progress persistence
- keyboard/gamepad paging
- foldable two-page/spread behavior
- hinge-aware gutter handling

### Done when

A user can comfortably read a CBZ comic or manga entirely with touch, keyboard, or controller.

---

## Phase 4 — Notes and Knowledge Layer

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
- Retro Apple UI light implementation
- Retro Apple UI dark implementation
- Paper / Vintage Library implementation
- Grid / Compact / List / Showcase view architecture
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
- permanent Premium unlock
- restore purchase behavior
- Premium feature registry
- Premium settings screen
- graceful offline entitlement behavior

### Candidate Premium features

- PageStation
- advanced themes
- advanced pen presets
- smart shelves
- advanced statistics
- deeper metadata tools
- advanced PDF reconstruction features
- enhanced comic/manga presentation

### Done when

Free ShelfOS remains excellent and Premium reliably restores on supported Google Play installations.

---

## Phase 8 — PageStation

### Goal
Ship ShelfOS's signature premium theme experience.

### Deliverables

- original boot animation
- optional original UI sounds
- custom library motion
- controller-friendly horizontal browsing
- custom focus states
- custom system labels
- custom loading interactions

### Legal rule

No PlayStation trademark, logo, button iconography, boot audio, copyrighted visuals, or copied interface assets.

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
sequenced above, not implemented:

- CBR
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
- optional WebDAV
- optional cloud sync
- theme SDK
- plugin architecture
- metadata provider extensions
- iOS / iPadOS
- SwiftUI
- iPhone Duo optimization
- Apple Pencil
