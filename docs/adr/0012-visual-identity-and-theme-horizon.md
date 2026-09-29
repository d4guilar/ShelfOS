# ADR-0012: Monochrome Core Identity and Theme Horizon

## Status
Accepted

## Context
ShelfOS needs a stable product identity while also supporting expressive themes.

The earlier direction risked making the default ShelfOS experience itself overly retro or glossy, which could weaken cross-platform longevity and compete visually with book/comic/manga covers.

## Decision

The canonical ShelfOS brand identity is monochrome.

ShelfOS Classic becomes a quiet, editorial, cover-first light theme rather than a skeuomorphic/retro theme.

ShelfOS Dark is its direct dark sibling.

The core visual principle is:

> The interface is monochrome. The library is the color.

Public launch will provide five polished free themes:

1. ShelfOS Classic
2. ShelfOS Dark
3. Pear Platinum
4. Pear Platinum Dark
5. Deckle

These names were finalized in `docs/design/THEMES.md`; the five-free-theme decision
is unchanged, and public-release naming review still applies.

Premium themes are explicitly more expressive and may alter motion, optional sound, focus treatment, and library presentation while preserving reader clarity and shared navigation semantics.

The initial Premium theme backlog contains at least ten themes and currently includes twelve:

- Frutiger Aero
- Terminal
- Archive
- PageStation
- Pocket
- Deck
- Dark Academia
- Crystal
- Vaporwave
- Swiss
- Solarpunk
- Ink

Backlog reconciliation (2026-09-28): the shipped premium lineup is now 13 themes in three
packs — **Retro Systems** (Terminal, Pagestation, Glassline, MonoDot, Deckwave),
**Pop & Print** (Sprite Clash, Halftone Hero, Pocket Pixel, Manga Print) and
**Dream Internet** (AeroBloom, Neo Shibuya, Sakura Mist, ClipPop 2000) — offered as $4.99
theme packs or through the lifetime ShelfOS Plus entitlement, with a themed-pack upgrade
path toward Plus. The list above is retained as the record of the earlier backlog; see
`docs/design/THEMES.md` and `docs/features/PREMIUM.md`.

## Consequences

Positive:

- stronger cross-platform brand consistency
- better compatibility with modern Android/iOS icon ecosystems
- media covers provide natural color and visual richness
- free users receive five complete visual experiences
- Premium monetization is based on delight rather than withholding core usability
- expressive themes can evolve without changing the ShelfOS logo

Negative:

- theme system requires stronger design tokens and abstraction
- some Premium themes may need custom presentation components
- trademark-inspired working names require legal review before public release
