# Series and virtual omnibus reading

Status: accepted direction, 2026-09-24; not implemented. See
[ADR-0019](../adr/0019-series-virtual-aggregation.md) and [roadmap](../ROADMAP.md).

## Identity and membership

A Series is a first-class intrinsic publication relationship, shared across
Books, Comics, Manga and other legitimate sequential publications. It can contain
novels, multi-volume reference books, collected volumes, issues, annuals, specials
and published omnibus editions. It is not Manga-only and is not a user Shelf.
A "Dark Fantasy" Shelf may organize several Series; each Series describes its
own related publications and reading order. See [Shelves](SHELVES.md).

Aggregation is virtual: every source file and LibraryItem stays independent.
"Read as Omnibus" never physically merges files, alters originals or requires a
network lookup. Removing grouping must not remove the publications or their state.

Conceptual `Series` data includes a stable UUID, title, creators, optional publisher,
metadata provenance, cover choice and ordered membership. `SeriesMembership` may
contain seriesId, libraryItemId, orderIndex, volumeNumber, issueNumber, issueStart,
issueEnd, publicationDate and itemType (`BOOK`, `VOLUME`, `ISSUE`, `ANNUAL`,
`SPECIAL`, `OMNIBUS`). These are evolving concepts, not a frozen database schema.
An actual omnibus publication as a member is distinct from virtual omnibus reading.

## Mixed-format members

A Series must not require all members to share a file format or archive container.
For example, `X-Men Vol 01.cbr` and `X-Men Vol 02.cbz` belong in the same Series
and can participate in the same virtual omnibus once both formats are supported.
The same principle applies to other supported publication formats, such as a PDF
volume alongside comic archives. No conversion, repacking or physical merge is required.

Detection, manual grouping and reading order follow publication relationships and
user decisions, not matching extensions. Bulk/folder import must not split an
otherwise valid Series just because its members use different formats.

At each omnibus boundary, select the appropriate ShelfOS reader adapter for the
next member's actual format and capabilities. Preserve independent member IDs,
locators, progress and preferences; a change of container must not reset Series
Continue Reading or require the user to return to the Library.

Format support remains a separate implementation requirement: CBR is still future
scope, not enabled by this decision. An unsupported or unreadable candidate must
be reported individually in import review without rejecting the other members or
changing their proposed grouping. Do not advertise an unsupported member as
readable or silently skip it during omnibus navigation. Apply the existing
unreadable-member recovery behavior when an imported member cannot be opened.

When both adapters exist, acceptance must cover CBR → CBZ and CBZ → CBR transitions,
reverse navigation, restart/resume in either member and unchanged source files.

## Ordering and detection

Honor manual ordering first, then trusted ordering metadata, then natural numeric
filename ordering: Vol 1, Vol 2, Vol 10, Vol 11. Lexical sorting is insufficient.
Volume/issue ranges, dates and item types help place collected editions, annuals
and specials; ambiguous chronology needs review rather than an invented sequence.
Users can reorder members at any time without renaming files.

Detection evidence includes embedded Series metadata, `ComicInfo.xml`, normalized
titles, volume/issue numbers, filename patterns, parent folders, publisher, creator,
publication date, category hints and LibrarySource context. External metadata may
help later. A Manga source, matching parent folder, numbered filename and matching
embedded metadata together strengthen a proposal; folder names alone are not truth.

Show reviewable proposals with **Import as Series**, **Review**, **Keep Separate**
(or Import Individually). Never aggressively group ambiguous items. Future explicit
preferences may accept very high-confidence proposals automatically. User grouping,
rejections and corrections always win over later rescans/enrichment suggestions.

## Creation flows

All routes create the same Series object:

1. Detect likely Series during normal import, then review and accept grouping.
2. **Add Series / Import as Series**: select many files at once or select a folder;
   discover supported files, analyze titles/numbers/metadata, propose order, review,
   then commit LibraryItems and membership. Fourteen volumes must not require
   fourteen separate imports. Books and Comics use the same flow as Manga.
3. Select existing LibraryItems and create a Series, reviewing order and metadata.

Folder-as-Series import does not permanently equate that folder with the Series.
Use the common [staging/review pipeline](DATA_INGESTION.md); unsupported files and
duplicates remain inspectable. A later source scan proposes new members for review.

A future generic ZIP library import can provide the same hierarchy evidence. For
example, `Comics/New X-Men/Vol 1.cbz`, a future-supported `Vol 2.cbr`, and
`Vol 3.cbz` may suggest one mixed-format Series. The outer ZIP is only an import
container; it is never the Series or an omnibus, and the proposal remains reviewable.

## Library and Series page

The Library can group members behind one cover, Series title, item count and useful
progress (for example "5 volumes · 62% complete"). Entering the Series opens its
details and member grid/list. Individual members remain addressable/searchable.

The Series page should expose cover, title, creators, publisher where useful,
total imported items, progress, current book/volume/issue, Continue Reading,
Read as Omnibus, member completion states, manual ordering, metadata editing and
cover selection. Show the effective reading direction where useful, especially
for Manga, while retaining member-specific overrides. An apparent gap in imported issues must not imply ownership of
missing publications or silently renumber the run.

Cover choices: first member cover, user-selected member cover, external Series
cover later, custom user image, or generated composite/stack. User selection wins;
external cover caching follows licensing and metadata provenance rules.

## Progress and continuous reading

Series-level progress summarizes imported members and shows the current member's
progress; do not imply completion of unowned volumes. Define the aggregation policy
when implemented and label the denominator honestly: unknown page counts or EPUB
repagination must not produce misleading exact totals.

Series Continue Reading persists `currentSeriesItem` plus `currentLocator` and
restores that member/passage, not the first volume. Per-item progress remains
independent. Reordering must not lose the current stable item identity.

Optional **Read as Omnibus** coordinates existing reader sessions. At Volume 3's
last page, offer "Volume 3 complete — Continue to Volume 4". Semantic Next can
naturally open the next imported member at its beginning; Previous at a member's
beginning can return to the preceding member's end. Preserve each format's locator,
direction and mode preferences across transitions. Files remain separate inside
the reader boundary; no monolithic merged in-memory publication is required.

Standalone reading does not unexpectedly traverse unrelated items. End of Series
is explicit. Missing/unreadable next members retain their metadata and show a
recoverable choice to locate, return or deliberately skip, never a silent jump.
Normal Back/Home remains available. Cross-volume continuity, failure states and
focus restoration need tests before calling virtual omnibus reading complete.
