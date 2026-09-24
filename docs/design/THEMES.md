# ShelfOS Themes

## Phase 0 implementation status

Classic and Dark are implemented through shared Compose tokens. Retro Apple UI,
Retro Apple UI Dark, and Paper / Vintage Library are registered but unavailable
and marked Planned. Their eventual complete design is described below.
No Premium themes are implemented. Theme selection is persisted locally in Room.

## Theme philosophy

Themes are complete presentation personalities, not only color palettes.

ShelfOS separates two ideas:

> **Free themes are polished interpretations of reading.**
>
> **Premium themes are expressive alternate worlds for the library.**

The active reader remains calm and usable in every theme.

## Core brand rule

The canonical ShelfOS logo remains monochrome.

Themes may reinterpret:

- surfaces
- colors
- typography presets
- focus treatment
- motion
- library layout hints
- optional sounds
- loading/boot experiences

Themes must not break:

- accessibility
- navigation semantics
- reader clarity
- keyboard/gamepad operation
- reduced-motion settings

---

# Free Themes

## 1. ShelfOS Classic

### Role
Default ShelfOS identity.

### Principle
**The interface is monochrome. The library is the color.**

### Characteristics

- warm white / ivory canvas
- graphite typography and controls
- neutral gray dividers
- card-light cover grids
- minimal monochrome navigation
- restrained focus states
- subtle 100–160 ms motion
- full-color media covers as the dominant visual layer

### Inspiration
Modern consumer-system clarity and editorial media browsing without reproducing another platform's UI.

### Frontend influence
Borrow structural strengths from ES-DE-quality media browsers while avoiding gamer visual language.

See `CLASSIC_UI.md`.

---

## 2. ShelfOS Dark

### Role
Low-light sibling to Classic.

### Characteristics

- near-black canvas
- warm white text
- graphite/gray surfaces
- full-color covers
- no required blue/neon accent
- identical geometry and navigation to Classic
- calm OLED-friendly presentation

### Rule
Dark is not a separate visual concept. It is Classic under a dark material system.

---

## 3. Retro Apple UI

### Status
Working/internal theme name. Final public naming must receive trademark review.

### Role
Free nostalgic computing theme.

### Characteristics

- Aqua-era optimism
- tactile controls
- soft blue/silver surfaces
- gentle glass and depth
- friendly system iconography
- restrained skeuomorphism
- full-color covers

### Rule
Do not copy Apple assets, icons, wallpapers, sounds, or proprietary UI layouts.

---

## 4. Retro Apple UI Dark

### Status
Working/internal theme name. Final public naming must receive trademark review.

### Role
Dark nostalgic counterpart.

### Characteristics

- deep blue / graphite
- silver/ivory typography
- restrained glass surfaces
- subtle depth
- reduced brightness
- same nostalgic language without excessive glow

---

## 5. Paper / Vintage Library

### Role
Warm literary alternative.

### Characteristics

- cream/paper canvas
- ink/brown typography
- subtle paper grain
- thin editorial rules
- restrained serif headings paired with readable sans-serif controls
- library-card / classic paperback influence
- no fake wooden bookshelf

### Target feeling
Old Penguin paperback + quiet reading room + modern product usability.

---

# Premium Themes

Premium themes are optional and should never gate core readability or accessibility.

At least ten premium themes should be available over the life of the product. The following twelve form the initial design backlog.

## 1. Frutiger Aero

### Personality
Optimistic, glossy, bright, aquatic, airy.

### Characteristics

- sky/water/glass motifs
- luminous blue/green surfaces
- soft reflections
- smooth motion
- optimistic early-web / mid-2000s energy

### Reader
Very restrained; expressive visuals belong mainly to library/navigation surfaces.

---

## 2. Terminal

### Personality
Late-90s/early-2000s cyber-computing.

### Inspiration
Broadly influenced by experimental terminal/cyber-media aesthetics, including the era associated with works such as *Serial Experiments Lain*, without using copyrighted assets.

### Characteristics

- black backgrounds
- green/amber/white terminal text
- technical labels
- subtle scanline/CRT options
- command-line-like focus treatment
- minimal animation

### Rule
No copied franchise imagery, fonts, logos, dialogue, or interface assets.

---

## 3. Archive

### Personality
Professional archival workstation.

### Characteristics

- folders/index cards
- off-white document surfaces
- stamps/index-number motifs
- restrained beige/gray palette
- metadata-forward layouts
- catalog / museum archive energy

---

## 4. PageStation

### Positioning
`PageStation — A ShelfOS Experience`

