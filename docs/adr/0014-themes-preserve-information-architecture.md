# ADR-0014: Themes Preserve ShelfOS Information Architecture

## Status
Accepted

## Context

ShelfOS will have many free and Premium themes, some with strong visual personalities.

Without a boundary, themes could fragment navigation, features, metadata behavior, and maintenance.

## Decision

Themes are presentation layers over one shared ShelfOS information architecture.

Themes may modify visual tokens, motion, focus treatment, optional sounds, decorative surfaces, and view presentation.

Themes may not arbitrarily redefine:

- global destinations
- media-category meanings
- metadata semantics
- reading state semantics
- source ownership behavior
- accessibility
- system navigation behavior

## Consequences

- themes remain sustainable
- new visual experiences can be added without duplicating product logic
- UI components need themeable tokens/hooks
- strong themes such as PageStation must still feel recognizably ShelfOS
