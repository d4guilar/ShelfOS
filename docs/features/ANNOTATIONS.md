# Annotation and Notes Specification

## Current status (2026-10-10)

Merged and accepted (Phase 4B, PR #34 commit `71c1740`; Samsung manual UAT passed and independent review passed): in EPUB, text highlights, selection-anchored notes, position-anchored notes, EPUB bookmarks, an in-reader
per-publication Notes list (jump, edit, delete), style and optional body on highlights, and restoration of inline marks after reopen, recreation and process death. Highlights and notes
use the canonical `annotation` table (Room v4); the legacy `bookmark` table is inert. Inline marks need a WebView whose Chromium major version is at least 69; otherwise only bookmarks and position notes
are offered (see `docs/VALIDATION.md`, Phase 4B). Implemented locally, ready for independent review (Phase 4C.1, not yet merged or accepted; Samsung UAT not performed): PDF, CBZ and CBR page bookmarks (keyboard B or the Bookmark chrome control; `{"version":1,"page":N}` anchored to the reader's authoritative source page, one row per page), page notes, strict annotation navigation (never clamps) and a fixed-reader **Notebook** dialog for the current publication (filters All, Notes, Bookmarks; edit, delete with confirmation). Not built yet: the adaptive side-by-side Publication Notebook and EPUB integration (4C.2, in planning: `PHASE_4_IMPLEMENTATION_PLAN.md` section 13.3), the global Notes hub (4D), export (4E). The Phase 4 plan and the model are in
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

## Fixed-layout bookmarks and page notes (PDF, CBZ, CBR; Phase 4C.1, implemented locally, ready for independent review)

- **Anchor.** `{"version":1,"page":N}` with `N` the true 0-based source page of the open publication (display is `N + 1`). Written
  only from `FixedReaderState.page`, the page the reader itself resumes on: never a spread group, slot, synthetic, slider or
  thumbnail index and never a physical left/right position, so SINGLE/SPREAD/AUTO and LTR/RTL do not change what is stored.
  Choosing a page through Pages or the slider makes that exact page the anchor.
- **Bookmarks.** One BOOKMARK row per page; the Bookmark chrome control or the keyboard letter B removes it when present and creates it
  otherwise (a rapid second press is ignored). No label (`title` stays null). No gamepad face button is mapped: gamepad users reach the
  control through chrome focus and gamepad A (A is activated on the focused control only; gamepad B stays Back).
- **Notes.** A NOTE row with a required non-blank body (20,000 characters at most, rejected rather than truncated), no selected text
  and no style. Editing changes only the body; kind, anchor, publication and creation time never change. Delete asks for confirmation.
  An unsaved draft survives rotation and recreation but NOT process death, and is never saved unless Save is pressed.
- **Strict navigation.** A Notebook row moves the reader only when its locator is a valid fixed-page locator whose page is inside the open
  publication. A malformed locator or a page past the end is listed with a "location unavailable" reason, stays editable (notes) and
  deletable, never moves the reader, is never clamped or rewritten and never deletes data. Resume position restoration keeps its own
  (clamping) rule; progress and ordering hints are never used to navigate.
- **Ordering.** `orderKey = page / pageCount` (0..1) and `progressSnapshot` from the existing `pageProgress`, so bookmarks and notes
  interleave in reading order.
- **Notebook.** The user-facing name of the reader-local, current-publication list is **Notebook** ("Publication Notebook" is the
  canonical documentation name); "Notes" remains the kind and filter name. Fixed readers show filters All, Notes and Bookmarks in a dialog.
  The side-by-side presentation, EPUB integration and highlights in this list are 4C.2.

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
