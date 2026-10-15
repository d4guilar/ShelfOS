# Phase 4 Implementation Plan — Notes and Knowledge Layer

Status (updated 2026-10-15): **Phase 4A, 4B, 4C and 4D are merged. Phase 4D is MERGED / IMPLEMENTATION COMPLETE in PR #38 on `main` at `90b9f1c7fe827c996247a60bcd8cbc224afc72d3`. Phase 4E — Portable Knowledge & Restore is IMPLEMENTED AND INDEPENDENTLY REVIEWED / ACCEPTED: final independent Codex High verdict PASS, all implementation findings M1–M5 CLOSED, accepted code HEAD `ecf6f621d24cbcb40c38f323ec9ed407d45e6314`. Phase 4E is merged (PR #40). Phase 4F — Acceptance: ACCEPTED. Owner Samsung SM-T580 UAT and RP5 physical/controller UAT both PASS; the final independent Codex High closure review returned PASS WITH NOTES (0 BLOCKER / 0 HIGH / 0 MEDIUM; LOW documentation findings resolved).** Phase 4 is ACCEPTED and will be COMPLETE on merge of the Phase 4F acceptance PR; Phase 5 has not started. Room remains at v5 (`annotation` plus additive `annotation_source_snapshot`); the external JSON `schemaVersion` is unrelated to the Room version. The owner completed the accumulated Samsung manual acceptance checklist with no blocking defect found. Physical TalkBack remains unverified; existing evidence limits are retained in `docs/VALIDATION.md`. The legacy `bookmark` table remains intentionally retained through Phase 4 closure, with deletion deferred to a future dedicated maintenance migration; no v6 migration in 4F.

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
  `ShelfCommand.TOGGLE_BOOKMARK` is mapped from the keyboard letter B (not gamepad B, which is BACK; corrected in section 13.3.2) in the reader context
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

**4C refinements (planning, 2026-10-10; all verified against the repository unless marked).** Full detail and the Ponytail review are in section 13.3.

- **Page identity (verified).** `PdfPages.render(index)` calls `PdfRenderer.openPage(index)` and `pageCount = renderer.pageCount`; CBZ/CBR use the natural-sorted entry list, so in all three formats the locator's `N` IS the reader's own 0-based source index (`core/reader/FixedReader.kt`). `FixedReaderState.page` is that same index and is also what resume persists (`positions.save(page)` → `pageLocator`).
- **Canonical string (proposed).** Fixed bookmarks dedupe by exact `locatorJson` string equality (`AnnotationDao.create`), and `pageLocator` is built with `org.json`, which the JVM unit-test classpath cannot run (no `org.json` test dependency in `app/build.gradle.kts`). 4C adds a pure-Kotlin formatter `FixedPageLocator.format(page)` (proposed) emitting exactly `{"version":1,"page":N}`, plus an instrumented test asserting it equals `pageLocator(N)`. All fixed annotation creates use it; the parser stays the single strict reader.
- **Navigation decision (proposed, pure).** `fixedPageNavigation(locatorJson, pageCount)` (proposed) returns `Navigable(page)`, `Malformed` (parse fails: missing/negative/non-integer/extra members/wrong version, per `FixedPageLocator.parse`), `OutOfRange(page, pageCount)` (page `>= pageCount`) or `Pending` (`pageCount` not yet known, i.e. 0). It never clamps and never calls the reader; only `Navigable` may reach `FixedReaderViewModel.showPage`, because `showPage` itself silently `coerceIn`s (verified in `FixedReaderViewModel.showPage`) and must keep doing so for resume/slider/thumbnail callers.
- **Spread and RTL binding.** The bookmark/"Add note here" page is `FixedReaderState.page`, the logical page the reader itself persists for resume. After ordinary Next/Previous it is the lowest page of the visible group (`nextPage`/`previousPage` return `group.pages.first()`; verified `SpreadModel.kt`); after a thumbnail/slider/resume selection it is exactly the requested page even if that is a pair's second member (`FixedReaderViewModel.showPage` doc). RTL changes physical placement only (`PageGroup.physicalOrder(rtl)`), never `state.page`. The control's label always names the page ("Page 13"), so the bound page is never implicit.
- **Ordering (proposed).** `AnnotationOrder.READING` sorts by `COALESCE(orderKey, progressSnapshot / 100.0)`, a 0..1 scale (EPUB `orderKey` is a total progression). Fixed annotations therefore set `orderKey = page.toDouble() / pageCount` for BOTH bookmarks and notes and `progressSnapshot = pageProgress(page, pageCount)` (existing helper). Setting `orderKey = pageIndex` for notes while leaving bookmarks null (the plan's section 5 sketch) would sort every bookmark before every note; this section supersedes that sketch for fixed pages. The derived key is never used to navigate; if a source's page count changes later the locator still decides.

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
- **4D enables `KEEP`** ("Remove, keep notes" vs "Remove, delete notes"). **Implementation decision (4D, owner rule):** when annotations exist the choice is two explicit actions with NO default and nothing implied, so `KEEP` is never implicit and the existing destructive behavior is not silently changed; with no annotations the dialog is the single unchanged action. Kept
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
  later than 4D. **Done in 4D:** `BookmarkRepository` and `RoomBookmarkRepository` are deleted; `EpubReaderViewModel` uses `observeEpubBookmarks`/`addEpubBookmark` (extension functions over `AnnotationRepository`, same order, opaque-locator and dedupe rules) and `AnnotationRepository.delete(id, BOOKMARK)`. The legacy table is dropped after 4F acceptance (see §8.3 item 4).

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

4C scope was extended on 2026-10-10 (administrator-approved) with an optional, reader-local **Publication Notebook** for the CURRENT publication across EPUB, PDF, CBZ and CBR. It is a presentation of the same canonical `annotation` rows (no second note store); the global cross-library Notes hub stays 4D. The refined 4C plan (two checkpoints, 4C.1 and 4C.2) is section 13.3; this section is retained as the original summary.

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
- **Input and adaptivity (4C audit correction):** the repository has NO `WindowSizeClass`/`material3-adaptive` dependency (only `androidx.window:window` 1.5.1, used by `MainActivity` for folds); the existing adaptive primitive is `adaptiveLayout(widthDp, heightDp)` in `feature/home/AdaptiveLayout.kt` (rail at >= 600 dp width, details pane at >= 1000 dp width and >= 480 dp height) fed by `BoxWithConstraints` in `ShelfApp`. 4D should reuse that pattern rather than assume window size classes (section 13.3.4). Original text: every action reachable by D-pad/keyboard with visible focus
  (reuse `ThumbnailNavigator`/hinge-safe dialog patterns); phone: single list with a filter row; tablet/foldable: list + detail pane using window size classes
  (AdaptiveLayout), never device-model checks; state (filter, query, scroll, selected item)
  survives recreation via `SavedStateHandle` as `LibraryViewModel` does.
- Library-level search (`Destination.SEARCH`) is unchanged; Notes search is local to Notes.

## 12. Portable Knowledge & Restore (4E)

Phase 4E has two deliberately different outputs. **Markdown is a human-readable export
format and is not imported. ShelfOS JSON is the canonical machine-readable, versioned
round-trip format and is the only knowledge format imported in v1.** This is knowledge
portability, not publication import and not a full device or library backup: publication
binaries, source URIs, private-copy paths, covers, settings and independently imported-file
identity are outside the format.

### 12.1 Canonical ShelfOS JSON v1

UTF-8 JSON has this public root contract:

```json
{
  "schema": "shelfos.knowledge",
  "schemaVersion": 1,
  "exportedAt": "2026-10-12T12:00:00.000Z",
  "appVersion": "0.0.0-dev",
  "publications": [
    {
      "libraryItemId": "uuid",
      "title": "…",
      "creator": "…",
      "category": "BOOK",
      "format": "EPUB",
      "sourceRemoved": false,
      "annotations": [
        {
          "id": "uuid",
          "kind": "HIGHLIGHT",
          "locatorFormat": "READIUM_LOCATOR_1",
          "locatorJson": "{…exact persisted locator string…}",
          "progressSnapshot": 42,
          "orderKey": 12.5,
          "title": null,
          "selectedText": "…",
          "body": null,
          "styleKey": "amber",
          "createdAt": "2026-10-12T12:00:00.000Z",
          "updatedAt": "2026-10-12T12:00:00.000Z"
        }
      ]
    }
  ]
}
```

Publication context is intentionally small: `libraryItemId`, title, creator, category,
format and `sourceRemoved`. `fileName`, `byteSize` and speculative `contentFingerprint`
are removed from v1; none is safe identity. JSON contains no SAF URI, filesystem/private
path, publication or cover bytes, credentials, account identifiers or device-specific
state. `sourceRemoved` is export-time context only. It never determines identity,
live/orphan association, reattachment, deletion or conflict handling; current Room state
always decides whether an exact `libraryItemId` is live.

Every illustrated root, publication and annotation member is required in v1; the annotation
members shown as null are required-but-nullable. Strings are never inferred from omitted
values. `publications` and `annotations` are arrays, ids are nonblank stable strings, and
category/format use the current known tokens. Each document has exactly one publication
group per `libraryItemId`; repeating a group id is invalid even when its context is
identical. Each annotation id also appears exactly once in the entire document. Either
kind of repetition rejects the complete document before mutation: v1 never merges,
concatenates, overwrites or chooses between repeated entries.

Every annotation preserves all canonical Room v5 fields: id, kind, locator format,
**`locatorJson` as the exact opaque string**, progress snapshot, nullable order key,
nullable title/selected text/body/style key, and timestamps. `READIUM_LOCATOR_1` and
`FIXED_PAGE_1` are never reconstructed from progress, display labels, Markdown or snapshot
metadata. A known-format locator which is malformed but already represents persisted user
knowledge is exported and imported byte-for-byte, remains listed as malformed/unplaceable,
and is never repaired, navigated from, or deleted implicitly.

`createdAt`, `updatedAt` and `exportedAt` use ISO-8601 UTC with millisecond precision and
convert losslessly to/from the internal epoch milliseconds. Invalid timestamps reject the
import; they are never replaced with the current time. `schemaVersion` gates breaking
format changes and is distinct from Room v5. A v1 reader ignores unknown object member
names at the root, publication and annotation levels when all required known members remain
valid. Unknown semantic values are not ignored: an unknown annotation kind, locator format,
publication category or publication format rejects the complete document, as do a missing
required field, wrong schema or unsupported version.

Canonical JSON ordering is fixed: publication groups sort by `libraryItemId` ascending and
annotations within each group sort by annotation `id` ascending, using deterministic
ordinal/string comparison. It never follows localized titles/creators, UI sort/grouping,
locale, physical database order or insertion order. Thus the same knowledge payload has
the same array order and canonical field representation, although export-instance metadata
such as `exportedAt` or `appVersion` can make complete files differ.

### 12.2 Identity, duplicates, conflicts and orphans

- Publication association uses **only exact `libraryItemId` equality with a currently live
  `LibraryItem`**. Title, creator, filename, size, category, format and approximate metadata
  never establish identity. There is no fingerprint, fuzzy match or automatic relink.
- An exact live match associates imported annotations with that item; live metadata remains
  authoritative and imported display context does not create or overwrite a snapshot.
  Any stale local snapshot for that id is ignored for association/display and is not
  cleaned up as an import side effect.
  A later explicit KEEP removal captures the then-current live display fields as the
  orphan fallback.
- Without an exact live match, import creates or preserves an
  `annotation_source_snapshot` for that source id and imports the annotations as orphaned
  knowledge. It creates no fake `library_item`. The group immediately appears in Global
  Notes, searchable/editable/deletable/re-exportable, with no Open-source action.
  If that orphan id already has a local snapshot, matching imported context reuses it.
  Different imported context does not overwrite it: the local snapshot wins, the mismatch
  is reported as a non-destructive note/warning, and otherwise-valid NEW annotations may
  still import against that orphan id. Snapshot-context equality is exact over title,
  creator, category and format; `sourceRemoved` is excluded. Imported context fills only
  an absent snapshot.
- Annotation ids round-trip. A new id imports. An existing id with identical canonical
  content is an idempotent duplicate and is skipped. The same id with different canonical
  content is a conflict: preserve the existing row, do not overwrite it, do not mint a
  copy, and report it. Canonical equality compares exactly the persisted representation of
  `id`, `libraryItemId`, `kind`, `locatorFormat`, `locatorJson`, `progressSnapshot`,
  `orderKey`, `title`, `selectedText`, `body`, `styleKey`, `createdAt` and `updatedAt`.
  It excludes `exportedAt`, `appVersion`, publication display context, `sourceRemoved`,
  localized location/date text, Markdown and cover/fallback visuals. Because
  `libraryItemId` participates, an existing annotation id under another source is a
  conflict, never reassociation. Timestamps never choose a winner.
- Explicit relink, persistent fingerprints and restoration across independently imported
  publication files remain future work; v1 does not probe or match content.

### 12.3 Import validation, preview and commit

`OpenDocument` reads only the user-selected JSON. Document invalidity (bad syntax/schema,
unsupported version, missing/unknown semantic values, duplicate group/annotation ids or a
limit violation) rejects the complete document before preview or mutation. A structurally
valid document can still have LOCAL duplicates and conflicts: preview reports publication
groups, annotation count, exact live matches, orphan groups, identical duplicates,
conflicts and snapshot-context warnings. Duplicates and conflicts are skipped, not document
failures; confirmed import atomically inserts every currently classified NEW row and any
required orphan snapshot while leaving those local rows unchanged.

Import is **strictly additive**. Absence from any all-knowledge, one-publication or filtered
document never deletes or changes a local annotation, snapshot, LibraryItem, reading state,
reader preference or source/publication file. V1 only inserts NEW knowledge, skips
identical duplicates, skips/reports conflicts, and creates a snapshot required by NEW orphan
knowledge. It is not synchronization, mirroring, replacement restore or authoritative
state reconciliation. Partial exports are valid canonical documents and reimport has no
"make local match this file" meaning.

The result groups are concise: Imported, Skipped duplicates, Conflicts and Orphan groups,
plus a non-destructive source-context warning when applicable. No v1 conflict-resolution UI
is required. The bounded workflow is:

1. **Streaming validation/inventory:** incrementally read and byte-count the SAF stream;
   validate syntax, schema/version, required structure, unique group and annotation ids,
   field/domain rules and global limits. Retain only bounded inventory/record data needed
   by later passes, never an arbitrarily large JSON tree or source string.
2. **Classification/preview:** classify validated records against Room as exact-live or
   orphan and NEW, DUPLICATE or CONFLICT, including snapshot-context warnings.
3. **Confirmed import:** reopen the selected URI using the current activity-result access,
   stream and revalidate it, then inside one Room transaction re-evaluate classifications.
   If any classification differs from preview, abort with zero writes and require a fresh
   preview. Otherwise insert NEW rows/snapshots and skip the already-classified duplicates
   and conflicts. Any read or database failure makes no success claim and commits nothing.

Passes may be combined only when bounded memory, complete validation before durable
mutation, stale-preview protection and one atomic database commit remain true. No new JSON
dependency is mandated. If a single transaction proves impractical at the approved bounds,
implementation stops for plan review rather than silently committing batches.

Because external input is untrusted, initial importer guards are **64 MiB of UTF-8 JSON,
10,000 publication groups and 100,000 annotations**. A known `ContentResolver` length above
64 MiB may fail before parsing; unknown length never bypasses the guard because the stream
is byte-counted. Meeting any guard exactly is valid when all fields are valid; exceeding
one rejects the complete document before mutation. The 100,000-row ceiling is deliberately
well above the current 10,000-annotation performance target. These are implementation
resource guards, not identity, Room-schema or JSON-format semantics, and a future app may
raise them without changing `schemaVersion`.

Each field also obeys the current canonical/domain limits: `locatorJson` is nonblank and at
most 64 KiB UTF-8; selected text at most 2,000 UTF-16 units; body at most 20,000 UTF-16
units; progress 0–100; order key null or finite; and kind/content/style combinations use
the current `AnnotationRules`. Portable restore preserves every domain-valid `styleKey`
exactly, including a nonblank value unknown to the current UI palette; palette membership
is an editor/rendering concern, not portable identity. Null stays null where allowed, and
no key is normalized, corrected or remapped. A style that actually violates the domain
rule rejects the document.

The outer `locatorJson` member must be a JSON string, but its opaque contents are not
normalized or required to parse: a known `locatorFormat` plus a malformed locator already
persisted as ShelfOS knowledge round-trips byte-for-byte as unplaceable. An unknown
`locatorFormat` is instead unsupported semantic input and rejects the document. The
importer accepts Unicode, emoji, quotes, Markdown symbols and RTL text and executes none of
it. No smaller new per-field caps are introduced.

### 12.4 Markdown export

Markdown remains clean, human-readable output only: a publication heading followed by
annotation kind/location, an excerpt blockquote where present, body/note text, and useful
dates/context. It uses localized display labels and escapes only characters needed to
preserve the structure. It contains no hidden machine restore payload and is never accepted
by the 4E importer. For the smallest deterministic rule, Markdown uses the same publication
and annotation id ordering as JSON; its presentation can remain localized.

### 12.5 SAF, failure, privacy and scope

- Export uses `ActivityResultContracts.CreateDocument`; import uses
  `ActivityResultContracts.OpenDocument` or the repository's established SAF equivalent.
  Both are explicit, accountless, offline user actions. No sidecars, storage scanning,
  automatic cloud or network calls.
- Export scopes are all knowledge, one publication and the current Notes filter result.
  "Current Notes filter result" means every database row matching the active logical query,
  not visible/loaded `LazyColumn` rows, the first keyset page or the current memory window.
  JSON and Markdown export must walk every keyset page until exhaustion. All three JSON
  scopes produce valid additive-import documents; omitted knowledge has no deletion meaning.
  Large JSON and Markdown exports stream and are never truncated to the 64 MiB **import**
  guard or any provider limit. Cancel is not an error. A write failure is
  localized, is never reported as success, and attempts to remove the newly created partial
  destination where the provider permits; provider/storage failure never yields a
  deliberately incomplete successful export. Import cancellation is not an error; read,
  invalid-JSON, wrong-schema, unsupported-version and database failures are localized and
  never reported as successful.
- Exported knowledge can contain sensitive notes, selected excerpts, publication
  titles/creators, exact locators and timestamps. ShelfOS writes only to the selected
  destination, reads only the selected import document, sends no telemetry/network data,
  and includes no publication bytes or source URI/path.
- `OpenDocument` permission exists only for the active import workflow. Multi-pass reads
  reuse that activity-result access while active; ShelfOS stores no import-source URI and
  does not retain persistable permission for later sessions.
- Before implementation, audit 4B/4D highlight-with-body and selection-note behavior. If
  excerpt-to-note is already complete, record **NO PRODUCTION CHANGES REQUIRED** instead of
  duplicating UI.
- Dictionary/provider work is one docs-only architecture note: a future ShelfOS-owned
  provider abstraction, offline-first behavior and privacy/credential constraints. No
  provider/network/UI/API-key/dependency work belongs in 4E.

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

#### 4B implementation record (2026-10-10; accepted; merged in PR #34, commit `71c1740`)

Implemented on `phase-4/4b-epub-highlights-notes` (from `main` @ `de2f2f2`); evidence in `docs/VALIDATION.md` ("PHASE 4B - EPUB HIGHLIGHTS AND NOTES", which also
carries the spike entry). ADR-0025 is unchanged and none of its decisions was contradicted. Phase 4 as a whole is NOT complete; 4C is in planning (section 13.3) and 4D-4F have not started.

- **Spike: CONDITIONAL GO (accepted direction).** Real long-press selection, lossless Locator JSON, exact decoration rendering (paginated and scroll), restoration after recreation, navigator rebuild,
  repagination and process death, decoration activation, 500 decorations without jank on the RP5 (WebView 109). Four conditions were found and are all resolved in this slice: (1) deterministic two-stage jump,
  (2) stale-anchor protection, (3) Back/selection precedence, (4) WebView capability gate.
- **Stale-anchor mitigation (section 9.4 risk closed for EPUB).** `core.reader.EpubAnchorVerifier` verifies a stored `text.before/highlight/after` against the same SANITIZED rendition ShelfOS exposes to Readium
  before drawing; PLACEABLE / UNPLACED / MALFORMED; unplaced annotations are never drawn, deleted or rewritten and are listed with a localized
  "text location unavailable" state. The first implementation used lossy whitespace/invisible-character compaction; independent review found word-boundary and ZWJ/ZWNJ false-positive paths. The hardened
  verifier preserves the sanitized DOM text-node stream exactly and requires one unique exact `before + highlight + after` window. Different or ambiguous text is UNPLACED; progression never resolves ambiguity.
  Sanitized resource text is cached (bounded, 24 resources) for the session; verdicts are not cached. The verdict also gates navigation: only PLACEABLE selection annotations are jumpable (UNPLACED/MALFORMED are preserved, listed, editable/deletable but not jumpable; position notes and bookmarks keep their persisted-locator jump). A sanitizer change that alters visible text is potentially anchor-affecting (regression tests named in VALIDATION.md).
- **Deterministic jump.** `EpubController.jumpTo`: exact persisted locator, `go`, await the navigator reaching the target resource, the same locator once more; bounded, cancellation-safe; 30/30 on the RP5 (a single `go` missed 6/30 in the same test).
- **Back/selection precedence.** ADR-0023 "Phase 4B clarification": selection, then dialog, then reveal chrome, then exit; same for Back, Escape and gamepad B.
- **WebView capability.** Offered only when the real WebView's Chromium major version is >= 69 (highest platform version Readium 3.4.0's unpolyfilled script statically requires; 52 measured failing, 109 measured passing, between untested).
  Unsupported/unknown: no inline Highlight or selection notes, no decorations; bookmarks and position notes remain; localized explanation. API 24 emulator (WebView 52) validated.
