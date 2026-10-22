# Omnibus Composite Covers

Status: **accepted product direction** (2026-10-22); **not implemented**. Implementation boundaries below are
**proposed**; items marked *pending design decision* are deliberately unresolved. No schema, migration, provider
permission, entitlement or date is assigned here. Related: [Series](SERIES.md), [Shelves](SHELVES.md),
[Metadata enrichment](METADATA_ENRICHMENT.md), [Book providers](BOOK_PROVIDERS.md), [Library](LIBRARY.md).

## 1. Purpose

Omnibus-style publications (several volumes presented as one) need covers that are accurate, attractive and honest
about what they are. ShelfOS must never pass off one volume's cover as an official omnibus cover, invent an edition
identity, or fetch artwork it has no right to redistribute. This document defines the cover treatment only. It does
not introduce a reader engine, a merge feature or a new publication type.

## 2. Three distinct cases

| Case | What it is | Edition identity | Cover |
| --- | --- | --- | --- |
| **Official omnibus** | A real published edition (for example a "Volumes 1-3" collected edition) with its own title, ISBN where one exists and authentic cover. | Its own. Identifiers come only from the file's embedded metadata, the user, or a confirmed, reviewed match. | Its authentic cover, via the normal LOCAL / USER / (future) ONLINE slots. |
| **Handmade omnibus** | A user-owned single file that combines several volumes (for example `Berserk 1-3.cbz`). | **None inherited.** It does not take on an official omnibus's ISBN or edition because its contents resemble one. | Embedded cover if usable, else user choice, else (optionally) a user-made Composite Cover. |
| **Virtual omnibus** | Several separately stored publications coordinated by a [Series](SERIES.md) "Read as Omnibus" experience. | Each member keeps its own. The Series has no ISBN. | A Series cover choice (member cover, custom image, or composite). Nothing is attached to member LibraryItems. |

A Series may *contain* an official omnibus as a member (`itemType = OMNIBUS` in SERIES.md); that member is an
ordinary LibraryItem and is distinct from virtual omnibus reading.

## 3. Rules that hold in every case

1. **No invented identifiers.** ShelfOS never assigns, guesses or derives an ISBN, edition id or provider id for an
   omnibus. A composite cover carries none.
2. **No silent borrowing.** A single constituent volume's cover is never silently used as an official omnibus cover.
   If it is used at all, the user chose it (Section 5).
3. **User choice wins.** A user-selected or user-composed cover always overrides embedded, fetched or generated art,
   consistent with cover precedence (Phase 4.5 constraints in the [roadmap](../ROADMAP.md)) (user-selected > confident online > usable embedded >
   generated). Automatic enrichment never replaces it.
4. **Reviewable ranges.** Constituent volume ranges are explicit, shown to the user and editable; they are
   suggestions until accepted. A folder name or filename pattern such as `1-3` is weak evidence, never truth.
5. **ISBN safety.** An ISBN found on or near a handmade omnibus is a candidate for review only. A matched official
   omnibus edition is applied only through the existing confirmed-match flow ([Book providers](BOOK_PROVIDERS.md)); a
   wrong edition is worse than none.
6. **Offline.** Composing, saving and removing a composite works with no network.
7. **No source modification.** The user's publication file is never rewritten, re-packed or "fixed up". A composite
   is separate app-owned artwork.
8. **No automatic external artwork.** Nothing in this feature downloads images on its own. Online cover discovery,
   where it exists, grants no redistribution right and no right to embed fetched art into a derived image (Section 7).

## 4. Single-file vs Series-based implementation

These are **separate deliverables** with different dependencies.

**Single-file (handmade or official omnibus LibraryItem).** The composite is the cover of one LibraryItem and is
stored through the established USER cover pipeline (4.5F.0 offline selection, bounded per-item storage). It depends
on nothing in Series. This can follow the existing pipeline directly.

**Series-based virtual omnibus.** The composite is the cover of a Series. It requires the Series foundation, which
is not implemented. Constituents are Series members (SERIES.md `SeriesMembership`); the Series' `cover choice`
already lists "generated composite/stack" as an accepted option. Until Series exists, no virtual-omnibus cover is
built, and no per-LibraryItem field is added to simulate one.

Neither deliverable adds a reader, merges files, or changes how members are read. "Read as Omnibus" remains the
coordination of existing reader sessions described in SERIES.md.

## 5. Choosing a cover (flow)

For a single-file handmade omnibus:

1. If an embedded cover exists and is usable, it is the LOCAL cover. The user can keep it.
2. **Choose from device** (existing) remains available.
3. **Make composite cover** (new) opens the Studio (Section 6) so the user can build one from artwork they already
   have.
4. A related volume's cover may be offered only as an explicit pick in the Studio or as a normal user cover choice,
   labelled as that volume's art, never as the omnibus's official cover.

For a Series: the Series page cover action offers first member cover, a chosen member's cover, a custom image and a
composite, per SERIES.md.

