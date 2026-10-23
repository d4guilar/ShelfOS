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
| **Official omnibus** | A real published edition (for example a "Volumes 1-3" collected edition) with its own title, ISBN where one exists and authentic cover. | Its own. Identifiers come from the file's embedded metadata, the user, or a match under the existing [BOOK_PROVIDERS §3.8](BOOK_PROVIDERS.md) rules. | Its authentic cover, via the normal LOCAL / USER / (future) ONLINE slots. |
| **Handmade omnibus** | A user-owned single file that combines several volumes (for example `Berserk 1-3.cbz`). | **None inherited.** It does not take on an official omnibus's ISBN or edition because its contents resemble one. | User choice (including a user-made Composite Cover) if set; otherwise the established cover-resolution policy (USER > confident ONLINE > LOCAL > generated); otherwise the generated fallback. No separate omnibus priority. |
| **Virtual omnibus** | Several separately stored publications coordinated by a [Series](SERIES.md) "Read as Omnibus" experience. | Each member keeps its own. The Series has no ISBN. | A Series cover choice (member cover, custom image, or composite). Nothing is attached to member LibraryItems. |

A Series may *contain* an official omnibus as a member (`itemType = OMNIBUS` in SERIES.md); that member is an
ordinary LibraryItem and is distinct from virtual omnibus reading.

## 3. Rules that hold in every case

1. **No invented identifiers.** ShelfOS never assigns, guesses or derives an ISBN, edition id or provider id for an
   omnibus. A composite cover carries none.
2. **No silent borrowing.** A single constituent volume's cover is never silently used as an official omnibus cover.
   If it is used at all, the user chose it (Section 5).
3. **User choice wins.** A user-selected or user-composed cover always overrides embedded, fetched or generated art,
   consistent with cover precedence (Phase 4.5 constraints in the [roadmap](../ROADMAP.md)): user-selected or
   user-composed cover first; otherwise confident online or usable embedded artwork per the established cover policy;
   otherwise a generated fallback. Embedded artwork never outranks a USER choice. Automatic enrichment never replaces it.
4. **Reviewable ranges.** Constituent volume ranges are explicit, shown to the user and editable; they are
   suggestions until accepted. A folder name or filename pattern such as `1-3` is weak evidence, never truth.
5. **ISBN and edition safety.** The three cases are handled separately:
   - **Official omnibus.** A genuine published omnibus follows the established candidate and edition-matching rules in
     [BOOK_PROVIDERS §3.8](BOOK_PROVIDERS.md), including their accepted high-confidence and exact-identifier
     behavior. This specification adds no new confirmation requirement.
   - **Identified handmade omnibus.** Once an item has been positively classified as a personal compilation,
     automatic application of a constituent volume's bibliographic edition or online artwork is suppressed, and any
     embedded ISBN belonging to a constituent volume is review-only evidence, never automatic proof of the
     compilation's own published edition.
   - **Unknown or unclassified publication.** ShelfOS cannot currently tell that an arbitrary Book is a handmade
     omnibus. The existing metadata-query collection flag applies only to the filename-inferred title pathway for
     Comics and Manga; it does not cover every Comics/Manga metadata path and does not identify handmade Books. Recognizing handmade Books is unresolved (Section 10) and must exist before automatic Book-compilation
     safety is claimed. Possible mechanisms, all proposals and none existing: explicit user declaration, reviewable
     metadata evidence, accepted filename/collection evidence.

   The implemented metadata matcher is unchanged by this document.
6. **Offline.** Composing, saving and removing a composite works with no network.
7. **No source modification.** The user's publication file is never rewritten, re-packed or "fixed up". A composite
   is separate app-owned artwork.
8. **No automatic external artwork.** Nothing in this feature downloads images on its own. Online cover discovery,
   where it exists, grants no redistribution right and no right to embed fetched art into a derived image (Section 7).

## 4. Single-file vs Series-based implementation

These are **separate deliverables** with different dependencies.

**Single-file (handmade or official omnibus LibraryItem).** The composite is the cover of one LibraryItem and is
stored as the USER cover of that item, reusing the 4.5F.0 storage and lifecycle rules (Section 9 lists what is
reused and what is new). It depends on nothing in Series.

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
user picks from device storage, or the item's existing cover, each subject to the eligibility rule below. Initial
editor limit is four; more is *pending design decision*.

**Input eligibility (interim rule).** Until provider-specific eligibility has been reviewed and approved, a cover whose
provenance identifies an external provider, or whose provenance is missing or unknown, is not eligible as Composite
Cover Studio input. The USER slot's high display precedence is not authorization to transform or redistribute
artwork, because future production online cover selection may save provider artwork into that slot. Therefore:
provenance is checked independently of cover slot; a provider-origin USER cover remains subject to provider rules;
unknown provenance fails closed; and selecting externally sourced provider artwork as a cover does not, by itself,
grant permission to transform or redistribute that artwork (this does not prohibit ordinary images the user chooses
directly from local device storage); and
provenance-aware eligibility is a prerequisite before Studio support launches. No grant, licence or provider
permission is created here.

