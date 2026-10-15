# Phase 4.5 Implementation Plan — Metadata, Covers & Library Presentation

Status (2026-10-15): **PLANNING. No Phase 4.5 slice is implemented or authorized beyond
this plan.** Phase 4 (Notes and Knowledge Layer) is COMPLETE (Phase 4F acceptance merged
in PR #42). Phase 4.5 is the next active milestone. Phase 5 remains Advanced PDF Ingestion;
numbering after 4.5 is unchanged.

This plan is the **implementation authority** for Phase 4.5. Product and architecture
behavior remain owned by [Metadata enrichment](features/METADATA_ENRICHMENT.md),
[PRODUCT](PRODUCT.md), [ARCHITECTURE](ARCHITECTURE.md) and the ADRs; where this plan
records a decision those documents did not yet contain, they were reconciled in the same
change (see [CHANGELOG_DOCS](CHANGELOG_DOCS.md)).

Markers used below:

- **(verified)** — checked against the repository at `e869762` or an official provider page
  on 2026-10-15; the path or URL is named.
- **(proposed)** — a recommendation for administrator review, not an accepted design.
- **(open)** — an unresolved decision; numbered in §31.
- **RESEARCH / NOT YET APPROVED** — a provider whose legal/usage fit is not established.
  No provider in this plan is production-approved; approval is an administrator decision
  after terms review (§14, §31).
- **CONDITIONAL** — a provider that is the preferred candidate but whose approval still
  depends on a named unresolved prerequisite (currently Metron, §14, D5).

Remediation (2026-10-15, after the first independent Codex High review): Comics acceptance
now requires an approved online path (§30, D3); Metron research was redone from Metron's
official commercial-use statement (§14); cover storage is bounded rather than byte-exact
(§7, §18); 4.5A is split into two independent PRs, 4.5A.1 and 4.5A.2 (§29); the
`publication_cover` design keeps one row per artwork kind (§23); extraction failures are
split into permanent and temporary (§23.3, draft).

---

## 1. Purpose and product rationale

> **The interface is monochrome. The library is the color.**
>
> **The file is the source of truth; metadata turns the folder into a library.**

ShelfOS is cover-first and card-light (`docs/design/CLASSIC_UI.md`,
`docs/design/VISUAL_IDENTITY.md`). Its chrome is deliberately neutral, so publication
artwork carries the Library's identity. Today every Library tile is a generated geometric
placeholder (§6). That is acceptable for a prototype; it is not the intended ShelfOS
Library. Phase 4.5 is therefore **not optional polish**: Library identity depends on real
artwork and correct metadata.

Phase 4.5 delivers, in order:

1. real local artwork (embedded / first-page covers) with non-destructive, user-configurable
   cover presentation;
2. a provider-neutral metadata core with provenance and confidence;
3. confident online enrichment for **Books, Comics and Manga** — all three are required for
   phase acceptance;
4. manual cover/metadata flows (Choose from device, Find cover online, Refresh metadata);
5. a phase-closure acceptance with physical UAT.

Local files stay authoritative. Online enrichment is optional network-assisted convenience:
import and reading never depend on any provider, and a wrong match is worse than no match.

Documents stay conservative: embedded/local metadata, first-page or generated presentation,
custom covers, and optional enrichment only when confidently identifiable. ShelfOS never
forces book matching onto arbitrary Documents.

## 2. Current repository baseline (verified)

- Branch `phase-4.5/metadata-covers-planning`, created from `main` @
  `e869762dd56c55cd2ad7c67fa72cd87dc9e19c9b` ("fix: finalize Phase 4 acceptance (#42)").
  Working tree clean.
- Single app module; Kotlin 2.4.20, Compose BOM 2026.09.00, Room 2.8.5, coroutines 1.11.0,
  Readium 3.4.0, jsoup 1.23.2, `minSdk` 24 (`gradle/libs.versions.toml`,
  `app/build.gradle.kts`).
- **Room database version 5** (`core/database/ShelfDatabase.kt`), `exportSchema = true`,
  schemas `app/schemas/com.d4guilar.shelfos.core.database.ShelfDatabase/1..5.json`.
  Hand-written `Migration` objects only (`MIGRATION_1_2` … `MIGRATION_4_5`); no
  destructive fallback; no `room-testing` dependency (migration tests use the repo's raw
  SQLite instrumented pattern, see `PHASE_4_IMPLEMENTATION_PLAN.md` §8.5).
- Entities: `appearance_preference`, `library_item`, `reading_state`, `reader_preference`,
  legacy `bookmark` (retained, unused at runtime), `annotation`,
  `annotation_source_snapshot`.
- **No network capability.** `app/src/main/AndroidManifest.xml` explicitly *removes*
  `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` with `tools:node="remove"`
  (they arrive transitively). No HTTP client, image-loading library, WorkManager or
  DataStore is a dependency (`gradle/libs.versions.toml`; `docs/DEPENDENCY_LICENSES.csv`
  lists no OkHttp, Coil, Glide, Ktor, Retrofit or `work-runtime`). No `core.network` or
  `data.metadata` package exists yet, although `docs/ARCHITECTURE.md` §3 reserves them.
- Platform-only building blocks already in use: `org.json` (`core/reader/ReaderPreferences.kt`),
  jsoup XML parsing with entity rejection (`core/files/EmbeddedMetadata.kt`),
  `BitmapFactory`/`PdfRenderer` page decoding and a byte-budgeted LRU
  (`core/reader/ThumbnailLoader.kt` `ByteBudgetedLruCache`), app-wide
  `AppContainer.backgroundScope` (`ShelfApplication.kt`).

## 3. Canonical product decisions

These are fixed by the administrator for Phase 4.5:

1. Milestone name: **Phase 4.5 — Metadata, Covers & Library Presentation**, between Phase 4
   and Phase 5. Phase 5+ are not renumbered.
2. Phase 4.5 **cannot close with Books only**: Books, Comics and Manga must each have viable
   enrichment and cover workflows (§30). Documents stay conservative. For Comics, "viable"
   **requires an approved, integrated issue-level online provider**; local extraction and
   local artwork alone do not satisfy it (§30). If none is approved and integrated, 4.5E and
   4.5G stay open and the phase does not close.
3. Cover **source** and cover **presentation** are separate. ShelfOS keeps the best
   available artwork under a bounded storage policy (small originals byte-exact, larger
   ones as a bounded high-quality derivative that records the original dimensions; §7) and
   controls display shape separately:
   `provider/embedded artwork → stored artwork (ORIGINAL or DERIVED) → ShelfOS cover
   renderer → user display aspect → Crop or Fit`. Never stretch or squash. The source
   publication is never modified and always keeps the full-size original artwork.
4. Cover shape options: **Original**, **2:3**, **3:4**, **4:5**, **1:1**, **Custom W:H**.
   Cover fit: **Crop to fill** or **Fit entire artwork**.
5. Preference hierarchy: **global default → optional per-category override** (Books,
   Comics, Manga, Documents). No per-Shelf or per-item aspect override in the first
   implementation.
6. UI/domain never depend on a provider brand. Provider-neutral abstractions
   (`MetadataProvider`, `MetadataEnrichmentService`, `MetadataCandidate`, identifier lookup,
   search, enrich) with replaceable `ComicMetadataProvider` / `MangaMetadataProvider` slots.
7. Matching is confidence-based; VERY HIGH may auto-apply to unresolved non-user fields;
   MEDIUM asks; LOW never silently modifies. User overrides always win.
8. Cover precedence: **USER-SELECTED > CONFIDENT HIGH-QUALITY ONLINE > USABLE EMBEDDED >
   GENERATED/FALLBACK**.
9. Enrichment is post-import background work. Order: local file usable → local metadata
   applied → reading works → online enrichment afterwards.
10. No secret credentials in the APK; keyed providers are acceptable only as user-supplied
    keys (BYOK); provider terms reviewed before any production use.

## 4. Non-goals

- No Phase 5 work: no PDF text extraction, Adapted PDF, PublicationDocument/SourceMap, PDF
  ISBN discovery from page text, or OCR.
- No Phase 6 ink/stylus work.
- No ShelfOS accounts, cloud sync, backend or metadata proxy.
- No AI/LLM metadata services; no web scraping; no HTML page parsing of provider sites.
- No scheduled cloud-style periodic re-scraping; refresh is deliberate or bounded
  background work (§19).
- No per-Shelf or per-item cover aspect overrides in the first implementation (later work).
- No bundled secrets, API keys or client credentials.
- No Series entity, Series grouping or Shelf automation (Series/volume/issue are stored as
  metadata evidence only; `docs/features/SERIES.md` owns grouping later).
- No modification of source publications (ComicInfo/OPF are never written back).
- No new dependency without the AGENTS.md review (license, maintenance, Android support,
  binary size, need). This plan assumes **zero new dependencies** (§19, §31).
- No Premium gating of metadata, covers or presentation — all of it is free core.

## 5. Current local metadata architecture inventory (verified)

| Concern | Today | Where |
| --- | --- | --- |
| Persisted fields | `title`, `creator`, `category`, `format`, `fileName`, `byteSize`, `favorite`, `addedAt`, `available`, `managedPath`, `titleOrigin`, `creatorOrigin` | `core/database/LibraryDao.kt` `LibraryEntity` |
| Provenance | Two free-text origin strings: `"embedded"`, `"filename"`, `"user"`, `"unknown"` | `core/files/PublicationFiles.kt` (import), `feature/importing/ImportViewModel.kt` (review edits), `feature/importing/ImportDialogs.kt` `originLabel` |
| Embedded extraction | `EmbeddedMetadata(title, creator, rightToLeftManga)` only. EPUB: OPF `dc:title`, `dc:creator`. CBZ/CBR: ComicInfo `Title` (or `Series` + `Number`), `Writer`, `Manga=YesAndRightToLeft`. Bounded (1 MiB XML), DTD/entity-safe, best-effort | `core/files/EmbeddedMetadata.kt`, `RarContainer.comicInfo()` |
| PDF metadata | None (Android `PdfRenderer` exposes no document info) | `core/files/PublicationFiles.kt` `inspect` |
| Not extracted | ISBN/identifiers, publisher, date, language, description, Series/Number/Volume as separate fields, Penciller etc. | — |
| Manual edit | Title, creator, category; sets both origins to `user` | `LibraryDao.edit`, `feature/importing/ImportDialogs.kt` `PublicationEditor`, `feature/library/PublicationDetails.kt` |
| Detail display | Shows title/creator origins; cover line always "generated" (`R.string.detail_cover_generated`) | `feature/library/PublicationDetails.kt` |
| Global reader preferences | JSON in `reader_preference` row with empty `itemId` | `LibraryDao.globalPreferences`, `ReaderPreferences.kt` |
| Theme preference | `appearance_preference(id, themeKey)` single row | `ShelfDatabase.kt`, `data/preferences/ThemeRepository.kt` |
| Interface language | AppCompat per-app locales (not Room) | `data/preferences/AppLanguageRepository.kt` |

There is **no** `MetadataProvider`, `MetadataEnrichmentService`, `MetadataCandidate`,
`IdentifierDetector` or provenance enum in code. They exist only as specification
(`features/METADATA_ENRICHMENT.md` §4). Phase 4.5 introduces them; nothing is duplicated.

## 6. Current cover architecture inventory (verified)

- **No real cover artwork exists.** No code extracts an EPUB cover, a CBZ/CBR first page as
  a cover, or a PDF first-page cover; no cover file is stored; no cover row exists in Room
  (`grep` for cover parsing in `app/src/main/java` finds none).
- Every cover is `PublicationCoverVisual(libraryItemId, title)`
  (`core/designsystem/PublicationCoverVisual.kt`): a Canvas-drawn geometric motif in one of
  four colors, hashed from the item id, with the title in serif type, clipped at a **fixed
  `aspectRatio(0.68f)`** (≈ 2:3), decorative semantics (`clearAndSetSemantics {}`).
  `feature/library/PublicationCover.kt` is a thin wrapper.
- `LibraryItem.coverColor`/`coverMotif` duplicate the same hash in the domain model
  (`domain/library/LibraryItem.kt`) — presentation logic leaking into domain; 4.5A.1 should
  remove the duplicate when it introduces the shared renderer.
- Consumers of the cover visual: Library grid tile and Continue Reading row
  (`feature/library/LibraryScreen.kt`, `GridCells.Adaptive(112/136 dp)`), details pane
  (`PublicationDetails.kt`, width derived from `0.68f`), reader-entry expand transition
  (`feature/home/CoverExpandTransition.kt`), Notes hub rows (Phase 4D, shared generated
  visual).
- Image decoding infrastructure exists only for reader pages/thumbnails
  (`core/reader/FixedReader.kt`, `ThumbnailLoader.kt` with `ByteBudgetedLruCache`). No
  image-loading library.

Consequence for slicing: "preserve original dimensions" has nothing to preserve yet, and
Crop/Fit cannot be validated against generated art. **4.5A therefore begins with local
artwork extraction as its own PR (4.5A.1)**, with presentation preferences following as
4.5A.2 (§29, adjustment A).

## 7. Cover presentation model

Pipeline (proposed):

```text
artwork source (embedded file entry | PDF first-page render | user image | provider image)
  → stored artwork (ORIGINAL bytes if small and supported, else a bounded DERIVED copy;
    source pixel dimensions always recorded; see rule 1)
  → ShelfOS cover renderer (one shared composable)
  → effective display aspect (resolved preference, §8)
  → Crop to fill | Fit entire artwork
```

Rules:

1. **Bounded artwork storage; the source publication is never touched (proposed, (open)
   D12/D20).** The original artwork always remains inside the source EPUB/CBZ/CBR (or the
   provider/user file); ShelfOS never duplicates multi-megabyte comic pages just to show
   Library covers. Every stored artwork file is recorded as **ORIGINAL** or **DERIVED**:
   - *ORIGINAL*: a supported image (JPEG, PNG, WebP, GIF, BMP — whatever
     `BitmapFactory` decodes on the device) that is **at most ~1 MiB and at most ~4 Mpx**
     (planning default; reviewable constant) is stored byte-for-byte, no re-encode.
   - *DERIVED*: anything larger is decoded with subsampling and re-encoded once as a bounded
     high-quality display copy — **longest edge ≤ 1600 px** (benchmark 1200–1600 px at
     implementation and keep the smallest size that still looks right in the Library tile
     and the details pane), aspect ratio preserved, no crop, no stretch, JPEG for opaque
     photographic art (quality proposed ~88; PNG when the source has alpha). Lossless WebP
     is **not** assumed: `Bitmap.CompressFormat.WEBP_LOSSY`/`WEBP_LOSSLESS` exist only
     from API 30 and the legacy `WEBP` constant is lossy-only on API 24–29 (Android SDK
     knowledge, not re-fetched; verify at implementation). WebP *decode* is available on
     every supported API level, so lossy WebP output is allowed only if a size/quality
     benchmark justifies it.
   - A **DERIVED copy is not byte-identical to the source image**, and the plan, UI copy
     and technical details never call it the original. The record keeps the **original
     pixel dimensions** and source provenance (entry name / page index / provider ref) so
     presentation math (`ORIGINAL` shape) uses the true native ratio and the original can be
     re-read from the source when it is available.
   - Provider images are stored as downloaded when within the thresholds, else DERIVED.
     User-selected images follow the same rule and are *durable* (§18).
   - A PDF has no embedded cover image, so its first page is *rendered* once into a DERIVED
     image at the same bounded long edge and recorded as `PDF_FIRST_PAGE`, never called
     "original". This is one case among several DERIVED cases, not the only one.
2. **Display shape is presentation only.** Shapes: `ORIGINAL`, `RATIO_2_3`, `RATIO_3_4`,
   `RATIO_4_5`, `RATIO_1_1`, `CUSTOM(w, h)` (proposed custom bounds: integers 1–20 each,
   resulting ratio clamped to 1:3 … 3:1).
3. **Crop to fill** scales uniformly to cover the frame and center-crops the overflow.
   **Fit entire artwork** scales uniformly to fit inside the frame; the remaining area uses a
   neutral theme token (Classic: `muted`), never a brand accent and never a blurred copy in
   Classic. Neither mode stretches or squashes.
4. **Original** shows each cover at its own native ratio. To keep the grid aligned and D-pad
   rows predictable (`LazyVerticalGrid`), every tile reserves one *slot* at the effective
   category ratio for Original (proposed 2:3) and the artwork is fitted inside it
   **bottom-aligned, without fill** — like objects standing on a shelf. Extreme ratios are
   clamped to 1:2 … 2:1 for layout safety. (open) D10.
5. **Generated covers** have no native ratio; they are drawn natively at the effective shape
   (Original → the slot ratio). Crop/Fit do not apply to them.
6. **Details pane / large cover** (proposed): always shows the whole artwork (Fit at its
   Original ratio) so the user can see the full cover regardless of the grid preference.
   (open) D11.
7. **Decode, not store, at display size.** Bitmaps are decoded with `inSampleSize` to the
   rendered pixel size and kept in a bounded memory LRU (reuse `ByteBudgetedLruCache`); the
   stored artwork file is untouched. No grid tile ever decodes a high-resolution source
   page: tiles decode the stored ≤ 1600 px (or ≤ ~1 MiB) file, never the source archive.
   Sized for the 2 GB SM-T580.
8. **Cover-expand transition** and Notes hub rows use the same renderer so a cover never
   changes identity between screens.
9. Accessibility: covers remain decorative (title text carries meaning); a details-pane
   cover may expose "Cover" plus provenance only inside technical details.

## 8. Preference hierarchy

```text
effective(category) = categoryOverride[category] ?: globalDefault
```

- One **global default** `{shape, customW?, customH?, fit}`.
- An optional override per `MediaCategory` (BOOK, COMIC, MANGA, DOCUMENT); "Use default"
  clears it. Favorites is a cross-category view and uses each item's own category.
- Proposed shipped defaults (open D9), following the administrator example: global
  **2:3, Crop to fill**; Books and Comics use the default; **Manga 3:4, Crop to fill**;
  **Documents Original**. Note for D9: many tankōbon covers are close to 2:3, so 3:4 crops
  slightly more of them; the example is kept unless D9 changes it.
- Storage (proposed, verified need): there is no suitable existing store.
  `appearance_preference` has a single typed `themeKey` column; `reader_preference` is the
  reader layering store keyed by item id (`''` = global reader defaults) and must not carry
  Library presentation; no SharedPreferences/DataStore are used. 4.5A.2 therefore adds a
  small Room table in the v6→v7 migration (§23); 4.5A.1 ships with the existing frame
  presentation and no preference table. Theme-independent: switching theme never changes
  cover shape.
- Settings placement: Settings → **Library appearance** (new section next to Theme):
  "Cover shape", "Cover fit", and "Per category" (four rows, each "Use default" or a
  shape/fit choice). All radio-style rows (`Role.RadioButton`), D-pad/gamepad reachable;
  Custom W:H uses two steppers (no IME required on gamepad devices).
- Not in scope: per-Shelf, per-Series, per-item, per-theme overrides.

## 9. Metadata provenance and confidence model

Provenance origins (proposed enum, replacing free-text strings in new code while keeping the
existing `titleOrigin`/`creatorOrigin` string values readable):

```text
USER > EMBEDDED > EXACT_IDENTIFIER_MATCH > HIGH_CONFIDENCE_EXTERNAL_MATCH
     > INFERRED_EXTERNAL_MATCH > FILENAME_INFERENCE > GENERATED/UNKNOWN
```

(matches `METADATA_ENRICHMENT.md` §3 and `ARCHITECTURE.md` §14).

Per field record (proposed): `value`, `origin`, `providerId?` (neutral string id, e.g.
`openlibrary`), `providerRef?` (edition/issue key), `confidence?` (tier), `resolvedAt`.

Confidence tiers (proposed): `VERY_HIGH`, `MEDIUM`, `LOW`. Scoring is rule-based and
explainable (§15); the tier and the winning evidence are persisted so the details pane can
say e.g. "Online match · exact ISBN" without naming mechanics elsewhere
(`features/LIBRARY.md`: do not surface provider mechanics throughout the main UI).

Field-level rules:

- A field with `USER` origin is never changed by any automatic process.
- `EMBEDDED` values are not replaced by external values; external values fill only
  **unresolved** fields (absent, blank, `FILENAME_INFERENCE`, `GENERATED/UNKNOWN`).
- Title is special: a VERY HIGH identifier match may replace a `FILENAME_INFERENCE` title,
  never an `EMBEDDED` or `USER` one.

## 10. Provider-neutral architecture

New packages, per `ARCHITECTURE.md` §3 (proposed): `data.metadata` (providers, coordinator,
cache) and `core.network` (one tiny HTTP helper). Domain types live in `domain.library` or a
new `domain.metadata` only if clarity requires it (AGENTS.md: use cases only where they
help).

```kotlin
// proposed shapes, not final code
enum class MetadataCapability { IDENTIFIER_LOOKUP, SEARCH, COVERS }
data class MetadataQuery(val category: MediaCategory, val identifiers: List<PublicationIdentifier>,
                         val title: String?, val creator: String?, val series: String?,
                         val volume: String?, val issue: String?, val year: Int?, val language: String?)
data class MetadataCandidate(val providerId: String, val providerRef: String,
                             val fields: CandidateFields, val covers: List<CoverCandidate>,
                             val evidence: MatchEvidence)  // evidence filled by the matcher, not the provider
data class CoverCandidate(val providerId: String, val ref: String, val widthPx: Int?, val heightPx: Int?,
                          val editionLabel: String?)       // URL resolution stays inside the provider
interface MetadataProvider {
    val id: String
    val categories: Set<MediaCategory>
    val capabilities: Set<MetadataCapability>
    suspend fun lookup(identifier: PublicationIdentifier): List<MetadataCandidate>
    suspend fun search(query: MetadataQuery): List<MetadataCandidate>
    suspend fun fetchCover(candidate: CoverCandidate): CoverBytes   // bounded size
}
```

- `ComicMetadataProvider` and `MangaMetadataProvider` are **not separate interfaces**; they
  are `MetadataProvider` implementations whose `categories` include COMIC/MANGA and whose
  queries carry `series`/`issue`/`volume`. This keeps one abstraction (ponytail) while giving
  the named extension slots. A provider is registered in one `MetadataProviderRegistry`
  (ordered per category, enabled flag, BYOK credentials if any).
- `MetadataEnrichmentService` (coordinator): builds a `MetadataQuery` from local metadata,
  asks eligible providers in order (identifier lookup first, then search), scores candidates
  with the shared `MetadataMatcher` (§15), applies the decision (§16–17) and persists
  results/provenance. Providers never decide confidence and never write Room.
- `IdentifierDetector` (pure JVM): ISBN-10/13 with checksum, 978/979, hyphen/space
  normalization, `urn:isbn:`, EPUB `opf:scheme`; UPC-A (12 digits) with optional 5-digit
  add-on; ComicInfo `GTIN` (ComicInfo v2.1 schema; verify at implementation).
- `FilenameInference` (pure JVM): extends the accepted filename rules
  (`METADATA_ENRICHMENT.md` §5) with volume/issue tokens (§15).
- `EmbeddedMetadataReader` is **extended**, not replaced (§5).
- UI asks the coordinator/repository for candidates; it never imports a provider class.

## 11. Book provider plan

| Role | Provider | Status |
| --- | --- | --- |
| Primary (default, keyless) | Open Library | **RECOMMENDED DEFAULT CANDIDATE — pending administrator terms approval (D1)** |
| Secondary | Google Books | **RESEARCH / NOT YET APPROVED** for default use; only conceivable as user-BYOK after review (D2). Conflicts with the earlier "Google Books fallback" direction — see §14, §31 |
| Optional BYOK | ISBNdb (paid key), Hardcover (personal beta token) | RESEARCH; later, not Phase 4.5 acceptance scope |

Open Library flow (proposed): ISBN → `/isbn/{isbn}.json` (edition) → work for description /
subjects → cover via the edition's `covers` id (`covers.openlibrary.org/b/id/{id}-L.jpg`,
not the per-IP rate-limited ISBN cover path) → `search.json` with `fields=` limited for
title/author fallback. Identify with a `User-Agent` naming ShelfOS and a project contact
(D4). Respect 1 req/s unidentified, 3 req/s identified; never bulk-download.

