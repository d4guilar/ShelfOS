# Rooms

Status: **accepted product direction** (2026-10-22); **not implemented**. **"Rooms" is a provisional working name**
pending a separate product naming decision. Implementation boundaries are **proposed**; items marked *pending design
decision* are unresolved. No schema, migration, entitlement or date is assigned. Related: [Shelves](SHELVES.md),
[Series](SERIES.md), [Library](LIBRARY.md), [Classic UI](../design/CLASSIC_UI.md), [Themes](../design/THEMES.md),
[Quiet Motion](../design/MOTION.md).

## 1. Concept

A Room is a personalized, curated library space made of several independently organized sections. It presents and
curates things the user already has; it organizes nothing new and owns nothing.

A Room may eventually contain:

- Manual Shelves
- Series
- individual featured publications
- saved author views
- saved genre/tag views
- a "currently reading" selection
- Smart Shelf results, once Smart Shelves exist

## 2. Hierarchy (unchanged)

| Concept | Role |
| --- | --- |
| **Category** | Describes publication type (Books, Comics, Manga, Documents). |
| **Series** | Intrinsic publication relationship and order ([SERIES.md](SERIES.md)). |
| **Shelf** | User organization of LibraryItems ([SHELVES.md](SHELVES.md)). |
| **Room** | *Presentation and curation across* the above. |

Rooms add no new kind of membership. Rooms do **not** duplicate LibraryItems, reading progress, annotations, covers
or source publications; a Room section is a reference (a view) onto existing Shelves, Series, items or saved views.
Favorites stays a cross-category system view and is not a Room. There is no "Collections" destination and this
feature must not reintroduce one.

## 3. Naming risk

Two naming concerns: Folio already uses "Rooms" for collection organization, and "Room" collides with Android's Room
database library, which ShelfOS uses (`core.database`, Room entities, schemas, migrations). Keep the user-facing name
provisional, and keep Room-feature vocabulary distinguishable from Room-database terminology in code and documentation
(for example by not reusing bare "Room" for feature types, packages or tables). No final name is chosen here. Do not hard-code the word into
identifiers that are expensive to rename, and keep the user-facing string in Android string resources
(localization rules). A separate naming decision will confirm the final name or choose another (for example
"Spaces" or "Rooms" retained). This document uses "Room" throughout for readability only.

## 4. Specification

**Identity.** Stable UUID, user-visible name, optional icon, creation order. Names are user text and never
translated or rewritten. Exact fields are *pending design decision* and not a frozen schema.

**Creation and renaming.** Manual only. A user creates, renames and reorders Rooms. No Room is created from folders
or by automation; folder structure is evidence, not truth.

**Cover / banner.** Optional, user-selected from the user's own images or a member's cover. Rooms have **no implemented
cover/banner storage**: current cover persistence is keyed to LibraryItems, and a Room cover cannot be written through a
LibraryItem's USER cover slot. Future Room artwork may reuse cover design, rendering, encoding and budget conventions,
but its ownership, persistence and lifecycle are new work. Absent a choice, a deliberate generated or neutral
presentation is shown (not a missing-image look). A Room cover is never fetched online.

**Featured publication.** At most one optional featured LibraryItem per Room (see MVP boundary), chosen by the user.
If the item is removed from the library the Room shows no feature and does not error.

**Sections.** An ordered list of sections. Each section references one of: a Shelf, a Series, a single publication,
a saved author or tag view, or a Currently Reading selection (all proposed; no section type is implemented, and a
Currently Reading section is proposed only). Section order and per-section title override are
user-controlled. A section can be hidden or removed without affecting its source Shelf/Series.

**Adding Shelves and Series.** A Shelf or Series can appear in many Rooms and also stay pinned to the Library. A Room
reference to a Shelf follows that Shelf's current membership; a Room never snapshots it.

**View options.** Each section may use Grid, Compact, List or Showcase (the canonical Shelf view names) where appropriate, reusing existing Library
components and the shared cover renderer. Sorting follows the underlying Shelf/Series unless the user overrides.

**Input.** Touch, keyboard and gamepad share semantic input commands (no screen-specific mappings). Every Room
action reachable by touch is reachable by D-pad/keyboard, with visible focus and predictable focus restoration after
returning from a publication. Reordering has a non-drag path.

**Responsive.** No fixed phone portrait assumption. Phones use a single scrolling column of sections; tablets and
foldables may show a wider layout (for example a featured area beside sections); landscape handhelds keep
sections scrollable by D-pad. Decisions use window size classes and posture, not device models. Room scroll position
and open Room survive rotation, fold/unfold, resize and process recreation.

