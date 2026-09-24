# ShelfOS Competitive Feature Harvest

## Purpose

ShelfOS should not copy competitors blindly. This document records outstanding ideas already proven useful in existing readers and translates them into ShelfOS-compatible product opportunities.

The guiding rule is:

> Borrow proven interaction ideas, not product identity.

ShelfOS differentiates through a unified personal-library experience, local-first ownership, Books/Comics/Manga/Documents as first-class media, adaptive reading, strong themes, gamepad/keyboard support, and excellent foldable behavior.

## Reference products

Primary references:

- Moon+ Reader / Moon+ Reader Pro
- ReadEra / ReadEra Premium
- KOReader
- Readest
- Panels
- Yomu
- KyBook 3

## Moon+ Reader — feature harvest

Outstanding ideas worth considering:

### Reader customization
- deep typography controls
- font size / scale
- line spacing
- alignment
- margins
- custom fonts
- day/night themes
- multiple page-turn modes
- auto-scroll

### Hardware controls
- configurable hardware keys
- headset / Bluetooth controls
- volume-button paging
- customizable actions

### PDF tools
- PDF highlighting
- annotations
- handwriting
- dual-page landscape mode
- night themes
- smart scrolling

### Convenience
- TTS / read aloud
- home-screen book shortcuts
- bookshelf widgets
- reading statistics
- password / biometric app protection

### ShelfOS interpretation

Adopt:
- configurable keyboard/gamepad bindings
- rich text controls
- dual-page/spread modes
- TTS
- reading statistics
- widgets
- optional library privacy lock
- auto-scroll as an accessibility/convenience feature

Do not adopt:
- ad-supported free-reader behavior
- excessive settings density in the default UI

## ReadEra — feature harvest

Outstanding ideas:

### Library discovery and management
- automatic discovery of supported local files
- search by title and author
- sorting/grouping by author, series, and format
- collections
- duplicate detection
- detailed source-file information
- file-management actions

### Reading knowledge
- one global location for notes, quotes, bookmarks, and reviews
- global dictionary/history concept
- custom fonts
- multiple library layouts

### Device continuity
- optional progress/bookmark sync
- background TTS

### ShelfOS interpretation

Adopt:
- optional library-folder scanning
- duplicate detection
- author/series grouping
- global Notes hub
- multiple library layouts
- custom fonts
- TTS
- source-file diagnostics
- optional sync later

Modify:
- ShelfOS should avoid becoming a general-purpose file manager. Relink/share/open-location operations are useful; destructive file operations should remain conservative.

## KOReader — feature harvest

Outstanding ideas:

### PDF/document power
- reflow of fixed-layout/scanned documents
- strong zoom/crop controls
- extensive typesetting
- margin and line-spacing control
- external fonts
- multilingual hyphenation

### Integrations
- Calibre integration
- OPDS
- dictionaries
- Wikipedia lookup
- translation
- content-provider/plugin extensibility

### Device optimization
- e-ink optimized mode
- low-animation UI
- performance focus on older devices

### ShelfOS interpretation

Adopt:
- advanced PDF reconstruction/reflow as a major long-term feature
- crop and fit controls
- multilingual typography/hyphenation
- local/offline dictionaries
- optional Wikipedia/translation actions
- OPDS
- Calibre integration
- e-ink/low-motion theme or reader mode
- plugin/provider architecture only after core stability

## Readest — feature harvest

Outstanding ideas:

### Reading
- scroll and paginated modes
- full-text search
- highlights, bookmarks, and notes
- excerpt-based note taking
- dictionary/Wikipedia lookup
- custom/local dictionaries
- paragraph-focused reading

### Organization
- configurable bookshelves
- tags
- bulk tagging
- library-level search

### Cross-device ecosystem
- Android/iOS/desktop/web reach
- WebDAV-style sync
- backup/restore concepts
- export to external knowledge tools
- TTS
- audiobook support

### ShelfOS interpretation

