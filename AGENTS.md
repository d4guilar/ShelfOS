# ShelfOS Agent Instructions

This file is the primary behavioral contract for coding agents working in the ShelfOS repository.

## Product rules

1. ShelfOS is a **local-first Android reading app**, not a launcher, kiosk, replacement home screen, or frontend that traps the user.
2. Core reading must remain usable without a network connection.
3. ShelfOS must never require a ShelfOS account for normal use.
4. Never modify, rewrite, or replace the user's source publication unless the user explicitly requests an export operation.
5. The user must always be able to leave ShelfOS using normal Android navigation, Home, Recent Apps, keyboard, or gamepad back behavior.
6. No advertising may interrupt reading.
7. Premium may add advanced tools, visual experiences, and power-user features, but must not make free reading intentionally unpleasant.
8. Manual metadata and cover choices always override fetched metadata.
9. Books, Comics, Manga, and Documents are first-class media categories. Favorites is a cross-category view.
10. Touch, keyboard, and gamepad must share semantic input commands rather than screen-specific hard-coded mappings.
11. Stylus/ink support must use page-relative or content-relative coordinates when implemented.
12. Themes must use the centralized theme/design system.
13. Reader engines must sit behind ShelfOS-owned abstractions.
14. Do not introduce a dependency without checking:
    - license compatibility
    - maintenance status
    - Android support
    - binary size impact
    - whether ShelfOS actually needs it
15. Prefer maintainable, conventional Android architecture over clever abstractions.
16. ShelfOS must be adaptive: never assume a fixed phone portrait size or aspect ratio.
17. Preserve reading and UI state across fold/unfold, rotation, window resizing, and process recreation.
18. Prefer window capabilities/size classes/posture over device-model checks.
19. Foldables are a first-class target, especially for two-page reading and document+notes layouts.
20. The canonical ShelfOS brand is monochrome; do not introduce required brand accent colors into Classic.
21. ShelfOS Classic is cover-first and card-light: system chrome stays neutral while publication covers supply most visual color.
22. Classic/Dark may borrow media-frontend interaction quality, but must not visually read as gaming/console frontends.
23. Public free theme set: Classic, Dark, Retro Apple UI (working name), Retro Apple UI Dark (working name), and Paper / Vintage Library.
24. Trademark/franchise-inspired theme names and assets must be reviewed before public release; never copy proprietary visual/audio assets.

## Technical direction

- Kotlin
- Jetpack Compose
- Room
- Coroutines / Flow
- Android Storage Access Framework
- Repository pattern
- Screen-level ViewModels
- Unidirectional data flow
- Domain use cases only where they improve clarity
- Start as one Gradle app module with strong package boundaries
- Modularize later only when boundaries are proven

## Architectural boundaries

Preferred package groups:

- `core`
- `data`
- `domain`
- `feature`

Important subareas:

- `core.database`
- `core.files`
- `core.network`
- `core.designsystem`
- `core.theme`
- `core.input`
- `core.reader`
- `core.entitlement`
- `data.library`
- `data.metadata`
- `data.annotations`
- `data.entitlement`
- `domain.importing`
- `domain.library`
- `domain.reader`
- `domain.annotations`
- `feature.home`
- `feature.library`
- `feature.importing`
- `feature.reader`
- `feature.notes`
- `feature.shelves`
- `feature.settings`

## Source of truth

Before changing architecture or product behavior, read:

1. `docs/PRODUCT.md`
2. `docs/ARCHITECTURE.md`
3. `docs/ROADMAP.md`
4. `docs/COMPETITIVE_FEATURES.md` when proposing reader/library features
5. `docs/PLATFORM_STRATEGY.md` for platform decisions
6. `docs/design/VISUAL_IDENTITY.md` for brand and visual-language decisions
7. `docs/design/CLASSIC_UI.md` for default UI implementation
8. `docs/design/THEMES.md` for theme behavior and backlog
9. Relevant design / feature specification
10. Relevant ADRs

