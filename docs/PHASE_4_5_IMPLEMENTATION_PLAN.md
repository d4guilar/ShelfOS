# Phase 4.5 Implementation Plan — Metadata, Covers & Library Presentation

Status (2026-10-16): **4.5A.1 Local Cover Artwork MERGED (PR #44)** (record §33) and **4.5A.2
Cover Presentation MERGED (PR #45)** (record §34); their physical UAT is part of 4.5G. **4.5B
Metadata Core IMPLEMENTED / UNDER REVIEW on branch `phase-4.5/4.5b-metadata-core` (not accepted,
not merged)**; record, network-boundary design and deviations: §35. No online enrichment is
shipped; no other slice is implemented.
Phase 4 (Notes and Knowledge Layer) is COMPLETE (Phase 4F acceptance merged
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
- **APPROVED** — an administrator decision recorded in §31 (currently D3, D9–D12, D18,
  D20 and D21; D12/D20/D21 approve the *initial 4.5A.1 policy* only).

Remediation (2026-10-15, after the first independent Codex High review): Comics acceptance
now requires an approved online path (§30, D3); Metron research was redone from Metron's
official commercial-use statement (§14); cover storage is bounded rather than byte-exact
(§7, §18); 4.5A is split into two independent PRs, 4.5A.1 and 4.5A.2 (§29); the
`publication_cover` design was first drawn with one row per artwork kind (superseded in
part 3 by the slot model, §23.1); extraction failures are split into permanent and
temporary (§23.3).

Remediation, part 2 (2026-10-15, same review): extraction failure semantics completed
(§23.3); cover-row lifecycle and removal race closed (§23.4); Library refresh-storm risk
answered from the real Room/Flow shape (§19.1); Google Books **rejected** for Phase 4.5
(§14, D2); Open Library status clarified and re-verified against its official pages (§14,
D1); solid-CBR extraction scheduling (§19.2); Manga acceptance matrix and EN/JP/ES corpus
(§12, §30); cover consumers, presentation semantics, thumbnail path, ComicInfo
FrontCover handling, matching safety and stale-source handling (§6, §7, §13, §15, §18,
§23); the local-cover storage budget re-evaluated (§18.1); 4.5B networking reconciled with
the ROADMAP (§29); the first implementation prompt rewritten (§32). Owner-approved
presentation decisions are marked **(owner-approved)**; nothing else in this plan is
approved.

Remediation, part 3 (2026-10-15, second independent review: 0 HIGH, 2 MEDIUM): **D20
approved** as a per-item local-cover storage policy with **no automatic eviction** and a
low-storage write guard (§7 rule 1, §18, §18.1; the adaptive-quota recommendation is
superseded); the **extraction crash loop closed** by durable per-publication attempt
tracking with parking after two unexplained interruptions (§23.5); the retry schedule made
single and consistent (§23.3); **D21 approved** as the LOCAL/USER/ONLINE cover-slot model,
replacing provenance-as-key (§23.1); **D12 approved** with bounded PDF-preview values (§7);
and the Library cover-map throttle, startup ordering, source-revision unknowns, Metron
wording, WebP facts and provisional Samsung criteria corrected (§14, §19.1, §23.2, §28.1).
Room v6 stays planned, not implemented; v7 stays reserved for 4.5A.2.

Final QA reconciliation (2026-10-15, third independent review: PASS WITH NOTES, 0 blocker/
high/medium, 7 LOW; plan accepted for merge): one Room schema for fresh and migrated
databases, value sets enforced by Kotlin enums and repository validation instead of
migration-only `CHECK`s (§23.1, §24); cover replacement by a new immutable file with the
Room row updated before the old file is deleted (§7, §23.4, §23.5); retry exhaustion parks
at four attempts or two interruptions, whichever first, and the clock-skew clamp uses the
largest single delay (§23.3); CBR/reader cover reuse only for the exact page the extractor
would choose (§19.2); storage admission counts bounded temporary extraction and separates
API 26+ from API 24/25 (§18.1); compatibility of the replacement model with the lifecycle
(§23.4); §32 carries the safeguards. No accepted decision changed (D3, D9-D12, D18, D20,
D21 as recorded; D19 open).

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
   available artwork under a per-item bounded storage policy (≈ 512 KiB per cover: small
   originals copied byte-exact within limits, larger ones as a bounded derivative that
   records the original dimensions; §7, §18.1) and controls display shape separately:
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
- **Cover consumer inventory (verified, every one must be migrated to the one shared
  renderer; none may keep a private copy):**

  | Consumer | Today | File |
  | --- | --- | --- |
  | Library grid tile | `PublicationCover(item, fillMaxWidth)` inside a `LazyVerticalGrid`, `GridCells.Adaptive(112/136 dp)`, selection border | `feature/library/LibraryScreen.kt` (`PublicationTile`) |
  | Continue Reading row | `PublicationCover(item, width 60.dp)` in a `LazyRow` | `feature/library/LibraryScreen.kt` |
  | Library list | **Does not exist.** Library is grid-only; if a list mode is added later it is one more consumer | — |
  | Publication details | `PublicationCover(item, width = min(240.dp, maxHeight*0.4*0.68))` | `feature/library/PublicationDetails.kt` |
  | **Search results** | `PublicationCover(item, Modifier.width(52.dp))` in the Search destination's `LazyColumn` row | `feature/home/ShelfApp.kt` (~line 277) |
  | **Global Notes hub row** | `PublicationCoverVisual(source.libraryItemId, publication, fillMaxWidth)` in a 48 dp box tagged `notes_cover` (live) / `notes_cover_fallback` (orphan); calls the design-system visual directly, bypassing `PublicationCover` | `feature/notes/NotesScreen.kt` (~line 345) |
  | Orphan Notes fallback | same box, `source.live == false`; no `library_item` row exists | `feature/notes/NotesScreen.kt`, `core/database/AnnotationDao.kt` (`SOURCE_JOIN`) |
  | Cover-expand / reader-entry transition | `PublicationCover(item, fillMaxSize)` inside an animated rect lerped from the tile bounds to the reader area | `feature/home/CoverExpandTransition.kt` |

  Note the Search and Notes rows were missing from the first plan's inventory. Notes is the
  only consumer that does not receive a `LibraryItem` (its `AnnotationSource` is built from
  the `annotation LEFT JOIN library_item LEFT JOIN annotation_source_snapshot` query), which
  is why it needs the dedicated data path in §6.2.
- Image decoding infrastructure exists only for reader pages/thumbnails
  (`core/reader/FixedReader.kt`, `ThumbnailLoader.kt` with `ByteBudgetedLruCache`). No
  image-loading library.

### 6.1 How Library state is observed today (verified)

- `LibraryDao.observe()` is `@Transaction @Query("SELECT * FROM library_item ORDER BY addedAt
  DESC, id")` returning `Flow<List<LibraryRecord>>`; `LibraryRecord` embeds the item and
  `@Relation`s `reading_state` and `reader_preference` (`core/database/LibraryDao.kt`).
  `RoomLibraryRepository.publications` maps it to `List<LibraryItem>`
  (`data/library/RoomLibraryRepository.kt`); `LibraryViewModel` holds it as an eager
  `stateIn` and `combine`s it with the filter, selection and query into one
  `LibraryUiState` (`feature/library/LibraryViewModel.kt`).
- Room invalidation is **per table**: any write to `library_item`, `reading_state` or
  `reader_preference` re-runs the whole query, re-maps every item and re-emits a new
  `LibraryUiState` (already true today for each reading-progress save). Cover rows live in a
  **different table**, so they re-emit this Flow only if the cover table is joined or
  `@Relation`-ed into it. That is the design lever for §19.1.
- Notes reads through `AnnotationDao`'s `SOURCE_JOIN` (`annotation LEFT JOIN library_item LEFT
  JOIN annotation_source_snapshot`); its change signal `observeChanges()` counts only those
  three tables (`core/database/AnnotationDao.kt`), so a cover-table write does not refresh
  Notes unless the cover table is added to those queries.

### 6.2 Minimal Notes data path (proposed)

Notes already resolves `libraryItemId`, `live` and `available` per row. Real covers reach it
**without changing the Notes queries**: the row calls the shared renderer with
`libraryItemId` and the renderer resolves the cover reference from the same independently
observed cover-reference map the Library uses (§19.1). Live row: `COVER` if a `READY` row
exists, else generated. Orphan row (`live == false`): the item and its cover rows are gone
(§23.4), so it keeps the generated visual keyed by the stable `libraryItemId` and the
snapshot title — no snapshot columns, no provider, no lookup against a removed item. This
avoids a LEFT JOIN to the cover table (which would make every cover write invalidate the
Notes page and the `observeChanges` signal) and keeps Notes UI free of any provider type.

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

1. **Bounded artwork storage; the source publication is never touched (D12/D20
   APPROVED for the initial 4.5A.1 policy; numeric ceilings are reviewable constants).**
   The original artwork always remains inside the source EPUB/CBZ/CBR (or the
   provider/user file); ShelfOS never duplicates multi-megabyte comic pages just to show
   Library covers. Storage is bounded **per item**: **≈ 512 KiB of stored bytes per cover**
   (§18.1). Every stored artwork file is recorded as **ORIGINAL** or **DERIVED**:
   - *ORIGINAL*: a supported image (JPEG, PNG, WebP, GIF, BMP — whatever `BitmapFactory`
     decodes on the device) whose bytes are **≤ 512 KiB** *and* whose longest edge is
     **≤ the derivative bound (1200 px)** *and* whose header pixel count is within the
     input pixel cap is copied **byte-for-byte**, no re-encode. (The 1200 px edge rule keeps
     every stored file inside one decode envelope; it is a proposal within the approved
     bytes bound.)
   - *DERIVED*: anything else is decoded **once** with subsampling and re-encoded as a
     bounded display copy: **default longest edge ≤ 1200 px** (1600 px only if later
     benchmarks show the details pane needs it, a reviewable constant), aspect ratio
     preserved, no crop, no upscale, an **explicit decoded-pixel cap** (header size is read
     first; input above ≈ 40 Mpx, proposed, is rejected as `FAILED_PERMANENT` without
     allocation, and the decode target is ≈ 2 Mpx), efficient lossy output — **JPEG for
     opaque art (quality ≈ 90 start, the safe default on every API level)**; images with
     alpha are composited on opaque white first (proposed) so the output stays JPEG.
   - **Bounded, non-recursive size reduction (proposed ladder).** The decoded bitmap is
     encoded at most **five times**, never re-encoding an encoded file: (1200 px, q90) →
     (1200, q80) → (1200, q70) → (1000, q70) → (800, q70), stopping at the first result
     ≤ 512 KiB; **800 px is the fixed minimum edge**. If the last pass is still over the
     bound the row becomes `FAILED_PERMANENT` / `UNSUPPORTED_REPRESENTATION` (reassessed
     only by an extractor-version change or an explicit Refresh cover) and the generated
     cover stays. There is no infinite recompression and no silent size overrun.
   - **WebP (facts verified 2026-10-15).** `Bitmap.CompressFormat.WEBP` exists since API 14
     and is **deprecated since API 30**; `WEBP_LOSSY` and `WEBP_LOSSLESS` exist **only
     since API 30** (Android SDK `android-37.0` `api-versions.xml`; the
     [Bitmap.CompressFormat reference](https://developer.android.com/reference/android/graphics/Bitmap.CompressFormat)
     page body could not be retrieved by the fetch tool, so the wording was checked against
     the AOSP `Bitmap.java` source instead). In that source the legacy `WEBP` documents
     that "as of Q" (API 29) a quality of 100 yields **lossless** WebP and any other quality
     yields lossy WebP; on API 24–28 the constant is therefore lossy at every quality. No
     single lossless-WebP behavior may be assumed across `minSdk` 24+. WebP *decode* is
     available on every supported API level; WebP *output* is allowed only if a size/quality
     benchmark justifies it and only through a path that is correct on API 24. **JPEG q90 is
     the safe default.**
   - A **DERIVED copy is never byte-identical to the source image**, and the plan, UI copy
     and technical details never call it the original. The record keeps the **original
     pixel dimensions** and source provenance (`localSource`, `sourceRef`, §23.1) so
     presentation math (`ORIGINAL` shape) uses the true native ratio and the original can be
     re-read from the source when it is available.
   - Provider images (future) are stored as downloaded when within the bounds, else
     DERIVED. User-selected images (future) follow the same rule and are *durable* user
     data, not regenerable cache (§18).
   - **PDF first page (D12 APPROVED).** A PDF has no embedded cover image, so its first
     page is *rendered* once through the existing PDF open path into a DERIVED image:
     bounded render (explicit total-pixel cap), **default longest edge ≈ 1200 px (1600 px
     ceiling only if implementation evidence justifies it)**, **opaque white background
     composited before encoding** (PDF pages are transparent), **JPEG quality ≈ 90 as the
     starting point, adjusted through the ladder above if the 512 KiB bound requires**,
     API 24+ compatible (including the legacy API 24/25 probe), original PDF untouched. It
     is recorded as `localSource = PDF_FIRST_PAGE`, DERIVED, never called "embedded" or
     "original". This is one case among several DERIVED cases, not the only one.
   - **Stored files are immutable and uniquely named (final-QA decision).** Every
     successful extraction writes a **new** file,
     `covers/<libraryItemId>/<slot>-<token>.<ext>` (for example `local-9f3c1a07b2d84e55.jpg`:
     the slot in lowercase, a 64-bit random token as 16 hex digits, a fixed extension from
     the stored MIME type; **no source-derived text** — no entry name, title or path — ever
     appears in a file name). A stored file is **never overwritten and never renamed over
     another file**; replacing a cover means committing a row that references a new file and
     only then deleting the previous one (§23.5). The file name is therefore also the
     artwork's identity for the bitmap cache (§19.1): a replaced cover can never be served
     from the previous cache key.
2. **Display shape is presentation only (owner-approved).** Default **2:3 with Crop to
   fill**. Shapes: `ORIGINAL`, `RATIO_2_3`, `RATIO_3_4`, `RATIO_4_5`, `RATIO_1_1`,
   `CUSTOM(w, h)`. **There is no separate "Auto" option.** Custom ratios are limited to
   **1:2 through 2:1** (integers 1–20 each; the resulting ratio is rejected/clamped outside
   that range) — the same bound as the Original-slot clamp in rule 4, so there is one limit
   everywhere. Books, Comics and Manga use the global default; Documents default to
   Original (§8). The user may change the global default and per-category overrides.
3. **Crop to fill** scales uniformly to cover the frame and center-crops the overflow.
   **Fit entire artwork** scales uniformly to fit inside the frame; the remaining area uses a
   neutral theme token (Classic: `muted`), never a brand accent and never a blurred copy in
   Classic. Neither mode stretches or squashes.
4. **Original (owner-approved)** displays the **complete artwork, no crop**. For regular
   grid navigation every tile reserves a **fixed 2:3 slot** (so `LazyVerticalGrid` rows and
   D-pad movement stay predictable) and the artwork is fitted inside it
   **bottom-aligned, without fill** — like objects standing on a shelf. Because Original
   always shows the whole artwork, the **Crop/Fit control is irrelevant and is disabled**
   (with an explanatory label) while Original is selected; Fit's neutral fill never
   applies. The **focus/selection border follows the displayed artwork's bounds**, not the
   slot. Extreme native ratios are clamped to 1:2 … 2:1 for slot layout only (the artwork
   itself is never stretched). D10 records this as owner-approved.
5. **Generated covers** have no native ratio; they are drawn natively at the effective shape
   (Original → the 2:3 slot). Crop/Fit do not apply to them.
6. **Details pane / large cover (owner-approved):** the original artwork is **fully
   visible at its real aspect ratio**, sized **height-first** (the cover yields height so
   the primary Read action stays in view on compact windows, as today), with **no hardcoded
   0.68** (`PublicationDetails.kt` currently derives width from `0.68f`; 4.5A.2 replaces it
   with the artwork's real ratio, and 4.5A.1 may keep 0.68 only for the generated visual).
   It does not follow the grid Crop/Fit preference.
7. **Decode, not store, at display size — bounded grid-thumbnail path (proposed).**
   Bitmaps are decoded with `inSampleSize` to the rendered pixel size (grid tiles are
   roughly 112–136 dp wide, ≈ 300–420 px at 3x density) and kept in a bounded memory LRU
   (reuse `ByteBudgetedLruCache`, `core/reader/ThumbnailLoader.kt`; **the cache key includes
   the stored `fileName`**, which changes with every replacement, so no stale bitmap is ever
   served under a previous key); the stored artwork file is untouched. No grid tile ever decodes a source archive page, and no tile is required
   to read a multi-megabyte image: it decodes the stored ≤ 1200 px / ≤ 512 KiB file with
   sampling. A regenerable small thumbnail, or a second display derivative, is **not
   committed**: the plan does not add a thumbnail table or format until measurement
   justifies it. **Measurement gate (proposed thresholds, to be confirmed on the Samsung
   SM-T580 2 GB at 4.5A.1 validation and 4.5G):** median tile decode ≤ ~40 ms and p95 ≤
   ~120 ms off the main thread for a 1200 px JPEG sampled to tile size; zero decode on the
   main thread; no `OutOfMemoryError` and no dropped-frame regression versus the generated
   covers while flinging a 2,000-item grid; memory LRU budget ≤ ~24 MiB for covers. Only if
   the gate fails is a small (~300 px) disk thumbnail considered — as a separate decision
   with the measurements attached, outside the approved 4.5A.1 storage policy (§18.1).
8. **Cover-expand transition (owner-approved).** The overlay never stretches the artwork
   during the transition and does not change the reader-navigation contract
   (`PendingCoverTransition.proceed` still runs when the expansion completes;
   `feature/home/CoverExpandTransition.kt`). The cover is lerped from the tile's *displayed
   artwork bounds* (not the slot) to the complete-artwork/reader rectangle, so a grid
   Crop or Fit tile grows into the complete artwork predictably: the overlay renders the
   *same* shape/fit as the tile at progress 0 and reveals the complete artwork by
   progress 1, never mixing two aspect ratios in one frame. Notes hub and Search rows
   use the same renderer so a cover never changes identity between screens.
9. **Placeholder, motion and focus.** While a real cover is known to exist (a `READY` row
   with a not-yet-decoded bitmap) the tile shows a **stable loading frame** — a neutral
   token surface at the final slot size — never the colourful generated cover, so a tile
   does not flash generated → real. The generated cover is shown only when no `READY` row
   exists (or the row failed). A cover change after load cross-fades only when reduced
   motion is off; with reduced motion (system animator scale 0 / the app's reduce-motion
   setting) the swap is instantaneous and **no cover reflow is animated** (the slot size
   never changes, so nothing reflows). Keyboard/gamepad focus and the selected item
   survive a cover appearing, changing or being refreshed (items are keyed by id; focus is
   not tied to the bitmap).
10. Accessibility: covers remain decorative in grids (title text carries meaning); a
    details-pane cover may expose "Cover" plus provenance only inside technical details;
    the loading frame and the generated fallback add no extra semantics; the
    focus border and selection state are conveyed by more than colour (existing
    `shelfAction` behavior).
11. **4.5A.1 rendering until 4.5A.2 (superseded by 4.5A.2, §34).** Until configurable presentation exists,
    stored artwork is shown with **Crop to fill** inside the *existing* fixed-shape frames
    (Library tile `aspectRatio(0.68f)`, Continue Reading, Search 52 dp, Notes 48 dp box and
    the details frame): it is never stretched or squashed. Full-cover details presentation
    and the approved shapes / Fit / Original arrive with 4.5A.2 (D9–D11).

## 8. Preference hierarchy

```text
effective(category) = categoryOverride[category] ?: globalDefault
```

- One **global default** `{shape, customW?, customH?, fit}`.
- An optional override per `MediaCategory` (BOOK, COMIC, MANGA, DOCUMENT); "Use default"
  clears it. Favorites is a cross-category view and uses each item's own category.
- Shipped defaults (**owner-approved**, D9): global **2:3, Crop to fill**; Books, Comics
  and **Manga all use the global default** (the earlier "Manga 3:4" example is withdrawn);
  **Documents Original**. Shapes are Original, 2:3, 3:4, 4:5, 1:1 and Custom; there is no
  Auto option; Custom is limited to 1:2…2:1 (§7 rule 2).
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
| Primary (keyless) | Open Library | **PROMISING / CONDITIONAL — production approval pending (D1).** Keyless access working is not approval. No 4.5A work depends on it |
| Secondary | Google Books | **REJECTED FOR PHASE 4.5** unless explicit written Google permission resolves its usage restrictions (§14, D2). Supersedes the earlier "Google Books fallback" direction |
| Optional BYOK | ISBNdb (paid key), Hardcover (personal beta token) | RESEARCH; later, not Phase 4.5 acceptance scope |

Open Library flow (proposed, only after D1): ISBN → `/isbn/{isbn}.json` (edition) → work for
description / subjects → cover via the edition's `covers` id
(`covers.openlibrary.org/b/id/{id}-L.jpg`, not the per-IP rate-limited ISBN cover path;
ids ≤ 0 such as `-1` are ignored, §15) → `search.json` with `fields=` limited for
title/author fallback. Identify with a `User-Agent` naming ShelfOS and a project contact
(D4). Respect 1 req/s unidentified, 3 req/s identified; never crawl or bulk-download;
cache every response (§14).

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
- **Manga acceptance matrix (required evidence for 4.5D/4.5G):**

  | | Case | Required behavior |
  | --- | --- | --- |
  | A | Exact ISBN **and** edition agreement (publisher/language/volume compatible, no contradiction) | May qualify VERY_HIGH |
  | B | Series + volume, no ISBN | Normally needs candidate confirmation (MEDIUM); never silent |
  | C | Filename-only evidence | Never enough for confident automatic metadata replacement |
  | D | Volume-specific artwork | Mandatory: the cover applied must belong to the matched volume |
  | E | One generic Series cover | Never applied to every volume; series images only in the manual picker, labelled |
  | F | Translated editions / language variants | Must not silently map to a different edition's cover; language contradiction caps at MEDIUM (§15) |

- **EN / JP / ES ISBN coverage corpus (acceptance evidence, 4.5G).** A fixed checked-in
  fixture list of real licensed volumes in English, Japanese and Spanish editions (including
  omnibus vs single, Vol 1 vs 10, same title in two languages), and for **each** case the
  recorded outcome: *exact match*, *partial match*, *ambiguous match*, *missing match*,
  *incorrect edition*, *conflicting volume*, and *existing correct local first-page cover*
  (the case where the correct local cover must be kept). The corpus measures the matcher and
  the chosen provider; **no provider coverage percentage is assumed or claimed in this plan**
  — the numbers are results of the corpus run, not inputs.
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
- **ComicInfo `FrontCover` handling (proposed).** `<Page Image="N" Type="FrontCover"/>`
  names a page by integer index. The official ComicInfo v2.1 schema page
  (anansi-project.github.io, accessed 2026-10-15) lists `Image` as a required integer and
  `FrontCover` among the `Type` values but **does not state the index base**; the
  community convention (and what most writers emit) is **0-based**. The plan therefore
  treats `Image` as an index into ShelfOS's *logical page list* — the image-only,
  `naturalCompare`-sorted list `ArchivePolicy.pages` (CBZ) / `RarContainer.pages` (CBR),
  the same order the reader uses — never into raw archive entry order, which can differ.
  The extractor requires `0 ≤ N < pages.size`; otherwise (out of range, unparsable,
  missing, or a fixture proving a 1-based writer) it **falls back to the ordinary first
  page** and records the reason; the index base is confirmed against fixtures at
  implementation and is **unverified in this plan**. A declared cover is a hint, not truth.
  **Exactly one LOCAL file is stored per publication (§23.1).** A FrontCover that resolves to
  page 0 is the same single file, recorded with `localSource = COMICINFO_FRONT_COVER`
  (`DECLARED`); a FrontCover naming another valid page stores that page; an invalid or
  unavailable declaration falls back to the ordinary first page (`ARCHIVE_FIRST_PAGE`,
  `INFERRED`) only when that is safe. No second row or second file is created.
- **Local comic identifiers (evidence for 4.5B/4.5E, nothing is fetched).** ComicInfo
  `Web` can carry a provider/issue URL or identifier (Metron/Comic Vine/GCD links) and
  `GTIN` a UPC/ISBN; `MetronInfo.xml` (schema to be verified at 4.5B implementation)
  may carry Metron ids, series, issue and variant data. These are parsed as *candidate
  identifiers with provenance*, validated (UPC/ISBN checksum; URL host allow-listed to
  known providers; never fetched automatically), and combined with Series + issue number
  for matching (§15). Variant covers share Series + issue, so a local match never assumes
  the variant: the local first page remains the accurate cover for *this* file.
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
  `metron.live` is unrelated to Metron comic metadata (per the administrator; the official
  statement does not mention it and cites only `metron.cloud`). The statement contains no
  separate service-terms document, so the `metron.cloud` service terms stay unresolved.
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
| **Open Library** ([API](https://openlibrary.org/developers/api), [Covers](https://openlibrary.org/dev/docs/api/covers), [Licensing](https://openlibrary.org/developers/licensing), [Search](https://openlibrary.org/dev/docs/api/search)) | Books; editions/works; many commercial manga volumes; some comic TPBs. Edition-level | Yes, S/M/L by cover id/OLID/ISBN | None. Identify via a `User-Agent` naming the app with a contact email; optional use-case registration form | 1 req/s unidentified, 3 req/s identified; covers by ISBN/OCLC/LCCN: 100 per IP per 5 min (403 beyond); cover-id/OLID not rate-limited | API page: "Cache responses whenever possible." Covers page: "do not crawl our cover API"; intended for displaying covers on public sites; `src` URLs to `covers.openlibrary.org` for web pages; bulk via archive.org dumps. Missing cover returns a blank image unless `?default=false` (then 404). The covers page says nothing about cover id `-1` | "Courtesy link back … appreciated" | Not for scraping or bulk metadata download; APIs not meant for high-traffic commercial services (violations may be rate-limited or blocked). IA "does not assert any new copyright"; "existing rights issues" may apply; licensing page silent on covers and commercial use | **PROMISING / CONDITIONAL — production approval pending (D1).** Re-verified from the three official pages 2026-10-15. Open: ShelfOS is a distributed app (one user = one device IP, which fits per-IP limits) but is a commercial-model app (Plus) — ask OL via the registration form; on-device caching is *encouraged* by the API page and sits outside the web-`src` guidance; a lazy per-item lookup, never a full-library burst, is the only compatible backfill. Keyless access working does **not** approve it |
| **Google Books** ([Terms](https://developers.google.com/books/terms), [APIs ToS](https://developers.google.com/terms), [Using API](https://developers.google.com/books/docs/v1/using), [Branding](https://developers.google.com/books/branding)) | Books, editions; some manga | `imageLinks` thumbnails on books.google.com | Docs: public requests "must be accompanied by an identifier" (API key or OAuth). ToS §4(b)(1): "Developer credentials may not be embedded in open source projects" | Quota per key (no figure stated) | APIs ToS §5(e): no permanent copies; no cache "longer than permitted by the cache header" | "Powered by Google" adjacent to results; prominent link to Google Books page; "must not alter results" | Books terms: "You may not charge users any fee for the use of your application" (conflict risk with ShelfOS Plus); cropping may be "altering" | **REJECTED FOR PHASE 4.5** unless explicit written Google permission resolves the restrictions below (D2). Not a fallback, not research-only-at-most, not optional BYOK |
| **Metron** ([official commercial-use statement, 2026-10-04](https://metron-project.github.io/blog/commercial-api-use); service `metron.cloud`, [repo](https://github.com/metron-project/metron)) | Comics, issue-level; series, creators, publishers | Not under the CC BY-SA license: copyright belongs to publishers or creators; Metron hosts them as fair use, which the statement says does not automatically extend to a downstream app (a US doctrine; other countries differ); ShelfOS prefers local artwork | Per-user account or API token; the statement recommends revocable tokens instead of an account password and says "Basic Auth will be retired eventually" (no date given), so new integrations should start with tokens | 20 requests/minute and 5,000/day per account; a donation can raise a matched account's daily limit up to 25,000; honor `X-RateLimit-*` / `Retry-After` | Caching recommended: store fetched data rather than re-requesting it ("Comic data doesn't change much once an issue has been released") | CC BY-SA 4.0: credit Metron wherever its data is shown, link to Metron and the license, and say whether the data was changed (a credits line or footer is enough); ShareAlike applies to adapted Metron data that is published or distributed, not to data kept internal (the author's reading, not legal advice) | CC BY-SA 4.0 permits commercial use; each user of a distributed app needs their own account/token — the statement says not to ship one built-in account shared by all users. **Not resolved by the statement**: current `metron.cloud` service/API terms; ShareAlike effect on ShelfOS cached data, exports and backups; paid-theme (Plus) interaction; image rights | **CONDITIONAL: preferred candidate, NOT APPROVED** until the D5 prerequisites close. Per-user token (BYOK) only |
| **Comic Vine** ([API](https://comicvine.gamespot.com/api/)) | Comics, issue-level | Yes | Per-user API key (login) | 200 req/resource/hour | Fandom ToS: "Do not edit, manipulate or reproduce on any other medium" | Link back on every page using data | "Strictly for non-commercial use only" | **Rejected for default.** BYOK only if legal review accepts user-side non-commercial use (D5) |
| **Grand Comics Database** ([API root](https://www.comics.org/api/)) | Comics, issue-level, deep international data | Scans exist; rights unclear | None stated | None stated | CC BY-SA 4.0 data (site footer, secondhand) | BY-SA attribution | Share-alike on redistributed data; cover rights not licensed | **RESEARCH.** API surface minimal; not planned for 4.5 |
| **MangaDex** ([API docs](https://api.mangadex.org/docs/)) | Manga series/chapters (scanlation-centric) | Series covers | None | Separate page | — | Must credit MangaDex and scanlation groups | "CANNOT run ads or paid services on your website and/or apps" | **Rejected**: conflicts with ShelfOS Plus; scanlation context; series-level only |
| **AniList** ([terms](https://docs.anilist.co/guide/terms-of-use), 403) | Manga/anime series-level | Series images | OAuth for user data; public queries keyless | Secondhand rate limit | Unknown | Unknown | Secondhand: free for non-commercial; commercial license above a revenue threshold | **RESEARCH / NOT YET APPROVED**; series-level, poor volume-cover fit |
| **MyAnimeList** ([ToU](https://myanimelist.net/membership/terms_of_use)) | Series-level | Series images | Registered client ID | Not reviewed | Not reviewed | Not reviewed | API license not reviewed | **RESEARCH**; client ID in OSS APK is a credential issue |
| **Kitsu** | Series-level | Series images | — | Not found | Not found | Not found | Terms not found | **RESEARCH** |
| **MangaUpdates** | Series-level, release data | Series images | Secondhand: keyless | Not found | Not found | Not found | Terms not found | **RESEARCH** |
| **ISBNdb** ([docs](https://isbndb.com/isbndb-api-documentation-v2), 403) | Books, ISBN-level | Yes | Paid API key (`x-api-key`) | Secondhand: 1–5 req/s by plan | Not reviewed | Not reviewed | Paid; key cannot ship | **Optional BYOK later**; not 4.5 scope |
| **Hardcover** ([getting started](https://docs.hardcover.app/api/getting-started)) | Books | Yes | Personal account token ("not meant to be shared") | Not stated | Not reviewed | Not reviewed | API "in beta" | **RESEARCH**; personal-token BYOK at most, later |

**Why Google Books is rejected (stated once; from the terms read 2026-10-15, not legal
advice).** (1) *Application fee:* the Books terms say you "may not charge users any fee for
the use of your application" — ShelfOS has a paid Plus tier. (2) *Embedded credentials:* the
API terms forbid developer credentials in open-source projects, and ShelfOS is open source.
(3) *Caching:* no permanent copies and no cache beyond what the cache header permits, which
conflicts with the offline, cached-cover Library. (4) *Presentation:* results must not be
altered and Google branding must sit adjacent, which conflicts with ShelfOS cropping/
reframing covers and its own metadata/cover precedence. **BYOK does not remove these**: a
user-supplied key moves the credential, but the application-level obligations (fee, caching,
presentation) still bind the application. Re-opening requires explicit written Google
permission, an owner decision and an ADR.

Cross-cutting conclusions:

1. **Keyed providers can only ever be BYOK** (user enters their own key/account; stored
   locally; never logged; never in backups/exports). No keyed provider can be a zero-setup
   default. BYOK is necessary but not sufficient: it does not satisfy a provider's
   application-level terms (see Google Books above).
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
   or distributed, and says data kept internal is not distributed and does not trigger it
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

**Matching safety rules (proposed).**

- **Title containment alone is never VERY_HIGH, and rarely MEDIUM.** Short-title editions
  with no volume identifier exist (*Saga*, *Batman*, translated manga editions whose title
  is a substring of several others). VERY_HIGH requires **independent converging
  evidence**: an ISBN/stable edition identifier, or Series + issue/volume plus at least one
  of creator, publisher, language/year agreeing. A single weak signal (title equality,
  folder name) plus no contradiction is at most MEDIUM/LOW.
- **Ambiguous edition titles go to confirmation.** If two or more editions of equal
  evidence strength remain (different publisher/language/year, same short title), nothing
  auto-applies; the picker shows them with edition-distinguishing text.
- **Invalid provider cover ids are not candidates.** Open Library edition records can carry
  cover ids such as `-1` (observed convention, not documented on the covers page); any id
  ≤ 0, null, or missing is dropped before a `CoverCandidate` exists, and a returned
  provider "no cover" placeholder is rejected (§17).
- **A wrong cover is worse than keeping the correct local cover.** When unsure, the existing
  local cover (especially a CBZ/CBR first page that is the true volume/issue cover) stays.

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

- The `USABLE EMBEDDED` tier is the single **LOCAL slot** (§23.1): one stored file per
  publication whose `localSource` records whether it is an EPUB cover, a ComicInfo
  FrontCover page, an archive first page or a PDF first-page render. There is no ordering
  between local sources because only one is ever stored. **Slot resolution (D21):** `USER`
  > confident, eligible `ONLINE` (§17 gate) > `LOCAL` > generated fallback.
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

- All covers live in app-private storage (proposed `filesDir/covers/<libraryItemId>/`,
  **one referenced file per item and slot**: `local-<token>.<ext>` now, uniquely named per
  successful extraction (§7 rule 1); `user-…` and `online-…` are future slots), plus one Room row per item and slot recording provenance, stored
  dimensions, original dimensions and ORIGINAL/DERIVED (§23.1). A file is never shared
  between rows. `filesDir`, not `cacheDir`: the system must not evict the Library's
  identity.
- **Bounded storage policy (D12/D20 APPROVED for the initial 4.5A.1 policy).** Each stored
  file follows §7 rule 1: ≈ 512 KiB per cover; ORIGINAL (byte-exact) only when small and
  within the size and pixel limits, otherwise a bounded DERIVED copy (default longest edge
  ≤ 1200 px). Multi-megabyte comic pages are never duplicated. A DERIVED copy is not
  byte-identical to the source image; the original artwork stays inside the source
  publication.
- **Local-cover storage policy: see §18.1 (D20 APPROVED).** No automatic eviction; a
  low-storage write guard; no adaptive total quota in 4.5A.1. Three tiers are kept distinct
  (durable user artwork, local extracted/derived artwork, regenerable grid thumbnails).
- **Regenerable vs durable.** Grid-size bitmaps in the memory LRU are regenerable caches.
  Extracted/derived artwork files are regenerable from the source *only while the source is
  available*, but are **persisted and not automatically evicted** (§18.1). User-selected
  covers are durable user data (they cannot be regenerated).
- Regenerate only when necessary and safe, and only through the attempt rules of §23.3 and
  §23.5: a missing/corrupt file with an available source (queued once per trigger window,
  never per Library display), an extractor-version bump, an actual source-revision change
  (known and comparable, §23.2), or an explicit **Refresh cover**. A source that becomes
  unavailable never erases the cached artwork.
- **Stale linked-source covers.** A Library Source/linked publication may change in place
  (the user replaces the file) or disappear. While the source is unavailable the cached
  cover stays (it may be the only copy). When it returns, the extractor compares the current
  best-effort `sourceRevision` (§23.2); a revision that is known, comparable and different
  re-extracts the LOCAL slot and replaces it only after the new file is fully written and its
  row is committed (§23.5); a
  provider that exposes no reliable `lastModified` (UNKNOWN) never auto-detects the change
  and never triggers costly re-extraction, which is why **Refresh
  cover** (an always-available planned action, 4.5F; the extractor entry point exists in
  4.5A.1) re-extracts on demand. ShelfOS never silently re-identifies the item as another
  publication, never reattaches by filename/size, and never hashes whole publications for
  freshness.
- Resolved metadata is persisted in Room; screens read Room only. **No provider call on
  screen open**; network happens only in the enrichment worker or an explicit user action.
- Offline: the Library is visually complete from cached state; provider failure never blanks
  metadata or covers.
- Unavailable sources keep their cover and metadata (`features/LIBRARY.md` §14).
- Removal: covers are **not** knowledge — a cover row is derived/owned state, not part of
  Portable Knowledge or `annotation_source_snapshot` — and never outlive their item (unlike
  orphan annotations, ADR-0025, whose lifecycle is unchanged and is not cascaded from
  covers). The full removal/race protocol is §23.4. Orphan annotation snapshots fall back to
  the generated cover.
- Invalidation: covers are re-derived only when the item's cover row is missing/invalid, the
  extractor version or source revision changed, or the user asks (Refresh cover, §23.3);
  online data has `resolvedAt` and a refresh-eligibility date (§19), never automatic
  periodic refetch.
- Provider-specific cache restrictions (D1/D2) are enforced by the registry: a provider
  flagged "no persistent cache" can only populate the manual picker for the session.
- Backups/Portable Knowledge: unchanged; no cover binaries or provider data.

### 18.1 Local-cover storage policy (D20 APPROVED for the initial 4.5A.1 policy)

**Administrator-approved initial policy: no automatic eviction of persisted local
publication artwork; storage is bounded per item, not by an adaptive total quota.** The
numeric ceilings below are reviewable constants; the policy shape is approved.

**Superseded (withdrawn, not current policy):** Part 1's flat 512 MiB total quota; Part 2's
adaptive soft quota (`clamp(8% of free space, 256–768 MiB)`), the ~150 MiB low-storage guard,
cost-aware automatic artwork eviction, and the earlier ~1 MiB / ~4 Mpx byte-exact ceiling
and 1600 px default derivative. None of them is implemented or authorized.

Policy:

1. **Per-item bound.** Each stored LOCAL cover is **≈ 512 KiB at most** (§7 rule 1:
   byte-exact copy when small and within limits, otherwise a bounded derivative, default
   ≤ 1200 px, fixed recompression ladder, never recursive). One stored file per item and
   slot; no file sharing between rows (§23.1).
2. **Write guard (low storage).** Before creating a cover file, and before any expensive
   decode/extraction work, verify free space: the free figure minus the **anticipated
   writes** must leave **at least 512 MiB free after the writes**, and a stricter Android
   low-storage condition (the platform's own threshold) always wins. The approved numbers
   are unchanged; this item only fixes what is counted and which API supplies the figure.
   - **Anticipated writes include bounded temporary extraction.** Admission counts the
     final artifact (≤ 512 KiB) **plus the temporary bytes the extraction is known to need**.
     A CBR entry is extracted whole to a temporary file before it is decoded and bounded,
     so the temporary need can far exceed the final 512 KiB. Where the archive's declared
     entry uncompressed size is trustworthy, use it **capped by the existing `ArchivePolicy`
     / `RarExtractionCache.MAX_ENTRY_BYTES` ceilings**; a missing, zero, negative or
     implausible declaration (above those ceilings) is **unknown** and admission falls
     back to a **conservative fixed temporary reserve (proposal: 32 MiB, a reviewable
     constant, not an approved number)**. Source archives are never modified.
   - **API 26+ (proposed):** the free figure is `min(StorageManager.getAllocatableBytes(
     getUuidForPath(filesDir)), StatFs(filesDir).availableBytes)`. `getAllocatableBytes(UUID)`
     and `getUuidForPath(File)` exist only since API 26 (SDK `android-37.0`
     `api-versions.xml`, verified; re-verify at implementation). Allocatable bytes count
     clearable cache, so taking the minimum never lets cache that the platform *could* free
     loosen the guard, while a platform-reported lower figure tightens it.
   - **API 24/25 (`minSdk` 24):** those two methods **do not exist**; use
     `StatFs(filesDir).availableBytes` (`getAvailableBytes()`, API 18) with the same 512 MiB
     reserve. The conservative fallback is a supported, never-loosening path, not a claim
     of platform parity, and no compat dependency is added.
   - `ACTION_DEVICE_STORAGE_LOW/OK` are deprecated since API 26 and are not relied on.
   - **`STORAGE_FULL` discovered during work** (an `ENOSPC`/`IOException` while writing the
     temporary or final file) is an **explained outcome**, not process death: it runs the
     explicit-outcome close of §23.5 step 4 — marker cleared, the temporary file removed,
     `FAILED_TEMPORARY` / `STORAGE_FULL` recorded, **no interruption counted and the attempt
     given back** (the existing `STORAGE_FULL` rule, §23.3), no timer. The existing gate and
     hysteresis (item 4) govern the next admission, so there is no retry loop after
     recovery. No new storage dependency.
3. **If the guard fails:** skip the write; preserve every existing artwork file and row;
   keep showing the generated fallback; record a retryable `FAILED_TEMPORARY` /
   `STORAGE_FULL` (no timer; it consumes **no** attempt: the guard failed before any work, or
   a mid-work `STORAGE_FULL` gives the attempt back, item 2). While
   the guard fails the scheduler admits no new local-cover work app-wide (one process-level
   gate, not a retry per item).
4. **No retry storm on a full device.** `STORAGE_FULL` rows are retried only on a
   **storage-recovery signal** (proposed): a re-check of free space at most **once per
   cold start** (idle, after reconciliation, §23.5) and once per foreground session,
   passing only with hysteresis (≥ 512 MiB + the write + a further 64 MiB), or an explicit
   Refresh cover, or the user opening that item's details (one on-demand initial attempt).
   Never per Library render, never on a timer.
5. **Never delete existing covers to make room.** There is no eviction in 4.5A.1 and no
   adaptive total-cache quota. A deleted or lost cover file is **not** regenerated on every
   Library display: a missing file for a `READY` row is queued at most once per trigger
   window (§23.3) and shows the generated cover meanwhile.
6. **User-selected covers (4.5F) are durable user data, not regenerable cache**, and are
   never touched by any cache control.

Growth expectation (planning arithmetic, not a typical claim): the cover store grows
**linearly with publication count**. At the 512 KiB ceiling, **2,000 covers ≈ 1 GiB is the
worst-case planning bound** (500 covers ≈ 250 MiB). Typical sizes are far smaller and are
to be replaced by measurements on the extraction corpus:

| Per-cover size (assumption) | 500 items | 2,000 items | Notes |
| --- | --- | --- | --- |
| ~150 KB (light/flat art, 1200 px JPEG q90) | ≈ 75 MB | ≈ 300 MB | low end of the typical range |
| ~400 KB (detailed comic/manga page, 1200 px) | ≈ 200 MB | ≈ 800 MB | high end of the typical range |
| 512 KiB (every file at the ceiling) | ≈ 250 MiB | ≈ 1 GiB | **worst-case planning bound** |

Tiers, kept distinct:

1. **User-selected durable artwork** (`USER` slot, 4.5F): durable user data.
2. **Local extracted/derived artwork** (`LOCAL` slot): persisted, not automatically
   evicted; regenerable from the source *only while the source is available*; if the source
   is unavailable the file may be the only copy.
3. **Regenerable grid thumbnails**: memory LRU (§7 rule 7) only; a disk thumbnail cache
   is not part of this policy.

**Future (not in 4.5A.1):** a cover-storage usage readout in Settings; explicit
cache-cleanup controls; an adaptive quota only after real measurements. Any future eviction
requires stable budgets, hysteresis, availability verification and regeneration-cost
safeguards (solid CBR is the worst case, §19.2) before it is proposed.

## 19. Background enrichment lifecycle

States per item (proposed): `PENDING_LOCAL` → `LOCAL_DONE` → `PENDING_ONLINE` →
`MATCHED(tier)` | `NEEDS_REVIEW` | `NO_MATCH` | `FAILED(retryable)` | `SKIPPED(disabled/
offline/category)`.

1. Import commits the item exactly as today (no added latency).
2. Local pass (4.5A.1 covers, 4.5B fields): runs on `AppContainer.backgroundScope`, bounded
   concurrency 1–2 (**CBR: exactly 1, §19.2**), per-file failure isolation, idempotent,
   batched writes (§19.1). Its durable state is the cover row
   plus the durable attempt marker (§23.5): process death is detected at the next startup
   *before* any extraction is scheduled, an interrupted item is retried only with persisted
   backoff and is parked after four attempts or two unexplained interruptions, whichever
   comes first — no job framework needed.
   Existing libraries are backfilled the same way.
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

### 19.1 Backfill and the Library refresh storm (proposed)

Problem (from §6.1): the Library list is one `SELECT * FROM library_item` query with
`@Relation`s. If cover data were joined or related into it, every cover write would re-run
the query, re-map up to 2,000 `LibraryItem`s and rebuild `LibraryUiState` — a 2,000-item
backfill would cause up to 2,000 full re-emissions and recompositions of the grid.

Strategies considered:

- **A. Bounded transactional batches.** Write 25–50 cover rows per Room transaction and
  `yield()`/pause between batches — **for cheap rows only** (EPUB covers, small CBZ
  pages, failure rows). Each **expensive extraction (CBR, PDF render, large derivative)
  commits its own row immediately after it finishes**, never waiting for a batch, so a
  process death never strands extracted files uncommitted behind a large batch. Cuts
  emissions ~25–50× on the cheap path even if covers *were* observed with the list.
  Necessary hygiene, not sufficient alone.
- **B. Independently observed cover state.** A separate small query
  (`SELECT libraryItemId, slot, fileName, storedWidthPx, storedHeightPx, updatedAt FROM
  publication_cover WHERE status = 'READY'`) observed as its own `Flow`, reduced to an
  immutable `Map<libraryItemId, CoverRef>` (slot precedence applied, §23.1; **`CoverRef`
  carries `fileName`**, so a replaced cover changes the map entry *and* the bitmap-cache
  key, §7 rule 7),
  `distinctUntilChanged()` (attempt-marker writes touch the same table but do not change
  the READY map, so they re-run the query yet emit nothing) and then **conflated with a
  latest-value throttle** — emit the latest map at most once per minimum interval (proposed
  ~300 ms, e.g. `sample` or a small leading-plus-trailing `throttleLatest`) — and read by
  the shared renderer. **Not `debounce`:** a debounce restarts its timer on every upstream
  emission, so during a steady backfill that writes more often than the debounce window it
  would emit nothing until the backfill ends and the Library would show generated art
  throughout; a conflated latest-value throttle guarantees a bounded emission rate *and*
  continuous visible progress. The main `LibraryDao.observe()` query and `LibraryRecord`
  are **not touched**, so cover writes do not re-emit the Library list, and Notes keeps its
  own queries (§6.2). Whether only the tiles whose `CoverRef` changed recompose is a
  **measurement target** (Compose recomposition counts at validation), not a claim.

**Recommendation: B, with A applied to the writer.** B is the smallest change that removes
the storm *by construction* (no join, no relation, no new dependency, no Paging) and also
serves Search and Notes from the same map; A bounds the map's own emission rate. Neither
needs WorkManager or Paging.

Backfill rules (proposed): scan `library_item` rows with no LOCAL cover row (absent row =
never attempted; started, interrupted and failed attempts are tracked durably, §23.5, so
process death can never cause an unbounded retry); process **newest/most recently read
first**; commit cheap rows in 25–50-row transactions and each expensive CBR/PDF extraction
immediately on its own (strategy A); keep memory bounded (one
decoded bitmap at a time per worker; never hold the whole library's bytes); user-visible
work wins — the item being opened, the visible grid items and the item being read get
priority over the idle backfill (an on-demand extraction for a visible tile jumps the
queue); the backfill is paused while a reader is active (§19.2) and resumes after.
**Measure at 4.5A.1 validation** with a seeded 2,000-item library: number of
`LibraryDao.observe()` emissions during backfill (target: **0** attributable to cover
writes), number of cover-map emissions (target: ≤ ~1 per throttle interval), recompositions
of unchanged tiles (measurement target: 0, reported rather than assumed), frame times while flinging the grid during backfill
(no regression versus idle), peak heap, and time-to-first-visible-real-cover for the first
screen of tiles.

### 19.2 Solid-CBR extraction cost and scheduling (proposed; no native change)

Facts (verified in `core/files/`): `NativeRarSession` documents that RAR (especially solid)
cannot be randomly seeked — *every* `extractEntry` restarts a fresh archive reader from the
beginning and sequentially skips/decodes to the target entry; `extractEntry` is
`synchronized(lock)` around a blocking JNI call (`nativeExtractEntry`), so **there is no
cancellation hook inside a native extraction** — coroutine cancellation only takes effect
between calls; `RarContainer.extract` only enforces a byte ceiling
(`RarExtractionCache.MAX_ENTRY_BYTES`) via `maxOutputBytes`; extracted pages land in the
shared `RarCacheCoordinator` budget (default 256 MiB / 64 entries under `cacheDir/cbr`,
shared with the reader), and for any source that is not a ShelfOS-managed private copy,
`rarCacheSourceKey` returns `null` so each open uses an ephemeral namespace
(`domain/library/LibraryItem.kt`). A solid archive whose first logical page is physically
late can therefore cost a large part of the archive in CPU and I/O for one cover, while
holding the session lock.

Rules (proposed):

1. **At most one background CBR extraction at a time**, app-wide (a single-permit
   semaphore), separate from the cap for other formats.
2. **Bounded queue; no aggressive full-library CBR backfill.** CBR covers are produced
   **lazily/on demand** (item becomes visible or is opened) and by a **slow idle backfill**
   that pauses (≥ a few seconds, proposed) between extractions.
3. **Reader first.** No new background CBR job starts while a reader (any format) is
   active or the app is in the background; a job already inside native code cannot be
   cancelled and is allowed to finish (bounded by the existing size ceiling), so the
   scheduler never *starts* work it would need to abort.
4. **Reuse reader artwork only when it is exactly the page the cover extractor would
   choose.** For CBZ and CBR the extractor's choice is: a valid ComicInfo `FrontCover` page
   in range wins (`COMICINFO_FRONT_COVER`, `sourceChoice = DECLARED`), otherwise the first
   page of the logical page list (`ARCHIVE_FIRST_PAGE`, `INFERRED`) — §23.1. A page the
   reader has already extracted may be reused instead of extracting it again **only if** its
   logical index is that chosen index **and** it came from the same revision of the same
   source. Otherwise the reader's page is ignored (for example, the reader opened at a
   resume position, or the FrontCover is not page 0). **One shared page-ordering function
   is the single source of truth** for the logical page list: the reader and the cover
   extractor both call it (the natural ordering of `ArchivePolicy.pages` / `RarContainer`),
   so they cannot disagree about which page is "first" or what index the FrontCover names;
   a test asserts both consumers resolve the same ordering on a fixture archive. A reused
   page is stored like any other extraction: `slot = LOCAL`, `localSource`, `sourceRef` and
   `sourceChoice` as the extractor would have written them, and **`representation` records
   the stored bytes**: `ORIGINAL` only if the reader's bytes are stored byte-exact within
   the §7 bounds, `DERIVED` if they were re-encoded or resized to meet them. There is no
   additional row or provenance type for "came from the reader". It is an **optimization,
   only if it is clean**; otherwise skipped.
5. **Restart-safe progress.** The attempt is recorded durably *before* native extraction
   starts (§23.5); a killed extraction leaves an abandoned marker and a `.tmp`, reconciled
   and swept at startup *before* anything is scheduled; four attempts or two unexplained
   interruptions, whichever comes first, park the item; no endless retry and no startup re-execution of interrupted native work
   (§23.3, §23.5).
6. Source archives are never modified; `ArchivePolicy`/`RarContainer` safety limits and
   native boundaries are unchanged; **no native code change is planned**.
7. A CBR cover extracted through the shared coordinator must not evict the reader's pages
   unfairly: the cover job uses an ephemeral namespace and releases its lease immediately.
   Whether to bypass the coordinator for cover-only extraction is an implementation
   question to settle with a measurement (note it; do not assume).

**Evidence required (4.5A.1 validation and 4.5G):** on the **Samsung SM-T580 (2 GB,
API 27)** — solid-archive first-page extraction time (CBR RAR4/RAR5, solid and non-solid,
small and large archives), peak memory, and **reader contention** (open/page-turn latency
in the reader while a background cover extraction runs). See §28/§29.

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
  `tools:node="remove"` lines — a deliberate, reviewed change made **only in the first
  provider slice** (4.5C, 4.5D or 4.5E, whichever first needs HTTP) **after that
  provider's own authorization gate** (§14, §31) and with a manifest/privacy review,
  documented in `DEPENDENCIES.md`/`ARCHITECTURE.md`. It is **not** authorized by 4.5B
  (which may design the network boundary but adds no permission and no HTTP call). No
  analytics, crash reporting or telemetry rides on it.
- BYOK secrets: stored in app-private storage (proposed: Android Keystore-wrapped), never
  in Room exports, logs, backups or crash text. `allowBackup=false` already.
- No provider sees user notes, Shelves, reading progress or file names unless the file name
  is the only title evidence (then only the parsed title tokens are sent).
- Offline path: everything in 4.5A.1/4.5A.2/4.5B works with no network permission at all.

## 22. Failure and rate-limit behavior

| Failure | Behavior |
| --- | --- |
| No network / airplane mode | Online pass skipped; state `SKIPPED(offline)`; no error UI on Library |
| Timeout / 5xx | Retry with persisted backoff (provider schedule defined by the provider slice; local cover extraction uses §23.3's schedule instead) |
| 403 / 429 / documented limit | Provider circuit opens for the advertised or default window; queue pauses; no hammering |
| Malformed JSON / unexpected schema | Candidate discarded; logged locally without user data; item unchanged |
| Oversized / undecodable image | Rejected; prior cover kept |
| Provider disabled by terms change | Registry disables it; cached data kept unless terms require deletion |
| Wrong match reported by user | "Not this publication" rejects the candidate; reverts non-USER fields it supplied |
| Manual search failure | Inline, localized message with Retry; never blocks editing |

Metadata/cover failures are never `PublicationProblem`s and never block opening the item.
This table covers *provider* failures; local cover-extraction failures follow §23.3.

## 23. Proposed Room/data changes — PLANNING ONLY

All shapes are **(proposed)/(open)**; each lands only with its slice and its own review.
Version numbering follows slice order and is not a pre-reservation: **v6 = 4.5A.1** (local
cover artwork), **v7 = 4.5A.2** (cover presentation preferences); any later schema
version (for 4.5B or provider work) is chosen when it is needed and no migration is
reserved. Provider-specific columns (provider id/ref/confidence on covers) are added only
by the slice that first writes them (4.5C+), and only if justified; they are not part of v6.

### 23.1 v5 → v6 (4.5A.1): `publication_cover` — slot model (D21 APPROVED)

Options considered:

| Option | Shape | Verdict |
| --- | --- | --- |
| A. One active-cover row per item (`PRIMARY KEY (libraryItemId)`) | Overwrites the previous evidence whenever a better cover arrives | **Rejected.** "Use file cover" after a user/online choice would need re-extraction; a failed extractor erases a good cover |
| B. One row per item **and source provenance** (EPUB cover, ComicInfo FrontCover, first page, PDF page as separate keyed rows) | Provenance is the key; precedence among local sources | **Rejected (superseded; was the part 1–2 recommendation).** A FrontCover that resolves to page 0 forced two rows sharing one file (reference counting, "distinct fileName" quota sums), and `USER`/`ONLINE` had to be modelled as more provenance values |
| C. One row per item **and slot** (`PRIMARY KEY (libraryItemId, slot)`), provenance stored as columns | One stored file per publication and slot; resolution is a pure function | **Approved (D21).** No file sharing, no reference counting |

Approved shape (column names are implementation details settled in the 4.5A.1 PR and its
Codex review). **One schema, no migration-only constraints (final-QA decision):** the SQL
below is *logical*. The authoritative DDL is the `createSql` Room exports to
`schemas/.../6.json` for the `@Entity`, and the hand-written `MIGRATION_5_6` must execute
**exactly that `createSql` and the exported index statements, character for character**
(Room's own table and index names, quoting and column order). A fresh install (Room creates
the table) and an upgraded database (the migration creates it) therefore have the same
schema. The migration adds **no `CHECK` constraint** that the generated fresh schema does not
have; the allowed values are enforced in Kotlin (below).

```sql
CREATE TABLE publication_cover (
  libraryItemId TEXT NOT NULL,
  slot TEXT NOT NULL,              -- CoverSlot enum name: LOCAL | USER | ONLINE
  status TEXT NOT NULL,            -- CoverStatus: PENDING | READY | FAILED_PERMANENT | FAILED_TEMPORARY
  failureReason TEXT,              -- CoverFailureReason (section 23.3), null when READY
  fileName TEXT,                   -- relative to filesDir/covers/<libraryItemId>/; null until the first READY commit; unique and immutable per extraction (section 7)
  mimeType TEXT,
  representation TEXT,             -- CoverRepresentation: ORIGINAL | DERIVED; null unless READY
  sourceWidthPx INTEGER, sourceHeightPx INTEGER,   -- native pixels of the source artwork
  storedWidthPx INTEGER, storedHeightPx INTEGER,   -- pixels of the stored file
  storedBytes INTEGER,
  localSource TEXT,                -- CoverLocalSource: EPUB_COVER | COMICINFO_FRONT_COVER | ARCHIVE_FIRST_PAGE | PDF_FIRST_PAGE; LOCAL slot only
  sourceRef TEXT,                  -- safe locator: entry name / logical page index / equivalent; never a raw path
  sourceChoice TEXT,               -- CoverSourceChoice: DECLARED | INFERRED (LOCAL slot: declared by the file, or inferred)
  sourceRevision TEXT,             -- BEST-EFFORT revision evidence at extraction time (23.2); not an identity
  extractorVersion INTEGER NOT NULL,        -- version that wrote the current row outcome
  attempts INTEGER NOT NULL DEFAULT 0,      -- attempts consumed in the current trigger window (23.3); Room @ColumnInfo(defaultValue = "0")
  interruptions INTEGER NOT NULL DEFAULT 0, -- unexplained interrupted attempts in the window (23.5)
  attemptStartedAt INTEGER,                 -- non-null = an attempt began and has not been closed (23.5)
  attemptExtractorVersion INTEGER,          -- extractor version of that attempt
  nextEligibleAt INTEGER,                   -- not-before time for the next automatic attempt
  parked INTEGER NOT NULL DEFAULT 0,        -- 1 = no automatic attempts until a trigger (23.3)
  updatedAt INTEGER NOT NULL,
  PRIMARY KEY (libraryItemId, slot),
  FOREIGN KEY (libraryItemId) REFERENCES library_item(id) ON DELETE CASCADE
);
```

Slot rules:

- **4.5A.1 writes only the `LOCAL` slot.** `USER` (4.5F) and `ONLINE` (4.5C+) are future
  slots reserved by the `CoverSlot` enum; the repository refuses to write them in 4.5A.1
  and provider columns arrive only with the slice that first writes them (D16).
- **One stored file per publication and slot.** `LOCAL` provenance is `localSource` +
  `sourceRef` + `sourceChoice`. The extractor picks exactly one source: EPUB OPF cover
  (`EPUB_COVER`, declared); a ComicInfo `FrontCover` page that is in range
  (`COMICINFO_FRONT_COVER`, declared — **including when it resolves to page 0; no second
  row and no second file**); otherwise the first page in the logical page order
  (`ARCHIVE_FIRST_PAGE`, inferred) — also the safe fallback when the declaration is
  invalid or unavailable; a bounded first-page render (`PDF_FIRST_PAGE`, inferred).
- **Resolution (pure function, never an overwrite):** `USER` > confident, eligible `ONLINE`
  (the §17 gate) > `LOCAL` > generated fallback. Only `READY` rows participate. A future
  `Use file cover` (4.5F) simply ignores the `USER`/`ONLINE` rows; nothing is re-extracted.
- No row ever references another row's file; deleting a row deletes its own file; no
  quota arithmetic depends on distinct file names (§18.1 has no quota).
- Lifecycle (§23.4) is preserved: FK `ON DELETE CASCADE` plus an explicit delete in both
  removal transactions; guarded final writes; ORIGINAL/DERIVED distinction; original
  dimensions; `extractorVersion`; source revision evidence; the source is never modified.

**Row before artwork.** A `PENDING` row (no file yet) is inserted at the *start* of an
attempt so the durable attempt marker has somewhere to live (§23.5); absent row = never
attempted. A first-ever extraction therefore has **no previous `READY` row and the `PENDING`
row has no `fileName` until the success commit**. A re-extraction over an existing `READY`
row keeps `READY` and its current file untouched while only the attempt columns change; its
commit then points the row at a **new** unique file (§23.5 step 4).

**Index requirements (verified conventions).** `reading_state` declares an FK on its
primary-key column `itemId` with no extra index, and `bookmark` declares an explicit
`Index("itemId")` (`core/database/LibraryDao.kt`). For `PRIMARY KEY (libraryItemId, slot)`
the leading column is the FK child column, so the primary-key index serves FK checks and
cascading deletes; no separate `Index` is planned. Room's KSP processor warns when an FK
child column is not covered by an index; whether it counts the composite primary key as
covering must be **confirmed by the implementation build** (add `Index("libraryItemId")`
if the warning appears). The cover-map query (§19.1) scans `status = 'READY'` once; the
startup reconciliation scans `attemptStartedAt IS NOT NULL` once (a table scan over at most
a few thousand rows; no index planned).

**Enums and validation instead of `CHECK` (final-QA decision; replaces the earlier plan to
hand-write `CHECK`s and the "does Room accept them" caveat).** Room entities cannot declare
`CHECK` constraints, and a migration-only `CHECK` would make fresh and upgraded databases
differ, so none exists. Instead:

- **Typed Kotlin enums** — `CoverSlot`, `CoverStatus`, `CoverFailureReason`,
  `CoverRepresentation`, `CoverLocalSource`, `CoverSourceChoice` — are persisted as their
  enum **name** in `TEXT` columns through Room type converters (or an explicit mapping in
  the repository); converters never throw.
- **Write validation at the persistence boundary** (the cover repository/DAO, the only
  writer): rejects an unknown enum value, an invalid combination (e.g. `READY` without a
  `fileName`/`representation`; `localSource` or `sourceChoice` on a non-`LOCAL` slot or a
  `LOCAL` slot without them when `READY`; `failureReason` on a `READY` row; any write to
  `USER`/`ONLINE` in 4.5A.1) and negative counters, before SQL runs.
- **Read tolerance:** a row whose stored enum string is unknown (downgrade artifacts,
  manual edits, a future version) is read as *unusable* — it never throws and never crashes
  the Library; it renders the generated cover, is excluded from the cover map and is
  treated as eligible for reassessment on the next trigger.
- **Defaults must match exactly.** Room's migration validation compares column defaults, so
  `@ColumnInfo(defaultValue = "0")` on `attempts`, `interruptions` and `parked` must equal
  the migration's `DEFAULT 0` exactly (it is the same exported `createSql`; a hand-edit that
  differs would fail validation on upgrade).

**D21 (approved):** the slot model above; `PRIMARY KEY (libraryItemId, slot)`; slots
LOCAL/USER/ONLINE with LOCAL-only writes in 4.5A.1; provenance as columns; FK
`ON DELETE CASCADE` plus explicit delete (matching `reading_state`); the extraction status
model of §23.3 and the attempt tracking of §23.5 carried by the same row.

### 23.2 Source revision and availability

**Inspected, not inferred:** `LibraryEntity` (`core/database/LibraryDao.kt`) has
`byteSize: Long?` (captured at import and never refreshed) but **no `lastModified`, no
revision and no hash column**; `LibraryItem.kt` documents that the persisted `byteSize` is
a stale signal and that a refreshed-at-open revision does not exist yet. Import reads only
`OpenableColumns.DISPLAY_NAME`/`SIZE` (`core/files/PublicationFiles.kt`).

Model (proposed): `library_item` is **not** changed. At extraction time, while the source
is open, the extractor reads the *current* size and, **if the provider exposes it**,
`DocumentsContract.Document.COLUMN_LAST_MODIFIED` via the `ContentResolver` query, and
stores the size and the `lastModified` value on the **LOCAL cover row only**
(`sourceRevision`). This is **best-effort evidence, not an authoritative identity or
fingerprint**: not every SAF provider returns a reliable or stable `lastModified` (it may
be absent, zero or a copy time), size can be unchanged by an edit, and a ShelfOS-managed
private copy is immutable by construction. Rules:

- **A zero, absent or unreliable `lastModified` is UNKNOWN.** Revisions are compared only
  when **both** the stored and the current values are known and comparable (same
  provider shape, both positive); size alone never triggers a re-extraction. A SAF
  provider that omits timestamps therefore never causes costly native re-extraction.
- An actual (known and comparable) *different* revision on an available source is a
  trigger (§23.3) for the LOCAL slot; an *identical or unknown* revision never proves the
  file is unchanged — which is why **Refresh cover** exists (§18). No whole-publication
  hashing is used for freshness and nothing hashes or re-reads a publication on every
  Library render. A cheap, non-blocking revision check when a reader opens the item may be
  considered at implementation (it never delays opening and never extracts synchronously).
- An unavailable source never changes anything (§18: cached artwork is kept); a
  `FAILED_TEMPORARY(SOURCE_UNAVAILABLE)` row is retried when the Source reconnects (a
  trigger) or the user taps Refresh cover.
- `extractorVersion` stays in the schema; source availability is read from
  `library_item.available`; nothing here reattaches an item to another file.
- CBR source hashing remains a known limitation carried from Phase 3.
- A future shared revision signal (the one `rarCacheSourceKey` already names as the only
  place a refreshed revision should be introduced) would replace this column's source, not
  its meaning.

### 23.3 Extraction status model and retry schedule (proposed; D21 carries the model)

Permanent and temporary failures never collapse into one permanent `NONE`, and a failure
never erases a usable cover.

- **PERMANENT** (`FAILED_PERMANENT`), written **only where the evidence supports it**:
  `NO_SUITABLE_ARTWORK` (no cover/page qualifies, e.g. an EPUB with no cover declaration or an
  archive with no image), `UNSUPPORTED_REPRESENTATION` (artwork exists in a form this
  extractor/device cannot use, or cannot be stored within the 512 KiB bound after the §7
  ladder), `UNDECODABLE_CONTENT` (the bytes were read completely and repeatedly fail to
  decode — not a truncated read or an out-of-memory). A permanent row is cached so the app
  does not re-scan on every launch, but it is **eligible for reassessment** on: an
  `extractorVersion` upgrade, an *actual* source-revision change (§23.2), or an explicit
  **Refresh cover**; device-dependent `UNSUPPORTED_REPRESENTATION` is also reassessed when
  the OS level changes (proposed).
- **TEMPORARY / RETRYABLE** (`FAILED_TEMPORARY`; the reason is stored): `SOURCE_UNAVAILABLE`
  (source missing/disconnected), `PERMISSION_UNAVAILABLE` (SAF grant or storage permission
  currently lacking — the item may be marked unavailable elsewhere), `INTERRUPTED`
  (an *unexplained* interruption: the attempt marker was found open at startup — this may
  be OS process death, a swipe-away or a native crash, and is **never asserted to be a
  crash**, §23.5), `CANCELLED` (explicit cancellation, e.g. the item was removed; consumes
  no attempt), `IO_ERROR` (transient read/write failure), `STORAGE_FULL` (the §18.1 write
  guard failed; consumes no attempt), `RESOURCE_EXHAUSTED` (decode out-of-memory or other
  low-resource condition), `TIMEOUT` (retryable timeout).
- **Never** persist a permanent `NONE`/`FAILED_PERMANENT` for a temporary failure. A
  transient failure **must not erase an existing usable cover**: a failure on slot S writes
  or updates only S's row, never deletes another slot's `READY` row, and for S itself it
  keeps the previous `READY` file/row (the row references a new file only after that file is
  complete and the row update has committed, §23.5); if S had a `READY` row the failure is recorded as retry metadata
  without clearing it. Coroutine cancellation records no failure.

**Definitions (one vocabulary for every number below).**

- **Attempt** — one start of extraction work for an item's LOCAL slot, recorded durably
  *before* the work (§23.5) and counted in `attempts` from that moment.
- **Trigger window** — the span from a trigger to the next trigger. A trigger resets
  `attempts`, `interruptions`, `parked` and `nextEligibleAt`.
- **Initial attempt** — attempt 1 of a window (no earlier failure to back off from).
- **Automatic retry** — attempts 2–4, each scheduled after a `FAILED_TEMPORARY` outcome
  (including `INTERRUPTED`). **At most 3 automatic retries, so at most 4 attempts per
  trigger window.** **Park rule (one rule, evaluated every time an attempt closes and at
  startup reconciliation):** the row is **parked when `attempts ≥ 4` OR `interruptions ≥ 2`,
  whichever comes first.** If attempt 4 itself ends in a temporary failure *or* is found
  interrupted at startup, the row is parked and **no further backoff is scheduled**.
- **Unexplained interrupted attempt** — an attempt whose marker was still open at the next
  startup. It **consumes one attempt** (the count is taken at attempt start) and increments
  `interruptions`; **two unexplained interruptions park the item immediately**, i.e.
  earlier than the four-attempt cap (worst case: parked after attempt 2), and an interrupted
  attempt 4 is parked by the attempt count (the budget is exhausted).
- **Explicit user-triggered retry** (Refresh cover, 4.5F) — a trigger: one immediate
  initial attempt (still subject to the storage guard and the one-CBR gate) plus a fresh
  window of up to 3 automatic retries.
- **Parked** — `parked = 1`: no automatic attempt, no timer, until a trigger. Parking
  **survives process restart**; a launch neither resets the counters nor is a trigger;
  `nextEligibleAt` is ignored (and cleared) while parked, so **no elapsed timer ever makes a
  parked item eligible** — only a defined trigger un-parks it. A parked item
  keeps its existing `READY` cover or the generated fallback, and the app and Library
  open normally.

**Triggers** (the only events that open a new window): (1) an **extractor-version change**
(requalifies `FAILED_*` and parked rows; whether it also re-extracts `READY` rows is
decided in that bump's change note, default **no**, to avoid a mass re-extraction);
(2) an **actual source-revision change** (known and comparable, §23.2); (3) an explicit
**Refresh cover**; (4) **storage recovery** for `STORAGE_FULL` (§18.1); (5) **source
reconnection / permission restored** for `SOURCE_UNAVAILABLE` / `PERMISSION_UNAVAILABLE`.
**A new app launch is not a trigger and never resets retry history**: `attempts`,
`interruptions`, `parked` and the persisted `nextEligibleAt` survive restarts. Opening an
item's details or showing a tile may start the *initial* attempt of an item that has **no
row yet**; it never un-parks an item or bypasses `nextEligibleAt`.

**Retry schedule (proposed; a not-before time, never a deadline).** Delays are measured
from the end of the failed attempt, ±20% jitter, ignoring attempt run time:

| Attempt | Kind | Delay after previous failure | Jittered delay range | Cumulative (nominal) | Cumulative range |
| --- | --- | --- | --- | --- | --- |
| 1 | initial | none (at the trigger, subject to the scheduler gates) | — | 0 | 0 |
| 2 | automatic retry 1 | ~1 min | 0.8–1.2 min | ~1 min | 0.8–1.2 min |
| 3 | automatic retry 2 | ~4 min | 3.2–4.8 min | ~5 min | 4.0–6.0 min |
| 4 | automatic retry 3 | ~16 min | 12.8–19.2 min | ~21 min | 16.8–25.2 min |
| — | after attempt 4 fails or is interrupted | parked, no timer | — | — | — |

What closes attempt N (the rule above applied; counters after the transition):

| Outcome of attempt N | `attempts` | `interruptions` | Result |
| --- | --- | --- | --- |
| Success | reset 0 | reset 0 | `READY`, no timer |
| Temporary failure, N < 4 and `interruptions` < 2 | N | unchanged | `nextEligibleAt = now + delay(N)` with jitter |
| Temporary failure, **N = 4** | 4 | unchanged | **parked, no timer** |
| Permanent failure (evidence-backed reason) | N | unchanged | `FAILED_PERMANENT`, parked, no timer |
| Unexplained interruption found at startup, N < 4 and new `interruptions` < 2 | N | +1 | backoff `nextEligibleAt`, never eligible at startup |
| Unexplained interruption, **N = 4 or new `interruptions` = 2** | N | +1 | **parked, no timer** |
| `STORAGE_FULL` (guard or mid-work) or explicit cancellation | N − 1 (attempt given back) | unchanged | no timer; `STORAGE_FULL` follows §18.1, cancellation records nothing |

The Part 1 schedule (five attempts with a multi-hour cap) is **withdrawn** and no other
section cites attempt counts or delays. The window therefore spans ≈ 21 minutes of nominal
delay (≈ 17–25 minutes with jitter) plus attempt run time, and may stretch further because
the scheduler postpones work while a reader is active or the app is in the background.

**No retry storm after restart.** `nextEligibleAt` is persisted; a restart honors it
instead of retrying everything; a global concurrency cap (§19, §19.2: one CBR at a time) and a
**per-launch cap** (proposed: at most ~25 temporary-failed rows retried per launch pass,
newest first) apply; a `nextEligibleAt` implausibly far in the future (clock moved
backwards; later than now + 19.2 min, the largest **single** delay, 16 min × 1.2) is clamped
to that value. (25.2 min is the cumulative upper bound of the whole window, not a single
delay, and is not the clamp.) `SOURCE_UNAVAILABLE`/
`PERMISSION_UNAVAILABLE` carry no timer; `STORAGE_FULL` follows §18.1. There is **no endless
automatic retry loop**: the longest automatic sequence is 4 attempts per window, and
interruptions shorten it to 2; a parked item has no pending timer.

A `FAILED_*` row means "generated cover shown" (or the existing cover kept), never a
`PublicationProblem`, and never blocks opening the item (§22).

### 23.4 Deletion lifecycle and the removal race

Covers differ from annotations (ADR-0025 keeps orphan annotations on purpose as user
knowledge); a cover is derived/owned state and must never outlive its item as a dangling row.
Verified facts: `reading_state` and the legacy `bookmark` use `ON DELETE CASCADE` foreign
keys to `library_item` (`LibraryDao.kt`, `ShelfDatabase.kt`), and `removeDeletingKnowledge` /
`removeKeepingKnowledge` still delete `reading_state` explicitly in one `@Transaction`;
`annotation` has no FK by design.

Recommended: **FK `ON DELETE CASCADE` plus an explicit delete in both removal transactions**
(cheap defense in depth, same style as `reading_state`; index coverage per §23.1).
Cover rows are **not knowledge**: they are not part of Portable Knowledge, are never
included in `annotation_source_snapshot`, and removal under *both* knowledge policies
deletes them; orphan annotations and their snapshot keep their separate ADR-0025 lifecycle
and are **never** cascade-deleted by a cover.

Removal protocol (`removeDeletingKnowledge` / `removeKeepingKnowledge` stay the only
paths that delete a `library_item`):

1. In the existing `@Transaction`, delete the cover rows (explicit) and the item (FK as a
   backstop). **Order: rows first, files after commit.** A failed transaction rolls back
   with **no file touched** (nothing to restore).
2. *After commit*, `RoomLibraryRepository.remove` deletes `covers/<id>/` (best-effort, on
   `Dispatchers.IO`, beside the existing `releaseAccess`). A crash/failure here leaves an
   unreferenced directory — harmless and swept (below). The reverse order (delete files,
   then fail the transaction) is rejected because it could leave a live item pointing at
   missing files.
3. Cancel the item's in-flight/queued extraction job at removal (best-effort; the guard
   below does not depend on it). A native CBR extraction already in progress cannot be
   cancelled (§19.2) and finishes harmlessly.
4. **A job started while the item existed does not make its result valid.** The
   attempt-begin write is `INSERT … SELECT … WHERE EXISTS (SELECT 1 FROM library_item WHERE
   id = :id)` (zero rows inserted = no attempt starts). The **final write is a guarded
   `UPDATE` of the attempt's own row, never an insert or upsert**: `UPDATE publication_cover
   SET … WHERE libraryItemId = :id AND slot = 'LOCAL' AND attemptStartedAt = :myAttempt AND
   EXISTS (SELECT 1 FROM library_item WHERE id = :id)`, and **the number of rows updated
   must be exactly 1**. Zero rows (the item and, by cascade, the row are gone, or the attempt
   no longer owns the row) is a **rejection**: no row is created or revived, and the writer
   deletes its temp and new final file(s) and exits. The FK also rejects a late insert
   (`SQLiteConstraintException`). Temp-file cleanup also happens on any
   exception/cancellation (`finally`). This closes the race "extraction completes after
   removal".
5. **Orphan-file sweep (startup; fixed order: reconcile attempt markers → delete orphan
   rows → sweep orphan files → only then enable the scheduler, §23.5):** delete
   `covers/<dir>` with no `library_item`; delete **every file in an item's directory that is
   not referenced** — *referenced* means its name equals the non-null `fileName` of some
   `publication_cover` row of that item (any slot); the sweeper keys on this and on nothing
   else (no age heuristic) — which covers both a replaced cover's old file whose
   post-commit delete never ran and a new file finalized but never committed; delete
   `*.tmp` files (the temporary suffix of §23.5 step 3; at that point no extraction of this
   process can be in flight, so none is a legitimate uncommitted write; the sweeper never
   runs while the scheduler is active); missing regenerable files are queued afterwards, subject to §23.3. **Orphan-row prevention:**
   the FK plus step 4 make orphan rows impossible; a defensive startup `DELETE FROM
   publication_cover WHERE libraryItemId NOT IN (SELECT id FROM library_item)` is a
   one-statement safety net (and enforces that SQLite foreign keys are on, which Room
   enables).
6. **Source unavailable:** never deletes rows or files (§18); the cover keeps showing.
   **Process death:** mid-extraction leaves an abandoned attempt marker and possibly a
   `.tmp`, reconciled and swept at startup (§23.5); mid-removal is atomic per step 1, with
   step 2 repaired by the sweep.
7. A missing file for a `READY` row: the `LOCAL` slot is queued once per trigger window
   (when the source is available; §23.3) and renders as generated meanwhile — never
   re-queued per Library display; a missing `USER` file is marked unavailable without
   deleting its row (4.5F).

Alternative considered: no FK, explicit deletes only (like `annotation`) — rejected, because
it re-creates the orphan class of bug the guarded design avoids.

**Compatibility of the unique-file replacement (checklist; each item holds because the
only files that ever exist are *referenced* files, *temporary* files and *unreferenced new
or old* files).**

- **Startup sweep:** referenced = row `fileName`; everything else, including a replaced
  cover's leftover and an uncommitted new file, is swept after reconciliation (step 5).
- **Deletion cascade:** rows go in the removal transaction; `covers/<id>/` (all files, old
  and new) is deleted after commit.
- **Pending attempt records:** the `PENDING` row of a first extraction has no `fileName`;
  an open marker on a `READY` row never touches its referenced file; reconciliation is a
  database update only (§23.5).
- **Parked failures:** parking changes counters only; a parked `READY` row keeps its file
  and renders; a parked row has no file to sweep or replace.
- **Migration (v5 → v6):** the table is new and empty; there are no pre-existing cover
  files, so nothing to rename or reconcile.
- **Orphan annotations:** untouched; they never reference cover files (ADR-0025).
- **Bitmap cache:** keyed by `fileName`, so a replaced cover is a new key and the previous
  entry simply ages out of the LRU (§7 rule 7, §19.1).
- **Source availability and revision:** an unavailable source changes nothing; unreliable
  or zero timestamps are UNKNOWN and size alone is not identity (§23.2); no full-file hash
  per Library render; user reading has priority (§19.2); existing artwork survives
  temporary source unavailability.

### 23.5 Restart-safe extraction and durable attempt tracking (proposed; closes the native-crash loop)

**Problem.** Without a durable "started" record, native CBR extraction or decoding that
kills the process leaves the item looking *pending* (no row, or an old failure), so every
launch would retry it and kill the process again — an uncounted crash loop. The attempt
must be recorded durably **before** any expensive work.

**Where the marker lives (compared; recommended: columns on the LOCAL row).**

| Option | For | Against |
| --- | --- | --- |
| **A. Attempt columns on the `LOCAL` `publication_cover` row** (`attemptStartedAt`, `attemptExtractorVersion`, `attempts`, `interruptions`, `nextEligibleAt`, `parked`; a `PENDING` row is inserted at attempt start when none exists) | One store; one transaction commits the marker; same FK cascade removes it with the item; counters already needed by §23.3; no second table; reconciliation is one set-based `UPDATE` | The row must exist before the artwork does (hence `PENDING`); a replacement attempt over a `READY` row must keep the cover displayable (so the marker is orthogonal to `status`) |
| B. A dedicated tiny `cover_attempt` table | Keeps `publication_cover` rows purely "artwork" | A second v6 table, FK, migration test and removal path for the same fields |
| C. A marker file in `covers/<id>/` | No schema | A second durable store with no atomicity with Room, counters need parsing, directly entangled with the orphan sweeper, easy to leak/forget |

**Recommendation: A.** Room (SQLite WAL) returns from a committed transaction only after
the commit, so the marker survives process death (OS kill, crash, swipe-away); only a power
loss can lose the most recent WAL frames, which at worst forgets one attempt.

**Lifecycle per attempt (LOCAL slot).**

1. **Admission** (scheduler): the item has no row, or `nextEligibleAt ≤ now` and `parked =
   0`, no marker is open in this process, the §18.1 storage guard passes, reader-first and
   the one-CBR gates allow it.
2. **Attempt-begin commit — synchronous, before any native archive extraction, expensive
   decoding or conversion/re-encoding.** One short guarded transaction (§23.4 step 4
   ownership check): insert a `PENDING` row if absent; set `attemptStartedAt = now`,
   `attemptExtractorVersion = current`, `attempts = attempts + 1`. Work starts only after
   the commit returns.
3. **Work** into a bounded temporary file `covers/<id>/<attemptToken>.tmp` — never into a
   final name. The existing `READY` row and its file stay **completely unchanged** and keep
   rendering, so a replacement never blanks or alters a displayed cover. The temporary file
   is validated (≤ 512 KiB, header/decode check, known MIME) before it is finalized.
4. **Normal full outcome.**
   **Success, strictly in this order** (the database is updated *before* the old file is
   deleted, and no file is ever overwritten): (a) the old `READY` file is untouched; (b) the
   validated temporary file is **finalized atomically under a new unique name**
   `local-<token>.<ext>` (§7 rule 1; a rename of the temp inside the same directory, never
   onto an existing file — if the name exists, draw another token); (c) **one guarded Room
   transaction** reads the row's current `fileName` as `oldFileName`, then runs the guarded
   `UPDATE` of §23.4 step 4 (`WHERE libraryItemId = ? AND slot = 'LOCAL' AND attemptStartedAt
   = ? AND` item-exists guard; **rows updated must be exactly 1**) writing `READY`, the new
   `fileName`, provenance, dimensions and sizes, **clearing `attemptStartedAt`** and
   resetting `attempts` and `interruptions` to 0; (d) **only after that transaction has
   committed**, best-effort delete `oldFileName` (if the delete fails or the process dies it
   is an unreferenced file that the startup sweep removes).
   **Failure cases of the replacement:**
   - *Room update throws or updates ≠ 1 row:* the old `READY` row and its artwork stay as
     they were and the Library cover is not damaged; the new, unreferenced file (and any
     temp) is deleted in `finally`. A thrown error closes the attempt as an explained
     `IO_ERROR` through a separate small transaction; if even that cannot run the marker
     stays open and step 6 reconciles it.
   - *The item was removed mid-extraction:* zero rows updated = rejection (§23.4 step 4);
     no cover row is created or kept; the new file is deleted; nothing references it.
   - *Process dies between finalize and the Room commit:* the old `READY` row is still the
     reference (still correct, old artwork intact); the marker is open, so step 6 records
     an unexplained interruption and persistent attempt accounting still applies; the new
     file is **unreferenced**, and the sweep (step 7) deletes it. Nothing is half-visible.
   - *First-ever extraction:* there is no previous `READY` row; the `PENDING` row (created
     at attempt begin) has no `fileName` until this commit, so a failure leaves a
     file-less row and no artifact.

   **Explained failure:** one transaction clears `attemptStartedAt` and records the §23.3
   reason; the §23.3 park rule decides parking (`attempts ≥ 4` or `interruptions ≥ 2` or a
   permanent reason), otherwise `nextEligibleAt` is computed from `attempts`. A
   `STORAGE_FULL` found during work is an explained outcome that gives the attempt back
   (§18.1 item 2, no interruption). Expensive CBR/PDF rows commit **immediately per item**
   (§19.1), not in a batch.
5. **Explicit cancellation** (the item was removed, the scope was cancelled, the user
   asked): handled in `finally` under `NonCancellable`: delete the temp file and any finalized-but-uncommitted new
   file, clear
   `attemptStartedAt`, **give back the attempt** (`attempts − 1`), count no interruption.
   A native CBR extraction cannot be cancelled (§19.2); cancellation takes effect after it
   returns. If the process dies before this cleanup runs, the marker is simply found open
   at the next startup (step 6) — conservative by design.
6. **Startup reconciliation (before anything is scheduled).** Every row with
   `attemptStartedAt IS NOT NULL` is an abandoned attempt: a previous process ended without
   closing it. This is recorded as an **unexplained interruption** — `interruptions + 1`,
   `failureReason = INTERRUPTED` (status `FAILED_TEMPORARY` for a `PENDING` row; a `READY`
   row stays `READY`), marker cleared — and it is **never assumed to prove a native
   crash** (the OS may have killed the process; `ApplicationExitInfo` exists only on
   API 30+ and is not used to decide). If `attempts ≥ 4` or `interruptions ≥ 2` the row is
   **parked** (`parked = 1`, no timer; an interrupted attempt 4 schedules no further
   backoff, §23.3). Otherwise `nextEligibleAt = now + the next §23.3 backoff
   delay` (jittered, at least the first-retry delay), so interrupted native work **cannot
   be eligible at startup**. Reconciliation is a database update only; it **never starts
   or awaits an extraction**, and it touches each publication independently.
7. **Startup order (fixed):** (1) reconcile attempt markers; (2) defensive deletion of
   orphan cover rows (§23.4 step 5); (3) sweep orphan directories, unreferenced files and
   `*.tmp` (§23.4 step 5) — the sweeper never runs concurrently with extraction, and a
   `.tmp` is only ever removed at this point, when no extraction of this process exists, so
   a legitimate uncommitted write cannot be deleted; (4) enable the scheduler after an
   idle delay (proposed: after first content and a few seconds of quiet, reader-first,
   §19.2). No previously interrupted native work runs at startup.
8. **Parked items** are not retried automatically on any launch. A trigger (§23.3 — in
   particular an explicit Refresh cover or an extractor-version / source-revision
   requalification) un-parks them with a fresh window. Parking affects only extraction: the
   app and the Library open normally with the generated or existing cover, and failures are
   isolated per publication.

**Residual risk (stated):** a swipe-away or low-memory kill during extraction counts as an
unexplained interruption, so two such events park a perfectly healthy item until the user
taps Refresh cover. This is the accepted price of a loop that is bounded at two
interruptions without being able to prove a native crash.

**Tests (4.5A.1; also listed in §27).** JVM state-machine tests for the attempt lifecycle
and every transition above; an instrumented **kill-process** test (a fake slow extractor,
`am force-stop` or `Process.killProcess` mid-extraction, then relaunch); a
startup-reconciliation test; a **parked item not retried on the next 3 launches**; an
explicit Refresh that un-parks; a **sweeper-ordering** test (a valid in-flight marker and
temp file survive until reconciliation has run); and the replacement tests of §27.

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

Proposed contents, in whichever version number is next when 4.5B lands (chosen then),
additive only:

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
  migration only if and when a slice needs it — additive `CREATE TABLE` only, exported
  schema JSON committed, no destructive fallback — same style as `MIGRATION_4_5`. Each
  migration ships in its own PR with its own migration test. **`MIGRATION_5_6` executes the
  Room-exported `createSql` and index statements of `6.json` exactly** (§23.1): no
  migration-only `CHECK`, and every `DEFAULT` equal to the entity's
  `@ColumnInfo(defaultValue)`.
- The legacy `bookmark` table stays untouched (its removal is a separate, dedicated
  maintenance migration, not Phase 4.5).
- Instrumented migration tests with the repository's raw-SQLite pattern: create the previous
  version with representative rows (items, reading, preferences, annotations, snapshot,
  legacy bookmarks), migrate, assert every pre-existing row and column is byte-identical
  and the new tables exist and are empty; for v6 also assert, with raw SQL (`sqlite_master`,
  `PRAGMA table_info` / `foreign_key_list` / `index_list`): the `publication_cover` table,
  its **primary key columns in order `(libraryItemId, slot)`**, the **foreign key to
  `library_item(id)` with `ON DELETE CASCADE`**, the expected indices, the column defaults,
  and the **cascade** (delete an item, its cover rows vanish). **Schema equality:** the
  migrated database's `sqlite_master` SQL, `table_info`, `foreign_key_list` and `index_list`
  for `publication_cover` equal both the exported `6.json` `createSql` and a database
  freshly created by Room at v6 (fresh install vs v5→v6 upgrade behave identically). Invalid
  enum strings and combinations are tested at the **application persistence boundary**
  (§23.1), not as SQL `CHECK` failures.
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
  `ArchivePolicy.pages`/`RarContainer` — **a test asserts the reader and the cover extractor
  use the same shared page-ordering function** and that reader-page reuse (§19.2 rule 4)
  happens only for exactly the extractor's chosen page (FrontCover vs first page), recording
  `ORIGINAL` only for byte-exact storage; ISBN-10/13/UPC checksum and normalization;
  filename volume/issue parser corpus (Latin + CJK tokens); cover storage policy (ORIGINAL
  vs DERIVED thresholds, the 512 KiB bound, the fixed recompression ladder and its minimum
  edge, original dimensions recorded, never-upscale, never duplicating a multi-megabyte
  page); extraction status transitions (permanent vs temporary, the single retry schedule,
  no permanent state from a temporary failure); the **attempt-marker state machine**
  (§23.5); slot resolution USER > ONLINE > LOCAL > generated; matcher tiers incl. ambiguity
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
  author, omnibus vs volume, variant covers, Vol 1 vs Vol 10, short-title editions such as
  *Saga*/*Batman*, translated manga editions, provider cover id `-1`) asserting no
  auto-apply and that a correct local cover is kept.
- **Cover lifecycle/refresh tests (4.5A.1):** guarded insert after item removal writes no
  row and leaves no file; removal rolls back without touching files; startup sweep removes
  orphan directories/`.tmp`; temporary failures never write `FAILED_PERMANENT` and never
  clear a `READY` row; the §23.3 schedule (attempts 1–4, delays ~1/4/16 min, ±20% jitter),
  **the park rule (`attempts ≥ 4` or `interruptions ≥ 2`; attempt 4 ending in a temporary
  failure or an interruption parks with no further backoff; a parked item never becomes
  eligible because a timer elapsed; parking survives restart; a relaunch is not a trigger
  and does not reset history; the clock-skew clamp is 19.2 min)** and the
  per-launch retry cap on a virtual clock; batched writes cause **no**
  `LibraryDao.observe()` emission (cover-map `Flow` test) and the throttled map keeps
  emitting under a continuous backfill (a debounce would not); ComicInfo `FrontCover`
  index in/out of range falls back to the first page; a FrontCover that resolves to page 0
  stores **one** LOCAL file and one row; free-space guard (≥ 512 MiB after the write),
  `STORAGE_FULL` with no retry storm and **no eviction ever**; one-CBR-at-a-time scheduler
  and reader-first pause (fake clock/fake session). **Crash-loop tests (§23.5):** JVM
  state-machine tests of every attempt transition (start, success, explained failure,
  explicit cancellation, unexplained interruption, parking at two); an **instrumented
  kill-process test** (`am force-stop` or `Process.killProcess` mid-extraction with a fake
  slow extractor, then relaunch); a **startup-reconciliation test** (abandoned marker →
  `INTERRUPTED`, counter, backoff, no extraction at startup); a **parked item is not
  retried on the next 3 launches**; an **explicit Refresh un-parks**; a **sweeper-ordering
  test** (reconcile → sweep → schedule; a marker or `.tmp` is never swept before
  reconciliation). **Atomic-replacement tests (§23.5 step 4):** a replacement writes a new
  uniquely named file and never overwrites the old one; a **crash between finalize and the
  Room commit** keeps the old `READY` reference and the sweep deletes the unreferenced new
  file, with attempt accounting intact; **Room update failure injection** (throws, and
  returns 0 rows) keeps the old row and artwork and discards the new file; **item removed
  mid-extraction** creates no row and deletes the new file; a **stale bitmap cache after
  replacement** is impossible (the cache key follows `fileName`); the **sweep** removes old
  unreferenced files and `.tmp` without touching referenced ones. **Room schema tests
  (§24):** fresh install; v5→v6 migration; migrated schema equal to the exported `6.json`
  `createSql` and to a fresh Room v6 database; foreign keys, composite primary key
  `(libraryItemId, slot)`, required indices and default values; the repository/DAO rejects
  an unknown enum string or invalid combination, and reading a row with an unknown stored
  enum string degrades safely (generated cover, no crash). **Storage tests (§18.1):**
  admission counts bounded temporary extraction (declared size capped by the archive
  limits; unknown or implausible declaration → the fixed reserve); API 26+ vs API 24/25
  free-space paths; `STORAGE_FULL` during work clears the marker, records
  `FAILED_TEMPORARY/STORAGE_FULL`, counts no interruption and consumes no attempt, with no
  retry loop after recovery.
- **Manga corpus (4.5D/4.5G):** the EN/JP/ES ISBN corpus of §12 with the seven recorded
  outcomes per case; no provider coverage figure is asserted.

## 28. Physical UAT strategy

Per the project's phase-closure policy, physical/manual UAT is accumulated and run once in
**4.5G**, not per slice:

- **Samsung Galaxy Tab A SM-T580** (Android 8.1 / API 27, 2 GB RAM, **no ADB**): owner
  installs the 4.5G APK over the local network (LAN download/sideload), records the APK
  SHA-256, and runs the written checklist: cover extraction on a real library, all shapes ×
  fits, per-category overrides, rotation, offline Library after enrichment, airplane-mode
  import + reading, memory/scroll smoothness with a large library, and **solid-CBR
  evidence (§19.2): first-page extraction time for solid and non-solid RAR4/RAR5 archives,
  peak memory, and reader contention while a background cover extraction runs**, plus grid
  tile decode latency against the §7 rule 7 gate and scrolling during a backfill, judged
  against the provisional criteria of §28.1.
- **Retroid Pocket 5** (Android 13 handheld, controller): controller-only settings, candidate
  picker, Choose from device, focus retention on shape change, short-landscape layout.
- Physical TalkBack and foldable hardware remain evidence gaps unless available.
- Slices 4.5A.1–4.5F use emulator/automated validation only; a slice may request an early
  owner spot-check only for a blocking performance doubt on the 2 GB device.

### 28.1 Samsung background-CBR contention — PROPOSED acceptance criteria

**Provisional acceptance thresholds, not observed results.** The repository holds **no**
SM-T580 cover-extraction or contention measurement (`docs/VALIDATION.md` states that
SM-T580 performance was not measured for Phase 4's Notes scale tests; the only latency
numbers there are emulator numbers on a fast x86 host and are not a judgment of the
SM-T580). The values below are starting points to be replaced by the first real
measurement, then fixed by the administrator.

- **Reader input is never blocked:** with a background CBR cover extraction running, no
  reader tap, swipe or key event is dropped, and no main-thread gap over 100 ms is
  attributable to the extraction (the same "max main-thread gap" metric style Phase 4 used
  on the emulator, `docs/VALIDATION.md`).
- **Page-turn regression bounded (PROVISIONAL):** page-turn latency p95 with extraction
  running is **not worse than the idle baseline by more than 25%**, measured on the same
  archive on the same device.
- **Bounded peak memory (PROVISIONAL):** extraction adds no more than ~48 MiB to the
  baseline peak Java heap and no `OutOfMemoryError`; the cover job releases its lease and
  bitmaps after each item (§19.2 rule 7). Heap/RSS ceiling to be confirmed on the device.
- **No crash and no ANR** during the contention run or the 2,000-item backfill.
- **No automatic concurrent solid-RAR backfill:** at most one CBR extraction at any time
  (§19.2 rule 1); the idle backfill pauses between extractions and stays paused while a
  reader is active.
- **Generated fallback when extraction is too expensive or the source is unavailable:**
  an item whose measured first-page extraction cost exceeds a provisional budget (to be
  set from the first SM-T580 measurement; e.g. several seconds) is restricted to
  on-demand extraction for a visible/opened item instead of the idle backfill, shows the
  generated cover meanwhile, and an unavailable source keeps its cached cover (§18). The
  open-ended native call cannot be cancelled, so the budget is an admission rule, not a
  timeout.

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

- **Scope:** Room v6 with `publication_cover` (§23.1, the **LOCAL/USER/ONLINE slot model**,
  `PRIMARY KEY (libraryItemId, slot)`, FK cascade; **4.5A.1 writes only the LOCAL slot**;
  provenance in `localSource`/`sourceRef`/`sourceChoice`; extraction status model §23.3;
  **one schema for fresh and migrated databases — Kotlin enums plus repository validation,
  no migration-only `CHECK`**, §23.1); EPUB embedded cover (OPF); CBZ/CBR cover extraction (ComicInfo `FrontCover` page if
  declared and in range, else first page in natural order; one file, never two); PDF
  first-page preview (bounded, through the existing PDF open path incl. API 24/25 legacy
  probe); **per-item bounded artwork storage** (§7 rule 1: ≈ 512 KiB per cover, ORIGINAL
  only when small and within limits, else DERIVED ≤ 1200 px with the fixed ladder, original
  dimensions recorded), the **low-storage write guard (≥ 512 MiB free after the write) and
  no automatic eviction** (§18.1); **durable per-publication attempt tracking** recorded
  before native extraction/decoding, reconciled at startup, parking after **four attempts or
  two unexplained interruptions, whichever comes first**, and the single retry schedule (initial attempt + up to 3 automatic
  retries; §23.3, §23.5), with **no startup retry loop**; local cover lifecycle
  (post-commit background extraction, **unique immutable cover files with the Room row
  updated before the old file is deleted**, deletion with item removal,
  **startup reconciliation → orphan sweep → scheduler**, in that order); safe idempotent
  backfill for existing libraries (cheap rows in 25–50-row batches, each expensive CBR/PDF
  extraction committed immediately, bounded concurrency, **one CBR extraction at a time**,
  reader-first scheduling, per-file failure isolation; §19.1–§19.2); the guarded removal
  protocol (§23.4); an independently observed, **distinct and latest-value-throttled**
  cover-reference map so cover writes never re-emit the Library list (§19.1); a **minimal
  shared cover renderer** (artwork | existing generated visual) used by **every consumer
  in the §6 inventory — Library grid, Continue Reading, details pane, cover-expand
  transition, Search results and Notes hub rows (live item → cover, orphan → generated,
  §6.2)** showing real artwork with **Crop to fill in the existing fixed-shape frames**
  (never stretched, §7 rule 11) and a stable loading frame (no generated→real flash, §7
  rule 9); no preference table, no Settings overhaul; details technical line shows cover
  provenance (embedded/first page/generated, ORIGINAL vs DERIVED); removal of the
  duplicated `coverColor`/`coverMotif` from `LibraryItem`.
- **Non-goals:** any network, permission change, provider, Choose from device (4.5F),
  shapes/Crop/Fit/Original/custom ratio and any preference storage (4.5A.2), metadata
  fields beyond covers, per-Shelf/per-item shapes, Series covers.
- **Depends on:** nothing in 4.5 (starts from `main`).
- **Validation:** JVM tests (§27), v5→v6 migration test incl. FK cascade, extraction corpus
  incl. malformed, oversized and huge-comic-page art, removal-race, kill-process attempt
  tracking and parked-item tests (§23.5), atomic-replacement, schema-equality and
  enum-validation tests (§24, §27), storage-guard tests (§18.1, no eviction, bounded
  temporary admission, API 26+ vs 24/25),
  process-death backfill resume without a retry loop, a seeded 2,000-item backfill
  measuring Library-query emissions, cover-map emissions and recompositions (§19.1),
  thumbnail decode latency against the §7 gate, emulator
  phone/tablet/short-landscape, Classic/Dark, keyboard/D-pad focus retention, 2 GB memory
  budget reasoning. Samsung SM-T580 CBR/contention evidence is accumulated for 4.5G
  (§19.2, §28) unless the owner requests an early spot-check.
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
  additive Room version after the merged 4.5A slices (number chosen when needed; §23.7);
  minimal editor for new fields with `USER` provenance; details pane shows new fields.
  **Network boundary *design* is in scope** (the `core.network` interface/contract,
  timeouts/size caps/privacy rules as documentation and fake-backed tests) — **but no
  `INTERNET` permission, no manifest change and no real provider HTTP call**. The first
  permission/HTTP arrives only in the first provider slice (4.5C/4.5D/4.5E) after that
  provider's own authorization gate, with a manifest/privacy review (§21).
  **Depends on:** 4.5A.1 (not 4.5A.2). Provider-specific schema changes may begin in 4.5B
  only if justified.
- **Non-goals:** real providers, `INTERNET`/manifest changes, HTTP calls, BYOK UI,
  candidate UI.
- **Validation:** JVM matcher/parser/precedence suites; coordinator tests with fakes;
  migration test; import latency unchanged.
- **Review:** Codex High.

### 4.5C — Books

- **Scope:** (requires D1, D4, D14, D15) `core.network` helper; manifest `INTERNET`;
  Settings online toggle + disclosure; Open Library provider (ISBN-first, search fallback,
  edition covers by cover id); rate limiting/circuit breaker; automatic VERY_HIGH apply to
  unresolved fields and §17 cover improvement; persisted MEDIUM suggestions; attribution.
  **Authorization gate:** Open Library production approval (D1) must be closed first. Google
  Books is rejected and is not built.
- **Non-goals:** comic/manga-specific providers; manual picker UI (4.5F); Google Books.
- **Validation:** fixtures, fake HTTP, offline/429/timeouts, wrong-match corpus; one
  optional isolated manual live check.
- **Review:** Codex High (network/privacy/terms).

### 4.5D — Manga

- **Scope:** volume parsing hardening; ISBN/edition volume matching through the existing
  provider; volume-specific cover rule (§12); manga query building (series + volume +
  creator + language); specialist provider only if D6 approves one.
- **Non-goals:** series-level cover auto-apply; scanlation sources.
- **Authorization gate:** its provider path (ISBN via Open Library) needs D1; any specialist
  provider needs D6.
- **Validation:** the Manga acceptance matrix A–F and the EN/JP/ES ISBN corpus (§12) with
  outcomes recorded per case (omnibus vs single, Vol 1 vs 10, RTL); wrong-match corpus.
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
  provider/legal checklist re-verified; the **Manga matrix and EN/JP/ES ISBN corpus
  results** (§12); the **Comics online acceptance** evidence; Samsung SM-T580 manual LAN UAT
  including the **solid-CBR first-page extraction time, memory and reader-contention
  evidence** (§19.2) and grid-thumbnail decode latency (§7 rule 7); RP5 controller UAT;
  keyboard; final docs reconciliation (ROADMAP, README, VALIDATION).
- **Review:** final independent Codex High closure review.

## 30. Acceptance criteria

Phase 4.5 is accepted only when **all** hold:

All categories:

- Import and reading work identically with online enrichment disabled, offline, or with every
  provider failing; no import latency regression.
- Source publications are byte-identical before/after (checksummed sample).
- Cover storage is bounded per item (§7, §18, §18.1): ≈ 512 KiB per cover, small supported
  artwork stored byte-exact within limits, larger artwork as a bounded DERIVED copy (default
  ≤ 1200 px) that records original pixel dimensions; a ≥ 512 MiB-after-write free-space
  guard; no automatic eviction; no unbounded duplication of source pages and one stored
  file per publication and slot; no uncounted extraction crash loop (§23.5); covers never
  outlive removed items (§23.4); no permanent `NONE` from a temporary failure (§23.3); a
  cover backfill never re-emits the Library list (§19.1); at most one background CBR
  extraction (§19.2); the source publication byte-identical; display shape is
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
when volume artwork exists; volume mismatches never auto-apply; the Manga matrix A–F (§12)
passes; the recorded EN/JP/ES ISBN corpus (§12) shows no incorrect-edition or
conflicting-volume cover applied automatically and every correct local first-page cover
kept (no provider coverage percentage is a criterion).

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
| D1 | Open Library production approval — status **PROMISING / CONDITIONAL**, not approved (on-device cover caching, per-user lazy lookups, attribution placement, use-case registration/clarification of commercial-app use). Keyless access working is not approval. **4.5A.1/4.5A.2 do not depend on it** | Approve only after registering the use case and a lazy, cached, ≤ 3 req/s identified pattern is documented; cover-id URLs, UA contact email, About-screen credit | 4.5C, 4.5D |
| D2 | Google Books — **REJECTED FOR PHASE 4.5** (application-fee, embedded-credential, caching and presentation restrictions, §14; BYOK does not remove them) unless explicit written Google permission resolves them | Rejected; reopen only with written permission, an owner decision and an ADR. **This replaces the earlier "Google Books fallback" and "BYOK later" wording** | 4.5C |
| D3 | **Resolved by the administrator (remediation); owner-confirmed in part 3 — Books, Comics and Manga are all required, no local-only Comics acceptance escape:** Comics acceptance requires an approved, integrated issue-level online provider path in addition to local extraction (§30). There is no local-only exit; without an approved provider 4.5E/4.5G stay open | Fixed; no decision needed. Preferred provider: Metron with per-user tokens | 4.5E, 4.5G |
| D4 | Contact string in `User-Agent` (project address, never the user's) | A project-owned address or the repository URL | 4.5C |
| D5 | Metron per-user-token provider — **CONDITIONAL**. Prerequisites before approval: (1) the current `metron.cloud` service/API terms reviewed manually (or clarified by Metron) because the commercial-use statement does not replace them; (2) D19 answered; (3) per-user token UX, no bundled account, attribution placement, caching within the documented 20/min and 5,000/day limits agreed. Comic Vine stays rejected | Contact Metron / read the service terms; approve per-user tokens only once (1)-(3) close | 4.5E |
| D6 | Any specialist manga provider in 4.5 | None; keep the slot | 4.5D |
| D7 | May an online cover replace an embedded CBZ/CBR first-page cover automatically? | No by default; only via the manual picker | 4.5C–E |
| D8 | Does confirming a candidate make its fields USER? | No (cover yes) | 4.5F |
| D9 | **Owner-approved (confirmed in part 3):** default 2:3 Crop; Books, Comics and Manga use the global default; Documents Original; shapes Original/2:3/3:4/4:5/1:1/Custom; no Auto option; Custom limited to 1:2…2:1; never stretch | Recorded; no further decision | 4.5A.2 |
| D10 | **Owner-approved (confirmed in part 3):** Original = complete artwork, fixed 2:3 grid slot, bottom-aligned, no crop, Fit control irrelevant/disabled, focus border follows the displayed artwork (clamp 1:2…2:1) | Recorded | 4.5A.2 |
| D11 | **Owner-approved (confirmed in part 3; effective with 4.5A.2):** details pane shows the original artwork fully, real aspect ratio, height-first, no hardcoded 0.68; cover expansion never stretches and does not change the reader-navigation contract; reduced motion avoids animated reflow | Recorded | 4.5A.2 |
| D12 | **APPROVED (bounded PDF preview and storage encoding).** Bounded artwork storage per §7 rule 1: ≈ 512 KiB per cover; ORIGINAL (byte-exact) only when small and within the size and pixel limits, else DERIVED, default longest edge ≈ 1200 px (1600 px only if later evidence justifies it), JPEG quality ≈ 90 start with a fixed bounded ladder, explicit decoded-pixel cap. **PDF first-page preview:** derived, never labelled original artwork, white background, bounded render and encode, API 24+ compatible, original PDF untouched. WebP only if a benchmark justifies it (WebP constants differ by API level, §7) | Recorded; JPEG q90 stays the safe default | 4.5A.1 |
| D13 | WorkManager | Not now; revisit only for closed-app enrichment, with dependency review/ADR | 4.5C |
| D14 | HTTP stack: platform `HttpURLConnection` + `org.json` | Approve | 4.5C |
| D15 | Online enrichment default ON (with disclosure) or OFF | ON with one-time non-blocking disclosure + toggle | 4.5C |
| D16 | Provider columns on `publication_cover` | Not in v6 or v7; the slice that first writes provider artwork (4.5C+) adds them, only if justified. Any post-v7 schema version is chosen when needed; no migration is reserved | 4.5A.1 |
| D17 | Separate `metadata_candidate` table vs one suggestion per item | One suggestion per item unless 4.5F needs more | 4.5B |
| D18 | Is 4.5A split into 4.5A.1 (local artwork, v6) and 4.5A.2 (presentation, v7) as two PRs | **Resolved by the administrator (remediation)**; recorded here | 4.5A.1, 4.5A.2 |
| D19 | **OPEN (undecided).** Metron/CC BY-SA licensing implications, open legal questions (no conclusion drawn): ShareAlike effect on cached data, exports/backups, the open-source repository and the paid theme model; attribution placement; whether any Metron-hosted image may be used | Owner/counsel review before 4.5E; keep provider data and covers out of exports meanwhile | 4.5E |
| D20 | **APPROVED for the initial 4.5A.1 policy.** Local-cover storage: **no automatic eviction** of persisted local publication artwork; storage bounded **per item** (≈ 512 KiB per cover); a low-storage write guard (≥ 512 MiB free after the write, stricter Android conditions win; on failure skip the write, keep existing artwork and the generated fallback, record a retryable `STORAGE_FULL`, no repeated attempts until a storage-recovery signal); never delete covers to make room; no auto-regeneration of lost artwork on every Library display; **no adaptive total-cache quota in 4.5A.1**; worst-case planning bound 2,000 × 512 KiB ≈ 1 GiB (§18.1). The earlier adaptive-quota recommendation is **SUPERSEDED** | Recorded. Future (not now): Settings storage usage, explicit cleanup controls, adaptive quota only after measurements | 4.5A.1 |
| D21 | **APPROVED.** `publication_cover` slot model: `PRIMARY KEY (libraryItemId, slot)`, slots LOCAL/USER/ONLINE (4.5A.1 writes only LOCAL); LOCAL provenance in `localSource` (EPUB_COVER, COMICINFO_FRONT_COVER, ARCHIVE_FIRST_PAGE, PDF_FIRST_PAGE), `sourceRef` and DECLARED/INFERRED `sourceChoice`; one stored file per publication and slot (a FrontCover resolving to page 0 is one file); no file sharing or reference counting; resolution USER > confident eligible ONLINE > LOCAL > generated; FK cascade plus explicit delete; the provenance-as-key design is **SUPERSEDED** (§23.1) | Recorded | 4.5A.1 |

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

### Handoff — 4.5A.1 Local Cover Artwork (branch `phase-4.5/4.5a1-local-cover-artwork`)

Preconditions closed in part 3: **D12, D20 and D21 are approved** (§31); D18 is recorded.
**Included:** the LOCAL/USER/ONLINE slot model with an actual **LOCAL-only** implementation
(§23.1); the **512 KiB per-cover ceiling** and **1200 px default derivative bound** (§7);
the **low-storage write guard (≥ 512 MiB free after the write)** and **no automatic
local-cover eviction** (§18.1); **crash-safe durable attempt tracking** and **parking of
problematic extraction after four attempts or two unexplained interruptions, whichever
first** with **no startup retry
loop** (§23.3, §23.5); **completed startup reconciliation and sweep before any
extraction is scheduled**; **latest-value cover observation** (§19.1); **Crop to fill in the
existing fixed-shape frames** (§7 rule 11); **Room v6 migration and tests** (§24, §27);
**EPUB, CBZ, CBR and PDF local cover extraction**; and **real artwork in Library, Search
and Notes**. **Excluded:** external provider calls, the `INTERNET` permission, Room v7,
cover-shape Settings UI, premium/monetization logic, and the Phase 5 PDF Adapted engine.

### Recommended first implementation prompt (4.5A.1 only)

> Implement **Phase 4.5A.1 — Local Cover Artwork** per
> `docs/PHASE_4_5_IMPLEMENTATION_PLAN.md` §6, §7, §17, §18, §18.1, §19 (incl. §19.1–§19.2),
> §23.1–§23.5 (v6 only), §24, §27, §28.1 and §29, from fresh `main`, branch
> `phase-4.5/4.5a1-local-cover-artwork`. This is an independently reviewable slice; 4.5A.2
> follows only after it merges. **Preconditions (closed):** D12, D20 and D21 are
> administrator-approved in §31; implement exactly those, and stop to ask about anything
> the plan still marks proposed-but-unconfirmed (the retry delays, the recompression
> ladder, the Samsung thresholds and the other (proposed) constants are reviewable).
> **Scope:** Room v6 with `publication_cover` (slot model, `PRIMARY KEY (libraryItemId,
> slot)`, slots LOCAL/USER/ONLINE with **only LOCAL written**, `localSource` +
> `sourceRef` + `sourceChoice`, FK `ON DELETE CASCADE` plus explicit delete in both
> removal transactions, **Kotlin enums with repository/DAO validation on the value sets (no
> migration-only `CHECK`)**, status PENDING/READY/FAILED_PERMANENT/
> FAILED_TEMPORARY with the permanent/temporary reason lists, `extractorVersion`,
> best-effort `sourceRevision` on the cover row only (zero/absent/unreliable
> `lastModified` = UNKNOWN), and the attempt-tracking columns `attemptStartedAt`,
> `attemptExtractorVersion`, `attempts`, `interruptions`, `nextEligibleAt`, `parked`) via a
> hand-written additive `MIGRATION_5_6` (legacy `bookmark` untouched, no `library_item`
> change) and a raw-SQLite migration test asserting the table, primary key, foreign key,
> indices, defaults, schema equality with the exported `6.json` and a fresh Room database,
> enum validation and the cascade; local artwork extraction for **EPUB** (EPUB2
> `meta name="cover"`, EPUB3 `properties="cover-image"`, path-safe through `ArchivePolicy`),
> **CBZ/CBR** (ComicInfo `FrontCover` only when its index is in range of the logical page
> list, else the first page in `ArchivePolicy.pages` / `RarContainer` natural order; **one
> file per publication and slot**, never two) and **PDF** (bounded first-page render
> through the existing PDF open path: default longest edge ≈ 1200 px, total-pixel cap,
> white background, JPEG ≈ q90, `PDF_FIRST_PAGE`/DERIVED); the **per-item bounded storage
> policy** (≈ 512 KiB per cover; ORIGINAL only when small and within limits, otherwise
> DERIVED with the fixed ladder; original pixel dimensions recorded; never duplicate large
> comic pages), the **free-space guard (≥ 512 MiB free after the write; on failure skip,
> keep existing artwork, record retryable `STORAGE_FULL`, no repeated attempts)** and
> **no automatic eviction and no adaptive quota** (§18.1); a bounded temp file, then a new
> uniquely named immutable file, then the row updated in a guarded transaction (updated
> rows must equal 1) **before** the old file is deleted;
> **extraction failure semantics and the single retry schedule** (§23.3: initial attempt +
> up to 3 automatic retries, no permanent state from a temporary failure, no endless retry,
> no retry storm after restart, a relaunch is not a trigger); **crash-safe attempt
> tracking** (§23.5: marker committed *before* native extraction/decoding/conversion;
> startup reconciliation before scheduling; park at `attempts ≥ 4` or `interruptions ≥ 2`;
> reconcile → sweep → schedule; no startup execution of interrupted native work); **safe
> backfill** (post-commit extraction on `AppContainer.backgroundScope`; cheap rows in
> 25–50-row batches, each expensive CBR/PDF extraction committed immediately; an
> independently observed, `distinctUntilChanged` and latest-value-throttled cover-reference
> map so cover writes never re-emit `LibraryDao.observe()`; at most one background CBR
> extraction at a time, reader-first scheduling, no aggressive full-library CBR backfill;
> removal deletes rows in the transaction and files after commit); **consumers:** one
> minimal shared cover renderer (artwork | existing generated visual) used by the Library
> grid, Continue Reading, details pane, cover-expand transition, **Search results**
> (`feature/home/ShelfApp.kt`) and **Notes hub rows** (`feature/notes/NotesScreen.kt`;
> live item → cover, orphan → generated, no Notes query change), showing **Crop to fill in
> the existing fixed-shape frames** (never stretched) with a stable loading frame; remove
> the duplicated `coverColor`/`coverMotif` from `LibraryItem`; decode with `inSampleSize`
> into a bounded `ByteBudgetedLruCache`; a details technical line showing cover provenance;
> source files byte-identical. **Exclude:** external provider calls, the `INTERNET`
> permission or any manifest change, Room v7, cover-shape Settings and per-category
> preferences, the full Crop/Fit/Original/Custom UI (4.5A.2), premium/monetization logic,
> Choose from device, metadata networking, Google Books, Metron integration, provider
> schemas, and any Phase 5 PDF Adapted work; no new dependency.
>
> **Mandatory safeguards (final QA; each must be visibly implemented and tested):**
>
> 1. **Matching Room schemas.** Fresh install and v5→v6 migration produce the same
>    `publication_cover` schema: `MIGRATION_5_6` runs the exported `6.json` `createSql`
>    exactly; **no migration-only `CHECK`**; enums (`CoverSlot`, `CoverStatus`,
>    `CoverFailureReason`, `CoverRepresentation`, `CoverLocalSource`, `CoverSourceChoice`)
>    plus repository/DAO validation; unknown stored enum strings degrade safely;
>    `@ColumnInfo(defaultValue)` equals the migration's `DEFAULT` exactly (§23.1, §24).
> 2. **Unique immutable cover-file identity.** Each successful extraction writes
>    `local-<token>.<ext>` (no source-derived text); never overwrite or rename over an
>    existing file; the bitmap-cache key and the cover map include `fileName` (§7, §19.1).
> 3. **Update the database before deleting the old cover.** Order: keep old READY → bounded
>    temp → finalize under a new name → guarded Room update → only then delete the old file;
>    failures keep the old row and artwork and discard the new file (§23.5).
> 4. **A zero-row guarded write is a failure.** Updated rows must equal 1; item removed
>    mid-extraction creates no row and deletes the new file (§23.4).
> 5. **Park at `attempts ≥ 4` or `interruptions ≥ 2`, whichever first**; attempt 4 ending
>    in a temporary failure or interruption parks with no further backoff; parking survives
>    restart; a launch is not a trigger; the clock-skew clamp is 19.2 min, the largest
>    single delay (§23.3).
> 6. **Correct reader-page reuse.** Reuse a reader page only when it is exactly the page the
>    extractor would choose (valid ComicInfo FrontCover, else the first page), using one
>    shared page-ordering function with a test; store LOCAL provenance and record
>    ORIGINAL or DERIVED from the stored bytes; no extra row (§19.2 rule 4).
> 7. **Bounded CBR temporary-storage admission.** Count temporary extraction (declared size
>    capped by archive limits, else the proposed fixed reserve) plus the 512 KiB artifact
>    against the 512 MiB reserve; `STORAGE_FULL` during work closes the attempt normally
>    (no interruption, no attempt consumed) (§18.1).
> 8. **API-compatible low-storage checks.** API 26+ may use `getAllocatableBytes(
>    getUuidForPath(filesDir))` (taking the minimum with `StatFs`); API 24/25 use
>    `StatFs.availableBytes` with the same reserve and must not claim those APIs (§18.1).
>
> **Validate** with focused
> JVM unit tests (storage policy and ladder, status transitions and the retry schedule, the
> attempt state machine, href resolution, ComicInfo index fallback, first-page selection,
> slot resolution, free-space guard, one-CBR scheduler) and instrumented tests (migration
> incl. cascade, guarded insert after removal, **kill-process mid-extraction with a fake
> slow extractor**, startup reconciliation, parked item not retried on 3 launches, explicit
> refresh un-parks, sweeper ordering, an extraction corpus including malformed, oversized
> and huge comic pages, a seeded 2,000-item backfill with Library-emission counts), plus
> emulator phone/tablet/short-landscape in Classic and Dark. Record evidence in
> `docs/VALIDATION.md`. Commit locally; do not push. Then stop for independent Codex High
> review.


### 4.5A.2 follow-on scope note

After 4.5A.1 merges, **4.5A.2 — Cover Presentation** starts from fresh `main` on branch
`phase-4.5/4.5a2-cover-presentation`: Room v7 `cover_presentation_preference`; shapes
(Original, 2:3, 3:4, 4:5, 1:1, Custom W:H) with Crop/Fit; global plus per-category
preferences; Settings → Library appearance; details-pane and cover-expand behavior;
keyboard/controller/accessibility; EN/ES/PT-BR. It has its own implementation prompt written
then, its own validation, Codex High review, remediation and merge.

## 33. 4.5A.1 implementation record (2026-10-15, awaiting review)

Branch `phase-4.5/4.5a1-local-cover-artwork` from `main` @ `0bf1063`. Evidence:
[VALIDATION](VALIDATION.md) "PHASE 4.5A.1". **Not accepted** until the independent Codex
High review closes.

**Delivered.** Room v6 `publication_cover` (§23.1 columns, `PRIMARY KEY (libraryItemId,
slot)`, FK `ON DELETE CASCADE`, defaults `0`, no `CHECK`, no extra index — the KSP build
raised no FK-index warning); `MIGRATION_5_6` runs the exported `6.json` `createSql`
verbatim (`ShelfDatabase.PUBLICATION_COVER_CREATE_SQL`, asserted against the JSON by a JVM
test). Enums and pure policy in `domain/library/Covers.kt`; guarded SQL in
`core/database/CoverDao.kt`; the only writer and its boundary validation in
`data/library/CoverRepository.kt`; scheduling in `data/library/CoverScheduler.kt`; files in
`core/files/CoverFiles.kt`; extraction in `core/reader/CoverExtractor.kt` (reuses
`PublicationFiles.open`, `ArchivePolicy.pages`, `RarContainer`, `EmbeddedMetadataReader`
and `FixedReaderFactory`); one shared renderer `core/designsystem/PublicationCover.kt`
used by the Library grid, Continue Reading, details pane, cover-expand overlay, Search rows
and Notes rows (live and orphan). `LibraryItem.coverColor`/`coverMotif` removed. Both
removal transactions delete cover rows; files are deleted after commit.

**Implementation decisions and deviations (for review):**

1. **Finalize = exists-checked rename, not a hard link.** `Os.link` in app data fails with
   `EACCES` (SELinux) on the API 24 emulator. The new name is a fresh 64-bit token checked
   absent; the scheduler's single worker (one mutex) is the only writer of a cover
   directory, so no file can appear under that name in between. Still never overwrites a
   referenced file in practice; a review may prefer another primitive.
2. **No 25–50-row batching.** Every attempt needs its own begin commit (marker before work,
   §23.5) and close commit, so batching cheap rows would weaken crash safety. The cover map
   throttle bounds emissions instead; measured: 2,000 covers → 1 Library emission and ~64
   cover-map emissions (§19.1 targets met).
3. **`SOURCE_UNAVAILABLE` / `PERMISSION_UNAVAILABLE` give the attempt back** (like
   `STORAGE_FULL`): they are detected when the source is opened, before extraction, and
   must not park an item. With no timer, these rows (and `STORAGE_FULL`) are reassessed
   **once per cold start**, within the per-launch cap of 25; `STORAGE_FULL` additionally on
   the foreground-return recovery signal with the 64 MiB hysteresis.
4. **A failure over a READY row records only counters/backoff** (`failureReason` stays null;
   READY never carries a failure reason, §23.1 validation).
5. **CBR uses the reader's cache key** (`rarCacheSourceKey`): a page the reader extracted
   from the same immutable private copy is reused only if it is the exact chosen physical
   page (shared `RarContainer` index); linked sources get an ephemeral namespace (no reuse).
   The lease is released right after the copy. Coordinator contention is still a 4.5G
   measurement (§19.2 rule 7).
6. **ComicInfo `Image` is 0-based** (unverified convention, §13); out-of-range falls back to
   the first logical page.
7. **Triggers implemented in 4.5A.1:** a READY file the renderer cannot read (re-extracted at
   most once per process) and `CoverScheduler.refresh` (the Refresh-cover entry point; no UI
   until 4.5F). `extractorVersion` is recorded (version 1); `sourceRevision` is recorded
   (`managed`, or provider size/`lastModified` when positive) but **no revision comparison
   runs yet** — see gaps.
8. **Scheduling gates:** foreground = `MainActivity` started; reader active = the fixed-reader
   route (the EPUB reader is its own activity, so `MainActivity` stops). One worker = at most
   one extraction of any format (so at most one CBR); 3 s pause after a CBR; 3 s idle delay
   after reconcile/sweep; while idle the worker polls every 30 s for elapsed retry timers
   (`shortcut:` in code). Visible tiles and details request their item once per process.
9. **Rendering:** Crop to fill in the existing 0.68 frames; a neutral frame while the cover
   map is unknown or a known cover is decoding (no generated→real flash); a cover change
   swaps instantly (no cross-fade in any motion setting). Decoding: off the main thread
   (2 threads), `inSampleSize` to the frame, LRU 24 MiB / 256 entries keyed by
   `id/fileName/sample`.
10. **Reviewable constants used:** 32 MiB unknown-CBR temporary reserve, 64 MiB recovery
    hysteresis, 40 Mpx input cap, 4 Mpx decode working cap, ladder exactly as §7, per-launch
    cap 25, map throttle 300 ms.

**Remaining gaps (not claimed):** no source-revision comparison trigger (Refresh cover in
4.5F covers changed files); no extractor-version requalification pass (only one version
exists); no RAR4 image fixture (the real-native image path is proven on ShelfOS-authored RAR5
fixtures, see the QA remediation below); the kill-process test is simulated (an attempt left open, then a new repository +
scheduler "launch"), not an OS process kill; SM-T580 decode latency, CBR contention and
memory evidence (§7 rule 7, §19.2, §28.1) and RP5 controller checks remain 4.5G; the Library
has no list mode (no list consumer exists); 4.5A.2 owns shapes, Fit/Original, real-ratio
details pane and artwork-bounds cover expansion.

**QA remediation (2026-10-15, two passes; awaiting review).** Production changes stay small:
an `openSession` test seam on `LocalCoverExtractor` (default `::openRarArchiveSession`);
`CoverScheduler.run()` dispatches priority, then waiting, then backfill without dropping
queued work (L1); ComicInfo `Type` matched as whitespace-separated tokens (L2);
`CoverArtwork.trimMemory` from `MainActivity.onTrimMemory`/`onLowMemory` using the SDK's named
levels: RUNNING_LOW halves the decoded-cover cache; RUNNING_CRITICAL and every more severe
level (UI_HIDDEN, BACKGROUND, MODERATE, COMPLETE) empty it (L3, `ByteBudgetedLruCache.trimTo`).
Evidence, including the correction of the first pass's unexecuted claims, is in
[VALIDATION](VALIDATION.md) "TARGETED QA REMEDIATION 2". The real-native image-bearing CBR
gate is met with ShelfOS-authored RAR5 fixtures (non-solid and solid); a RAR4 image fixture
cannot be produced (RAR 7 dropped RAR4 creation).

**Accepted deferrals:** L4 in-place linked-source revision detection → 4.5F; L5
extractor-version requalification implemented and tested before the first version bump; L7
solid-CBR double native pass (ComicInfo, then the page) measured in 4.5G on the SM-T580; L10
cover map/query scale measured in 4.5G; plus the 4.5G items above (old-device latency and
memory, RP5/controller, real process-kill recovery, API 26+ storage at runtime, frame/decode
performance).

## 34. 4.5A.2 implementation record (2026-10-16; merged in PR #45)

Branch `phase-4.5/4.5a2-cover-presentation` from `main` `340074f`. Not accepted or merged;
next gate: independent Codex High review. Evidence: [VALIDATION](VALIDATION.md) "PHASE 4.5A.2".

**Delivered.**
- **Room v7** `cover_presentation_preference` (§23.6) exactly as planned: `scope` TEXT PK
  (GLOBAL/BOOK/COMIC/MANGA/DOCUMENT), `shape`, `customWidth`, `customHeight`, `fit`; no CHECK.
  Additive `MIGRATION_6_7` runs the exported `7.json` createSql
  (`ShelfDatabase.COVER_PRESENTATION_CREATE_SQL`); no row is backfilled (no row = default).
  `ShelfDatabase.ALL_MIGRATIONS` is the one chain used by the app and tests.
- **Model and resolution** (`domain/library/CoverPresentation.kt`): `CoverShape`, `CoverFit`,
  `CoverScope`; `coverPresentation(...)` is the validated constructor (Custom sides 1…20, ratio
  1:2…2:1, otherwise rejected); `CoverPresentations.effective(category) = override ?:
  (Documents → Original, else global)`; "Use default" deletes the override row. Stored rows with
  any unknown or invalid value are ignored, so that scope uses its default. The repository
  writes only presentations the validated constructor accepts. No theme code reads or writes it.
- **One renderer, one geometry** (`core/designsystem/PublicationCover.kt`): `coverLayout`
  (slot, frame, crop, neutral fill) and `artworkRect` (one uniform scale, centered; Original
  stands on the bottom edge). The bitmap is drawn into that rectangle, so the tested math is the
  rendered math. Fit paints the theme's `muted` surface; no blur, gradient or accent. Generated
  covers are drawn natively at the frame (Original → 2:3). Loading frame, fallback, sampled
  decode, LRU and file-name keys are unchanged; a shape change can re-decode the stored file at a
  new sample size but never requests extraction.
- **Original (D10):** fixed 2:3 slot; visible frame = native ratio clamped to 1:2…2:1,
  bottom-aligned; the complete artwork is fitted inside the frame without fill; the tile's
  selection border is applied to the frame (`frame` modifier), not the slot.
- **Details (D11):** whole-artwork mode at the real (clamped) ratio, sized height-first: at most
  40 % of the pane height and 352 dp (the old 240 dp × 0.68 ceiling) and never wider than the pane;
  the hard-coded 0.68 is gone. The provenance line is unchanged.
- **Cover expansion:** the start rectangle is the details cover (the only caller that supplies
  bounds, unchanged); `coverExpandTarget` fits that same ratio into the reader area, so every
  frame keeps one ratio and progress 1 shows the whole artwork. `PendingCoverTransition.proceed`,
  navigation, back and the reduced-motion skip are unchanged.
- **Consumers:** Library grid, Continue Reading, Search, details, transition and Notes (live
  and orphan, using the row's category) all go through the shared renderer with the item's own
  category, so Favorites and other cross-category views resolve per item. Shelves has no covers
  yet (placeholder screen).
- **Settings → Library appearance** (`feature/settings/LibraryAppearanceSettings.kt`): Cover
  shape and Cover fit radio rows (`Role.RadioButton`), Custom width/height steppers (48 dp
  targets, out-of-range steps disabled), fit disabled with an explanation in Original, and four
  category rows (Use default / Category override, then the same controls). Enabling an override
  starts from what the category already shows. EN/ES/PT-BR strings.

**Deviations and notes.**
1. Layout uses the stored image's dimensions (`storedWidthPx`/`storedHeightPx`, already in the
   cover map), not the recorded source dimensions: DERIVED copies preserve the ratio, so the two
   agree within rounding, and the stored ratio is what is drawn.
2. "Progress 0 matches the tile" is met as "matches the cover the transition starts from": in
   the current app only the details cover starts the transition, and it shows the whole artwork.
3. On cold start the preference flow starts at the defaults until Room's first emission, so a
   non-default shape can relayout once in the first frames (L-5, deferred to 4.5G below).
4. There is no in-app reduce-motion setting; the system setting is honored as before. Shape
   changes are instant (no animated reflow).
5. Running-app responsiveness (rotation, tablet, short landscape), Classic/Dark comparison,
   TalkBack, SM-T580 performance and RP5 controller UAT are 4.5G items; Compose window-size tests
   cover the details pane here.

**Low-finding polish (2026-10-16, after Codex High PASS WITH NON-BLOCKING FINDINGS; still under
review).**
- **L-1 stepper focus:** a step that would leave the bounds no longer disables the button (Compose
  dropped D-pad focus); it stays focusable, activation is a no-op, and it is exposed with
  disabled semantics. 48 dp targets kept; no invalid write is possible.
- **L-2 Original focus (D10, resolved, not weakened):** `shelfAction` gains `focusIndicator`
  (default true, so every other screen is unchanged). The Library tile turns its outer ring off
  and draws focus (2 dp) and selection (1 dp) rings on the cover's visible frame, which in
  Original is the artwork, not the empty slot. The whole tile stays the focus and hit target with
  the same semantics. In shaped modes the frame is the whole cover, so the ring now outlines the
  cover rather than cover plus title.
- **L-3 semantics:** category rows announce Expanded/Collapsed with expand/collapse actions and
  are no longer reported as selected; their summary is a polite live region; the "+"/"−" glyphs
  are hidden from accessibility; the stepper value is read as "Width: 3" in a polite live region;
  unavailable steps are disabled.
- **L-4:** a real `LazyVerticalGrid` test (VALIDATION).
- **L-5 deferred to 4.5G (administrator):** on the SM-T580, measure whether the one-time cold-start
  shape relayout is visibly distracting; implement the minimal loading-state fix only if it is.
- **Locales:** ES/PT-BR "Category override" no longer shares "Ajuste" with Cover fit:
  "Configuración propia de la categoría" / "Configuração própria da categoria".

## 35. 4.5B implementation record (2026-10-16, implemented / under review)

Branch `phase-4.5/4.5b-metadata-core` from `main` `1d949be` (4.5A.1 and 4.5A.2 merged). Not
accepted or merged; next gate: independent Codex High implementation QA. Evidence:
[VALIDATION](VALIDATION.md) "PHASE 4.5B". Fully offline: no `INTERNET` permission, no manifest
change, no HTTP code, no provider, no new dependency.

**Room v8 — `publication_metadata` (§23.7).** `libraryItemId` PK with FK cascade to
`library_item`; nullable `subtitle`, `description`, `publisher`, `publishedDate`, `language`,
`seriesName`, `volume`, `issue`, `identifiersJson`, `creditsJson`, `provenanceJson`;
`localAnalysisVersion INTEGER NOT NULL`, `updatedAt INTEGER NOT NULL`. Additive `MIGRATION_7_8`
runs the exported `8.json` createSql (`ShelfDatabase.PUBLICATION_METADATA_CREATE_SQL`) and is in
`ALL_MIGRATIONS`; the migration never opens a publication. Title/creator stay on
`library_item` with their existing `titleOrigin`/`creatorOrigin` strings (`user`, `embedded`,
`filename`, `unknown` read as before; external origins add `exact_identifier_match`,
`high_confidence_external_match`, `inferred_external_match`). Both removal policies delete the
row explicitly (the FK is a backstop); every write runs in one transaction that first checks
the item exists, so a late result never resurrects a removed publication.

**Schema decisions.** (1) One added column versus §23.7: `creditsJson` (ComicInfo creator
roles), because the details pane must show credits and there was no other place for them.
(2) `enrichment_state` and `metadata_candidate` are **not** created: the 4.5B coordinator keeps
no durable state (no production provider, no candidate UI); they arrive with the slice that
needs them (4.5C+/4.5F, D17). (3) JSON columns are small arrays of string arrays written and
read by `domain.metadata.MetadataCodec` (no JSON dependency); every value is re-validated on
read and anything unknown degrades to "unknown"/absent.

**Provenance (§9, §16)** — `domain/metadata/PublicationMetadata.kt`. `MetadataOrigin` (USER >
EMBEDDED > EXACT_IDENTIFIER_MATCH > HIGH_CONFIDENCE_EXTERNAL_MATCH > INFERRED_EXTERNAL_MATCH >
FILENAME_INFERENCE > UNKNOWN), `FieldProvenance(origin, providerId?, providerRef?, confidence?,
resolvedAt?)`, `MatchConfidence` (VERY_HIGH, MEDIUM, LOW). One rule, `canReplace` /
`resolveMetadata`, decides every write: USER replaces anything and is never replaced
automatically (a user-cleared field stays cleared); a blank proposal never deletes; external
values fill only empty/filename/unknown fields (or refresh a weaker-or-equal external one) and
never replace EMBEDDED; a title needs a VERY_HIGH external match; otherwise a stronger-or-equal
origin replaces. Manual edits mark only the fields changed relative to what the editor opened
with (`userEdits`), so a value written by analysis while the editor was open is not overwritten.
A confirmed external value stays external, never USER.

**Identifiers** — `IdentifierDetector`: ISBN-13 (978/979, checksum), ISBN-10 (checksum, `X`,
only with an ISBN label, `urn:isbn:` or an EPUB ISBN scheme), hyphen/space/Unicode-dash
normalization, ISBN-10 ≡ ISBN-13 comparison; UPC-A with checksum and an optional 2/5-digit
supplement; ComicInfo GTIN (UPC-A, EAN-13, a Bookland EAN reported as ISBN-13). Years, page
counts, issue numbers and file-name digits never qualify.

**Embedded extraction (extended, not replaced)** — `EmbeddedMetadataReader`. EPUB: identifiers
(`opf:scheme`, EPUB3 `identifier-type` incl. ONIX 02/15), subtitle (`title-type`), publisher,
date, language, description (markup stripped, ≤ 4,000 chars), series/position (EPUB3
`belongs-to-collection` or calibre). ComicInfo: Series, Number, Volume, Year/Month/Day,
Publisher, Summary, LanguageISO, GTIN and the credit roles Writer, Penciller, Inker, Colorist,
Letterer, CoverArtist, Editor, Translator (comma-separated). ComicInfo's `-1` "unknown" numbers,
invalid dates/languages and malformed XML are dropped safely; entity-bearing XML is still
refused. Title/creator/FrontCover/manga direction behave as before. PDFs declare nothing ShelfOS
reads (no OCR, no text reconstruction). Sources are never modified.

**File-name inference** — `FilenameInference`, category-aware (corrected in the QA remediation
below): explicit volume words `Vol`, `Vol.`, `Volume`, `Tome`, `Tomo`, `Band`, `巻`/`第N巻`
(full-width digits) for Books, Comics and Manga; the bare `v03` form for Comics and Manga only;
issue tokens `#12`, `Issue`, `No.`, `nº` for Comics only; `(of 12)` ignored; years in
parentheses; a zero-padded trailing number is an issue for Comics and a volume for Manga, never
for Books; Documents keep only the cleaned name. Results are FILENAME_INFERENCE
candidates; titles are never transliterated or rewritten. **Deviation:** the displayed title
keeps the import behaviour (the file name without its extension); the cleaned title is used only
for matching queries. Creator inference is not attempted (bracketed names are usually groups).
**Portability:** patterns use explicit `\p{Nd}`/`\p{Z}` and escaped brackets, because Android's
ICU regex rejects Java's `(?U)` flag and unescaped `}` — found by the on-device test.

**Provider contract and matcher** — `domain/metadata/Matching.kt`. One `MetadataProvider`
(`id`, `categories`, `capabilities`, `lookup(identifier)`, `search(query)`), `MetadataQuery`
(category, identifiers, title, creator, series, volume, issue, year, language, publisher,
`titleIsInferred`), `MetadataCandidate`, `CoverCandidate`, `MetadataCapability`. Providers
return no confidence and write nothing. `MetadataMatcher` records agreeing/contradicting signals
and decides: VERY_HIGH = exact identifier with no contradiction, or (Comics only) series + issue
plus two of creator/publisher/year; a different volume/issue or category is a hard rejection;
any other contradiction (title, creator, language, publisher, year, edition kind such as
omnibus/collection, a missing volume/issue for sequential art, a different ISBN) caps at MEDIUM;
Manga and Books without an identifier reach at most MEDIUM; Documents need an identifier; title
equality alone is never VERY_HIGH; two disagreeing VERY_HIGH candidates both drop to MEDIUM. A
file-name title may agree but never contradicts. Normalization is NFKC, case-folded,
punctuation-free, with no translation or transliteration. `fetchCover` (§10 sketch) is not
added: no cover is fetched or applied in 4.5B.

**Coordinator** — `MetadataEnrichmentService`: query from local metadata → providers eligible by
category → identifier lookup first, search only if that found nothing safe (Documents are never
searched) → matcher → only an unambiguous VERY_HIGH candidate is applied, through the store's
precedence rule, with EXACT_IDENTIFIER_MATCH or HIGH_CONFIDENCE_EXTERNAL_MATCH provenance.
Outcomes: APPLIED, NEEDS_REVIEW (best MEDIUM, nothing written), NO_MATCH, SKIPPED. One failing
provider never fails the others. Cover candidates are ignored. **No production provider is
registered**; the service is not wired into the app and is exercised with fakes only.

**Local analysis lifecycle** — `LocalMetadataAnalyzer` (`VERSION = 1`). New imports record the
evidence inspection already read (`PreparedImport.embedded`) after the commit, in the app's
background scope; the import never waits (tested). Existing items are backfilled one at a time
through a new `idleWork` hook on the cover scheduler: it runs only when no cover work is due,
under the same foreground/no-reader gates and CBR pause, so it never overlaps a cover extraction
or a reader. Pending = available items without a row at the current version; an unreadable or
missing source is skipped until the next launch (no retry loop, nothing marked); a parser change
bumps `VERSION` and user choices survive re-analysis. Metadata writes do not touch `library_item`
unless title/creator really change, so the Library list is not re-emitted per item. **Known
cost:** an existing CBR gets one extra native ComicInfo read during its backfill (once per
version); new CBR imports need none.

**Manual editing and details.** The existing editor (details → Edit) adds the category's fields:
Books subtitle, series, volume, publisher, published, language, description; Comics series,
issue, volume, publisher, published, language, description; Manga series, volume, publisher,
published, language, description; Documents subtitle, publisher, published, language,
description. Text is only trimmed; an invalid date (`YYYY[-MM[-DD]]`) or language tag blocks
saving with a message. Edit is enabled once the stored metadata has loaded. The details pane shows
the same category fields plus credits (not for Documents) and the description (6 lines);
identifiers appear in the technical details. EN/ES/PT-BR strings. No candidate picker, cover
search, device cover, bulk wizard or per-field revert (4.5F).

**Network boundary — design only (§21; nothing implemented).** The first provider slice (4.5C,
4.5D or 4.5E, after its own authorization gate) adds the `INTERNET` permission with a manifest
and privacy review and one `core.network` helper behind `MetadataProvider`:
- HTTPS only (no cleartext, no redirect to HTTP), no cookies, a `User-Agent` with a project
  contact (D4), gzip accepted; platform `HttpURLConnection` + `org.json` (D14).
- Timeouts ≈ 10 s connect / 15 s read; response caps ≈ 1 MiB for metadata JSON and ≈ 5 MiB for an
  image before decoding; per-provider rate limits and a circuit breaker on 403/429/5xx (§19).
- Requests carry only what the query needs (identifiers, title, creator, series, volume/issue,
  year, language): never file bytes, file names or paths, notes, reading state or account data;
  no provider credentials embedded in the open-source APK.
- Online enrichment never blocks import or reading; failures leave local metadata untouched.
- Provider gates stay as recorded: Open Library conditional (D1), Google Books rejected (D2),
  Metron conditional on its own terms, tokens and artwork-licence decisions (D5, D19).

**Accepted limitations.** No online enrichment or provider; no durable enrichment state or
candidates; no cover candidate handling; title/creator provenance detail beyond the origin
column is stored only for external values; ComicInfo `Volume` is stored as written (a series
start year for many comics, a tankōbon number for manga); RTL/real-library corpora and SM-T580
backfill timing are 4.5G measurements.

**4.5B targeted QA remediation (2026-10-16; Codex High CHANGES REQUIRED: 1 HIGH, 5 LOW; still
under review).**
- **H-1 editor snapshot (HIGH, fixed).** The editor diffed its fields against *live* Room values,
  so a value analysis wrote while it was open (e.g. Publisher) was saved as a false USER clear.
  Now the editable text fields are captured once when Edit is pressed (`captureEditSnapshot`),
  held in `rememberSaveable` keyed by item (survives recomposition and saved-state restoration,
  never reused for another item, never refreshed from Room while editing). Save sends only the
  fields changed relative to that snapshot (`userEdits`) to the existing transactional
  `MetadataStore.apply`/`resolveMetadata` against the current row, so background values the user
  did not touch keep their origin, deliberate clears stay USER, and a removed item is never
  written. One save per editor session (a second tap writes nothing).
- **L-1 file names (fixed).** "The No. 1 Ladies' Detective Agency" and "Python v3" are no longer
  given an issue/volume as Books (see the corrected paragraph above). Earlier text claiming Book
  titles were already protected was inaccurate for these tokens.
- **L-3 bounded traversal (fixed).** The pending query used `NOT IN (:failed)`, which could exceed
  SQLite's 999 bind variables on API 24 and, being outside the per-item boundary, stop metadata and
  cover scheduling for the launch. It is now a keyset traversal over `(addedAt DESC, id)` with a
  fixed parameter count: each item is visited at most once per launch, failures stay pending for
  the next launch, the query sits inside a recoverable `Exception` boundary (never `Throwable`),
  and the cover scheduler isolates idle-work failures so covers keep running.
- **L-4 untrusted provenance (fixed).** A non-blank stored value whose provenance is missing,
  malformed or unknown is never replaced automatically; only an explicit USER edit replaces it.
  The unknown origin is kept as is (nothing invented); an empty field with unknown origin is
  still filled; valid neighbouring fields are unaffected.
- **L-5 strict dates (fixed).** `YYYY`, `YYYY-MM`, `YYYY-MM-DD` must be real calendar dates (leap
  years, month lengths); anything else is rejected (never truncated or shifted), at the written
  precision.
- **L-2 Comics match provenance — DEFERRED, mandatory before 4.5E.** The matcher can count
  file-name-derived series/issue/year toward the Comics corroboration rule for VERY_HIGH. No
  production provider exists, so nothing is exposed today. **Before the first live Comics
  provider:** carry trustworthy evidence provenance into `MetadataQuery`, never let weak
  file-name signals alone produce VERY_HIGH, keep the issue/volume identity checks, and add a
  contradictory Comics test corpus.
