# Quiet Motion

Status: **accepted product direction** (2026-10-22); **implemented so far: v1A shared press feedback only, provisional pending owner sign-off** (§2.1); all else
proposed. All
durations and curves here are **provisional prototype values for review**, not implementation constants. Nothing
here is claimed performant until measured (Section 9). Related: [Design system](DESIGN_SYSTEM.md),
[Classic UI](CLASSIC_UI.md), [Themes](THEMES.md), [ADR-0007](../adr/0007-theme-system.md).

## 1. Principle

> Motion should make ShelfOS feel tactile, elegant and responsive without delaying reading or distracting from
> publications.

Motion is feedback, never a gate. It scopes to interfaces **outside active reading**: buttons, press/release, key and
gamepad focus, Library cover selection, bottom navigation/rail, dialogs, Settings controls, favorite changes, Shelf and
Series browsing, and future Room navigation. Active reading surfaces stay calm and are untouched by this document.

## 2. What already exists (reuse, do not duplicate)

- `ShelfMotion` (in `core.theme`, carried by `ShelfTokens`): the extension point for motion tokens. Capability inventory,
  distinguishing defined, used and implemented:
  - *Defined tokens:* `focusMillis = 120`; and, for v1A, `pressMillis = 90`, `releaseMillis = 130`, `pressedScale = 0.98f`
    (the v1A values are provisional, pending owner sign-off). `ShelfFocus` also defines `scale = 1.01f`.
  - *Actively used:* `focusMillis` drives the existing NavHost enter/exit fade (`ShelfApp`); the v1A tokens drive the
    `shelfAction` press/release scale. The `ShelfFocus` scale is defined but is **not** an implemented shared focus animation.
  - *Implemented animations:* the NavHost fade, the reader-entry cover transition below, and the v1A press/release scale.
  - *Proposed only:* dedicated navigation-duration tokens, selection/rail indicators, dialog, favorite and Shelf/Series/Room
    motion, haptics, and everything else in Section 4.
- `shelfAction` (`core.designsystem`): the single focus/activation policy for touch, keyboard, D-pad and gamepad. It now
  carries the v1A press/release scale (§2.1). Focus is still an **instant static outline** with no focus-scale animation.
- `reducedMotionEnabled()` (`core.theme/ReducedMotion.kt`): reads the system "Remove animations" /
  animator-duration setting; never inferred from a device model. Currently used by the cover transition.
- The **reader-entry cover transition** (`CoverExpandTransition.kt`, `COVER_EXPAND_MILLIS = 220`, CLASSIC_UI §12): the
  one signature animation. **Preserved as accepted**; this plan does not alter its timing, geometry or fallback.
- Existing Compose animation APIs. No new animation library or dependency is proposed (product rule 14).

A future implementation extends `ShelfMotion` and `shelfAction`; it does not add a parallel motion system.

### 2.1 Implemented: Quiet Motion v1A (prototype, owner review pending)

- `ShelfMotion` gained `pressMillis = 90`, `releaseMillis = 130`, `pressedScale = 0.98f` (`focusMillis = 120` unchanged).
  **Prototype values, not approved constants**; easing is the stock `FastOutSlowInEasing`, no overshoot.
- `shelfAction` scales the control's **content** with a draw-only `drawWithContent` placed *inside* `clickable` (no graphics
  layer, so pointer hit-testing, semantics bounds, the focus ring and the ripple keep the full resting box; layout size,
  padding and neighbours never change) toward `pressedScale` while pressed and back on release. (QA remediation: the first
  prototype used an outermost `graphicsLayer`, whose 0.98 transform made edge touches fail hit-testing.) Pressed comes from
  one `MutableInteractionSource` (touch, and Enter/D-pad centre / NumPad Enter that `clickable` handles) plus a flag set by
  the existing confirm key handler for keys `clickable` does not own (controller A). The flag is set only by the initial
  DOWN (`repeatCount == 0`), because `clickable` consumes that DOWN and its UP but lets key-repeat DOWNs bubble to the outer
  handler, which would otherwise latch the pressed look; it clears on key-up and on focus loss. Activation is unchanged: key-up
  confirm, `clickable` onClick, never tied to animation. Any new input retargets the animation; nothing queues.
- **Reduced Motion:** `reducedMotionEnabled()` is read on each interaction-driven recomposition (no polling); when true
  the scale is dropped and any change is a `snap()`.
- **Focus emphasis is deliberately not a scale.** Growing a full-width item (navigation item, row) by 1 % pushes its 2 dp
  ring past the screen edge, so `ShelfFocus.scale` stays an unused token. Focus remains the static ring (instant, never
  faded) plus the Material indication's existing focus state layer. `focusIndicator = false` (Library tile) still draws
  no ring of its own; only the press scale applies there.