Books acceptance needs: identifier detection (EPUB `dc:identifier`, filename), title, author,
publisher, year, description, edition-aware cover candidates (several editions shown with
publisher/year/format), automatic confident cover improvement.

## 12. Manga provider plan

- **Volume-aware matching is mandatory.** A Manga item is a volume; the query always carries
  `series` + `volume` where known.
- **Primary path: exact ISBN** (most commercial tankōbon and licensed translations have
  per-volume ISBNs) via the Book provider (Open Library), which covers many commercial manga
  volumes. Search fallback uses series + volume + creator + language.
- **Local first**: for CBZ/CBR the first page is almost always the actual volume cover, so
  4.5A.1 already gives a correct volume-specific cover offline; online covers only replace it
  under §17.
- **Never apply one generic Series cover to every volume.** A cover candidate is eligible for
  a volume only if it belongs to the exact matched edition/volume (ISBN match or a
  volume-number match on an edition record). A series-level image is never auto-applied to a
  volume and is shown in the manual picker only labelled as a series image.
- Specialist manga providers are an **architecture slot**, not an acceptance dependency:
  MangaDex is rejected (forbids paid services), AniList / MyAnimeList / Kitsu /
  MangaUpdates are series-level and RESEARCH (§14). None is planned for 4.5 unless the
  administrator approves one (D6).

