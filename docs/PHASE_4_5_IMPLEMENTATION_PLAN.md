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
   enrichment and cover workflows (§30). Documents stay conservative.
3. Cover **source** and cover **presentation** are separate. ShelfOS preserves the best
   cached original artwork at native dimensions and controls display shape separately:
   `provider/embedded artwork → cached original → ShelfOS cover renderer → user display
   aspect → Crop or Fit`. Never stretch or squash.
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
  (`domain/library/LibraryItem.kt`) — presentation logic leaking into domain; 4.5A should
  remove the duplicate when it touches the renderer.
- Consumers of the cover visual: Library grid tile and Continue Reading row
  (`feature/library/LibraryScreen.kt`, `GridCells.Adaptive(112/136 dp)`), details pane
  (`PublicationDetails.kt`, width derived from `0.68f`), reader-entry expand transition
  (`feature/home/CoverExpandTransition.kt`), Notes hub rows (Phase 4D, shared generated
  visual).
- Image decoding infrastructure exists only for reader pages/thumbnails
  (`core/reader/FixedReader.kt`, `ThumbnailLoader.kt` with `ByteBudgetedLruCache`). No
  image-loading library.

Consequence for slicing: "preserve original dimensions" has nothing to preserve yet, and
Crop/Fit cannot be validated against generated art. **4.5A must begin with local artwork
extraction** (§29, adjustment A).

## 7. Cover presentation model

Pipeline (proposed):

```text
artwork source (embedded file entry | PDF first-page render | user image | provider image)
  → cached original (bytes kept as delivered; native pixel dimensions recorded)
  → ShelfOS cover renderer (one shared composable)
  → effective display aspect (resolved preference, §8)
  → Crop to fill | Fit entire artwork
```

Rules:

1. **Originals are never re-shaped on disk.** Embedded EPUB/CBZ/CBR cover entries are stored
   byte-for-byte (no re-encode). Provider images are stored as downloaded. A PDF has no
   embedded cover image, so its first page is *rendered* once into a derived image at a
   bounded long edge (proposed 1600 px, (open) D12) and recorded as `FIRST_PAGE`, never
   called "original".
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
   on-disk original is untouched. Sized for the 2 GB SM-T580.
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
  Library presentation; no SharedPreferences/DataStore are used. 4.5A therefore adds a small
  Room table in the v5→v6 migration (§23). Theme-independent: switching theme never changes
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
  4.5A already gives a correct volume-specific cover offline; online covers only replace it
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
  Writer, Penciller, Publisher, Summary, LanguageISO, GTIN, Web) and the first page as the
  issue cover. For digital comics this is usually the most reliable evidence available.
- **Online**: there is **no approved default comic provider** today (§14):
  - **Metron** — serious candidate (community DB, issue-level, covers). Requires a Metron
    *user account* (Basic auth), documented secondhand at 20 req/min and 5,000 req/day;
    data/image terms could not be established (site behind bot protection on 2026-10-15).
    Only viable as **user-supplied credentials (BYOK)** — ShelfOS must never ship an
    account. RESEARCH / NOT YET APPROVED (D5).
  - **Comic Vine** — "strictly for non-commercial use only"; rejected for default; BYOK only
    after legal review (D5).
  - **GCD** — data CC BY-SA 4.0 (share-alike obligations on redistributed data), minimal
    public API (series/publisher), cover-scan rights unclear. RESEARCH.
  - **Open Library** — covers ISBN-bearing collected editions only.
- Metadata licensing ≠ cover-art rights: even a provider whose *data* is openly licensed does
  not license the publisher's cover art.
- Acceptance for Comics therefore depends on D3 (what "viable comic enrichment" means
  without an approved keyless issue-level provider). See §30 and §31 — this is a **stop
  condition** for 4.5E.

## 14. Provider / legal / terms checklist

Accessed 2026-10-15. "Secondhand" means the official page could not be read (blocked or 403)
and the figure comes from a client library or search summary — it must be re-verified
before implementation.

