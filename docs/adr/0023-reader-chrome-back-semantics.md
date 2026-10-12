# ADR-0023: Reader chrome visibility and Back semantics

## Status

Accepted 2026-09-25, as part of Phase 2A (`docs/PHASE_2_PLAN.md`).

## Context

A Samsung Galaxy Tab A field test found that a real user, reading with chrome
hidden for immersion, pressed Android Back expecting it to reveal the reader's
controls — and instead exited the reader immediately. `docs/design/READER_UX.md`
and `docs/design/INPUT_SYSTEM.md` both left "whether Back reveals hidden chrome
before leaving" as an explicit open question pending this decision.

Investigation of the Phase 1 implementation found the bug's exact cause: both
`FixedReaderScreen.kt` and `EpubActivity.kt` gated their `BackHandler` on
`enabled = controls`. Once chrome was hidden (`controls == false`), the handler
was disabled entirely and Back fell through to the system default (closing the
reader), with nothing revealing chrome first. The same gap existed independently
in the raw key-event path used for Escape/gamepad B. No existing automated test
exercised a system Back press while chrome was hidden, so the bug reached a real
user untested.

## Decision

Reader Back handling (system Back gesture/button, and the `ShelfCommand.BACK`
key path for Escape/gamepad B) is unconditional and follows one rule in both
readers:

- If reader chrome is hidden, Back reveals it and does **not** leave the reader.
- If reader chrome is visible, Back leaves the reader (to Library/details).

Any transient dialog (Appearance, Chapters) that is open closes first, via
Compose's own default dialog back-dismiss behavior — this ADR does not change
that layer.

This makes leaving the reader via Back always reachable in at most two presses,
never traps the user (AGENTS.md rule 5), and directly fixes the field-reported
bug: the first press a user makes from hidden chrome now shows them their
options instead of ejecting them.

Chrome show/hide remains an instant, unanimated Compose recomposition (no
`AnimatedVisibility`/crossfade was added). There is currently nothing here to
gate behind reduced-motion, so the requirement is trivially satisfied; if a
future increment adds a chrome transition, it must route through
`Context.reducedMotionEnabled()` (`core.theme.ReducedMotion`), the same hook
already used for the library cover-expansion transition.

## Alternatives considered

- **Keep the two-step "hide, then exit" behavior from a visible start, and add a
  separate reveal-first step only when starting hidden.** Rejected: this
  produces an inconsistent contract depending on the chrome state a session
  happens to start with, and doesn't compose cleanly with a single Back handler.
- **Toggle chrome unconditionally on every Back press (hidden → visible,
  visible → hidden), only exiting via an explicit Library/back-to-library
  action.** Rejected: this can never exit the reader via Back at all once
  chrome is visible, which would violate the requirement that the user can
  always leave using normal Android Back behavior.

## Consequences

- `app/src/main/java/com/d4guilar/shelfos/feature/reader/FixedReaderScreen.kt`
  and `EpubActivity.kt` each gained a small local `backPress()` function used by
  both the `BackHandler` composable and the `ShelfCommand.BACK` key branch,
  replacing the two independently-buggy call sites.
- `NavigationSmokeTest.kt`'s prior `backHidesReaderControlsBeforeLeavingTheReader`
  test encoded the old (incomplete) contract and never exercised the hidden-chrome
  starting state; it was replaced with
  `backRevealsHiddenControlsBeforeLeavingTheReader` (Original/PDF reader) and a new
  `epubBackRevealsHiddenControlsBeforeLeavingTheReader`, both of which start from
  hidden chrome and assert the reveal-then-exit sequence.
- `docs/design/READER_UX.md` and `docs/design/INPUT_SYSTEM.md` are updated to
  record this as resolved rather than open.
- Touch gestures remain outside the `ShelfCommand`/`InputMapper` semantic layer
  (screen-specific `pointerInput` handling); unifying touch into that layer is
  out of scope for this ADR and remains open for a later increment.

## Phase 4B clarification (2026-10-10)

An active text selection (the platform selection ActionMode over EPUB text) is a transient layer ABOVE chrome semantics.
Back priority, identical for system Back, Escape and gamepad B (all reach the single reader `backPress()` path):

1. An active text selection is dismissed (selection cleared, ActionMode finished); the reader stays open and chrome is left
   exactly as it was.
2. A transient ShelfOS dialog or sheet dismisses itself (its own window, as before).
3. Chrome hidden: Back reveals chrome.
4. Chrome visible: Back leaves the reader.

Rule 3 and 4 are unchanged. Before this clarification the reader consumed Back first, so with chrome visible the first Back
left the reader (and destroyed the selection with it) and with chrome hidden it revealed chrome while the selection stayed
active (spike evidence, `docs/VALIDATION.md`). There is no Back trap: with nothing selected the original two-press contract
holds. The selection is tracked from the framework's `Activity.onActionModeStarted/Finished`, so it also applies on
WebViews where inline annotations are unavailable and the platform's own selection menu is shown.

## Phase 4C.2 clarification (2026-10-11)

An open in-window Publication Notebook pane is a transient supporting layer above chrome semantics. After any active EPUB selection and any editor/delete dialog, Back, Escape or gamepad B closes the Notebook pane before revealing hidden chrome or leaving the reader. Each press removes exactly one layer; compact Notebook dialogs retain their own window-level dismissal.
