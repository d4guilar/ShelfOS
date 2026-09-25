# ADR-0021: Staged library-scale ingestion

## Status

Accepted, 2026-09-24. Product/architecture decision; not implementation completion.

## Context

The original import flow handles one file and places optional online enrichment
before commit. It cannot safely describe large folder imports or library migration.

## Decision

All Import Center entry points share discovery → staging → local analysis →
organization → review → commit → library → background enrichment. Model ImportSession,
ImportCandidate/StagedItem and ImportPlan separately from committed LibraryItems.
Use bounded local work, progressive duplicates, failure isolation and durable
checkpoints for bulk/process-death recovery. Folder hierarchy is evidence, not truth.
Reference files by default; optional managed copies preserve originals. Migration
adapters use exported/user-accessible content and never bypass DRM or app sandboxes.
Generic ZIP library import is another source adapter in this pipeline, not a comic
reader or parallel importer. It safely discovers multiple nested publication files,
stages one candidate per publication, preserves relative hierarchy as evidence and
extracts committed candidates into managed storage. The original archive remains
untouched. Generic ZIP, CBZ and ShelfOS backup archives have distinct semantic
interpretations even when they share bounded low-level archive utilities.

## Consequences and sequencing

This supersedes the pre-commit online-enrichment sequence in older Import/Metadata
specifications. The present single-file prototype is an incomplete first slice;
it must not be advertised as resumable library-scale ingestion. No one-class-per-name
architecture or immediate complete schema is mandated. Online enrichment, advanced
PDF analysis and future Calibre/OPDS adapters are not early import prerequisites.

Detailed source of truth: [DATA_INGESTION](../features/DATA_INGESTION.md).
Integration evidence: [coverage audit](../DECISION_INTEGRATION_AUDIT.md).
