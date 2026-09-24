# Import feature entry points

Status: single-file import is Phase 1 work in progress; the library-scale system
below is accepted direction, not completed functionality.

## Entry points and shared contract

The Import Center offers Add Files (one or many), Add Folder (recursive discovery),
Add Series (multi-file/folder grouping) and Import Library (existing libraries or
future migration adapters). All use [DATA_INGESTION](DATA_INGESTION.md), the source
of truth for discovery, staging, local analysis, organization, review, commit,
background enrichment, duplicates, errors, recovery and migration.

Durable origin/access belongs to [Library Sources](LIBRARY_SOURCES.md); an import
session is not a Source. Android uses scoped document/tree access. Share/Open With
can be added later. Manual rescan precedes any optional scheduled scanning; no
guaranteed real-time folder watcher is assumed.

## Format and review surface

Initial reading scope: EPUB, PDF and CBZ. CBR, CB7, DOCX, TXT/Markdown and other
formats remain later work subject to capability/license review.

Review may show cover, title, creator, proposed category, Series/volume/issue,
year, provenance/confidence, duplicate warnings and Shelf assignments. User
classification is editable: Book, Comic, Manga or Document. Straightforward
single-file imports can minimize ceremony; bulk review must expose the plan.

Cover precedence is USER_SELECTED > EMBEDDED > HIGH_CONFIDENCE_ONLINE_MATCH >
FIRST_PAGE > GENERATED. Online covers arrive through optional post-commit
enrichment; expensive extraction must not block a batch. Generated covers should
look intentional in the active theme. User metadata and cover choices always win.

## Related specifications

- [PDF ingestion](PDF_INGESTION.md): Original/Adapted capability, recommendations and deferred analysis.
- [Series](SERIES.md): reviewable detection, natural order and bulk/folder creation.
- [Shelves](SHELVES.md): assignment during import and accepted folder suggestions.
- [Metadata enrichment](METADATA_ENRICHMENT.md): provider independence and provenance.
- [Phase 1 plan](../PHASE_1_PLAN.md): limited first reading build and gaps before bulk ingestion.

Original files are never modified by import. Reference existing files by default;
copy only by explicit choice. Removing library metadata or disconnecting a Source
does not delete source files. Future export/conversion produces a separate output.
Online metadata never gates local import or reading.
