# ADR-0018: Dual PDF reading modes

## Status

Accepted, 2026-09-24. Product/architecture decision; not implementation completion.

## Context

Fixed-layout PDFs can benefit from reading typography without losing their authored
layout. Earlier "reflow/book-mode" descriptions did not define a structured model
or traceability contract.

## Decision

Support Original and, for compatible PDFs, derived Adapted presentation. Preserve
the source PDF, allow mode switching, remember per-title choices and recommend a
mode from local analysis. Future global defaults remain subordinate to title choices.
Adapted content uses structured PublicationDocument blocks and a SourceMap back to
original page/bounds/text ranges. Cross-mode position and annotation mapping is
explicitly best-effort. Keep EPUB's existing structure; do not reconstruct it as PDF.
Image-dominant Comics/Manga remain page-oriented.

## Consequences and sequencing

Original support precedes advanced Adapted ingestion; OCR stays later. Expensive
PDF analysis cannot block a bulk import. Low-confidence mapping must be disclosed,
with source revision awareness and preserved annotation anchors. This refines
ADR-0003 and ADR-0017 without moving advanced PDF work into Phase 1.

Detailed source of truth: [PDF_INGESTION](../features/PDF_INGESTION.md).
Integration evidence: [coverage audit](../DECISION_INTEGRATION_AUDIT.md).

