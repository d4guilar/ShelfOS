# ShelfOS Input System

## Phase 0 implementation status

`core.input` defines semantic commands and a context-aware mapper. In Library,
Compose handles Tab/arrows/D-pad focus; Enter/center/gamepad A activate controls,
Ctrl+F opens Search, Escape/gamepad B use normal back behavior. Android Back/Home
are left to the system. Reader commands are modeled but have no active reader.
Focus rings are centralized in `shelfAction`; no remapping UI exists yet.

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

## Future Series continuity

In explicit virtual omnibus reading, semantic NEXT_PAGE at a member's end may
advance to the next imported member; PREVIOUS_PAGE at its start may return to the
preceding member's end. This is reader-session coordination, not separate hardware
bindings or a merged file. Direction-aware physical mappings still resolve to
Next/Previous; Library focus retains normal directional behavior. Missing members
and Series end need explicit recoverable UI. Standalone reading does not silently
open unrelated items. Back/Home/system navigation must remain normal.
See [Series](../features/SERIES.md).
