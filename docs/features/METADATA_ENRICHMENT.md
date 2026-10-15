# Metadata Enrichment Specification

Phase 0 contains original mock metadata only. The extraction, inference, editing,
provenance and provider work described below starts with functional import/library
phases; none is implemented in the Phase 0 prototype. See ADR-0016.

**Implementation authority (2026-10-15):** this specification is delivered by
[Phase 4.5 — Metadata, Covers & Library Presentation](../PHASE_4_5_IMPLEMENTATION_PLAN.md).
Current state at Room v5: only EPUB title/creator and ComicInfo title/writer/manga-direction
are extracted; every Library cover is generated; there is no network access. Phase 4.5
requires Books, Comics **and** Manga enrichment for acceptance (§17 below is reconciled);
for Comics this means an approved, integrated issue-level online provider in addition to
local extraction (local artwork alone does not satisfy it). Stored cover artwork is bounded per item (≈ 512 KiB per cover): small supported images byte-exact within limits, larger
ones as a bounded derivative that records the original dimensions; no automatic eviction
and a low-storage write guard (plan §7, §18.1).

## 1. Goal

ShelfOS should transform an imported file into a useful library object without requiring users to manually type information that can be safely inferred or resolved.

Metadata enrichment is optional network-assisted convenience layered on top of a local-first library.

Reading the source publication must never depend on metadata lookup succeeding.

Optional means never required, not opt-in: when enrichment is implemented, ordinary
users should receive good default background enrichment without configuring providers,
keys or accounts.