### Personality
Original Y2K Japanese consumer-electronics/media-system theme.

### Characteristics

- deep navy/black
- cold white/silver
- original crystalline startup motion/sound
- horizontal library presentation
- small technical system labels
- controller-forward focus motion

### Legal boundary
Do not reproduce PlayStation names, trademarks, logos, sounds, controller symbols, boot graphics, or proprietary UI assets.

Final public name should receive trademark review before release.

---

## 5. Pocket

### Personality
Playful monochrome handheld computing.

### Characteristics

- limited green/gray/amber palettes
- pixel-inspired details
- chunky but readable controls
- optional LCD ghosting simulation kept subtle
- compact handheld presentation

### Legal boundary
Do not use Game Boy branding, copyrighted iconography, shell designs, or proprietary visual assets.

---

## 6. Deck

### Personality
Polished desktop media-library theme.

### Characteristics

- dark slate surfaces
- dense shelves
- horizontal artwork rows
- strong controller/keyboard navigation
- desktop media-hub feel
- compact metadata panels

### Legal boundary
Do not copy Steam branding, layout assets, iconography, or proprietary interface details.

---

## 7. Dark Academia

### Personality
Moody literary study.

### Characteristics

- espresso/burgundy/cream
- serif display headings
- restrained paper/leather texture
- elegant separators
- study-desk atmosphere
- warm low-light presentation

### Rule
Keep texture subtle; no fake photorealistic bookshelves.

---

## 8. Crystal

### Personality
Clean translucent glass interface.

### Characteristics

- frosted glass layers
- pale neutral backgrounds
- subtle spectral highlights
- minimal chrome
- light/refraction-inspired motion

### Target audience
Users who enjoy premium contemporary glass UI without heavy retro references.

---

## 9. Vaporwave

### Personality
Dreamlike retro-digital.

### Characteristics

- violet/pink/cyan accents
- deep gradient atmospheres
- geometric horizon motifs used sparingly
- expressive library browsing
- restrained reader canvas

### Rule
Avoid illegible novelty typography.

---

## 10. Swiss

### Personality
Editorial modernist library.

### Characteristics

- strict grid
- black/white/red or black/white accent system
- strong typography
- asymmetrical editorial composition
- almost no decorative texture

### Target audience
Users who want a design-forward, non-retro premium experience.

---

## 11. Solarpunk

### Personality
Warm optimistic future.

### Characteristics

- natural greens
- warm cream
- gentle organic curves
- sunlight-inspired surfaces
- restrained botanical motifs
- calm animation

### Rule
Keep it sophisticated rather than illustrated or childish.

---

## 12. Ink

### Personality
High-contrast print / manga-editorial aesthetic.

### Characteristics

- black, white, warm paper
- brush/ink-inspired secondary marks
- halftone used sparingly
- high-contrast cover presentation
- particularly compatible with manga/comics but usable across the whole library

### Rule
Do not reduce accessibility or make body text decorative.

---

# Theme Registration

Conceptual registry:

```text
ThemeRegistry
├── Free
│   ├── Classic
│   ├── Dark
│   ├── RetroAppleLight   (working name)
│   ├── RetroAppleDark    (working name)
│   └── Paper
└── Premium
    ├── FrutigerAero
    ├── Terminal
    ├── Archive
    ├── PageStation
    ├── Pocket
    ├── Deck
    ├── DarkAcademia
    ├── Crystal
    ├── Vaporwave
    ├── Swiss
    ├── Solarpunk
    └── Ink
```

Do not hard-code theme branches across unrelated screens.

## Theme implementation rule

A theme should be data/configuration-driven where possible.

Theme-specific custom components or layouts are allowed only when the experience genuinely requires them, and they must still conform to shared navigation/accessibility semantics.
## Structural invariants

Themes are presentation layers over the ShelfOS product structure.

Unless explicitly documented by an adaptive layout specification, a theme may not redefine:

- global navigation destinations
- Favorites / Books / Comics / Manga / Documents semantics
- LibraryItem metadata semantics
- reading-progress semantics
- source-file behavior
- accessibility semantics
- Back/Home/system navigation
- entitlement logic
- core reader capabilities

Themes may change:

- palette
- typography styling
- icon treatment
- motion personality
- focus styling
- surface treatment
- optional sound profile
- decorative motifs
- library view presentation

The default structural reference is:

`docs/design/CLASSIC_LIBRARY_REFERENCE.md`

This ensures a Premium theme feels like another ShelfOS experience rather than another application.
