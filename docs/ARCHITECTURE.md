# ShelfOS Architecture

## 1. Architecture goals

ShelfOS architecture should prioritize:

- local-first behavior
- maintainability
- offline correctness
- clear reader-engine boundaries
- testability
- input flexibility
- theme extensibility
- gradual growth
- low operational complexity

Avoid premature distributed systems, accounts, and backend dependencies.

## 2. Technology stack

Initial target:

- Kotlin
- Jetpack Compose
- AndroidX
- Coroutines
- Flow / StateFlow
- Room
- Android Storage Access Framework
- Readium Kotlin Toolkit where appropriate
- Gradle
- GitHub Actions for build, unit tests, and lint

Dependency injection may use Hilt or another conventional Android approach if introduced deliberately.

## 3. Initial module strategy

Start with one Android app module.

Do not begin with a large multi-module Gradle graph.

Use package-level architectural boundaries first:

```text
app/
└── src/main/java/<package>/
    ├── core/
    │   ├── database/
    │   ├── files/
    │   ├── network/
    │   ├── designsystem/
    │   ├── theme/
    │   ├── input/
    │   ├── reader/
    │   └── entitlement/
    │
    ├── data/
    │   ├── library/
    │   ├── metadata/
    │   ├── annotations/
    │   ├── preferences/
    │   └── entitlement/
    │
    ├── domain/
    │   ├── importing/
    │   ├── library/
    │   ├── reader/
    │   └── annotations/
    │
    └── feature/
        ├── home/
        ├── library/
        ├── importing/
        ├── reader/
        ├── notes/
        ├── collections/
        └── settings/
```

Split into Gradle modules only when compile times, ownership, reuse, or dependency isolation justify it.

## 4. Layering

### UI layer

Jetpack Compose screens and reusable components.

Responsibilities:

- render state
- emit user actions
- manage focus behavior
- accessibility semantics
- navigation presentation

Avoid direct database or network calls from composables.

### Presentation / state holders

Screen-level ViewModels.

Responsibilities:

- expose immutable UI state
- consume UI actions
- coordinate repositories / use cases
- handle loading/error state

Prefer unidirectional data flow.

### Domain layer

Optional business-use-case layer.

Use where logic is meaningful enough to deserve explicit representation.

Examples:

- `ImportLibraryItem`
- `ClassifyPublication`
- `ResolveMetadata`
- `OpenLibraryItem`
- `SaveReadingProgress`
- `ToggleFavorite`
- `CreateAnnotation`

Do not create one-line use cases solely to satisfy a pattern.

### Data layer

Repositories provide stable interfaces over:

- Room
- file access
- metadata providers
- reader state
- preferences
- entitlements

## 5. Core data model

Conceptual model:

```text
LibraryItem
├── id
├── source
│   ├── uri
│   ├── mimeType
│   ├── format
│   ├── originalFileName
│   └── checksum?
├── metadata
│   ├── title
│   ├── authors
│   ├── description
│   ├── isbn
│   ├── publisher
│   ├── publicationDate
│   └── language
├── category
│   ├── BOOK
│   ├── COMIC
│   ├── MANGA
│   └── DOCUMENT
├── favorite
├── cover
├── readingState
├── presentationPreferences
├── createdAt
└── updatedAt
```

The exact Room schema may normalize some of these concerns.

## 6. Suggested future Room entities

Phase 0 implements only the persisted appearance preference. The following
publication schemas begin in Phase 1 or later, not in the mock library prototype.

Early:

- `LibraryItemEntity`
- `ReadingProgressEntity`
- `BookmarkEntity`
- `AnnotationEntity`
- `CollectionEntity`
- `CollectionItemCrossRef`
- `CoverEntity` or cover fields attached to LibraryItem
- `MetadataOverrideEntity` if needed

Later:

- `ReadingSessionEntity`
- `ImportRecordEntity`
- `InkAnnotationEntity`

## 7. Source file model

Use Android Storage Access Framework URIs.

Do not assume direct filesystem paths.

Persist URI permissions when appropriate.

A source publication may become unavailable because:

- file was deleted
- file moved
- storage provider changed
- permission was revoked
- external storage disconnected

