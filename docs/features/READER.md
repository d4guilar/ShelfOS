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