- Not done: selection/rail indicator, dialogs, favorites, haptics, any timing approval, any device measurement.
- Caveat (API 26+): `ValueAnimator.areAnimatorsEnabled()` reflects the value the process last received. Bounded observation
  on a disposable API 35 emulator only: after `animator_duration_scale` was set to 0, `areAnimatorsEnabled()` reported false
  within about 2.5 s without recreating the activity; the next interaction then suppressed the scale, and a press already
  active at that moment returned to scale 1 on release. This is not a claim for every device or an instantaneous reaction.
  The pre-26 settings path was exercised on an API 24 emulator. RP5 and SM-T580 remain unverified.
- Status: the 90/130 ms timings and the 0.98 scale are provisional prototype values awaiting owner sign-off; automated
  checks show correctness (activation, hit target, restore), not visual approval, and performance acceptance (§9) is open.

## 3. Interaction contract

1. **Semantic activation is immediate.** A press, key-up confirm or tap triggers its action at once. Animation is
   decoration around the action, never in front of it. For new animations: button feedback never blocks an action,
   tab changes never wait for decorative transitions, and decorative animations are interruptible.
   **One existing, accepted exception:** the 220 ms cover-to-reader expansion may complete before the reader opens.
   It remains skippable under Reduced Motion and when the required cover position is unavailable. This spec does
   not modify it and authorizes no additional delayed-navigation behavior.
2. **No gating delays.** No spinner, fade or hold is added before content that is already available.
3. **Interruptible.** Any in-flight animation can be superseded by new input without a visible jump, queue or stall.
4. **Rapid input is safe.** Fast D-pad stepping, repeated taps and key repeat never accumulate queued animations.
   Focus feedback follows the latest focus target; intermediate targets may skip feedback entirely.
5. **Stable geometry.** Motion never moves layout: no animation resizes bottom navigation, reflows labels or shifts
   hit targets (cf. the navigation-width stabilization in 4.5C.2C). Transform and alpha only, not layout changes. Press/focus scaling is visual-only: the original touchable and
   focusable region and minimum touch dimensions stay usable throughout the animation. How to achieve this in Compose
   must be verified against hit-testing and layout behavior before a modifier is chosen; none is prescribed here.
6. **Disabled motion keeps every function.** With animations off, every interaction works identically, with a clear
   instant state change and visible focus.
7. **Focus stays visible** and meets contrast at all times, including mid-transition.

## 4. Proposed vocabulary (provisional)

| Role | Purpose | Starting range (review only) | Preferred properties |
| --- | --- | --- | --- |
| **Press** | Acknowledge touch/confirm down | 70-100 ms | small scale-down (about 0.96-0.98), slight alpha |
| **Release** | Settle after press | 110-160 ms | return to rest, no overshoot |
| **Focus** | Move focus ring/emphasis | 100-140 ms | outline/alpha/color; optional ~1.02 scale on covers |
| **Selection** | Selected/checked state, tab change, favorite toggle | 100-160 ms | alpha and color; brief indicator change |
| **Appearance / disappearance** | Menus, chips, inline sections | 100-160 ms (leaving shorter than entering) | alpha, small translate |
| **Dialog** | Modal enter/exit | 150-220 ms | alpha plus small scale or translate |
| **Navigation** | Between top-level or detail screens | to be prototyped; must not exceed the cover transition's 220 ms | alpha/shared-element where it aids orientation |
| **Completion acknowledgment** | Confirm a saved/finished action | brief; to be prototyped | subtle, non-blocking, not celebratory |

The existing `focusMillis = 120` sits inside the Focus range. The Classic 100-160 ms guidance in DESIGN_SYSTEM §10
is the envelope.

**Style rules.** Prefer small scale changes (a few percent), opacity and color transitions. Restrain springs:
critically damped or default Material easing is the baseline; **no overshoot** on common controls and no bouncy
motion on Classic/Dark. Avoid parallax, large travel, looping or ambient animation outside deliberate theme effects.
Exact easing curves are *pending design decision* after prototyping.

## 5. Surfaces

- **Buttons and actions** (`shelfAction`): press/release feedback on the shared modifier so Read, Favorite, Remove and
  tabs inherit it. Destructive actions get no extra flourish.
- **Library cover selection/focus:** subtle emphasis on the cover artwork (the Library already draws its own focus on
  visible artwork, D10); never at the cost of cover legibility or neighbor reflow.
