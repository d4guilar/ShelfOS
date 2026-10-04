# ShelfOS Product Specification

Status: product direction. Phase 0 is the recorded acceptance baseline; Phase 1 is
unfinished work in progress. Capabilities below are not a claim of production readiness.

## 1. Product definition

ShelfOS is a **personal library system** for Android.

It imports books, comics, manga, and documents the user already owns and turns them into organized library items with:

- cover art
- metadata
- reading progress
- bookmarks
- highlights
- notes
- shelves
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

## 7. Offline-complete, online-enhanced

> **The cloud may enrich the library; it must never be required to read it.**

Every core reading function should have a local path wherever technically reasonable:
importing, reading, library browsing, metadata editing, progress, Series, Shelves,
notes, highlights, search, generated covers, backup/export and future Adapted PDF/OCR
processing. A reader in airplane mode retains the essential ShelfOS experience.

Network access may later be used for optional enrichment such as:

- cover lookup
- metadata lookup
- optional cloud sync
- optional purchase verification

Network failure must not prevent reading local content.

Online services may improve metadata and covers after a publication is locally usable.
Ordinary users should receive good default enrichment without configuring APIs or
keys; optional bring-your-own-key providers are a later power-user enhancement.

Enrichment runs after commit and in the background. It must never block import,
staging, commit or reading, and network failure must not remove usable local data.

Enrichment runs after commit and in the background. It must never block import,
staging, commit or reading, and network failure must not remove usable local data.

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
- shelves

separately.

## 9. Libraries, sources and organization

ShelfOS imports libraries, not only files. Add Files, Add Folder, Add Series and
Import Library will share a reviewable process that discovers publications,
proposes organization and commits accepted items before optional background
enrichment. Large imports are resumable and isolate bad files. Users can retain
their existing folders rather than rebuilding them around ShelfOS.

Connected **Library Sources** remember origin and access, enabling safe manual
rescans and reconnection. Missing storage preserves library knowledge; disconnect
and removal do not delete source files by default. Referencing files is the default;
an optional managed copy preserves the original. Export-based migrations respect
DRM and other apps' private storage boundaries.

**Series** groups intrinsically related books, comic issues/annuals/specials and
manga volumes. Continue Reading can resume the current member; optional **Read as
Omnibus** moves between members without physically merging files. **Shelves** are
personal organization with multiple memberships and optional Library pinning;
Smart Shelves follow later. Categories stay Books, Comics, Manga and Documents,
with Favorites as an independent cross-category view.

Compatible PDFs can offer **Adapted** typography and **Original** authored pages.
The original remains untouched. ShelfOS recommends a mode while respecting per-title
choice; complex layouts may map imperfectly between views. Original reading arrives
first, advanced Adapted reconstruction later, and OCR later still.

Original mode faithfully preserves authored layout, including typography and page
backgrounds that ShelfOS cannot restyle. Adapted mode is the future structured path
for ShelfOS typography, spacing, margins, themes, semantic search, highlights, notes,
accessibility and trustworthy reflow. Scanned publications may use local OCR inside
that Adapted pipeline; OCR is an implementation detail, not a third reader mode.

These are accepted product targets, not implemented feature claims. See
[ingestion](features/DATA_INGESTION.md), [Sources](features/LIBRARY_SOURCES.md),
[Series](features/SERIES.md), [Shelves](features/SHELVES.md),
[PDF modes](features/PDF_INGESTION.md) and [roadmap](ROADMAP.md).

## 10. Themes

Themes are complete presentation personalities rather than simple palettes.

### Canonical visual rule

> **The interface is monochrome. The library is the color.**

The ShelfOS logo remains primarily monochrome. The default Classic UI uses neutral system chrome and lets book/comic/manga/document covers provide most of the color.

### Five free themes

1. **ShelfOS Classic** — monochrome, editorial, cover-first.
2. **ShelfOS Dark** — near-black/ivory sibling of Classic.
3. **Pear Platinum** — light, tactile retro-computing/Platinum-inspired interpretation.
4. **Pear Platinum Dark** — its graphite dark sibling.
5. **Deckle** — paper/editorial/literary theme; interface neutral, collection in color.

All five free themes must feel complete and premium, and none of the five is a
Plus/Premium theme. Details: [themes](design/THEMES.md).

### Premium themes

Premium themes are optional expressive experiences delivered as **theme packs**
($4.99 each, 4–5 themes) or through **ShelfOS Plus** ($14.99 lifetime launch
direction): Retro Systems (5), Pop & Print (4) and Dream Internet (4) — 13 themes total.
They may introduce stronger motion, custom library presentation, optional effects and
alternate focus language, while the reader itself remains calm and accessible.

None of them gates core reading, and none of the five free themes is a Plus theme.
Details: [themes](design/THEMES.md) and [premium](features/PREMIUM.md).

## 11. Input philosophy

ShelfOS is touch-first but not touch-only.

Supported input concepts:

- touch
- keyboard
- gamepad
- stylus later

All should map into semantic application commands.

## 12. Monetization philosophy

