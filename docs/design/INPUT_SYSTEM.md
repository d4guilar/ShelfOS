# ShelfOS Input System

## Implementation status

`core.input` defines semantic commands and a context-aware mapper. In Library,
Compose handles Tab/arrows/D-pad focus; Enter/center/gamepad A activate controls,
Ctrl+F opens Search, Escape/gamepad B use normal back behavior. Android Home is
left to the system. Reader commands (`NEXT_PAGE`/`PREVIOUS_PAGE`/`OPEN_MENU`/`BACK`)
are implemented and active in both the EPUB and Original (PDF/CBZ) readers as of
Phase 1, covering keyboard and gamepad; see the Phase 2A note below for Back and
touch, and §10 for the Phase 2A.1 input-discovery hint layer built on top of this
mapper. Focus rings are centralized in `shelfAction`; no remapping UI exists yet.

## 1. Principle

ShelfOS is touch-first but input-agnostic.

Supported categories:

- touch
- keyboard
- gamepad
- stylus later

Raw hardware events should be translated into semantic ShelfOS commands.

## 2. Reader commands

Initial command set:

```text
NEXT_PAGE
PREVIOUS_PAGE
SCROLL_UP
SCROLL_DOWN
OPEN_READER_MENU
CLOSE_READER_MENU
NEXT_CHAPTER
PREVIOUS_CHAPTER
TOGGLE_BOOKMARK
SEARCH
ZOOM_IN
ZOOM_OUT
CONFIRM
BACK
```

## 3. Suggested keyboard defaults

```text
Right Arrow   → Next page
Left Arrow    → Previous page
Page Down     → Next page
Page Up       → Previous page
Space         → Next page
Shift+Space   → Previous page
Escape        → Close overlay / Back
Enter         → Confirm
Ctrl+F        → Search
B             → Toggle bookmark
+             → Zoom/text size up where relevant
-             → Zoom/text size down where relevant
```

Shortcuts may be refined after usability testing.

For the Phase 1 readers, the arrow defaults above describe LTR content.
In RTL horizontal reading, Left invokes Next and Right invokes Previous. Page
Down/R1 remain semantic Next and Page Up/L1 remain Previous in either direction.
Resolve direction from the title override/category default before mapping reader
input; do not change Library focus navigation or intercept arrows in editable
fields/reader controls. See [Phase 1 plan](../PHASE_1_PLAN.md).

Implemented in Phase 1 (`InputMapper.readerCommand`): the page surface takes focus
when a reader opens, so keys work immediately; while a reader control has focus,
arrows/Tab/Enter/Space/A keep normal focus behavior and page keys still turn pages.
Menu/Start toggles controls and focuses the first one. System Back, Escape and B
hide visible controls first, then leave the reader. Returning from details or a
fixed-layout reader restores keyboard focus to the selected publication.

## 4. Suggested gamepad defaults

```text
D-pad Right   → Next page / move focus right
D-pad Left    → Previous page / move focus left
D-pad Up      → move focus / scroll up
D-pad Down    → move focus / scroll down
R1            → Next page
L1            → Previous page
A / Confirm   → Select
B / Back      → Back
Start         → Reader menu
L2/R2         → Optional chapter navigation later
```

Do not over-map the controller.

## 5. Navigation behavior

Gamepad and keyboard users must be able to:

- open ShelfOS
- navigate main categories
- browse library items
- open an item
- read
- open/close reader controls
- return to library
- leave the app normally

## 6. Immersive, not possessive

Hidden reader chrome must remain rediscoverable across touch, keyboard, gamepad and
accessibility input. **Resolved in Phase 2A**: Back reveals hidden chrome before
leaving — the reader's `BackHandler` and the `ShelfCommand.BACK` key path (Escape,
gamepad B) both apply this unconditionally, so at most two Back presses ever
exit the reader and Back never traps the user. Touch gestures (tap-to-page,
pinch-zoom, center-tap-to-toggle) remain screen-specific `pointerInput` handling
in `FixedReaderScreen`/`EpubSurface` rather than routed through `ShelfCommand`;
unifying touch into the semantic command layer remains open for a later increment.

Never:

- intercept Home
- replace launcher behavior
- trap Back navigation
- create kiosk behavior without explicit user action
- prevent Android multitasking

## 7. Focus

Every interactive component should have visible focus treatment.

Themes define their own focus styling but must maintain clarity.

## 8. Custom bindings

Not required for initial versions.

Later:

`Settings → Controls`

could allow remapping for:

- gamepad
- keyboard
- page turn
- chapter navigation

## 9. Stylus

