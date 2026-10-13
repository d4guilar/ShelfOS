# Annotation and Notes Specification

## Current status (2026-10-12)

Merged and accepted (Phase 4B, PR #34 commit `71c1740`; Samsung manual UAT passed and independent review passed): in EPUB, text highlights, selection-anchored notes, position-anchored notes, EPUB bookmarks, an in-reader
per-publication Notes list (jump, edit, delete), style and optional body on highlights, and restoration of inline marks after reopen, recreation and process death. Highlights and notes
use the canonical `annotation` table (Room v4; v5 adds the display-only `annotation_source_snapshot` for kept orphans); the legacy `bookmark` table is inert. Inline marks need a WebView whose Chromium major version is at least 69; otherwise only bookmarks and position notes
are offered (see `docs/VALIDATION.md`, Phase 4B). Phase 4C.1 is merged and accepted at `0040c52187e50515b7d20608e5ef8e71dacc7809`: PDF, CBZ and CBR page bookmarks, page notes, strict annotation navigation and the shared Notebook foundation. Phase 4C.2 is merged (PR #37, `ca47e3d`; physical UAT deferred to Phase 4F): the same Publication Notebook is a dialog below 840×480dp and an end-edge 320–400dp pane at or above it, across EPUB/PDF/CBZ/CBR. EPUB filters All, Notes, Highlights and Bookmarks; fixed readers omit Highlights. Phase 4D is **MERGED / IMPLEMENTATION COMPLETE** in PR #38 at `90b9f1c7fe827c996247a60bcd8cbc224afc72d3` and replaces the Notes placeholder with the global hub on Room v5; physical/manual UAT remains deferred to Phase 4F. Phase 4E — Portable Knowledge & Restore is **PLANNING RECONCILED / IMPLEMENTATION NOT STARTED**; 4F is not started and Phase 4 is not complete or accepted. The Phase 4 plan and model are in
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

## Fixed-layout bookmarks and page notes (PDF, CBZ, CBR; Phase 4C.1, merged and accepted)

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
  Phase 4C.2 adds the side-by-side presentation and ports EPUB highlights, notes and bookmarks onto the same panel.

## Global Notes hub (Phase 4D; merged and implementation complete)

The **Notes** destination (`feature/notes`) lists every note, highlight and bookmark across the library. The reader-local
list stays the **Publication Notebook** ("Notebook" in the UI); the two are different surfaces over the same `annotation` rows.

- **Browse.** Compact rows: the shared locally generated publication cover (a safe display-only fallback for an orphan), localized
  kind label plus monochrome icon, highlight style name (or "Note at position"), optional user label, selected excerpt, note-body
  preview, publication title, location (fixed: "Page N"; EPUB: stored chapter/title and/or "Location N", with percentage only as
  supplemental/fallback presentation), and locale-aware Created/Updated times. A text status identifies a removed publication or an
  unavailable original. Meaning is never colour-only; visual and spoken location forms remain separate.
- **Filters.** All / Notes / Highlights / Bookmarks by kind (a highlight with a body stays a Highlight; Notes is NOTE only), plus a
  publication filter that lists live and removed publications with their counts.
- **Search.** A plain case-insensitive-for-ASCII `LIKE` (no FTS) over body, selected text, the user label, and the publication
  title and creator (the live ones, or the removal snapshot's); `%`, `_` and `\` typed by the user are literal. It composes with the
  kind and publication filters.
- **Sort.** By publication (default; publications by title, each in reading order) or newest first. Publication headers appear in the
  publication sort and once above a single-publication filter. No Series grouping, Smart Shelves or inferred grouping.
- **Pages.** Keyset pages of 100 rows (`(createdAt, id)` for date; `(title, item, order, createdAt, id)` for publication), no Paging
  dependency; a change reloads as many rows as are shown. Rows of a kind or locator format this build does not know stay stored and
  are left out of every view.
- **Open source.** For a publication still in the library, "Open" starts the same reader entry as opening the book, carrying only the
  annotation id. EPUB selection-backed annotations move the reader only when their anchor is verified PLACEABLE; fixed pages only through
  the strict `fixedPageNavigation` (never clamped). An UNPLACED or MALFORMED locator, a missing annotation or a page past the end shows
  "That spot could not be opened. The note is still saved." and changes nothing. An orphan offers no Open action.
- **Edit and delete.** Notes edit their body; highlights edit body and style; bookmarks have nothing to edit. Kind, locator and
  publication never change by editing. Deleting an item asks first and never touches the original file.
- **Removal choice.** Removing a publication that has saved items offers two explicit actions and no default: "Remove, keep notes" or
  "Remove, delete notes". Keep retains the annotations as orphans and saves a four-field display snapshot (title, creator, category,
  format) in `annotation_source_snapshot`; delete removes the annotations and any snapshot. With no saved items the dialog is unchanged.
  The original file is untouched either way.
- **Orphans.** An annotation whose library item no longer exists. Listed under its snapshot title (or "Removed publication" when no
  snapshot exists), searchable, editable (notes, highlights), deletable, and never reattached: re-adding the same file creates a new
  item and the old notes stay with the removed publication. "Delete notes for this removed publication" is an explicit confirmed action
  that deletes the group and its snapshot; deleting the last orphan of a publication removes its snapshot. Nothing is garbage-collected.
- **State.** Search text, kind filter, publication filter and sort survive recreation (saved state); edits are committed to Room.
  An unsaved edit draft is not kept across process death.

## Portable Knowledge & Restore (Phase 4E; planned, not implemented)

Markdown is a human-readable export only. Canonical versioned ShelfOS JSON is the sole
Phase 4E round-trip format: it preserves annotation ids, exact opaque locator strings,
content and timestamps. Import associates a publication only when `libraryItemId` exactly
matches a live item; otherwise it restores searchable/editable/exportable orphan knowledge
under the safe source snapshot, with no Open-source action and no fake publication row.
Identical annotation ids/content are skipped; divergent content under an existing id is a
reported conflict and never overwrites or duplicates the saved annotation.

The operation is explicit, offline and SAF-based. It exports no publication bytes, source
URI/path or cover bytes and does not claim full backup/device migration. Parse, validate
and classify the whole JSON before one transactional Room v5 commit. Markdown is never
imported. See Phase 4 plan §12 for the schema and validation contract.

## PDF modes, Series and source revisions

Keep annotation identity tied to a LibraryItem and its content/source revision,
not a transient omnibus-wide page number. Series aggregation does not merge notes.
[PDF SourceMap](PDF_INGESTION.md#sourcemap-and-annotations) links Adapted semantic
anchors with Original page/bounds/text ranges where possible. One logical system
must preserve unmappable annotations without promising perfect cross-mode placement.
[Changed-source review](LIBRARY_SOURCES.md#changed-publications) protects annotations
when publication bytes change. Missing/disconnected Sources preserve notes and
anchors; portable backups preserve them even when access requires reconnection.
