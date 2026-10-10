# Annotation and Notes Specification

## Current status (2026-10-10)

Implemented locally (Phase 4B, pending Samsung manual UAT and review): in EPUB, text highlights, selection-anchored notes, position-anchored notes, EPUB bookmarks, an in-reader
per-publication Notes list (jump, edit, delete), style and optional body on highlights, and restoration of inline marks after reopen, recreation and process death. Highlights and notes
use the canonical `annotation` table (Room v4); the legacy `bookmark` table is inert. Inline marks need a WebView whose Chromium major version is at least 69; otherwise only bookmarks and position notes
are offered (see `docs/VALIDATION.md`, Phase 4B). Not built yet: fixed-layout (PDF/CBZ/CBR) bookmarks and notes (4C), the global Notes hub (4D), export (4E). The Phase 4 plan and the model are in
[`PHASE_4_IMPLEMENTATION_PLAN.md`](../PHASE_4_IMPLEMENTATION_PLAN.md) and [ADR-0025](../adr/0025-annotation-anchor-and-knowledge-model.md).

Stale text: before a selection-backed annotation is drawn, ShelfOS verifies its stored text context against the same sanitized rendition it gives the reader. If the text cannot be found exactly, the
annotation is kept, listed with a "text location unavailable" state, and never drawn on other text. The source publication is never modified.

## Goal

Annotations should become a reusable knowledge layer across ShelfOS.

## Types

Initial:

- bookmark
- highlight
- text note

Later:

- underline
- drawing
- margin note
- ink highlight

## Data model concept

```text
Annotation
├── id
├── libraryItemId
├── locator
├── selectedText
├── type
├── style
├── body
├── createdAt
└── updatedAt
```

## Global Notes hub

Users should eventually browse:

- all notes
- highlights
- bookmarks
- by title
- by date
- via search

Selecting an annotation should jump to its source location where possible.

## Export

Later support export to a portable format.

Avoid locking notes into an opaque proprietary database forever.

## PDF modes, Series and source revisions

Keep annotation identity tied to a LibraryItem and its content/source revision,
not a transient omnibus-wide page number. Series aggregation does not merge notes.
[PDF SourceMap](PDF_INGESTION.md#sourcemap-and-annotations) links Adapted semantic
anchors with Original page/bounds/text ranges where possible. One logical system
must preserve unmappable annotations without promising perfect cross-mode placement.
[Changed-source review](LIBRARY_SOURCES.md#changed-publications) protects annotations
when publication bytes change. Missing/disconnected Sources preserve notes and
anchors; portable backups preserve them even when access requires reconnection.
