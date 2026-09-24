# ShelfOS Product Specification

## 1. Product definition

ShelfOS is a **personal library system** for Android.

It imports books, comics, manga, and documents the user already owns and turns them into organized library items with:

- cover art
- metadata
- reading progress
- bookmarks
- highlights
- notes
- collections
- reader preferences

ShelfOS is app-focused, not launcher-focused. It may feel like a small reading operating environment while open, but it must remain a respectful Android application.

## 2. Product promise

> Import what you own. Read it beautifully. Organize it your way.

Alternative short line:

> Your files. Your library. Your ShelfOS.

## 3. What ShelfOS is not

ShelfOS is not:

- a bookstore
- a subscription catalog
- an ebook piracy tool
- a DRM circumvention tool
- a replacement Android launcher
- a kiosk
- a social network
- a mandatory cloud service
- an ad-supported reader

## 4. Target users

### Personal readers
People with EPUB/PDF books who want a more polished reading experience.

### Comic and manga readers
People with CBZ/CBR/PDF archives who want a unified visual library.

### Students and researchers
People with papers, lecture PDFs, manuals, and study material who want reading + annotation.

### Enthusiast-device users
People using Android tablets, foldables, Chromebooks, Retroid/AYN-style handhelds, keyboard cases, Bluetooth controllers, or similar hardware.

## 5. First-class library navigation

Main categories:

1. **Favorites**
2. **Books**
3. **Comics**
4. **Manga**
5. **Documents**

Favorites is not a media type. It is a cross-category filter.

Every item has one primary category:

```text
BOOK
COMIC
MANGA
DOCUMENT
```

and an independent:

```text
isFavorite
```

## 6. Media behavior

### Books

Optimized for:

- EPUB
- text-heavy PDFs where practical
- later DOCX/TXT

Expected experience:

- chapters
- typography controls
- pagination or scrolling
- bookmarks
- highlights
- notes
- search
- resume reading

### Comics

Optimized for:

- CBZ
- later CBR
- comic PDFs

Defaults:

- left-to-right
- page view
- fit page / fit width
- optional spreads
- thumbnails

### Manga

Uses much of the same engine as Comics but has distinct user-facing identity and defaults.

Defaults:

- right-to-left
- manga-aware spreads
- page view
- thumbnails

The user may override reading direction.

### Documents

Optimized for:

- papers
- manuals
- study PDFs
- lecture material

Expected experience:

- original layout
- PDF navigation
- annotations
- notes
- bookmarks
- search
- later study mode and stylus tools

## 7. Local-first philosophy

Core features must work offline after the file is imported.

Network access may later be used for optional enrichment such as:

- cover lookup
- metadata lookup
- optional cloud sync
- optional purchase verification

Network failure must not prevent reading local content.

## 8. Ownership and source files

ShelfOS must never silently alter the source publication.

The source file remains the canonical original.

ShelfOS stores:

- metadata overrides
- cover references
- reading position
- annotations
- preferences
- classification
- collections

separately.

## 9. Import philosophy

Import should feel more like adding media to a library than opening a file.

Conceptual flow:

```text
Select file
    ↓
Detect format
    ↓
Extract embedded metadata
    ↓
Classify media
    ↓
Resolve cover
    ↓
Allow user correction
    ↓
Create LibraryItem
```

Classification must always be editable.

## 10. Themes

Themes are complete presentation personalities rather than simple palettes.

### Canonical visual rule

> **The interface is monochrome. The library is the color.**

The ShelfOS logo remains primarily monochrome. The default Classic UI uses neutral system chrome and lets book/comic/manga/document covers provide most of the color.

### Five free themes

1. **ShelfOS Classic** — monochrome, editorial, cover-first.
2. **ShelfOS Dark** — near-black/ivory sibling of Classic.
3. **Retro Apple UI** — nostalgic Aqua-era computing influence; working/internal name pending public trademark review.
4. **Retro Apple UI Dark** — dark nostalgic counterpart; working/internal name pending public trademark review.
5. **Paper / Vintage Library** — warm editorial paper/library-card atmosphere.

All five free themes must feel complete and premium.

### Premium themes

Premium themes are optional expressive experiences. Initial backlog includes at least twelve concepts:

- Frutiger Aero
- Terminal
- Archive
- PageStation
- Pocket
- Deck
- Dark Academia
- Crystal
- Vaporwave
- Swiss
- Solarpunk
- Ink

Premium themes may introduce stronger motion, custom library presentation, optional sounds, and alternate focus language, while the reader itself remains calm and accessible.

See `design/VISUAL_IDENTITY.md`, `design/CLASSIC_UI.md`, and `design/THEMES.md`.

## 11. Input philosophy

