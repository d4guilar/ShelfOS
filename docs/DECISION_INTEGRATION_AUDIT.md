# Ingestion and organization decision integration audit

Date: 2026-09-24. Scope: documentation reconciliation only.

## Origin and audit status

The supplied file was found under `docs/`, not the repository root:
`docs/SHELFOS_CRITICAL_INGESTION_AND_ORGANIZATION_DECISIONS.md`.
Original SHA-256: `6317c69d952df01e3a13564dff8b84d3b834035179a097324ece75317abda7b8`.

Status: full documentation coverage audit passed. All 112 checklist items and
the complete specification were reread and reconciled. The temporary specification
was deleted only after coverage, link, diff and scope checks passed; implementation
remains unfinished.

## Integration map and ADRs

| Accepted area | Detailed source of truth | Decision |
| --- | --- | --- |
| Dual PDF modes | [PDF_INGESTION](features/PDF_INGESTION.md) | [ADR-0018](adr/0018-dual-pdf-reading-modes.md) |
| Series / virtual omnibus | [SERIES](features/SERIES.md) | [ADR-0019](adr/0019-series-virtual-aggregation.md) |
| Manual / Smart Shelves | [SHELVES](features/SHELVES.md) | [ADR-0020](adr/0020-categories-series-and-shelves.md) |
| Library-scale ingestion | [DATA_INGESTION](features/DATA_INGESTION.md) | [ADR-0021](adr/0021-staged-library-ingestion.md) |
| Persistent Sources | [LIBRARY_SOURCES](features/LIBRARY_SOURCES.md) | [ADR-0022](adr/0022-persistent-library-sources.md) |

The map was established before editing. [Architecture](ARCHITECTURE.md) owns
taxonomy, relationships and responsibility boundaries; feature specs own detail.
[Product](PRODUCT.md) and [README](../README.md) summarize direction; the
[roadmap](ROADMAP.md) owns sequencing. Related feature/design docs link to the
detailed contracts instead of duplicating them. ADR numbering continues after 0017.

## Conflicts and resolutions

| Previous statement / ambiguity | Accepted resolution |
| --- | --- |
| Collections was a global destination and schema/package suggestion | The latest owner decision makes Shelves canonical; existing code labels/routes remain documented migration debt. No separate Collections feature |
| Import and metadata diagrams put online lookup before commit | ADR-0021 moves online enrichment after local commit; local import/reading never waits on providers |
| Source URI described both origin and file identity | ADR-0022 separates durable Source, platform references and stable item UUID; ImportSession is process state |
| Reflow/book-mode shorthand lacked a document/mapping contract | ADR-0018 defines structured Adapted + Original, SourceMap, confidence and annotation limits; Original stays early, Adapted Phase 5 and OCR later |
| Phase 1 plan said no code/dependency work had begun | Prior owner authorization started unfinished work; README/plan/architecture/roadmap now separate that working tree from historical Phase 0 acceptance |
| ViewModel-only single-file retry could be mistaken for bulk recovery | Existing limitation is explicit; durable sessions/staging/recovery are gates before bulk/folder ingestion ships |
| Prototype removal deletes an owned private copy | Latest non-destructive contract governs future managed Sources; implementation mismatch is recorded in architecture/Phase 1 and must be corrected in code later |
| Temporary single-PDF examples analyze before commit, while large imports must not wait | Cheap ready analysis may inform review; expensive analysis/reconstruction runs later and never holds the batch |
| Suggested sequence lists PDF foundation after organization, but approved Phase 1 already includes Original PDF | Retain the approved first-reader slice; roadmap separates Original hardening/basic analysis from later Adapted work and all ingestion increments |
| Contribution guidance requested UI screenshots | Existing owner policy keeps concepts/prototype screenshots local; public README images wait until the first fully working version |
| Favorites appears in an Add to Shelf example | Keep the convenient favorite action separate from actual manual Shelf membership |

The temporary accepted specification and latest explicit owner instructions take
precedence over older plans. Existing ADRs retain their numbers; 0004/0009 gain
follow-on clarifications and 0017 records acceptance/status accurately. Optional
future Shelf-mode precedence and entitlement are intentionally not finalized,
as allowed by the specification; no current product conflict requires a decision.

