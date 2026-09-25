# Metadata Enrichment Specification

Phase 0 contains original mock metadata only. The extraction, inference, editing,
provenance and provider work described below starts with functional import/library
phases; none is implemented in the Phase 0 prototype. See ADR-0016.

## 1. Goal

ShelfOS should transform an imported file into a useful library object without requiring users to manually type information that can be safely inferred or resolved.

Metadata enrichment is optional network-assisted convenience layered on top of a local-first library.

Reading the source publication must never depend on metadata lookup succeeding.

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

## 4. Provider architecture

Do not hard-code one metadata service into UI or domain logic.

Concept:

```text
MetadataProvider
├── EmbeddedMetadataProvider
├── IdentifierDetector
├── FilenameInferenceProvider
├── OpenLibraryProvider
├── GoogleBooksProvider
├── ComicMetadataProvider        later
└── MangaMetadataProvider        later
```

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

Useful fallback/secondary provider for:

- titles
- authors
- descriptions
- publication metadata
- identifiers
- categories
- covers where permitted

Provider use must respect current API terms, quotas, attribution requirements, and licensing at implementation time.

Do not assume current provider behavior is permanent.

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

### Next enrichment milestone

- ISBN/identifier detection
- Open Library provider
- Google Books fallback
- confidence scoring
- match confirmation
- local caching
- Refresh metadata action

### Later

- comic/manga providers
- OCR-assisted identification
- series intelligence
- bulk library enrichment
