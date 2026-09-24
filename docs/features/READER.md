# Reader Feature Specification

## Shared goals

All readers should support:

- open
- close
- current position
- progress persistence
- resume
- semantic input commands
- Back behavior
- error recovery

## Books

Initial:

- EPUB
- compatible PDFs via PDF reader

Features:

- page/scroll modes where supported
- chapters
- text preferences
- bookmark
- search

Later:

- highlight
- notes
- advanced reflow

## Documents

Initial:

- PDF

Features:

- original layout
- zoom
- fit width
- fit page
- search where available
- bookmark

Later:

- annotations
- reflow
- Focus mode
- Study mode

## Comics

Initial:

- CBZ

Features:

- single page
- page turn
- fit page
- fit width
- progress
- thumbnails
- LTR default

## Manga

Initial:

- CBZ

Features:

- same rendering core as Comics
- RTL default
- page turn
- progress
- thumbnails

## Reader engine selection

Reader engine should be chosen by content capability, not only file extension.

## Source preservation

Reader must not write modifications into the original publication.
