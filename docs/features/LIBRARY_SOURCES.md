# Persistent Library Sources

Status: accepted architecture, 2026-09-24; durable Source management, scans and
reconnection are not implemented. See [ADR-0022](../adr/0022-persistent-library-sources.md).

## Source identity and session distinction

`LibrarySource` answers where content came from and whether ShelfOS can access it
again. `ImportSession` describes an ingestion process. A session importing 428
publications ends; the Books folder Source remains with its items, health and last
scan. **ImportSession != LibrarySource; Source != LibraryItem.**

Many Sources feed one Library: internal storage, SD card, external storage,
user-selected provider folders, a managed ShelfOS directory and future adapters.
Users can retain Books/Comics/Manga/University/Manuals folders as they already exist.
They need not rebuild their filesystem around ShelfOS.

Source does not determine Category: Downloads can contain a BOOK EPUB, DOCUMENT
PDF and MANGA CBZ. An optional category hint remains correctable. Source hierarchy
and folder context strengthen [Series proposals](SERIES.md) but do not override
trusted metadata or user decisions. Source → Shelf assignment is explicit optional
organization, not an identity relation. Renaming a Shelf does not rename a Source.

## Types, capabilities and conceptual model

| Source | Lifetime and possible capabilities |
| --- | --- |
| Folder (`FOLDER`) | Persistent; rescan/reconnect while user-granted tree access exists |
| Managed ShelfOS Library (`MANAGED`) | Persistent destination for explicit copies; local maintenance and export |
| Selected file set | Origin of one/many selected documents; do not infer permission to scan parent folders |
| Generic ZIP archive (`ZIP_ARCHIVE`, future conceptual name) | One-shot multi-publication import; safe discovery and review, then extraction to the managed Source; normally not rescannable or required for reading afterward |
| Calibre directory (`CALIBRE`, future) | Persistent/rescannable structured library when accessible |
| OPDS (`OPDS`, future) | Explicit provider adapter; capabilities and offline availability depend on access/local copies |
| ShelfOS backup (`SHELFOS_BACKUP`, future) | Usually a one-shot migration, not a permanently watched archive |
| Reader/library export (`EXTERNAL_EXPORT`, future) | Usually one-shot; retains provenance after the operation ends |

Represent `persistent`, `rescanSupported`, `reconnectable` and `writable` capabilities
explicitly; being a Source never grants permission to write to originals. Not all
Sources support every action, and network-backed types must not make core use online.

Conceptual fields: stable ShelfOS UUID, display name, type, platform location/root
descriptor, createdAt, lastScannedAt, health/status, recursive setting, categoryHint,
optional shelfId mapping, capabilities and scanPolicy. `SourceScan` records sourceId,
start/completion times and discovered/added/changed/moved/missing/failed counts.
Keep these conceptual until the corresponding storage milestone.

