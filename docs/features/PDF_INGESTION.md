# PDF ingestion and reading modes

Status: accepted product/architecture direction, 2026-09-24; advanced ingestion
is future work. Original-page PDF reading is in the unvalidated Phase 1 working
tree. No Adapted engine, PDF analysis service or SourceMap is implemented.
See [ADR-0018](../adr/0018-dual-pdf-reading-modes.md) and [roadmap](../ROADMAP.md).

## Modes and source preservation

**Original** renders the authored PDF pages. **Adapted** derives a structured
reading experience from compatible content. The original PDF remains the source
of truth: never overwrite, replace or silently modify it. Both experiences sit
behind ShelfOS-owned reader abstractions. Adapted is more than plain-text reflow.

Original suits research papers, textbooks, magazines, art books, manuals,
technical documentation, equations, tables, multi-column and heavily illustrated
layouts. Its eventual controls include fit width/page, zoom/pan, continuous scroll,
page snapping, thumbnails, dual-page views and orientation handling, bookmarks,
annotations, keyboard/gamepad paging, smart crop/margin trimming, optional night
treatment where appropriate, and later smart column navigation. These are a
capability roadmap, not promises of the first PDF renderer.

A physical-tablet test of *Dune* confirmed this boundary: Original worked, but its
authored font and white page could not be meaningfully restyled. That is an
Original-mode constraint, not a defect. When appearance changes are unavailable,
ShelfOS should communicate the reason elegantly and may suggest Adapted mode; exact
wording remains a design question.

Adapted suits novels, essays, text-heavy books, simple reports and single-column
documents with reliable reading order. Where extraction confidence permits, it
offers font family/size, line and paragraph spacing, margins, pagination or scroll,
day/night/sepia reading palettes, reconstructed chapter navigation, search,
bookmarks, highlights, notes, semantic navigation and the shared reader controls
and keyboard/gamepad commands. Theme presets must respect explicit reader choices.

## Choice, preference and recommendation

Normal UI says **View as → Adapted / Original**, not "semantic reconstruction".
Persist `ADAPTED` or `ORIGINAL` per PDF-backed LibraryItem. Switching is available
where the requested capability exists; Original remains available for readable
PDFs. A recommendation never permanently restricts the user's choice. Explain
unavailable Adapted support and disclose low confidence rather than pretending
reconstruction is exact.

A later global **PDF default** offers Use ShelfOS recommendation (preferred
default), Prefer Adapted, Prefer Original, or Ask on import. The per-title choice
wins. Optional future Shelf defaults must have explicit conflict/precedence rules
before implementation; multiple Shelf memberships cannot silently change a title.

`PdfAnalyzer` considers text/image density, number of columns, reading-order
confidence, font consistency, tables, figures, equations/formulas, repeated
headers/footers, page geometry consistency, OCR need and overall layout complexity.
Internal assessments include `TEXT_NATIVE`, `OCR_REQUIRED`, `COMPLEX_LAYOUT`;
these describe capabilities/confidence and need not be exclusive or user-facing.
Text-native content is not automatically safe to reconstruct.

A text-rich single-column novel may recommend Adapted; a paper with columns,
tables and figures may recommend Original while allowing Adapted if supported.
Preparation UI may show detected text, chapters, reconstructed order and filtered
headers only when those analyses actually succeeded. Show complexity warnings for
papers; do not invent detected counts or confidence.

## Semantic document model

The derived pipeline is:

```text
Original PDF → analysis → text extraction → reading-order detection
→ heading/paragraph detection → repeated header/footer filtering
→ image/figure extraction → caption/footnote identification where possible
→ PublicationDocument → ShelfOS reader
```

`PublicationDocument` contains chapters and structured blocks: Heading, Paragraph,
Image, Caption, Quote, Footnote, List, page-reference information and extensible
semantic block types. Preserve
structure and provenance, rather than concatenating extracted text. Exact storage,
parser selection and schema remain open until implementation is scoped.

## SourceMap and annotations

`SourceMap` connects semantic block identities to source page, source bounds,
source text range where available, and mapping confidence. OCR-derived blocks also
retain recognition confidence rather than discarding uncertainty. Retain source revision
identity so a map for an old file is not applied to changed content. For example,
paragraph 483 may correspond to page 184 and its bounding region.

Use this map for Adapted → Original and Original → Adapted position restoration,
**View original page**, source traceability, annotation association and future
export/navigation. Switching should retain approximately the same passage where
possible. Partial, ambiguous and absent mappings must be represented explicitly;
offer the closest known page or a clear position fallback, never fabricate precision.

Aim for one logical annotation system. Adapted highlights retain semantic anchors
plus available source mappings; Original annotations retain original page geometry
and associate with extracted text when possible. Scans, OCR-derived text, complex
layouts, unusual encodings and graphical pages make bidirectional mapping best-effort.
Preserve the original annotation and its anchor even if another view cannot display
it. Reanalysis or a changed source must not silently move/delete annotations; follow
[changed-publication review](LIBRARY_SOURCES.md#changed-publications).

## Format distinctions

- Reflowable EPUB already has structured content: parse that publication directly.
  Do not run it through PDF reconstruction. Fixed-layout EPUB remains capability-dependent.
- Future DOCX can parse document structure into `PublicationDocument`; this does
  not authorize adding DOCX now.
- Image/page-dominant Comic/Manga PDFs use their page reader and category defaults,
  including overridable Manga RTL. They must not default to text-centric Adapted
  presentation or replace artwork with reflowed dialogue. Later OCR could support
  dialogue search, accessibility, translation or speech-bubble text lookup while
  preserving artwork. These are future possibilities, not OCR commitments now.

## Scanned publications and OCR

OCR-required scans remain part of Adapted PDF, not a user-facing third mode:

```text
Scanned PDF → detect OCR need → local OCR → layout/reading-order analysis
            → PublicationDocument → Adapted reader
```

The original scan remains untouched. A fully local `OcrEngine` path is required
and is the default architectural direction; optional remote OCR could only be a
later, explicit enhancement. No engine is selected here. Engine choice, model size
and packaging, language support, and remote policy remain research questions.

Future confidence-aware UX may show uncertain text, open the original page or accept
a user correction. Corrections override interpreted text without modifying the
source. Exact UI remains open.

Conceptually, `OcrEngine` has a required/default `LocalOcrEngine` adapter and may
later admit an optional `RemoteOcrEngine`. Core scanned-publication support must not
depend on the remote adapter.

## Ingestion scheduling and scope

Follow [DATA_INGESTION](DATA_INGESTION.md). A single PDF's review may show a cheap
analysis recommendation if ready; it must not wait for expensive reconstruction.
For a large import: basic discovery/import → usable LibraryItem → background PDF
analysis → recommendation → advanced reconstruction when needed. Pending analysis
is an honest state. One difficult scanned PDF must not hold up other publications.

Original support, progress, bookmarks/search where supported and basic analysis
precede advanced Adapted support. Semantic documents, SourceMap and cross-mode
annotations arrive in the advanced PDF milestone. OCR is deferred further and
must never become a prerequisite for early PDF import or Original reading.
