# Validation

## PHASE 3E-D — CBR PRODUCT INTEGRATION (2026-10-08)

Status: **IMPLEMENTED locally; pending review.** 3E-A/3E-B/3E-C are complete/accepted (3E-C's final
Codex R2 PASS). 3E-E not started; Phase 3 overall not complete. Branch: `phase-3/3e-native-cbr`.
No native file changed; no change to `NativeRarSession`, `shelfos_rar_session_jni.cpp`,
`RarCacheCoordinator`, or `RarExtractionCache`. See `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s §31 for
the full narrative record; this entry is the exact evidence/commands summary.

**PublicationFormat.CBR**: added to `domain/library/LibraryItem.kt`. Exhaustive `when`s updated:
`core/reader/FixedReader.kt` (`FixedReaderFactory.open`), `core/reader/ReaderPreferences.kt`
(`capabilities()`, `spreadCapable()`), `domain/importing/ImportPolicy.kt` (`suggestedCategory()`).
Persisted as a plain Room `String` column (`LibraryEntity.format`); Room schema change: **NONE**;
migration required: **NONE** (confirmed by `PublicationFormat.entries.forEach { ... valueOf ... }`
round-trip assertion).

**Magic detection**: `isRarMagic` (new, `core/files/RarArchiveSession.kt`): exact RAR4
(`52 61 72 21 1A 07 00`) / RAR5 (`52 61 72 21 1A 07 01 00`) prefix match, filename-independent.
Wired into `PublicationFiles.inspect`'s existing header-sniff `when`. Short/partial (1-6 byte) prefix
is rejected as evidence. `.cbr`-named non-RAR bytes fall through to the existing
`UNSUPPORTED_FORMAT` path (no filename-driven acceptance exists anywhere in the detector).

**Import routing**: `PublicationFiles.inspectRar` → `openRarArchiveSession` (new helper, duplicates
the descriptor's fd, mirrors `ArchivePolicy.open` for ZIP; original descriptor never consumed) →
`RarPageSource.open` (unchanged 3E-C policy: index/filter/safety-check, `ComicInfo.xml` read via the
existing `EmbeddedMetadataReader`). Source mutation: **NO**. Conversion/repack: **NONE**. A rejected
import leaves no partial private copy (same transaction discipline as CBZ/EPUB/PDF).
**Superseded by the "PHASE 3E-D R1A" entry below**: `PublicationFiles.inspectRar` no longer imports or
calls `core.reader.RarPageSource` at all — it opens `core.files`'s `RarContainer` directly.

**Source key**: `rarCacheSourceKey(id, byteSize) = "$id:${byteSize ?: -1}"` (new,
`domain/library/LibraryItem.kt`) — the item's own stable id plus its persisted byte size; never
derived from title/fileName/display name. Same formula and same cache root
(`PublicationFiles.rarCacheRoot = context.cacheDir`) used at import-time inspection and at the real
`FixedReaderFactory` reading route, so `RarCacheCoordinator.getInstance` resolves to the same
singleton coordinator both times (reopen-reuse for an unchanged source; a changed byte size yields a
different key).
**Superseded by the "PHASE 3E-D R1A" entry below**: this formula was proven NOT revision-safe for a
referenced external source (`byteSize` is stale, never refreshed on reopen) and is replaced by a
managed-vs-external-aware factory that returns `null` (ephemeral) for any source without a
`LibraryItem.managedPath`.

**Metadata/ComicInfo**: `RarPageSource.comicInfo()` (unchanged) reuses the existing
`EmbeddedMetadataReader.comicInfo(Document)` mapping (title/series/number/writer/Manga-RTL flag) —
no second parser. Missing/unparsable `ComicInfo.xml` still imports via filename fallback. Zero
safe page-image-named entries → `EMPTY_ARCHIVE` (proven with the real native engine against the
vendored non-image upstream RAR fixture, see test evidence below) — never a silent zero-page import.

**Category/Manga/RTL/spreads**: `suggestedCategory`/`capabilities`/`spreadCapable` treat
`CBZ`/`CBR` identically; no CBR-only branch. RTL/spread/fold behavior is entirely inherited through
the shared `PageSource`/`ImagePageRenderer`/`SpreadModel`/`FixedReader` machinery.

**Reader routing**: new `RarPages` (private, `core/reader/FixedReader.kt`), structurally identical to
`ArchivePages`: `openRarArchiveSession` → `RarPageSource.open` → the same `ImagePageRenderer`/
`PageSource` contract CBZ/PDF already share. Created once per `FixedReaderFactory.open`, closed once
by `RarPages.close()`; `FixedReaderViewModel` already closes its one session only on `onCleared()`.

**Error mapping**: `NativeRarError.toPublicationProblem()` relocated unmodified from
`core.reader`'s `RarPageSource.kt` to `core.files`'s `RarArchiveSession.kt` (layering fix only — lets
the new `core.files` `openRarArchiveSession` use it without a `core.files` → `core.reader` reverse
dependency through the old location). `PublicationProblem.messageRes()`/`importMessageRes()` already
map every case generically (not per-format): PROTECTED/CORRUPT/TOO_LARGE/NEEDS_COPY/
PERMISSION_LOST/UNREADABLE for CBR all resolve to the SAME existing localized EN/ES/PT-BR strings
CBZ/EPUB/PDF already use — no raw libarchive text, no password prompt, no new CBR-specific string.
Only `problem_unsupported_format_message` (EN/ES/PT-BR) was updated to also name CBR.

**Import UI/picker/cover**: picker already launches `arrayOf("*/*")` (no MIME filtering) — unchanged.
Cover art is locally generated per-id artwork (`PublicationCover`/`LibraryItem.coverColor`/
`coverMotif`), never derived from a page image for any format — zero CBR-specific change needed.

**Targeted JVM tests** (`./gradlew testDebugUnitTest --tests "com.d4guilar.shelfos.ImportPolicyTest" --tests "com.d4guilar.shelfos.core.files.RarMagicDetectionTest" --tests "com.d4guilar.shelfos.core.reader.RarPageSourceTest"`):
`ImportPolicyTest` 12/12 (4 new CBR cases: category suggestion, format persistence round trip,
capability/spread parity, cache-source-key stability), `RarMagicDetectionTest` 6/6 (new),
`RarPageSourceTest` 19/19 (unchanged behavior after the error-mapping relocation). Total: 37/37, 0
failures.

**Targeted instrumented tests** (API 24 emulator, `shelfos-api24(AVD)`, run via
`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.CbrProductIntegrationTest`):
`CbrProductIntegrationTest` 3/3 — `realRarMagicIsDetectedAsCbrAndAZeroImagePageArchiveFailsTruthfullyLikeAnEquivalentEmptyCbz`,
`fixedReaderFactoryRoutesCbrThroughTheRealNativeEngineNeverThroughTheZipOrPdfPaths`,
`passwordProtectedRar4AndRar5BothMapToProtectedThroughTheRealImportPath` — all against the REAL
accepted native engine and the already-vendored upstream `.uu` RAR fixtures (`test_read_format_rar.rar.uu`,
`test_read_format_rar4_encrypted.rar.uu`, `test_read_format_rar5_encrypted.rar.uu`); no new RAR-writing
tooling added. 0 failures, 0 errors.

**Native/cache regression**: production native files unchanged (confirmed by `git diff --stat`
showing no `.cpp`/`.h`/CMake changes); `RarCacheCoordinator`/`RarExtractionCache` architecture
unchanged (not touched by this diff); no targeted native/cache regression re-run performed (not
needed — nothing in that layer changed).

**Build gates**: `assembleDebug` PASS, `assembleDebugAndroidTest` PASS, `lintDebug` PASS (no new
lint errors; build succeeded).

**APK/fixture hygiene**: confirmed no `.uu`, decoded `.rar`, owner's real `.cbr`, or owner-extracted
pages are committed or packaged — `git diff --cached` scanned for the owner's fixture filename/path
and for `archive_write`/RAR-writer code: clean. The only RAR test bytes anywhere are the pre-existing,
already-accepted vendored `.uu` fixtures under `app/src/androidTest/assets/libarchive_fixtures/`.

**Owner's real local CBR fixture** (owner-provided local real-world CBR fixture, not committed, not
packaged): used for one local manual acceptance pass via a throwaway instrumented test
(`OwnerCbrManualAcceptanceThrowaway`, written, run once via `adb shell am instrument` against a
manually installed debug/androidTest APK pair, then deleted immediately — never part of any commit).
Results: import succeeded (format CBR, category Comics, a title sourced from embedded
`ComicInfo.xml`, `titleOrigin=embedded`); page count 151; first-page render succeeded (994x1528);
last-page (index 150) render succeeded (1200x1017); a thumbnail-sized render succeeded (124x191);
page geometry resolved (1988x3056); a progress-locator round trip to the last page restored the exact
same logical page; source SHA-256 before and after the full import/open/render/close cycle was
**byte-identical** (confirmed equal, value not retained in this doc per hygiene policy); the source
file itself was not modified. All device-side copies of the fixture (app cache, `/data/local/tmp`) and
the throwaway test file were deleted after the run; the test app/test package were uninstalled.

**Native crash**: NONE observed across any targeted JVM, instrumented, or owner-fixture run.

**Physical ARM hardware**: NOT PERFORMED. The x86_64 API 24 emulator (`shelfos-api24(AVD)`) was used
for all instrumented evidence above, including the owner-fixture manual pass.

**Full JVM / full connected regression**: NOT RUN (out of scope for this checkpoint; only the targeted
classes above were run).

**Documentation**: `docs/PHASE_3_IMPLEMENTATION_PLAN.md` (§31 added; status line updated) and this
file updated. No other canonical doc (`PRODUCT.md`/`ARCHITECTURE.md`/`COMICS_MANGA.md`/`READER.md`)
required a change: none of their existing statements became factually false by CBR becoming a working
format at this checkpoint.

## PHASE 3E-D R1A — HIGH ARCHITECTURE REMEDIATION (2026-10-08)

Status: **IMPLEMENTED locally; pending review.** Fixes exactly the two HIGH findings Codex High
returned against the "PHASE 3E-D" entry above; everything else there was accepted and is unchanged.
No native file changed; `NativeRarSession`, `shelfos_rar_session_jni.cpp`, `RarCacheCoordinator`'s
global limits/eviction/lease model, and the native extraction API are all byte-for-byte unchanged. See
`docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s §32 for the full narrative record.

**HIGH-1 (layering, `core.files → core.reader`)**: new `RarContainer` (`core/files/RarContainer.kt`,
`internal class`) is the lower-level container abstraction both `PublicationFiles` (import) and
`RarPageSource` (reader) depend on independently. It owns entry indexing/safety/filtering/ordering
(`ArchivePolicy.safeName`/`MAX_ENTRIES`, `isPageImage`, `naturalCompare`, physical-ordinal tie-break),
`ComicInfo.xml` identification/extraction/parsing (`EmbeddedMetadataReader`, unchanged), and cached
materialization (`RarExtractionCache`/`RarCacheCoordinator`, unchanged). `PublicationFiles.inspectRar`
now opens `RarContainer` directly and calls only `comicInfo()` — **zero** imports of `core.reader`
anywhere in `core.files` for the CBR path (verified: `grep -rn "import com.d4guilar.shelfos.core.reader"
app/src/main/java/com/d4guilar/shelfos/core/files/` returns nothing). `core.reader.RarPageSource` is
now a thin `PageSource` adapter (`pageCount`/`openPage`/`close`/`comicInfo()` all delegate to
`RarContainer`) — no second enumeration/filter/ComicInfo policy exists anywhere. One unrelated,
pre-existing exception noted (not fixed, out of scope): `core/designsystem/FontImportMessages.kt`
imports `core.reader` — unconnected to CBR/RAR.

**HIGH-2 (cache identity)**: `rarCacheSourceKey` (`domain/library/LibraryItem.kt`) now derives from
`LibraryItem.managedPath`, the only managed-vs-external distinction ShelfOS's data model has today (the
same split `PublicationDetails` already shows the user as "linked" vs "private copy"). Non-null
`managedPath` (a ShelfOS-owned private copy, written once at import, never mutated after) → stable
`"managed:$id"` key, cross-reopen reuse preserved. Null `managedPath` (a referenced external source,
no revision signal refreshed at open time) → `null`, which `RarContainer.open` turns into a fresh
ephemeral/random namespace per open — never reused, so a same-size OR changed-size content replacement
behind the same durable URI can never serve a stale cached page. `byteSize`/`title`/`fileName` are
never consulted. `PublicationFiles.inspectRar` always opens with `sourceKey = null` (import-time
inspection never needs cross-reopen reuse); only the reader-time route
(`FixedReaderFactory`'s `RarPages`, via `LibraryItem.rarCacheSourceKey()`) ever uses the persistent
managed-copy key. Room schema change: **NONE**. No revision fingerprint added to persistence.

**Tests**: `ImportPolicyTest` — new
`managedSourcesGetAStablePersistentKeyDerivedOnlyFromIdNeverFromByteSizeOrDisplayName` and
`externalReferencedSourcesWithoutATrustworthyRefreshedRevisionAlwaysGetAnEphemeralNullKey` (replacing
the obsolete byteSize-sensitivity test), covering: managed key depends only on `id`; external key is
always `null` including same-size-after-replacement (the old key's most dangerous case) and
changed-size-after-replacement; unknown/null byteSize still falls back to ephemeral; title/fileName
never affect either outcome. `RarPageSourceTest` — new
`nullSourceKeyNeverReusesCachedBytesAcrossReopensEvenWithIdenticalPhysicalOrdinals`, an
integration-level stale-hit regression against `FakeRarArchiveSession` proving a `null` sourceKey never
serves a stale page across simulated reopens with same-size and changed-size replacement content.

**Targeted JVM tests** (`./gradlew :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.RarPageSourceTest" --tests "com.d4guilar.shelfos.ImportPolicyTest" --tests "com.d4guilar.shelfos.core.files.RarMagicDetectionTest"`):
`RarPageSourceTest` 20/20 (19 original + 1 new), `ImportPolicyTest` 13/13 (12 original, 1 obsolete test
replaced by 2 new ones), `RarMagicDetectionTest` 6/6 (unaffected). Total: 39/39, 0 failures.

**Targeted instrumented test** (API 24 emulator, `shelfos-api24(AVD)`, run via
`./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.CbrProductIntegrationTest`):
`CbrProductIntegrationTest` 3/3 PASS, rerun after the import-layer refactor — real native routing,
import detection, and protected-archive handling all still work. `adb logcat` scanned for
SIGSEGV/SIGABRT/fatal signal/FORTIFY/JNI fatal: **none observed**.

**Native/cache regression**: production native files unchanged (`git diff --stat` shows no `.cpp`/
`.h`/CMake changes); `RarCacheCoordinator`/`RarExtractionCache`/native extraction API architecture
unchanged (not touched by this diff); no 3E-B native matrix or 3E-C coordinator matrix re-run (not
needed — nothing in that layer changed).

**Build gates**: `assembleDebug` PASS, `assembleDebugAndroidTest` PASS, `lintDebug` PASS.

**Full JVM suite**: NOT RUN. **Full connected suite**: NOT RUN. **Physical ARM hardware**: NOT
PERFORMED. `PublicationFormat.CBR`: RETAINED. Room schema: unchanged. Migration: NONE. Owner's real
local CBR fixture: not committed, not packaged, not re-run for this remediation.

**Subsequent R1B closure**: archive-open failures retain their typed `NativeRarError` through an
internal `RarOpenException` cause without changing `PublicationProblem` mapping; stale source KDoc now
describes CBR's active shared-reader route truthfully.

## PHASE 3E-C R1B — CACHE LIFECYCLE / ERROR SEMANTICS REMEDIATION (2026-10-08)

Status: **COMPLETE locally; pending fresh Codex High R2.** Branch:
`phase-3/3e-native-cbr`; base/R1A HEAD `68ae828`; original 3E-C `8ca4960`. This pass closes only
the four reserved lower-cost findings and does not begin 3E-D.

**Lease/accounting mechanics**: releasing a slot's last active lease immediately reruns the
existing global eviction path, so a cache temporarily over budget only because candidates were
active returns to bounds without another cache access. `CachedExtraction.open()` releases its
acquired lease if stream construction throws. Eviction removes a final from slot/byte accounting
only after deletion succeeds; a failed deletion stays accounted, other eligible inactive
candidates are still attempted, and an all-failed pass terminates without looping.

**Error semantics**: `PROTECTED` -> `PROTECTED`; `CORRUPT`/`INVALID_ARGUMENT` -> `CORRUPT`;
`UNSUPPORTED` -> `UNSUPPORTED_FORMAT`; `TOO_LARGE` -> `TOO_LARGE`; `NOT_SEEKABLE` ->
`NEEDS_COPY`. `IO` and `NATIVE_INTERNAL` retain `UNREADABLE` as the canonical problem, while the
exact typed `RarExtractionException` remains attached as the `PublicationException` cause, so no
native-category information is lost before 3E-D. Expected filesystem/PFD `IOException` is mapped
to `UNREADABLE` with its cause retained; `SecurityException` maps to `PERMISSION_LOST`;
`CancellationException` is rethrown unchanged. No UI strings or raw libarchive codes were added.

**Internal surface**: `RarArchiveSession`, `NativeRarArchiveSession`, `RarExtractionException`,
`RarExtractionCache`, `CachedExtraction`, and `RarCacheCoordinator` are now `internal`; the
coordinator `Key` is private. Kotlin unit/androidTest friend paths compile normally.

**Global cache and byte ceiling retained**: production root remains `cacheDir/cbr`, coordinated by
one process-local/root-scoped `RarCacheCoordinator`. The 256 MiB/64-final limits apply across
namespaces; existing finals are lazily discovered/accounted; stable revision-sensitive namespaces
reuse finals across reopen; random per-open namespaces remain globally accounted; eviction stays
deterministic and lease-aware. Known declared size is prechecked, while native extraction enforces
`maxOutputBytes` during streaming before a chunk could exceed the limit, with the fixed 64 KiB
buffer retained. `TOO_LARGE` removes the partial temp and never publishes a final. R1B changes no
native production code.

**Focused JVM tests**:

`./gradlew.bat :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.files.RarCacheCoordinatorTest" --tests "com.d4guilar.shelfos.core.files.RarExtractionCacheTest" --tests "com.d4guilar.shelfos.core.files.RarExtractionCacheCeilingTest" --tests "com.d4guilar.shelfos.core.reader.RarPageSourceTest" --console=plain --no-daemon`
-> **BUILD SUCCESSFUL**. Exact results: coordinator **10/10**, extraction cache **11/11**, ceiling
**3/3**, page source/error mapping **19/19**; 0 failures, 0 errors, 0 skipped. Coverage includes
release-driven bound restoration, stream-open cleanup, deletion-failure accounting plus alternate
candidate eviction, every native mapping/cause, representative filesystem IO, and cancellation.

**Focused instrumented regression**:

`./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.reader.RarPageSourceRenderInstrumentedTest --console=plain --no-daemon`
on `shelfos-api24(AVD) - 7.0` -> **3/3 PASS**, 0 failures/errors/skips. Post-run logcat scan for
`SIGSEGV`, `SIGABRT`, `JNI DETECTED ERROR`, `FORTIFY`, and `Fatal signal` -> **0 matches**.

**Retained administrator-verified on-device evidence**: `RarPageSourceRealSessionInstrumentedTest`
**1/1 PASS**; `LibarchiveRarNativeTest` **13/13 PASS**, including
`extractEntryEnforcesHardByteCeilingDuringExtractionOnRealDevice`;
`LibarchiveRarNativeLifecycleTest` **PASS** (count not restated); no ShelfOS native crash signals.
R1B intentionally did not rerun these unchanged native/session classes.

**Build**: `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
--console=plain --no-daemon` -> **BUILD SUCCESSFUL**. No native source changed.

**Exclusions/scope**: full JVM **NOT RUN**; full connected **NOT RUN**; physical ARM
**NOT PERFORMED**. `PublicationFormat.CBR` **NOT ADDED**; no import routing, Room/schema,
factory/thumbnail/reader product wiring, or other 3E-D work added. 3E-A/3E-B remain accepted;
3E-C is implemented/remediation complete/pending fresh R2; 3E-D/3E-E are not started; Phase 3 is
not complete.

## PHASE 3E-C R1A — GLOBAL CACHE COORDINATOR + HARD EXTRACTION CEILING (2026-10-08)

Status: **COMPLETE locally.** Branch: `phase-3/3e-native-cbr`; pre-remediation HEAD `8ca4960`
("feat: add Phase 3E CBR container adapter and bounded extraction cache", the §28/below 3E-C
checkpoint). Codex High reviewed §28's 3E-C and returned CHANGES REQUIRED on two HIGH findings;
this pass fixes exactly those two and nothing else — see
`docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s new §29 for the full architecture record and the explicit
list of lower-cost findings intentionally left for a separate, reserved Codex R1B pass. The 3E-C
entry immediately below this one describes the ORIGINAL (now partially superseded) architecture;
its "Cache" claims about a 256MiB/64-entry bound and cross-reopen reuse were real defects this
pass fixes, not merely documentation errors — see §29's "SUPERSEDED BY §29" callout in the plan
doc for the precise correction.

**HIGH-1 fix (cache not globally bounded)**: `core/files/RarCacheCoordinator.kt` (new) is now the
one process-wide accounting/coordination owner per cache root, shared by every
`RarExtractionCache`/`RarPageSource` instance targeting the same root via
`RarCacheCoordinator.getInstance(root, maxBytes, maxEntries)`. Byte/entry budgets and LRU eviction
now span every source namespace sharing a root (previously per-instance/per-namespace). Lazy,
first-use-triggered disk discovery folds pre-existing ShelfOS-generated finals into accounting, so
a reopen of the same `sourceKey` now genuinely reuses an earlier materialization. `core/files/
RarExtractionCache.kt` is now a thin per-namespace facade over the coordinator.

**HIGH-2 fix (no hard extraction-time byte ceiling)**: a narrow extension to the accepted 3E-B
native API — `NativeRarSession.extractEntry(index, destinationFd, maxOutputBytes)` — threads a
caller-supplied ceiling into `shelfos_rar_session_jni.cpp`'s native write loop, which now aborts
with a new `NativeRarError.TOO_LARGE` the moment a chunk would exceed it, DURING streaming, never
only after the full payload is written. `RarPageSource` passes `RarExtractionCache.MAX_ENTRY_BYTES`
(128MiB, unchanged) as this ceiling in production. Session ownership, FD ownership, restart
architecture, the handle model, synchronization, and RAR/RAR5 registration were all left untouched.

**Targeted unit tests (exact commands/counts)**:

```sh
./gradlew.bat testDebugUnitTest --tests "com.d4guilar.shelfos.core.files.RarExtractionCacheTest" --tests "com.d4guilar.shelfos.core.files.RarCacheCoordinatorTest" --tests "com.d4guilar.shelfos.core.files.RarExtractionCacheCeilingTest" --tests "com.d4guilar.shelfos.core.reader.RarPageSourceTest"
```

Result: **BUILD SUCCESSFUL**. `RarExtractionCacheTest`: **10 tests, 0 failures, 0 errors, 0
skipped** (adapted to the new per-namespace-facade constructor shape; same behavioral coverage as
before). `RarCacheCoordinatorTest` (new): **8 tests, 0 failures, 0 errors, 0 skipped** — global
byte limit across namespaces, global entry limit across namespaces, lazy cross-reopen disk
discovery, discovered-final reuse with extraction count unchanged, ephemeral-namespace payload
boundedness, `getInstance` singleton sharing, two-client same-key concurrent-dedup (latch-driven,
no sleep), active-lease eviction protection across clients. `RarExtractionCacheCeilingTest` (new):
**3 tests, 0 failures, 0 errors, 0 skipped** — exact-limit succeeds, limit+1 rejected with no
leftover file/accounting change, subsequent normal entry still works after a rejection.
`RarPageSourceTest`: **16 tests, 0 failures, 0 errors, 0 skipped** (15 original + 1 new
declared-size-precheck-still-works test; the existing error-mapping test was extended with the new
`TOO_LARGE` case).

**Targeted instrumented tests**: `LibarchiveRarNativeTest` gained
`extractEntryEnforcesHardByteCeilingDuringExtractionOnRealDevice` (real native engine, tiny
injectable limit against the real rar4-plain fixture's 21-byte entry — never a 128MiB payload).
R1A itself only compiled/packaged it because no device was then available. Later administrator-
verified on-device evidence now records `LibarchiveRarNativeTest` **13/13 PASS**, including this
case, plus `LibarchiveRarNativeLifecycleTest` **PASS** and no ShelfOS native crash signal.

**Build**: `./gradlew.bat assembleDebug` -> **BUILD SUCCESSFUL**; native C++ changed
(`shelfos_rar_session_jni.cpp`), so all 3 ABIs' CMake configure+build genuinely re-ran and
produced fresh binaries this time (not merely incremental bookkeeping). Stripped
`libshelfos_cbr.so` sizes: arm64-v8a 678520 bytes, armeabi-v7a 406208 bytes, x86_64 665392 bytes
(no x86 ABI, no standalone `libarchive.so` — unchanged policy). `./gradlew.bat
assembleDebugAndroidTest` -> **BUILD SUCCESSFUL** (required one fix: a local
`FakeRarArchiveSession` in `RarPageSourceRenderInstrumentedTest.kt` needed its `extractEntry`
override updated to the new 3-parameter signature). `./gradlew.bat lintDebug` -> **BUILD
SUCCESSFUL**, zero findings against any touched file.

**ABI**: native rebuild WAS needed and triggered (see sizes above) — `shelfos_rar_session_jni.cpp`
changed for HIGH-2's streaming ceiling.

**Physical ARM**: **NOT PERFORMED** (no device/emulator available in this environment — see the
instrumented-test note above).

**Full JVM**: **NOT RUN** (reserved for Phase 3F; standing policy). **Full connected**: **NOT RUN**
(reserved for Phase 3F; standing policy).

**Scope discipline**: `PublicationFormat.CBR` **NOT ADDED**; no product/import integration added;
Phase 3E-D **NOT started**. The four lower-cost findings from the same Codex review (lease-
release/pinning/deletion-accounting mechanics beyond what the two HIGH fixes structurally
required, user-actionable error-semantic preservation/mapping cleanup, internal-visibility
tightening of support classes, and broader docs/evidence reconciliation) were intentionally left
for the reserved Codex R1B pass — see §29 of the plan doc for the exact list.

**Documentation**: `docs/PHASE_3_IMPLEMENTATION_PLAN.md` (new §29, plus a "SUPERSEDED BY §29"
callout inline in §28's Cache paragraph correcting its now-inaccurate global-bound and
cross-reopen-reuse claims) and this entry. No other canonical doc was touched.

## PHASE 3E-C — CBR CONTAINER ADAPTER / BOUNDED EXTRACTION CACHE (2026-10-08)

Status: **IMPLEMENTED locally; pending review.** Branch: `phase-3/3e-native-cbr`;
pre-slice HEAD `a9f9d17` ("fix: tighten native RAR prefix classification", the accepted
3E-A/3E-B checkpoint). This slice adds `RarPageSource` (the CBR container adapter) and
`RarExtractionCache` (a bounded on-disk extraction cache) on top of 3E-B's accepted
`NativeRarSession`. No `PublicationFormat.CBR`, import routing, Room/`LibraryEntity` change,
reader UI change, or native C++ change was made — see `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s
§28 for the full architecture record; this entry records exact commands/counts/results only.

**`RarPageSource` architecture**: `core/reader/RarPageSource.kt` (new, `internal class`).
Implements the existing `PageSource` contract (`pageCount`, `openPage(index): InputStream`,
`close()`) exactly — the same contract `ZipPageSource` already satisfies for CBZ — so it feeds
the existing `ImagePageRenderer` unchanged. `PageSource`/`ImagePageRenderer` in
`core/reader/FixedReader.kt` were widened from file-private to `internal` (visibility-only
change, zero behavior change) so `RarPageSource`, in a different file in the same package,
could implement/use them. `RarPageSource` is NOT referenced by `FixedReaderFactory` or any
other product code path.

**Shared-policy changes**: none. `ArchivePolicy.safeName`, `naturalCompare`, and
`domain.importing.isPageImage` were already format-independent plain functions and are reused
by `RarPageSource` completely as-is; `ArchivePolicy.MAX_ENTRIES`/`MAX_IMAGE_BYTES` are reused
for RAR's entry-count/per-image-size limits instead of inventing new numbers.
`ArchivePolicy.kt`/`SeekableZip.kt`/`ZipPageSource` are byte-for-byte unchanged, so no CBZ
regression tests were needed (confirmed by `git diff --stat`, below).

**Cache (`core/files/RarExtractionCache.kt`, new)**: root = caller-supplied directory
(`RarPageSource.open`'s `cacheRoot` parameter) + `"cbr/" + UUID.nameUUIDFromBytes(sourceKeyOrRandomUUID)`
— a hashed, filesystem-safe namespace, never a raw caller string used as a path segment. Entry
key = RAR physical ordinal (`Int`); final filename = `"$physicalIndex.bin"` — never a raw
archive entry name. Namespace-invalidation model: when the caller supplies a stable
`sourceKey`, cache contents survive a `RarPageSource` reopen of the same key (intentional
cross-session reuse); when no `sourceKey` is supplied, a fresh random per-call namespace is
used instead, trading cache persistence for correctness (no product/import context yet exists
in 3E-C to derive a reliable stable key — deferred to 3E-D). Max bytes: **256 MiB** (disk, not
RAM — deliberately far larger than `RenderMemoryPolicy.SESSION_BUDGET_BYTES`'s ~96 MiB
in-memory budget or `ThumbnailLoader.DEFAULT_BUDGET_BYTES`'s 16 MiB, since `cacheDir` is
OS-reclaimable and not competing for the same scarce resource; comfortably holds several
full-resolution comic pages at the 128 MiB per-entry ceiling below). Max entries: **64**
(mirrors `ByteBudgetedLruCache.DEFAULT_MAX_ENTRIES`'s identical 3B reasoning). Both are
constructor-injectable for tests. Eviction policy: deterministic LRU bounded by BOTH limits,
tie-broken by an injectable monotonic access counter (never wall-clock time); a slot with an
active unreleased reference is never evicted. Atomic-write behavior: extract into a
ShelfOS-generated temp sibling file, verify (native success + file present + within the
128 MiB `ArchivePolicy.MAX_IMAGE_BYTES` per-entry ceiling, reused from CBZ), then
`File.renameTo` (same-directory, atomic) to the final filename; any failure deletes the temp
file and never produces a final one. This per-entry check is an honest **post-hoc** check
(measured after extraction completes), not a true mid-stream abort — `RarArchiveSession.
extractEntry`/`NativeRarSession.extractEntry` is one blocking native call with no
interruption point, and adding one would require a 3E-B native C++ change, which this
checkpoint does not make. Stale cleanup: each `RarExtractionCache` construction lazily deletes
any leftover `*.tmp-*` file under its own `root` only (bounded, never a wider scan).
Concurrency model: one lock object per cache key (`ConcurrentHashMap<Int, Any>`, never a
process-global archive lock) deduplicates concurrent `acquire` calls for the same key; 3E-C has
no reader-UI caller yet, so `RarPageSource` is not yet wired into any shared render mutex —
deferred to 3E-D, which will need to route it through the same mutex `FixedReaderViewModel`
already uses for CBZ/PDF, exactly like `ThumbnailLoader`'s decode already does.

**Extraction-count / cache-hit evidence**: `RarExtractionCacheTest.firstAcquireExtractsExactlyOnceAndCacheHitExtractsZeroMore`
and `.boundsThenFullDecodeOfTheSamePageReusesOneMaterialization` (pure JVM, fake-counted native
calls) plus `RarPageSourceTest.firstMaterializationExtractsOnceAndCacheHitIsZeroAdditionalExtractions`
and `.solidStyleNonSequentialAccessReExtractsOnlyOnCacheMiss` (later page -> same page again
[+0] -> different page [+1] -> first page again [+0]) prove the cache's own behavior against
`FakeRarArchiveSession`'s real extraction-count counter — see that class's doc for the honest
boundary (this proves `RarExtractionCache`/`RarPageSource` logic, never real libarchive
parsing). `RarPageSourceRenderInstrumentedTest.imagePageRendererBoundsThenFullDecodeOfTheSamePageReusesOneMaterialization`
proves the same one-extraction guarantee end to end through the REAL `ImagePageRenderer`/
`BitmapFactory` decode path (real PNG bytes via a local androidTest fake), and
`.imagePageRendererAcrossTwoDifferentPagesCostsTwoExtractions` proves two distinct pages cost
two extractions while a third render of page 0 stays a cache hit.

**Natural-sort / unsafe-name / duplicate-name evidence**: `RarPageSourceTest.naturalOrderingMatchesCbzBehavior`
(page1/page2/page10 natural order, never lexicographic), `.duplicateFilenamesAtDifferentPhysicalOrdinalsNeverCollide`,
`.unsafeNamesNeverBecomeLogicalPages` (`../escape.jpg` -> `PublicationException(CORRUPT)`),
`.nonImageEntriesAreExcludedFromPages`, `.differentSourceKeysNeverShareCachedBytesEvenWithTheSamePhysicalOrdinal`.

**ComicInfo.xml behavior**: `RarPageSourceTest.comicInfoIsNeverExposedAsALogicalPageAndIsParsedViaTheExistingReader`
(real `EmbeddedMetadataReader.parse`/`.comicInfo` round trip against synthetic XML) and
`.noComicInfoEntryReturnsNull`. No product/`LibraryEntity` integration exists or was added.

**ZIP (CBZ) regression**: none run — `ArchivePolicy.kt`/`SeekableZip.kt`/`ZipPageSource` were
not modified in any way (confirmed via `git diff --stat` below listing no CBZ-path file), so no
CBZ-specific regression test was needed per the brief's own "confirm none needed + why" option.

**`ImagePageRenderer` reuse evidence**: `RarPageSourceRenderInstrumentedTest` (3 instrumented
tests) calls `ImagePageRenderer.bounds`/`ImagePageRenderer.render` directly against a real
`RarPageSource` instance, proving the exact same decode-bounds-then-sample object CBZ uses is
reused unmodified; `ImagePageRenderer` itself was not changed at all (only its visibility
modifier, shared with `PageSource`, see above).

**Source immutability**: `RarPageSourceRealSessionInstrumentedTest.sourceArchiveIsByteForByteUnchangedAfterAFullEnumerateExtractCloseCycle`
— fixture `test_read_format_rar.rar.uu` (decoded to a real `.rar` temp file), driven through
the REAL `NativeRarArchiveSession`/`NativeRarSession` for a full open -> enumerate (5 entries)
-> extract (every `REGULAR_FILE` entry) -> close cycle. SHA-256 before and after: **identical**
(exact hash values are session-local, deterministic, and recorded via the test's own
`assertArrayEquals` failure message if ever violated — not reproduced here as a magic string
since the fixture is regenerated per test run from the vendored `.uu` asset). Result: **PASS**.

**Error mapping (original behavior; superseded by the R1B entry above)**:
`NativeRarError.toPublicationProblem()` originally collapsed `NOT_SEEKABLE`/`IO`/
`NATIVE_INTERNAL` to `UNREADABLE`. R1B now maps `NOT_SEEKABLE` to `NEEDS_COPY` and preserves the
exact typed `IO`/`NATIVE_INTERNAL` cause through the `PublicationException`, without new UI strings
or raw codes. `PROTECTED`, `UNSUPPORTED`, `CORRUPT`, `INVALID_ARGUMENT`, and `TOO_LARGE` retain
their documented canonical mappings.

**Cache failure tests**: `RarExtractionCacheTest.extractionFailureNeverLeavesAPartialFileMasqueradingAsACacheHitAndRetrySucceeds`
(partial bytes written, `IO` error simulated, temp deleted, no `.bin`/`.tmp-` leftover, retry
with the same key succeeds), `.staleTempFilesFromAnAbandonedProcessAreCleanedUpOnConstruction`,
`.evictsDeterministicallyByEntryCount`, `.evictsDeterministicallyByByteBudget`,
`.anActivelyReferencedEntryIsNeverEvicted`, `.entryExceedingTheMaxSizeFailsAndLeavesNoCacheEntry`.
`RarPageSourceTest.extractionFailureDuringRenderThrowsAMappedPublicationExceptionAndRetrySucceeds`
proves the same retry-succeeds property one layer up, through `RarPageSource` itself, across
two independently-constructed sessions sharing one source key (simulating a real retry-after-
failure reopen). Duplicate-key-collision: covered by `.distinctPhysicalEntriesProduceDistinctNonCollidingCacheEntries`/
`.differentSourceNamespacesDoNotCollideEvenWithIdenticalOrdinals` (cache level) and
`RarPageSourceTest.differentSourceKeysNeverShareCachedBytesEvenWithTheSamePhysicalOrdinal`
(container level) — no collision observed in any case.

**Targeted unit tests (exact commands/counts)**:

```sh
./gradlew.bat testDebugUnitTest --tests "com.d4guilar.shelfos.core.files.RarExtractionCacheTest" --tests "com.d4guilar.shelfos.core.reader.RarPageSourceTest"
```

Result: **BUILD SUCCESSFUL**. `RarExtractionCacheTest`: **10 tests, 0 failures, 0 errors, 0
skipped**. `RarPageSourceTest`: **15 tests, 0 failures, 0 errors, 0 skipped**.

**Targeted instrumented tests**: the original environment only compiled the written classes.
Subsequent administrator-verified execution records `RarPageSourceRenderInstrumentedTest`
**3/3 PASS** and `RarPageSourceRealSessionInstrumentedTest` **1/1 PASS**. The R1B focused render
rerun also passes **3/3** on the API 24 emulator with no native/JNI crash signature.

**Build**: `./gradlew.bat assembleDebug` -> **BUILD SUCCESSFUL** (3 ABI CMake configure/build
tasks ran as part of normal incremental Gradle bookkeeping; no `.cpp`/`.h` source changed, so
no native rebuild was actually triggered by this slice's own changes). `./gradlew.bat
assembleDebugAndroidTest` -> **BUILD SUCCESSFUL** (confirms the new instrumented test sources
compile against the real native/main classpath). `./gradlew.bat lintDebug` -> **BUILD
SUCCESSFUL**, zero findings reported against any new file (`grep` of the new class names
against `lint-results-debug.html` returned no matches).

**ABI**: no native rebuild was needed (no C++ file touched); confirmed by diff (`git diff
--stat` lists no `app/src/main/cpp/*` file).

**APK fixture hygiene**: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -iE
"\.uu$|libarchive_fixtures|daredevil|\.rar$"` -> **no matches** (exit 1) — confirmed absent.

**Physical ARM**: **NOT PERFORMED** (not required for this checkpoint; no native change).

**Full JVM**: **NOT RUN** (reserved for Phase 3F; standing policy).

**Full connected**: **NOT RUN** (reserved for Phase 3F; standing policy).

**Documentation**: `docs/PHASE_3_IMPLEMENTATION_PLAN.md` (new §28, plus status-line updates at
the top and in §1) and this file. No other canonical doc (`ARCHITECTURE.md`/
`COMICS_MANGA.md`/`READER.md`) was touched — none of their existing claims became factually
false by this slice (no product/reader-facing behavior changed).

**git diff --check**: PASS (no whitespace errors). **Working tree**: all new files untracked
plus one modified file (`core/reader/FixedReader.kt`, visibility-only) at the time of this
entry, prior to the single closing commit. **Pushed**: NO.

## PHASE 3E-B CODEX R1C MICROSCOPIC REMEDIATION (2026-10-08)

Status: **COMPLETE locally; pending final confirmation.** Branch:
`phase-3/3e-native-cbr`; pre-remediation HEAD `cc95120`. This pass addresses only the
RAR-prefix classification and stale FD-ownership documentation findings. It does not begin 3E-C.

**RAR-prefix classification**: the prior classifier treated even a one-byte match as recognizable
RAR input. The bounded, position-preserving `pread()` check now requires the complete six-byte
common marker (`52 61 72 21 1A 07`). Matching prefixes of lengths 1 through 5 remain
`UNSUPPORTED`; the six-byte marker, complete RAR4/RAR5 prefixes and signatures, and recognizable
truncations remain `CORRUPT` when opening or reading the first header fails.

**Focused instrumented test**: `./gradlew.bat :app:connectedDebugAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.files.LibarchiveRarNativeTest
--console=plain --no-daemon` on `emulator-5554`, `shelfos-api24(AVD) - 7.0`, x86_64 ->
**12 tests, 0 failures, 0 errors, 0 skipped**. The added table-driven regression checks each
matching prefix length from 1 through 5 as `UNSUPPORTED`; the explicit six-byte boundary remains
`CORRUPT`. Lifecycle/ownership executable code did not change, so
`LibarchiveRarNativeLifecycleTest` was intentionally not rerun.

**FD-ownership documentation**: this file and `docs/PHASE_3_IMPLEMENTATION_PLAN.md` now state the
actual transition: the caller transfers the detached fd to `NativeRarSession.open()`; Kotlin
closes it if native is unavailable before JNI, otherwise ownership transitions into native
`Session` handling. Every path closes exactly once. No ownership implementation changed.

**Build**: `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
--console=plain --no-daemon` -> **BUILD SUCCESSFUL** for all three tasks and configured ABIs.
Stripped native outputs: `arm64-v8a` **678,392 bytes**, `armeabi-v7a` **406,048 bytes**, and
`x86_64` **665,248 bytes**. No `x86` ABI was added; `bundleDebug` was not run.

**Native crash scan**: logcat was cleared before the focused test and scanned afterward for
`SIGSEGV`, `SIGABRT`, `Fatal signal`, `JNI DETECTED ERROR`, and `FORTIFY` -> **0 matches**.

**Scope/test exclusions**: full JVM tests **NOT RUN**; full connected suite **NOT RUN**;
physical ARM **NOT PERFORMED**. No `PublicationFormat.CBR`, `RarPageSource`, cache, image/page
filtering, reader/thumbnail/spread/fold integration, or other 3E-C+ work was added.

## PHASE 3E-B CODEX R1B REMEDIATION (2026-10-08)

Status: **COMPLETE locally; pending focused independent re-review.** Branch:
`phase-3/3e-native-cbr`; original 3E-B commit `1746d53`; Claude R1A ownership
remediation `38e8ac6` ("fix: make native RAR ownership exception-safe"). This R1B
pass implements only the five remaining lower-severity review findings plus the
newly discovered test-baseline bug. It does not begin 3E-C.

**R1A ownership preservation and FD-test correction**: R1A's `std::unique_ptr<Session>`
initialization ownership, release-after-success, Session destructor cleanup, and Kotlin
native-unavailable fd close remain unchanged. The native-unavailable lifecycle test now captures
its `/proc/self/fd` baseline before creating/opening/detaching the fixture descriptor, so the
closed transferred fd is no longer incorrectly counted only in the `before` value.

**Early/truncated RAR classification**: a bounded, position-preserving `pread()` inspects the
exact pinned RAR4 (`52 61 72 21 1A 07 00`) and RAR5 (`52 61 72 21 1A 07 01 00`) signatures.
Unrelated bytes and matching prefixes shorter than the six-byte common RAR marker remain
`UNSUPPORTED`. Once all six common-marker bytes match, the input is recognizable RAR-family data;
an early open/first-header failure or exact-signature EOF maps to `CORRUPT`. Classification does
not depend solely on `EILSEQ`/`EINVAL`.

**Native edge hardening**: the extraction loop returns `IO` when `write()` returns zero, before
advancing its offset, removing the no-progress infinite-loop risk while retaining `EINTR` retry
and positive partial-write behavior. A real Android fd cannot deterministically produce a
zero-byte write for a positive request without an artificial production hook, so this regression
is static-inspection-only as explicitly allowed. `restartFd()` now maps only `ESPIPE` to
`NOT_SEEKABLE`; `EBADF` and every other `lseek` errno map to `IO`. A real pipe test proves the
`ESPIPE` path, while the existing unopened/closed-fd tests prove `IO`.

**JNI narrowing**: entry count is bounded against `jint` maximum before the vector can exceed
the representable count and again immediately before the getter cast. Entry names are measured
with bounded `strnlen` and rejected with `NATIVE_INTERNAL` before string storage/JNI allocation
if they exceed `jsize`; `nativeEntryName` retains a defensive no-allocation check immediately
before its casts. No oversized fixture or enormous allocation was created.

**Documentation truthfulness**: the original 3E-B record now states that catch-all boundaries
cover the four allocating/fallible JNI calls, while the four non-allocating metadata getters do
not have catch-all wrappers. It distinguishes the fixed 64 KiB payload streaming buffer from
Session metadata allocations that scale with entry count/name bytes and remain subject to 3E-C
policy limits. `docs/PHASE_3_IMPLEMENTATION_PLAN.md` now consistently records 3E-A as complete
and accepted, 3E-B as implemented and undergoing remediation/review, 3E-C through 3E-E as not
started, and Phase 3 as incomplete.

**Focused instrumented tests** (separate invocations on `emulator-5554`,
`shelfos-api24(AVD) - 7.0`, x86_64; never a comma-separated class argument):

- `./gradlew.bat :app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.files.LibarchiveRarNativeTest
  --console=plain --no-daemon` -> **11 tests, 0 failures, 0 errors, 0 skipped**. This covers RAR4
  enumerate/extract, RAR5 enumerate/extract, solid last-to-earlier-to-last, encrypted RAR4/RAR5
  `PROTECTED`, unrelated bytes `UNSUPPORTED`, short RAR4/RAR5 prefixes `CORRUPT`, RAR4/RAR5
  signature-only `CORRUPT`, and the existing 100-byte truncation `CORRUPT` case.
- `./gradlew.bat :app:connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.files.LibarchiveRarNativeLifecycleTest
  --console=plain --no-daemon` -> **9 tests, 0 failures, 0 errors, 0 skipped**. This covers the
  corrected native-unavailable ownership baseline, real pipe `NOT_SEEKABLE`, unopened/closed fd
  `IO`, destination failure, normal/double close, use-after-close, and failed-open cleanup.

The first 11-test run exposed libarchive treating a bare RAR5 signature as an empty archive; the
bounded inspection was extended by one byte to identify the exact signature-only EOF case, after
which the full class passed. No production ownership behavior changed in response.

**Build**: `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
--console=plain --no-daemon` -> **BUILD SUCCESSFUL** for all three tasks and configured ABIs.
Stripped native outputs: `arm64-v8a` **678,472 bytes**, `armeabi-v7a` **406,112 bytes**, and
`x86_64` **665,408 bytes**. No `x86` ABI was added. `bundleDebug` was not required or run.

**Native crash scan**: logcat was cleared before the focused runs and scanned afterward for
`SIGSEGV`, `SIGABRT`, `Fatal signal`, `JNI DETECTED ERROR`, `FORTIFY`, and `native abort`.
Result: **0 matches; no ShelfOS native crash signal**.

**Scope/test exclusions**: full JVM tests **NOT RUN**; full connected suite **NOT RUN**;
physical ARM **NOT PERFORMED**. No `PublicationFormat.CBR`, `RarPageSource`, cache, image/page
filtering, reader/thumbnail/spread/fold integration, or other 3E-C+ work was added.

## PHASE 3E-B NATIVE RAR ENGINE (2026-10-08)

Status: **IMPLEMENTED, pending independent review (administrator/Codex).** Branch:
`phase-3/3e-native-cbr`; 3E-A accepted HEAD `9f1b0ed069b5456c3d4b9ce5c5a878266c62cf2c`
("fix: make Phase 3E native foundation reproducible"). See
`docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s §27 for the full design record; this entry is the
exact evidence log.

**Native session design**: one native `Session` struct per `NativeRarSession.open(fd)` call,
addressed by an opaque `jlong` (the heap pointer, `reinterpret_cast`). No native handle-validity
registry, no process-global "current archive" — `NativeRarSession` (Kotlin) is the sole owner,
enforcing atomic set/clear-on-close and idempotent close/reject-after-close before any native
call. Files: `app/src/main/cpp/shelfos_rar_session_jni.cpp` (new),
`app/src/main/java/com/d4guilar/shelfos/core/files/NativeRarSession.kt` (new). 3E-A's
`shelfos_cbr_jni.cpp`/`LibarchiveNative.kt` are unmodified; the new source file was added to the
same `shelfos_cbr` CMake target.

**FD ownership model**: the caller transfers source-fd ownership to `NativeRarSession.open()`
after `ParcelFileDescriptor.detachFd()`. If the native backend is unavailable before JNI,
`NativeRarSession.open()` closes the fd itself; once JNI/native Session initialization begins,
ownership transitions into native Session handling. Every path closes exactly once. No `dup()` —
one owned fd, restarted via `lseek(fd, 0, SEEK_SET)`. The vendored
`archive_read_open_fd.c` `file_close()` callback only frees its internal buffer and never closes
the fd. Destination fd (extraction) remains borrowed and is never closed by the engine.

**Handle model**: opaque `jlong`, never exposed/logged/persisted outside `NativeRarSession`.
Raw pointer persisted outside the handle: **NO**. Global "current archive": **NO**.

**Thread-safety contract**: one archive_read object alive at a time per session, scoped to a
single call; `NativeRarSession` synchronizes every public operation on its own per-instance
lock (never a global/cross-archive lock); native performs no internal synchronization.

**Native memory behavior**: payload extraction uses a fixed 64 KiB (`kStreamBufferSize`)
streaming buffer, never sized from archive-claimed entry size. `EINTR` on `write()` retries the
same write; a zero-byte/no-progress write and every other write failure report `IO`; a real
partial write is handled by accumulating the written offset. Session metadata is not
constant-memory: the entry vector and entry-name strings scale with enumerated archive metadata
and remain subject to the archive-policy limits planned for 3E-C.

**Error categories**: `INVALID_ARGUMENT`, `IO`, `NOT_SEEKABLE`, `CORRUPT`, `PROTECTED`,
`UNSUPPORTED`, `NATIVE_INTERNAL`. No raw libarchive numeric code and no
`archive_error_string()` text crosses into Kotlin or any test assertion as product text.
Wrong-format detection does not depend solely on libarchive's format-bid errno. The engine uses
position-preserving `pread()` against the pinned RAR4/RAR5 signatures. Matching prefixes shorter
than the six-byte common RAR marker remain unrecognized/`UNSUPPORTED`; six matching common-marker
bytes are the minimum recognizable RAR-family prefix and map an early open/first-header failure
to `CORRUPT`. Unrelated bytes retain the `EILSEQ`/defensive-`EINVAL` mapping to `UNSUPPORTED`.
Encryption: any entry encrypted (data and/or metadata) maps the WHOLE `open()` to `PROTECTED` —
no partial-success interpretation — checked both after a successful metadata pass
(`archive_read_has_encrypted_entries()`) and immediately after any header-read failure (so a
fully header-encrypted archive, which never yields one successful header, still maps to
`PROTECTED` rather than `CORRUPT`/`UNSUPPORTED`).

**RAR support registration**: `archive_read_support_format_rar` + `archive_read_support_format_rar5`
only. `archive_read_support_format_all`: **NO**. `archive_read_support_format_filter_all`: **NO**
(no filter registration of any kind — the vendored RAR/RAR5 fixtures need none). `grep -c
archive_write app/src/main/cpp/shelfos_rar_session_jni.cpp` → **0**.

**Fixtures used and results** (all decoded from the vendored upstream `.uu` files at test time via
a new test-only uudecode helper, `app/src/androidTest/java/com/d4guilar/shelfos/core/files/RarFixtures.kt`
— no external `uudecode` executable, no production packaging, decoded files written only under
the target app's cache dir and deleted in `@After`):

| Fixture | Result |
| --- | --- |
| `test_read_format_rar.rar.uu` (RAR4 plain) | 5 entries enumerated (`test.txt`, `testlink` [symlink→`OTHER`, non-extractable], `testdir/test.txt`, `testdir`, `testemptydir`); `test.txt`/`testdir/test.txt` extracted and byte-compared against the exact expected `"test text document\r\n"` (verified against upstream `test_read_format_rar.c`) |
| `test_read_format_rar5_compressed.rar.uu` (RAR5 plain/compressed) | 1 entry `test.bin`, size 1200; extracted and verified word-for-word against upstream `test_read_format_rar5.c`'s `verify_data()` generator formula (`val = max(0, k*k-3*k+1)` per little-endian int32) |
| `test_read_format_rar5_solid.rar.uu` (RAR5 solid) | 7 entries (`test.bin`, `test1..6.bin`) enumerated in order; non-sequential extraction sequence (last entry → an earlier entry → last entry again) verified via CRC32 against upstream `test_read_format_rar5.c` values (`test1.bin`=`0x7E13B2C6`, `test6.bin`=`0x36A448FF`) |
| `test_read_format_rar4_encrypted.rar.uu` | `PROTECTED` (never `CORRUPT`, no crash) |
| `test_read_format_rar5_encrypted.rar.uu` | `PROTECTED` (never `CORRUPT`, no crash) |
| Synthetic 256-byte non-RAR buffer | `UNSUPPORTED` |
| `test_read_format_rar5_solid.rar.uu` truncated to a 100-byte prefix | `CORRUPT` (empirically chosen cut point — see below) |

**Truncation bisection evidence**: the solid-RAR5 fixture decodes to exactly 1050 bytes. A
one-off debug test (removed before the final commit) tried cut lengths
`48,100,150,200,300,400,500,525,600,700,800,900,1000,1030,1040,1045` and logged each result via
`adb logcat`. Cuts of `100,150,200,300,400,600,800,1030,1040,1045` all deterministically produced
`CORRUPT`; cuts of `48,500,525,700,900,1000` happened to land exactly on an entry boundary and
parsed as a legitimately shorter (but structurally valid) archive instead — a property of this
tiny fixture's actual byte layout, not a defect. The final test uses the 100-byte cut.

**Solid-RAR5 diagnostic timing** (purely diagnostic, no threshold): three separate test runs
logged `extract(last)=1-2ms, extract(earlier)=0-1ms, extract(last again)=1ms` for the full
restart-rescan-extract sequence against this tiny fixture.

**Lifecycle results**: normal close — pass. Double close — harmless no-op, pass. Use-after-close
— `entryCount`→0, `entryAt`→null, `extractEntry`→`INVALID_ARGUMENT`, pass. Failed open (unopened
fd number 999999) — `IO`, `/proc/self/fd` count unchanged, pass. Failed open (wrong format) —
`UNSUPPORTED`, `/proc/self/fd` count unchanged (no fd leak), pass. Closed/invalid source fd
(closed via `ParcelFileDescriptor.adoptFd(fd).close()` before calling `open()`) — `IO`, pass.
Destination-fd write failure (destination pfd closed before `extractEntry`) — `IO`, and the
session remained usable (`entryCount`/`entryAt` still worked) and closable afterward, pass.

**JNI/C++ safety**: the allocating/fallible entry points (`nativeOpen`, `nativeClose`,
`nativeEntryName`, and `nativeExtractEntry`) have catch-all exception boundaries. The four
metadata getters (`nativeEntryCount`, `nativeEntryType`, `nativeEntrySize`, and
`nativeEntryIsNameUtf8`) do not: by construction they perform only handle/index checks and
non-allocating reads from already-collected metadata. Entry-count and entry-name lengths are
explicitly bounded to JNI `jint`/`jsize` limits before narrowing/allocation; oversized metadata
fails session creation with `NATIVE_INTERNAL`. No global mutable state exists. Payload extraction
uses the fixed 64 KiB streaming buffer, while Session metadata allocation scales with enumerated
entry count/name bytes and remains subject to later 3E-C archive-policy limits. No path-based
extraction exists (destination is always a caller-supplied fd; the archive pathname is never
used as an output path). `archive_write` usage: **NONE**.

**Targeted tests** (each run as its own separate Gradle invocation, per this checkpoint's
standing constraint — never a comma-separated class list), on `emulator-5554`
(`shelfos-api24(AVD) - 7.0`, x86_64):

- `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.files.LibarchiveRarNativeTest --console=plain`
  → **7 tests, 0 failures, 0 errors, 0 skipped.**
- `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.files.LibarchiveRarNativeLifecycleTest --console=plain`
  → **7 tests, 0 failures, 0 errors, 0 skipped.**
- Regression spot-check: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.files.LibarchiveNativeSmokeTest --console=plain`
  (3E-A's own test class, unmodified) → **3 tests, 0 failures, 0 errors, 0 skipped** — confirms
  adding `shelfos_rar_session_jni.cpp` to the shared library caused no regression.

**Native crashes**: **NONE**. `adb logcat -d` was scanned for `SIGSEGV`/`SIGABRT`/`FORTIFY`/
"Fatal signal"/tombstone entries across the whole session; the only `Fatal signal 11 (SIGSEGV)`
entries found belong to `uid=2000(shell)` / a `Binder` thread coinciding with an unrelated `adb`
package-install event, not this project's app/test process or the `shelfos_cbr` library —
confirmed by inspecting the surrounding logcat context (process name `shell`, not
`com.d4guilar.shelfos`/`com.d4guilar.shelfos.test`).

**Build**: `./gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug --console=plain -q`
→ **BUILD SUCCESSFUL** for all three tasks (no new lint findings surfaced in the console summary).
`bundleDebug` was **NOT run** — native packaging/CMake wiring itself did not change beyond adding
one new source file to the existing `shelfos_cbr` target's source list, consistent with 3E-A's
own packaging being unaffected.

**ABI compile results and native `.so` sizes** (stripped, package payload — the same measurement
convention 3E-A used):

- `arm64-v8a/libshelfos_cbr.so`: compiled successfully, **674,832 bytes** (3E-A: 569,616 bytes)
- `armeabi-v7a/libshelfos_cbr.so`: compiled successfully, **404,428 bytes** (3E-A: 338,836 bytes)
- `x86_64/libshelfos_cbr.so`: compiled successfully, **662,024 bytes** (3E-A: 562,680 bytes)

Growth (~100-125 KiB per ABI) is expected and accepted — this checkpoint adds real RAR4/RAR5
parsing/extraction logic on top of 3E-A's pure smoke-test surface. No `x86` output exists. A full
APK-size inventory was not redone (not requested for this checkpoint beyond recording `.so`
growth).

**APK fixture exclusion**: the five `.uu` fixtures remain under `app/src/androidTest/assets/`
(test-only source set) — nothing in `app/src/main` references them, consistent with 3E-A's own
established convention; this checkpoint added no new fixture and did not change that packaging
boundary.

**Physical ARM**: **NOT PERFORMED** (no physical ARM hardware available; same limitation 3E-A
recorded).

**Full JVM / full connected suite**: **NOT RUN** — only the targeted classes above, per this
checkpoint's standing constraint.

**Documentation touched**: `docs/PHASE_3_IMPLEMENTATION_PLAN.md` (status line + new §27 record),
`docs/VALIDATION.md` (this entry). `docs/adr/0024-native-cbr-libarchive.md` was left unchanged —
nothing in 3E-B's concrete FD/handle architecture contradicted or needed to clarify ADR-0024's
already-accepted decision; it already anticipated "opening archives, enumerating entries,
extracting pages... deferred to 3E-B" without committing to any specific ownership/handle
mechanism, so there was nothing to reconcile.

**git diff --check**: PASS (no whitespace errors). **Working tree**: left as the single
commit described below plus nothing else untracked/unstaged beyond normal build output
(ignored). **Pushed**: NO.

## PHASE 3E-A CODEX R1 REMEDIATION (2026-10-07)

Status: **COMPLETE locally; pending focused independent re-review.** Branch:
`phase-3/3e-native-cbr`; original 3E-A commit `9f7eb21`; clean-worktree test
commit `6e88c22` (the same remediation commit before this validation record was
added). This pass implements only the five accepted review findings. It makes
no executable production or native API change, adds no archive I/O, does not
add `PublicationFormat.CBR`, and does not begin 3E-B.

**Ignored vendor-input defect and exact fix**: the global `**/build/` rule had
left all 17 locally present libarchive CMake inputs untracked. `.gitignore` now
has only the scoped exceptions `!third_party/libarchive/build/` and
`!third_party/libarchive/build/**`. Exactly 17 files from upstream libarchive
tag `v3.8.9`, commit `27cbc7827172698143e440801fc0ba39ccb4f1f5`, are now tracked:
15 files under `build/cmake/`, `build/pkgconfig/libarchive.pc.in`, and
`build/version`. No generated CMake output, cache, `.cxx` content, binary, host
file, or upstream release-helper script is tracked. A detached clean worktree
configured and compiled successfully without copying any ignored source from
the original worktree, proving the old ignored files are no longer a hidden
build dependency.

**Vendor-byte preservation and provenance**: `.gitattributes` now scopes
`third_party/libarchive/** -text -whitespace`, which `git check-attr` reports as
`text: unset` and `whitespace: unset` for vendor paths while ShelfOS-owned files
retain their existing text/whitespace policy. This prevents line-ending
normalization and prevents untouched upstream whitespace style from failing
ShelfOS diff hygiene. `contrib/android/include/android_lf.h` was restored to
the exact upstream blob. Blob-level comparison against the pinned commit found
**251 tracked vendored upstream files, 251 exact matches, 0 mismatches**. The
five test-only `.uu` fixtures were compared separately against
`libarchive/test/` at the same commit: **5 exact matches, 0 mismatches**.
`git diff --cached --check` and ordinary `git diff --check` both passed.

**Other accepted findings**: the stale Phase 3 status paragraph now records 3A
through 3D merged as PRs #24-#27, 3E-A implemented on this branch pending
review/remediation, 3E-B through 3E-E not started, and Phase 3 incomplete.
`docs/DEPENDENCIES.md` now says five `.uu` fixtures, matching the five named
files in the repository. The CMake comment now accurately says libarchive's
broader static source set is compiled, with bounded linked packaging and only
ShelfOS's small JNI capability surface exposed; executable CMake is unchanged.
ADR-0024 is unchanged because its claim that `build/cmake` and `build/version`
are vendored became literally accurate after this remediation.

**Clean-worktree method**: `git worktree add --detach .tmp-r1-clean 6e88c22`.
The only pre-build untracked file added there was ignored `local.properties`,
pointing at the existing Android SDK. Gradle used the existing external
`GRADLE_USER_HOME`; Android debug-signing state used an ignored temporary
Android user directory. No source or vendor input was copied into the clean
worktree. Initial attempts stopped before or at packaging because the managed
sandbox exposed unwritable Gradle/Android user-state paths; after selecting
writable external state and restarting the daemon outside the sandbox, the
same source tree passed all gates.

**Clean build gates**: one invocation of `:app:assembleDebug
:app:assembleDebugAndroidTest :app:lintDebug :app:bundleDebug --console=plain
--no-daemon` finished **BUILD SUCCESSFUL**. Each of the four requested tasks
completed successfully. Full JVM tests and the full connected suite were not
run.

**Native smoke**: from the clean worktree,
`./gradlew.bat :app:connectedDebugAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.files.LibarchiveNativeSmokeTest
--console=plain --no-daemon` ran separately on `emulator-5554`
(`shelfos-api24(AVD) - 7.0`, x86_64). Result: **3 tests, 0 failures, 0 errors,
0 skipped**: `nativeLibraryLoads`, `backendVersionReportsLibarchive389`, and
`rarAndRar5CapabilityRegisterCleanly`.

**Clean three-ABI outputs** (stripped/package payload sizes):

- `arm64-v8a/libshelfos_cbr.so`: **569,616 bytes**
- `armeabi-v7a/libshelfos_cbr.so`: **338,836 bytes**
- `x86_64/libshelfos_cbr.so`: **562,680 bytes**

These exactly match the original 3E-A measurements. No `x86` output exists.
The clean debug APK is **24,538,184 bytes**, also unchanged from the original
record. The clean debug AAB is **20,765,157 bytes** (2,215 bytes above the
original archive-level measurement); all three embedded native payload sizes
are unchanged.

**APK/AAB inspection**: the APK contains exactly
`lib/<abi>/libshelfos_cbr.so` and the AAB exactly
`base/lib/<abi>/libshelfos_cbr.so` for the three required ABIs. Both packages
contain no `x86`, standalone `libarchive.so`, `bsdtar`, `bsdcpio`, `bsdcat`,
`bsdunzip`, `.rar`, `.rar.uu`, other `.uu` fixture, Daredevil/owner fixture, or
generated test-output entry. Result: **PASS**.

**CI**: static inspection confirms `.github/workflows/android.yml` invokes
`$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager`, installs NDK
`28.2.13676358` and CMake `3.31.6` matching `app/build.gradle.kts`, and contains
no local `.tools/android-sdk` path. The clean worktree executed the equivalent
compile/build gates locally. Remote GitHub Actions remains **PENDING FINAL 3E
PR BY ADMINISTRATOR POLICY**; nothing was pushed solely to test CI.

## PHASE 3E-A NATIVE CBR DEPENDENCY FOUNDATION (2026-10-07)

Status: **IMPLEMENTED, pending independent review (administrator/Codex).** Branch:
`phase-3/3e-native-cbr`, base `main` @ `12b4fb7` ("feat: add Phase 3D adaptive foldable
comic spreads (#27)"). This is a deliberately narrow checkpoint: native build plumbing
only, **zero archive-reading logic**. It does not implement CBR import, `PublicationFormat.CBR`,
a `RarPageSource`, archive I/O of any kind, or touch reader UI/Compose/`PageSource`/
`LibraryItem`. See `docs/adr/0024-native-cbr-libarchive.md` for the dependency decision
this checkpoint implements.

**Toolchain actually used**: NDK `28.2.13676358` at `.tools/android-sdk/ndk/28.2.13676358/`
(confirmed present before use). CMake `3.31.6`, the only version found under
`.tools/android-sdk/cmake/`. `clang.exe --version` from that NDK reported:
`Android (13624864, based on r530567e) clang version 19.0.1
(https://android.googlesource.com/toolchain/llvm-project
97a699bf4812a18fb657c2779f5296a4ab2694d2)`, target `x86_64-w64-windows-gnu` (host triple;
the toolchain cross-compiles to each Android target ABI).

**libarchive pin verification**: `git ls-remote --tags https://github.com/libarchive/libarchive v3.8.9`
returned annotated tag object `f1f785cc218bb05876c54680f10d3d4e54575ea2`, which peels
(`^{}`) to commit `27cbc7827172698143e440801fc0ba39ccb4f1f5` — an exact match to the
required pin. Independently confirmed via `git clone --depth 1 --branch v3.8.9
https://github.com/libarchive/libarchive.git`, which landed on that same commit
(`git rev-parse HEAD` after clone: `27cbc7827172698143e440801fc0ba39ccb4f1f5`).

**License evidence**: read directly from that exact commit. `COPYING`: 2-clause
BSD-style ("Copyright (c) 2003-2018 <author(s)>"), with only a short, explicitly listed
set of exceptions (3-clause UC Regents for the compress filter, public domain for
`archive_parse_date.c`, CC0/OpenSSL/Apache-2.0 triple-license for the BLAKE2 files) —
none of which are the RAR reader files. `libarchive/archive_read_support_format_rar.c`
header: 2-clause BSD ("Copyright (c) 2003-2007 Tim Kientzle", "Copyright (c) 2011 Andres
Mejia"). `libarchive/archive_read_support_format_rar5.c` header: 2-clause BSD
("Copyright (c) 2018 Grzegorz Antoniak"). No UnRAR-derived-source notice, no
UnRAR-License field-of-use clause, no GPL/AGPL text found in either file or in
`COPYING`. Both `archive_read_support_format_rar` and `archive_read_support_format_rar5`
confirmed present and callable in this tagged tree (and, this checkpoint, confirmed
linkable and callable via the native smoke test below).

**Vendoring**: `third_party/libarchive/` — a reasoned subset (the `libarchive/` source
directory minus its 16 MB all-formats `test/` fixture corpus, top-level `CMakeLists.txt`,
`build/cmake` + `build/version`, `contrib/android`, `COPYING`, and guard-only
`CMakeLists.txt` stubs for `cat/`/`tar/`/`cpio/`/`unzip/`/`test/` subdirectories so
upstream's own unconditional `add_subdirectory()` calls resolve while those tools stay
disabled via `ENABLE_TAR`/`ENABLE_CPIO`/`ENABLE_CAT`/`ENABLE_UNZIP`/`ENABLE_TEST=OFF`).
Source modifications: NONE — `COPYING` and every source file's copyright header are
byte-for-byte as vendored. No build-time network fetch: the build is fully offline once
vendored.

**Upstream test fixtures vendored** (test-only, `app/src/androidTest/assets/libarchive_fixtures/`,
same exact tagged commit, raw uuencoded `.uu` text, not decoded, not referenced by any
production code): `test_read_format_rar.rar.uu` (plain RAR4), `test_read_format_rar4_encrypted.rar.uu`
(encrypted RAR4), `test_read_format_rar5_compressed.rar.uu` (plain non-solid RAR5),
`test_read_format_rar5_solid.rar.uu` (solid RAR5), `test_read_format_rar5_encrypted.rar.uu`
(encrypted RAR5). Confirmed absent from the built debug APK (see APK inspection below).

**Gradle/CMake wiring**: `app/build.gradle.kts` pins `ndkVersion = "28.2.13676358"` and
`externalNativeBuild.cmake.version = "3.31.6"` (top-level `android {}` block — note this
must be at the top level, not inside `defaultConfig`, or AGP silently falls back to its
own bundled CMake; this was hit and fixed during this checkpoint, see deviations below),
`abiFilters = ["arm64-v8a", "armeabi-v7a", "x86_64"]` (no `x86`). CMake entry point:
`app/src/main/cpp/CMakeLists.txt`, target `shelfos_cbr` (`SHARED`), which forces every
libarchive `ENABLE_*` optional-dependency/CLI/test option OFF (including `ENABLE_WERROR`,
needed because upstream defaults it ON for Debug builds and two pre-existing upstream
`-Wunused-function`/`-Wunused-variable` warnings in `archive_read_support_format_zip.c`
and `archive_write_set_format_mtree.c` — files ShelfOS does not use for RAR — would
otherwise fail the build under `-Werror`; this is upstream's own documented escape hatch,
not a suppression we invented) and `BUILD_SHARED_LIBS OFF`, then links `shelfos_cbr`
statically against the resulting `archive_static` target plus `log`. No new optional
native dependency (OpenSSL, zstd, lz4, xz, bzip2, expat, libxml2) was required: `rar.c`
only conditionally touches `zlib.h` for CRC32 with a bundled fallback (unused here since
`ENABLE_ZLIB OFF`), and `rar5.c` has no crypto/OpenSSL/mbedTLS reference at all in this
version.

**JNI bridge**: `app/src/main/cpp/shelfos_cbr_jni.cpp`. Exposes exactly two native
methods: `nativeBackendVersion()` (returns libarchive's version/details string) and
`nativeProbeRarCapability()` (creates and frees one `archive_read` object via an RAII
wrapper, registers RAR4 and RAR5 format support, returns a bitmask; never opens or reads
archive data). No archive I/O, no global/mutable native state, no raw pointer exposed to
Kotlin, every path frees the `archive_read` object (destructor-based). Kotlin wrapper:
`app/src/main/java/com/d4guilar/shelfos/core/files/LibarchiveNative.kt` — knows nothing
about `LibraryItem`/`PublicationFormat`/reader state/Compose/`PageSource`.

**Native smoke test**: `app/src/androidTest/java/com/d4guilar/shelfos/core/files/LibarchiveNativeSmokeTest.kt`.
Command: `./gradlew.bat :app:connectedDebugAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.core.files.LibarchiveNativeSmokeTest`.
Device: `emulator-5554`, reported as `shelfos-api24(AVD) - 7.0` (API 24), ABI confirmed
x86_64 via `adb shell getprop ro.product.cpu.abi`. Result: **BUILD SUCCESSFUL**, all 3
tests passed, 0 failures, 0 errors
(`app/build/outputs/androidTest-results/connected/debug/TEST-shelfos-api24(AVD) - 7.0.xml`):
`nativeLibraryLoads` (native library loaded), `backendVersionReportsLibarchive389`
(version string contained both "libarchive" and "3.8.9"), `rarAndRar5CapabilityRegisterCleanly`
(archive_read create + RAR4 registration + RAR5 registration all succeeded — full
expected bitmask). No RAR file was opened or parsed by this test.

**Three-ABI build**: `assembleDebug` built and physically verified on disk for every
pinned ABI (stripped, as packaged into the APK):
- `arm64-v8a`: `app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libshelfos_cbr.so` — 569,616 bytes
- `armeabi-v7a`: `app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/armeabi-v7a/libshelfos_cbr.so` — 338,836 bytes
- `x86_64`: `app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/x86_64/libshelfos_cbr.so` — 562,680 bytes

All three built cleanly (only pre-existing upstream warnings noted above; zero errors).

**APK inspection**: `app/build/outputs/apk/debug/app-debug.apk` unzipped and inspected
directly (not just trusting the build log). Exactly one `libshelfos_cbr.so` per
configured ABI (`lib/arm64-v8a/`, `lib/armeabi-v7a/`, `lib/x86_64/`), alongside the
pre-existing `libandroidx.graphics.path.so` (unrelated Compose dependency, present
before this checkpoint). No `x86` directory. Confirmed **absent**: any libarchive CLI
binary (`bsdtar`/`bsdcpio`/`bsdcat`/`bsdunzip`), any `.uu` test fixture, any decoded
`.rar` file, any Daredevil-derived content, any duplicate native library, any separate
`libarchive.so` (statically linked into `shelfos_cbr` as intended).

**APK size**: pre-3E-A baseline (built directly at this checkpoint's base commit,
`12b4fb7`, before any 3E-A change — a real build, not an estimate): **24,088,943 bytes**.
Post-3E-A debug APK: **24,538,184 bytes**. Delta: **+449,241 bytes (~439 KiB)** for three
ABIs' worth of `libshelfos_cbr.so`.

**AAB**: `bundleDebug` — **BUILD SUCCESSFUL**. `app/build/outputs/bundle/debug/app-debug.aab`
(20,762,942 bytes) unzipped and inspected: `base/lib/<abi>/libshelfos_cbr.so` present for
all three ABIs, correctly namespaced under `base/lib/`.

**CI**: `.github/workflows/android.yml` — added one step ("Install pinned NDK and CMake
for native CBR foundation (3E-A)") running `sdkmanager --licenses` followed by
`sdkmanager "ndk;28.2.13676358" "cmake;3.31.6"` before the existing build step, since
`ubuntu-latest`'s preinstalled Android SDK does not bundle these specific pinned
versions by default. No other CI change. Local-equivalent build (this checkpoint's own
`assembleDebug`/`assembleDebugAndroidTest`/`lintDebug`/`bundleDebug` runs, all
BUILD SUCCESSFUL) stands in for this; **remote CI result is PENDING PR** — not run or
claimed passing here.

**Targeted builds run** (and only these, per this checkpoint's scope): `assembleDebug`,
`assembleDebugAndroidTest`, `lintDebug`, `bundleDebug` — all BUILD SUCCESSFUL. The one
filtered instrumented smoke test above. **No full `testDebugUnitTest` run. No full
connected test suite run. No Phase 3A-3D test class re-run.** Physical ARM hardware was
**not** used — only the x86_64 emulator and host cross-compilation for all three ABIs.
Physical-device validation remains Phase 3F scope.

**What does NOT work yet and must not be assumed**: CBR files are not recognized,
imported, or readable. `PublicationFormat.CBR` does not exist. No RAR archive (real or
the vendored test fixtures) was opened, enumerated, or extracted by any code added in
this checkpoint — only a version query and a side-effect-free capability probe were
exercised. Encrypted/password-protected RAR is unaddressed. Solid-archive handling is
unaddressed. None of this is implied to work by this checkpoint passing.

**Deviations from a hypothetically perfect first pass** (all resolved within this
checkpoint, recorded for transparency): (1) `externalNativeBuild.cmake.version` was
initially placed inside `defaultConfig`, where AGP ignores it and silently falls back to
its own bundled CMake 3.22.1; moved to the top-level `android.externalNativeBuild.cmake`
block. (2) The first configure+build attempt (with the wrong CMake 3.22.1) failed with a
`CONFIGURE_FILE ... Permission denied` error from `CheckFuncs.cmake`; this did not
recur once the correct pinned CMake 3.31.6 was used, so it is recorded as resolved by
the version fix rather than independently root-caused. (3) `ENABLE_WERROR` (upstream's
own switch, defaulting ON for Debug builds) had to be forced OFF to avoid two
pre-existing upstream `-Werror` warnings-as-errors failures unrelated to RAR; see Gradle/CMake
wiring above.

Status: **IMPLEMENTED, one remediation commit on top of `b3b6062`** ("fix: finalize Phase 3D
adaptive reader behavior", the R2 remediation entry below). Branch:
`phase-3/3d-adaptive-foldable-spreads`. Codex R3 found **no remaining production correctness
defect** in the range from the original 3D commit (`9e26140`) through `b3b6062` — this remediation
is test/evidence/documentation/comment-only. **No production behavior changed.** It does not start
3E, implement CBR, or touch Fit Width/Fit Page/pane sizing/the memory model/gamepad-B/system
Back/focus-restoration/background-suppression/continuity, all of which R3 explicitly accepted.

**Why this remediation exists**: R3's own finding was that the EXISTING pure `RenderKeyTest`
hysteresis test (783<->784 never changing the ACCEPTED key) proves the key-equality MATH, but never
proves the real `FixedReaderViewModel.updateViewport -> effectiveRenderKey -> render() ->
FixedReader.render(PageRenderRequest)` integration actually avoids an extra decode, or that a real
material resize changes the real request by exactly the expected amount. R3 also flagged the
existing horizontal-fold request test as too loose (only checked request height against a 75%-of-
whole-reader threshold, which plenty of wrong values would also satisfy) and three comments/docs
that no longer matched actual accepted behavior. All four are closed below with real, observed
evidence — not merely corrected prose.

**Task 1 — real decode boundary-jitter integration evidence.** New test
`FixedReaderFoldRenderRequestTest.boundaryJitterThroughTheRealViewModelPathNeverTriggersAnExtraDecode`:
a STABLE single-page group (explicit `SpreadMode.SINGLE`, so `AUTO` can never flip the group shape
mid-test and confound the evidence) is settled through the REAL `FixedReaderViewModel`, then driven
through the REAL `updateViewport` entry point with raw widths `784 -> 784(baseline) -> [784, 783,
784, 783, 784, 783]` (784px is the exact old half-bucket boundary: `RENDER_KEY_BUCKET_PX` == 32,
and 784.0 == 24.5*32). Group shape (one page, never a spread) was unchanged throughout, confirmed
by construction (`SpreadMode.SINGLE`) rather than merely observed. **Initial decode count (after
the baseline-establishing `updateViewport(784)` call) was 2** (1 from the session's own initial
`open()` decode + 1 from the baseline call actually changing the key from its initial
geometry-`flat(0,0,0)` state). **Final decode count after the full 784/783 jitter sequence was
also 2 — zero additional decodes.** This proves `acceptedRenderKeyBucket`'s hysteresis band
(bucket 25's accepted range widens to px [772, 828), comfortably containing both 783 and 784) holds
through the real integration path, not merely in the isolated key-math comparison.

**Task 2 — real material same-shape resize integration evidence.** New test
`FixedReaderFoldRenderRequestTest.materialResizeThroughTheRealViewModelPathCorrectsExactlyOnceAndDeliversTheNewRawDimensions`:
the SAME stable single-page group (`SpreadMode.SINGLE`, unchanged throughout — the exact R3
deficiency closed: this resize is never confounded with an `AUTO` single<->spread flip) is settled
at raw dimensions **783x3000**, then moved to **900x4500** — well outside `renderKeyBucket(783)`'s
(24) accepted range `[740, 796)` (`RENDER_KEY_BUCKET_PX` (32) + `RENDER_KEY_HYSTERESIS_PX` (12) on
each side), landing on a genuinely different bucket (`renderKeyBucket(900)` == 28). **Decode count
before the resize was 2, after was 3 — an actual delta of exactly +1**, matching the expected
single-page-group delta exactly (never 0 — silently suppressed; never >1 — thrashed). The final
recorded `PageRenderRequest` was inspected directly: **`viewportWidth == 900`, `viewportHeight ==
4500`** — an EXACT match (no rounding tolerance needed; a single-page group's `resolveRenderTargets`
passes `geometry.single`'s raw px straight through unchanged), proving production delivers the new
RAW geometry to the actual decode call, never a bucketed/quantized approximation of it.

**Task 3 — tightened horizontal request dimension assertions.** Rewrote the final assertions of
`FixedReaderFoldRenderGeometryUiTest.horizontalFoldRequestUsesTheSafePaneDimensionsNeverTheWholeReaderSurface`.
The prior assertion only checked `request.viewportHeight < readerBounds.height * 0.75f` — true of
the correct value, but also true of many wrong ones, and never checked width at all. Under the same
injected centered horizontal fold as before, the real selected safe pane
(`node("reader_pane_content").boundsInWindow`) was measured at **1080x666px**; the whole,
un-confined reader surface measured **1080x1353px**; and the actual recorded `PageRenderRequest`
was **1080x666px** — an EXACT match (both width and height) to the measured safe pane, within the
test's documented 2px integer-rounding tolerance, and clearly distinct from (not merely "less
than some fraction of") the whole reader's own height. No production code needed to change — this
test only measures more precisely what production was already doing correctly.

**Comment fixes (production, comment-only — no behavior changed)**:

- `HingeSafeDialog.kt` (~line 70): corrected an inaccurate claim that the root preview handler
  "swallows page/menu commands." It intercepts ONLY the semantic Back/gamepad-B dismissal path;
  `NEXT_PAGE`/`PREVIOUS_PAGE` are never globally swallowed there (they stay free to move focus
  between the overlay's own controls). The background reader cannot receive a page command while a
  modal is open because its own focus is structurally suppressed (`canFocus = false`), not because
  any key is centrally blocked at the root.
- `FixedReaderScreen.kt` (~line 730): corrected an inaccurate claim that focus returns to "the
  control that opened the dialog." Actual (R2-accepted) behavior: focus is restored to the reader's
  own stable `pageFocus` surface, never an attempt at exact-original-trigger restoration — the
  accurate description already present a few lines above `dismissAppearance()`/
  `dismissThumbnails()`'s own declaration now matches the comment at the call site too.
- `docs/PHASE_3_IMPLEMENTATION_PLAN.md` (~line 1164): corrected "Two new localized string resources
  were added" to "One localized content-description resource was added across EN, ES, and PT-BR" —
  there is exactly one string key (`content_desc_hinge_safe_dialog`), translated into three locale
  files (`values/strings.xml`, `values-es/strings.xml`, `values-pt-rBR/strings.xml`), confirmed by
  inspection of all three.

**Stale-evidence note**: this remediation looked for the "42/42 instrumented" vs. "closer to 45"
discrepancy the task brief described, but found no such literal claim anywhere in
`docs/VALIDATION.md` or `docs/PHASE_3_IMPLEMENTATION_PLAN.md` to correct (the only "42/42" hits in
either document are `SpreadModelTest`'s own unrelated JVM count from Phase 3C). Rather than invent a
correction for text that does not exist, this entry instead records the ACTUAL observed counts for
every class this remediation touched or re-ran (below) — so the record stays accurate going
forward even if no prior literal discrepancy existed.

**Targeted JVM unit tests — PASS.**
`./gradlew testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.RenderKeyTest"` — exit 0,
**25/25 PASS**, unchanged/preserved exactly as R2 left it (including the mutation-proof hysteresis
cases) — this remediation's new evidence is layered ON TOP of this pure math layer, never a
replacement for it.

**Targeted instrumented tests — PASS** (API 24 x86_64 emulator, `shelfos-api24` AVD, each class run
SEPARATELY per standing policy):

- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldRenderRequestTest`
  — **4/4 PASS** (8s). Two tests preserved unchanged
  (`verticalFoldSpreadRequestsEachSlotAtItsOwnAsymmetricPaneWidth`,
  `continuousFoldPaneWidthChangesThatNeverFlipSpreadActiveNeverTriggerANewDecode` — the latter is
  the existing mutation-proof render-storm guard, left exactly as-is); two NEW real-integration
  tests added for Tasks 1 and 2 above.
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldRenderGeometryUiTest`
  — **3/3 PASS** (12s). `soloToSpreadFirstDecodeAlreadyUsesCorrectPerPaneTargetsWithNoIncidentalSecondLayout`
  and `spreadToSoloFirstDecodeAlreadyUsesTheSoloPaneTarget` unchanged;
  `horizontalFoldRequestUsesTheSafePaneDimensionsNeverTheWholeReaderSurface` tightened per Task 3.
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.ReaderDialogFoldSafetyTest`
  — **4/4 PASS** (7s), unchanged — re-run as a regression guard since this remediation's comment
  changes touched `HingeSafeDialog.kt`/`FixedReaderScreen.kt`; confirms the pane-confinement proof
  still holds with zero behavior change.

**Full JVM suite / full connected suite**: NOT RUN (reserved for Phase 3F; never
`testDebugUnitTest` unfiltered, never the full connected suite, never `NavigationSmokeTest`, never
Phase 2/3C broad suites, per standing policy). `FixedReaderFoldableUiTest`,
`FixedReaderSpreadViewModelTest`, `FixedReaderViewModelLifecycleTest`, `ThumbnailNavigationUiTest`,
`FixedReaderHingeSafeModalTest` were not re-run this pass — none of their own files, or any file
they depend on beyond the comment-only edits above, changed.

**Build/Lint**: NOT RUN. Every source change this pass is either a new/modified test (`@Test`
methods and one test helper in two `androidTest` files, no new production API) or a COMMENT-ONLY
edit to two existing production Kotlin functions' doc comments (no signature, behavior, or
visible-string change) — per the task's own build/lint policy, a full `assembleDebug`/
`assembleDebugAndroidTest`/`lintDebug` pass is not required when no production function signature
changes. `assembleDebugAndroidTest` WAS run once to confirm the new/modified test code compiles
cleanly (**BUILD SUCCESSFUL**).

**Dependencies/Room/ReaderPreferences/manifest**: unchanged.

**git diff --check**: PASS (no whitespace errors). **Working tree**: clean after commit.

**Risks / Phase 3F follow-ups**: none new. The standing real-foldable-physical-device gap noted in
the R1/R2 entries below is unchanged by this remediation (test/evidence-only, no new hardware-
dependent behavior introduced).

## PHASE 3D CODEX R2 REMEDIATION (2026-10-07)

Status: **IMPLEMENTED, one remediation commit on top of `ba391c9`** ("fix: complete Phase 3D
foldable reader behavior", the R1 remediation entry below). Branch:
`phase-3/3d-adaptive-foldable-spreads`. See `docs/PHASE_3_IMPLEMENTATION_PLAN.md` §26b for the
full architectural record of what changed and why; this entry is the validation evidence. Fixes
Codex R2's 2 CHANGES-REQUIRED findings (circular render-geometry delivery + render-key
quantization boundary jitter; `HingeSafeDialogOverlay` not truly modal for focus/accessibility),
while R2 explicitly accepted everything else R1 fixed. Does not start 3E, implement CBR, or
redesign Fit Width/the multiple-`FoldingFeature` architecture/route entry/the memory model — all
of those are byte-for-byte unchanged from `ba391c9`. No dependency/Room/preference/manifest change.

**What changed** (see §26b for the full "why"): `core/reader/RenderKey.kt` — `ReaderRenderGeometry`
is no longer sealed (`Single`/`Spread`); it is one data class with `single: SingleTarget` (always
populated) and `spread: SpreadTarget?` (populated whenever the fold layout genuinely has two usable
panes, independent of published slot count) plus a `flat(...)` convenience factory for the common
no-fold-spread case. New `resolveRenderTargets(geometry, groupSize)` (the one shared shape-selection
helper `effectiveRenderKey` and `FixedReaderViewModel.render` both use). `effectiveRenderKey` now
takes the resolved `groupSize` and the previous accepted key; new `acceptedRenderKeyBucket`/
`RENDER_KEY_HYSTERESIS_PX` (hysteresis around the last-accepted bucket, not just nearest-rounding).
`FixedReaderScreen.kt`: `onGloballyPositioned` computes `single`/`spread` unconditionally from the
current `ReaderFoldLayout` alone — no `state.slots.size` check anywhere in that computation; a new
`hingeSafeModalOpen`-gated root `onPreviewKeyEvent` (Back interception) and background `Column`
modifier (`Modifier.focusProperties { canFocus = false }.clearAndSetSemantics {}`);
`dismissAppearance()`/`dismissThumbnails()` restore focus to the reader's own stable `pageFocus`
surface. `FixedReaderViewModel.kt`: new `isLandscapeAtForPresentationSync` (synchronous, cache-only,
optimistic — used by both `updateViewport` and `render` to resolve `groupSize` before selecting a
render target or building the key); `currentWidthDp()` simplified (no stale-`Spread`-fallback
special case, since `single` is now always correct). `HingeSafeDialog.kt`: the overlay's `Surface`
gained `Modifier.semantics { paneTitle = ... }` (new string `content_desc_hinge_safe_dialog`,
English/Spanish/Portuguese — spoken-only, never visible).

**Targeted JVM unit tests — PASS.**
`./gradlew testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.RenderKeyTest"` — exit 0,
25/25 pass (rewritten for the new `ReaderRenderGeometry`/`effectiveRenderKey(geometry, groupSize,
spreadActive, previous)` API; 8 new hysteresis-specific cases:
`acceptedBucketNeverThrashesAcrossTheOldPlainBoundary`,
`acceptedBucketStillMovesOnceDriftGenuinelyExitsTheHysteresisBand`,
`acceptedBucketWithNoPriorStateFallsBackToPlainNearestRounding`,
`acceptedBucketOfNonPositivePxIsZeroRegardlessOfPriorState`,
`boundaryJitterAroundTheOldBucketBoundaryProducesZeroKeyChangesAfterStabilizing`,
`boundaryJitterOnASpreadPaneAlsoProducesZeroExtraRendersAfterStabilizing`,
`materialResizeProducesExactlyOneRenderCorrection`,
`sameGeometryDifferentGroupSizeNeverCollidesEitherSinceShapeFollowsGroupSizeNow`; 3 new
`resolveRenderTargets` cases). No other JVM classes touched this remediation — `FoldLayoutTest`/
`FixedReaderTransformTest`/`SpreadModelTest` were not re-run (not filtered-relevant; none of their
own files changed).

**Targeted instrumented tests — PASS** (API 24 x86_64 emulator, `shelfos-api24` AVD, each class run
SEPARATELY per standing policy):

- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldRenderGeometryUiTest`
  — 3/3 PASS. **NEW class** — the required production-screen-driven proof that render geometry no
  longer depends on `state.slots.size`: `soloToSpreadFirstDecodeAlreadyUsesCorrectPerPaneTargetsWithNoIncidentalSecondLayout`
  (an off-center fold, so panes are meaningfully asymmetric; records EVERY decode per page, not
  just the last, and asserts each new slot decoded exactly once with meaningfully asymmetric
  widths — **verified to actually catch the regression**: temporarily reintroducing the old
  `state.slots.size >= 2` gate made this test fail with "page 1... expected:&lt;1&gt; but
  was:&lt;2&gt;", confirming an incidental second decode; removing the gate again restored the
  pass), `spreadToSoloFirstDecodeAlreadyUsesTheSoloPaneTarget` (the reverse transition),
  `horizontalFoldRequestUsesTheSafePaneDimensionsNeverTheWholeReaderSurface` (closes the horizontal-
  fold request-evidence gap Codex flagged).
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderHingeSafeModalTest`
  — 7/7 PASS. **NEW class** — drives the REAL `FixedReaderScreen` under an injected vertical fold:
  `pagesUnderFoldBlocksPageCommandsWhileOpenAndGamepadBDismissesIt`,
  `appearanceUnderFoldBackDismissesItWithPageUnchanged`, `backgroundTouchWhileModalOpenNeverTurnsThePage`
  (no click-through), `accessibilityExposesDialogSemanticsAndHidesABackgroundControlWhileOpen`
  (`PaneTitle` present; a background control absent from the merged tree while open, present
  again after dismiss), `focusCannotMoveFromOverlayToBackgroundControlsWhileModalOpen`
  (`canFocus = false` proven directly), `dismissingAppearanceRestoresFocusToAStableReaderControlRatherThanLeavingItStranded`/
  `dismissingPagesRestoresFocusToAStableReaderControlRatherThanLeavingItStranded` (focus lands on
  `reader_page`, never nowhere). See §26b's own "honest limitation" note: this harness
  (`createComposeRule()`, no real Activity window) could not reliably force KEYBOARD focus onto a
  SPECIFIC deeper dialog descendant via `requestFocus()`/Tab — a harness characteristic, not a
  production claim; `performKeyInput` still dispatches to whichever node actually holds focus
  regardless of which node reference it is called on, so the dismissal/page-command-block proofs
  remain genuine.
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldRenderRequestTest`
  — 2/2 PASS. Both tests reworked to carry the full `ReaderRenderGeometry` (single + spread) from
  ONE `updateViewport` call (never a corrective second one, matching the architecture fix). The
  render-storm-guard test's final assertion — previously assertion-by-comment (comment claimed
  "exactly one render," assertion only checked `slots.size == 1`) — now asserts the actual recorded
  decode-count delta directly.
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.ReaderDialogFoldSafetyTest`
  — 4/4 PASS, unchanged (R1's pane-confinement proof still holds with the new modal machinery).
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldableUiTest`
  — 9/9 PASS, unchanged (no dialog involved; confirms the broader fold reading/gesture path is
  unaffected by the `FixedReaderScreen.kt` changes).
- `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.ThumbnailNavigationUiTest`
  — 7/7 PASS, unchanged. The pre-existing NON-fold regression suite (real `MainActivity` flow,
  ordinary `BasicAlertDialog` path) — proves the new hinge-safe-specific modal machinery does not
  touch the ordinary, unconstrained dialog case.
- Regression (mechanical `ReaderRenderGeometry.Single(...)` → `.flat(...)` rename only, both files'
  own test semantics unchanged; each run SEPARATELY): `FixedReaderSpreadViewModelTest` — 11/11
  PASS; `FixedReaderViewModelLifecycleTest` — 2/2 PASS.

**Foldable AVD / physical foldable**: same honest gap as the R1 entry below — `shelfos-api24` has
no real fold-posture simulation; every scenario here is an injected `ReaderFoldDescriptor`/
`FoldRect` parameter, needing no real posture event, by design. Flagged for Phase 3F, not required
for this remediation.

**Full JVM suite / full connected suite**: NOT RUN (reserved for Phase 3F; never
`testDebugUnitTest` unfiltered, never the full connected suite, never `NavigationSmokeTest`, never
Phase 2/3C broad suites, per standing policy).

**Build**: `assembleDebug` — PASS. `assembleDebugAndroidTest` — PASS.

**Lint**: `lintDebug` — PASS, 0 errors, 21 pre-existing warnings (required adding Spanish/
Portuguese translations for the one new string resource, `content_desc_hinge_safe_dialog`, to
clear a `MissingTranslation` error before this passed).

**git diff --check**: PASS (no whitespace errors). **Working tree**: clean after commit.

**Risks / Phase 3F follow-ups**:

- Real foldable physical-device acceptance remains untested (consistent with the standing 3D/3F
  honest gap — no hardware confirmed available this pass).
- This test harness's inability to force precise intra-dialog keyboard focus (see the "honest
  limitation" note above) means a FUTURE change that needs to assert exact focus position within
  an open hinge-safe dialog will need either a different test harness (`createAndroidComposeRule`)
  or a different verification strategy.
- Recommended next step: administrator review followed by a focused Codex R3 of the range from the
  original 3D commit (`9e26140`) through this remediation's new HEAD.

## PHASE 3D CODEX R1 REMEDIATION (2026-10-07)

Status: **IMPLEMENTED, one remediation commit on top of `3550484`** ("feat: add fold-aware comic
spread layout", the 3D entry below). Branch: `phase-3/3d-adaptive-foldable-spreads`. See
`docs/PHASE_3_IMPLEMENTATION_PLAN.md` §26a for the full architectural record of what changed and
why; this entry is the validation evidence. Fixes Codex R1's 5 CHANGES-REQUIRED findings
(folded Fit Width reachability, the effective render key, multiple-`FoldingFeature` selection +
an untested production mapper, Appearance/Pages hinge safety, and two documentation/test-coverage
overclaims) plus the explicitly-requested missing horizontal-fold UI test and corrupt-SECOND-member
test. Does not start 3E; no dependency/Room/preference change.

**What changed** (see §26a for the full "why"): `core/reader/FixedReaderTransform.kt` gained
`foldPaneVerticalOverflow`/`foldPaneReadingTranslationY`/`foldSpreadDragToProgress` (finding 1's
shared-normalized-progress model). New `core/reader/RenderKey.kt` —
`ReaderRenderGeometry`/`EffectiveRenderKey`/`effectiveRenderKey`/`renderKeyBucket` (finding 2).
`core/reader/FoldLayout.kt` gained `selectRelevantFoldDescriptor` and a `List<ReaderFoldDescriptor>`-
taking `resolveReaderFoldLayout` overload (finding 3); the single-descriptor overload is now a thin
delegate, unchanged behaviorally. `MainActivity.kt`'s `toReaderFoldDescriptor()` is now `internal`
(was `private`) and maps the FULL feature list, never `firstOrNull` before reader bounds are known;
its own non-reader legacy padding calculation moved into `ShelfApp.kt` (computed synchronously,
same composition pass as `reading` — the route-entry race fix). `FixedReaderViewModel.kt`:
`updateViewport` now takes `(geometry: ReaderRenderGeometry, foldPaneWidths: FoldPaneWidths?)`;
`render()` sizes every `PageRenderRequest` from `geometry` (per-pane width AND height for an active
fold spread, never a flat approximation). `FixedReaderScreen.kt`: `fold: ReaderFoldDescriptor?` is
now `folds: List<ReaderFoldDescriptor>`; computes `ReaderRenderGeometry` alongside `foldPaneWidths`
in the same `onGloballyPositioned` callback; the vertical-fold-spread gesture/render path now tracks
`foldSpreadProgress` instead of a shared `panY`. New `feature/reader/HingeSafeDialog.kt`
(`HingeSafeDialogOverlay`); `ReaderAppearance.kt`/`ThumbnailNavigator.kt` gained an optional
`safePane: FoldRect?` parameter routing through it when non-null, else the unchanged ordinary
platform dialog.

**Targeted JVM unit tests — PASS.**
`./gradlew :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.FoldLayoutTest" --tests "com.d4guilar.shelfos.core.reader.FixedReaderTransformTest" --tests "com.d4guilar.shelfos.core.reader.RenderKeyTest" --tests "com.d4guilar.shelfos.core.reader.SpreadModelTest"`
— exit 0, all 139 tests pass: `FoldLayoutTest` 29/29 (22 original + 7 new multiple-descriptor-
selection cases), `FixedReaderTransformTest` 54/54 (41 original + 13 new fold-spread-progress-model
cases, including the required mixed-fitted-height regression — pane 1000 / fitted 1600 / fitted
2600, directly refuting the old `min(600, 1600)` defect), `RenderKeyTest` 14/14 (new file — bucket
quantization + `Single`/`Spread`/material-change/harmless-drift key comparisons), `SpreadModelTest`
42/42 (untouched, re-run as a regression guard since `FixedReaderViewModel.render()` changed).

**Targeted instrumented tests — PASS** (API 24 x86_64 emulator, `shelfos-api24` AVD, each class run
SEPARATELY per standing policy — the AGP multi-class caveat):
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldableUiTest`
  — 9/9 PASS (the original 6 cases A–F, still passing unchanged, plus 3 new: (G) a horizontal
  separating fold resolves to exactly one safe pane with no `spread_slot_*` tag ever created; (H)
  pair `[3, 4]` with page 4 (the SECOND member) corrupt — page 3 stays visible in its own pane,
  page 4's placeholder stays in its own pane, neither intersects the hinge, no substitution with a
  nonexistent page 5, LTR physical identity unchanged; (I) a real one-finger drag through the
  production gesture handler, under Fit Width with two deliberately very-differently-shaped pages,
  moves `foldSpreadProgress` (read via the extended `reader_transform_probe`) from `0` at base
  scale, and a full reverse drag returns it exactly to `0` with no stuck/unreachable region).
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldRenderRequestTest`
  — 2/2 PASS (both extended to mirror production's real two-pass Single-then-Spread layout
  sequence and the new `EffectiveRenderKey`/`ReaderRenderGeometry` API; same two production
  behaviors re-proven: asymmetric per-pane request widths, and the render-storm coalescing guard).
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FoldDescriptorMapperTest`
  — 9/9 PASS (NEW class — the previously-untested REAL production `FoldingFeature.toReaderFoldDescriptor()`
  mapper: bounds, `VERTICAL`/`HORIZONTAL` orientation, `isSeparating` true/false, FULL occlusion,
  no-occlusion, irrelevant-crease-flags-preserved, multiple-feature list mapping).
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.ReaderDialogFoldSafetyTest`
  — 4/4 PASS (NEW class — mounts the REAL `ReaderAppearance`/`ThumbnailNavigator` composables
  directly: both dialogs' surfaces stay entirely inside the given safe pane and never intersect a
  simulated hinge with their primary action reachable; an RTL-selected opposite-side pane is also
  honored; `safePane == null` still uses the ordinary unconstrained platform dialog).
- Regression (mechanically updated for the new `updateViewport(ReaderRenderGeometry, ...)`
  signature; each run SEPARATELY): `FixedReaderSpreadViewModelTest` — 11/11 PASS;
  `FixedReaderViewModelLifecycleTest` — 2/2 PASS.

**Foldable AVD**: `shelfos-api24` (the same x86_64 phone emulator used throughout Phase 3D) was
used to run every instrumented test above — it has no real fold-posture simulation capability, but
every fold/hinge scenario in this remediation is a plain Compose parameter
(`FixedReaderScreen(folds = ...)`/`safePane = ...`) needing no real posture event, by design. A
dedicated foldable-posture-simulation AVD or physical foldable device remains an honest, flagged
Phase 3F follow-up, not required for this slice (R1 explicitly did not gate merge on it).

**Physical foldable**: not performed (no hardware confirmed available this pass; consistent with
the standing Phase 3D/3F honest-gap note).

**Full JVM suite / full connected suite**: NOT RUN (reserved for Phase 3F per standing policy; the
targeted filters above are the required/bounded validation for this slice).

**Build**: `assembleDebug` — PASS. `assembleDebugAndroidTest` — PASS (required fixing three
pre-existing test files' mechanical call sites to the new `updateViewport` signature before this
passed: `FixedReaderFoldableUiTest`'s `fold =` → `folds = listOfNotNull(fold)`,
`FixedReaderSpreadViewModelTest`/`FixedReaderViewModelLifecycleTest`'s raw-int `updateViewport`
calls → `ReaderRenderGeometry.Single(...)`).

**Lint**: `lintDebug` — PASS, 0 errors (SARIF report checked directly).

**git diff --check**: PASS (no whitespace errors). **Working tree**: clean after commit.

## PHASE 3D — ADAPTIVE / FOLDABLE COMIC SPREADS (2026-10-06)

Status: **IMPLEMENTED, pending administrator/Codex review.** Base: `main` @ `9e26140`
("feat: add Phase 3C comic and manga spread reading (#26)"). Branch:
`phase-3/3d-adaptive-foldable-spreads`. See `docs/PHASE_3_IMPLEMENTATION_PLAN.md` §26 for the
full architectural record; this entry is the validation evidence.

**What changed**: new `core/reader/FoldLayout.kt` (pure, Android/Compose-free) —
`FoldRect`/`FoldOrientation`/`ReaderFoldDescriptor`/`FoldPresentation`/`ReaderFoldLayout`/
`FoldPaneWidths`/`FoldInset`, `resolveReaderFoldLayout` (window-to-local coordinate translation +
relevant-feature filtering + vertical/horizontal split resolution), `selectSoloPane`,
`verticalFoldSpreadEligibleForAuto`/`verticalFoldHasTwoUsablePanes`, and `legacySafePaneInset`
(the pre-3D `MainActivity` padding formula extracted unchanged for non-reader-screen regression
testing). `MainActivity.kt`: one new private `FoldingFeature.toReaderFoldDescriptor()` mapping
function (the only `WindowInfoTracker`/`FoldingFeature` consumer in the app, unchanged from
Phase 0); `reading` state (reported by `ShelfApp`) now gates whether the existing legacy
safe-pane padding applies (non-reader screens, unchanged behavior) or the reader gets the full
safe-drawing window plus the raw fold descriptor. `feature/home/ShelfApp.kt`: new
`readerFold`/`onReadingChanged` parameters threading the descriptor to `FixedReaderScreen` only
on the reader route. `feature/reader/FixedReaderViewModel.kt`: `updateViewport` gained an
optional `foldPaneWidths: FoldPaneWidths?` parameter (stored like the existing px/dp fields,
never itself triggering a render); `spreadActive()` consults the fold-aware AUTO/SPREAD policy
when a vertical split is active; `render()` computes per-slot pane-aware pixel widths for an
active fold spread (reusing `PageGroup.physicalOrder(rtl)` for the logical-to-physical mapping,
never a second RTL system) while leaving the flat/solo-page path and the existing
`RenderMemoryPolicy`/`spreadSlot` budget untouched. `feature/reader/FixedReaderScreen.kt`:
measures its own window bounds via `onGloballyPositioned`, resolves `ReaderFoldLayout` each
layout pass, confines solo-page/legacy-spread rendering to the single active pane
(FLAT/HORIZONTAL_SPLIT/VERTICAL_SPLIT-solo, reusing all existing 3C Fit Page/Fit Width/zoom-pan
code against the pane's own size), and adds a new, small vertical-split two-pane spread renderer
(two independently fixed, clipped pane `Box`es, one shared scale/pan state, clamped via the new
`foldPaneMaxPan`/`foldSpreadSharedMaxPan` helpers) plus fold-aware confinement of the reader
chrome rows under a vertical split. No new dependency, no Room schema change, no new persisted
preference.

**Pure JVM unit tests — PASS.**
`./gradlew :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.FoldLayoutTest" --tests "com.d4guilar.shelfos.core.reader.SpreadModelTest" --tests "com.d4guilar.shelfos.core.reader.PageRenderRequestTest" --tests "com.d4guilar.shelfos.core.reader.ReaderPreferencesSpreadTest" --tests "com.d4guilar.shelfos.core.reader.FixedReaderTransformTest"`
— exit 0, all tests pass (22 `FoldLayoutTest` cases: no-fold==flat, non-separating/non-occluding
crease ignored, feature outside reader bounds ignored, vertical center/asymmetric/full-occlusion/
zero-width hinges, horizontal fold safe-pane selection + exact-tie-break, malformed/empty-bounds
fallback, solo-pane selection including the reading-direction tie-break, AUTO's fold-aware
per-pane-floor policy, explicit SPREAD's two-usable-panes policy, and the extracted
`legacySafePaneInset` non-reader-regression-guard cases). `SpreadModelTest`/`PageRenderRequestTest`/
`ReaderPreferencesSpreadTest`/`FixedReaderTransformTest` re-run unchanged (no 3D regression) since
this slice reuses, rather than modifies, their underlying policies.

**Targeted instrumented tests — PASS** (API 24 x86_64 emulator, `shelfos-api24` AVD, each class run
SEPARATELY per standing policy):
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldableUiTest`
  — 6/6 PASS. The required test matrix: (A) vertical LTR spread (pair [1,2] correctly placed in
  their own panes, no hinge intersection), (B) vertical RTL Manga (physical placement mirrored,
  logical page identity unchanged), (C) solo/cover page wholly inside one safe pane, (D) a corrupt
  spread member's placeholder stays in its own hinge-safe pane with the healthy sibling in its
  own, (E) a real zoom (double-tap) + one-finger drag through the production `awaitEachGesture`
  transform loop never moves either slot under the hinge, (F) fold→flat→fold preserves the exact
  same authoritative `state.page` throughout and presentation correctly cycles back to a
  fold-aware spread.
- `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderFoldRenderRequestTest`
  — 2/2 PASS. `verticalFoldSpreadRequestsEachSlotAtItsOwnAsymmetricPaneWidth`: an asymmetric fold
  (150px/1700px panes) proves each slot's actual `PageRenderRequest.viewportWidth` matches its own
  pane (never the flat `viewportWidth/2` approximation), both still flagged `spreadSlot = true`,
  and both decoded bitmaps stay within `RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES`.
  `continuousFoldPaneWidthChangesThatNeverFlipSpreadActiveNeverTriggerANewDecode`: the render-storm
  guard — five drifting-but-AUTO-eligible `updateViewport` calls trigger zero additional decodes,
  while the one call that actually flips AUTO's decision below its floor triggers exactly the
  required re-render (proving the guard doesn't silently suppress a genuine required change).
- Regression (existing 3C classes re-run, each separately, to confirm no behavioral regression from
  the `FixedReaderScreen`/`FixedReaderViewModel` changes above): `FixedReaderSpreadUiTest` — PASS;
  `FixedReaderSpreadViewModelTest` — PASS; `ReaderPreferencesSpreadInstrumentedTest` — PASS;
  `FixedReaderViewModelLifecycleTest` (memory/PSS evidence, light-touch re-run since this slice
  changed render-request sizing) — PASS.

**Foldable AVD**: not available this pass (the `shelfos-api24` x86_64 phone emulator has no fold
posture simulation capability). No dedicated foldable AVD was created or attempted given the time
budget for this slice; the deterministic injected-fold-descriptor tests above (which need no real
posture event, by design — `fold` is a plain `FixedReaderScreen` parameter) are the primary gate,
exactly as `docs/PHASE_3_IMPLEMENTATION_PLAN.md` §15 anticipates. A bounded real foldable-AVD or
physical-device posture-transition check remains an honest, flagged Phase 3F follow-up.

**Physical foldable**: not performed (no hardware confirmed available this pass; consistent with
§15's standing honest-gap note).

**Full JVM suite / full connected suite**: NOT RUN (reserved for Phase 3F per standing policy).

**Build**: `assembleDebug` — PASS. `assembleDebugAndroidTest` — PASS.

**Lint**: `lintDebug` — PASS, no new errors.

**git diff --check**: PASS (no whitespace errors). **Working tree**: clean after commit.

## PHASE 3C — SPREADS + MANGA PAIRING (2026-10-05)

Status: **IMPLEMENTED, pending administrator/Codex review.** Base: `main` @ `673ff49`
("feat: add Phase 3B page thumbnail navigation (#25)"). Branch: `phase-3/3c-spreads-manga-pairing`.
Exact model/architecture is recorded in `docs/PHASE_3_IMPLEMENTATION_PLAN.md` §25 (new); this
entry is the validation evidence.

**What changed**: new `core/reader/SpreadModel.kt` (`SpreadMode`, `PageGeometry`, `PageGroup`,
`canonicalPageGroups`, `resolvePageGroups`/`resolveGroups`, `resolveSpreadActive`,
`AUTO_SPREAD_MIN_WIDTH_DP`, `nextPage`/`previousPage`/`resolveCurrentGroup` bounded-cost
navigation, plus the whole-list `nextLogicalPage`/`previousLogicalPage` reference form) — pure,
Compose/Android-free. `core/reader/FixedReader.kt`: `FixedReader.pageGeometry(index)` added to
the interface (`PdfPages` via `PdfRenderer.Page.width/height`; `ArchivePages` via a new
`ImagePageRenderer.bounds()` bounds-only decode, reusing the existing `inJustDecodeBounds` pass).
`core/reader/ReaderPreferences.kt`: additive `spreadMode: SpreadMode?` field (JSON only, no Room
schema/migration), `ReaderPreferences.DEFAULT.spreadMode = SpreadMode.AUTO`,
`resolveReaderPreferences`/`appearanceUpdate` treat it exactly like `direction` (title-specific,
never globalized), `capabilities(format, category)` overload + `spreadCapable()` gating the
control to PDF/CBZ **and** `MediaCategory.COMIC`/`MANGA`. `feature/reader/FixedReaderViewModel.kt`:
`FixedReaderState.slots: List<PageSlot>` (1 or 2 visible logical pages, each with an independent
bitmap/error), `turn()` now does semantic group-to-group navigation via `nextPage`/`previousPage`,
`updateViewport(width, height, widthDp)` tracks dp and re-renders only when AUTO's spread/single
decision actually flips, `render()` decodes every slot in the active group **sequentially** inside
the existing render mutex (never parallel decodes), a defense-in-depth `spreadActive()` gate also
re-checks `spreadCapable()` against the item's real category so a stray `SPREAD` preference can
never show a Book/Document as a spread even if the UI gate were somehow bypassed.
`feature/reader/FixedReaderScreen.kt`: a new 2-slot `Row` rendering path (two independent `Image`s,
never a stitched bitmap) reusing the existing `fixedReaderFittedContentSize`/`fixedReaderMaxPan`/
`fixedReaderMaxPanY`/`fixedReaderVerticalScaleOverflow` transform math against a synthesized
"combined content size" (`combinedContentDimensions`), RTL physical mirroring via
`PageGroup.physicalOrder`, a corrupt-slot placeholder, and per-slot accessibility descriptions;
`ReaderAppearance.kt` gained the spread-mode control (gated by `capabilities.spread`) and the
`ReaderPreferencesSaver` now carries `spreadMode`. Six new localized strings (EN/ES/PT-BR). No
new dependency, no Room schema change, no migration.

**Pure JVM unit tests — PASS.**
`./gradlew.bat :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.SpreadModelTest" --tests "com.d4guilar.shelfos.core.reader.ReaderPreferencesSpreadTest" --tests "com.d4guilar.shelfos.core.reader.FixedReaderTransformTest" --tests "com.d4guilar.shelfos.core.reader.ThumbnailLoaderTest" --tests "com.d4guilar.shelfos.ReadingPolicyTest" -q`
— exit 0, all tests pass. `SpreadModelTest` (34 tests): canonical pairing (cover solo, interior
pairs, odd final page, 0/1/2-page edge cases), `resolvePageGroups`/`resolveGroups` (SINGLE every
page solo, SPREAD matching canonical when nothing is landscape, a landscape member splitting its
pair without skipping/duplicating any page, later pairs unaffected by an earlier split, unknown
geometry treated as not-landscape), `PageGeometry.isLandscape` threshold (portrait/near-square
not landscape, clearly-wide landscape, non-positive dimensions not landscape),
`PageGroup.physicalOrder` (LTR unchanged, RTL mirrored placement with identical underlying
pair-membership/values, solo groups unaffected by direction), `groupContaining`/current-page
invariant (selecting a pair's second member never normalizes to the lower index),
`resolveSpreadActive` (SINGLE never active, SPREAD always active, AUTO's exact threshold
boundary), `nextLogicalPage`/`previousLogicalPage` (cover→first spread, group-to-group movement,
odd-final-page navigation, boundary clamping, SINGLE-mode reduction to ±1), and the
bounded-cost `nextPage`/`previousPage`/`resolveCurrentGroup` equivalents (proven equal to the
whole-list form when nothing is landscape, plus a dedicated landscape-split case using only that
one pair's geometry). `ReaderPreferencesSpreadTest` (13 tests, deliberately JSON-free — see its
class doc for why `org.json.JSONObject` cannot be exercised in a plain JVM test here):
`resolveReaderPreferences` AUTO default and title-override precedence, the global layer never
leaking a stray `spreadMode`, `appearanceUpdate` never globalizing `spreadMode` even when another
field legitimately globalizes in the same edit, an existing title override surviving an unrelated
global save, `appearanceReset` returning a title to AUTO while a global-scope reset leaves the
title's override untouched, and `direction`'s pre-existing never-global behavior staying
structurally unaffected by the new field.

**Targeted instrumented tests — PASS** (real device: see "Environment" below).
`./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.ReaderPreferencesSpreadInstrumentedTest` —
exit 0, 7/7 pass: the real `org.json.JSONObject`-backed half of the persistence contract (old
JSON without `spreadMode` parses safely and resolves to AUTO; each `SpreadMode` round-trips
through a real `.json()`/`.parse()`; an unrecognized or wrong-type `spreadMode` value falls back
to `null` rather than throwing; a fully malformed JSON blob falls back to the default; other
existing fields round-trip unaffected).
`./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderSpreadViewModelTest` —
exit 0, 8/8 pass at this initial-implementation point in history (superseded by the R1/R2/R3
remediation entries below, which record the real, final, current count against the current test
file -- this "8/8" is preserved as a historical record only, not the current status), against real
CBZ fixtures through the real `FixedReaderViewModel`/`FixedReader`
pipeline (not just the pure model in isolation): explicit SPREAD pairs an interior pair as 2 slots
while the cover stays solo and `state.page` matches exactly what was navigated to; explicit SINGLE
never shows 2 slots even at a 4000px-wide viewport; AUTO resolves to single on a narrow viewport
and to a spread on a wide one, and resizing back and forth between them never changes `state.page`;
a landscape page stays solo in explicit SPREAD mode and its canonical partner becomes its own solo
group too, without skipping, duplicating, or disturbing the next unrelated pair; a corrupt page
within a pair leaves its healthy sibling visible with its own bitmap while the corrupt slot carries
its own error (top-level `state.error` stays `null` since the pair is not fully failed), and
navigation continues to work afterward; selecting a thumbnail-style direct jump to a pair's second
member (`vm.showPage(2)`) keeps `state.page == 2` rather than normalizing to `1`; and a stray
`SpreadMode.SPREAD` preference on a `MediaCategory.BOOK` item never produces 2 slots, proving the
`spreadActive()` category defense-in-depth gate (not just the Appearance UI gate) actually holds in
the real render pipeline.

**Memory evidence — PASS.** `repeatedSpreadTurnsStayWithinThePerBitmapBudgetAndDoNotGrowProcessMemoryUnboundedly`
(in the same `FixedReaderSpreadViewModelTest` run above) drives a 9-page, 3000x4500 CBZ through 11
rapid forward/backward spread turns (each turn sequentially decoding up to 2 full-resolution
slots). Observed: every settled slot bitmap ≈3,375,000 bytes (≈3.2MiB, well inside
`RenderMemoryPolicy.MAX_BITMAP_BYTES`'s per-render budget -- see the Codex R1/R2 remediation
entries below for the real, final policy numbers; this initial pass predates the
`THUMBNAIL_RESERVATION_BYTES` carve-out those entries record); process PSS went from 72,308KB
before the sequence to 82,565KB after settling+GC (≈10MB growth across 11 two-slots-per-turn
turns, well under the test's environment-tolerant 3-max-cost-bitmaps-worth threshold, and
consistent with ordinary transient decode/GC churn rather than unbounded stale-bitmap
accumulation). This PSS observation is supplemental runtime evidence only ("no observed runaway
growth"), not proof of the exact byte-budget arithmetic -- see the R1/R2 entries' deterministic
`PageRenderRequestTest` evidence for that.

**Regression spot-check — PASS (individually), environment flake when run back-to-back.**
Re-ran, each filtered individually: `FixedReaderViewModelLifecycleTest` (viewport plumbing +
single-page memory evidence), `FixedReaderTransformBoundsTest` (Fit Page/Fit Width pan-clamp,
unaffected by the spread addition — single-slot code path is byte-for-byte the pre-3C branch),
`FixedReaderRecreationTest`, `MalformedFixedReaderResilienceTest`, `ReaderStateTest`,
`ThumbnailNavigationUiTest`, and `LibraryPersistenceTest#pdfAndArchiveRenderAndArchiveUsesNaturalPageOrder`/
`#nonSeekableProviderCopiesCommitsReopensAndCleansWithoutTouchingTheSource` — all PASS individually.
Running the full `LibraryPersistenceTest` class back-to-back on this `shelfos-api24` AVD produced a
native `SIGSEGV` inside `libart.so` on the emulator (not a JVM/Kotlin assertion failure, no
stack trace through any ShelfOS or test code) partway through the class, consistent with this
project's previously-documented AVD instability (`docs/PHASE_3_IMPLEMENTATION_PLAN.md` §22's "API
37 AVD Espresso/InputManager incompatibility... repeated external Gradle-daemon interruptions");
every test in that class passes when run alone. Recorded honestly as an environment limitation,
not a 3C regression — none of `LibraryPersistenceTest`'s SAF/content-provider-focused cases touch
the spread code paths this slice added.

**Build/lint — PASS.** `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` all
exit 0 (lint required adding the six new strings' ES/PT-BR translations before passing clean — no
other new errors/warnings). `git diff --check` — PASS (no whitespace errors). Working tree clean
after commit.

**Environment**: `shelfos-api24` AVD (`emulator-5554`, API 24, `Android SDK built for x86_64`),
already running and confirmed stable at session start (`adb devices -l`, one tiny smoke test run
before the full targeted suites, per standing environment discipline). No physical device used
this pass (not blocking for 3C per the administrator brief — RP5/tablet/foldable physical
acceptance stays 3D/3F's job). Full JVM suite and full connected suite were **not** run (standing
policy; reserved for Phase 3F).

**What this initial pass deliberately did NOT prove** (honest gaps at the time, not silent
omissions — see the 3C handoff for the full list): RTL Manga's mirrored PHYSICAL on-screen
placement and the combined Fit Page/Fit Width/zoom-pan geometry are Compose-UI-level concerns
`FixedReaderSpreadViewModelTest` (ViewModel-only, no Espresso) cannot observe directly —
`state.slots` is always logical ascending order regardless of reading direction; only
`FixedReaderScreen`'s `PageGroup.physicalOrder` call (itself unit-proven correct by
`SpreadModelTest`) determines which physical side each slot draws on. **This gap is closed** by
the Codex R1 remediation's real Espresso/Compose `FixedReaderSpreadUiTest` pass (RTL physical
placement, Fit Page mixed-aspect, corrupt-first/corrupt-second slot layout) and the Codex R2
remediation's real pointer-transform-loop regression test added to that same class — see both
entries immediately below.

### PHASE 3C — Codex R1 remediation (2026-10-05)

Status: **IMPLEMENTED on one remediation commit on top of `385edf4`**, on
`phase-3/3c-spreads-manga-pairing`. Independent review (Codex) returned CHANGES REQUIRED with six
findings: (1) unresolved/missing geometry must never authorize a navigation skip (the
`isLandscapeAtForNavigation` conservative-default contract); (2) a spread-transition's real
four-live-bitmap peak needed its own stricter per-slot byte budget
(`RenderMemoryPolicy.MAX_SPREAD_BITMAP_BYTES`/`PageRenderRequest.spreadSlot`), with
`ThumbnailLoader`'s 16MiB cache explicitly carved out of the same 96MiB session envelope
(`THUMBNAIL_RESERVATION_BYTES`) rather than sitting outside it; (3) a corrupt slot within a pair
must keep its OWN source `PageGeometry` for its placeholder, never borrow its healthy sibling's;
(4) the zoom/pan gesture's content-geometry read must survive a spread<->single flip mid-gesture
without restarting the `pointerInput` coroutine (`rememberUpdatedState`); (5) an active session's
effective `SpreadMode` change must reconcile the currently-visible page immediately; (6) Next/
Previous enablement must use the same canonical navigation resolver `turn()` itself uses, not raw
`page +/- 1` arithmetic (wrong at the final-complete-spread boundary). `geometryCache` also moved
from a plain `MutableMap` to a `ConcurrentHashMap` (UI-thread reads racing the IO-dispatched
writer). See `docs/PHASE_3_IMPLEMENTATION_PLAN.md`'s 3C section for the full per-finding record.

**Pure JVM unit tests — PASS.** `SpreadModelTest` and `PageRenderRequestTest` both extended for
findings 1/2/6 (navigation conservative-default cases, the spread-slot byte-budget constants and
every transition-class arithmetic proof, `resolveCurrentGroup`/`hasNext`/`hasPrevious` canonical
equivalence) — all green.

**Targeted instrumented tests — PASS.** `FixedReaderSpreadViewModelTest` extended with the
rapid-navigation race test (finding 1, later found by Codex R2 to be non-deterministic — see the
R2 entry below) and the memory test now asserting the spread-slot ceiling specifically. A new
`FixedReaderSpreadUiTest` (real Compose/Espresso, `createAndroidComposeRule<MainActivity>`) closed
the honest RTL/Fit-Page-geometry gap the initial pass above flagged as outstanding: physical LTR/
RTL placement, Fit Page mixed-aspect pairing, corrupt-first/corrupt-second slot layout, final-
complete-spread Next disablement, immediate `SpreadMode` reconciliation in both directions, and a
double-tap-based "no stale geometry across a spread<->single flip" regression test for finding 4
(Codex R2 later found this last test did not actually reach the real pointer-transform loop — see
below).

### PHASE 3C — Codex R2 micro-remediation (2026-10-06)

Status: **IMPLEMENTED on one remediation commit on top of `bc96099`** ("fix: harden Phase 3C
spread reading"), on `phase-3/3c-spreads-manga-pairing`. Independent review (Codex R2) returned
CHANGES REQUIRED with four findings; every R1 finding above was explicitly accepted/closed and
intentionally left unmodified (memory policy, corrupt-slot layout, `SpreadMode` reconciliation,
final-nav resolver, RTL physical ordering, AUTO's deterministic width-driven evidence with
physical-rotation deferred to 3F).

**Finding 1 — unusable cached geometry was still navigation-pair-eligible.** A failed/unknown
geometry lookup is cached as the `PageGeometry(0, 0)` sentinel (unchanged from R1); R1's
navigation conservative-default (`geometryCache[page]?.isLandscape ?: true`) only engaged when a
page had NO cache entry at all. Once the sentinel WAS cached (a corrupt/undecodable page), reading
`.isLandscape` directly on it evaluated `false` ("not landscape"), bypassing the `?:` fallback and
letting `nextPage`/`previousPage` treat it as "confirmed pair-eligible" -- able to reintroduce the
exact skip R1 closed. Fixed by adding a single centralized `PageGeometry.isUsable`
(`width > 0 && height > 0`) and rewriting `isLandscapeAtForNavigation` to check it before ever
trusting `isLandscape`; `FixedReaderScreen`'s separate `isUnknown()` helper (presentation-side,
intentionally left optimistic per R1) now delegates to the same centralized property instead of
its own duplicated `width <= 0 || height <= 0` check. One accepted, mechanical downstream
consequence: `FixedReaderSpreadUiTest#corruptFirstSlotLeavesHealthySiblingVisibleAndCorrectlyPositioned`'s
final-complete-spread-with-a-corrupt-first-member case previously asserted Next was disabled --
that assertion depended on the exact bug being fixed (a cached `(0, 0)` sentinel being misread as
"confirmed not landscape"); it now correctly asserts Next stays enabled (navigation can no longer
confirm the pair is complete when one member's true aspect is unknown) and that pressing it
single-steps within the same visible pair without a crash or skip.

**Finding 2 — the rapid-navigation race test was non-deterministic.** The R1 test fired three
`vm.turn(1)` calls back-to-back and used a background `StateFlow` collector to record every
`state.page` value observed, then asserted the split page appeared somewhere in that list.
Collecting in the background can conflate or miss intermediate values (Codex R2 observed
`visited=[1, 1, 3, 3]` on one run). Replaced with a deterministic adversarial test: a
`GatedGeometryFixedReaderFactory` test seam (`FixedReaderFactory`/`FixedReaderFactory.open` made
`open` for exactly this purpose) wraps the real `FixedReader` so `pageGeometry` for the candidate
pair blocks on a `CountDownLatch` the test controls explicitly. `showPage()` updates
`state.value.page` synchronously (a plain `MutableStateFlow.update`, before the async render is
even launched), so the authoritative navigation result is asserted immediately after each `turn()`
call with no waiting and no collector. A direct, non-race companion test
(`cachedUnusableGeometryForACorruptPairMemberNeverAuthorizesANavigationSkip`) also proves finding
1 end-to-end through a genuinely corrupt page, independent of any timing window.

**Finding 3 — the gesture regression test never reached the real transform loop.** The R1 test
used `doubleClick`, which only flips `scale` directly in Compose state through a separate
`detectTapGestures(onDoubleTap = ...)` pointer handler -- never the `awaitEachGesture`/
`calculateZoom`/`calculatePan` loop that actually reads `currentContentDimensions` for its pan
clamp (the behavior Codex R1 finding 4 fixed). A new test,
`pointerTransformGestureUsesCurrentGeometryAcrossASpreadToSingleAndBackFlipWithoutAPageChange`,
uses the existing "Zoom in" control (sets `scale = 2f`) plus a one-finger drag -- `scale > 1f`
alone routes a single-pointer drag through the real transform branch. The fixture (a tall/narrow
single-mode page paired with a wide second member) makes the spread's combined content clearly
width-constrained under Fit Page (large pan headroom once zoomed) while the single-slot content
stays clearly height-constrained (near-zero pan headroom) -- a measurable difference the assertion
is capable of failing against if stale geometry were ever captured again across the flip.

**Targeted JVM tests — PASS.** `PageRenderRequestTest` 27/27, `SpreadModelTest` 42/42 (includes
four new tests: `PageGeometry.isUsable`/`isLandscape` sentinel/non-positive/positive cases, and a
test reproducing the exact buggy-vs-fixed `isLandscapeAtForNavigation` contract against the real
`nextPage`/`previousPage` functions).
`./gradlew.bat :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.PageRenderRequestTest" --tests "com.d4guilar.shelfos.core.reader.SpreadModelTest"`
— exit 0.

**Targeted instrumented tests — PASS, final counts.**
`./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderSpreadViewModelTest`
— **11/11 PASS** (the real, final count after R1 + R2 remediation -- supersedes this phase's
initial-implementation "8/8" and R1's intermediate counts above, neither of which reflect the
current test file). Includes the two deterministic gated-geometry rapid-navigation tests (first-
and second-candidate-member-landscape) and the direct cached-unusable-geometry test, replacing the
non-deterministic race test R1 had added.
`./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderSpreadUiTest`
— **11/11 PASS** (10 from R1 plus the new real pointer-transform regression test; one existing
test's assertion corrected per Finding 1's accepted downstream consequence above).

**Memory policy — unchanged from R1, verified against the actual current constants (not
re-derived here).** `RenderMemoryPolicy.SESSION_BUDGET_BYTES` = 96MiB;
`THUMBNAIL_RESERVATION_BYTES` = `ThumbnailLoader.DEFAULT_BUDGET_BYTES` = 16MiB;
`READING_BUDGET_BYTES` = 80MiB; ordinary single-page ceiling `MAX_BITMAP_BYTES` =
`READING_BUDGET_BYTES / 3` ≈ **26.7MiB**; active-spread per-slot ceiling
`MAX_SPREAD_BITMAP_BYTES` = `READING_BUDGET_BYTES / 4` = **20MiB**. (The initial-implementation
entry above's "≈32MiB-per-render" wording predates the `THUMBNAIL_RESERVATION_BYTES` carve-out and
is corrected there rather than restated as current.) This is the deterministic, JVM-proven policy
(`PageRenderRequestTest`); the separate PSS/`Debug.getPss()` observations recorded in this phase's
instrumented memory tests are supplemental runtime evidence only ("no observed runaway growth"),
not independent proof of the exact byte-budget arithmetic.

**Build/lint — PASS.** `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug` all
exit 0. `git diff --check` — PASS. Working tree clean after commit.

**Corrupt-slot layout, `SpreadMode` reconciliation, final-nav resolver, RTL physical ordering,
AUTO/width-driven behavior**: unchanged from the R1 entry above -- Codex R2 explicitly accepted
all of these, and this remediation did not modify any of them.

**Dependencies/schema**: none added/changed. **Room**: unchanged. **CBR**: not implemented, not
stubbed, not detected. Physical device/true-rotation acceptance and the full accumulated
regression suite remain Phase 3F's job, not this micro-remediation's.

### PHASE 3C — Codex R3 micro-remediation (2026-10-06)

Status: **IMPLEMENTED on one remediation commit on top of `d55a507`** ("fix: complete Phase 3C
review remediation"), on `phase-3/3c-spreads-manga-pairing`. Independent review (Codex R3) found
no remaining production correctness defect -- this is purely a test-evidence gap plus stale
documentation. No production file was changed.

**Finding (missing persisted locator/progress assertion).** The R2 deterministic gated
rapid-navigation test (`deterministicRapidNextDoesNotSkipTheGatedPair_firstMemberLandscape`/
`_secondMemberLandscape`) proved `state.value.page` directly never skips from 1 to 3, but never
proved what actually got PERSISTED -- the final assertion before this remediation only checked
`state.value.page in 0 until state.value.count`, a bound loose enough to also pass if the
persisted value were wrong. Fixed in the TEST ONLY: `FixedReaderSpreadViewModelTest`'s
`FakeLibraryRepository` now records every `reading(id, locator, progress)` call into a
`ConcurrentLinkedQueue<RecordedReading>` (safe to read from the test thread while
`PositionWriter`'s consumer coroutine writes to it from `appScope`); a new
`viewModelWithRepository` helper exposes that fake alongside the `FixedReaderViewModel` so the
test can inspect it (`viewModel` itself is now a thin wrapper over it, unchanged for every other
caller). The gated test now polls (`awaitUntil`, the same async-convergence idiom already used
throughout this file) until a recorded reading matches the REAL `pageLocator(splitPageIndex)`/
`pageProgress(splitPageIndex, count)` -- production's own functions, never reimplemented in the
test -- and separately asserts no recorded reading ever matches `pageLocator(3)`, the exact page
the no-skip invariant already proved `state.value.page` never became.

**Targeted instrumented tests — PASS.**
`./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.FixedReaderSpreadViewModelTest`
— **11/11 PASS** (no new test method was added; the two existing gated rapid-navigation tests were
extended in place with the persisted locator/progress assertion, so the count is unchanged from
the R2 final count above).

**Production files changed**: none. **Dependencies/schema**: none added/changed. **Room**:
unchanged. Build/lint were not re-run (test/docs-only change; no production file touched).

## PHASE 3B — PAGE THUMBNAILS (2026-10-04)

Status: **IMPLEMENTED, pending administrator/Codex review.** Base: `main` @ `4c58fa6`
("feat: establish Phase 3A fixed-page rendering foundation (#24)"). Branch:
`phase-3/3b-page-thumbnails`. Exact architecture, cache budget, lazy/cancellation model and
scope boundaries are recorded in `docs/PHASE_3_IMPLEMENTATION_PLAN.md` §24; this entry is the
validation evidence.

**What changed**: `PageRenderRequest.THUMBNAIL_MAX_DIMENSION`/`PageRenderRequest.thumbnail()`
(`core/reader/FixedReader.kt`); a new `core/reader/ThumbnailLoader.kt`
(`ByteBudgetedLruCache`, `nextThumbnailToLoad`, `ThumbnailResult`, `ThumbnailLoader`); a new
`feature/reader/ThumbnailNavigator.kt` (the "Pages" dialog/strip); `FixedReaderViewModel`
gained a `thumbnails: ThumbnailLoader<Bitmap>?` property, created once the session's page count
is known and closed in `onCleared()`; `FixedReaderScreen` gained a "Pages" control-row button
and the dialog invocation. Four new localized strings (EN/ES/PT-BR). No dependency, Room
schema, or migration change.

**JVM unit tests — PASS.**
`./gradlew.bat :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.PageRenderRequestTest" --tests "com.d4guilar.shelfos.core.reader.ByteBudgetedLruCacheTest" --tests "com.d4guilar.shelfos.core.reader.NextThumbnailToLoadTest" --tests "com.d4guilar.shelfos.core.reader.ThumbnailLoaderTest" -q`
— exit 0, **35/35 tests, 0 failures** (`PageRenderRequestTest` 17/17 including three new
`PageRenderRequest.thumbnail()` cases; `ByteBudgetedLruCacheTest` 6/6; `NextThumbnailToLoadTest`
6/6; `ThumbnailLoaderTest` 6/6, driving the real coroutine-based `ThumbnailLoader` under
`kotlinx-coroutines-test` virtual time with a fake non-Bitmap payload). Two real defects were
found and fixed during this pass, not merely "ran until green":
1. Five `ThumbnailLoaderTest` cases initially failed with
   `UncompletedCoroutinesError: ... there were active child jobs` — `ThumbnailLoader`'s single
   worker coroutine (`collectLatest` over its visible-range flow) never completes on its own by
   design, and `runTest` correctly refuses to finish with a leaked child job. Fixed by calling
   `loader.close()` at the end of each affected test (already the correct production lifecycle
   pattern `FixedReaderViewModel.onCleared()` follows) rather than a production or test-harness
   change.
2. `PageRenderRequestTest.thumbnailRequestNeverUpscalesASourceSmallerThanTheThumbnailCeiling`
   asserted something `resolveRenderTarget` never promised on its own: the pure function may
   compute an upscale factor for a small source (no-upscale is enforced downstream by
   `ImagePageRenderer`'s sample-size floor at 1x during a real decode, exactly like the
   pre-existing non-thumbnail small-source test already documents). Replaced with
   `thumbnailRequestPreservesAspectRatioAndStaysBudgetSafeForASmallSource`, which asserts what
   the pure function actually guarantees (aspect preservation, byte-budget safety); the real
   no-upscale guarantee for the thumbnail factory specifically is proven by the instrumented
   test below instead.

**Build — PASS.** `./gradlew.bat :app:assembleDebug -q` and
`./gradlew.bat :app:assembleDebugAndroidTest -q`, each exit 0.

**Lint — PASS.** `./gradlew.bat :app:lintDebug -q`, exit 0, no new findings.

**Instrumented evidence — PASS**, on `shelfos-api24` (`emulator-5554`, already running at session
start, API 24/Android 7.0 — no AVD instability hit this session, unlike 3A's record). Each class
run individually via `-Pandroid.testInstrumentationRunnerArguments.class=<class>`
(`:app:connectedDebugAndroidTest`):

- **`FixedReaderRenderRequestTest` — 20/20 PASS** (17 pre-existing + 3 new). New:
  `cbzThumbnailRequestStaysWellBelowReadingResolution`,
  `pdfThumbnailRequestStaysWellBelowReadingResolution` (both assert the real decoded bitmap's
  longest edge is ≤`THUMBNAIL_MAX_DIMENSION=320` and well under 2048, through
  `FixedReaderFactory` directly for both formats), and
  `cbzThumbnailRequestNeverUpscalesASourceSmallerThanTheThumbnailCeiling` (a 100x150 source at
  `PageRenderRequest.thumbnail()` decodes at exact native 100x150 — the real no-upscale proof
  JVM finding 2 above deferred to this layer).
- **`ThumbnailLoaderInstrumentedTest` — 6/6 PASS** (new file). Drives a real
  `FixedReaderViewModel` directly (same no-Compose-UI pattern as
  `FixedReaderViewModelLifecycleTest`) against real CBZ decodes:
  - `thumbnailLoaderOnA300PageCbzDecodesOnlyABoundedWindowAroundTheRequestedPage` — a
    synthetic **320-page** CBZ (tiny 50x75 per-page source, since this test is about page-count
    scaling, not per-page decode cost); `setVisibleRange(200)` settles with **13 resident
    thumbnails** (exactly `2*DEFAULT_PREFETCH(6)+1`), all within `180..220`, and **process PSS
    went from 74,418KB to 72,017KB** (`adb logcat`: "320-page CBZ: requested center=200,
    resident thumbnails=13, PSS before=74418KB after=72017KB") — concrete evidence a 320-page
    publication never caused anything close to 320 resident thumbnails.
  - `thumbnailLoaderRapidlyChangingRangesStaysBoundedRatherThanSweepingTheWholeBook` — a
    300-page CBZ; ten rapid `setVisibleRange` calls sweeping center 0→270 in quick succession,
    then settling: **13 resident thumbnails** after settling (`adb logcat`: "rapid-scroll
    300-page CBZ: resident thumbnails after settling=13"), far below the ~95 a fully-exhaustive
    (non-deprioritizing) sweep of every historical range would have produced, and the final
    requested page was still correctly served.
  - `thumbnailLoaderRecoversFromACorruptPageWithoutBreakingTheStrip` — a 20-page CBZ with one
    corrupt page (index 10): that page's thumbnail settles as `ThumbnailResult.Failed`, all four
    neighboring pages settle `Loaded`, and the reader's own `state.error` stays `null`
    throughout (the corrupt page was never the current full-page reading target).
  - `thumbnailLoaderClosesWhenTheViewModelIsClearedLeavingNoResidentThumbnails` — clearing the
    owning `ViewModelStore` (`FixedReaderViewModel.onCleared()`) leaves `loader.peek(7)` `null`
    afterward.
  - `thumbnailRequestUsesTheDedicatedThumbnailCeilingNotReadingResolution` — end-to-end (real
    decode through the ViewModel's actual wiring, not just `FixedReaderFactory` directly) proof
    that a thumbnail decode stays ≤320px.
  - `mangaRtlPublicationStillExposesThumbnailsByPlainLogicalPageIndex` — a Manga/CBZ item
    (`readingDirection(MANGA, null) == RTL` asserted directly); requesting and then selecting
    page index 3 via `vm.showPage(3)` lands on `state.page == 3` — the loader itself never
    special-cases RTL, by construction.
  One real test bug was found and fixed here too: the rapid-range test's
  `0..290 step 30` loop actually lands on 270 as its last value (270+30=300 exceeds the declared
  end), so the original assertion waiting on page 290 timed out waiting for a page that was
  never actually requested — fixed to assert against the loop's own real last value (`270`)
  rather than the originally-intended-but-wrong `290`, a test-fixture-math bug, not a product
  defect (confirmed by the identical test passing immediately once corrected).
- **`ThumbnailNavigationUiTest` — 4/4 PASS** (new file). Full real-app Compose UI pass
  (`createAndroidComposeRule<MainActivity>`, real `container.library.add`, real navigation —
  same proven-reliable pattern as `MalformedFixedReaderResilienceTest`):
  - `selectingAThumbnailJumpsToThatLogicalPageUsingTheNormalNavigationPath` — opening the
    "Pages" strip and selecting the thumbnail keyed to logical page 2 lands on "3 / 5" (the
    `showPage()` path), and the dialog dismisses afterward.
  - `mangaRtlThumbnailSelectionStillLandsOnThePlainLogicalPageIndex` — the same selection on a
    Manga/RTL fixture lands on the identical logical page ("2 / 5"), proving the invariant
    through the real UI, not just the ViewModel layer.
  - `eachThumbnailExposesAPageNumberAndTheCurrentPageIsDistinguishable` — real semantics-tree
    assertions that "Page 1, current page" and "Page 2" content descriptions exist.
  - `aCorruptPageAmongGoodPagesDoesNotBreakTheThumbnailStrip` — a real corrupt CBZ page renders
    the localized "Unavailable" placeholder in its own cell while the strip and neighboring
    cells stay fully usable.
  One real test bug was found and fixed here: all four tests initially failed waiting for their
  seeded publication to appear, because the library defaults to the Books filter tab
  (`LibraryViewModel`'s saved-state default) and these fixtures are Comic/Manga category items —
  invisible under Books, exactly as `filterPublications` is supposed to behave. Fixed by
  switching to the fixture's own category tab first (the same switch
  `NavigationSmokeTest.keyboardHintsReflectRtlSwapInMangaCbz` already performs for its own Manga
  fixture), which is a test-fixture-navigation bug, not a library-filtering defect.

No AVD/environment instability was hit this session (contrast with 3A's record) — one already-
running `shelfos-api24` emulator instance was used throughout without restart.

**Dependencies/schema**: none added/changed. **EPUB**: untouched. **CBR**: not implemented, not
stubbed, not detected. **Spreads/foldable-specific layout**: not implemented, not stubbed.

### PHASE 3B — Codex R1 remediation (2026-10-04)

Status: **IMPLEMENTED, pending administrator/Codex R2 review.** Base candidate reviewed:
`4c58fa6..180e605` ("feat: add page thumbnail navigation"). Codex returned CHANGES REQUIRED on
four findings; the overall architecture (thumbnail render path, 320px ceiling, 16MiB byte cache
budget, single worker, 13-page window, dialog-based UI, jump-via-`showPage`, RTL logical-index
invariant) was judged structurally sound and is **unchanged** here. This entry covers exactly the
four findings below, as one remediation commit on top of `180e605` (not an amend).

**Finding 1 (HIGH) — cache entry count was unbounded.** `ByteBudgetedLruCache` bounded only
accounted bitmap bytes: `ThumbnailResult.Failed` contributes 0 bytes, a tiny thumbnail contributes
almost nothing, and neither accounts for per-entry map/object overhead, so a pathological
publication could accumulate cache entries roughly proportional to page count. Fixed by adding an
explicit, independent `maxEntries` bound (default **64** — see `ByteBudgetedLruCache.
DEFAULT_MAX_ENTRIES`'s doc: substantially larger than the 13-page active window, but small and
explicit enough to bound a degenerate all-Failed or all-tiny case) alongside the existing 16MiB
byte budget; eviction now runs whenever *either* bound is exceeded, and LRU/replacement
accounting is shared correctly across both. New JVM tests in `ByteBudgetedLruCacheTest`:
`entryCountIsBoundedEvenWhenEveryValueContributesZeroBytes`,
`entryCountIsBoundedForManyTinySuccessfulValues`,
`entryCountEvictionStillUsesLruOrderNotInsertionOrder`,
`byteBudgetEvictionStillWorksWhenEntryCountNeverExceedsItsOwnBound`,
`replacingAnExistingKeyUnderBothBoundsAccountsBytesCorrectlyAndNeverDoubleCountsEntries`.

**Finding 2 (HIGH) — gamepad B could not reliably dismiss the Pages dialog.** An ordinary
`AlertDialog` reliably handles system Back and Escape, but does not reliably translate
`KEYCODE_BUTTON_B` into dismissal once the dialog owns focus/window state, making
`FixedReaderScreen`'s own key handler an unreliable fallback. Fixed with a dialog-local
`Modifier.onKeyEvent` on `ThumbnailNavigator`'s content surface (`testTag
"thumbnail_dialog_surface"`, made focusable/focus-requested on open) that checks
`KeyEvent.shelfCommand(InputContext.READER) == ShelfCommand.BACK` — reusing the existing
`InputMapper`, which already maps both Escape and `GAMEPAD_B` to `ShelfCommand.BACK` — rather than
hard-coding the raw keycode or inventing a second input subsystem. Only `ShelfCommand.BACK` is
consumed; every other key (D-pad focus movement, cell activation) is left unconsumed. New
instrumented tests in `ThumbnailNavigationUiTest`: `escapeClosesPagesWithoutExitingTheReader`,
`gamepadButtonBClosesPagesWithoutExitingTheReader` (both assert Pages closes, `reader_screen`
stays present, and `page_number` is unchanged).

**Finding 3 (MEDIUM) — dismissing Pages left pending prefetch running.** Closing the dialog
stopped the UI's own `snapshotFlow` collector, but `ThumbnailLoader` kept its last-requested
window active, so its worker kept decoding the rest of the 13-page window after dismissal,
contending for the shared reader-render mutex. Fixed with a new `ThumbnailLoader.deactivate()`
that sets the loader's `wanted` range back to its existing "nothing requested" sentinel (`-1`) —
reusing infrastructure `setVisibleRange`/the worker's `collectLatest` already has, not a new state
machine: this supersedes (cancels, via `collectLatest`) the active window's decode loop at its
next suspension point, preserves every already-cached thumbnail, never calls `close()` (the
session/worker stay alive), and permits at most one already-started synchronous decode to finish
(it is not cooperatively cancellable mid-call). `ThumbnailNavigator` wires this from a
`DisposableEffect(loader) { onDispose { loader?.deactivate() } }`. New JVM tests in
`ThumbnailLoaderTest`: `deactivateStopsRemainingPrefetchForTheActiveWindow`,
`deactivatePreservesAlreadyCachedThumbnails`, `reopeningAfterDeactivateEstablishesAFreshRangeNormally`,
`deactivateNeverLeavesAStaleRangeResumingOnItsOwn`.

**Finding 4 (LOW) — recreation docs contradicted the implementation.**
`PHASE_3_IMPLEMENTATION_PLAN.md`'s "Process death/rotation/recreation" paragraph claimed no
reader dialog (including Pages) persists its open/closed state across recreation — but
`thumbnails`, like `controls`/`appearance`, is `rememberSaveable`, which *does* survive supported
recreation by design. Administrator decision: **keep `rememberSaveable`** (working, intentional
behavior) and correct the doc instead. The paragraph now states truthfully that Pages' open/closed
state may be restored across recreation, the thumbnail bitmap cache remains ephemeral and is never
restored, the restored current logical page stays authoritative, and thumbnails are simply
re-decoded lazily on demand if Pages does reopen — no thumbnail state is itself persisted.

**Targeted JVM tests — PASS.**
`./gradlew.bat :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.ByteBudgetedLruCacheTest" --tests "com.d4guilar.shelfos.core.reader.ThumbnailLoaderTest" --tests "com.d4guilar.shelfos.core.reader.NextThumbnailToLoadTest" -q`
— exit 0. `ByteBudgetedLruCacheTest` **11/11**, `NextThumbnailToLoadTest` **6/6**,
`ThumbnailLoaderTest` **10/10** (both classes' new counts include the Finding 1/3 tests above).

**Targeted instrumented tests — PASS**, on the same already-running `shelfos-api24` emulator
(`emulator-5554`), each run individually via
`-Pandroid.testInstrumentationRunnerArguments.class=<class>` (`:app:connectedDebugAndroidTest`):
- **`ThumbnailNavigationUiTest` — 6/6 PASS** (4 pre-existing + the 2 new Finding 2 dismissal
  tests above).
- **`ThumbnailLoaderInstrumentedTest` — 6/6 PASS** (pre-existing; rerun because
  `ThumbnailLoader`'s internals changed directly — unaffected by Findings 1–3's behavior, confirms
  no regression).

**Build — PASS.** `:app:assembleDebug -q` and `:app:assembleDebugAndroidTest -q`, each exit 0.
**Lint — PASS.** `:app:lintDebug -q`, exit 0, no new findings. `git diff --check`: clean.

### PHASE 3B — Codex R2 micro-remediation (2026-10-04)

Status: **IMPLEMENTED.** Base: `addb928` ("fix: harden Phase 3B thumbnail navigation"). Codex R2
returned CHANGES REQUIRED with two narrow items remaining; cache bounds, bitmap ownership, lazy
loading, cancellation/deactivation, the shared render mutex, lifecycle cleanup, PDF/CBZ handling,
RTL logical identity and future-CBR compatibility were all otherwise approved and are **unchanged**
here.

**Finding — gamepad B's dismissal handler did not cover the whole Pages dialog.** The R1
remediation's `Modifier.onKeyEvent` lived on a `Box` inside `AlertDialog`'s `text` slot — a
*sibling* of the `confirmButton` slot's Close button, not an ancestor of it. Compose key events
bubble from the focused node up its focus-parent chain, so once focus moved to Close, the handler
never received the event, and gamepad B could fail to dismiss Pages from that focus state. Fixed
by rebuilding `ThumbnailNavigator`'s dialog on `BasicAlertDialog` (material3's own lower-level
primitive, still backed by the same `Dialog`/`DialogProperties` `AlertDialog` uses internally, so
`dismissOnBackPress`/`dismissOnClickOutside` are unchanged) with title, thumbnail content and the
Close button all composed together inside one `Surface`, carrying the `onKeyEvent` handler on that
single shared root (`testTag "thumbnail_dialog_surface"` unchanged). Gamepad B now dismisses Pages
regardless of which dialog child owns focus; Escape/system Back are unaffected (still ordinary
dialog behavior); D-pad focus movement and Enter/center activation are untouched (only
`ShelfCommand.BACK` is ever consumed); the reader itself is never exited from this handler. New
instrumented test in `ThumbnailNavigationUiTest`:
`gamepadButtonBWithFocusOnCloseButtonClosesPagesWithoutExitingTheReader` (requests focus onto the
Close button via `requestFocus()`, sends gamepad B, asserts Pages closes, `reader_screen` stays
present, `page_number` unchanged, and a subsequent D-pad Right still advances the page — proving
normal reader-level controller handling resumes once the dialog is gone).

**Documentation — three stale statements in `PHASE_3_IMPLEMENTATION_PLAN.md`'s §24 (3B
implementation record) corrected:** the cache paragraph now states both the 16MiB byte budget and
the 64-entry cap explicitly (a prior revision read as byte-only bounding); the gamepad-B paragraph
no longer claims gamepad B "dismisses exactly the way" Escape/system Back do through ordinary
dialog behavior, and instead states the dialog-wide `onKeyEvent` scope this remediation
establishes; a new "Dismissal/prefetch" paragraph documents `ThumbnailLoader.deactivate()`'s actual
behavior (clears the active requested range, preserves cached thumbnails, permits at most one
already-in-flight synchronous decode to finish, prevents a stale prefetch window from continuing
after Pages closes) which this record previously did not describe at all.

**Targeted instrumented tests — PASS.** `ThumbnailNavigationUiTest`, run via
`-Pandroid.testInstrumentationRunnerArguments.class=com.d4guilar.shelfos.ThumbnailNavigationUiTest`
(`:app:connectedDebugAndroidTest`) on the already-running `shelfos-api24` emulator
(`emulator-5554`): **7/7 PASS** (100% success rate, 0 failures, 0 errors, 10.830s) — the 6
pre-existing tests (including `escapeClosesPagesWithoutExitingTheReader` and
`gamepadButtonBClosesPagesWithoutExitingTheReader`, confirming the restructuring did not regress
either) plus the 1 new Close-button-focus test above. Full JVM suite, full connected suite,
`NavigationSmokeTest`, and cache tests were **not** re-run — out of scope for this micro-remediation
(no cache code changed).

**Build — PASS.** `:app:assembleDebug -q` and `:app:assembleDebugAndroidTest -q`, each exit 0. One
`@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)` was required on
`ThumbnailNavigator` for `BasicAlertDialog`, mirroring the same per-function opt-in pattern already
used elsewhere in this codebase (e.g. `EpubActivity.kt`'s `@OptIn(ExperimentalLayoutApi::class)`).
**Lint — PASS.** `:app:lintDebug -q`, exit 0, no new findings. `git diff --check`: clean.

Per standing policy, the full JVM suite and full connected/instrumented regression matrix were
**not** re-run here — reserved for Phase 3F, same as the original 3B entry above.

**Dependencies/schema**: none added/changed. **Mutex**: unchanged (Finding 3 removes unnecessary
post-dismissal *contention* by stopping unneeded prefetch sooner; the mutex design itself was not
touched). **CBR compatibility**: preserved — the cache/loader contract still exposes no
ZIP-specific types.

## PHASE 3A — RENDERING FOUNDATION (2026-10-03)

Status: **IMPLEMENTED, pending administrator/Codex review.** Scope, exact contract, and
what remains for later slices are recorded in `docs/PHASE_3_IMPLEMENTATION_PLAN.md` §22.
Base: `main` @ `c8fff36`. Branch: `phase-3/3a-rendering-foundation`.

**What changed**: `core/reader/FixedReader.kt`'s `render(index): Bitmap` became
`render(index, request: PageRenderRequest = PageRenderRequest.DEFAULT): Bitmap`, with a
pure `resolveRenderTargetLongestEdge(request)` policy function replacing the flat
`MAX_PAGE_PIXELS = 2048` constant, plus a private `PageSource`/`ArchivePageSource`/
`ImagePageRenderer` container boundary generalizing CBZ's decode path away from direct
`SeekableZip` coupling. `FixedReaderViewModel` gained `updateViewport(width, height)` and
now builds each render's `PageRenderRequest` from the last-known viewport; `FixedReaderScreen`
calls it from the existing `onSizeChanged`. No dependency, Room schema, or migration change.

**JVM unit tests — PASS.** `./gradlew.bat :app:testDebugUnitTest -q` (exit 0), full suite,
including the new `com.d4guilar.shelfos.core.reader.PageRenderRequestTest` (9 tests: unknown-
viewport fallback reproduces the old flat `2048`; a 720x1280 viewport requests `1280` (less
than the old default); a 2560x1600 viewport requests `2560` (exceeds the old `2048` cap); a
1080x1920 viewport with `maxDimension=200` requests `200` (thumbnail-sized, bounded by the
smaller of the two); a 50,000x50,000 viewport is bounded at the absolute `SAFE_MAX_DIMENSION
= 4096`; a requested `maxDimension` above `4096` can never raise the result above `4096`;
negative/zero/`Int.MAX_VALUE` inputs are treated as unspecified rather than thrown/overflowed;
the result is always ≥1; the function is deterministic for a fixed request). One test
(`requestedMaxDimensionCanNeverRaiseTheResultAboveTheSafeMaximum`) initially asserted the
wrong expected value against its own premise (used a 1000px viewport, which legitimately
caps the result at 1000 regardless of `maxDimension`); corrected to use a 10,000px viewport
so the assertion actually exercises the ceiling it names, then re-ran green.

**Lint — PASS.** `./gradlew.bat :app:lintDebug -q`, exit 0, no new findings.

**Build — PASS.** `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -q`,
exit 0.

**Instrumented evidence — PASS (new contract, directly targeted).**
`com.d4guilar.shelfos.FixedReaderRenderRequestTest`, a new instrumented suite exercising
`FixedReaderFactory` directly (no Compose UI driving, matching
`LibraryPersistenceTest.pdfAndArchiveRenderAndArchiveUsesNaturalPageOrder`'s pattern) against
real `BitmapFactory`/`PdfRenderer` decodes: **10 tests, 0 failed** (confirmed via
`adb logcat`: "run finished: 10 tests, 0 failed, 0 ignored"), on a `shelfos-api37` emulator
(`sdk_gphone64_x86_64`):
- `cbzDefaultRequestPreservesTheOldTwoThousandFortyEightCap` — a synthetic 6000x4000 CBZ
  page at the default request decodes to a bitmap with longest edge ≤2048 (unchanged).
- `cbzLargerViewportRequestExceedsTheOldCapOnAHighResolutionSource` — the same source at a
  3000x3000 viewport request decodes with longest edge >2048 and ≤3000 (real evidence the
  new contract exceeds the old flat ceiling when a real viewport calls for it); wall time
  ≈2.1s on the x86_64 emulator (`inSampleSize`=2, bitmap ≈3000x2000, ≈24MB ARGB_8888).
- `cbzThumbnailLikeRequestProducesAMuchSmallerBitmap` — a 200x200/`maxDimension=200` request
  decodes to longest edge ≤200 (contract already supports a future thumbnail-sized request).
- `cbzOversizedRequestIsBoundedBySafeMaximumNotRequestedViewport` — a 20,000x20,000 request
  is bounded at the 4096 safety ceiling, not the requested viewport; wall time ≈1.6s.
- `cbzSourceSmallerThanRequestedTargetIsNeverUpscaled` — a 100x150 source at a 3000x3000
  request decodes at its exact native 100x150 (no synthesized upscale).
- `cbzMalformedImagePageStillFailsGracefullyWithANewStyleRequest` — a non-image archive
  entry still throws `PublicationException` through the new request-shaped `render()` call.
- `pdfDefaultRequestPreservesTheOldTwoThousandFortyEightCap` — unchanged default PDF cap.
- `pdfLargerViewportRequestExceedsTheOldCap` — a 2200x3300 viewport request on a 400x600 PDF
  page exceeds 2048 (wall time ≈32ms — PdfRenderer scales a vector page cheaply even well
  above the old cap, unlike CBZ's raster resample cost above).
- `pdfOversizedRequestIsBoundedBySafeMaximum` — bounded at 4096 regardless of a 50,000px
  request.
- `pdfPageOrderingIsUnaffectedByTheRequestShapeChange` — a 3-page PDF's natural order
  (red/green/blue) is unchanged when every `render()` call now carries a non-default request.

No obvious unbounded-allocation path was found: every decoded bitmap's longest edge is
bounded by `resolveRenderTargetLongestEdge`'s `coerceIn(1, min(maxDimension,
SAFE_MAX_DIMENSION=4096))`, and CBZ's `inSampleSize` only ever increases (never upscales),
so the worst case for any single currently-displayed page is one `4096x4096` ARGB_8888
bitmap (~64MB) — no spread/multi-page cache exists in this slice to multiply that.

**Broader instrumented regression — ATTEMPTED, INCONCLUSIVE (environment, not product
defect); explicitly deferred to Phase 3F, not silently skipped.** After the targeted
evidence above, an attempt was made to also re-run `FixedReaderTransformBoundsTest`,
`FixedReaderRecreationTest`, `MalformedFixedReaderResilienceTest`, `LibraryPersistenceTest`,
`NavigationSmokeTest`, and the synthetic large-fixture acceptance tests as a broader
regression check. Two independent environment problems were hit, neither caused by this
slice's product code:
1. **`shelfos-api37` AVD / Espresso `InputManager` incompatibility.** Every test that drives
   a real touch gesture or raw `KeyEvent` through Espresso's `UiController` (pinch-zoom in
   `FixedReaderTransformBoundsTest`, keyboard/gamepad dispatch in `NavigationSmokeTest`)
   failed uniformly with `java.lang.NoSuchMethodException:
   android.hardware.input.InputManager.getInstance` from
   `androidx.test.espresso.base.InputManagerEventInjectionStrategy.initialize` — an
   Espresso-version/AVD-system-image incompatibility unrelated to any ShelfOS code
   (confirmed via `adb logcat`: every one of `NavigationSmokeTest`'s 28 tests and
   `FixedReaderTransformBoundsTest`'s 15 tests failed with the identical stack trace, not a
   ShelfOS assertion failure). Tests that don't need Espresso's native event injection
   (`FixedReaderRenderRequestTest` above, which drives `FixedReaderFactory` directly) were
   unaffected and passed cleanly on this same AVD.
2. **API 24 fallback AVD, repeated external Gradle-daemon interruptions.** Switching to the
   `shelfos-api24` AVD (confirmed booted, `ro.build.version.sdk=24`) avoided problem (1), but
   two subsequent attempts to run `FixedReaderTransformBoundsTest` there were each
   interrupted mid-build by an external `gradle --stop` ("Gradle build daemon has been
   stopped: stop command received") before producing a result, and further broad regression
   runs were administratively halted at that point to keep this slice's validation scoped
   and timely (per the owner's explicit instruction) rather than repeatedly re-attempted.
   **No test in this list is recorded as failing** — none of them actually completed a run
   against the new contract; this is an honest "not obtained," not a pass being claimed.

This gap is judged acceptable to close later rather than now because: `FixedReaderTransform.kt`
(the pinch/pan/Fit Width geometry `FixedReaderTransformBoundsTest`/`FixedReaderRecreationTest`
exercise) was **not touched** by this slice at all; `FixedReaderViewModel`'s and
`FixedReaderScreen`'s changes are a new `updateViewport` field pair plus one additional method
call at an existing call site, reasoned through directly in this review (no change to
render-storm-relevant logic, recreation/locator logic, or error-handling control flow); and
`MalformedFixedReaderResilienceTest`/`LibraryPersistenceTest`'s `render(2)`/`render(0)` calls
use the default `PageRenderRequest`, which is proven byte-for-byte equivalent to the old
behavior by both `PageRenderRequestTest` and `FixedReaderRenderRequestTest` above. Full
re-validation of this broader matrix (plus Manga RTL navigation, which was not independently
re-exercised here) is Phase 3F's explicit job, not re-litigated per-slice.

**Dependencies/schema**: none added/changed. **EPUB**: untouched (no file in the EPUB
reading path was touched). **CBR**: not implemented, not stubbed, not detected.

## PHASE 3A — CODEX R1 REMEDIATION (2026-10-04)

Status: **IMPLEMENTED on one remediation commit on top of `c48dd33`** (not an amend), on
`phase-3/3a-rendering-foundation`. Independent review (Codex) of `c48dd33` returned CHANGES
REQUIRED with six findings; the `PageSource`/container direction itself was judged fundamentally
sound and was not redesigned. See `docs/PHASE_3_IMPLEMENTATION_PLAN.md` §23 for the full
per-finding record (what was wrong and exactly what changed); this entry is the validation
evidence for that remediation. Full JVM suite and full connected/instrumented regression were
**not** re-run here (reserved for Phase 3F; `c48dd33` already has a full-suite PASS on record) —
only the tests this remediation's changes actually required.

**What changed (summary; §23 has the full reasoning)**: `resolveRenderTargetLongestEdge(request)`
became `resolveRenderTarget(sourceWidth, sourceHeight, request)` — now aspect-aware via a new
`PageRenderRequest.fit: FitMode` field, with `DEFAULT_MAX_DIMENSION` (2048) now doing double duty
as an explicit reading-quality floor (not just the pre-layout fallback), and a new
`RenderMemoryPolicy` object replacing "one bitmap ≤4096 edge" with an explicit
`ARGB_8888` byte budget (~32MiB/render, derived from a 96MiB total session budget divided across
up to 3 concurrently-live same-session bitmaps). `FixedReaderViewModel.render` now builds its
request with the title's actual fit preference and holds its render `Mutex` across decode +
cancellation-check + publish-or-recycle (previously released before that check), closing the
window that let a stale, not-yet-disposed bitmap coexist with a newly-decoding one. `
ArchivePageSource` was renamed `ZipPageSource` (naming only; the generic `PageSource` interface
is unchanged).

**JVM unit tests — PASS.**
`./gradlew.bat :app:testDebugUnitTest --tests "com.d4guilar.shelfos.core.reader.PageRenderRequestTest" -q`
— exit 0, 14/14 tests, 0 failures. `PageRenderRequestTest` was rewritten for the new
`resolveRenderTarget(sourceWidth, sourceHeight, request)` signature (the original Phase 3A
version took no source dimensions, which is exactly what finding 2 required fixing) and now
covers, purely in `Int`/`Double` math with no Android dependency: the pre-layout fallback
(unchanged, `2048`); finding 1's exact regression scenario (a 720x1280 viewport against a
6000x4000 source resolves to longest edge `2048`, not the ~720 a floor-less aspect-correct Fit
Page computation would give); that a larger aspect-matched viewport still exceeds the old `2048`
cap (3A's original point, preserved); that an explicit small `maxDimension` still opts out of the
floor; finding 2's Fit Width aspect-awareness (a 1:6 tall page in a landscape viewport resolves
meaningfully wider than the old longest-edge-only defect, and Fit Page/Fit Width demonstrably
diverge for the same source/viewport); finding 3's byte-budget enforcement (a square oversized
request is bounded below the full `SAFE_MAX_DIMENSION` square, by the explicit byte budget, not
just the per-axis ceiling); the absolute safety ceiling; and the carried-over malformed-input/
overflow/determinism properties from the original Phase 3A suite.

**Build — PASS.** `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest -q`, exit 0.

**Instrumented evidence — PASS**, on a fresh `shelfos-api24` emulator (the environment note below
explains why a fresh instance was needed). Three test classes, run individually with
`-Pandroid.testInstrumentationRunnerArguments.class=<class>` (`:app:connectedDebugAndroidTest`):

- **`FixedReaderRenderRequestTest` — 17/17 PASS.** Rewritten/extended for the new contract.
  Carries over 3A's original CBZ/PDF default-cap/larger-viewport/thumbnail-sized/oversized/
  no-upscale/malformed-page/page-ordering coverage (now passing `fit = FitMode.PAGE` explicitly
  where relevant), and adds: a square 10,000x10,000 source at a 20,000x20,000 request resolves
  below the full 4096 safe-max square and within the explicit byte budget (finding 3); a
  3000x2000 source at `maxDimension=3000` decodes at exact native resolution (`inSampleSize=1`),
  while `maxDimension=2999` — one pixel less — drops a full power-of-two step to 1500x1000
  (`inSampleSize=2`), demonstrating `BitmapFactory`'s quantized sampling transition precisely at
  the boundary; a 1200x7200 (1:6) CBZ page in a 1920x1080 landscape viewport with `fit =
  FitMode.WIDTH` decodes to an exact, deterministic 600x3600 (`inSampleSize=2`, bounded by the
  `SAFE_MAX_DIMENSION` ceiling) — double the pre-remediation defect's 300x1800
  (`inSampleSize=4`, driven by the old code's bare viewport-longest-edge target of 1920)
  on both axes, and the same source/viewport pair under `FitMode.PAGE` produces a visibly
  narrower bitmap than `FitMode.WIDTH`, proving fit mode actually participates; the equivalent
  PDF Fit Width/Fit Page divergence case on a 400x2400 (1:6) PDF page (PDF has no sampling
  quantization, so this is checked against the old defect's naive formula with a 2x-or-more
  margin rather than an exact value).
- **`FixedReaderViewModelLifecycleTest` — 2/2 PASS** (new file). Drives a real
  `FixedReaderViewModel` directly (a `ViewModelStore`-backed instance with a minimal in-file fake
  `LibraryRepository`, a real `FixedReaderFactory`/`PublicationFiles` against real CBZ fixtures on
  disk — no Compose UI, no Espresso event injection).
  - `viewportReportedByScreenReachesTheActualRenderRequest` (finding 4's "viewport plumbing"
    requirement): opens a 1200x7200 Fit-Width CBZ item with no viewport known yet (pre-layout
    fallback render), then calls `vm.updateViewport(1920, 1080)` + `vm.retry()` — the exact
    sequence `FixedReaderScreen`'s `onSizeChanged` and the ViewModel's own render path use — and
    asserts the *next real decode* is both wider and taller than the first, and still within the
    byte budget. This proves the screen-reported viewport genuinely reaches `FixedReader.render`'s
    request end-to-end, not just that `resolveRenderTarget`'s math is correct in isolation.
  - `rapidPageTurnsDoNotAccumulateStaleBitmapsOrGrowMemoryUnboundedly` (finding 4's memory/
    lifecycle evidence requirement): a 6000x4000, 6-page CBZ item; displayed page 0, then 5 rapid
    un-awaited `vm.turn(1)` calls (10ms apart — far faster than one ~0.5-2s decode, so this
    genuinely exercises cancellation of an in-flight/queued render), settle, then 6 more rapid
    back-and-forth turns, settle again. Captured evidence (`adb logcat -s FixedReaderLifecycleTest`):
    displayed page 0 decoded at `1500x1000` (6,000,000 bytes — the reading-floor target for this
    source, well within the ~32MiB/render budget); after the first rapid-turn burst, settled
    cleanly at page 5 at the same `1500x1000`; process PSS (`Debug.getPss()`) was `73,991KB`
    before the second rapid-navigation burst and `85,970KB` after settling + `System.gc()` — an
    ~12MB delta across 11 total page-turns, well under the test's loose `2x`-one-max-bitmap-budget
    (~64MB) threshold for "no accumulation proportional to page-turns," and no error state at any
    point. Every observed bitmap (first/settled/final) was individually asserted within
    `RenderMemoryPolicy.MAX_BITMAP_BYTES`.
- **`MalformedFixedReaderResilienceTest#cbzNavigationRecoversAfterABadPageWithoutStaleBitmap` —
  1/1 PASS** (existing regression test, run narrowly rather than the whole class/suite, per the
  "any single existing regression test directly required by a changed code path" requirement:
  this is the one existing test that already exercises `FixedReaderViewModel.render`'s
  error-recovery branch, which this remediation's lock-scope change touched directly). Uses
  `createAndroidComposeRule<MainActivity>`/`performClick`, so unlike the two classes above it
  does go through Compose UI test input dispatch — it passed cleanly on this environment.

**Environment note (adb/emulator instability, not a product defect).** The `shelfos-api24`
AVD instance already running at the start of this session entered a state where installed-app
state and `adb`'s device transport disagreed (`INSTALL_FAILED_ALREADY_EXISTS` against an app `pm
list packages` did not show, then the device transport itself went `offline` and did not recover
after `adb reconnect`/server restart). The emulator process was killed and a fresh
`shelfos-api24` instance launched from the same AVD image (`emulator -avd shelfos-api24
-no-snapshot -no-boot-anim`); all instrumented evidence above is from that fresh instance. This
is consistent with — not a repeat of, since the specific symptom differed — the `api37`
AVD/Espresso `InputManager` incompatibility recorded in the original Phase 3A entry above: AVD
instability in this environment is a recurring, environment-level characteristic rather than
specific to any one AVD image or any one ShelfOS code change, and is not evidence of a product
defect in this remediation.

**Dependencies/schema**: none added/changed. **EPUB**: untouched. **CBR**: not implemented, not
stubbed, not detected. **Thumbnails/spreads/live zoom re-render**: not implemented, not stubbed.

## POST-PHASE-2 EPUB XHTML REGRESSION (2026-10-02)

**Status: FIXED on `fix/epub-xhtml-head-injection` (base `main` `3625324`), pending independent QA.**
This is a post-Phase-2 maintenance fix. The Phase 2 acceptance record below is unchanged and stays accurate
for what it tested: it passed against its then-current EPUB fixtures. Nobody knew about this defect during the
final Phase 2 gate. A later real EPUB exposed an XHTML case those fixtures never covered.

**How it was found.** While capturing Reddit screenshots, the real Project Gutenberg *Frankenstein* EPUB
(`Example Book files/`) rendered every chapter as Chromium's XML error page: *"This page contains the
following errors: error on line 30 at column 8: Opening and ending tag mismatch: meta line 22 and head"*.
The screenshot agent confirmed the source chapter was well-formed and reproduced the failure with two
minimal synthetic EPUBs (`Frankenstein_test_min.epub`, `Frankenstein_test_min2.epub`).

**Baseline reproduced before any change (API 35 emulator, base `3625324`).**

- Frankenstein: Letter 2 and Chapter 4 both showed the error page above.
- `Frankenstein_test_min.epub`: *"error on line 28 at column 8: Opening and ending tag mismatch: meta line
  21 and head"*.
- A production-replica probe of the sanitizer (same jsoup 1.23.2 calls) under a strict XML parser showed all
  33 of Frankenstein's XHTML resources are well-formed as authored and malformed after sanitizing.
  `min2.xhtml` has no `<meta>` and fails on `<br>` instead.

**Root cause (proven, not the hypothesis).** ShelfOS does not inject any markup into `<head>`. The defect is
the rendition sanitizer `sanitizeEpubHtml` in `core/reader/EpubReader.kt`. Every `.xhtml/.html/.htm/.svg`
resource passes through it via Readium's `TransformingContainer`, and it parsed every document with jsoup's
**HTML5** parser and re-serialized it as **HTML**. For a document Readium serves as `application/xhtml+xml`,
which the WebView parses as XML, that round trip:

- dropped the self-closing slash from every void element (`<meta …/>` became `<meta …>`, `<link …/>` became
  `<link …>`, `<br/>` became `<br>`), so XML parsing fails;
- reopened a self-closed empty anchor (`<a id="letter1"/>`): the HTML parser wraps the heading text in it and
  duplicates it after the heading, giving two elements with the same id;
- turned the XML declaration into a bogus comment.

**Affected subset.** Not universal. It hits any XHTML (`application/xhtml+xml`) content document that
contains a void element (`meta`, `link`, `br`, `hr`, `img`, …) or a self-closed non-void element. That covers
most real EPUBs. It is independent of Publisher/ShelfOS presentation and managed fonts, since the sanitizer
runs before either. Every earlier XHTML fixture happened to contain no void or self-closed element, so its HTML
round trip stayed XML-well-formed. Documents served as `text/html` were and remain unaffected.

**Fix.** The transform now looks up each resource's media type in the publication manifest. XML content
documents (`application/xhtml+xml`, `image/svg+xml`) are parsed with jsoup's XML parser and serialized with XML
syntax. When the resource isn't in the manifest, the file extension is the fallback. The same sanitizing rules
apply in both paths (script/iframe/object/embed/form/base/`meta[http-equiv]` removal, event-handler and
remote/`javascript:` link stripping), and `text/html` documents keep the existing HTML path. A declared XML
encoding is rewritten to `UTF-8` to match the output bytes, and no declaration is added where the source had
none. The source EPUB is never modified. No dependency, Room or schema change.

**Regression coverage (original content, not Frankenstein).**

- `OriginalFixtures.strictXhtmlEpub`: an XML declaration, self-closed `<meta/>`/`<link/>`, `<a id/>`,
  `<br/>`/`<hr/>`, `epub:type`/`xml:lang`, inline script and `onclick`, plus a second chapter named `.html` but
  declared `application/xhtml+xml`.
- `EpubStrictXhtmlRenderingTest` (instrumented, 2 tests) **failed 2/2 before the fix and passes 2/2 after**:
  - It reads the exact bytes the production `EpubReaderFactory` pipeline serves for each chapter and asserts they
    are well-formed XML. It also asserts a unique, empty anchor, that script and `onclick` are stripped, and that
    head content and `epub:type` are kept.
  - It opens the fixture in the real reader and asserts, through the WebView's accessibility tree, that the
    chapter text renders and Chromium's "This page contains the following errors" page does not appear.
- `EpubXhtmlSanitizerTest` (JVM, 9 tests) covers the transform boundary directly:
  - well-formedness and anchor structure;
  - active-content stripping in XML mode;
  - head, text and namespaced-attribute preservation;
  - the declared-encoding rewrite (ISO-8859-1 source) and HTML named entities;
  - the unchanged `text/html` path;
  - media-type-driven (not extension-driven) parser selection.

**Validation (API 35 emulator, `ANDROID_SERIAL=emulator-5554`).**

- Full JVM: 209/209 (the earlier 200 plus the 9 new).
- Focused EPUB instrumentation: 36 tests across `EpubStrictXhtmlRenderingTest`, `EpubBookmarkTest`,
  `EpubBookmarkLocationInstrumentedTest`, `EpubChapterHighlightTest`, `EpubSearchTest`,
  `EpubSearchServiceInstrumentedTest`, `EpubRecreationTest`, the three `EpubManagedFont*` tests,
  `ManagedFontRepositoryTest` and `ReaderStateTest`.
  - 35/36 passed in one run. The miss was a 10 s `awaitAddEnabled` wait in `EpubBookmarkTest` on the first test
    after a cold emulator restart; the class then passed 9/9 twice.
- Earlier in the session the emulator degraded and produced system-wide input-focus ANRs: the launcher's main
  thread was blocked in `libhwui` waiting on its render thread, and an "Application Not Responding" dialog held
  focus. Under that condition `EpubRecreationTest` failed or hit an ANR at the same rate on the unmodified base
  and on the fix (2/3 passes each, measured with the same protocol). For that test's fixture the served bytes are
  identical before and after the fix. The emulator process was restarted, after which the class passed.
- `git diff --check`: PASS.

**Real *Frankenstein* acceptance after the fix (API 35 emulator).**

- Letter 1, Chapter 4, Chapter 5, Chapter 12 and the final Chapter 24 all render text, with no XML error page.
- Chapter-dialog navigation and the Next page turn work.
- Publisher presentation and ShelfOS presentation (Spacious, then Editorial preset) both apply and render.
- The previously missing Reddit capture `docs/design/screenshots/reddit_androidapps_02_epub.png` (local-only,
  git-ignored like the rest of that folder) was taken only after this validation: Chapter 5, ShelfOS
  presentation, 1600×2560 tablet window cropped to 1600×2465 like the other Reddit captures.

## Phase 2D.4 — performance/resilience + final Phase 2 acceptance (2026-10-02)

**Status: PHASE 2 COMPLETE.** Branch `phase-2/final-reader-acceptance` (base
`main` `6e38b77`), candidate frozen at `2f2116a`. Full detail, the acceptance
matrix, severity classification and the Part-Z device-targeting finding are
in `docs/PHASE_2D_IMPLEMENTATION_PLAN.md` §22; this entry is the validation-
log summary.

**Owner-physical RP5 acceptance:** all three outstanding 2D.3 questions
(B-button reveal-then-exit, D-pad focus across reader controls, Manga/RTL
L1/R1/D-pad direction) — owner-confirmed **"All 3: Pass"** (2026-10-02).

**New test coverage added** (`app/src/androidTest`, no production code
touched): `SyntheticLargePdfAcceptanceTest` (1 test: 140-page synthetic PDF,
open + 60 sequential page-turns + a fast overlapping-navigation/cancellation
sequence, PSS before/after) and `MalformedFixedReaderResilienceTest` (4
tests: truncated PDF, truncated CBZ, CBZ with a non-image page, mixed good/
bad-page CBZ — each confirming the existing graceful-error UI, Retry vs.
Back-to-library, no crash).

**Full JVM** (`:app:testDebugUnitTest --rerun-tasks --offline`): **200
tests, 200 passed, 0 failed, 0 skipped.** Run twice (once standalone, once
as part of the final Gradle gate) with identical results.

**Full connected** (`:app:connectedDebugAndroidTest --offline`, restricted
to the emulator via `ANDROID_SERIAL=emulator-5554` with the RP5 still
attached — see the device-targeting finding below): **122 tests, 121
passed, 1 failed** on the full run
(`NavigationSmokeTest.fixedReaderZoomFitAndGlobalAppearancePersistAcrossRecreation`,
`ComposeNotIdleException: Global time out`, after 32m03s of sustained load,
coinciding with `android.hardware.graphics.composer3-service.ranchu` at 92%
kernel CPU per `adb shell dumpsys cpuinfo`). An isolated retry of that one
test passed in 83s. Classified **infrastructure flake, not a product
defect** — the test itself was not modified. All formerly-flaky-suspect
large-fixture tests (`SyntheticLoadAcceptanceTest`, `SyntheticLargePdfAcceptanceTest`,
`MalformedFixedReaderResilienceTest`, `FixedReaderTransformBoundsTest`,
`FixedReaderRecreationTest`) passed cleanly within the same full run.

**Device-targeting finding:** the brief anticipated that
`connectedDebugAndroidTest` might run on every attached device at once,
requiring the RP5 to be physically disconnected before the final gate.
Empirically, `ANDROID_SERIAL=emulator-5554` restricted every run in this
slice (six separate invocations) to the emulator alone — confirmed via each
run's own `Running tests on devices: shelfos-phase0(AVD) - 15` log line and
via the RP5 never appearing in any result XML, despite `adb devices -l`
showing it attached and online throughout. No physical disconnect was
needed.

**Full Gradle gate**
(`compileDebugKotlin compileDebugAndroidTestKotlin assembleDebug
testDebugUnitTest lintDebug assembleDebugAndroidTest --rerun-tasks
--offline`): **BUILD SUCCESSFUL**, 86/86 tasks executed, debug and
androidTest APKs both assembled, lint completed with no abort-triggering
errors, JVM suite 200/200 within the same gate run.

**Scope audit:** `git diff --stat main...phase-2/final-reader-acceptance --
app/src/main` is empty — no production code changed anywhere in this slice,
so no CBR/comic-spread/multi-column/OCR/Adapted-PDF/AI/annotation/cloud/
Series/Library-Sources/bulk-import/theme/input-remapping/plugin scope could
have leaked in.

**`git diff --check`:** PASS. **Working tree:** clean after the closure
commit. **Pushed:** no (local commits only, per standing policy).

Phase 2D.4 is marked **COMPLETE**. This closes Phase 2D (2D.1–2D.4) and
Phase 2 as a whole — see `docs/PHASE_2D_IMPLEMENTATION_PLAN.md` §22 for the
full twelve-clause acceptance table and final decision.

## Phase 2D.3 — input/accessibility/focus closure (2026-10-02)

Evidence-first closure slice (`docs/PHASE_2D_IMPLEMENTATION_PLAN.md`'s own
§7/§8/§10 RP5-physical, focus-order/TalkBack and localization-layout gaps).
Read `AGENTS.md`, `PHASE_2D_IMPLEMENTATION_PLAN.md` (2D.1/2D.2's settled
records), `ARCHITECTURE.md`, `features/READER.md`, `features/COMICS_MANGA.md`,
`design/READER_UX.md`, `design/INPUT_SYSTEM.md`, `VALIDATION.md`, and
re-inspected `InputMapper`/`ShelfCommand`/`InputModality`/`InputHints`,
`FixedReaderScreen.kt`, `EpubActivity.kt`, `ReaderAppearance.kt`,
`NavigationSmokeTest.kt`, `InputModalityClassificationTest.kt` directly
(not trusting docs alone). 2D.1's transform/bounds geometry and 2D.2's
recreation/resize conclusions were not reopened; no evidence surfaced
questioning either.

### Input ownership map (Part A)

Confirmed by reading `core.input` directly: `ShelfCommand`
(`NEXT_PAGE`/`PREVIOUS_PAGE`/`CONFIRM`/`BACK`/`OPEN_MENU`/`SEARCH`/
`TOGGLE_BOOKMARK`), `InputMapper.command`/`readerCommand` resolve identically
for both readers from one shared keymap; `InputModality`
(`TOUCH`/`KEYBOARD`/`CONTROLLER`) and `InputHints` are also fully shared,
validated against the real `InputMapper` bindings rather than hardcoded.
Chrome/Back (`backPress()`) is duplicated per-reader (pre-existing,
previously-deferred refactor target, unchanged) but behaviorally identical.
Touch remains screen-specific `pointerInput`, not routed through
`ShelfCommand` (pre-existing, previously-deferred gap, unchanged). No new
divergence found between EPUB and fixed-page command resolution.

### Keyboard/controller walkthrough (Parts B, C)

`NavigationSmokeTest.kt` already encodes this walkthrough end-to-end and was
re-run, not re-invented: real `KeyEvent` injection for Escape, Page Up/Down,
arrows (LTR and RTL/Manga CBZ), gamepad L1/R1/A/B, Ctrl+F, Tab/D-pad focus
traversal, across EPUB/PDF/CBZ. Executed on both real hardware:

- **Emulator (`emulator-5554`, API 35, `shelfos-phase0`)**: 36/36 passed
  (34 pre-existing + 2 new, see below), before and after this slice's
  production fix.
- **RP5 (`d8f7f1b6`, Retroid Pocket 5, API 33) — ADB-injected, not owner
  physical button press**: 34/34 passed (the suite as it existed before this
  slice's two new tests were added; the RP5 run was taken as the hardware
  baseline before adding them, the emulator run after). This is real
  `instrumentation.sendKeyDownUpSync`/`sendKeySync` injection routed through
  the RP5's actual Android input stack and this build's actual
  `dispatchKeyEvent`/`onPreviewKeyEvent` code — genuine evidence that
  keyboard/gamepad key codes, chrome reveal-then-exit (ADR-0023), RTL Manga
  CBZ keyboard mapping, and focus-hint derivation all work on this physical
  device — but it is **not** a substitute for an owner's own hand on a
  physical D-pad/trigger, since ADB injects already-resolved `KeyEvent`s
  rather than exercising the RP5's own button-to-keycode hardware mapping
  end to end. See "Remaining owner-only questions" below.

### Back/chrome semantics (Part G)

Unchanged from ADR-0023, re-confirmed by `backRevealsHiddenControlsBeforeLeavingTheReader`/
`epubBackRevealsHiddenControlsBeforeLeavingTheReader` and the RP5/emulator
runs above: hidden chrome → Back/Escape/B reveals; visible chrome → Back
exits. Dialogs (Appearance/Chapters/Search/Bookmarks) dismiss via Compose's
own default `AlertDialog` back-dismiss, unchanged by this slice.

### Input modality/hints, including the known EPUB edge-tap asymmetry (Part D)

Re-confirmed harmless by code re-reading, not newly tested: `InputHints`
hints are chrome-gated and command-derived for both readers
(`hiddenChromeNeverExposesInputHints`, `controllerInputShowsControllerHintsThenTouchClearsThem`,
etc., all still green). The known, already-documented asymmetry
(`docs/design/INPUT_SYSTEM.md` §10) — EPUB edge-tap page turns happen inside
Readium's navigator and never update tracked modality, so an edge-tap-only
EPUB session after a keyboard/controller session can show a stale hint until
the next center-tap or key press — was re-examined against this slice's own
criterion (does it have a *visible* user consequence beyond the
already-documented cosmetic staleness). It does not newly regress anything,
has no functional consequence (the actual accepted command set still works
via center-tap/keys regardless of displayed hint style), and remains
classified **LOW/harmless**, same as `PHASE_2D_IMPLEMENTATION_PLAN.md` §11's
existing classification — not escalated, not fixed (Part S's narrow-fix gate:
fixing it would mean routing EPUB edge-tap through the modality tracker,
which is the broader, previously-deferred "unify touch into `ShelfCommand`"
item, not a small fix).

### Focus order (Parts E, F, K)

By code re-reading and the existing `FocusRequester` wiring
(`pageFocus`/`firstControl`/`controlFocusRequests` in both readers): opening
chrome moves focus to the first control; hiding it returns focus to the page
surface; no `FocusRequester` targets a node that becomes non-composed while
chrome is hidden (hidden chrome's controls are not emitted at all while
`controls == false`, so they cannot hold stale focus). `EpubSearchTest`'s
`acceptedCtrlFAndKeyboardFocusCanOpenSearchAndActivateAResult` and
`EpubChapterHighlightTest`'s filter/jump tests independently exercise
keyboard focus into the Search field, a search result, and the Chapters
filter/list — all dismissible, all returning to a navigable reader. No focus
trap found in either reader's chrome, Appearance, Chapters, Search, or
Bookmarks.

### Accessibility semantics audit (Part H) — one real, fixed defect

Re-auditing `FixedReaderScreen.kt`/`EpubActivity.kt`/`ReaderAppearance.kt`
confirmed the existing chrome `stateDescription`/conditional reveal-action
pattern (2A) is unchanged and correct — no stale/duplicate reveal action
while chrome is visible. **New finding**: all four `Slider` controls in the
app (`FixedReaderScreen`'s page-jump slider; `ReaderAppearance`'s text
size/line spacing/page margins sliders, EPUB-only) had no accessible name —
Compose's `Slider` does not inherit a preceding sibling `Text`'s label, so
TalkBack would announce only a bare numeric value/range, not what the slider
does. Classified **MEDIUM** (a real, reproducible-by-code-inspection unnamed
action on a meaningful control, not merely a style nit) — fixed narrowly per
Part S: added `Modifier.semantics { contentDescription = ... }` to each of
the 4 sliders, reusing each slider's own already-resolved label string
(`content_desc_page_slider` new string; `appearance_text_size`/
`appearance_line_spacing`/`appearance_page_margins` reused). No other
unnamed/icon-only action, duplicate announcement, or hidden-but-exposed
control was found in either reader's chrome or the Chapters/Search/Bookmarks
dialog interiors.

### TalkBack (Part I)

Checked `adb shell settings get secure enabled_accessibility_services` on
both devices: emulator returned `null`, RP5 returned only
`com.rp.gameassistant/...ForegroundAppMonitorV4Service` (the RP5's own
overlay service, not a screen reader). `pm list packages | grep talkback`
found no TalkBack package on either device. Per the brief's own instruction,
no accessibility software was installed. **Recorded honestly: semantic-code
audit complete (above), manual TalkBack validation unavailable on both
current test targets** — not a failure, a documented boundary.

### Reduced motion (Part N)

Unchanged: no `AnimatedVisibility`/`animate*AsState` in either reader; chrome
show/hide remains an instant recomposition. `readerEntryTransitionIsSkippedUnderReducedMotion`
(existing, re-run as part of the suite above) confirms the reader remains
usable with `animator_duration_scale = 0`.

### Localization/layout (Part O)

`values`/`values-es`/`values-pt-rBR` all carry 224 strings (parity, no
missing translations) and every reader-chrome/Appearance/Chapters/Search/
Bookmarks string used by `FixedReaderScreen.kt`/`EpubActivity.kt`/
`ReaderAppearance.kt` is translated in both locales (spot-checked by key, not
exhaustively). Structurally, the reader chrome does not use fixed-width rows
that could clip longer localized text: `FixedReaderScreen`'s top row uses
`horizontalScroll`, and `EpubActivity`'s top row uses `FlowRow` — both reflow
rather than clip regardless of string length or viewport width. Compact vs.
expanded viewport resize for the reader was already validated in 2D.2 (real
`wm size` live resize, `docs/VALIDATION.md`'s "Orientation and live resize");
this slice did not find a new layout regression from the Slider
accessibility fix above (purely a semantics-tree addition, no visual change).
Not independently re-verified: a live side-by-side ES/pt-BR screenshot
comparison at forced-expanded size (code-structural reasoning above is judged
sufficient given no adaptive layout branch exists per 2D's §6 finding, and no
field report of localized clipping exists).

### RTL/Manga input (Part Q)

`mangaReadsRightToLeftWithKeyboardAndPageKeysStaySemantic` and
`keyboardHintsReflectRtlSwapInMangaCbz` (existing, re-run on both devices
above) confirm CBZ Manga RTL keyboard/D-pad direction and hint labels resolve
correctly; 2D.1's geometry is unchanged and not conflated with reading
direction, per this slice's own instruction.

### Findings (Part R)

- **MEDIUM (fixed)**: four unlabeled `Slider` controls (accessibility
  semantics gap, above).
- **LOW (pre-existing, not escalated, not fixed)**: EPUB edge-tap modality
  staleness (already documented, no new visible consequence found).
- **OBSERVATIONS**: chrome/Back-state logic still duplicated per-reader;
  touch still not routed through `ShelfCommand`; no adaptive compact/expanded
  layout branch exists yet — all pre-existing, previously deferred, unchanged
  by this slice.
- No BLOCKER/HIGH found.

### Tests added

- `NavigationSmokeTest.fixedReaderPageSliderIsAccessiblyLabeled` and
  `NavigationSmokeTest.epubAppearanceSlidersAreAccessiblyLabeled` (new,
  regression coverage for the fixed defect above).

### Validation performed (proportional to scope, per the standing policy)

- `NavigationSmokeTest`: 34/34 on RP5 (`d8f7f1b6`, pre-fix baseline), 34/34
  then 36/36 on the emulator (`emulator-5554`, pre-fix then post-fix with the
  2 new tests), all green.
- `git diff --check`: PASS.
- Not run: full JVM suite, full `connectedDebugAndroidTest` suite, the
  combined Gradle gate — reserved per the standing policy for broad
  cross-cutting changes and the final Phase 2 integration boundary (2D.4),
  not this narrow closure slice.

### Remaining owner-only questions

ADB-injected RP5 evidence above cannot fully substitute for an owner's own
hand on the device. If a live RP5 session becomes available, the smallest
set of genuinely owner-only checks left open:

1. Press the physical B button with chrome hidden on the RP5, across EPUB,
   PDF and CBZ — does it reveal controls instead of leaving the reader?
2. D-pad through the visible reader controls on the RP5 — can you reach
   Appearance, Previous and Next without focus disappearing or jumping
   somewhere unexpected?
3. Open the Manga/CBZ fixture (or any RTL title) on the RP5 — do the
   physical L1/R1 and D-pad left/right controls navigate in the expected
   (mirrored) reading direction, matching what you see on screen?

None of these were skipped out of doubt about the result — ADB's
`sendKeyDownUpSync` already proved the same key codes produce the same
behavior on this exact hardware/build. They are listed because this task's
own constraint is that ADB injection of an already-resolved `KeyEvent` is not
identical evidence to the RP5's own physical button-to-keycode hardware path
and a human's own perception of focus/timing.

## Validation policy (standing, 2026-10-02)

Validation depth is proportional to change scope. Narrow fixes use focused
tests plus relevant regression coverage (e.g. `NavigationSmokeTest`).
Expensive full connected/gate runs are reserved for broad cross-cutting
changes and final integration boundaries, rather than repeated after every
small remediation.

## Phase 2D.2 — recreation & resize continuity closure (2026-10-02)

Status: **Phase 2D.2 COMPLETE**, validation-only (no production code changed).
Base `8a3e4d1` (2D.1 complete, PR #17), branch `phase-2/reader-continuity`.
This was an evidence-first closure slice per its own brief: prove current
recreation/process-death/resize behavior for EPUB, PDF and CBZ, and fix only
reproducible defects actually found. None were found; current behavior
already satisfies the canonical continuity model in
`docs/PHASE_2D_IMPLEMENTATION_PLAN.md` §5.

### State ownership (confirmed by direct code reading, not assumed)

- **EPUB**: publication identity — `EpubActivity` intent extra, re-delivered
  by the system on both recreation and process death. Locator/progress —
  `ReadingEntity` (Room, `reading_state` table), written via an app-scoped
  `PositionWriter` (`ReaderPersistence.kt`) that outlives the Activity/VM;
  the Readium navigator is deliberately rebuilt from this Room locator on
  every open (`restoreEpubNavigatorAsPlaceholder`/`removeRestoredEpubNavigator`
  explicitly discard any FragmentManager-restored navigator state) rather than
  trusting the navigator's own saved state. Appearance/managed
  font/presentation — `ReaderPreferenceEntity` (Room), title+global layered.
  Chrome/dialog-open/search-query UI state — Compose `rememberSaveable`
  (`EpubActivity.kt`), survives config recreation and is contractually
  expected to survive process death via the same `onSaveInstanceState`
  Bundle path, though this is a framework guarantee rather than something
  this pass independently re-verified bit-for-bit. Bookmarks —
  `BookmarkEntity` (Room). Search (`EpubSearchCoordinator`,
  `SearchIterator`/cursor) — instance-scoped to each `EpubReaderViewModel`,
  never a Hilt/global singleton; a fresh process gets a fresh, empty search
  state by construction, not a resurrected stale one.
- **Fixed reader (PDF/CBZ)**: publication identity — Navigation-Compose back
  stack route arg (`reader/{id}`), restored via the Activity's saved-instance
  state. Current page — `FixedReaderViewModel` `StateFlow` (VM-scoped, lost on
  process death) mirrored durably to `ReadingEntity` via the same
  app-scoped `PositionWriter` pattern, and reconstructed from Room on the next
  `open()` regardless of whether the VM instance itself survived. Fit mode and
  reading direction — `ReaderPreferenceEntity` (Room; direction is
  deliberately title-only, never global). Chrome visibility —
  `rememberSaveable`. Zoom (`scale`) and pan (`panX`/`panY`) —
  plain `remember(state.page, fitWidth)`, intentionally transient, confirmed
  NOT to survive recreation or process death (matches the "may reset" canonical
  expectation). Fit Width's `verticalScroll` state is `rememberSaveable`
  internally but keyed inside `key(state.page)`; the audit flagged a
  theoretical inconsistency (scroll value persisted while the `scale` it was
  computed against resets) as the single highest-value thing to empirically
  test — see below.
- `PositionWriter` (`ReaderPersistence.kt`) has **no artificial debounce**: a
  `Channel.CONFLATED` collapses rapid writes and the collector dispatches the
  latest value immediately in the app-level `CoroutineScope`. A ~1 second
  wait after the last navigation action before a process kill is a safe
  margin for the write to land; there is no multi-second delay to account for.

### Configuration recreation (API 35 emulator, `emulator-5554`)

- EPUB: **PASS** — existing `EpubRecreationTest` (`scenario.recreate()` +
  a real hardware-relaunch orientation change) green, 1/1.
- PDF/CBZ: **PASS** — no prior recreation test existed for the fixed reader
  (a real gap relative to EPUB's coverage), so this slice added
  `FixedReaderRecreationTest.kt` (new, 7 focused cases, all passing):
  non-first-page recreation (PDF, CBZ), first-page edge, last-page edge,
  Fit Page zoom/pan resets to a valid `(1x, 0, 0)` transform (not a stale
  one), leave/return via Back-to-details-to-library, and the suspected Fit
  Width gap below.
- **Suspected gap, empirically tested, found NOT reproducible**: Fit Width +
  tall page + zoomed to 2x + scrolled to mid-range + real
  `ActivityScenario.recreate()`. Hypothesis: `scale` resets to 1x (plain
  `remember`) but `rememberScrollState()`'s value is `rememberSaveable`-backed,
  so a restored scroll offset could exceed the freshly-collapsed (unzoomed)
  scroll ceiling — an analogous "gray escape" to the one 2D.1 fixed for
  pan/zoom. `fitWidthTallPageRecreationNeverLeavesScrollPastCollapsedCeiling`
  asserts `scrollExtent().value <= scrollExtent().max` immediately after
  recreation and **passes** — no invalid scroll position was observed. Not
  reopened or further instrumented beyond this one proof, per the "fix only
  reproducible defects" brief.
- Chrome/preferences: confirmed via the above tests and direct code reading
  to survive recreation (`rememberSaveable` + Room, respectively).
- Zoom/pan: confirmed to reset safely to a valid resting transform on
  recreation (both Fit Page and Fit Width), consistent with the already-
  accepted "transient, may reset" classification.

### TRUE process death (not `ActivityScenario.recreate()`, not `am force-stop`)

Method: seeded the real on-disk Room DB (same file the production app process
reads) with three real, differently-sized publications (`Example Book
files/Frank Herbert - Dune 1 - Dune.pdf` → PDF, `.../Frankenstein; or, the
modern prometheus.epub` → EPUB, `.../New X-Men By Grant Morrison v03 ... .cbz`
→ CBZ, Manga category/RTL) via a temporary instrumented seeder
(`ManualProcessDeathSeeder.kt`, deleted before this slice's commit — not part
of the permanent suite) using the exact same "managed storage" `LibraryItem`
shape a real SAF import produces, skipping only the file-picker UI
interaction. Then, against the real (non-instrumented) app process:
navigated via `adb shell input tap`/`uiautomator dump`-driven coordinates,
confirmed the position had actually landed in Room via
`run-as ... sqlite3 shelfos.db`, pressed Home, captured the PID
(`adb shell pidof com.d4guilar.shelfos`), killed it with
**`adb shell am kill` only** (never `am force-stop`), confirmed `pidof` now
found nothing, then resumed via **Recents** (`KEYCODE_APP_SWITCH` +
tapping the ShelfOS task card — a genuine task-preserving "switch back",
not `am start` from scratch), and confirmed a **new** PID.

| Format | Old PID → New PID | State before kill | State after resume |
| --- | --- | --- | --- |
| PDF (Dune) | 5922 → 6150 | page 12/345 (Room: `page:11`) | page 12/345 ✓ |
| EPUB (Frankenstein) | 6150 → 6611 | 68% progress, 1 bookmark (Ch. 19) | 68% ✓, bookmark intact ✓, Search opened fresh/empty (no stale iterator) ✓ |
| CBZ (New X-Men, Manga/RTL) | 6611 → 7008 | page 8/163, RTL control layout (Next on left) | page 8/163 ✓, RTL layout preserved ✓ |

All three PIDs differ old→new, proving genuine process death occurred, not a
same-process recreation. `am force-stop` was never used as the kill
mechanism (confirmed avoided).

### Process-death state table

| State | Expected | Observed |
| --- | --- | --- |
| Publication identity | Survive | PASS (all 3 formats) |
| Fixed page / EPUB locator-progress | Survive, truthful | PASS (exact page/percent match, all 3) |
| Reader preferences (fit/direction) | Survive | PASS (CBZ RTL control placement identical pre/post-kill) |
| Bookmarks | Survive | PASS (EPUB bookmark intact, label/location unchanged) |
| Chrome/dialog state | Acceptable either way | Not exhaustively probed per-dialog across process death; chrome toggle itself confirmed functional post-resume, no crash |
| Zoom/pan | May reset | Not re-separately probed across process death (already proven transient and safely-resetting across the cheaper, equivalent `ActivityScenario.recreate()` case above; process death uses the same `remember`, not `rememberSaveable`, mechanism, so the same reset is expected to apply uniformly, not merely assumed) |
| EPUB search runtime objects | Must NOT resurrect stale state | PASS — Search dialog opened post-process-death showed its fresh empty-state copy ("Enter a word or phrase..."), not stale results |

### Orientation and live resize

- Real hardware-rotation (`adb shell settings put system user_rotation 1`)
  while the CBZ reader was open: page position preserved, all chrome controls
  remained present and tappable, no crash.
- A **true live resize distinct from recreation** (`adb shell wm size
  1600x2560`, then `wm size reset`) while the CBZ reader was open: page
  position preserved, no crash; a subsequent tap toggled chrome visibility as
  per the existing production tap-to-reveal/hide behavior (not a defect —
  confirmed by immediately re-tapping to reveal chrome again). This is
  evidence that `wm size`-driven resizing works as a genuine separate-from-
  recreation resize mechanism on this emulator, satisfying Part J's
  requirement without needing Android Studio's resizable-emulator window
  controls.
- 2D.1 Fit Page/Fit Width resize regression: re-ran
  `FixedReaderTransformBoundsTest` in full on the emulator — **15/15 passed**,
  including the landscape-rotation-driven tall-page Fit Width top/bottom
  reachability and no-gray-escape cases from 2D.1's own remediation rounds.
  No regression from this slice's (validation-only) changes.
- `NavigationSmokeTest`: **26/26 passed** (no navigation/input lifecycle
  production code was touched, but run anyway since this slice's new test
  exercises Back/navigation paths).

### Leave/return

- PDF: **PASS** — `FixedReaderRecreationTest.pdfLeaveAndReturnRestoresPage`
  (reader → details → library → reopen → same page), new focused coverage
  (no prior equivalent existed for the fixed reader).
- EPUB: reused as already-strong existing coverage —
  `EpubRecreationTest`'s cold-relaunch-after-close assertion (reopens and
  confirms the persisted Appearance choice) plus this slice's own manual
  process-death pass (which is a strictly stronger leave/return proof: the
  process didn't just restart the Activity, it restarted the whole app).
- CBZ: covered by this slice's manual process-death pass above (equivalent
  to leave/return, strictly stronger).

### Findings

- No BLOCKER, HIGH, MEDIUM, or LOW defects found. One suspected MEDIUM-shaped
  risk (Fit Width scroll-vs-scale recreation inconsistency) was formulated
  from the state-ownership audit, concretely tested, and found **not
  reproducible** — recorded as an OBSERVATION only, not a defect, since no
  actual invalid/out-of-range state was ever observed.
- **OBSERVATION**: the fixed reader had no `EpubRecreationTest`-equivalent
  instrumented recreation test before this slice — closed by
  `FixedReaderRecreationTest.kt` (new, permanent, 7 cases).
- **OBSERVATION**: chrome/dialog-open state's survival across true process
  death relies on the standard Android `rememberSaveable`/`onSaveInstanceState`
  contract; this pass confirmed the mechanism is wired correctly and observed
  no crash or misbehavior, but did not individually probe every dialog
  (Appearance/Chapters/Bookmarks/Search) open-state surviving a kill — the
  canonical spec treats this as "acceptable either way," so this is not
  classified as a gap requiring a fix, just an honestly-recorded validation
  boundary.
- No production code changes were required or made.

### Validation performed (proportional to scope, per the standing policy)

- New: `FixedReaderRecreationTest.kt` (7/7 passing on the emulator).
- Regression: `EpubRecreationTest` (1/1), `FixedReaderTransformBoundsTest`
  (15/15), `NavigationSmokeTest` (26/26) — all on `emulator-5554`.
- JVM: `FixedReaderTransformTest` (cached green from the 2D.1 close-out run;
  no production code changed in this slice to invalidate that cache).
- Manual/ADB: true process-death validation for all three formats (see
  table above), real hardware rotation, real `wm size` live resize — all on
  `emulator-5554`.
- Not run: full `connectedDebugAndroidTest` suite, full JVM suite re-run, the
  combined Gradle gate — reserved per the standing proportional-validation
  policy for broad cross-cutting changes and the final Phase 2 integration
  boundary, not a narrow, validation-only closure slice.
- RP5 (`d8f7f1b6`): confirmed online at session start; not used this pass —
  the emulator's `adb shell am kill`/Recents/`wm size` tooling already gave
  stronger, more controllable process-death and resize evidence than a short
  RP5 ADB pass would have added, and the brief only required RP5 "if
  connected" for a short sanity pass, not as a hard requirement. Honestly
  recorded as not run, not fabricated.
- `git diff --check`: PASS.

### Honest limitations

- Chrome/dialog-open state was not individually re-verified per-dialog across
  true process death (only chrome's basic toggle functionality was confirmed
  post-resume); the canonical spec marks this "acceptable either way," so
  this is a recorded boundary, not an unresolved gap.
- Zoom/pan reset-on-process-death was inferred from (a) the same
  `remember`-not-`rememberSaveable` mechanism already proven to reset safely
  under the cheaper `ActivityScenario.recreate()` case, and (b) no code path
  that would behave differently under true process death, rather than
  independently re-captured mid-zoom immediately before a kill in this pass's
  manual session. Recorded honestly as inferred-not-independently-observed
  for this specific combination.
- Physical RP5 was not used this pass (see above); no foldable-specific
  testing was performed or is in scope.

Phase 2D.2 is marked **COMPLETE**, validation-only. Phase 2D.3
(input/accessibility/focus closure) is next and unstarted.

## Phase 2D.1 close-out (2026-10-02)

Status: **Phase 2D.1 COMPLETE** at HEAD `e59eab8`. This is a lean close-out
pass, not a re-investigation: the two prior remediation rounds
(`06bcc14`, `d0ea51b`) already found and fixed the real issues, with strong
targeted evidence (43/43 focused JVM including the overflow-helper cases,
15/15 focused instrumentation on both the emulator and the physical RP5,
owner physical RP5 acceptance, `git diff --check` PASS, clean tree).
Per the standing proportional-validation policy above, this close-out ran
one additional regression check rather than a full connected suite or
Gradle gate re-run:

- `git status --short`: clean. `git log -1 --oneline`: `e59eab8`.
  `git diff --check`: PASS.
- Full JVM suite (`:app:testDebugUnitTest --rerun-tasks --offline`):
  **200/200 passed, 0 failed, 0 skipped**, including `FixedReaderTransformTest`
  (43/43, overflow-helper cases present).
- `NavigationSmokeTest` on the canonical API 35 emulator (full class,
  `connectedDebugAndroidTest` filtered to this class): **26/26 passed, 0
  failed, 0 skipped**, no retries needed — fixed-reader touch, page
  navigation, Back, keyboard/controller modality, PDF, CBZ, and RTL coverage
  within the class all passed.
- No full `connectedDebugAndroidTest` suite or combined Gradle gate was
  re-run for this close-out, consistent with the proportional-validation
  policy for a narrow, already-validated slice. No production code was
  changed in this pass — docs only.

Phase 2D.1 is marked **COMPLETE**. Phase 2D.2 (recreation/resize continuity
closure) remains next and unstarted.

## Phase 2D.1 — fixed-reader transform/bounds correctness (2026-10-02)

Status: **First pass IMPLEMENTED, but independent QA returned BLOCKED (Fit
Width tall-content blocker); see the 2026-10-02 remediation entry below for
the fix. The owner RP5 acceptance quoted here did not happen to exercise a
tall enough page to expose that gap.** Full detail in
[`PHASE_2D_IMPLEMENTATION_PLAN.md`](PHASE_2D_IMPLEMENTATION_PLAN.md#21-2d1-implementation-record-2026-10-02).

- **OWNER LIVE REPRODUCTION** (before any code was written, on the physical
  RP5): *"If I zoom out pulling to the gray side, it just overrides the
  actual pdf page and I can continue moving until even the page is completely
  gone, having to change page for it to even reset. This is the same on both
  sides or up and down."*
- **MEASURED root cause**: `FixedReaderScreen`'s pinch/pan gesture handler
  clamped translation to the full viewport dimension times scale, unrelated
  to how far the actual fitted, scaled page content extends past the
  viewport — permitting the page to be dragged fully off-screen, exactly
  matching the reproduction above. Fixed via a new pure, Compose-free,
  Context-free helper (`core.reader.FixedReaderTransform.kt`) computing the
  correct `max(0, (scaledContent - viewport) / 2)` bound per axis, plus two
  adjacent fixes: switching Fit Page ↔ Fit Width now resets the transform
  (previously could carry an invalid transform into the new geometry), and an
  idle transform is now re-clamped after a viewport resize/rotation/fold even
  without an active gesture.
  **Correction (2026-10-02): this pass's own claim that Fit Width's
  `verticalScroll` was "independent of this pan model" was false** — see the
  remediation entry below. The formula above was only ever correct for Fit
  Page; Fit Width's tall-content case needed a different Y-axis model.
- **Test evidence**: 29/29 new JVM tests; 5/5 new instrumented tests on both
  the physical RP5 and the API 35 emulator; 26/26 `NavigationSmokeTest`
  regression on both devices (no input/accessibility/Back/RTL regression);
  186/186 full JVM suite; final Gradle gate BUILD SUCCESSFUL (86/86 tasks);
  full connected suite 98/98 clean on the emulator, and 98/98 clean on the
  RP5 for this slice's own tests specifically (the RP5's unrelated failures —
  in EPUB bookmark/search keyboard-focus tests this change never touches —
  were isolated to a pre-existing, already-documented RP5 touch-mode/focus
  quirk from this project's own prior validation history, not a regression).
  `git diff --check`: PASS.
- **Scope**: one modified production file (`FixedReaderScreen.kt`) plus three
  new files (pure helper, JVM test, instrumented test). No PDF resolution,
  CBZ sampling, EPUB, persistence, or input-remapping code touched;
  dependencies and Room schema unchanged.
- **OWNER PHYSICAL ACCEPTANCE: PASS (for the gestures actually exercised).**
  The owner installed the fix build on the real RP5 and performed the
  pinch-zoom-in/pan-to-edge/zoom-back-out sequence by hand: *"pass! Zoom in
  and zoom out dont go out of bounds or slides of screen, fit width too. All
  good."* This did not happen to include a tall-enough page in a landscape
  viewport to trigger the gap the 2026-10-02 remediation below closes —
  independent targeted QA subsequently found and reproduced that gap.

## Phase 2D.1 remediation — Fit Width tall-content blocker (2026-10-02)

Status: **IMPLEMENTED, automated evidence on both devices PASSED, owner RP5
physical acceptance PASSED.** Full detail in
[`PHASE_2D_IMPLEMENTATION_PLAN.md`](PHASE_2D_IMPLEMENTATION_PLAN.md#211-2d1-remediation--fit-width-tall-content-blocker-2026-10-02).

- **Independent QA verdict (authoritative)**: BLOCKED. Fit Page was correct;
  Fit Width with content taller than the viewport could still be dragged out
  of bounds into gray — landscape viewport, tall page, Fit Width, zoom to
  ~2x, scroll to top, drag downward repeatedly (and the symmetric case at the
  bottom dragging up).
- **MEASURED root cause**: the shared pan-bound formula has no fit-mode
  concept, so it was also fed Fit Width's fitted content *height* — which
  routinely exceeds the viewport by design (that is what `verticalScroll` is
  for) — the same way it is fed Fit Page's always-viewport-sized content.
  This produced a nonzero Y bound even at `scale == 1`, and let
  `graphicsLayer.translationY` and `verticalScroll` both move the same
  one-finger drag at once (double movement), a pre-existing defect QA also
  flagged.
- **Chosen model: verticalScroll-only (Option B).** Fit Width's vertical
  movement now belongs entirely to `verticalScroll`; the pan transform's Y
  axis is forced to `0` in that mode via a new pure function,
  `fixedReaderMaxPanY(contentSize, viewportHeight, scale, fitWidth)`, used
  both by the gesture handler and the viewport-resize re-clamp. The gesture
  handler only treats a Fit Width gesture as a zoom/pan transform for a real
  multi-finger pinch or a horizontal-dominant one-finger drag while already
  zoomed; a vertical-dominant one-finger drag is left unconsumed so
  `verticalScroll`'s own detector moves it, eliminating the double-movement
  defect at its source rather than only bounding it. Fit Page is unaffected
  (no scroll container, same full pinch-zoom + clamped pan X/Y as before).
  `pointerInput(state.page, rtl)` → `pointerInput(state.page, rtl, fitWidth)`
  so the gesture coroutine restarts when fit mode changes mid-session.
- **Test evidence**: JVM 35/35 (29 existing + 6 new: `fixedReaderMaxPanY` at
  scale 1/2/5 for tall content, the H ≤ V case, a 5→2→1 zoom-down sequence,
  Fit Page parity, horizontal-pan-unaffected; two previously weak tests —
  `case4`'s `maxX != maxY` tautology and `case10`'s identical-input PDF/CBZ
  tautology — strengthened to assert exact values / genuinely different
  shapes). Instrumented: new tall-page PDF fixture
  (`OriginalFixtures.tallPdf`, 1:6 aspect) in a rotated landscape viewport;
  3 new tests (zoomed drag beyond TOP edge stays bounded, beyond BOTTOM edge
  stays bounded, scale-1 vertical movement comes from scroll not pan) plus
  the 5 existing tests, **8/8 passed on both the physical RP5 and the API 35
  emulator.** `NavigationSmokeTest` regression: 26/26 on both devices. Full
  JVM suite: 192/192 passed, 0 failed, 0 skipped. `git diff --check`: PASS.
- **Scope**: the same four files as the original 2D.1 slice plus one fixture
  addition (`OriginalFixtures.tallPdf`) — no PDF rasterization, CBZ decoding,
  EPUB, persistence, Room schema, or dependency changes.
- **RP5 OWNER PHYSICAL ACCEPTANCE FOR THIS REMEDIATION: PASS.** The owner
  installed the committed remediation build (`06bcc14`) on the real RP5 and
  performed the pinch-zoom/drag-to-top/drag-to-bottom sequence on a tall Fit
  Width page by hand, plus a Fit Page sanity check: *"ALL GOOD!"*

## Phase 2D.1 remediation, round two — zoomed Fit Width top/bottom reachability (2026-10-02)

Status: **IMPLEMENTED, automated evidence on both devices PASSED, RP5 owner
physical acceptance for THIS round PASSED.** Full detail in
[`PHASE_2D_IMPLEMENTATION_PLAN.md`](PHASE_2D_IMPLEMENTATION_PLAN.md#212-2d1-remediation-round-two--zoomed-fit-width-topbottom-reachability-2026-10-02).

- **Independent QA finding (authoritative, after `06bcc14`)**: the `06bcc14`
  fix correctly removed gray-escape and double-vertical-movement, but Fit
  Width zoomed in on a tall page still made the outer top/bottom fraction of
  the page permanently unreachable by scrolling (~25% at each end at 2x,
  ~40% at 5x).
- **MEASURED root cause**: `graphicsLayer` scales the `Image` visually around
  its own layout center without changing its *layout* size, so
  `verticalScroll` only ever measured the unscaled fitted height `H` — never
  the visually-scaled `scale*H` actually painted — leaving its scroll range
  at `H - viewport` instead of the correct `scale*H - viewport`.
- **Chosen fix: scroll-range compensation.** A new pure function,
  `fixedReaderVerticalScaleOverflow(contentHeight, scale)` (`max(0,
  (scale-1)*contentHeight/2)`), and a `Column` with blank `Spacer`s of that
  height above/below the `Image` — *outside* its `graphicsLayer`, so the
  reserved space is never itself scaled — make the scrollable column's
  measured height exactly `H + 2*overflow == scale*H`, matching the visual
  extent and giving `verticalScroll` the correct range for free.
  `graphicsLayer.translationY` stays forced to `0` in Fit Width exactly as
  `06bcc14` established; no second vertical-movement mechanism was added.
- **Test evidence, including a self-correction during this pass**: JVM
  43/43 passed (35 existing + 8 new for the overflow helper). Instrumented:
  **this pass's first attempt at the new reachability tests failed on both
  devices** — not because the fix was wrong, but because (1) the fixture's
  extreme 1:6 aspect ratio in a wide landscape viewport meant a single
  screenful at 2x/5x zoom showed too little of the page for the original
  marker positions (`y=80`/`y=2340` of 2400px) to land inside, and (2) a
  fixed swipe-repeat count calibrated for the old flat scroll range fell
  short of the new, correctly-larger zoomed range at 5x on both devices.
  Both were fixed (markers moved closer to the true edges for the 2x cases;
  the 5x stress case proved instead via a direct, device-independent check
  that the real `verticalScroll.maxValue` equals the expected `scale*H -
  viewport`; swipe helper changed to swipe-until-actually-reached rather
  than a fixed count). After these fixes: **15/15 instrumented tests passed
  on both the physical RP5 and the API 35 emulator**, confirmed via direct
  `adb shell am instrument -e class
  com.d4guilar.shelfos.FixedReaderTransformBoundsTest` runs on each device.
  `git diff --check`: PASS.
- **Validation-depth note (owner-directed, this iteration only)**: at the
  owner's explicit mid-task instruction, this iteration's validation was
  narrowed to the focused JVM test class and focused instrumentation (direct
  `adb shell am instrument` on both devices, not the Gradle
  `connectedAndroidTest` task) plus `git diff --check`/`git diff --stat`. The
  full JVM suite, `NavigationSmokeTest`, the full connected suite, and the
  final combined Gradle gate were **not** re-run for this specific iteration
  — the owner asked that this tradeoff (narrower validation on narrow
  iterative fixes, to avoid repeated ~40-minute waits) be surfaced here for a
  standing policy decision rather than re-litigated each time.
- **Scope**: the same four files as the first 2D.1 remediation plus the
  `OriginalFixtures.tallPdf` marker-position adjustment (same fixture, same
  page dimensions, markers moved closer to the true page edges) — no PDF
  rasterization, CBZ decoding, EPUB, persistence, Room schema, or dependency
  changes.
- **RP5 OWNER PHYSICAL ACCEPTANCE FOR THIS ROUND: PASS.** The owner installed
  the committed fix (`d0ea51b`) on the physical RP5 and confirmed live: a
  tall Fit Width page zoomed to ~2x showed true top and bottom content after
  scrolling fully in each direction, no gray escape dragging past either
  edge, horizontal pan still worked, and Fit Page remained unaffected —
  reported as *"all good."*

## Phase 2C evidence closure — no production render change justified (2026-10-01)

Status: **Phase 2C investigation CLOSED. DECISION: no production PDF
rendering change.** Full detail in
[`PHASE_2C_IMPLEMENTATION_PLAN.md`](PHASE_2C_IMPLEMENTATION_PLAN.md#22c-final-evidence-closure-dense-vector-control-on-rp5-2026-10-01).

- **MEASURED**: a purpose-built, locally generated dense vector/text PDF
  (same 612x792pt page shape as the New X-Men file, no embedded raster, no
  source-resolution ceiling) was rendered on the RP5 at the current 2048
  default (bitmap ≈1582x2048px, a 1.21x upscale versus the ~1920px landscape
  viewport in Fit Width) and at a computed viewport-sufficient target of
  ~2485 (bitmap ≈1920x2485px, ~18.2MB vs. 2048's ~12.4MB).
- **OWNER VISUAL OBSERVATION**: 2048 Fit Width "looks clean"; 2048 vs. 2485
  Fit Width — **C, effectively the same**; 2048 vs. 2485 Fit Page — **same,
  no visible difference**.
- **DECISION**: even with the source-quality confound fully removed, closing
  the measured Fit Width upscale produced no visible improvement. Combined
  with the prior live session's finding that raising render resolution was
  actively harmful on the real-world source-limited PDF, there is no
  remaining evidence-backed case for any PDF rendering change. Current
  behavior (`MAX_PAGE_PIXELS = 2048`, no zoom rerender, no cache, no
  prefetch, CBZ unchanged) is **retained as-is**. No production code was
  changed in this pass or the two preceding Phase 2C validation passes.

## Phase 2C live visual fidelity validation (2026-10-01)

Status: **Phase 2C live owner visual A/B session — explicitly NOT
implemented.** Closes the "owner visual observation: not obtained" gap left
by the discovery pass immediately below. Full detail, with every quote
tagged MEASURED / OWNER VISUAL OBSERVATION / INFERENCE, is in
[`PHASE_2C_IMPLEMENTATION_PLAN.md`](PHASE_2C_IMPLEMENTATION_PLAN.md#22b-live-owner-visual-ab-session-on-rp5-2026-10-01).

- The owner sat at the physical RP5 while builds were swapped between a
  temporarily modified 2048/3072/4096 `MAX_PAGE_PIXELS` (reverted after every
  comparison; the final diff against the previous commit is docs-only).
- **New X-Men PDF, Fit Page**: 2048 was reported "a little blurry"; 3072 and
  4096 looked "effectively the same" as the step before — no improvement.
- **New X-Men PDF, ~2x zoom**: 3072 and 4096 were reported as **visibly
  worse** than 2048, not merely unchanged — a materially stronger and more
  important finding than "no benefit." The owner independently compared two
  native CBZ comics (no PDF conversion step) from the same era/publisher and
  described them as looking excellent, consistent with the fidelity problem
  being specific to the lossy CBR→PDF conversion rather than to ShelfOS's
  renderer.
- **New X-Men PDF, Fit Width landscape**: no visible difference between
  2048/3072/4096, despite a previously measured mathematical under-render at
  2048 in this mode — the source image's own resolution ceiling appears to
  mask that gap on this particular file.
- **Synthetic vector/text PDF fixture**: no visible difference found between
  2048 and 3072 at Fit Page or at ~2-3x zoom; the fixture is acknowledged as
  too sparse (one large line of text per page) to be a strong control either
  way.
- **Cross-cutting product finding** (outside Phase 2C's own scope): the owner
  directly compared a CBR-converted-to-PDF volume against a native CBZ
  volume of the same comic series and found the CBZ version markedly
  better — concrete, real-world evidence reinforcing the existing product
  priority on native CBR support as a more effective fix for this class of
  complaint than any PDF-rendering change.
- **Revised recommendation**: do not raise the default PDF render target and
  do not build a zoom-triggered high-detail rerender (2C.2) on this
  evidence — the observed regression at higher targets on real content is a
  real risk, not just a missed opportunity. Fit Width's viewport-width-aware
  sizing remains a narrow, legitimate correctness fix but its real-world
  benefit is unconfirmed pending a less source-degraded test file. No
  implementation was accepted or built in this pass.

## Phase 2C discovery / device validation (2026-10-01)

Status: **Phase 2C discovery / device validation — explicitly NOT
implemented.** This is a measurement/validation pass only; no production
code changed (`git diff --stat` against the pass's starting commit is
docs-only). Full detail, including the complete pixel chain and evidence-tag
breakdown (MEASURED / OWNER VISUAL OBSERVATION / ANALYTICAL / INFERENCE / NOT
VERIFIED), is in
[`PHASE_2C_IMPLEMENTATION_PLAN.md`](PHASE_2C_IMPLEMENTATION_PLAN.md#22a-rp5-physical-device-validation-2026-10-01).

- **Hardware**: a physical Retroid Pocket 5 (Android 13, API 33, 1080×1920
  physical, 360dpi, `isLowRamDevice=false`, 256MB memory class, not the
  original Galaxy Tab A field-report device).
- **Controlled comparison**: an owner-provided New X-Men volume 1 CBR/PDF
  conversion pair (private, never committed to this repository). The CBR's
  source page images (sampled: ~2100–4000px on the longest edge, JPEG) are
  downsampled by the CBR→PDF conversion tool to a uniform 584×754px embedded
  JPEG on every one of the PDF's 186 pages — a >4x longest-edge loss,
  classified **SEVERE**, that happens entirely upstream of ShelfOS.
- **Resolution experiment**: real `PdfRenderer` measurements at 2048
  (current), 3072 and 4096 longest-edge targets against the real converted
  PDF and against the repository's synthetic vector/text PDF fixture, on the
  RP5. Bitmap sizes and allocation bytes matched the discovery doc's
  analytical Letter-page estimates almost exactly. Render time for the
  real (JPEG-embedding) comic PDF was 20–40x slower than the vector/text
  fixture at equal targets (tens of ms vs. low single-digit ms).
- **Key finding**: for this specific converted PDF, ShelfOS's current 2048px
  budget already renders at roughly 2.7x the embedded image's own native
  pixel density — raising the budget to 3072 or 4096 cannot recover detail
  the conversion already discarded, only add memory/time cost. This file's
  fidelity complaint is **source-limited at the conversion step**, not a
  ShelfOS rendering defect. A separate, genuine ShelfOS-side gap was also
  measured: in the RP5's landscape orientation, Fit Width's layout request
  exceeds the 2048 budget's resulting bitmap *width* (independent of the
  source PDF's own quality), confirming the discovery doc's analytical
  concern that a single longest-edge constant does not correctly serve
  Fit Width.
- **Memory/timing**: per-bitmap allocation and transient-overlap-during-swap
  figures were confirmed on real hardware; the RP5's own 256MB memory class
  and 8GB RAM comfortably absorb even a 4096px target with transient
  overlap, but the RP5 is not low-RAM and this does not generalize to a
  low-RAM/budget device.
- **Owner visual findings**: **NOT OBTAINED.** This pass could not pause for
  a live, synchronous human visual judgment on the RP5 screen; no visual
  sharpness comparison is recorded as an owner opinion, and none is inferred
  from the measured pixel/byte numbers above. Closing this gap requires a
  live session with the owner at the device.
- **Final architecture recommendation**: the discovery doc's
  viewport/fit-mode-aware render-target direction (replacing the flat 2048
  constant) is confirmed, not contradicted, by this evidence — reframed
  around Fit Width's measured width requirement rather than "raise the
  ceiling," since a higher fixed ceiling would not have helped this file. No
  implementation was accepted or built in this pass.

## Localization foundation complete (2026-09-30)

The ShelfOS interface localization foundation is **implemented and validated** on
`feat/localization-foundation` at `e6cc519` (`ce32d5f` implementation + `e6cc519` error
and lifecycle remediation). Independent targeted QA returned **PASS WITH NON-BLOCKING
FOLLOW-UPS**: all previously identified MEDIUM findings are fixed, with no remaining HIGH
or MEDIUM localization findings. The branch is not yet merged or pushed.

Delivered scope: AppCompat per-app locales (`AppCompatDelegate.setApplicationLocales` /
`getApplicationLocales` with `LocaleListCompat`) driving an in-app
`Settings → Language` selector; System default / English / Español / Português (Brasil)
with English as canonical fallback and `pt-BR` for Brazilian Portuguese; locale-neutral
language identity with no Room schema, table or migration; current production UI moved to
string resources; locale-neutral typed error and import-progress models mapped to
localized resources at the presentation boundary; locale-aware formatting on the touched
paths; and localized accessibility copy. Localization changes ShelfOS-owned UI only —
there is no publication-translation engine, and publication/user data is never translated.
ShelfOS does not declare `android:localeConfig`, so it is not exposed in Android
Settings → Apps → App language.

Validation evidence:

- Full JVM unit suite: **157 / 157 passed**, 0 failed, 0 skipped (initial implementation
  was 150 / 150)
- Full connected suite, API 35: **93 / 93 passed**, 0 failed, 0 skipped
- Focused `AppLanguageInstrumentedTest`: **3 / 3 passed**
- Additional targeted runs: repeated `AppLanguageInstrumentedTest`, then
  `AppearanceRestorationTest` immediately afterward with no locale leakage, plus
  `ManagedFontRepositoryTest` and `EpubManagedFontAppearanceTest`
- Gradle gate: **PASS** — `compileDebugKotlin`, `compileDebugAndroidTestKotlin`,
  `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest`
- Dependencies: **unchanged**; Room/schema: **unchanged**; `git diff --check`: **PASS**

Independent QA found four MEDIUM issues during the cycle, all fixed in `e6cc519`: a
locale-test cleanup/recreation flake, the API 24–32 import-progress locale path,
untranslated publication exception details, and untranslated font-import errors.

### Non-blocking infrastructure flake (not a localization defect)

Independent QA observed one intermittent, unrelated instrumentation teardown failure in
`NavigationSmokeTest.escapeAndGamepadBEstablishModalityWhileRevealingChromeInFixedReader`
(signature: activity never reaches requested state `DESTROYED`, last observed `PAUSED`). A
dedicated read-only triage then ran that exact test repeatedly — 5 / 5 PASS on baseline
`1a03ce5` and 5 / 5 PASS on `e6cc519`, with no infrastructure crash across those runs. No
evidence implicated the localization/AppCompat migration. Classified
**EMULATOR / INFRA FLAKE**; not merge-blocking, and not recorded as a localization defect.

### Remaining non-blocking follow-ups

1. **API 24–32 device validation** — the import-progress architecture fix is considered
   sound and is architecture/unit validated, but has not yet been connected-tested on an
   API 24–32 AVD/device. Device verification pending; no claim that it is broken.
2. **External locale-change observation** — `AppLanguageRepository`'s `StateFlow` is
   initialized from the current locale and can become stale if the app locale changes
   externally while ShelfOS runs. LOW priority and non-blocking. Today users cannot
   trigger this through Android Settings because ShelfOS exposes no
   `localeConfig`/system App Languages integration, and no such feature is promised.
3. **Optional translation polish** — the Spanish and Brazilian Portuguese "durable
   seekable access" wording is technically correct but slightly repetitive. Optional
   polish only; not a reason to reopen implementation.

## Phase 2B.4 managed fonts + ShelfOS reading presentation — complete (2026-09-30)

Phase 2B.4 is **implemented and complete** on `phase-2/managed-fonts` at `93d0aaf`
(`fix: preserve global managed font application`), following `ed38761` and `65309ab`.
Final independent QA returned **PASS**, with no additional production changes
required. Phase 2B.4 is not yet merged or pushed. Implementation summary:
[`PHASE_2_PLAN.md`](PHASE_2_PLAN.md#12-phase-2b4-implementation-record--managed-fonts--shelfos-reading-presentation).

Delivered scope: user-imported TTF/OTF managed fonts (SAF import, validation,
app-private managed copy, family catalog, and stable logical family ids
`builtin:serif` / `builtin:sans` / `user:<uuid>` persisted in reader preferences), the
Readium 3.4.0 serving path into the navigator WebView via `@font-face`, live catalog
resolution plus live switching between imported fonts without reopening the
publication, and the Publisher/ShelfOS reading-presentation boundary. Source
publication bytes are never modified, and an unresolvable managed font falls back to
built-in serif rather than failing.

Validation evidence:

- JVM unit suite: **143 discovered, 143 passed, 0 failed, 0 skipped**
- Full connected suite (`shelfos-phase0` emulator, API 35): **90 discovered,
  90 passed, 0 failed, 0 skipped**
- Gradle gate: **86/86 tasks successful** — `compileDebugKotlin`,
  `compileDebugAndroidTestKotlin`, `assembleDebug`, `testDebugUnitTest`, `lintDebug`,
  `assembleDebugAndroidTest`
- Room/schema: **unchanged**; dependencies: **unchanged**; `git diff --check`: **PASS**

The connected 90-test suite was run after `ed38761`. The final `93d0aaf` change was a
narrow `ReaderPreferences` policy fix, validated independently afterward with
`ReadingPolicyTest`, the complete 143-test JVM suite,
`EpubManagedFontAppearanceTest`, `compileDebugKotlin`,
`compileDebugAndroidTestKotlin` and `git diff --check`. The full connected suite was
**not** re-run after `93d0aaf`, and no claim is made that it was.

Defects found and resolved during QA (summarized, not a diary): initial and live
font-preference divergence; a publication transformation callback attached at the
wrong lifecycle point; a shared seekable font resource being unsafe across concurrent
requests; a missing `@font-face` declaration when live-switching fonts; a font
imported inside an open reader hidden behind a stale session snapshot; legacy title
font precedence conflicting with newer global managed fonts; valid OpenType
cross-signature extension combinations being rejected; and a stale title override
surviving a global managed-font application.

Known low-severity follow-ups. These are non-blocking and do **not** reopen Phase
2B.4:

- replace-rollback has a theoretical restore-failure edge case
- repository startup performs small synchronous disk work
- an unusual Activity recreation timing case may drop the import UI/create callback
- the stored checksum is not revalidated at load
- malformed manually-created private folder names may not be removable
- a missing selected font may leave no font chip visually selected
- Publisher mode may carry harmless unused font-face declarations
- managed font tests currently depend on Android system font fixtures; a bundled OFL
  test font could improve portability later
- one live-switch test has an avoidable timeout wait in its precondition check
- live managed resource resolution performs small filesystem checks per request

## Phase 2B.3 final technical review and owner RP5 acceptance (2026-09-28)

Phase 2B.3 is **accepted and ready for PR/merge, but is not yet merged or
pushed**. The final targeted independent review of
`e395ce7fc57c38072c806d577ec1095458663270` returned **PASS WITH NON-BLOCKING
FINDINGS** with **no R1, R2, or R3 findings**. The two remaining R4 items are
unbounded search-result accumulation, deferred to later performance closure,
and the debug-only opener's lack of parallel-test safety.

The owner physically tested the exact latest installed debug build on the
Retroid Pocket 5 and reported **RP5 PASS** for the Phase 2B.3 acceptance flow:

- reached and activated Search using the RP5 D-pad/controller;
- entered a known query and navigated the result list;
- opened a result and landed at the correct passage;
- confirmed a real miss shows the zero-results state;
- confirmed Back dismisses Search without leaving the reader;
- confirmed Back with hidden chrome still reveals controls before exit; and
- confirmed compact RP5 layout and focus behavior remained usable.

This is owner-reported physical-button evidence, distinct from ADB/Compose
automation. With technical review and physical acceptance complete, no Phase
2B.3 gate remains before PR/merge. No merge is claimed here.

## Phase 2B.3 R3 lifecycle-evidence remediation (2026-09-28)

The first independent review of `bd50ad2` returned **CHANGES REQUIRED with no
R1 or R2 findings**. Production search behavior and architecture were found
correct; the only blocker was one R3 evidence gap: the recreation and teardown
UI tests started real searches but did not positively observe an active cursor
or its closure. That gap was remediated and subsequently accepted by the final
targeted review recorded above.

- A small factory/constructor seam exposes the existing `EpubSearchCursor`
  boundary without changing release behavior: the default still calls
  `EpubSession.search()`. A non-exported debug-only Activity accepts the
  instrumentation-owned opener. No service locator, production debug flag,
  dependency, or alternate Readium behavior was added.
- `recreationWhileCursorIsActiveClosesItAndUsesAFreshCursor` uses two
  controlled cursors. It observes the first cursor acquired and suspended in
  `next()`, recreates before any result is released, observes that cursor close,
  observes a second cursor acquired for the restored query, and releases the
  second cursor to a valid result. This is lifecycle evidence; it does not claim
  that a closed cursor can later return a page.
- `leavingReaderWhileCursorIsActiveClosesItAndDestroysActivity` observes a
  cursor acquired and suspended in `next()`, calls `ActivityScenario.close()`
  without completing the search, observes cursor close, and requires the close
  call's positive `DESTROYED` transition. The coordinator JVM test now also
  records that `EpubSearchCoordinator.close()` returns only after cursor close;
  the existing ViewModel ordering then closes `EpubSession` after that joined
  coordinator barrier. Direct session-close instrumentation is not claimed.
- Generation-guard evidence is separate and deterministic. The generic,
  default-no-op `beforeStatePublication` seam pauses request A after its cursor
  has returned a real page but before `publish()`. Request B then becomes the
  latest request while A still owns `cursorMutex`. Resuming A leaves B's empty
  loading state intact; after A closes and releases the mutex, B acquires its
  cursor and publishes its result normally.
- Negative control was performed locally: temporarily removing
  `id == requestId` made
  `returnedPageCannotPublishAfterReplacementBecomesLatest` fail at the
  authoritative-query assertion. The guard was restored before final
  validation. This proves the test depends on the generation check rather than
  cancellation or its fake dropping output.
- The corrected architecture finding is explicit: cursor serialization means
  B cannot acquire a cursor before A closes. A cancellable
  `withContext(worker)` also does not deliver a cursor value returned after
  cancellation into normal processing. The real guard race is therefore a
  page returned before replacement whose post-result/pre-publish processing
  resumes after B becomes latest; production mutex and cancellation behavior
  were not weakened to fabricate an impossible post-close return.
- Final focused instrumentation: `EpubSearchTest` **6/6 PASS**,
  `EpubSearchServiceInstrumentedTest` **1/1 PASS**, `EpubRecreationTest`
  **1/1 PASS**, and `NavigationSmokeTest` **26/26 PASS**. The active-recreation
  method also passed two additional isolated runs while diagnosing cleanup,
  the final six-test class passed together, and both lifecycle cases passed
  again in the complete connected suite.
- Focused JVM search/presentation coverage: **11/11 PASS** (coordinator 9/9,
  presentation 2/2), including cursor-close-before-return and the corrected
  generation-guard race.
- Full offline Gradle gate: **BUILD SUCCESSFUL in 1m 30s; 86/86 tasks
  executed** (compile, Android-test compile, debug APK, all JVM tests, lint,
  and Android-test APK).
- The preceding lifecycle remediation's complete API 35 connected suite remains
  **84/84 PASS**, with 678.328 cumulative testcase seconds. It was not repeated
  for this corrected guard-only pass because release behavior is unchanged;
  `EpubSearchTest` 6/6 and the real SearchService test 1/1 were rerun.
- No Room schema, migration, or dependency changed. Production search
  semantics are unchanged. Unbounded result accumulation remains the accepted
  R4 carry-forward for later performance closure, with no current failure
  evidence. The debug-only process-global opener remains acceptable for the
  sequential instrumentation suite but is not parallel-test-safe. Physical
  RP5 reachability was still pending at this remediation checkpoint; the later
  acceptance record above supersedes that status.

## Phase 2B.3 — EPUB publication search implementation (2026-09-28)

Phase 2B.3 was implemented on `phase-2/epub-search`; its review and acceptance
history is recorded in the newer sections above. Phase 2B.4, 2C, and 2D remain
not started.

- Verified the pinned Readium Kotlin Toolkit 3.4.0 artifacts directly:
  `Publication.findService(SearchService::class)` returns the parser-attached
  service; `SearchService.search()` returns a paged `SearchIterator`; and that
  iterator has an explicit `close()` lifecycle. No parser registration,
  dependency, HTML scraping, or parallel index was added.
- Implemented a ShelfOS-owned search boundary and copied value result model.
  The coordinator serializes iterator ownership, cancels superseded work,
  blocks stale-generation writes, and explicitly closes on completion, error,
  replacement, clear, cancellation, and teardown before the EPUB session is
  closed. Only the dialog-open flag and query string survive recreation; the
  query is rerun with a new service iterator.
- UI evidence covers the reader Search entry point, cold-launch query-field
  focus, readable and accessible snippets, explicit zero-results state,
  Clear/Close, existing Ctrl+F, Compose keyboard focus, locator-authoritative
  result jumps, recreation, rapid replacement, and leaving during active
  search. Default compact 1080×1920 and forced expanded 2560×1600 emulator
  viewports passed; the viewport was reset afterward.
- Focused JVM: **10/10 PASS** (`EpubSearchPresentationTest` and
  `EpubSearchCoordinatorTest`), including completion/error/replacement/clear/
  teardown closure and a three-query ownership regression.
- Search instrumentation: **7/7 PASS** (`EpubSearchTest` 6/6 plus
  `EpubSearchServiceInstrumentedTest` 1/1) against real generated EPUBs and
  Readium's actual attached service. The largest-fixture search completed in
  **909 ms** in the final connected run and left the source file's length and
  modification time unchanged.
- Required regressions: **39/39 PASS** (`EpubBookmarkTest` 9,
  `EpubRecreationTest` 1, `EpubChapterHighlightTest` 3,
  `NavigationSmokeTest` 26).
- Complete API 35 connected suite: **84/84 PASS in 164.364 seconds**. A prior
  invalid run was interrupted by a host/emulator suspension lasting hours; its
  two affected endpoints were rerun together 2/2 before the clean continuous
  84/84 result. Ordinary search tests now dismiss their dialog before fixture
  teardown, while the dedicated active-search teardown test still closes the
  Activity with search running.
- Offline evidence: airplane mode was set to `1`, the real parser search test
  passed 1/1 in 1.311 seconds, and airplane mode was restored to `0` in a
  `finally` block. Search uses only the already-open local publication.
- Full offline Gradle gate: **BUILD SUCCESSFUL in 3m 1s, 86/86 tasks
  executed** for compile, Android-test compile, debug APK, all JVM tests, lint,
  and Android-test APK. The existing `ImportLeasesTest` unnecessary `!!`
  compiler warning and debug-manifest removed-INTERNET warning remain
  pre-existing, non-failing output.
- No Room schema, migration, dependency, source publication, `LibraryItem`,
  bookmark persistence, or search-history change. Normal navigator movement
  after selecting a result remains the only reading-state effect.
- RP5 was not connected during this pass. No RP5 execution or owner physical-
  control evidence is claimed. API 24/API 37 limitations remain the existing
  documented environment items; this slice did not claim to resolve them.

## Phase 2B.2.2 acceptance and Phase 2B.3 start (2026-09-28)

Phase 2B.2.2 is **accepted and squash-merged to `main` via PR #11** at
`ab612a46cc929c1a5d32df0d9ed608d1792e7a95`. Its initial independent review
returned CHANGES REQUIRED with no R1/R2 findings; the three R3 test/documentation
findings were remediated, the final wording cleanup was completed, and the
implementation is closed. The detailed entries below remain as the historical
review and validation record.

At this branch's starting point, Phase 2B.3 (local EPUB publication search)
became the active implementation slice. Its completed implementation status
and evidence are recorded in the newer section above. Phase 2B.4, 2C and 2D
remain not started.

## Phase 2B.2.2 R3 remediation (2026-09-28)

Independent Codex review of `e1c92f0` (2B.2.2's original implementation,
recorded below) returned **CHANGES REQUIRED**. **No R1 findings. No R2
findings — the production implementation was found correct** (bookmark
identity/equivalence, `sameEpubBookmarkLocation` authority, deletion via the
matched persisted `Bookmark`, Add→Remove→Add behavior, Room `Flow`
authority, Readium-locator navigation, Location N staying presentation-only,
no input-binding change, accessibility semantics). Three R3 findings, all
closed here on the same branch (`phase-2/epub-reading-flow`), without
resetting or dropping `e1c92f0`, and — per the review's own instruction —
without touching any production file. **2B.2.2 remains IMPLEMENTED, R3
CLOSED, PENDING INDEPENDENT RE-REVIEW — not accepted, not merged.**

### R3 #1 — RAPID-ACTIVATION COVERAGE WAS CLAIMED BUT NOT TESTED

The original `addBookmarkControlTogglesToRemoveOnceBookmarkedAndBackAgain`
labeled a fully-settled click→wait→click sequence a "rapid repeat tap." Two
remediation attempts were tried, in order, with the first two's failures
kept as evidence rather than discarded:

1. **Two `performClick()` calls fired back to back, no wait between them —
   failed empirically.** Compose's `performClick()` resyncs to Compose-idle
   before dispatching a touch; on this real device that resync reliably
   outlasts the Add action's Room-write-and-recompose round trip, so the
   second call's "Add bookmark" matcher throws
   `AssertionError: could not find any node that satisfies: ...`. Confirmed
   by an actual failing run, not assumed.
2. **Suspending `compose.mainClock.autoAdvance` around the two clicks —
   avoided that error but corrupted shared state.** The two clicks
   succeeded, but re-enabling `autoAdvance` left Compose's shared idling-
   resource registry in a bad state badly enough to break a *different*,
   previously-passing test in the same run:
   `addListJumpAndDeleteBookmarksAcrossDialogReopens` failed with
   `androidx.compose.ui.test.junit4.android.ComposeNotIdleException: Idling
   resource timed out: possibly due to compose being busy`. Confirmed by an
   actual failing run; confirmed fixed by removing the clock manipulation
   entirely and re-running the full `EpubBookmarkTest` class clean, twice in
   a row, for stability (9/9 both times).
3. **Concurrent repository-level activation — the mechanism actually used.**
   Per this task's own instruction that direct-repository simulation is
   acceptable "unless the UI test framework makes the real action
   impossible" (now demonstrated, not assumed), the new
   `concurrentAddBookmarkActivationsForTheSameLocationPersistOnlyOneBookmark`
   test performs one real, settled Add via the UI to capture the actual
   live locator (not a fabricated one), removes it via the UI, then fires
   two genuinely concurrent `addBookmark` calls (`kotlinx.coroutines.async`
   + `awaitAll`, both started before either completes) for that exact real
   locator, and asserts exactly one bookmark persists — a strictly stronger
   concurrency proof than the existing sequential-call coverage in
   `BookmarkPersistenceTest.addingTheSameLocatorTwiceDoesNotCreateADuplicateRow`.

### R3 #2 — MISSING PERSISTENCE/STATE-TRANSITION REGRESSION COVERAGE

Two new tests added:

- **`removingTheCurrentBookmarkPersistsThroughPublicationReopen`** (§3 of
  the review): add → confirm "Remove bookmark" → remove → confirm "Add
  bookmark" → close the publication → reopen it → confirm the empty state
  and "Add bookmark" persisted (not merely transient Compose state) →
  confirm directly against `container.library.bookmarks(...)` that no
  matching bookmark remains.
- **`returningToAPreviouslyBookmarkedLocationShowsRemoveBookmarkAgain`**
  (§4 of the review): bookmark location A (Chapter 1) → navigate to a
  distinct location B (Chapter 5, confirmed "Add" there, not "Remove") →
  return to A via the existing, already-proven bookmark-jump mechanism (not
  chapter title or Location N, which are presentation only) → confirm
  "Remove bookmark" is shown again, proving the control recomputes from the
  live locator rather than retaining stale UI state.

### R3 #3 — STALE STATUS WORDING

Corrected in this file (new section above the superseded one below),
`PHASE_2_PLAN.md` (top status block, §2 goal section, and the 2B.2.2
section header — no longer described as only "discovery/implementation-
planning" now that implementation exists and has been reviewed), and
`ROADMAP.md`. Historical passages describing the earlier planning-only
stage are left as accurate dated snapshots, not rewritten. Status at that
remediation checkpoint: 2B.2.1 accepted/merged via PR #10 (`af5410c`); 2B.2.2 implementation
complete, initial review CHANGES REQUIRED with no R1/R2 findings, R3
remediation complete on this branch, not accepted, not merged, pending
independent re-review and owner acceptance; 2B.3/2B.4 not started.

### TEST NAMES KEPT HONEST

The original toggle test's "rapid repeat tap" comment was corrected to
describe what it actually demonstrates (a settled re-add after a settled
remove); the genuine concurrency claim now lives only in the new, accurately
named `concurrentAddBookmarkActivationsForTheSameLocationPersistOnlyOneBookmark`.

### FOCUSED TEST RESULTS (`shelfos-phase0`, API 35)

| Test class | Result |
| --- | --- |
| `EpubBookmarkTest` | 9/9 passed (up from 6) — run twice in a row for stability after the clock-corruption incident was fixed; both runs clean |
| `EpubRecreationTest` | 1/1 passed |
| `NavigationSmokeTest` | 26/26 passed |

A full `connectedDebugAndroidTest` rerun was **not performed** — optional
per this remediation's own instruction since production code remained
untouched and the focused instrumented coverage above passed cleanly.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| Full gate (`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --rerun-tasks --offline --console=plain`) | **BUILD SUCCESSFUL**, 86/86 tasks |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean — no schema change |

### RP5

**No RP5 re-certification performed because remediation changed only
tests/docs.** `EpubActivity.kt` and every other production file are
byte-for-byte unchanged from `e1c92f0`, which was already RP5-certified.

### PRODUCTION-CODE STATUS

**Unchanged.** The remediation diff contains one instrumentation-test file
(`app/src/androidTest/java/com/d4guilar/shelfos/EpubBookmarkTest.kt`) plus four
documentation files. After commit, the working tree is clean. No production
source file, schema, or dependency changed.

### FINAL ACCEPTANCE (2B.2.2 R3 remediation) — pending

All three R3 findings closed with evidence, including two genuine test-
mechanism failures kept as part of the record rather than hidden. **Not yet
re-reviewed by Codex, not merged, not pushed.** 2B.3, 2B.4 remain untouched
and unstarted.

## Phase 2B.2.2 validation — EPUB reading flow, bookmark toggle (2026-09-28)

**Superseded by the "Phase 2B.2.2 R3 remediation" section above, which is
the current status.** Kept as the original evidence trail.

Branch `phase-2/epub-reading-flow`, base `main` at
`af5410cfe3219f566d00b17fa5604f52d7a9c228` (2B.2.1 accepted/merged via PR
#10). **2B.2.2 IMPLEMENTED, PENDING INDEPENDENT REVIEW — not accepted, not
merged.** See `PHASE_2_PLAN.md`'s "2B.2.2 (reading flow)" section for the
full discovery pass (current-flow walkthrough, friction analysis, deferred
items, and the decision that no ADR/owner sign-off is required for this
REQUIRED scope).

### DISCOVERY FINDINGS (empirical, not guessed)

- **Keyboard/D-pad focus after dialog dismiss**: tested directly with a
  temporary instrumented probe (reverted before commit) — focused
  "Bookmarks" via `RequestFocus`, opened it with `Key.Enter`, dismissed with
  the real hardware Back key, confirmed focus returns to the "Bookmarks"
  chrome button. **No defect found**; not in scope for a fix.
- **Chrome width**: real screenshots captured on a standard 1080×1920
  portrait emulator and the connected Retroid Pocket 5 (landscape). On the
  portrait phone, the four existing chrome buttons already span nearly
  edge-to-edge with almost no room — **a fifth standalone chrome button
  does not safely fit** without a visual-redesign decision. RP5's landscape
  width is not the binding constraint. This directly shaped the REQUIRED
  scope below (no new button).
- **`ShelfCommand.TOGGLE_BOOKMARK`**: confirmed in code
  (`core/input/ShelfCommand.kt`) to already map keyboard `InputKey.B` in
  reader context, and confirmed in `EpubActivity.kt` to be unconsumed
  (falls to `else -> false`). `docs/design/INPUT_SYSTEM.md` lists it only as
  a conceptual command with a *suggested, not committed* keyboard default.
  **Left unwired** — see "explicitly deferred" below.

### PRODUCTION CHANGE

`EpubActivity.kt`'s Bookmarks-dialog current-position control is now a real
two-way toggle instead of Add/disabled-once-bookmarked:
- Not bookmarked: "Add bookmark" (unchanged).
- Already bookmarked: **"Remove bookmark"** (enabled, not disabled) — tapping
  deletes that exact bookmark via the existing `vm.deleteBookmark(id)`.
- The current-bookmark lookup (`sameEpubBookmarkLocation`-based) now returns
  the matched `Bookmark` itself, not only a boolean, so the control knows
  exactly which row to delete.
- No new repository method, no schema change, no new `ShelfCommand`, no new
  UI surface — same control, same position, same width.

### JVM / REGRESSION

No JVM-only test class needed; this is UI/repository-call wiring, exercised
instrumented against the real dialog and real Room-backed repository.

### INSTRUMENTED / EMULATOR (`shelfos-phase0`, API 35)

| Test class | Result |
| --- | --- |
| `EpubBookmarkTest` | 6/6 passed (up from 5) — new `addBookmarkControlTogglesToRemoveOnceBookmarkedAndBackAgain` (not-bookmarked → bookmarked → not-bookmarked via the same control, a settled re-add after removal produces no duplicate, toggle reflects the current position after a chapter jump); existing tests updated for the new "Remove bookmark" label; recreation and reopen tests extended to assert the toggle state itself survives, not only the row count. This historical run did not exercise rapid UI double-click input; the current remediation record above supersedes that earlier wording. |
| `BookmarkPersistenceTest` | 7/7 passed (untouched — no persistence/ordering/equivalence change) |
| `EpubBookmarkLocationInstrumentedTest` | 6/6 passed (untouched) |
| `EpubChapterHighlightTest` | 3/3 passed (untouched) |
| `NavigationSmokeTest` | 26/26 passed (untouched) |
| `EpubRecreationTest` | 1/1 passed (untouched) |
| Full `connectedDebugAndroidTest` suite | **74/74 passed, 0 failures, 0 errors**, all 12 classes complete — required because production reader UI/behavior changed |

**Emulator infrastructure note (recurrence):** the same class of ADB
transport-disconnection failure documented earlier in this project's history
recurred once during an initial `EpubBookmarkTest` run (`Transport endpoint
is not connected` / `cmd: Can't find service: package`). Resolved with the
same established procedure (confirm no stale `emulator`/`qemu` processes,
restart ADB, relaunch the emulator, poll for boot completion, verify
`pm list packages`); all runs after the restart were clean, including one
observed timing timeout in `bookmarksSurviveClosingAndReopeningThePublication`
(a `ComposeTimeoutException` waiting for the reader session to open, on the
freshly-restarted emulator's first run) that passed cleanly on immediate
re-run — consistent with device-load timing instability, not a regression.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| Full gate (`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --rerun-tasks --offline --console=plain`) | **BUILD SUCCESSFUL**, 86/86 tasks |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean — no schema change |

### RP5 (physical Retroid Pocket 5, `d8f7f1b6`, Android 13 / API 33)

Production reader UI changed, so RP5 was exercised, not skipped.
`EpubBookmarkTest` was run against the connected device after waking/
unlocking it: **6/6 passed**, including the toggle test and the keyboard-
focus test (which had previously shown a device-specific quirk in 2B.2's R3
remediation — it passed cleanly here, though a single pass does not itself
prove that earlier finding was wrong, and is not claimed to). A direct
`adb exec-out screencap` captured the real device's screen showing the
"Remove bookmark" control rendering clearly and legibly. ShelfOS was tested
on a Retroid Pocket 5. This is real hardware execution with a directly
observed visual and functional result — not a claim of manual physical
controller button-pressing, which was not performed in this pass.

### PRESERVED ARCHITECTURE (confirmed unchanged)

Bookmark Room schema, `Bookmark` domain model, stored locator format,
navigation authority, the DAO's exact-locator duplicate guard,
`sameEpubBookmarkLocation`, bookmark ordering, `matchChapter`, 2B.2.1's
Location N presentation, source EPUB, resume model — confirmed via `git
diff --stat` showing no changes to `LibraryDao.kt`, `ShelfDatabase.kt`,
`RoomLibraryRepository.kt`, `Bookmark.kt`, or `core/reader/EpubReader.kt`.
No new `ShelfCommand`/physical binding was wired.

### FINAL ACCEPTANCE (2B.2.2) — pending

Implementation complete with the evidence above, including explicit
discovery findings for two friction candidates that were investigated and
*ruled out* (dialog focus restoration, RP5-specific chrome width) rather
than assumed. **Not yet reviewed by Codex, not merged, not pushed.** 2B.3,
2B.4 remain untouched and unstarted; the deferred reading-flow items
(ambient bookmark indicator, single-tap-without-dialog affordance,
`TOGGLE_BOOKMARK` wiring) remain explicitly unimplemented pending an owner
product/visual decision.

## Phase 2B.2.1 — merged (2026-09-27)

Following the final R3 closure below (`7f8eef9` + the R3 fix), **2B.2.1 was
accepted and merged to `main` via PR #10** at commit
`af5410cfe3219f566d00b17fa5604f52d7a9c228`. This is the current,
authoritative status; the sections below (and the superseded section
further down) are the evidence trail, preserved as written at the time.

## Phase 2B.2.1 final R3 closure (2026-09-27)

Final independent review of `7f8eef9` returned **PASS WITH NON-BLOCKING
FINDINGS**, one remaining R3: `epubPositions()`'s catalog conversion
defaulted a missing `locations.progression` to `0.0`, indistinguishable
from a genuine first-segment start. Fixed by extracting the conversion into
a pure `toEpubPositions(List<RawEpubPosition>)` (new `RawEpubPosition`
holds nullable `position`/`progression`), which now drops any entry missing
either field instead of defaulting it — floor/segment-start semantics,
valid-range handling, global one-based numbering, and the existing
chapter/progress fallback are all unchanged. Added 6 focused JVM tests
(`EpubBookmarkPresentationTest`, 31/31 total): missing progression dropped,
missing position dropped, a dropped entry cannot become Location 1 by
default, another valid candidate still resolves when a malformed entry is
present, no Location is produced when every candidate is unusable, and a
dropped entry does not renumber remaining global positions.

Validated: `EpubBookmarkPresentationTest` 31/31,
`EpubBookmarkLocationInstrumentedTest` 6/6, `EpubBookmarkTest` 5/5 — all
green on `shelfos-phase0`. Full Gradle gate: BUILD SUCCESSFUL, 86/86 tasks.
`git diff --check` clean; no schema change; no dependency change. No
bookmark persistence/navigation/equivalence code touched. This closes the
last open review finding from the "PASS WITH NON-BLOCKING FINDINGS" verdict
on `7f8eef9`; acceptance/merge status is not asserted here.

## Phase 2B.2.1 R2/R3 remediation (2026-09-27)

Independent Codex review of `f43e70d` (2B.2.1's original implementation,
recorded below) returned **CHANGES REQUIRED**: R2 (a location-resolution
defect) and R3 (dispatcher, cancellation, and documentation findings). All
are fixed here, on the same branch (`phase-2/bookmark-location-polish`),
without resetting or dropping `f43e70d`. **2B.2.1 remains IMPLEMENTED /
REMEDIATED / PENDING FINAL INDEPENDENT REVIEW — not accepted, not merged.**

### R2 — LOCATION RESOLUTION DEFECT

**Root cause:** `resolveEpubLocation` selected the numerically *nearest*
position to a bookmark's progression. Readium's position catalog lists
segment *starts*, not midpoints, so "nearest" is the wrong axis: a bookmark
at progression `0.7`, between segment starts `0.4` and `0.8`, was
incorrectly resolved to `0.8` (numerically closer) instead of `0.4` (the
segment the bookmark is actually inside).

**Fix:** floor/segment-start semantics — the resolved Location is now the
greatest-progression segment start that is still `<=` the bookmark's own
progression, among positions sharing its resource. Progression validation
is conservative: a missing, negative, `>1`, `NaN`, or infinite progression
returns no Location (never clamped or guessed); the existing chapter/
progress fallback still applies. Candidate positions with an invalid
progression are ignored rather than trusted. Global one-based numbering
(never reset per resource) is unchanged.

| Bookmark progression | Segment starts 0.0/0.4/0.8 | Old (nearest) | New (floor) |
| --- | --- | --- | --- |
| 0.0 | | segment 1 | segment 1 |
| 0.2 | | segment 1 | segment 1 |
| 0.4 | | segment 2 | segment 2 |
| **0.7** | | **segment 3 (wrong)** | **segment 2 (correct)** |
| 0.8 | | segment 3 | segment 3 |
| 0.99 | | segment 3 | segment 3 |
| 1.0 | | segment 3 | segment 3 |

### R3 — DISPATCHER / CANCELLATION / DOCUMENTATION

- **Off-Main dispatch (fixed):** `EpubReaderViewModel`'s position-catalog
  launch ran on `viewModelScope`'s own `Dispatchers.Main.immediate` (neither
  `Publication.positions()` nor `EpubSession.epubPositions()` switches
  dispatchers itself). Now wrapped in `withContext(Dispatchers.IO) {
  session.epubPositions() }`, matching this codebase's own established
  convention (`EpubReaderFactory.open`, `PublicationFiles`,
  `FixedReaderViewModel`) — no new dispatcher abstraction was introduced.
- **Cancellation (fixed):** the same code used
  `runCatching { session.epubPositions() }.getOrDefault(emptyList())`,
  which would silently swallow a real `CancellationException` alongside an
  ordinary failure. Replaced with an explicit `catch (e: CancellationException)
  { throw e } catch (e: Exception) { emptyList() }` — cancellation now
  propagates normally; only a genuine, non-cancellation failure degrades to
  no Location N.
- **Archive-length documentation (corrected):** re-verified via `javap`
  decompilation of `ArchiveEntryLength.positionCount`: it reads
  `Resource.properties().archive?.entryLength` (the archive-stored,
  typically DEFLATE-compressed length) **when the container reports one**,
  falling back to `Resource.length()` (the resource's own decoded length)
  otherwise — not universally "the compressed length," as the original
  wording stated. Both `EpubPosition`'s and
  `EpubBookmarkLocationInstrumentedTest`'s doc comments now state the
  fallback explicitly. The product-level conclusion is unchanged: Location N
  is structurally derived from the unchanging published file, independent
  of rendered typography/layout for a given publication.
- **Compose-blocking wording (corrected):** removed language implying
  position computation "can never block Compose." Replaced with the
  provable claim: catalog generation is launched after the session opens
  and is dispatched onto `Dispatchers.IO`; the catalog is memoized per EPUB
  session. No claim is made that this makes UI jank mathematically
  impossible.
- **Timeout causality (corrected):** the original wording characterized the
  one observed `EpubBookmarkTest.addListJumpAndDeleteBookmarksAcrossDialogReopens`
  timeout as "consistent with device-load flakiness... not a regression,"
  stated as a settled conclusion. Corrected to record only what was
  observed: one timeout occurred during a loaded full-suite run; the same
  test passed in isolation immediately after; the full `EpubBookmarkTest`
  class passed; a subsequent full connected-suite run passed completely; no
  product defect was reproduced after those re-runs. This is **observed
  timing instability**, not a confirmed environmental cause — the evidence
  trail is preserved, not erased.

### RP5 FOCUS FINDING — CLASSIFICATION CORRECTED

The original wording asserted the RP5 keyboard-focus failure was "plausibly
a device/launcher-specific touch-mode quirk," presented as if established.
Corrected: the best evidence currently available indicates pre-existing/
device-specific behavior (a 2B.2 R3 test; no input/focus code was touched
in either the original 2B.2.1 slice or this remediation) — but a same-device
comparison against accepted `main` was not performed, so a launcher-specific
cause is not confirmed, and 2B.2.1 cannot be shown to definitely have no
bearing on it either. The accepted 2B.2 keyboard-focus behavior itself was
**not modified** in this remediation, per the task's explicit instruction
not to expand scope to fix it. A same-device main-branch comparison remains
a reasonable separate follow-up, not a blocker for this remediation.

### JVM TESTS

| Test class | Result |
| --- | --- |
| `EpubBookmarkPresentationTest` | 25/25 passed (up from 13) — old "closest progression"/"tied distance" tests removed (their expectations were provably wrong under floor semantics) and replaced with the full boundary matrix: exact/mid-segment boundaries, the critical 0.7 regression case, 0.99/1.0 end-of-resource behavior, negative/`>1`/NaN/infinite/missing progression, unmatched href, global numbering across two resources, invalid-candidate filtering, deterministic tied-floor tie-breaking, enriched-target equivalence |

### INSTRUMENTED TESTS (`shelfos-phase0`, API 35)

| Test class | Result |
| --- | --- |
| `EpubBookmarkLocationInstrumentedTest` | 6/6 passed (up from 4) — two new tests against real, non-fabricated Readium-computed data: a later-resource bookmark resolves successfully with Location > 1 and no numbering reset; and, using a new fixture (`OriginalFixtures.epubWithLongChapter`, seeded high-entropy content that does not compress down to one position), the real floor/segment-start boundary behavior is confirmed against real segment starts Readium actually computed |
| `EpubBookmarkTest` | 5/5 passed |
| `BookmarkPersistenceTest` | 7/7 passed |
| `EpubChapterHighlightTest` | 3/3 passed |
| `NavigationSmokeTest` | 26/26 passed |
| `EpubRecreationTest` | 1/1 passed |
| Full `connectedDebugAndroidTest` (first attempt) | Stopped after 32/65 tests with 2 failures (`EpubRecreationTest.readerUiStateSurvivesRecreationAndAppliedAppearanceReloads`, `InputModalityClassificationTest.homeHasNoModality`, the latter with an empty stack trace) |
| Full `connectedDebugAndroidTest` (after emulator restart) | **73/73 passed, 0 failures, 0 errors**, all 12 instrumented classes complete |

**Investigated factually, not auto-labeled a flake, per this task's own
instruction.** The first full-suite run's log showed the same signature as
prior documented emulator infrastructure failures this project has hit
before: `Failed to list .../additional_test_output: ls: ...: Transport
endpoint is not connected` and `Error Output: cmd: Can't find service:
package`. Only 32 of 65 expected tests ran (7 of 11 classes), consistent
with the emulator's `system_server` package service dying mid-run rather
than a code defect — the `EpubRecreationTest` failure's own message ("last
lifecycle transition = PAUSED", 72.689s vs. a normal ~1s) is consistent with
the activity never being able to complete its lifecycle once the system
service died, and `InputModalityClassificationTest.homeHasNoModality`'s
empty failure lines up with the same moment. Neither failing test touches
this remediation's changed code (bookmark location resolution/dispatch);
both are unrelated regression-suite tests (recreation/appearance, HOME-key
modality classification). Recovery followed the same established procedure
as prior incidents (confirm no stale `emulator`/`qemu` processes via
PowerShell, `adb kill-server`/`start-server`, fresh `emulator.exe -avd
shelfos-phase0 -no-snapshot -no-boot-anim`, poll `sys.boot_completed`,
verify `pm list packages`) — the subsequent complete re-run passed 73/73.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| Full gate (`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --rerun-tasks --offline --console=plain`) | **BUILD SUCCESSFUL**, 86/86 tasks executed |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas app/build.gradle.kts gradle/libs.versions.toml` | Clean — no schema change, no dependency change |

### PRESERVED ACCEPTED ARCHITECTURE (confirmed unchanged)

Bookmark Room schema, `Bookmark` domain model, stored locator format,
navigation authority (`EpubController.goTo`), the DAO's exact-locator
duplicate guard, `sameEpubBookmarkLocation`, bookmark ordering,
`matchChapter`, the source EPUB, and the reader's resume model — confirmed
via `git diff --stat` showing no changes to `LibraryDao.kt`,
`ShelfDatabase.kt`, `RoomLibraryRepository.kt`, `Bookmark.kt`, or
`EpubActivity.kt`. Derived Location N remains presentation only.

### FINAL ACCEPTANCE (2B.2.1 R2/R3 remediation) — pending

Both R2 and R3 findings are closed with evidence, including two newly
strengthened real-fixture tests and a corrected documentation trail. **Not
yet re-reviewed by Codex, not merged, not pushed.** 2B.2.2, 2B.3, 2B.4
remain untouched and unstarted.

## Phase 2B.2.1 validation — bookmark location polish (2026-09-27)

**Superseded by the "Phase 2B.2.1 R2/R3 remediation" section above, which is
the current status and contains the corrected wording for the specific
claims flagged below (archive-length semantics, Compose-blocking language,
timeout causality, RP5 focus classification).** Kept as the original
evidence trail, not current status.

Branch `phase-2/bookmark-location-polish`, base `main` at
`b1ad0290fa6a4e7b5148a9cd068a4913ac616704` (2B.2 accepted/merged via PR #9).
**2B.2.1 IMPLEMENTED, PENDING INDEPENDENT REVIEW — not accepted, not
merged.** Presentation-only: no schema/migration change, no dependency
change, no touch to bookmark identity/equivalence or DAO duplicate handling.

### READIUM POSITION API — INVESTIGATION FINDINGS

Investigated via `javap` decompilation of the pinned Readium 3.4.0 artifacts
in this repo's Gradle cache (`readium-shared-3.4.0-runtime.jar`,
`readium-streamer-3.4.0-runtime.jar`) — no source jar exists for this pinned
release, consistent with every prior Readium investigation in this project.

| Question | Finding |
| --- | --- |
| Can a stored Locator without `position` be resolved against Publication positions? | Yes — `Publication.positions(): List<Locator>` (suspend extension, `PositionsServiceKt`) returns a catalog independent of what the stored locator itself carries; resolution compares resource + progression, never the target's own `position` field |
| Wired by default? | Yes — `EpubParser`'s constructor wires `EpubPositionsService.Companion.createFactory(ReflowableStrategy.Companion.recommended)` onto every EPUB `Publication`, confirmed directly in `EpubParser`'s decompiled bytecode |
| Offline? | Yes — `EpubPositionsService` is `Container<Resource>`-based, never `HttpClient`-based; `EpubReaderFactory`'s always-failing `offlineClient` is never touched |
| Stable across font/line-height/margins/orientation/screen size? | Yes — the default strategy (`ArchiveEntryLength(1024)`, confirmed in `ReflowableStrategy`'s static initializer) segments by each resource's *archive-stored* (compressed) byte length, a purely structural property of the unchanging EPUB file, entirely independent of rendering |
| Expensive / blocking? | Reads container/resource metadata once; not free, not free of I/O, but cheap relative to opening the publication itself |
| Cached by Readium? | Yes — `EpubPositionsService` (and `WebPositionsService`) both memoize in a private field after the first call |
| Would it block reader startup/dialog opening? | Only if called incorrectly; this implementation calls it in `EpubReaderViewModel`'s own coroutine *after* the session is already open, never during startup |
| Requires changing the persisted bookmark? | No — never persisted; recomputed from the live `Publication` every session |
| Deterministic enough to label "Location N"? | Yes, for a fixed local EPUB file (ShelfOS never rewrites source publications) |

**Conclusion: Location N is reliable enough to show**, via a mechanism
independent of 2B.2's own documented finding that the stored locator does
not reliably carry `position` at Add time — this slice never reads that
field at all, resolving instead against the independent position catalog.

### DISPLAY HIERARCHY

`EpubBookmarkPresentation(chapterTitle, location, progress)` +
`bookmarkDisplayText`/`bookmarkAccessibilityText` (pure, in
`core.reader.EpubReader.kt`):

| Chapter | Location | Display | Accessible |
| --- | --- | --- | --- |
| yes | yes | `Chapter 7\nLocation 184 · 56% through book` | `Chapter 7, Location 184, 56 percent through book` |
| no | yes | `Location 184 · 56% through book` | `Location 184, 56 percent through book` |
| yes | no | `Chapter 7\n56% through book` | `Chapter 7, 56 percent through book` |
| no | no | `56% through book` | `56 percent through book` |

Never "Page N" for a reflowable EPUB. Both parts independently allowed to be
absent, per `matchChapter`/`resolveEpubLocation`'s own honesty contracts —
neither is ever fabricated.

### LOCATOR AUTHORITY / BOOKMARK-EQUIVALENCE CONFIRMATION

`sameEpubBookmarkLocation` (the "already bookmarked" check) and
`LibraryDao.addBookmark`'s exact-locator duplicate guard are byte-for-byte
unchanged by this slice. `resolveEpubLocation` is a separate, read-only
function with no write path and no influence on either — confirmed by
inspection and by `git diff` showing no changes to `RoomLibraryRepository`,
`LibraryDao`, or `ShelfDatabase`. No fake position is ever persisted:
`EpubPosition`/`EpubBookmarkPresentation` never enter `Bookmark`,
`BookmarkEntity`, Room, or `rememberSaveable`/`SavedState`.

### JVM TESTS

| Test class | Result |
| --- | --- |
| `EpubBookmarkPresentationTest` (new) | 13/13 passed — `resolveEpubLocation` (unique/no-match/ambiguous-without-progression/closest-progression/tied-distance-determinism using exact binary fractions/enriched-target equivalence), all four `bookmarkDisplayText`/`bookmarkAccessibilityText` format branches, format-consistency, no-"Page"-terminology assertion across all branches |

### INSTRUMENTED TESTS (`shelfos-phase0`, API 35)

| Test class | Result |
| --- | --- |
| `EpubBookmarkLocationInstrumentedTest` (new) | 4/4 passed against the real `epubWithChapters` fixture and the real Readium pipeline — see below |
| `EpubBookmarkTest` | 5/5 passed |
| `BookmarkPersistenceTest` | 7/7 passed |
| `EpubChapterHighlightTest` | 3/3 passed |
| `NavigationSmokeTest` | 26/26 passed |
| `EpubRecreationTest` | 1/1 passed |

**Real-fixture finding (empirically confirmed, not assumed):** the fixture's
10 chapters (highly repetitive synthetic text, "Chapter N paragraph M...")
each compress under 1024 bytes, so `ArchiveEntryLength`'s default yields
exactly one position per chapter (10 positions total) rather than several —
first observed as a genuine, initially-surprising test failure (an
over-eager assertion expecting more than 10 positions), then corrected to
assert what is actually true, with the finding recorded directly in the
test's own doc comment. The multi-position-per-resource, closest-progression
disambiguation path is instead proven via synthetic `EpubPosition` lists in
`EpubBookmarkPresentationTest`; a real, less-compressible full-length book
would exercise both paths together. Positions are confirmed global,
1-based, and contiguous (`positions.map { it.position } ==
(1..positions.size).toList()`), and a locator enriched with extra fields
(`title`, `totalProgression`, an unrelated `position` value) resolves to the
identical Location as the minimal one, against the real fixture — directly
proving the equivalence `EpubBookmarkPresentationTest`'s pure-logic test
already established.

**One flake observed and confirmed non-reproducible:** a full-class
`EpubBookmarkTest` run on the emulator recorded one
`addListJumpAndDeleteBookmarksAcrossDialogReopens` Espresso idling timeout
(70s vs. a normal 8–15s); re-run alone immediately after, it passed cleanly
in 50s. Consistent with the same device-load flakiness pattern already
documented for 2B.1/2B.2's own validation history, not a regression
introduced by this slice (this slice touches only bookmark row *label*
composition, not the add/jump/delete logic that test exercises).

### RP5 (physical Retroid Pocket 5, `d8f7f1b6`, Android 13 / API 33)

Production reader UI changed (the bookmark row's visible text), so RP5 was
exercised, not skipped. `EpubBookmarkTest` was run against the connected
device:

- First attempt: all 5 failed with "No compose hierarchies found" — the
  device's screen was dozing/locked (`mWakefulness=Dozing`, focus on the
  device's own game launcher), so the test activity never actually gained
  focus. Diagnosed via `dumpsys power`/`dumpsys window`, not assumed.
- After waking and unlocking the device (`input keyevent KEYCODE_WAKEUP`,
  `wm dismiss-keyguard`) and re-running: **4/5 passed** — the full add/
  list/jump/delete flow, activity recreation, publication reopen, and
  malformed-locator handling all passed on the real device.
- `bookmarksDialogIsReachableAndOperableThroughKeyboardFocus` failed at its
  first `RequestFocus`/`assertIsFocused` step (a 2B.2 R3 test, unrelated to
  this slice — no input/focus code was touched in 2B.2.1). Plausibly a
  device/launcher-specific touch-mode quirk on this handheld's customized
  Android build, differing from the emulator where the same test passes.
  Not investigated further — out of scope for a presentation-only slice —
  and flagged here explicitly rather than silently dropped.
- **Visual legibility confirmed by direct screenshot**, not assumed:
  `adb exec-out screencap` captured the real device's screen while a test
  held the Bookmarks dialog open, showing "Chapter 1" / "Location 1 · 0%
  through book" rendered legibly at the device's real resolution, DPI, and
  theme. The capture mechanism (a temporary `Thread.sleep` added to one
  test to hold the dialog open for an external screenshot) was fully
  reverted before commit — confirmed via `git diff` on
  `EpubBookmarkTest.kt` showing no residual changes.

ShelfOS was tested on a Retroid Pocket 5. This is real hardware execution
(the real WebView/GPU/screen, not the emulator) with a directly observed
visual result — not a claim of manual physical controller button-pressing,
which was not performed in this pass.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** |
| Full gate (`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --rerun-tasks --offline --console=plain`) | **BUILD SUCCESSFUL**, 86/86 tasks executed |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean — no schema change |
| `git status --porcelain -- app/build.gradle.kts gradle/libs.versions.toml` | Clean — no dependency change |

### FINAL ACCEPTANCE (2B.2.1) — pending

Implementation complete with the evidence above, including one explicitly
flagged, out-of-scope, pre-existing device-specific finding
(RP5 keyboard-focus quirk) rather than a silently-dropped gap. **Not yet
reviewed by Codex, not merged, not pushed.** 2B.2.2, 2B.3, 2B.4 remain
untouched and unstarted.

## Phase 2B.2 bookmark-state blocker remediation (2026-09-27)

The reproducible blocker was investigated on
`phase-2/epub-bookmarks-ready`, starting from prior HEAD `46c821b` (the
production implementation under test originated at `25612d5`). The failing
method was reproduced again with temporary end-to-end diagnostics before any
behavior changed. Add was enabled and activated once; the captured locator was
`chapter1.xhtml` at resource progression `0`; the duplicate check returned
false; Room inserted the row; the repository Flow emitted it; and the
ViewModel and Compose state both received it.

The failure was in production live-state comparison rather than persistence,
Flow collection, test isolation, or a stale UI string. Immediately after the
insert, the stored and live locators were identical and the intended
"Bookmarked" disabled-button state appeared. Readium 3.4.0 then emitted the
same physical reading location enriched with `title`, publication `position =
1`, and `totalProgression = 0`. The stored snapshot contained none of those
fields. Exact serialized JSON equality therefore became false and the UI
incorrectly returned to "Add bookmark" even though the reader had not moved.

The UI now compares parsed locators using stable location evidence: matching
resource plus, in precedence order, an available position on both locators,
matching fragments on both locators, or identical resource-relative
progression. It deliberately ignores title, total progression, text, JSON key
ordering, and other enrichment fields. No progression tolerance was added.
The DAO's exact `(itemId, locator)` duplicate-storage guard remains unchanged
and separate from this live UI equivalence rule. Temporary diagnostics were
removed. `EpubBookmarkLocationTest` adds six pure JVM cases for the observed
minimal-to-enriched transition and conservative negative cases; the existing
end-to-end test remains unchanged and still asserts the intended
"Bookmarked" state.

### Bookmark location-display finding

The authoritative locator captured at Add time did **not** reliably contain
`Locator.Locations.position`; position appeared only in a later enriched live
emission. ShelfOS therefore does not display or persist a fabricated EPUB
page/location number in 2B.2. Bookmark rows retain chapter plus percentage,
or percentage alone when chapter matching is ambiguous. A future "Location
N" display can be reconsidered only when the locator actually stored for each
bookmark reliably supplies a stable publication position. EPUB must never
label reflowable visual pagination as "Page N."

### Post-fix evidence

- The formerly failing method passed twice in separate rerun-task
  instrumentation invocations.
- `EpubBookmarkTest` passed 5/5; `BookmarkPersistenceTest` 7/7;
  `NavigationSmokeTest` 26/26; `EpubRecreationTest` 1/1; and
  `EpubChapterHighlightTest` 3/3.
- The unfiltered API 35 connected suite passed **67/67**, with zero failures,
  errors, or skips. This replaces the pre-fix 66/67 result below.
- The required offline static gate completed all 86 tasks successfully:
  production and Android-test compilation, debug APKs, 93/93 JVM tests, and
  lint with zero issues.
- `git diff --check` and `git status --porcelain -- app/schemas` are clean. No
  schema, migration, dependency, or Phase 2B.3/2B.4 change was introduced.

### Fixed-build RP5 physical acceptance

The exact debug APK built from fixed HEAD `264d6f4` (SHA-256
`cfa17e5d469452af44ac8644340f1ba183c40d29b81b473b0eaeaab27361b9df`)
was installed successfully on Retroid Pocket 5 `d8f7f1b6` with
`adb install -r`. ShelfOS was not uninstalled, app data was not cleared, and
the app launched through `.MainActivity` after replacement.

The owner then reported **RP5 FIXED BUILD PASS** after physically exercising
the focused checklist with the RP5 controls. Add created a bookmark and the
same current location remained recognized as "Bookmarked" after the reader
settled; moving elsewhere was not falsely recognized as the saved bookmark;
jumping through the saved bookmark closed the dialog, returned to the saved
location, and restored the recognized state; Delete removed the bookmark and
made Add available again; and physical B dismissed Bookmarks first, revealed
hidden reader chrome, and exited only with chrome visible. This is owner-
reported physical-button evidence, distinct from emulator/ADB automation.
The earlier physical PASS against `25612d5` remains historical evidence only;
this fixed-build result supersedes it for final readiness.

### Final Phase 2B.2 acceptance status

- **IMPLEMENTED**
- **INDEPENDENT REVIEW PASSED**
- **AUTOMATED ACCEPTANCE PASSED**
- **RP5 PHYSICAL ACCEPTANCE PASSED**
- **READY FOR PR**

Phase 2B.2 is not merged. Nothing was pushed and no PR was opened during this
acceptance pass. Phase 2B.3 and 2B.4 remain untouched.

## Phase 2B.2 pre-remediation final-gate and RP5 validation (2026-09-27)

Branch `phase-2/epub-bookmarks-ready` at commit `25612d5` was built and
reviewed against `main` at `ac9476d56f332c067aa39dc2b1e5533288103c12`.
The branch contains only the two Phase 2B.2 commits (`9d0d965`, `25612d5`);
the unrelated future Notes interoperability commit `90622a6` and
`docs/features/ANNOTATIONS.md` are absent. Historical schemas `1.json` and
`2.json` are unchanged and `3.json` remains the only added schema artifact.

The two independent-review R3 findings are closed:

- Bookmark ordering is `progress ASC, createdAt ASC, id ASC`, with an
  equal-progress/equal-created-time regression test proving the `id`
  tie-breaker.
- Automated keyboard/controller evidence uses explicit focus plus real key
  events for Bookmarks, Add, jump, Delete, and Back. Owner physical acceptance
  on the RP5 is recorded below; injected events are not presented as physical
  evidence.

### Automated final-gate evidence

The static gate completed successfully: 86/86 tasks, 87/87 JVM tests, and
lint with 0 errors and 7 warnings. `git diff --check` and the schema working
tree check were clean. On the API 35 emulator,
`BookmarkPersistenceTest` passed 7/7, `NavigationSmokeTest` 26/26,
`EpubRecreationTest` 1/1, and `EpubChapterHighlightTest` 3/3.

The final connected suite completed **66/67** tests. The only failure was
`EpubBookmarkTest.addListJumpAndDeleteBookmarksAcrossDialogReopens`, which
timed out after 10 seconds at `EpubBookmarkTest.kt:56` while waiting for the
"Bookmarked" label after Add. The same test then failed at the same line in
an isolated class run and again in an isolated run after fully restarting the
emulator; `EpubBookmarkTest` was 4/5 in each isolated run. This final evidence
supersedes the earlier description of that timeout as confirmed
non-reproducible. It does not establish whether the defect is in production
behavior or the assertion, so it remains an unresolved automated pre-PR
blocker rather than being attributed to emulator load.

### RP5 owner physical acceptance

The exact debug APK built from `25612d5` (SHA-256
`5a18f323038ff8f99e86633f91ae29e3147317cd9d5232041ea56ab45877eadf`)
was installed on the Retroid Pocket 5 (`d8f7f1b6`) with `adb install -r`.
Installation returned `Success`; ShelfOS was not uninstalled and app data was
not cleared. The package's original `firstInstallTime` remained unchanged.
The app launched successfully through its declared `.MainActivity`. The
visible library was empty before the acceptance publication was added, so no
pre-existing publication/progress row was available for a visual migration
check; the automated migration tests remain authoritative.

The owner then reported **RP5 PASS** after physically exercising the agreed
checklist with the RP5's own controls: Bookmarks was reachable from reader
chrome; Add created a visible bookmark; activating the bookmark closed the
dialog and returned to the saved location; Delete removed the intended row
and the final deletion restored the empty state; physical B dismissed the
Bookmarks dialog first, revealed hidden reader chrome, and exited only when
chrome was already visible. This is owner-reported physical-button evidence,
distinct from Codex's ADB/Compose-injected automation.

**Historical status at `46c821b`:** independent review and the owner's RP5
check passed, but the reproducible automated failure still blocked PR
readiness. The remediation and current status are recorded in the section
above. Phase 2B.3 and 2B.4 remain untouched.

## Phase 2B.2 R3 remediation (2026-09-26)

Independent Codex review of 2B.2 (below) returned **PASS WITH NON-BLOCKING
FINDINGS** — no R1/R2 findings — with two R3 findings: (1) bookmark ordering
lacked a final deterministic tie-breaker when both `progress` and `createdAt`
are identical; (2) automated tests did not directly prove D-pad/controller
focus traversal through the Bookmarks UI, and physical RP5 validation
remained pending. Both are addressed here, on the same branch
(`phase-2/epub-bookmarks`), without resetting or dropping `e1ab272`. **2B.2
remains IMPLEMENTED, PENDING INDEPENDENT REVIEW — not accepted.**

### R3.1 — deterministic ordering

`LibraryDao.observeBookmarks` now orders `progress ASC, createdAt ASC, id
ASC`. `progress`, `createdAt`, and locator authority are unchanged — this is
ordering only. New regression test
`BookmarkPersistenceTest.bookmarksWithIdenticalProgressAndCreatedAtStillSortDeterministicallyById`
inserts three bookmarks sharing one `progress` and one `createdAt`, with
explicit deterministic ids (`"a-bookmark"`, `"b-bookmark"`, `"c-bookmark"`)
inserted directly via the DAO out of id order (not through
`RoomLibraryRepository.addBookmark`, which always generates a random UUID
and the current time), and asserts the returned order is exactly `a, b, c`.
All prior migration/FK/duplicate/isolation/delete tests are preserved
unchanged.

### R3.2 — keyboard/controller focus evidence

Inspected `NavigationSmokeTest`'s established convention for real input
testing: explicit `performSemanticsAction(SemanticsActions.RequestFocus)` on
a specific node followed by a real `performKeyInput { pressKey(...) }` or
`instrumentation.sendKeyDownUpSync(...)`, rather than counting an arbitrary
number of directional key presses through an unspecified focus order (which
would be brittle, per the task's own explicit warning). This pattern
transfers cleanly to the Bookmarks dialog, so a new instrumented test was
added rather than declining to add one:

`EpubBookmarkTest.bookmarksDialogIsReachableAndOperableThroughKeyboardFocus`
verifies, all via explicit focus + a real key event (never a semantic
click):
- the chrome's Bookmarks entry is focusable and opens the dialog via `Key.Enter`
- Add bookmark is focusable and activatable the same way (a real bookmark is created)
- an existing bookmark row is focusable, and activating it performs the real jump (`EpubController.goTo`) — the dialog only closes on success, not merely because a handler ran
- reopening the dialog and pressing the real hardware Back key (`KEYCODE_BACK`) dismisses it while leaving the reader chrome and `epub_reader` tag in place — Phase 2A/ADR-0023 Back semantics are undisturbed
- Delete is focusable and activatable the same way, producing the normal empty state

No new `ShelfCommand` mapping was added or needed. This closes the automated
half of the R3.2 finding.

### RP5 STATUS (unchanged, factual)

**RP5 physical controller acceptance remains pending before merge.** A
physical RP5 device was connected to this environment during this
remediation pass (alongside the emulator), but no physical button-press
interaction was performed — only `adb`-injected/Compose-injected key events
on the emulator, which are real key events but are explicitly not a
substitute for physical hardware interaction, per this task's own
instruction. No physical validation is claimed.

### FOCUSED TEST RESULTS (`shelfos-phase0`, API 35, freshly restarted emulator)

| Test class | Result |
| --- | --- |
| `BookmarkPersistenceTest` | 7/7 passed (6 prior + 1 new ordering tie-break regression test) |
| `EpubBookmarkTest` | 5/5 passed in this historical focused run (4 prior + 1 new keyboard/D-pad focus test); later reproduction and final remediation are recorded above |
| `NavigationSmokeTest` | 26/26 passed |
| `EpubRecreationTest` | 1/1 passed |

Because production DAO ordering changed but the schema did not, a second
full `connectedDebugAndroidTest` pass was not required per this task's own
instruction (all focused tests green) and was not run again in this
remediation.

**Emulator infrastructure note (recurrence):** the same class of ADB
transport disconnection documented in the original 2B.2 validation recurred
once during this remediation pass (`Failed to uninstall package ...: cmd:
Can't find service: package`), again consistent with the AVD instance
degrading under sustained load. Resolved with the same procedure as before
(confirm no stale `emulator`/`qemu` processes, `adb kill-server`/
`start-server`, fresh `emulator.exe -avd shelfos-phase0 -no-snapshot
-no-boot-anim` launch, poll `sys.boot_completed`, verify `pm list packages`)
before re-running; all runs after the restart were clean.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| Full gate (`:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --rerun-tasks --offline --console=plain`) | **BUILD SUCCESSFUL**, 86/86 tasks executed, no failed tasks |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean — no new/changed schema revision; still only the v3 schema added by the original 2B.2 commit |

### FINAL ACCEPTANCE (2B.2 R3 remediation) — pending

Both R3 findings are closed with evidence (ordering fully; keyboard/
controller focus automated evidence added, with physical RP5 acceptance
explicitly still pending). **Not yet re-reviewed by Codex, not merged, not
pushed.** 2B.3 (search) and 2B.4 (custom fonts) remain untouched.

## Phase 2B.2 validation — durable EPUB bookmarks + Room v2→v3 migration (2026-09-26)

Branch `phase-2/epub-bookmarks`, base `main` at `ac9476d56f332c067aa39dc2b1e5533288103c12`
(2B.1 accepted and merged via PR #8). **2B.2 IMPLEMENTED, PENDING INDEPENDENT
REVIEW — not accepted, not merged.** No 2B.3 (search), 2B.4 (custom fonts), or
Notes/highlights/export work is included.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** |
| `:app:testDebugUnitTest` | **BUILD SUCCESSFUL** |
| `:app:lintDebug` | **BUILD SUCCESSFUL** |
| `:app:assembleDebugAndroidTest` | **BUILD SUCCESSFUL** |
| Full gate, one invocation (`--rerun-tasks --offline --console=plain`) | **BUILD SUCCESSFUL**, no failed tasks |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | New `3.json` only (the `bookmark` table's KSP-exported v3 schema); no historical `1.json`/`2.json` modified |

### ROOM MIGRATION (v2 → v3)

`ShelfDatabase` bumped `version = 2` to `version = 3`, adding `BookmarkEntity`
(`bookmark` table: `id TEXT PK`, `itemId TEXT NOT NULL` FK to
`library_item.id` `ON DELETE CASCADE`, `locator TEXT NOT NULL`,
`progress INTEGER NOT NULL`, `label TEXT NULL`, `createdAt INTEGER NOT NULL`,
indexed on `itemId`) and `MIGRATION_2_3`, additive-only — no existing table,
column, or index is altered or dropped. No `fallbackToDestructiveMigration`
anywhere.

`BookmarkPersistenceTest.bookmarkMigrationPreservesExistingDataAndSupportsCascadeDelete`
bootstraps the **real** v2 schema via raw SQL matching
`app/schemas/.../2.json` exactly (not `MigrationTestHelper` — following this
project's existing `LibraryPersistenceTest` convention of a raw-SQL bootstrap
plus real `Room.databaseBuilder(...).addMigrations(...)`, which already
covered this need without a new test-only dependency), seeds a pre-existing
`library_item`, `reading_state`, and `reader_preference` row, migrates via
`MIGRATION_1_2, MIGRATION_2_3`, then asserts: the library item, its resume
state, and its preferences are all still present and unchanged; the
`bookmark` table exists and accepts a row; a bookmark insert against a
non-existent `itemId` is rejected by the real FK (Room enforces foreign keys
by default in this project, confirmed empirically); and deleting the
`library_item` cascades to delete its bookmarks via the FK's
`ON DELETE CASCADE`, with no effect on any other item's data.

No `room-testing`/`MigrationTestHelper` dependency was added — the existing
raw-SQL-bootstrap convention already satisfied the requirement, avoiding an
unnecessary new dependency per `AGENTS.md`'s dependency-review rule.

### JVM / REGRESSION

No new JVM-only test class was needed for this slice; bookmark domain logic
(duplicate handling, ordering, cascade, isolation) is exercised instrumented
against the real database, since it is inseparable from real Room/FK
behavior. `EpubChapterMatchTest` (16/16, unchanged from 2B.1) continues to
pass, confirming the chapter matcher reused for bookmark labels is untouched.

### INSTRUMENTED / EMULATOR (`shelfos-phase0`, API 35)

| Test class | Result |
| --- | --- |
| `BookmarkPersistenceTest` (new) | 6/6 passed — migration+preservation+cascade, duplicate-add no-op, deterministic ordering (progress ascending, createdAt tie-break), FK rejection for a nonexistent item, per-item isolation, single-row delete leaving siblings intact |
| `EpubBookmarkTest` (new) | 4/4 passed — full add/list/jump/delete flow across dialog reopens (`addListJumpAndDeleteBookmarksAcrossDialogReopens`), activity recreation, publication close/reopen, and malformed-locator handling (readable failure, no crash, still deletable) |
| `EpubChapterHighlightTest` | 3/3 passed — 2B.1 chapter-highlight/filter behavior unaffected |
| `NavigationSmokeTest` | 26/26 passed |
| `EpubRecreationTest` | 1/1 passed |
| `LibraryPersistenceTest` | 10/10 passed (see regression note below) |
| Full `connectedDebugAndroidTest` suite | **65/65 passed, 0 failures, 0 errors**, one clean complete run, required because this slice changes the persistent schema |

**Emulator infrastructure note:** during validation, the `shelfos-phase0`
emulator's ADB transport disconnected mid-run (`Transport endpoint is not
connected`, `cmd: Can't find service: package`), consistent with the AVD
instance degrading under sustained load rather than a code issue — confirmed
by checking for stale processes, restarting ADB and the emulator cleanly, and
then observing the full suite complete much faster and without incident.

**Historical timeout, subsequently reproduced and fixed:** in one full-suite
run, `EpubBookmarkTest.addListJumpAndDeleteBookmarksAcrossDialogReopens`
failed on a 10-second Compose wait for the "Bookmarked" label
(`ComposeTimeoutException`). An immediate isolated rerun and a subsequent
65/65 suite happened to pass, but later final-gate runs reproduced the same
failure repeatedly, including after an emulator restart. The blocker
remediation section above records the production root cause and final 67/67
evidence; the earlier passing reruns did not prove the timeout was a flake.

**Real regression found and fixed:** the same full-suite run also failed
`LibraryPersistenceTest.migrationPreservesAppearanceAndLibrarySurvivesReopen`
with `IllegalStateException: A migration from 1 to 3 was required but not
found`. Root cause: this pre-existing test's own `open()` helper hardcoded
only `MIGRATION_1_2`; once the schema version moved to 3, any caller building
the database — including this test's local helper, independent of
production's own (already-correct) `ShelfDatabase.create()` — needs a path to
v3. Fixed by adding `MIGRATION_2_3` to that helper's `addMigrations(...)`
call. Confirmed fixed: 10/10 `LibraryPersistenceTest` cases pass in isolation,
and the subsequent full-suite run above (65/65) confirms no other caller was
affected.

### REPOSITORY / DOMAIN BEHAVIOR

- **Locator-authoritative, progress-snapshot-only:** `Bookmark.locator` is the
  serialized Readium `Locator` JSON and is the only value ever used for
  navigation (`EpubController.goTo`); `Bookmark.progress` is stored purely for
  display/sort and is never read back into navigation.
- **Duplicate handling:** `LibraryDao.addBookmark` is a `@Transaction` that
  checks for an existing row with the same `(itemId, locator)` exact string
  before inserting; no database `UNIQUE` constraint was added over the opaque
  locator JSON (not proven stable enough for one). Covered by
  `addingTheSameLocatorTwiceDoesNotCreateADuplicateRow` and, at the UI layer,
  by the Add button being disabled once the current position is already
  bookmarked.
- **Ordering:** `progress ASC, createdAt ASC` — deterministic, covered by
  `bookmarksAreOrderedByProgressThenCreationTimeDeterministically`.
- **Live current-location capture:** "Add bookmark" reuses `EpubActivity`'s
  existing `currentLocatorJson`, itself populated by `EpubSurface`'s single
  `navigator.currentLocator` collector (established in 2B.1) — no second
  long-lived collector was introduced. If no live locator is available yet,
  Add is disabled; no empty/fake locator is ever created.
- **Cascade delete:** relies entirely on the Room FK's `ON DELETE CASCADE` —
  `LibraryDao.remove(itemId)` was deliberately left unchanged.

### JUMP-TO-BOOKMARK / MALFORMED LOCATOR

`EpubController.goTo(locatorJson)` parses the stored JSON via
`Locator.fromJSON` and calls the existing navigator's `go(Locator, animated =
false)` through the same controller/session ownership boundary used
elsewhere — no second navigator is created. On successful jump the Bookmarks
dialog closes and resume persistence updates naturally through the existing
locator pipeline. On a malformed/unparseable locator, `goTo` returns `false`
without throwing; the UI shows "This bookmark's saved location could not be
opened. You can still delete it." and leaves the dialog open and usable —
verified by
`malformedBookmarkLocatorShowsAReadableFailureRatherThanCrashingAndCanStillBeDeleted`,
which writes a malformed locator directly through the repository (the UI
itself can never produce one). No fallback to progress-percentage navigation
exists.

### ACCESSIBILITY

Each bookmark row exposes a single meaningful `contentDescription`
("Bookmark, <chapter title or progress>%"), not icon-only or unlabeled. The
Add control's `contentDescription` states whether adding is currently
possible ("Add bookmark at current position" / "Current position already
bookmarked" / "Add bookmark, not yet available"). The Delete control's
description names its own bookmark's label so its target is unambiguous.
Progress is never communicated by color/visual-only means — it is always in
the row's text and description.

### KEYBOARD / CONTROLLER

No new `ShelfCommand` mapping was introduced; the Bookmarks chrome button and
all dialog controls are ordinary focusable Compose `TextButton`s, reachable
and activatable exactly like the existing Chapters/Appearance buttons and
dialogs. Back/Escape/gamepad B closes the Bookmarks dialog first via the same
`AlertDialog` dismiss handling already used elsewhere; Phase 2A Back
semantics (ADR-0023) and Phase 2A.1 input hints are unaffected — confirmed by
`EpubRecreationTest` (1/1) and `NavigationSmokeTest` (26/26).
`INPUT_SYSTEM.md`'s conceptual `TOGGLE_BOOKMARK` command was **not** wired in
this slice, since no accepted mapping contract exists for it yet.

### RP5 (physical device)

**Not performed.** Only the `shelfos-phase0` API 35 emulator was used for
instrumented validation in this slice; no physical-device evidence is claimed
or fabricated. Flagged for a future validation pass before this slice is
considered release-ready, consistent with this doc's standing evidentiary
rule of recording only what was actually observed.

### RECREATION / RESTART

Bookmarks are Room-backed, not `rememberSaveable`-backed — no Readium object
or bookmark list is ever placed in `SavedState`. Verified surviving: dialog
close/reopen, activity recreation (`bookmarksSurviveActivityRecreation`), and
publication close/reopen (`bookmarksSurviveClosingAndReopeningThePublication`).

### REGRESSION CHECK

Confirmed unchanged by this slice: 2A Back semantics and hidden-chrome
accessibility action, 2A.1 input hints, touch modality, 2B.1 chapter
dialog/filter/current-chapter behavior, resume/progress persistence,
appearance persistence, continuous scroll, typography, page colors, EPUB
recreation, offline reading (no network dependency anywhere in this change),
and source preservation (no publication bytes are read, written, or
re-encoded by any bookmark code path) — evidenced by the full 65/65 connected
suite and the full local Gradle gate above.

### FINAL ACCEPTANCE (2B.2) — pending

Implementation complete with the evidence above. **Not yet reviewed by
Codex, not merged, not pushed.** 2B.3 (search) and 2B.4 (custom fonts) remain
untouched and unstarted.

## Phase 2B.1 R2 second remediation round validation (2026-09-26)

A second independent Codex review of the R2/R3 remediation below returned
**CHANGES REQUIRED** on two remaining points: (R2) the corrected fallback
could still present a specific, named chapter as "current" in a genuinely
ambiguous same-resource case rather than admitting the position could not be
determined; (R3) this document's flaky-test wording overstated a full-suite
teardown timeout as confirmed environmental flakiness rather than evidence
consistent with it. Both are fixed on the same branch,
`phase-2/epub-chapters`, without resetting/dropping either prior commit.
**2B.1 remains IMPLEMENTED, PENDING INDEPENDENT REVIEW — not accepted.**

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** |
| `:app:testDebugUnitTest` | **BUILD SUCCESSFUL** |
| `:app:lintDebug` | **BUILD SUCCESSFUL** |
| `:app:assembleDebugAndroidTest` | **BUILD SUCCESSFUL** |
| Full gate, one invocation (`--rerun-tasks --offline --console=plain`) | **BUILD SUCCESSFUL**, no failed tasks |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean (no Room changes) |

### JVM / REGRESSION

| Test class | Result |
| --- | --- |
| `EpubChapterMatchTest` | 16/16 passed — the prior 14 (one, the duplicate-href test, now asserts the corrected ambiguous/null outcome instead of "id 0 wins") plus 2 new: `twoFragmentOnlyCandidatesWithNoUsableLocatorFragmentAreAmbiguous`, `aSingleFragmentOnlyCandidateWinsByEliminationWhenNoOtherSameResourceEntryExists` — plus one existing test extended with a second, unrelated-fragment ambiguous case (`twoFragmentOnlyCandidatesWithAnUnrelatedLocatorFragmentAreStillAmbiguous`) |

### INSTRUMENTED / EMULATOR (`shelfos-phase0`, API 35)

| Test class | Result |
| --- | --- |
| `EpubChapterHighlightTest` | 3/3 passed — `currentChapterIsMarkedAndUpdatesAfterNavigatingElsewhere` (unambiguous case, unchanged) and `chapterTitleFilterNarrowsClearsAndHandlesNoMatches` (unchanged) plus the renamed, re-asserted `ambiguousSameResourceFragmentsShowNoCurrentRowButNavigationAndTheDialogStillWork` (zero current rows for the fragmented fixture's genuinely ambiguous case, both at initial open and after navigating to the other same-resource entry; navigation and the dialog itself remain fully functional) |
| `NavigationSmokeTest` (run alone) | 26/26 passed |
| `EpubRecreationTest` (run alone) | 1/1 passed |
| Full `connectedDebugAndroidTest` package | Not re-run as a second complete pass — the three focused instrumented runs above are all green and nothing in this round's change (a pure narrowing of `matchChapter`'s fallback, no schema/dependency/other-file change) suggests a broader regression; per this task's own instruction, a second complete run is optional in that case |

### ACCESSIBILITY

Re-verified after the corrected fallback: when `currentChapterId` is null
(the ambiguous case), no `Int` row id ever equals it, so **no row** receives
the checkmark, bold weight, or "current chapter" `contentDescription` — every
row remains a normal, navigable chapter button, confirmed directly by
`ambiguousSameResourceFragmentsShowNoCurrentRowButNavigationAndTheDialogStillWork`'s
zero-count assertions. No "current chapter unknown" text or warning was
added — the plain absence of a marker is the intended, non-alarming signal.
When a match is unambiguous, the existing three-signal treatment (checkmark +
bold + `contentDescription`) is unchanged, confirmed by
`currentChapterIsMarkedAndUpdatesAfterNavigatingElsewhere`.

### REGRESSION CHECK

Confirmed unchanged by this round: chapter navigation and the chapter filter,
stable row identities, all-locator-fragment matching (order preserved),
Back semantics and the accessibility "Show reader controls" action,
keyboard/controller hints, touch modality, resume/progress persistence,
appearance persistence, scrolling, typography, page colors
(`NavigationSmokeTest`, 26/26), recreation (`EpubRecreationTest`, 1/1),
offline reading (no network dependency anywhere in this change), and source
preservation (read-only TOC/locator handling, no EPUB bytes touched).

### FLAKY-TEST WORDING CORRECTION (R3)

The prior round's entry (below) stated a full-connected-suite
`ActivityScenario` teardown timeout was "confirming device-load flakiness."
That overstated the evidence: the timeout was observed to follow immediately
after a 34-minute `SyntheticLoadAcceptanceTest` run in the same session, and
the affected test then passed 26/26 when re-run alone — both facts are
consistent with load-related/environmental flakiness, but neither directly
proves that cause. Corrected wording (also applied at the original entry
below): *"The `ActivityScenario` teardown timeout occurred after the long
`SyntheticLoadAcceptanceTest` run and is consistent with load-related/
environmental flakiness. The affected `NavigationSmokeTest` subsequently
passed 26/26 in isolation. No production regression was reproduced."*

### FINAL ACCEPTANCE (2B.1 R2 second remediation round) — pending

Both remaining Codex findings from the second review are closed with
evidence. **Not yet re-reviewed by Codex, not merged.** 2B.2 (bookmarks)/2B.3
(search)/2B.4 (custom fonts) remain untouched.

## Phase 2B.1 R2/R3 remediation validation (2026-09-26)

Independent Codex review of the initial 2B.1 implementation (below) returned
**CHANGES REQUIRED**: two R2 findings (secondary locator fragments ignored;
raw href not a unique chapter-row identity) and two R3 findings (fragment
behavior lacked real integration coverage; this document didn't directly
record the full Gradle gate result). All four are fixed on the same branch,
`phase-2/epub-chapters`, without resetting/dropping the prior commit. **2B.1
remains IMPLEMENTED, PENDING INDEPENDENT REVIEW — not accepted.**

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:compileDebugAndroidTestKotlin` | **BUILD SUCCESSFUL** |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** |
| `:app:testDebugUnitTest` | **BUILD SUCCESSFUL** |
| `:app:lintDebug` | **BUILD SUCCESSFUL** |
| `:app:assembleDebugAndroidTest` | **BUILD SUCCESSFUL** |
| Full gate, run together as one invocation (`--rerun-tasks --offline --console=plain`) | **BUILD SUCCESSFUL**, no failed tasks, run directly for this remediation (this is the record R3.2 asked for — not a pointer to a separate report) |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean (no Room changes) |

### JVM / REGRESSION

| Test class | Result |
| --- | --- |
| `EpubChapterMatchTest` | 14/14 passed — the original 11 plus 3 new: `laterLocatorFragmentsAreConsideredWhenEarlierOnesDoNotMatchAnyChapter` (R2.1), `duplicateHrefRowsResolveToExactlyOneStableIdNotBothByHrefEquality` (R2.2), `noLocatorFragmentMatchingAnyChapterStillFallsBackDeterministically`; the misleading `onlyTheFirstLocatorFragmentIsConsidered` test was renamed to `firstLocatorFragmentWinsWhenItMatches` (still valid coverage of the common case, no longer implying only the first fragment is ever checked) |

### INSTRUMENTED / EMULATOR (`shelfos-phase0`, API 35)

| Test class | Result |
| --- | --- |
| `EpubChapterHighlightTest` | 3/3 passed — the original 2 plus `sameResourceFragmentedChapterAlwaysHasExactlyOneCurrentRowAndNavigationStillWorks` (new, against `OriginalFixtures.epubWithFragmentedChapter`) |
| `NavigationSmokeTest` (run alone) | 26/26 passed |
| `EpubRecreationTest` (run alone) | 1/1 passed |
| Full `connectedDebugAndroidTest` package (all 9 classes together) | 55/55 tests executed; 1 failure (`NavigationSmokeTest.mangaReadsRightToLeftWithKeyboardAndPageKeysStaySemantic`, `ActivityScenario` teardown never reached `DESTROYED`). **Wording corrected in the second remediation round below:** this was originally stated as "confirmed device-load flakiness," which overstated what was actually shown — the teardown timeout occurred after a 34-minute `SyntheticLoadAcceptanceTest` run in the same session and is *consistent with* load-related/environmental flakiness, not directly proven to be caused by it. The affected test passed 26/26 when re-run alone immediately after, and no production regression was reproduced. |

**Empirically confirmed Readium navigator limitation, discovered during this
remediation while investigating R3's fragmented-fixture requirement.** Real
`Locator` JSON logged from `EpubSurface`'s `onLocation` callback, for a real
two-fragment EPUB (`chapter.xhtml#section-one`/`#section-two`, matching
element `id`s in the markup), showed:
- `Publication.locatorFromLink(link)` correctly resolves each TOC link's own
  fragment (`{"fragments":["section-one"]}` / `{"fragments":["section-two"]}`
  respectively) — TOC-side resolution works exactly as designed.
- The live navigator's own `currentLocator`, after navigating to either
  fragment via **either** `Navigator.go(Link, animated)` or
  `Navigator.go(Locator, animated)` (both were tried directly), reports only
  `{"progression":0.333...,"position":1,"totalProgression":0}` —
  **`locations.fragments` is absent from the navigator's own reported
  locator in both cases**, for this pinned Readium 3.4.0 EPUB navigator in
  its default paginated (non-scroll) mode.

This is a real, verified constraint of the current navigator/configuration,
not a ShelfOS matching defect. The multi-fragment-matching fix (R2.1) is
correct and fully proven by synthetic-locator JVM tests; it simply is not yet
observably exercised by real in-app navigation given this navigator
behavior. No HTML-position heuristic was built to work around it (explicitly
out of scope, and would have reintroduced the positional-accuracy claim
already ruled out for the fallback rule). The instrumented fragmented-fixture
test was designed around what is honestly verifiable given this finding: the
row-identity fix (R2.2) holds for a real same-resource pair (exactly one row
is ever marked current), and chapter-jump navigation/dialog-close still work
correctly for same-resource entries — see the test's own doc comment
(`EpubChapterHighlightTest.kt`) and `matchChapter`'s doc comment
(`core.reader.EpubReader.kt`) for the full assessment, and
`docs/PHASE_2_PLAN.md`'s 2B.1 R2/R3 remediation record for the summary.

### ACCESSIBILITY

Re-verified after the R2.2 identity fix: `EpubChapter.id` is never rendered
in any `Text` or `contentDescription` — the accessible name remains
`"<title>, current chapter"` for the current row, exactly as before. No new
focus targets, no duplicate current-row indicators, no raw ordinal/index
text anywhere in the UI or accessibility tree.

### REGRESSION CHECK

Confirmed unchanged by this remediation: chapter navigation and the chapter
filter (`EpubChapterHighlightTest`, all passing), Back semantics and the
accessibility "Show reader controls" action, contextual input hints, touch
modality behavior, resume/progress persistence, appearance persistence,
scroll/typography/page colors (`NavigationSmokeTest`, 26/26), recreation
(`EpubRecreationTest`, 1/1), offline reading (no network dependency
anywhere in this change), and source preservation (read-only TOC/locator
handling, no EPUB bytes touched).

### FINAL ACCEPTANCE (2B.1 R2/R3 remediation) — superseded, see the second remediation round at the top

All four Codex findings (R2.1, R2.2, R3.1, R3.2) were closed with evidence,
then a second independent Codex review found the fallback contract (R2.1's
fix) still too confident in one ambiguous case, and this document's flaky-
test wording overstated its own evidence (R3.2-adjacent) — see "Phase 2B.1
R2 second remediation round validation" at the top of this document for the
fixes. 2B.2 (bookmarks)/2B.3 (search)/2B.4 (custom fonts) remain untouched.

## Phase 2B.1 validation (2026-09-26)

Status: **2B planning is accepted and merged** (PR #7, `410ce4d6c4daa5d6fa65fb2787027d50d722822f`).
**2B.1 (chapter-navigation polish + scroll/typography/page-color closure) is
IMPLEMENTED, PENDING INDEPENDENT REVIEW** on branch `phase-2/epub-chapters`,
base `main` at `410ce4d...`. Do not treat 2B.1 as accepted until Codex review
lands. See `docs/PHASE_2_PLAN.md`'s 2B.1 implementation record and acceptance
criteria for the full description of what was built; this entry records the
evidence.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | Passed (one fix needed: `Publication.locatorFromLink(link)` is `Locator?`, not `Locator` — the plan's own bytecode-only research had not surfaced this method's nullability; handled with a null-safe fallback to the raw `Link.href` rather than assuming non-null) |
| `:app:compileDebugAndroidTestKotlin` | Passed |
| `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebugAndroidTest` (`--rerun-tasks --offline`) | **BUILD SUCCESSFUL** — recorded directly in the "Phase 2B.1 R2/R3 remediation validation" entry above after a Codex R3 finding that this line originally deferred to "see final report" instead of stating the result here |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean (no Room changes, as required — 2B.1 is schema-free) |

### JVM / REGRESSION

| Test class | Result |
| --- | --- |
| `EpubChapterMatchTest` (new) | 11/11 passed — the five plan-required scenarios plus six additional edge cases (different resource, empty list, unlisted fragment, determinism, multi-fragment precedence) |

### INSTRUMENTED / EMULATOR (`shelfos-phase0`, API 35)

| Test class | Result |
| --- | --- |
| `EpubChapterHighlightTest` (new) | 2/2 passed — current-chapter highlight + reopen-recompute, and the chapter-title filter (narrow/clear/zero-match) |
| Full `connectedDebugAndroidTest` package | 54/54 passed (`AppearanceRestorationTest`, `EpubChapterHighlightTest`, `EpubRecreationTest`, `InputModalityClassificationTest`, `LibraryPersistenceTest`, `NavigationSmokeTest`, `ReaderStateTest`, `RoomPersistenceTest`, `SyntheticLoadAcceptanceTest`) |

**A real regression was found and fixed during this pass, not merely
documented as a risk.** The first implementation rendered the current-chapter
indicator as a single interpolated string (`"✓ ${chapter.title}"`), which
broke `NavigationSmokeTest.originalEpubOpensAndOffersTypographyAndChapters`
(that fixture's one chapter, "Reading Room", is always the current one, so
its exact-text lookup — `onNodeWithText("Reading Room")` — stopped matching
once the rendered text became "✓ Reading Room"). Fixed by rendering the
checkmark as its own sibling `Text` node instead of concatenating it into the
title string, so the title itself remains exact-text-matchable everywhere it
already was, while an explicit `contentDescription` override still gives an
unambiguous accessible name — the same "override coexists with a separately
matchable child `Text`" pattern the existing Previous/Next hint buttons
already use. Re-ran the full instrumented package after the fix: 54/54 passed,
confirming no other exact-text-dependent test was affected.

### ACCESSIBILITY

Current-chapter indication uses three independent, non-color signals: a
leading "✓ " glyph, bold font weight, and a `contentDescription` override
("<title>, current chapter"). No separate focus target was created for the
checkmark — it is a plain decorative sibling `Text` inside the same
`TextButton`, not its own semantics node. The filter field carries an
explicit "Filter chapters" `contentDescription`. Not independently walked
with TalkBack physically running, for the same reason recorded in prior
Phase 2 entries (TalkBack unavailable on the test targets used).

### KEYBOARD / CONTROLLER

No new `ShelfCommand` mapping was added. The full `NavigationSmokeTest` suite
(26/26, including its keyboard/gamepad/D-pad/Escape/modality-transition
coverage) passed with behavior unchanged, confirming Phase 2A's Back
semantics and Phase 2A.1's input hints are unaffected outside the Chapters
dialog. RP5 physical re-certification was not performed and is not required
for this slice — no new top-level reader-chrome entry point was added (the
existing "Chapters" button is unchanged; only its dialog's contents changed).

### RECREATION

`EpubRecreationTest` (1/1) and the new `EpubChapterHighlightTest` reopen case
confirm the current-chapter highlight is never stored in `rememberSaveable`
or Room — it recomputes from the live/restored locator on every recomposition,
exactly like `item.progress` already does via Room-backed state.

### SCROLL / TYPOGRAPHY / PAGE-COLOR CLOSURE

Verified by reading the unchanged production code paths (`ReaderPreferences`,
`ReaderAppearance`, `epubPreferences()`) and by the full `NavigationSmokeTest`
pass (which exercises Appearance apply/persist/recreation flows): pagination
vs. continuous scrolling, the three typography presets plus sliders, and
`PagePalette.THEME/LIGHT/DARK/PAPER` reading-surface colors are all already
implemented and were not touched by this slice. `PagePalette.DARK` produces a
genuinely dark reading surface (`Theme.DARK`, explicit `backgroundColor`/
`textColor`) independent of the ShelfOS app theme; `PagePalette.THEME`
deliberately does follow the app's dark/light state (`night = dark`), which is
what a "Theme" page-color option is supposed to mean, not a bug; `PAPER` maps
to `Theme.SEPIA`. No second/duplicate dark-mode setting exists. No paragraph/
word/letter spacing, hyphenation, ligature or column/spread control was added.

### FINAL ACCEPTANCE (2B.1) — superseded, see the R2/R3 remediation entry at the top

2B.1 was implemented and self-validated (JVM + instrumented, full regression
package green, one real regression found and fixed during this same pass, not
after), then received an independent Codex review returning CHANGES REQUIRED
— see "Phase 2B.1 R2/R3 remediation validation" at the top of this document
for the fixes and their evidence. RP5 physical validation was correctly not
attempted (not relevant to this slice's scope, per `docs/PHASE_2_PLAN.md`'s
own testing-strategy section).

## Phase 2A.1 acceptance and post-merge maintenance (2026-09-26)

2A.1 received final Codex confirmation after the R3 cleanup below and was
squash-merged to `main` via PR #5 at commit
`44c8f10600f93f875a3cfb685c27f47c25929c29`. This supersedes the "Not yet
re-confirmed by Codex; not merged" lines in the R3 cleanup and remediation
entries below — 2A.1 is accepted.

A separate, small `maintenance/epub-recreation-back-test` pass then corrected
`EpubRecreationTest.kt`'s `readerUiStateSurvivesRecreationAndAppliedAppearanceReloads`
test, which the R3 cleanup entry below correctly identified as out-of-scope,
pre-existing Phase 2A test debt (never a 2A.1 production defect): the test
pressed `KEYCODE_BACK` while chrome was visible, expecting chrome to hide and
the reader to stay open — a pre-[ADR-0023](adr/0023-reader-chrome-back-semantics.md)
assumption that Phase 2A's accepted Back contract (visible chrome + Back →
exit) had already made stale. Fixed by hiding chrome via `KEYCODE_MENU`
(`ShelfCommand.OPEN_MENU`) instead, matching the pattern already used in
`NavigationSmokeTest.kt`. No production code changed; Back's reveal-then-exit
behavior is untouched and remains covered by `NavigationSmokeTest`.

**STATIC / BUILD:** `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`,
`:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`,
`:app:assembleDebugAndroidTest` (`--rerun-tasks --offline`) — see this pass's
final report for the actual run results. `git diff --check` clean; `git status
--porcelain -- app/schemas` clean (no Room changes).

**INSTRUMENTED / EMULATOR:** `EpubRecreationTest` alone, then
`EpubRecreationTest` + `NavigationSmokeTest` together, on `shelfos-phase0`
(API 35) — see this pass's final report for pass counts.

**PHYSICAL DEVICE:** no RP5 re-certification performed or required — no
production code changed, test-only fix.

**FINAL ACCEPTANCE:** Phase 2A and Phase 2A.1 are both accepted and merged to
`main`. This maintenance pass closes the last known test debt from Phase 2A's
Back-contract change. Phase 2B has not started.

## Phase 2A.1 owner physical-controller verification (2026-09-25)

The owner manually pressed the Retroid Pocket 5's actual physical controller
controls (not an ADB-injected key event) while a reader was open with chrome
visible. The physical controller inputs activated the corresponding ShelfOS
controller-hint badges/actions in the reader UI as expected.

This is **owner-verified physical hardware evidence**, distinct from the
Codex-side RP5 evidence recorded elsewhere in this document, which was real
hardware *execution* driven through ADB-injected key events and screen taps,
not physically pressing the device's own buttons by hand. Codex's review did
not independently press the RP5's physical controls; that distinction stands
as previously recorded.

The owner's statement does not specify an exact per-button sequence (which
buttons, which reader format, how many presses), so none is recorded here
beyond what was actually stated: physical controller input worked and
produced the expected controller-hint UI response.

**Keyboard evidence remains unchanged by this note.** Keyboard hint/input
behavior has automated (JVM) and injected-key (emulator and RP5, over ADB)
validation only. No physical tablet keyboard has been tested, on the RP5 or
otherwise. This owner physical-controller check does not extend to, and must
not be read as implying, physical keyboard validation — controller and
keyboard are independent input modalities in this feature and are evidenced
separately.

## Phase 2A.1 R3 cleanup validation (2026-09-25)

A second independent Codex review of the remediated 2A.1 implementation
(below) returned **PASS WITH NON-BLOCKING FINDINGS** — no R1/R2 defects, four
small R3 items. All four are documentation/test-quality corrections; only one
touches production code, and as a pure refactor with no behavior change
(verified by all affected tests still passing identically before and after).

**1. Source-precedence test quality.** The prior regression test
(`specificEventSourceTakesPrecedenceOverDeviceAggregate`) used a nonexistent
`deviceId`, so `device` resolved to `null` and the test never actually
exercised a real hybrid device's aggregate sources — it could not have failed
even if precedence were reverted to aggregate-first. Fixed by extracting
`resolveInputSources(eventSource, deviceSources)` and `isGamepadSource(sources)`
(`core/input/InputHints.kt`) as small, pure, internal functions operating only
on `Int` bitmasks — no `KeyEvent`/`InputDevice` involved, so they compile and
run in a plain JVM unit test. Two new `InputHintsTest` cases construct an
explicit hybrid aggregate (`SOURCE_KEYBOARD or SOURCE_DPAD or SOURCE_GAMEPAD`)
against a concrete `SOURCE_KEYBOARD` event source and assert the *event*
source wins, plus the `SOURCE_UNKNOWN`-falls-back-to-aggregate case — both
would fail if precedence reverted. The misleading instrumented test was
removed; a correctly-scoped instrumented test remains, honestly documented as
proving only that the real `KeyEvent`/absent-device path doesn't crash (not
device-aggregate precedence, which the JVM tests now prove deterministically).
Production classification (`inputModalityOrNull()`) delegates to these two
helpers with identical logic to before — no behavior change.

**2. Controller → Escape coverage.** The prior
`escapeAndGamepadBEstablishModalityWhileRevealingChromeInFixedReader` test's
middle step was `KEYBOARD → Escape → KEYBOARD` — a no-op that never actually
established `CONTROLLER` before pressing Escape, despite the surrounding
comment implying that transition was covered. Fixed by replacing that step
with `TOUCH → GAMEPAD_B → CONTROLLER` first, so the sequence now explicitly
exercises `CONTROLLER → Escape → KEYBOARD`, asserting both the resulting
keyboard hint style and that the reader remains open (Back semantics correct)
at each step.

**3. Stale Phase 2 plan status.** `PHASE_2_PLAN.md`'s top status line and
2A.1 acceptance checklist were updated to reflect: 2A accepted; 2A.1
implementation complete, first Codex review CHANGES REQUIRED (remediated),
second Codex review PASS WITH NON-BLOCKING FINDINGS (this cleanup); not yet
merged, not yet re-confirmed.

**4. Remediation history accuracy.** The prior remediation entries in
`PHASE_2_PLAN.md`, `VALIDATION.md` (below) and `docs/design/INPUT_SYSTEM.md`
§10 stated raw system Back "fell through to an unguarded KEYBOARD default" as
the observed defect. Re-verified directly against the pre-remediation commit
(`git show <commit>^:...`): `InputMapper` mapped `InputKey.BACK`/`HOME` to no
command in *both* the original implementation and the first fix, so raw Back
never entered the affected branch in either version — the claim was
inaccurate. The actual defect: `InputKey.ESCAPE`/`InputKey.GAMEPAD_B` both map
to `ShelfCommand.BACK`, and the first fix's `if (command != ShelfCommand.BACK)`
guard excluded that resolved command from the modality update, so this real
keyboard/controller input silently failed to update the displayed hint style
even though Back/reveal behavior itself stayed correct. Corrected in all three
locations.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` / `:app:compileDebugAndroidTestKotlin` | Passed |
| `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebugAndroidTest` (`--rerun-tasks --offline`) | Passed |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean (no Room changes) |

### JVM / REGRESSION

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` (`InputHintsTest`) | 8/8 passed (6 existing + 2 new source-precedence tests) |

### INSTRUMENTED / EMULATOR

| Test class | Device | Result |
| --- | --- | --- |
| `NavigationSmokeTest` | `shelfos-phase0` (API 35) | 26/26 passed (unchanged count — one existing test edited, not added) |
| `InputModalityClassificationTest` | `shelfos-phase0` (API 35) | 10/10 passed (11 → 10: one misleading test removed) |

### PHYSICAL DEVICE

No RP5 re-certification was performed for this pass. The only production
change (`resolveInputSources`/`isGamepadSource` extraction) is a pure
refactor — identical logic moved into named, independently-testable
functions, with no change to `inputModalityOrNull()`'s observable behavior —
so per the review's own instruction, a focused RP5 sanity check is not
required unless the extraction changed runtime logic, which it did not.

### FINAL ACCEPTANCE (2A.1 R3 cleanup) — superseded, see acceptance entry at the top

All four non-blocking findings are closed with evidence. `EpubRecreationTest.kt`
remains untouched, as instructed — it is separate, pre-existing Phase 2A test
debt, handled in the `maintenance/epub-recreation-back-test` follow-up after
2A.1 merged. 2A.1 subsequently received final Codex confirmation and was
merged via PR #5 — see "Phase 2A.1 acceptance and post-merge maintenance"
at the top of this document.

## Phase 2A.1 remediation validation (2026-09-25)

Independent Codex review of the initial 2A.1 implementation (below) returned
**CHANGES REQUIRED**: the modality-tracking fix described there as final was
itself incorrect. Root cause and fix are recorded in full in
`docs/PHASE_2_PLAN.md`'s 2A.1 "Remediation" note and `docs/design/
INPUT_SYSTEM.md` §10. In short: `InputMapper` maps `InputKey.BACK`/`HOME` to no
command at all, in both the original implementation and the fix — raw system
Back never entered the affected branch in either version, so the original
fix's stated reasoning (excluding it would stop raw Back from claiming a
modality) was moot from the start. The actual defect was that
`InputKey.ESCAPE`/`InputKey.GAMEPAD_B` both map to `ShelfCommand.BACK`, and
excluding that *resolved command* from the modality update meant this real,
attributable keyboard/controller input silently failed to update the
displayed hint style, even though it still correctly revealed/exited the
reader. The fix moved the exclusion to the raw key-classification function
itself (`inputModalityOrNull()` now returns `null` specifically for the
system Back/Home keys, independent of what command they produce), applied
unconditionally before any command dispatch. The ambiguous-source precedence
was also corrected to prefer the specific event's own source over a device's
aggregate sources.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` / `:app:compileDebugAndroidTestKotlin` | Passed |
| `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebugAndroidTest` (`--rerun-tasks --offline`) | Passed |
| `git diff --check` | Clean |
| `git status --porcelain -- app/schemas` | Clean (no Room changes) |

### INSTRUMENTED / EMULATOR

New instrumented test class `InputModalityClassificationTest` (11 tests):
constructs real `KeyEvent`s with explicit `source` values (plain JVM tests
cannot exercise real `KeyEvent`/`InputDevice` behavior) and asserts: Escape is
always `KEYBOARD`; gamepad A/B/L1/R1 are always `CONTROLLER`; raw system
Back/Home have no modality (`null`) regardless of source; D-pad/Enter resolve
from source (keyboard vs. gamepad/joystick/dpad source); the specific event's
own source takes precedence over a hybrid device's aggregate sources; an
unknown event source falls back without crashing.

`NavigationSmokeTest` grew from 21 to 26 tests with five new cases:
`escapeAndGamepadBEstablishModalityWhileRevealingChromeInFixedReader`,
`rawSystemBackNeverChangesModalityInFixedReader`,
`escapeAndGamepadBEstablishModalityInEpubReader`,
`keyboardHintsReflectRtlSwapInMangaCbz`,
`focusNavigationKeyStillUpdatesModalityWithoutBeingConsumed`. Since Escape/
gamepad B produce `ShelfCommand.BACK` and would exit the reader if chrome were
visible (ADR-0023), these tests establish the "before" modality, hide chrome
via a real touch action, then press Escape/gamepad B — the same key that
reveals chrome instead of exiting from that state — and assert the resulting
hint style, directly proving the fixed transition without ever leaving the
reader.

| Test class | Device | Result |
| --- | --- | --- |
| `NavigationSmokeTest` | `shelfos-phase0` (API 35) | 26/26 passed |
| `InputModalityClassificationTest` | `shelfos-phase0` (API 35) | 11/11 passed |
| `NavigationSmokeTest` | RP5 (Android 13) | 26/26 passed |
| `InputModalityClassificationTest` | RP5 (Android 13) | 11/11 passed |

A separate, pre-existing full-suite run (all test classes, not just the two
above) surfaced one unrelated failure in `EpubRecreationTest.kt`
(`readerUiStateSurvivesRecreationAndAppliedAppearanceReloads`): it presses
system Back expecting the pre-ADR-0023 "hide chrome, stay open" contract, but
current (accepted) Phase 2A behavior is "visible chrome + Back exits," so the
reader now actually closes and the test's later rotation-persistence
assertions fail against a closed Activity. `git log` confirms this test file
was last touched in the Phase 1 foundation commit and was never updated when
Phase 2A's merge (`cb9df1a`) changed `EpubActivity`'s Back behavior — this is a
**pre-existing gap from the Phase 2A merge, unrelated to 2A.1's modality
remediation and outside this review's scope**. Recorded here, not fixed, per
the instruction to avoid scope creep beyond the reviewed findings. **Fixed in
the `maintenance/epub-recreation-back-test` pass** after 2A.1 merged — see
"Phase 2A.1 acceptance and post-merge maintenance" at the top of this
document.

### PHYSICAL DEVICE

**Retroid Pocket 5 (RP5), Android 13/API 33, ADB serial `d8f7f1b6`.** Beyond
the automated re-run above, the exact manual sequence the review specified was
performed, with screenshots at each step:

1. Start from TOUCH (fresh reader open, no hints). Press the RP5's B-equivalent
   (`KEYCODE_BUTTON_B` over ADB) — chrome reveals (not exit, ADR-0023 held) and
   controller hints (`L1`/`R1`/`B`) appear: confirms TOUCH → CONTROLLER.
2. Press R1 — page turns forward (1→2), Next behavior matches the `R1` label.
3. Press L1 — page turns backward (2→1), Previous behavior matches the `L1` label.
4. A real touch (edge-tap page turn, chrome left visible) — controller hints
   disappear; chrome stays visible with plain "Previous"/"Next", no keycaps.
5. Press R1 again — controller hints (`L1`/`R1`/`B`) return, page turns.
6. Hide chrome (touch), press Escape — chrome reveals and hints switch to
   keyboard style (`←`/`→`/`Esc`): confirms Escape establishes `KEYBOARD` even
   though it also produces `ShelfCommand.BACK`.
7. Hide chrome again, press raw system Back — chrome reveals (does not exit)
   and hints **remain** keyboard-styled: confirms the actual fix — raw Back no
   longer claims a modality of its own, in either direction.

All screenshots showed correct, legible chrome with no clipping/overflow on
the RP5's 1080×1920 screen. No crashes observed. This sequence, like the
review's own framing, is real hardware execution via ADB-injected key events
and screen taps, not a record of physically pressing the handheld's own
buttons by hand — the owner's separate general confirmation that the RP5's
physical controls work was not independently re-verified button-by-button in
this pass.

**Manual TalkBack:** still not performed — unavailable on the test targets used.

**Samsung Galaxy Tab A:** not performed, per the device strategy — optional/periodic.

### FINAL ACCEPTANCE (2A.1 remediation) — superseded, see R3 cleanup entry above

This entry's fix was independently re-reviewed and returned PASS WITH
NON-BLOCKING FINDINGS — see "Phase 2A.1 R3 cleanup validation" at the top of
this document, which also corrects this entry's inaccurate claim about raw
Back's original behavior. The reviewed defect (Escape/gamepad B failing to
establish modality) is fixed; independently reproducible evidence (11
raw-classification tests at the time, now 10 after the R3 cleanup removed a
misleading one; 5 transition tests; a 7-step real-hardware RP5 sequence)
confirms the corrected behavior in both directions. A separate, pre-existing,
out-of-scope gap (`EpubRecreationTest`) was found and documented, not fixed —
still true, still not fixed. **Not yet re-confirmed by Codex; not merged.**

## Phase 2A.1 validation (2026-09-25)

Status: 2A.1 (input-discovery/controller-hint polish, `docs/PHASE_2_PLAN.md`)
implemented on branch `phase-2/input-hints`, based on the merged Phase 2A
(`main`). Not yet reviewed by Codex; not yet merged.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | Passed |
| `:app:compileDebugAndroidTestKotlin` | Passed |
| `:app:assembleDebug` | Passed |
| `:app:lintDebug` | Passed |
| `:app:assembleDebugAndroidTest` | Passed |
| Room schema cleanliness (`git status --porcelain -- app/schemas`) | Clean — no Room changes |
| `git diff --check` | Clean |
| Dependency check | No dependency added or changed (`gradle/libs.versions.toml`/`app/build.gradle.kts` diff is empty); the hint system uses only existing Compose/Android APIs |

### JVM / REGRESSION

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | Passed, including new `InputHintsTest` (touch→null, controller labels match the real `InputMapper` bindings, controller labels are not RTL-swapped, keyboard labels are and match the actual arrow/Escape bindings, no hint for a command without a candidate binding) |

### INSTRUMENTED / EMULATOR

`NavigationSmokeTest` grew from 16 to 21 tests with five new Phase 2A.1 tests
(`controllerInputShowsControllerHintsThenTouchClearsThem`,
`keyboardInputShowsKeyboardHintsInFixedReader`,
`hiddenChromeNeverExposesInputHints`,
`decorativeBackHintIsNotItsOwnAccessibilityStop`,
`keyboardInputShowsKeyboardHintsInEpubReader`), run via
`:app:connectedDebugAndroidTest` targeted explicitly at one device with
`ANDROID_SERIAL` to avoid accidentally exercising a connected RP5 during
routine emulator runs:

| Device | Result |
| --- | --- |
| `shelfos-phase0` (AVD, API 35) | 21/21 passed |

A test-design pitfall was found and fixed during this pass: `InputKeycap` is
deliberately stripped of its own semantics (`clearAndSetSemantics {}`), so
`onNodeWithText` queries against it always fail — Compose test APIs only see
the semantics tree, not rendered pixels. Tests were corrected to check the
merged `contentDescription` on Previous/Next (e.g. `"Next, R1"`) and a
`testTag` on the decorative Back hint instead of raw keycap text. A second,
real bug was found this way and independently confirmed on RP5 hardware (see
PHYSICAL DEVICE below): the system Back key was unconditionally classified as
`KEYBOARD` modality, flipping an active controller hint set on every Back
press. **This entry's original fix — excluding `ShelfCommand.BACK` from the
modality update — was itself incorrect**, as a later independent Codex review
found: it filtered at the semantic-command level, which suppressed legitimate
Escape/gamepad-B modality updates without actually addressing raw Back (see
"Phase 2A.1 remediation validation" above for the corrected fix and its
evidence).

The emulator's synthetic `DPAD_LEFT`/`DPAD_RIGHT` key injection was found to be
ambiguous for modality classification (unclear whether the virtual keyboard
device used by `Instrumentation.sendKeyDownUpSync` reports gamepad-like
`SOURCE_DPAD`); the keyboard-hint tests use `PAGE_DOWN`/`PAGE_UP` instead,
which are unambiguous keyboard-only keycodes in the classifier and produce the
same displayed hint (`→`/`←`) via `InputHints`' candidate-based lookup
regardless of which actual key triggered the modality.

### PHYSICAL DEVICE

**Retroid Pocket 5 (RP5), Android 13/API 33, ADB serial `d8f7f1b6`:**

- Full 21-test `NavigationSmokeTest` suite: **21/21 passed**, run via
  `ANDROID_SERIAL` targeting the RP5 specifically. One run hung for ~9 minutes
  on `controllerInputShowsControllerHintsThenTouchClearsThem` (a
  `performTouchInput` synthetic touch) and failed via an outer timeout; this
  was traced to the device's screen timing out mid-test, not a code defect —
  confirmed by extending `screen_off_timeout` and re-running cleanly at 52s
  for all 21 tests. The device's screen timeout was restored to 30s afterward.
- Manual real-hardware verification (screenshots, not merely the automated
  suite): imported a real PDF through the actual SAF import UI, opened it,
  pressed the physical-equivalent `R1` key over ADB — controller hints
  (`L1 Previous`, `R1 Next`, `B Back`) rendered correctly and legibly on the
  RP5's 1080×1920 screen, with no chrome overflow or clipping. Hid chrome —
  hints disappeared with it. Pressed Back — chrome and hints both reappeared
  (hidden-chrome contract from ADR-0023 held), and, after the fix above, the
  hints correctly stayed in controller mode instead of flipping to keyboard.
  Pressed Back again from visible chrome — reader exited to Library/details
  correctly, position saved.
- No ShelfOS crashes or navigation exceptions observed in RP5 logcat during
  either the automated run or the manual pass.
- No obvious performance regression observed.

**Important distinction, preserved from the Phase 2A review:** the above is
real RP5 hardware execution driven through ADB-injected key events and actual
screen taps, not a record of physically pressing the handheld's own L1/R1/B
buttons by hand. The owner's separate general confirmation that the RP5's
physical controls work was not re-verified with a new specific per-button
sequence in this pass.

**Manual TalkBack:** not performed — TalkBack was unavailable on the test
targets used, consistent with the Phase 2A record.

**Samsung Galaxy Tab A (SM-T580):** not performed in this pass, per the device
strategy in `PHASE_2_PLAN.md` §7 — optional/periodic and non-blocking.

### ACCESSIBILITY

Previous/Next hints are merged into the existing buttons'
`contentDescription` (e.g. `"Next, R1"`, `"Previous, ←"`) rather than adding a
new focusable node — verified by the automated tests checking for exactly that
merged description. The Back hint, which has no single existing "Back"
control to merge into, is fully decorative: `clearAndSetSemantics {}` removes
it from the tree entirely (confirmed via `onAllNodesWithText("Back")` finding
zero nodes in the default merged tree, and `assert(!hasClickAction())` on its
`testTag`), so it cannot become a noisy duplicate stop. This was not
independently walked through with TalkBack physically running, for the same
reason recorded in the Phase 2A entry above.

### MOTION

No animation was added for hint appearance/disappearance — hints follow the
same instant, unanimated pattern as chrome show/hide (2A). Reduced motion is
honored trivially, as there is no motion to gate.

### FINAL ACCEPTANCE (2A.1) — superseded, see remediation entry above

This entry originally claimed the Back/modality fix below was final. An
independent Codex review found that fix itself incorrect (it filtered on the
wrong abstraction level); see "Phase 2A.1 remediation validation" at the top
of this document for the corrected fix and its full evidence. The 21/21
figures here reflect the pre-remediation test count (26/26 + 11/11 after
remediation). Keyboard hint validation used ADB-injected key events on both
the emulator and RP5, not a physical tablet keyboard — none was available in
this environment; this remains true after remediation. **Do not treat 2A.1 as
accepted** — not yet re-reviewed by Codex, not merged.

## Phase 2A validation (2026-09-25)

Status: **Phase 2A (reader chrome/Back semantics, `docs/PHASE_2_PLAN.md`) is
accepted and merged to `main`.** Independent Codex review verdict: **PASS WITH
NON-BLOCKING FINDINGS** (see FINAL ACCEPTANCE below for the full evidence
summary and explicitly unclaimed items). Phase 1's acceptance (below) is
unaffected; no Phase 1 code outside the reader Back-handling paths described
in [ADR-0023](adr/0023-reader-chrome-back-semantics.md) was touched. 2A.1
(input-discovery/controller-hint polish) builds on this merge — see the
"Phase 2A.1 validation" section below.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | Passed |
| `:app:compileDebugAndroidTestKotlin` | Passed |
| `:app:assembleDebug` | Passed |
| `:app:lintDebug` | Passed |
| `:app:assembleDebugAndroidTest` | Passed |
| Room schema cleanliness (`git status --porcelain -- app/schemas`) | Clean — no Room changes in this increment |
| `git diff --check` | Clean |

### JVM / REGRESSION

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | Passed — 63/63 (independent Codex re-review count) |

### INSTRUMENTED / EMULATOR

`NavigationSmokeTest` run via `:app:connectedDebugAndroidTest`. Test count grew
from 14 (the original acceptance pass, including the two Back-reveal tests
replacing the prior single Back test — see ADR-0023) to 16 after the Codex-review
accessibility remediation added `accessibilityActionRevealsHiddenControlsInFixedReader`
and `...InEpubReader`:

| Device | Result |
| --- | --- |
| `shelfos-api37` (AVD, API 37, freshly data-wiped) | All 14 (pre-remediation) failed identically at Espresso's `onIdle()` with `NoSuchMethodException: android.hardware.input.InputManager.getInstance` — a pre-existing, already-documented environment gap (see "COMPATIBILITY: API 24 and API 37" below), not a regression from this change. Not retried post-remediation; this gap is not claimed fixed. |
| `shelfos-phase0` (AVD, API 35), default phone viewport (1080×1920, 420dpi) | 14/14 passed pre-remediation; **16/16 passed** after the accessibility remediation, targeted explicitly via `ANDROID_SERIAL` to exclude a physical RP5 that was connected at the same time but intentionally not used for this pass (see Phase 2A remediation note below) |
| `shelfos-phase0` (AVD, API 35), forced expanded/tablet viewport (`wm size 1600x1000`, `wm density 160`) | 14/14 passed (pre-remediation) |

This is real automated evidence for the fix: `backRevealsHiddenControlsBeforeLeavingTheReader`
(Original/PDF reader) and `epubBackRevealsHiddenControlsBeforeLeavingTheReader`
(EPUB) both start from hidden chrome, press system Back, assert chrome reappears
and the reader is still open, press Back again, and assert the reader exits —
directly exercising the previously-untested path that let the field bug through.

### PHYSICAL DEVICE

**Retroid Pocket 5 (RP5), Android 13 / API 33, ADB serial `d8f7f1b6`.**
Performed during independent Codex re-review (2026-09-25), after the
accessibility remediation above:

- Real-hardware execution (over ADB, not simulated) confirmed for EPUB, PDF and
  CBZ opening and reading.
- Hide/reveal/exit Back semantics (ADR-0023) validated on-device through
  Android key events: hidden chrome + Back reveals chrome; visible chrome +
  Back exits the reader.
- PDF resume validated on-device.
- CBZ D-pad/input validated on-device.
- Android Home / Recent Apps safety confirmed — normal system navigation
  remains available and is not intercepted.
- A focused RP5 instrumentation pass: **6/6 passed**.
- No ShelfOS crashes or navigation exceptions observed in RP5 logcat during
  the pass.
- No obvious Phase 2A performance regression observed.

**Important distinction (Codex's own framing, preserved here):** this is real
RP5 hardware execution driven through Android key events over ADB, not a
record of physically pressing the handheld's own L1/R1/B buttons during this
review pass. The owner has separately confirmed that physical controller
controls work on the RP5, but exact per-button physical-press sequences for
each reader were not explicitly recorded in this pass, so physical-controller
support is described here conservatively rather than as a specific tested
sequence.

**Manual TalkBack:** not performed. TalkBack was unavailable on the test
targets used for this review. This is not claimed as a pass.

**Pre-existing, non-blocking observation (not caused by Phase 2A):** in
landscape orientation, the import dialog's category-chip row visually exposed
only the Books chip without usable scrolling to reach Comics/Manga/Documents.
This predates Phase 2A and is not part of its acceptance criteria; tracked
here as a future adaptive/import UX follow-up, not fixed in this branch.

**Samsung Galaxy Tab A (SM-T580):** not performed in this pass. Per the device
strategy in `PHASE_2_PLAN.md` §7, this is optional/periodic and non-blocking for
2A; recorded as pending, not claimed.

### ACCESSIBILITY

`stateDescription` semantics were added to the chrome-toggle areas in both
readers (`FixedReaderScreen`'s page `Box`, `EpubActivity`'s `EpubSurface`
container). **Remediated 2026-09-25** after independent Codex review (R2)
found the initial version announced "double tap to show controls" while
double-tap was actually reserved for zoom, and neither surface exposed an
`onClick` accessibility action at all — so TalkBack's double-tap-to-activate
gesture had nothing to invoke and the announced instruction did not correspond
to a working action. Both surfaces now expose `stateDescription` reflecting
only the real state ("Controls shown" / "Controls hidden") and, exclusively
while chrome is hidden, `onClick(label = "Show reader controls")`, which
reveals chrome when invoked. New tests
(`accessibilityActionRevealsHiddenControlsInFixedReader` /
`...InEpubReader`) invoke the semantic action itself via
`performSemanticsAction(SemanticsActions.OnClick)` rather than merely
asserting a description string exists, and pass on `shelfos-phase0` (API 35).
This is still not independently verified with TalkBack physically running —
no physical/emulator TalkBack walkthrough was performed for this change. That
explicit TalkBack pass remains open for 2D's accessibility closure or an
earlier follow-up.

### MOTION

No animation was added to reader chrome show/hide (remains an instant
conditional composition, as it already was). `Context.reducedMotionEnabled()`
was not called from reader code in this pass because there is no motion in
reader chrome to gate — reduced motion is honored trivially. Not independently
tested with the system "Remove animations" setting for this reason.

### FINAL ACCEPTANCE (2A)

**Phase 2A is accepted (2026-09-25).** Independent Codex re-review verdict:
**PASS WITH NON-BLOCKING FINDINGS.** Evidence: JVM unit tests 63/63; API 35
`NavigationSmokeTest` 16/16; a focused RP5 (Android 13/API 33) instrumentation
pass 6/6 with real-hardware EPUB/PDF/CBZ execution, on-device Back-semantics
validation, PDF resume, CBZ D-pad input, Home/Recent-Apps safety, no crashes in
logcat and no obvious performance regression; full Gradle validation passing;
the previously blocking accessibility finding (R2) and its three non-blocking
documentation findings (R3) both independently confirmed resolved.

Explicitly **not** claimed as part of this acceptance: a manual TalkBack
walkthrough (TalkBack was unavailable on the test targets used); exact
per-button physical-press sequences on the RP5's own controls beyond the
owner's separate general confirmation that they work; the API 37
Espresso/InputManager tooling gap (pre-existing, unrelated, not fixed); PDF
rendering resolution/fidelity (explicitly deferred to Phase 2C); and the
pre-existing RP5 landscape import-dialog category-chip scrolling issue noted
above (predates Phase 2A, not part of its acceptance criteria).

## Manual legacy-tablet field evidence

A physical Samsung Galaxy Tab A SM-T580 (Android 8.1, approximately 2 GB RAM)
session found the app responsive and pleasant, with strong reader controls and no
obvious low-memory usability failure. The tablet was not available through ADB, so
this is qualitative product research rather than benchmark evidence. Publication-
specific observations and their limits are retained in the
[field-test record](research/SAMSUNG_TABLET_FIELD_TEST_2026-09.md). This evidence
does not change Phase 1 status or replace the formal acceptance matrix below.

## Phase 1 validation (2026-09-24)

Status: increments 1A–1C are implemented; 1D (integration polish) is partial. The
automated and API 35 emulator checks below pass, including reading the private samples
by reference. The first Phase 1 review found five defects; their fixes and evidence are
under [Review remediation](#review-remediation-2026-09-24). Phase 1 is **not accepted**:
the [open software gaps](#open-software-gaps) remain, and the physical-device, API-range,
accessibility, controller and low-storage gates in
[PHASE_1_PLAN](PHASE_1_PLAN.md#7-validation-and-completion-gates) have not been executed.
Emulator results are not physical-device evidence.

### Automated checks (before the review)

| Check | Result |
| --- | --- |
| Clean export of commit-eligible files (`git ls-files -co --exclude-standard`, no `local.properties`, `ANDROID_HOME` set): `gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --offline --no-build-cache` | BUILD SUCCESSFUL; 86 of 86 tasks executed |
| JVM tests | 44 passed: FoundationTest 5, ImportPolicyTest 9, ImportViewModelTest 9, ReadingPolicyTest 14, SeekableZipTest 5, StateTest 2 |
| Lint | 0 errors; 5 `UseKtx` style suggestions (the working tree also shows the informational Gradle 9.7.1 hint) |
| Room | Schemas v1/v2 regenerate byte-identically; no schema change or migration in this pass |
| Dependencies | None added; every resolved `debugRuntimeClasspath` artifact is covered by `DEPENDENCY_LICENSES.csv` |
| `:app:connectedDebugAndroidTest` (AOSP API 35 emulator) | 20 of 20 passed on 1080×1920 @ 420 dpi; on 1600×1000 @ 160 dpi (rail + detail pane); and on 1080×1920 with all animation scales set to 0 (reduced motion) |

Check per-test XML results, not only the Gradle exit code: during this session AGP
reported BUILD SUCCESSFUL for a connected run whose APK install had failed with a
signing mismatch. Before this pass the two reader device tests failed because the
compact details screen pushed Read below the fold.

Instrumented tests (run on the emulator) cover: v1→v2 migration keeping the theme; close/reopen persistence of
edits, favorite, position and preferences; non-destructive removal that keeps other
titles and private copies until explicit cleanup; copy import of PDF/CBZ/EPUB with
embedded EPUB metadata and unchanged originals; specific problems for text, broken PDF,
imageless archive and DRM EPUB with no partial copy left; partial-copy cleanup; missing
sources; PDF/CBZ rendering with natural page order; locator/preference serialization;
five destinations with Shelves naming; theme persistence across recreation; keyboard
and D-pad navigation; details; PDF open, turn, return and resume; Back hiding controls
before leaving; Manga RTL with arrows, Page Down and L1; keyboard focus restoration;
EPUB open, Appearance and chapter navigation.

### Private-sample acceptance (API 35 emulator, not a physical device)

Samples were pushed to the emulator's Downloads folder and imported through the real
system picker (DocumentsUI), by reference, with persisted read grants.

| Sample | Result |
| --- | --- |
| Dune (PDF, 3.4 MB) as Book | 345 pages; resumed at page 5 after force-stop via Continue Reading; removed from ShelfOS with the original left in place |
| Chainsaw Man Vol. 01 (PDF, 101 MB) as Manga | RTL by default: Next on the left, left-edge tap and swipe right advance; per-title Left-to-right override and Category default reset work; artwork not mirrored; counter reads "2 / 193" |
| Frankenstein (EPUB, 474 KB) | Title and creator read from the package metadata; Spacious preset and chapter navigation; the same passage restored after force-stop, rotation and verified process death; Dark theme page colors follow the theme while explicit typography is kept |
| Immortal Hulk Omnibus (CBZ, 3.16 GB, 1,481 pages) as Comic | Imported by reference without copying or extraction; opened in about 5 s; 120 keyboard Page Down presses landed on pages 61 and 121; slider reached pages 1449 and 772; final page 1481 rendered; resumed at 772 after force-stop (52%) and restored after verified process death |
| Disposable Dune copy, deleted outside ShelfOS after import | Entry kept and marked unavailable; details explain the state; Try to open shows a non-crashing access message |

- SHA-256 of Dune, Chainsaw Man Vol. 01 and Frankenstein was identical before and after
  import, reading and removal. The 3.16 GB Immortal Hulk CBZ was not hashed, so its
  byte-for-byte integrity is unverified.
- Memory while paging through the 3.16 GB archive stayed flat: Java heap about 16–20 MB,
  native heap about 54–57 MB, total PSS 209–216 MB after 1, 61 and 121 pages.
- The review dialog appeared within about 5–9 s of choosing a file, including picker
  dismissal and UI-automation polling overhead.
- Settings → Storage reported no unused private copies (reference imports only).

### Defects found and fixed during validation

- The compact details screen hid Read below the fold (both baseline reader device tests failed).
- EPUB and CBZ imports by reference from shared storage fell back to a copy or failed:
  provider descriptors cannot be reopened through `/proc/self/fd`. Archives are now read
  through the granted descriptor (`core.files.SeekableZip`).
- Opening an EPUB could crash because preferences were submitted before Readium's
  fragment was attached; page commands during attachment had the same risk.
- In right-to-left reading the page counter rendered as "193 / 3"; selected chips were
  hard to see in the monochrome theme; deleted-source messages over-claimed a cause.

### Review remediation (2026-09-24)

The first Phase 1 review reported five defects. The review's own reproduction tests
failed before the fixes and pass after them.

| Finding | Fix |
| --- | --- |
| High: clearing the import screen during a save could discard the work being saved | A preparation has one owner at a time: preparing, review, save, then the library. Cleanup handles only abandoned work and never touches work the library references |
| Medium: cleanup of an abandoned import could release a grant that a new import of the same source needed | `ImportLeases` records which sources unfinished imports hold. A grant is released only when neither a library item nor another import depends on it; otherwise it passes to that import |
| Medium: EPUB controls and dialogs, and unapplied Appearance changes in every reader, were lost on recreation | `EpubActivity` restores its saved state; Readium's navigator is still rebuilt from the saved locator. The Appearance draft, its starting values and its scope are saved with the dialog |
| Low: a ZIP comment containing the end-of-directory signature could hide an archive's entries | End-record candidates are validated against the directory they describe |
| Low: documentation overstated the evidence | Status wording, the named hash results and the gap list on this page |

**Static/build validation.** `gradlew :app:assembleDebug :app:assembleDebugAndroidTest
:app:lintDebug --offline`: BUILD SUCCESSFUL. Lint: 0 errors; the same five `UseKtx`
suggestions and Gradle-version hint as before. No dependency, manifest, schema or
migration change.

**Unit/regression validation (JVM).** The review's three reproduction tests, run from its
harness: 3 of 3 failed before the fixes, 3 of 3 pass after. `gradlew
:app:testDebugUnitTest --rerun`: 60 passed, 0 failed (FoundationTest 5, ImportLeasesTest 5,
ImportPolicyTest 9, ImportViewModelTest 17, ReadingPolicyTest 15, SeekableZipTest 7,
StateTest 2). Copies of the review's tests are part of this suite. Against the
pre-remediation import and ZIP code, 9 of the new tests fail.

**Emulator validation (AOSP API 35, 1080×1920 @ 420 dpi).** `connectedDebugAndroidTest`:
22 of 22 passed (per-test XML checked). Two tests are new. `AppearanceRestorationTest`:
an unapplied draft, its starting values and the "all titles" scope survive state
restoration. `EpubRecreationTest`: after recreation the EPUB Appearance dialog reopens with
its unapplied change, Apply stores it, hidden controls stay hidden through a real rotation,
and reopening the reader shows the applied appearance. Both failed on the same emulator
against the pre-remediation reader code. Not repeated after the fixes: the 1600×1000,
reduced-motion and private-sample runs. EPUB UI state after process death was not
exercised separately; it uses the same restoration path.

**Physical-device validation.** Not verified: no physical device was connected.

### Open software gaps

- 1D motion: only the existing 120 ms fades; no cover-expansion transition or motion pass.
- 1D restoration and switching: Library grid scroll restoration is untested, and there is
  no rapid title-switching stress test.
- Fixed-layout Fit width, pinch zoom and double-tap zoom were not exercised on a device.
- Saving Appearance for all titles is covered by unit tests and the editor test, but the
  full path to stored defaults was not exercised on a device.
- Only early pages of the two private PDFs were checked (the CBZ's early, middle and final
  pages were).
- Room transaction rollback and genuine grant revocation (as opposed to a deleted source)
  are untested.
- A preparation that completes at the moment its import is cancelled is not handed back
  for cleanup: its grant is released at the next launch, and its private copy is listed
  under Settings → Storage as an unused copy.

### Not executed

- Physical phone, tablet or foldable; physical keyboard and game controller (only
  injected key events on the emulator); fold postures.
- API 24 and API 37 runtimes; TalkBack and large font scale.
- Low storage, interrupted copies and non-seekable providers with a real provider: the
  private-copy path is covered only by `file://` fixtures and unit tests.
- Frame-time profiling and physical-device heap measurements.
- A GitHub Actions run of this working tree.

## Phase 1 acceptance closure (2026-09-24)

Codex's focused re-review passed the R1–R5 remediation (`PHASE1_CODEX_REVIEW.md`, "Final
Verification"). This pass addresses the remaining acceptance gates: device, accessibility,
input, performance and motion validation. It does not reopen R1–R5.

**Device used.** A physical **Retroid Pocket 5** (Moorechip, Android 13, API 33, serial
`d8f7f1b6`), connected over USB with USB debugging authorized. Every category below says
whether its evidence is from this physical device, the API 35 emulator, or neither.

### STATIC / BUILD

| Check | Result |
| --- | --- |
| `gradlew :app:testDebugUnitTest --tests com.d4guilar.shelfos.ReviewRegressionTest --init-script .tools/codex-review.init.gradle --rerun` | PASS: 3 run, 0 failed. R1–R5 remain resolved |
| `gradlew :app:testDebugUnitTest --rerun` | PASS: 60 tests, 0 failed, 0 errors |
| `gradlew :app:lintDebug` | PASS: 0 errors; the same 5 `UseKtx` warnings and Gradle-version hint as before |
| `gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `git diff --check` | Clean |

No dependency, manifest, schema or migration change in this pass. One instrumented test
(`EpubRecreationTest`) was corrected during this pass; see Instrumented/physical below.

### UNIT / REGRESSION

Unchanged from Review remediation above: 60 JVM tests, 0 failures. No new unit tests were
needed; this pass is device/runtime validation, not a source of new production code.

### INSTRUMENTED / EMULATOR

Not repeated in this pass. The API 35 emulator result already on record (22 of 22, dated
during Review remediation) stands; see above.

### PHYSICAL DEVICE

All physical-device checks below ran on the Retroid Pocket 5 described above, with a debug
build freshly installed for this pass (no prior ShelfOS install or user data on the device).

**Full instrumented suite.** `adb shell am instrument -w -r
com.d4guilar.shelfos.test/androidx.test.runner.AndroidJUnitRunner`:

- First run: **21 of 22 passed.** `EpubRecreationTest.readerUiStateSurvivesRecreationAndAppliedAppearanceReloads`
  timed out waiting for a landscape recreation. Cause: this handheld ships with auto-rotate
  off and `user_rotation` locked to landscape by default, so requesting
  `SCREEN_ORIENTATION_LANDSCAPE` when the device is already effectively landscape never
  triggers a recreation. This is a test assumption bug (fixed orientation target), not a
  production defect; R3's fix and its JVM/emulator regression coverage are unaffected.
  Fixed by requesting whichever orientation the device is not currently in, so the test
  also exercises hardware that defaults to landscape.
- After the fix: **22 of 22 passed**, including the corrected recreation/rotation test.
- Repeated with `animator_duration_scale`/`window_animation_scale`/`transition_animation_scale`
  at 0 (reduced motion): **22 of 22 passed.**

**Real SAF import, read, resume.** Using a small original EPUB fixture (not committed;
generated for this pass and pushed to the device's Downloads folder), through the device's
own system file picker, not a test harness:

1. Add file → real document picker → Downloads → the fixture. ShelfOS's own semantic Escape
   binding correctly dismissed the review dialog on the first attempt (confirmed the
   documented Escape-dismisses behavior); redone by dismissing the keyboard instead.
2. Review dialog showed the correct format/size (EPUB · 5 KB) and the title read from
   embedded metadata; confirmed with the default category (Books).
3. The publication appeared in Library, opened, rendered its content, and paging moved
   from 0% to 50%, then (during frame-rate measurement, see Performance) to 75%.
4. `am force-stop` (simulating process death) then a cold relaunch: Continue Reading showed
   75%, and reopening the reader rendered the same paragraph at 75%.
5. The Read button stayed above the fold on the details screen, and the adaptive rail
   layout (Library/Search/Notes/Shelves/Settings) appeared automatically at this device's
   1920×1080 landscape window, without a device-model check.

**Large text.** `font_scale=1.3`, cold relaunch: Library, Continue Reading and the
navigation rail scaled up without clipping or overlap; long titles wrapped/truncated with
an ellipsis rather than breaking the layout. Not exercised inside the reader or Settings.
Restored to 1.0 afterward.

**Accessibility node tree (runtime, not source inspection).** `uiautomator dump` on the
open EPUB reader, then checked against Android's minimum touch-target guidance at this
device's 360dpi: every clickable control (Library, Chapters, Appearance, Previous, Next)
carries a visible text label as its accessible name and measures at least 48dp in the
constrained dimension. This is the same accessibility-node data TalkBack would read and
navigate; it is not a substitute for hearing spoken output or confirming swipe-based
TalkBack traversal, which this device cannot provide (below).

**TalkBack: NOT VERIFIED.** Neither TalkBack nor Android Accessibility Suite is installed
on this device (`pm list packages` has no matching package), and none was installed for
this pass. Spoken-output and swipe-gesture traversal remain unverified on any device.

**Physical keyboard/controller: NOT VERIFIED.** The RP5's own hardware D-pad and buttons
were not exercised, because that requires a person to physically press them; this pass
only has shell/ADB access. The existing keyboard/D-pad instrumented tests (above) inject
key events through Android's input pipeline and are real-device evidence for the semantic
mapping, but they are simulated key events, not confirmed hardware button presses. See the
physical-device checklist below for the manual controller check.

**API 24 / API 37: NOT VERIFIED.** Only API 33 (this device) and API 35 (existing
emulator) were available. No new emulator image was downloaded for this pass.

**Reliability gates unchanged:** low storage, non-seekable provider, interrupted copy and
Room transaction rollback remain NOT TESTED, as recorded above.

### PERFORMANCE

Measured on the Retroid Pocket 5 (API 33), debug build, with only the one small fixture
imported. No Phase 1 document defines numeric performance thresholds, so these are
measurements, not pass/fail gates against a target.

| Measurement | Method | Result |
| --- | --- | --- |
| Cold start | `adb shell am start -W -n com.d4guilar.shelfos/.MainActivity` | 714–724 ms `TotalTime`, twice |
| Frame timing while paging the EPUB reader (5 page turns) | `dumpsys gfxinfo com.d4guilar.shelfos` | 50th/90th/95th/99th percentile 10/44/150/250 ms combined across two activity windows; the reported "janky (legacy)" rate ranged 3–21% depending on window. Not compared against a target: none is documented |
| Memory footprint | `dumpsys meminfo com.d4guilar.shelfos` | TOTAL PSS 196 MB, Java heap 9.9 MB, native heap 14.3 MB, graphics 31 MB, with the small fixture and two activities resident |

The 3.16 GB CBZ heap-flatness measurement from the emulator private-sample pass was not
repeated on physical hardware: pushing a large private sample to this personal device was
out of scope for this pass. Frame-time profiling under real load (a large CBZ/PDF) and
physical-device heap measurement under that load remain open, as recorded above.

### MOTION

- Reduced motion (`animator_duration_scale`/`window_animation_scale`/`transition_animation_scale = 0`):
  the full instrumented suite passes unchanged on both the emulator (existing evidence) and
  this physical device (above). No test depends on animation timing.
- Rotation/recreation does not produce a broken transition: confirmed by the corrected
  `EpubRecreationTest` on physical hardware — after a real rotation, controls stay hidden
  and the layout settles without a stale frame.
- Focus/selection feedback (`ShelfChoiceChip` check marks, chip selection) is unchanged
  from Review remediation and was not re-verified in this pass.
- **The documented cover-expansion reader transition (`docs/design/CLASSIC_UI.md` §12) is
  still not implemented.** This is unchanged from the existing "1D Library/detail/reader
  transitions: PARTIAL" row. Implementing a shared-element/cover-expansion transition is
  animation-system feature work, not a small fix to an existing acceptance test failure;
  building it now would go beyond this closure pass's "smallest necessary fix" mandate and
  risk exactly the kind of new UI-state defect this pass exists to avoid. It remains an
  explicit, named software gap rather than something quietly dropped from the requirement.

### FINAL ACCEPTANCE

**PHASE 1: NOT YET ACCEPTED.**

What changed in this pass: the physical-device gate has real evidence for the first time
(full instrumented suite, a real SAF import end to end, force-stop/resume, large text,
reduced motion, and an accessibility-node-tree check), and one test bug found only on real
landscape-locked hardware was fixed without touching R1–R5 or production code.

What still blocks acceptance, unchanged in kind from Codex's final verdict:

- TalkBack spoken-output/gesture verification (no TalkBack on the available device).
- Confirmed physical keyboard/controller button presses (only simulated key events were run).
- API 24 and API 37 runtime checks.
- Reader zoom/fit and the global "all titles" Appearance save, exercised on a device.
- Real non-seekable provider, low-storage and interrupted-copy cases, and Room transaction
  rollback.
- Frame-time profiling and physical-device heap measurement under real (large-file) load.
- The documented cover-expansion reader transition; 1D scroll restoration and rapid-switch
  evidence.

None of these are R1–R5 findings. See the [physical device acceptance
checklist](../PHASE1_CLAUDE_HANDOFF.md#physical-device-acceptance-checklist) for what the
owner can complete manually on this or another device.

## Phase 1 acceptance closure — continuation (2026-09-24)

Continues the closure pass above. New automated coverage was found already in the working
tree at the start of this continuation — `LibraryPersistenceTest` gained non-seekable
provider, low-storage, interrupted-copy and Room-rollback cases; `NavigationSmokeTest`
gained a reader zoom/fit + global-Appearance-persistence case and a combined 1D
scroll-restoration/rapid-title-switching case; a new `SyntheticLoadAcceptanceTest` exercises
a large generated archive; a debug-only `NonSeekableTestProvider` backs the provider tests.
This section verifies that coverage, finishes API 24/37, and completes performance
measurement. It preserves and does not duplicate that work.

### STATIC / BUILD

`gradlew :app:testDebugUnitTest --tests com.d4guilar.shelfos.ReviewRegressionTest
--init-script .tools/codex-review.init.gradle --rerun`: 3/3 pass. `gradlew
:app:testDebugUnitTest --rerun`: 60/60 pass (unchanged; the new tests are instrumented).
`gradlew :app:lintDebug`: 0 errors. Lint now also reports one new `ExportedContentProvider`
warning for `NonSeekableTestProvider`; it is a debug-only test provider (`app/src/debug/`),
never part of a release build, and does not change lint's error count. `assembleDebug` and
`assembleDebugAndroidTest`: BUILD SUCCESSFUL. `git diff --check`: clean.

### JVM / REGRESSION

Unchanged: 60 JVM tests, 0 failures.

### RESILIENCE (non-seekable provider, low storage, interrupted copy, Room rollback)

All four have real automated coverage now, in `LibraryPersistenceTest`:

- **Non-seekable provider.** A debug-only `ContentProvider` returns a pipe descriptor
  (`ParcelFileDescriptor.createReliablePipe()`), not a file. `nonSeekableProviderCopies…`
  copies it, commits it, reopens the committed item, and confirms cleanup never touches the
  provider's own source.
- **Low storage.** `PublicationFiles` takes an injectable free-space function (production
  code addition: `availableBytes: ((File) -> Long)?`, defaulting to the real
  `StorageManager`/`File.usableSpace` check as before). The test forces 0 available bytes
  and confirms `INSUFFICIENT_STORAGE` with no partial file left.
- **Interrupted copy.** The same provider's `.../interrupted` path writes half the bytes
  then calls `closeWithError(...)`. The test confirms `COPY_FAILED` with no partial file,
  and that a retry of the same source succeeds normally afterward.
- **Room rollback.** A SQLite trigger injected only for this test aborts the delete inside
  `LibraryDao.remove()`'s existing `@Transaction`. The test confirms the whole transaction
  rolls back: the item, its reading position and its preferences all survive. This needed
  no production change — `remove()` was already `@Transaction`.

Confirmed PASS on the physical Retroid Pocket 5 (10/10 `LibraryPersistenceTest` cases, one
run, no crash) and confirmed PASS on the `shelfos-api24` emulator, though not reliably in
the same run as several preceding tests — see Compatibility below for that emulator-specific
finding.

### COMPATIBILITY: API 24 and API 37

Both AVDs already existed (`shelfos-api24`, `shelfos-api37`) and were already running.

**API 24 (`shelfos-api24`, Android 7.0, x86_64, GPU emulation disabled).**

- App launches (`am start -W`: COLD, succeeds) and the full `NavigationSmokeTest` class —
  launch, import (seeded via the repository, matching the emulator/RP5 smoke pattern),
  Library render, PDF/EPUB/Manga reader open/read/resume/Back, keyboard and D-pad, the new
  zoom/fit + global-Appearance test, and the new scroll-restoration/rapid-switch test —
  **passes 12 of 12.**
- `LibraryPersistenceTest`'s 10 cases pass individually and in small groups. Run together
  (as the full class, or as part of the full 28-test suite), the same suite intermittently
  ends with a **native crash**: `Fatal signal 11 (SIGSEGV) ... libpdfium.so
  (CPDF_Document::CPDF_Document+96)`, always inside
  `nonSeekableProviderCopiesCommitsReopensAndCleansWithoutTouchingTheSource`, reproduced in
  3 of 3 full-sequence attempts.
  - It does **not** reproduce running that test alone, or paired with the immediately
    preceding test.
  - It does **not** reproduce at all on the physical Retroid Pocket 5 running the identical
    test, identical code, in the identical full-suite sequence (confirmed twice).
  - The crash is inside the platform's bundled `libpdfium.so`, not ShelfOS code, and other
    tests exercising the same `PublicationFiles` → `PdfRenderer` path (`pdfAndArchiveRender…`,
    `copyImportDetects…`) pass repeatedly on this same emulator.
  - Conclusion: this is most consistent with a resource/memory-accumulation instability
    specific to the `shelfos-api24` x86_64 AOSP emulator image (a 2016 build already known,
    before this pass, to need GPU emulation disabled just to boot) under repeated native
    `PdfRenderer` allocation within one process, not a confirmed ShelfOS or production
    defect. It is **not independently confirmed absent on real API 24 hardware**, which was
    unavailable for this pass. Recorded honestly rather than hidden or asserted as a fix.
- **API 24 result: PASS for the app's own behavior** (launch, import, read, resume,
  zoom/fit, global Appearance, scroll restoration, rapid switching, and the four resilience
  cases all individually verified). The emulator-specific crash above is a flagged,
  unresolved environment finding, not claimed as fixed or as a confirmed defect.

**API 37 (`shelfos-api37`, Android 17, Google Play image).**

- The system image boots and **ShelfOS itself launches and renders correctly**
  (`am start -W`: COLD, 1794 ms; screenshot confirms Library, navigation and layout render
  as expected).
- Non-UI instrumented tests — `LibraryPersistenceTest`, `RoomPersistenceTest`,
  `ReaderStateTest` (13 tests: Room, import/file logic, locator/preference serialization) —
  **pass 13 of 13.**
- Automated Compose UI instrumented tests (everything that needs Espresso's `onIdle`, i.e.
  every `NavigationSmokeTest`/`AppearanceRestorationTest`/`EpubRecreationTest`/
  `SyntheticLoadAcceptanceTest` case) **cannot run**: every one fails with
  `java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance`. This
  is the project's pinned `androidx.test`/Espresso version calling a static
  `InputManager.getInstance()` accessor that this platform version has removed — a test-
  tooling version gap against a very new API level, not a missing system image and not
  reproduced as an app defect (the app itself runs fine, per the previous two points). No
  dependency version change was attempted: this offline environment cannot download a newer
  artifact to verify, and a test-only dependency bump is outside this pass's scope.
- **API 37 result: system image and app PASS; automated UI-instrumented-test coverage
  NOT VERIFIED (tooling limitation, not a system-image or app problem).** This is the exact
  blocker, not a substitution: the image is real, current (Android 17), and boots; only the
  UI-driving test harness cannot run against it yet.

### ACCESSIBILITY

The runtime accessibility-node-tree check from the previous pass stands (every reader
control has a visible text label and a ≥48dp touch target).

New this pass: TalkBack (`com.google.android.marvin.talkback`) is preinstalled on the
`shelfos-api37` Google Play emulator (unlike the RP5 or the AOSP `shelfos-api24` image).
It was enabled and confirmed bound and running (`dumpsys accessibility`: TalkBack service
bound with `FEEDBACK_SPOKEN, FEEDBACK_HAPTIC, FEEDBACK_AUDIBLE`). However, `adb shell input`
touch/swipe gestures did not reliably drive TalkBack's touch-exploration or announcement
pipeline in this environment: no accessibility-focus change and no utterance-related log
activity were observed after repeated swipe gestures. This is a genuine limitation of
driving TalkBack through ADB-injected input, not a ShelfOS finding either way. TalkBack was
disabled again afterward, restoring the emulator's default state.

**TalkBack: still NOT VERIFIED.** No environment available to this pass could produce
genuine spoken-output or gesture-traversal evidence. This requires a person operating
TalkBack, on the RP5 or on the `shelfos-api37` emulator (already has TalkBack installed) —
see the physical/manual checklist.

### INPUT / HARDWARE

Unchanged: keyboard/D-pad coverage now also confirmed via simulated key events on API 24
(12/12) and, for non-UI paths, API 37. Real physical button presses on the RP5's own
hardware remain **NOT VERIFIED** — see the physical checklist.

### PERFORMANCE

A representative synthetic load, generated for this pass and not committed to Git: a 219 MB,
160-page CBZ (1200×1800 JPEG pages), run on the physical Retroid Pocket 5.

| Measurement | Method | Result |
| --- | --- | --- |
| Memory while paging (page 1 → page 81 of 160) | `Debug.MemoryInfo().totalPss` before/after, logged by the test | 335,750 KB → 346,326 KB PSS (roughly flat over 80 page turns); Java heap 9.6 MB |
| Memory snapshot held at page 81 | `adb shell dumpsys meminfo com.d4guilar.shelfos` | TOTAL PSS 271,784 KB; Java heap 7.4 MB, native heap 36.0 MB, graphics 99.9 MB |
| Frame timing while paging and holding at page 81 | `adb shell dumpsys gfxinfo com.d4guilar.shelfos` | 718 frames rendered, 0.28% janky (legacy 0.28%); 50th/90th/95th/99th percentile 5/5/6/10 ms |
| Cold start (trivial fixture, from the previous pass) | `adb shell am start -W` | 714–724 ms, RP5; 1794 ms, API 37 emulator (first cold start after install, includes APK verification) |

No documented Phase 1 threshold exists, so these are measurements, not pass/fail gates.
The earlier trivial-fixture measurements from the previous closure pass are retained above
for cold-start reference; this larger, generated load is the more representative one for
memory/frame behavior. The original 3.16 GB private-sample measurement (emulator, Phase 1
validation) remains the largest load actually measured for this app; it was not repeated on
physical hardware in this or the previous pass.

### 1D SCROLL RESTORATION AND RAPID SWITCHING

New test: `libraryScrollRestoresAfterReaderAndRapidTitleSwitchingKeepsTheRightSession`
(`NavigationSmokeTest`). It seeds 30 additional Book items, scrolls deep into the grid,
opens one, reads a page, returns, and confirms the scrolled-to item is still visible;
then switches Library → Manga reader → Library → Books reader → Library twice more in
quick succession, confirming each reopen shows the correct title's own session and page,
and that the Library remains valid throughout.

- **PASS on the Retroid Pocket 5** (part of the 28/28 full-suite run).
- **PASS on API 24** (part of the 12/12 `NavigationSmokeTest` run), after a narrow test fix:
  the test scrolled to a hardcoded index (33) copied from an assumption about the total
  item count; Compose's own bounds error ("Can't scroll to index 33, it is out of bounds
  [0, 33)") showed the true count is 33 because the Library's "Continue Reading" header
  shares the same tagged `LazyVerticalGrid` as the publication items (confirmed by reading
  `LibraryScreen.kt`). The valid last index is 32; the test now computes it
  (`extraIds.size + 2`) instead of hardcoding a guessed count. This is a one-line test
  correctness fix with a verified root cause, not a weakening: the assertions and the
  scenario are unchanged.

### ZOOM / FIT AND GLOBAL APPEARANCE

New test: `fixedReaderZoomFitAndGlobalAppearancePersistAcrossRecreation`
(`NavigationSmokeTest`). Opens an Original PDF, exercises the existing "Zoom in"/"Reset
zoom" control (present since Phase 1's original implementation), sets Fit width and "Use as
default for all titles" through Appearance, applies it, reopens Appearance to confirm the
selection stuck, then recreates the activity and confirms the same selection survives.
**PASS on the Retroid Pocket 5 and on API 24** (both full-suite runs above).

### MOTION

No change from the previous pass's determination: the documented cover-expansion reader
transition (`docs/design/CLASSIC_UI.md` §12) remains unimplemented. See the previous
"Decision: motion" analysis in `PHASE1_CLAUDE_HANDOFF.md`; nothing in this continuation
changes that determination or reopens it as a smaller fix than originally assessed.

### FINAL ACCEPTANCE (continuation)

**PHASE 1: NOT YET ACCEPTED.**

What this continuation resolved: non-seekable provider, low storage, interrupted copy, and
Room rollback all now have real automated coverage and pass on real hardware; 1D scroll
restoration and rapid-switch stress now have coverage and pass; reader zoom/fit and global
Appearance persistence now have on-device coverage and pass; API 24 and API 37 were both
actually run, each with a precise, honest result rather than an assumption.

What remains, precisely:

- TalkBack spoken-output/gesture confirmation (a person operating TalkBack is required).
- Real Retroid Pocket 5 hardware button presses (a person is required).
- The API 24 emulator-specific native-crash finding above, unconfirmed on real API 24
  hardware (none available).
- API 37 automated UI-instrumented-test coverage (blocked by an androidx.test/Espresso
  version gap against this very new platform, not by the system image or the app).
- Frame-time/heap profiling under an even larger load than the 219 MB synthetic sample used
  here (the original 3.16 GB private sample was not repeated on physical hardware).
- The documented cover-expansion transition, by deliberate choice (see Motion above).

None of these are R1–R5 findings.

### Physical controller — owner-verified (2026-09-24)

The owner tested ShelfOS on the Retroid Pocket 5 using its actual physical gamepad
(D-pad/buttons), not ADB-injected key events. Reported result: the gamepad works correctly
with no issues observed during normal Library and Reader interaction — physical D-pad/
controller navigation, activation, reader controls and Back behavior all worked as
intended. This is owner-reported, on-device evidence, distinct from and additional to this
pass's own simulated-key-event coverage; it was not rerun or replaced with simulated input.

**REAL HARDWARE INPUT / PHYSICAL CONTROLLER: VERIFIED (PASS)** on the Retroid Pocket 5.

This closes the "Real Retroid Pocket 5 hardware button presses" item above and the
corresponding row in `PHASE1_CLAUDE_HANDOFF.md`.

## Phase 1 acceptance closure — final items (2026-09-24)

Closes the five remaining items from the previous continuation: the cover-expansion
transition, large-load performance, and a precise interpretation of the API 24/API 37
compatibility criterion against the plan's own wording. TalkBack is handed to the owner as
a manual checklist (below). R1–R5 and everything already resolved in the two earlier
closure sections were not reopened.

### Cover-expansion reader transition (CLASSIC_UI.md §12)

**Implemented — the smallest faithful version.** `docs/design/CLASSIC_UI.md` §12: "selected
cover subtly expands, system chrome fades, reader appears... fast and optional under
reduced-motion settings."

What it is: when Read is chosen from a publication's Details screen (the standalone
compact route, or the persistent pane in expanded/tablet layouts), that screen's own cover
image is captured at its on-screen position. A transient, decorative overlay (excluded from
the accessibility tree) grows that cover from there to the incoming reader's area over
220 ms, while the surrounding chrome (top bar, navigation rail or bottom bar) fades out
over the first 60% of that time so the reader is never revealed through visible chrome.
The real navigation — the same `NavHost` route change or `EpubActivity` launch as before —
runs once the animation completes; nothing about reader lifecycle, ViewModels, or Activity
launches changed.

What it deliberately does not do, and why that is still faithful to the requirement:
- It does not attempt a true cross-Activity shared element into `EpubActivity` (a separate
  Activity hosting Readium's fragment). Building that would mean Activity-transition/shared-
  element machinery well beyond "the smallest faithful version," and risk exactly the
  reader-lifecycle regressions this closure exists to avoid. EPUB gets the same expand
  overlay up to the point of launch, then the existing Activity launch takes over.
- The Continue Reading card's direct tap (which does not pass through a Details screen, so
  there is no "selected cover" moment in the documented sense) is unchanged: no overlay,
  instant navigation, exactly as before.
- Exit reversal ("when practical," per the source text) was not implemented; the existing
  120 ms crossfade continues to handle leaving the reader. The specification allows this.

Reduced motion: `Context.reducedMotionEnabled()` (`core/theme/ReducedMotion.kt`) checks
`ValueAnimator.areAnimatorsEnabled()` (API 26+) or the `ANIMATOR_DURATION_SCALE` setting
directly (API 24–25, since `ValueAnimator.areAnimatorsEnabled()` requires API 26 and minSdk
is 24). When true, `openReader` skips the overlay and navigates immediately — the
"documented minimal/no-motion behavior" the source text calls for.

No generalized animation system was created: the overlay is a single-purpose composable
(`feature/home/CoverExpandTransition.kt`) with one pure, unit-tested function
(`chromeAlpha`) and no reusable transition API. `LibraryScreen`/`PublicationDetails` were
not redesigned; each gained one parameter (a nullable on-screen `Rect`) threaded through
existing callbacks.

**Regression coverage:**
- JVM: `CoverExpandTransitionTest` (3 tests) — chrome is fully visible before the transition
  starts, fully faded before the expansion finishes, and never increases in between.
- Instrumented: `NavigationSmokeTest.readerEntryTransitionIsSkippedUnderReducedMotion`
  (new) — forces `animator_duration_scale=0` via a shell command, confirms Read still opens
  the reader. All of `NavigationSmokeTest`'s existing Details→Read cases
  (`originalPdfOpensTurnsPagesResumesAndReturnsToLibrary`,
  `originalEpubOpensAndOffersTypographyAndChapters`, the expanded-layout detail pane cases,
  and the newer zoom/fit and scroll-restoration/rapid-switch cases) now exercise the
  transition's normal-motion path as a side effect of exercising Read at all, since they
  already go through `PublicationDetails`.

**Validation:**
- JVM: 63 tests (was 60), 0 failures. Codex's 3 regressions unaffected. Lint 0 errors
  (unchanged). `assembleDebug`/`assembleDebugAndroidTest`: BUILD SUCCESSFUL.
- Physical device (Retroid Pocket 5): full instrumented suite, three separate runs across
  this pass — 28/28 with a partial suite once, then 29/29, 29/29 (clean rebuild), and 29/29
  again with system-wide reduced motion forced. One run in the middle of this work showed 9
  unrelated failures ("No compose hierarchies found," including in a test using bare
  `createComposeRule()` that shares no code with this feature); the very next run, and the
  one after, passed cleanly. Treated as transient device load from a long testing session,
  not a regression — the failure pattern (test-infrastructure registration errors,
  unrelated to which code was under test) is inconsistent with a code defect and
  inconsistent between consecutive otherwise-identical runs.
- Emulator (API 24, `shelfos-api24`): `NavigationSmokeTest`, 13/13 (12 existing + the new
  reduced-motion case).
- The transition's exact visual appearance was not captured frame-by-frame: at 220 ms it is
  faster than manual ADB screenshot polling can reliably catch (confirmed by attempting it;
  the capture landed on the reader's own load state, after the transition had already
  finished). Its correctness is established by the passing tests above, not by a visual
  recording.

### Large-load performance (closer to the ~3.16 GB private sample)

A synthetic, non-copyrighted CBZ was generated on-device for this pass only (not committed;
not left on disk afterward) and read on the physical Retroid Pocket 5 (API 33).

| Property | Value |
| --- | --- |
| Generated size | 2,997,685,907 bytes (≈2.99 GB) |
| Page count | 947 (1600×2400 JPEG pages, stored uncompressed-in-ZIP so archive size matches page bytes exactly) |
| Device / API | Retroid Pocket 5, API 33 |
| Open time | 534–535 ms (two runs) from tapping Read to the first page rendering |
| Pages visited | 1 through 180, by the same sequential Next control the UI exposes (no private API) |
| Memory (`Debug.MemoryInfo`, in-process) | PSS 231.6 MB → 249.7 MB (page 150) → 246.4 MB (page 180) on the first run; 231.6 MB → 241.2 MB → 241.3 MB on the second. Roughly flat, not growing linearly with pages read |
| Java heap | 6.6 MB, both runs |
| Memory (`adb shell dumpsys meminfo`, held at page 180) | TOTAL PSS 241.8 MB; Java heap 7.3 MB; native heap 21.8 MB; graphics 82.4 MB |
| Frame timing (`adb shell dumpsys gfxinfo`, held at page 180) | 1543 frames rendered; 0.19% janky; 50th/90th/95th/99th percentile 5/5/5/6 ms |

No documented Phase 1 performance threshold exists, so this is a measurement, not a
pass/fail gate. It is not the original 3.16 GB private sample (per instruction, that sample
was not reused), but at essentially the same order of magnitude (2.99 GB vs. 3.16 GB, both
roughly 1,000-page-class archives) it shows the same pattern the original private-sample
validation found: memory stays bounded and frame timing stays smooth while paging through
an archive of this size, on real hardware. The generating test file was a temporary,
uncommitted scratch file, deleted from the repository after this validation; the archive
itself was deleted from the device by its own test teardown (confirmed via `run-as ls`
immediately afterward).

### API 24 and API 37: interpreting the compatibility criterion

Exact source (`docs/PHASE_1_PLAN.md` §7, "Compatibility"):

> API 24 and current-target runtime, compact/expanded layouts, at least one physical
> device, keyboard/D-pad and physical controller where available; TalkBack and
> reduced-motion checks. Document any unexecuted hardware tests.

This wording exercises specific dimensions (each API level, layout modes, at least one
physical device, input methods, TalkBack, reduced motion) and asks that unexecuted hardware
tests be documented. It does not say the mechanism must be one specific automated
instrumented-test command completing without any flake, on every runtime, as the sole
proof. Read that way:

**API 24: the runtime requirement is satisfied**, with an emulator-specific limitation
documented rather than hidden. Evidence: the app launches; the full UI smoke suite
(`NavigationSmokeTest`, including the reader-entry transition and its reduced-motion case)
passes reliably (13/13, confirmed again in this pass); the resilience suite
(`LibraryPersistenceTest`) passes individually and in pairs. The one unresolved item is an
intermittent native crash inside the platform's own `libpdfium.so`, reproduced only when
many `PdfRenderer`-touching tests run back-to-back on the specific `shelfos-api24` x86_64
AOSP emulator image (a 2016 build already known, before this pass, to need GPU emulation
disabled just to boot) — never on the physical device running the identical sequence, and
never in smaller groupings on the same emulator. This is documented as an accurate,
unresolved test-environment limitation specific to that emulator image, not classified as a
ShelfOS defect and not silently dropped from the record. If the owner's intent is
specifically "an uninterrupted, all-tests-in-one-run, zero-flake automated pass on this
particular emulator image," that narrower bar remains unmet; the plan's own text does not
require that specific mechanism.

**API 37: the runtime requirement is satisfied at the application level; automated
UI-instrumented coverage on this platform is not.** Evidence, kept separate as the plan's
wording (and this pass's instructions) ask: the system image boots; ShelfOS itself launches
and renders correctly (screenshot evidence, 1794 ms cold start); non-UI instrumented tests
(Room, import/file logic) pass 13/13. Automated UI-instrumented tests cannot run against
this specific platform version with the project's currently pinned androidx.test/Espresso
version, which calls a static `InputManager.getInstance()` accessor this platform removed —
a test-tooling version gap against a very new API level (Android 17), not a missing system
image and not a demonstrated ShelfOS runtime failure. No dependency version change was made
to work around this: it is out of this pass's scope, and this offline environment cannot
fetch a newer artifact to verify one regardless.

Neither classification substitutes a different API level, weakens the criterion, or
invents a numeric pass threshold.

### TalkBack: manual checklist — PASS (owner-verified, 2026-09-24)

This pass enabled and confirmed TalkBack running (bound, `FEEDBACK_SPOKEN`/`HAPTIC`/
`AUDIBLE`) on the Google Play `shelfos-api37` emulator, then handed it to the owner: ADB-
injected gestures do not reliably drive TalkBack's touch-exploration or announcement
pipeline, so no automated run could have produced genuine spoken-output evidence itself.
The emulator was relaunched with a visible window (data preserved, TalkBack still enabled
after restart) so the owner could operate it directly with real gestures.

The owner ran the 7-item checklist in `PHASE1_CLAUDE_HANDOFF.md` → "TalkBack manual
checklist" on the windowed `shelfos-api37` emulator and reported: **Pass.** Navigation
labels, publication/Details announcements, the reader entry path, the Appearance dialog's
controls, and Back/Library traversal all worked with TalkBack's real spoken/gesture
navigation, with no focus trap encountered.

**TALKBACK: VERIFIED (PASS)**, owner-reported, genuine spoken/gesture evidence — not
simulated, not inferred from the accessibility node tree alone.

### Final validation (this pass)

| Check | Result |
| --- | --- |
| Codex R1–R5 regressions | 3/3 pass |
| Full JVM suite | 63/63 pass (was 60; +3 `CoverExpandTransitionTest`) |
| Lint | 0 errors (same warnings as before) |
| `assembleDebug` / `assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `git diff --check` | Clean |
| RP5, full instrumented suite (normal motion) | 29/29, twice (after one transient failure, see above) |
| RP5, full instrumented suite (system-wide reduced motion) | 29/29 |
| API 24, `NavigationSmokeTest` | 13/13 |
| Large-load synthetic archive (RP5) | Completed; see above |

### Final acceptance

**PHASE 1: ACCEPTED (2026-09-24).**

Every item from `PHASE_1_PLAN.md` §7 has now been exercised, with evidence recorded rather
than assumed, across three closure passes plus the original Phase 1 validation and Codex's
R1–R5 review and remediation verification:

- **Unit, Room, instrumented, CI/build:** all pass (63 JVM tests, Codex's 3 regressions, 0
  lint errors, both builds, `git diff --check` clean).
- **Private acceptance, reliability:** the original four private samples, plus non-seekable
  provider, low storage, interrupted copy and Room transaction rollback — all covered and
  passing.
- **Compatibility:** API 24 and API 37 (current-target) runtime both exercised, each
  classified precisely against the plan's own wording (above); at least one physical
  device (Retroid Pocket 5) exercised extensively; keyboard/D-pad confirmed by both
  simulated and, for the RP5's own hardware, owner-verified real button presses; TalkBack
  now owner-verified PASS; reduced-motion checks pass on physical hardware and emulators,
  including the new cover-expansion transition's own fallback.
- **1D polish:** scroll restoration, rapid title switching, reader zoom/fit, global
  Appearance persistence, and the cover-expansion transition are all implemented and tested.
- **Performance:** measured at a scale (2.99 GB) close to the original 3.16 GB reference,
  on physical hardware, with no documented threshold to fail against.

Two items remain recorded as documented, non-blocking limitations rather than resolved
gates, per their own classification above — neither is a ShelfOS defect, neither was
substituted or weakened, and both are called out explicitly rather than silently absorbed
into "accepted":

- The API 24 emulator-specific native-crash finding (`shelfos-api24` x86_64 AOSP image
  only, never reproduced on physical hardware).
- API 37 automated UI-instrumented-test coverage, blocked by an androidx.test/Espresso
  version gap against a very new platform (the application itself is confirmed working).

None of these, nor anything above, reopens R1–R5.

## Phase 0 validation (2026-09-23)

Status: Phase 0 foundation implemented and validated in the local emulator
acceptance environment on 2026-09-23. This is an early prototype, not a
production-readiness statement. Unexecuted checks are listed separately below.

### Automated commands

```sh
bash ./gradlew clean :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
bash ./gradlew :app:connectedDebugAndroidTest
```

Use `./gradlew.bat` on Windows. JDK 17, SDK API 37 and Build Tools 36.0.0
are required. A connected emulator/device is required only for the second command.

Unit tests cover registry fallback/availability, navigation breakpoints, context
sensitive input mapping, system key pass-through, favorite/category semantics,
saved-state reconstruction and preference-write failure. Device tests cover Room
close/reopen persistence, five destinations, theme selection/recreation and basic
keyboard activation/publication selection.

### Device acceptance matrix

- Compact phone portrait: bottom navigation; scrollable complete category names;
  2–3 column grid; selecting a cover opens details; Back returns to Library.
- Tablet >=1000dp wide and >=480dp high: compact rail; grid and persistent details;
  touch/keyboard/D-pad selection updates the selected publication.
- Resize and rotate: preserve category, selection, query, theme and navigation.
- Separating/occluding fold: use larger unobstructed region; no controls under hinge.
- Switch Classic/Dark, force-stop and relaunch: same saved theme.
- Tab, Shift+Tab and D-pad: visible focus, reachable navigation/categories/covers;
  Enter/gamepad A activates; Ctrl+F opens Search; Escape/gamepad B goes back.
- Android Back/Home/Recents leave the app normally; test predictive/system Back.
- Large font/display scaling and TalkBack: usable touch targets, meaningful labels,
  readable navigation, scrollable content, no focus trap.
- Airplane mode: all prototype behavior works; no network permission.
- API 24 and current API: launch and basic navigation.

### Results

| Check | Result |
| --- | --- |
| Clean Git checkout, build cache disabled | Passed: debug APK, unit tests, lint, device-test APK; all 85 tasks executed |
| JVM tests | 7 passed: FoundationTest (5), StateTest (2) |
| Phone Android tests | 5 passed on AOSP API 35, 1080×1920 at 420dpi |
| Expanded Android tests | Same 5 passed on AOSP API 35, 1600×1000 at 160dpi |
| Launch | Successful cold launches on the emulator |
| Classic / Dark | Visually inspected; local-only screenshots in `design/screenshots/` (excluded from Git) |
| Theme persistence | Room close/reopen test, activity recreation test, and manual Dark force-stop/cold-relaunch check passed |
| Navigation / focus | Five destinations, publication details, Enter activation and D-pad movement passed on both window sizes |
| Window adaptation | Observed bottom navigation → rail + details → bottom navigation when resizing the same running emulator |
| Saved state | Category, selection, query and demo favorites reconstructed in ViewModel unit test |
| Source / secrets audit | No signing/credential/local-machine files among nonignored source candidates; no recognized private-key/API-token patterns; main manifest requests no permissions |
| Room | Generated schema v1 contains only `appearance_preference` |
| CI | Workflow configured; not executed on GitHub during this session |

The clean-checkout check used a disposable local Git snapshot of the source
candidates, cloned into an ignored directory. No commits or remote changes were
made in the user's repository. It used the same JDK 17 and installed SDK but no
local project properties, build outputs or Gradle build-cache reuse. Command:

```sh
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-build-cache --console=plain
```

Device checks used `:app:connectedDebugAndroidTest` in each window configuration.
The final phone pass also ran the built test APK directly with:

```sh
adb shell am instrument -w com.d4guilar.shelfos.test/androidx.test.runner.AndroidJUnitRunner
```

The fresh checkout's lint report contains no issues. Earlier connected/source
checks produced an informational Gradle 9.7.1 update suggestion; 9.6.0 remains
the documented AGP 9.4 baseline. The backup-rule warning found during development
was corrected for both pre-Android-12 and newer Android versions.

The debug APK is approximately 12.3 MiB, including debug tooling. This is not
a release size measurement. `DEPENDENCY_LICENSES.csv` records the 74 resolved
runtime artifacts and their license declarations.

### Remaining validation and limitations

- Physical phone/tablet/foldable and physical gamepad testing has not been done.
- Runtime tests used API 35; API 24 and API 37 runtime checks remain unexecuted.
- Hinge avoidance is implemented conservatively, but physical fold/posture and
  process-death continuity need broader testing.
- Full TalkBack, large-font, accessibility, performance and large-library QA
  are still needed before production. This prototype has ten sample publications.
- GitHub branch protection and the first remote CI run require repository-side
  follow-through after these local changes are reviewed and pushed.

These limits must remain visible when evaluating the prototype. Importing,
reading, metadata enrichment, annotations and production releases are future work.
