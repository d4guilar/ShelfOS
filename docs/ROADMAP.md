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
**Update (2026-10-15):** this milestone is now scheduled as
[Phase 4.5 — Metadata, Covers & Library Presentation](#phase-45--metadata-covers--library-presentation);
provider choices there are subject to the terms review in
[`PHASE_4_5_IMPLEMENTATION_PLAN.md`](PHASE_4_5_IMPLEMENTATION_PLAN.md) §14 (Google Books
is **rejected for Phase 4.5** unless explicit written Google permission resolves its usage
restrictions; Open Library is promising but conditional, production approval pending).

- metadata provenance model
- embedded metadata extraction
- manual metadata editor
- cover override (custom user cover from device)
- cover candidate picker ("Find cover online")
- identifier detection
- Open Library provider
- ~~Google Books fallback/provider~~ (rejected for Phase 4.5; see the plan §14, D2)
- confidence scoring
- candidate confirmation UI
- background automatic metadata enrichment after commit
- best suitable cover selected by default for confidently matched items
- local cover/metadata cache
- Refresh metadata action

Cover policy defaults live in [Metadata enrichment](features/METADATA_ENRICHMENT.md):
a user-selected cover always wins, a confident online cover may improve a weak
embedded/generated cover, and enrichment never blocks local import or reading.
Comic and Manga enrichment is required Phase 4.5 acceptance scope; specialist
Comic/Manga providers are replaceable architecture slots there, used only if their
terms are approved. Comics acceptance specifically requires an approved, integrated
issue-level online provider (preferred: Metron with per-user tokens, conditional on a
terms review); local extraction and local artwork alone do not satisfy it (see the Phase 4.5
plan).

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
finished: Phase 4 (Notes and Knowledge Layer) followed and is now COMPLETE (see its section below), and Phase 4.5 (Metadata, Covers & Library Presentation) is the next active milestone.

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

**Phase 4 is COMPLETE (2026-10-15):** the Phase 4F acceptance PR merged as PR #42 (`e869762dd56c55cd2ad7c67fa72cd87dc9e19c9b`). The next active milestone is [Phase 4.5](#phase-45--metadata-covers--library-presentation); Phase 5 has not started. The record below is retained as history.

4A through 4D are merged and implementation complete; 4D, the Room-v5 global Notes hub, merged in PR #38 at `90b9f1c7fe827c996247a60bcd8cbc224afc72d3`. Physical/manual acceptance is recorded under Phase 4F below. **4E — Portable Knowledge & Restore is IMPLEMENTED AND INDEPENDENTLY REVIEWED / ACCEPTED** (final independent Codex High verdict PASS; implementation findings M1–M5 CLOSED; accepted code HEAD `ecf6f621d24cbcb40c38f323ec9ed407d45e6314`): Markdown is human-readable export, while canonical ShelfOS JSON is the only versioned round-trip import/restore format. It restores unmatched groups as orphans, is strictly additive and never guesses publication identity or includes publication binaries. 4E is merged (PR #40). 4F is **ACCEPTED**: owner Samsung SM-T580 UAT and RP5 physical/controller UAT both PASS, and the final independent Codex High closure review returned PASS WITH NOTES (0 BLOCKER / 0 HIGH / 0 MEDIUM; LOW documentation findings resolved). Physical TalkBack remains an evidence gap, covered only by available semantic/accessibility automation. Room remains v5, with the legacy `bookmark` table retained through closure and deletion deferred to a future dedicated maintenance migration; no v6 in 4F. Phase 4 is **ACCEPTED** and will be **COMPLETE** on merge of the Phase 4F acceptance PR; Phase 5 has not started. Evidence is in [`VALIDATION.md`](VALIDATION.md) and the plan in [`PHASE_4_IMPLEMENTATION_PLAN.md`](PHASE_4_IMPLEMENTATION_PLAN.md).

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
- human-readable Markdown knowledge export
- canonical versioned ShelfOS JSON knowledge export/import/restore
- excerpt-to-note workflow
- dictionary/provider architecture research

### Done when

A student can highlight and annotate a supported publication, review that knowledge from
one central screen, export it as readable Markdown or canonical ShelfOS JSON, and safely
restore the JSON without publication-identity guessing.

---

## Phase 4.5 — Metadata, Covers & Library Presentation

### Current status

**Next active milestone (planning, 2026-10-15).** Phase 4 is complete. Phase 4.5 is
inserted between Phase 4 and Phase 5; Phase 5 remains Advanced PDF Ingestion and the
numbering of every later phase is unchanged. **4.5A.1 Local Cover Artwork (Room v6) is merged
(PR #44)** and **4.5A.2 Cover Presentation (Room v7) is merged (PR #45)**; their physical UAT is
part of 4.5G. **4.5B Metadata Core (Room v8, offline) is implemented on branch
`phase-4.5/4.5b-metadata-core` and under independent review; it is not accepted or merged** (plan
§35, [validation](VALIDATION.md)). No online enrichment is shipped and no other 4.5 slice is
implemented. The
implementation authority is [`PHASE_4_5_IMPLEMENTATION_PLAN.md`](PHASE_4_5_IMPLEMENTATION_PLAN.md);
behavior is owned by [Metadata enrichment](features/METADATA_ENRICHMENT.md).

### Goal

> **The interface is monochrome. The library is the color.**
>
> **The file is the source of truth; metadata turns the folder into a library.**

Turn local files into a recognizable, cover-first Library. This is not optional polish:
Library identity depends on real artwork and correct metadata. Local files stay
authoritative; online enrichment is optional, background, network-assisted convenience,
and import and reading never depend on a provider.

### Deliverables (slices)

- **4.5A Cover Presentation Foundation** — umbrella of two independent PRs, each with its
  own validation, Codex High review and merge. **4.5A.1 Local Cover Artwork** (Room v6):
  embedded EPUB cover, CBZ/CBR cover, PDF first-page preview, per-item bounded artwork
  storage (≈ 512 KiB per cover: small originals byte-exact within limits, larger ones as
  bounded derivatives of ≈ 1200 px that record the original dimensions; a low-storage write
  guard; **no automatic eviction**), a LOCAL/USER/ONLINE cover-slot schema (only LOCAL is
  written; one Room schema for fresh and migrated databases, with enums and repository
  validation instead of migration-only `CHECK`s), local cover lifecycle with **crash-safe
  durable attempt tracking** (parked after four attempts or two unexplained interruptions,
  whichever comes first), **unique immutable cover files** (the Room row is updated before
  the old file is deleted) and safe backfill (batched, one CBR extraction at a time),
  a minimal shared cover renderer using Crop to fill in the existing frames for the
  Library, Search, details, Notes and expand transition; **no networking**. **4.5A.2 Cover Presentation**
  (Room v7): display shape Original / 2:3 / 3:4 / 4:5 / 1:1 / Custom W:H (1:2 to 2:1, no
  Auto) with Crop to fill or Fit entire artwork, never stretched; global default (2:3 Crop;
  Documents Original) plus per-category overrides; Settings UI; **no networking**.
- **4.5B Metadata Core** — provenance/confidence, identifier detection, richer embedded
  metadata, provider-neutral interfaces, candidate model, enrichment coordinator, cache and
  network/privacy boundary **design**. 4.5B does not by itself authorize the `INTERNET`
  permission or any real provider HTTP call: the first network permission/HTTP arrives only
  in the first provider slice (4.5C/4.5D/4.5E) after that provider's own authorization gate
  and a manifest/privacy review.
- **4.5C Books** — modular multi-provider system ([`features/BOOK_PROVIDERS.md`](features/BOOK_PROVIDERS.md),
  ADR-0026 accepted; 4.5C.1 registry/policy/clustering merged, fakes only): registry, ISBN-first, metadata and covers; Open Library conditional
  (D1), Library of Congress conditional metadata-only; Google Books, OverDrive default,
  Gutendex and Goodreads wrappers rejected. Planned sub-slices 4.5C.1–4.5C.5. 4.5F.0 offline cover selection is merged (PR #49); 4.5D.0 offline Manga edition/volume matching is merged (PR #50; an early sub-slice of 4.5D; live Manga acceptance still needs an approved provider). 4.5C.P0 (2026-10-18) is a research-only Inventaire/Wikidata evaluation ([`research/PHASE_4_5_PROVIDER_EVALUATION.md`](research/PHASE_4_5_PROVIDER_EVALUATION.md)); no provider is approved and approval gates remain pending.
- **4.5D Manga** — volume-aware matching, ISBN/edition, volume-specific covers.
- **4.5E Comics** — Series + issue matching, UPC/ISBN, ComicInfo/MetronInfo evidence, issue
  covers, and an approved issue-level online provider (preferred: Metron with per-user
  tokens; conditional on terms review and owner authorization). 4.5E and 4.5G stay open if
  no provider is approved and integrated.
- **4.5F Enrichment UX** — "We think this is…", Find cover online, Choose from device,
  Refresh metadata, failure/offline states.
- **4.5G Acceptance** — Books, Comics and Manga matrices, wrong-match protection,
  cache/offline, outages, source immutability, privacy, provider/legal checklist,
  Samsung SM-T580 manual LAN UAT and RP5 controller UAT.

### Constraints

- User-selected covers and user edits always win; a wrong match is worse than no match.
- Cover precedence: user-selected > confident high-quality online > usable embedded >
  generated/fallback.
- No secret credentials in the APK; keyed providers only as user-supplied keys (BYOK); no
  provider is used before its terms are reviewed and approved.
- No accounts, sync, AI, scraping, Phase 5 PDF analysis or Phase 6 ink work.

### Done when

Books, Comics **and** Manga each receive confident metadata and correct
(edition/issue/volume-specific) covers, Documents stay conservative, the Library is visually
complete offline, and the plan's acceptance criteria (§30) pass with physical UAT. Phase 4.5
cannot close with Books only, and it cannot close with local-only Comics: Comics need an
approved, working issue-level online path, otherwise 4.5E and 4.5G remain open.

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
- permanent **ShelfOS Plus** lifetime unlock ($29.99 launch/founding direction)
- individual premium themes ($4.99 each) and four-theme packs ($11.99 each), with
  upgrade accounting toward Plus where platform billing capabilities allow
- restore purchase behavior
- Plus entitlement registry and settings surface
- graceful offline entitlement behavior

### Candidate Plus content

- the 16 launch premium themes in four packs of four (Retro Systems, Pop & Print,
  Dream Internet, Interface Futures)
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
- optional BYOK specialist metadata providers beyond Phase 4.5 (zero-setup default
  enrichment, cover presentation and provider-term-aware caching moved into Phase 4.5)
- local Completion Cards, system-share images and local completion history
- launcher icon density/adaptive/legacy fidelity validation
- iOS / iPadOS
- SwiftUI
- iPhone Duo optimization
- Apple Pencil
