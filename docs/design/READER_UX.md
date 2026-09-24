# ShelfOS Reader UX

## Goal

Reader screens should prioritize content and reduce interface noise.

ShelfOS branding belongs primarily in the library and navigation experience.

## Shared reader principles

- resume exactly where possible
- tap/click/button to reveal controls
- obvious exit/back behavior
- progress always recoverable
- preserve source
- readable defaults
- support touch, keyboard, and gamepad

## Book reader

Primary capabilities:

- pagination
- scroll mode
- chapter navigation
- typography controls
- bookmarks
- highlights later
- notes later
- search

Controls should disappear when not needed.

## PDF / Document reader

Primary capabilities:

- original page
- fit width
- fit page
- zoom
- page navigation
- bookmarks
- search where supported
- annotations later

Future:

- Focus mode
- Reflow mode
- Study mode

## Comic reader

Defaults:

- left-to-right
- single page
- fit page
- optional spread
- thumbnails
- quick page turns

## Manga reader

Defaults:

- right-to-left
- manga-aware page order
- optional spread
- thumbnails

The user must be able to override direction.

## Reader command philosophy

Touch gestures, keys, and controller buttons should invoke common commands.

Avoid separate feature implementations for each input type.

## Leaving the reader

Back should:

1. close transient UI if open
2. close reader to the library
3. allow normal Android app exit behavior

Do not trap the user inside a theme or reading mode.
