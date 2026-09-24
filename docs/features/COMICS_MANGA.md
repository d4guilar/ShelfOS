# Comics and Manga Specification

## Shared engine

Comics and Manga should share image-sequence infrastructure where possible.

They remain separate user-facing categories.

## CBZ

A CBZ is treated as an archive containing ordered image pages.

Requirements:

- safe archive extraction/streaming
- natural file sorting
- common image format support
- corrupt image handling
- page count
- thumbnails

## Comic defaults

```text
direction = LEFT_TO_RIGHT
spread = AUTO
```

## Manga defaults

```text
direction = RIGHT_TO_LEFT
spread = AUTO
```

## User overrides

Allow per-title override for:

- reading direction
- spread behavior
- fit mode

## Later

- CBR
- guided panels
- automatic panel detection
- page enhancement
- crop detection