## 13. Comic provider plan

- **Issue-level matching is mandatory**: Series + issue number (+ volume/start year where
  applicable) + UPC/ISBN + creators + publisher. A trade paperback/collected edition is
  matched as a book-like edition (ISBN).
- **Local first**: ComicInfo.xml (expanded in 4.5B: Series, Number, Volume, Year, Month,
  Writer, Penciller, Publisher, Summary, LanguageISO, GTIN, Web), **MetronInfo.xml** where
  present (as evidence, read like ComicInfo; schema to be verified at 4.5B implementation),
  filename/Series/issue inference, and the first page (or declared front cover) as the
  issue cover. For digital comics this is usually the most reliable *local* evidence.
- **Online is required for acceptance.** Local extraction and local artwork are necessary
  but **not sufficient**: Phase 4.5 Comics acceptance (§30) requires a working, **approved**,
  issue-level online metadata path with explicit candidate matching and safe handling of
  variants and ambiguous issues. If no suitable provider is approved and integrated, 4.5E
  and 4.5G stay OPEN and the phase cannot close. Local artwork alone never satisfies online
  Comics enrichment.
- **Preferred online candidate: Metron** (community database, issue-level), using **per-user
  tokens** (each ShelfOS user supplies their own Metron account/API token; ShelfOS never
  ships or shares an account). Basis: Metron's official commercial-use statement of
  2026-10-04 (§14). Status **CONDITIONAL**: approval still requires the prerequisites in D5
  (current `metron.cloud` service/API terms reviewed manually or clarified by Metron, and
  the ShareAlike/attribution questions in D19 answered by the owner/counsel).
  `metron.live` is unrelated to Metron comic metadata (per administrator brief, not
  verified on the page; the comics project is Metron at `metron.cloud`, the only domain
  the official statement cites).
- **Other candidates** (none approved): **Comic Vine** — "strictly for non-commercial use
  only"; rejected for default; BYOK only after legal review. **GCD** — data CC BY-SA 4.0,
  minimal public API, cover-scan rights unclear; RESEARCH. **Open Library** — covers
  ISBN-bearing collected editions only (useful for TPBs, not single issues).
