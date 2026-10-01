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
They are official ShelfOS reinterpretations, not alternate applications: they preserve
ShelfOS information architecture, navigation, core interaction behavior,
accessibility and recognizable product identity.

> **Same ShelfOS. Different personality.**

Premium themes ship as **theme packs** ($4.99 each, 4–5 themes) or through
**ShelfOS Plus** ($14.99 lifetime launch direction). Pack contents, pack → Plus
upgrade fairness and the lifetime boundary are owned by
[PREMIUM.md](../features/PREMIUM.md).

Everything named below is an **inspiration reference only**. ShelfOS never ships
proprietary logos, characters, copyrighted artwork, copied UI assets, proprietary
sounds or copied boot animations.

## Retro Systems — 5 themes

### Terminal

- terminal / command-line interface; ASCII-art influence; monospace-heavy language
- low-level computer / system-console personality with a Linux/Unix boot-log mood
- *Serial Experiments Lain* is an internal mood reference only
- accent controls the primary code / terminal-highlight color; possible curated
  directions include phosphor green, amber, ice blue, white and red
- optional effect: one very short terminal/Linux-style boot sequence
- no copied franchise imagery, fonts, logos, dialogue or interface assets

### Pagestation

Public name **Pagestation**; this replaces the earlier working name *Memory Orbit*.

- early-2000s console-system atmosphere: dark, spacious, restrained blue/cyan or
  alternate accent glow, floating system-like geometry, subtle motion
- memory-card / system-browser feeling while ShelfOS structure stays intact
- accent may control the principal system glow / light color
- optional effect: one very short console/system-startup-inspired sequence
- internal visual references only: *PS2 WebXperience* (startup-sequence atmosphere,
  dark virtual-dashboard mood, restrained scanline/glow/motion rhythm) and
  *OPL-Theme-PS2pops* (a small handmade loader/startup animation, restrained
  console-menu presentation)
- do not copy PlayStation logos, BIOS assets, proprietary sounds, commercial artwork,
  original boot sequences, copyrighted interface graphics or source code unless
  separately verified as license-compatible and actually needed

---

### Glassline

- Windows Vista / Aero-era inspiration: translucent layered glass, soft aurora lighting
- glossy but tasteful controls; late-2000s optimistic computing character
- do not copy Microsoft logos or proprietary assets

### MonoDot

- bitmap / monochromatic computing: 1-bit / low-bit visual language
- bitmap typography, stark graphic hierarchy, intentionally restricted palette
- optional accent behaves like a single alternate system color

### Deckwave

- SteamOS / handheld-console-library inspiration: cover-forward, modern dark interface
- controller-friendly visual rhythm with a restrained contemporary presentation
- do not clone SteamOS or ship Valve/Steam proprietary assets

---

## Pop & Print — 4 themes

### Sprite Clash

- 1990s / early-2000s arcade fighting-game energy
- sprite-inspired visual details, bold graphic framing, energetic accent colors
- *Marvel vs. Capcom* is an internal inspiration reference only
- do not use Marvel/Capcom characters, copyrighted sprites, logos or proprietary assets

### Halftone Hero

- 1990s American superhero-comic energy: ink, halftone, dramatic framing
- bold caption/panel character
- the Alex Ross / Jim Lee era may be referenced internally for broad mood and era
- do not imitate or reproduce specific protected artwork or characters

---

### Pocket Pixel

- late-1990s / early-2000s handheld RPG aesthetic
- creature-collector-era pixel UI, compact menus, playful map/menu language
- *Pokémon* is an internal inspiration reference only
- do not use Pokémon names, characters, sprites, logos or proprietary assets

### Manga Print

- manga / Japanese print-editorial influence, black-and-white print personality
- screentone and ink inspiration, restrained accent color
- usable across all ShelfOS content categories
- avoid turning the entire UI into novelty manga panels

---

## Dream Internet — 4 themes

### AeroBloom

- Frutiger Aero: optimistic technology/nature relationship
- sky, water, glass, greenery; Y2K-to-late-2000s digital optimism
- colorful while preserving readability

### Neo Shibuya

- Japanese cyberpunk: dense but controlled neon, urban-night signage
- technological editorial presentation
- accent strongly influences the main neon/highlight family
- avoid generic gamer-RGB styling

