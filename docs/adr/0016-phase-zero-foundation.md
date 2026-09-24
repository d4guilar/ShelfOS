# ADR-0016: Phase 0 implementation boundary

## Status

Accepted for the requested Phase 0 prototype, 2026-09-23.

## Decisions

- One Android application module, package/application ID `com.d4guilar.shelfos`.
- Kotlin, Compose, Navigation Compose, Coroutines/Flow, ViewModels, manual
  application-scoped dependency injection. API 24 minimum, API 37 compile/target,
  Java 17. Stable dependency versions are centralized in the version catalog.
- Room schema v1 contains only `appearance_preference`, used to persist the
  selected theme. This is actual product state; no speculative publication,
  annotation, entitlement, or import tables. Export and commit Room schemas;
  future changes require migrations, never destructive fallback.
- Library fixtures live behind `LibraryRepository`. They have no source URI,
  reader engine or network dependency. Favorite edits are demo session state.
- `SavedStateHandle` preserves category, selected publication, query and demo
  favorites. Navigation Compose and saveable lazy-list state preserve UI state.
- Classic and Dark share tokens/geometry. The other three free registrations
  remain unavailable and visibly marked Planned. No premium implementation.
- Window width determines bottom navigation / rail and supporting details.
  Short landscape windows can use a rail. A separating/occluding fold constrains
  Phase 0 to the larger unobstructed region; dual-region posture layouts come later.
- Compose owns directional focus. Semantic mapping handles confirm/search/back
  and defines future reader commands without installing a reader. Android Back
  and Home are never intercepted. Escape/gamepad B delegate to normal back behavior.
- No logging library or telemetry. Use Android Logcat for development diagnostics;
  never log publication content, filenames, URIs or credentials. Report preference
  failures in UI without exposing private exception details.

## Documentation reconciliation

`ARCHITECTURE.md` listed Classic / Night / Paper / Aqua. Accepted ADR-0012 and
ADR-0007 (explicitly expanded by ADR-0012), plus v4 design specifications, supersede
that older list with Classic / Dark / Retro Apple UI / Retro Apple UI Dark / Paper.
The architecture list is corrected rather than maintaining both names.

The existing license is the full MPL-2.0 text. The owner's explicit license choice
and that file finalize the earlier ADR-0015 / licensing-document recommendation.
Documentation now says adopted rather than recommended.

`ARCHITECTURE.md`'s suggested “early” entities and the metadata specification's
“early prototype” scope describe later functional library phases. Phase 0 is a
mock UI foundation; it does not implement those schemas or metadata pipelines.
The newer approved Library reference specifies a 2–3 column compact library,
superseding the older foldables document's one-column compact library suggestion.

## Consequences

The prototype can exercise navigation and persistence without touching user
publications. All five theme identities are stable while only two presentations
are selectable. There are no reading, import, metadata enrichment, billing or
account claims. Phase 0 stays open until build and device acceptance evidence
supports every completion criterion; code presence alone is insufficient.
