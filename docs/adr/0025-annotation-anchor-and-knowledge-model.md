# ADR-0025: Annotation anchor and knowledge model

## Status

Proposed, 2026-10-10 (administrator and independent review pending). Product and
architecture decision for Phase 4; not implementation completion. Detailed plan:
[`docs/PHASE_4_IMPLEMENTATION_PLAN.md`](../PHASE_4_IMPLEMENTATION_PLAN.md).

## Context

ShelfOS already ships EPUB bookmarks (Room v3 table `bookmark`, a foreign key to
`library_item` with `ON DELETE CASCADE`, a serialized Readium `Locator` as the
authoritative position and an integer progress snapshot for display). Fixed-page readers
(PDF, CBZ, CBR) store resume positions as `{"version":1,"page":N}` and have no bookmarks.
The Notes destination is a placeholder. Phase 4 must add highlights, notes, a global
review screen and export without a second, parallel system, without mutating publications,
and without pretending every format can do everything.

Facts that constrain the decision (verified 2026-10-10, see the plan §2):
`LibraryItem.id` is a random UUID minted at import; there is no content fingerprint, and
`byteSize` is not a revision signal for referenced sources; removing a LibraryItem today
silently cascades to its bookmarks (and the removal dialog does not say so); unavailable
sources already preserve all state; Readium 3.4.0 exposes `SelectableNavigator`,
`DecorableNavigator` and `Decoration` on the EPUB navigator, but runtime behavior in
ShelfOS's setup is unproven.

## Decision

1. **One annotation model.** Bookmarks, highlights and notes are `kind` values of a single
   Annotation record: id, library item id, kind, a versioned locator format, the
   authoritative locator, display/sort snapshots, optional excerpt, optional body, optional
   style key, created/updated timestamps. Persisted in ShelfOS's own database; the
   existing bookmark table's data migrates into it.
2. **The locator is authoritative.** The stored locator (a serialized Readium `Locator` for
   EPUB, `{"version":1,"page":N}` for fixed pages) is the only source of truth for where an
   annotation points. Progress percentage and any ordering key are derived, recomputable
   conveniences and are never used to navigate. Domain and data layers treat the locator as
   an opaque string; only the reader adapter parses it (ADR-0003).
3. **Format capabilities differ, honestly.** EPUB gets real text selection, persistent
   highlights, excerpts and notes attached to selected text (gated on a bounded engineering
   proof against the pinned Readium). PDF, CBZ and CBR get bookmarks, page-anchored notes
   and reliable jump-to-page only. No semantic highlighting on `PdfRenderer` or image
   pages, no OCR, no coordinate or ink annotation in this phase. Semantic PDF highlighting
   waits for Adapted PDF/SourceMap work (ADR-0018 and later).
4. **Kinds stay distinct.** `BOOKMARK` carries no text or body. `HIGHLIGHT` is EPUB text
   with a stable palette `styleKey` and may carry a body. `NOTE` requires a body and is
   anchored to a selection or a position (EPUB) or a page (fixed). A kind never changes
   implicitly. `styleKey` is a stable token resolved to color by the central theme system;
   color is never the only way to tell annotations apart.
5. **Identity.** Annotations attach to the LibraryItem UUID, never to a file name or URI.
   Series aggregation is virtual (ADR-0019), so annotations belong to the member item and
   are never merged across a Series or omnibus.
6. **Migration from bookmarks.** Room v3 → v4 by a hand-written migration (the repository
   convention), copying every bookmark row into an annotation row of kind `BOOKMARK`
   preserving id, item, locator, progress, label and creation time, verified for row-count
   equality inside the migration, with no destructive fallback. The legacy `bookmark` table
   is retained, unwritten, until physical acceptance confirms zero loss, and is dropped in
   a later small migration. The existing `BookmarkRepository` interface is kept as a
   compatibility adapter over the new table during the transition so current bookmark
   behavior is unchanged, then retired.
7. **Missing source preserves knowledge.** A source that is unavailable, moved or has lost
   its permission never deletes or hides annotations. They remain listable, editable,
   searchable and exportable; only jump-to-source can fail, with an explanatory message.
8. **Deletion is explicit and disclosed.** The annotation table has no cascading foreign
   key to `library_item`. Removing a LibraryItem takes an explicit knowledge policy: delete
   the annotations in the same transaction, or (once the Notes hub can display them) keep
   them as orphans under a captured publication snapshot. The removal confirmation states
   the consequence and count. Nothing deletes annotations automatically or as a side effect
   of source problems.