ShelfOS is open source and distributed publicly (GitHub, Google Play, possibly
F-Droid-compatible distribution later), using one app with optional permanent in-app
purchases rather than separate Free and Pro applications.

> **Free users should not feel limited. Paid users should feel rewarded.**

ShelfOS Free is the complete core product: reading, organization, accessibility,
reliability and format support are never intentionally limited, and there are no ads.
Paid scope is primarily personalization — official premium themes through theme packs
($4.99) or ShelfOS Plus ($14.99 lifetime launch direction), curated accents, optional
theme effects, cosmetic extras and Labs/early access. A separate "Theme Pass" is not part
of the model, and no local cosmetic functionality is subscription-gated.

Premium should answer:

> “Can ShelfOS become even more delightful and personally mine?”

not:

> “Can I comfortably read my own files?”

See [Premium](features/PREMIUM.md) for the Plus lifetime boundary, pack → Plus upgrade
fairness, Labs and supporter tone.

## 13. Accounts

ShelfOS accounts are not part of the core product.

Free users: no account.

Premium users: no ShelfOS account required.

Later, Google Play Billing may provide entitlement verification for the permanent Plus
purchase.

Optional synchronization, if ever added, must not redefine ShelfOS as a cloud-first
product. A future bring-your-own-cloud integration (Google Drive, WebDAV, Nextcloud)
should ideally need no ShelfOS account — the user authenticates with their own provider.
An accountless managed ShelfOS Cloud vault is future research only, not a decision. See
[local-first and optional future synchronization](ARCHITECTURE.md#local-first-and-optional-future-synchronization).

## 14. Product principles

### Offline-complete, online-enhanced
The library belongs on the user's device. Online services improve it without deciding
whether it works.

### Beautiful by default
A non-technical user should get an attractive, coherent library without understanding
metadata schemas, filenames, providers or API keys. Power users may configure more.

Because ShelfOS is cover-first and its chrome is deliberately restrained, automatic cover
quality is a major Library aesthetic requirement, not optional decoration. An import flow
that succeeds but leaves confidently identifiable Books, Comics or Manga with poor generic
artwork indefinitely is not the intended experience — while still never blocking local
import or reading on network enrichment.

Because ShelfOS is cover-first and its chrome is deliberately restrained, automatic cover
quality is a major Library aesthetic requirement, not optional decoration. An import flow
that succeeds but leaves confidently identifiable Books, Comics or Manga with poor generic
artwork indefinitely is not the intended experience — while still never blocking local
import or reading on network enrichment.

### Preserve source fidelity
ShelfOS must not visibly degrade the source. Source-faithful rendering comes before
optional enhancement, and high fidelity must remain practical on modest hardware.

### Immersive, not possessive
ShelfOS should be delightful while open and effortless to leave.

### Respect ownership
Do not create artificial restrictions around user-owned files.

### Free is complete
Do not intentionally degrade the free experience.

### Supporters are rewarded, not free users limited
Paid content adds personalization and official polish; it never removes capability from the free product.

### Do not pre-build community infrastructure
Community/custom theme importing and cross-device cloud synchronization are post-launch,
demand-driven features. ShelfOS does not build community infrastructure before the
community demonstrates a need for it.

### Personal, not sterile
ShelfOS should have identity.

### Adapt to content
Books, comics, manga, and documents should not be forced into the same reader behavior.

### Manual control wins
ShelfOS proposes; the user wins. The user can override metadata, cover,
classification, reading direction, and other inferred decisions.

### The collection is the product
The reader is the means. Import, organization, presentation and durable local state
must make the user's whole collection feel intentional.

### Quiet satisfaction
Future completion experiences may acknowledge finishing a publication without game-like
pressure, mandatory sharing, streaks or intrusive rewards. The publication stays central.

## 15. Long-term possibilities

Beyond the accepted ingestion/organization direction; timing is not committed. (CBR is
an exception: it is mandatory Phase 3 scope, not an uncommitted long-term possibility —
see `docs/ROADMAP.md` Phase 3 and `docs/PHASE_3_IMPLEMENTATION_PLAN.md`.)

- DOCX
- OCR
- advanced Adapted PDF refinement (accepted direction; implementation later)
- stylus handwriting
- universal notes
- advanced reading stats
- widgets
- optional state synchronization and later bring-your-own-cloud providers (post-launch and
  demand-gated; see [architecture](ARCHITECTURE.md#local-first-and-optional-future-synchronization))
- community/custom theme import (post-launch, gated on a very active community or
  substantial repeated demand; free if it ever ships)
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
- tags and shelves
- reading statistics
- widgets
- backup/restore
- OPDS
- Calibre integration
- optional WebDAV
- Adapted PDF semantic reconstruction with Original access and SourceMap
- e-ink/low-animation mode
- biometric privacy lock
- configurable hardware controls
- CBR/CB7/WebP support
- paragraph/focus reading
- robust large-library indexing

These are backlog opportunities, not all launch requirements. Accepted Series,
Shelves, Sources and dual PDF direction are scoped by their feature specs and roadmap.

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
## 19. Community and feedback

ShelfOS should eventually have a restrained **Feedback / Community** surface (Settings /
About / Help & Feedback, or the equivalent area that fits actual navigation) offering
actions such as joining the community, requesting a feature, reporting a bug, following
development, opening the GitHub repository and supporting ShelfOS.

Initial community direction:

- **Discord first** — early testers, direct discussion, quick feedback, screenshots, theme
  discussion and voting, bug discussion, ideas and development conversation
- **GitHub Issues** — reproducible bugs, technical issues and structured implementation
  tracking
- **A subreddit later** — public discovery, release posts, searchable discussion,
  showcases and broader participation; it is not an initial requirement

Community/custom theme importing and cross-device cloud synchronization are post-launch,
demand-driven features — see [themes](design/THEMES.md) and the local-first section in
[architecture](ARCHITECTURE.md#local-first-and-optional-future-synchronization).

## 20. Interface language

ShelfOS's interface language is a core, Free product capability, and is independent from
the language of the publication being read.

Changing the ShelfOS language changes the **application UI only**. It does not translate:

- EPUB or PDF text
- comic/manga page content
- publication source content
- user-written notes
- titles, creators or imported metadata

No publication-translation feature exists or is planned. Any such capability would have
to be designed deliberately as its own feature.

### Initial locales

The initial official app locales are **implemented and shipped**:

- System default (the default behavior)
- English
- Español
- Português (Brasil) — specifically `pt-BR`, not generic Portuguese

English is the canonical/fallback language.

System default means "follow Android's system language"; it is represented as an empty
locale override rather than snapshotting the current language into a permanent ShelfOS
preference. When the user explicitly chooses English, Español or Português (Brasil),
ShelfOS respects that choice independently of the system language.

ShelfOS provides its own in-app language selector. It does **not** declare
`android:localeConfig`, so ShelfOS does not appear in Android Settings → Apps → App
language. Do not describe the system-level App Languages settings integration as
existing.

### Language setting

The language control lives in the existing Settings UI as `Settings → Language`,
offering System default / English / Español / Português (Brasil). Settings was not
redesigned for this requirement.

### What must be localizable

ShelfOS-owned UI strings are localization-ready, including navigation (Library, Search,
Notes, Shelves, Settings), category labels (Favorites, Books, Comics, Manga,
Documents), actions and buttons, dialogs, onboarding, errors, empty states,
accessibility labels and content descriptions, settings descriptions, system messages,
pluralized strings, and locale-aware formatting of dates, numbers, percentages and file
sizes where applicable. Theme-owned ShelfOS UI copy resolves through the same
localization system.

Current production UI is localization-ready across navigation, Library, Search, Notes,
Shelves, Settings, Favorites, Books, Comics, Manga, Documents, import UI, reader chrome,
Appearance, Publisher/ShelfOS presentation controls, managed-font UI, accessibility
copy, errors, empty states and progress/status copy. Publication and user data are not
translated.

### What localization never covers

Three concepts stay separate, and changing one must never silently change another:

- **App language** — the ShelfOS interface language
- **Reading Presentation** — font, size, line height, margins, palette and the
  Publisher/ShelfOS presentation choice
- **Publication language** — the language of the source content

Imported publication metadata remains source/user data. ShelfOS does not translate
titles, creator names, descriptions, Series names, Shelf names, tags or user notes.
Locale-aware *formatting* is not content translation.

Future right-to-left and additional locales are a compatibility consideration, not a
launch promise.

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

### Automatic cover and metadata enrichment

Once automatic enrichment is implemented, it should be the default experience rather
than something users must discover:

- Books, Comics and Manga should be automatically enriched when they can be identified
  with sufficient confidence, including selecting the best suitable cover automatically.
- The user should not normally need to look for a cover right after importing a
  recognizable publication.
- Comics and Manga deserve the same visual priority as Books, with cover matching
  attentive to Series, issue/volume, edition, front cover and resolution.
- Documents may keep embedded art, a first-page/thumbnail representation or a generated
  fallback, and should not be aggressively matched to unrelated books; they remain fully
  customizable by the user.
- Enrichment is provider-neutral, runs in the background after commit, and caches
  permitted results so the Library still looks correct offline.

Cover precedence, in order:

```text
USER-SELECTED COVER
>
CONFIDENT HIGH-QUALITY ONLINE COVER
>
USABLE EMBEDDED COVER
>
GENERATED / FALLBACK COVER
```

A user-selected cover can never be silently replaced. A confident online cover may improve
a weak embedded/generated one; a weak guess never replaces a good local cover.

Edit Publication should let users **Choose from device** (creating a user override that
subsequent refresh must preserve) and **Find cover online** through ShelfOS's
provider-neutral metadata layer, never through an individual provider.

Full behavior, precedence and scope rules are owned by
[Metadata enrichment](features/METADATA_ENRICHMENT.md).

## Themes as layers, not alternate products

Themes share one ShelfOS information architecture.

Free and Premium themes may add visual personality, motion, optional sound, and presentation differences, but they sit on top of the same core:

- Library
- Search
- Notes
- Shelves
- Settings
- Favorites
- Books
- Comics
- Manga
- Documents
- publication details
- reading engines

This keeps theme work sustainable and prevents feature fragmentation.