| Provider | Coverage / granularity | Covers | Auth / key | Documented limits | Caching / storage | Attribution | Commercial / OSS fit | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Open Library** ([API](https://openlibrary.org/developers/api), [Covers](https://openlibrary.org/dev/docs/api/covers), [Licensing](https://openlibrary.org/developers/licensing), [Search](https://openlibrary.org/dev/docs/api/search)) | Books; editions/works; many commercial manga volumes; some comic TPBs. Edition-level | Yes, S/M/L by cover id/OLID/ISBN | None. Identify via `User-Agent: App (contact)`; optional registration form | 1 req/s unidentified, 3 req/s identified; covers by ISBN/OCLC/LCCN: 100 per IP per 5 min (403 beyond); cover-id/OLID not limited | No explicit cache terms; "not for bulk download"; asks web pages to use `covers.openlibrary.org` src URLs | "Courtesy link back … appreciated" | APIs "not intended to serve as … high-traffic commercial infrastructure"; prioritizes open-source/library projects. IA "does not assert any new copyright"; existing rights may apply | **Recommended default candidate — NOT YET APPROVED** (D1). Questions: on-device caching vs src-URL guidance; per-user library backfill vs "bulk" |
| **Google Books** ([Terms](https://developers.google.com/books/terms), [APIs ToS](https://developers.google.com/terms), [Using API](https://developers.google.com/books/docs/v1/using), [Branding](https://developers.google.com/books/branding)) | Books, editions; some manga | `imageLinks` thumbnails on books.google.com | Docs: public requests "must be accompanied by an identifier" (API key or OAuth). ToS §4(b)(1): "Developer credentials may not be embedded in open source projects" | Quota per key (no figure stated) | APIs ToS §5(e): no permanent copies; no cache "longer than permitted by the cache header" | "Powered by Google" adjacent to results; prominent link to Google Books page; "must not alter results" | Books terms: "You may not charge users any fee for the use of your application" (conflict risk with ShelfOS Plus); cropping may be "altering" | **RESEARCH / NOT YET APPROVED.** Not usable as keyless default (key required, no embedding). BYOK-only after legal review (D2). Offline cover caching conflicts with no-permanent-copies |
| **Metron** ([site](https://metron.cloud/) blocked; [repo](https://github.com/metron-project/metron); secondhand [mokkari](https://mokkari.readthedocs.io/en/stable/mokkari/session/)) | Comics, issue-level; series, creators, publishers | Yes (rights unknown) | Metron user account (Basic auth) | Secondhand: 20 req/min, 5,000/day | Unknown | Unknown | Server code GPL-3.0 (irrelevant to API use); data/image terms not established | **RESEARCH / NOT YET APPROVED.** User-credential BYOK only (D5) |
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
  file per *kind*: `embedded.<ext>`, `firstpage.webp|png`, `user.<ext>`, `online.<ext>`),
  plus one Room row per item naming the active cover and its native dimensions (§23).
  `filesDir`, not `cacheDir`: the system must not evict the Library's identity; size is
  bounded per image (proposed 10 MiB original cap; larger embedded images are downsampled
  into a derived copy and recorded as such).
- Resolved metadata is persisted in Room; screens read Room only. **No provider call on
  screen open**; network happens only in the enrichment worker or an explicit user action.
- Offline: the Library is visually complete from cached state; provider failure never blanks
  metadata or covers.
- Unavailable sources keep their cover and metadata (`features/LIBRARY.md` §14).
- Removal: item removal (both knowledge policies) deletes that item's cover directory after
  the Room transaction commits; a startup sweep deletes cover directories without a
  `library_item` (like `unusedPrivateCopies`). Orphan annotation snapshots fall back to the
  generated cover.
- Invalidation: covers are re-derived only when the item's cover row is missing or the user
  asks (Refresh); online data has `resolvedAt` and a refresh-eligibility date (§19), never
  automatic periodic refetch.
- Provider-specific cache restrictions (D1/D2) are enforced by the registry: a provider
  flagged "no persistent cache" can only populate the manual picker for the session.
- Backups/Portable Knowledge: unchanged; no cover binaries or provider data.

## 19. Background enrichment lifecycle

States per item (proposed): `PENDING_LOCAL` → `LOCAL_DONE` → `PENDING_ONLINE` →
`MATCHED(tier)` | `NEEDS_REVIEW` | `NO_MATCH` | `FAILED(retryable)` | `SKIPPED(disabled/
offline/category)`.

1. Import commits the item exactly as today (no added latency).
2. Local pass (4.5A covers, 4.5B fields): runs on `AppContainer.backgroundScope`, bounded
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
- Offline path: everything in 4.5A/4.5B works with no network permission at all.

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

**v5 → v6 (4.5A)** — additive:

```sql
CREATE TABLE publication_cover (
  libraryItemId TEXT NOT NULL PRIMARY KEY,   -- no FK, like `annotation`; deleted explicitly
  kind TEXT NOT NULL,                        -- EMBEDDED | FIRST_PAGE | USER | ONLINE | NONE (extraction failed)
  fileName TEXT,                             -- relative to filesDir/covers/<id>/
  widthPx INTEGER, heightPx INTEGER, mimeType TEXT,
  providerId TEXT, providerRef TEXT, confidence TEXT,   -- null until 4.5C
  updatedAt INTEGER NOT NULL
);
CREATE TABLE cover_presentation_preference (
  scope TEXT NOT NULL PRIMARY KEY,           -- GLOBAL | BOOK | COMIC | MANGA | DOCUMENT
  shape TEXT NOT NULL, customWidth INTEGER, customHeight INTEGER, fit TEXT NOT NULL
);
```

Alternative considered: a cover file convention with no Room row. Rejected: the Library
observes Room `Flow`s, so a row gives reactive refresh, records native dimensions without
re-decoding headers per frame, and is the natural home for provenance later.
Including the provider columns now is acceptable only if the reviewer agrees; otherwise
they arrive in v7 (D16).

**v6 → v7 (4.5B)** — additive (proposed):

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

No change to `annotation`, `annotation_source_snapshot`, `reading_state`,
`reader_preference` or legacy `bookmark`.

## 24. Migration considerations

- Hand-written `Migration(5, 6)` / `Migration(6, 7)` in `ShelfDatabase`, additive
  `CREATE TABLE` only, exported schema JSON committed, no destructive fallback — same style
  as `MIGRATION_4_5`.
- The legacy `bookmark` table stays untouched (its removal is a separate, dedicated
  maintenance migration, not Phase 4.5).
- Instrumented migration tests with the repository's raw-SQLite pattern: create v5 with
  representative rows (items, reading, preferences, annotations, snapshot, legacy
  bookmarks), migrate, assert every pre-existing row and column is byte-identical and the
  new tables exist and are empty.
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
  filename volume/issue parser corpus (Latin + CJK tokens); matcher tiers incl. ambiguity
  and contradiction cases; precedence resolver (USER never replaced); provider JSON parsing
  from **checked-in fixture files**; rate limiter/backoff with a virtual clock.
- **Fake providers** implement `MetadataProvider` for coordinator tests (success, timeout,
  429, malformed, two conflicting VERY_HIGH candidates, series-only cover).
- **No live network in automated tests.** An optional, manually-invoked contract check
  against real providers may exist outside the CI task graph, clearly isolated.
- **Instrumented:** v5→v6 / v6→v7 migrations; cover extraction on the sample corpus (EPUB2,
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
- Slices 4.5A–4.5F use emulator/automated validation only; a slice may request an early
  owner spot-check only for a blocking performance doubt on the 2 GB device.

## 29. Per-slice scope

Workflow for every slice: implementation (Claude, Ponytail discipline) → targeted
validation → **independent Codex High review** → remediation → merge. Physical UAT at 4.5G.

**Adjustments to the proposed order (repo evidence):**

- **A. 4.5A starts with local artwork extraction** (4.5A.1) before presentation (4.5A.2):
  no cover artwork exists today (§6), so Crop/Fit/Original could not be built or validated
  otherwise. It is fully offline and needs no provider.
- **B. Manga (4.5D) before Comics (4.5E) is kept**: Manga's primary path (exact ISBN) runs
  through the Book provider path built in 4.5C, while Comics depends on unresolved provider
  decisions (D3, D5).
- No other reordering.

### 4.5A — Cover Presentation Foundation

- **Scope:** 4.5A.1 local artwork: EPUB embedded cover (OPF), CBZ/CBR first page (ComicInfo
  `FrontCover` page if declared, else first page in natural order), PDF first-page render
  (bounded, through the existing PDF open path incl. API 24/25 legacy probe); store
  originals byte-for-byte; `publication_cover` row; post-commit background extraction +
  idempotent startup backfill for existing libraries; deletion with item removal + orphan
  sweep. 4.5A.2 presentation: shared cover renderer (artwork | generated) with shape/fit;
  `cover_presentation_preference`; Settings → Library appearance (global + per category,
  Custom W:H steppers); apply to grid, Continue Reading, details, expand transition, Notes
  hub; bounded bitmap LRU; details technical line shows cover provenance
  (embedded/first page/generated).
- **Non-goals:** any network, permission change, provider, Choose from device (4.5F),
  metadata fields beyond covers, per-Shelf/per-item shapes, Series covers.
- **Validation:** JVM tests (§27), v5→v6 migration test, extraction corpus incl. malformed
  and oversized art, process-death backfill resume, emulator phone/tablet/short-landscape,
  Classic/Dark, keyboard/D-pad focus retention, 2 GB memory budget reasoning.
- **Review:** Codex High (schema, file lifecycle, memory).

### 4.5B — Metadata Core

- **Scope:** provenance/confidence model; `IdentifierDetector`; extended
  `EmbeddedMetadataReader` (EPUB identifier/publisher/date/language/description; full
  ComicInfo fields incl. GTIN/Volume/Number/Series); `FilenameInference` volume/issue;
  local analysis backfill; `MetadataProvider`/`MetadataCandidate`/`MetadataQuery`;
  `MetadataMatcher`; `MetadataEnrichmentService` with fake providers only; v6→v7; minimal
  editor for new fields with `USER` provenance; details pane shows new fields.
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

- **Scope:** (gated on D3/D5) Series + issue matching; UPC/ISBN; issue cover rule (§17,
  D7); trade paperbacks via ISBN; the approved comic provider path (e.g. Metron BYOK with
  Keystore-held credentials) or, if none is approved, the documented local-first comic
  acceptance from D3; licensing/artwork-policy decision recorded as an ADR.
- **Non-goals:** Comic Vine/GCD integration without approval; variant-cover browsing beyond
  the candidate picker.
- **Validation:** comic corpus (single issues, annuals, #0/#½, volume restarts, TPB),
  ComicInfo-only path, BYOK credential handling tests.
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

- **Scope:** accumulated acceptance: Books, Comics, Manga matrices; Documents conservative
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
- Original artwork stored at native dimensions; display shape is presentation only; no
  stretch/squash in any shape/fit; global default + per-category overrides persist across
  restart, rotation and process death.
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

**Comics** — Series, issue number, volume where applicable, creators, publisher, date/year,
UPC/ISBN where available resolved for a confident match; issue-specific cover; correct
Series + issue matching; acceptance definition per D3 if no keyless issue-level provider is
approved.

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
| D3 | Comics acceptance without an approved keyless issue-level provider: is "ComicInfo + filename + first-page cover + Open Library for ISBN editions + optional BYOK Metron" viable enrichment? | Yes, if D5 approves Metron BYOK; otherwise accept the local-first definition explicitly | 4.5E, 4.5G |
| D4 | Contact string in `User-Agent` (project address, never the user's) | A project-owned address or the repository URL | 4.5C |
| D5 | Metron (user-account BYOK) and Comic Vine (non-commercial BYOK) — approve, research further, or reject | Contact Metron for terms; Comic Vine rejected | 4.5E |
| D6 | Any specialist manga provider in 4.5 | None; keep the slot | 4.5D |
| D7 | May an online cover replace an embedded CBZ/CBR first-page cover automatically? | No by default; only via the manual picker | 4.5C–E |
| D8 | Does confirming a candidate make its fields USER? | No (cover yes) | 4.5F |
| D9 | Shipped defaults (global 2:3 Crop; Manga 3:4; Documents Original) | Ship as listed | 4.5A |
| D10 | Original mode layout (bottom-aligned in category-ratio slot, clamp 1:2…2:1) | Approve | 4.5A |
| D11 | Details pane always shows whole artwork | Approve | 4.5A |
| D12 | PDF first-page render size and format (1600 px long edge, WebP/PNG) | Approve; verify lossless WebP encoding on API 24–29 at implementation, else PNG | 4.5A |
| D13 | WorkManager | Not now; revisit only for closed-app enrichment, with dependency review/ADR | 4.5C |
| D14 | HTTP stack: platform `HttpURLConnection` + `org.json` | Approve | 4.5C |
| D15 | Online enrichment default ON (with disclosure) or OFF | ON with one-time non-blocking disclosure + toggle | 4.5C |
| D16 | Provider columns in v6 or v7 | v7 (keep v6 minimal) | 4.5A |
| D17 | Separate `metadata_candidate` table vs one suggestion per item | One suggestion per item unless 4.5F needs more | 4.5B |

## 32. Recommended agent workflow

1. Start each slice from fresh `main`, branch `phase-4.5/<slice>`; read this plan, AGENTS.md
   and the docs it cites; confirm the slice's decisions are resolved.
2. Implement the smallest complete slice (Ponytail full); no speculative tables, providers
   or dependencies; update docs touched by the slice.
3. Targeted validation (unit, migration, emulator) and record evidence in
   `docs/VALIDATION.md`.
4. Independent Codex High review → remediation → re-review → merge (owner pushes/merges).
5. Physical UAT accumulates in the 4.5G checklist.

### Recommended first implementation prompt (4.5A)

> Implement **Phase 4.5A — Cover Presentation Foundation** per
> `docs/PHASE_4_5_IMPLEMENTATION_PLAN.md` §7, §8, §17, §18, §23 (v6 only), §24 and §29,
> from fresh `main`, branch `phase-4.5/4.5a-cover-presentation`. Two checkpoints in one PR:
> **4.5A.1 local artwork** — extract EPUB embedded covers (EPUB2 `meta name="cover"` and
> EPUB3 `properties="cover-image"`, path-safe through `ArchivePolicy`), CBZ/CBR first page
> (ComicInfo `FrontCover` if declared, otherwise first page in `ArchivePolicy.pages` /
> `RarContainer` order), and a bounded PDF first-page render via the existing PDF open path;
> store originals byte-for-byte under `filesDir/covers/<itemId>/`; add Room v6 with
> `publication_cover` and `cover_presentation_preference` via a hand-written additive
> `MIGRATION_5_6` (legacy `bookmark` untouched) and a raw-SQLite migration test; run
> extraction post-commit on `AppContainer.backgroundScope` plus an idempotent launch
> backfill; delete cover rows/files with item removal and sweep orphans.
> **4.5A.2 presentation** — one shared cover renderer (real artwork or the existing generated
> visual) supporting Original/2:3/3:4/4:5/1:1/Custom W:H and Crop to fill/Fit entire
> artwork without stretching; global default + per-category overrides in Settings → Library
> appearance (EN/ES/PT-BR, D-pad/gamepad, steppers for Custom); apply to the Library grid,
> Continue Reading, details pane (whole artwork), cover-expand transition and Notes hub;
> decode with `inSampleSize` into a bounded `ByteBudgetedLruCache`; keep grid focus/scroll on
> shape change; remove the duplicated `coverColor`/`coverMotif` from `LibraryItem`.
> No network, no permission change, no provider, no new dependency, no Choose from device.
> Validate with JVM tests (fit/crop math, preference resolution, cover href resolution,
> first-page selection), the migration test, an extraction corpus including malformed and
> oversized art, and emulator phone/tablet/short-landscape in Classic and Dark. Record
> evidence in `docs/VALIDATION.md`. Commit locally; do not push. Then stop for independent
> Codex High review.