If implementation and documentation conflict, do not silently choose one. Reconcile the conflict and update the docs or create an ADR.

## Definition of quality

A feature is not complete merely because it compiles.

Consider:

- configuration changes
- process recreation
- accessibility
- keyboard focus
- D-pad/gamepad navigation
- offline behavior
- malformed files
- large libraries
- source-file deletion or movement
- error messaging
- state persistence
- tests where practical

## Do not build yet unless explicitly requested

The following are future features and should not leak into early phases through speculative complexity:

- ShelfOS accounts
- mandatory cloud sync
- iOS implementation
- OCR
- AI services
- plugin marketplace
- Calibre integration
- guided comic panels
- advanced PDF reconstruction
- expressive Premium themes (unless the active phase explicitly targets them)
- Play Billing
- stylus ink engine
- social features
## Visual implementation references

The approved ShelfOS Classic library mock is:

`docs/design/references/ShelfOS_Classic_Library_v1.jpeg`

Concept images and prototype screenshots are local-only and excluded from Git.
When the mock is unavailable, use the written specifications below. Do not add
concept images to commits; public README screenshots are deferred until the first
fully working version is ready.

Before implementing Library UI, read:

- `docs/design/CLASSIC_LIBRARY_REFERENCE.md`
- `docs/design/CLASSIC_UI.md`
- `docs/features/LIBRARY.md`
- `docs/features/METADATA_ENRICHMENT.md`

The image is not a pixel-perfect contract. Written specifications win if they conflict.

Do not invent additional visual systems merely because the mock does not show a state.

## Metadata rules for agents

- Never couple UI directly to Open Library, Google Books, or any provider.
- Keep provider logic behind ShelfOS metadata abstractions.
- Persist provenance.
- User edits always win.
- Metadata network failure must never block local reading.
- Do not upload full publications for metadata lookup.


## Open-source and release rules

Read:

- `docs/OPEN_SOURCE_MODEL.md`
- `docs/LICENSING.md`
- `docs/RELEASE_GOVERNANCE.md`

Rules:

- ShelfOS source is public.
- Do not design Plus around source secrecy.
- Never commit signing material or store credentials.
- Official distribution depends on protected signing/release processes.
- New dependencies require license review.
- Do not casually introduce GPL/AGPL components.
- Debug entitlement simulation must not become an accidental production bypass.

## Ingestion and organization boundaries

- Read [data ingestion](docs/features/DATA_INGESTION.md), [PDF ingestion](docs/features/PDF_INGESTION.md),
  [Series](docs/features/SERIES.md), [Shelves](docs/features/SHELVES.md) and
  [Library Sources](docs/features/LIBRARY_SOURCES.md) before changing these systems.
- Source ≠ LibraryItem; ImportSession is a process, LibrarySource is durable origin/access.
- Category ≠ Shelf; Series ≠ Shelf. Keep four categories and Favorites independent.
- Canonical global destinations: Library, Search, Notes, Shelves, Settings. Existing
  Collection/Collections names are legacy implementation debt, not another feature.
- Adapted PDF is a structured derived view, never replacement of the original;
  preserve SourceMap confidence/revision limits. Original remains available.
- Series is generic virtual aggregation, never physical file merging.
- Ingestion is non-destructive, staged and reviewable; bulk work requires durable
  recovery and per-file failure isolation. Online metadata/expensive PDF analysis
  must not block local import or reading.
- Folder/source structure is evidence, not truth. User metadata, classification
  and grouping decisions override automatic suggestions.
- Missing or disconnected Sources must not automatically delete LibraryItems or
  their state. Disconnect/removal must not delete source files by default.
- Use stable domain UUIDs and platform access adapters; portable backups require
  Source reconnection, not reliance on Android URI strings alone.
- Respect scoped access, DRM and app sandboxes. Do not upload publications for lookup.
- Accepted architecture does not authorize building all future features now;
  follow [roadmap sequencing](docs/ROADMAP.md#accepted-ingestion-and-organization-sequence).