- **Cover rights.** Metron states publisher cover images are not covered by its CC BY-SA
  license (§14). ShelfOS therefore prefers the **local publication artwork** for Comic
  covers (the issue cover inside the user's own CBZ/CBR); online Comic covers are optional,
  never default-replace local art (D7), and any use of Metron-hosted images needs its own
  rights decision (D19).
- Metadata licensing is not cover-art rights, and neither CC BY-SA metadata terms nor a
  provider's data license resolves API access terms or image rights by itself.
- **Variants and ambiguity**: variant covers, reprints, annuals, #0/#½, volume restarts and
  multiple near-equal candidates never auto-apply; they demote to the confirmation/picker
  path (§15, §20).

## 14. Provider / legal / terms checklist

Accessed 2026-10-15. "Secondhand" means the official page could not be read (blocked or 403)
and the figure comes from a client library or search summary — it must be re-verified
before implementation. The Metron row is **not** secondhand: it is read from Metron's own
commercial-use statement (fetched 2026-10-15; quoted phrases are short excerpts).

| Provider | Coverage / granularity | Covers | Auth / key | Documented limits | Caching / storage | Attribution | Commercial / OSS fit | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Open Library** ([API](https://openlibrary.org/developers/api), [Covers](https://openlibrary.org/dev/docs/api/covers), [Licensing](https://openlibrary.org/developers/licensing), [Search](https://openlibrary.org/dev/docs/api/search)) | Books; editions/works; many commercial manga volumes; some comic TPBs. Edition-level | Yes, S/M/L by cover id/OLID/ISBN | None. Identify via `User-Agent: App (contact)`; optional registration form | 1 req/s unidentified, 3 req/s identified; covers by ISBN/OCLC/LCCN: 100 per IP per 5 min (403 beyond); cover-id/OLID not limited | No explicit cache terms; "not for bulk download"; asks web pages to use `covers.openlibrary.org` src URLs | "Courtesy link back … appreciated" | APIs "not intended to serve as … high-traffic commercial infrastructure"; prioritizes open-source/library projects. IA "does not assert any new copyright"; existing rights may apply | **Recommended default candidate — NOT YET APPROVED** (D1). Questions: on-device caching vs src-URL guidance; per-user library backfill vs "bulk" |
| **Google Books** ([Terms](https://developers.google.com/books/terms), [APIs ToS](https://developers.google.com/terms), [Using API](https://developers.google.com/books/docs/v1/using), [Branding](https://developers.google.com/books/branding)) | Books, editions; some manga | `imageLinks` thumbnails on books.google.com | Docs: public requests "must be accompanied by an identifier" (API key or OAuth). ToS §4(b)(1): "Developer credentials may not be embedded in open source projects" | Quota per key (no figure stated) | APIs ToS §5(e): no permanent copies; no cache "longer than permitted by the cache header" | "Powered by Google" adjacent to results; prominent link to Google Books page; "must not alter results" | Books terms: "You may not charge users any fee for the use of your application" (conflict risk with ShelfOS Plus); cropping may be "altering" | **RESEARCH / NOT YET APPROVED.** Not usable as keyless default (key required, no embedding). BYOK-only after legal review (D2). Offline cover caching conflicts with no-permanent-copies |
| **Metron** ([official commercial-use statement, 2026-10-04](https://metron-project.github.io/blog/commercial-api-use); service `metron.cloud`, [repo](https://github.com/metron-project/metron)) | Comics, issue-level; series, creators, publishers | Not under the CC license: publisher/creator copyright, hosted under "a fair-use rationale"; ShelfOS prefers local artwork | Per-user account or API token; tokens recommended over account passwords; "Basic Auth will be retired eventually" (no date given) | 20 requests/minute and 5,000/day per account; a donation can raise a matched account's daily limit up to 25,000; honor `X-RateLimit-*` / `Retry-After` | Caching recommended: store fetched data (released-issue data "rarely changes") | CC BY-SA 4.0 attribution wherever data is shown (one about/footer credit suffices, e.g. "Comic data from Metron, licensed under CC BY-SA 4.0"); ShareAlike on adapted Metron data that is published or distributed (the author's reading, not legal advice) | CC BY-SA 4.0 permits commercial use; each user of a distributed app signs in with their own account/token; a shared built-in account is discouraged. **Not resolved by the statement**: current `metron.cloud` service/API terms; ShareAlike effect on ShelfOS cached data, exports and backups; paid-theme (Plus) interaction; image rights | **CONDITIONAL: preferred candidate, NOT APPROVED** until the D5 prerequisites close. Per-user token (BYOK) only |
| **Comic Vine** ([API](https://comicvine.gamespot.com/api/)) | Comics, issue-level | Yes | Per-user API key (login) | 200 req/resource/hour | Fandom ToS: "Do not edit, manipulate or reproduce on any other medium" | Link back on every page using data | "Strictly for non-commercial use only" | **Rejected for default.** BYOK only if legal review accepts user-side non-commercial use (D5) |
| **Grand Comics Database** ([API root](https://www.comics.org/api/)) | Comics, issue-level, deep international data | Scans exist; rights unclear | None stated | None stated | CC BY-SA 4.0 data (site footer, secondhand) | BY-SA attribution | Share-alike on redistributed data; cover rights not licensed | **RESEARCH.** API surface minimal; not planned for 4.5 |
| **MangaDex** ([API docs](https://api.mangadex.org/docs/)) | Manga series/chapters (scanlation-centric) | Series covers | None | Separate page | — | Must credit MangaDex and scanlation groups | "CANNOT run ads or paid services on your website and/or apps" | **Rejected**: conflicts with ShelfOS Plus; scanlation context; series-level only |
| **AniList** ([terms](https://docs.anilist.co/guide/terms-of-use), 403) | Manga/anime series-level | Series images | OAuth for user data; public queries keyless | Secondhand rate limit | Unknown | Unknown | Secondhand: free for non-commercial; commercial license above a revenue threshold | **RESEARCH / NOT YET APPROVED**; series-level, poor volume-cover fit |
| **MyAnimeList** ([ToU](https://myanimelist.net/membership/terms_of_use)) | Series-level | Series images | Registered client ID | Not reviewed | Not reviewed | Not reviewed | API license not reviewed | **RESEARCH**; client ID in OSS APK is a credential issue |
| **Kitsu** | Series-level | Series images | — | Not found | Not found | Not found | Terms not found | **RESEARCH** |
| **MangaUpdates** | Series-level, release data | Series images | Secondhand: keyless | Not found | Not found | Not found | Terms not found | **RESEARCH** |
| **ISBNdb** ([docs](https://isbndb.com/isbndb-api-documentation-v2), 403) | Books, ISBN-level | Yes | Paid API key (`x-api-key`) | Secondhand: 1–5 req/s by plan | Not reviewed | Not reviewed | Paid; key cannot ship | **Optional BYOK later**; not 4.5 scope |
| **Hardcover** ([getting started](https://docs.hardcover.app/api/getting-started)) | Books | Yes | Personal account token ("not meant to be shared") | Not stated | Not reviewed | Not reviewed | API "in beta" | **RESEARCH**; personal-token BYOK at most, later |

Cross-cutting conclusions:

1. **Keyed providers can only ever be BYOK** (user enters their own key/account; stored
   locally; never logged; never in backups/exports). No keyed provider can be a zero-setup
   default (Google's own ToS forbids credentials in open-source projects).
2. **Metadata rights ≠ cover-art rights.** Cover art belongs to publishers/artists. Caching a
   cover on the user's own device to display the user's own library is the intended use;
   ShelfOS never redistributes provider covers (not in Portable Knowledge exports — already
   excluded — nor future backups without a separate decision).
3. **No hotlinking.** ShelfOS downloads a selected cover once and renders it locally; this
   is load-reducing but differs from Open Library's web `src` guidance — confirm (D1).
4. **Attribution** is shown in the details pane technical section and in Settings → About
   (proposed); Google-style adjacent branding would be required if Google Books is ever
   enabled (D2).
5. Terms are re-checked at each provider slice's implementation start; a terms change after
   approval disables the provider (registry flag) without touching cached user data.
6. **Open licensing question (no legal conclusion drawn here).** Metron data is CC BY-SA 4.0.
   Metron's author reads ShareAlike as applying to adapted Metron *data* that is published
   or distributed, not to the whole application, and says internal use does not trigger it
   (his reading, "not legal advice"). Whether ShelfOS's own cache, Portable Knowledge
   exports, future backups, the open-source repository and the paid (Plus) theme model
   change that analysis is unresolved and is owner/counsel work (D19). ShelfOS currently
   excludes provider data and covers from exports; that exclusion stays until D19 closes.
7. CC BY-SA metadata licensing does **not** by itself resolve API access terms or image
   rights; each needs its own approval row.

## 15. Matching rules

Evidence hierarchy (strongest first): exact ISBN/identifier → exact UPC (comics) → exact
normalized title → creator → Series + issue/volume → publisher → year → language →
filename/folder inference.

Tiering (proposed starting rules; tuned with the fixture corpus in §27):

- **VERY_HIGH**: exact ISBN/UPC/GTIN equality on the candidate edition **and** no
  contradiction (title normalized-similar, creator compatible if both known, volume/issue
  equal if both known).
- **MEDIUM**: no identifier, but normalized title equal + creator match (+ for Comics/Manga:
  series + issue/volume equal); or identifier match with a soft contradiction.
- **LOW**: anything else, including filename-only evidence, title-only matches, or multiple
  near-equal candidates (ambiguity always demotes to at most MEDIUM; two VERY_HIGH
  candidates that disagree demote both).
- **Hard rejections**: different volume/issue number; different category class (a Document
  never auto-matches a Book record); language contradiction when both known (MEDIUM at
  most).

Normalization: Unicode NFKC, case-fold, strip punctuation/articles per script-neutral rules
(no translation, no transliteration of publication text), collapse whitespace; volume/issue
tokens parsed from `Vol`, `Vol.`, `Volume`, `v03`, `#12`, `Issue`, `No.`, `巻`/`第N巻`,
`Tome`, `Tomo`, `Band`, trailing zero-padded numbers; years `(1965)`.

Category rules: Books by ISBN/title/creator; Manga by ISBN, else series + volume; Comics by
UPC/ISBN, else series + issue (+ start year/volume); Documents only by an explicit
identifier the user or file supplies (never by title search automatically).

**A wrong match is worse than no match**: ties, missing volume/issue for sequential art, or
any contradiction never auto-apply.

## 16. User override rules

- Any field edited by the user becomes `USER` and is never changed automatically.
- A user-chosen cover (device or online picker) is `USER` and is never replaced by
  enrichment, refresh, rescan or re-import of the same item.
- "Use this match" in candidate confirmation applies the candidate's fields to non-`USER`
  fields and records origin `HIGH_CONFIDENCE_EXTERNAL_MATCH` with `confirmedByUser`; it does
  not convert those fields to `USER` (they remain refreshable), except the cover, which the
  user explicitly picked (open D8).
- "Keep file metadata" records a rejection of that candidate so it is not re-proposed.
- "Revert to file/automatic" per field (proposed, 4.5F) clears `USER` and re-resolves.

## 17. Cover precedence

```text
USER-SELECTED  >  CONFIDENT HIGH-QUALITY ONLINE  >  USABLE EMBEDDED  >  GENERATED/FALLBACK
```

- `USABLE EMBEDDED` includes an EPUB/CBZ/CBR embedded cover; a PDF `FIRST_PAGE` render sits
  at the bottom of this tier (it is local and usable, but weaker evidence than an embedded
  cover image).
- A user cover always wins; enrichment never overwrites it.
- An online cover replaces an embedded/first-page cover only when the match is VERY_HIGH
  **and** the image is "high quality": at least the embedded cover's pixel area or a
  provider-large size (proposed ≥ 600 px long edge), correct orientation (portrait unless
  the local art is landscape), and not a provider placeholder ("no cover" images are
  detected by size/hash).
- For Comics/Manga the local first page is usually the exact issue/volume cover; online
  replacement additionally requires the exact issue/volume match and is **off for CBZ/CBR
  with an embedded cover by default** unless the administrator chooses otherwise (D7).
- Ambiguous remote artwork never replaces good local artwork. Previous artwork is retained
  until the new artwork is fully downloaded and decoded; a failed fetch changes nothing.
- Cached selected artwork remains usable offline where terms allow (§18).

Reconciliation: `ARCHITECTURE.md` §15 and `design/VISUAL_IDENTITY.md` §6 previously listed
EMBEDDED above ONLINE. They were updated to this order, which `METADATA_ENRICHMENT.md` §3 and
`PRODUCT.md` already define.

## 18. Caching and offline design

- All covers live in app-private storage (proposed `filesDir/covers/<libraryItemId>/`, one
  file per artwork *kind*, e.g. `epub_embedded.<ext>`, `pdf_first_page.jpg`,
  `user_selected.<ext>`), plus one Room row **per item and kind** recording stored
  dimensions, original dimensions and ORIGINAL/DERIVED (§23). `filesDir`, not `cacheDir`:
  the system must not evict the Library's identity.
- **Bounded storage policy (proposed, D12/D20; supersedes any byte-for-byte rule).** Each
  stored file follows §7 rule 1: ORIGINAL only when small and supported (~1 MiB / ~4 Mpx),
  otherwise a bounded DERIVED copy (longest edge ≤ 1600 px). Multi-megabyte comic pages are
  never duplicated. A DERIVED copy is not byte-identical to the source image; the original
  artwork stays inside the source publication.
- **Local-cover storage budget (proposed, D20).** An explicit app-wide budget for
  *regenerable* artwork (artwork extracted from a source file or fetched online that can be
  produced again), proposed default 512 MiB, tracked as a sum of `storedBytes` in Room. When
  over budget, only regenerable rows whose source is currently available may be evicted,
  least-recently-displayed first; eviction deletes the file and the row's `READY` state, so
  the cover is re-extracted on demand. **Never silently evicted:** `USER_SELECTED` covers
  (durable; counted separately and never part of eviction), and any artwork that is the only
  usable cached art for an item whose source is unavailable. Cover-storage usage
  (regenerable vs user-selected) is shown under Settings → Storage when that supporting
  surface exists; until then it is visible in the technical details only.
- **Regenerable vs durable.** Grid-size bitmaps in the memory LRU and any future small
  thumbnails are regenerable caches. Extracted/derived artwork files are regenerable from
  the source. User-selected covers are durable user data (they cannot be regenerated).
- Regenerate only when necessary and safe: a missing/corrupt file with an available source,
  an extractor-version bump, a source-revision change, or an explicit Refresh cover. A
  source that becomes unavailable never erases the cached artwork.
- Resolved metadata is persisted in Room; screens read Room only. **No provider call on
  screen open**; network happens only in the enrichment worker or an explicit user action.
- Offline: the Library is visually complete from cached state; provider failure never blanks
  metadata or covers.
- Unavailable sources keep their cover and metadata (`features/LIBRARY.md` §14).
- Removal: covers are **not** knowledge and never outlive their item (unlike orphan
  annotations, ADR-0025). Item removal under both knowledge policies deletes the item's
  `publication_cover` rows with the item (foreign-key cascade plus explicit delete in the
  removal transaction, §23.4) and deletes the item's cover directory after commit; a startup
  sweep removes cover directories, files and `.tmp` leftovers with no matching item/row
  (like `unusedPrivateCopies`). Orphan annotation snapshots fall back to the generated
  cover.
- Invalidation: covers are re-derived only when the item's cover row is missing/invalid, the
  extractor version or source revision changed, or the user asks (Refresh cover, §23.3); online data has `resolvedAt` and a refresh-eligibility date (§19), never
  automatic periodic refetch.
- Provider-specific cache restrictions (D1/D2) are enforced by the registry: a provider
  flagged "no persistent cache" can only populate the manual picker for the session.
- Backups/Portable Knowledge: unchanged; no cover binaries or provider data.

## 19. Background enrichment lifecycle

States per item (proposed): `PENDING_LOCAL` → `LOCAL_DONE` → `PENDING_ONLINE` →
`MATCHED(tier)` | `NEEDS_REVIEW` | `NO_MATCH` | `FAILED(retryable)` | `SKIPPED(disabled/
offline/category)`.

1. Import commits the item exactly as today (no added latency).
2. Local pass (4.5A.1 covers, 4.5B fields): runs on `AppContainer.backgroundScope`, bounded
   concurrency 1–2, per-file failure isolation, idempotent. Its durable state is *derivable*
   ("no cover row" / "no local-analysis marker"), so process death simply resumes on next
   launch — no job framework needed. Existing libraries are backfilled the same way.
3. Online pass (4.5C+): only when online enrichment is enabled and the network is available;
   ordered per provider rate limits, newest imports first, persisted `nextEligibleAt` with
   exponential backoff; a per-provider circuit breaker on 403/429/5xx.

**WorkManager decision (open, D13):** WorkManager is *not* a dependency today. The proposed
design needs none (foreground-process background work + derivable/persisted state, resumed
at launch). If the administrator wants enrichment to continue while the app is closed or to
use constraint scheduling, adding `androidx.work` requires the AGENTS.md dependency review
(Apache-2.0, AndroidX-maintained, binary size) and an ADR. This plan does not choose it.

HTTP client decision (proposed, D14): platform `HttpURLConnection` + `org.json` behind one
`core.network` helper (timeouts, size caps, gzip, `User-Agent`, TLS only, no cookies). No
OkHttp/Retrofit/Ktor.

## 20. Manual candidate UX

Edit Publication (today: `PublicationEditor` dialog with title/creator/category) grows into
an editor with a **Cover** section and a **Metadata** section (4.5F; layout must work on
compact, expanded, short-landscape and gamepad):

- **Cover → Choose from device**: SAF image picker (`ACTION_OPEN_DOCUMENT`, `image/*`);
  copied into the cover store; `USER` cover. No persistent URI grant kept.
- **Cover → Find cover online**: provider-neutral candidate grid (multiple candidates;
  each shows artwork plus edition-distinguishing text: publisher, year, format/edition,
  volume/issue, pixel size, and a neutral source label). Picking one stores it as the `USER`
  cover. Empty/offline/disabled states are explicit.
- **Cover → Use file cover / Use generated cover**: revert options.
- **Metadata → Refresh metadata**: re-runs the online pass for this item now; never touches
  `USER` fields; ambiguous results go to confirmation.
- **Candidate confirmation** ("We think this is…"): Use this match / Choose another / Keep
  file metadata (`METADATA_ENRICHMENT.md` §12). Reachable from details and from a quiet
  Library indicator; never a modal interruption on import or while reading.
- Expanded editable fields (4.5B minimal, 4.5F complete): subtitle, description,
  publisher, year, language, Series, volume, issue, identifiers (under technical details).

## 21. Privacy and network rules

- Never upload publication files, page images, or text content. Queries contain only:
  identifiers, title, creator, series, volume/issue, year, language.
- Requests go directly from the device to the provider; the provider sees the device IP and
  the query. Disclose this in Settings and the privacy disclosure (Phase 9 data-safety).
- **User control**: Settings → "Online metadata and covers" toggle (+ "Wi-Fi only",
  proposed). Default (open D15): ON with a one-time, non-blocking disclosure, consistent
  with "optional means never required, not opt-in" (`METADATA_ENRICHMENT.md` §1); or OFF
  until confirmed.
- Adding `INTERNET` (and `ACCESS_NETWORK_STATE` if used) means deleting the manifest
  `tools:node="remove"` lines — a deliberate, reviewed change in 4.5C, documented in
  `DEPENDENCIES.md`/`ARCHITECTURE.md`. No analytics, crash reporting or telemetry rides on it.
- BYOK secrets: stored in app-private storage (proposed: Android Keystore-wrapped), never
  in Room exports, logs, backups or crash text. `allowBackup=false` already.
- No provider sees user notes, Shelves, reading progress or file names unless the file name
  is the only title evidence (then only the parsed title tokens are sent).
- Offline path: everything in 4.5A.1/4.5A.2/4.5B works with no network permission at all.

## 22. Failure and rate-limit behavior

| Failure | Behavior |
| --- | --- |
| No network / airplane mode | Online pass skipped; state `SKIPPED(offline)`; no error UI on Library |
| Timeout / 5xx | Retry with backoff (proposed 1 min → 6 h cap, max 5 attempts per refresh) |
| 403 / 429 / documented limit | Provider circuit opens for the advertised or default window; queue pauses; no hammering |
| Malformed JSON / unexpected schema | Candidate discarded; logged locally without user data; item unchanged |
| Oversized / undecodable image | Rejected; prior cover kept |
| Provider disabled by terms change | Registry disables it; cached data kept unless terms require deletion |
| Wrong match reported by user | "Not this publication" rejects the candidate; reverts non-USER fields it supplied |
| Manual search failure | Inline, localized message with Retry; never blocks editing |

Metadata/cover failures are never `PublicationProblem`s and never block opening the item.

## 23. Proposed Room/data changes — PLANNING ONLY

All shapes are **(proposed)/(open)**; each lands only with its slice and its own review.
Version numbering follows slice order and is not a pre-reservation: **v6 = 4.5A.1** (local
cover artwork), **v7 = 4.5A.2** (cover presentation preferences), **v8 = the next version
if 4.5B (or 4C-era provider work) persists new tables** — not a mandatory speculative
migration. Provider-specific columns (provider id/ref/confidence on covers) are added only
by the slice that first writes them (4.5C+), and only if justified; they are not part of v6.

### 23.1 v5 → v6 (4.5A.1): `publication_cover`

Options considered:

| Option | Shape | Verdict |
| --- | --- | --- |
| A. One active-cover row per item (`PRIMARY KEY (libraryItemId)`, `kind` column) | Overwrites the previous evidence whenever a better kind arrives | **Rejected.** "Use file cover" after a user/online choice would need re-extraction; a failed extractor erases a good cover; evidence is lost |
| B. One row per item **and artwork kind** (`PRIMARY KEY (libraryItemId, kind)`), active cover chosen at read time by precedence | Keeps every meaningful kind; precedence is a pure function (§17) | **Recommended** |
| C. B plus a separate `active_cover` pointer table | Adds pointer-drift risk for no gain | Rejected; precedence is deterministic |

Recommended (Option B) shape:

```sql
CREATE TABLE publication_cover (
  libraryItemId TEXT NOT NULL,
  kind TEXT NOT NULL,              -- see kinds below
  status TEXT NOT NULL,            -- READY | FAILED_PERMANENT | FAILED_TEMPORARY (section 23.3)
  failureReason TEXT,              -- reason enum, null when READY
  fileName TEXT,                   -- relative to filesDir/covers/<libraryItemId>/; null unless READY
  mimeType TEXT,
  representation TEXT,             -- ORIGINAL | DERIVED (null unless READY)
  sourceWidthPx INTEGER, sourceHeightPx INTEGER,   -- native pixels of the source artwork
  storedWidthPx INTEGER, storedHeightPx INTEGER,   -- pixels of the stored file
  storedBytes INTEGER,
  sourceRef TEXT,                  -- provenance: archive entry name / page index / "page 1" / device image / (4.5C+) provider ref
  sourceRevision TEXT,             -- revision the extraction was done against (see 23.2)
  extractorVersion INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  nextEligibleAt INTEGER,          -- retry gate for FAILED_TEMPORARY
  updatedAt INTEGER NOT NULL,
  PRIMARY KEY (libraryItemId, kind),
  FOREIGN KEY (libraryItemId) REFERENCES library_item(id) ON DELETE CASCADE
);
```

Kinds, grounded in what the code can actually produce or will need (the current code
extracts none of them, §6):

| Kind | Produced by | When |
| --- | --- | --- |
| `EPUB_COVER` | OPF `meta name="cover"` (EPUB2) / `properties="cover-image"` (EPUB3) image entry | 4.5A.1 |
| `COMICINFO_FRONT_COVER` | CBZ/CBR page declared `Type="FrontCover"` in ComicInfo `Pages` | 4.5A.1 |
| `ARCHIVE_FIRST_PAGE` | first page in `ArchivePolicy.pages` / `RarContainer` order | 4.5A.1 |
| `PDF_FIRST_PAGE` | bounded first-page render through the existing PDF open path | 4.5A.1 (always DERIVED) |
| `USER_SELECTED` | Choose from device, or a picked online candidate (origin in `sourceRef`) | 4.5F |
| `ONLINE` | confident high-quality provider cover (§17) | 4.5C+ |

Read-time precedence for the displayed cover (pure function, never an overwrite):
`USER_SELECTED` > `ONLINE` (only if it passes the §17 gate) > `COMICINFO_FRONT_COVER` >
`EPUB_COVER` > `ARCHIVE_FIRST_PAGE` > `PDF_FIRST_PAGE` > generated. Only `READY` rows
participate. `Use file cover` (4.5F) simply ignores the `USER_SELECTED`/`ONLINE` rows;
nothing is re-extracted. 4.5A.1 writes only the four local kinds; `USER_SELECTED` and
`ONLINE` rows and any provider columns arrive with their own slices.

The two declared FrontCover/first-page kinds are separate rows because ComicInfo may be
wrong or later corrected; keeping both costs one small row, not a second file when the
declared page is page 1 (the second row can reference the same stored file only if the
implementation chooses; default is one row = one file).

**Decisions needed (D21):** accept Option B and the kind list; whether FK `ON DELETE
CASCADE` needs the explicit delete as well (recommended: yes, matching `reading_state`);
whether `COMICINFO_FRONT_COVER` and `ARCHIVE_FIRST_PAGE` collapse to one `ARCHIVE_COVER`
kind with the page recorded in `sourceRef` (simpler; loses the ability to prefer the
declared cover over the heuristic after correction).

### 23.2 Source revision and availability

`library_item` stores `byteSize` but no modification time. `sourceRevision` is proposed as
a best-effort string (`"<byteSize>:<lastModified or ''>"` read when the Source is
available). A changed revision on an available source re-triggers extraction of the local
kinds; an unavailable source never changes anything (§18: cached artwork is kept) and a
`FAILED_TEMPORARY(SOURCE_UNAVAILABLE)` row is retried when the Source reconnects or the
user taps Refresh cover. CBR source hashing remains a known limitation carried from Phase 3.

### 23.3 Extraction status model (draft — brief truncated; pending administrator confirmation of the remaining items)

Permanent and temporary failures must never both collapse into one permanent `NONE`.

- **PERMANENT** (`FAILED_PERMANENT`; no retry unless the extractor version or the source
  revision changes, or the user taps Refresh cover): `NO_SUITABLE_ARTWORK` (the publication
  has no cover/page that qualifies), `UNSUPPORTED_REPRESENTATION` (the cover exists but is
  in a form this device/extractor cannot use, e.g. a format `BitmapFactory` cannot decode on
  this API level). Note: device-dependent unsupported formats should also be retried when
  the OS level changes (proposed, open).
- **TEMPORARY / RETRYABLE** (`FAILED_TEMPORARY`; **ShelfOS proposal, because the review text
  listing them was truncated**): `SOURCE_UNAVAILABLE` (source missing or permission lost),
  `IO_ERROR`, `DECODE_OUT_OF_MEMORY` (decode failure under low memory), `INTERRUPTED`
  (process death/crash mid-extraction, detected at startup), `STORAGE_FULL`,
  `TIMEOUT`, `EXTRACTOR_CRASH`. Coroutine cancellation writes nothing (the row stays as it
  was; an absent row is simply pending).
- Retry/backoff (proposed): `nextEligibleAt` = 1 min, then ×4 per attempt, capped at 6 h,
  at most 5 automatic attempts per trigger; afterwards the row waits for the next trigger.
  `SOURCE_UNAVAILABLE` is not time-backed-off; it retries when the Source becomes available.
- Re-extraction triggers: **extractor version bump** (`extractorVersion` lower than the
  current constant), **source revision change**, and **user "Refresh cover"** (4.5F; also
  retries `FAILED_PERMANENT`). A temporary failure never writes `FAILED_PERMANENT` and never
  deletes an existing `READY` artwork of another kind or of the same kind (the old file is
  replaced only after the new one is fully written, §23.5).
- A `FAILED_*` row means "generated cover shown", never a `PublicationProblem`, and never
  blocks opening the item (§22).

### 23.4 Deletion lifecycle and the removal race

Covers differ from annotations (ADR-0025 keeps orphan annotations on purpose as user
knowledge); a cover is derived/owned state and must never outlive its item as a dangling row.
Verified facts: `reading_state` and the legacy `bookmark` use `ON DELETE CASCADE` foreign
keys to `library_item` (`LibraryDao.kt`, `ShelfDatabase.kt`), and `removeDeletingKnowledge` /
`removeKeepingKnowledge` still delete `reading_state` explicitly in one `@Transaction`;
`annotation` has no FK by design.

Recommended: **FK `ON DELETE CASCADE` plus an explicit delete in both removal transactions**
(cheap defense in depth, same style as `reading_state`). The composite PK
`(libraryItemId, kind)` has `libraryItemId` as its leading column, which serves as the FK
child index Room expects (confirm no Room warning at implementation; add `Index` otherwise).
Background extraction cannot create a dangling row after removal: the row insert is made in
one transaction that is guarded by the item still existing (`INSERT … SELECT … WHERE EXISTS
(SELECT 1 FROM library_item WHERE id = :id)`), and the FK additionally rejects a late insert;
on rejection the writer deletes its just-written file. Removal also cancels the item's
extraction job. Alternative considered: no FK, explicit deletes only (like `annotation`) —
rejected, because it re-creates the orphan class of bug the guarded design avoids.

Disk lifecycle: removal deletes `covers/<id>/` after the transaction commits; a startup
reconciliation (a) deletes directories with no `library_item`, (b) deletes files with no
`READY` row and any `.tmp`, (c) re-queues rows whose file is missing (regenerable kinds) or
marks a missing `USER_SELECTED` file as unavailable without deleting its row.

### 23.5 Restart-safe extraction

Write the artwork to `covers/<id>/<kind>.<ext>.tmp`, `fsync`/close, atomically rename to the
final name, then write/replace the row last in the guarded transaction. **No `IN_PROGRESS`
status is needed**: state is derivable (absent row = pending; `.tmp` or unreferenced file =
interrupted, cleaned by the startup reconciliation). A replacement keeps the old file and
row until the new file is fully written. (Alternative: persist `IN_PROGRESS` and reconcile
at startup — rejected as extra state with no extra safety.)

### 23.6 v6 → v7 (4.5A.2): `cover_presentation_preference`

```sql
CREATE TABLE cover_presentation_preference (
  scope TEXT NOT NULL PRIMARY KEY,   -- GLOBAL | BOOK | COMIC | MANGA | DOCUMENT
  shape TEXT NOT NULL, customWidth INTEGER, customHeight INTEGER, fit TEXT NOT NULL
);
```

Independent of `publication_cover`; shipping 4.5A.1 without it leaves the existing frame
presentation unchanged.

### 23.7 Metadata/enrichment tables (4.5B and later) — next version, not pre-reserved

Proposed contents, in whichever version number is next when 4.5B lands (v8 if 4.5A.1 and
4.5A.2 are merged first), additive only:

- `publication_metadata(libraryItemId PK, subtitle, description, publisher,
  publishedDate, language, seriesName, volume, issue, identifiersJson, provenanceJson,
  localAnalysisVersion INTEGER, updatedAt)`. `title`/`creator` and their origin columns
  stay on `library_item` (hot paths, existing UI); `provenanceJson` holds the per-field
  record from §9 for the new fields.
- `enrichment_state(libraryItemId PK, status, tier, providerId, providerRef, attempts,
  lastAttemptAt, nextEligibleAt, lastErrorKind)`.
- `metadata_candidate(id PK, libraryItemId, providerId, providerRef, tier, payloadJson,
  createdAt, rejected INTEGER)` — only for persisted MEDIUM suggestions; pruned on
  apply/reject/remove. (Could be folded into `enrichment_state.payloadJson` if only one
  pending suggestion per item is shown — D17.)

These tables follow the same lifecycle rule as covers (FK cascade or guarded transactional
write; removed with the item). No change to `annotation`, `annotation_source_snapshot`,
`reading_state`, `reader_preference` or legacy `bookmark`.

## 24. Migration considerations

- Hand-written `Migration(5, 6)` (4.5A.1), `Migration(6, 7)` (4.5A.2) and a later
  `Migration(7, 8)` only if the next slice needs it — additive `CREATE TABLE` only, exported
  schema JSON committed, no destructive fallback — same style as `MIGRATION_4_5`. Each
  migration ships in its own PR with its own migration test.
- The legacy `bookmark` table stays untouched (its removal is a separate, dedicated
  maintenance migration, not Phase 4.5).
- Instrumented migration tests with the repository's raw-SQLite pattern: create the previous
  version with representative rows (items, reading, preferences, annotations, snapshot,
  legacy bookmarks), migrate, assert every pre-existing row and column is byte-identical
  and the new tables exist and are empty; for v6 also assert the FK cascade (delete an item,
  its cover rows vanish).
- Existing libraries receive covers through the local backfill (§19), not in the migration.
- Removal paths (`LibraryDao.removeDeletingKnowledge` / `removeKeepingKnowledge`) gain the
  new tables in the same transaction; cover files are deleted after commit.
- Downgrade is unsupported (consistent with existing practice).

## 25. Localization and accessibility

- Every new string in `values`, `values-es`, `values-pt-rBR` (EN/ES/PT-BR parity), plurals
  and locale-aware numbers/dates/sizes (`docs/ARCHITECTURE.md` "Localization architecture").
- **Never translate** titles, creators, descriptions, Series names, publisher names or any
  provider/embedded metadata. Interface language does not influence provider queries except
  as an optional language *hint* the user controls (proposed: publication language from
  file first, never the UI locale silently).
- Shape names ("2:3") are formatted, not translated; "Original", "Custom", "Crop to fill",
  "Fit entire artwork" are localized.
- TalkBack: settings rows announce state; candidate grid items announce title, edition
  details and source; covers stay decorative in grids. Physical TalkBack remains an evidence
  gap unless the owner can test it (carry Phase 4's caveat).
- Contrast of the Fit background token checked in Classic/Dark.

## 26. Keyboard, controller and adaptive layout

- All new controls use `shelfAction`/semantic input (`core/input`), reachable by D-pad and
  gamepad; confirm/back follow ADR-0023 Back semantics; no screen-specific key maps.
- Changing cover shape must preserve grid focus and scroll position (selected item stays
  focused; `LazyGridState` restored).
- Adaptive: grid min cell width stays 112/136 dp; cover slot height derives from the
  effective ratio; detail pane cover keeps the existing "cover yields height first" rule.
  Verify compact portrait, short landscape (RP5), expanded tablet (SM-T580 landscape),
  fold/unfold and rotation with process recreation.
- Candidate grid: adaptive columns, focus starts on the first candidate, Back closes.

## 27. Test strategy

- **JVM unit tests (bulk of coverage):** crop/fit rect math (no stretch invariant: x/y scale
  equal); preference resolution; Original slot clamp; EPUB cover href resolution
  (EPUB2 `meta name="cover"`, EPUB3 `properties="cover-image"`, relative paths, unsafe
  paths rejected via `ArchivePolicy.safeName`); first-page selection order consistent with
  `ArchivePolicy.pages`/`RarContainer`; ISBN-10/13/UPC checksum and normalization;
  filename volume/issue parser corpus (Latin + CJK tokens); cover storage policy (ORIGINAL
  vs DERIVED thresholds, original dimensions recorded, never-upscale, never duplicating a
  multi-megabyte page); extraction status transitions (permanent vs temporary, backoff,
  no permanent state from a temporary failure); cover precedence over per-kind rows; matcher tiers incl. ambiguity
  and contradiction cases; precedence resolver (USER never replaced); provider JSON parsing
  from **checked-in fixture files**; rate limiter/backoff with a virtual clock.
- **Fake providers** implement `MetadataProvider` for coordinator tests (success, timeout,
  429, malformed, two conflicting VERY_HIGH candidates, series-only cover).
- **No live network in automated tests.** An optional, manually-invoked contract check
  against real providers may exist outside the CI task graph, clearly isolated.
- **Instrumented:** each slice's own migration (v5→v6 in 4.5A.1, v6→v7 in 4.5A.2, any later one in its slice); cover extraction on the sample corpus (EPUB2,
  EPUB3, CBZ, CBR RAR4/RAR5, protected/damaged PDF, huge cover image); Library rendering in
  each shape/fit; settings persistence across process death; focus restoration.
- **Wrong-match corpus**: a fixture set of near-miss publications (same title different
  author, omnibus vs volume, variant covers, Vol 1 vs Vol 10) asserting no auto-apply.

## 28. Physical UAT strategy

Per the project's phase-closure policy, physical/manual UAT is accumulated and run once in
**4.5G**, not per slice:

- **Samsung Galaxy Tab A SM-T580** (Android 8.1 / API 27, 2 GB RAM, **no ADB**): owner
  installs the 4.5G APK over the local network (LAN download/sideload), records the APK
  SHA-256, and runs the written checklist: cover extraction on a real library, all shapes ×
  fits, per-category overrides, rotation, offline Library after enrichment, airplane-mode
  import + reading, memory/scroll smoothness with a large library.
- **Retroid Pocket 5** (Android 13 handheld, controller): controller-only settings, candidate
  picker, Choose from device, focus retention on shape change, short-landscape layout.
- Physical TalkBack and foldable hardware remain evidence gaps unless available.
- Slices 4.5A.1–4.5F use emulator/automated validation only; a slice may request an early
  owner spot-check only for a blocking performance doubt on the 2 GB device.

## 29. Per-slice scope

Workflow for every slice: implementation (Claude, Ponytail discipline) → targeted
validation → **independent Codex High review** → remediation → merge. Physical UAT at 4.5G.
4.5A is an umbrella of **two independent PRs**; each of 4.5A.1 and 4.5A.2 runs its own full
cycle (implementation → validation → Codex High → remediation → merge), 4.5A.2 starting
from `main` after 4.5A.1 merges.

**Adjustments to the proposed order (repo evidence):**

- **A. 4.5A starts with local artwork extraction** (4.5A.1, its own PR) before presentation
  (4.5A.2, a second PR): no cover artwork exists today (§6), so Crop/Fit/Original could not
  be built or validated otherwise. Splitting keeps each PR reviewable (schema/file lifecycle
  vs preferences/Settings UI). Both are fully offline and need no provider.
- **B. Manga (4.5D) before Comics (4.5E) is kept**: Manga's primary path (exact ISBN) runs
  through the Book provider path built in 4.5C, while Comics depends on unresolved provider
  decisions (D5, D19). Manga acceptance does not wait on Comics, but the phase does.
- No other reordering.

### 4.5A — Cover Presentation Foundation (umbrella: two independent PRs)

#### 4.5A.1 — Local Cover Artwork (Room v6)

- **Scope:** Room v6 with `publication_cover` (§23.1, one row per artwork kind, FK cascade,
  extraction status model §23.3); EPUB embedded cover (OPF); CBZ/CBR cover extraction
  (ComicInfo `FrontCover` page if declared, else first page in natural order); PDF
  first-page preview (bounded, through the existing PDF open path incl. API 24/25 legacy
  probe); **bounded artwork storage and decoding** (§7 rule 1: ORIGINAL ≤ ~1 MiB/~4 Mpx,
  else DERIVED ≤ 1600 px, original dimensions recorded; storage budget §18); local cover
  lifecycle (post-commit background extraction, atomic temp-then-rename, deletion with item
  removal, startup reconciliation, orphan sweep); safe idempotent backfill for existing
  libraries (bounded concurrency, per-file failure isolation); a **minimal shared cover
  renderer** (artwork | existing generated visual) used by the Library grid, Continue
  Reading, details pane, cover-expand transition, Search and Notes hub rows; the
  **existing frame presentation** (today's fixed ≈ 2:3 frame) initially — no preference
  table, no Settings overhaul; details technical line shows cover provenance
  (embedded/first page/generated, ORIGINAL vs DERIVED); removal of the duplicated
  `coverColor`/`coverMotif` from `LibraryItem`.
- **Non-goals:** any network, permission change, provider, Choose from device (4.5F),
  shapes/Crop/Fit/Original/custom ratio and any preference storage (4.5A.2), metadata
  fields beyond covers, per-Shelf/per-item shapes, Series covers.
- **Depends on:** nothing in 4.5 (starts from `main`).
- **Validation:** JVM tests (§27), v5→v6 migration test incl. FK cascade, extraction corpus
  incl. malformed, oversized and huge-comic-page art, removal-race and interrupted-extraction
  tests, storage-budget tests, process-death backfill resume, emulator
  phone/tablet/short-landscape, Classic/Dark, keyboard/D-pad focus retention, 2 GB memory
  budget reasoning.
- **Review gate:** its own Codex High (schema, file lifecycle, memory, storage policy).

#### 4.5A.2 — Cover Presentation (Room v7)

- **Scope:** Room v7 with `cover_presentation_preference` (§23.6); configurable shapes
  Original / 2:3 / 3:4 / 4:5 / 1:1 / Custom W:H; Crop to fill / Fit entire artwork (never
  stretched); global default plus per-category overrides; Settings → Library appearance
  (Custom W:H steppers); details-pane behavior (whole artwork); cover-expand behavior;
  keyboard/controller/accessibility; grid focus and scroll preserved on shape change;
  EN/ES/PT-BR parity.
- **Non-goals:** network, providers, per-Shelf/per-item shapes, new artwork extraction.
- **Depends on:** 4.5A.1 merged (needs real artwork, the shared renderer and recorded
  original dimensions).
- **Validation:** JVM crop/fit/preference math, v6→v7 migration test, settings persistence
  across restart/rotation/process death, emulator phone/tablet/short-landscape, Classic/Dark,
  D-pad/gamepad reachability, localization parity.
- **Review gate:** its own Codex High (preference model, focus, localization).

### 4.5B — Metadata Core

- **Scope:** provenance/confidence model; `IdentifierDetector`; extended
  `EmbeddedMetadataReader` (EPUB identifier/publisher/date/language/description; full
  ComicInfo fields incl. GTIN/Volume/Number/Series); `FilenameInference` volume/issue;
  local analysis backfill; `MetadataProvider`/`MetadataCandidate`/`MetadataQuery`;
  `MetadataMatcher`; `MetadataEnrichmentService` with fake providers only; the next
  additive Room version (v8 if 4.5A.1/4.5A.2 are merged; §23.7); minimal editor for new
  fields with `USER` provenance; details pane shows new fields. **Depends on:** 4.5A.1
  (not 4.5A.2). Provider-specific schema changes may begin in 4.5B only if justified.
- **Non-goals:** real providers, `INTERNET`, BYOK UI, candidate UI.
- **Validation:** JVM matcher/parser/precedence suites; coordinator tests with fakes;
  migration test; import latency unchanged.
- **Review:** Codex High.

### 4.5C — Books

- **Scope:** (requires D1, D4, D14, D15) `core.network` helper; manifest `INTERNET`;
  Settings online toggle + disclosure; Open Library provider (ISBN-first, search fallback,
  edition covers by cover id); rate limiting/circuit breaker; automatic VERY_HIGH apply to
  unresolved fields and §17 cover improvement; persisted MEDIUM suggestions; attribution.
  Google Books only if D2 approves BYOK (otherwise not built).
- **Non-goals:** comic/manga-specific providers; manual picker UI (4.5F).
- **Validation:** fixtures, fake HTTP, offline/429/timeouts, wrong-match corpus; one
  optional isolated manual live check.
- **Review:** Codex High (network/privacy/terms).

### 4.5D — Manga

- **Scope:** volume parsing hardening; ISBN/edition volume matching through the existing
  provider; volume-specific cover rule (§12); manga query building (series + volume +
  creator + language); specialist provider only if D6 approves one.
- **Non-goals:** series-level cover auto-apply; scanlation sources.
- **Validation:** manga corpus (EN/JP/ES releases, omnibus vs single, Vol 1 vs 10, RTL);
  wrong-match corpus.
- **Review:** Codex High.

### 4.5E — Comics

- **Scope:** Series + issue matching; UPC/ISBN; MetronInfo.xml/ComicInfo evidence; issue
  cover rule (§17, D7); trade paperbacks via ISBN; **an approved, integrated issue-level
  online provider path is required** — preferred: Metron with per-user tokens (Keystore-held,
  never shipped), only after the D5 prerequisites are closed; explicit candidate matching
  with safe variant/ambiguity handling; Metron attribution; licensing/artwork-policy
  decision recorded as an ADR. **4.5E cannot be closed with local-only enrichment.** If no
  suitable provider is approved and integrated, 4.5E stays OPEN.
- **Non-goals:** Comic Vine/GCD integration without approval; variant-cover browsing beyond
  the candidate picker; shipping any shared provider account.
- **Validation:** comic corpus (single issues, annuals, #0/#½, volume restarts, variants,
  TPB), ComicInfo/MetronInfo-only path, token-credential handling tests, rate-limit/
  caching behavior against the provider's documented limits (fake HTTP), wrong-match corpus.
- **Review:** Codex High (credentials, terms).

### 4.5F — Enrichment UX

- **Scope:** "We think this is…" confirmation; Find cover online candidate grid; Choose from
  device; Use file/generated cover; Refresh metadata; per-field revert; failure/offline
  states; quiet Library indicator for items needing review.
- **Non-goals:** bulk "fix all" wizards; per-item shape overrides.
- **Validation:** UI tests for each flow incl. gamepad/keyboard, recreation, compact/
  expanded/short-landscape, localization parity.
- **Review:** Codex High (user-override correctness), may be standard for pure UI if the
  administrator agrees.

### 4.5G — Acceptance

- **Scope:** accumulated acceptance (**blocked while 4.5E is open**): Books, Comics, Manga
  matrices; Documents conservative
  behavior; wrong-match protection; cache/offline; outage/rate-limit simulation; source
  immutability (checksums before/after); privacy review (query contents, no file upload);
  provider/legal checklist re-verified; Samsung SM-T580 manual LAN UAT; RP5
  controller UAT; keyboard; final docs reconciliation (ROADMAP, README, VALIDATION).
- **Review:** final independent Codex High closure review.

## 30. Acceptance criteria

Phase 4.5 is accepted only when **all** hold:

All categories:

- Import and reading work identically with online enrichment disabled, offline, or with every
  provider failing; no import latency regression.
- Source publications are byte-identical before/after (checksummed sample).
- Cover storage is bounded (§7, §18): small supported artwork stored byte-exact, larger
  artwork as a bounded DERIVED copy that records original pixel dimensions; no unbounded
  duplication of source pages; the source publication byte-identical; display shape is
  presentation only; no stretch/squash in any shape/fit; global default + per-category
  overrides persist across restart, rotation and process death.
- A user-selected cover is never replaced by any automatic path; user-edited fields are
  never changed automatically.
- No provider call on screen open; the Library is visually complete offline after
  enrichment.
- No secret in the APK; every enabled provider has an approved terms review row (§14).

**Books** — identifier detection; title/author/publisher/year/description resolved for a
confident match; edition-aware cover candidates in Find cover online; automatic confident
cover improvement over generated/weak art.

**Manga** — Series/title, volume, creators, publisher, year, ISBN resolved for a confident
match; correct **volume-specific** cover; one Series cover is never applied to every volume
when volume artwork exists; volume mismatches never auto-apply.

**Comics** — **all** of the following are required; there is no local-only exit:
local metadata extraction; ComicInfo.xml evidence; MetronInfo.xml evidence where present;
filename/Series/issue inference; correct local issue-cover extraction; **a working,
approved, issue-level online metadata path**; explicit candidate matching (Series, issue
number, volume where applicable, creators, publisher, date/year, UPC/ISBN where available);
safe handling of variants and ambiguous issues (never auto-applied). Preferred online
candidate: Metron with per-user tokens, pending its terms/owner authorization (D5, D19).
**If no suitable provider is approved and integrated, 4.5E and 4.5G remain OPEN and Phase
4.5 cannot close.** Local artwork alone does not satisfy online Comics enrichment.

**Documents** — embedded/first-page/generated presentation; custom cover; never
auto-matched by title search; optional enrichment only with an explicit identifier.

**Quality** — EN/ES/PT-BR parity; keyboard/D-pad/gamepad complete; compact/expanded/
short-landscape; Samsung SM-T580 and RP5 physical UAT PASS; final Codex High PASS.

## 31. Stop conditions and unresolved decisions (administrator)

Stop and ask before implementing any slice whose decision below is unresolved.

| # | Decision | Recommendation | Blocks |
| --- | --- | --- | --- |
| D1 | Approve Open Library as default keyless Books/Manga provider (on-device cover caching, per-user backfill pacing, attribution placement, optional OL registration) | Approve with 1 req/s pacing, cover-id URLs, UA contact, About-screen credit; register use case with OL | 4.5C, 4.5D |
| D2 | Google Books: drop as default (key required; credentials may not be embedded in OSS; no permanent copies; "may not charge users any fee"; must not alter results) — BYOK later or never? | Do not build in Phase 4.5; revisit as BYOK after legal review. **This changes the earlier "Google Books fallback" direction** | 4.5C |
| D3 | **Resolved by the administrator (remediation):** Comics acceptance requires an approved, integrated issue-level online provider path in addition to local extraction (§30). There is no local-only exit; without an approved provider 4.5E/4.5G stay open | Fixed; no decision needed. Preferred provider: Metron with per-user tokens | 4.5E, 4.5G |
| D4 | Contact string in `User-Agent` (project address, never the user's) | A project-owned address or the repository URL | 4.5C |
| D5 | Metron per-user-token provider — **CONDITIONAL**. Prerequisites before approval: (1) the current `metron.cloud` service/API terms reviewed manually (or clarified by Metron) because the commercial-use statement does not replace them; (2) D19 answered; (3) per-user token UX, no bundled account, attribution placement, caching within the documented 20/min and 5,000/day limits agreed. Comic Vine stays rejected | Contact Metron / read the service terms; approve per-user tokens only once (1)-(3) close | 4.5E |
| D6 | Any specialist manga provider in 4.5 | None; keep the slot | 4.5D |
| D7 | May an online cover replace an embedded CBZ/CBR first-page cover automatically? | No by default; only via the manual picker | 4.5C–E |
| D8 | Does confirming a candidate make its fields USER? | No (cover yes) | 4.5F |
| D9 | Shipped defaults (global 2:3 Crop; Manga 3:4; Documents Original) | Ship as listed | 4.5A.2 |
| D10 | Original mode layout (bottom-aligned in category-ratio slot, clamp 1:2…2:1) | Approve | 4.5A.2 |
| D11 | Details pane always shows whole artwork | Approve | 4.5A.2 |
| D12 | Bounded artwork storage: ORIGINAL ≤ ~1 MiB and ≤ ~4 Mpx else DERIVED with longest edge ≤ 1600 px (benchmark 1200–1600), JPEG (PNG for alpha), PDF first page always DERIVED | Approve as planning defaults; WebP only if a benchmark justifies lossy WebP (lossless WebP encode is API 30+) | 4.5A.1 |
| D13 | WorkManager | Not now; revisit only for closed-app enrichment, with dependency review/ADR | 4.5C |
| D14 | HTTP stack: platform `HttpURLConnection` + `org.json` | Approve | 4.5C |
| D15 | Online enrichment default ON (with disclosure) or OFF | ON with one-time non-blocking disclosure + toggle | 4.5C |
| D16 | Provider columns on `publication_cover` | Not in v6; the slice that first writes provider artwork (4.5C+) adds them, only if justified. v8 is the next version for 4.5B tables, not a mandatory speculative migration | 4.5A.1 |
| D17 | Separate `metadata_candidate` table vs one suggestion per item | One suggestion per item unless 4.5F needs more | 4.5B |
| D18 | Is 4.5A split into 4.5A.1 (local artwork, v6) and 4.5A.2 (presentation, v7) as two PRs | **Resolved by the administrator (remediation)**; recorded here | 4.5A.1, 4.5A.2 |
| D19 | Metron/CC BY-SA open legal questions (no conclusion drawn): ShareAlike effect on cached data, exports/backups, the open-source repository and the paid theme model; attribution placement; whether any Metron-hosted image may be used | Owner/counsel review before 4.5E; keep provider data and covers out of exports meanwhile | 4.5E |
| D20 | Local-cover storage budget (proposed 512 MiB for regenerable artwork), eviction order, Settings → Storage surfacing, whether small grid thumbnails are persisted | Approve budget; no persisted thumbnails in 4.5A.1 | 4.5A.1 |
| D21 | `publication_cover` design: Option B (`PRIMARY KEY (libraryItemId, kind)`), kind list, FK cascade plus explicit delete, kinds `COMICINFO_FRONT_COVER`/`ARCHIVE_FIRST_PAGE` separate or merged, extraction status model (draft, §23.3) | Approve Option B as written; confirm the temporary-failure list | 4.5A.1 |

## 32. Recommended agent workflow

1. Start each slice from fresh `main`, branch `phase-4.5/<slice>`; read this plan, AGENTS.md
   and the docs it cites; confirm the slice's decisions are resolved.
2. Implement the smallest complete slice (Ponytail full); no speculative tables, providers
   or dependencies; update docs touched by the slice.
3. Targeted validation (unit, migration, emulator) and record evidence in
   `docs/VALIDATION.md`.
4. Independent Codex High review → remediation → re-review → merge (owner pushes/merges).
   Each of 4.5A.1 and 4.5A.2 gets its own cycle.
5. Physical UAT accumulates in the 4.5G checklist.

### Recommended first implementation prompt (4.5A.1 only)

> Implement **Phase 4.5A.1 — Local Cover Artwork** per
> `docs/PHASE_4_5_IMPLEMENTATION_PLAN.md` §6, §7, §17, §18, §19, §23.1–§23.5 (v6 only), §24
> and §29, from fresh `main`, branch `phase-4.5/4.5a1-local-cover-artwork`. Scope: add Room
> v6 with `publication_cover` (one row per artwork kind, `PRIMARY KEY (libraryItemId, kind)`,
> FK `ON DELETE CASCADE` plus explicit delete in both removal transactions, status
> READY/FAILED_PERMANENT/FAILED_TEMPORARY with the draft reason lists in §23.3) via a
> hand-written additive `MIGRATION_5_6` (legacy `bookmark` untouched) and a raw-SQLite
> migration test including the cascade; extract EPUB covers (EPUB2 `meta name="cover"` and
> EPUB3 `properties="cover-image"`, path-safe through `ArchivePolicy`), CBZ/CBR covers
> (ComicInfo `FrontCover` if declared, otherwise first page in `ArchivePolicy.pages` /
> `RarContainer` order) and a bounded PDF first-page render via the existing PDF open path;
> apply the **bounded storage policy**: byte-exact only for supported images ≤ ~1 MiB and
> ≤ ~4 Mpx, otherwise a DERIVED copy with longest edge ≤ 1600 px (JPEG, PNG for alpha),
> record ORIGINAL/DERIVED and original pixel dimensions, enforce the storage budget, never
> duplicate large comic pages, never evict a user-selected cover; write temp file then
> atomic rename and the row last in a guarded transaction; run extraction post-commit on
> `AppContainer.backgroundScope` with an idempotent, bounded-concurrency launch backfill and
> startup reconciliation; delete rows/files with item removal and sweep orphans; add one
> minimal shared cover renderer (artwork or the existing generated visual) used by the
> Library grid, Continue Reading, details pane, cover-expand transition, Search and Notes
> hub rows with the **existing frame presentation** (no shapes, Crop/Fit, preferences or
> Settings changes); remove the duplicated `coverColor`/`coverMotif` from `LibraryItem`; decode with
> `inSampleSize` into a bounded `ByteBudgetedLruCache`. No network, no permission change,
> no provider, no new dependency, no Choose from device, no Room v7. Validate with JVM tests
> (storage policy, status transitions, href resolution, first-page selection, precedence),
> the migration test, an extraction corpus including malformed, oversized and huge comic
> pages, removal-race and interrupted-extraction tests, and emulator
> phone/tablet/short-landscape in Classic and Dark. Record evidence in `docs/VALIDATION.md`.
> Commit locally; do not push. Then stop for independent Codex High review.

### 4.5A.2 follow-on scope note

After 4.5A.1 merges, **4.5A.2 — Cover Presentation** starts from fresh `main` on branch
`phase-4.5/4.5a2-cover-presentation`: Room v7 `cover_presentation_preference`; shapes
(Original, 2:3, 3:4, 4:5, 1:1, Custom W:H) with Crop/Fit; global plus per-category
preferences; Settings → Library appearance; details-pane and cover-expand behavior;
keyboard/controller/accessibility; EN/ES/PT-BR. It has its own implementation prompt written
then, its own validation, Codex High review, remediation and merge.
