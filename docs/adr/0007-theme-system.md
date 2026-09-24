# ADR-0007: Themes as First-Class Presentation Systems

## Status
Accepted; expanded by ADR-0012

## Context
Visual identity and personalization are major ShelfOS differentiators.

## Decision
Implement a centralized theme contract covering colors, typography, surfaces, focus, motion, icon treatment, library presentation, and optional sound hooks.

Public free theme target:

- ShelfOS Classic
- ShelfOS Dark
- Retro Apple UI (working/internal name)
- Retro Apple UI Dark (working/internal name)
- Paper / Vintage Library

Premium themes are registered separately and may be more expressive.

The canonical ShelfOS logo remains monochrome across themes.

## Consequences

- strong differentiation
- free experience remains visually complete
- premium monetization can focus on optional delight
- themes remain maintainable through shared design tokens
- some expressive themes may require controlled theme-specific components
- public naming that references third-party brands must be reviewed before release