### Sakura Mist

- cherry blossom / Japanese seasonal inspiration, soft surfaces
- subtle pink botanical accents, calm and elegant mood
- possible washi / traditional Japanese material influence
- avoid caricature or excessive kawaii styling

### ClipPop 2000

- maximalist Y2K web/desktop energy: clip-art, stickers, chrome, layered graphics
- playful early-web chaos; may be intentionally busy
- core ShelfOS usability must remain intact

## Theme effects (optional)

Official themes may include small optional effects that strengthen their personality
(for example Terminal's and Pagestation's short startup sequences).

- effects are optional, and enabled by default because they contribute personality
- users always have an easy toggle to disable them
- ShelfOS remains fully usable with effects disabled
- effects must be short and must never repeatedly block library or reader access
- Reduced Motion / accessibility preferences take priority
- no network dependency, no proprietary boot animations, no proprietary sounds, no
  copied commercial assets

ShelfOS may recommend leaving them enabled; it never forces them.

## Community and custom themes (post-launch)

Community/custom theme support — importing, exporting, personal themes, sharing and an
open documented format — is a **post-launch** investigation only.

> **Do not implement community/custom theme import for the initial release.**

Reconsider it only after public launch, and only with either a **VERY active
community** or **substantial repeated user demand**. Before that, ShelfOS should
already have a mature internal theme engine, stable semantic tokens, several official
themes in real use, real-world accessibility validation, stable cross-device behavior
and enough demand to justify supporting a public format. Do not freeze a public theme
schema, build an importer, or take on long-term format compatibility prematurely.

If it ever ships:

- importing and using your own or community themes stays **free** — ShelfOS does not
  charge permission to use a theme the user created or obtained elsewhere
- **themes are data, not executable code**: no Kotlin, Java, JavaScript, shell scripts,
  plugins or arbitrary executable logic; a theme may declare a manifest, semantic
  colors, typography/component tokens, safe image assets, a preview image and
  references to ShelfOS-provided effects
- community animation must reference ShelfOS-controlled built-in effects rather than
  arbitrary executable animation
- the exact format is undecided; a future importer would need explicit design for
  archive-size limits, path-traversal prevention, strict schema validation, allowed
  asset formats, image-dimension limits, safe font handling, format versioning,
  fallback to a safe official theme, easy uninstall/reset and preview-before-apply
- community themes change presentation, never core navigation or functionality
- ShelfOS does not become a marketplace: no user storefronts, creator payouts, ratings,
  reviews, accounts, hosted distribution, moderation infrastructure or paid user themes.
  Distribution can happen externally (community chat, repositories, community sites,
  later a subreddit, or direct file sharing); ShelfOS needs only a safe import
  mechanism, if and when demand justifies it. A curated showcase is not roadmap scope.

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
    ├── RetroSystems       (pack: 5)
    │   ├── Terminal
    │   ├── Pagestation
    │   ├── Glassline
    │   ├── MonoDot
    │   └── Deckwave
    ├── PopAndPrint        (pack: 4)
    │   ├── SpriteClash
    │   ├── HalftoneHero
    │   ├── PocketPixel
    │   └── MangaPrint
    └── DreamInternet      (pack: 4)
        ├── AeroBloom
        ├── NeoShibuya
        ├── SakuraMist
        └── ClipPop2000
```

13 premium themes in three packs at launch. Every theme ships one designed default
accent plus its standard curated accent set, and the same semantic accent roles apply
to premium themes as to free themes.

Do not hard-code theme branches across unrelated screens.

Placeholder code registrations still use the earlier IDs and labels; see the
status section above.

## Theme implementation rule

A theme should be data/configuration-driven where possible.

Theme-specific custom components or layouts are allowed only when the experience genuinely requires them, and they must still conform to shared navigation/accessibility semantics.
## Theme localization

Themes must not assume English-only ShelfOS UI copy. Any ShelfOS-owned copy a theme
displays — for example Terminal's `READY` and system-status labels and boot-sequence
strings, Pagestation's theme labels and startup/effect settings, or Deckle and Pear
theme supporting copy — resolves through the same application localization resources as
the rest of ShelfOS.

A theme may style localized text, but it must not own a separate translation mechanism,
and reference-theme terminology is not translated content.

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
