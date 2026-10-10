# Annotation and Notes Specification

## Current status (2026-10-10)

Only **EPUB bookmarks** exist today (Room table `bookmark`, with a cascade from the
LibraryItem); there are no highlights, notes, Notes hub or export, and fixed-page readers
(PDF/CBZ/CBR) have no bookmarks. The Phase 4 plan and the proposed model, which supersedes
the conceptual sketch below for Phase 4, are in
[`PHASE_4_IMPLEMENTATION_PLAN.md`](../PHASE_4_IMPLEMENTATION_PLAN.md) and
[ADR-0025](../adr/0025-annotation-anchor-and-knowledge-model.md).

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
