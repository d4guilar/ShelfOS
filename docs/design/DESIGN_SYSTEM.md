# ShelfOS Design System

## Phase 0 implementation

`core.theme` provides `ShelfTokens`, `ShelfTheme`, and `ThemeRegistry`.
Classic/Dark share geometry and typography. `core.designsystem` owns original
stroke icons and a common focus/activation modifier. Compose/Material provides
semantics and primitives; ShelfOS tokens define the monochrome appearance.
Decorative animation is absent in Phase 0; the motion contract reserves a 120ms
focus duration for future polish. No theme requires motion or sound.

## 1. Design goal

ShelfOS should feel like a product with identity, not a generic document utility.

The design system must support:

- touch
- keyboard focus
- gamepad/D-pad focus
- accessibility
- light and dark environments
- multiple visual themes
- dense library views
- distraction-free reading

## 2. Brand personality

ShelfOS should feel:

- personal
- elegant
- polished
- editorial
- trustworthy
- quiet while reading
- cover-first in Classic
- expressive only when the selected theme calls for it

Core visual principle:

> **The interface is monochrome. The library is the color.**

Avoid:

- aggressive gamification
- fake urgency
- casino-style reward design
- ad-like UI
- overuse of neon
- unreadable retro fonts
- visual noise inside the reader

## 3. Theme contract

Every ShelfOS theme should define:

- color roles
- typography roles
- corner / shape system
- surfaces
- focus indicators
- motion style
- icon treatment
- optional sound profile
- library presentation rules

Reader content itself should remain highly legible regardless of theme.

## 4. Interaction states

Every actionable control should support:

- default
- pressed
- disabled
- focused
- selected
- loading where relevant

Focused state is mandatory for keyboard/gamepad use.

## 5. Accessibility

Themes must preserve:

- adequate contrast
- scalable text
- touch targets
- visible keyboard focus
- reduced-motion compatibility
- screen-reader semantics

Decorative motion and sounds must be optional or suppressible by system settings where practical.

## 6. Reading surface

The reading surface must prioritize content over branding.

Themes may influence:

- chrome
- menus
- progress indicators
- typography presets
- background choices

Themes should not introduce distracting animation behind text during active reading.

## 7. Cover presentation

Covers are the visual anchors of the library.

Classic should avoid wrapping every publication in a heavy card. The preferred default is cover + concise metadata on the canvas, allowing artwork to provide the dominant color.

Support:

- portrait book covers
- comic/manga covers
- document thumbnails
- generated fallback covers

Fallback covers should appear intentional, not like missing-image errors.

## 8. Motion

Motion should communicate:

- selection
- opening
- closing
- moving between library sections
- progress
- state changes

Motion must never slow down basic reading navigation.

## 9. Sound

Optional only.

No theme should require sound.

Sounds may later be used for:

- PageStation boot
- selection confirmation
- subtle navigation feedback

Provide global sound disable.


## 10. Default Classic behavior

ShelfOS Classic and Dark share the same component geometry and motion.

Classic should use:

- monochrome navigation/chrome
- restrained focus outlines
- card-light cover grids
- minimal system decoration
- 100–160 ms interaction motion
- optional Grid / Compact / List / Showcase library views

See `CLASSIC_UI.md`.

## 11. Theme tiers

Free themes are complete reading experiences.

Premium themes monetize optional visual delight and deeper personalization rather than basic readability.

The design system must support at least five free themes and a growing Premium theme registry without branching unrelated feature logic around individual themes.
