# Shelves and personal organization

Status: accepted direction, 2026-09-24; Manual and Smart Shelves are not implemented.
See [ADR-0020](../adr/0020-categories-series-and-shelves.md).

## Terminology and category boundary

Canonical global destinations are **Library · Search · Notes · Shelves · Settings**.
Shelves replaces the earlier user-facing **Collections** term; there is no separate
Collections product concept. Phase 1 renamed the application's destination label,
route (`shelves`), icon and test names; no Collection names remain in code. Saved
navigation state that referenced the old prototype route is not migrated; this affects
only unreleased prototype installs. New documentation and feature naming use Shelf.

Core categories remain `BOOK`, `COMIC`, `MANGA`, `DOCUMENT`: Books, Comics, Manga,
Documents. Categories guide reading behavior and defaults, subject to actual format
capabilities. Research, Work and Recipes are Shelves, not new media types or engines.
A veterinary research paper stays DOCUMENT even when on a Research Shelf.

A [Series](SERIES.md) expresses intrinsic relatedness and ordering; a Shelf expresses
the user's organization. Neither is a Source. Renaming, sorting or deleting a Shelf
must not rename, move, rewrite or delete source files or the LibraryItems it contains.

## Manual Shelves and Favorites

A Manual Shelf contains explicitly selected LibraryItems. One item can belong to
Research, University and Current Semester simultaneously, retain its category and
also be a Favorite. Shelf membership and favorite state are independent.

Favorites remains the prominent special cross-category system view before Books,
Comics, Manga and Documents. It is not a user-created Shelf, though an implementation
may share filter machinery. Import organization may offer a Favorite checkbox
alongside Shelf choices, without storing Favorites as a manual Shelf membership.

## Browsing, pinning and customization

The dedicated **Shelves** destination lists and manages all Shelves. **Pin to
Library** adds selected Shelves after the fixed Favorites/Books/Comics/Manga/Documents
area. Do not crowd this row with every Shelf. Unpinned Shelves remain accessible;
compact windows scroll the row without truncating labels.

Conceptual Shelf data includes a stable UUID, name, icon, optional cover/banner,
Manual/Smart kind, pinned state, default view (Grid, Compact, List or Showcase),
and sorting preference. Respect shared theme tokens and keyboard/touch accessibility.
Later customization can add a preferred PDF reading mode, but it must never silently
override an explicit per-title preference. Because an item may be on several Shelves,
define conflict resolution and communicate the effective preference before shipping
that optional feature; membership alone must not change presentation unpredictably.

## Import and folder suggestions

Import Review supports selecting existing Shelves, **New Shelf**, or no assignment;
batch review can add all selected candidates to one or more Shelves. A folder such
as University/Pathology may suggest **Create Shelf Pathology**, **Choose Existing**
or **No Shelf**. The suggestion needs user acceptance; not every folder is a Shelf.
Renaming the Shelf does not rename the folder.

An optional explicit [LibrarySource → Shelf mapping](LIBRARY_SOURCES.md) can later
add newly imported source items to a selected Shelf. Keep origin and organization
independent. Missing files or Source disconnect do not automatically remove Shelf
memberships. User corrections override folder and automatic grouping suggestions.

## Smart Shelves — future

Smart Shelves evaluate rules dynamically rather than requiring explicit membership.
Examples include Category = Documents AND Tag = Research; Format = PDF AND Publisher
contains a chosen publisher; or Category = Documents AND Date Added within the
current semester. Rule definitions, sorting and view preferences belong to the Shelf.
Rule export/restore should remain portable when backup support is introduced.

Manual Shelves work offline without online metadata. Smart Shelves also operate on
available local metadata; missing enrichment must not make ordinary organization
network-dependent. A rule builder, advanced rules and automation are later milestones,
not prerequisites for Manual Shelves. Existing Premium lists are candidates only;
this decision does not finalize Smart Shelf entitlement or add billing.
