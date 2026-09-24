# ShelfOS Platform Strategy

## 1. Strategy

ShelfOS begins Android-first.

Android should be treated as the primary implementation and learning platform, not as a temporary prototype that must be compromised for hypothetical code reuse.

The long-term product may expand to iOS/iPadOS while preserving shared product concepts and portable library data.

## 2. Android

### Native stack

- Kotlin
- Jetpack Compose
- Room
- Readium Kotlin Toolkit where appropriate
- Storage Access Framework
- Jetpack adaptive/foldable APIs
- Google Play Billing later

### Form factors

ShelfOS Android should intentionally support:

- conventional phones
- small tablets
- large tablets
- foldable phones
- Android handhelds
- keyboard-equipped devices
- ChromeOS/resizable windows where practical

## 3. iOS / iPadOS — eventual support

iOS is a planned future platform, not part of the first implementation.

Likely native stack:

- Swift
- SwiftUI
- Readium Swift Toolkit where appropriate
- SwiftData or equivalent local persistence
- Files/document picker integration
- StoreKit for Premium
- PencilKit or relevant stylus APIs for Apple Pencil
- iCloud/Files integrations as optional services

### iOS principles

The iOS app should preserve the ShelfOS product model but feel native to Apple platforms.

Do not force the Android UI implementation onto iOS.

Share:
- concepts
- file/library schema
- metadata rules
- annotation interchange formats
- feature semantics
- reader behavior expectations

Do not require sharing:
- UI runtime
- view code
- platform navigation code
- purchase implementation

## 4. Cross-platform library compatibility

Long-term goal:

A ShelfOS library backup/export should be understandable across Android and iOS.

Candidate portable data:

```text
LibraryItem
MediaCategory
Metadata
Cover reference or asset
ReadingProgress
Bookmark
Annotation
Collection
Tag
Reading preferences
```

Platform-specific file URIs must not be treated as portable identifiers.

Use stable ShelfOS UUIDs and content fingerprints where useful.

## 5. iPhone Duo and future Apple foldables

Apple announced iPhone Duo in September 2026 as its first foldable iPhone.

ShelfOS should treat it as a high-value future form factor because reading benefits directly from a device that moves between compact-phone and book/tablet-like layouts.

Potential iPhone Duo experiences:

- compact outer-display library/resume experience
- expanded inner-display library
- two-page book spreads
- comic/manga spreads
- library + detail side-by-side
- document + notes side-by-side
- hinge-aware safe areas
- seamless reading-position continuity when folding/unfolding
- multitasking-aware layouts

Implementation must follow current Apple platform guidance at the time the iOS client is developed.

## 6. Premium portability

Premium purchase mechanisms are platform-specific.

Android:
- Google Play Billing

iOS:
- StoreKit

A future policy decision will be required on whether a Premium purchase is cross-platform.

Do not create an account system merely to solve cross-platform Premium before there is a demonstrated need.
