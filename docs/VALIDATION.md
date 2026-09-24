# Phase 0 validation

Status: Phase 0 foundation implemented and validated in the local emulator
acceptance environment on 2026-09-23. This is an early prototype, not a
production-readiness statement. Unexecuted checks are listed separately below.

## Automated commands

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

## Device acceptance matrix

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

## Results

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

## Remaining validation and limitations

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