**Offline.** Entirely local. No network, account, sync or online metadata is required to create or view a Room.

## 5. Ownership and safe deletion

- Deleting a Room deletes only the Room and its section references. It never deletes publications, source files,
  Shelves, Series, progress, annotations or covers. The confirmation says so.
- Removing a Shelf or Series that a Room references does not delete the Room; the affected section shows a clear
  "no longer available" state the user can remove, not a crash or silent disappearance.
- A missing or disconnected Source never removes Room references (consistent with Library Sources rules).
- Renaming a Room never renames a Shelf, Series, folder or file. Conversely, a Shelf or Series rename is reflected in
  the Room section by default unless the section has an explicit title override.
- If an individual LibraryItem disappears, a section referencing it shows the unavailable state; it never triggers
  destructive cleanup of unrelated entities.
- Missing or deleted references can be repaired or relinked when identity is recoverable, or removed deliberately by the
  user when it is not. A missing publication Source is not a deleted LibraryItem and is never treated as one.
- A publication shown in several sections (or Rooms) is the same underlying LibraryItem with shared progress.
- Back navigation returns predictably to the screen from which the Room was opened, subject to normal app navigation.
- None of this fixes a storage schema; reference and override representation remain *pending design decision*.

## 6. Accessibility

Meaningful content descriptions for banner, featured item and section headers; heading semantics for sections;
screen-reader order matches visual order; touch targets at least 48 dp; visible focus; scalable text without
clipping (including large font and localized strings, per the navigation-width lessons in Phase 4.5C.2C); no meaning
conveyed by color alone; reduced-motion compatible (see Motion).

## 7. Portable backup (future semantics only)

When backup/restore exists, a Room exports as stable IDs referencing Shelf/Series/LibraryItem UUIDs plus its own
presentation settings, never Android URI strings. Restoring a Room whose references cannot be resolved keeps the Room
and marks those sections unresolved. This is a semantic constraint, not a format.

## 8. MVP boundary (proposed)

First version is deliberately small:

- manual creation, rename, delete, reorder
- sections that reference **existing Manual Shelves, Series and individual publications** only
- optional featured publication and optional user-chosen cover
- Grid / Compact / List / Showcase from existing components
- all local

Not required and **not** part of MVP: Smart Shelves, saved filter views, online metadata, synchronization, social
sharing, accounts, full 3D rendering, new reader engines, AI. Advanced visual Room themes and animated presentations
are later enhancements. Saved author/tag views are a later increment (they depend on a saved-filter concept that does
not yet exist).

## 9. Dependencies

Rooms depend on the **Manual Shelves** and **Series** foundations, neither of which is implemented. Saved views and
Smart Shelf sections depend on Smart Shelves. Room covers may reuse cover design and rendering conventions but need their own storage and lifecycle (Section 4).

## 10. Risks and testing

- **Reuse:** Shelf/Series list components, shared cover renderer, Library views, `shelfAction`, Cover Presentation.
- **Performance:** a Room can aggregate many sections; sections must virtualize and share image caches, honoring
  large-library behavior. Measure on modest hardware before claiming smoothness.
- **Privacy/storage:** local-only; a Room cover counts toward user-artwork budgets.
- **Tests required:** reference integrity when Shelf/Series/items are removed; Room deletion leaves all
  publications and Shelves untouched; state preservation across rotation/fold/process death; keyboard and gamepad
  traversal of every control; large library; localization/RTL-tolerant layout; accessibility semantics.
- **Scope boundary:** no new media concept, no per-Room reading progress, no per-Room copy of metadata.

## 11. Decisions needing owner approval (open)

1. Final product name.
2. Whether a Room may contain the same Shelf twice, and how many featured items.
3. Whether Rooms appear as a Library pin row, a top-level destination, or inside Shelves. The canonical destination
   set (Library, Search, Notes, Shelves, Settings) must not change without an explicit decision; the leading
   proposal is to surface Rooms **within** Library/Shelves rather than add a sixth destination.
4. Whether Rooms (or only advanced visuals) are free or Plus. **No entitlement is assigned;** basic organization is
   not to be made unpleasant (product rule 7).
5. Section type set for the first release.
6. Room banner source constraints.
7. Whether an ADR is warranted. Proposed: one ADR once naming, destination placement and the reference-not-copy
   rule are settled together ("Rooms are views over Shelves and Series"). Not before.
