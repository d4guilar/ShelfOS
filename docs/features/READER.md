# Reader Feature Specification

## Next-build plan

The accepted [Phase 1 plan](../PHASE_1_PLAN.md) pulls forward basic EPUB/PDF/CBZ
reading and resume. Settings must reflect publication capabilities: reflowable
EPUB supports typography changes; original-page PDF and image pages retain
authored fonts/layout. PDF font replacement remains future reconstruction work.
Manga PDFs use the same category-based RTL default as Manga CBZs. Reader adapters
are present in the unfinished Phase 1 working tree; full acceptance
is pending. The sections below describe target behavior, not a completed feature list.

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

Features:

- single page
- page turn
- fit page
- fit width
- progress
- thumbnails
- LTR default

## Manga

Initial:

- CBZ

Features:

- same rendering core as Comics
- RTL default
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
and acceptance cases. CBR support remains future work.