- **Added:** `core/reader/{EpubAnnotationBridge,EpubAnnotationModel,AnnotationPalette,EpubAnchorVerifier,InlineAnnotationCapability}.kt`, `EpubController.jumpTo`, decoration/selection wiring in `EpubSurface`, `EpubSession.sanitizedMarkup/anchorVerifier/resourceRange`,
  `feature/reader/EpubAnnotationUi.kt` (Notes list, editor, delete confirmation), annotation state in `EpubReaderViewModel`, a "Notes" entry in the reader controls, `AnnotationLimits` (caps enforced in `AnnotationRules`), strings EN/ES/PT-BR.
  Debug-only (`src/debug`): `SyntheticAnnotationEpub` (also the androidTest fixture), `UatLauncherActivity` + `UatStressActivity` (manual UAT harness, kept: it is debug-source-set only and `UatHarnessTest` exercises it),
  `EpubLegacyWebViewTestActivity` (legacy-WebView simulation for instrumentation).
- **Deviations from this plan (all deliberate; none changes ADR-0025):**
  1. The existing Bookmarks dialog and `BookmarkRepository` compatibility adapter are kept as they are (zero bookmark regression risk); the new in-reader "Notes" dialog lists all three kinds, and bookmark rows there delete directly like before.
  2. `orderKey` is derived for selection annotations from the verified text offset within the resource and Readium's position catalog (`resourceRange`), and for position notes from the live total progression; bookmarks keep a null `orderKey`.
  3. Selection notes draw a neutral underline and carry no `styleKey`; only HIGHLIGHT has a palette style.
  4. Readium replaces the platform selection menu with ours when the callback is set, so the toolbar offers Highlight, Add note and Copy (no Select all/share) on supported WebViews; unsupported WebViews keep the platform menu.
  5. The selection is cleared when the note editor opens (the draft is already captured), not after Save; Cancel creates nothing. The unsaved draft survives rotation (ViewModel) but not process death.
  6. A new HIGHLIGHT whose text cannot be verified against the rendition is refused with a message instead of being stored unplaceable; a note on such a selection is also refused (it could never be drawn).
  7. Search results and the legacy Bookmarks dialog still use the single `goTo`; only the Notes list uses the deterministic `jumpTo`.
