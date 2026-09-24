# Dependency review

Phase 0 uses stable releases, checked against official release notes and Maven
metadata on 2026-09-23. Versions are pinned in `gradle/libs.versions.toml`.
No reader, network, billing, analytics, image-loading or dependency-injection
framework is included. Room 2 remains the conventional Android-only foundation;
a Room 3 migration is unnecessary for this single-module prototype.

Gradle 9.6.0 is intentionally paired with AGP 9.4's documented default/minimum.
Lint may suggest a newer stable Gradle release; this is an informational update
hint, not a compatibility failure. Upgrades should rerun the same build/device checks.

| Dependency | License | Need / maintenance / binary impact |
| --- | --- | --- |
| Android Gradle plugin 9.4.1 | Apache-2.0 | Official Android build tools; actively maintained; build only |
| Gradle 9.6.0 and wrapper | Apache-2.0 | Required AGP-compatible build runner; build only |
| Kotlin / Compose compiler 2.4.20 | Apache-2.0 | Native language and compiler; maintained by JetBrains; compiler build only, stdlib packaged |
| KSP 2.3.12 | Apache-2.0 | Room code generation; Google-maintained; build only |
| Compose stable BOM 2026.09.00: UI, Foundation, Material 3 | Apache-2.0 | Requested native UI; AndroidX-maintained; main UI runtime cost |
| Activity Compose 1.13.0 | Apache-2.0 | Activity integration and system navigation; small runtime adapter |
| Lifecycle 2.11.0 | Apache-2.0 | ViewModels, saved state, lifecycle-aware Flow; small runtime support |
| Navigation Compose 2.10.2 | Apache-2.0 | Shared navigation/back stack; moderate runtime support |
| Room runtime/compiler/plugin 2.8.5 | Apache-2.0 | Actual local theme preference persistence; modest runtime + generated code; uses system SQLite |
| Coroutines Android/test 1.11.0 | Apache-2.0 | Flow, structured concurrency, coroutine tests; modest runtime, test artifact excluded |
| WindowManager 1.5.1 | Apache-2.0 | Observe separating/occluding folds; small runtime support |
| JUnit 4.13.2 | EPL-1.0 | Maintained JUnit 4 test baseline; test only, never packaged |
| AndroidX Test runner 1.7.0 / ext JUnit 1.3.0 / Compose test | Apache-2.0 | Device smoke and persistence tests; test only |

The resolved 74-artifact debug runtime inventory is recorded in
`DEPENDENCY_LICENSES.csv`, including license declarations from cached Maven POMs
and Guava's inherited parent license. All runtime declarations in this snapshot
are Apache-2.0. Review updated resolutions and bundled notices before distributing
binaries. Size impact above is qualitative, not a measured per-library attribution.

Sources:

- [AGP compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
- [AndroidX stable releases](https://developer.android.com/jetpack/androidx/versions)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [AndroidX license](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)
- [Kotlin releases](https://kotlinlang.org/docs/releases.html) and [license](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt)
- [KSP releases](https://github.com/google/ksp/releases) and [license](https://github.com/google/ksp/blob/main/LICENSE)
- [Coroutines license](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt)
- [Gradle license](https://github.com/gradle/gradle/blob/master/LICENSE)
- [JUnit license](https://junit.org/junit4/license.html)

Original geometric demo covers and vector icons are ShelfOS source under MPL-2.0.
User-supplied concept images remain design references and are not packaged in the APK.

CI-only actions: `actions/checkout` and `actions/setup-java` (MIT),
`actions/upload-artifact` (MIT), and `gradle/actions` (MIT). These maintained
official actions are pinned by commit and add no APK size. JDK and Android SDK
are development tools under their respective vendor terms, not vendored source
or application dependencies. No tools, SDK archives or credentials are committed.
