# ADR-0020: Categories, Series and Shelves

## Status

Accepted, 2026-09-24. Product/architecture decision; not implementation completion.

## Context

Older specifications use Collections for personal organization; categories and
Series risk being overloaded to represent arbitrary folders and user interests.

## Decision

Keep four fixed categories: BOOK, COMIC, MANGA, DOCUMENT. Series expresses intrinsic
publication relationships; Shelf expresses personal organization with multiple
memberships. Favorites stays a special cross-category system view. Canonical global
destinations are Library, Search, Notes, Shelves, Settings. Shelves replaces Collections.
Manual Shelves support import assignment and optional Library pinning; Smart Shelves
and Source-to-Shelf automation follow later. No folder rename or file move follows
from Shelf edits.

## Consequences and sequencing

This supersedes the old user-facing Collections terminology and clarifies ADR-0009's
"primary navigation" as Library categories, not global destinations. Existing code
labels/routes remain explicit migration debt during this documentation-only task;
there is no separate Collection domain requirement. Feature/package naming moves
toward shelves when implemented, with deliberate saved-route/state migration.
Optional Shelf reader defaults need explicit precedence for multi-membership.

Detailed source of truth: [SHELVES](../features/SHELVES.md).
Integration evidence: [coverage audit](../DECISION_INTEGRATION_AUDIT.md).