9. **No invisible remapping.** ShelfOS has no content fingerprint. Re-importing a removed
   publication creates a new item and does not re-associate old annotations; a replaced
   file behind an existing item keeps its annotations and reports ones that can no longer be
   placed. Fingerprint-assisted, user-confirmed re-association belongs to the Library
   Sources relink/changed-publication work, not this phase.
10. **Source immutability.** Annotations are never written into, beside or inside a
    publication (no sidecar, no embedded markup). The only file ever produced is an export
    the user explicitly creates through the Android Storage Access Framework.
11. **EPUB selected text.** The excerpt is stored only when the reader supplies it
    (`Selection.locator.text.highlight`), normalized for display and search, length-capped,
    and otherwise left empty (never fabricated from surrounding text). A selection that
    yields neither text nor a fine-grained location is rejected rather than stored as a
    coarse position. The full locator (including its text context) is stored verbatim.
12. **Fixed-layout anchors.** The anchor is the 0-based source page index, displayed
    1-based. It is a source page, not a spread group or screen position; spread mode and RTL
    never change it. A page beyond the current page count fails the jump with a message
    instead of silently clamping. CBZ/CBR logical order is the existing natural-sort order,
    which must not change without anchor migration.
13. **Export boundary.** Users can export their knowledge, never the publication, as
    canonical versioned JSON (`schemaVersion`, publication reference fields, annotations
    with kind, locator, excerpt, body, style and timestamps) and as human-readable Markdown,
    through user-selected SAF documents and with no network. Import/round-trip and portable
    backup are not part of this decision.
14. **Search and scale.** Notes search uses an ordinary repository `LIKE` boundary and
    keyset pagination without adding a dependency; full-text search is reconsidered only
    against measured thresholds on the project's slowest device.

## Alternatives considered

- **Keep bookmarks as a separate permanent table and add a second table for highlights and
  notes.** Rejected: two sources of truth for "saved places", duplicated ordering/export/hub
  logic, and the Notes hub would have to union them forever.
- **Keep the cascading foreign key.** Rejected: it makes "remove from library, keep notes"
  impossible without a later SQLite table rebuild and keeps destroying user knowledge as a
  side effect of a library delete.
- **Drop the old bookmark table in the same migration.** Rejected as unnecessarily
  irreversible for owner devices holding real data; retention costs a few rows.
- **A ShelfOS-injected JavaScript highlighter, or storing highlights as markup inside the
  EPUB.** Rejected: bypasses the engine boundary, enlarges the content-security surface, and
  mutates (or imitates mutating) source content. Not an acceptable fallback without explicit
  administrator review.
- **Store only a coordinate or percentage position for highlights.** Rejected: position
  within a resource changes with typography preferences and cannot represent a text range.
- **Auto-remap annotations on re-import or file replacement by filename or size.** Rejected:
  invisible guessing that can attach notes to the wrong text.
- **Tombstones/soft delete now.** Deferred: no sync exists; hard delete matches current
  behavior.

## Consequences

- One repository and one table serve EPUB, PDF, CBZ and CBR, the Notes hub and export; the
  per-format differences live in the locator format and in what each reader offers.
- Referential integrity for annotations is enforced by the repository and tests rather than
  by a foreign key; orphans become a deliberate, visible state.
- The first slice is a schema migration over real user data and therefore needs the
  strictest review; the EPUB highlight slice is conditional on a bounded proof, with a
  defined fallback (position-anchored notes and bookmarks) if the proof fails.
- Documentation (`docs/features/ANNOTATIONS.md`, `docs/ARCHITECTURE.md` §16) described a
  model without the shipped bookmark table; this ADR supersedes their conceptual sketch
  for Phase 4 and keeps their "do not lock notes into an opaque database" intent through
  the export decision.

## Explicitly deferred

OCR; AI features; cloud sync, accounts and collaboration; ink, freeform drawing, comic
panel regions and coordinate PDF markup; Adapted PDF/SourceMap-anchored annotations;
Series/omnibus note aggregation; content fingerprints and fingerprint-based re-association;
backup/restore and export import; online dictionary/provider integration (research only);
full-text search; tombstones; merging overlapping highlights.
