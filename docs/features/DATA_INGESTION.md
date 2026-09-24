# Library-scale data ingestion

Status: accepted architecture, 2026-09-24. Basic single-file import is work in
progress; bulk/folder staging and recovery are not implemented. See
[ADR-0021](../adr/0021-staged-library-ingestion.md), [implementation sequence](../ROADMAP.md#accepted-ingestion-and-organization-sequence)
and [Import entry points](IMPORT.md). This is the detailed pipeline source of truth.

## One Import Center, one pipeline

ShelfOS imports libraries, not just isolated files. The eventual **Add to ShelfOS**
surface has four entry points:

| Entry | Selection and intent |
| --- | --- |
| Add Files | One or many publications through the platform picker |
| Add Folder | Recursively discover a user-selected folder tree |
| Add Series | Many files or a folder explicitly intended as one Series |
| Import Library | An existing mixed library, exported files or a future migration adapter |

These feed the same architecture, not separate implementations per button:

```text
IMPORT SOURCE (Files / Folder Tree / Migration Adapter)
↓
DISCOVERY
↓
STAGING
↓
LOCAL ANALYSIS (format, embedded metadata, category evidence, cheap PDF signals)
↓
ORGANIZATION (Category / Series / Shelves proposals)
↓
IMPORT REVIEW
↓
COMMIT
↓
SHELFOS LIBRARY
↓
BACKGROUND ENRICHMENT
```

Discovery inventories accessible publications; staging keeps candidates outside
the committed Library until reviewed. Local analysis is bounded and offline.
Organization produces proposals, not authoritative user edits. Commit persists the
accepted selection and grouping coherently, with idempotent retry. Enrichment
improves already usable items afterward. One-file import can minimize ceremony,
but must preserve these boundaries as bulk support grows.

## Sessions, staging and plans

`ImportSession` describes a process, distinct from the durable [LibrarySource](LIBRARY_SOURCES.md).
It has a stable ID, source references, status, timestamps and progress counts:
discovered, analyzed, staged, committed, needing review, skipped and failed.
A completed session does not remove its Source.

`ImportCandidate` / `StagedItem` represents an uncommitted publication with source
reference, detected format, proposed category, embedded/inferred metadata,
proposed Series and order, suggested Shelves, duplicate state, PDF analysis summary,
warnings and review status. `ImportPlan` groups candidates, detected Series,
suggested Shelves, duplicate groups, unresolved items and warnings for review.
These conceptual fields guide implementation; they do not freeze tables now.

Large imports persist staging/checkpoints and user review decisions. Resume after
process death, app restart, interruption or temporary provider failure without
duplicating committed items or losing accepted edits. Commit in bounded recoverable
batches; keep item creation, membership and checkpoint consistency transactional.
An incomplete batch must not appear complete. Cancellation/retry outcomes and
owned partial-copy cleanup must be explicit and must not affect originals.

## Discovery and folder evidence

Enable multi-file selection where the platform supports it. Folder selection is
recursive and user-scoped, using Android SAF tree/document grants where appropriate,
not blanket storage permission or assumed POSIX paths. Provider trees may be slow,
remote-backed or temporarily unavailable. Report discovery progressively with
supported and ignored counts; do not assume a fast, complete directory listing.

Optional **What's mainly in this folder?** choices are Detect automatically, Books,
Comics, Manga and Documents. This is a correctable classification hint that can
improve direction defaults and Series detection, not a fixed category for all files.

Hierarchy is evidence: Books/Dune plus numbered novels may suggest a Series;
Manga/a-series plus embedded metadata may strengthen grouping; University/Research
may suggest a Shelf of Documents. Never turn every directory into a Series or Shelf.
Keep provenance/confidence and allow per-item corrections. User classification,
grouping and metadata decisions always override inference.

## Review and duplicates

Import Review summarizes publication counts by category, detected Series with
member counts, suggested Shelves, duplicate groups, unknown types and unsupported,
corrupt, protected or inaccessible candidates. Each section is inspectable. Users
can correct titles/categories, review/reorder Series, keep items separate, choose
Shelves, create a Shelf and skip/retry failures before committing the accepted plan.
Show how many items will actually import; ignored/failed candidates are not success.
Distinguish unsupported format/layout, corrupt publication or page, unsupported
encrypted/password-protected content, denied/revoked access, unavailable source,
archive with no readable pages and insufficient storage. Provider unavailability
or ambiguous online matches are enrichment states, not failed local imports.

Series proposals may contain mixed formats, such as a CBR first volume and a CBZ
second volume. Do not group or split Series by extension. Assess format support
per candidate and isolate unsupported members without failing the whole batch;
see [mixed-format Series](SERIES.md#mixed-format-members). This does not add CBR
support to the current implementation scope.

Progressive duplicate detection uses known source URI/reference, then name + size +
modified date, then identifiers/metadata, then content fingerprint when needed.
Do not hash every multi-gigabyte publication before discovery/review becomes usable.
Weak name/size matches and shared ISBN/work identity do not prove identical files;
editions may differ. Duplicates remain reviewable and are never automatically deleted.
Source reconnection should prefer a verified relink over a second LibraryItem.

## Fast local work and deferred work

The fast pass detects format, reads supported embedded metadata, infers from
filename/folder evidence, obtains embedded/inexpensive covers or a generated
fallback, and proposes category/Series for review. Expensive cover generation,
full indexing and [advanced PDF analysis](PDF_INGESTION.md) must not stall the batch.

After commit, optional background enrichment can resolve identifiers through
replaceable providers, improve covers/descriptions/publisher/Series metadata and
cache permitted results. API quotas, provider failure and offline operation must
not invalidate import or block reading locally accessible files. Online metadata
is never a mandatory review prerequisite. Follow [metadata precedence](METADATA_ENRICHMENT.md).

Use controlled batches, bounded concurrency and bounded memory rather than loading
thousands of publications together. One corrupt publication yields an item-level
failure (for example 2,478 imported, 3 need attention), not total migration failure.
Leave room for later search indexing without putting it on the critical import path.

## Reference or copy

**Reference existing files** is the default: retain user organization and avoid
duplicate storage. Persist access where the provider supports it; do not claim
offline availability for content that remains remote or inaccessible.

**Copy into ShelfOS Library** is optional and explicit. Check space, report progress,
support cancellation and clean incomplete owned copies. Keep normal files in a
user-visible/exportable location where practical, with no opaque proprietary lock-in.
The destination is a managed LibrarySource. Preserve the original and its origin
provenance; do not automatically delete it or move/reorganize a user's folder.

## Migration adapters — later

An import-source adapter discovers candidates and origin context for the common
pipeline. Conceptual adapters include DocumentSource, MultiDocumentSource,
FolderTreeSource, ShelfOSBackupSource, CalibreSource, OPDSSource and
ExternalLibraryAdapter. They do not create independent library subsystems or put
provider logic in Library UI. Match capabilities to the source rather than assuming
every adapter can rescan, reconnect, write or remain permanently connected.

Migration uses user-owned/exported files. If another reader keeps them in a private
sandbox, the user must first use its Export, Backup, Share or Save to Files support.
ShelfOS does not crawl private app data or circumvent DRM. Future Import Library
choices may include ShelfOS Backup, Calibre Library, OPDS and supported reader
exports. Network/provider adapters must be explicit opt-ins.

A future Calibre adapter can interpret `metadata.opf`, cover images, author folders,
Series metadata, tags and identifiers, producing ordinary ShelfOS LibraryItems,
Series and Shelves. ShelfOS backup import restores portable knowledge and initiates
[Source reconnection](LIBRARY_SOURCES.md#backup-and-device-reconnection); restoring
a URI string does not restore platform access permission.

## End-to-end acceptance flows

| Flow | Expected result |
| --- | --- |
| One EPUB | Add Files → discover/stage → embedded metadata → preview → commit → later enrichment |
| One PDF novel | Basic import; show Adapted recommendation if analysis is ready, otherwise analyze later; Original remains usable |
| Technical paper | Complex-layout evidence can recommend Original; supported Adapted remains a choice |
| Many volumes | Add Series → select together → analyze → review order → commit members + Series → eventually Read as Omnibus |
| Series folder | Add Series → folder discovery → trusted metadata/natural order → review → commit |
| Books folder | Add Folder → Auto/Books hint → recursive staging → Series/duplicate review → commit → connected Source retained |
| Mixed library | Import Library → root discovery → correctable categories/Series/Shelves → review → commit → enrichment and rescannable origin |
| Research folder | Accept suggested Research Shelf; publications retain DOCUMENT category |
| Later additions / moved files | Manual Source rescan → review new items or verified relink; retain existing library state |
| New device | Backup restore → access needs reconnection → select equivalent Source → reconcile, review ambiguity |

## Security and evaluation

Treat PDFs, archives and embedded metadata as untrusted input. Isolate parser
failures; bound archive entry counts, expanded bytes, compression ratios and decoded
images; reject malicious paths, external XML entity access and unsupported protection.
Never execute embedded scripts or transmit publication contents for metadata lookup.
Queries use minimal identifiers/title/creator. Preserve source bytes throughout.

Acceptance needs interruption/process-death recovery, corrupt-file isolation,
cancelled copies, low storage, slow/revoked providers, duplicate retry, user override
preservation and large-library memory/progress evidence. These requirements apply
when bulk ingestion is implemented; the current single-file prototype is not a
completed library-scale ingestion system.