ShelfOS must represent this as an unavailable source, not as a corrupted database row.

## 8. Import pipeline

Conceptual pipeline:

```text
URI
 ↓
FileInspector
 ↓
FormatDetector
 ↓
FormatParser
 ↓
EmbeddedMetadataExtractor
 ↓
MediaClassifier
 ↓
CoverResolver
 ↓
OptionalMetadataResolver
 ↓
UserReview / Override
 ↓
LibraryRepository
```

Import should be resumable or fail safely.

Do not mutate the source publication.

## 9. Media classification

Public categories:

```text
BOOK
COMIC
MANGA
DOCUMENT
```

Automatic classification is a suggestion.

The user may override it.

Comic and Manga may share rendering infrastructure while retaining separate user-facing defaults.

## 10. Reader abstraction

The rest of ShelfOS should not be tightly coupled to Readium.

Define a ShelfOS-owned abstraction.

Conceptual interface:

```text
ReaderEngine
- supports(item)
- open(item)
- close()
- goTo(locator)
- next()
- previous()
- search(query)
- getProgress()
- getCurrentLocator()
```

Specialized capability interfaces may be preferable to one giant interface.

Potential implementations:

- `EpubReaderEngine`
- `PdfReaderEngine`
- `ImageSequenceReaderEngine`
- later `DocxReaderEngine`
- later `ReflowReaderEngine`

## 11. Reader/content mapping

```text
BOOK
├── EPUB → EpubReaderEngine
└── PDF  → PdfReaderEngine or future ReflowReaderEngine

COMIC
├── CBZ  → ImageSequenceReaderEngine
└── PDF  → PdfReaderEngine with comic presentation

MANGA
├── CBZ  → ImageSequenceReaderEngine + RTL defaults
└── PDF  → PdfReaderEngine + RTL-aware presentation where possible

DOCUMENT
└── PDF  → PdfReaderEngine
```

## 12. Input architecture

Screens must consume semantic commands, not raw device-specific events.

Concept:

```text
Raw Input
├── touch
├── keyboard
├── gamepad
└── stylus later
      ↓
InputMapper
      ↓
ShelfCommand / ReaderCommand
      ↓
Feature logic
```

Examples:

```text
NEXT_PAGE
PREVIOUS_PAGE
OPEN_MENU
CLOSE_MENU
NEXT_CHAPTER
PREVIOUS_CHAPTER
TOGGLE_BOOKMARK
ZOOM_IN
ZOOM_OUT
SCROLL_UP
SCROLL_DOWN
```

## 13. Theme architecture

Do not implement themes as isolated color swaps.

Conceptual theme contract:

```text
ShelfTheme
├── colors
├── typography
├── shapes
├── elevations/surfaces
├── focusStyle
├── motion
├── optional sounds
├── icon treatment
└── library presentation hints
```

Free themes:

- Classic
- Dark
- Retro Apple UI (working/internal name)
- Retro Apple UI Dark (working/internal name)
- Paper / Vintage Library

This list follows accepted ADR-0012, which supersedes the earlier Night/Aqua naming.

Future premium:

- PageStation

Themes must not compromise accessibility or basic reader clarity.

## 14. Metadata precedence

Manual user input always wins.

Suggested precedence:

```text
USER
> EMBEDDED
> EXACT_EXTERNAL_MATCH
> INFERRED_EXTERNAL_MATCH
> GENERATED
```

Metadata fields may independently have different sources.

Do not overwrite user-edited fields during refresh.

## 15. Cover precedence

Suggested:

```text
USER_SELECTED
> EMBEDDED
> ONLINE_MATCH
> FIRST_PAGE
> GENERATED
```

Generated covers should still look intentional and fit the active ShelfOS design language.

## 16. Annotation model

Text-based annotation concept:

```text
Annotation
├── id
├── libraryItemId
├── locator
├── selectedText
├── type
│   ├── HIGHLIGHT
│   ├── UNDERLINE
│   └── NOTE
├── color/style
├── body
├── createdAt
└── updatedAt
```

Bookmarks may be separate entities.

Future ink annotations should store page/content-relative geometry rather than raw screen coordinates.

