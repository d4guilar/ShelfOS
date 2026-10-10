# Reader Feature Specification

## Next-build plan

The accepted [Phase 1 plan](../PHASE_1_PLAN.md) pulls forward basic EPUB/PDF/CBZ
reading and resume. Settings must reflect publication capabilities: reflowable
EPUB supports typography changes; original-page PDF and image pages retain
authored fonts/layout. PDF font replacement remains future reconstruction work.
Manga PDFs use the same category-based RTL default as Manga CBZs. Reader adapters
for EPUB, Original PDF and CBZ are implemented and accepted as part of Phase 1
(ADR-0017, 2026-09-24). Phase 2 (see `docs/PHASE_2_PLAN.md`) hardened and extended
that foundation. The Phase 3 fixed-layout/comic reader is implemented and accepted
(2026-10-09): PDF/CBZ/CBR fixed-page reading with page thumbnails and a page
navigator, SINGLE/SPREAD/AUTO page modes, progress/resume, zoom and fit behaviors,
LTR/RTL and keyboard/controller navigation, and tablet/foldable-aware layouts.
AUTO reads one source page at a time (genuine authored wide source pages display
whole); SPREAD is the explicit pairing mode. CBR/RAR archives are read natively
and directly through the shared image-sequence pipeline with no conversion and no
source mutation (see `docs/adr/0024-native-cbr-libarchive.md`). The sections below
describe the shared target behavior; remaining gaps (annotations, Adapted PDF,
later formats) stay future work. Note: bookmarks are implemented for EPUB only; PDF/CBZ/CBR
page bookmarks are Phase 4 work (see `docs/PHASE_4_IMPLEMENTATION_PLAN.md`).

## Shared goals

All readers should support:

- open
- close
- current position
- progress persistence
- resume
- semantic input commands
- Back behavior
- error recovery

## Books

Initial:

- EPUB
- compatible PDFs via PDF reader

Features:

- page/scroll modes where supported
- chapters
- text preferences
- bookmark
- search
- user-imported managed fonts (TTF/OTF) with app-private managed storage
- Publisher / ShelfOS reading presentation

Later:

- highlight
- notes
- Adapted PDF presentation through structured content and SourceMap (later)

## Documents

Initial:

- PDF

Features:

- original layout
- zoom
- fit width
- fit page
- search where available
- bookmark

Later:

- annotations
- Adapted PDF presentation where supported (later)
- Focus mode
- Study mode

## Comics

Initial:

- CBZ
- CBR

Features:

- single page
- spread modes: SINGLE / SPREAD / AUTO (AUTO = one source page at a time)
- page turn
- fit page
- fit width
- progress
- thumbnails
- LTR default

## Manga

Initial:

- CBZ
- CBR

Features:

- same rendering core as Comics
- RTL default
- spread modes: SINGLE / SPREAD / AUTO
- page turn
- progress
- thumbnails

## Reader engine selection

Reader engine should be chosen by content capability, not only file extension.

## Source preservation

Reader must not write modifications into the original publication.

## PDF modes and Series context

[PDF_INGESTION](PDF_INGESTION.md) defines Original/Adapted choice, recommendations,
per-title preference and best-effort position/annotation mapping. Original PDF
rendering does not change embedded fonts; supported Adapted content eventually can.
EPUB retains its own structured publication pathway. Comic/Manga PDFs remain
page-oriented by default and preserve artwork.

[SERIES](SERIES.md) defines Series-level progress/Continue Reading and optional
Read as Omnibus. The ShelfOS reader coordinator restores current member ID +
locator, supports semantic Next/Previous across members in continuous mode and
handles missing/end members explicitly. Individual files and reader sessions stay
independent. User direction/mode choices survive transitions. These are later
capabilities; the first reader opens standalone LibraryItems.

Series members may use different formats. Omnibus transitions choose an adapter
per member's actual format/capabilities while retaining its own locator, progress
and preferences. A CBR volume followed by a CBZ volume must work continuously once
both formats are supported; no conversion or shared archive container is required.
See [mixed-format Series](SERIES.md#mixed-format-members) for the support boundary
and acceptance cases. Both container adapters now exist: CBR is implemented natively
(Phase 3, 2026-10-09; ADR-0024), though Series/omnibus itself remains future work.

## Reader performance and scale hardening (future)

Future requirement; not implemented, and not part of Phase 4 (Notes and
Knowledge Layer). Working framing: **Reader Performance / Scale Hardening**.

ShelfOS should explicitly plan for very large publications, including
multi-gigabyte CBZ, CBR and PDF files. Large-file support is a
performance/reliability requirement, not a format-support checkbox: it is not
enough to "support large files"; future reader performance acceptance must
evaluate behavior, not merely successful open.

Current evidence is narrow. Large CBZ archives have measurement-level evidence
only (a ~3.16 GB private sample and a ~2.99 GB synthetic load pass, recorded in
[validation](../VALIDATION.md), with no documented pass/fail threshold), and
there is no accepted multi-GB CBR or PDF evidence. Current validation does not
establish multi-GB acceptance across formats, and no maximum supported file
size is committed.

Future reader performance acceptance should evaluate:

- time to first readable page
- memory usage while opening
- memory usage during long reading sessions
- random page navigation
- sequential page turns
- thumbnail generation
- thumbnail navigation
- zoom / high-resolution re-render behavior
- archive access behavior
- recovery under Android memory pressure
- reopening/resuming a very large publication
- large page counts
- unusually large individual page images
- behavior on modest/older Android hardware

Architectural expectation: ShelfOS should not require the entire
publication/archive to be loaded into memory in order to begin reading.

Comic/manga container specifics at scale are recorded in
[Comics and Manga](COMICS_MANGA.md#large-publication-scale-future). Roadmap
grouping: [Reader & Library Hardening](../ROADMAP.md#reader--library-hardening).

## Phone-first zoom and gesture refinement (future)

Future reader UX requirement; research only, not implemented, and not part of
Phase 4.

Standard pinch-to-zoom works, but repeated pinch gestures can make comic/manga
reading on small phone displays cumbersome. ShelfOS should investigate
phone-first and potentially one-handed zoom interactions such as:

- double-tap zoom
- double-tap-and-drag / hold-and-drag zoom
- anchored zoom toward the tapped content
- quick return to fitted page
- other established reader patterns that reduce repeated pinch gestures

This requirement describes behavior, not competitor cloning: ShelfOS must not
copy another application's gesture set wholesale.

Any eventual gesture must coexist safely with:

- ordinary pinch-to-zoom
- page turning
- edge taps
- center-tap chrome behavior
- immersive/fullscreen reader behavior
- keyboard/controller navigation
- future fixed-layout annotation interactions
- accessibility

The eventual implementation must begin with a bounded UX/interaction research
task before choosing the final gesture; the exact gesture is intentionally not
specified here. This is reader UX hardening, not a redesign of the fixed-page
reader. Comic/manga-specific context is recorded in
[Comics and Manga](COMICS_MANGA.md#phone-first-zoom-gestures-future). Roadmap
grouping: [Reader & Library Hardening](../ROADMAP.md#reader--library-hardening).
