# ShelfOS

**Early development / Phase 0 prototype — not production-ready.**

ShelfOS is an open-source, local-first Android personal reading system for
Books, Comics, Manga, and Documents you own. Core use is accountless and ad-free.
Favorites is a cross-category view. ShelfOS is a normal app, not a launcher,
bookstore, subscription catalog, or cloud service.

> The interface is monochrome. The library is the color.

## What this prototype does

- Library, Search, Notes, Collections, and Settings destinations.
- Original fictional sample publications and geometric covers, Continue Reading,
  category filters, demo favorites, a cover grid and publication details.
- Bottom navigation on compact windows, compact rail on wider/short landscape
  windows, supporting detail pane where sufficient room exists.
- Classic and Dark using a shared token-based Compose design system.
- Theme preference persisted locally in Room; three other free themes registered
  as Planned. No premium themes or billing.
- Semantic input foundation with keyboard/D-pad/gamepad focus and normal Android
  Back/Home behavior.

**Importing and reading files are not implemented.** Continue Reading samples
open details. Notes and Collections explain their planned purpose. Search only
searches sample titles/creators. Demo favorites survive saved-state restoration,
but are not a persistent user library. No Readium, metadata APIs, OCR, accounts,
network permission, analytics, or release signing is included.

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

One `app` module, with `core` (design system, themes, input, database), `data`
(demo library, preference repository), and `feature` (navigation, library,
settings) boundaries. Screen state flows from ViewModels into Compose.
The future domain/import/reader packages are intentionally not created yet.

See [validation status and acceptance checklist](docs/VALIDATION.md),
[roadmap](docs/ROADMAP.md), [architecture](docs/ARCHITECTURE.md), and
[Phase 0 decisions](docs/adr/0016-phase-zero-foundation.md).
GitHub Actions is configured for builds, unit tests and lint; device tests run
separately. Local validation passed a clean-checkout build, seven JVM tests and
five Android tests on both phone and expanded API 35 emulator layouts. Physical
devices, fold postures, API 24/37 runtimes and broader accessibility QA remain.

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
