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
split into permanent and temporary (§23.3).

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
     and the details pane), aspect ratio preserved, no crop, no stretch, **JPEG for opaque
     art (quality proposed ~90, the recommended safe default on every API level)**; PNG only
     when the source has alpha and the result stays within the bound. Lossless WebP is
     **not** assumed: `Bitmap.CompressFormat.WEBP_LOSSY`/`WEBP_LOSSLESS` exist only from API
     30 and the legacy `WEBP` constant is lossy-only on API 24–29 (Android SDK knowledge,
     not re-fetched; **verify at implementation**). WebP *decode* is available on every
     supported API level, so lossy WebP output is allowed only if a size/quality benchmark
     justifies it. A DERIVED file that still exceeds a **~1 MiB stored-byte ceiling** is
     re-encoded once at lower quality (never upscaled, never cropped). Subsampled decoding (`inSampleSize`) is chosen from the header
     dimensions *before* allocation, with an independent **total-pixel cap** on the decode
     target so a pathological image (e.g. 20000×20000) is rejected as
     `FAILED_PERMANENT` rather than allocated.
   - A **DERIVED copy is not byte-identical to the source image**, and the plan, UI copy
     and technical details never call it the original. The record keeps the **original
     pixel dimensions** and source provenance (entry name / page index / provider ref) so
     presentation math (`ORIGINAL` shape) uses the true native ratio and the original can be
     re-read from the source when it is available.
   - Provider images are stored as downloaded when within the thresholds, else DERIVED.
     User-selected images follow the same rule and are *durable* (§18).
   - A PDF has no embedded cover image, so its first page is *rendered* once through the
     existing PDF open path into a DERIVED image — bounded first-page render, **longest
     edge ≤ 1600 px, independent total-pixel cap, opaque white background composited
     before encoding** (PDF pages are transparent), **lossy JPEG ~q90 (safe default; WebP
     only if a benchmark justifies it)**, original PDF untouched — and recorded as
     `PDF_FIRST_PAGE` / DERIVED, never called "embedded" or "original". This is one case
     among several DERIVED cases, not the only one.
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
   (reuse `ByteBudgetedLruCache`, `core/reader/ThumbnailLoader.kt`); the stored artwork file
   is untouched. No grid tile ever decodes a source archive page, and no tile is required
   to read a multi-megabyte image: it decodes the stored ≤ 1600 px / ≤ ~1 MiB file with
   sampling. A regenerable small thumbnail, or a second display derivative, is **not
   committed**: the plan does not add a thumbnail table or format until measurement
   justifies it. **Measurement gate (proposed thresholds, to be confirmed on the Samsung
   SM-T580 2 GB at 4.5A.1 validation and 4.5G):** median tile decode ≤ ~40 ms and p95 ≤
   ~120 ms off the main thread for a 1600 px JPEG sampled to tile size; zero decode on the
   main thread; no `OutOfMemoryError` and no dropped-frame regression versus the generated
   covers while flinging a 2,000-item grid; memory LRU budget ≤ ~24 MiB for covers. Only if
   the gate fails is a small (~300 px) regenerable thumbnail written to the regenerable
   tier (§18.1) — as a decision, with the measurements attached.
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
| **Open Library** ([API](https://openlibrary.org/developers/api), [Covers](https://openlibrary.org/dev/docs/api/covers), [Licensing](https://openlibrary.org/developers/licensing), [Search](https://openlibrary.org/dev/docs/api/search)) | Books; editions/works; many commercial manga volumes; some comic TPBs. Edition-level | Yes, S/M/L by cover id/OLID/ISBN | None. Identify via a `User-Agent` naming the app with a contact email; optional use-case registration form | 1 req/s unidentified, 3 req/s identified; covers by ISBN/OCLC/LCCN: 100 per IP per 5 min (403 beyond); cover-id/OLID not rate-limited | API page: "Cache responses whenever possible." Covers page: "do not crawl our cover API"; intended for displaying covers on public sites; `src` URLs to `covers.openlibrary.org` for web pages; bulk via archive.org dumps. Missing cover returns a blank image unless `?default=false` (then 404). The covers page says nothing about cover id `-1` | "Courtesy link back … appreciated" | Not for scraping or bulk metadata download; APIs not meant for high-traffic commercial services (violations may be rate-limited or blocked). IA "does not assert any new copyright"; "existing rights issues" may apply; licensing page silent on covers and commercial use | **PROMISING / CONDITIONAL — production approval pending (D1).** Re-verified from the three official pages 2026-10-15. Open: ShelfOS is a distributed app (one user = one device IP, which fits per-IP limits) but is a commercial-model app (Plus) — ask OL via the registration form; on-device caching is *encouraged* by the API page and sits outside the web-`src` guidance; a lazy per-item lookup, never a full-library burst, is the only compatible backfill. Keyless access working does **not** approve it |
| **Google Books** ([Terms](https://developers.google.com/books/terms), [APIs ToS](https://developers.google.com/terms), [Using API](https://developers.google.com/books/docs/v1/using), [Branding](https://developers.google.com/books/branding)) | Books, editions; some manga | `imageLinks` thumbnails on books.google.com | Docs: public requests "must be accompanied by an identifier" (API key or OAuth). ToS §4(b)(1): "Developer credentials may not be embedded in open source projects" | Quota per key (no figure stated) | APIs ToS §5(e): no permanent copies; no cache "longer than permitted by the cache header" | "Powered by Google" adjacent to results; prominent link to Google Books page; "must not alter results" | Books terms: "You may not charge users any fee for the use of your application" (conflict risk with ShelfOS Plus); cropping may be "altering" | **REJECTED FOR PHASE 4.5** unless explicit written Google permission resolves the restrictions below (D2). Not a fallback, not research-only-at-most, not optional BYOK |
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
- **Local-cover storage budget: see §18.1 (proposed, D20 OPEN).** Three tiers are kept
  distinct (durable user artwork, local extracted/derived artwork, regenerable grid
  thumbnails); the Part 1 figure of 512 MiB is **not** owner-approved and is re-evaluated
  there.
- **Regenerable vs durable.** Grid-size bitmaps in the memory LRU and any future small
  thumbnails are regenerable caches. Extracted/derived artwork files are regenerable from
  the source *only while the source is available*. User-selected covers are durable user data
  (they cannot be regenerated).
- Regenerate only when necessary and safe: a missing/corrupt file with an available source,
  an extractor-version bump, a source-revision change (best-effort evidence, §23.2), or an
  explicit **Refresh cover**. A source that becomes unavailable never erases the cached
  artwork.
- **Stale linked-source covers.** A Library Source/linked publication may change in place
  (the user replaces the file) or disappear. While the source is unavailable the cached
  cover stays (it may be the only copy). When it returns, the extractor compares the current
  best-effort `sourceRevision` (§23.2); a differing revision re-extracts the *local* kinds
  and replaces them only after the new file is fully written; a provider that exposes no
  reliable `lastModified` simply never auto-detects the change, which is why **Refresh
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

### 18.1 Local-cover storage budget — re-evaluation (proposed; D20 OPEN)

Part 1 proposed a flat 512 MiB for regenerable artwork. That figure was an engineering guess
and is **not owner-approved**. Arithmetic with the §7 policy (stored file ≤ 1600 px, with a
byte ceiling of ~1 MiB per stored file: a DERIVED JPEG that exceeds it is re-encoded once at
lower quality; ORIGINAL already ≤ ~1 MiB):

| Per-cover size (assumption) | 500 items | 2,000 items | Notes |
| --- | --- | --- | --- |
| ~150 KB (light/flat art, 1200–1600 px JPEG q90) | ≈ 75 MB | ≈ 300 MB | low end of the typical range |
| ~400 KB (detailed comic/manga page at 1600 px) | ≈ 200 MB | ≈ 800 MB | high end of the typical range |
| 1 MiB (every file at the ceiling) | ≈ 500 MB | ≈ 2.0 GB | theoretical worst case |

Sizes are estimates for planning, to be replaced by measurements on the extraction corpus.
Consequences:

- A flat **512 MiB would be exceeded by a typical 2,000-item library** (≈ 0.3–0.8 GB), so
  the evictor would run constantly and covers would be re-extracted over and over — worst on
  solid CBR (§19.2). Shrinking the quota makes this worse, not better; the real levers are
  the stored size and the policy, not a smaller cap.
- **Recommendation.** (1) Benchmark and prefer the smaller DERIVED size (1200 px, q~85–90)
  if the Library tile and details pane look right: ≈ 100–250 KB typical → 2,000 items ≈
  0.2–0.5 GB. (2) Use an **adaptive soft quota** for *local extracted/derived artwork*:
  `quota = clamp(8% of the filesystem's currently free space, floor 256 MiB, cap 768 MiB)`,
  re-evaluated at launch/sweep, plus a **low-space guard**: stop writing new regenerable
  covers when free space is below ~150 MiB (`STORAGE_FULL` → temporary failure, generated
  cover shown, retried later). Illustrations: a 16 GB tablet (SM-T580) with 5 GB free → 400
  MiB; a 32 GB phone with 15 GB free → cap 768 MiB; a device with 1 GB free → floor 256 MiB
  (≈ 25% of free space, which the low-space guard then keeps from running the device out).
  (3) Eviction is **cost-aware**: least-recently-displayed first, but EPUB/CBZ/PDF rows go
  before CBR rows (re-extraction cost) and never a row whose source is unavailable. (4) The
  quota is reviewable (a named constant plus Settings → Storage display later) and bounded.
- **Tiers, kept distinct:**
  1. **User-selected durable artwork** (`USER_SELECTED`, 4.5F): never evicted, never counted
     against the regenerable quota; shown separately.
  2. **Local extracted/derived artwork** (EPUB/CBZ/CBR/PDF kinds): regenerable from the
     source **while the source is available**; if the source is unavailable the cached file
     may be the only copy and is **not evicted**, even over quota (the quota then reports as
     exceeded rather than deleting).
  3. **Regenerable grid thumbnails**: memory LRU (§7 rule 7) and, only if the measurement
     gate fails, a small disk cache counted in this tier.
- D20 stays OPEN: the owner decides the numbers; the table above is the evidence.

## 19. Background enrichment lifecycle

States per item (proposed): `PENDING_LOCAL` → `LOCAL_DONE` → `PENDING_ONLINE` →
`MATCHED(tier)` | `NEEDS_REVIEW` | `NO_MATCH` | `FAILED(retryable)` | `SKIPPED(disabled/
offline/category)`.

1. Import commits the item exactly as today (no added latency).
2. Local pass (4.5A.1 covers, 4.5B fields): runs on `AppContainer.backgroundScope`, bounded
   concurrency 1–2 (**CBR: exactly 1, §19.2**), per-file failure isolation, idempotent,
   batched writes (§19.1). Its durable state is *derivable*
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

### 19.1 Backfill and the Library refresh storm (proposed)

Problem (from §6.1): the Library list is one `SELECT * FROM library_item` query with
`@Relation`s. If cover data were joined or related into it, every cover write would re-run
the query, re-map up to 2,000 `LibraryItem`s and rebuild `LibraryUiState` — a 2,000-item
backfill would cause up to 2,000 full re-emissions and recompositions of the grid.

Strategies considered:

- **A. Bounded transactional batches.** Write 25–50 cover rows per Room transaction and
  `yield()`/pause between batches. Cuts emissions ~25–50× even if covers *were* observed
  with the list. Necessary hygiene, not sufficient alone.
- **B. Independently observed cover state.** A separate small query
  (`SELECT libraryItemId, kind, fileName, storedWidthPx, storedHeightPx, updatedAt FROM
  publication_cover WHERE status = 'READY'`) observed as its own `Flow`, reduced to an
  immutable `Map<libraryItemId, CoverRef>` (precedence applied, §23.1), conflated/
  `debounce`d (~250–500 ms), and read by the shared renderer. The main
  `LibraryDao.observe()` query and `LibraryRecord` are **not touched**, so cover writes do not
  re-emit the Library list, and Notes keeps its own queries (§6.2). Only tiles whose
  `CoverRef` actually changed recompose (the map is read per item).

**Recommendation: B, with A applied to the writer.** B is the smallest change that removes
the storm *by construction* (no join, no relation, no new dependency, no Paging) and also
serves Search and Notes from the same map; A bounds the map's own emission rate. Neither
needs WorkManager or Paging.

Backfill rules (proposed): scan `library_item` rows with no cover row of the applicable
kinds (derivable state, so process death resumes at next launch); process **newest/most
recently read first**; batch 25–50 rows per transaction; keep memory bounded (one
decoded bitmap at a time per worker; never hold the whole library's bytes); user-visible
work wins — the item being opened, the visible grid items and the item being read get
priority over the idle backfill (an on-demand extraction for a visible tile jumps the
queue); the backfill is paused while a reader is active (§19.2) and resumes after.
**Measure at 4.5A.1 validation** with a seeded 2,000-item library: number of
`LibraryDao.observe()` emissions during backfill (target: **0** attributable to cover
writes), number of cover-map emissions (target: ≤ ~1 per 250 ms window), recompositions
of unchanged tiles (target: 0), frame times while flinging the grid during backfill
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
4. **Reuse reader artwork when safe.** When the user opens a comic, the first page the
   reader has already decoded/extracted may feed the cover store (a DERIVED
   `ARCHIVE_FIRST_PAGE` row) instead of extracting it again — an **optimization, only if it
   is clean** (same page identity, same revision); otherwise skipped.
5. **Restart-safe progress.** State is derivable (no row = pending; §23.5); a killed
   extraction leaves only a `.tmp`, swept at startup; no endless retry (§23.3).
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
| Timeout / 5xx | Retry with backoff (proposed 1 min → 6 h cap, max 5 attempts per refresh) |
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
  sourceRevision TEXT,             -- BEST-EFFORT revision evidence captured at extraction time (23.2); not an identity
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

**Same page as both FrontCover and first page.** When the ComicInfo-declared page *is* the
archive's first page, the same file must not be stored twice. **Recommendation (smallest
approach that keeps provenance and correct precedence):** write the artwork file once under
the `COMICINFO_FRONT_COVER` row and record `ARCHIVE_FIRST_PAGE` as a **second row with the
same `fileName` and `sourceRef`** (a reference, `storedBytes` counted once in the quota —
the quota sums distinct `fileName`s). Deletion of a row deletes the file only when no other
row of the item references it. This keeps both kinds, so a later extractor correction can
still prefer the declared cover over the heuristic, at the cost of one small row. The
simpler alternative — a single `ARCHIVE_COVER` kind whose `sourceRef` says whether the
page was declared or heuristic — halves the rows but cannot represent "declared cover was
wrong, fall back to page 1" without re-extraction. **D21 stays OPEN and unapproved;** the
recommendation above is the planning default.

**Index requirements (verified conventions).** `reading_state` declares an FK on its
primary-key column `itemId` with no extra index, and `bookmark` declares an explicit
`Index("itemId")` (`core/database/LibraryDao.kt`). For `PRIMARY KEY (libraryItemId, kind)`
the leading column is the FK child column, so the primary-key index serves FK checks and
cascading deletes; no separate `Index` is planned. Room's KSP processor warns when an FK
child column is not covered by an index; whether it counts the composite primary key as
covering must be **confirmed by the implementation build** (add `Index("libraryItemId")`
if the warning appears — a one-line change). `kind` is queried only together with the
item, and the cover-map query (§19.1) scans `status = 'READY'` once; no other index is
planned.

**Decisions needed (D21):** accept Option B and the kind list; the same-page reference
rule above; FK `ON DELETE CASCADE` **plus** the explicit delete (recommended, matching
`reading_state`); whether to collapse the two archive kinds (see above).

### 23.2 Source revision and availability

**Inspected, not inferred:** `LibraryEntity` (`core/database/LibraryDao.kt`) has
`byteSize: Long?` (captured at import and never refreshed) but **no `lastModified`, no
revision and no hash column**; `LibraryItem.kt` documents that the persisted `byteSize` is
a stale signal and that a refreshed-at-open revision does not exist yet. Import reads only
`OpenableColumns.DISPLAY_NAME`/`SIZE` (`core/files/PublicationFiles.kt`).

Model (proposed): `library_item` is **not** changed. At extraction time, while the source
is open, the extractor reads the *current* size and, **if the provider exposes it**,
`DocumentsContract.Document.COLUMN_LAST_MODIFIED` via the `ContentResolver` query, and
stores `"<size>:<lastModified or ''>"` on the **cover row only** (`sourceRevision`). This
is **best-effort evidence, not an authoritative identity or fingerprint**: not every SAF
provider returns a reliable or stable `lastModified` (it may be absent, zero or a copy
time), size can be unchanged by an edit, and a ShelfOS-managed private copy is immutable
by construction. Rules:

- A *different* revision on an available source re-triggers extraction of the local kinds;
  an *identical or unknown* revision never proves the file is unchanged — which is why
  **Refresh cover** exists (§18) and why no whole-publication hashing is used for
  freshness.
- An unavailable source never changes anything (§18: cached artwork is kept); a
  `FAILED_TEMPORARY(SOURCE_UNAVAILABLE)` row is retried when the Source reconnects or the
  user taps Refresh cover.
- `extractorVersion` stays in the schema; source availability is read from
  `library_item.available`; nothing here reattaches an item to another file.
- CBR source hashing remains a known limitation carried from Phase 3.
- A future shared revision signal (the one `rarCacheSourceKey` already names as the only
  place a refreshed revision should be introduced) would replace this column's source, not
  its meaning.

### 23.3 Extraction status model (proposed)

Permanent and temporary failures never collapse into one permanent `NONE`, and a failure
never erases a usable cover.

- **PERMANENT** (`FAILED_PERMANENT`), written **only where the evidence supports it**:
  `NO_SUITABLE_ARTWORK` (no cover/page qualifies, e.g. an EPUB with no cover declaration or an
  archive with no image), `UNSUPPORTED_REPRESENTATION` (artwork exists in a form this
  extractor/device cannot use), `UNDECODABLE_CONTENT` (the bytes were read completely and
  repeatedly fail to decode — not a truncated read or an out-of-memory). A permanent row is
  cached so the app does not re-scan on every launch, but it is **eligible for
  reassessment** on: an `extractorVersion` upgrade, an *actual* source-revision change
  (§23.2), or an explicit **Refresh cover**; device-dependent
  `UNSUPPORTED_REPRESENTATION` is also reassessed when the OS level changes (proposed).
- **TEMPORARY / RETRYABLE** (`FAILED_TEMPORARY`; the reason is stored): `SOURCE_UNAVAILABLE`
  (source missing/disconnected), `PERMISSION_UNAVAILABLE` (SAF grant or storage permission
  currently lacking — the item may be marked unavailable elsewhere), `INTERRUPTED`
  (process death or cancellation mid-extraction, detected at startup), `IO_ERROR` (transient
  read/write failure), `STORAGE_FULL` (insufficient free space or over-quota writing, §18.1),
  `RESOURCE_EXHAUSTED` (decode out-of-memory or other low-resource condition),
  `TIMEOUT` (retryable timeout), `EXTRACTOR_CRASH`.
- **Never** persist a permanent `NONE`/`FAILED_PERMANENT` for a temporary failure. A
  transient failure **must not erase an existing usable cover**: a failure on kind K writes
  or updates only K's row, never deletes another kind's `READY` row, and for K itself it
  keeps the previous `READY` file/row (the old file is replaced only after a new one is
  complete, §23.5); if K had a `READY` row the failure is recorded as retry metadata
  without clearing it (or not recorded at all). Coroutine cancellation writes nothing.
- **Retry eligibility.** Only `FAILED_TEMPORARY` rows with `nextEligibleAt ≤ now` (or null,
  for source-driven reasons) and attempts below the cap are retried automatically.
  `SOURCE_UNAVAILABLE`/`PERMISSION_UNAVAILABLE` carry no timer: they are retried when the
  Source reconnects/the item becomes available again. `STORAGE_FULL` is retried when free
  space rises above the guard (§18.1), not on a timer.
- **Backoff evaluation (Part 1 proposal: 1 min, ×4, cap 6 h, max 5 attempts per trigger).**
  Schedule: 1 min → 4 → 16 → 64 → ~4.3 h (cap 6 h), about 5.4 h total. **Kept** as a
  sound default for transient I/O (gentle early, bounded late, no polling), **with
  adjustments (proposed, not an approved contract):** (a) ±20% **jitter** so rows failed
  together do not retry together; (b) a **max of 4 automatic attempts per trigger**
  (fewer, because each attempt for a large CBR is expensive and the item stays readable);
  (c) `nextEligibleAt` is **persisted**, so a restart honors the existing gate instead of
  retrying everything at once; (d) a **global concurrency cap** (§19, §19.2) and a
  **per-launch retry cap** (e.g. at most ~25 temporary-failed rows retried per launch
  pass, newest first) so a process restart after a bad day cannot cause a retry storm;
  (e) after the cap the row is **parked** (stays `FAILED_TEMPORARY`, no timer) until a
  trigger; there is **no endless automatic retry loop**.
- **Triggers that reset attempts/eligibility:** extractor-version bump, a changed source
  revision, source reconnect, an explicit **Refresh cover** (4.5F; also reassesses
  `FAILED_PERMANENT`), and a deliberate user action on the item (opening its details may
  schedule one on-demand attempt per session).
- A `FAILED_*` row means "generated cover shown", never a `PublicationProblem`, and never
  blocks opening the item (§22). `extractorVersion` is kept in the schema.

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
4. **A job started while the item existed does not make its result valid.** The final write
   rechecks ownership *inside a transaction*: `INSERT … SELECT … WHERE EXISTS (SELECT 1
   FROM library_item WHERE id = :id)` (zero rows inserted = rejected), and the FK also
   rejects a late insert (`SQLiteConstraintException`). On rejection the writer deletes its
   temp and final file(s) and exits; it never recreates a row. Temp-file cleanup also
   happens on any exception/cancellation (`finally`). This closes the race "extraction
   completes after removal".
5. **Orphan-file sweep (startup, after reconciliation of sources):** delete `covers/<dir>`
   with no `library_item`; delete files in an item's directory referenced by no row; delete
   every `*.tmp`; only then re-queue missing regenerable files. **Orphan-row prevention:**
   the FK plus step 4 make orphan rows impossible; a defensive startup `DELETE FROM
   publication_cover WHERE libraryItemId NOT IN (SELECT id FROM library_item)` is a
   one-statement safety net (and enforces that SQLite foreign keys are on, which Room
   enables).
6. **Source unavailable:** never deletes rows or files (§18); the cover keeps showing.
   **Process death:** mid-extraction leaves only a `.tmp` (swept) and no row; mid-removal is
   atomic per step 1, with step 2 repaired by the sweep.
7. A missing file for a `READY` row: regenerable kinds are re-queued (when the source is
   available) and render as generated meanwhile; a missing `USER_SELECTED` file is marked
   unavailable without deleting its row (4.5F).

Alternative considered: no FK, explicit deletes only (like `annotation`) — rejected, because
it re-creates the orphan class of bug the guarded design avoids.

### 23.5 Restart-safe extraction

Write the artwork to `covers/<id>/<kind>.<ext>.tmp`, `fsync`/close, atomically rename to the
final name, then write/replace the row last in the guarded transaction (§23.4 step 4;
rows are written in the bounded batches of §19.1 where several items finish together). **No `IN_PROGRESS`
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
  author, omnibus vs volume, variant covers, Vol 1 vs Vol 10, short-title editions such as
  *Saga*/*Batman*, translated manga editions, provider cover id `-1`) asserting no
  auto-apply and that a correct local cover is kept.
- **Cover lifecycle/refresh tests (4.5A.1):** guarded insert after item removal writes no
  row and leaves no file; removal rolls back without touching files; startup sweep removes
  orphan directories/`.tmp`; temporary failures never write `FAILED_PERMANENT` and never
  clear a `READY` row; jittered backoff and the per-launch retry cap on a virtual clock;
  batched writes cause **no** `LibraryDao.observe()` emission (cover-map `Flow` test);
  ComicInfo `FrontCover` index in/out of range falls back to the first page; same-page
  FrontCover/first-page stores one file; quota/low-space guard; one-CBR-at-a-time
  scheduler and reader-first pause (fake clock/fake session).
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
  tile decode latency against the §7 rule 7 gate and scrolling during a backfill.
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
  libraries (batched transactional writes, bounded concurrency, **one CBR extraction at a
  time**, reader-first scheduling, per-file failure isolation; §19.1–§19.2); extraction
  failure semantics (§23.3) and the guarded removal protocol (§23.4); an independently
  observed cover-reference map so cover writes never re-emit the Library list (§19.1);
  a **minimal shared cover renderer** (artwork | existing generated visual) used by **every
  consumer in the §6 inventory — Library grid, Continue Reading, details pane,
  cover-expand transition, Search results and Notes hub rows (live item → cover, orphan →
  generated, §6.2)**; a stable loading frame (no generated→real flash, §7 rule 9); the
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
  tests, storage-budget tests, process-death backfill resume, a seeded 2,000-item backfill
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
- Cover storage is bounded (§7, §18, §18.1): small supported artwork stored byte-exact,
  larger artwork as a bounded DERIVED copy that records original pixel dimensions; no
  unbounded duplication of source pages or of the same page stored twice; covers never
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
| D3 | **Resolved by the administrator (remediation):** Comics acceptance requires an approved, integrated issue-level online provider path in addition to local extraction (§30). There is no local-only exit; without an approved provider 4.5E/4.5G stay open | Fixed; no decision needed. Preferred provider: Metron with per-user tokens | 4.5E, 4.5G |
| D4 | Contact string in `User-Agent` (project address, never the user's) | A project-owned address or the repository URL | 4.5C |
| D5 | Metron per-user-token provider — **CONDITIONAL**. Prerequisites before approval: (1) the current `metron.cloud` service/API terms reviewed manually (or clarified by Metron) because the commercial-use statement does not replace them; (2) D19 answered; (3) per-user token UX, no bundled account, attribution placement, caching within the documented 20/min and 5,000/day limits agreed. Comic Vine stays rejected | Contact Metron / read the service terms; approve per-user tokens only once (1)-(3) close | 4.5E |
| D6 | Any specialist manga provider in 4.5 | None; keep the slot | 4.5D |
| D7 | May an online cover replace an embedded CBZ/CBR first-page cover automatically? | No by default; only via the manual picker | 4.5C–E |
| D8 | Does confirming a candidate make its fields USER? | No (cover yes) | 4.5F |
| D9 | **Owner-approved:** default 2:3 Crop; Books, Comics and Manga use the global default; Documents Original; shapes Original/2:3/3:4/4:5/1:1/Custom; no Auto option; Custom limited to 1:2…2:1; never stretch | Recorded; no further decision | 4.5A.2 |
| D10 | **Owner-approved:** Original = complete artwork, fixed 2:3 grid slot, bottom-aligned, no crop, Fit control irrelevant/disabled, focus border follows the displayed artwork (clamp 1:2…2:1) | Recorded | 4.5A.2 |
| D11 | **Owner-approved:** details pane shows the original artwork fully, real aspect ratio, height-first, no hardcoded 0.68; cover expansion never stretches and does not change the reader-navigation contract; reduced motion avoids animated reflow | Recorded | 4.5A.2 |
| D12 | Bounded artwork storage: ORIGINAL ≤ ~1 MiB and ≤ ~4 Mpx else DERIVED with longest edge ≤ 1600 px (benchmark 1200–1600), JPEG ~q90 (PNG only for alpha within the bound), independent total-pixel cap, ~1 MiB stored-byte ceiling, PDF first page always DERIVED with white background | Approve as planning defaults; WebP only if a benchmark justifies lossy WebP (lossless WebP encode is API 30+, verify at implementation) | 4.5A.1 |
| D13 | WorkManager | Not now; revisit only for closed-app enrichment, with dependency review/ADR | 4.5C |
| D14 | HTTP stack: platform `HttpURLConnection` + `org.json` | Approve | 4.5C |
| D15 | Online enrichment default ON (with disclosure) or OFF | ON with one-time non-blocking disclosure + toggle | 4.5C |
| D16 | Provider columns on `publication_cover` | Not in v6 or v7; the slice that first writes provider artwork (4.5C+) adds them, only if justified. Any post-v7 schema version is chosen when needed; no migration is reserved | 4.5A.1 |
| D17 | Separate `metadata_candidate` table vs one suggestion per item | One suggestion per item unless 4.5F needs more | 4.5B |
| D18 | Is 4.5A split into 4.5A.1 (local artwork, v6) and 4.5A.2 (presentation, v7) as two PRs | **Resolved by the administrator (remediation)**; recorded here | 4.5A.1, 4.5A.2 |
| D19 | Metron/CC BY-SA open legal questions (no conclusion drawn): ShareAlike effect on cached data, exports/backups, the open-source repository and the paid theme model; attribution placement; whether any Metron-hosted image may be used | Owner/counsel review before 4.5E; keep provider data and covers out of exports meanwhile | 4.5E |
| D20 | **OPEN, not approved.** Local-cover storage budget. Part 1's flat 512 MiB is withdrawn as a number to approve: a typical 2,000-item library needs ≈ 0.3–0.8 GB (§18.1) | Adaptive soft quota `clamp(8% of free space, 256 MiB, 768 MiB)` for local extracted artwork, low-space guard (~150 MiB free), cost-aware eviction (CBR last), three tiers distinct; prefer 1200 px if it benchmarks acceptable; no persisted thumbnails unless the §7 rule 7 gate fails | 4.5A.1 |
| D21 | **OPEN, not approved.** `publication_cover` design: Option B (`PRIMARY KEY (libraryItemId, kind)`), kind list, FK cascade plus explicit delete, `COMICINFO_FRONT_COVER`/`ARCHIVE_FIRST_PAGE` separate or merged, extraction status model (§23.3) | Option B; keep both archive kinds with the second row referencing the same file when the declared cover is page 1 (§23.1); confirm the permanent/temporary lists | 4.5A.1 |

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
> `docs/PHASE_4_5_IMPLEMENTATION_PLAN.md` §6, §7, §17, §18, §18.1, §19 (incl. §19.1–§19.2),
> §23.1–§23.5 (v6 only), §24, §27 and §29, from fresh `main`, branch
> `phase-4.5/4.5a1-local-cover-artwork`. This is an independently reviewable slice; 4.5A.2
> follows only after it merges. **Preconditions:** the owner has reviewed D12, D20 and D21
> (they are OPEN in the plan; implement the plan's recommendations only as far as the owner
> confirmed, and stop to ask about the rest). **Scope:** Room v6 with `publication_cover`
> (one row per artwork kind, `PRIMARY KEY (libraryItemId, kind)`, FK `ON DELETE CASCADE`
> plus explicit delete in both removal transactions, status READY/FAILED_PERMANENT/
> FAILED_TEMPORARY with the permanent/temporary reason lists, `extractorVersion`,
> best-effort `sourceRevision` on the cover row only, jittered persisted `nextEligibleAt`)
> via a hand-written additive `MIGRATION_5_6` (legacy `bookmark` untouched, no
> `library_item` change) and a raw-SQLite migration test including the cascade; local
> artwork extraction for **EPUB** (EPUB2 `meta name="cover"`, EPUB3 `properties="cover-image"`,
> path-safe through `ArchivePolicy`), **CBZ/CBR** (ComicInfo `FrontCover` only when its
> index is in range of the logical page list, else the first page in `ArchivePolicy.pages` /
> `RarContainer` natural order; the same page is never stored twice) and **PDF** (bounded
> first-page render through the existing PDF open path: longest edge ≤ 1600 px, total-pixel
> cap, white background, JPEG ~q90, `PDF_FIRST_PAGE`/DERIVED); the **bounded storage
> policy** (ORIGINAL only ≤ ~1 MiB and ≤ ~4 Mpx, otherwise DERIVED; original pixel
> dimensions recorded; stored-byte ceiling; never duplicate large comic pages) and the
> storage budget exactly as the owner confirmed D20 (never evict a user-selected cover or
> the only copy of an unavailable source's cover); temp file then atomic rename and the row
> last in a guarded transaction that re-checks the item still exists; **extraction failure
> semantics** (§23.3: no permanent state from a temporary failure, no endless retry, no
> retry storm after restart, a failure never erases a usable cover); **safe backfill**
> (post-commit extraction on `AppContainer.backgroundScope`; bounded batches of 25–50 rows
> per transaction; an independently observed cover-reference map so cover writes never
> re-emit `LibraryDao.observe()`; at most one background CBR extraction at a time,
> reader-first scheduling, no aggressive full-library CBR backfill; startup reconciliation
> and orphan sweep; removal deletes rows in the transaction and files after commit);
> **consumers:** one minimal shared cover renderer (artwork | existing generated visual)
> used by the Library grid, Continue Reading, details pane, cover-expand transition,
> **Search results** (`feature/home/ShelfApp.kt`) and **Notes hub rows**
> (`feature/notes/NotesScreen.kt`; live item → cover, orphan → generated, no Notes query
> change), with the **existing frame presentation** and a stable loading frame; remove the
> duplicated `coverColor`/`coverMotif` from `LibraryItem`; decode with `inSampleSize` into a
> bounded `ByteBudgetedLruCache`; a details technical line showing cover provenance;
> source files byte-identical. **Exclude:** cover-shape Settings, per-category preferences,
> the full Crop/Fit/Original/Custom UI (4.5A.2), any provider API, the `INTERNET`
> permission or manifest change, metadata networking, Google Books, Metron integration,
> provider schemas, Choose from device, Room v7, and any Phase 5 work; no new dependency.
> **Validate** with focused JVM unit tests (storage policy, status transitions and backoff,
> href resolution, ComicInfo index fallback, first-page selection, precedence, quota/low-space,
> one-CBR scheduler) and instrumented tests (migration incl. cascade, guarded insert after
> removal, interrupted extraction, orphan sweep, an extraction corpus including malformed,
> oversized and huge comic pages, a seeded 2,000-item backfill with Library-emission
> counts), plus emulator phone/tablet/short-landscape in Classic and Dark. Record evidence in
> `docs/VALIDATION.md`. Commit locally; do not push. Then stop for independent Codex High
> review.


### 4.5A.2 follow-on scope note

After 4.5A.1 merges, **4.5A.2 — Cover Presentation** starts from fresh `main` on branch
`phase-4.5/4.5a2-cover-presentation`: Room v7 `cover_presentation_preference`; shapes
(Original, 2:3, 3:4, 4:5, 1:1, Custom W:H) with Crop/Fit; global plus per-category
preferences; Settings → Library appearance; details-pane and cover-expand behavior;
keyboard/controller/accessibility; EN/ES/PT-BR. It has its own implementation prompt written
then, its own validation, Codex High review, remediation and merge.