Adopt:
- library-level full-text search eventually
- excerpt-to-note workflow
- tags in addition to Shelves
- bulk organization for large libraries
- offline dictionary packs
- backup/restore
- optional WebDAV sync
- TTS
- paragraph/focus reading mode

Consider later:
- audiobook support
- external knowledge-tool exports

## Panels — feature harvest

Outstanding ideas:

### Comics/manga
- dedicated comic-first UX
- CBR / CBZ / CB7 / PDF / comic EPUB
- WebP support
- polished library organization
- progress sync
- custom folder presentation

### Performance
- streaming indexing
- deferred metadata processing
- parallel prefetch/rendering
- robust fingerprinting
- optimized PDF rendering

### Ecosystem
- OPDS
- Komga/Kavita-style server connectivity
- local-network/web-server workflows

### ShelfOS interpretation

Adopt:
- comic/manga reader as a first-class engine
- CBR/CB7/WebP roadmap
- aggressive image prefetch/cache discipline
- streaming import/indexing for large libraries
- robust file fingerprinting
- OPDS
- optional self-hosted library connections later

## Yomu / KyBook-style feature harvest

Useful ideas:

- editable metadata and cover art
- folders/tags/collections
- iCloud/Files integration on iOS
- note export
- widgets
- broad DRM-free format support
- OPDS
- cloud-provider integration

### ShelfOS interpretation

Adopt:
- manual metadata/cover editing
- widgets
- portable note export
- platform-native file-provider integration
- optional cloud-provider access without making cloud mandatory

## Consolidated ShelfOS feature inventory

### Core reading
- EPUB
- PDF
- CBZ
- later CBR / CB7 / DOCX / TXT / Markdown / additional formats
- paginated and scrolling modes
- chapter navigation
- full-text search
- typography controls
- custom fonts
- margins / line spacing / alignment
- bookmarks
- highlights
- text notes
- TTS
- auto-scroll
- dual-page/spread mode
- zoom / fit width / fit page
- reading direction
- multilingual hyphenation

### Library
- Favorites
- Books
- Comics
- Manga
- Documents
- Continue Reading
- Recently Added
- Shelves
- tags
- author grouping
- series grouping
- search
- multiple visual layouts
- duplicate detection
- connected Library Sources with manual rescan; optional scheduled rescans later
- metadata/cover editing
- generated fallback covers
- reading status
- reading statistics

### Knowledge / study
- global Notes hub
- excerpt-to-note
- highlight colors
- annotation search
- export
- dictionaries
- Wikipedia lookup
- translation
- later ink/stylus annotations
- Focus/Paragraph mode

### Hardware / accessibility
- touch
- keyboard
- gamepad
- custom bindings later
- stylus later
- TTS
- reduced motion
- e-ink/low-animation presentation
- widgets
- biometric privacy lock later

### Ecosystem
- OPDS
- Calibre integration
- backup/restore
- optional WebDAV
- optional platform cloud storage
- optional self-hosted library integrations
- iOS/iPadOS
- foldable-specific UX
- plugin/provider system much later

## Prioritization rule

The existence of a feature in this document does not mean it belongs in V0.1 or V1.0.

Every feature must pass three questions:

1. Does it materially improve owned-media reading?
2. Does it fit ShelfOS rather than turn ShelfOS into another product?
3. Is the maintenance cost justified at the current project maturity?

## Research references

- Moon+ Reader Pro: Google Play
- ReadEra Premium: Google Play
- KOReader: GitHub
- Readest: GitHub
- Panels: Apple App Store
- Yomu: Apple App Store
- KyBook 3: Apple App Store

## Accepted ShelfOS organization terminology

References to competitors' collections above describe their features. ShelfOS uses
[Shelves](features/SHELVES.md), with [Series](features/SERIES.md) for intrinsic
sequences and [Library Sources](features/LIBRARY_SOURCES.md) for origin/access.
The accepted [ingestion](features/DATA_INGESTION.md) and [PDF](features/PDF_INGESTION.md)
specifications supersede generic harvest shorthand; they do not assume real-time
watching or treat advanced Adapted PDF/OCR as an early requirement.
