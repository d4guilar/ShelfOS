# Phase 4 Implementation Plan — Notes and Knowledge Layer

Status: **Phase 4A (Annotation foundation) is IMPLEMENTED LOCALLY and pending independent review (see the 4A
implementation record in section 13.1 and `docs/VALIDATION.md`); Phase 4 as a whole is NOT complete and 4B-4F have not
started.** The rest of this document is the original planning text (2026-10-10, [ADR-0025](adr/0025-annotation-anchor-and-knowledge-model.md),
Proposed). Each slice still needs explicit administrator authorization, exactly as Phase 3's slices did
(`docs/PHASE_3_IMPLEMENTATION_PLAN.md` §12/§21). 4A added the schema, migration and removal policy described below; the
remaining slices add none until authorized.

Anything marked **(proposed)** is a recommendation for review, not an accepted design.
Anything marked **(verified)** was checked against the repository or the pinned
dependency artifacts on 2026-10-10 and names the place where it was checked. Anything
marked **(unverified)** could not be established from the repository or the artifacts
available offline and is carried as an Unknown (§9.5, §20) rather than asserted.

## 1. Baseline

- Branch `phase-4/planning`, created from `main` @ `488fe5e0983c176ad8efa6a47cf562fa17f3fe50`
  ("docs: reconcile Phase 3 reader documentation (#30)"). Working tree was clean.
- Phases 0–3 are complete and accepted (`docs/VALIDATION.md`, Phase 3 accepted 2026-10-09).
  `docs/ROADMAP.md` lists Phase 4 as the next area and states it "does not yet scope its
  implementation"; this document is that scoping.
- Room **2.8.5**, Room database version **3** (`ShelfDatabase`, `exportSchema = true`;
  schema JSON `app/schemas/com.d4guilar.shelfos.core.database.ShelfDatabase/{1,2,3}.json`).
- Readium Kotlin Toolkit **3.4.0** (`gradle/libs.versions.toml`: `readium = "3.4.0"`;
  `readium-navigator` and `readium-streamer`; `readium-shared` arrives transitively).
  `fragment-ktx` 1.9.0, `appcompat` 1.8.0, activity 1.13.0, Compose BOM 2026.09.00,
  Kotlin 2.4.20, `minSdk = 24`, `targetSdk = compileSdk = 37` (`app/build.gradle.kts`).
- Not present as dependencies (verified from `libs.versions.toml`): Paging 3,
  `room-testing`/`MigrationTestHelper`, any FTS/search library, any JSON serialization
  library beyond `org.json`. This task adds none; §11 and §16 explain how the plan lives
  within that.

## 2. Repository audit (verified)

### 2.1 Persistence

| Fact | Where verified |
| --- | --- |
| DB class `ShelfDatabase`, `version = 3`, entities `AppearancePreference`, `LibraryEntity`, `ReadingEntity`, `ReaderPreferenceEntity`, `BookmarkEntity` | `app/src/main/java/com/d4guilar/shelfos/core/database/ShelfDatabase.kt` |
| Migrations are **hand-written `Migration` objects with raw `execSQL`** (`MIGRATION_1_2`, `MIGRATION_2_3`), registered with `addMigrations(...)`. No `AutoMigration`, and **no `fallbackToDestructiveMigration`** | same file, `create()` |
| `library_item`: `id TEXT PK` (random UUID string minted at import in `PublicationFiles.prepare`), `sourceUri TEXT NOT NULL` with a **unique index**, `title`, `creator`, `category`, `format`, `fileName`, `byteSize INTEGER NULL`, `favorite`, `addedAt`, `available`, `managedPath TEXT NULL`, `titleOrigin`, `creatorOrigin`. **No content hash or revision token anywhere.** | `LibraryDao.kt` (`LibraryEntity`); `core/files/PublicationFiles.kt` (`UUID.randomUUID()`); `3.json` |
| `bookmark`: `id TEXT NOT NULL PK`, `itemId TEXT NOT NULL`, `locator TEXT NOT NULL`, `progress INTEGER NOT NULL`, `label TEXT NULL`, `createdAt INTEGER NOT NULL`; index `index_bookmark_itemId(itemId)`; **FOREIGN KEY(itemId) REFERENCES library_item(id) ON UPDATE NO ACTION ON DELETE CASCADE**. No `updatedAt`, no kind, no uniqueness constraint. | `3.json`; `MIGRATION_2_3`; `LibraryDao.kt` (`BookmarkEntity`) |
| `reading_state` likewise cascades from `library_item`; `reader_preference` has **no** FK and is deleted explicitly | `3.json`; `LibraryDao.remove` |
| Bookmark ordering is `progress ASC, createdAt ASC, id ASC`. Duplicate handling is an application-level check (exact locator-string equality inside `@Transaction addBookmark`), chosen deliberately over a UNIQUE constraint on opaque locator JSON. | `LibraryDao.observeBookmarks`/`addBookmark` |
| Migration tests do **not** use `MigrationTestHelper`. They create a raw-SQL v2 database file, open it through Room with the real migrations, and assert surviving rows (`BookmarkPersistenceTest.bookmarkMigrationPreservesExistingDataAndSupportsCascadeDelete`, `LibraryPersistenceTest.migrationPreservesAppearanceAndLibrarySurvivesReopen`). The first explicitly states the choice (no `room-testing` dependency). | `app/src/androidTest/java/com/d4guilar/shelfos/` |
| The existing test **asserts the cascade**: removing a `LibraryItem` removes its bookmarks "through the real FK, not application code". | `BookmarkPersistenceTest` |

### 2.2 Library item removal and unavailable sources

- `RoomLibraryRepository.remove(id)` calls `LibraryDao.remove`, a `@Transaction` that
  deletes `reader_preference`, `reading_state` and the `library_item` row; **bookmarks are
  deleted only by the FK cascade**. It then releases the SAF grant if no other item needs
  it. It never deletes the source file or the private copy (private copies are removed
  only by the explicit "delete unused copies" action) — `RoomLibraryRepository.kt`,
  `LibraryPersistenceTest.removalKeepsPrivateCopiesUntilExplicitCleanup...`.
- The removal confirmation (`dialog_remove_body`, `strings.xml`) says: "This removes the
  entry, its reading position and its reading preferences from ShelfOS. The original file
  stays untouched." **It does not mention bookmarks, which are deleted today.**
- Unavailable sources are non-destructive: `PublicationProblem.PERMISSION_LOST` and
  `SOURCE_UNAVAILABLE` carry `unavailable = true`; `markUnavailable`/`reconcileSources`
  only set `library_item.available = 0`; readers call `markAvailable(false)` on such a
  failure; user-facing text (`problem_*_message`) says the reading position and edits are
  kept. Nothing deletes a `LibraryItem` or its dependent rows because a source is missing
  (`domain/library/PublicationProblem.kt`, `RoomLibraryRepository.reconcileSources`).

### 2.3 Identity

- Item identity is the random UUID `LibraryItem.id`. `sourceUri` is unique per row but is
  an access reference (ADR-0022); `byteSize` is import-time metadata that is not
  refreshed (the `rarCacheSourceKey` doc in `domain/library/LibraryItem.kt` documents that
  it is **not** a revision signal for externally referenced sources). A managed private
  copy (`managedPath != null`) is written once and never mutated.
- Consequence: re-importing a removed publication produces a **new** UUID. There is no
  content fingerprint with which to prove it is "the same publication". Any automatic
  re-association of old annotations to a re-imported file would be invisible guesswork
  and is therefore out of scope (§7.4).

### 2.4 Readers

- **EPUB** (`feature/reader/EpubActivity.kt`, a separate Activity started with
  `EpubActivity.intent(context, id)` from `ShelfApp.kt`): Compose UI around a Readium
  `EpubNavigatorFragment` hosted in an `AndroidView` `FragmentContainerView`
  (`core/reader/EpubSurface.kt`). The fragment is instantiated from
  `EpubSession.fragmentFactory(...)` → `EpubNavigatorFactory(publication).createFragmentFactory(initialLocator, initialPreferences, configuration)`
  (`core/reader/EpubReader.kt`). On recreation the restored fragment is replaced by a
  placeholder (`restoreEpubNavigatorAsPlaceholder`/`removeRestoredEpubNavigator`) and the
  navigator is **rebuilt from the persisted locator**, not restored from FragmentManager
  state. The live `currentLocator` StateFlow is collected and persisted as
  `Locator.toJSON().toString()` + `totalProgression` percent through `PositionWriter`
  into `reading_state`. `EpubActivity.kt` itself imports no `org.readium` types; Readium is
  confined to `core.reader` behind `EpubSession`/`EpubController` (ADR-0003 is honored).
- ShelfOS rewrites EPUB content documents at open time (`sanitizeEpubHtml` via
  `TransformingResource`: removes scripts, iframes, forms, external links; XHTML re-serialized
  as XML after the 2026-10-02 fix in `docs/CHANGELOG_DOCS.md`). **Anything Readium computes
  about positions inside a content document is computed on this transformed DOM.**
- The Readium configuration currently sets only `shouldApplyInsetsPadding = false` and font
  family declarations; **no selection action mode callback, no decoration templates, no
  `SelectableNavigator`/`DecorableNavigator` use exists anywhere in `app/src/main`**
  (grep for `ActionMode`, `Decoration`, `currentSelection` over `app/src/main` is empty
  apart from unrelated fold-selection comments).
