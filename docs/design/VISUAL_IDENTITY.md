# ShelfOS Visual Identity

## 1. Brand horizon

ShelfOS should feel like a **personal reading system** rather than a generic ebook utility.

The brand identity is deliberately simple and stable while the application themes are allowed to express different personalities.

The central visual principle is:

> **The interface is monochrome. The library is the color.**

ShelfOS itself should remain quiet, elegant, and recognizable. Book, comic, manga, and document covers provide most of the visual richness in the default experience.

## 2. Canonical logo

The primary ShelfOS identity is monochrome.

The approved symbol direction contains:

- three simplified media spines
- two upright spines
- one rightmost spine with a subtle intentional tilt
- a thick rounded shelf/media dock underneath
- a narrow inset slot inside the dock
- a simple geometric silhouette that survives at small sizes

The symbol should work as:

- black on white
- white on black
- graphite on ivory
- ivory on graphite
- Android themed icon
- future iOS icon treatment

The logo should not depend on gloss, gradient, color, or 3D rendering to be recognizable.

## 3. Wordmark

The approved ShelfOS wordmark is elegant and restrained.

Characteristics:

- `Shelf` carries slightly stronger visual weight
- `OS` is lighter and more refined
- typography is clean, modern, and premium
- no cyberpunk, gamer, or exaggerated futuristic styling
- kerning and optical spacing are more important than decoration

The wordmark should remain useful on:

- GitHub
- documentation
- website
- splash screens
- Play Store / App Store assets
- social/avatar lockups

## 4. Core brand palette

The logo itself is primarily monochrome.

Optional UI support palette:

| Token | Suggested direction | Role |
|---|---|---|
| Graphite | near-black | primary brand/UI |
| Ivory | warm off-white | light surfaces |
| Neutral Gray | middle gray | secondary UI |
| Aqua | restrained accent | optional system/status accent |
| Silver | cool neutral | optional material/detail tone |

Aqua is **not** required in the core logo. It belongs primarily to optional UI states and certain themes.

## 5. Default visual philosophy

ShelfOS Classic is not the retro theme.

It is the timeless ShelfOS identity:

- monochrome
- editorial
- cover-driven
- quiet
- touch-friendly
- keyboard/gamepad navigable
- elegant at phone, tablet, handheld, and foldable sizes

The system chrome should rarely compete with the content.

## 6. Cover-first hierarchy

Covers are the visual anchors of ShelfOS.

Default library views should avoid placing every cover inside a heavy card.

Preferred presentation:

```text
[ COVER ]
Title
Author / Creator
Progress
```

rather than:

```text
[ large decorative card containing cover + all metadata ]
```

Cards are better reserved for:

- Continue Reading
- Notes
- settings
- focused detail panes
- contextual surfaces

## 7. ES-DE influence: structural, not visual

ShelfOS may learn from high-quality gaming frontends such as ES-DE in these ways:

- satisfying controller navigation
- persistent selection
- fast browsing
- multiple library views
- metadata/detail panes
- categories and shelves
- smooth cover loading
- strong large-library performance

ShelfOS should **not** imitate gaming frontend visual language by default.

Avoid in Classic:

- game-system logos
- arcade transitions
- oversized button prompts
- console-style lock-in
- system-list aesthetics everywhere
- noisy selection animations

Target:

> **An ES-DE-quality media browser designed as a premium reading application.**

## 8. Motion language

Classic motion should be subtle and functional.

Typical target:

- 100–160 ms selection response
- slight selected-cover scale around 1.01–1.02x
- restrained outline or shadow
- metadata crossfade
- no bounce by default

Signature transition candidate:

```text
Library cover
    ↓
expands smoothly
    ↓
Reader opens
```

Closing the reader may reverse the motion back into the library position.

Reduced-motion preferences must be respected.

## 9. Navigation hierarchy

Global navigation:

- Library
- Search
- Notes
- Shelves
- Settings

Library filtering:

- Favorites
- Books
- Comics
- Manga
- Documents

On phones, global navigation may use a bottom bar.

On tablets/foldables, it may become a navigation rail or compact side navigation.

## 10. Library view modes

ShelfOS should eventually support multiple browsing modes.

### Grid
Default cover-first browsing.

### Compact
Higher-density cover browsing.

### List
Metadata-forward, accessible, efficient.

### Showcase
Frontend-inspired browsing with one prominent selected item and neighboring covers.

Showcase should feel editorial and elegant, not like a game carousel.

## 11. Reader restraint

Themes can be expressive in the library, but the active reader remains calm.

Themes may affect:

- reader chrome
- page background presets
- typography presets
- progress indicators
- transitions

Themes must not place decorative animation behind reading text or obscure publication artwork.

## 12. Free theme strategy

ShelfOS launches with five polished free themes:

1. ShelfOS Classic
2. ShelfOS Dark
3. Retro Apple UI (working/internal name; public naming requires trademark review)
4. Retro Apple UI Dark (working/internal name; public naming requires trademark review)
5. Paper / Vintage Library

Free themes are complete experiences, not intentionally inferior versions of Premium.

## 13. Premium theme strategy

Premium themes are optional alternate worlds for the user's library.

They may use stronger motion, custom presentation, optional sounds, and theme-specific browsing behavior while preserving the underlying ShelfOS navigation and accessibility model.

Premium theme concepts are documented in `THEMES.md`.

## 14. Platform consistency

The canonical ShelfOS mark stays consistent across Android and future iOS.

Platform packaging can adapt:

- Android adaptive icon and themed icon
- future iOS light/dark/tinted icon treatment
- platform-native spacing and surfaces

The brand should never require a skeuomorphic glossy app icon to feel complete.