- **Bottom navigation / rail:** selection indicator transition only; label widths and item geometry stay fixed.
- **Dialogs:** short enter/exit; focus moves into the dialog immediately; Back/Escape/B dismiss at once.
- **Settings controls:** toggle/selection transition; value changes apply immediately.
- **Favorite state change:** a small, quick confirmation of the new state; state is correct before the animation ends.
- **Shelf and Series browsing, future Rooms:** lightweight list/section transitions; entering a Series or Room must not
  show a loading delay because of animation.
- **Haptics:** optional future work, off by default or setting-controlled, never required for understanding state. Not
  part of the first motion work.

## 6. Reduced motion and accessibility

- When `reducedMotionEnabled()` is true: no scale, travel or decorative transitions; state changes are instant or a
  very short opacity change, and the reader-entry transition uses its already documented minimal fallback.
- Respect OS animator-duration scale beyond the on/off check where Compose animation does so natively.
- A ShelfOS-level motion toggle is *pending design decision*; the system setting must take priority regardless
  (THEMES.md, optional theme effects: Reduced Motion / accessibility preferences take priority).
- Motion must not rely on color or movement alone to convey state. Screen-reader semantics (selected, focused,
  enabled) update immediately; announcements are not delayed by animation.
- Large font scale and localized strings: motion must not clip, overlap or reflow content.

## 7. Themes

Classic and Dark share the same polished, restrained motion (CLASSIC_UI: Dark uses the same motion). Future
expressive themes may vary **optional presentation effects** (such as a short signature startup effect, already
governed by THEMES) but must not change navigation semantics, input timing or activation speed, and must honor
Reduced Motion. A theme's motion personality is a token-level variation, not a bypass of the interaction contract.
Pagestation/Terminal-style effects remain optional, short and skippable. No animation-related core quality feature
requires ShelfOS Plus; motion polish for the free themes is free. Any future premium theme may sell *visual*
personality only, never responsiveness.

## 8. Performance and efficiency principles

- Animate cheap properties (alpha, scale, translation via graphics layers) and avoid layout-triggering animation.
- Do not run continuous animations while idle; stop work when the screen is not visible; no always-running timers.
- Do not animate large bitmaps by re-decoding; reuse cached cover bitmaps (source-fidelity rules still apply).
- Battery: no animation keeps the display or GPU busy without user input.
- Older hardware: every effect must degrade by being dropped, not by lagging input. A frame budget shortfall removes
  decoration first.

## 9. Benchmark and acceptance plan (future; nothing is claimed yet)

Before any motion is declared acceptable, measure with real frame timing (for example frame-timing/Perfetto or
`dumpsys gfxinfo`, and Macrobenchmark where practical) on:

- API 35 emulator (functional and regression baseline, not a performance authority)
- Retroid Pocket 5 (controller-first, Android 13)
- an older Samsung tablet (for example the SM-T580, Android 8.1, 2 GB RAM)

Scenarios: rapid repeated D-pad traversal of a large Library grid; fast tab/rail switching; dialog open/close spam;
favorite toggling; cover focus with large artwork; returning from the reader; different refresh rates (60/90/120
Hz where hardware allows); low-memory conditions; **Reduced Motion on**, and font/display scaling at accessibility
extremes (including localized navigation labels).

Pass criteria are to be agreed before implementation (for example no dropped input, no growth in focus-to-visual
latency, jank percentiles not worse than the static baseline). Results go to VALIDATION.md with device and
orientation stated. A motion that fails on the reference older tablet is simplified or removed there rather than
tuned for flagship hardware.

## 10. Testing requirements

Automated: animations disabled in instrumentation must not change any functional outcome; existing keyboard/gamepad
navigation tests keep passing; rapid-input tests assert final focus/selection correctness; reduced-motion path covered;
geometry stability asserted around navigation. Manual: controller feel on the RP5; low-end tablet feel.

## 11. Scope boundaries

Not in scope: reader page-turn or reading-surface animation, ambient/looping backgrounds, sound, 3D, new animation
dependencies, per-theme timing that alters activation, and any paywalled responsiveness. Reader-entry cover
transition remains as accepted.

## 12. Decisions needing owner approval (open)

1. Final durations and easing after prototype review.
2. Whether a ShelfOS-specific motion setting is added beyond the system toggle.
3. Haptics: whether to pursue and under what default.
4. Pass thresholds for Section 9.
5. Whether `ShelfMotion` expands to a role-based token set (proposed) or stays minimal.
6. Whether an ADR is needed. Proposed: a short ADR ("motion tokens extend `ShelfMotion`; reduced motion is
   authoritative; activation is never delayed") **only** at the point Quiet Motion v1 implementation is approved.