A generic ZIP archive is not a connected folder Source and is not a ShelfOS backup.
Its original location remains provenance, while committed extracted publications
are backed by the managed Source. The original archive remains untouched and is not
automatically deleted. Capability modeling should express its one-shot lifetime
rather than forcing folder-style rescan/reconnect behavior. See the
[ZIP ingestion contract](DATA_INGESTION.md#generic-zip-library-import).

LibraryItem provenance retains sourceId, platform source reference, optional relative
path, fingerprint and sourceModifiedAt. Preserve origin when creating a managed copy;
storage location and import provenance need not be identical. Platform references
must never replace a LibraryItem's stable UUID.

## Sources UI and health

Use **Settings → Library → Sources**, not another global destination. List name,
type, publication count, last scan and quiet actionable health (for example
"3 connected · 1 needs attention"). Source details show location, counts for new,
missing and changed files, **Rescan**, category hint, Series/Shelf suggestion settings,
explicit auto-add-to-Shelf mapping where implemented, and **Disconnect Source**.

Health vocabulary:

| State | Meaning / response |
| --- | --- |
| CONNECTED | Currently accessible |
| NEEDS_PERMISSION | Ask the user to regrant scoped access |
| PARTIALLY_AVAILABLE | Some publications inaccessible; preserve and identify them |
| UNAVAILABLE | Origin cannot currently be reached; allow reconnect/locate |
| SCANNING | Scan in progress; expose progress without declaring items missing prematurely |
| ERROR | Scan/provider error; explain recoverable action and permit retry |

Health is not publication identity or category. A timeout or disconnected SD card
must not be interpreted as proof that every publication was deleted.

## Manual rescan and safe reconciliation

Start with an explicit manual **Rescan**. A scan produces a reviewable diff:

| Result | Safe action |
| --- | --- |
| NEW | Stage new candidate through normal import review |
| CHANGED | Review content revision and affected state |
| MOVED | Propose/verify relink to the existing LibraryItem |
| MISSING | Mark unavailable; keep LibraryItem and owned state |
| UNCHANGED | Retain existing identity, user edits and progress |

Only complete traversal supports absence findings; incomplete provider scans must
report uncertainty/partial availability. Reconciliation is additive, not destructive
mirroring. It must not silently overwrite manual metadata/grouping or re-import
known files as duplicates. Record scan results separately from applying changes.

For a missing publication offer **Locate**, **Keep in Library**, **Remove from
ShelfOS**. Preserve metadata, cover cache, notes/annotations/bookmarks, progress,
Series/Shelf memberships, user edits and presentation preferences until the user
explicitly removes the LibraryItem. Missing files or folders do not imply removal.

Later optional schedules may offer Never, When ShelfOS opens, Daily or Weekly.
Scheduling remains best-effort and provider/platform constrained. Do not assume
real-time filesystem watching: SAF/document-provider trees are rescannable origins,
not guaranteed filesystem event streams.

## Moved files and safe disconnect

For a move such as Books/novel.epub → Books/Sci-Fi/novel.epub, compare fingerprint,
size, identifiers, title/author, filename and source-relative context within
authorized access. A verified match should relink the existing LibraryItem without
losing state or creating a duplicate. Present ambiguous matches for review; weak
filename or work-identifier similarity is not proof of identical content.

Disconnect defaults to **Keep imported LibraryItems**. An explicit alternative may
remove LibraryItems originating only from that Source, with clear scope and no
deletion of source files. Do not remove items still backed by another Source or
managed copy. Disconnect stops future scanning and releases access only when no
other item/operation needs it. It never deletes the user's files.

## Changed publications

Content changes may invalidate page locators, SourceMaps, annotations, chapters and
progress. Offer **Use Updated File**, **Keep Current Reference**, **Review**.
Keeping a reference preserves prior state, not a guarantee that overwritten bytes
can be recovered; do not pretend an uncached old version is still readable.

Retain source revision/fingerprint evidence and existing anchors. Adoption of a
new revision triggers review/reanalysis and explicit best-effort reconciliation;
never silently apply an old SourceMap to new pages or move annotations destructively.
Advanced reconciliation is later work, while basic changed-source detection/review
can precede it. See [PDF SourceMap](PDF_INGESTION.md#sourcemap-and-annotations).

## Managed Source

**Copy into ShelfOS Library** creates normal independent files under a managed
Source. Prefer user-visible/exportable storage where practical; a Books/Comics/
Manga/Documents directory layout is possible, not a fixed schema requirement.
Avoid proprietary containers and automatic deletion of originals. Managed storage
shares provenance, scanning and health concepts; it is not a second Library.

## Backup and device reconnection

Portable backups preserve LibraryItems, metadata and manual overrides, covers where
appropriate, reading progress, bookmarks, annotations, Series and member order,
Shelves and Smart rules where supported, Source definitions and fingerprints/
identifiers, plus theme/reader preferences. Publication bytes require an explicit
backup/export choice; a metadata backup is not a promise that external files were copied.

Android uses SAF/document URIs at the platform edge; future iOS uses file-provider
and document-picker mechanisms. Stable ShelfOS UUIDs identify domain objects on
both platforms. Portable identity/backups must not depend solely on Android URIs
or assume grants can be silently restored on another device.

After restore, show **Source needs reconnecting → Select Books folder**. Reconcile
fingerprints, identifiers, filenames, metadata and Series context to existing item
UUIDs, then report matched/unresolved counts (for example 428/430 reconnected,
2 need review). Automatically reconnect only verified matches; review ambiguity.
Do not discard restored annotations or create duplicate items just because URIs changed.

## Privacy and acceptance

Users explicitly grant individual folders/files; do not assume blanket storage
access, parent-directory permission or access to another app's private sandbox.
Keep Source definitions and publication contents local; ordinary metadata services
receive minimal lookup metadata, never entire publications. Provider adapters remain
explicit and cannot bypass DRM. See [ingestion security](DATA_INGESTION.md#security-and-evaluation).

Validate revoked access, removed storage, incomplete scans, moved/changed content,
ambiguous relinks, repeated scans, disconnect with shared access and device restore.
None of these accepted requirements claims a scanner or backup implementation exists.
