# ADR-0019: Series as virtual aggregation

## Status

Accepted, 2026-09-24. Product/architecture decision; not implementation completion.

## Context

Volumes/issues clutter libraries and interrupt sequential reading. A filename field
alone cannot represent relationships, manual order or Series-level resume.

## Decision

Use a first-class generic Series and SeriesMembership for Books, Comics, Manga and
other legitimate sequences, including issues, annuals, specials and volumes. Keep
every LibraryItem and source independent. Provide grouped browsing, a Series page,
Series progress/resume and optional Read as Omnibus through successive reader sessions.
Order using user decisions, trusted metadata and natural numeric filename inference.
Support detected grouping, bulk/file-folder import and grouping existing LibraryItems.
Detection is reviewable and works offline; a Series is distinct from a Shelf.

Series membership is independent of file format: a CBR volume and a CBZ volume
can belong to one Series without conversion. Virtual omnibus reading selects the
appropriate adapter per member, preserving its locator and progress across format
boundaries. This owner clarification does not move CBR implementation into an
earlier phase; unsupported members remain explicit, isolated capability gaps.

## Consequences and sequencing

No physical merge or online metadata requirement. Reader coordination must preserve
member locators and handle missing members, reverse navigation and end-of-Series.
Series foundation precedes richer continuous-reading and detection refinements.

Detailed source of truth: [SERIES](../features/SERIES.md).
Integration evidence: [coverage audit](../DECISION_INTEGRATION_AUDIT.md).