- **Bookmarks exist only in the EPUB reader.** The bookmark dialog and add/remove toggle
  live in `EpubActivity.kt`; `EpubReaderViewModel` holds a `BookmarkRepository`.
  `FixedReaderScreen.kt`/`FixedReaderViewModel.kt` contain no bookmark code (grep).
  `ShelfCommand.TOGGLE_BOOKMARK` is mapped from gamepad B in the reader context
  (`core/input/ShelfCommand.kt`, asserted in `FoundationTest`) but **no production code
  consumes it** (grep).
- **Fixed-page readers** (PDF, CBZ, CBR): locator is `{"version":1,"page":N}` built by
  `pageLocator(index)` and read by `restorePage(locator, pageCount)` in
  `core/reader/ReaderPreferences.kt`; `N` is a **0-based source page index**, and
  `restorePage` **silently clamps** out-of-range values. `PageSlot.page` is documented as
  "always the true logical source-page index (never a synthetic spread index)"
  (`FixedReaderViewModel.kt`). CBZ/CBR page order is `naturalCompare` over entry names
  (`core/files/ArchivePolicy.kt`, `RarContainer.kt`). Fixed-page readers open through the
  nav route `reader/{id}` (`ShelfApp.kt`).
- **Notes destination is a placeholder** (`composable(Destination.NOTES.route) { PlaceholderScreen(... R.string.placeholder_notes_body) }`
  in `ShelfApp.kt`); README says so too.
- Localization: `values`, `values-es`, `values-pt-rBR`, 236 `<string>` entries each; plurals
  are used (`plurals` in `values/strings.xml`). Input: semantic `ShelfCommand` +
  `InputMapper` in `core/input`.

### 2.5 Documentation vs implementation conflicts found

1. `docs/features/ANNOTATIONS.md` and `docs/ARCHITECTURE.md` §16 describe an annotation
   model but say nothing of the **already-shipped bookmark table**; ARCHITECTURE only says
   "Bookmarks may be separate entities", and its §16 type list includes UNDERLINE, which
   the ANNOTATIONS.md initial set does not. ADR-0025 reconciles these.
2. `docs/features/READER.md` lists "bookmark" among the PDF features, and
   `ARCHITECTURE.md` §12 (input architecture) lists `TOGGLE_BOOKMARK`. In the implementation bookmarks exist
   **only for EPUB**, and `TOGGLE_BOOKMARK` is mapped but unconsumed. READER.md describes
   target behavior; this plan makes the gap explicit and assigns it to 4C.
3. The removal dialog copy omits bookmarks although removal deletes them (§2.2). Phase 4
   must make the knowledge consequence explicit (§7.3).
4. `docs/features/LIBRARY_SOURCES.md` says notes/annotations/bookmarks are preserved "until
   the user explicitly removes the LibraryItem". That is consistent with today's behavior
   but gives no way to remove an item and **keep** its knowledge; §7 defines that.
5. `docs/ROADMAP.md` Phase 4 status said the roadmap "does not yet scope its implementation";
   updated by this change to point here.

## 3. Constraints carried into every slice

AGENTS.md rules apply unchanged. The ones that shape Phase 4 most:

- Never modify the user's source publication; no sidecar next to publications. Annotations
  live in ShelfOS's own database. Export is a separate, explicit, user-selected file.
- Local-first and accountless: every annotation operation works with no network, no
  account and no provider. No network code is added in Phase 4.
- Reader engines stay behind ShelfOS-owned abstractions (ADR-0003). Domain and data
  layers hold **opaque locator JSON strings and ShelfOS enums**, never Readium types; all
  Readium types stay in `core.reader` (as `EpubActivity` already demonstrates).
- Themes use the central theme system; no hard-coded highlight colors in feature code.
- No hard-coded user-facing strings; EN/ES/PT-BR for every new string; publication
  content and user notes are never translated or rewritten.
- Touch, keyboard and gamepad share semantic commands; no touch-only critical path.
- Preserve UI/reader state across rotation, fold/unfold, resize, process death.
- Do not add a dependency without the AGENTS.md checks. This plan asks for none.
- Ink/stylus, OCR, AI, sync, accounts, Calibre and Adapted PDF remain out of scope (§19).

## 4. Principle: one knowledge system, different capabilities per format

| Capability | EPUB | PDF (Original) | CBZ / CBR |
| --- | --- | --- | --- |
| Bookmark | Yes (exists today) | Yes (new, 4C) | Yes (new, 4C) |
| Note anchored to a position | Yes (position or selection) | Yes (page) | Yes (page) |
| Real text selection, highlights, excerpts | **Yes (4B, gated by spike)** | **No** — `PdfRenderer` yields bitmaps | **No** — image pages |
| Note attached to selected text | Yes (4B) | No | No |
| Jump to source | Locator via navigator | Page index | Page index |
| Coordinate/ink/region markup | Later phase | Later phase | Later phase |

The UI must not offer a capability the format cannot honor (no disabled "Highlight" button
that does nothing in a PDF; the control is absent or explains itself). Semantic PDF
highlighting waits for Adapted PDF/SourceMap work; nothing in the repository today provides
trustworthy PDF text geometry, so none is attempted. No OCR.

## 5. Canonical model (proposed; the persisted shape is in §8)

```text
Annotation (proposed domain model; Readium-free)
├── id                 UUID string (stable; legacy bookmark ids are preserved)
├── libraryItemId      LibraryItem.id (UUID). Not a filename, not a URI.
├── kind               BOOKMARK | HIGHLIGHT | NOTE
├── locatorFormat      READIUM_LOCATOR_1 | FIXED_PAGE_1   (versioned token)
├── locatorJson        authoritative anchor (opaque to domain/data)
├── progressSnapshot   0..100, display/sort convenience only (as Bookmark.progress today)
├── orderKey?          REAL, derived reading-order hint, recomputable, never authoritative
├── title?             user label (what Bookmark.label is today)
├── selectedText?      EPUB excerpt copy (see §6.1)
├── body?              user-written text
├── styleKey?          stable palette token (not a raw color)
├── createdAt, updatedAt
```

Refinements to the sketch in the brief, with reasons:

- **`locatorFormat`** instead of guessing the format from JSON shape. The two existing
  conventions (`{"version":1,"page":N}` and a serialized Readium `Locator`) are
  distinguishable today, but an explicit token lets readers reject an unknown future
  format without parsing and lets export be self-describing.
- **`orderKey`** because `progressSnapshot` is an integer percent: in a long EPUB 1% spans
  several pages, so multiple highlights on nearby pages tie and would fall back to
  creation order. `orderKey` (EPUB: `locations.totalProgression` as a double when present;
  fixed pages: the page index) gives stable reading-order sorting. It is nullable;
  `ORDER BY COALESCE(orderKey, progressSnapshot / 100.0), createdAt, id`. It is **derived**
  and can be recomputed from `locatorJson`; it must never be used to navigate. (Whether
  Readium selection locators reliably carry `totalProgression` is a spike item, §9.5.)
- **`title`** (not `label`) is the bookmark label carried forward; today it is never set or
  edited (`Bookmark` KDoc), so the migration copies NULLs.
- **`updatedAt`** is added (bookmarks have only `createdAt`; migration sets
  `updatedAt = createdAt`). It also becomes the natural conflict basis if demand-gated sync
  is ever built; Phase 4 builds none.
- **No tombstones / `deletedAt`.** There is no sync in Phase 4, no multi-device merge, and
  hard delete matches today's bookmark behavior. A UI-level "Undo" (delayed delete in the
  ViewModel) is a 4B/4C UI choice, not a schema feature. Reconsider only if sync is
  accepted.
- **Kind invariants**, enforced in the repository/domain (validated on write; the schema
  stays permissive so future kinds do not need a table rebuild):
  - `BOOKMARK`: no `selectedText`, no `body`, no `styleKey`; `title` optional.
  - `HIGHLIGHT`: `locatorFormat = READIUM_LOCATOR_1`; `styleKey` required; `selectedText`
    nullable (§6.1); `body` optional (an annotated highlight is still a `HIGHLIGHT`).
  - `NOTE`: `body` required (non-blank). In EPUB the anchor is either a selection
    (`selectedText` present) or the reader's current position (no selection). In fixed
    layout the anchor is a page and there is no `selectedText`.
  - **Kind never changes implicitly.** Adding a body to a highlight keeps it a
    `HIGHLIGHT`; it displays a "has note" indicator and is returned by both the
    Highlights filter (by kind) and by search (by text). The Notes filter shows kind
    `NOTE` only. This avoids overlapping filter semantics. (Product question for the
    administrator: should the Notes filter also include annotated highlights? §21.)
- **`styleKey`** is a stable token (proposed palette keys such as `amber`, `green`, `blue`,
  `rose`; final set decided in 4B) mapped to a tint by the central theme system **per reader
  palette** (the EPUB reader already has a `palette` preference in `ReaderPreferences`).
  Color is never the only differentiator: the in-text shape differs by kind (filled
  highlight vs underline for notes, both verified Readium styles, §9.2), and the hub
  shows a localized style name and kind icon/label.
