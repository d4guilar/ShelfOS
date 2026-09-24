# Import Feature Specification

## 1. Goal

Import transforms a user-selected file into a durable ShelfOS `LibraryItem`.

The file is the source. The LibraryItem is the ShelfOS experience.

## 2. Initial sources

- Android document picker
- share/open-with later
- watched folders later

## 3. Initial formats

- EPUB
- PDF
- CBZ

Later:

- CBR
- CB7
- DOCX
- TXT / Markdown
- additional formats where justified

## 4. Import flow

```text
Choose file
   ↓
Persist source permission where possible
   ↓
Detect format
   ↓
Parse publication/archive
   ↓
Extract embedded metadata
   ↓
Detect identifiers
   ↓
Infer missing title/creator cautiously
   ↓
Suggest media category
   ↓
Resolve cover
   ↓
Optional online metadata enrichment
   ↓
Score candidate match
   ↓
Auto-apply high-confidence OR ask user
   ↓
Preview / correct
   ↓
Create LibraryItem
   ↓
Cache resolved metadata/cover
```

## 5. Media classification

User-facing options:

- Book
- Comic
- Manga
- Document

Automatic classification is advisory.

The user can always override it.

## 6. Metadata resolution

See:

`docs/features/METADATA_ENRICHMENT.md`

Key rule:

> Manual user metadata always wins over automatic enrichment.

## 7. Covers

Suggested precedence:

```text
USER_SELECTED
> EMBEDDED
> HIGH_CONFIDENCE_ONLINE_MATCH
> FIRST_PAGE
> GENERATED
```

A generated fallback cover should use ShelfOS design language and never look like a broken-image placeholder.

## 8. Preview

Before finalizing an ambiguous import, ShelfOS may show:

- cover
- title
- creator
- suggested category
- series / volume
- year
- metadata match confidence

High-confidence embedded/exact-identifier imports may minimize confirmation friction.

## 9. Duplicate handling

Potential signals:

- source URI
- stable content fingerprint
- file size + filename as weak signal
- ISBN/identifier as metadata signal

Do not automatically delete duplicates.

Different editions may legitimately share a title or work identifier.

## 10. Offline behavior

Import must still work without network connectivity.

Offline fallback:

- embedded metadata
- filename inference
- manual correction
- embedded/first-page/generated cover

Online enrichment can happen later through explicit refresh.

## 11. Failure states

Handle explicitly:

- unsupported format
- corrupt file
- encrypted/password-protected file where unsupported
- permission denied
- source unavailable
- archive with no readable pages
- metadata provider unavailable
- ambiguous metadata match

## 12. Source safety

Never modify the source during import.

A future export/conversion feature must produce a separate output and require explicit user action.
