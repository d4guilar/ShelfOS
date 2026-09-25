# ShelfOS

**Early development / prototype — not production-ready.**

ShelfOS is an open-source, local-first Android personal reading system for
Books, Comics, Manga, and Documents you own. Core use is accountless and ad-free.
Favorites is a cross-category view. ShelfOS is a normal app, not a launcher,
bookstore, subscription catalog, or cloud service.

Its direction is offline-complete and online-enhanced: local reading remains the
core, while optional background services may later improve metadata and covers.

> The interface is monochrome. The library is the color.

## Current status

Phase 1 is **accepted** (2026-09-24): single-file import by reference (or an
explicit private copy), a persisted library with Continue Reading, Original PDF/CBZ reading
with right-to-left Manga, Readium EPUB typography, and a subtle reader-entry cover
transition. It passes its automated, emulator and physical-device checks, including the
fixes for its first review and a full acceptance-closure pass; see
[validation](docs/VALIDATION.md) for two documented, non-blocking environment limitations
(an emulator-specific API 24 test flake and an API 37 UI-test tooling gap, neither a
ShelfOS defect). This is not a release.

Global destinations are Library, Search, Notes, Shelves and Settings. Notes and
Shelves are placeholders; their functionality is not implemented. See the [Phase 1 plan](docs/PHASE_1_PLAN.md)
and [roadmap](docs/ROADMAP.md) for current gaps and completion gates.

ShelfOS aims to support whole local libraries: bulk/folder import, connected
Library Sources, Series with virtual omnibus reading, personal Shelves, and
Adapted/Original PDF presentation. Those capabilities are accepted future direction,
not current functionality. Original publications remain untouched; online metadata
must never gate local reading. No metadata APIs, OCR, billing or release pipeline
is claimed by this prototype.

## Build

Install JDK 17 and an Android SDK with API 37 (`platforms;android-37.0`) and
Build Tools 36.0.0. Set `ANDROID_HOME` or use an ignored `local.properties`
containing `sdk.dir`. Android Studio must support AGP 9.4.

```sh
git clone https://github.com/d4guilar/ShelfOS.git
cd ShelfOS
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Windows use `./gradlew.bat` instead of `bash ./gradlew`.
The checked-in wrapper downloads checksum-verified Gradle 9.6.0. The first build
requires network access for tools/dependencies; the app itself works offline.
Minimum Android version: API 24. Application ID: `com.d4guilar.shelfos`.

With an emulator or device connected:

```sh
bash ./gradlew :app:installDebug
adb shell am start -n com.d4guilar.shelfos/.MainActivity
bash ./gradlew :app:connectedDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Structure and validation

One `app` module uses `core`, `data`, `domain` and `feature` package boundaries.
Screen state flows from ViewModels into Compose; source access and reader adapters
sit behind ShelfOS-owned boundaries. The accepted architecture separates Sources,
publications, Categories, Series, Shelves and Reader Modes.

See [validation status and acceptance checklist](docs/VALIDATION.md),
[roadmap](docs/ROADMAP.md), [architecture](docs/ARCHITECTURE.md), and
[Phase 0 decisions](docs/adr/0016-phase-zero-foundation.md).
GitHub Actions is configured for builds, unit tests and lint; device tests run
separately. Historical Phase 0 validation passed a clean-checkout build, seven JVM tests and
five Android tests on both phone and expanded API 35 emulator layouts. Physical
devices, fold postures, API 24/37 runtimes and broader accessibility QA remain.
Phase 1 emulator and private-sample results are recorded separately in the validation document.

## Product and design

- [Product specification](docs/PRODUCT.md)
- [Classic Library reference](docs/design/CLASSIC_LIBRARY_REFERENCE.md)
- [Visual identity](docs/design/VISUAL_IDENTITY.md)
- [Design system](docs/design/DESIGN_SYSTEM.md) and [themes](docs/design/THEMES.md)
- [Input system](docs/design/INPUT_SYSTEM.md)
- [Agent instructions](AGENTS.md)

Written specifications define the public design contract. Concept images and
prototype screenshots are kept locally and excluded from Git. README screenshots
are deferred until the first fully working version is ready.

## Open source

ShelfOS code uses [MPL-2.0](LICENSE). See [dependency review](docs/DEPENDENCIES.md),
[licensing](docs/LICENSING.md), [contribution guide](CONTRIBUTING.md), and
[security policy](SECURITY.md).

Future official store builds may offer an optional one-time Plus/Supporter
entitlement while keeping the source public and core reading free. Official
distribution is controlled by maintainer signing and review, as described in
[release governance](docs/RELEASE_GOVERNANCE.md) and the
[open-source model](docs/OPEN_SOURCE_MODEL.md). No release pipeline exists yet.