ShelfOS is touch-first but not touch-only.

Supported input concepts:

- touch
- keyboard
- gamepad
- stylus later

All should map into semantic application commands.

## 12. Monetization philosophy

ShelfOS should be open source and distributed publicly.

Planned distribution:

- GitHub
- Google Play
- possibly F-Droid-compatible distribution later

Google Play should use one app with an optional permanent Premium entitlement rather than separate Free and Pro apps.

Core reading stays free.

No ads.

Potential Premium areas:

- PageStation
- advanced themes
- advanced stylus tools
- advanced PDF reconstruction
- sophisticated reading statistics
- smart collections
- enhanced comic/manga features
- deeper personalization

Premium should answer:

> “Can ShelfOS become even more delightful and powerful?”

not:

> “Can I comfortably read my own files?”

## 13. Accounts

ShelfOS accounts are not part of the core product.

Free users: no account.

Premium users: no ShelfOS account required.

Later, Google Play Billing may provide entitlement verification for the permanent Premium purchase.

Optional sync, if ever added, must not redefine ShelfOS as a cloud-first product.

## 14. Product principles

### Local first
The library belongs on the user's device.

### Immersive, not possessive
ShelfOS should be delightful while open and effortless to leave.

### Respect ownership
Do not create artificial restrictions around user-owned files.

### Free is complete
Do not intentionally degrade the free experience.

### Personal, not sterile
ShelfOS should have identity.

### Adapt to content
Books, comics, manga, and documents should not be forced into the same reader behavior.

### Manual control wins
The user can override metadata, cover, classification, reading direction, and other inferred decisions.

## 15. Long-term possibilities

Not commitments:

- DOCX
- CBR
- OCR
- intelligent PDF reconstruction
- stylus handwriting
- universal notes
- advanced reading stats
- widgets
- optional WebDAV / Drive-style sync
- OPDS
- Calibre integration
- theme SDK
- metadata providers
- iOS / iPadOS
- Apple Pencil support

## 16. Competitive feature philosophy

ShelfOS should actively learn from mature readers rather than rediscover every useful feature.

The competitive feature inventory is maintained in `COMPETITIVE_FEATURES.md`.

High-value long-term capabilities include:

- TTS/read aloud
- custom fonts and advanced typography
- auto-scroll
- global Notes/Highlights hub
- dictionary and Wikipedia lookup
- optional translation
- duplicate detection
- author/series grouping
- tags and collections
- reading statistics
- widgets
- backup/restore
- OPDS
- Calibre integration
- optional WebDAV
- advanced PDF reflow/reconstruction
- e-ink/low-animation mode
- biometric privacy lock
- configurable hardware controls
- CBR/CB7/WebP support
- paragraph/focus reading
- robust large-library indexing

These are backlog opportunities, not all launch requirements.

## 17. Adaptive and foldable product principle

ShelfOS should be designed for changing window sizes from the beginning.

Foldables are a priority platform category because their unfolded displays are naturally suited to books, comics, manga, documents, and study layouts.

Potential differentiated experiences:

- two-page book reading
- comic/manga spreads
- document + notes panes
- chapter list + reader panes
- compact cover-screen Continue Reading
- tabletop controls
- seamless fold/unfold continuity

Use capabilities, window size, and posture rather than hard-coded device models.

See `design/FOLDABLES.md`.

## 18. Future iOS / iPadOS support

ShelfOS is Android-first but should eventually support iPhone and iPad through a native Apple-platform application.

The product model and portable library data should remain platform-neutral where reasonable.

Future Apple features may include:

- iPhone/iPad reader
- Apple Pencil annotations
- iOS widgets
- Files/iCloud integration
- iPhone Duo foldable layouts
- StoreKit Premium entitlement

Do not compromise the first Android implementation merely to share UI code.

See `PLATFORM_STRATEGY.md`.
## Metadata as part of the library experience

ShelfOS should enrich local files into recognizable publications when possible.

The goal is not merely to open `something.pdf`; it is to present:

- a useful title
- creator
- cover
- synopsis
- series/volume
- publication information
- reading state

Enrichment is optional and local-first.

If online lookup fails, ShelfOS still reads the publication using embedded or user-provided metadata.

The user can always edit metadata and cover art.

Manual edits take precedence over automatic refresh.

## Themes as layers, not alternate products

Themes share one ShelfOS information architecture.

Free and Premium themes may add visual personality, motion, optional sound, and presentation differences, but they sit on top of the same core:

- Library
- Search
- Notes
- Collections
- Settings
- Favorites
- Books
- Comics
- Manga
- Documents
- publication details
- reading engines

This keeps theme work sustainable and prevents feature fragmentation.
