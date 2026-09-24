# Library Feature Specification

## 1. Role

The Library is the primary ShelfOS surface.

It should make local media feel like a coherent owned collection rather than a folder of files.

Approved Classic visual reference:

`docs/design/references/ShelfOS_Classic_Library_v1.jpeg`

Implementation specification:

`docs/design/CLASSIC_LIBRARY_REFERENCE.md`

## 2. Main category order

1. ★ Favorites
2. Books
3. Comics
4. Manga
5. Documents

Favorites is a cross-category view.

A favorited Manga item remains `MANGA`.
A favorited Document remains `DOCUMENT`.

## 3. Global navigation

Global ShelfOS destinations are distinct from media categories:

- Library
- Search
- Notes
- Collections
- Settings

Expanded layouts may use a navigation rail.

Compact phone layouts may use bottom navigation.

## 4. Continue Reading

A high-priority, compact resume surface.

Each item may include:

- cover
- title
- creator
- progress
- percentage

Behavior:

- opens directly to saved reading position
- ordered by recent meaningful reading activity
- should not become an oversized marketing carousel

## 5. Standard cover grid

Initial default view.

Each tile should prioritize:

- cover
- title
- author/creator
- subtle progress if applicable

Avoid oversized card containers.

Covers provide most of the visual color in Classic.

## 6. Focus / selection

Required for:

- keyboard
- D-pad
- gamepad
- accessibility focus

Classic focused item:

- thin monochrome outline
- restrained elevation
- tiny scale change
- updates detail pane on expanded layouts

Touch users should not be forced into focus-heavy interaction.

## 7. Contextual detail pane

On sufficiently expanded layouts, selecting/focusing a publication exposes a persistent detail pane.

Primary fields:

- cover
- title
- creator
- content type
- series
- volume
- year
- reading progress
- current position
- synopsis
- primary read/continue action
- Favorite action

Secondary technical metadata belongs inside collapsed `Details`.

## 8. Phone details

On compact layouts, publication details use a dedicated screen rather than a permanent side pane.

Include:

- back
- overflow
- cover
- title
- creator
- progress
- Read / Continue
- Favorite
- synopsis
- Details

## 9. Metadata provenance

The detail surface may communicate provenance unobtrusively.

Examples:

```text
Open Library · Exact ISBN match
Embedded in publication
Edited by you
```

Do not surface provider mechanics throughout the main UI.

## 10. Views

Initial:

- Grid

Planned:

- Compact
- List
- Showcase

Showcase is the most frontend-inspired presentation but remains a library view, not a launcher mode.

## 11. Sorting

Early:

- recently opened
- recently added
- title
- author/creator

Later:

- series
- progress
- publication date
- manual ordering

## 12. Filtering

Primary categories provide the main filter.

Later:

- unread
- in progress
- finished
- collections
- tags
- creator
- series

## 13. Search

Library-level search should eventually search:

- title
- creator
- series
- identifiers
- tags
- collections

Later full-text publication search is a separate capability.

## 14. Source unavailable

If the original source becomes unavailable:

- preserve LibraryItem
- preserve metadata
- preserve cover cache
- preserve annotations
- preserve progress
- mark source unavailable
- offer relink later

Do not silently delete the item.

## 15. Manual management

Users should be able to:

- edit metadata
- change cover
- change category
- toggle Favorite
- add/remove collections
- refresh metadata
- inspect source details
- relink source later
- remove item from ShelfOS

Removing from ShelfOS must not delete the source file by default.

## 16. Theme behavior

Themes may alter:

- visual tokens
- typography styling
- animation
- focus treatment
- decorative surfaces
- optional sounds
- view presentation

Themes must preserve:

- navigation semantics
- category semantics
- metadata hierarchy
- source ownership rules
- accessibility
- normal Android exit behavior
