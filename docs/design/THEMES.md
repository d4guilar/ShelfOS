# ShelfOS Themes

## Phase 0 implementation status

Classic and Dark are implemented through shared Compose tokens. Pear Platinum,
Pear Platinum Dark, and Deckle are registered but unavailable and marked Planned.
Their eventual complete design is described below. No Premium themes are
implemented. Theme selection is persisted locally in Room.

The current placeholder registrations in `core.theme` still carry the earlier
working IDs and labels (`RETRO_LIGHT`, `RETRO_DARK`, `PAPER`). Renaming them is
part of the Phase 6.5 theme work, not a Phase 0 change; their persisted storage
keys are migration-relevant.

## Planned reader presentation

The [Phase 1 plan](../PHASE_1_PLAN.md) adds capability-aware reader presets through
shared tokens. Supported per-title preferences override explicit global reading
preferences, which override theme defaults. Changing theme must preserve explicit
font, spacing and reading-direction choices. Reflowable book typography is separate
from Library UI typography. Original PDF and image-based pages retain their authored layout;
theme selection does not replace embedded fonts or recolor artwork by default.
This is planned behavior, not a capability of the Phase 0 prototype.

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
Default ShelfOS identity: native, neutral, modern and collection-first.

### Principle
**The interface is monochrome. The library is the color.**

### Default accent
ShelfOS Blue. A designed default used for selection, focus and progress; it never
replaces the monochrome logo or the cover-first hierarchy.

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

### Reference
`references/ShelfOS_Classic_Library_v1.jpeg` — approved Classic library visual
direction. Concept image only; see "Theme references" below.

---

## 2. ShelfOS Dark

### Role
Low-light sibling to Classic.

### Characteristics

- near-black canvas
- warm white text
- graphite/gray surfaces
- full-color covers
- no required neon or fluorescent accent
- identical geometry and navigation to Classic
- calm OLED-friendly presentation

### Default accent
ShelfOS Blue, matching Classic.

### Rule
Dark is not a separate visual concept. It is Classic under a dark material system.

---

## 3. Pear Platinum

### Role
Pear Classic theme family: light retro-computing / Platinum-inspired aesthetic.

### Characteristics

- tactile controls
- restrained bevels
- warm neutral surfaces
- full-color covers
- same ShelfOS structure as Classic; theme only, not a product redesign

### Default accent
Restrained Classic Blue.

### Rule
Do not copy third-party assets, icons, wallpapers, sounds, or proprietary UI
layouts.

---

## 4. Pear Platinum Dark

### Role
Dark sibling of Pear Platinum.

### Characteristics

- graphite / dark retro-computing interpretation
- same ShelfOS structure and Pear material language as Pear Platinum
- restrained bevels and tactile controls
- reduced brightness without losing contrast

### Default accent
Cool restrained blue.

---

## 5. Deckle

### Role
Paper / editorial / literary theme.

### Principle
**Interface is neutral. Collection is color.**

### Characteristics

- cream paper surfaces, warm ink
- editorial typography
- thin rules and restrained texture
- identity carried by typography, spacing and paper surfaces rather than bevels
- no fake wooden bookshelf

### Default accent
Oxblood.

### Target feeling
Old Penguin paperback + quiet reading room + modern product usability.

---

## Theme accent personalization

Each theme ships one carefully designed default accent.

A future release may let users choose from a curated set of accents compatible
with that theme's character. An unrestricted RGB picker is not the initial design,
and a curated choice must preserve the theme's visual character.

Accents recolor semantic roles (accent, selection, focus, progress) rather than
hard-coded color values, so every choice still looks like that theme.

| Theme | Default accent | Curated direction |
|---|---|---|
| Classic | ShelfOS Blue | Blue, Green, Purple, Amber, Red |
| Dark | ShelfOS Blue | Blue, Teal, Green, Purple, Amber |
| Pear Platinum | Restrained Classic Blue | Blue, Forest Green, Burgundy, Graphite, Amber |
| Pear Platinum Dark | Cool restrained blue | Cool Blue, Emerald, Amber, Plum, Ice |
| Deckle | Oxblood | Oxblood, Library Green, Navy Ink, Burnt Orange, Plum |

This is a future implementation direction only. No accent architecture, storage
model or settings UI is defined here.

## App theme, reading profile and page palette

The app theme/accent and the Reading Profile (page palette) are independent.

A Deckle app theme with a Library Green accent can still read in a Paper, Sepia,
Sage or Dark reading palette, and changing one never forces the other.

Comics and Original PDF pages remain source-faithful: theme colors and reading
palettes never recolor or replace authored artwork or embedded fonts.

Reader behavior is owned by `READER_UX.md`; implemented page-color behavior is
recorded in `../PHASE_2_PLAN.md`.

## Theme references (concept images)

Concept images are visual reference, not pixel-perfect implementation specs.

- Classic: `references/ShelfOS_Classic_Library_v1.jpeg` (concept images are
  local-only and excluded from Git; see `references/README.md`)
- Pear Platinum, Pear Platinum Dark, Deckle: no approved concept image yet, so the
  written specification on this page is authoritative

Implementation preserves the real ShelfOS layout, the monochrome logo assets,
accessibility requirements, semantic navigation and component behavior. If an
image and a written specification conflict, the written specification wins.

## Free-theme policy

The five themes above are the initial free theme set, and none of them may be
assigned to Plus/Premium in documentation or product messaging.

Pear Platinum and Pear Platinum Dark are the canonical project names. The
public-release naming review in `AGENTS.md` rule 24 still applies before launch.

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
│   ├── PearPlatinum
│   ├── PearPlatinumDark
│   └── Deckle
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

Placeholder code registrations still use the earlier IDs and labels; see the
status section above.

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

## PDF mode boundary

Future [Adapted PDF](../features/PDF_INGESTION.md) content can use shared reading
typography/palette presets; this derives a semantic view without changing the PDF.
Original mode preserves authored layout. Explicit per-title preferences win, and
themes never select a mode by changing category, Shelf, Source or Series identity.
All themes use Library / Search / Notes / Shelves / Settings.
