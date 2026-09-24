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

Use **View as → Adapted / Original** when supported. Original keeps the source
layout with fit/zoom/page controls; Adapted eventually supplies comfortable
structured typography. Recommend a mode without locking the user into it, remember
per-title choice and disclose low reconstruction confidence. Do not expose internal
analysis state names or unavailable controls as working capabilities.

Mode switching and **View original page** use best-effort SourceMap positioning;
make approximate/unavailable mappings understandable and retain annotations.
Original remains available. Advanced reconstruction, OCR and Study/Focus tools
are later work. See [PDF ingestion](../features/PDF_INGESTION.md) for capabilities,
recommendation signals, mapping limitations and sequencing.

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

## Series boundaries

Optional **Read as Omnibus** uses a restrained completion/next-member surface,
for example "Volume 3 complete — Continue to Volume 4". Continue Reading restores
the current member and passage. Next/Previous can cross member boundaries in that
mode; keep missing-member recovery, end-of-Series and normal Back behavior clear.
Preserve each title's presentation/direction preferences and avoid transition
animation that delays reading. See [Series](../features/SERIES.md).