- **Duplicates.** `BOOKMARK` dedupe keeps today's rule — exact `locatorJson` equality for
  the same item inside a repository transaction (not a UNIQUE index: Room's `@Index` does
  not express a partial index per kind, and the existing code comment documents the
  reasoning for not constraining opaque JSON). Overlapping highlights are allowed and are
  separate annotations; merging them is a non-goal.

## 6. Anchor and locator rules

### 6.1 EPUB (4B)

- `locatorJson` is exactly `Locator.toJSON().toString()` (verified `Locator.toJSON()`,
  `Locator.fromJSON(JSONObject, WarningLogger)` in readium-shared 3.4.0; the same pair is
  already used for resume in `EpubReader.kt`/`EpubSurface.kt`). It is stored verbatim; the
  reader adapter re-parses it. Domain code never parses it.
- Fields that matter (verified on `Locator`/`Locator.Locations`/`Locator.Text`):
  `href`, `mediaType`, `title`, `locations.{fragments, progression, position,
  totalProgression, otherLocations}`, `text.{before, highlight, after}`.
- **Why both text and position are required for highlights.** `progression` within a
  resource is relative to the rendered layout and therefore changes with font size/margins
  (ShelfOS lets users change those); it can locate a *region*, not a range. The text
  triplet (`before/highlight/after`) is the portable anchor; Readium's own decoration
  mechanism consumes the locator (§9.2). A selection locator that carries neither
  `text.highlight` nor any fine location is **rejected** (error message, nothing stored)
  rather than saved as a coarse position pretending to be a highlight.
- `selectedText` (proposed rules, numbers to be confirmed by the 4B spike):
  - Source is `Selection.locator.text.highlight`, the only text the reader provides.
  - Normalize for display/search copy only: collapse whitespace runs to one space and trim.
    The raw triplet stays inside `locatorJson` untouched.
  - Cap at 2,000 UTF-16 code units, cut on a code-point boundary, no ellipsis stored (the UI
    adds one). `body` cap 20,000 characters. Reject `locatorJson` larger than 64 KiB.
  - If the reader yields no text, store `selectedText = NULL`; the hub shows a localized
    "text unavailable" line plus the location label. Never fabricate an excerpt from
    surrounding `before`/`after`.
- `cssSelector`/CFI-like data, if the navigator provides it, rides inside
  `locations.otherLocations`; key names are **unverified** (`Locations.get(String)` and
  `getOtherLocations()` exist; which keys the EPUB navigator fills is a spike item).
- **Sanitizer coupling (risk).** Any DOM-path data is computed against the *sanitized*
  rendition. A future change to `sanitizeEpubHtml` can shift paths (the 2026-10-02 XHTML
  fix is a precedent). Therefore the text triplet must be sufficient on its own, and any
  change to the sanitizer requires a decoration-restoration regression test (stop
  condition, §21).
- Jump: `EpubController.goTo(locatorJson)` already exists (`Locator.fromJSON` → `navigator.go(locator, animated=false)`) and returns false on malformed input; this is the jump path for every EPUB annotation kind.

### 6.2 Fixed-layout (4C)

- Locator stays `{"version":1,"page":N}` (`pageLocator`). **Storage is 0-based; display is N+1.** Parsing for annotations must be stricter than `restorePage`: a missing/negative/non-integer
  `page` is a malformed anchor; a page `>= pageCount` at jump time is **not clamped** —
  the jump fails with an explanatory message (the source has fewer pages than when the
  note was made), and the annotation stays listed. (Resume position may keep clamping;
  that is a different feature.)
- Anchors are **source pages**, not spread groups. In SPREAD mode the reader's authoritative
  logical page is `FixedReaderState.page`; creating a bookmark/note from a spread anchors to
  that page (proposed; the visible-slot indicator rule is a 4C UX decision, §13.3).
  RTL/manga changes presentation, never the page index.
- CBZ/CBR page order is the `naturalCompare` order. Changing that comparator would
  silently move every anchor; it is therefore frozen by a regression test and listed as a
  stop condition.
- PDF anchors apply to **Original** page reading only (ADR-0018). Adapted-PDF anchors,
  SourceMap mapping and cross-mode placement are Phase 5 work and are not designed here.
- No selection, region or coordinate geometry is stored for fixed pages in Phase 4.

## 7. Identity, removal and missing-source semantics

### 7.1 Anchor to LibraryItem, not file name or URI

`libraryItemId` is the UUID domain identity (ADR-0004, ADR-0022). Series aggregation
(ADR-0019) is virtual: **annotations belong to the member item and are never merged across
omnibus members** — page indices and locators are per publication. Series aggregation of
notes is out of scope (§19).

### 7.2 Source temporarily unavailable, permission lost, moved

Annotations are **preserved and fully usable as data**: listed, searched, edited, deleted,
exported. Only *jump-to-source* can fail, with the item's existing localized problem
message and a way to reach the item's details (Locate/Keep flows belong to LIBRARY_SOURCES
work and are not built here). No annotation row is ever deleted because a source is
unavailable. This matches current behavior for reading state.

### 7.3 Explicit removal of a LibraryItem

Today removal deletes bookmarks silently (§2.2). Proposed policy:

- Phase 4A introduces the new table **without a foreign key to `library_item`** (rationale
  §8.2) and gives `LibraryRepository.remove` an explicit knowledge policy
  (proposed names: `KnowledgePolicy.DELETE` / `KEEP`).
