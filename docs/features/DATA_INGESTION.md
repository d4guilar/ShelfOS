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
IMPORT SOURCE (Files / Folder Tree / ZIP Archive / Migration Adapter)
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

Filename tokens such as creator phrases, years, volume/issue markers and separators
may produce [metadata candidates](METADATA_ENRICHMENT.md#5-ingestion-integration).
They remain reviewable proposals; valid embedded metadata such as `ComicInfo.xml`
normally carries stronger evidence, and the user always has final authority.

## Generic ZIP library import

**Status: accepted future capability; not implemented.** A normal `.zip` selected
through Import Library can be a multi-publication import container. `ZipArchiveSource`
(conceptual name) is an import-source adapter beside single-document, multi-document,
folder-tree, backup and external-library adapters. It feeds the same canonical
pipeline and must not create a parallel ZIP-specific import subsystem:

```text
ZipArchiveSource
→ safe entry discovery
→ one staged candidate per supported publication
→ bounded local analysis
→ category / Series / Shelf proposals
→ duplicate and warning review
→ commit selected items into the managed ShelfOS Source
→ optional background enrichment
```

### Generic ZIP is not CBZ

A `.cbz` is one publication whose ordered image entries are pages for the comic/
manga reader. A generic `.zip` library is one import container holding multiple
publication files, such as EPUB, PDF and CBZ. The outer ZIP never becomes one giant
publication or comic. A ZIP containing `Book.epub`, `Paper.pdf` and `Comic.cbz`
produces three candidates; the nested CBZ remains one candidate and is interpreted
by its publication-format adapter. Future CBR or structured formats participate
only after their own format support ships.

Arbitrary nested generic ZIP archives are not recursively expanded by default.
Supported publication containers are inspected according to their format; other
nested archives are skipped or presented for review under an explicit future policy.
Apply depth, count and size limits without prescribing premature numeric values.

### Hierarchy, organization and review

Preserve safe relative paths as evidence. `Books/` or `Manga/` may strengthen a
category hint; `Dune/` or `Berserk/` plus names and metadata may suggest a Series;
`Research/` may suggest a Shelf. Folder evidence never creates authoritative
Categories, Series or Shelves. Proposals stay reviewable and user decisions win.
Mixed nested publication formats, such as CBZ and future CBR volumes, can propose
one Series without conversion or physical merging.

Archive review reports totals by proposed category, detected Series, suggested
Shelves, duplicates and per-entry warnings. It also states that importing extracts
selected publications into ShelfOS-managed storage and leaves the original ZIP
untouched. Unsupported, encrypted, corrupt or ambiguous entries remain inspectable;
one bad entry does not invalidate otherwise safe candidates. Durable sessions and
checkpoints support interruption, resumable review and bounded commit when this
library-scale track is implemented.

A future review may summarize, for example, **143 publications found** with Books,
Comics, Manga and Documents counts; detected Series such as Berserk or Dune;
possible Shelves such as Research; and duplicate, unsupported or corrupt warnings.
The primary action imports the accepted item count, accompanied by clear copy such
as: “ShelfOS will extract copies into managed storage. The original ZIP will not
be modified.” Exact presentation and wording can evolve with Import Review.

ZIP candidates use the normal progressive duplicate policy: source/origin evidence,
name and size, metadata/identifiers and fingerprint only when needed. There is no
ZIP-specific duplicate identity.

### Commit, extraction and source lifetime

Successful commit extracts each accepted publication as an independent normal file
under the managed ShelfOS LibrarySource. Do not retain long-term reading dependence
on random access inside one large archive, and do not freeze the managed filesystem
layout in this specification. Preserve archive path/provenance separately from the
managed copy's location. Incomplete extraction is temporary import state and must
be cleaned or resumed without exposing a committed item as complete.

The original ZIP is never modified, moved or automatically deleted. Disconnecting,
removing or finishing the one-shot archive import does not delete it. The archive
is normally a one-shot, non-rescannable origin after extraction; imported items use
the managed Source and remain independently readable and manageable.

A generic ZIP library and a ShelfOS backup are distinct adapters even if both use
ZIP internally. Generic ZIP interpretation discovers publication files and folder
evidence. A ShelfOS backup may restore database knowledge, metadata, progress,
annotations, Series, Shelves, Source definitions and preferences under its own
versioned contract. They may share low-level safe archive utilities, never semantic
interpretation rules.

### Archive safety

Treat every entry as untrusted. Reject absolute paths, drive-qualified paths and
`..` traversal; normalize destinations and prove they remain inside the staging/
managed root before writing. Bound entry count, directory depth, individual and
total expanded size, compression ratio and resource use. Detect duplicate entry
names and ambiguous normalized paths. Isolate malformed headers, corrupt entries,
unsupported compression and password/encrypted archives. Do not execute content,
follow archive-provided links outside the destination, or recursively explode
arbitrary archives. Interrupted extraction must leave only owned temporary state.
User selection never relaxes these limits.

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
FolderTreeSource, ZipArchiveSource, ShelfOSBackupSource, CalibreSource, OPDSSource and
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
| Generic ZIP library | Import Library → safe archive discovery → one candidate per publication → review organization/duplicates/warnings → extract accepted items to managed storage; original ZIP remains untouched |
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