## Section 23 checklist traceability

Each original checkbox has its own permanent target below. Checkmarks mean
documentation coverage only. The checklist is retained as an audit index, not
as another copy of the detailed specifications.

### Taxonomy

- [x] Source definition documented — [ARCHITECTURE.md](ARCHITECTURE.md#canonical-taxonomy).
- [x] LibraryItem definition documented — [ARCHITECTURE.md](ARCHITECTURE.md#canonical-taxonomy).
- [x] Category definition documented — [ARCHITECTURE.md](ARCHITECTURE.md#canonical-taxonomy).
- [x] Series definition documented — [ARCHITECTURE.md](ARCHITECTURE.md#canonical-taxonomy).
- [x] Shelf definition documented — [ARCHITECTURE.md](ARCHITECTURE.md#canonical-taxonomy).
- [x] Reader Mode definition documented — [ARCHITECTURE.md](ARCHITECTURE.md#canonical-taxonomy).
- [x] relationships among all six documented — [ARCHITECTURE.md](ARCHITECTURE.md#canonical-taxonomy).

### PDF

- [x] Adapted Mode — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#modes-and-source-preservation).
- [x] Original Mode — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#modes-and-source-preservation).
- [x] source preservation — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#modes-and-source-preservation).
- [x] mode switching — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#choice-preference-and-recommendation).
- [x] per-title preference — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#choice-preference-and-recommendation).
- [x] future global preference — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#choice-preference-and-recommendation).
- [x] recommendation system — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#choice-preference-and-recommendation).
- [x] PDF analysis signals — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#choice-preference-and-recommendation).
- [x] TEXT_NATIVE / OCR_REQUIRED / COMPLEX_LAYOUT concept — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#choice-preference-and-recommendation).
- [x] semantic document model — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#semantic-document-model).
- [x] SourceMap — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#sourcemap-and-annotations).
- [x] annotation implications — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#sourcemap-and-annotations).
- [x] EPUB distinction — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#format-distinctions).
- [x] DOCX future path — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#format-distinctions).
- [x] comic/manga PDF distinction — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#format-distinctions).
- [x] large-import non-blocking analysis — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#ingestion-scheduling-and-scope).
- [x] OCR deferred — [features/PDF_INGESTION.md](features/PDF_INGESTION.md#ingestion-scheduling-and-scope).

### Series / Omnibus

- [x] Series first-class — [features/SERIES.md](features/SERIES.md#identity-and-membership).
- [x] virtual, not physical merge — [features/SERIES.md](features/SERIES.md#identity-and-membership).
- [x] main-library grouping — [features/SERIES.md](features/SERIES.md#library-and-series-page).
- [x] Series page — [features/SERIES.md](features/SERIES.md#library-and-series-page).
- [x] Series-level progress — [features/SERIES.md](features/SERIES.md#progress-and-continuous-reading).
- [x] Continue Reading — [features/SERIES.md](features/SERIES.md#progress-and-continuous-reading).
- [x] cross-volume transition — [features/SERIES.md](features/SERIES.md#progress-and-continuous-reading).
- [x] Read as Omnibus — [features/SERIES.md](features/SERIES.md#progress-and-continuous-reading).
- [x] generic books/comics/manga support — [features/SERIES.md](features/SERIES.md#identity-and-membership).
- [x] issues/annuals/specials acknowledged — [features/SERIES.md](features/SERIES.md#identity-and-membership).
- [x] natural sorting — [features/SERIES.md](features/SERIES.md#ordering-and-detection).
- [x] manual reorder — [features/SERIES.md](features/SERIES.md#ordering-and-detection).
- [x] detection signals — [features/SERIES.md](features/SERIES.md#ordering-and-detection).
- [x] reviewable automatic detection — [features/SERIES.md](features/SERIES.md#ordering-and-detection).
- [x] multi-file Import as Series — [features/SERIES.md](features/SERIES.md#creation-flows).
- [x] folder-as-Series import — [features/SERIES.md](features/SERIES.md#creation-flows).
- [x] create Series from existing LibraryItems — [features/SERIES.md](features/SERIES.md#creation-flows).
- [x] Series cover options — [features/SERIES.md](features/SERIES.md#library-and-series-page).
- [x] Series vs Shelf distinction — [features/SERIES.md](features/SERIES.md#identity-and-membership).

### Shelves

- [x] core categories remain fixed — [features/SHELVES.md](features/SHELVES.md#terminology-and-category-boundary).
- [x] manual Shelf — [features/SHELVES.md](features/SHELVES.md#manual-shelves-and-favorites).
- [x] Smart Shelf future — [features/SHELVES.md](features/SHELVES.md#smart-shelves--future).
- [x] multiple Shelf membership — [features/SHELVES.md](features/SHELVES.md#manual-shelves-and-favorites).
- [x] preferred user-facing terminology — [features/SHELVES.md](features/SHELVES.md#terminology-and-category-boundary).
- [x] Collections terminology reconciled — [features/SHELVES.md](features/SHELVES.md#terminology-and-category-boundary).
- [x] pin Shelf to Library — [features/SHELVES.md](features/SHELVES.md#browsing-pinning-and-customization).
- [x] Shelf customization — [features/SHELVES.md](features/SHELVES.md#browsing-pinning-and-customization).
- [x] Add to Shelf during import — [features/SHELVES.md](features/SHELVES.md#import-and-folder-suggestions).
- [x] Create Shelf from folder suggestion — [features/SHELVES.md](features/SHELVES.md#import-and-folder-suggestions).
- [x] Source → Shelf mapping — [features/SHELVES.md](features/SHELVES.md#import-and-folder-suggestions).
- [x] Favorites distinction — [features/SHELVES.md](features/SHELVES.md#manual-shelves-and-favorites).

### Library-scale ingestion

- [x] Import Center concept — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#one-import-center-one-pipeline).
- [x] Add Files — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#one-import-center-one-pipeline).
- [x] Add Folder — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#one-import-center-one-pipeline).
- [x] Add Series — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#one-import-center-one-pipeline).
- [x] Import Library — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#one-import-center-one-pipeline).
- [x] common pipeline — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#one-import-center-one-pipeline).
- [x] ImportSession — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#sessions-staging-and-plans).
- [x] multi-file import — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#discovery-and-folder-evidence).
- [x] folder recursive import — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#discovery-and-folder-evidence).
- [x] folder content hint — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#discovery-and-folder-evidence).
- [x] folder hierarchy as evidence — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#discovery-and-folder-evidence).
- [x] staging — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#sessions-staging-and-plans).
- [x] Import Review — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#review-and-duplicates).
- [x] fast local pass — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#fast-local-work-and-deferred-work).
- [x] background enrichment — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#fast-local-work-and-deferred-work).
- [x] resumability — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#sessions-staging-and-plans).
- [x] failure isolation — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#fast-local-work-and-deferred-work).
- [x] progressive duplicate detection — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#review-and-duplicates).
- [x] reference vs copy — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#reference-or-copy).
- [x] managed ShelfOS library — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#reference-or-copy).
- [x] migration from exported data — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#migration-adapters--later).
- [x] no private sandbox bypass — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#migration-adapters--later).
- [x] adapter architecture — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#migration-adapters--later).
- [x] Calibre future compatibility — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#migration-adapters--later).
- [x] no DRM circumvention — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#migration-adapters--later).

### Library Sources

- [x] ImportSession vs LibrarySource distinction — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#source-identity-and-session-distinction).
- [x] persistent source purpose — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#source-identity-and-session-distinction).
- [x] Sources settings location — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#sources-ui-and-health).
- [x] Source details — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#sources-ui-and-health).
- [x] Rescan — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#manual-rescan-and-safe-reconciliation).
- [x] NEW/CHANGED/MOVED/MISSING concept — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#manual-rescan-and-safe-reconciliation).
- [x] additive/safe behavior — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#manual-rescan-and-safe-reconciliation).
- [x] safe disconnect — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#moved-files-and-safe-disconnect).
- [x] moved-file relink — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#moved-files-and-safe-disconnect).
- [x] changed-file review — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#changed-publications).
- [x] multiple Sources → one Library — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#source-identity-and-session-distinction).
- [x] Source ≠ Category — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#source-identity-and-session-distinction).
- [x] optional Source → Shelf mapping — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#source-identity-and-session-distinction).
- [x] Source context improves Series detection — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#source-identity-and-session-distinction).
- [x] persistent vs one-shot Sources — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#types-capabilities-and-conceptual-model).
- [x] managed library as Source — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#managed-source).
- [x] device migration/reconnect — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#backup-and-device-reconnection).
- [x] Source health — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#sources-ui-and-health).
- [x] manual-first rescan policy — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#manual-rescan-and-safe-reconciliation).
- [x] scoped privacy model — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#privacy-and-acceptance).

### Shared architecture

- [x] ImportCandidate/staged object concept — [ARCHITECTURE.md](ARCHITECTURE.md#6-persistence-and-conceptual-models).
- [x] ImportPlan concept — [ARCHITECTURE.md](ARCHITECTURE.md#6-persistence-and-conceptual-models).
- [x] LibrarySource concept — [ARCHITECTURE.md](ARCHITECTURE.md#6-persistence-and-conceptual-models).
- [x] source provenance on LibraryItem — [ARCHITECTURE.md](ARCHITECTURE.md#6-persistence-and-conceptual-models).
- [x] SourceScan concept — [ARCHITECTURE.md](ARCHITECTURE.md#6-persistence-and-conceptual-models).
- [x] platform abstraction — [PLATFORM_STRATEGY.md](PLATFORM_STRATEGY.md#source-portability).
- [x] backup implications — [features/LIBRARY_SOURCES.md](features/LIBRARY_SOURCES.md#backup-and-device-reconnection).
- [x] security/privacy implications — [features/DATA_INGESTION.md](features/DATA_INGESTION.md#security-and-evaluation).
- [x] roadmap sequencing — [ROADMAP.md](ROADMAP.md#accepted-ingestion-and-organization-sequence).
- [x] relevant ADRs — [DECISION_INTEGRATION_AUDIT.md](DECISION_INTEGRATION_AUDIT.md#integration-map-and-adrs).
- [x] AGENTS guidance updated — [../AGENTS.md](../AGENTS.md#ingestion-and-organization-boundaries).
- [x] README/product summary updated where appropriate — [PRODUCT.md](PRODUCT.md#9-libraries-sources-and-organization).

Total: **112 / 112 checklist items mapped**.

## Coverage beyond the checklist

| Temporary sections | Permanent treatment |
| --- | --- |
| 0–1, 24: product purpose, taxonomy and final principles | [Product](PRODUCT.md#9-libraries-sources-and-organization), [architecture diagram](ARCHITECTURE.md#canonical-taxonomy), five feature contracts |
| 2.1–2.15: complete PDF behavior and analysis details | [PDF spec](features/PDF_INGESTION.md), including typography, palettes, structured blocks, both mapping directions, OCR possibilities for artwork and non-blocking work |
| 3.1–3.16: Series behaviors, member types and creation paths | [Series spec](features/SERIES.md), including issue ranges, cover choices, manual order and independent locators |
| 4.1–4.11: Shelf types, UI, preferences and automation | [Shelf spec](features/SHELVES.md), including optional future PDF preference and rule examples |
| 5.1–5.17: import architecture and migration details | [Data ingestion](features/DATA_INGESTION.md), including all adapter types, Calibre metadata.opf, supported exports and reference/copy |
| 6.1–6.20: durable Source behaviors | [Sources](features/LIBRARY_SOURCES.md), including UNCHANGED results, capabilities, shared-access disconnect, source revisions and backup/reconnect |
| 7: conceptual data fields | [Architecture models](ARCHITECTURE.md#6-persistence-and-conceptual-models), [sessions/staging](features/DATA_INGESTION.md#sessions-staging-and-plans), [Source data](features/LIBRARY_SOURCES.md#types-capabilities-and-conceptual-model) |
| 8.1–8.11: all eleven end-to-end flows | [Acceptance flow table](features/DATA_INGESTION.md#end-to-end-acceptance-flows); later additions and moved-file rows share a row with separate Source behaviors |
| 9: illustrative preparation/discovery/grouping UX | [PDF choice](features/PDF_INGESTION.md#choice-preference-and-recommendation), [discovery/review](features/DATA_INGESTION.md#discovery-and-folder-evidence), [Series detection](features/SERIES.md#ordering-and-detection); factual status, not copied sample counts |
| 10–11: non-destructive, performance/resilience | [Data ingestion](features/DATA_INGESTION.md), [Source reconciliation](features/LIBRARY_SOURCES.md#manual-rescan-and-safe-reconciliation), [agent rules](../AGENTS.md#ingestion-and-organization-boundaries) |
| 12–14: metadata, readers and UI surfaces | [Metadata](features/METADATA_ENRICHMENT.md#5-ingestion-integration), [reader](features/READER.md#pdf-modes-and-series-context), [reader UX](design/READER_UX.md), [input](design/INPUT_SYSTEM.md#future-series-continuity), detailed feature UI sections |
| 15–16: phasing and responsibility boundaries | [Roadmap sequence](ROADMAP.md#accepted-ingestion-and-organization-sequence), [architecture responsibilities](ARCHITECTURE.md#8-ingestion-architecture) |
| 17–19: platform, backups, privacy/security | [Platform](PLATFORM_STRATEGY.md#source-portability), [backup/reconnect](features/LIBRARY_SOURCES.md#backup-and-device-reconnection), [ingestion security](features/DATA_INGESTION.md#security-and-evaluation) |
| 20–23: integration, ADRs, consistency and deletion gate | This audit, linked ADRs, terminology scan and validation record below |

## Implementation delta, not completion claims

- Existing Phase 0 acceptance stays historical; unfinished Phase 1 code is preserved.
- Existing file references need durable Source provenance/access separation.
- Existing one-file jobs need shared multi-file discovery, durable staging and recovery.
- The visible Collections placeholder must become Shelves during later code work.
- Owned-copy removal must conform to the accepted non-destructive Source policy.
- Series, Manual/Smart Shelves, scans/reconnect, Adapted PDFs/SourceMap and adapters
  are specified, not implemented by this change. No database schema is frozen.
- Basic Original PDF support remains independent of Adapted reconstruction and OCR.

## Validation record

- Re-read the complete 3,283-line temporary specification after integration.
- Verified all 112 checklist entries against their linked permanent sections.
- Reviewed the additional sections and eleven flows, with traceability above.
- Checked all 291 local Markdown links and heading anchors with a workspace audit
  script; none were broken.
- Searched repository documentation and code for Collection/Collections: remaining
  mentions are historical/competitor vocabulary, explicit migration notes or untouched
  legacy application names. No conflicting future destination remains.
- Reviewed documentation diffs and ran git diff --check for whitespace errors.
- Compared file hashes against the pre-task snapshot: no application, schema,
  Gradle, dependency inventory, ignore-policy or other non-documentation changes.
- No Android build/tests run for this documentation-only change; prior app test
  results are not re-certified here. No photos, publications or dependencies added.
- No unresolved documentation requirement or product conflict remains.
- Temporary specification: deleted from its actual `docs/` location after all
  checks passed. No root-level copy existed. No implementation followed deletion.

## Permanent file inventory

Created: the five feature specifications and five ADRs in the integration map,
plus this audit (11 files). Modified existing documents (26 files):

- AGENTS.md
- CONTRIBUTING.md
- README.md
- docs/ARCHITECTURE.md
- docs/CHANGELOG_DOCS.md
- docs/COMPETITIVE_FEATURES.md
- docs/PHASE_1_PLAN.md
- docs/PLATFORM_STRATEGY.md
- docs/PRODUCT.md
- docs/ROADMAP.md
- docs/adr/0004-library-item-model.md
- docs/adr/0009-media-categories.md
- docs/adr/0017-phase-one-reading-scope.md
- docs/design/CLASSIC_LIBRARY_REFERENCE.md
- docs/design/CLASSIC_UI.md
- docs/design/INPUT_SYSTEM.md
- docs/design/READER_UX.md
- docs/design/THEMES.md
- docs/design/VISUAL_IDENTITY.md
- docs/features/ANNOTATIONS.md
- docs/features/COMICS_MANGA.md
- docs/features/IMPORT.md
- docs/features/LIBRARY.md
- docs/features/METADATA_ENRICHMENT.md
- docs/features/PREMIUM.md
- docs/features/READER.md
