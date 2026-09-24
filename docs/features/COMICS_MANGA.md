# Comics and Manga Specification

## Next-build plan

See [Phase 1 plan](../PHASE_1_PLAN.md) for the first PDF/CBZ reader work in progress.
Manga RTL defaults apply to both formats and remain user-overridable. Direction
changes navigation, not stored page order or the artwork itself. Initial AUTO
spread behavior resolves to single-page; adaptive pairing and cover-offset rules
remain later work. These features are not implemented in Phase 0.

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

## Series and PDF integration

[Series](SERIES.md) is shared with Books: volumes, collected editions, issues,
annuals and specials retain independent LibraryItems and files. Embedded
`ComicInfo.xml`, numbered titles and folder/source context provide reviewable
grouping/order evidence; manual order wins over natural numeric inference.
Multi-file and folder-as-Series import must avoid one-volume-at-a-time workflows.
Series details, progress and Continue Reading lead to optional virtual omnibus
transitions without merging archives or PDFs.

Image-dominant PDFs use Original page presentation and category-specific navigation;
[Adapted PDF](PDF_INGESTION.md) is not a default text replacement for artwork.
Manga RTL changes physical navigation/spread placement, never page identity, stored
sequence or image mirroring. Member transitions preserve the member's explicit
direction preference. Source files and per-item progress remain independently usable.