## 6. Composite Cover Studio (proposed)

A local, offline editor that produces one cover image from two to four source images.

**Inputs.** Two to four images from: covers of the volumes involved (their stored artwork, read-only), images the
user picks from device storage, or the item's existing cover. Initial editor limit is four; more is *pending design
decision*.

**Composition modes (initial proposal).**

- **Triptych**: two to four panels side by side, each cropped to its slot.
- **Primary plus supporting**: one dominant cover with smaller supporting covers overlapping or beside it.
- **Minimalist stack**: slightly offset stacked covers, largest on top.

Modes are layout presets over the same inputs; adding a mode must not change stored data. Exact geometry, spacing,
backgrounds and optional title text are *pending design decision*.

**User control.** Order is user-controlled (drag, or move earlier/later controls that work with keyboard and
gamepad). Each slot has a crop/position control. The Studio shows a live preview at the destination's display
shape (see Cover Presentation, 4.5A.2). Nothing is auto-cropped without the user being able to adjust it.

**Accessibility and input.** All controls are reachable by touch, keyboard and D-pad/gamepad through shared semantic
commands, with visible focus and text alternatives. A pointer-only drag is not the only way to reorder. Layout adapts
to phone portrait, tablet and landscape handheld (it never assumes a fixed aspect ratio).

## 7. Output, storage and provenance

- The result is **one raster image stored as a USER-selected cover** for the item (or, later, the Series), through
  the existing cover storage rules. It does not create a new cover slot kind.
- It must respect the existing per-cover storage budget (about 512 KiB per cover, bounded derivative dimensions) and
  the low-storage write guard. The Studio output is encoded to fit; if it cannot, it reports a truthful error rather
  than exceeding the budget. There is no automatic eviction.
- **Atomic persistence.** Write to a unique immutable file, update the database row, then delete the old file, as
  the current cover pipeline does. A crash must leave either the previous cover or the new one, never a half-written
  file. Cancelling the Studio writes nothing. Orphan temp files are cleaned up.
- **Provenance.** Record that the cover is a user-made composite and, for each constituent, where its artwork came
  from (a stored LOCAL cover of a named LibraryItem, a device file the user picked, or a previously user-selected
  image). The *shape* of this record is *pending design decision*; it must not store source URIs as durable identity
  (see portable backups in [Library Sources](LIBRARY_SOURCES.md)).
- **Rights.** Using stored covers of the user's own files, or images the user supplies, is a user action on the
  user's own library. Artwork obtained from an online provider is subject to that provider's terms and the project's
  cover-rights decisions (D1-C, D31); this feature does not enable or imply redistribution, and does not make fetched
  covers eligible as Studio inputs. Whether fetched art may be an input is *pending design decision* and requires
  owner approval.
- The composite is a derived image of the user's own choosing. It is never uploaded.

## 8. Edition matching and removal

- Offering a composite never changes the item's title, creators, identifiers or edition label.
- Deleting or replacing the composite returns to the next cover in precedence order; it never deletes constituent
  covers or any publication.
- If a constituent LibraryItem is later removed, the saved composite remains as ordinary USER artwork; it does not
  silently change.

## 9. Reusable components, dependencies, risks

- **Reuse:** USER cover pipeline and storage budget, shared cover renderer and Cover Presentation shapes, Choose from
  device, `shelfAction` focus, existing Library cover grid.
- **Depends on:** nothing new for single-file; the Series foundation for virtual omnibus.
- **Privacy:** fully local; no network, no account.
- **Performance risk:** decoding up to four bitmaps at once on modest hardware. Decode bounded, downsampled sources
  and free them before encoding. To be measured, not assumed.
- **Testing needed:** budget enforcement and truthful failure; atomic persistence under simulated crash and
  cancellation; precedence (user composite survives enrichment); order/crop determinism; keyboard and gamepad
  operability; configuration change and process recreation mid-edit (state preserved); source files unchanged;
  large and malformed constituent images.
- **Scope boundaries:** not an image editor, no text/effects authoring beyond what a later decision approves, no AI,
  no scraping, no cloud.

## 10. Decisions needing owner approval (open)

1. Whether fetched (online) covers may be Studio inputs.
2. Maximum constituent count beyond four.
3. Whether composite generation is free or part of a future Plus offering. **No entitlement is assigned here;** per
   the free-reading rules, nothing required for a pleasant core library experience may be paywalled without an
   explicit decision.
4. Provenance record shape and whether constituent artwork is retained for re-editing (storage cost) or only the
   final image is kept.
5. Whether re-editing an existing composite is supported or each edit starts fresh.
6. Whether an ADR is warranted (see Section 11).

## 11. Proposed ADR

None yet. Recommend an ADR *only* when the provenance record and the "composite is a USER cover, not a new slot"
decision are settled together with Rooms/Series covers, to avoid recording an unsettled shape.
