# ShelfOS Classic UI Specification

## 1. Role

ShelfOS Classic is the default visual experience and the clearest expression of the ShelfOS brand.

Approved visual implementation reference:

`docs/design/references/ShelfOS_Classic_Library_v1.jpeg`

Detailed structural specification:

`docs/design/CLASSIC_LIBRARY_REFERENCE.md`

It should feel:

- monochrome
- editorial
- modern
- quiet
- cover-first
- premium
- platform-natural
- controller-friendly without feeling game-like

Core principle:

> **The interface is monochrome. The library is the color.**

## 2. Light palette

Suggested starting tokens:

```text
Canvas            #F7F7F5
Primary Surface   #FFFFFF
Primary Text      #111111
Secondary Text    #6B6B68
Divider           #E4E4E0
Focused Border    #151515
Muted Surface     #EFEFEC
```

Do not use a brand accent color as a requirement.

Full-color media covers provide the dominant color.

## 3. Dark sibling

ShelfOS Dark uses the same layout, spacing, components, motion, and iconography as Classic.

Suggested starting tokens:

```text
Canvas            #0B0B0B
Primary Surface   #161616
Primary Text      #F3F3EF
Secondary Text    #A4A4A1
Divider           #343434
Focused Border    #F3F3EF
Muted Surface     #202020
```

Avoid blue/neon accents in the default Dark theme.

## 4. Header

Default tablet/foldable header:

- ShelfOS wordmark at left
- Search action at right
- Settings action at right
- optional current-view controls

Header should remain visually light.

No oversized toolbar or decorative gradient.

## 5. Home / Library composition

Recommended hierarchy:

1. Header
2. Continue Reading
3. Library category selector
4. Cover library
5. Optional contextual/detail surface depending on width

### Continue Reading

May use a slightly richer card than the library grid.

Show:

- cover
- title
- author/creator
- progress
- Continue action

Keep the number of visible Continue Reading items modest.

## 6. Library categories

Permanent order:

- Favorites
- Books
- Comics
- Manga
- Documents

Classic presentation:

- plain text tabs
- active state indicated by stronger weight + thin underline
- `★ Favorites` appears first
- no rounded active-category pill in Classic
- no colored category chips
- compact screens scroll the row horizontally rather than truncating `Documents`

## 7. Cover grid

The standard grid is card-light.

Each item may show:

- cover
- title
- creator
- progress when useful

Avoid large background cards around every title.

Use predictable portrait proportions while respecting unusual comic/document thumbnails.

## 8. Focus and controller navigation

Focused item should be unmistakable without becoming game-like.

Suggested treatment:

- thin graphite/ivory outline
- very small scale increase (1.01–1.02)
- subtle elevation/shadow where appropriate
- metadata update/crossfade

No pulsing glow, bounce, particles, or arcade animation in Classic.

## 9. Phone layout

Suggested phone structure:

- compact header
- Continue Reading horizontal row
- category selector
- 2–3 column adaptive cover grid
- bottom global navigation

Global navigation:

- Library
- Search
- Notes
- Shelves
- Settings

## 10. Tablet / unfolded foldable layout

Expanded layouts may use:

- compact navigation rail
- adaptive cover grid
- optional persistent detail pane

Example:

```text
┌──────────────────────────────────────────────────────────────┐
│ ShelfOS                              Search      Settings     │
├──────────────────────────────────────────────────────────────┤
│ Favorites   Books   Comics   Manga   Documents              │
├────────────────────────────────────┬─────────────────────────┤
│ [cover] [cover] [cover] [cover]    │      LARGE COVER        │
│                                    │                         │
│ [cover] [cover] [cover] [cover]    │ Title                   │
│                                    │ Author                  │
│ [cover] [cover] [cover] [cover]    │ 62% read                │
│                                    │ Continue                │
│                                    │ Favorite                │
└────────────────────────────────────┴─────────────────────────┘
```

The detail pane should feel like a reading/library panel, not a game metadata screen.

## 11. Showcase mode

Showcase is an optional library view inspired structurally by media frontends.

Concept:

```text
       neighboring cover
              ↓
      [ LARGE COVER ]

          Title
        Creator
      62% complete

         Continue
```

Neighboring covers may peek at the edges.

Showcase is especially suitable for:

- tablets
- foldables
- controller devices
- TV-like large windows where supported later

Keep typography and motion restrained.

## 12. Reader transition

Preferred signature transition:

- selected cover subtly expands
- system chrome fades
- reader appears

Reverse on exit when practical.

The animation must be fast and optional under reduced-motion settings.

## 13. Icons

Use simple monochrome iconography.

Icons should visually align with the ShelfOS logo:

- clean geometry
- consistent stroke/fill behavior
- no colorful system icons in Classic

## 14. Empty states

Empty states should remain minimal and helpful.

Example:

```text
Your Books category is empty.

Import a book to get started.
[ Import ]
```

Avoid mascots or excessive illustration in Classic.

## 15. Success criteria

Classic succeeds when:

- the UI still looks premium with every cover temporarily replaced by gray placeholders
- real covers provide natural visual color without the system becoming chaotic
- touch, keyboard, and gamepad navigation feel equally intentional
- the interface does not resemble a gaming frontend despite supporting frontend-quality navigation
- light and dark modes feel like one design system

## Accepted organization extensions

The fixed category order remains unchanged. Future pinned [Shelves](../features/SHELVES.md)
follow it in the scrollable category area; unpinned Shelves remain in their global
destination. [Series](../features/SERIES.md) can appear as grouped covers opening a
member details page. These additions preserve Classic's cover-first geometry and
shared focus semantics. Existing application navigation still has a legacy label;
the Shelf specification records the pending implementation rename.