Future stylus behavior should distinguish:

- stylus input
- finger input
- palm rejection where platform support permits

Ink coordinates must be stored relative to page/content space.

## 10. Input discovery hints (Phase 2A.1)

Implemented on top of the existing `ShelfCommand`/`InputMapper` layer, not a
parallel system: `core.input.InputModality` (`TOUCH`/`KEYBOARD`/`CONTROLLER`)
tracks the user's recent input per reader screen, and
`core.input.InputHints.hint(command, modality, rightToLeft)` resolves the
on-screen label from a small candidate catalog (`keyboardCandidates`/
`controllerCandidates`), each entry validated against the real
`InputMapper.command()` before being shown. This means a displayed hint (`R1`,
`←`, `Esc`, ...) can never disagree with `InputMapper`'s current behavior for a
binding the catalog covers — it does **not** mean an arbitrary future rebound
or remapped key is automatically discoverable; a new binding is only ever
hinted once it is also added to the candidate catalog. `core.designsystem.
InputKeycap` renders the small monochrome keycap badge.

Raw modality classification (`KeyEvent.inputModalityOrNull()`) is a separate
question from *which command an event produces*, and is evaluated independently
of it — this distinction matters because it must also apply to keys that never
become a reader `ShelfCommand` at all (Compose focus navigation, `CONFIRM`).
Classification never inspects device model/name: gamepad-exclusive keycodes
(`L1`/`R1`/`GAMEPAD_A`/`GAMEPAD_B`/`START`) are always `CONTROLLER`; keys a
keyboard's arrows/Enter and a gamepad's D-pad report identically are resolved
from the reporting sources, with the *specific event's own* `source` taking
precedence over the device's aggregate `sources` (a hybrid device's aggregate
capabilities could otherwise misclassify one of its plain keyboard events as
gamepad input; the aggregate is only consulted when the event itself reports
none — via the small, pure, internal `resolveInputSources`/`isGamepadSource`
helpers, which operate on plain `Int` bitmasks so the precedence rule itself
is directly unit-testable without a real or fake `InputDevice`). The function
returns `null` — no modality signal at all — for the raw
system Back/Home keys and unclassified keys, and each reader applies that
result unconditionally, before dispatching any semantic command, rather than
gating on the *resolved command* being `ShelfCommand.BACK`.

**This distinction was the subject of a real defect, found by independent
review.** `InputMapper` maps `InputKey.BACK`/`InputKey.HOME` to no command at
all (`null`) — only `InputKey.ESCAPE` and `InputKey.GAMEPAD_B` produce
`ShelfCommand.BACK`. This was true of both the original implementation and an
earlier attempted fix that filtered at the semantic level
(`if (command != ShelfCommand.BACK) modality = ...`): raw system Back never
entered the branch containing that line in either version, so the fix's own
stated reasoning — that excluding `ShelfCommand.BACK` would stop raw Back from
claiming a modality — was moot from the start. The actual defect was simpler:
that same guard also excluded Escape and gamepad B, which legitimately
produce `ShelfCommand.BACK` while being real, attributable keyboard/controller
input, so pressing them correctly revealed/exited the reader (Back semantics
were never wrong) but silently failed to update the displayed hint style. The
fix moved the exclusion to the raw-classification function itself
(`inputModalityOrNull()` returns `null` for `InputKey.BACK`/`InputKey.HOME`
directly, independent of what command they produce), so Escape and gamepad B
now correctly establish `KEYBOARD`/`CONTROLLER` modality, while the raw system
Back key/gesture still never claims a modality of its own.

Touch itself is still not routed through `ShelfCommand` (§6's open item
remains open) — only the *hint display* is command-derived. Real touch
gestures (tap/double-tap/swipe in the Original reader, center-tap in EPUB)
clear the tracked modality back to `TOUCH`; a known gap is that EPUB page
turns via edge-tap are handled entirely inside Readium's own navigator and
never reach this tracking, so edge-tap-only EPUB reading after a keyboard/
controller session can leave a stale hint showing until a center-tap or key
press occurs.

## Future Series continuity

In explicit virtual omnibus reading, semantic NEXT_PAGE at a member's end may
advance to the next imported member; PREVIOUS_PAGE at its start may return to the
preceding member's end. This is reader-session coordination, not separate hardware
bindings or a merged file. Direction-aware physical mappings still resolve to
Next/Previous; Library focus retains normal directional behavior. Missing members
and Series end need explicit recoverable UI. Standalone reading does not silently
open unrelated items. Back/Home/system navigation must remain normal.
See [Series](../features/SERIES.md).
