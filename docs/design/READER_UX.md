# ShelfOS Reader UX

## Goal

Reader screens should prioritize content and reduce interface noise.

ShelfOS branding belongs primarily in the library and navigation experience.

## Shared reader principles

- resume exactly where possible
- tap/click/button to reveal controls
- obvious exit/back behavior
- progress always recoverable
- preserve source
- readable defaults
- support touch, keyboard, and gamepad
- never visibly degrade the source

## Book reader

Primary capabilities:

- pagination
- scroll mode
- chapter navigation
- typography controls
- bookmarks
- highlights later
- notes later
- search

Controls should disappear when not needed.

## PDF / Document reader

Use **View as → Adapted / Original** when supported. Original keeps the source
layout with fit/zoom/page controls; Adapted eventually supplies comfortable
structured typography. Recommend a mode without locking the user into it, remember
per-title choice and disclose low reconstruction confidence. Do not expose internal
analysis state names or unavailable controls as working capabilities.

Mode switching and **View original page** use best-effort SourceMap positioning;
make approximate/unavailable mappings understandable and retain annotations.
Original remains available. Advanced reconstruction, OCR and Study/Focus tools
are later work. See [PDF ingestion](../features/PDF_INGESTION.md) for capabilities,
recommendation signals, mapping limitations and sequencing.

## Comic reader

Defaults:

- left-to-right
- single page
- fit page
- optional spread
- thumbnails
- quick page turns

When the source is good, fullscreen presentation should be sharp and immersive
enough for the artwork to dominate. Rendering must be resolution-aware and
re-render at useful zoom resolution while protecting usability on modest devices.

Hidden chrome improves immersion, but the Samsung tablet field test found that
restoring controls was not obvious. The design problem was **reader immersive mode
lacks sufficient rediscoverability**.

**Resolved in Phase 2A** (see `docs/PHASE_2_PLAN.md`): center-tap toggling (touch)
and the existing `OPEN_MENU` semantic command (keyboard/gamepad) remain the way to
show/hide chrome. Back is unconditional: while chrome is hidden, Back reveals it
instead of leaving the reader; only once chrome is visible does Back close the
reader. This never requires more than two Back presses to exit and never traps the
user, matching AGENTS.md rule 5. Chrome show/hide remains an instant, unanimated
state change (no motion to gate behind reduced-motion — trivially honored). A
restrained first-use affordance and any further discoverability polish beyond this
Back contract remain open for a later increment.

## Completion experience — future

After genuine completion of a book, comic/manga volume, virtual omnibus or eventually
a Series, ShelfOS may show an optional, tasteful Completion Card
with the cover, title/creator, completion date, pages, reading time when tracked,
notes/highlights and subtle ShelfOS branding. Its tone is
quiet satisfaction rather than a game achievement. Avoid streak pressure, loud
trophies, mandatory sharing and repeated popups.

Completion must not be inferred from display position 100% alone. Prior progress,
the final meaningful content, accidental scrubbing, repeats and rereads all matter;
the exact heuristic remains open. A local completion record may later support a
Completed view, history, monthly summaries, rereads and yearly recap.

Sharing renders a local image and invokes the Android system share sheet. A future
story-style layout may be useful, but ShelfOS needs no social backend or direct
social integration.

Celebrations remain optional, easy to dismiss and available for deliberate later
review without repeatedly appearing for the same completion. Exact Off/Subtle/share
settings and visual design remain open. Completion Cards follow reliable progress,
metadata, covers, Series/Omnibus semantics and library presentation rather than
displacing that foundational work.

## Manga reader

Defaults:

- right-to-left
- manga-aware page order
- optional spread
- thumbnails

The user must be able to override direction.

## Reader command philosophy

Touch gestures, keys, and controller buttons should invoke common commands.

Avoid separate feature implementations for each input type.

## Leaving the reader

Back should:

1. close transient UI if open
2. close reader to the library
3. allow normal Android app exit behavior

Do not trap the user inside a theme or reading mode.
**Resolved in Phase 2A**: hidden chrome is the first transient layer Back reveals
— after transient UI (dialogs, then hidden chrome) is handled, the normal exit
sequence above still applies once chrome is visible.

## Series boundaries

Optional **Read as Omnibus** uses a restrained completion/next-member surface,
for example "Volume 3 complete — Continue to Volume 4". Continue Reading restores
the current member and passage. Next/Previous can cross member boundaries in that
mode; keep missing-member recovery, end-of-Series and normal Back behavior clear.
Preserve each title's presentation/direction preferences and avoid transition
animation that delays reading. See [Series](../features/SERIES.md).
