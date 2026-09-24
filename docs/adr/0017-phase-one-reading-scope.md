# ADR-0017: Phase 1 reading scope and presentation capabilities

## Status

Proposed 2026-09-23; scope accepted by the owner before Phase 1 implementation
started. Updated 2026-09-24 to distinguish unfinished implementation from completion.
Dependency review and acceptance gates still apply. ADRs 0018–0022 refine the future
PDF/ingestion/organization architecture without pulling all of it into Phase 1.

## Conflict and precedence

The original roadmap assigns Library to Phase 1, EPUB/PDF readers to Phase 2,
CBZ/manga to Phase 3 and PDF reconstruction to Phase 5. The newer owner request
brings reading presentation into the next-build discussion. The accepted expanded scope pulls
basic readers into Phase 1 without claiming that all of Phases 2/3 are complete.
The roadmap now links the accepted scope; historical phase lists are retained
with explicit notes about overlap and remaining refinements.

The PDF font request also crosses a capability boundary: an original-page reader
does not provide reflowable typography. Theme changes cannot satisfy that request.
The plan discloses this limit and separates later reconstruction research.

`COMICS_MANGA.md` specifies AUTO spreads while `READER_UX.md` describes a single-page
default. These are compatible when AUTO resolves to one page until adaptive spread
support is implemented. The initial reader explicitly uses that policy. Existing
input tables describe LTR arrows; RTL needs direction-aware interpretation while
semantic Next/Previous and global navigation retain their meanings.

## Decisions

- Implement the sequence in [PHASE_1_PLAN.md](../PHASE_1_PLAN.md): durable Library,
  PDF/CBZ reading, reflowable EPUB typography, then integrated browsing polish.
- Preserve ADR-0003's engine boundary, ADR-0004's source/library separation and
  the single-module architecture. Add only schema serving implemented features.
- Treat rendering capability separately from user category: Manga may be PDF,
  and a Book may be fixed-layout rather than reflowable.
- Centralize reader defaults and resolve explicit user preferences ahead of theme
  suggestions. Theme switches preserve explicit typography and direction choices.
- Keep source page identity stable across LTR/RTL changes; do not reverse the stored
  page list, mirror artwork or change application locale for manga.
- Keep PDF font substitution/reconstruction, OCR and online enrichment outside
  the first reading build. Make this limitation visible in planning and reader UI.

## Consequences and evaluation gates

Phase 1 becomes larger and should ship in reviewable increments. Library-only
work remains useful but is not the expanded phase's final acceptance milestone.
Readium/other adapter versions, transitive licenses, minSdk compatibility and
multi-gigabyte archive access must be evaluated before dependencies are selected.
Reader polish and later refinements remain in Phases 2/3; no new premium or cloud
work follows from this change. The original planning change was documentation only; application work subsequently
started but has not completed acceptance. The 2026-09-24 reconciliation is again
documentation only. [ADR-0018](0018-dual-pdf-reading-modes.md) now defines advanced
Adapted/Original behavior, and [ADR-0021](0021-staged-library-ingestion.md) requires
durable recovery before library-scale bulk ingestion can be claimed.
