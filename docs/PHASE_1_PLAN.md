# Phase 1 plan: local library and first reading experience

Status: scope accepted after the 2026-09-23 proposal. As of 2026-09-24, **Phase 1 is
accepted**: increments 1A–1D (including the cover-expansion transition and the large-load,
compatibility, TalkBack and physical-controller items) are complete, and the automated,
emulator and physical-device checks recorded in
[VALIDATION](VALIDATION.md#phase-1-validation-2026-09-24) pass, including the fixes for the
first Phase 1 review and the full acceptance-closure passes that followed it. Two items
remain recorded as documented, non-blocking environment limitations rather than resolved
gates — an API 24 emulator-specific test flake and an API 37 automated-UI-test tooling gap,
neither a ShelfOS defect — per section 7 and the validation document. This document remains
the plan, not a completion report.

## 1. Outcome and scope

Import an owned EPUB, PDF or CBZ, organize it, read it offline, close ShelfOS,
and resume at the saved location. Books should have comfortable typography where
the format allows it. Manga should have right-to-left navigation by default,
with a per-title override. Classic and Dark remain the two implemented themes.

The owner requested reading customization and manga direction in the next build.
The accepted first-build scope is therefore an expanded Phase 1: retain the
library foundation and pull forward the minimum reader work from Phases 2 and 3.
This changes sequencing, not the product architecture. PDF text reconstruction
is explicitly outside this build. See [ADR-0017](adr/0017-phase-one-reading-scope.md)
for the scope conflict and accepted resolution. New ingestion/organization
contracts and later increments are sequenced in the [roadmap](ROADMAP.md#accepted-ingestion-and-organization-sequence).

The first implementation increment is still a usable local library. Deliver
the increments below separately; do not label a library-only build as a reader.

## 2. What customization can actually do

| Content | Planned controls | Limits in this build |
| --- | --- | --- |
| Reflowable EPUB | Font family/size, line spacing, margins, alignment, page/background colors, paginated/scroll presentation where supported | Preserve headings, emphasis and reading position; no arbitrary publication rewriting |
| Fixed-layout EPUB | Only capabilities verified in the reader evaluation | No promise of reflowable font controls; report unsupported layout if the first adapter cannot handle it |
| Book or Document PDF | Original pages, zoom, fit page/width, page navigation, saved position | Fonts and paragraph layout remain those authored in the PDF |
| Manga PDF | PDF controls plus RTL navigation, overridable to LTR | Do not mirror artwork, reverse the document's stored page list or replace lettering |
| Comic/Manga CBZ | Ordered image pages, fit/zoom, page navigation, LTR/RTL preference | Text inside page images is not editable typography |

**Dune's PDF font request is not fulfilled by ordinary PDF rendering.** Changing
that typography requires extracting text and reconstructing a reading layout;
scanned pages may require OCR. Text extraction alone does not establish correct
paragraphs, reading order or chapter structure. The supplied PDF has not been
assessed for extraction quality. A theme cannot safely promise to fix it.

Keep Original available. Accepted [Adapted PDF](features/PDF_INGESTION.md) direction
derives a structured PublicationDocument and SourceMap without altering the source.
This remains advanced Phase 5 work, not a requirement for the first reader build.
Use an original reflowable EPUB fixture to validate typography in this phase.

Technical references checked during planning:

- [Readium font customization](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/epub-fonts.md): font replacement targets reflowable EPUB rather than fixed-layout publications.
- [Readium navigator preferences](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/preferences/): preferences differ by navigator and publication capabilities.
- [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer): original-page rendering and file-descriptor requirements; evaluate compatibility before selecting an adapter.

These references inform the plan; they do not select a dependency version.

## 3. Architecture and data

Retain one `app` module, `com.d4guilar.shelfos`, manual AppContainer wiring,
ViewModels, Coroutines/StateFlow, Navigation Compose and Room. Target API 24
compatibility unless a separate documented decision establishes a necessary change.

| Area | Responsibility |
| --- | --- |
| `core.files` | SAF access, permission handling, bounded inspection, seekable-source access and managed temporary copies |
| `domain.importing` | Import validation, duplicate policy, classification and cancellation outcomes |
| `data.library` / `domain.library` | Persistent publication model, repository contracts, queries and edits |
| `core.database` | Room entities, transactions, migrations and exported schemas |
| `core.reader` | ShelfOS-owned capabilities, locators, sessions and format adapters |
| `data.preferences` | Global reading defaults and per-title overrides |
| `feature.importing` | Picker result, preview/correction, progress, errors and retry |
| `feature.reader` | Reader ViewModel, controls, lifecycle and semantic commands |
| Existing Library/Search/theme/input packages | Real data, resume actions, shared presentation and direction-aware input |

Create packages only when their increment needs code. Composables do not open
URIs, query DAOs or call reader SDKs directly. Keep engine-specific objects out
of LibraryItem and navigation arguments; pass stable item IDs between screens.

Add only tables serving the increment being implemented:

- Library item: stable generated ID; source URI; original display name; detected
  format/MIME; nullable byte size as a 64-bit value; title/creator and their
  provenance; category; favorite; local cover reference; availability; timestamps.
- Reading state when readers arrive: item ID, versioned format-specific locator,
  progress and last meaningful reading time. PDF/CBZ use stable page indices;
  EPUB uses a publication locator rather than a display page number.
- Reader preferences when controls arrive: persisted global defaults and nullable
  per-item overrides for supported controls. A direction override is distinct
  from the category default. Do not populate speculative annotation/Shelf tables.

Migrate from schema v1 without losing `appearance_preference`. Use separate,
tested migrations as increments add tables; never use destructive fallback.
Removing an entry must not delete source files by default, including managed
publications. Cache cleanup is separate from source deletion. Implemented: removal keeps
a private offline copy; Settings → Storage deletes unreferenced copies only after explicit
confirmation. Release URI grants only when no remaining ShelfOS item or active operation
requires them.

## 4. Import and large-file policy

Use the Android document picker and read grants; never assume a URI is a filesystem
path. Persist grants when available. If a provider cannot retain access, explain
the limitation and offer an explicit app-managed copy or cancel; do not advertise
durable access that will disappear at restart. Provider-backed remote content may
need a local copy for offline use; no automatic full-library downloads.

Inspect bounded headers/container structures, not just filename extensions.
Separate format detection from full readability validation. EPUB ZIPs must not
be mistaken for CBZs. Treat metadata as untrusted; manual corrections always win.
Start with filename fallback, supported embedded metadata and generated covers;
local first-page/embedded cover generation is best-effort and cannot block import.
Network metadata providers stay outside the first reading build.

Import runs off the main thread with cancellation and observable stage progress.
Use indeterminate progress when the provider cannot report byte totals. Rotation
keeps the operation attached to its state holder. The current single-file implementation may lose an unfinished import on process
death and require retry; record this limitation, not a library-scale guarantee. On the next
launch, startup maintenance deletes interrupted partial copies and releases read grants no
library item references before any new import begins; a completed but unconfirmed copy
appears under Settings → Storage as an unused private copy.
Before bulk/folder imports ship, persist ImportSession/staging checkpoints and
review decisions so restart resumes safely. Do not extend a ViewModel-only job into
a bulk import engine. Complete the database transaction only after required access/validation
succeeds, and clean incomplete owned files/grants without touching shared grants.

Exact repeated source URIs are idempotent. Different URIs with similar names/sizes
may prompt a possible-duplicate warning but must not be merged automatically.
Do not hash every multi-gigabyte file before allowing an import.

The private CBZ sample is approximately 3.16 GB. Never load or expand an entire
publication in memory. Evaluate seekable archive access and ZIP64 behavior;
stream page decoding with a bounded bitmap cache and limited prefetch. Evaluation
result: provider descriptors for shared storage cannot be reopened by path (including
`/proc/self/fd`), so archives are read through the granted descriptor with positional
reads (`core.files.SeekableZip`, ZIP64 directories supported); one page bitmap is held
at a time and no prefetch is implemented yet. If a
provider requires a temporary seekable copy, check available storage, disclose
the copy, permit cancellation and clean partial output. Avoid unconditional
whole-archive extraction. Limit entry counts, decoded pixel dimensions, expanded
bytes and compression ratios; prevent archive path traversal and XML external
entity access. Do not execute embedded publication scripts or fetch remote assets
without an explicit reviewed requirement.

Failures distinguish permission loss, unavailable source, unsupported layout or
format, protected content, corrupt archive/page, empty archive and insufficient
storage. Retain existing library metadata and progress when a source disappears.
No DRM bypass, source rewrites, publication-content logging or uploads.

## 5. Reader and theme behavior

Evaluate a stable Readium EPUB adapter, Android PDF rendering and an image-sequence
adapter behind ShelfOS interfaces. Do not assume one engine provides all features.
The evaluation must check minSdk, lifecycle/Compose integration, locators, offline
behavior, input hooks, archive access, large files, licenses/transitive dependencies
and APK impact. Record exact versions and licenses before adding dependencies.
If an API-24-capable PDF adapter lacks search or text selection, omit those controls
and carry them into Phase 2; do not raise minSdk merely to obtain them.

Expose capabilities such as reflowable typography, zoom, fit mode, navigation and
direction. Unsupported controls are omitted, with a concise fixed-layout explanation
in Appearance where helpful. The reader session owns resource cleanup; stale render
results cannot update a newly opened title. Save location during reading and when
backgrounded, not only on an orderly close; recover after process recreation.

Extend centralized theme data with reader presentation defaults, separate from
library UI typography. Offer calm book styles such as Editorial (serif), Clean
(sans-serif) and Spacious (more leading/margins), initially using system fonts.
These are reading presets, not extra themes. Map Classic/Dark to matching default
page colors while preserving contrast. Additional fonts require license review.

Resolve supported settings field by field: explicit per-title override, explicit
global reading preference, then theme/default presentation. Changing theme must
not erase explicit font or spacing choices. Provide reset-to-default actions.
Preserve semantic location when typography repaginates the text. Theme presets
must not recolor fixed-layout artwork by default or imply PDF font replacement.

### Manga and input

Manga defaults to RTL whether its file is PDF or CBZ; Comics default to LTR.
An explicit per-title choice wins. Direction belongs to the reading surface,
not the whole application locale. Source sequence remains page 1, 2, 3; RTL changes
navigation and eventual spread placement, not page identity or image mirroring.

For horizontal paginated reading: LTR next is right arrow/D-pad, right-side tap,
or swipe left; RTL next is left arrow/D-pad, left-side tap, or swipe right.
Page Down and R1 remain semantic Next in both directions; Page Up and L1 remain
Previous. While controls or editable fields have focus, normal Compose focus/text
behavior takes priority. Back dismisses controls, then exits the reader normally.

Begin with single-page fixed-layout reading on every viewport. `AUTO` spread
policy resolves to single-page until pairing and hinge behavior are implemented
and tested. Two-page pairing, cover offsets and advanced fold posture behavior
remain Phase 3 refinements. Do not label unsupported spread modes as working.

## 6. Ordered implementation increments

| Increment | Deliverable | Exit evidence |
| --- | --- | --- |
| 1A: Durable library | Room migration, SAF import, preview/title/category correction, real Library/Search/Favorites, recent-added ordering, remove and unavailable-source states | Import original PDF/EPUB/CBZ fixtures; restart and preserve entries/theme; failed/cancelled import leaves no completed row |
| 1B: Fixed-layout reading | Reader boundary, PDF and CBZ adapters, fit/zoom/page controls, RTL/LTR override, position persistence, real Continue Reading | Read local book/comic/manga, restart, resume correct page; direction works across touch/keyboard/D-pad |
| 1C: Book typography | Reflowable EPUB adapter, chapter navigation, reading presets, font/spacing controls, per-title/global preferences | Reflow text, change theme/size, restart and resume same passage without losing explicit preferences |
| 1D: Integration and polish | Library/detail/reader transitions, focus/scroll restoration, reduced motion, adaptive QA and performance fixes | Rapid navigation has no stale state or focus loss; physical-device frame/heap measurements and CI checks recorded |

Engine feasibility/licensing checks occur before the corresponding adapter work.
Do not commit to a date before these checks, especially large CBZ access and EPUB
integration. Online enrichment remains a subsequent library milestone; bookmarks,
full-text search, advanced spreads and richer reader controls remain later work.

Fresh installs start with an honest empty library. Keep fictional fixtures in
tests or an explicit development-only demo; never seed them as owned publications.
Continue Reading contains real saved reading state after 1B, and is an empty state
before then. Notes and Shelves remain truthful planned destinations. The legacy
Collections placeholder is renamed to Shelves in the visible label, route, icon and tests;
no Collection names remain in application code.

Motion work uses the shared token contract and Classic's restrained 100-160 ms
interaction guidance. Study ES-DE-like continuity as inspiration, then profile
our implementation. Transitions must be interruptible, restore originating cover
focus/scroll position, honor system animation settings and avoid decorative delays.

## 7. Validation and completion gates

Use original small PDF, reflowable/fixed-layout EPUB and numbered CBZ fixtures in
public tests. Private files under `Example Book files/`, extracted artwork and
captures stay ignored and local. Do not package them or send them to CI. Stage
private samples onto a test device separately; desktop paths are not Android URIs.

- Unit: format detection, category/default direction, overrides, deterministic
  natural archive ordering, duplicate decisions, capability filtering, preference
  precedence, locator serialization and cancellation/error-state transitions.
- Room: upgrade from v1 with theme retained; library/preference/progress persistence;
  transaction rollback and deletion preserving other titles and shared sources.
- Instrumented: real picker/provider grants and revoked access; navigation, source
  removal, cold relaunch, reader resume, input direction, large text and theme parity.
- Private acceptance: Dune as Book/PDF; Chainsaw Man as Manga/PDF with RTL; large
  comic archive as Comic/CBZ with LTR. Verify early/middle/final pages and overrides.
  Confirm source bytes remain unchanged without making hashing part of normal import.
- Reliability: low storage, malformed archives, non-seekable providers, interrupted
  copies, rapid title switching, rotation/resizing and process death. Measure heap
  while moving through the large archive; it must stay bounded rather than grow
  with pages read. Record device, build type, cold/warm timings and frame data.
- Compatibility: API 24 and current-target runtime, compact/expanded layouts,
  at least one physical device, keyboard/D-pad and physical controller where available;
  TalkBack and reduced-motion checks. Document any unexecuted hardware tests.
- CI/build: clean checkout `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug
  :app:assembleDebugAndroidTest`; run `:app:connectedDebugAndroidTest` on suitable
  devices. Verify exported schemas and dependency licenses, ignored private data,
  and no unnecessary network/storage permissions.

Expanded Phase 1 is complete only when 1A-1D have evidence, imported EPUB/PDF/CBZ
can be read/resumed offline, customization matches actual capabilities, and docs
describe shipped behavior. A locally accessible source is required for reading;
missing-source states must remain understandable. Update README, ROADMAP,
ARCHITECTURE, VALIDATION, relevant feature/design docs and dependency inventories
as each increment ships. Public screenshots remain deferred to the first fully
working version and require a separate publishing step with original content.

## 8. Explicit follow-on work

PDF font replacement/book-mode reconstruction, OCR, guided panels, CBR, advanced
foldable spreads, annotation/ink engines, metadata APIs, cloud/accounts, billing,
additional full themes and release signing are not part of this build. The plan
preserves their existing roadmap tracks rather than introducing speculative code.

## Accepted architecture follow-through

The current sourceUri/managedPath pair is a first-file access representation, not
the final [LibrarySource](features/LIBRARY_SOURCES.md) model. Retain stable item
UUIDs and migrate provenance separately from platform access. Future sessions and
scans must preserve identity and user edits; missing files never delete items.

Next import increments add multi-selection, then durable staging/review, recursive
folder discovery, Source health and manual rescan through [DATA_INGESTION](features/DATA_INGESTION.md).
Series and Manual Shelves follow that foundation. Smart Shelves, migration adapters,
advanced PDF/SourceMap, OCR and sophisticated reconciliation are separate later
work. New specifications do not authorize implementing them in this first reader
build. Use the roadmap sequence and [integration audit](DECISION_INTEGRATION_AUDIT.md)
to see how the existing implementation differs from the accepted target.
