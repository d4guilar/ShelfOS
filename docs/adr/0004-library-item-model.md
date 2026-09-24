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
