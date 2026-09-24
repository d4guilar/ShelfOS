# ShelfOS Foldables and Adaptive Layouts

## 1. Why foldables matter

Foldables are unusually well aligned with ShelfOS.

Reading naturally benefits from:

- larger unfolded canvases
- book-like aspect ratios
- two-page spreads
- side-by-side notes
- compact outer-screen quick access
- posture changes

Foldable support should therefore become a ShelfOS differentiator rather than basic compatibility work.

## 2. Principle

> Adapt to the current window and posture, not to a hard-coded device model.

ShelfOS should work on:

- Samsung Galaxy Z Fold family
- Google / other Android foldables
- future tri-folds
- iPhone Duo when iOS support exists
- tablets
- resizable desktop/ChromeOS windows

Avoid code such as:

```text
if device == GalaxyFold:
```

Prefer capabilities, size classes, and posture.

## 3. Android adaptive architecture

Use current Android adaptive guidance:

- window size classes
- responsive/adaptive Compose layouts
- Jetpack WindowManager / FoldingFeature where needed
- preserve state across folding/unfolding
- support multi-window
- avoid orientation locks
- avoid assumptions about aspect ratio

## 4. Fold-aware reader behavior

### Folded / compact

Prioritize:

- single page
- simple bottom navigation
- Continue Reading
- compact controls
- adaptive 2–3 column library (per the newer approved Classic Library reference)

### Unfolded / expanded

Enable:

- larger cover grids
- navigation rail where appropriate
- list-detail layouts
- two-page book spreads
- comic/manga double pages
- document + annotation pane
- chapter list + reader
- Notes + source side-by-side

### Book posture

When a vertical hinge/fold meaningfully divides the screen:

Potential book reader:

```text
LEFT PAGE | HINGE | RIGHT PAGE
```

Potential study reader:

```text
DOCUMENT | HINGE | NOTES
```

Never place critical text or controls under an occluding hinge.

### Tabletop posture

Potential layout:

```text
CONTENT
-------
CONTROLS / NOTES
```

Useful for:

- hands-free reading
- TTS control
- annotation palette
- page navigation
- document study

## 5. App continuity

When a user folds/unfolds:

Preserve:

- current title
- exact reading position
- zoom where sensible
- open chapter
- selected annotation
- unsaved note text
- active category/filter
- theme
- focus where practical

The transition should feel like the same reading session expanding or contracting.

## 6. Comics and manga on foldables

Foldables are a priority form factor for sequential art.

Features:

- automatic single-page vs spread decisions
- configurable gutter
- RTL-aware manga spreads
- no content hidden by hinge
- prefetch both visible pages
- fast rotation/posture transitions

A portrait comic page should not be forcibly stretched merely because more space exists.

## 7. Documents and study mode

Expanded foldables can expose a supporting pane.

Examples:

```text
PDF | Notes
```

```text
Paper | Highlights
```

```text
Text | Dictionary
```

This should be one of ShelfOS's strongest large-screen features.

## 8. Library layouts

Compact:
- bottom navigation
- 2–3 column cover grid depending on width

Medium/expanded:
- navigation rail
- denser cover grid
- optional list-detail view
- persistent Continue Reading pane
- richer metadata preview

Do not simply scale phone cards to enormous widths.

## 9. Outer / cover displays

Where platform APIs allow apps on an outer display:

Prioritize quick tasks:

- Continue Reading
- Favorites
- current book
- next/previous page for short sessions
- quick bookmark
- TTS controls

Avoid trying to reproduce the full expanded library UI.

## 10. Testing matrix

At minimum test:

- compact portrait
- compact landscape
- unfolded portrait
- unfolded landscape
- half-open/book posture where emulator/device supports it
- tabletop posture where supported
- split-screen
- resized window
- fold/unfold while actively reading

## 11. iPhone Duo

Future iOS client should test:

- outer display
- inner display
- multiple poses
- safe/reserved regions
- size-class transitions
- Split View/multitasking
- reading continuity across display changes

Use Apple's current developer guidance when implementation begins.

## 12. Product opportunity

ShelfOS should eventually be able to market itself as:

> Built for phones. Better when they unfold.

This is a product direction, not a launch claim until the implementation reaches the required quality.
