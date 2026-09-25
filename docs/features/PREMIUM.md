# Premium Feature Specification

## Philosophy

Premium supports development and unlocks advanced delight/power.

It must not cripple free reading.

## Distribution model

One Google Play application.

Free install.

Optional permanent Premium in-app purchase later.

No ShelfOS login required.

## Free baseline

Free should include:

- unlimited local library
- Books / Comics / Manga / Documents
- Favorites
- EPUB
- PDF
- CBZ
- reading progress
- bookmarks
- basic highlights/notes when implemented
- ShelfOS Classic
- ShelfOS Dark
- Retro Apple UI (working/internal name)
- Retro Apple UI Dark (working/internal name)
- Paper / Vintage Library
- keyboard
- gamepad
- offline reading
- no ads

## Potential Premium

### Premium theme catalog / backlog

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

### Other Premium candidates

- deeper appearance customization
- advanced stylus tools
- advanced PDF reconstruction
- enhanced statistics
- smart shelves
- advanced metadata management
- enhanced comic/manga tools

Theme purchases/unlocks must never be required for core reading.

Smart Shelves and advanced PDF entries are candidates, not finalized entitlement
policy. Their accepted behavior and sequencing live in [Shelves](SHELVES.md) and
[PDF ingestion](PDF_INGESTION.md); this list does not move them into an early phase.

## Entitlement

Do not spread purchase logic through UI.

Use centralized feature access.

Concept:

```text
Entitlement = FREE | PREMIUM
FeatureAccess.canUse(feature)
```

## Play Billing

Later implementation should:

- use Google Play Billing
- support permanent one-time entitlement
- query owned purchases
- handle pending purchases correctly
- restore entitlement
- acknowledge purchases as required
- consider stronger server-side verification only if the project reaches a scale that justifies it