- **Current status:** ACCEPTED AND MERGED (PR #34, commit `71c1740`). Acceptance evidence at merge time: Samsung manual UAT PASS with a non-blocking repeated-rotation harness timeout; independent Codex High review PASS. Remaining Phase 4 work: 4C (4C.1 merged and accepted at `0040c52`; 4C.2 implementation complete, Codex High PASS, ready to merge, physical UAT deferred to 4F; section 13.3), then 4D-4F.

### 13.3 4C — Fixed-layout notes, bookmark polish and the reader-local Publication Notebook

**Status: 4C.1 MERGED + ACCEPTED (`main` @ `0040c52187e50515b7d20608e5ef8e71dacc7809`); 4C.2 IMPLEMENTATION COMPLETE + CODEX HIGH PASS, MERGED in PR #37 at `ca47e3d1bee2c3d5426996ab6c76204cd5920131`; physical UAT deferred to 4F (reconciled 2026-10-12; section 13.3.16).** This section supersedes the earlier short 4C description; the original summary is §10 and the anchor rules are §6.2.

#### 13.3.1 Goal, scope and non-goals

- **Goal:** (a) page bookmarks and page-anchored notes for PDF, CBZ and CBR on the canonical annotation layer, with strict navigation; (b) an OPTIONAL reader-local **Publication Notebook** (current publication only) opened from the reader in EPUB, PDF, CBZ and CBR, that adapts to the available width (side by side on wide windows, a dialog otherwise) without losing reader state.
- **In scope:** fixed bookmarks (toggle, indicator, list); fixed page notes (add at the current page, edit, delete); strict fixed-page annotation navigation; semantic keyboard/gamepad parity; the notebook (list, filter, quick add, jump, edit, delete, empty/unavailable states) over the existing `AnnotationRepository`; EPUB integration over the accepted 4B annotations; adaptive presentation; state preservation; EN/ES/PT-BR; accessibility.
- **Non-goals (hard):** PDF/CBZ/CBR text selection or highlights, OCR, comic panel anchors, coordinate/region annotations (§19); **handwriting, pen, ink or any freehand canvas (§13.3.11)**; the global cross-library Notes hub, cross-publication search and export (4D/4E); a second note store; any schema/Room change; any dependency; any change to `FixedReaderViewModel`'s render/mutex/spread logic, `restorePage`, `pageLocator`, `naturalCompare`, `sanitizeEpubHtml` or EPUB anchor verification; a foldable-specific architecture; reader chrome redesign beyond the controls named below.

#### 13.3.2 Repository audit (verified)

**Fixed reader.**

| Fact | Where |
| --- | --- |
| The fixed reader is not an Activity: it is the nav route `reader/{id}` in the single `MainActivity`; `FixedReaderViewModel(id, container.library, container.fixedReaders, container.backgroundScope)` is built with `viewModelFactory` inside that `composable`, so it is scoped to the NavBackStackEntry (survives rotation/fold recreation, closed in `onCleared`). EPUB is a separate `EpubActivity` started with `EpubActivity.intent(context, id)`. | `feature/home/ShelfApp.kt` (`openReader`, `composable("reader/{id}")`) |
| `AndroidManifest.xml` declares **no `configChanges`** for either Activity, so rotation, resize and fold/unfold RECREATE the Activity; reader state survives only through ViewModels, Room and `rememberSaveable`. | `app/src/main/AndroidManifest.xml` |
| `FixedReaderState(item, page, count, slots, loading, error, preferences, globalPreferences)`: `page` is the authoritative logical source page; `slots` are the 1 or 2 visible `PageSlot`s (`PageSlot.page` is the true source index). Resume persists `pageLocator(page)` through `PositionWriter`. | `feature/reader/FixedReaderViewModel.kt` |
| `showPage(index)` **silently clamps** (`coerceIn(0, count-1)`) and so does `restorePage`. Strict annotation navigation must therefore validate BEFORE calling `showPage`, which keeps clamping for resume/slider/thumbnail callers. | `FixedReaderViewModel.showPage`; `core/reader/ReaderPreferences.kt` |
| PDF: `PdfPages.render(index)` = `PdfRenderer.openPage(index)`, `pageCount = renderer.pageCount`; CBZ/CBR: `entries.size` in natural order. In all three the locator's `N` is the reader's own 0-based source index. | `core/reader/FixedReader.kt` |
| Spread: `SpreadMode.AUTO` never pairs (`resolveSpreadActive`); explicit `SPREAD` pairs for Comic/Manga only (`spreadCapable`). After ordinary Next/Previous `state.page` is the lowest page of the visible group; after thumbnail/slider/resume it is exactly the requested page (possibly a pair's second member). RTL changes only `PageGroup.physicalOrder(rtl)`. | `core/reader/SpreadModel.kt`, `FixedReaderViewModel.kt` |
| Render geometry is derived from the **reader surface's own bounds** (`Box("reader_page").onGloballyPositioned` → `resolveReaderFoldLayout(boundsInWindow, folds, gutter)` → `ReaderRenderGeometry` → `vm.updateViewport`). A fold that does not intersect the reader surface is ignored (`selectRelevantFoldDescriptor`). A side pane that shrinks the reader surface therefore automatically shrinks render geometry and fold/spread eligibility; no window-width input exists in spread decisions (`AUTO_SPREAD_MIN_WIDTH_DP = 600` is legacy: only the default floor of `verticalFoldSpreadEligibleForAuto`, which no longer decides presentation). Re-rendering is gated by `EffectiveRenderKey` (bucketed sizes with hysteresis). | `FixedReaderScreen.kt`, `core/reader/FoldLayout.kt`, `RenderKey.kt` |
| Chrome: a top `Row` (Library, Appearance, Pages, Zoom, Hide controls; `horizontalScroll`), the page surface, and a bottom Previous/Next/slider block, gated by `controls` (`rememberSaveable`). `appearance` and `thumbnails` open flags are also `rememberSaveable` (an inline comment claims they reopen closed on recreation; the code restores them; the code is authoritative and must be tested). Zoom/pan (`scale`, `panX`, `panY`) are `remember(state.page, fitWidth)`, NOT saveable: zoom resets on recreation and on page change. | `FixedReaderScreen.kt` |
| Back: `BackHandler { backPress() }` where `backPress() = if (controls) onBack() else reveal controls`, plus the Column `onPreviewKeyEvent` for `ShelfCommand.BACK` (Escape, gamepad B). Hinge-safe overlays add their own later-composed `BackHandler` and a root key interceptor. | `FixedReaderScreen.kt`, `HingeSafeDialog.kt` |
| `FixedReaderViewModel(` is constructed directly by seven instrumented test classes (`FixedReaderFoldableUiTest`, `FixedReaderFoldRenderGeometryUiTest`, `FixedReaderFoldRenderRequestTest`, `FixedReaderHingeSafeModalTest`, `FixedReaderSpreadViewModelTest`, `FixedReaderViewModelLifecycleTest`, `ThumbnailLoaderInstrumentedTest`) plus `ShelfApp`; a constructor change would ripple through all of them. | grep of `app/src` |

**Input.**

| Fact | Where |
| --- | --- |
| **Correction to §2.4:** `ShelfCommand.TOGGLE_BOOKMARK` is mapped from the keyboard letter **B** (`InputKey.B`, from `KeyEvent.KEYCODE_B`) in the READER context only. Gamepad B is `InputKey.GAMEPAD_B` and maps to `ShelfCommand.BACK`. No gamepad button maps to TOGGLE_BOOKMARK today. | `core/input/ShelfCommand.kt`, `AndroidInput.kt`, `FoundationTest` lines 36-37 |
| **No production code consumes `TOGGLE_BOOKMARK`** in either reader (EPUB `readerKeys` handles NEXT/PREVIOUS/OPEN_MENU/BACK/SEARCH; the fixed Column handles NEXT/PREVIOUS/OPEN_MENU/BACK; the command falls through). Consuming it is a behavior change that must not swallow the letter B anywhere text is typed. | `EpubActivity.kt` (`readerKeys`), `FixedReaderScreen.kt` |
| EPUB key handling is **Activity-level** (`dispatchKeyEvent` → `readerKeys`, before Compose focus). `readerCommand(rtl, controlsFocused)` lets focus-moving keys (arrows, Tab, Enter, Space, A) through only when chrome has focus (or the Search dialog is open). An in-window notebook pane in `EpubActivity` would otherwise have its arrow/Space/A keys turned into page turns. | `EpubActivity.kt`, `core/input/ShelfCommand.kt` (`focusKeys`) |
| The accepted 4B gamepad-Back handling inside annotation dialogs is `dismissOnShelfBack` (`onPreInterceptKeyBeforeSoftKeyboard`: consumes system Back and `ShelfCommand.BACK`, i.e. Escape and gamepad B, on down and up). It is applied to the note editor and the delete confirmation. | `EpubAnnotationUi.kt` |

**Annotation layer (4A/4B API, verified).** `AnnotationRepository`: `create(draft, validateLocator=true)`, `get`, `update(id, AnnotationEdit)`, `delete(id, kind?)`, `observeForPublication(itemId, kinds?, order)` (`READING` or `BOOKMARK_COMPAT`), `observeRecent`, `counts`, `countOrphans`, `deleteForPublication`. `AnnotationRules` already enforces what fixed notes need: NOTE requires a non-blank body; a `FIXED_PAGE_1` NOTE rejects `selectedText`; BOOKMARK carries no text/body/style; HIGHLIGHT requires `READIUM_LOCATOR_1`; body cap 20,000; locator cap 64 KiB; `FIXED_PAGE_1` validated by `FixedPageLocator.parse`. `AnnotationDao.create` checks item existence and, for kind BOOKMARK only, returns the existing row when `locatorJson` is exactly equal. `RoomBookmarkRepository` (the compatibility adapter) hard-codes `READIUM_LOCATOR_1` and `validateLocator = false`, so **fixed bookmarks must call `AnnotationRepository` directly**, never the adapter. A publication has a single format, so mixed EPUB/fixed ordering does not arise inside one list. The `annotation` index `(libraryItemId, kind, createdAt)` serves the per-publication query.

**EPUB reader (4B, verified).** `EpubReaderViewModel` already exposes `annotations`, `anchors` (PLACEABLE/UNPLACED/MALFORMED), `editor` (`AnnotationEditorTarget`: `NewSelectionNote`, `NewPositionNote`, `Existing`) and `notice`; the editor target and unsaved draft survive rotation (ViewModel plus `rememberSaveable(editorKey)` body) but not process death. The chrome already has a **Notes** button (`testTag("epub_notes")`) that opens `AnnotationListDialog` (an `AlertDialog`): filter chips (All/Notes/Highlights/Bookmarks), "Add note at current position", jump through `navigateToAnnotation` with the `canNavigate` gate (UNPLACED/MALFORMED selection annotations are listed, editable and deletable but never navigated), Edit and Delete, plus the note editor and delete confirmation dialogs; the older Bookmarks dialog also remains. `EpubSurface` creates the Readium navigator in `remember(session)` and removes it in `DisposableEffect(navigator)`, so **moving `EpubSurface` to a different position in the composition tree disposes the navigator and rebuilds it from the persisted locator** (written asynchronously and conflated by `PositionWriter`). `EpubActivity` has **no fold/hinge handling** (no `WindowInfoTracker`; only `safeDrawingPadding`).

**Adaptive/window infrastructure: exactly what exists.**

| Exists (verified) | Does NOT exist (verified by grep of `app/src`, `gradle/libs.versions.toml`, `app/build.gradle.kts`) |
| --- | --- |
| `androidx.window:window` **1.5.1** (`libs.versions.toml`), consumed only in `MainActivity` (`WindowInfoTracker.windowLayoutInfo` → `FoldingFeature.toReaderFoldDescriptor()` → `folds` → `ShelfApp` → `FixedReaderScreen(folds = ...)`). | `WindowSizeClass`, `material3-adaptive`, `currentWindowAdaptiveInfo`, `WindowMetricsCalculator`, `LocalConfiguration`/`screenWidthDp` use. |
| `feature/home/AdaptiveLayout.kt`: `adaptiveLayout(widthDp, heightDp)`: rail when width >= 600 dp (or height < 480 dp with width >= 480 dp); `showDetails` when width >= 1000 dp and height >= 480 dp; fed by `BoxWithConstraints(maxWidth, maxHeight)` in `ShelfApp`; JVM-tested in `FoundationTest`; used by the Library list/detail. | Any reader-side adaptive layout; any width-based reader decision; any side pane in a reader. |
| `core/reader/FoldLayout.kt` (pure): `ReaderFoldDescriptor`, `resolveReaderFoldLayout`, `selectRelevantFoldDescriptor`, `selectSoloPane`, `legacySafePaneInset`; `RenderKey.kt` `EffectiveRenderKey`; `HingeSafeDialogOverlay` (in-reader overlay confined to a pane when a vertical fold constrains the fixed reader). `MainActivity` applies `legacySafePaneInset` to non-reader screens only. | Fold handling for `EpubActivity`. |
| Precedent for forced-size tablet validation: `adb shell wm size 1600x1000` with `wm density 160` (VALIDATION.md, tablet run) and `wm size 1600x2560` live resize (Phase 2D). | A reader-side breakpoint helper. |

**Decision: no new dependency.** The breakpoint comes from the reader host's own `BoxWithConstraints` (the `ShelfApp` precedent) through a pure function (proposed, §13.3.4). `material3-adaptive`/`WindowSizeClass` would add a dependency that the AGENTS.md checks require for one boolean; it is **not recommended**; if the administrator wants it anyway it is explicit decision D13.

**Doc/implementation conflicts found (reconciled by this change).** (1) §2.4 said `TOGGLE_BOOKMARK` is "mapped from gamepad B": it is the keyboard letter B. (2) §5/§6 said fixed `orderKey` is the page index: that sorts every bookmark (null key, 0..1 fallback) before every note; replaced by `page/pageCount` (§6.2). (3) §11 said "window size classes (AdaptiveLayout)": no size classes exist; `AdaptiveLayout` is a width/height function (§11 annotated). (4) `docs/features/ANNOTATIONS.md` still said "pending Samsung manual UAT and review" (reconciled). (5) An inline comment in `FixedReaderScreen.kt` says the Appearance/Pages overlays reopen closed on recreation while the code uses `rememberSaveable`; the tested behavior must be recorded before the notebook copies the pattern (the code comment itself is implementation work, not changed here).

#### 13.3.3 Design

**A. Fixed-page annotation model.**

- BOOKMARK: `kind=BOOKMARK`, `locatorFormat=FIXED_PAGE_1`, `locatorJson = FixedPageLocator.format(page)` (proposed, §6.2), no body/text/style, `progressSnapshot = pageProgress(page, count)`, `orderKey = page.toDouble() / count`.
- NOTE: same locator/ordering fields; `selectedText = null`, `styleKey = null`, `title = null`, `body` required, non-blank, at most 20,000 characters (existing rules; the UI never truncates: it disables Save and shows the counter as 4B does).
- Display "Page N+1", store N. Never stored: spread index, slot index, thumbnail index, synthetic pair index.
- **Navigation** `fixedPageNavigation(locatorJson, pageCount)` (proposed, pure, JVM-testable): `Malformed` | `OutOfRange(page, pageCount)` | `Pending` (count not yet known) | `Navigable(page)`. Only `Navigable` reaches the existing `FixedReaderViewModel.showPage(page)`. The others leave the row listed, editable and deletable with a localized reason (§13.3.6); nothing is clamped, rewritten or deleted. Reader resume keeps its own clamping.
- **Which page a bookmark / "Add note here" binds to:** `FixedReaderState.page`, the page the reader would resume on. In spread mode the control's label names that page ("Page 13") so the bound page is never implicit; the sibling slot's page is not implied. Rationale: it is the one page value the reader already persists and keeps authoritative across spread and RTL; an "either visible slot" target would make the toggle ambiguous (remove or add?). To bookmark the sibling page the user selects it through Pages (thumbnail selection keeps the requested page as `state.page`, as resume does). Alternative recorded as D8.
- **Toggle semantics.** Toggle acts on `state.page`: under a single-flight guard, read the publication's BOOKMARK rows once; if one or more parse to that page, delete them all; otherwise create one. Delete is immediate with no confirmation (a bookmark is cheap to recreate; 4B's bookmark delete is also unconfirmed). A rapid double press cannot create a duplicate: the guard serializes read-then-write and `AnnotationDao.create` returns the existing row for an identical locator string. This dedupe (exact page integer) is stronger than EPUB's fuzzy `sameEpubBookmarkLocation`.
- **Current-page indicator.** The Bookmark control shows "bookmarked" for exactly the toggle-target page, from a `Map<page, annotationIds>` rebuilt only when the observed list changes, never a per-page-turn DB read.

**B. Repository changes (H): none required.** Per-publication observation, kind filtering and reading order exist (`observeForPublication`); page lookup and the toggle run in the ViewModel over the observed list or a one-shot `first()` of the same flow; create/update/delete reuse the existing methods. No interface, DAO or schema change; no Paging, FTS or JSON library. If implementation proves otherwise (for example the lookup is measurably slow on a 10,000-bookmark publication) it must STOP and request a minimal additive DAO query (§13.3.14).

**C. State ownership and preservation (D).**

| State | Owner | Survives |
| --- | --- | --- |
| Publication; current page / EPUB locator | `FixedReaderViewModel` + `reading_state` / `EpubReaderViewModel` + `reading_state` | rotation, fold, process death (existing) |
| Spread / fit / direction preferences | Room via `ReaderPreferences` (existing) | all |
| Zoom / pan | `remember` in `FixedReaderScreen` (existing) | notebook open/close and resizing while the page is unchanged; NOT recreation, NOT page change (existing, unchanged) |
| Committed annotations | Room | all |
| Notebook open/closed and its filter | `rememberSaveable` in the reader host (like `controls`, `appearance`) | rotation, resize, fold, process recreation |
| Fixed note editor target (new at page N / editing id) and its unsaved text | NEW `FixedAnnotationViewModel` (proposed; NavBackStackEntry-scoped, created beside `FixedReaderViewModel`) + `rememberSaveable(editorKey)` body, the 4B pattern | rotation, resize, fold/unfold; **NOT process death** (stated honestly, same as 4B). A draft is saved only by the Save button; Cancel, Back and dismissal discard it; there is no auto-save path. D5 offers saveable drafts as an option. |
| EPUB note drafts | existing `EpubReaderViewModel.editor` | unchanged |

Why a separate `FixedAnnotationViewModel` (proposed) instead of extending `FixedReaderViewModel`: the latter holds the lock-ordered render/mutex/session state with a remediation history (Phase 3C/3D), is constructed directly by seven instrumented test classes, and annotations need none of its internals (page and count are passed in from `FixedReaderState`). This keeps 4C.1 additive and outside the highest-risk file; the cost is one more factory in `ShelfApp` (single call site, verified).

Rotation, resize and fold/unfold therefore cannot: duplicate notes (creates happen only on explicit Save/toggle and bookmark creation is idempotent; the editor target is captured when it opens), change anchors (the page is captured at open, not re-read at Save), convert page numbers (only the true source index is stored), silently save an abandoned draft (no auto-save), lose committed notes (Room) or reset the reading position (ViewModels/Room/EPUB locator restore are unchanged). Each is a named validation (§13.3.9).

**D. Publication Notebook interaction model (B, J).**

- One ShelfOS-owned, format-neutral content composable `NotebookPanel` (proposed, `feature/reader/`) fed by `List<NotebookEntry>` (proposed: `id`, kind, label resources, optional excerpt (EPUB), body preview, location text and spoken location, `navigation: Navigable | AnchorUnavailable(reason) | Pending`, `editable`, `deletable`). Two pure mappers build entries: an EPUB mapper that wraps the existing 4B logic (`presentBookmark`, `canNavigate(anchors[id])`) and a fixed mapper over `fixedPageNavigation`. The panel never assumes an entry is text: an entry is "kind + labels + actions", so a future entry type is a new kind and mapper rather than a rewrite (§13.3.11).
- **Entry point (verified current chrome).** EPUB: the existing top-chrome button (`epub_notes`) becomes the Notebook toggle and is labeled **Notebook** (new string; `action_notes` stays for the Notes filter and the future Notes destination) (no new top-level control; the older **Bookmarks** dialog stays untouched). Fixed reader: two new buttons in the existing top chrome `Row` (already horizontally scrollable): **Bookmark** (toggle with state) and **Notebook** (opens the notebook). User-facing UI says **Notebook** (D7, D12); **Notes** is kept for `kind=NOTE` annotations, the Notes filter and the Phase 4D Notes destination. "Publication Notebook" is the canonical product/documentation name and is not spelled out in compact UI (D12).
- **Contents.** One list with the existing filter chips (All / Notes / Highlights / Bookmarks); bookmarks are the same canonical rows, the filter is accepted 4B behavior, and reading order interleaves them naturally, so a separate bookmarks view is not recommended. All highlights appear (with a body preview when present), as in the accepted 4B list; the **Notes** chip stays kind NOTE only (the §21 question about annotated highlights in the global hub is left to 4D).
- **Quick add.** EPUB: existing "Add note at current position". Fixed: "Add note on page N" (new string) opening the existing `AnnotationEditorDialog` (page in the title, `quote = null`, `bodyRequired = true`, `styleEditable = false`). Activating a Navigable entry returns the reader to its exact anchor: in DIALOG presentation the dialog closes; in the side pane the pane stays open and the reader jumps (reading and notes both stay in view). A non-navigable entry has a disabled jump control with its reason visible; its Edit and Delete remain focusable; it never navigates.
- **Empty state / counts / edit / delete.** Existing `annotations_empty`, `annotations_empty_filtered`, count plural, editor and `AnnotationDeleteConfirmDialog`. Notes and highlights confirm deletion; bookmarks delete directly (as 4B).
- **Editor placement.** The note editor stays a platform dialog (own window) in every layout, including side by side; it is not rendered inline in the pane in 4C because (1) the Activity-level EPUB key handler and the fixed reader's key handling would see typed letters (B, F, Space, arrows) as reader commands, and (2) the dialog already works with the IME and the accepted gamepad-Back handling. Trade-off: the editor is modal over the reader instead of persistent beside it; recorded as D5. Because the editor is a dialog, the pane has no text field, which is the invariant that makes consuming the letter B safe (§13.3.5).

#### 13.3.4 Responsive/adaptive layout (C)

The deciding input is the **available width of the reader host** (the `BoxWithConstraints` around the reader content), not device category, orientation or `Configuration.screenWidthDp`; in multi-window or a resized window the constraint is already the real available width. A pure function `notebookPresentation(widthDp, heightDp)` (proposed; same shape and JVM test style as `adaptiveLayout`) returns:

| Presentation | Condition (proposed) | What the user gets |
| --- | --- | --- |
| `SIDE_BY_SIDE` | width >= 840 dp **and** height >= 480 dp | Reader and notebook share one row; the notebook is a full-height pane at the END edge, width `clamp(0.36 x width, 320 dp, 400 dp)`; the reader keeps the rest. |
| `DIALOG` | everything else | The same `NotebookPanel` inside the established reader dialog pattern (`AlertDialog`; `HingeSafeDialogOverlay` when a vertical fold constrains the fixed reader), standard dialog width, list height from the available height instead of the fixed 420 dp. |

The breakpoint constants live in that one function so the administrator can tune them (D3). Full-height overlay/drawer variants for medium widths were considered and rejected for 4C: they would add a third presentation and a new component, while the dialog already is the established ShelfOS pattern and is accepted on phones, so medium widths (portrait tablets, narrow unfolded foldables, split-screen) reuse it.

**Why these numbers (evidence).** The pane needs a floor wide enough for a row with Edit/Delete: 320 dp, the narrowest phone width in the test matrix (RP5 portrait 360 dp, owner-provided) minus insets. The reader needs a width the repository already accepts: 480 dp is its minimum useful landscape width (`adaptiveLayout`'s `heightDp < 480 && widthDp >= 480` rule) and the RP5 landscape reader at 640 dp is the narrowest accepted reading width. 480 + 320 + a 1 dp divider is 801 dp; **840 dp** adds margin for insets and is the conventional "expanded" boundary (a general Android convention, not a repository fact: **unverified here**). The Library details threshold of 1000 dp (`showDetails`) is deliberately not reused: it would exclude unfolded foldables and 840-1000 dp windows where side by side still leaves a 520+ dp reader, and the notebook needs only a list column, not a cover grid beside details. The 0.36 ratio with a 400 dp cap keeps the reader near 65-70 percent on tablets.

| Window (dp) | Example | Presentation | Notebook / reader |
| --- | --- | --- | --- |
| 1280 x 800 landscape | **Samsung SM-T580** (1920 x 1200 px at about 240 dpi) and the tablet-sized AVD derived from `shelfos-api24` (1200 x 1920 at 240 dpi); the dp figures are owner-provided, **not recorded in the repository** | SIDE_BY_SIDE | 400 / 880 dp (31 / 69 percent) |
| 800 x 1280 portrait | same devices | DIALOG | n/a |
| 1600 x 1000 | existing forced tablet viewport (`wm size 1600x1000`, `wm density 160`; verified in VALIDATION.md) | SIDE_BY_SIDE | 400 / 1200 dp |
| 640 x 360 landscape; 360 x 640 portrait | **RP5** (1080 x 1920 at 360 dpi, owner-provided) | DIALOG | n/a |
| 731 x 411 landscape; 411 x 731 portrait | phone-sized AVD (1080 x 1920 at 420 dpi = 2.625x) | DIALOG | n/a |
| about 673-841 dp inner display; about 360 dp cover display | typical foldables (**unverified**, general knowledge) | 673: DIALOG; >= 840: SIDE_BY_SIDE (reader about 520 dp); cover: DIALOG | n/a |
| 640 dp (half of a 1280 dp window) | split-screen | DIALOG | n/a |

**Foldables by usable width, not product category.** Folded/cover screens fall below the threshold and get the dialog; an unfolded window at or above 840 x 480 dp gets the pane. Fold/unfold is an Activity recreation (no `configChanges`); the notebook-open flag is `rememberSaveable`, so a notebook that was open stays open and changes presentation (pane to dialog on folding, dialog to pane on unfolding), consistent with `docs/design/FOLDABLES.md` section 5 ("the same reading session expanding or contracting"). D6 records the alternative (close the notebook on contraction).

**Interactions to implement and test (flagged, not assumed).**

1. **Spread eligibility already follows the reader pane.** Spread and fold decisions come from `reader_page`'s measured bounds (§13.3.2); no window-width input exists to override. Rule: spread logic is not touched; the notebook only changes the size of the reader surface. An explicit `SPREAD` title stays a spread in a narrower reader pane (smaller pages); changing that would alter the spread model (stop condition).
2. **Vertical hinge with a side-by-side notebook (fixed reader).** The pane is a sibling of the reader column inside a stable `Row`. A hinge that falls over the notebook does not intersect the reader surface, so `selectRelevantFoldDescriptor` ignores it and the reader is FLAT; a hinge crossing the reader surface yields the existing VERTICAL_SPLIT inside the reader pane. No new fold code. Risk to verify: `HingeSafeDialogOverlay` and `chromePane` use reader-LOCAL coordinates that assume the overlay shares the reader's left/top origin, true for an end-edge pane in LTR; layout-direction mirroring is unverified (R4).
3. **EPUB has no fold handling today.** 4C adds none; the pane decision there is width-only.
4. **Do not re-parent the readers.** Both readers wrap their existing content in an always-present `Row` whose first child is the existing content (`weight(1f)`) and whose second child is the conditional pane, so the call site of `EpubSurface` and of the fixed reader column never changes position in the composition tree (re-parenting `EpubSurface` would rebuild the navigator, §13.3.2).
5. **EPUB reflow on resize.** Opening/closing the pane resizes the WebView and repaginates. Whether Readium preserves the first visible position through a width change is **unverified**: 4C.2 begins with a bounded check (4C.2 step 1): after N open/close cycles the locator `href` is unchanged and `totalProgression` moves by no more than one position of the publication's position list. If it fails, EPUB keeps the DIALOG presentation at every width (still a Publication Notebook) and side by side ships for the fixed readers only; that is a stop-and-report condition, not a silent fallback.
6. **No animation.** Pane show/hide is an instant recomposition, like reader chrome (ADR-0023); any later transition must honor `reducedMotionEnabled`.

#### 13.3.5 Back precedence, focus and input (F, K)

**Proposed Back precedence (identical for system Back, Escape and gamepad B, all routed through each reader's single `backPress()`):**

1. An active native text selection (EPUB only; unchanged, ADR-0023 Phase 4B clarification).
2. The note editor or a delete confirmation (own dialog window; dismisses itself; gamepad B/Escape via the accepted `dismissOnShelfBack`).
3. The notebook: in DIALOG presentation the dialog dismisses itself (own window; the fixed reader's hinge overlay has its own `BackHandler`); in SIDE_BY_SIDE presentation **Back closes the pane first** and does not also reveal chrome or leave the reader (one press, one layer; the pane is user-opened and transient).
4. Hidden chrome: Back reveals it.
5. Visible chrome: Back leaves the reader.

This adds at most one extra press and never a trap (AGENTS.md rule 5). Gamepad B/Escape with focus inside the side pane must reach layer 3: in the fixed reader the pane is a sibling of the Column that owns the Back key handler, so the pane carries its own preview-key Back handler (the `dismissOnShelfBack` approach reused); in `EpubActivity` the Activity-level `readerKeys` already sees the key and `backPress()` gains the pane check. **ADR-0023 needs a dated clarification** (proposal, to be applied during 4C.2: "an in-window supporting pane is a transient layer above chrome semantics; Back closes it before revealing chrome or exiting"). ADR-0025 is not affected (§13.3.12). Testable: a Back sequence test per layer on each reader (§13.3.9).

**Focus order and key ownership (side by side).** Natural traversal: reader chrome, reader surface, bottom controls, notebook pane. Keyboard: Tab moves focus into the pane (Tab is not consumed by `InputMapper`). Gamepad: START/Menu reveals chrome, D-pad reaches the **Notebook** button, A toggles the pane and moves initial focus into it; closing returns focus to the Notes button (or the reader surface when chrome is hidden), the same stable-target rule as `dismissAppearance`. While the pane has focus, arrows/Enter/Space/A operate the list (EPUB: the `controlsFocused` argument of `readerCommand` gains a "notebook focused" term so those keys are not turned into page turns; fixed: structural, the pane is outside the Column handler). L1/R1 and Page Up/Down keep turning pages, so a user can read while the notebook is open. Focus and key ownership are verified manually with a keyboard on the emulator and a controller on the RP5; they are proposals until then.

**Bookmark input parity (F).** Touch: the Bookmark button (with state). Keyboard: letter **B** (`TOGGLE_BOOKMARK`), consumed only when no dialog or text entry is active (the EPUB Search and note-editor surfaces are dialogs and do not reach the Activity-level handler's reader branch). Gamepad: **no button maps to TOGGLE_BOOKMARK today** and B is Back; default for 4C.1 is that the gamepad reaches the Bookmark button through chrome (START, D-pad, A), which satisfies "not touch-only". Optional D4: map one more face button (for example `KEYCODE_BUTTON_Y`) to `TOGGLE_BOOKMARK` by adding one `InputKey`, one `AndroidInput` line, the `gamepadOnlyKeys` and `InputHints` candidates, and `FoundationTest`/`InputHintsTest` cases; it touches shared semantic input, so it needs administrator approval. Administrator decision D4: **no** gamepad face-button shortcut in 4C; the chrome path is the gamepad route. EPUB gets `TOGGLE_BOOKMARK` consumption in 4C.2 on the same terms (reusing the existing `currentBookmark`/`addBookmark`/`deleteBookmark` path).

#### 13.3.6 UX details (J)

- **Location labels:** fixed "Page N" (existing page-of-count style resource); EPUB unchanged (`presentBookmark`: chapter, "Location N" or percent).
- **Unavailable anchors.** EPUB: existing UNPLACED/MALFORMED wording `annotation_location_unavailable` and the `canNavigate` gate (listed, editable, deletable, never navigated). Fixed Malformed: "Page location unavailable"; fixed OutOfRange: "Saved on page P, but this publication now has C pages" (two-argument string); Pending (count not yet known): row visible, jump disabled, no error text. Anchors are never clamped, rewritten or deleted.
- **Accessibility semantics.** Each entry is one merged semantic row ("Note, page 12, <body>, location unavailable") with separate Edit and Delete buttons whose labels carry kind and page (existing templates extended with a page argument); the Bookmark button's `contentDescription` and `stateDescription` name the page and state ("Add bookmark, page 12" / "Remove bookmark, page 12") so the state change is announced through the control itself, not a toast; the pane has `paneTitle` and heading semantics; touch targets at least 48 dp; visible focus; no information by color alone (kind and state are text).

#### 13.3.7 Localization, accessibility, performance and the 2 GB Samsung (M)

- **Localization:** every new string in `values`, `values-es`, `values-pt-rBR` (each has 283 `<string>` entries today, verified) and a plural for any count text; locale-aware page numbers; note bodies, excerpts and titles are never translated or altered. Proposed new strings: bookmark toggle labels and descriptions with page, "Add note on page %d", editor title with page, two fixed unavailable reasons, notebook pane title/description, "Close notes". Existing strings are reused wherever possible.
- **Accessibility:** §13.3.6 plus TalkBack availability must be re-checked on the test devices before TalkBack is claimed (it was unavailable on both physical devices earlier, §17); keyboard-only and gamepad-only passes are required acceptance steps.
- **Performance risks:** (1) notebook list size: `LazyColumn` with stable `key = { it.id }`, entry mapping done once per list change (not per recomposition), no per-row DB reads; (2) in `EpubActivity` every page turn updates `currentLocatorJson` and recomposes `EpubReaderContent`: the pane must receive stable inputs and skippable lambdas so an open side pane does not recompose per page turn (verify with Layout Inspector recomposition counts on the RP5; stop if page turns visibly jank); (3) fixed render geometry: opening/closing the pane changes the reader surface and triggers at most one re-render through `EffectiveRenderKey` (hysteresis prevents storms); (4) the fixed bookmark indicator is a map lookup, never IO on page turn; (5) `FixedAnnotationViewModel` collects the per-publication flow for its lifetime so the indicator is correct on the first frame, at the cost of one small Room flow.
- **2 GB Samsung (API 27, Android 8.1, WebView version unknown):** the notebook allocates no bitmaps; a narrower reader pane lowers fixed decode targets (less memory, not more); EPUB inline annotations remain gated by `InlineAnnotationSupport` and the notebook itself is not WebView-gated (bookmarks and position notes always work). Manual LAN-APK UAT only (no ADB: the tablet's Micro-USB data hardware is damaged); the tablet-sized emulator proves layouts, not Samsung memory, and must not be cited as Samsung memory evidence. API 27 does not exercise the API 24/25 `PdfRenderer` special paths.

#### 13.3.8 Internal slicing: two independently reviewable checkpoints

The administrator-approved split is confirmed, with one adjustment from repository evidence: the format-neutral `NotebookPanel`/`NotebookEntry` is introduced in **4C.1** (used by the fixed reader's dialog) so the fixed list UI is not built twice, and **EPUB is ported onto it in 4C.2**. That keeps each checkpoint inside one reader family: 4C.1 never edits `EpubActivity` or the accepted 4B list dialog; 4C.2 isolates the genuinely risky work (Activity-level key handling, Back precedence, navigator-safe layout, EPUB reflow) into the checkpoint that gets the High review. The fixed reader is the easier side-by-side host (the reader is already a composable in the single Activity, measures its own bounds and is fold-aware); EPUB is the harder one (separate Activity, navigator rebuild on re-parenting, no fold code, Activity-level key dispatch), which is why EPUB side by side is gated by the step-1 reflow check.

##### 4C.1 — Fixed-layout annotation capability

- **Goal:** a PDF/CBZ/CBR reader user can bookmark pages, add/edit/delete page notes, see them in a list and jump to them exactly, by touch, keyboard and (via chrome) gamepad, with the data persisting across recreation. Presentation of the list is the DIALOG form only.
- **Scope:** `FixedPageLocator.format` (proposed) and `fixedPageNavigation` (proposed) pure rules; fixed create/orderKey/progress derivation; `FixedAnnotationViewModel` (proposed) with single-flight bookmark toggle, per-page map, editor target; `NotebookEntry` + `NotebookPanel` (proposed, neutral) shown in a dialog from a new **Notebook** chrome button; **Bookmark** chrome button with page-named state; `TOGGLE_BOOKMARK` consumed in the fixed reader (keyboard B); strict navigation with the unavailable-reason rows; EN/ES/PT-BR; accessibility semantics; optional D4 gamepad key.
- **Non-goals:** side-by-side layout, `notebookPresentation`, any `EpubActivity`/4B list change, EPUB `TOGGLE_BOOKMARK`, schema, dependency, handwriting, spread/render/mutex changes, bookmark labels (D2).
- **Implementation areas (existing paths verified; new names proposed):** `domain/annotations/FixedPageLocator.kt` (add `format`); proposed `domain/annotations/FixedPageNavigation.kt`; proposed `feature/reader/FixedAnnotationViewModel.kt`, `NotebookPanel.kt`, `NotebookEntry.kt`; `feature/reader/FixedReaderScreen.kt` (two buttons, key branch, dialog host; additive); `feature/home/ShelfApp.kt` (factory beside the existing one, `container.annotations`); `res/values*/strings.xml` (3 files); `core/input/*` only if D4 is approved. Not touched: `FixedReaderViewModel.kt`, `core/reader/*` render/spread files, `core/database/*`, `data/annotations/*`.
- **Targeted tests:** JVM: `FixedPageLocator.format`/`parse` round trip incl. 0 and `Int.MAX_VALUE`; `fixedPageNavigation` (malformed, negative, fractional/exponent/string page, extra members, wrong version, `page == count - 1`, `page == count`, `count == 0` pending); orderKey/progress derivation and bookmark-vs-note ordering; toggle plan (none → create, one → delete, several for one page → delete all); `InputMapper` unchanged except D4; frozen `naturalCompare` ordering regression if `ReadingPolicyTest` does not already freeze it (unverified: it references `naturalCompare`). Instrumented (one class per Gradle invocation): `FixedPageLocator.format(n) == pageLocator(n)` on device; repository cases through `AnnotationRepository` for fixed BOOKMARK/NOTE (create, dedupe by page, edit, delete, reading order of mixed bookmarks and notes, rejected blank body, rejected `selectedText`, rejected highlight on `FIXED_PAGE_1`); `FixedAnnotationViewModel` (rapid double toggle yields one row; toggle twice yields none; editor target survives ViewModel retention); UI per format (PDF, CBZ, CBR fixtures, existing fixture conventions): bookmark, recreate, still bookmarked, note add/edit/delete, jump from list lands on the exact page; spread-mode binding (`state.page` is the bound page; sibling page not implied) and RTL manga (anchor identical, only placement mirrored); persisted malformed (`{"version":1}`, negative, non-integer) and out-of-range (`page = count`, 9999) anchors inserted with `validateLocator = false`: row listed, jump disabled with reason, edit and delete work, no clamp, reader page unchanged; keyboard B toggles, B in the Appearance/Pages dialogs does not; Back with the notebook dialog open dismisses only the dialog.
- **Acceptance:** bookmarks and notes on all three formats persist and jump exactly; spread/RTL do not change anchors; every action reachable by touch, keyboard and gamepad (D-pad/START/A through chrome); out-of-range/malformed anchors never navigate and never lose data; no regression in the existing resume/spread/thumbnail/fold targeted suites; builds and lint green; no schema/dependency change.
- **Risks:** touching `FixedReaderScreen`'s large composable (additive only; chrome row width; focus restoration after the notebook dialog, same pattern as `dismissAppearance`); the hinge-safe overlay path for the dialog under a vertical fold; the seven direct `FixedReaderViewModel` constructions are avoided by design.
- **Codex review:** standard independent review is sufficient because no render/mutex/lifecycle code changes; escalate to **High** if `FixedReaderViewModel`, the persistence layer or shared input mapping (D4) is touched beyond what is listed.
- **Validation order (progressive, no full regression until 4F):** JVM unit tests for the new pure rules, then the targeted instrumented classes on the RP5 (API 33, WebView 109; primary automated device), then the same targeted classes on the API 24 emulator as the fallback, then `assembleDebug`, `lintDebug` and, because instrumented tests change, `assembleDebugAndroidTest`. Verify result-XML counts after every instrumented run (infrastructure caveats §16: comma-separated class filters, `INSTALL_FAILED_ALREADY_EXISTS` with 0 tests, API 24 watchdog deaths). Manual keyboard (emulator) and controller (RP5) passes. Samsung SM-T580 only at checkpoint acceptance via the LAN-APK manual UAT.

##### 4C.2 — Publication Notebook / adaptive workspace

- **Goal:** the optional reader-local notebook works in all four formats and adapts to the available width: side by side where the window is wide enough, a dialog otherwise, without losing reader state on rotation, resize or fold/unfold.
- **Scope:** (step 1, a bounded check before any layout work) EPUB reflow-on-resize position preservation (§13.3.4 item 5) with its go/no-go recorded in `VALIDATION.md`; `notebookPresentation` pure function; the always-present `Row` host in `FixedReaderScreen` and `EpubActivity` with the end-edge pane; EPUB ported onto `NotebookPanel` (existing 4B behavior preserved: filters, add-here, `canNavigate` gate, UNPLACED rows, editor, delete confirmation) and the existing top-chrome button (relabeled **Notebook**) becoming the toggle; Back precedence (§13.3.5) and the pane key/focus rules; EPUB `TOGGLE_BOOKMARK`; state preservation of the open flag and filter; accessibility and input polish; ADR-0023 dated clarification; documentation updates (READER.md, ANNOTATIONS.md, FOLDABLES.md pointer, VALIDATION.md).
- **Non-goals:** a third (drawer/sheet) presentation, inline editor in the pane, EPUB fold/hinge handling, pane animation, global hub features, polished book/tabletop posture layouts (those stay with the later foldable-polish work in ROADMAP, which this baseline does not replace), handwriting.
- **Implementation areas:** `feature/reader/EpubActivity.kt` (Row host, key-handler flag, `backPress()` pane check, `TOGGLE_BOOKMARK`), `feature/reader/EpubAnnotationUi.kt` (list dialog becomes a thin host of `NotebookPanel`; editor/delete dialogs untouched), `feature/reader/FixedReaderScreen.kt` (Row host, pane Back handler), proposed `feature/reader/NotebookPresentation.kt`, `NotebookPanel.kt` (from 4C.1), `core/input/ShelfCommand.kt` only if the `controlsFocused` rule needs a named parameter, `docs/adr/0023-...md`, strings.
- **Targeted tests:** JVM `notebookPresentation` boundary table (839/840 width, 479/480 height, the table rows in §13.3.4, pane width clamp); EPUB mapper parity with the 4B list behavior (existing `AnnotationNavigationTest` stays green); instrumented on the **tablet-sized AVD** (1200 x 1920 at 240 dpi, API 24 image derived from `shelfos-api24`; or `wm size`/`wm density` on any AVD) and the phone-sized AVD: landscape shows the pane, portrait the dialog, `wm size` live resize across 840 dp switches presentation with the same publication/page/locator, spread and fit preserved, no duplicate notes, no anchor change, unsaved note text preserved across rotation but never saved, committed notes intact; tapping a PLACEABLE EPUB entry lands on the exact anchor with the pane open; UNPLACED entry listed, editable, deletable, jump disabled; fixed entry jumps to the exact page in SPREAD mode and RTL with the pane open; Back precedence per layer (selection, editor/confirm, pane, hidden chrome, visible chrome) on EPUB and fixed for system Back, Escape and gamepad B; keyboard focus enters the pane with Tab and leaves with Left/Tab, arrows in the pane never turn pages; the B key never toggles while a dialog/editor is open; open/close cycles on both readers keep page/locator and zoom (fixed, unchanged page); fold simulation (existing `FixedReader*Foldable*` fold-injection pattern: `folds` parameter) with a vertical hinge over the reader and over the pane; a 500-annotation list scroll check; recomposition-count check of the open pane during page turns (manual Layout Inspector evidence on the RP5).
- **Acceptance:** all of the above; EPUB 4B tests (`EpubAnnotationsTest`, `EpubBookmarkTest`, `EpubBookmarkLocationInstrumentedTest`, `EpubRecreationTest`) still pass unchanged on the RP5; reader remains usable beside the notebook (no control clipped; the reader keeps at least 480 dp); keyboard- and gamepad-only operation of the whole notebook; localized EN/ES/PT-BR with no hard-coded strings; Samsung manual UAT (landscape pane, portrait dialog, rotate with the notebook open, add a note, jump, reopen).
- **Risks:** EPUB navigator rebuild or position drift on resize; Activity-level key handling leaking reader commands into the pane; Back-layer ordering regressions in two readers; focus loss on open/close; per-page-turn recomposition of the pane on EPUB; hinge-safe overlay origin assumption (R4); the 2 GB tablet's WebView relayout cost.
- **Codex review:** **High: yes.** It changes the EPUB Activity's key dispatch and Back path, the reader layout hierarchy around a navigator whose lifecycle is tied to composition position, and the shared Back contract (ADR-0023).
- **Validation order:** JVM first; step-1 reflow check on the RP5; targeted instrumented classes on the tablet-sized AVD (layout/state) and the RP5 (EPUB on WebView 109; the API 24 emulator's WebView 52 does not exercise inline annotations, a known EPUB limitation), then the existing EPUB annotation/bookmark/recreation classes as the regression gate (one class per invocation, check XML counts); `assembleDebug`, `lintDebug`, `assembleDebugAndroidTest`; manual keyboard and controller passes; Samsung LAN-APK UAT last. No full regression until 4F.

#### 13.3.9 Cross-checkpoint validation matrix

| Risk | Evidence required |
| --- | --- |
| Rotation/resize/fold creates a duplicate note | Instrumented: add note, recreate/resize N times, count rows == 1 |
| Anchor changes or page number converts | Instrumented: stored `locatorJson` byte-identical before/after, `Page N+1` displayed |
| Abandoned draft saved | Instrumented: type, rotate, Cancel/Back, count rows unchanged; Save only on explicit press |
| Committed notes lost | Instrumented: counts equal across recreation, process kill (`am kill`) on the RP5 |
| Reading position reset by opening the notebook | Instrumented: page/locator equal after open/close cycles (EPUB: step-1 tolerance) |
| Process death | Documented honestly: open flag, committed notes and reading position survive; unsaved draft does not (D5) |
| Strict navigation | JVM table + instrumented UI (§13.3.8) |

#### 13.3.10 Dependencies and schema

**Dependencies: none required.** No `WindowSizeClass`/`material3-adaptive`, Paging, FTS, JSON or test library (D13 records the one tempting exception and why it is not recommended). **Schema: none.** Room stays at v4, `app/schemas` untouched; the 4A table already holds every field fixed annotations need. A schema or dependency need discovered in implementation is a stop condition (§13.3.14).

#### 13.3.11 Handwriting, pen and ink remain future

Not part of 4C: no strokes in `Annotation.body`, no vector-stroke schema, no freehand drawing over pages, no canvas, no stylus code path; no stylus hardware is available for acceptance. The 4C UI does not make pen support impossible in concrete terms: (1) annotations stay one canonical model whose `kind` and `locatorFormat` are extensible tokens persisted by name (unknown kinds are already skipped and counted, never reinterpreted), (2) `NotebookEntry` is "kind + labels + actions" with no text assumption, so a future ink entry type is a new mapper, (3) the notebook container is a list host that could later host a different entry or a notebook-page surface, and (4) future handwriting should use its own deliberate model (notebook pages plus versioned vector-stroke payloads with page- or content-relative coordinates, per AGENTS.md rule 11) rather than overloading `body`, and would need its own ADR and review. The roadmap's later stylus work (`docs/ROADMAP.md`) is unchanged.

#### 13.3.12 Ponytail review of this plan (manual, 2026-10-10)

Applied `~/.claude/skills/ponytail-review` by hand to this plan's own assumptions and the architecture it touches (it is supplemental; independent Codex review stays separate). The review reads the connected code, finds a concrete failing case for each finding and prefers fixes that delete scope. Result: **Verdict: the plan was changed in 13 places before commit.**

*Over-engineering caught and removed or reduced*
1. A third "drawer/sheet" presentation for medium widths: dropped; medium widths reuse the established dialog (one `NotebookPanel`, two hosts). 2. New repository methods (bookmark-at-page, per-page observation): dropped; `observeForPublication` plus a ViewModel map suffices and the DAO already dedupes bookmarks. 3. Adding annotation state to `FixedReaderViewModel`: replaced by a small separate `FixedAnnotationViewModel`, which also avoids rewriting seven direct constructions in instrumented tests. 4. An inline note editor in the side pane: rejected (typed B/F/Space/arrows would reach reader commands; the dialog editor is already accepted). 5. A separate bookmarks view and "highlights with bodies only": dropped; one list plus the accepted filter chips. 6. `WindowSizeClass`/`material3-adaptive`: not added; `BoxWithConstraints` plus a pure function matches `adaptiveLayout`. 7. Pane animation: dropped (ADR-0023 chrome is instant).
*Kept on purpose (not over-engineering):* `NotebookEntry`/`NotebookPanel` (one panel and two small mappers is less code than two panels, and EPUB and fixed genuinely differ in navigability and labels); the pure `fixedPageNavigation` and `notebookPresentation` (JVM-testable, mirroring `adaptiveLayout`); the always-present `Row` host (required by navigator lifecycle).

*Assumptions found wrong or unverified, and what changed*
8. "`TOGGLE_BOOKMARK` is mapped from gamepad B": wrong (keyboard B; gamepad B is Back); input plan and D4 rewritten. 9. "`orderKey` = page index for fixed pages": would sort every bookmark before every note; replaced by `page/pageCount` for both kinds (§6.2). 10. "Window size classes exist": none do; §11 and the design use the real primitive. 11. "Moving the reader into a side-by-side layout is just a layout change": for `EpubSurface` it rebuilds the navigator; the stable-`Row` rule and the step-1 reflow check were added. 12. "Fixed-page JSON string is reproducible in JVM tests": `org.json` is not available to JVM tests; a pure-Kotlin `format` plus an on-device equality test were added. 13. Samsung/RP5/AVD dp figures are owner-provided and not recorded in the repository, the 840 dp "expanded" convention is not a repository fact, and foldable widths are general knowledge: all marked unverified and the threshold constants isolated in one tunable function (D3).

*Missing tests added:* strict-navigation boundary table (`page == count - 1` vs `count`), spread-mode binding and RTL, persisted malformed/out-of-range rows via `validateLocator = false`, rapid double toggle, B-key leakage into dialogs, Back precedence per layer for both readers and all three Back inputs, open/close cycles preserving page/locator/zoom, recomposition while the pane is open, presentation switch on live resize, draft-not-saved on Cancel/rotation.

*Not checked:* Readium behavior on width change (gated by step 1), TalkBack availability, real controller focus traversal, Samsung WebView version and memory, layout-direction mirroring of the pane.

#### 13.3.13 Administrator decisions

Decided 2026-10-10 by the administrator; all fourteen decisions are now recorded. The Recommendation column keeps the original proposal; the Status column is authoritative.

| # | Decision | Recommendation | Status |
| --- | --- | --- | --- |
| D1 | Approve the 4C.1 / 4C.2 split with the neutral `NotebookPanel` created in 4C.1 and EPUB ported in 4C.2 | Approve | **APPROVED.** 4C.1 = fixed-layout annotation capability; 4C.2 = adaptive Publication Notebook / EPUB integration. The neutral fixed-reader notebook panel may land in 4C.1. |
| D2 | Bookmark labels (`title`) in 4C (open question 2) | No; keep unlabeled as EPUB today | **APPROVED.** Bookmark labels are NOT added in 4C; fixed-layout bookmarks stay unlabeled, matching current EPUB behavior. The existing optional `title` field remains available for future work. |
| D3 | Side-by-side thresholds: width >= 840 dp, height >= 480 dp, pane `clamp(0.36 x width, 320, 400)` dp (vs reusing the Library's 1000 dp) | Approve 840/480/0.36/320-400; constants tunable in one function | **APPROVED** as the initial adaptive contract: side-by-side eligible at usable reader area >= 840 dp wide and >= 480 dp high; notebook target about 36%, width clamped about 320-400 dp. Implementation/validation thresholds, not universal device-class labels; layout is driven by usable width, not phone/tablet identity. |
| D4 | Add a gamepad face-button mapping (for example `KEYCODE_BUTTON_Y`) to `TOGGLE_BOOKMARK` (touches shared input) | Approve as optional; chrome path is the required parity | **DECIDED (differs from recommendation): NO dedicated gamepad face-button bookmark shortcut in 4C.** Keyboard B may toggle bookmark; gamepad users reach Bookmark through focusable reader chrome. Do not overload an existing controller button. |
| D5 | Unsaved note drafts: keep the 4B behavior (survive rotation, not process death) for fixed and EPUB, vs make fixed drafts survive process death; and whether the modal editor (not inline in the pane) is acceptable | Keep 4B behavior; modal editor acceptable | **APPROVED.** An unsaved note draft survives Activity recreation/rotation; process-death recovery of an unsaved draft is NOT required in 4C; committed notes must survive. A modal editor is acceptable for 4C.1 / compact presentation; the expanded notebook may host the same editor inline if one editor implementation can be reused cleanly. |
| D6 | When the window contracts below the threshold with the notebook open: keep it open as a dialog (recommended, continuity) vs close it | Keep open | **DECIDED.** If an open notebook crosses from expanded to compact presentation (rotation/resize/fold), KEEP IT OPEN and transform its presentation; do not dismiss it because a breakpoint was crossed. Preserve active draft/editor state where normal recreation handling permits. |
| D7 | UI term: keep **Notes** (existing string) vs introduce **Notebook** | Keep **Notes** (original; overridden, see Status) | **DECIDED (overrides the recommendation).** The reader-local workspace is called **Notebook** in user-facing UI. **Notes** is kept for `kind=NOTE` annotations, the Notes filter and the future global Notes destination / Phase 4D knowledge hub. NOTE annotations are not renamed to Notebook entries. |
| D8 | Spread bookmark target: `state.page` only (recommended) vs also an explicit control for the sibling slot (open question 6) | `state.page` only; revisit after UAT | **DECIDED.** Fixed-layout bookmark/note creation anchors to the authoritative `FixedReaderState.page`; no separate "lowest visible page" rule. The stored locator is the exact true 0-based source page that state represents. |
| D9 | Highlights listed in the reader notebook: all (recommended, 4B parity) vs only those with bodies; and whether the global hub's Notes filter includes annotated highlights (open question 1, deferred to 4D) | All; defer hub question | **DECIDED.** EPUB Publication Notebook: All = all relevant current-publication annotations; Highlights = ALL `HIGHLIGHT` annotations; Notes = `NOTE` only; an annotated highlight stays a `HIGHLIGHT` and does not also become a `NOTE`; Bookmarks = bookmarks. |
| D10 | Codex review level: 4C.1 standard, 4C.2 High | Approve | **DECIDED (differs from recommendation): both 4C.1 and 4C.2 require a fresh Codex High independent review after Claude implementation.** Claude uses Ponytail during implementation and may run `/ponytail-review` as supplemental self-review; Ponytail does not replace independent QA. |
| D11 | If the EPUB step-1 reflow check fails: ship fixed side-by-side only and keep EPUB dialog-only | Approve as the fallback | **APPROVED fallback.** Start 4C.2 with a bounded EPUB resize/reflow validation. If reliable locator/reading-position preservation needs architecture-heavy redesign, keep the EPUB Notebook in dialog/overlay presentation and ship expanded side-by-side for fixed readers only. Never weaken reading-position correctness to force an EPUB split pane. |
| D12 | Keep "Publication Notebook" as a planning name only | Approve (original; rejected, see Status) | **DECIDED (rejects "planning name only").** "Publication Notebook" is the canonical product/documentation name for the reader-local current-publication workspace. User-facing controls and headers use the shorter **Notebook**; the full phrase is not shown in compact UI. |
| D13 | Do not add `WindowSizeClass`/`material3-adaptive` | Approve (no dependency) | **APPROVED.** No Material `WindowSizeClass`/new adaptive dependency; reuse existing usable-width/`androidx.window` infrastructure unless later evidence proves it insufficient and the administrator separately approves a dependency. |
| D14 | Record side-by-side notes as a baseline delivered in 4C.2 while the polished book/tabletop posture work stays in the later foldable phase (ROADMAP) | Approve | **APPROVED.** Side-by-side Notebook is a baseline capability delivered in 4C.2 when usable-width requirements are met. Ambitious book/tabletop posture work and deeper foldable-specific polish remain future Reader/Foldable hardening work. |

#### 13.3.14 Stop conditions for the 4C implementation agents

Stop and report (do not improvise) if: a schema/Room or dependency change appears necessary; `AnnotationRepository`/DAO needs a new method; `FixedReaderViewModel` render/mutex/lifecycle code, `showPage`/`restorePage`/`pageLocator`, `naturalCompare`, the spread/page-index model or `sanitizeEpubHtml` would have to change; strict navigation would require clamping; the EPUB step-1 reflow check fails (apply D11 only with approval) or `EpubSurface` would have to be re-parented; the letter B or any reader command is observed leaking into a text field or the pane; Back cannot be kept to at most one extra press or any Back path traps the user; a fixed annotation would store anything but the true source page; handwriting/ink/OCR/selection on fixed pages is requested; the pane would require a text field in the main window; Samsung/RP5 evidence shows page-turn jank with the pane open; an instrumented run reports 0 tests or an install failure (infrastructure caveats §16) and is being counted as a pass; a doc/implementation conflict with AGENTS.md appears; or the administrator decisions above are needed to proceed.

#### 13.3.15 4C.1 implementation and acceptance record (2026-10-11; MERGED + ACCEPTED)

Implemented on `phase-4/4c1-fixed-annotations`, accepted after independent High review and Samsung/RP5 validation, then squash-merged to `main` at `0040c52187e50515b7d20608e5ef8e71dacc7809`. Evidence: `docs/VALIDATION.md`, "PHASE 4C.1".

**What exists.** PDF, CBZ and CBR bookmarks and page notes on the canonical annotation layer (Room stays v4; no schema, migration, dependency or `AnnotationRepository`/DAO change) with strict never-clamping navigation and a fixed-reader **Notebook** dialog:

- `domain/annotations/FixedPageLocator.format` (canonical `{"version":1,"page":N}`, equal to the reader's `pageLocator` on device) and `FixedPageNavigation.kt` (pure: `fixedPageNavigation`, `fixedPageOrderKey` = `page/pageCount`, `fixedPageDraft`, bookmark lookup helpers).
- `feature/reader/FixedAnnotationViewModel.kt` (NavBackStackEntry-scoped beside `FixedReaderViewModel`, which is untouched): observes the per-publication list, single-flight bookmark toggle and note creation, editor target captured at open (page and count), body-only edits, delete.
- `feature/reader/NotebookPanel.kt`: format-neutral `NotebookEntry` + `NotebookPanel` (kind, optional 1-based page, body preview, jump availability and reason, filters passed in) and `NotebookDialog` (platform dialog, or `HingeSafeDialogOverlay` when a vertical fold constrains the reader, like Appearance and Pages). 4C.2 can host the same panel in a pane and add Highlights/EPUB entries through a new mapper.
- `FixedReaderScreen`: new optional `annotations` parameter (absent in the existing direct-host tests, which therefore render exactly as before); **Bookmark** and **Notebook** buttons appended to the existing scrollable top chrome row AFTER Hide controls (placing them before Zoom/Hide pushed those off-screen on a 411 dp phone and failed six `NavigationSmokeTest` and two `FixedReaderRecreationTest` tests; on a narrow phone the two new buttons therefore need a horizontal scroll or focus traversal, recorded as a risk); keyboard B consumed only in the reader Column (never while a dialog or text field owns focus); the fixed editor and delete confirmation reuse the shared 4B surfaces.
- `EpubAnnotationUi.kt`: `dismissOnShelfBack` became internal so the Notebook dialog gets the same Back/Escape/gamepad-B handling. The review remediation also changes only the shared editor's input/focus shell: sourced controller A on the focused body moves focus to Save without saving; focused Save/Cancel accept the existing gamepad-confirm behavior. EPUB anchors, decorations, navigation, capability gates and persistence semantics are unchanged.
- Strings in `values`, `values-es`, `values-pt-rBR` (15 new each; parity tests pass).

**Decisions and findings.**

- Bound page is always `FixedReaderState.page`; no "lowest visible page" algorithm was added (D8). Pages/slider selection keeps that exact source page.
- Navigation validates before calling `showPage`; out-of-range and malformed rows stay listed with a localized reason (`Page location unavailable`; `Saved on page P. This publication now ends at page C.`), Edit (notes) and Delete stay available, nothing is clamped, rewritten or deleted.
- Gamepad A: Compose buttons do not activate on `KEYCODE_BUTTON_A` in the original API 24 evidence. The new controls and shared editor Save/Cancel use focused-control-only semantic CONFIRM (`activateOnGamepadConfirm`); A is not globally remapped. In the editor, only an explicitly gamepad/joystick/DPAD-sourced A while the body owns focus is intercepted: down enters focus mode, up releases the text field and requests Save, and both halves are consumed so the first press cannot save. Normal keyboard, touch and IME input are unchanged. Delete confirmation was not redesigned.
- Escape/gamepad B/Back with a focused editor text field are handled by the reused 4B `dismissOnShelfBack`; the Notebook dialog uses the same handler and requests focus when its window gains it (a dialog with no focused element would not receive the key).
- Drafts survive rotation (ViewModel target plus `rememberSaveable` body) and are never auto-saved. They do NOT survive process death (D5); committed annotations do (verified, see VALIDATION).
- Delivered by 4C.2: adaptive side-by-side, `notebookPresentation`, EPUB port onto the panel, EPUB `TOGGLE_BOOKMARK`, and highlights in the Notebook. The 4D hub, export, ink, OCR and PDF text selection remain future work.

#### 13.3.16 4C.2 implementation record (2026-10-11; IMPLEMENTATION COMPLETE, CODEX HIGH PASS; MERGED in PR #37 at `ca47e3d1bee2c3d5426996ab6c76204cd5920131`, reconciled 2026-10-12)

**Final status.** Original implementation `953e9c03fbea854ce1e5562386bded74ae6ce45a`; accessibility/docs remediation `a3d74cba52503b79f753234959416c071617c01e`. Independent Codex High found H1 (EPUB Notebook spoken accessibility semantics lost; restored through separate visual and spoken location representations) and M1 (validation documentation status contradiction; corrected); the final Codex High re-review is **PASS**. Workflow decision: physical/manual UAT is performed once at phase closure, so the Samsung UAT for 4C.2 is **deferred to Phase 4F**, which owns the comprehensive hardware acceptance matrix over the accumulated 4A, 4B, 4C.1, 4C.2, 4D and 4E behavior (checklist not yet written). It is not a merge blocker and no Samsung 4C.2 PASS is claimed. Phase 4 as a whole remains NOT complete or accepted until 4E and 4F finish.

Implemented on `phase-4/4c2-adaptive-notebook` from the accepted 4C.1 merge `0040c52187e50515b7d20608e5ef8e71dacc7809`. The mandatory RP5 gate passed before adaptive UI work: the same Activity, EPUB session and `EpubNavigatorFragment` remained mounted through four pane-equivalent width cycles; one navigator remained; href was unchanged; total progression stayed within 0.01; the resource remained usable. The retained final-UI regression repeats the test by opening/closing the real pane under a forced 1600×1000 at 160 dpi viewport and restores the RP5 to 360 dpi.

The implementation adds the pure 840×480dp adaptive policy with a 36% pane clamped to 320–400dp; stable always-present Row hosts in both readers; the shared saveable filter and format-neutral panel; EPUB NOTE/HIGHLIGHT/BOOKMARK mapping with PLACEABLE/UNPLACED/MALFORMED behavior preserved; the Notebook chrome name; keyboard B bookmark consumption with dialog/text-entry leakage blocked; pane Back/focus ownership; and L1/R1/PageUp/PageDown reading while the pane owns focus. No schema, migration, dependency, repository/DAO, `FixedReaderViewModel`, spread, locator, sanitizer or source-publication change was made. Validation and known baseline caveats are recorded in `docs/VALIDATION.md`.

### 13.4 4D — Global Notes hub

- **Goal:** one central screen to review, search, filter, edit and jump.
- **Scope:** §11 in full; `KEEP` removal semantics with `annotation_source_snapshot` (additive v5 migration + migration test), orphan presentation and explicit group delete; removal dialog choice; retire `BookmarkRepository` adapter; strings and accessibility.
- **Non-goals:** export, FTS, Series aggregation, Smart Shelves, sync, re-association of orphans.
- **Likely areas:** `feature/home/ShelfApp.kt` (Notes destination), proposed `feature/notes/`, `data/annotations/`, `core/database/`, `feature/library/PublicationDetails.kt`.
- **Targeted tests:** repository filter/search/keyset-pagination tests; v4→v5 migration test; keep/delete removal matrix; orphan listing; Compose UI tests for filters/search/jump/unavailable/missing item; large-dataset test (≥10,000 rows); recreation/state restore; keyboard focus traversal; phone/tablet layouts.
- **Acceptance:** §11 behaviors; an unavailable-source annotation remains listed and its jump explains itself; keep-notes removal leaves orphans visible and exportable data intact; large collection stays responsive on the SM-T580/RP5.
- **Risks:** removal-path data semantics; hub query cost; deep-link argument wiring across the EPUB Activity and nav route.
- **Codex High review: yes** for the removal/orphan/migration part; the UI-only portion could be reviewed at standard level — recommend one High review over the slice.

#### 13.4.1 4D implementation record (2026-10-12; MERGED / IMPLEMENTATION COMPLETE)

Implemented on `phase-4/4d-global-notes-hub` from `main` at `ca47e3d1bee2c3d5426996ab6c76204cd5920131` (4C.2, PR #37), then merged in PR #38 at `90b9f1c7fe827c996247a60bcd8cbc224afc72d3`. No physical/manual UAT was run; that remains Phase 4F. At the time of this 2026-10-12 entry Phase 4E was implemented locally and pending independent Codex High review; Phase 4E has since been ACCEPTED (see the header status and §13.5), while Phase 4F has not started and Phase 4 is NOT complete or accepted. Evidence and its device limits (including intermittent RP5 ADB authorization) are in `docs/VALIDATION.md`.

Independent Codex High review returned FAIL with exactly H1 (a stale locator could remain authoritative across Activity recreation) and M1 (rows omitted the §11 cover/timestamps and reduced EPUB location to percentage). The narrow remediation gives the ViewModel saved-state ownership of one-shot launch consumption and the latest accepted locator, and completes the existing row contract with the shared generated cover visual, kind icon/label, stored chapter/Location presentation and locale-aware Created/Updated labels. Final Codex High re-review returned **PASS WITH NOTES**, with no BLOCKER, HIGH or MEDIUM findings; its sole LOW documentation note corrected the recreation-test, API 24 assumption and root-cause wording in `docs/VALIDATION.md`. H1 and M1 are RESOLVED. No accepted 4D area, schema, query, migration, dependency, exact-anchor rule, fixed navigation or reader session architecture changed. Physical/manual UAT remains deferred to Phase 4F.

- **Schema.** Room v5, additive hand-written `MIGRATION_4_5`; table `annotation_source_snapshot(libraryItemId PK, title, creator, category, format)` created for keep-notes removals; no foreign key; no URI/path/hash. `5.json` committed. Migration tests use the repository's real-file raw-SQL pattern (no new test dependency).
- **Removal.** `LibraryRepository.remove(id, policy)`; `KnowledgePolicy.KEEP` added. With saved items the removal dialog shows two explicit actions ("Remove, keep notes" / "Remove, delete notes") and no default (this refines the plan's earlier "default KEEP" wording to the owner rule that KEEP is never implicit); with none it is the unchanged single action. KEEP runs in one transaction: snapshot (only when annotations exist), delete preferences/reading state/item, annotations untouched. DELETE deletes the annotations and any stale snapshot. The last orphan deleted removes its snapshot; "delete notes for this removed publication" is explicit and refuses while the item exists.
- **Hub.** `feature/notes` (`NotesViewModel`, `NotesScreen`); `ShelfApp` only routes. Keyset pages of 100 (no Paging dependency), result count, escaped `LIKE` search over body/selected text/label/publication and snapshot title/creator, kind and publication filters, publication/date sorts with group headers, orphan rows and group delete, edit through the shared `AnnotationEditorDialog`, delete through the shared confirmation. Controls survive recreation (saved state); a new filter/sort/search returns to the top.
- **Open source.** EPUB: optional `annotationId` extra on `EpubActivity` (the existing opening mechanism); the reader resolves the annotation, waits for the anchor verdict of selection-backed ones, and calls the existing `navigateToAnnotation`/`jumpTo` (PLACEABLE only). Fixed readers: optional `annotation` argument on the existing `reader/{id}` route; `FixedReaderScreen` waits for the real page count and uses the strict `fixedPageNavigation` through `showPage` (never `restorePage`'s clamp). Failures show "That spot could not be opened. The note is still saved." and change nothing; the jump is consumed once per open. Orphans offer no Open action.
- **Adapter retirement.** `BookmarkRepository` and `RoomBookmarkRepository` are deleted; the EPUB view model uses `observeEpubBookmarks`/`addEpubBookmark` (extension functions over `AnnotationRepository` keeping the `progress, createdAt, id` order, opaque-locator storage and exact-locator dedupe) and `delete(id, BOOKMARK)`. No reader redesign was needed.
- **Not touched** (per the slice rules): `FixedReaderViewModel`, `showPage`/`restorePage`/`pageLocator`, spread/page identity, `sanitizeEpubHtml`, notebook presentation thresholds, `EpubSurface` host structure. No export, import, FTS, Series/Smart Shelves grouping, metadata work, reattachment, fingerprinting, sync or account code.
- **Decisions for the administrator.** (1) The publication sort uses a temp B-tree per page (about 30 ms per page at 10,000 rows on a fast host); whether that is acceptable on the SM-T580 is a 4F measurement, and the fallback (a stored lower-cased sort title or a cheaper default sort) would be a later migration. (2) Global listings skip rows of an unknown kind/format without counting them (the per-publication readers still count); say if the hub should surface that count. (3) Hub rows hold full note bodies (up to 20,000 characters each) in memory for the loaded pages. (4) The legacy `bookmark` table is still not dropped (after 4F).


### 13.5 4E — Portable Knowledge & Restore

**Status: IMPLEMENTED AND INDEPENDENTLY REVIEWED / ACCEPTED.** Final independent Codex
High verdict: **PASS**. All implementation findings M1–M5 are CLOSED. Canonical chain:
base `87ea509`; initial implementation `ed074f2`; M1–M4 remediation `0e6e98b`; M5
remediation / accepted code HEAD `ecf6f62`.

Implementation uses a dependency-free UTF-8 streaming codec and bounded record inventory,
walks all keyset pages for every export scope, reopens and revalidates the selected SAF
document, binds confirmation to the previewed document, and reclassifies Room truth inside
one v5 transaction before exact-id inserts. The Global Notes hub owns the compact export,
import, preview and result flow. No schema, migration or dependency changed.

The excerpt-to-note audit found the accepted highlight-with-optional-body and
selection-anchored-note flows already satisfy the need: **NO PRODUCTION CHANGES REQUIRED**.
Physical/manual portability acceptance remains Phase 4F.

- **Goal:** human-readable Markdown export plus lossless canonical ShelfOS JSON export and
  safe JSON import/restore through SAF, all offline and user initiated.
- **Scope:** §12 in full; JSON v1 export/import, preview/classification/result, exact-id live
  association, orphan snapshot restoration, Markdown export, three export scopes, failure
  handling and privacy disclosures. Audit excerpt-to-note first and make no production
  change if 4B/4D already satisfies it. Add only a small docs-only future
  dictionary/provider architecture note.
- **Non-goals:** arbitrary Markdown/TXT/PDF-as-note import; Obsidian, Notion,
  Kindle/Kobo/Readwise/Calibre adapters; publication binaries or ordinary Library import;
  full/device/library backup; cloud, accounts or sync; fingerprinting, automatic
  reattachment, filename/metadata matching or manual relink UI; provider/network/UI/API
  key work; metadata enrichment, OCR, ink/handwriting or source-publication modification.
- **Implementation boundary:** prefer `domain/annotations/portable/` for pure canonical
  DTOs, serializer, parser/validator, import classification/plan and result models. Keep
  SAF launchers and presentation in the feature/Android layer; do not couple the format to
  Compose. Add a narrow transactional repository/DAO import path rather than reusing the
  ordinary create path that mints ids/timestamps. Existing Room v5 is expected to suffice
  through `annotation` and `annotation_source_snapshot`; do not plan v6 without concrete
  proof. No new dependency is planned; platform JSON streaming is sufficient unless
  implementation evidence proves otherwise.
- **Targeted tests — JSON export:** schema v1; every annotation kind; live/orphan sources;
  Unicode/emoji/RTL and hostile strings; byte-exact malformed persisted locator; exact
  timestamps/ids; no URI/path/publication/cover bytes; publication arrays ascending by id
  and annotations ascending by id, stable across different database insertion orders. A
  domain-valid style key unknown to the current palette must export → import → re-export
  exactly. A filtered result larger than one repository page must export every matching row
  for JSON and Markdown, including rows never loaded/visible in the UI.
- **Targeted tests — document validation:** invalid JSON, wrong schema, unsupported version,
  missing required fields, repeated publication `libraryItemId`, repeated annotation id
  anywhere in the file, unknown kind/locator/category/format, and domain-invalid content all
  reject the whole document. Unknown member names at root/publication/annotation are ignored
  when known fields remain valid. Known-format malformed locator is preserved; unknown
  locator format rejects.
- **Targeted tests — local classification/import:** exact-id live match; unmatched source →
  orphan; identical local annotation duplicate skip; divergent content conflict/no
  overwrite; same annotation id under a different `libraryItemId` is a conflict with no
  move/overwrite/copy; ids/timestamps/locator/style remain exact; idempotent import and
  canonical re-export. Pre-existing orphan snapshot with same context is reused; different
  context preserves the local snapshot, reports a mismatch and still imports eligible NEW
  rows. Exact live item plus stale snapshot uses live metadata and imports no snapshot
  context; both `sourceRemoved` values leave current Room live/orphan classification
  unchanged. A previewed NEW row changed locally before confirmation aborts the entire
  transaction with zero partial rows.
- **Targeted tests — additive/limits:** partial or filtered reimport leaves local annotations,
  snapshots and LibraryItems absent from the file untouched. Reject over 64 MiB, over
  10,000 groups and over 100,000 annotations; accept boundary values where practical; an
  unknown-length SAF stream still trips the byte counter. Retain realistic large-library,
  rollback-on-invalid/failing-import and database-failure coverage.
- **Targeted tests — Markdown/SAF:** Unicode, minimal escaping, hostile Markdown, orphan
  headings, excerpts/body and readable deterministic output; `CreateDocument` and
  `OpenDocument` success/cancel/failure; active-workflow-only import permission; no
  sidecar/network and unchanged source hash. A failed export reports failure, attempts
  deletion of its new destination, emits no success, never truncates, and leaves the source
  publication untouched. Run targeted tests only, not the full Phase 4 regression.
- **Acceptance:** §12 contract and tests pass; import is bounded, atomic, additive,
  idempotent and never guesses publication identity; JSON round-trips every currently valid
  canonical annotation; Markdown stays clean and human-readable; user-selected locations
  only; no publication mutation.
- **Risks:** an external contract is hard to revise; hostile/large input; exact opaque
  locator preservation; collision/conflict classification; transaction size; Markdown
  escaping; sensitive exported content.
- **Codex High review: required, fresh and independent.** Focus on round-trip fidelity,
  validation, conflicts, atomicity, privacy, no identity guessing, orphan restoration and
  source immutability.

### 13.6 4F — Acceptance

- **Status (2026-10-15): ACCEPTED. Phase 4: ACCEPTED, COMPLETE on merge of the Phase 4F acceptance PR. Final independent Codex High closure review: PASS WITH NOTES (0 BLOCKER / 0 HIGH / 0 MEDIUM; LOW documentation findings only).** Owner Samsung Galaxy Tab A SM-T580 physical UAT: **PASS**, with no blocking defect found on the accumulated checklist (final APK from `16635bd`). Owner Retroid Pocket 5 physical/controller UAT: **PASS**, closing the actual-hardware-controls gap. Evidence, APK SHA-256 and checklist scope are in `docs/VALIDATION.md` ("PHASE 4F — FINAL OWNER PHYSICAL UAT"). Resolved in `2c9e772`: the EPUB bookmark locator race and first-Back/IME draft loss. Resolved in `16635bd`: the short-landscape Notes hub layout and its nine RP5 test failures (`NotesHubUiTest` 20/20). Physical TalkBack remains unverified, covered only by available semantic/accessibility automation. The short-window editor Save/Cancel limitation (hide the keyboard to reach them) and other documented evidence limits remain. Administrator decision: Room stays at v5; the legacy `bookmark` table is intentionally retained through Phase 4 closure as a migration-safety artifact; removal is deferred to a future dedicated maintenance migration after Phase 4 acceptance; no v6 migration in 4F.
- **Goal:** accumulated regression and physical UAT; the only slice that runs the full regression.
- **Scope:** full JVM suite; connected suites run **one class per invocation**; `assembleDebug`,
  `assembleDebugAndroidTest`, `lintDebug`, `bundleDebug`; migration validation from a real
  Phase-3 install; physical UAT (§17); documentation reconciliation (ROADMAP, ANNOTATIONS.md, ARCHITECTURE §16, READER.md, PRODUCT status) and, as a separate maintenance migration after Phase 4 acceptance (retained through Phase 4 closure; no v6 in 4F), the drop of the legacy `bookmark` table.
- **Non-goals:** new features.
- **Acceptance matrix:** migration safety; persistence and restart/process death; reopen; missing/unavailable source; deletion semantics (DELETE and KEEP); EPUB highlight restoration; fixed-page anchors (PDF/CBZ/CBR, spread, RTL); jump-to-source; large collections; hub search/sort/grouping; EN/ES/PT-BR; accessibility; touch/keyboard/controller; phone/tablet layouts; source immutability (hash before/after); Markdown export; canonical JSON export → import round-trip, including exact live association and orphan restoration.
- **Codex High review: PASS WITH NOTES** — final independent review of the accumulated diff and evidence found no BLOCKER, HIGH or MEDIUM issue; the LOW documentation findings were resolved in the closure documentation commit. Known accepted limitations (physical TalkBack unverified, no dedicated foldable hardware, limited SAF-provider diversity and endurance testing, manual CBR source hash not captured, old Phase 3 APK not byte-verified against a tag, short-window editor Save/Cancel may need the IME hidden) are recorded in `docs/VALIDATION.md`. The legacy `bookmark` table is retained at Room v5 as a migration-safety artifact, not blocking debt.

## 14. Per-slice dependencies

| Slice | Depends on | Notes |
| --- | --- | --- |
| 4A | none | Gate for all schema consumers |
| 4B spike | none (no schema use) | May run in parallel with 4A; throwaway or isolated branch; result must be recorded before 4B proper |
| 4B | 4A merged + spike go | |
| 4C.1 | 4A merged | Fixed readers only; no Readium; touches neither schema nor `FixedReaderViewModel` (section 13.3.8) |
| 4C.2 | 4C.1 and 4B merged | EPUB integration, adaptive notebook, Back/key changes in both readers; needs the Back clarification in ADR-0023 |
| 4D | 4A, preferably 4B and 4C merged | The hub needs real kinds; if 4B is no-go it proceeds with bookmarks, page notes and EPUB position notes |
| 4E | 4D | Reuses the hub's query/scope selection |
| 4F | all | |

## 15. Recommended order and parallelism

Historical order: `4A` → (`4B-spike` ∥ `4C`) → `4B` (if go) → `4D` → `4E` → `4F`. Remaining order after the merged 4D: 4E → 4F. Disagreements with the
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

**4C addendum (planning):** the SM-T580 cannot be driven by ADB (damaged Micro-USB data hardware), so 4C.1/4C.2 acceptance on it is a manual UAT with a reviewed debug APK installed over the LAN (as 4B); the RP5 is the primary automated device and the API 24 emulator the fallback. A tablet-sized emulator (1200 x 1920 at 240 dpi, or any AVD with `wm size`/`wm density`) can exercise the side-by-side and portrait layouts automatically, but proves layout and state only, not Samsung memory or WebView behavior. No foldable hardware is documented; fold behavior uses the existing fold-descriptor injection tests and emulator posture simulation.

UAT scope remains Phase 4F; no manual UAT is required during 4E implementation. Its final
checklist will include a simple portability path: export → import → verify notes,
highlights and bookmarks → verify orphan groups → verify exact-id live association where
applicable. The complete Phase 4F checklist is intentionally not written in this planning
slice.

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
annotations; ink/handwriting, pen, stylus and freeform drawing (4C's notebook explicitly excludes them, section 13.3.11); comic panel-region highlights;
coordinate-based PDF markup; Adapted PDF reconstruction and SourceMap mapping; any source
mutation or publication embedding; Series/omnibus annotation aggregation; Smart Shelves;
online dictionary integration; metadata or cover enrichment implementation; automatic
re-association of annotations to re-imported publications; content hashing; arbitrary
Markdown/TXT/PDF-as-note import; Obsidian, Notion, Kindle, Kobo, Readwise or Calibre
adapters; publication-binary/library/device backup; cloud/account sync; manual relink UI;
unrelated Library/Reader redesign. Canonical ShelfOS JSON knowledge import/restore is in
4E, but it must not be described as full backup or device migration. Metadata and cover
enrichment are important future work but are not authorization to broaden Phase 4.

## 20. Risks and unknowns

- Readium runtime behaviors listed in §9.5 (selection locator completeness, decoration
  restoration timing, WebView 52/SM-T580 WebView behavior, scale, interaction with chrome tap and Back).
- Sanitizer coupling of DOM-derived anchors (§6.1).
- Migration defects on real owner data; mitigated by retained legacy table, in-migration verification and device upgrade test.
- FK-less table integrity (§8.2).
- Source replacement without detection (§7.4) — documented, not solved.
- Fixed reader state complexity (`FixedReaderViewModel`); 4C avoids it by design (section 13.3.3).
- 4C: EPUB reflow/position on pane resize, Activity-level key handling vs an in-window pane, Back-layer regressions, hinge-safe overlay origin assumptions (section 13.3.8, R-items in 13.3.4).
- Selection accessibility with TalkBack/keyboard/gamepad is unverified.
- Hub performance on a 2 GB-RAM tablet.
- Markdown export escaping.
- Portable JSON contract compatibility; hostile or very large input; transaction size;
  duplicate/conflict classification; lossless locator/timestamp/id round-trip; accidentally
  treating snapshot metadata as publication identity.
- Unknown WebView version on the SM-T580.

## 21. Stop conditions (an implementation agent must stop and report)

- The spike fails a go criterion (§9.5), or any ShelfOS-owned JavaScript appears necessary.
- A migration test shows any bookmark loss, or Room's generated schema disagrees with the hand-written SQL.
- Any change would write to a publication file or create a sidecar next to one.
- A new dependency (Paging, room-testing, serialization, FTS) seems necessary — stop and request the AGENTS.md dependency review.
- A slice needs a schema change beyond its stated scope.
- 4E appears to require Room v6, partial-commit batching, publication identity inference,
  locator repair/reconstruction, or a non-canonical import adapter — stop and reconcile the
  plan before implementation.
- 4E cannot satisfy the 64 MiB/10,000-group/100,000-annotation guards with bounded memory,
  complete preflight and one atomic commit using current/platform capabilities — stop and
  review the architecture; do not weaken a safety property or add a dependency implicitly.
- `sanitizeEpubHtml`, `naturalCompare`, `pageLocator`/`restorePage` semantics or the spread/page-index model would have to change.
- Removal would delete annotation data without the disclosed, user-confirmed choice.
- Any network call would be required for annotation CRUD, review, jump, export or import.
- An unresolved doc/implementation conflict or a conflict with AGENTS.md rules.
- Validation shows the infrastructure traps in §16 (0 tests run, install failures) — report, do not claim a pass.
- Product questions below are needed to proceed.

Open product questions for the administrator (4C answers and recommendations are decisions D1-D14 in section 13.3.13; 4C re-opens (2) and (6) there): (1) should the Notes filter include annotated highlights; (2) are bookmark labels in 4C scope; (3) final palette tokens; (4) default `KEEP` on removal; (5) whether to adopt `MigrationTestHelper` (dependency) instead of the repo's raw-SQL convention; (6) fixed-layout spread bookmark indicator rule.

## 22. Agent workflow per slice (recommended)

1. Administrator authorizes one slice.
2. **Claude** — first implementation of the slice → targeted validation → local commit and handoff (no push).
3. **Codex High** — independent QA for meaningful architecture, persistence or reader changes (4A, 4B, 4C.2, 4D, 4E, 4F; 4C.1 standard review, escalate if it touches render/lifecycle/persistence/shared input).
4. **ChatGPT administrator** — triage of findings.
5. **Fresh lower-cost Codex** — narrow/mechanical remediation by default.
6. **Claude** — remediation only when architecture, lifecycle, schema or ownership must change.
7. Focused re-validation → fresh independent review where warranted → push → PR → merge.

A reviewer must not implement and then self-approve its own remediation. Commits stay local
until the owner pushes; no AI co-author trailers (owner rule).