## 17. Entitlement architecture

Early versions:

```text
Entitlement = FREE
```

No billing dependency required.

Later:

```text
EntitlementRepository
├── GooglePlayEntitlementRepository
└── optional verified backend implementation later
```

Features query a centralized access policy.

Avoid scattered `if (premium)` logic.

Concept:

```text
FeatureAccess.canUse(Feature.PAGESTATION_THEME)
```

## 18. Backend policy

No backend is required for the prototype.

A future backend may be justified for:

- stronger purchase verification
- optional sync
- remote metadata proxying if rate limits require it

Core reading must not depend on it.

## 19. Error model

Use explicit domain errors where useful.

Examples:

- unsupported format
- source unavailable
- permission lost
- corrupt archive
- invalid publication
- metadata lookup unavailable
- cover lookup failed

Do not collapse every failure into a generic "Something went wrong."

## 20. Testing priorities

Prioritize tests around:

- media classification
- metadata precedence
- favorite/category behavior
- import logic
- progress persistence
- input mapping
- theme registry
- reader-engine selection
- source-unavailable behavior
- Room migrations once schema stabilizes

UI tests later for high-value navigation paths.

## 21. Adaptive layout architecture

ShelfOS must not assume a fixed portrait-phone viewport.

UI decisions should respond to:

- current window dimensions
- window size class
- orientation
- fold posture where available
- hinge/occlusion geometry where relevant
- multi-window state

Compact layouts may use bottom navigation and single-pane screens.

Expanded layouts may use:

- navigation rails
- list-detail
- supporting panes
- larger adaptive grids
- two-page reader presentation

Reader state must survive fold/unfold and resizing.

Do not identify foldable behavior by manufacturer/model checks.

## 22. Cross-platform boundary

Android implementation details remain Android-native.

Future iOS should reuse product semantics and portable models, not Android UI/runtime code.

Portable concepts should include stable identifiers and serializable forms for:

- metadata
- annotations
- bookmarks
- collections
- tags
- progress

Platform-specific source URIs/URLs are adapters and must not become the identity of a LibraryItem.
## Metadata enrichment architecture

Metadata enrichment is a first-class data-layer concern.

Suggested boundary:

```text
MetadataEnrichmentService
├── EmbeddedMetadataProvider
├── IdentifierDetector
├── FilenameInferenceProvider
├── OpenLibraryProvider
├── GoogleBooksProvider
└── future media-specific providers
```

The UI must not call providers directly.

Repository/domain flow:

```text
UI
 ↓
ViewModel
 ↓
EnrichMetadata / RefreshMetadata use case
 ↓
MetadataRepository
 ↓
MetadataEnrichmentService
 ↓
Providers
```

Persist provenance so manual overrides survive refresh.

Candidate objects should include:

- resolved fields
- provider
- identifiers
- confidence
- cover candidates

Core reading remains functional when every external provider is unavailable.

See `docs/features/METADATA_ENRICHMENT.md`.

## Library presentation architecture

Library presentation should expose stable semantic state independent of theme.

Example screen state:

```text
LibraryUiState
├── activeCategory
├── continueReading
├── items
├── focusedItemId
├── selectedItemDetails
├── viewMode
├── sortMode
└── loading/error state
```

The theme renders this state but does not redefine what it means.

Expanded layouts may derive a persistent detail pane from `focusedItemId` / `selectedItemDetails`.

Compact layouts navigate to a details destination instead.

## Visual references

Visual mockups are supporting artifacts.

Agents and implementation code should follow written design specifications first, then use reference images for:

- proportion
- density
- hierarchy
- visual tone
- interaction intent

Do not reverse-engineer literal pixel values from AI-generated references.

## Phase 0 implementation

See [ADR-0016](adr/0016-phase-zero-foundation.md) for the implemented boundary.
`ShelfApplication` owns a manual `AppContainer`; UI receives ViewModels backed by
repositories. `RoomThemeRepository` persists the selected theme. Library content
is an in-memory set of original demo fixtures, not the final LibraryItem model.
Theme tokens centralize colors, typography, shapes, spacing, surfaces, focus,
motion and icons. Navigation destinations and category meanings are shared.
