# Samsung tablet field test — September 2026

Status: qualitative product research. This is historical evidence, not a feature
specification, formal benchmark, or claim that future capabilities are implemented.
Permanent behavior is defined by the linked product, feature and design documents.

## Device and session

- Samsung Galaxy Tab A 10.1 (2016), model SM-T580
- Android 8.1.0 / Samsung Experience 9.5
- approximately 2 GB RAM
- physical, owner-operated session
- APK transferred over local Wi-Fi because USB data did not enumerate; the tablet
  was not connected through ADB

ShelfOS remained surprisingly responsive. General interaction was usable and pleasant,
reader controls felt excellent, the tablet form factor worked well, and no obvious
low-memory usability failure occurred during the session. These observations are
qualitative; no memory, frame-time or other ADB metrics were captured.

The result supports an important product opportunity: an inexpensive or repurposed
Android tablet can become a compelling dedicated personal reader. Future OCR,
rendering, metadata, cover, theme, animation and indexing work must protect this
lightweight baseline whenever technically reasonable.

## Publication observations

### Native CBZ

*Dawn of X (2020)* in native CBZ was sharp and clear. Fullscreen presentation was
highly immersive, and hiding controls let the artwork dominate. The experience felt
comparable in spirit to a dedicated commercial comic reader.

This result weakens the hypothesis that ShelfOS's general comic renderer is inherently
blurry. The earlier blurry New X-Men PDF needs controlled investigation of source
quality, PDF image/render resolution, bitmap/cache resolution and zoom re-rendering.

### CBR workflow

The original comic was CBR and could not be opened directly. CBR-to-CBZ conversion
during this test was unsuccessful; CBR-to-PDF worked but is not an acceptable routine
workflow. This supports high-priority future CBR support through the same image-sequence
reader semantics as CBZ, subject to RAR dependency/license review.

### Original PDF

*Dune* worked technically in Original PDF mode, but its source typography and white
page could not be meaningfully restyled. This validates the Original/Adapted boundary:
Original preserves authored presentation; future Adapted mode reconstructs compatible
content into ShelfOS presentation. It is not an Original-mode defect.

## UX and visual observations

Fullscreen comic reading was strongest with chrome hidden, but restoring controls was
not obvious. The resulting product problem is reader immersive-mode rediscoverability;
the exact interaction remains open.

The ShelfOS launcher icon looked visibly blurrier than nearby Android 8.1 icons. Cause
was not established. Resource type, density scaling, adaptive/legacy fallbacks and
padding require later inspection without redesigning the approved logo.

## Product conclusions

The field evidence supports, but does not itself specify, these directions:

- preserve source fidelity while remaining usable on modest hardware;
- treat CBR and CBZ as containers feeding one image-sequence reader;
- keep Original PDF truthful and make Adapted PDF the future restyling path;
- keep core reading offline-complete and use online services only for enhancement;
- make immersive controls discoverable without cluttering the publication.

Sources of truth: [product principles](../PRODUCT.md#14-product-principles),
[Comics/Manga](../features/COMICS_MANGA.md),
[PDF ingestion](../features/PDF_INGESTION.md),
[Reader UX](../design/READER_UX.md), and
[visual identity](../design/VISUAL_IDENTITY.md).
