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
Phase 1. No import, reader, metadata provider, annotation, billing or release work
has begun. Scope and reconciled documentation decisions are in ADR-0016.

### Prototype review follow-up (2026-09-23)

The initial hands-on review was positive overall. Opening/closing publication
details and switching tabs felt insufficiently smooth; motion polish remains
pending for a later build. The prototype does not yet open publications for reading.

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

## Phase 1 — Library

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
- Continue Reading placeholder
- manual title/author/category edits
- manual favorite toggle
- remove library item
- source-unavailable state
- adaptive cover grids
- baseline compact/expanded layouts
- preserve library state across resizing/fold changes

### Metadata enrichment milestone

After the local import model is stable:

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

---

## Phase 2 — Reading

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

## Phase 5 — Smart Import and Better PDFs

### Goal
Differentiate ShelfOS from ordinary viewers.

### Research areas

- PDF text extraction
- reading-order reconstruction
- repeated header/footer detection
- chapter/heading detection
- paragraph reconstruction
- column handling
- generated table of contents
- book-mode conversion

### Constraint

Preserve original PDF and always provide original-page access.

### Done when

Selected compatible PDFs can be presented in a substantially more comfortable reading mode without destructive conversion.

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
- smart collections
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

Not committed:

- CBR
- DOCX
- TXT / Markdown
- OCR
- guided comic panels
- automatic panel detection
- reading statistics
- widgets
- watch folders
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