Metadata matching is also distinct from publication identity: resemblance alone must
never prove that two imports are the same publication, even though enrichment and
future identity evidence may share candidate-resolution concepts. See
[publication identity across removal and re-import](LIBRARY_SOURCES.md#publication-identity-across-removal-and-re-import-future).

### Beautiful by default

An accepted product goal is that importing a recognizable publication leaves the library
visually polished with as little manual cleanup as possible.

After importing a Book, Comic or Manga item, the user should not normally need to hunt
for a cover manually. This follows from the core visual principle:

> **The interface is monochrome. The library is the color.**

Cover quality is therefore part of the core Library experience, not merely cosmetic
polish: ShelfOS is cover-first, and publication artwork is the main visual identity of
the user's collection.

Because the system chrome stays restrained, an import flow that succeeds but leaves
confidently identifiable Books, Comics or Manga with poor generic artwork indefinitely is
not the intended final ShelfOS experience. This is a product-quality target only. It must
**not** be used to justify blocking local import or reading on network enrichment.

The rules below describe accepted product direction for behavior that is **not yet
implemented**; the current prototype implements only the local-first paths listed in §17.

## 2. Metadata fields

ShelfOS may manage:

- title
- subtitle
- creators/authors
- description/synopsis
- publisher
- publication date/year
- language
- ISBN and other identifiers
- subjects/categories
- series
- volume
- issue where applicable
- cover
- page count where trustworthy
- media category
- reading direction for sequential art
- provenance/confidence per field or resolved record

Not every format/provider supplies every field.

## 3. Core rule: user data wins

Manual edits must never be silently overwritten.

Suggested provenance order:

```text
USER
> EMBEDDED
> EXACT_IDENTIFIER_MATCH
> HIGH_CONFIDENCE_EXTERNAL_MATCH
> INFERRED_EXTERNAL_MATCH
> FILENAME/FOLDER_INFERENCE
> GENERATED
```

A metadata refresh may update unresolved/non-user fields but must preserve user overrides.

### Cover resolution precedence

Cover art is deliberately treated differently from other fields, because a confidently
matched, high-quality remote cover **should** be able to improve a weak embedded or
generated cover that no user ever chose.

Conceptual cover precedence:

```text
USER-SELECTED COVER
>
CONFIDENT HIGH-QUALITY ONLINE COVER
>
USABLE EMBEDDED COVER
>
GENERATED / FALLBACK COVER
```

Rules:

- A user-selected or user-uploaded cover **MUST** always win.
- Automatic enrichment **MUST NOT** silently replace a user-selected cover, in the first
  pass or any later refresh.
- A high-confidence online cover **MAY** replace a non-user embedded or generated cover.
- A weak or ambiguous match **MUST NOT** replace a usable local cover.
- Cover selection MAY use its own documented resolution policy, as long as
  confidence and user ownership are respected.

This does not change the general provenance order in §3 for other fields. It records that
cover resolution has its own policy, and that USER remains the absolute top priority in
every case.

### Cover presentation (accepted 2026-10-15)

Cover source and cover display are separate. ShelfOS keeps the best available artwork under
a per-item bounded storage policy (≈ 512 KiB per cover; small originals byte-exact within
limits, larger ones as a bounded derivative that records the original dimensions; no
automatic eviction; the source publication is never modified) and never
rewrites it into a fixed shape. A shared cover renderer applies the effective display
shape — **Original, 2:3, 3:4, 4:5, 1:1 or Custom W:H** (custom ratios 1:2 to 2:1; no Auto
option; default 2:3 Crop, Documents Original) — with
**Crop to fill** or **Fit entire artwork**, never stretching or squashing. The effective
shape is a global default with optional per-category overrides (Books, Comics, Manga,
Documents); per-Shelf and per-item overrides are later work. Details in the
[Phase 4.5 plan](../PHASE_4_5_IMPLEMENTATION_PLAN.md) §7–§8.

## 4. Provider architecture

Do not hard-code one metadata service into UI or domain logic.

Concept:

```text
MetadataProvider
├── EmbeddedMetadataProvider
├── IdentifierDetector
├── FilenameInferenceProvider
├── OpenLibraryProvider
├── GoogleBooksProvider          REJECTED for Phase 4.5 (plan §14); not to be built
├── ComicMetadataProvider        slot (Phase 4.5E; terms-gated)
└── MangaMetadataProvider        slot (Phase 4.5D; terms-gated)
```

Comic/Manga providers are `MetadataProvider` implementations scoped to those categories, not
separate abstractions. Open Library is promising but conditional (not production-approved
until its terms review passes); Google Books is rejected for Phase 4.5 (see §10).

A `MetadataEnrichmentService` coordinates providers and produces candidates.

Potential interface:

```text
search(query): List<MetadataCandidate>
lookup(identifier): MetadataCandidate?
enrich(seed): List<MetadataCandidate>
```

Provider implementations remain replaceable.

The product has three enrichment layers:

1. **Offline complete:** embedded metadata, filename/folder candidates, local
   analysis, future local OCR/search, generated covers and local reading.
2. **Default online enrichment:** background metadata/cover improvement with no
   API configuration expected from ordinary users.
3. **Power user / BYOK:** optional specialist providers, user keys and provider
   priority.

BYOK is an enhancement, never a prerequisite for a good library. Secret provider
credentials must not ship in an open-source APK; bundled client values are
discoverable. Prefer no-key/public providers or authentication designed for
installed clients. Keep user keys local where practical, and do not add a proxy
merely to hide secrets without deliberately accepting its infrastructure and
privacy costs.

## 5. Ingestion integration

[DATA_INGESTION](DATA_INGESTION.md) owns the common pipeline. Its fast local pass
extracts supported embedded metadata/identifiers and filename/folder evidence,
then stages organization proposals for review and commit. External enrichment
operates on committed LibraryItems in the background; reading and local import
never wait for provider availability, rate limits or candidate lookup.

Import MUST stay local-first and immediate:

1. the publication becomes locally usable;
2. embedded/local metadata and any available local cover information are used
   immediately;
3. reading starts without waiting for the network;
4. optional background metadata and cover enrichment follows when connectivity and
   a provider are available.

Network or provider failure MUST NOT prevent import, staging, commit or reading.

Filename parsing may propose title, creator, year, Series, volume or issue from
signals such as `by`, `written by`, four-digit years, brackets, `Vol`/`Volume`,
`Issue`/`#`, separators and parent folders. These are reviewable candidates, never
authoritative truth. For example, `Dune by Frank Herbert (1965).pdf` may propose
Dune / Frank Herbert / 1965. **ShelfOS proposes; the user wins.** Valid structured
metadata such as `ComicInfo.xml` normally outranks filename inference.

Series/volume/issue metadata, `ComicInfo.xml`, folder hierarchy, category hints and
LibrarySource context feed [reviewable Series detection](SERIES.md). Persist
provenance/confidence for inferred fields and grouping proposals. Folder/source
evidence is weaker than trusted embedded or user data. User classification,
ordering, grouping and rejection decisions must survive rescans and refresh.

Online Series candidates may improve unresolved metadata later but must not
silently regroup items or override Manual Shelves. Neither Series nor Shelves
requires online metadata. Cache permitted covers and resolved fields locally.

## 6. EPUB

Prefer structured publication metadata first.

Typical useful fields:

- title
- author
- language
- identifier
- publisher
- date
- embedded cover

External enrichment is a supplement, not a replacement.

### Default background enrichment for Books

When online, and when the publication can be identified with sufficient confidence,
background enrichment SHOULD attempt to improve unresolved, non-user fields:

- title
- subtitle where available
- authors/creators
- description/synopsis
- publisher
- publication date/year
- language
- identifiers such as ISBN
- subjects/categories where trustworthy
- Series/volume data where trustworthy
- cover art

A high-confidence external match MAY populate unresolved metadata. User overrides always
win. A weak or ambiguous match MUST NOT silently overwrite good local data.

### Default cover behavior for Books

For a confidently identified Book, ShelfOS SHOULD automatically choose a strong
available front-cover candidate when enrichment runs. This states the selection goal,
not a provider-specific algorithm.

Candidate preference, roughly in order:

1. correct publication/edition match;
2. correct front-cover artwork;
3. correct orientation and a sensible book-cover aspect;
4. best available resolution permitted by the provider;
5. a stable, reliable candidate rather than an arbitrary low-resolution thumbnail.

When confidently resolved, the improved cover SHOULD become the normal displayed Library
cover without requiring per-item user approval. It SHOULD be cached locally where
licensing/provider terms permit, so the Library keeps looking correct offline.

This applies to Books specifically; see §8 for Comics/Manga and §7 for Documents.

## 7. PDF

PDF metadata quality varies greatly.

Potential signals:

- PDF document properties
- filename
- first-page/title-page text where extraction is available
- ISBN-like identifiers
- creator/title combinations

Do not assume a PDF filename is a title.

Scanned PDFs may require future OCR to discover text-based identifiers.

OCR is not an early-phase dependency.

### Documents and cover art

Documents MAY use custom cover art. ShelfOS MUST NOT aggressively match generic
documents to unrelated books merely to make them prettier.

Default Document presentation MAY use, depending on available information:

- embedded cover or artwork;
- a suitable first-page/thumbnail representation where supported;
- a generated fallback.

If a Document is confidently identified as a real publication, optional enrichment MAY
improve it like any other item. Regardless of identification, Edit Publication MUST allow
the user to choose a custom cover from the device. Where an online cover/metadata search
is appropriate for the document, the same provider-neutral candidate mechanism described
in §13 MAY be offered manually.

There is no requirement that every random PDF or document automatically receives an
online book cover.

## 8. CBZ / sequential art

Potential sources:

- archive filename
- embedded `ComicInfo.xml` where present
- user classification
- later comic/manga metadata providers

Useful valid `ComicInfo.xml` fields include Title, Series, Number, Volume, Year,
Writer and Publisher. They can inform credits, dates, Series detection/order and
cover selection, while still yielding to user overrides.

Do not force book-oriented metadata providers onto comics/manga if match quality is poor.

### Default enrichment for Comics and Manga

Comics and Manga receive the same visual priority as Books. For a confidently identified
Comic or Manga item, automatic enrichment SHOULD attempt to resolve:

- title
- Series
- issue/volume
- creators where available
- description/synopsis where available
- publisher
- date/year
- relevant identifiers
- correct cover artwork

Cover matching for sequential art SHOULD pay particular attention to:

- the correct Series;
- the correct issue or volume;
- the correct edition where relevant;
- the correct front cover;
- appropriate orientation/aspect;
- the highest useful resolution available.

Provider architecture SHOULD stay open for specialist Comic/Manga providers (see §10).
Provider-specific implementation is out of scope for this specification.

## 9. Candidate confidence

Initial scoring can be rule-based.

Illustrative signals:

```text
Exact ISBN / stable identifier   very high
Exact normalized title           high
Creator match                    high
Series + volume match            high
Year match                       medium
Publisher match                  medium
Language match                   low/medium
Filename-only match              low
```

Exact thresholds should be tuned with real test data.

Behavior:

- very high confidence → may auto-apply
- medium confidence → show "We think this is..."
- low confidence → ask user to search/select or keep embedded/inferred data

Never silently overwrite confident local metadata with a weak remote guess.

## 10. External provider candidates

Initial candidates:

### Open Library

Useful for:

- ISBN/title/author lookup
- editions/works
- covers

### Google Books

**REJECTED FOR PHASE 4.5** — not a fallback, not a secondary provider and not an optional
BYOK feature — unless explicit written Google permission resolves its usage restrictions.
The earlier "useful fallback/secondary provider" description is withdrawn.

Do not assume current provider behavior is permanent.

**Terms review (2026-10-15; reconciliation):** Google's Books terms say you "may not charge
users any fee for the use of your application" (ShelfOS has a paid Plus tier), Google's API
terms forbid developer credentials in open-source projects (ShelfOS is open source) and
forbid permanent copies beyond cache headers (ShelfOS caches covers for offline use), and
results must not be altered and carry adjacent Google branding (ShelfOS reframes covers and
applies its own precedence). A user-supplied key does not remove these application-level
obligations. Open Library is **promising but conditional**: production approval is pending
(it advises caching responses, asks that the cover API not be crawled, and does not assert
rights over covers; see the plan §14). Comic and Manga provider findings (Metron, Comic
Vine, GCD, MangaDex, AniList and others) are in the
[Phase 4.5 plan](../PHASE_4_5_IMPLEMENTATION_PLAN.md) §14. **Metron** (comics project at
`metron.cloud`) is the preferred, **conditional** Comics candidate on the basis of its
official commercial-use statement of 2026-10-04: CC BY-SA 4.0 data permits commercial use
with attribution and ShareAlike; each user supplies their own account/token (no shared
bundled account — the statement says not to ship one account shared by all users); tokens
are recommended and "Basic Auth will be retired eventually"; limits are 20 requests/minute
and 5,000/day (a donation can raise the daily limit up to 25,000); caching is recommended;
publisher cover images are **not** under that license (Metron hosts them as fair use, which
does not automatically extend to ShelfOS). `metron.live` is unrelated to Metron comic
metadata, and the `metron.cloud` service terms stay unresolved. It is not approved until the current service terms are reviewed and the
ShareAlike questions are answered (plan D5, D19).

## 11. Local-first caching

After successful enrichment, persist usable metadata locally.

Covers used by the library should be cached locally where licensing/provider terms permit.

Benefits:

- offline library browsing
- fast details pane
- less repeated API usage
- lower provider dependency

A future refresh should be explicit or controlled, not performed on every screen opening.
Caching and retention rights must be reviewed per provider; ShelfOS does not assume
that every response or cover can be stored permanently.

Where provider terms allow, ShelfOS SHOULD:

- cache the selected cover locally;
- cache resolved metadata locally;
- avoid re-fetching on every Library open;
- keep the polished Library experience available offline.

If a network or provider later disappears, the user's local publications MUST remain
readable and correctly displayed from cached state.

## 12. UI states

### Exact/high-confidence match

Example:

```text
Metadata
Open Library · Exact ISBN match
```

Normal screens show the enriched data without extra ceremony.

### Possible match

Example import flow:

```text
We think this is:

Dune
Frank Herbert
1965

[ Use this match ]
[ Choose another ]
[ Keep file metadata ]
```

### No match

Keep local/embedded data and allow:

- manual editing
- manual search
- cover selection
- generated cover

## 13. Manual edit surface

Users should be able to edit:

- title
- creator
- description
- category
- series
- volume
- year
- cover
- reading direction where relevant

Advanced identifiers may live under technical details.

### Cover actions in Edit Publication

Cover SHOULD offer at least two user-facing actions:

**A. Choose from device**

- the user may select an image from device storage/gallery/files;
- this creates a `USER` override;
- automatic refresh MUST NOT replace it.

**B. Find cover online**

- the user may search through ShelfOS metadata-provider abstractions;
- several useful candidates SHOULD be shown where available;
- the user chooses the exact artwork/edition they prefer;
- the choice becomes a `USER` override.

The UI MUST NOT be coupled directly to Open Library, Google Books or any individual
provider. It asks the ShelfOS metadata layer for candidates and applies the cover
precedence rules in §3.

## 14. Refresh behavior

`Refresh metadata` should:

- preserve all `USER` fields
- attempt to improve unresolved fields
- show ambiguous matches instead of forcing them
- never remove usable local metadata merely because a provider is unavailable

## 15. Privacy

Do not upload the publication file for ordinary metadata enrichment.

Queries should use the minimum metadata needed, such as:

- ISBN
- title
- creator

If future OCR/content analysis requires network transmission, it must be a separate explicit feature with clear disclosure.

## 16. Failure behavior

Network/provider failure must degrade to:

- embedded metadata
- inferred metadata
- user edits
- generated fallback cover

Reading remains available.

## 17. Initial implementation scope

### Early prototype

- embedded metadata
- filename inference
- manual metadata editing
- cover extraction
- generated fallback cover
- provenance model

### Next enrichment milestone (Phase 4.5)

- ISBN/identifier detection
- local cover artwork extraction and cover presentation (shape + Crop/Fit)
- Open Library provider (promising/conditional; production approval pending; not needed by 4.5A)
- Google Books is **rejected for Phase 4.5** (not a fallback, not an optional BYOK) unless explicit written Google permission resolves its usage restrictions (§10)
- Comic and Manga enrichment (required for Phase 4.5 acceptance; providers terms-gated; Comics
  need an approved online path, not local-only)
- confidence scoring
- match confirmation
- background automatic metadata enrichment after commit
- best suitable cover selected by default for confidently matched items
- cover candidate picker ("Find cover online")
- custom user cover from device
- local caching
- Refresh metadata action

### Later

- additional BYOK specialist comic/manga providers beyond the Phase 4.5 slots
- OCR-assisted identification
- series intelligence
- bulk library enrichment
