# ADR-0004: Source Files Become Library Items

## Status
Accepted

## Context
ShelfOS must treat imported media as rich objects rather than raw paths.

## Decision
Create persistent LibraryItem records that reference source URIs and store ShelfOS-owned metadata/state separately.

## Consequences
- stable library UX
- source remains untouched
- supports favorites, progress, notes, covers, and collections
- requires source-unavailable handling

## Follow-on decisions (2026-09-24)

[ADR-0020](0020-categories-series-and-shelves.md) replaces the historical Collections
term with Shelves. [ADR-0022](0022-persistent-library-sources.md) extends source URI
references into durable LibrarySource provenance/access while retaining stable
LibraryItem identity. URI strings are not portable object IDs.
