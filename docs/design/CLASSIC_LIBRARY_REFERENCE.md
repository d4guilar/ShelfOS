# ShelfOS Classic — Library UI Reference v1

## Status

Phase 0 implements this hierarchy using fictional sample publications and
original geometric covers. There are no publication files or reader actions;
Continue Reading samples open details. Search filters sample titles/creators.
Favorite edits are demo session state. View/sort controls and metadata actions
are deferred rather than displayed as working features. Bottom navigation is
used below 600dp, with a rail for larger or short landscape windows; persistent
details require at least 1000dp width and 480dp height. Separating folds use the
larger unobstructed region in this prototype.

**Approved implementation reference for the ShelfOS Classic library surface.**

Visual reference:

`docs/design/references/ShelfOS_Classic_Library_v1.jpeg`

This concept image is local-only and excluded from Git. Public contributors should
use this written specification without requiring access to the image.

The image is a visual aid. This written specification is the source of truth if the mock contains ambiguous spacing, text, metadata, or platform details.

## 1. What this reference establishes

This reference locks the structural foundation shared by ShelfOS themes:

- global navigation
- Continue Reading
- Favorites / Books / Comics / Manga / Documents
- cover-first library browsing
- focused/selected library item state
- contextual detail pane on expanded layouts
- dedicated details screen on compact layouts
- metadata enrichment presentation
- responsive behavior between tablet/foldable and phone
- monochrome Classic visual language

Themes may restyle this structure but should not arbitrarily replace the information architecture.

## 2. Core visual principle

> **The interface is monochrome. The library is the color.**

Classic Light uses quiet grayscale application chrome and full-color publication artwork.

Primary visual hierarchy:

1. publication covers
2. publication title / creator
3. reading progress
4. actions
5. secondary metadata
6. technical file metadata

## 3. Expanded layout anatomy

Typical tablet / unfolded foldable layout:

```text
┌────────────────────────────────────────────────────────────────────┐
│ NAV │ ShelfOS                               Search      Settings    │
│     ├───────────────────────────────────────────────┬──────────────┤
│     │ Continue Reading                              │              │
│     │ [cover] Title  [cover] Title  [cover] Title   │              │
│     ├───────────────────────────────────────────────┤   SELECTED   │
│     │ ★ Favorites  Books  Comics  Manga  Documents │    DETAIL    │
│     │                                     Grid List│     PANE     │
│     │ [cover] [cover] [cover] [cover]              │              │
│     │ title   title   title   title                 │              │
│     │ [cover] [cover] [cover] [cover]              │              │
└─────────────────────────────────────────────────────┴──────────────┘
```

### Global navigation rail

Order:

- Library
- Search
- Notes
- Collections
- Settings

Requirements:

- compact
- monochrome
- keyboard/D-pad focusable
- selected destination obvious but restrained
- must never resemble a console launcher

### Header

Contains:

- approved ShelfOS monochrome logo + wordmark
- Search
- Settings where appropriate

Do not duplicate actions unnecessarily if adaptive navigation already exposes them elsewhere.

### Continue Reading

Purpose:

- instant resume
- recent context
- not a marketing carousel

Each item may contain:

- cover
- title
- creator
- progress line
- percentage

Keep compact. The library remains the primary screen.

### Category row

Permanent order:

1. ★ Favorites
2. Books
3. Comics
4. Manga
5. Documents

Active state:

- stronger label weight
- thin underline

Do not use colored chips or large Material pills in Classic.

### View / sort controls

Keep subtle.

Initial concept:

- Grid
- List
- Recent ▼

Future view modes:

- Grid
- Compact
- List
- Showcase

Do not overload the toolbar.

## 4. Cover grid

Covers should appear as media objects, not giant cards.

Each standard library tile may display:

- cover
- title
- creator
- reading progress when applicable

Avoid:

- large background cards
- colorful status badges
- excessive metadata
- decorative chrome around every cover

The cover should dominate.

## 5. Focused / selected publication

Focused state must work for:

- touch
- keyboard
- D-pad
- gamepad

Classic focus treatment:

- thin graphite border
- subtle 1–2% scale change
- restrained shadow/elevation
- instant update of detail pane

Avoid:

- glow
- pulse
- bounce
- particles
- neon
- gaming-style focus frames

## 6. Expanded detail pane

The detail pane is a first-class ShelfOS pattern.

Primary information:

- large cover
- title
- creator
- category
- series / volume when applicable
- publication year when known
- reading progress
- chapter/page/volume position where meaningful
- Continue Reading / Read action
- Favorite action
- short synopsis

Information hierarchy:

1. cover
2. title
3. creator
4. media identity / series
5. progress
6. primary action
7. synopsis
8. collapsed technical details

Technical information belongs inside **Details**, not in the main presentation.

Examples:

- ISBN
- language
- publisher
- file format
- file size
- import date
- source
- metadata provider / confidence

Example provenance line:

```text
Metadata
Open Library · Exact ISBN match
```

or:

```text
Metadata
Embedded in publication
```

or:

```text
Metadata
Edited by you
```

## 7. Compact phone layout

Phone structure:

1. compact ShelfOS header
2. Continue Reading horizontal row
3. horizontally scrollable category selector
4. adaptive 2–3 column cover grid
5. bottom global navigation

Bottom navigation:

- Library
- Search
- Notes
- Collections
- Settings

Do not truncate `Documents` merely to force all categories into the width. The category row should scroll horizontally.

## 8. Compact details screen

On phone, selecting a publication may open a dedicated details screen.

Contents:

- back action
- overflow menu
- large cover
- title
- creator
- media identity / volume / year
- progress
- Continue Reading / Read
- Favorite
- synopsis
- collapsible Details

Overflow may later include:

- Edit metadata
- Change cover
- Refresh metadata
- View file details
- Relink source
- Remove from ShelfOS

## 9. Metadata enrichment presentation

ShelfOS should make enriched metadata useful without making the app feel cloud-dependent.

The UI may display:

- title
- creator
- cover
- description
- series
- volume
- publication date/year
- publisher
- language
- ISBN/identifier
- categories/subjects

The user should not need to understand providers during normal use.

Provider/confidence information belongs in Details.

## 10. Theme boundary

All themes inherit the same semantic structure unless a specific adaptive layout requires a documented exception.

Themes may change:

- colors
- typography styling
- surface treatment
- focus appearance
- motion personality
- optional sounds
- decorative presentation
- Showcase presentation

Themes must not change:

- category meanings
- global navigation meanings
- metadata semantics
- reading progress semantics
- source-file ownership rules
- accessibility requirements
- Back/Home behavior
- core feature availability solely for aesthetic reasons

## 11. Motion

Classic motion:

- quick
- restrained
- functional

Preferred selected-item behavior:

- detail pane crossfade/update
- 100–150 ms focus transitions
- optional cover-to-reader expansion

Honor reduced-motion settings.

## 12. Implementation rule for agents

Do not treat the reference image as a pixel-perfect screenshot specification.

Implement the **behavioral and structural rules in this document** using idiomatic Compose, adaptive layouts, accessibility semantics, and real content.

If mock and written spec conflict, written spec wins.
