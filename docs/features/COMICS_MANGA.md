# Comics and Manga Specification

## Current status

Basic PDF/CBZ reading and Manga RTL defaults were accepted in Phase 1
([Phase 1 plan](../PHASE_1_PLAN.md); ADR-0017), and the Phase 3 fixed-layout/comic
reader is **complete and accepted (2026-10-09)**: single-page reading, explicit
two-page SPREAD mode, AUTO mode, page thumbnails and a page navigator,
progress/resume, zoom and fit behaviors, LTR/RTL navigation, keyboard/controller
navigation, tablet and foldable-aware layouts, and native CBR reading. Direction
changes navigation, not stored page order or the artwork itself.

AUTO semantics (final, accepted): AUTO normally reads one source page at a time at
every width and never pairs ordinary portrait pages; a genuine authored wide source
page is displayed whole. SPREAD is the explicit pairing mode for normal pages.
See `../PHASE_3_IMPLEMENTATION_PLAN.md` §35.

## Shared engine

Comics and Manga should share image-sequence infrastructure where possible.

They remain separate user-facing categories.

CBZ and CBR are container adapters, not separate reading systems. Both are
implemented and accepted (Phase 3, 2026-10-09):

```text
CBZ/ZIP → ZipPageSource ───────────────┐
                                       ├── PageSource → ImagePageRenderer
CBR/RAR → RarContainer/RarPageSource ──┘
```

CBR is read natively and directly through libarchive (pinned 3.8.9, 2-clause BSD;
see [ADR-0024](../adr/0024-native-cbr-libarchive.md)): RAR4 and RAR5, including
solid archives, with no conversion or repacking of the source file. Encrypted
archives are unsupported and report a truthful error. Archives are read through
Android SAF/PFD/FD boundaries rather than archive filesystem paths. Credible public
demonstration of polished mixed-format comic Series should still wait until Series
itself is implemented; both container paths now exist.

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

## Fidelity and immersive presentation

ShelfOS must not visibly degrade the source. Prioritize source-faithful output,
resolution-aware rendering, high-resolution zoom/re-render, and appropriate
filtering/scaling. Optional enhancement or super-resolution is much later research
and cannot disguise an undersized render/cache path. Fidelity must remain usable
on modest hardware.

A native CBZ field test on a Samsung Galaxy Tab A found *Dawn of X (2020)* sharp,
clear and immersive, with hidden chrome allowing the artwork to dominate. This is
qualitative evidence that a good native comic should feel comparable in quality
and focus to a dedicated reader. It also weakens the hypothesis that the general
comic renderer is inherently blurry. The earlier blurry New X-Men PDF remains an
investigation into source quality, embedded image resolution, PDF render resolution,
bitmap/cache resolution and zoom re-render behavior; it is not yet evidence of a
defective renderer.

Already-sharp sources should be left alone. Do not introduce sharpening artifacts,
color shifts, unnecessary memory pressure or page-turn latency merely to claim an
enhancement. Optional processing should address a demonstrated deficiency and remain
subordinate to source fidelity.

## Large-publication scale (future)

Future requirement; not implemented, and not part of Phase 4 (Notes and Knowledge
Layer). Comics and Manga are the formats most likely to encounter very large files:
multi-gigabyte CBZ and CBR archives of huge page images. The reader-wide acceptance
criteria (time to first readable page, memory while opening and during long sessions,
random and sequential navigation, thumbnail generation and navigation,
zoom/high-resolution re-render, archive access behavior, recovery under Android memory
pressure, reopening/resuming, large page counts, unusually large individual page
images, and behavior on modest/older hardware) are canonical in
[Reader](READER.md#reader-performance-and-scale-hardening-future).

Comic-specific aspects that future acceptance must also cover:

- archive access behavior for CBZ and CBR (including solid RAR archives) without
  requiring the whole archive in memory to begin reading, and without a full
  extract-before-read path
- thumbnail generation and thumbnail navigation for very large page counts
- zoom / high-resolution re-render of unusually large individual page images
- bounded memory while paging and while generating thumbnails
- truthful, recoverable behavior when the device cannot handle a given archive

Current evidence is measurement-level only for large CBZ (see
[validation](../VALIDATION.md)); no multi-GB CBR or PDF acceptance exists, and no
maximum supported file size is committed. Large-file support here is a
performance/reliability requirement, not a format-support checkbox.

## Phone-first zoom gestures (future)

Future reader UX requirement; research only, not implemented, and not part of Phase 4.
Pinch-to-zoom works, but repeated pinch gestures can make phone comic/manga reading
cumbersome. ShelfOS should investigate phone-first and potentially one-handed
interactions — double-tap zoom, double-tap-and-drag / hold-and-drag zoom, anchored
zoom toward tapped content, quick return to fitted page, and other established reader
patterns — so one-handed phone reading stays comfortable.

The canonical requirement — candidate behaviors, the coexistence rules (ordinary
pinch-to-zoom, page turning, edge taps, center-tap chrome, immersive/fullscreen
reader behavior, keyboard/controller navigation, future fixed-layout annotation
interactions and accessibility) and the bounded research-first process before any
gesture is chosen — is recorded in
[Reader](READER.md#phone-first-zoom-and-gesture-refinement-future). This describes
behavior, not competitor cloning; no other application's gesture set is to be copied
wholesale.
