# ADR-0009: First-Class Media Categories

## Status
Accepted

## Decision
ShelfOS exposes the following primary Library category/filter navigation:

1. Favorites
2. Books
3. Comics
4. Manga
5. Documents

Library items have one media category:
BOOK, COMIC, MANGA, or DOCUMENT.

Favorites is independent of category.

## Consequences
- clear user mental model
- Manga may share reader code with Comics while retaining different defaults

## Clarification (2026-09-24)

These are Library filters, not global destinations. [ADR-0020](0020-categories-series-and-shelves.md)
establishes Library / Search / Notes / Shelves / Settings globally and distinguishes
fixed categories from Series and personal Shelves.