- **4A–4C ship only `DELETE`**, executed in the same transaction as the item removal
  (no orphan rows, no invisible data — the hub that could display orphans does not exist
  yet), **and the confirmation dialog now discloses the count** ("This also deletes N
  bookmarks, highlights and notes."), fixing the copy conflict in §2.5. When the count is
  0 the dialog is unchanged.
- **4D enables `KEEP`** ("Remove from library, keep notes" vs "Remove including notes",
  default `KEEP` when annotations exist, because user-authored knowledge is more valuable
  than reading position and the project's rule is non-destructive by default). Kept
  annotations become **orphans**: rows whose `libraryItemId` no longer matches a
  `library_item`. They appear in the Notes hub grouped under a publication snapshot (title,
  creator, format, category captured at removal into a small additive table, proposed
  `annotation_source_snapshot`, created by a v5 additive migration in 4D), are
  searchable/editable/deletable/exportable, and show "original no longer in your library"
  on jump instead of failing silently.
- Deleting a single orphan group ("Delete notes for this removed publication") is an
  explicit user action in 4D. Nothing garbage-collects orphans automatically.

### 7.4 Re-import, replacement and revision

- **Re-import of the same file creates a new UUID and does not re-associate orphans.**
  Safe re-association needs a content fingerprint (hash) the repository does not have, and
  `docs/features/LIBRARY_SOURCES.md` already requires verified evidence for relinking. Phase 4
  does **not** compute hashes and does **not** remap silently. Deferred: an explicit,
  user-confirmed "Attach these notes to a publication" action (and fingerprint-based
  suggestions) belongs with the LIBRARY_SOURCES changed-publication/relink work.
- **Same item, bytes replaced behind a referenced URI.** ShelfOS has no detection today.
  Phase 4 policy: annotations stay attached to the item; EPUB decorations that cannot be
  placed are counted and reported, not remapped (§9.4); fixed-page anchors past the new
  page count fail with a message (§6.2). No invisible remapping. Revision-aware review is the LIBRARY_SOURCES "Changed publications" flow, later.
- **Managed copies** are immutable and keep the same UUID, so annotations on a managed
  item are stable for the life of the item.

### 7.5 Backup/portability note

Backups are not in Phase 4. The export format (§12) carries enough publication reference
fields for a human or a future importer to reconnect knowledge to a publication; the stable
UUID is included so a future restore of the same library can match exactly.

## 8. Schema and migration strategy (4A)

### 8.1 Proposed table (Room v4)

```text
annotation
  id               TEXT    NOT NULL PRIMARY KEY
  libraryItemId    TEXT    NOT NULL          -- no FOREIGN KEY (see 8.2)
  kind             TEXT    NOT NULL          -- BOOKMARK | HIGHLIGHT | NOTE (stored by name)
  locatorFormat    TEXT    NOT NULL          -- READIUM_LOCATOR_1 | FIXED_PAGE_1
  locatorJson      TEXT    NOT NULL
  progressSnapshot INTEGER NOT NULL          -- 0..100
  orderKey         REAL                      -- nullable, derived
  title            TEXT
  selectedText     TEXT
  body             TEXT
  styleKey         TEXT
  createdAt        INTEGER NOT NULL
  updatedAt        INTEGER NOT NULL
indices (proposed)
  index_annotation_libraryItemId_kind_createdAt (libraryItemId, kind, createdAt)  -- per-item lists, dedupe lookup
  index_annotation_createdAt_id (createdAt, id)                                   -- global "recent" hub
```

Kinds/formats are stored by `name` strings like `category`/`format` already are
(`ImportPolicyTest` documents that `.name`/`valueOf` round-tripping avoids schema changes).
Unknown values must be tolerated on read (skipped and counted, never crashing the list).

Search uses `LIKE` over `title`, `selectedText`, `body` (§11); no extra index is proposed
for it.

### 8.2 FK policy (decision)

`ON DELETE CASCADE` to `library_item` is **not** used for `annotation`. Reasons: (1) it
makes "keep notes on removal" impossible without a later table rebuild — SQLite cannot
alter a foreign key in place, so choosing now avoids a second risky migration; (2) the
user's knowledge should not be destroyed as a side effect of an unrelated table's delete;
(3) portable-identity direction (stable UUIDs, ADR-0022) means the id remains meaningful
even when the row is gone. Cost: referential integrity moves into the repository.
Mitigations: every deletion path goes through `LibraryRepository.remove`; a repository
test asserts no code path leaves rows behind under `DELETE`; and an integrity query
("annotations whose item is missing and that have no snapshot") is exposed to tests and
the 4F acceptance checklist.

### 8.3 Migration v3 → v4 (manual `Migration`, consistent with repo convention)

1. `CREATE TABLE annotation (...)` and the two indices (raw SQL identical to what Room
   generates — verified against the exported `4.json` after build).
2. Copy every bookmark, **preserving the id**:
   ```sql
   INSERT INTO annotation (id, libraryItemId, kind, locatorFormat, locatorJson,
       progressSnapshot, orderKey, title, selectedText, body, styleKey, createdAt, updatedAt)
   SELECT b.id, b.itemId, 'BOOKMARK',
          CASE WHEN li.format = 'EPUB' THEN 'READIUM_LOCATOR_1' ELSE 'FIXED_PAGE_1' END,
          b.locator, b.progress, NULL, b.label, NULL, NULL, NULL, b.createdAt, b.createdAt
   FROM bookmark b LEFT JOIN library_item li ON li.id = b.itemId;
   ```
   No JSON is parsed in SQL (SQLite JSON1 availability on `minSdk 24` is not assumed).
   `LEFT JOIN` is defensive; the old FK guarantees the item exists.
3. **Verify inside the migration:** compare `SELECT COUNT(*) FROM bookmark` to the
   copied count and check there are no NULL ids; throw on mismatch. The platform
   `SQLiteOpenHelper` runs `onUpgrade` inside a transaction (platform behavior; to be
   confirmed by the failure-injection test below), so a throw leaves the database at v3
   untouched instead of silently losing rows. There is **no destructive fallback** in
   `create()` and none may be added.
4. **Do not drop `bookmark` in v4.** Recommended: keep it, declared as a legacy
   `@Entity` (so the exported schema still describes the physical database and Room's
   validation is satisfied without relying on undeclared-table tolerance), with its DAO
   write methods removed so nothing writes to it after migration. Drop it in a later,
   small migration after 4F physical acceptance confirms zero loss. Reasons: it is the
   only recovery source if a migration defect is found after real data has been migrated;
   its cost is a few rows; and dropping in the same step gains nothing. Caveat: the
   legacy table keeps its FK/cascade, so deleting an item still cascades there —
   harmless, since nothing reads it. (Alternative considered: drop immediately and rely
   on tests. Rejected as unnecessarily irreversible on owner devices holding real
   bookmarks.)
5. Room `AutoMigration` is **not** used: the data copy is not expressible as an automatic
   migration, and the repo's convention is hand-written migrations.

### 8.4 Bookmark compatibility boundary

- Keep `interface BookmarkRepository` and `data class Bookmark` unchanged in 4A, now
  **implemented over the annotation table** (kind `BOOKMARK`), so `EpubReaderViewModel`,
  `EpubActivity`'s dialog and `sameEpubBookmarkLocation` behavior are untouched and
  verifiably identical to users. `Bookmark.progress` ⇄ `progressSnapshot`, `label` ⇄
  `title`.
- Ordering for the adapter remains `progress ASC, createdAt ASC, id ASC` so user-visible
  bookmark order is unchanged during the transition.
- Retirement: 4B/4C consume `AnnotationRepository` directly for new surfaces; the
  `BookmarkRepository` adapter is removed when the last caller (EPUB dialog) moves, no
  later than 4D. The legacy table is dropped after 4F acceptance (see §8.3 item 4).

### 8.5 Migration test design (instrumented, repo convention)

Follow `BookmarkPersistenceTest`/`LibraryPersistenceTest` (no `room-testing` dependency;
this plan does not propose adding one — if the administrator prefers
`MigrationTestHelper`, that is a dependency decision with its own review):

1. Bootstrap a **real v3 database file** with raw SQL matching `3.json` exactly, containing:
   two library items (one `EPUB`, one `PDF` or `CBZ`), reading state and preferences for
   each, and **multiple bookmarks** per item including: a NULL label, a non-NULL label,
   identical `progress`/`createdAt` (tie-break coverage), the exact serialized locator
   strings used by `EpubBookmarkTest`, and a `{"version":1,"page":N}` row.
2. Open through Room with `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`.
3. Assert: every bookmark id present as a `BOOKMARK` annotation; `locatorJson`, `progressSnapshot`,
   `title`, `createdAt` equal to source; `updatedAt == createdAt`; `libraryItemId` equal;
   correct `locatorFormat`; legacy `bookmark` rows still present; library, reading and
   preference rows untouched; `BookmarkRepository.bookmarks(itemId)` returns the same list
   in the same order as a pre-migration snapshot taken with the v3 DAO semantics.
4. **Failure injection:** a test-only migration that fails after the table creation proves the database stays at v3 with all
   bookmarks intact and that the app does not fall back to a destructive rebuild.
5. v1 → v4 and v2 → v4 chains run through the existing bootstrap helpers.
6. Schema export check: `4.json` is generated and committed with the slice.

### 8.6 Device upgrade path

Build the unmodified `main` @ `488fe5e` debug APK, install it on the emulator and on the
Retroid Pocket 5 (`d8f7f1b6`, API 33), create real bookmarks through the EPUB UI (several, in more than one book), then install the 4A build with `adb install -r`
(same debug signature) and verify bookmarks, order and jump behavior in the UI. Do not assume an owner-held install is signed identically; the owner decides whether to upgrade a
device that holds real data. `INSTALL_FAILED_ALREADY_EXISTS` must not be mistaken for a pass (§16).

## 9. EPUB / Readium research and spike (4B)

### 9.1 How the navigator is hosted today (verified)

`EpubNavigatorFragment` in an `AndroidView`, instantiated through `EpubNavigatorFactory(publication).createFragmentFactory(...)` with an `EpubNavigatorFragment.Configuration { ... }`
block built in `EpubSession.fragmentFactory`. Preferences are applied after the fragment
is started via `navigator.lifecycle.withStarted { navigator.submitPreferences(...) }`; the
live locator is collected under `repeatOnLifecycle(STARTED)`. Input uses
`navigator.addInputListener(...)` with `DirectionalNavigationAdapter` plus a center-tap
listener that toggles chrome (`core/reader/EpubSurface.kt`). This is the same
lifecycle-aware pattern a decoration/selection feature must reuse.

### 9.2 APIs confirmed present in 3.4.0 (verified)

Verified by `javap` over the Gradle-cached artifacts
`readium-navigator-3.4.0-runtime.jar` and `readium-shared-3.4.0-runtime.jar` (Gradle
transform cache under `C:\Users\dagui\.gradle\caches\9.6.0\transforms\`; the original AARs
are in `caches\modules-2\files-2.1\org.readium.kotlin-toolkit\`). No Readium sources jar
was found locally, so **signatures and class membership are verified; runtime behavior is
not.**

- `org.readium.r2.navigator.epub.EpubNavigatorFragment` is declared as implementing
  `OverflowableNavigator`, **`SelectableNavigator`**, **`DecorableNavigator`**,
  `HyperlinkNavigator` and `Configurable<EpubSettings, EpubPreferences>`.
- `org.readium.r2.navigator.SelectableNavigator`:
  `suspend fun currentSelection(): Selection?` (JVM signature takes a `Continuation<? super Selection>`; nullability not visible in bytecode, **unverified**) and `fun clearSelection()`.
  There is **no selection-changed listener** on this interface.
- `org.readium.r2.navigator.Selection(locator: Locator, rect: RectF)` — coordinate space of
  `rect` (view vs. WebView pixels) is **unverified**.
- `org.readium.r2.navigator.DecorableNavigator`:
  `suspend fun applyDecorations(decorations: List<Decoration>, group: String)`,
  `fun <T : Decoration.Style> supportsDecorationStyle(style: KClass<T>): Boolean`,
  `fun addDecorationListener(group: String, listener: DecorableNavigator.Listener)`,
  `fun removeDecorationListener(listener)`. `Listener.onDecorationActivated(event: OnActivatedEvent): Boolean`;
  `OnActivatedEvent(decoration: Decoration, group: String, rect: RectF?, point: PointF?)`
  (nullability of `rect`/`point` unverified).
- `org.readium.r2.navigator.Decoration(id: String, locator: Locator, style: Decoration.Style, extras: Map<String, Any>)`
  — `Parcelable`, `JSONable`. Built-in styles: `Decoration.Style.Highlight(tint: Int, isActive: Boolean)` and
  `Decoration.Style.Underline(tint: Int, isActive: Boolean)` (both `Tinted`, `Activable`).
  `org.readium.r2.navigator.DecorationChange` and the extension `changesByHref(...)`
  exist (diffing helper).
- Decoration rendering templates: `org.readium.r2.navigator.html.HtmlDecorationTemplate(s)`;
  `HtmlDecorationTemplates.defaultTemplates(...)` and `Configuration.decorationTemplates` (get/set).
  Custom `Decoration.Style` classes can be registered with a `HtmlDecorationTemplate` (layout, width, element, stylesheet). This is Readium's own templating API; it is **not** an invitation to add custom JavaScript, which remains prohibited (§9.5).
- `EpubNavigatorFragment.Configuration` exposes **`selectionActionModeCallback: ActionMode.Callback`** (property verified), `decorationTemplates`, `disableSelectionWhenProtected`, `shouldApplyInsetsPadding` (already used by ShelfOS). Whether Readium's default selection toolbar is shown when the callback is null is **unverified**.
- `Locator`: `Locator.toJSON()`, `Locator.fromJSON(JSONObject, WarningLogger?)`, `fromLegacyJSON`, `fromJSONArray`; `Locator.Text(before, highlight, after)` and `Text.substring(IntRange)`; `Locator.Locations(fragments, progression, position, totalProgression, otherLocations)`; `Locations.get(String)`.
- `EpubNavigatorFragment.currentLocator: StateFlow<Locator>` and `firstVisibleElementLocator()`.
- Opt-in: ShelfOS already opts in to `org.readium.r2.shared.ExperimentalReadiumApi` file-wide in
  `EpubReader.kt`/`EpubSurface.kt`. Whether the selection/decoration symbols carry that
  annotation is **unverified**; a compile will answer it.
- The navigator AAR ships `assets/readium/scripts/readium-reflowable.js` (and `readium-fixed.js`, `divina*.js`) — the JS that backs decorations/selection is Readium's own and is injected by Readium, not by ShelfOS.

### 9.3 Mapping to ShelfOS (design, depends on the spike)

- A ShelfOS-owned adapter in `core.reader` (proposed `EpubAnnotationBridge`, name
  provisional) owns every Readium type: it translates a `Selection` → a domain
  `AnnotationDraft(locatorJson, selectedText, progress)` and a list of domain
  annotations → `List<Decoration>` for **one** group (proposed constant
  `"shelfos.annotations"`), with `Decoration.id = annotation.id` and `extras` carrying
  `kind`. `EpubController` (already the only controller type the Activity touches) gains
  the new commands; the feature layer sees only ShelfOS types (ADR-0003).
- **Selection action menu.** Because there is no selection listener, the entry point is the
  `selectionActionModeCallback` placed into the `Configuration` built in
  `EpubSession.fragmentFactory`: menu items "Highlight" and "Add note" that call a
  ShelfOS callback, which then calls `currentSelection()`. The ActionMode menu is a View
  menu; the follow-up UI (style/body) is Compose in `EpubActivity` (a modal sheet/dialog
  built with the existing hinge-safe dialog conventions, `HingeSafeDialog.kt`).
- **Applying and restoring.** The ViewModel exposes the item's annotations as a Flow (as it
  already does for bookmarks, independent of session opening so annotations never gate
  reader start). The surface applies `applyDecorations(list, group)` inside
  `navigator.lifecycle.withStarted { ... }`/`repeatOnLifecycle`, again for every newly
  created navigator (the navigator is rebuilt on recreation, §2.4). Edits/deletes re-apply
  the full list for the group.
- **Tap on an existing mark.** `addDecorationListener(group, ...)` → open an edit sheet
  (change style, edit body, delete, copy excerpt).
- **Interaction conflicts to resolve in the spike:** the center-tap `InputListener` that
  toggles chrome vs. decoration activation vs. selection clearing; reader Back semantics
  (ADR-0023: Back is unconditional; the system dismisses a text-selection ActionMode first —
  confirm this does not trap or double-handle Back).

### 9.4 Lifecycle, scale and failure considerations

- Decorations must survive: rotation/recreation (navigator rebuilt), fold/unfold, window
  resize, typography/palette changes (`submitPreferences` re-paginates), scroll vs.
  paginated mode (`ReaderPreferences.scroll`), process death (re-applied from the database,
  never from Fragment state).
- Large counts: `applyDecorations` takes the whole group; Readium computes a diff
  (`changesByHref`). Performance at ~500 and ~2,000 decorations in one book is **unverified**
  and is measured in the spike on the slowest device available (SM-T580, 2 GB RAM) and
  the RP5.
- A decoration whose text can no longer be located (changed source, sanitizer change) must
  not crash the reader and must not be silently dropped from the data: count unplaced ones
  and surface a non-blocking message; the hub still lists them (§7.4).
- Accessibility: how WebView text selection and the selection ActionMode behave with
  TalkBack, a hardware keyboard and a gamepad is **unverified**; TalkBack was unavailable
  on both validation devices in earlier phases (`docs/VALIDATION.md`, "TalkBack (Part I)").
  The design therefore adds **non-selection paths**: "Add note at this position" in the
  reader menu and bookmark toggle (keyboard/controller-reachable via the existing semantic
  commands), so a user who cannot make a selection can still create a position-anchored NOTE.
- WebView version matters: the API 24 emulator runs WebView 52.0.2743.100 and the RP5
  runs 109.0.5414.123 (`docs/VALIDATION.md`); EPUB WebView tests already fail on WebView 52
  for font reasons. Behavior of Readium's decoration/selection scripts on WebView 52 is
  unverified. The WebView version on the SM-T580 is **not recorded** in the repository docs.

### 9.5 Unknowns and the bounded 4B proof (spike)

First task of 4B, a time-boxed proof against the pinned 3.4.0, **before** any schema use,
palette design or hub integration. Deliverable: a short evidence record appended to
`docs/VALIDATION.md` plus a go/no-go note. Prove, on the RP5 (WebView 109) and then on the
API 24 emulator and SM-T580 if available, with a **synthetic, license-clean EPUB fixture** (no owner-private files):

1. **Selection capture.** A custom `selectionActionModeCallback` appears on long-press; a menu action can call `currentSelection()` and obtain a `Locator` whose `text.highlight` equals the selected text and which carries `href` and a `progression`; record which of `totalProgression`, `position`, `fragments`, `otherLocations[...]` are actually populated; record `rect` coordinate space.
2. **Decoration render.** `applyDecorations` with a `Highlight` and an `Underline` built from a *persisted-and-reparsed* `Locator.toJSON()` string renders at the right text, in paginated and scroll modes, with both reading palettes.
3. **Restoration.** After (a) rotation/Activity recreation (navigator rebuilt), (b) process death via `adb shell am kill`, (c) a font-size change, the same persisted JSON re-renders at the same text. Record whether `applyDecorations` called before the first resource has loaded is queued or lost.
4. **Interaction.** Tapping a decoration triggers `onDecorationActivated` with usable `decoration.id`; center-tap chrome toggle and edge-tap paging still behave; Back with an active selection behaves per ADR-0023.
5. **Jump.** `EpubController.goTo(persistedJson)` lands on the annotated text.
6. **Resilience.** A locator whose text is absent from the (changed) document does not crash; observe what is rendered. Apply 500 and 2,000 synthetic decorations; record time and memory.
7. **Sanitizer.** Highlights created against the sanitized rendition re-resolve after the app is rebuilt (same sanitizer) — and document what happens if `sanitizeEpubHtml` output changes.
8. **Opt-in/annotations.** Record any `ExperimentalReadiumApi` requirement and any deprecation warnings.

**Go criteria:** items 1–3 and 5 pass on the RP5 with no ShelfOS-owned JavaScript, selection yields trustworthy text, and 500 decorations apply without ANR/jank on the RP5. Items 4, 6, 7 may be satisfied by a documented,
ShelfOS-side mitigation. **No-go:** selection text/locator unreliable, decorations not restorable from persisted JSON, or unacceptable cost on the slowest device.

**Fallback if no-go:** ship 4B as **position-anchored EPUB notes and the existing bookmarks only** (no inline highlight rendering), keep the `HIGHLIGHT` kind in the model but un-surfaced, record the finding, and ask the administrator whether to (a) wait for a newer Readium release (a dependency decision under AGENTS.md rule 14), or (b) approve a separate design. **A custom JavaScript highlighting system injected by ShelfOS is explicitly not an acceptable fallback without explicit administrator review**: it
would bypass the Readium engine boundary and enlarge the content-security surface that `sanitizeEpubHtml` exists to shrink.

### 9.6 Non-goals specific to EPUB

No underline-as-separate-kind in Phase 4 (underline is only a rendering style for NOTE),
no in-text note icons beyond what Readium styles provide, no fixed-layout EPUB (ShelfOS
rejects fixed-layout EPUB today with `UNSUPPORTED_LAYOUT`), no multi-select, no cross-chapter
highlights if the navigator cannot produce a single locator for them (the spike records
behavior; unsupported spans are rejected with a message), no dictionary lookup.

## 10. Fixed-layout notes (4C summary)

Page bookmarks, page-anchored notes, edit/delete, jump-to-page for PDF/CBZ/CBR through the
shared `FixedReaderViewModel`/`FixedReaderScreen` path; the new surfaces consume
`AnnotationRepository`. A bookmark toggle on the current page; "Add note" (page-anchored);
a list of this publication's page annotations with page labels; semantic commands
(`TOGGLE_BOOKMARK` finally gets a consumer; keyboard/controller reachable); localized and
accessible labels ("Bookmark, page 12"). Rules in §6.2. No text selection, no region markup, no OCR.

## 11. Global Notes architecture (4D)

- Replaces the `PlaceholderScreen` at `Destination.NOTES`; the destination name is
  canonical (AGENTS.md). `NotesViewModel` (proposed) + `AnnotationRepository`.
- **Filters:** All / Notes / Highlights / Bookmarks (by `kind`). **Grouping/sorting:** by
  publication (default; per-publication reading order via `orderKey`/`progressSnapshot`)
  or by date (newest first, `createdAt, id` keyset). Orphans (§7.3) appear under their
  snapshot titles.
- **Row content:** publication cover/title, kind label + icon, style name, excerpt and/or
  note body (preview), location label (EPUB: chapter title and/or "Location N" using the
  existing `presentBookmark`/`EpubPosition` helpers where possible; fixed: "Page N"),
  created/updated times with locale-aware formatting.
- **Search:** ordinary repository query, `LIKE ? ESCAPE` over `title`, `selectedText`,
  `body` plus publication title/creator; case-insensitive; user-typed `%`/`_` escaped. No FTS.
  **Threshold to reconsider FTS (proposed):** a synthetic 10,000-annotation database where
  p95 search latency on the SM-T580 or RP5 exceeds ~150 ms, or a requirement for
  ranking/stemming. FTS would be a new schema/migration and (for FTS5) needs its
  availability on `minSdk 24` verified first.
- **Large collections:** no Paging 3 dependency exists and none is added. Use keyset
  pagination in the DAO (`LIMIT` + last-seen key) driving a `LazyColumn` with incremental loading; group headers computed from the page. Evidence: a synthetic dataset
  (≥10,000 rows) scroll/search test.
- **Jump to source:** the hub opens the reader for the item with the **annotation id** (not the locator) as an argument — a new intent extra for `EpubActivity` and a nav argument for `reader/{id}` — and the reader resolves the locator from the
  repository, so process-death restores the same target. An unavailable source, a missing
  item (orphan) or a failing jump shows the explanatory message and leaves the annotation
  intact.
- **Edit/delete** in place (body, title, style); deletes confirm unless undoable.
- **Input and adaptivity:** every action reachable by D-pad/keyboard with visible focus
  (reuse `ThumbnailNavigator`/hinge-safe dialog patterns); phone: single list with a filter row; tablet/foldable: list + detail pane using window size classes
  (AdaptiveLayout), never device-model checks; state (filter, query, scroll, selected item)
  survives recreation via `SavedStateHandle` as `LibraryViewModel` does.
- Library-level search (`Destination.SEARCH`) is unchanged; Notes search is local to Notes.

## 12. Export (4E)

- **Boundary:** export the user's knowledge, never the publication. Android SAF
  `ActivityResultContracts.CreateDocument` chosen by the user (activity 1.13 is already a
  dependency); no sidecar beside publications; no network.
- **Scope choices:** all knowledge, one publication, or the current Notes filter result.
- **Canonical JSON (proposed), UTF-8:**

```json
{
  "schema": "shelfos.knowledge",
  "schemaVersion": 1,
  "exportedAt": "2026-10-10T12:00:00Z",
  "appVersion": "0.0.0-dev",
  "publications": [
    {
      "libraryItemId": "uuid",
      "title": "…", "creator": "…", "format": "EPUB", "category": "BOOK",
      "fileName": "…", "byteSize": 123456,
      "contentFingerprint": null,
      "sourceRemoved": false,
      "annotations": [
        {
          "id": "uuid", "kind": "HIGHLIGHT", "styleKey": "amber",
          "locatorFormat": "READIUM_LOCATOR_1",
          "locator": { "…verbatim Readium Locator JSON object…": null },
          "progressSnapshot": 42,
          "selectedText": "…", "body": null, "title": null,
          "createdAt": "2026-10-10T12:00:00Z", "updatedAt": "2026-10-10T12:00:00Z"
        }
      ]
    }
  ]
}
```
  `locator` is embedded as a JSON object (not an escaped string) when it parses and as a
  string otherwise. `contentFingerprint` is reserved and always `null` in Phase 4 because no
  fingerprint exists (§7.4); no binaries, covers or source paths/URIs are exported (URIs
  are access references and may be private). Unknown future fields must be ignorable
  (`schemaVersion` gates breaking changes).
- **Markdown (proposed), supported cleanly:** one section per publication (`## Title — Creator`), then per annotation a list item with kind, location label,
  `> excerpt` blockquote for text, and the note body as a paragraph; page/chapter labels
  are the same localized strings the UI uses; user text is emitted verbatim with
  Markdown-significant characters escaped only where needed to avoid breaking structure (rules to be settled in 4E, with tests for
  hostile text such as backticks, `#` and leading `>`).
- **Round-trip/import is out of scope for Phase 4** (it needs the fingerprint/relink
  design and conflict rules). Export must not claim to be a backup (`docs/features/LIBRARY_SOURCES.md`
  describes the future portable backup).
- Failure behavior: SAF cancellation is not an error; write failure shows a localized message and leaves no partial file reported as success (write to the provided document stream; on exception report failure and attempt to
  delete the document); large exports stream rather than build one string.
- **Dictionary/provider:** architecture research only — a short design note (provider
  interface behind a ShelfOS abstraction per `METADATA_ENRICHMENT.md` conventions,
  offline-first, no credentials in the open-source APK, explicit privacy statement about
  what leaves the device). **No network code, no provider, no UI** in Phase 4.

## 13. Slices

The proposed sequence **4A–4F is confirmed**, with two refinements recorded in §15:
the 4B spike may run in parallel with 4A and 4C because it touches no schema, and the
`KEEP`-on-removal semantics are deliberately placed in 4D, not 4A. Each slice authorizes
only its own scope.

### 13.1 4A — Annotation foundation

- **Goal:** the canonical persistence and repository layer, a lossless bookmark migration,
  and an explicit removal policy — with no user-visible change other than the removal
  dialog count.
- **Scope:** domain model + invariants (proposed `domain/annotations/`); `AnnotationEntity`,
  `AnnotationDao`, indices; `MIGRATION_3_4` with in-migration verification; legacy `BookmarkEntity`
  kept as a declared legacy entity with write paths removed; `AnnotationRepository` (CRUD,
  deterministic ordering, per-item and global reads, orphan-safe queries, `KnowledgePolicy.DELETE` on removal);
  `BookmarkRepository` reimplemented over it; locator validation (parse-only checks in
  `core.reader` for READIUM, strict page parse for FIXED_PAGE); removal dialog count + string
  resources EN/ES/PT-BR; exported `4.json`.
- **Non-goals:** Notes UI, highlights, fixed-page bookmarks, export, `KEEP` removal, snapshots, FTS, any Readium change.
- **Likely areas (verified paths / proposed names):** `core/database/ShelfDatabase.kt`,
  `core/database/LibraryDao.kt` (existing); `data/library/RoomLibraryRepository.kt` (existing);
  `domain/library/Bookmark.kt` (existing); proposed `data/annotations/`, `domain/annotations/`,
  `core/database/AnnotationDao.kt`; `app/schemas/.../4.json`; `ShelfApplication.kt` (wiring);
  `feature/library/PublicationDetails.kt` + `ShelfApp.kt` (removal dialog count).
- **Targeted tests:** migration test per §8.5 (multi-bookmark real-v3, failure injection, v1/v2 chains); `AnnotationRepository` CRUD/ordering/dedupe/isolation instrumented tests mirroring the existing bookmark tests;
  JVM tests for invariants and locator validation; removal-with-count test and a
  no-orphan-under-`DELETE` test; the existing `BookmarkPersistenceTest`, `EpubBookmark*`
  tests unchanged and green (compatibility proof).
- **Acceptance:** zero bookmark loss on migration; EPUB bookmark add/remove/jump/order
  behavior unchanged; `4.json` committed; no destructive fallback; builds/lint green.
- **Risks:** hand-written SQL drift from Room's generated schema (mitigate by diffing
  against `4.json`); migration performance on large bookmark counts (single INSERT…SELECT);
  FK-less integrity (§8.2).
- **Codex High review: yes** — schema, migration, data-loss surface, removal semantics.

#### 4A implementation record (2026-10-10; local, pending independent review)

Implemented on `phase-4/4a-annotation-foundation` (from `main` @ `b2ce163`); evidence in `docs/VALIDATION.md`
("PHASE 4A"). ADR-0025 remains Proposed and none of its decisions changed. Contradicting evidence found: none.

- **Added:** `domain/annotations/` (`Annotation`, kinds/formats, `KnowledgePolicy.DELETE`, `AnnotationRules`,
  strict `FixedPageLocator`), `core/database/AnnotationDao.kt` (`AnnotationEntity`, `AnnotationDao`),
  `data/annotations/` (`AnnotationRepository`, `RoomAnnotationRepository`, `RoomBookmarkRepository`),
  `core/reader/AnnotationLocators.kt` (parse-only Readium check), `MIGRATION_3_4`, `4.json` (Room-generated).
- **Removal:** `LibraryDao.removeDeletingKnowledge` deletes preferences, reading state, annotations and the item in one
  transaction; it is the only code path that deletes a `library_item` row. The removal dialog shows per-kind counts
  (plurals EN/ES/PT-BR) and keeps removal disabled while the count is still loading.
- **Deviations from this plan (all deliberate; none changes ADR-0025):**
  1. `LibraryRepository.remove(id)` keeps its one-argument interface shape (about ten test fakes implement it) and means
     `KnowledgePolicy.DELETE`; `RoomLibraryRepository.remove(id, policy)` takes the policy explicitly.
  2. `RoomLibraryRepository` no longer implements `BookmarkRepository`; `RoomBookmarkRepository` (over
     `AnnotationRepository`) does, exposed as `AppContainer.bookmarks`. `EpubActivity` and the existing EPUB tests moved
     from `container.library` to `container.bookmarks` for bookmark calls.
  3. The compatibility adapter stores locator strings without Readium parsing (`validateLocator = false`), because
     `EpubBookmarkTest.malformedBookmarkLocator...` requires a malformed locator to be storable and deletable. New
     `AnnotationRepository` callers validate by default (4B/4C should). Kind invariants still apply to the adapter.
  4. Item existence is checked inside the create transaction (replacing the dropped FK; the old FK-rejection test now
     asserts the repository rejects an unknown item).
  5. Migration is stricter than §8.3: it also fails (rolls back) on a bookmark whose item is missing or whose format is
     not EPUB/PDF/CBZ/CBR, and verifies every migrated row field-by-field, not just the count.
  6. The 64 KiB `locatorJson` cap and 2,000/20,000 text caps in §6.1 are 4B work and are not enforced yet.
  7. The DAO exposes both bookmark-compat and reading ordering, a recent-first global read, per-kind counts and an orphan
     count; unknown persisted kinds/formats are skipped and counted (`AnnotationListing.skippedUnknown`).
  8. `BookmarkPersistenceTest` was updated: the "cascade through the real FK" assertion became "removal deletes the
     canonical annotation rows explicitly", because the annotation table deliberately has no FK; the legacy table's own
     cascade is unchanged and asserted in `AnnotationRepositoryTest`.
- **Known limitations:** deleted bookmarks stay in the unwritten legacy table until it is dropped; no UI exposes
  annotations other than the unchanged EPUB bookmark dialog; the upgrade check seeded bookmarks with `sqlite3` rather than
  through the reader UI.

### 13.2 4B — EPUB highlights and notes

- **Goal:** select text in EPUB, highlight or attach a note, persist, re-render on reopen, edit/delete, jump.
- **Scope:** **begins with the spike (§9.5) and its go/no-go** recorded in
  `docs/VALIDATION.md`; then (if go) the bridge in `core.reader`, selection action menu,
  Compose style/body sheet, decoration apply/restore/tap, excerpt storage rules (§6.1),
  palette tokens in the theme system with per-reader-palette tints, position-anchored "Add note", per-publication annotations list inside the EPUB reader (moving the bookmark dialog onto
  `AnnotationRepository`, removing the compatibility adapter if now unused), strings
  EN/ES/PT-BR, accessibility labels.
- **Non-goals:** hub, export, fixed layout, merging overlapping highlights, any custom JS, any schema change (an additive v5 column/table needs separate Codex High + administrator approval).
- **Likely areas:** `core/reader/EpubReader.kt` (`EpubSession.fragmentFactory` Configuration), `core/reader/EpubSurface.kt`
  (`EpubController`, decoration apply effect), `feature/reader/EpubActivity.kt`,
  `feature/reader/EpubReaderViewModel.kt`, `core/theme/`, proposed `core/reader/EpubAnnotationBridge.kt`.
- **Targeted tests:** spike evidence; JVM tests for the pure mapping (selection → draft, excerpt normalization/caps, annotation → decoration spec) with synthetic `Locator` JSON; instrumented EPUB tests using the existing synthetic-fixture conventions (`EpubBookmarkTest`,
  `EpubRecreationTest` patterns) for create → persist → recreate → still decorated →
  tap → edit → delete → jump; run on RP5 for WebView 109 (the API 24 emulator's WebView 52 is a known EPUB limitation).
- **Acceptance:** §9.5 go criteria; highlights/notes survive reopen, recreation and process death; edits/deletes persist; jump lands on text; no source mutation; a keyboard/controller path to create a position note exists.
- **Risks:** §9.4 (WebView 52, scale, interaction conflicts, selection accessibility, sanitizer coupling).
- **Codex High review: yes** — reader lifecycle, persistence, WebView interaction; the spike result itself should get an administrator go/no-go before the full slice proceeds.

### 13.3 4C — Fixed-layout notes and bookmark polish

- **Goal:** PDF/CBZ/CBR page bookmarks and page-anchored notes with reliable jump.
- **Scope:** bookmark toggle and list in the fixed reader; page notes (add/edit/delete); `TOGGLE_BOOKMARK` consumed; page labels "Page N" (1-based display); strict page-anchor parsing and the no-clamp jump failure message; bookmark `title` editing if the 4A model's field is exposed (decision for the administrator: whether to ship bookmark labels now); spread-mode behavior (bookmark attaches to
  `FixedReaderState.page`; indicator when either visible slot's page is bookmarked — to be confirmed with the administrator); strings EN/ES/PT-BR; hinge-safe dialogs
  consistent with `HingeSafeDialog.kt`.
- **Non-goals:** selection, highlight, OCR, ink, region/coordinate markup, Adapted PDF, any change to page ordering or spread logic.
- **Likely areas:** `feature/reader/FixedReaderScreen.kt`, `FixedReaderViewModel.kt`, `ThumbnailNavigator.kt` (marker affordance, optional), `core/reader/ReaderPreferences.kt` (page-anchor helpers beside `pageLocator`), `core/input/ShelfCommand.kt` consumers.
- **Targeted tests:** JVM page-anchor parse/validation/0-based⇄display; instrumented fixed-reader create/persist/recreate/jump on PDF, CBZ and CBR fixtures (existing `OriginalFixtures` and `FixedReader*` conventions; one class per Gradle invocation); SPREAD-mode anchor test; RTL test; malformed/out-of-range anchor; a frozen `naturalCompare` ordering regression test.
- **Acceptance:** bookmarks/notes on all three formats persist and jump exactly; spread/RTL do not change anchors; D-pad/keyboard reachable; no regression to resume/spread/thumbnail behavior in the targeted suites.
- **Risks:** touching `FixedReaderViewModel`'s large, lock-ordered state (3C/3D remediation history); API 24/25 PDF special paths (not exercised by the API 27 owner tablet).
- **Codex review: yes; escalate to High if the change goes beyond additive state in
  `FixedReaderViewModel` (render/mutex/lifecycle)** — otherwise a standard independent review suffices.

### 13.4 4D — Global Notes hub

- **Goal:** one central screen to review, search, filter, edit and jump.
- **Scope:** §11 in full; `KEEP` removal semantics with `annotation_source_snapshot` (additive v5 migration + migration test), orphan presentation and explicit group delete; removal dialog choice; retire `BookmarkRepository` adapter; strings and accessibility.
- **Non-goals:** export, FTS, Series aggregation, Smart Shelves, sync, re-association of orphans.
- **Likely areas:** `feature/home/ShelfApp.kt` (Notes destination), proposed `feature/notes/`, `data/annotations/`, `core/database/`, `feature/library/PublicationDetails.kt`.
- **Targeted tests:** repository filter/search/keyset-pagination tests; v4→v5 migration test; keep/delete removal matrix; orphan listing; Compose UI tests for filters/search/jump/unavailable/missing item; large-dataset test (≥10,000 rows); recreation/state restore; keyboard focus traversal; phone/tablet layouts.
- **Acceptance:** §11 behaviors; an unavailable-source annotation remains listed and its jump explains itself; keep-notes removal leaves orphans visible and exportable data intact; large collection stays responsive on the SM-T580/RP5.
- **Risks:** removal-path data semantics; hub query cost; deep-link argument wiring across the EPUB Activity and nav route.
- **Codex High review: yes** for the removal/orphan/migration part; the UI-only portion could be reviewed at standard level — recommend one High review over the slice.

### 13.5 4E — Portable knowledge and research

- **Goal:** export to JSON and Markdown via SAF; excerpt-to-note polish; dictionary/provider architecture research note.
- **Scope:** §12; "turn this excerpt into a note" polish if not already covered by 4B; a design note (docs only) for dictionary/provider.
- **Non-goals:** import/round-trip, backup, cloud, provider code, network, fingerprinting.
- **Likely areas:** proposed `domain/annotations/export/` (pure serializer), `feature/notes/` (export action), `core/files/` is not touched.
- **Targeted tests:** JVM golden-file tests for JSON and Markdown (including hostile text, Unicode, empty/orphan publications, `schemaVersion`), SAF instrumented test writing to a `CreateDocument` result, cancellation and failure behavior, proof that no publication bytes/URIs are in the output, source-file hash unchanged by export.
- **Acceptance:** export reproduces the data exactly; user-selected location only; works offline; no sidecar files.
- **Risks:** Markdown escaping correctness; privacy of exported URIs (excluded by design).
- **Codex High review: yes (standard-high)** for data-integrity and privacy of the export format, since it becomes an external contract.

### 13.6 4F — Acceptance

- **Goal:** accumulated regression and physical UAT; the only slice that runs the full regression.
- **Scope:** full JVM suite; connected suites run **one class per invocation**; `assembleDebug`,
  `assembleDebugAndroidTest`, `lintDebug`, `bundleDebug`; migration validation from a real
  Phase-3 install; physical UAT (§17); documentation reconciliation (ROADMAP, ANNOTATIONS.md, ARCHITECTURE §16, READER.md, PRODUCT status) and the post-acceptance drop of the legacy `bookmark` table if UAT shows zero loss.
- **Non-goals:** new features.
- **Acceptance matrix:** migration safety; persistence and restart/process death; reopen; missing/unavailable source; deletion semantics (DELETE and KEEP); EPUB highlight restoration; fixed-page anchors (PDF/CBZ/CBR, spread, RTL); jump-to-source; large collections; hub search/sort/grouping; EN/ES/PT-BR; accessibility; touch/keyboard/controller; phone/tablet layouts; source immutability (hash before/after); export correctness.
- **Codex High review: yes** — independent review of the accumulated diff and evidence before merge, or an explicit owner-UAT acceptance recorded as in Phase 3.

## 14. Per-slice dependencies

| Slice | Depends on | Notes |
| --- | --- | --- |
| 4A | none | Gate for all schema consumers |
| 4B spike | none (no schema use) | May run in parallel with 4A; throwaway or isolated branch; result must be recorded before 4B proper |
| 4B | 4A merged + spike go | |
| 4C | 4A merged | Independent of Readium; may proceed before the 4B spike result |
| 4D | 4A, preferably 4B and 4C merged | The hub needs real kinds; if 4B is no-go it proceeds with bookmarks, page notes and EPUB position notes |
| 4E | 4D | Reuses the hub's query/scope selection |
| 4F | all | |

## 15. Recommended order and parallelism

`4A` → (`4B-spike` ∥ `4C`) → `4B` (if go) → `4D` → `4E` → `4F`. Disagreements with the
brief's sequence are limited to: (1) the spike should not wait for 4A because it needs no
schema, and getting its answer early de-risks the whole phase; (2) `KEEP` removal moves to
4D because it is meaningless before orphans are visible, while the **count disclosure** and
the FK-less table decision stay in 4A, where they are cheap and irreversible later.

## 16. Validation strategy (progressive)

Follows ShelfOS's progressive validation:

- **4A–4E:** targeted JVM tests for the slice's pure logic, targeted instrumented classes
  (**one class per Gradle invocation**, with the filter given as
  `-Pandroid.testInstrumentationRunnerArguments.class=<FQN>`), and the relevant build/lint
  tasks only (`assembleDebug`, `lintDebug`; `assembleDebugAndroidTest` when instrumented
  tests change). **No automatic full regression after each slice.**
- **4F:** the full accumulated regression, migration validation, build gates and physical UAT.
- **Documented infrastructure caveats to respect** (all from `docs/VALIDATION.md`):
  - Comma-separated instrumentation class arguments can silently run only the first class.
  - AGP can fail an install with `INSTALL_FAILED_ALREADY_EXISTS` yet report
    `BUILD SUCCESSFUL` with **0 tests**; always check the result XML counts.
  - The API 24 emulator (`shelfos-api24`, AOSP WebView 52.0.2743.100) has produced
    `system_server` watchdog deaths and ADB/AVD going offline; EPUB managed-font tests fail
    or hang there (`EpubManagedFontLiveSwitchTest`, `EpubManagedFontProofTest`) while passing on the RP5 (WebView 109). Treat WebView-52 EPUB failures as environmental only when the same test passes on the RP5; do not extend that excuse to new Phase 4 EPUB behavior without evidence.
  - API 37 Espresso/`InputManager` incompatibilities on one AVD image were infrastructure.
  - An isolated native `libart` SIGSEGV was classified as noise in one run; investigate before dismissing.
  - API 27 (the owner tablet) does not exercise the API 24/25 `PdfRenderer` path.
- Device selection via `ANDROID_SERIAL`, confirmed by "Running tests on devices: ..." and per-device result XML.
- Evidence is recorded in `docs/VALIDATION.md` per slice (commands, counts, device, WebView), as in earlier phases.

## 17. Physical UAT strategy (4F)

Devices named in the repository docs (`docs/VALIDATION.md`): Samsung Galaxy Tab A (SM-T580,
Android 8.1 / API 27, 2 GB RAM; owner device; WebView version not recorded), Retroid Pocket
5 (`d8f7f1b6`, arm64-v8a, Android 13 / API 33, WebView 109.0.5414.123, not a foldable,
controller hardware), and the `shelfos-api24` emulator (API 24, WebView 52). No dedicated
foldable hardware is documented; foldable behavior relies on emulator posture/window
simulation as in Phase 3. TalkBack was unavailable on both physical devices previously;
Phase 4 accessibility acceptance must re-check availability before claiming it.
UAT script: migrate a real Phase-3 install with several bookmarks; create/edit/delete
highlights and notes; kill the process (`adb shell am kill`) and reopen; rotate/resize;
make a source unavailable and confirm notes remain and jump explains; remove an item
under both policies; export and inspect; import a 10,000-annotation synthetic dataset and
search/scroll; operate by keyboard and controller; verify the source files' hashes are
unchanged.

## 18. Localization and accessibility expectations

- Every new string in `values`, `values-es`, `values-pt-rBR`; plurals for counts; locale-aware
  dates/numbers; no hard-coded text in domain/data; export field names are stable English
  identifiers (they are a format, not UI), while Markdown headings/labels use the UI locale's resources.
- Never translate or alter excerpts/notes/titles. Do not claim RTL support beyond not
  breaking it.
- Content descriptions for every icon action ("Delete note on page 12"); kind and style
  conveyed by text, not color alone; focus order and visible focus in lists and sheets;
  selection/decoration accessibility explicitly tested or documented as a gap.

## 19. Explicit non-goals (Phase 4)

OCR; AI summaries or generated notes; cloud sync, accounts, collaboration or social
annotations; ink/handwriting and freeform drawing; comic panel-region highlights;
coordinate-based PDF markup; Adapted PDF reconstruction and SourceMap mapping; any source
mutation or publication embedding; Series/omnibus annotation aggregation; Smart Shelves;
online dictionary integration; metadata or cover enrichment implementation; automatic
re-association of annotations to re-imported publications; content hashing; backup/restore
and import of exported knowledge; unrelated Library/Reader redesign. Metadata and cover
enrichment are important future work but are not authorization to broaden Phase 4.

## 20. Risks and unknowns

- Readium runtime behaviors listed in §9.5 (selection locator completeness, decoration
  restoration timing, WebView 52/SM-T580 WebView behavior, scale, interaction with chrome tap and Back).
- Sanitizer coupling of DOM-derived anchors (§6.1).
- Migration defects on real owner data; mitigated by retained legacy table, in-migration verification and device upgrade test.
- FK-less table integrity (§8.2).
- Source replacement without detection (§7.4) — documented, not solved.
- Fixed reader state complexity (`FixedReaderViewModel`).
- Selection accessibility with TalkBack/keyboard/gamepad is unverified.
- Hub performance on a 2 GB-RAM tablet.
- Markdown export escaping.
- Unknown WebView version on the SM-T580.

## 21. Stop conditions (an implementation agent must stop and report)

- The spike fails a go criterion (§9.5), or any ShelfOS-owned JavaScript appears necessary.
- A migration test shows any bookmark loss, or Room's generated schema disagrees with the hand-written SQL.
- Any change would write to a publication file or create a sidecar next to one.
- A new dependency (Paging, room-testing, serialization, FTS) seems necessary — stop and request the AGENTS.md dependency review.
- A slice needs a schema change beyond its stated scope.
- `sanitizeEpubHtml`, `naturalCompare`, `pageLocator`/`restorePage` semantics or the spread/page-index model would have to change.
- Removal would delete annotation data without the disclosed, user-confirmed choice.
- Any network call would be required for annotation CRUD, review, jump or export.
- An unresolved doc/implementation conflict or a conflict with AGENTS.md rules.
- Validation shows the infrastructure traps in §16 (0 tests run, install failures) — report, do not claim a pass.
- Product questions below are needed to proceed.

Open product questions for the administrator: (1) should the Notes filter include annotated highlights; (2) are bookmark labels in 4C scope; (3) final palette tokens; (4) default `KEEP` on removal; (5) whether to adopt `MigrationTestHelper` (dependency) instead of the repo's raw-SQL convention; (6) fixed-layout spread bookmark indicator rule.

## 22. Agent workflow per slice (recommended)

1. Administrator authorizes one slice.
2. **Claude** — first implementation of the slice → targeted validation → local commit and handoff (no push).
3. **Codex High** — independent QA for meaningful architecture, persistence or reader changes (4A, 4B, 4D, 4E, 4F; 4C conditionally).
4. **ChatGPT administrator** — triage of findings.
5. **Fresh lower-cost Codex** — narrow/mechanical remediation by default.
6. **Claude** — remediation only when architecture, lifecycle, schema or ownership must change.
7. Focused re-validation → fresh independent review where warranted → push → PR → merge.

A reviewer must not implement and then self-approve its own remediation. Commits stay local
until the owner pushes; no AI co-author trailers (owner rule).