**Provenance of Studio inputs (future requirements, not implemented checks).**
1. Existing USER-cover records may lack explicit source provenance; the USER slot alone does not establish
   eligibility. (Since 4.5C.3A, schema v9, a USER row records `originKind` = `USER_SUPPLIED` or `PROVIDER_SELECTED`
   plus provider, edition and artwork references; rows written before v9 keep NULL = unknown and stay ineligible.
   The record is evidence for this rule, not an eligibility decision.)
2. An existing USER cover with unknown provenance is ineligible for Studio reuse until trustworthy origin evidence
   exists. Re-selecting the underlying image directly from device storage through a future Studio input flow can
   establish that evidence; re-selecting a cached provider cover cannot.
3. A genuinely user-supplied device image, or an eligible local embedded image, can be a Studio input when its source
   is recorded and applicable image-use requirements are respected.
4. ShelfOS-created copies, resized derivatives and re-encodes preserve their original provenance; re-encoding never
   erases a known provider restriction.
Provider-origin or unknown-origin artwork is not eligible for Studio reuse unless the relevant provenance and rights
conditions have been resolved. No licence, provider permission or database field is created here.

**Unusable inputs.** A missing, corrupt, unreadable or unsupported input is marked unavailable with a truthful
reason, and the user may replace or remove it. Saving requires at least two usable, eligible inputs. A failed
composition leaves the previously saved cover intact. (This describes proposed behavior, not an implemented UI.)

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

- The result is **one raster image stored as a USER-selected cover** for the item (or, later, a Series, which needs
  its own new storage), following the existing cover storage rules. It does not create a new cover slot kind.
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
  cover-rights decisions (D1-C-PROD for the authorized implementation scope; D31 for aggregate-use and operational
  compliance); this feature does not enable or imply redistribution, and does not make fetched covers eligible as
  Studio inputs (see the interim eligibility rule in Section 6). Whether fetched art may be an input is *pending design decision* and requires
  owner approval.
- The composite is a derived image of the user's own choosing. It is never uploaded.

## 8. Edition matching and removal

- Offering a composite never changes the item's title, creators, identifiers or edition label.
- Deleting the composite reveals the next eligible source in precedence order; replacing it displays the newly saved
  USER cover. Neither deletes constituent covers or any publication.
- If a constituent LibraryItem is later removed, the saved composite remains as ordinary USER artwork; it does not
  silently change.

## 9. Reusable components, dependencies, risks

- **Reusable existing concepts and infrastructure:** cover rendering and Cover Presentation shapes; existing cover
  encoding and storage rules; the roughly 512 KiB per-cover budget; the low-storage guard; crash-safe atomic
  persistence principles; USER-cover precedence; existing user cover management patterns (Choose from device);
  `shelfAction` focus; the Library cover grid. Cover persistence today is keyed to existing LibraryItems.
- **New implementation requirements:** a bounded image-composition renderer; a way to save generated image bytes or a
  generated file through an appropriate USER-cover path; provenance for multiple constituent inputs; eligibility
  enforcement for Studio source images; safe replacement, deletion and recovery; and separate storage and ownership
  for virtual Series covers (no Series-cover entity exists). A database migration may be needed for provenance or
  group-cover ownership; no schema is specified here.
- **Depends on:** the new items above for single-file; additionally the Series foundation for virtual omnibus.
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

1. Whether fetched (online) covers may be Studio inputs under specific provider policies (until decided, the
   Section 6 interim rule applies).
2. How handmade Books are recognized as personal compilations (explicit user declaration, reviewable metadata
   evidence, accepted filename/collection evidence, or another mechanism). Unresolved; no Book-compilation safety is
   claimed until it exists.
3. Maximum constituent count beyond four.
4. Whether composite generation is free or part of a future Plus offering. **No entitlement is assigned here;** per
   the free-reading rules, nothing required for a pleasant core library experience may be paywalled without an
   explicit decision.
5. Provenance record shape and whether constituent artwork is retained for re-editing (storage cost) or only the
   final image is kept.
6. Whether re-editing an existing composite is supported or each edit starts fresh.
7. Whether an ADR is warranted (see Section 11).

## 11. Proposed ADR

None yet. Recommend an ADR *only* when the provenance record and the "composite is a USER cover, not a new slot"
decision are settled together with Rooms/Series covers, to avoid recording an unsettled shape.
